package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionStatus;

import java.time.Instant;
import java.util.List;

public record OperationsVisitSessionView(
        Long sessionId,
        VisitSessionStatus status,
        Long version,
        String city,
        String areaName,
        Instant scheduledAt,
        Instant reservedEndAt,
        Integer durationMinutes,
        String zoneId,
        Long representativeUserId,
        Instant assignedAt,
        List<VisitSessionItemView> items,
        List<LinkedRequestVersion> linkedRequestVersions) {}
