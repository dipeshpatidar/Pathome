package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persistent record of an unfinished property upload draft (Single or Batch).
 */
@Entity
@Table(name = "property_upload_drafts", indexes = {
    @Index(name = "idx_pud_admin_updated", columnList = "admin_id, updated_at"),
    @Index(name = "idx_pud_draft_id", columnList = "draft_id"),
    @Index(name = "idx_pud_status", columnList = "status"),
    @Index(name = "idx_pud_published_property_id", columnList = "published_property_id")
})
public class PropertyUploadDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draft_id", nullable = false, unique = true, length = 64)
    private String draftId;

    @Column(name = "admin_id", length = 120)
    private String adminId;

    @Column(name = "landlord_user_id")
    private Long landlordUserId;

    @Column(name = "draft_type", nullable = false, length = 30)
    private String draftType = "SINGLE"; // SINGLE or BATCH

    @Column(name = "status", nullable = false, length = 30)
    private String status = "DRAFT"; // DRAFT, REVIEW, PUBLISHING, PUBLISHED, DISCARDED

    @Column(name = "title_summary", length = 255)
    private String titleSummary;

    @Column(name = "item_count", nullable = false)
    private Integer itemCount = 1;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "published_property_id")
    private Long publishedPropertyId;

    @Column(name = "revision_base_version")
    private Long revisionBaseVersion;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public PropertyUploadDraft() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDraftId() { return draftId; }
    public void setDraftId(String draftId) { this.draftId = draftId; }

    public String getAdminId() { return adminId; }
    public void setAdminId(String adminId) { this.adminId = adminId; }

    public Long getLandlordUserId() { return landlordUserId; }
    public void setLandlordUserId(Long landlordUserId) { this.landlordUserId = landlordUserId; }

    public String getDraftType() { return draftType; }
    public void setDraftType(String draftType) { this.draftType = draftType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTitleSummary() { return titleSummary; }
    public void setTitleSummary(String titleSummary) { this.titleSummary = titleSummary; }

    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Long getPublishedPropertyId() { return publishedPropertyId; }
    public void setPublishedPropertyId(Long publishedPropertyId) { this.publishedPropertyId = publishedPropertyId; }

    public Long getRevisionBaseVersion() { return revisionBaseVersion; }
    public void setRevisionBaseVersion(Long revisionBaseVersion) { this.revisionBaseVersion = revisionBaseVersion; }

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (version == null) version = 1;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
