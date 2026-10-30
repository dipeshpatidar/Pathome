package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "property_revision_events")
public class PropertyRevisionEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "draft_id", updatable = false, length = 64)
    private String draftId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 40)
    private String eventType;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @Column(name = "occurred_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant occurredAt;

    @Column(name = "draft_version", updatable = false)
    private Integer draftVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;

    protected PropertyRevisionEvent() {}

    public PropertyRevisionEvent(Long listingId, String draftId, String eventType, Long actorUserId,
                                 Instant occurredAt, Integer draftVersion, Map<String, Object> details) {
        this.listingId = Objects.requireNonNull(listingId);
        this.draftId = draftId;
        this.eventType = Objects.requireNonNull(eventType);
        this.actorUserId = Objects.requireNonNull(actorUserId);
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.draftVersion = draftVersion;
        this.details = Map.copyOf(details);
    }

    public Long getId() { return id; }
    public Long getListingId() { return listingId; }
    public String getDraftId() { return draftId; }
    public String getEventType() { return eventType; }
    public Long getActorUserId() { return actorUserId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Integer getDraftVersion() { return draftVersion; }
    public Map<String, Object> getDetails() { return details; }
}

