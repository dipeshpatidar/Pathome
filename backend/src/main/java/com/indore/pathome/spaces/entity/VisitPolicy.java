package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "visit_policy")
public class VisitPolicy {
    public static final long SINGLETON_ID = 1L;
    public static final int DEFAULT_FREE_VISIT_SESSIONS = 5;
    public static final int DEFAULT_MAX_SESSION_DURATION_MINUTES = 60;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "free_visit_sessions_default", nullable = false)
    private Integer freeVisitSessionsDefault = DEFAULT_FREE_VISIT_SESSIONS;

    @Column(name = "max_visit_session_duration_minutes", nullable = false)
    private Integer maxVisitSessionDurationMinutes = DEFAULT_MAX_SESSION_DURATION_MINUTES;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

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
        if (id == null || id != SINGLETON_ID) throw new IllegalStateException("Visit policy must use its singleton identity");
        if (freeVisitSessionsDefault == null || freeVisitSessionsDefault < 0)
            throw new IllegalStateException("Free visit session count cannot be negative");
        if (maxVisitSessionDurationMinutes == null || maxVisitSessionDurationMinutes <= 0)
            throw new IllegalStateException("Maximum session duration must be positive");
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getFreeVisitSessionsDefault() { return freeVisitSessionsDefault; }
    public void setFreeVisitSessionsDefault(Integer freeVisitSessionsDefault) { this.freeVisitSessionsDefault = freeVisitSessionsDefault; }
    public Integer getMaxVisitSessionDurationMinutes() { return maxVisitSessionDurationMinutes; }
    public void setMaxVisitSessionDurationMinutes(Integer maxVisitSessionDurationMinutes) { this.maxVisitSessionDurationMinutes = maxVisitSessionDurationMinutes; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
