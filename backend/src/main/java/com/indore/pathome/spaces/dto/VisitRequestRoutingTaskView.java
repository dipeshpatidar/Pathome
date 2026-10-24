package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record VisitRequestRoutingTaskView(
        Long taskId,
        Long taskVersion,
        String state,
        String reasonCode,
        Long requestId,
        Long requestVersion,
        String requestStatus,
        Long cityId,
        String cityDisplayName,
        Long localityId,
        String localityDisplayName,
        Long listingId,
        String listingTitle,
        Long teamId,
        Long coordinatorUserId,
        Long resultTeamId,
        String resultTeamDisplayName,
        Long resultRequestVersion,
        Instant enrolledAt,
        Instant updatedAt,
        boolean initialRoutingEligible) {
}
