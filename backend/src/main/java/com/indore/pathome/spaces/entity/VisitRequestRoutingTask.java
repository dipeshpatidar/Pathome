package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Durable enrollment and recovery state for one trusted new Visit Request. */
@Entity
@Table(name = "visit_request_routing_tasks", uniqueConstraints = {
        @UniqueConstraint(name = "uk_visit_request_routing_task_request", columnNames = "request_id")
})
public class VisitRequestRoutingTask {
    public static final String TRUSTED_NEW_REQUEST_V1 = "TRUSTED_NEW_REQUEST_V1";
    public static final int SUPPORTED_PROTOCOL_VERSION = 1;
    public static final int MAX_ATTEMPTS = 8;
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    private PropertyVisitRequest request;

    @Column(nullable = false, updatable = false, length = 40)
    private String provenance;

    @Column(name = "protocol_version", nullable = false, updatable = false)
    private int protocolVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private VisitRequestRoutingTaskState state = VisitRequestRoutingTaskState.PENDING;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "expected_request_version", nullable = false, updatable = false)
    private Long expectedRequestVersion;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false, columnDefinition = "timestamptz")
    private Instant availableAt;

    @Column(name = "enrolled_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant enrolledAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @Column(name = "completed_at", columnDefinition = "timestamptz")
    private Instant completedAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "decision_code", length = 64)
    private String decisionCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coverage_id")
    private TeamLocalityCoverage coverage;

    @Column(name = "coverage_version")
    private Long coverageVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_team_id")
    private OperatingTeam resultTeam;

    @Column(name = "result_request_version")
    private Long resultRequestVersion;

    @Column(name = "audit_event_id")
    private Long auditEventId;

    protected VisitRequestRoutingTask() {}

    public VisitRequestRoutingTask(PropertyVisitRequest request, Instant at) {
        this.request = Objects.requireNonNull(request);
        this.provenance = TRUSTED_NEW_REQUEST_V1;
        this.protocolVersion = SUPPORTED_PROTOCOL_VERSION;
        this.expectedRequestVersion = Objects.requireNonNull(request.getVersion());
        this.availableAt = Objects.requireNonNull(at);
        this.enrolledAt = at;
        this.updatedAt = at;
    }

    public boolean isDue(Instant now) {
        return state == VisitRequestRoutingTaskState.PENDING || state == VisitRequestRoutingTaskState.RETRY;
    }

    public void needsReview(String reasonCode, Instant at) {
        this.state = VisitRequestRoutingTaskState.NEEDS_REVIEW;
        this.lastErrorCode = validateCode(reasonCode);
        this.availableAt = at;
        this.updatedAt = at;
        this.completedAt = null;
    }

    public void retry(String reasonCode, Instant at) {
        this.attempts = Math.min(MAX_ATTEMPTS, attempts + 1);
        this.lastErrorCode = validateCode(reasonCode);
        this.state = attempts >= MAX_ATTEMPTS
                ? VisitRequestRoutingTaskState.NEEDS_REVIEW
                : VisitRequestRoutingTaskState.RETRY;
        long delaySeconds = Math.min(300L, 5L * (1L << Math.min(6, Math.max(0, attempts - 1))));
        this.availableAt = at.plus(Duration.ofSeconds(delaySeconds));
        this.updatedAt = at;
    }

    public void requeue(Instant at) {
        if (state != VisitRequestRoutingTaskState.NEEDS_REVIEW) {
            throw new IllegalStateException("Only routing exceptions can be rechecked");
        }
        this.state = VisitRequestRoutingTaskState.PENDING;
        this.availableAt = Objects.requireNonNull(at);
        this.updatedAt = at;
        this.lastErrorCode = null;
    }

    public void complete(TeamLocalityCoverage coverage, OperatingTeam team, Long resultRequestVersion,
                         Long auditEventId, Instant at) {
        this.state = VisitRequestRoutingTaskState.DONE;
        this.decisionCode = "AUTOMATIC_COVERAGE_MATCH_V1";
        this.coverage = Objects.requireNonNull(coverage);
        this.coverageVersion = coverage.getVersion();
        this.resultTeam = Objects.requireNonNull(team);
        this.resultRequestVersion = Objects.requireNonNull(resultRequestVersion);
        this.auditEventId = Objects.requireNonNull(auditEventId);
        this.completedAt = Objects.requireNonNull(at);
        this.updatedAt = at;
        this.lastErrorCode = null;
    }

    public void completeManual(OperatingTeam team, Long resultRequestVersion, Long auditEventId, Instant at) {
        this.state = VisitRequestRoutingTaskState.DONE;
        this.decisionCode = "MANUAL_EXCEPTION_V1";
        this.coverage = null;
        this.coverageVersion = null;
        this.resultTeam = Objects.requireNonNull(team);
        this.resultRequestVersion = Objects.requireNonNull(resultRequestVersion);
        this.auditEventId = Objects.requireNonNull(auditEventId);
        this.completedAt = Objects.requireNonNull(at);
        this.updatedAt = at;
        this.lastErrorCode = null;
    }

    public void skip(String reasonCode, Instant at) {
        this.state = VisitRequestRoutingTaskState.SKIPPED;
        this.lastErrorCode = validateCode(reasonCode);
        this.completedAt = null;
        this.updatedAt = at;
    }

    private static String validateCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("A bounded uppercase routing code is required");
        }
        return code;
    }

    public Long getId() { return id; }
    public PropertyVisitRequest getRequest() { return request; }
    public VisitRequestRoutingTaskState getState() { return state; }
    public Long getVersion() { return version; }
    public int getProtocolVersion() { return protocolVersion; }
    public Long getExpectedRequestVersion() { return expectedRequestVersion; }
    public int getAttempts() { return attempts; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public String getDecisionCode() { return decisionCode; }
    public TeamLocalityCoverage getCoverage() { return coverage; }
    public Long getCoverageVersion() { return coverageVersion; }
    public OperatingTeam getResultTeam() { return resultTeam; }
    public Long getResultRequestVersion() { return resultRequestVersion; }
    public Long getAuditEventId() { return auditEventId; }
}
