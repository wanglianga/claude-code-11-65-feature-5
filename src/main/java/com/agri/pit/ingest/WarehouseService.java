package com.agri.pit.ingest;

import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.GeoUtil;
import com.agri.pit.model.SourceType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Point-in-time warehouse writer.
 * <p>
 * Every call appends a record; a correction is modelled as a new revision with its own
 * {@code ingestedAt} and a link back to the record it supersedes. Nothing is overwritten,
 * so as-of queries can reconstruct exactly what was visible at any past cutoff.
 */
@Service
public class WarehouseService {

    private final Db db;
    private final GeoUtil geo;
    private final RevisionRecorder revisions;

    public WarehouseService(Db db, GeoUtil geo, RevisionRecorder revisions) {
        this.db = db;
        this.geo = geo;
        this.revisions = revisions;
    }

    // ------------------------------------------------------------------ parcels

    @Transactional
    public long createParcel(IngestDtos.ParcelRequest req) {
        if (req.parcelCode() == null || req.parcelCode().isBlank()) {
            throw ApiException.badRequest("PARCEL_CODE_REQUIRED", "parcelCode is required");
        }
        String code = req.parcelCode().trim().toUpperCase();
        Integer exists = db.jdbc().queryForObject(
                "select count(*) from parcel where parcel_code = ?", Integer.class, code);
        if (exists != null && exists > 0) {
            throw ApiException.conflict("PARCEL_EXISTS", "parcel already exists: " + code);
        }
        return db.insert("insert into parcel (parcel_code, name, region, crop_code) values (?, ?, ?, ?)",
                code, req.name() == null ? code : req.name(), req.region(), req.cropCode());
    }

    @Transactional
    public long addBoundary(long parcelId, IngestDtos.BoundaryRequest req) {
        requireParcel(parcelId);
        if (req.observedAt() == null || req.ingestedAt() == null) {
            throw ApiException.badRequest("BOUNDARY_CLOCKS_REQUIRED",
                    "observedAt and ingestedAt are required for boundary versions");
        }
        Geometry geometry = geo.parse(req.geometry());
        if (!geometry.isValid()) {
            geometry = geometry.buffer(0);
        }
        double area = Math.round(geo.areaHectares(geometry) * 10000d) / 10000d;
        int versionNo = req.versionNo() != null ? req.versionNo()
                : db.jdbc().queryForObject(
                "select coalesce(max(version_no), 0) + 1 from parcel_boundary_version where parcel_id = ?",
                Integer.class, parcelId);
        Integer dup = db.jdbc().queryForObject(
                "select count(*) from parcel_boundary_version where parcel_id = ? and version_no = ?",
                Integer.class, parcelId, versionNo);
        if (dup != null && dup > 0) {
            throw ApiException.conflict("BOUNDARY_VERSION_EXISTS",
                    "boundary version " + versionNo + " already exists for parcel " + parcelId);
        }
        Long revisionOf = req.revisionOfVersionId();
        if (revisionOf == null) {
            List<Long> latest = db.jdbc().query(
                    "select id from parcel_boundary_version where parcel_id = ? order by version_no desc limit 1",
                    (rs, i) -> rs.getLong(1), parcelId);
            revisionOf = latest.isEmpty() ? null : latest.get(0);
        }
        long id = db.insert("""
                        insert into parcel_boundary_version
                            (parcel_id, version_no, geometry_geojson, area_ha, valid_from, valid_to,
                             observed_at, ingested_at, revision_of_id, survey_note)
                        values (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                        """,
                parcelId, versionNo, db.jsonArg(req.geometry()), area,
                req.validFrom(), req.validTo(),
                Db.timestamp(req.observedAt()), Db.timestamp(req.ingestedAt()),
                revisionOf, req.surveyNote());
        revisions.record(SourceType.PARCEL_BOUNDARY, "parcel_boundary_version", id,
                parcelId, null, versionNo, revisionOf, req.observedAt(), req.ingestedAt(),
                req.observedAt(), req.observedAt(),
                "boundary v" + versionNo + " for parcel " + parcelId
                        + (req.surveyNote() == null ? "" : " (" + req.surveyNote() + ")"));
        return id;
    }

    public List<BoundaryRow> boundaryVersions(long parcelId) {
        return db.jdbc().query("""
                        select id, parcel_id, version_no, geometry_geojson, area_ha, valid_from, valid_to,
                               observed_at, ingested_at, revision_of_id, survey_note
                          from parcel_boundary_version
                         where parcel_id = ?
                         order by version_no
                        """,
                (rs, i) -> new BoundaryRow(
                        rs.getLong("id"), rs.getLong("parcel_id"), rs.getInt("version_no"),
                        db.readTree(rs.getString("geometry_geojson")),
                        rs.getBigDecimal("area_ha").doubleValue(),
                        rs.getObject("valid_from", java.time.LocalDate.class),
                        rs.getObject("valid_to", java.time.LocalDate.class),
                        Db.instant(rs, "observed_at"), Db.instant(rs, "ingested_at"),
                        (Long) rs.getObject("revision_of_id"), rs.getString("survey_note")),
                parcelId);
    }

    // ----------------------------------------------------------------- stations

    @Transactional
    public long createStation(IngestDtos.StationRequest req) {
        if (req.stationCode() == null || req.stationCode().isBlank()) {
            throw ApiException.badRequest("STATION_CODE_REQUIRED", "stationCode is required");
        }
        String code = req.stationCode().trim().toUpperCase();
        Integer exists = db.jdbc().queryForObject(
                "select count(*) from weather_station where station_code = ?", Integer.class, code);
        if (exists != null && exists > 0) {
            throw ApiException.conflict("STATION_EXISTS", "station already exists: " + code);
        }
        return db.insert("insert into weather_station (station_code, name, lon, lat) values (?, ?, ?, ?)",
                code, req.name(), req.lon(), req.lat());
    }

    @Transactional
    public void linkStation(long parcelId, long stationId, Double weight) {
        requireParcel(parcelId);
        db.jdbc().update("""
                        insert into parcel_weather_link (parcel_id, station_id, weight) values (?, ?, ?)
                        on conflict (parcel_id, station_id) do update set weight = excluded.weight
                        """, parcelId, stationId, weight == null ? 1.0d : weight);
    }

    // ------------------------------------------------------------------- scenes

    @Transactional
    public long ingestScene(IngestDtos.SceneRequest req) {
        if (req.sceneKey() == null || req.sceneKey().isBlank()) {
            throw ApiException.badRequest("SCENE_KEY_REQUIRED", "sceneKey is required");
        }
        if (req.acquisitionAt() == null || req.ingestedAt() == null) {
            throw ApiException.badRequest("SCENE_CLOCKS_REQUIRED",
                    "acquisitionAt and ingestedAt are required; availability must never be inferred from acquisition alone");
        }
        if (req.ingestedAt().isBefore(req.acquisitionAt())) {
            throw ApiException.badRequest("SCENE_INGEST_BEFORE_ACQUISITION",
                    "ingestedAt cannot precede acquisitionAt");
        }
        if (req.footprint() == null) {
            throw ApiException.badRequest("SCENE_FOOTPRINT_REQUIRED", "footprint GeoJSON is required");
        }
        geo.parse(req.footprint());
        int revisionNo = req.revisionNo() != null ? req.revisionNo()
                : nextSceneRevision(req.sceneKey());
        if (revisionNo < 1) {
            throw ApiException.badRequest("INVALID_REVISION", "revisionNo must be >= 1");
        }
        Integer dup = db.jdbc().queryForObject(
                "select count(*) from satellite_scene where scene_key = ? and revision_no = ?",
                Integer.class, req.sceneKey(), revisionNo);
        if (dup != null && dup > 0) {
            throw ApiException.conflict("SCENE_REVISION_EXISTS",
                    "scene " + req.sceneKey() + " revision " + revisionNo + " already exists");
        }
        Set<String> cellKeys = new HashSet<>();
        for (IngestDtos.SceneCellRequest cell : req.cells() == null ? List.<IngestDtos.SceneCellRequest>of() : req.cells()) {
            if (cell.lon() < -180 || cell.lon() > 180 || cell.lat() < -90 || cell.lat() > 90) {
                throw ApiException.badRequest("CELL_COORD_OUT_OF_RANGE",
                        "cell coordinate out of range: " + cell.lon() + "," + cell.lat());
            }
            if (!(cell.ndvi() >= -1d && cell.ndvi() <= 1d)) {
                throw ApiException.badRequest("NDVI_OUT_OF_RANGE", "ndvi must be within [-1, 1]");
            }
            String key = cell.cellKey() == null || cell.cellKey().isBlank()
                    ? "c_" + cell.lon() + "_" + cell.lat() : cell.cellKey();
            if (!cellKeys.add(key)) {
                throw ApiException.badRequest("DUPLICATE_CELL", "duplicate cell key in scene: " + key);
            }
        }
        long sceneId = db.insert("""
                        insert into satellite_scene
                            (scene_key, revision_no, acquisition_at, ingested_at, cloud_cover_pct,
                             footprint_geojson, source, revision_of_id)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        """,
                req.sceneKey(), revisionNo, Db.timestamp(req.acquisitionAt()), Db.timestamp(req.ingestedAt()),
                req.cloudCoverPct(), db.jsonArg(req.footprint()), req.source(), req.revisionOfSceneId());
        if (req.cells() != null) {
            for (IngestDtos.SceneCellRequest cell : req.cells()) {
                String key = cell.cellKey() == null || cell.cellKey().isBlank()
                        ? "c_" + cell.lon() + "_" + cell.lat() : cell.cellKey();
                db.jdbc().update("""
                                insert into satellite_scene_cell (scene_id, cell_key, lon, lat, ndvi, area_ha)
                                values (?, ?, ?, ?, ?, ?)
                                """,
                        sceneId, key, cell.lon(), cell.lat(), cell.ndvi(),
                        cell.areaHa() == null ? 0.09d : cell.areaHa());
            }
        }
        if (req.revisionOfSceneId() != null) {
            db.jdbc().update("""
                            update satellite_scene set status = 'SUPERSEDED', superseded_by_id = ?
                             where id = ? and status = 'ACTIVE'
                            """, sceneId, req.revisionOfSceneId());
        }
        revisions.record(SourceType.SATELLITE_NDVI, "satellite_scene", sceneId, null,
                req.sceneKey(), revisionNo, req.revisionOfSceneId(), req.acquisitionAt(),
                req.ingestedAt(), req.acquisitionAt(), req.acquisitionAt(),
                "scene " + req.sceneKey() + " rev " + revisionNo);
        return sceneId;
    }

    private int nextSceneRevision(String sceneKey) {
        Integer value = db.jdbc().queryForObject(
                "select coalesce(max(revision_no), 0) + 1 from satellite_scene where scene_key = ?",
                Integer.class, sceneKey);
        return value == null ? 1 : value;
    }

    // ------------------------------------------------------------------ weather

    @Transactional
    public long ingestWeather(IngestDtos.WeatherRequest req) {
        if (req.stationCode() == null || req.periodStart() == null || req.periodEnd() == null) {
            throw ApiException.badRequest("WEATHER_FIELDS_REQUIRED",
                    "stationCode, periodStart and periodEnd are required");
        }
        if (!req.periodEnd().isAfter(req.periodStart())) {
            throw ApiException.badRequest("INVALID_PERIOD", "periodEnd must be after periodStart");
        }
        if (req.observedAt() == null || req.ingestedAt() == null) {
            throw ApiException.badRequest("WEATHER_CLOCKS_REQUIRED",
                    "observedAt and ingestedAt are required; revisions keep both clocks");
        }
        long stationId = stationId(req.stationCode());
        String externalKey = req.externalKey() == null || req.externalKey().isBlank()
                ? req.stationCode() + "|" + req.periodStart() : req.externalKey();
        int revisionNo = req.revisionNo() != null ? req.revisionNo() : nextWeatherRevision(stationId, externalKey);
        Integer dup = db.jdbc().queryForObject("""
                        select count(*) from weather_observation
                         where station_id = ? and external_key = ? and revision_no = ?
                        """, Integer.class, stationId, externalKey, revisionNo);
        if (dup != null && dup > 0) {
            throw ApiException.conflict("WEATHER_REVISION_EXISTS",
                    "weather observation " + externalKey + " revision " + revisionNo + " already exists");
        }
        Long revisionOf = req.revisionOfId();
        if (revisionOf == null) {
            List<Long> latest = db.jdbc().query("""
                            select id from weather_observation
                             where station_id = ? and external_key = ?
                             order by revision_no desc limit 1
                            """, (rs, i) -> rs.getLong(1), stationId, externalKey);
            revisionOf = latest.isEmpty() ? null : latest.get(0);
        }
        long id = db.insert("""
                        insert into weather_observation
                            (station_id, external_key, period_start, period_end, rainfall_mm, temp_mean_c,
                             observed_at, ingested_at, revision_no, revision_of_id, source)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                stationId, externalKey, Db.timestamp(req.periodStart()), Db.timestamp(req.periodEnd()),
                req.rainfallMm(), req.tempMeanC(), Db.timestamp(req.observedAt()),
                Db.timestamp(req.ingestedAt()), revisionNo, revisionOf, req.source());
        if (revisionOf != null) {
            db.jdbc().update("""
                            update weather_observation set status = 'SUPERSEDED', superseded_by_id = ?
                             where id = ? and status = 'ACTIVE'
                            """, id, revisionOf);
        }
        Long parcelId = db.jdbc().query("""
                        select parcel_id from parcel_weather_link where station_id = ?
                         order by parcel_id limit 1
                        """, rs -> rs.next() ? rs.getLong(1) : null, stationId);
        revisions.record(SourceType.WEATHER_OBSERVATION, "weather_observation", id, parcelId,
                externalKey, revisionNo, revisionOf, req.observedAt(), req.ingestedAt(),
                req.periodStart(), req.periodEnd(),
                "weather " + externalKey + " rev " + revisionNo + " rainfall=" + req.rainfallMm());
        return id;
    }

    private int nextWeatherRevision(long stationId, String externalKey) {
        Integer value = db.jdbc().queryForObject("""
                        select coalesce(max(revision_no), 0) + 1 from weather_observation
                         where station_id = ? and external_key = ?
                        """, Integer.class, stationId, externalKey);
        return value == null ? 1 : value;
    }

    // -------------------------------------------------------------- declarations

    @Transactional
    public long ingestDeclaration(IngestDtos.DeclarationRequest req) {
        requireParcel(req.parcelId());
        if (req.declarationNo() == null || req.plantingDate() == null) {
            throw ApiException.badRequest("DECLARATION_FIELDS_REQUIRED",
                    "declarationNo and plantingDate are required");
        }
        if (req.observedAt() == null || req.ingestedAt() == null) {
            throw ApiException.badRequest("DECLARATION_CLOCKS_REQUIRED",
                    "observedAt and ingestedAt are required");
        }
        String cropCode = req.cropCode();
        if (cropCode == null || cropCode.isBlank()) {
            cropCode = db.jdbc().query("select crop_code from parcel where id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, req.parcelId());
        }
        if (cropCode == null || cropCode.isBlank()) {
            throw ApiException.badRequest("CROP_REQUIRED", "cropCode is required on the declaration or parcel");
        }
        int revisionNo = req.revisionNo() != null ? req.revisionNo()
                : nextDeclarationRevision(req.declarationNo());
        Integer dup = db.jdbc().queryForObject("""
                        select count(*) from planting_declaration where declaration_no = ? and revision_no = ?
                        """, Integer.class, req.declarationNo(), revisionNo);
        if (dup != null && dup > 0) {
            throw ApiException.conflict("DECLARATION_REVISION_EXISTS",
                    "declaration " + req.declarationNo() + " revision " + revisionNo + " already exists");
        }
        Long revisionOf = req.revisionOfId();
        if (revisionOf == null) {
            List<Long> latest = db.jdbc().query("""
                            select id from planting_declaration where declaration_no = ?
                             order by revision_no desc limit 1
                            """, (rs, i) -> rs.getLong(1), req.declarationNo());
            revisionOf = latest.isEmpty() ? null : latest.get(0);
        }
        long id = db.insert("""
                        insert into planting_declaration
                            (declaration_no, parcel_id, crop_code, planting_date, declared_area_ha,
                             observed_at, ingested_at, revision_no, revision_of_id, source)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                req.declarationNo(), req.parcelId(), cropCode, req.plantingDate(), req.declaredAreaHa(),
                Db.timestamp(req.observedAt()), Db.timestamp(req.ingestedAt()), revisionNo, revisionOf,
                req.source());
        if (revisionOf != null) {
            db.jdbc().update("""
                            update planting_declaration set status = 'SUPERSEDED', superseded_by_id = ?
                             where id = ? and status = 'ACTIVE'
                            """, id, revisionOf);
        }
        revisions.record(SourceType.PLANTING_DECLARATION, "planting_declaration", id, req.parcelId(),
                req.declarationNo(), revisionNo, revisionOf, req.observedAt(), req.ingestedAt(),
                req.observedAt(), req.observedAt(),
                "declaration " + req.declarationNo() + " rev " + revisionNo + " crop=" + cropCode);
        return id;
    }

    private int nextDeclarationRevision(String declarationNo) {
        Integer value = db.jdbc().queryForObject("""
                        select coalesce(max(revision_no), 0) + 1 from planting_declaration
                         where declaration_no = ?
                        """, Integer.class, declarationNo);
        return value == null ? 1 : value;
    }

    // -------------------------------------------------------------------- lookup

    private void requireParcel(long parcelId) {
        Integer exists = db.jdbc().queryForObject(
                "select count(*) from parcel where id = ?", Integer.class, parcelId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("PARCEL_NOT_FOUND", "parcel not found: " + parcelId);
        }
    }

    private long stationId(String stationCode) {
        List<Long> ids = db.jdbc().query("select id from weather_station where station_code = ?",
                (rs, i) -> rs.getLong(1), stationCode.trim().toUpperCase());
        if (ids.isEmpty()) {
            throw ApiException.notFound("STATION_NOT_FOUND", "weather station not found: " + stationCode);
        }
        return ids.get(0);
    }

    public record BoundaryRow(long id, long parcelId, int versionNo, com.fasterxml.jackson.databind.JsonNode geometry,
                              double areaHa, java.time.LocalDate validFrom, java.time.LocalDate validTo,
                              Instant observedAt, Instant ingestedAt, Long revisionOfId, String surveyNote) {
        public Geometry toGeometry(GeoUtil geo) {
            return geo.parse(geometry);
        }
    }

    public record WarehouseRecord(SourceType sourceType, long sourceId, String externalKey, int revisionNo,
                                  Instant observedAt, Instant ingestedAt,
                                  com.fasterxml.jackson.databind.JsonNode geometry, Long parcelId,
                                  Instant windowStart, Instant windowEnd) {
    }

    public ObjectNode empty() {
        return db.mapper().createObjectNode();
    }
}
