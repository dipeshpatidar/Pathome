package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable locality-to-Team identity with a revocable active state and optimistic version. */
@Entity
@Table(name = "team_locality_coverage", uniqueConstraints = {
        @UniqueConstraint(name = "uk_team_locality_coverage_pair",
                columnNames = {"canonical_locality_id", "operating_team_id"})
})
public class TeamLocalityCoverage {
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supported_city_id", nullable = false, updatable = false)
    private SupportedCity supportedCity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "canonical_locality_id", nullable = false, updatable = false)
    private Locality canonicalLocality;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operating_team_id", nullable = false, updatable = false)
    private OperatingTeam operatingTeam;

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false, updatable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "updated_by_user_id", nullable = false)
    private User updatedBy;

    @Column(name = "change_reason_code", nullable = false, length = 64)
    private String changeReasonCode;

    protected TeamLocalityCoverage() {}

    public TeamLocalityCoverage(SupportedCity supportedCity, Locality canonicalLocality,
                                OperatingTeam operatingTeam, User actor, Instant at, String reasonCode) {
        this.supportedCity = Objects.requireNonNull(supportedCity);
        this.canonicalLocality = Objects.requireNonNull(canonicalLocality);
        this.operatingTeam = Objects.requireNonNull(operatingTeam);
        this.active = true;
        this.createdAt = Objects.requireNonNull(at);
        this.updatedAt = at;
        this.createdBy = Objects.requireNonNull(actor);
        this.updatedBy = actor;
        this.changeReasonCode = validateReason(reasonCode);
        validateCity();
    }

    public void changeActive(boolean active, User actor, Instant at, String reasonCode) {
        this.active = active;
        this.updatedBy = Objects.requireNonNull(actor);
        this.updatedAt = Objects.requireNonNull(at);
        this.changeReasonCode = validateReason(reasonCode);
        validateCity();
    }

    private void validateCity() {
        Long cityId = supportedCity.getId();
        Long localityCityId = canonicalLocality.getSupportedCity() == null
                ? null : canonicalLocality.getSupportedCity().getId();
        Long teamCityId = operatingTeam.getCity() == null ? null : operatingTeam.getCity().getId();
        if (cityId == null || !cityId.equals(localityCityId) || !cityId.equals(teamCityId)) {
            throw new IllegalArgumentException("Coverage locality and Team must belong to one canonical City");
        }
    }

    private static String validateReason(String reasonCode) {
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("A bounded uppercase reason code is required");
        }
        return reasonCode;
    }

    public Long getId() { return id; }
    public SupportedCity getSupportedCity() { return supportedCity; }
    public Locality getCanonicalLocality() { return canonicalLocality; }
    public OperatingTeam getOperatingTeam() { return operatingTeam; }
    public boolean isActive() { return active; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public User getCreatedBy() { return createdBy; }
    public User getUpdatedBy() { return updatedBy; }
    public String getChangeReasonCode() { return changeReasonCode; }
}
