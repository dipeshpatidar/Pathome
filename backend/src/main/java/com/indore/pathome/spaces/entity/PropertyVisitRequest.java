package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A tenant's initial expression of interest. This is intentionally not a scheduled Visit Session.
 */
@Entity
@Table(name = "property_visit_requests", indexes = {
        @Index(name = "idx_property_visit_request_tenant_history", columnList = "tenant_id, created_at, id"),
        @Index(name = "idx_property_visit_request_listing", columnList = "listing_id"),
        @Index(name = "idx_property_visit_request_status_created", columnList = "status, created_at")
}, uniqueConstraints = @UniqueConstraint(
        name = "uk_property_visit_request_tenant_listing", columnNames = {"tenant_id", "listing_id"}))
public class PropertyVisitRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private User tenant;

    @ManyToOne(optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @Column(name = "budget_min", precision = 14, scale = 2)
    private BigDecimal budgetMin;

    @Column(name = "budget_max", precision = 14, scale = 2)
    private BigDecimal budgetMax;

    @Column(name = "preferred_areas", length = 500)
    private String preferredAreas;

    @Column(name = "move_in_timing", length = 160)
    private String moveInTiming;

    @Column(name = "preferred_visit_timing", length = 240)
    private String preferredVisitTiming;

    @Column(name = "availability_start_at")
    private Instant availabilityStartAt;

    @Column(name = "availability_end_at")
    private Instant availabilityEndAt;

    @Column(name = "availability_zone_id", length = 64)
    private String availabilityZoneId;

    @Column(name = "preferred_at")
    private Instant preferredAt;

    @Column(name = "tenant_note", length = 2000)
    private String tenantNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private VisitRequestStatus status = VisitRequestStatus.RECEIVED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private VisitSession session;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getTenant() { return tenant; }
    public void setTenant(User tenant) { this.tenant = tenant; }
    public Listing getListing() { return listing; }
    public void setListing(Listing listing) { this.listing = listing; }
    public BigDecimal getBudgetMin() { return budgetMin; }
    public void setBudgetMin(BigDecimal budgetMin) { this.budgetMin = budgetMin; }
    public BigDecimal getBudgetMax() { return budgetMax; }
    public void setBudgetMax(BigDecimal budgetMax) { this.budgetMax = budgetMax; }
    public String getPreferredAreas() { return preferredAreas; }
    public void setPreferredAreas(String preferredAreas) { this.preferredAreas = preferredAreas; }
    public String getMoveInTiming() { return moveInTiming; }
    public void setMoveInTiming(String moveInTiming) { this.moveInTiming = moveInTiming; }
    public String getPreferredVisitTiming() { return preferredVisitTiming; }
    public void setPreferredVisitTiming(String preferredVisitTiming) { this.preferredVisitTiming = preferredVisitTiming; }
    public Instant getAvailabilityStartAt() { return availabilityStartAt; }
    public void setAvailabilityStartAt(Instant availabilityStartAt) { this.availabilityStartAt = availabilityStartAt; }
    public Instant getAvailabilityEndAt() { return availabilityEndAt; }
    public void setAvailabilityEndAt(Instant availabilityEndAt) { this.availabilityEndAt = availabilityEndAt; }
    public String getAvailabilityZoneId() { return availabilityZoneId; }
    public void setAvailabilityZoneId(String availabilityZoneId) { this.availabilityZoneId = availabilityZoneId; }
    public Instant getPreferredAt() { return preferredAt; }
    public void setPreferredAt(Instant preferredAt) { this.preferredAt = preferredAt; }
    public String getTenantNote() { return tenantNote; }
    public void setTenantNote(String tenantNote) { this.tenantNote = tenantNote; }
    public String getStatus() { return status.name(); }
    public void setStatus(String status) { this.status = VisitRequestStatus.valueOf(status); }
    public VisitRequestStatus getStatusValue() { return status; }
    public void setStatus(VisitRequestStatus status) { this.status = status; }
    public VisitSession getSession() { return session; }
    public void setSession(VisitSession session) { this.session = session; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
