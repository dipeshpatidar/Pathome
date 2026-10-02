package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SchedulingWindowValidatorTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    @Test
    void acceptsValidWindowAndPreferredPointUsingMatchingIanaZone() {
        var result = SchedulingWindowValidator.optional(
                OffsetDateTime.parse("2026-10-03T13:00:00+05:30"),
                OffsetDateTime.parse("2026-10-03T17:00:00+05:30"), "Asia/Kolkata",
                OffsetDateTime.parse("2026-10-03T14:00:00+05:30"), NOW, true).orElseThrow();

        assertEquals(Instant.parse("2026-10-03T07:30:00Z"), result.startsAt());
        assertEquals(Instant.parse("2026-10-03T11:30:00Z"), result.endsAt());
        assertEquals(Instant.parse("2026-10-03T08:30:00Z"), result.preferredAt());
        assertEquals("Asia/Kolkata", result.zoneId());
    }

    @Test
    void allowsLegacyRequestWithoutStructuredWindow() {
        assertTrue(SchedulingWindowValidator.optional(null, null, null, null, NOW, true).isEmpty());
    }

    @Test
    void rejectsReversedWindowAndPreferredPointOutside() {
        assertThrows(IllegalArgumentException.class, () -> SchedulingWindowValidator.required(
                OffsetDateTime.parse("2026-10-03T17:00:00+05:30"),
                OffsetDateTime.parse("2026-10-03T13:00:00+05:30"), "Asia/Kolkata", NOW, true));
        assertThrows(IllegalArgumentException.class, () -> SchedulingWindowValidator.optional(
                OffsetDateTime.parse("2026-10-03T13:00:00+05:30"),
                OffsetDateTime.parse("2026-10-03T17:00:00+05:30"), "Asia/Kolkata",
                OffsetDateTime.parse("2026-10-03T17:30:00+05:30"), NOW, true));
    }

    @Test
    void rejectsPastWindowAndOffsetThatDoesNotMatchZone() {
        assertThrows(IllegalArgumentException.class, () -> SchedulingWindowValidator.required(
                OffsetDateTime.parse("2026-10-01T13:00:00+05:30"),
                OffsetDateTime.parse("2026-10-01T17:00:00+05:30"), "Asia/Kolkata", NOW, true));
        assertThrows(IllegalArgumentException.class, () -> SchedulingWindowValidator.required(
                OffsetDateTime.parse("2026-10-03T13:00:00Z"),
                OffsetDateTime.parse("2026-10-03T17:00:00Z"), "Asia/Kolkata", NOW, true));
    }

    @Test
    void rejectsOffsetOnlyTimezoneAndIncompleteWindow() {
        assertThrows(IllegalArgumentException.class, () -> SchedulingWindowValidator.required(
                OffsetDateTime.parse("2026-10-03T13:00:00+05:30"),
                OffsetDateTime.parse("2026-10-03T17:00:00+05:30"), "+05:30", NOW, true));
        assertThrows(IllegalArgumentException.class, () -> SchedulingWindowValidator.optional(
                OffsetDateTime.parse("2026-10-03T13:00:00+05:30"), null, "Asia/Kolkata", null, NOW, true));
    }
}
