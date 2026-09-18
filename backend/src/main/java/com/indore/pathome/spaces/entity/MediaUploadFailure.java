package com.indore.pathome.spaces.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persistent record of a media upload failure.
 *
 * <p>A row is created for every upload attempt that does not reach a successful
 * Cloudinary URL + database save. Rows transition through
 * FAILED → RETRYING → RESOLVED (or DISMISSED).
 *
 * <p>The {@code internalDiagnostic} field contains only the exception class name
 * and message — never a stack trace, file path, or credential.
 */
@Entity
@Table(name = "media_upload_failures", indexes = {
    @Index(name = "idx_muf_listing_id", columnList = "listingId"),
    @Index(name = "idx_muf_status",     columnList = "status")
})
public class MediaUploadFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Idempotency key — mirrors the same field on PropertyMediaAsset. Null for validation-only failures. */
    @Column(name = "upload_request_id", length = 80, unique = true)
    private String uploadRequestId;

    /** Null when the failure happened during property creation before the listing was saved. */
    @Column(name = "listing_id")
    private Long listingId;

    @Column(name = "media_type", nullable = false, length = 30)
    private String mediaType = "IMAGE";

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename = "";

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "room_tag", length = 40)
    private String roomTag;

    /** VALIDATION / CLOUDINARY_UPLOAD / DB_PERSIST */
    @Column(name = "failure_stage", nullable = false, length = 60)
    private String failureStage;

    /** Safe, user-visible message — no internal details. */
    @Column(name = "failure_reason", nullable = false, length = 500)
    private String failureReason;

    /** ExceptionClass: message — never exposed in HTTP responses. */
    @JsonIgnore
    @Column(name = "internal_diagnostic", columnDefinition = "TEXT")
    private String internalDiagnostic;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /** FAILED / RETRYING / RESOLVED / DISMISSED */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "FAILED";

    /** Populated when status transitions to RESOLVED. */
    @Column(name = "resolved_media_url", length = 1000)
    private String resolvedMediaUrl;

    @Column(name = "storage_url", length = 1000)
    private String storageUrl;

    @Column(name = "storage_public_id", length = 200)
    private String storagePublicId;

    /** Private object key in temporary staging storage — never exposed to client. */
    @JsonIgnore
    @Column(name = "staging_object_key", length = 300)
    private String stagingObjectKey;

    @Column(name = "staging_expires_at")
    private LocalDateTime stagingExpiresAt;

    @Column(name = "cloudinary_failure_category", length = 50)
    private String cloudinaryFailureCategory;

    @Column(name = "provider_status_code")
    private Integer providerStatusCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public MediaUploadFailure() {}

    /**
     * Determines whether one-click automatic retry (without file picker) is available.
     */
    public boolean isAutoRetryAvailable() {
        if ("RESOLVED".equals(status) || "DISMISSED".equals(status)) {
            return false;
        }
        if ("DB_PERSIST".equals(failureStage)) {
            return storageUrl != null && !storageUrl.isBlank();
        }
        if ("CLOUDINARY_UPLOAD".equals(failureStage)) {
            if ("MEDIA_REJECTED".equals(cloudinaryFailureCategory)) {
                return false;
            }
            if (stagingObjectKey != null && !stagingObjectKey.isBlank()) {
                return stagingExpiresAt == null || stagingExpiresAt.isAfter(LocalDateTime.now());
            }
        }
        return false;
    }

    /**
     * Recovery strategy: DATABASE_RECONCILIATION, STAGED_MEDIA_RETRY, or REPLACE_FILE_REQUIRED.
     */
    public String getRecoveryStrategy() {
        if ("DB_PERSIST".equals(failureStage) && storageUrl != null && !storageUrl.isBlank()) {
            return "DATABASE_RECONCILIATION";
        }
        if (stagingObjectKey != null && !stagingObjectKey.isBlank()
                && !"MEDIA_REJECTED".equals(cloudinaryFailureCategory)
                && (stagingExpiresAt == null || stagingExpiresAt.isAfter(LocalDateTime.now()))) {
            return "STAGED_MEDIA_RETRY";
        }
        return "REPLACE_FILE_REQUIRED";
    }

    // ---- Getters and Setters ----

    public Long getId() { return id; }

    public String getUploadRequestId() { return uploadRequestId; }
    public void setUploadRequestId(String uploadRequestId) { this.uploadRequestId = uploadRequestId; }

    public Long getListingId() { return listingId; }
    public void setListingId(Long listingId) { this.listingId = listingId; }

    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getRoomTag() { return roomTag; }
    public void setRoomTag(String roomTag) { this.roomTag = roomTag; }

    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String failureStage) { this.failureStage = failureStage; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getInternalDiagnostic() { return internalDiagnostic; }
    public void setInternalDiagnostic(String internalDiagnostic) { this.internalDiagnostic = internalDiagnostic; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResolvedMediaUrl() { return resolvedMediaUrl; }
    public void setResolvedMediaUrl(String resolvedMediaUrl) { this.resolvedMediaUrl = resolvedMediaUrl; }

    public String getStorageUrl() { return storageUrl; }
    public void setStorageUrl(String storageUrl) { this.storageUrl = storageUrl; }

    public String getStoragePublicId() { return storagePublicId; }
    public void setStoragePublicId(String storagePublicId) { this.storagePublicId = storagePublicId; }

    public String getStagingObjectKey() { return stagingObjectKey; }
    public void setStagingObjectKey(String stagingObjectKey) { this.stagingObjectKey = stagingObjectKey; }

    public LocalDateTime getStagingExpiresAt() { return stagingExpiresAt; }
    public void setStagingExpiresAt(LocalDateTime stagingExpiresAt) { this.stagingExpiresAt = stagingExpiresAt; }

    public String getCloudinaryFailureCategory() { return cloudinaryFailureCategory; }
    public void setCloudinaryFailureCategory(String cloudinaryFailureCategory) { this.cloudinaryFailureCategory = cloudinaryFailureCategory; }

    public Integer getProviderStatusCode() { return providerStatusCode; }
    public void setProviderStatusCode(Integer providerStatusCode) { this.providerStatusCode = providerStatusCode; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
