package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.List;

public record TenantVisitSessionOutcomeView(
        Long sessionId,
        String lifecycle,
        String outcomeSummary,
        boolean outcomeReportAvailable,
        Instant scheduledAt,
        Instant startedAt,
        Instant finishedAt,
        String city,
        String locality,
        Long totalProperties,
        Long viewedProperties,
        Instant lastUpdatedAt,
        List<TenantVisitSessionItemOutcomeView> properties) {}
