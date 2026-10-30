package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "lead_visit_links")
public class LeadVisitLink {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false)
    private LeadRoutingQueue lead;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false)
    private LeadVisitProposal proposal;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    protected LeadVisitLink() {}

    public LeadVisitLink(UUID id, LeadRoutingQueue lead, User user, LeadVisitProposal proposal,
                         Long requestId, Instant confirmedAt) {
        this.id = Objects.requireNonNull(id);
        this.lead = Objects.requireNonNull(lead);
        this.user = Objects.requireNonNull(user);
        this.proposal = Objects.requireNonNull(proposal);
        this.listingId = Objects.requireNonNull(proposal.getListingId());
        this.requestId = Objects.requireNonNull(requestId);
        this.confirmedAt = Objects.requireNonNull(confirmedAt);
    }

    public UUID getId() { return id; }
    public LeadRoutingQueue getLead() { return lead; }
    public User getUser() { return user; }
    public LeadVisitProposal getProposal() { return proposal; }
    public Long getListingId() { return listingId; }
    public Long getRequestId() { return requestId; }
    public Instant getConfirmedAt() { return confirmedAt; }
}
