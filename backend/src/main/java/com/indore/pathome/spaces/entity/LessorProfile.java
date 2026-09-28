package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * First-class supply-side lessor identity.
 *
 * <p>Represents a lessor independently of a login User account.
 * For self-service users, linkedUserId references their User record.
 * For internally sourced lessors (field team, CRM, admin, partner, import),
 * linkedUserId remains null until an authorized claiming process links it.</p>
 */
@Entity
@Table(name = "lessor_profiles", indexes = {
    @Index(name = "idx_lessor_profiles_linked_user", columnList = "linked_user_id"),
    @Index(name = "idx_lessor_profiles_mobile", columnList = "mobile_number"),
    @Index(name = "idx_lessor_profiles_source", columnList = "source_type")
})
public class LessorProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "linked_user_id")
    private Long linkedUserId;

    @Column(name = "display_name", length = 150)
    private String displayName;

    @Column(name = "mobile_number", length = 30)
    private String mobileNumber;

    @Column(name = "email", length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    private LessorSourceType sourceType = LessorSourceType.SELF_SERVICE;

    @Column(name = "source_reference", length = 255)
    private String sourceReference;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public LessorProfile() {}

    public LessorProfile(Long id, Long linkedUserId, String displayName, String mobileNumber,
                         String email, LessorSourceType sourceType) {
        this.id = id;
        this.linkedUserId = linkedUserId;
        this.displayName = displayName;
        this.mobileNumber = mobileNumber;
        this.email = email;
        this.sourceType = sourceType != null ? sourceType : LessorSourceType.SELF_SERVICE;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getLinkedUserId() { return linkedUserId; }
    public void setLinkedUserId(Long linkedUserId) { this.linkedUserId = linkedUserId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public LessorSourceType getSourceType() { return sourceType; }
    public void setSourceType(LessorSourceType sourceType) { this.sourceType = sourceType; }

    public String getSourceReference() { return sourceReference; }
    public void setSourceReference(String sourceReference) { this.sourceReference = sourceReference; }

    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }

    public LocalDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(LocalDateTime claimedAt) { this.claimedAt = claimedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
