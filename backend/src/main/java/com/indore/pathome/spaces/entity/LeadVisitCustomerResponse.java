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
@Table(name = "lead_visit_customer_responses")
public class LeadVisitCustomerResponse {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false)
    private LeadVisitProposal proposal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_state", nullable = false, length = 20)
    private LeadVisitCustomerResponseState responseState;

    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected LeadVisitCustomerResponse() {}

    public LeadVisitCustomerResponse(UUID id, LeadVisitProposal proposal, User user,
                                     LeadVisitCustomerResponseState responseState, Long requestId,
                                     Instant recordedAt) {
        this.id = Objects.requireNonNull(id);
        this.proposal = Objects.requireNonNull(proposal);
        this.user = Objects.requireNonNull(user);
        this.responseState = Objects.requireNonNull(responseState);
        this.requestId = requestId;
        this.recordedAt = Objects.requireNonNull(recordedAt);
    }

    public void update(LeadVisitCustomerResponseState state, Long requestId, Instant at) {
        this.responseState = Objects.requireNonNull(state);
        this.requestId = requestId;
        this.recordedAt = Objects.requireNonNull(at);
    }

    public UUID getId() { return id; }
    public LeadVisitProposal getProposal() { return proposal; }
    public User getUser() { return user; }
    public LeadVisitCustomerResponseState getResponseState() { return responseState; }
    public Long getRequestId() { return requestId; }
    public Instant getRecordedAt() { return recordedAt; }
    public Long getVersion() { return version; }
}
