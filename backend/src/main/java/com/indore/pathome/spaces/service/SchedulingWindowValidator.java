package com.indore.pathome.spaces.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

public final class SchedulingWindowValidator {
    private SchedulingWindowValidator() {}

    public static Optional<Window> optional(OffsetDateTime startsAt, OffsetDateTime endsAt,
                                            String zoneId, OffsetDateTime preferredAt,
                                            Instant now, boolean requireFuture) {
        boolean allAbsent = startsAt == null && endsAt == null && zoneId == null && preferredAt == null;
        if (allAbsent) return Optional.empty();
        if (startsAt == null || endsAt == null || zoneId == null || zoneId.isBlank())
            throw new IllegalArgumentException("Availability requires a start, end, and IANA timezone");
        if (!startsAt.toInstant().isBefore(endsAt.toInstant()))
            throw new IllegalArgumentException("Availability start must be before its end");

        ZoneId zone = parseIanaZone(zoneId);
        requireZoneOffset(startsAt, zone, "Availability start");
        requireZoneOffset(endsAt, zone, "Availability end");
        if (preferredAt != null) {
            requireZoneOffset(preferredAt, zone, "Preferred time");
            if (preferredAt.toInstant().isBefore(startsAt.toInstant())
                    || preferredAt.toInstant().isAfter(endsAt.toInstant()))
                throw new IllegalArgumentException("Preferred time must fall inside the availability window");
        }
        if (requireFuture && startsAt.toInstant().isBefore(now))
            throw new IllegalArgumentException("Availability window cannot start in the past");
        return Optional.of(new Window(startsAt.toInstant(), endsAt.toInstant(), zone.getId(),
                preferredAt == null ? null : preferredAt.toInstant()));
    }

    public static Window required(OffsetDateTime startsAt, OffsetDateTime endsAt,
                                  String zoneId, Instant now, boolean requireFuture) {
        return optional(startsAt, endsAt, zoneId, null, now, requireFuture)
                .orElseThrow(() -> new IllegalArgumentException("Availability window is required"));
    }

    private static ZoneId parseIanaZone(String zoneId) {
        try {
            ZoneId zone = ZoneId.of(zoneId.trim());
            if (zone instanceof ZoneOffset)
                throw new IllegalArgumentException("Timezone must be an IANA timezone ID");
            return zone;
        } catch (DateTimeException invalidZone) {
            throw new IllegalArgumentException("Timezone must be a valid IANA timezone ID", invalidZone);
        }
    }

    private static void requireZoneOffset(OffsetDateTime value, ZoneId zone, String label) {
        if (!zone.getRules().getValidOffsets(value.toLocalDateTime()).contains(value.getOffset()))
            throw new IllegalArgumentException(label + " offset does not match the supplied timezone");
    }

    public record Window(Instant startsAt, Instant endsAt, String zoneId, Instant preferredAt) {}
}
