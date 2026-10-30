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
@Table(name = "lead_visit_proposals")
public class LeadVisitProposal {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private LeadRoutingQueue lead;

    @Column(name = "shortlist_id", nullable = false)
    private Long shortlistId;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(name = "contact_preference_revision", nullable = false)
    private Long contactPreferenceRevision;

    @Column(name = "lead_version", nullable = false)
    private Long leadVersion;

    @Column(name = "shortlist_version", nullable = false)
    private Long shortlistVersion;

    @Column(name = "listing_version", nullable = false)
    private Long listingVersion;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LeadVisitProposalState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "association_status", nullable = false, length = 16)
    private LeadVisitAssociationStatus associationStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected LeadVisitProposal() {}

    public LeadVisitProposal(UUID id, LeadRoutingQueue lead, Long shortlistId, Long listingId,
                             Long contactPreferenceRevision, Long leadVersion,
                             Long shortlistVersion, Long listingVersion, String tokenHash,
                             User createdBy, Instant createdAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id);
        this.lead = Objects.requireNonNull(lead);
        this.shortlistId = Objects.requireNonNull(shortlistId);
        this.listingId = Objects.requireNonNull(listingId);
        this.contactPreferenceRevision = Objects.requireNonNull(contactPreferenceRevision);
        this.leadVersion = Objects.requireNonNull(leadVersion);
        this.shortlistVersion = Objects.requireNonNull(shortlistVersion);
        this.listingVersion = Objects.requireNonNull(listingVersion);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.state = LeadVisitProposalState.OPEN;
        this.associationStatus = LeadVisitAssociationStatus.UNAVAILABLE;
        this.createdBy = Objects.requireNonNull(createdBy);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public void expire(Instant at) {
        if (state != LeadVisitProposalState.OPEN) return;
        state = LeadVisitProposalState.EXPIRED;
        revokedAt = at;
    }

    public void revoke(Instant at) {
        if (state != LeadVisitProposalState.OPEN) return;
        state = LeadVisitProposalState.REVOKED;
        revokedAt = at;
    }

    public void supersede(Instant at) {
        if (state != LeadVisitProposalState.OPEN) return;
        state = LeadVisitProposalState.SUPERSEDED;
        supersededAt = at;
    }

    public void markAssociationPending() {
        associationStatus = LeadVisitAssociationStatus.PENDING;
    }

    public void association(LeadVisitAssociationStatus status) {
        associationStatus = Objects.requireNonNull(status);
    }

    public UUID getId() { return id; }
    public LeadRoutingQueue getLead() { return lead; }
    public Long getShortlistId() { return shortlistId; }
    public Long getListingId() { return listingId; }
    public Long getContactPreferenceRevision() { return contactPreferenceRevision; }
    public Long getLeadVersion() { return leadVersion; }
    public Long getShortlistVersion() { return shortlistVersion; }
    public Long getListingVersion() { return listingVersion; }
    public String getTokenHash() { return tokenHash; }
    public LeadVisitProposalState getState() { return state; }
    public LeadVisitAssociationStatus getAssociationStatus() { return associationStatus; }
    public User getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getSupersededAt() { return supersededAt; }
    public Long getVersion() { return version; }
}
