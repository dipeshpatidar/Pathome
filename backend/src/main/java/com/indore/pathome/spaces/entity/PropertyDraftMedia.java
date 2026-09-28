package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persistent record of a staged media file associated with a property upload draft.
 */
@Entity
@Table(name = "property_draft_media", indexes = {
    @Index(name = "idx_pdm_draft_id", columnList = "draft_id"),
    @Index(name = "idx_pdm_admin_id", columnList = "admin_id"),
    @Index(name = "idx_pdm_staging_key", columnList = "staging_object_key")
})
public class PropertyDraftMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "media_id", nullable = false, unique = true, length = 64)
    private String mediaId;

    @Column(name = "draft_id", nullable = false, length = 64)
    private String draftId;

    @Column(name = "admin_id", length = 120)
    private String adminId;

    @Column(name = "landlord_user_id")
    private Long landlordUserId;

    @Column(name = "card_id", length = 64)
    private String cardId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename = "";

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes = 0L;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType = "image/jpeg";

    @Column(name = "staging_object_key", length = 300)
    private String stagingObjectKey;

    @Column(name = "cloudinary_url", length = 1000)
    private String cloudinaryUrl;

    @Column(name = "cloudinary_public_id", length = 300)
    private String cloudinaryPublicId;

    @Column(name = "upload_status", length = 20)
    private String uploadStatus;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "room_tag", length = 40)
    private String roomTag;

    @Column(name = "is_cover", nullable = false)
    private Boolean isCover = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public PropertyDraftMedia() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMediaId() { return mediaId; }
    public void setMediaId(String mediaId) { this.mediaId = mediaId; }

    public String getDraftId() { return draftId; }
    public void setDraftId(String draftId) { this.draftId = draftId; }

    public String getAdminId() { return adminId; }
    public void setAdminId(String adminId) { this.adminId = adminId; }

    public Long getLandlordUserId() { return landlordUserId; }
    public void setLandlordUserId(Long landlordUserId) { this.landlordUserId = landlordUserId; }

    public String getCardId() { return cardId; }
    public void setCardId(String cardId) { this.cardId = cardId; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename != null ? originalFilename : ""; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes != null ? fileSizeBytes : 0L; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType != null ? contentType : "image/jpeg"; }

    public String getStagingObjectKey() { return stagingObjectKey; }
    public void setStagingObjectKey(String stagingObjectKey) { this.stagingObjectKey = stagingObjectKey; }

    public String getCloudinaryUrl() { return cloudinaryUrl; }
    public void setCloudinaryUrl(String cloudinaryUrl) { this.cloudinaryUrl = cloudinaryUrl; }

    public String getCloudinaryPublicId() { return cloudinaryPublicId; }
    public void setCloudinaryPublicId(String cloudinaryPublicId) { this.cloudinaryPublicId = cloudinaryPublicId; }

    public String getUploadStatus() { return uploadStatus; }
    public void setUploadStatus(String uploadStatus) { this.uploadStatus = uploadStatus; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getRoomTag() { return roomTag; }
    public void setRoomTag(String roomTag) { this.roomTag = roomTag; }

    public Boolean getIsCover() { return isCover; }
    public void setIsCover(Boolean isCover) { this.isCover = isCover != null ? isCover : false; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (isCover == null) isCover = false;
    }
}
