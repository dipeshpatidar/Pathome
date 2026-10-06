package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "staff_access_grants")
public class StaffAccessGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private StaffCapability capability;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, updatable = false, length = 16)
    private StaffScopeType scopeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", updatable = false)
    private SupportedCity city;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", updatable = false)
    private OperatingTeam team;

    @Column(name = "effective_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant effectiveAt;

    @Column(name = "expires_at", columnDefinition = "timestamptz", updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", columnDefinition = "timestamptz")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by_user_id", updatable = false)
    private User grantedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by_user_id")
    private User revokedBy;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "grant_reason_code", nullable = false, updatable = false, length = 64)
    private String grantReasonCode;

    @Column(name = "revoke_reason_code", length = 64)
    private String revokeReasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "provisioning_source", nullable = false, updatable = false, length = 32)
    private StaffGrantProvisioningSource provisioningSource;

    protected StaffAccessGrant() {}

    public StaffAccessGrant(User user, StaffCapability capability, StaffScopeType scopeType,
                            SupportedCity city, OperatingTeam team, Instant effectiveAt, Instant expiresAt,
                            User grantedBy, Instant createdAt, String grantReasonCode,
                            StaffGrantProvisioningSource provisioningSource) {
        this.user = Objects.requireNonNull(user);
        this.capability = Objects.requireNonNull(capability);
        this.scopeType = Objects.requireNonNull(scopeType);
        this.city = city;
        this.team = team;
        this.effectiveAt = Objects.requireNonNull(effectiveAt);
        this.expiresAt = expiresAt;
        this.grantedBy = grantedBy;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.grantReasonCode = Objects.requireNonNull(grantReasonCode);
        this.provisioningSource = Objects.requireNonNull(provisioningSource);
        validate();
    }

    private void validate() {
        boolean shapeValid = switch (scopeType) {
            case GLOBAL -> city == null && team == null;
            case CITY -> city != null && team == null;
            case TEAM -> city == null && team != null;
        };
        boolean capabilityValid = switch (capability) {
            case STAFF_ADMIN -> scopeType == StaffScopeType.GLOBAL && expiresAt == null;
            case CITY_TEAM_ADMIN, OPS_INTAKE -> scopeType == StaffScopeType.CITY;
            case OPS_COORDINATE, OPS_SUPERVISE -> scopeType == StaffScopeType.TEAM;
        };
        if (!shapeValid || !capabilityValid) {
            throw new IllegalArgumentException("Staff capability and scope shape are incompatible");
        }
        if (expiresAt != null && !expiresAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException("Grant expiry must be later than its effective time");
        }
        if (grantReasonCode.isBlank() || grantReasonCode.length() > 64) {
            throw new IllegalArgumentException("Grant reason code must be bounded and non-empty");
        }
    }

    public void revoke(User actor, Instant at, String reasonCode) {
        if (revokedAt != null) return;
        this.revokedBy = Objects.requireNonNull(actor);
        this.revokedAt = Objects.requireNonNull(at);
        this.revokeReasonCode = Objects.requireNonNull(reasonCode);
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public StaffCapability getCapability() { return capability; }
    public StaffScopeType getScopeType() { return scopeType; }
    public SupportedCity getCity() { return city; }
    public OperatingTeam getTeam() { return team; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public User getGrantedBy() { return grantedBy; }
    public User getRevokedBy() { return revokedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getGrantReasonCode() { return grantReasonCode; }
    public String getRevokeReasonCode() { return revokeReasonCode; }
    public StaffGrantProvisioningSource getProvisioningSource() { return provisioningSource; }
}
