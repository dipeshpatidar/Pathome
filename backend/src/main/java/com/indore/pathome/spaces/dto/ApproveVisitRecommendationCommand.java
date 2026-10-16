package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.UUID;

public record ApproveVisitRecommendationCommand(
        Long expectedSessionVersion,
        Long groundExecutiveUserId,
        Instant scheduledAt,
        String zoneId,
        String overrideReason,
        UUID commandId) {
    /** Existing Admin callers retain their original contract. OE callers must supply a stable ID. */
    public ApproveVisitRecommendationCommand(Long version, Long ge, Instant at, String zone, String reason) {
        this(version, ge, at, zone, reason, null);
    }
}
