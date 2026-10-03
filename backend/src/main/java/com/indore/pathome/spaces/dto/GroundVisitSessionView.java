package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionStatus;

import java.time.Instant;
import java.util.List;

public record GroundVisitSessionView(
        Long sessionId,
        VisitSessionStatus status,
        Long version,
        String city,
        Instant scheduledAt,
        Instant reservedEndAt,
        Integer durationMinutes,
        String zoneId,
        Instant arrivedAt,
        Instant startedAt,
        Instant expectedEndAt,
        Instant finishedAt,
        Instant tenantEtaAt,
        String tenantConfirmationState,
        String repairState,
        boolean overPlannedTime,
        List<GroundVisitSessionItemView> items) {}
