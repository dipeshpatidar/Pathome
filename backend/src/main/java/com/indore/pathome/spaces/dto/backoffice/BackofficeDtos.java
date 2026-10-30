package com.indore.pathome.spaces.dto.backoffice;

import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.RoomTag;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BackofficeDtos {
    private BackofficeDtos() {}

    public record Summary(
            Long listingId,
            String title,
            String city,
            String locality,
            String coverUrl,
            BigDecimal monthlyRent,
            ListingWorkflowStatus status,
            Long version,
            Instant updatedAt,
            String openRevisionDraftId,
            String openRevisionStatus
    ) {}

    public record Page(List<Summary> items, int page, boolean hasMore) {}

    public record Detail(
            Long listingId,
            ListingWorkflowStatus status,
            Long version,
            String city,
            String locality,
            StaffPropertySnapshot content,
            String openRevisionDraftId,
            String openRevisionStatus,
            Integer openRevisionVersion,
            String openRevisionMode,
            boolean canSupersedeRevision,
            Long publicationVersion,
            Instant publishedAt,
            Instant lastContentPublishedAt
    ) {}

    public record Revision(
            String draftId,
            Long listingId,
            String revisionMode,
            String status,
            Integer version,
            Long revisionBaseVersion,
            StaffPropertySnapshot content,
            List<FieldChange> changes,
            Preview preview
    ) {}

    public record Basics(String title, String description, String bhkCount, PropertyType propertyType) {}

    public record Commercial(
            BigDecimal monthlyRent,
            BigDecimal securityDeposit,
            LocalDate availableFrom,
            BigDecimal maintenanceCharge,
            BigDecimal brokerageAmount,
            Integer brokerageDays,
            Integer securityDepositMonths
    ) {}

    public record Details(
            Double totalAreaSqFt,
            Integer floorNumber,
            Integer totalFloors,
            String furnishingStatus,
            String amenities,
            Integer bathroomCount,
            String vastuFacing,
            Boolean bachelorAllowed,
            String preferredTenant
    ) {}

    public record PresentationAddress(String address, String landmark, String colony, String pincode) {}

    public record MediaPresentation(
            String mediaId,
            RoomTag roomTag,
            String caption,
            Integer sortOrder,
            boolean cover
    ) {}

    public record FieldChange(String field, String label, String before, String after) {}

    public record Preview(
            boolean ready,
            List<String> blockers,
            List<String> warnings,
            List<FieldChange> changes,
            Long baseVersion,
            Long currentVersion
    ) {}

    public record PublishCommand(String reason) {}

    public record SupersedeCommand(String reason) {}

    public record LifecycleCommand(String reason) {}

    public record PublishResult(
            Long listingId,
            Long listingVersion,
            Long publicationVersion,
            Instant publishedAt,
            ListingWorkflowStatus status
    ) {}

    public record Activity(
            String eventId,
            String kind,
            String eventType,
            String draftId,
            Long actorUserId,
            String actorDisplayName,
            Instant occurredAt,
            Long version,
            String summary,
            Map<String, Object> details
    ) {}

    public record ActivityPage(List<Activity> items, int page, boolean hasMore, String nextCursor) {}

    public record Committed(Long listingId, Long version, ListingWorkflowStatus status,
                            Long publicationVersion, Instant committedAt) {}

    public record CommandOutcome(UUID operationId, String commandType, Long listingId,
                                 Long resultVersion, Long listingVersion,
                                 ListingWorkflowStatus status, Instant committedAt) {}

    public record NewMedia(String mediaId, String url, MediaType mediaType, RoomTag roomTag,
                           String caption, Integer sortOrder, boolean cover, String uploadStatus) {}

    public record MediaPatch(String mediaId, RoomTag roomTag, String caption,
                             Integer sortOrder, boolean cover) {}
}

