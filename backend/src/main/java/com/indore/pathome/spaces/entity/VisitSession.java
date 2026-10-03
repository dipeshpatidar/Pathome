package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "visit_sessions", indexes = {
        @Index(name = "idx_visit_session_tenant_history", columnList = "tenant_id, created_at, id"),
        @Index(name = "idx_visit_session_status_schedule", columnList = "status, scheduled_at"),
        @Index(name = "idx_visit_session_rep_schedule", columnList = "representative_user_id, status, scheduled_at"),
        @Index(name = "idx_visit_session_ge_reservation", columnList = "representative_user_id, scheduled_at, reserved_end_at")
})
public class VisitSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private User tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private VisitSessionStatus status = VisitSessionStatus.DRAFT;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(nullable = false, length = 160)
    private String city;

    @Column(name = "area_name", length = 160)
    private String areaName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "canonical_locality_id")
    private Locality canonicalLocality;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "reserved_end_at")
    private Instant reservedEndAt;

    @Column(name = "zone_id", length = 64)
    private String zoneId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "representative_user_id")
    private User representative;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_snapshot_minutes")
    private Integer durationSnapshotMinutes;

    @Column(name = "entitlement_consumed_at")
    private Instant entitlementConsumedAt;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "execution_duration_snapshot_minutes")
    private Integer executionDurationSnapshotMinutes;

    @Column(name = "expected_end_at")
    private Instant expectedEndAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "tenant_eta_at")
    private Instant tenantEtaAt;

    @Column(name = "tenant_confirmation_state", nullable = false, length = 24)
    private String tenantConfirmationState = "NOT_REQUIRED";

    @Column(name = "tenant_confirmed_at")
    private Instant tenantConfirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_confirmed_by_user_id")
    private User tenantConfirmedBy;

    @Column(name = "repair_state", nullable = false, length = 24)
    private String repairState = "NONE";

    @Column(name = "repair_operation_id")
    private java.util.UUID repairOperationId;

    @Column(name = "provisional_no_show_at")
    private Instant provisionalNoShowAt;

    @Column(name = "no_show_dispute_until")
    private Instant noShowDisputeUntil;

    @Column(name = "start_operation_id")
    private java.util.UUID startOperationId;

    @Column(name = "needs_more_time_at")
    private Instant needsMoreTimeAt;

    @Column(name = "execution_state_changed_at")
    private Instant executionStateChangedAt;

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
        if (tenant == null) throw new IllegalStateException("Visit session tenant is required");
        if (status == null) throw new IllegalStateException("Visit session status is required");
        if (city == null || city.isBlank()) throw new IllegalStateException("Visit session city is required");
        if (durationSnapshotMinutes != null && durationSnapshotMinutes < 0)
            throw new IllegalStateException("Duration snapshot cannot be negative");
        if (startedAt != null && expiresAt != null && expiresAt.isBefore(startedAt))
            throw new IllegalStateException("Visit session expiry cannot precede its start");
        if ((scheduledAt == null) != (zoneId == null))
            throw new IllegalStateException("Scheduled timestamp and timezone must be provided together");
        if (zoneId != null) java.time.ZoneId.of(zoneId);
        if ((representative == null) != (assignedAt == null))
            throw new IllegalStateException("Representative and assignment timestamp must be provided together");
        if (reservedEndAt != null && (scheduledAt == null || !reservedEndAt.isAfter(scheduledAt)))
            throw new IllegalStateException("Reserved end must follow the scheduled start");
        if ((status == VisitSessionStatus.SCHEDULED || status == VisitSessionStatus.STARTED)
                && (scheduledAt == null || representative == null || assignedAt == null
                    || durationSnapshotMinutes == null || durationSnapshotMinutes <= 0
                    || reservedEndAt == null))
            throw new IllegalStateException("Scheduled sessions require an assigned representative and positive reservation");
        if (status == VisitSessionStatus.SCHEDULED
                && !reservedEndAt.equals(scheduledAt.plus(durationSnapshotMinutes, ChronoUnit.MINUTES)))
            throw new IllegalStateException("Reservation end must match the planned duration");
        if (status == VisitSessionStatus.STARTED && (startedAt == null || executionDurationSnapshotMinutes == null
                || executionDurationSnapshotMinutes <= 0 || expectedEndAt == null
                || !reservedEndAt.equals(expectedEndAt)))
            throw new IllegalStateException("Started sessions require an execution end reservation");
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getTenant() { return tenant; }
    public void setTenant(User tenant) { this.tenant = tenant; }
    public VisitSessionStatus getStatus() { return status; }
    public void setStatus(VisitSessionStatus status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getAreaName() { return areaName; }
    public void setAreaName(String areaName) { this.areaName = areaName; }
    public Locality getCanonicalLocality() { return canonicalLocality; }
    public void setCanonicalLocality(Locality canonicalLocality) { this.canonicalLocality = canonicalLocality; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    public Instant getReservedEndAt() { return reservedEndAt; }
    public void setReservedEndAt(Instant reservedEndAt) { this.reservedEndAt = reservedEndAt; }
    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }
    public User getRepresentative() { return representative; }
    public void setRepresentative(User representative) { this.representative = representative; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Integer getDurationSnapshotMinutes() { return durationSnapshotMinutes; }
    public void setDurationSnapshotMinutes(Integer durationSnapshotMinutes) { this.durationSnapshotMinutes = durationSnapshotMinutes; }
    public Instant getEntitlementConsumedAt() { return entitlementConsumedAt; }
    public void setEntitlementConsumedAt(Instant entitlementConsumedAt) { this.entitlementConsumedAt = entitlementConsumedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getArrivedAt() { return arrivedAt; }
    public void setArrivedAt(Instant arrivedAt) { this.arrivedAt = arrivedAt; }
    public Integer getExecutionDurationSnapshotMinutes() { return executionDurationSnapshotMinutes; }
    public void setExecutionDurationSnapshotMinutes(Integer value) { this.executionDurationSnapshotMinutes = value; }
    public Instant getExpectedEndAt() { return expectedEndAt; }
    public void setExpectedEndAt(Instant expectedEndAt) { this.expectedEndAt = expectedEndAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getTenantEtaAt() { return tenantEtaAt; }
    public void setTenantEtaAt(Instant tenantEtaAt) { this.tenantEtaAt = tenantEtaAt; }
    public String getTenantConfirmationState() { return tenantConfirmationState; }
    public void setTenantConfirmationState(String value) { this.tenantConfirmationState = value; }
    public Instant getTenantConfirmedAt() { return tenantConfirmedAt; }
    public void setTenantConfirmedAt(Instant value) { this.tenantConfirmedAt = value; }
    public User getTenantConfirmedBy() { return tenantConfirmedBy; }
    public void setTenantConfirmedBy(User value) { this.tenantConfirmedBy = value; }
    public String getRepairState() { return repairState; }
    public void setRepairState(String value) { this.repairState = value; }
    public java.util.UUID getRepairOperationId() { return repairOperationId; }
    public void setRepairOperationId(java.util.UUID value) { this.repairOperationId = value; }
    public Instant getProvisionalNoShowAt() { return provisionalNoShowAt; }
    public void setProvisionalNoShowAt(Instant value) { this.provisionalNoShowAt = value; }
    public Instant getNoShowDisputeUntil() { return noShowDisputeUntil; }
    public void setNoShowDisputeUntil(Instant value) { this.noShowDisputeUntil = value; }
    public java.util.UUID getStartOperationId() { return startOperationId; }
    public void setStartOperationId(java.util.UUID value) { this.startOperationId = value; }
    public Instant getNeedsMoreTimeAt() { return needsMoreTimeAt; }
    public void setNeedsMoreTimeAt(Instant value) { this.needsMoreTimeAt = value; }
    public Instant getExecutionStateChangedAt() { return executionStateChangedAt; }
    public void setExecutionStateChangedAt(Instant value) { this.executionStateChangedAt = value; }
}
