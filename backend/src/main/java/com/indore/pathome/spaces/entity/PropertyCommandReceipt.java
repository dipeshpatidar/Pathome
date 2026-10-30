package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "property_command_receipts")
public class PropertyCommandReceipt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id", nullable = false, updatable = false, unique = true)
    private UUID operationId;

    @Column(name = "command_type", nullable = false, updatable = false, length = 40)
    private String commandType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private Long aggregateId;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @Column(name = "result_version", updatable = false)
    private Long resultVersion;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    protected PropertyCommandReceipt() {}

    public PropertyCommandReceipt(UUID operationId, String commandType, Long aggregateId,
                                  String payloadHash, Long actorUserId, Long resultVersion,
                                  Instant createdAt) {
        this.operationId = Objects.requireNonNull(operationId);
        this.commandType = Objects.requireNonNull(commandType);
        this.aggregateId = Objects.requireNonNull(aggregateId);
        this.payloadHash = Objects.requireNonNull(payloadHash);
        this.actorUserId = Objects.requireNonNull(actorUserId);
        this.resultVersion = resultVersion;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public UUID getOperationId() { return operationId; }
    public String getCommandType() { return commandType; }
    public Long getAggregateId() { return aggregateId; }
    public String getPayloadHash() { return payloadHash; }
    public Long getActorUserId() { return actorUserId; }
    public Long getResultVersion() { return resultVersion; }
    public Instant getCreatedAt() { return createdAt; }
}

