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
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "lead_lessor_availability_enquiries")
public class LeadLessorAvailabilityEnquiry {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private LeadRoutingQueue lead;

    @Column(name = "shortlist_id", nullable = false)
    private Long shortlistId;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_event_id", nullable = false)
    private LeadSourceEvent sourceEvent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private SupportedCity city;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private OperatingTeam team;

    @Column(name = "lead_version", nullable = false)
    private Long leadVersion;

    @Column(name = "shortlist_version", nullable = false)
    private Long shortlistVersion;

    @Column(name = "listing_version", nullable = false)
    private Long listingVersion;

    @Column(nullable = false, length = 32)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private LeadLessorAvailabilityOutcome outcome;

    @Column(name = "outcome_note_activity_id")
    private Long outcomeNoteActivityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporting_user_id")
    private User reportingUser;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected LeadLessorAvailabilityEnquiry() {}

    public LeadLessorAvailabilityEnquiry(UUID id, LeadRoutingQueue lead, Long shortlistId,
                                         Long listingId, LeadSourceEvent sourceEvent, User createdBy,
                                         SupportedCity city, OperatingTeam team, Long leadVersion,
                                         Long shortlistVersion, Long listingVersion, Instant createdAt,
                                         Instant expiresAt) {
        this.id = Objects.requireNonNull(id);
        this.lead = Objects.requireNonNull(lead);
        this.shortlistId = Objects.requireNonNull(shortlistId);
        this.listingId = Objects.requireNonNull(listingId);
        this.sourceEvent = Objects.requireNonNull(sourceEvent);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.city = Objects.requireNonNull(city);
        this.team = Objects.requireNonNull(team);
        this.leadVersion = Objects.requireNonNull(leadVersion);
        this.shortlistVersion = Objects.requireNonNull(shortlistVersion);
        this.listingVersion = Objects.requireNonNull(listingVersion);
        this.purpose = "VIEWING_AVAILABILITY";
        this.outcome = LeadLessorAvailabilityOutcome.PENDING;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    /**
     * Records a report only while this enquiry remains open. Withdrawal, dispute and property
     * decline permanently close this disclosure context; later reports belong to activity history
     * and cannot resurrect the old enquiry.
     */
    public boolean recordOutcome(LeadLessorAvailabilityOutcome next, Long activityId, User actor,
                                 Instant at) {
        if (next == null || next == LeadLessorAvailabilityOutcome.PENDING) {
            throw new IllegalArgumentException("A reported availability outcome is required");
        }
        if (isDisclosureClosed()) return false;
        this.outcome = next;
        this.outcomeNoteActivityId = activityId;
        this.reportingUser = Objects.requireNonNull(actor);
        this.reportedAt = Objects.requireNonNull(at);
        return true;
    }

    public boolean isDisclosureClosed() {
        return outcome == LeadLessorAvailabilityOutcome.CUSTOMER_WITHDREW
                || outcome == LeadLessorAvailabilityOutcome.CUSTOMER_DISPUTED
                || outcome == LeadLessorAvailabilityOutcome.PROPERTY_DECLINED;
    }

    public UUID getId() { return id; }
    public LeadRoutingQueue getLead() { return lead; }
    public Long getShortlistId() { return shortlistId; }
    public Long getListingId() { return listingId; }
    public LeadSourceEvent getSourceEvent() { return sourceEvent; }
    public User getCreatedBy() { return createdBy; }
    public SupportedCity getCity() { return city; }
    public OperatingTeam getTeam() { return team; }
    public Long getLeadVersion() { return leadVersion; }
    public Long getShortlistVersion() { return shortlistVersion; }
    public Long getListingVersion() { return listingVersion; }
    public String getPurpose() { return purpose; }
    public LeadLessorAvailabilityOutcome getOutcome() { return outcome; }
    public Long getOutcomeNoteActivityId() { return outcomeNoteActivityId; }
    public User getReportingUser() { return reportingUser; }
    public Instant getReportedAt() { return reportedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Long getVersion() { return version; }
}
