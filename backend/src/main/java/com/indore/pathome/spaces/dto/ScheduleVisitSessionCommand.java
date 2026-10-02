package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record ScheduleVisitSessionCommand(
        Long expectedSessionVersion,
        Instant scheduledAt,
        String zoneId,
        Long groundExecutiveUserId,
        Integer durationMinutes) {}
