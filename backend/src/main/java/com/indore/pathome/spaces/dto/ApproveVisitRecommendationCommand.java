package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record ApproveVisitRecommendationCommand(
        Long expectedSessionVersion,
        Long groundExecutiveUserId,
        Instant scheduledAt,
        String zoneId,
        String overrideReason) {}
