package com.indore.pathome.spaces.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Single CRM Lead authority. The historical table and identifiers are retained deliberately; the
 * pre-existing fields stay readable, while new facts use explicit unknown/nullable values rather than
 * fabricated defaults.
 */
@Entity
@Table(name = "lead_routing_queue", indexes = {
    @Index(name = "idx_lead_status_sector", columnList = "status, targetSector"),
    @Index(name = "idx_lead_employee", columnList = "assigned_employee_id")
})
public class LeadRoutingQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "tenant_name")
    private String tenantName;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "target_sector")
    private String targetSector;

    @Column(name = "budget")
    private BigDecimal budget;

    @Column(name = "contact_phone_normalized", length = 16)
    private String contactPhoneNormalized;

    @Column(name = "customer_said", columnDefinition = "text")
    private String customerSaid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private LeadStatus status = LeadStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LeadIntent intent = LeadIntent.UNSPECIFIED;

    @Column(name = "property_type", length = 32)
    private String propertyType;

    @Column(length = 32)
    private String bhk;

    @Column(name = "budget_min", precision = 14, scale = 2)
    private BigDecimal budgetMin;

    @Column(name = "budget_max", precision = 14, scale = 2)
    private BigDecimal budgetMax;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "target_move_in_date")
    private LocalDate targetMoveInDate;

    @Column(length = 40)
    private String furnishing;

    @Column(name = "tenant_profile", length = 64)
    private String tenantProfile;

    @Column(name = "parking_required")
    private Boolean parkingRequired;

    @Column(name = "pets_allowed")
    private Boolean petsAllowed;

    @Column(name = "lift_required")
    private Boolean liftRequired;

    @Column(name = "floor_requirement", length = 80)
    private String floorRequirement;

    @Column(name = "follow_up_at", columnDefinition = "timestamptz")
    private Instant followUpAt;

    @Column(name = "follow_up_zone_id", length = 64)
    private String followUpZoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "legacy_provenance", nullable = false, length = 32)
    private LeadLegacyProvenance legacyProvenance = LeadLegacyProvenance.VERIFIED_MANUAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supported_city_id")
    private SupportedCity supportedCity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operating_team_id")
    private OperatingTeam operatingTeam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_user_id")
    private User linkedUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_employee_id")
    private EmployeeProfile assignedEmployee;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public LeadRoutingQueue() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVersion() { return version; }

    /** The CRM-facing name; the column remains tenant_name for backward-compatible history. */
    public String getCustomerName() { return tenantName; }
    public void setCustomerName(String customerName) { this.tenantName = customerName; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getTargetSector() { return targetSector; }
    public void setTargetSector(String targetSector) { this.targetSector = targetSector; }
    public BigDecimal getBudget() { return budget; }
    public void setBudget(BigDecimal budget) { this.budget = budget; }
    public String getContactPhoneNormalized() { return contactPhoneNormalized; }
    public void setContactPhoneNormalized(String value) { this.contactPhoneNormalized = value; }
    public String getCustomerSaid() { return customerSaid; }
    public void setCustomerSaid(String customerSaid) { this.customerSaid = customerSaid; }
    public LeadStatus getStatus() { return status; }
    public void setStatus(LeadStatus status) { this.status = status; }
    public LeadIntent getIntent() { return intent; }
    public void setIntent(LeadIntent intent) { this.intent = intent; }
    public String getPropertyType() { return propertyType; }
    public void setPropertyType(String propertyType) { this.propertyType = propertyType; }
    public String getBhk() { return bhk; }
    public void setBhk(String bhk) { this.bhk = bhk; }
    public BigDecimal getBudgetMin() { return budgetMin; }
    public void setBudgetMin(BigDecimal budgetMin) { this.budgetMin = budgetMin; }
    public BigDecimal getBudgetMax() { return budgetMax; }
    public void setBudgetMax(BigDecimal budgetMax) { this.budgetMax = budgetMax; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDate getTargetMoveInDate() { return targetMoveInDate; }
    public void setTargetMoveInDate(LocalDate targetMoveInDate) { this.targetMoveInDate = targetMoveInDate; }
    public String getFurnishing() { return furnishing; }
    public void setFurnishing(String furnishing) { this.furnishing = furnishing; }
    public String getTenantProfile() { return tenantProfile; }
    public void setTenantProfile(String tenantProfile) { this.tenantProfile = tenantProfile; }
    public Boolean getParkingRequired() { return parkingRequired; }
    public void setParkingRequired(Boolean parkingRequired) { this.parkingRequired = parkingRequired; }
    public Boolean getPetsAllowed() { return petsAllowed; }
    public void setPetsAllowed(Boolean petsAllowed) { this.petsAllowed = petsAllowed; }
    public Boolean getLiftRequired() { return liftRequired; }
    public void setLiftRequired(Boolean liftRequired) { this.liftRequired = liftRequired; }
    public String getFloorRequirement() { return floorRequirement; }
    public void setFloorRequirement(String floorRequirement) { this.floorRequirement = floorRequirement; }
    public Instant getFollowUpAt() { return followUpAt; }
    public void setFollowUpAt(Instant followUpAt) { this.followUpAt = followUpAt; }
    public String getFollowUpZoneId() { return followUpZoneId; }
    public void setFollowUpZoneId(String followUpZoneId) { this.followUpZoneId = followUpZoneId; }
    public LeadLegacyProvenance getLegacyProvenance() { return legacyProvenance; }
    public void setLegacyProvenance(LeadLegacyProvenance value) { this.legacyProvenance = value; }
    public SupportedCity getSupportedCity() { return supportedCity; }
    public void setSupportedCity(SupportedCity supportedCity) { this.supportedCity = supportedCity; }
    public OperatingTeam getOperatingTeam() { return operatingTeam; }
    public void setOperatingTeam(OperatingTeam operatingTeam) { this.operatingTeam = operatingTeam; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public User getLinkedUser() { return linkedUser; }
    public void setLinkedUser(User linkedUser) { this.linkedUser = linkedUser; }
    public EmployeeProfile getAssignedEmployee() { return assignedEmployee; }
    public void setAssignedEmployee(EmployeeProfile assignedEmployee) { this.assignedEmployee = assignedEmployee; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** Marks aggregate-version change for CRM child commands that must invalidate stale workspaces. */
    public void touchCrmState() {
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
