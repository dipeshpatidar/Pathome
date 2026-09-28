package com.indore.pathome.spaces.dto;

import java.time.LocalDateTime;

public record NotificationResponseDto(
    Long id,
    String targetRole,
    String recipientUserId,
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
    String actionTarget,
    String eventKey
) {
    public static NotificationResponseDto from(com.indore.pathome.spaces.entity.SystemNotification n) {
        if (n == null) return null;
        return new NotificationResponseDto(
                n.getId(),
                n.getTargetRole() != null ? n.getTargetRole().name() : "ALL",
                n.getRecipientUserId(),
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
                n.getActionTarget(),
                n.getEventKey()
        );
    }
}
