package com.agri.pit.ingest;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class IngestDtos {

    public record ParcelRequest(
            String parcelCode,
            String name,
            String region,
            String cropCode) {
    }

    public record BoundaryRequest(
            Integer versionNo,
            JsonNode geometry,
            LocalDate validFrom,
            LocalDate validTo,
            Instant observedAt,
            Instant ingestedAt,
            Long revisionOfVersionId,
            String surveyNote) {
    }

    public record StationRequest(String stationCode, String name, double lon, double lat) {
    }

    public record ParcelStationLinkRequest(long stationId, Double weight) {
    }

    public record SceneCellRequest(String cellKey, double lon, double lat, double ndvi, Double areaHa) {
    }

    public record SceneRequest(
            String sceneKey,
            Integer revisionNo,
            Instant acquisitionAt,
            Instant ingestedAt,
            Double cloudCoverPct,
            JsonNode footprint,
            String source,
            Long revisionOfSceneId,
            List<SceneCellRequest> cells) {
    }

    public record WeatherRequest(
            String stationCode,
            String externalKey,
            Instant periodStart,
            Instant periodEnd,
            double rainfallMm,
            Double tempMeanC,
            Instant observedAt,
            Instant ingestedAt,
            Integer revisionNo,
            Long revisionOfId,
            String source) {
    }

    public record DeclarationRequest(
            String declarationNo,
            long parcelId,
            String cropCode,
            LocalDate plantingDate,
            double declaredAreaHa,
            Instant observedAt,
            Instant ingestedAt,
            Integer revisionNo,
            Long revisionOfId,
            String source) {
    }
}
