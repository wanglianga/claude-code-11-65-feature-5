package com.agri.pit.model;

public enum SourceType {
    SATELLITE_NDVI,
    WEATHER_OBSERVATION,
    PLANTING_DECLARATION,
    PARCEL_BOUNDARY;

    public static SourceType parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("source type is required");
        }
        try {
            return SourceType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown source type: " + value);
        }
    }
}
