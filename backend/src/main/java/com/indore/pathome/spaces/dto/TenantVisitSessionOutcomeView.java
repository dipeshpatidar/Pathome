package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.List;

public record TenantVisitSessionOutcomeView(
        Long sessionId,
        String lifecycle,
        String outcomeSummary,
        Instant scheduledAt,
        Instant startedAt,
        Instant finishedAt,
        List<TenantVisitSessionItemOutcomeView> properties) {}
