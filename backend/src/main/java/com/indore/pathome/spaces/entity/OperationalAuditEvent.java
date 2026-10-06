package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "operational_audit_events")
public class OperationalAuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, updatable = false, length = 32)
    private AuditActorKind actorKind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", updatable = false)
    private User actorUser;

    @Column(name = "operator_reference", updatable = false, length = 120)
    private String operatorReference;

    @Column(name = "action_code", nullable = false, updatable = false, length = 64)
    private String actionCode;

    @Column(name = "target_type", nullable = false, updatable = false, length = 48)
    private String targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", updatable = false)
    private SupportedCity city;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", updatable = false)
    private OperatingTeam team;

    @Column(name = "operation_id", updatable = false, length = 100)
    private String operationId;

    @Column(name = "correlation_id", updatable = false, length = 100)
    private String correlationId;

    @Column(name = "reason_code", nullable = false, updatable = false, length = 64)
    private String reasonCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;

    protected OperationalAuditEvent() {}

    public OperationalAuditEvent(Instant occurredAt, AuditActorKind actorKind, User actorUser,
                                 String operatorReference, String actionCode, String targetType,
                                 Long targetId, SupportedCity city, OperatingTeam team,
                                 String operationId, String correlationId, String reasonCode,
                                 Map<String, Object> details) {
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.actorKind = Objects.requireNonNull(actorKind);
        this.actorUser = actorUser;
        this.operatorReference = operatorReference;
        this.actionCode = Objects.requireNonNull(actionCode);
        this.targetType = Objects.requireNonNull(targetType);
        this.targetId = Objects.requireNonNull(targetId);
        this.city = city;
        this.team = team;
        this.operationId = operationId;
        this.correlationId = correlationId;
        this.reasonCode = Objects.requireNonNull(reasonCode);
        this.details = Map.copyOf(details);
        validateActor();
    }

    private void validateActor() {
        if (actorKind == AuditActorKind.USER && (actorUser == null || operatorReference != null)) {
            throw new IllegalArgumentException("User audit events require the real acting User");
        }
        if (actorKind == AuditActorKind.DEPLOYMENT_OPERATOR
                && (actorUser != null || operatorReference == null || operatorReference.isBlank())) {
            throw new IllegalArgumentException("Deployment audit events require an operator reference only");
        }
    }

    public Long getId() { return id; }
}
