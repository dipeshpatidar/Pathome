package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_email", columnList = "email"),
    @Index(name = "idx_user_phone", columnList = "phone_number"),
    @Index(name = "idx_user_role", columnList = "role")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String email;

    @Column
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "phone_number")
    private String phoneNumber;

    /** Verified account login identity; legacy phoneNumber remains contact data. */
    @Column(name = "mobile_number_normalized", length = 16)
    private String mobileNumberNormalized;

    @Column(name = "mobile_verified_at", columnDefinition = "timestamp with time zone")
    private OffsetDateTime mobileVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.ROLE_TENANT;

    @Column(name = "google_sub")
    private String googleSub;

    @Column(name = "free_visits_remaining", nullable = false)
    private Integer freeVisitsRemaining = 5;

    @Column(name = "landlord_activated_at")
    private LocalDateTime landlordActivatedAt;

    @Column(name = "landlord_activated_by_user_id")
    private Long landlordActivatedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public User() {}

    public User(Long id, String email, String passwordHash, String fullName, String phoneNumber, Role role, String googleSub, Integer freeVisitsRemaining) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
        this.role = role;
        this.googleSub = googleSub;
        this.freeVisitsRemaining = freeVisitsRemaining != null ? freeVisitsRemaining : 5;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getMobileNumberNormalized() { return mobileNumberNormalized; }
    public void setMobileNumberNormalized(String mobileNumberNormalized) { this.mobileNumberNormalized = mobileNumberNormalized; }

    public OffsetDateTime getMobileVerifiedAt() { return mobileVerifiedAt; }
    public void setMobileVerifiedAt(OffsetDateTime mobileVerifiedAt) { this.mobileVerifiedAt = mobileVerifiedAt; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getGoogleSub() { return googleSub; }
    public void setGoogleSub(String googleSub) { this.googleSub = googleSub; }

    public Integer getFreeVisitsRemaining() { return freeVisitsRemaining; }
    public void setFreeVisitsRemaining(Integer freeVisitsRemaining) { this.freeVisitsRemaining = freeVisitsRemaining; }

    public LocalDateTime getLandlordActivatedAt() { return landlordActivatedAt; }
    public void setLandlordActivatedAt(LocalDateTime landlordActivatedAt) { this.landlordActivatedAt = landlordActivatedAt; }

    public Long getLandlordActivatedByUserId() { return landlordActivatedByUserId; }
    public void setLandlordActivatedByUserId(Long landlordActivatedByUserId) { this.landlordActivatedByUserId = landlordActivatedByUserId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
