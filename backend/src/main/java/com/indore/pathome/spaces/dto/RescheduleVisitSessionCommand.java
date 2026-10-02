package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record RescheduleVisitSessionCommand(
        Long expectedSessionVersion,
        Instant scheduledAt,
        String zoneId) {}
