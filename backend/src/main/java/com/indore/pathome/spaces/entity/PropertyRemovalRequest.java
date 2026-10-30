package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "property_removal_requests")
public class PropertyRemovalRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "requester_user_id", nullable = false, updatable = false)
    private Long requesterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_status", nullable = false, length = 16)
    private PropertyRemovalRequestStatus requestStatus = PropertyRemovalRequestStatus.PENDING;

    @Version
    @Column(name = "request_version", nullable = false)
    private Long requestVersion = 0L;

    @Column(name = "creation_operation_id", nullable = false, updatable = false)
    private UUID creationOperationId;

    @Column(name = "creation_payload_hash", nullable = false, updatable = false, length = 64)
    private String creationPayloadHash;

    @Column(name = "requested_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant requestedAt;

    @Column(name = "requested_listing_version", nullable = false, updatable = false)
    private Long requestedListingVersion;

    @Column(name = "owner_reason", updatable = false, length = 1000)
    private String ownerReason;

    @Column(name = "terminal_operation_id")
    private UUID terminalOperationId;

    @Column(name = "terminal_payload_hash", length = 64)
    private String terminalPayloadHash;

    @Column(name = "decision_actor_user_id")
    private Long decisionActorUserId;

    @Column(name = "decided_at", columnDefinition = "timestamptz")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    @Column(name = "observed_listing_version")
    private Long observedListingVersion;

    @Column(name = "resulting_listing_version")
    private Long resultingListingVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "authority_evidence", columnDefinition = "jsonb")
    private Map<String, Object> authorityEvidence;

    protected PropertyRemovalRequest() {}

    public PropertyRemovalRequest(Long listingId, Long requesterUserId, UUID creationOperationId,
                                  String creationPayloadHash, Instant requestedAt,
                                  Long requestedListingVersion, String ownerReason) {
        this.listingId = Objects.requireNonNull(listingId);
        this.requesterUserId = Objects.requireNonNull(requesterUserId);
        this.creationOperationId = Objects.requireNonNull(creationOperationId);
        this.creationPayloadHash = Objects.requireNonNull(creationPayloadHash);
        this.requestedAt = Objects.requireNonNull(requestedAt);
        this.requestedListingVersion = Objects.requireNonNull(requestedListingVersion);
        this.ownerReason = ownerReason;
    }

    public void approve(Long actorUserId, UUID operationId, String operationHash,
                        String reason, Long observedVersion, Long resultingVersion,
                        Instant at, Map<String, Object> evidence) {
        terminal(PropertyRemovalRequestStatus.APPROVED, actorUserId, operationId, operationHash,
                reason, observedVersion, resultingVersion, at, evidence);
    }

    public void reject(Long actorUserId, UUID operationId, String operationHash,
                       String reason, Long observedVersion, Instant at, Map<String, Object> evidence) {
        terminal(PropertyRemovalRequestStatus.REJECTED, actorUserId, operationId, operationHash,
                reason, observedVersion, observedVersion, at, evidence);
    }

    public void withdraw(Long requesterUserId, UUID operationId, String operationHash,
                         Long observedVersion, Instant at) {
        terminal(PropertyRemovalRequestStatus.WITHDRAWN, requesterUserId, operationId, operationHash,
                null, observedVersion, observedVersion, at, Map.of());
    }

    private void terminal(PropertyRemovalRequestStatus target, Long actorUserId, UUID operationId,
                          String operationHash, String reason, Long observedVersion,
                          Long resultingVersion, Instant at, Map<String, Object> evidence) {
        if (requestStatus != PropertyRemovalRequestStatus.PENDING) {
            throw new IllegalStateException("Property removal request is already terminal");
        }
        this.requestStatus = target;
        this.decisionActorUserId = Objects.requireNonNull(actorUserId);
        this.terminalOperationId = Objects.requireNonNull(operationId);
        this.terminalPayloadHash = Objects.requireNonNull(operationHash);
        this.decisionReason = reason;
        this.observedListingVersion = Objects.requireNonNull(observedVersion);
        this.resultingListingVersion = Objects.requireNonNull(resultingVersion);
        this.decidedAt = Objects.requireNonNull(at);
        this.authorityEvidence = evidence == null || evidence.isEmpty() ? null : Map.copyOf(evidence);
    }

    public Long getId() { return id; }
    public Long getListingId() { return listingId; }
    public Long getRequesterUserId() { return requesterUserId; }
    public PropertyRemovalRequestStatus getRequestStatus() { return requestStatus; }
    public Long getRequestVersion() { return requestVersion; }
    public UUID getCreationOperationId() { return creationOperationId; }
    public String getCreationPayloadHash() { return creationPayloadHash; }
    public Instant getRequestedAt() { return requestedAt; }
    public Long getRequestedListingVersion() { return requestedListingVersion; }
    public String getOwnerReason() { return ownerReason; }
    public UUID getTerminalOperationId() { return terminalOperationId; }
    public String getTerminalPayloadHash() { return terminalPayloadHash; }
    public Long getDecisionActorUserId() { return decisionActorUserId; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionReason() { return decisionReason; }
    public Long getObservedListingVersion() { return observedListingVersion; }
    public Long getResultingListingVersion() { return resultingListingVersion; }
    public Map<String, Object> getAuthorityEvidence() { return authorityEvidence; }
}

