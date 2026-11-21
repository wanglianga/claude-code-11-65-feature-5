package com.agri.pit.ingest;

import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.model.SourceType;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/warehouse")
public class WarehouseController {

    private final WarehouseService warehouse;
    private final Db db;

    public WarehouseController(WarehouseService warehouse, Db db) {
        this.warehouse = warehouse;
        this.db = db;
    }

    @PostMapping("/parcels")
    public IdResponse createParcel(@RequestBody IngestDtos.ParcelRequest req) {
        return new IdResponse(warehouse.createParcel(req));
    }

    @GetMapping("/parcels")
    public List<Map<String, Object>> listParcels() {
        return db.jdbc().queryForList("""
                select p.id, p.parcel_code, p.name, p.region, p.crop_code,
                       (select count(*) from parcel_boundary_version b where b.parcel_id = p.id) as boundary_versions
                  from parcel p order by p.parcel_code
                """);
    }

    @PostMapping("/parcels/{parcelId}/boundaries")
    public IdResponse addBoundary(@PathVariable long parcelId, @RequestBody IngestDtos.BoundaryRequest req) {
        return new IdResponse(warehouse.addBoundary(parcelId, req));
    }

    @GetMapping("/parcels/{parcelId}/boundaries")
    public List<BoundaryView> listBoundaries(@PathVariable long parcelId) {
        return warehouse.boundaryVersions(parcelId).stream()
                .map(b -> new BoundaryView(b.id(), b.versionNo(), b.areaHa(), b.validFrom(), b.validTo(),
                        Times.format(b.observedAt()), Times.format(b.ingestedAt()),
                        b.revisionOfId(), b.surveyNote(), b.geometry()))
                .toList();
    }

    @PostMapping("/stations")
    public IdResponse createStation(@RequestBody IngestDtos.StationRequest req) {
        return new IdResponse(warehouse.createStation(req));
    }

    @GetMapping("/stations")
    public List<Map<String, Object>> listStations() {
        return db.jdbc().queryForList("select id, station_code, name, lon, lat from weather_station order by station_code");
    }

    @PostMapping("/parcels/{parcelId}/stations")
    public StatusResponse linkStation(@PathVariable long parcelId,
                                      @RequestBody IngestDtos.ParcelStationLinkRequest req) {
        warehouse.linkStation(parcelId, req.stationId(), req.weight());
        return new StatusResponse("LINKED");
    }

    @PostMapping("/scenes")
    public IdResponse ingestScene(@RequestBody IngestDtos.SceneRequest req) {
        return new IdResponse(warehouse.ingestScene(req));
    }

    @GetMapping("/scenes")
    public List<Map<String, Object>> listScenes() {
        return db.jdbc().queryForList("""
                select id, scene_key, revision_no, acquisition_at, ingested_at, cloud_cover_pct, status,
                       revision_of_id, superseded_by_id,
                       (select count(*) from satellite_scene_cell c where c.scene_id = satellite_scene.id) as cells
                  from satellite_scene order by scene_key, revision_no
                """);
    }

    @PostMapping("/weather")
    public IdResponse ingestWeather(@RequestBody IngestDtos.WeatherRequest req) {
        return new IdResponse(warehouse.ingestWeather(req));
    }

    @GetMapping("/weather")
    public List<Map<String, Object>> listWeather(@RequestParam(required = false) String stationCode) {
        if (stationCode == null || stationCode.isBlank()) {
            return db.jdbc().queryForList("""
                    select w.id, s.station_code, w.external_key, w.period_start, w.period_end, w.rainfall_mm,
                           w.observed_at, w.ingested_at, w.revision_no, w.revision_of_id, w.status
                      from weather_observation w join weather_station s on s.id = w.station_id
                     order by s.station_code, w.external_key, w.revision_no
                    """);
        }
        return db.jdbc().queryForList("""
                select w.id, s.station_code, w.external_key, w.period_start, w.period_end, w.rainfall_mm,
                       w.observed_at, w.ingested_at, w.revision_no, w.revision_of_id, w.status
                  from weather_observation w join weather_station s on s.id = w.station_id
                 where s.station_code = ?
                 order by w.external_key, w.revision_no
                """, stationCode.trim().toUpperCase());
    }

    @PostMapping("/declarations")
    public IdResponse ingestDeclaration(@RequestBody IngestDtos.DeclarationRequest req) {
        return new IdResponse(warehouse.ingestDeclaration(req));
    }

    @GetMapping("/declarations")
    public List<Map<String, Object>> listDeclarations(@RequestParam(required = false) Long parcelId) {
        String sql = """
                select d.id, d.declaration_no, d.parcel_id, p.parcel_code, d.crop_code, d.planting_date,
                       d.declared_area_ha, d.observed_at, d.ingested_at, d.revision_no, d.revision_of_id, d.status
                  from planting_declaration d join parcel p on p.id = d.parcel_id
                """;
        if (parcelId == null) {
            return db.jdbc().queryForList(sql + " order by d.declaration_no, d.revision_no");
        }
        return db.jdbc().queryForList(sql + " where d.parcel_id = ? order by d.declaration_no, d.revision_no", parcelId);
    }

    @GetMapping("/revision-events")
    public List<Map<String, Object>> listRevisions(@RequestParam(required = false) String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return db.jdbc().queryForList("""
                    select id, source_type, source_table, source_id, external_key, revision_no, previous_id,
                           parcel_id, observed_at, ingested_at, window_start, window_end, description, created_at
                      from data_revision_event order by id desc
                    """);
        }
        SourceType type = SourceType.parse(sourceType);
        return db.jdbc().queryForList("""
                select id, source_type, source_table, source_id, external_key, revision_no, previous_id,
                       parcel_id, observed_at, ingested_at, window_start, window_end, description, created_at
                  from data_revision_event where source_type = ? order by id desc
                """, type.name());
    }

    @GetMapping("/as-of/weather")
    public List<Map<String, Object>> weatherAsOf(@RequestParam String stationCode,
                                                 @RequestParam String cutoff,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        Instant cutoffAt = parseInstant(cutoff, "cutoff");
        Instant fromAt = from == null ? null : parseInstant(from, "from");
        Instant toAt = to == null ? null : parseInstant(to, "to");
        return db.jdbc().queryForList("""
                select distinct on (w.external_key)
                       w.id, w.external_key, w.period_start, w.period_end, w.rainfall_mm,
                       w.observed_at, w.ingested_at, w.revision_no
                  from weather_observation w join weather_station s on s.id = w.station_id
                 where s.station_code = ?
                   and w.ingested_at <= ?
                   and (?::timestamptz is null or w.period_end >= ?::timestamptz)
                   and (?::timestamptz is null or w.period_start <= ?::timestamptz)
                 order by w.external_key, w.revision_no desc
                """, stationCode.trim().toUpperCase(), Db.timestamp(cutoffAt),
                fromAt == null ? null : Db.timestamp(fromAt), fromAt == null ? null : Db.timestamp(fromAt),
                toAt == null ? null : Db.timestamp(toAt), toAt == null ? null : Db.timestamp(toAt));
    }

    public static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("TIMESTAMP_REQUIRED", field + " is required (ISO-8601)");
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_TIMESTAMP",
                    field + " must be an ISO-8601 instant such as 2024-06-01T00:00:00Z");
        }
    }

    public record IdResponse(long id) {
    }

    public record StatusResponse(String status) {
    }

    public record BoundaryView(long id, int versionNo, double areaHa, LocalDate validFrom, LocalDate validTo,
                               String observedAt, String ingestedAt, Long revisionOfId, String surveyNote,
                               com.fasterxml.jackson.databind.JsonNode geometry) {
    }
}
