package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionOutcomeReportState;
import com.indore.pathome.spaces.entity.VisitSessionStatus;

import java.time.Instant;

public record VisitSessionOutcomeExceptionView(
        Long sessionId,
        VisitSessionOutcomeReportState reportState,
        VisitSessionStatus sessionState,
        String city,
        Instant finishedAt,
        Instant scopeCapturedAt,
        Instant lastUpdatedAt,
        Instant overdueSince,
        Long itemCount,
        Long unrecordedCount,
        Long visitedCount) {}
