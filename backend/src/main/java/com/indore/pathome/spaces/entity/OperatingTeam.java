package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.regex.Pattern;

/** City-bound operating scope; Package 1C must add its exclusive fresh operational-case drain guard before deactivation. */
@Entity
@Table(name = "operating_teams", uniqueConstraints = {
        @UniqueConstraint(name = "uk_operating_teams_city_code", columnNames = {"city_id", "code"})
})
public class OperatingTeam {
    private static final Pattern CANONICAL_CODE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false, updatable = false)
    private SupportedCity city;

    @Column(nullable = false, updatable = false, length = 64)
    private String code;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected OperatingTeam() {}

    public OperatingTeam(SupportedCity city, String code, String displayName, boolean active) {
        this.city = city;
        this.code = code;
        this.displayName = displayName;
        this.active = active;
        validate();
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
        if (city == null) throw new IllegalStateException("Operating team must belong to a supported city");
        if (code == null || !CANONICAL_CODE.matcher(code).matches()) {
            throw new IllegalStateException("Operating team code must be a canonical lowercase slug");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalStateException("Operating team display name is required");
        }
    }

    public Long getId() { return id; }
    public SupportedCity getCity() { return city; }
    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
