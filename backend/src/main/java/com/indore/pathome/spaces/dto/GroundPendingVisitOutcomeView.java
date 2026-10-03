package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionStatus;
import java.time.Instant;

public record GroundPendingVisitOutcomeView(
        Long sessionId,
        VisitSessionStatus sessionState,
        Instant scheduledAt,
        Instant finishedAt,
        String city,
        Long pendingPropertyCount,
        Long totalPropertyCount) {}
