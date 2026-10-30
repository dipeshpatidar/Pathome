package com.indore.pathome.spaces.dto.removal;

import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.PropertyRemovalRequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PropertyRemovalDtos {
    private PropertyRemovalDtos() {}

    public record CreateRequest(UUID operationId, String reason) {}

    public record CreateResult(RequestView request, boolean created) {}

    public record WithdrawRequest(UUID operationId) {}

    public enum DecisionType { APPROVED, REJECTED }

    public record DecisionRequest(UUID operationId, DecisionType decision,
                                  Long expectedListingVersion, String reason) {}

    public record RequestView(
            Long requestId,
            Long listingId,
            PropertyRemovalRequestStatus requestStatus,
            Long requestVersion,
            ListingWorkflowStatus currentListingStatus,
            Long currentListingVersion,
            Instant requestedAt,
            Instant terminalAt,
            String ownerReason,
            String decisionReason,
            List<String> permittedActions
    ) {}

    public record OwnerHistory(List<RequestView> items, int page, boolean hasMore) {}

    public record AdminQueueItem(
            Long requestId,
            Long listingId,
            String propertyTitle,
            String city,
            String locality,
            PropertyRemovalRequestStatus requestStatus,
            Long requestVersion,
            Long currentListingVersion,
            ListingWorkflowStatus currentListingStatus,
            Instant requestedAt,
            String ownerReason,
            long activeAppointmentCount
    ) {}

    public record AdminQueue(List<AdminQueueItem> items, int page, boolean hasMore) {}

    public record AdminDetail(
            RequestView request,
            String propertyTitle,
            String city,
            String locality,
            long scheduledAppointmentCount,
            long startedAppointmentCount,
            String openRevisionStatus,
            String openRevisionDraftId
    ) {}
}

