package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionStatus;
import java.time.Instant;

public record VisitExecutionView(Long sessionId, VisitSessionStatus status, Long version,
        Instant scheduledAt, String zoneId, Instant arrivedAt, Instant startedAt, Instant expectedEndAt,
        Instant finishedAt, Instant tenantEtaAt, String tenantConfirmationState,
        String repairState, boolean overPlannedTime, String resultCode) {}
