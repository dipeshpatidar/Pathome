package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.List;

public record GroundExecutiveSchedulingView(
        Long groundExecutiveUserId,
        boolean schedulingActive,
        Long version,
        Instant updatedAt,
        List<Shift> shifts,
        List<Unavailability> unavailableIntervals,
        List<Coverage> coverage
) {
    public record Shift(Long id, Instant startsAt, Instant endsAt, String zoneId, Long version) {}
    public record Unavailability(Long id, Instant startsAt, Instant endsAt, String zoneId,
                                 String intervalType, Long version) {}
    public record Coverage(Long id, String city, Long localityId, String localityName) {}
}
