package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionOutcomeReportState;

import java.time.Instant;
import java.util.List;

public record GroundVisitSessionOutcomeView(
        Long sessionId,
        String sessionState,
        Long sessionVersion,
        VisitSessionOutcomeReportState reportState,
        Long reportVersion,
        Instant scopeCapturedAt,
        String summary,
        List<GroundVisitSessionItemOutcomeView> items) {}
