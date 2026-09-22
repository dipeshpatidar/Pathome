package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "system_notifications", indexes = {
    @Index(name = "idx_notif_target_role", columnList = "target_role, created_at"),
    @Index(name = "idx_notif_recipient", columnList = "recipient_user_id, created_at"),
    @Index(name = "idx_notif_event_key", columnList = "event_key", unique = true)
})
public class SystemNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", length = 120, unique = true)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_role", nullable = false)
    private TargetRole targetRole = TargetRole.ALL;

    @Column(name = "recipient_user_id")
    private String recipientUserId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(nullable = false)
    private String category = "SYSTEM";

    @Column(nullable = false)
    private String type = "info";

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public SystemNotification() {}

    public SystemNotification(TargetRole targetRole, String recipientUserId, String title, String message, String details, String category, String type) {
        this.targetRole = targetRole != null ? targetRole : TargetRole.ALL;
        this.recipientUserId = recipientUserId;
        this.title = title;
        this.message = message;
        this.details = details;
        this.category = category != null ? category : "SYSTEM";
        this.type = type != null ? type : "info";
        this.isRead = false;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }

    public TargetRole getTargetRole() { return targetRole; }
    public void setTargetRole(TargetRole targetRole) { this.targetRole = targetRole; }

    public String getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(String recipientUserId) { this.recipientUserId = recipientUserId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
