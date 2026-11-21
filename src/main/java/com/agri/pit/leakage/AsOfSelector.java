package com.agri.pit.leakage;

import com.agri.pit.common.Db;
import com.agri.pit.common.GeoUtil;
import com.agri.pit.common.Times;
import com.agri.pit.model.InputVerdict;
import com.agri.pit.model.SourceType;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects point-in-time-correct inputs and records the evidence ledger for a run.
 * <p>
 * Two independent guards are applied here:
 * <ol>
 *   <li><b>Availability guard</b>: an observation only counts if a revision of it was
 *       {@code ingestedAt <= availabilityCeiling}. When a newer revision exists but arrived too
 *       late, the newest still-visible revision is used and the late one is explicitly excluded.</li>
 *   <li><b>Pixel attribution guard</b>: a satellite cell may fall inside several overlapping
 *       parcel boundary versions. Each cell is granted to exactly one boundary version by a
 *       deterministic priority (locked-case boundaries outrank unclaimed ones, then parcel and
 *       version id), so a pixel is never counted into two fields.</li>
 * </ol>
 */
@Component
public class AsOfSelector {

    private final Db db;
    private final GeoUtil geo;

    public AsOfSelector(Db db, GeoUtil geo) {
        this.db = db;
        this.geo = geo;
    }

    public record Window(Instant start, Instant end) {
    }

    public record SceneCellMatch(long sceneId, long cellId, String sceneKey, int revisionNo,
                                 Instant acquisitionAt, Instant ingestedAt, double lon, double lat,
                                 double ndvi) {
    }

    public record ScenePick(List<SceneCellMatch> cells, boolean anySceneInWindow) {
    }

    // ------------------------------------------------------------------ scenes

    public ScenePick selectSatellite(InputLedger.Builder ledger, Geometry geometry, Window window,
                                     Instant ceiling, long runId, Long caseId, long parcelId,
                                     long boundaryVersionId) {
        List<SceneRow> scenes = db.jdbc().query("""
                        select id, scene_key, revision_no, acquisition_at, ingested_at, cloud_cover_pct, status
                          from satellite_scene
                         where acquisition_at >= ? and acquisition_at <= ? and ingested_at <= ?
                         order by acquisition_at, revision_no
                        """,
                (rs, i) -> new SceneRow(rs.getLong("id"), rs.getString("scene_key"),
                        rs.getInt("revision_no"), Db.instant(rs, "acquisition_at"),
                        Db.instant(rs, "ingested_at"), rs.getString("status")),
                Db.timestamp(window.start()), Db.timestamp(window.end()), Db.timestamp(ceiling));

        List<SceneRow> lateScenes = db.jdbc().query("""
                        select id, scene_key, revision_no, acquisition_at, ingested_at, cloud_cover_pct, status
                          from satellite_scene
                         where acquisition_at >= ? and acquisition_at <= ? and ingested_at > ?
                         order by acquisition_at, revision_no
                        """,
                (rs, i) -> new SceneRow(rs.getLong("id"), rs.getString("scene_key"),
                        rs.getInt("revision_no"), Db.instant(rs, "acquisition_at"),
                        Db.instant(rs, "ingested_at"), rs.getString("status")),
                Db.timestamp(window.start()), Db.timestamp(window.end()), Db.timestamp(ceiling));

        for (SceneRow late : lateScenes) {
            ledger.add(record(SourceType.SATELLITE_NDVI, "satellite_scene", late.id(), late.revisionNo(),
                    late.sceneKey(), late.acquisitionAt(), late.ingestedAt(),
                    InputVerdict.EXCLUDED_LATE_AVAILABILITY,
                    "acquired " + Times.format(late.acquisitionAt()) + " but ingested "
                            + Times.format(late.ingestedAt()) + ", after the availability ceiling "
                            + Times.format(ceiling) + "; it must not influence this scoring run"));
            ledger.violation(new InputLedger.Violation("LATE_SCENE_EXCLUDED", "WARNING",
                    SourceType.SATELLITE_NDVI, late.id(), null,
                    "scene " + late.sceneKey() + " (acquired before the cutoff) arrived after the ceiling and "
                            + "was excluded from scoring"));
        }

        List<SceneCellMatch> matches = new ArrayList<>();
        for (SceneRow scene : scenes) {
            List<CellRow> cells = db.jdbc().query("""
                            select id, cell_key, lon, lat, ndvi from satellite_scene_cell
                             where scene_id = ? order by cell_key
                            """,
                    (rs, i) -> new CellRow(rs.getLong("id"), rs.getString("cell_key"),
                            rs.getDouble("lon"), rs.getDouble("lat"),
                            rs.getBigDecimal("ndvi").doubleValue()),
                    scene.id());
            boolean anyInParcel = false;
            int allocatedElsewhere = 0;
            for (CellRow cell : cells) {
                if (!geo.contains(geometry, cell.lon(), cell.lat())) {
                    continue;
                }
                anyInParcel = true;
                PixelOwner owner = resolveOwner(scene.id(), cell.id(), cell.lon(), cell.lat(),
                        cell.ndvi(), ceiling, runId, caseId, parcelId, boundaryVersionId);
                if (!owner.ownedByCurrentRun()) {
                    allocatedElsewhere++;
                    ledger.add(record(SourceType.SATELLITE_NDVI, "satellite_scene_cell", cell.id(),
                            scene.revisionNo(), scene.sceneKey() + "/" + cell.key(),
                            scene.acquisitionAt(), scene.ingestedAt(),
                            InputVerdict.EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL,
                            "cell is granted to parcel " + owner.parcelId() + " boundary v"
                                    + owner.boundaryVersionNo() + " to avoid double counting the same pixel"));
                    continue;
                }
                matches.add(new SceneCellMatch(scene.id(), cell.id(), scene.sceneKey(), scene.revisionNo(),
                        scene.acquisitionAt(), scene.ingestedAt(), cell.lon(), cell.lat(), cell.ndvi()));
            }
            if (anyInParcel && allocatedElsewhere > 0) {
                ledger.violation(new InputLedger.Violation("PIXEL_SHARED_BETWEEN_FIELDS", "WARNING",
                        SourceType.SATELLITE_NDVI, scene.id(), null,
                        allocatedElsewhere + " cell(s) of scene " + scene.sceneKey()
                                + " overlap another parcel/version and were excluded to keep fields isolated"));
            }
            if (cells.isEmpty()) {
                ledger.violation(new InputLedger.Violation("SCENE_WITHOUT_CELLS", "INFO",
                        SourceType.SATELLITE_NDVI, scene.id(), null,
                        "scene " + scene.sceneKey() + " has no stored cells"));
            }
        }

        // One ledger entry per scene, with the weighted mean of its usable cells.
        Map<Long, List<SceneCellMatch>> byScene = new LinkedHashMap<>();
        for (SceneCellMatch match : matches) {
            byScene.computeIfAbsent(match.sceneId(), k -> new ArrayList<>()).add(match);
        }
        for (List<SceneCellMatch> group : byScene.values()) {
            SceneCellMatch first = group.get(0);
            double mean = group.stream().mapToDouble(SceneCellMatch::ndvi).average().orElse(Double.NaN);
            var candidate = record(SourceType.SATELLITE_NDVI, "satellite_scene", first.sceneId(),
                    first.revisionNo(), first.sceneKey(), first.acquisitionAt(), first.ingestedAt(),
                    InputVerdict.ELIGIBLE,
                    group.size() + " usable cell(s) inside the locked boundary version");
            candidate.detail().put("cells", group.size());
            candidate.detail().put("meanNdvi", mean);
            ledger.add(candidate.withContribution(mean));
        }
        if (scenes.isEmpty()) {
            ledger.violation(new InputLedger.Violation("NO_ELIGIBLE_SCENE", "WARNING",
                    SourceType.SATELLITE_NDVI, null, null,
                    "no scene was both acquired inside the window and available at the ceiling"));
        }
        return new ScenePick(matches, !scenes.isEmpty());
    }

    // ------------------------------------------------------------ pixel owners

    public record PixelOwner(boolean ownedByCurrentRun, long parcelId, int boundaryVersionNo) {
    }

    private record BoundaryCandidate(long boundaryVersionId, long parcelId, int versionNo, Long lockedCaseId) {
    }

    /**
     * Resolve the single owner of a raster cell among boundary versions that contain it.
     * Priority: a boundary locked by an existing claim case wins (earliest case id first),
     * otherwise the lowest parcel id, then the lowest boundary version id. The decision depends
     * only on stored data, never on which run happens to execute first, so replays reproduce it.
     */
    private PixelOwner resolveOwner(long sceneId, long cellId, double lon, double lat, double ndvi,
                                    Instant ceiling, long runId, Long caseId, long parcelId,
                                    long boundaryVersionId) {
        List<BoundaryCandidate> candidates = new ArrayList<>();
        db.jdbc().query("""
                        select b.id, b.parcel_id, b.version_no, b.geometry_geojson,
                               (select min(c.id) from claim_case c where c.boundary_version_id = b.id) as case_id
                          from parcel_boundary_version b
                         where b.ingested_at <= ?
                            or exists (select 1 from claim_case c where c.boundary_version_id = b.id)
                         order by b.id
                        """, rs -> {
            Geometry geometry = geo.parse(db.readTree(rs.getString("geometry_geojson")));
            if (geo.contains(geometry, lon, lat)) {
                candidates.add(new BoundaryCandidate(rs.getLong("id"), rs.getLong("parcel_id"),
                        rs.getInt("version_no"), (Long) rs.getObject("case_id")));
            }
        }, Db.timestamp(ceiling));
        BoundaryCandidate owner = null;
        for (BoundaryCandidate candidate : candidates) {
            if (owner == null || better(candidate, owner)) {
                owner = candidate;
            }
        }
        if (owner == null) {
            // No boundary registry entry; the run's own locked version is the only owner.
            return new PixelOwner(true, parcelId, 0);
        }
        boolean current = owner.boundaryVersionId() == boundaryVersionId;
        upsertPixelClaim(sceneId, cellId, current ? runId : null, caseId, owner.parcelId(),
                owner.boundaryVersionId(), ndvi);
        return new PixelOwner(current, owner.parcelId(), owner.versionNo());
    }

    private static boolean better(BoundaryCandidate candidate, BoundaryCandidate current) {
        boolean candLocked = candidate.lockedCaseId() != null;
        boolean currLocked = current.lockedCaseId() != null;
        if (candLocked != currLocked) {
            return candLocked;
        }
        if (candLocked) {
            return candidate.lockedCaseId() < current.lockedCaseId();
        }
        if (candidate.parcelId() != current.parcelId()) {
            return candidate.parcelId() < current.parcelId();
        }
        return candidate.boundaryVersionId() < current.boundaryVersionId();
    }

    private void upsertPixelClaim(long sceneId, long cellId, Long runId, Long caseId, long parcelId,
                                  long boundaryVersionId, double ndvi) {
        db.jdbc().update("""
                        insert into pixel_claim (scene_id, cell_id, run_id, case_id, parcel_id,
                                                 boundary_version_id, ndvi)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict (scene_id, cell_id)
                        do update set run_id = coalesce(excluded.run_id, pixel_claim.run_id),
                                      ndvi = excluded.ndvi
                        """, sceneId, cellId, runId, caseId, parcelId, boundaryVersionId, ndvi);
    }

    // ----------------------------------------------------------------- weather

    public record WeatherValue(String stationCode, String externalKey, long observationId, int revisionNo,
                               double rainfallMm, double weight, Instant observedAt, Instant ingestedAt) {
    }

    public record WeatherPick(List<WeatherValue> values) {
        public boolean missing() {
            return values.isEmpty();
        }
    }

    public WeatherPick selectWeather(InputLedger.Builder ledger, long parcelId, Window window, Instant ceiling) {
        List<StationLink> links = db.jdbc().query("""
                        select s.id, s.station_code, l.weight
                          from parcel_weather_link l join weather_station s on s.id = l.station_id
                         where l.parcel_id = ? order by s.station_code
                        """,
                (rs, i) -> new StationLink(rs.getLong("id"), rs.getString("station_code"),
                        rs.getBigDecimal("weight").doubleValue()),
                parcelId);
        List<WeatherValue> values = new ArrayList<>();
        for (StationLink link : links) {
            List<WeatherRow> all = db.jdbc().query("""
                            select id, external_key, period_start, period_end, rainfall_mm, observed_at,
                                   ingested_at, revision_no
                              from weather_observation
                             where station_id = ? and period_end >= ? and period_start <= ?
                             order by external_key, revision_no desc
                            """,
                    (rs, i) -> new WeatherRow(rs.getLong("id"), rs.getString("external_key"),
                            Db.instant(rs, "period_start"), Db.instant(rs, "period_end"),
                            rs.getBigDecimal("rainfall_mm").doubleValue(),
                            Db.instant(rs, "observed_at"), Db.instant(rs, "ingested_at"),
                            rs.getInt("revision_no")),
                    link.stationId(), Db.timestamp(window.start()), Db.timestamp(window.end()));

            Map<String, WeatherRow> newestVisible = new LinkedHashMap<>();
            Map<String, WeatherRow> newestAny = new LinkedHashMap<>();
            for (WeatherRow row : all) {
                newestAny.putIfAbsent(row.externalKey(), row);
                if (!row.ingestedAt().isAfter(ceiling)) {
                    newestVisible.putIfAbsent(row.externalKey(), row);
                }
            }
            for (Map.Entry<String, WeatherRow> entry : newestVisible.entrySet()) {
                WeatherRow chosen = entry.getValue();
                WeatherRow latest = newestAny.get(entry.getKey());
                boolean newerExists = latest != null && latest.revisionNo() > chosen.revisionNo();
                InputVerdict verdict = newerExists
                        ? InputVerdict.ELIGIBLE_OLDER_REVISION_USED : InputVerdict.ELIGIBLE;
                String reason = newerExists
                        ? "revision " + latest.revisionNo() + " was ingested "
                        + Times.format(latest.ingestedAt()) + ", after the ceiling "
                        + Times.format(ceiling) + "; the newest revision visible at the cutoff was used"
                        : "newest revision available at the cutoff";
                var candidate = record(SourceType.WEATHER_OBSERVATION, "weather_observation",
                        chosen.id(), chosen.revisionNo(), chosen.externalKey() + "@" + link.stationCode(),
                        chosen.observedAt(), chosen.ingestedAt(), verdict, reason);
                candidate.detail().put("stationCode", link.stationCode());
                candidate.detail().put("rainfallMm", chosen.rainfallMm());
                ledger.add(candidate.withContribution(chosen.rainfallMm()));
                if (newerExists) {
                    ledger.violation(new InputLedger.Violation("WEATHER_REVISION_NOT_YET_VISIBLE", "INFO",
                            SourceType.WEATHER_OBSERVATION, chosen.id(), null,
                            "station " + link.stationCode() + ": a later revision was ingested after the cutoff; "
                                    + "the older visible revision was used"));
                }
                values.add(new WeatherValue(link.stationCode(), chosen.externalKey(), chosen.id(),
                        chosen.revisionNo(), chosen.rainfallMm(), link.weight(),
                        chosen.observedAt(), chosen.ingestedAt()));
            }
            for (WeatherRow row : all) {
                if (row.ingestedAt().isAfter(ceiling)
                        && newestAny.get(row.externalKey()) == row) {
                    ledger.add(record(SourceType.WEATHER_OBSERVATION, "weather_observation", row.id(),
                            row.revisionNo(), row.externalKey() + "@" + link.stationCode(),
                            row.observedAt(), row.ingestedAt(),
                            InputVerdict.EXCLUDED_LATE_AVAILABILITY,
                            "revision ingested after the availability ceiling; excluded from scoring"));
                }
            }
        }
        if (links.isEmpty()) {
            ledger.violation(new InputLedger.Violation("NO_WEATHER_STATION", "WARNING",
                    SourceType.WEATHER_OBSERVATION, null, null,
                    "no weather station is linked to the parcel"));
        }
        return new WeatherPick(values);
    }

    // ------------------------------------------------------------- declarations

    public record DeclarationValue(long id, int revisionNo, String declarationNo, String cropCode,
                                   LocalDate plantingDate, double declaredAreaHa,
                                   Instant observedAt, Instant ingestedAt) {
    }

    public record DeclarationPick(DeclarationValue value) {
        public boolean missing() {
            return value == null;
        }
    }

    public DeclarationPick selectDeclaration(InputLedger.Builder ledger, long parcelId, Instant ceiling,
                                             String caseCrop) {
        List<DeclarationRow> all = db.jdbc().query("""
                        select id, declaration_no, crop_code, planting_date, declared_area_ha,
                               observed_at, ingested_at, revision_no
                          from planting_declaration where parcel_id = ?
                         order by revision_no desc
                        """,
                (rs, i) -> new DeclarationRow(rs.getLong("id"), rs.getString("declaration_no"),
                        rs.getString("crop_code"), rs.getObject("planting_date", LocalDate.class),
                        rs.getBigDecimal("declared_area_ha").doubleValue(),
                        Db.instant(rs, "observed_at"), Db.instant(rs, "ingested_at"),
                        rs.getInt("revision_no")),
                parcelId);
        DeclarationRow chosen = null;
        for (DeclarationRow row : all) {
            if (!row.ingestedAt().isAfter(ceiling)) {
                chosen = row;
                break;
            }
        }
        if (chosen == null) {
            ledger.violation(new InputLedger.Violation("NO_DECLARATION_VISIBLE", "WARNING",
                    SourceType.PLANTING_DECLARATION, null, null,
                    "no planting declaration was ingested by the availability ceiling"));
            return new DeclarationPick(null);
        }
        DeclarationRow latest = all.isEmpty() ? null : all.get(0);
        boolean newerExists = latest != null && latest.revisionNo() > chosen.revisionNo();
        InputVerdict verdict = newerExists
                ? InputVerdict.ELIGIBLE_OLDER_REVISION_USED : InputVerdict.ELIGIBLE;
        String reason = newerExists
                ? "revision " + latest.revisionNo() + " was ingested " + Times.format(latest.ingestedAt())
                + ", after the ceiling; the newest visible revision was used"
                : "newest declaration revision available at the cutoff";
        var candidate = record(SourceType.PLANTING_DECLARATION, "planting_declaration", chosen.id(),
                chosen.revisionNo(), chosen.declarationNo(), chosen.observedAt(), chosen.ingestedAt(),
                verdict, reason);
        candidate.detail().put("cropCode", chosen.cropCode());
        candidate.detail().put("plantingDate", String.valueOf(chosen.plantingDate()));
        ledger.add(candidate);
        if (newerExists) {
            ledger.add(record(SourceType.PLANTING_DECLARATION, "planting_declaration", latest.id(),
                    latest.revisionNo(), chosen.declarationNo(), latest.observedAt(), latest.ingestedAt(),
                    InputVerdict.EXCLUDED_LATE_AVAILABILITY,
                    "declaration revision ingested after the availability ceiling; excluded"));
        }
        if (caseCrop != null && chosen.cropCode() != null && !caseCrop.equalsIgnoreCase(chosen.cropCode())) {
            ledger.violation(new InputLedger.Violation("CROP_MISMATCH", "WARNING",
                    SourceType.PLANTING_DECLARATION, chosen.id(), null,
                    "declared crop " + chosen.cropCode() + " differs from case crop " + caseCrop));
        }
        return new DeclarationPick(new DeclarationValue(chosen.id(), chosen.revisionNo(),
                chosen.declarationNo(), chosen.cropCode(), chosen.plantingDate(),
                chosen.declaredAreaHa(), chosen.observedAt(), chosen.ingestedAt()));
    }

    // ---------------------------------------------------------------- boundary

    public void recordBoundary(InputLedger.Builder ledger, long boundaryVersionId, int versionNo,
                               Instant observedAt, Instant ingestedAt, Instant ceiling) {
        boolean late = ingestedAt.isAfter(ceiling);
        ledger.add(record(SourceType.PARCEL_BOUNDARY, "parcel_boundary_version", boundaryVersionId,
                versionNo, "boundary-v" + versionNo, observedAt, ingestedAt,
                late ? InputVerdict.EXCLUDED_LATE_AVAILABILITY : InputVerdict.ELIGIBLE,
                late ? "locked boundary version was ingested after the availability ceiling"
                        : "locked boundary version for this case; geometry frozen for the run"));
        if (late) {
            ledger.violation(new InputLedger.Violation("BOUNDARY_LATE_AVAILABILITY", "BLOCKING",
                    SourceType.PARCEL_BOUNDARY, boundaryVersionId, null,
                    "the case locks boundary v" + versionNo + " which was not ingested until "
                            + Times.format(ingestedAt) + ", after the ceiling " + Times.format(ceiling)));
        }
    }

    // ----------------------------------------------------------------- helpers

    private InputLedger.Candidate record(SourceType type, String table, long id, Integer revisionNo,
                                          String key, Instant observedAt, Instant ingestedAt,
                                          InputVerdict verdict, String reason) {
        return new InputLedger.Candidate(type, table, id, revisionNo, key, observedAt, ingestedAt,
                verdict, reason, null, db.mapper().createObjectNode());
    }

    private record CellRow(long id, String key, double lon, double lat, double ndvi) {
    }

    private record SceneRow(long id, String sceneKey, int revisionNo, Instant acquisitionAt,
                            Instant ingestedAt, String status) {
    }

    private record StationLink(long stationId, String stationCode, double weight) {
    }

    private record WeatherRow(long id, String externalKey, Instant periodStart, Instant periodEnd,
                              double rainfallMm, Instant observedAt, Instant ingestedAt, int revisionNo) {
    }

    private record DeclarationRow(long id, String declarationNo, String cropCode, LocalDate plantingDate,
                                  double declaredAreaHa, Instant observedAt, Instant ingestedAt, int revisionNo) {
    }

    public Geometry parseGeometry(com.fasterxml.jackson.databind.JsonNode node) {
        return geo.parse(node);
    }
}
