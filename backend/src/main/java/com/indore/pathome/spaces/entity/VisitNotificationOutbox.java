package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** JPA mapping kept aligned with V39 so isolated repository tests can use create-drop schemas. */
@Entity
@Table(name = "visit_notification_outbox", indexes = {
        @Index(name = "idx_visit_notification_outbox_due", columnList = "available_at, id")
})
public class VisitNotificationOutbox {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_key", nullable = false, unique = true, length = 180)
    private String eventKey;
    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;
    @Column(name = "recipient_role", nullable = false, length = 24)
    private String recipientRole;
    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;
    @Column(nullable = false, length = 180)
    private String title;
    @Column(nullable = false, length = 1000)
    private String message;
    @Column(nullable = false, length = 16, columnDefinition = "VARCHAR(16) NOT NULL DEFAULT 'QUEUED'")
    private String state = "QUEUED";
    @Column(nullable = false, columnDefinition = "INTEGER NOT NULL DEFAULT 0")
    private int attempts;
    @Column(name = "available_at", nullable = false,
            columnDefinition = "TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP")
    private Instant availableAt = Instant.now();
    @Column(name = "claimed_at")
    private Instant claimedAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;
    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP")
    private Instant createdAt = Instant.now();
}
