package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lead_share_events")
public class LeadShareEvent {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "share_id", nullable = false)
    private LeadShare share;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private LeadShareEventType eventType;

    @Column(name = "occurred_at", nullable = false, columnDefinition = "timestamptz")
    private Instant occurredAt;

    protected LeadShareEvent() {}

    public LeadShareEvent(UUID id, LeadShare share, User actor, LeadShareEventType eventType,
                          Instant occurredAt) {
        this.id = id;
        this.share = share;
        this.actor = actor;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public LeadShare getShare() { return share; }
    public Long getActorId() { return actor.getId(); }
    public LeadShareEventType getEventType() { return eventType; }
    public Instant getOccurredAt() { return occurredAt; }
}
