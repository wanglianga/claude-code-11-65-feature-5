package com.agri.pit.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class Times {

    public static final ZoneOffset UTC = ZoneOffset.UTC;

    private Times() {
    }

    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(UTC).toInstant();
    }

    public static Instant endOfDayExclusive(LocalDate date) {
        return date.plusDays(1).atStartOfDay(UTC).toInstant();
    }

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(UTC);
    }

    public static String format(Instant instant) {
        return instant == null ? null : instant.atOffset(UTC).toString();
    }
}
