package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "lead_activities")
public class LeadActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private LeadRoutingQueue lead;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actor;

    @Column(name = "activity_type", nullable = false, length = 48)
    private String activityType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_event_id")
    private LeadSourceEvent sourceEvent;

    @Column(name = "occurred_at", nullable = false, columnDefinition = "timestamptz")
    private Instant occurredAt;

    @Column(columnDefinition = "text")
    private String note;

    protected LeadActivity() {}

    public LeadActivity(LeadRoutingQueue lead, User actor, String activityType,
                        LeadSourceEvent sourceEvent, Instant occurredAt, String note) {
        this.lead = lead;
        this.actor = actor;
        this.activityType = activityType;
        this.sourceEvent = sourceEvent;
        this.occurredAt = occurredAt;
        this.note = note;
    }

    public Long getId() { return id; }
    public String getActivityType() { return activityType; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getNote() { return note; }
}
