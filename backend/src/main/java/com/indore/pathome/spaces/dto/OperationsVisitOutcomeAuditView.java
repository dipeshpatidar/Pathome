package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record OperationsVisitOutcomeAuditView(
        Long itemId,
        String previousOutcome,
        String previousSkipReason,
        String correctedOutcome,
        String correctedSkipReason,
        String correctionReason,
        Long actorUserId,
        Instant correctedAt) {}
