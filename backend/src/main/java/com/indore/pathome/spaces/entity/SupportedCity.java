package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Canonical persisted city identity. Package 1C must first establish operational request/session scope and ownership,
 * then add an expected-version check and exclusive fresh drain check over the frozen blocking statuses before
 * exposing deactivation. This entity's active flag alone is not a completed drain guard; no force-deactivation path
 * is permitted.
 */
@Entity
@Table(name = "supported_cities")
public class SupportedCity {
    private static final Pattern CANONICAL_CODE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false, length = 64)
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

    protected SupportedCity() {}

    public SupportedCity(String code, String displayName, boolean active) {
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
        if (code == null || !CANONICAL_CODE.matcher(code).matches()) {
            throw new IllegalStateException("Supported city code must be a canonical lowercase slug");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalStateException("Supported city display name is required");
        }
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
