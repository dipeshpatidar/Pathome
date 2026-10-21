package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lead_command_receipts")
public class LeadCommandReceipt {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false)
    private User actor;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "result_reference", length = 160)
    private String resultReference;

    @Column(name = "completed_at", nullable = false, columnDefinition = "timestamptz")
    private Instant completedAt;

    protected LeadCommandReceipt() {}

    public LeadCommandReceipt(User actor, UUID operationId, String action, String payloadHash,
                              String resultReference, Instant completedAt) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.operationId = operationId;
        this.action = action;
        this.payloadHash = payloadHash;
        this.resultReference = resultReference;
        this.completedAt = completedAt;
    }

    public UUID getOperationId() { return operationId; }
    public String getAction() { return action; }
    public String getPayloadHash() { return payloadHash; }
    public String getResultReference() { return resultReference; }
    public Instant getCompletedAt() { return completedAt; }
}
