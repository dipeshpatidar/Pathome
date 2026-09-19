package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "media_upload_claims",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_media_upload_claim", columnNames = {"listing_id", "upload_request_id"})
    },
    indexes = {
        @Index(name = "idx_media_upload_claims_expiry", columnList = "status, expires_at")
    }
)
public class MediaUploadClaim {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(name = "upload_request_id", nullable = false, length = 80)
    private String uploadRequestId;

    @Column(name = "owner_token", nullable = false, length = 64)
    private String ownerToken;

    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_IN_PROGRESS;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public MediaUploadClaim() {}

    public MediaUploadClaim(Long listingId, String uploadRequestId, String ownerToken, Instant expiresAt) {
        this.listingId = listingId;
        this.uploadRequestId = uploadRequestId;
        this.ownerToken = ownerToken;
        this.status = STATUS_IN_PROGRESS;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public String getUploadRequestId() {
        return uploadRequestId;
    }

    public void setUploadRequestId(String uploadRequestId) {
        this.uploadRequestId = uploadRequestId;
    }

    public String getOwnerToken() {
        return ownerToken;
    }

    public void setOwnerToken(String ownerToken) {
        this.ownerToken = ownerToken;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
