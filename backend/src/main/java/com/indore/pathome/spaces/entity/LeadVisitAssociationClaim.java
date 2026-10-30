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
@Table(name = "lead_visit_association_claims")
public class LeadVisitAssociationClaim {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false)
    private LeadVisitProposal proposal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_response_id", nullable = false)
    private LeadVisitCustomerResponse customerResponse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "consent_wording_version", nullable = false, length = 64)
    private String consentWordingVersion;

    @Column(name = "consent_recorded_at", nullable = false)
    private Instant consentRecordedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LeadVisitAssociationStatus state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_user_id")
    private User decidedBy;

    @Column(name = "decision_reason_code", length = 64)
    private String decisionReasonCode;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected LeadVisitAssociationClaim() {}

    public LeadVisitAssociationClaim(UUID id, LeadVisitProposal proposal,
                                     LeadVisitCustomerResponse response, User user, Long requestId,
                                     String consentWordingVersion, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.proposal = Objects.requireNonNull(proposal);
        this.customerResponse = Objects.requireNonNull(response);
        this.user = Objects.requireNonNull(user);
        this.requestId = Objects.requireNonNull(requestId);
        this.consentWordingVersion = Objects.requireNonNull(consentWordingVersion);
        this.consentRecordedAt = Objects.requireNonNull(now);
        this.state = LeadVisitAssociationStatus.PENDING;
        this.createdAt = now;
    }

    public void decide(LeadVisitAssociationStatus next, User actor, String reason, Instant at) {
        if (state != LeadVisitAssociationStatus.PENDING || next == null
                || next == LeadVisitAssociationStatus.PENDING) {
            throw new IllegalStateException("Only a pending association claim can be decided");
        }
        state = next;
        decidedBy = Objects.requireNonNull(actor);
        decisionReasonCode = reason;
        decidedAt = Objects.requireNonNull(at);
    }

    public void withdraw(Instant at) {
        if (state == LeadVisitAssociationStatus.PENDING) {
            state = LeadVisitAssociationStatus.WITHDRAWN;
            decidedAt = Objects.requireNonNull(at);
        }
    }

    public UUID getId() { return id; }
    public LeadVisitProposal getProposal() { return proposal; }
    public LeadVisitCustomerResponse getCustomerResponse() { return customerResponse; }
    public User getUser() { return user; }
    public Long getRequestId() { return requestId; }
    public String getConsentWordingVersion() { return consentWordingVersion; }
    public Instant getConsentRecordedAt() { return consentRecordedAt; }
    public LeadVisitAssociationStatus getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public User getDecidedBy() { return decidedBy; }
    public String getDecisionReasonCode() { return decisionReasonCode; }
    public Long getVersion() { return version; }
}
