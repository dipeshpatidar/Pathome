package com.indore.pathome.spaces.dto;

public record VisitSchedulingApprovalView(
        OperationsVisitSessionView session,
        Long decisionId,
        boolean override,
        Long topRecommendedGroundExecutiveUserId,
        java.time.Instant topRecommendedAt) {}
