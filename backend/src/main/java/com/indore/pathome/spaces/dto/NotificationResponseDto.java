package com.indore.pathome.spaces.dto;

import java.time.LocalDateTime;

public record NotificationResponseDto(
    Long id,
    String title,
    String message,
    String details,
    String category,
    String type,
    boolean isRead,
    LocalDateTime createdAt,
    LocalDateTime readAt,
    Long listingId,
    String revisionId,
    String actionType,
    String actionTarget
) {
    public static NotificationResponseDto from(com.indore.pathome.spaces.entity.SystemNotification n) {
        if (n == null) return null;
        return new NotificationResponseDto(
                n.getId(),
                n.getTitle(),
                n.getMessage(),
                n.getDetails(),
                n.getCategory(),
                n.getType(),
                n.isRead(),
                n.getCreatedAt(),
                n.getReadAt(),
                n.getListingId(),
                n.getRevisionId(),
                n.getActionType(),
                safeActionTarget(n)
        );
    }

    private static String safeActionTarget(com.indore.pathome.spaces.entity.SystemNotification notification) {
        String target = notification.getActionTarget();
        if (target != null && target.startsWith("/") && !target.startsWith("//") && !target.contains("\\")) {
            return target;
        }
        if (notification.getCategory() != null && notification.getCategory().equals("VISIT_SESSION")
                && notification.getTargetRole() == com.indore.pathome.spaces.entity.TargetRole.TENANT) {
            return "/tenant#visit-history";
        }
        String eventType = notification.getEventKey() == null ? ""
                : notification.getEventKey().split(":", 2)[0];
        if (notification.getTargetRole() == com.indore.pathome.spaces.entity.TargetRole.LANDLORD
                && isLessorWorkflowEvent(eventType)) {
            return notification.getListingId() != null && notification.getListingId() > 0
                    ? "/lessor/listings/" + notification.getListingId() : "/lessor";
        }
        return null;
    }

    private static boolean isLessorWorkflowEvent(String eventType) {
        return switch (eventType) {
            case "PROPERTY_SUBMITTED", "REVIEW_STARTED", "CHANGES_REQUIRED", "PROPERTY_PUBLISHED",
                    "REVISION_SUBMITTED", "REVISION_UNDER_REVIEW", "REVISION_CHANGES_REQUIRED",
                    "REVISION_PUBLISHED", "PROPERTY_PAUSED_BY_OPERATIONS", "PROPERTY_ARCHIVED_BY_OPERATIONS" -> true;
            default -> false;
        };
    }
}
