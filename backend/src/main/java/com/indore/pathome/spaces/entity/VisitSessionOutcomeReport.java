package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "visit_session_outcome_reports")
public class VisitSessionOutcomeReport {
    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private VisitSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private VisitSessionOutcomeReportState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_source", nullable = false, length = 24)
    private VisitSessionOutcomeScopeSource scopeSource;

    @Column(name = "scope_captured_at")
    private Instant scopeCapturedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finalized_by_user_id")
    private User finalizedBy;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected VisitSessionOutcomeReport() {}

    public VisitSessionOutcomeReport(VisitSession session, VisitSessionOutcomeReportState state,
            VisitSessionOutcomeScopeSource scopeSource, Instant scopeCapturedAt) {
        this.session = session;
        this.sessionId = session == null ? null : session.getId();
        this.state = state;
        this.scopeSource = scopeSource;
        this.scopeCapturedAt = scopeCapturedAt;
    }

    @PrePersist
    protected void onCreate() {
        validate();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        validate();
        updatedAt = Instant.now();
    }

    private void validate() {
        if (session == null || state == null || scopeSource == null)
            throw new IllegalStateException("Outcome report identity and state are required");
        boolean legacy = state == VisitSessionOutcomeReportState.LEGACY_UNRECORDED;
        if (legacy != (scopeSource == VisitSessionOutcomeScopeSource.LEGACY_COMPLETED)
                || legacy != (scopeCapturedAt == null))
            throw new IllegalStateException("Legacy outcome reports must not claim a captured scope");
        if (state == VisitSessionOutcomeReportState.FINALIZED
                && (finalizedAt == null || finalizedBy == null))
            throw new IllegalStateException("Finalized outcome reports require actor and timestamp");
        if (state != VisitSessionOutcomeReportState.FINALIZED
                && (finalizedAt != null || finalizedBy != null))
            throw new IllegalStateException("Only finalized outcome reports may have finalization metadata");
    }

    public void markFinalized(User actor, Instant at) {
        this.state = VisitSessionOutcomeReportState.FINALIZED;
        this.finalizedBy = actor;
        this.finalizedAt = at;
    }

    public void touch() { this.updatedAt = Instant.now(); }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public VisitSession getSession() { return session; }
    public void setSession(VisitSession session) { this.session = session; this.sessionId = session == null ? null : session.getId(); }
    public VisitSessionOutcomeReportState getState() { return state; }
    public void setState(VisitSessionOutcomeReportState state) { this.state = state; }
    public VisitSessionOutcomeScopeSource getScopeSource() { return scopeSource; }
    public void setScopeSource(VisitSessionOutcomeScopeSource scopeSource) { this.scopeSource = scopeSource; }
    public Instant getScopeCapturedAt() { return scopeCapturedAt; }
    public void setScopeCapturedAt(Instant scopeCapturedAt) { this.scopeCapturedAt = scopeCapturedAt; }
    public Instant getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(Instant finalizedAt) { this.finalizedAt = finalizedAt; }
    public User getFinalizedBy() { return finalizedBy; }
    public void setFinalizedBy(User finalizedBy) { this.finalizedBy = finalizedBy; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
