package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.List;

public record OperationsVisitOutcomeDetailView(
        Long sessionId,
        String physicalState,
        String reportState,
        Long reportVersion,
        Long currentGroundExecutiveUserId,
        String city,
        String locality,
        Instant scheduledAt,
        Instant startedAt,
        Instant finishedAt,
        Instant lastUpdatedAt,
        String summary,
        long totalProperties,
        long pendingProperties,
        long viewedProperties,
        List<OperationsVisitOutcomeItemView> properties,
        List<OperationsVisitOutcomeAuditView> correctionHistory) {}
