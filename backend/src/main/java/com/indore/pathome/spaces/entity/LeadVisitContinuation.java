package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "lead_visit_continuations")
public class LeadVisitContinuation {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false)
    private LeadVisitProposal proposal;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    protected LeadVisitContinuation() {}

    public LeadVisitContinuation(UUID id, LeadVisitProposal proposal, String secretHash,
                                 Instant createdAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id);
        this.proposal = Objects.requireNonNull(proposal);
        this.secretHash = Objects.requireNonNull(secretHash);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public void seen(Instant at) {
        lastSeenAt = Objects.requireNonNull(at);
    }

    public void revoke(Instant at) {
        if (revokedAt == null) revokedAt = Objects.requireNonNull(at);
    }

    public UUID getId() { return id; }
    public LeadVisitProposal getProposal() { return proposal; }
    public String getSecretHash() { return secretHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Long getVersion() { return version; }
}
