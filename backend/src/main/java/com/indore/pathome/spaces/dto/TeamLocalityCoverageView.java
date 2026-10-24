package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record TeamLocalityCoverageView(
        Long id,
        Long cityId,
        String cityDisplayName,
        Long localityId,
        String localityDisplayName,
        Long teamId,
        String teamDisplayName,
        boolean active,
        Long version,
        Instant effectiveFrom,
        Instant updatedAt,
        Long createdByUserId,
        Long updatedByUserId,
        String changeReasonCode) {
}
