package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "listings", indexes = {
    @Index(name = "idx_listing_status_sector", columnList = "status, sector"),
    @Index(name = "idx_listing_status_type", columnList = "status, listing_type"),
    @Index(name = "idx_listing_bhk", columnList = "status, bhk_count"),
    @Index(name = "idx_listing_owner_phone", columnList = "owner_phone_number"),
    @Index(name = "idx_listing_origin_draft_id", columnList = "origin_draft_id", unique = true)
})
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "listing_category", discriminatorType = DiscriminatorType.STRING)
public abstract class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "listing_type", nullable = false)
    private ListingType listingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false)
    private PropertyType propertyType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ListingStatus status = ListingStatus.ACTIVE;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String sector; // e.g. Vijay Nagar, Bhawarkua

    @Column(nullable = false)
    private String city = "Indore";

    @Column(name = "bhk_count", nullable = false)
    private String bhkCount;

    @Column(name = "furnishing_status")
    private String furnishingStatus;

    @Column(name = "vastu_facing")
    private String vastuFacing;

    @Column(columnDefinition = "TEXT")
    private String amenities;

    @Column(name = "total_area_sq_ft")
    private Double totalAreaSqFt;

    @Column(name = "media_gallery_urls", columnDefinition = "TEXT")
    private String mediaGalleryUrls;

    @Column(name = "owner_phone_number", nullable = false)
    private String ownerPhoneNumber;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "bathroom_count")
    private Integer bathroomCount;

    @Column(name = "colony")
    private String colony;

    @Column(name = "state")
    private String state;

    @Column(name = "pincode")
    private String pincode;

    @Column(name = "landmark")
    private String landmark;

    @Column(name = "possession_date_text")
    private String possessionDateText;

    @Column(name = "origin_draft_id", length = 64)
    private String originDraftId;

    @Column(name = "floor_number")
    private Integer floorNumber;

    @Column(name = "total_floors")
    private Integer totalFloors;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Listing() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ListingType getListingType() { return listingType; }
    public void setListingType(ListingType listingType) { this.listingType = listingType; }

    public PropertyType getPropertyType() { return propertyType; }
    public void setPropertyType(PropertyType propertyType) { this.propertyType = propertyType; }

    public ListingStatus getStatus() { return status; }
    public void setStatus(ListingStatus status) { this.status = status; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getSector() { return sector; }
    public void setSector(String sector) { this.sector = sector; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getBhkCount() { return bhkCount; }
    public void setBhkCount(String bhkCount) { this.bhkCount = bhkCount; }

    public String getFurnishingStatus() { return furnishingStatus; }
    public void setFurnishingStatus(String furnishingStatus) { this.furnishingStatus = furnishingStatus; }

    public String getVastuFacing() { return vastuFacing; }
    public void setVastuFacing(String vastuFacing) { this.vastuFacing = vastuFacing; }

    public String getAmenities() { return amenities; }
    public void setAmenities(String amenities) { this.amenities = amenities; }

    public Double getTotalAreaSqFt() { return totalAreaSqFt; }
    public void setTotalAreaSqFt(Double totalAreaSqFt) { this.totalAreaSqFt = totalAreaSqFt; }

    public String getMediaGalleryUrls() { return mediaGalleryUrls; }
    public void setMediaGalleryUrls(String mediaGalleryUrls) { this.mediaGalleryUrls = mediaGalleryUrls; }

    public String getOwnerPhoneNumber() { return ownerPhoneNumber; }
    public void setOwnerPhoneNumber(String ownerPhoneNumber) { this.ownerPhoneNumber = ownerPhoneNumber; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public Integer getBathroomCount() { return bathroomCount; }
    public void setBathroomCount(Integer bathroomCount) { this.bathroomCount = bathroomCount; }

    public String getColony() { return colony; }
    public void setColony(String colony) { this.colony = colony; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }

    public String getLandmark() { return landmark; }
    public void setLandmark(String landmark) { this.landmark = landmark; }

    public String getPossessionDateText() { return possessionDateText; }
    public void setPossessionDateText(String possessionDateText) { this.possessionDateText = possessionDateText; }

    public String getOriginDraftId() { return originDraftId; }
    public void setOriginDraftId(String originDraftId) { this.originDraftId = originDraftId; }

    public Integer getFloorNumber() { return floorNumber; }
    public void setFloorNumber(Integer floorNumber) { this.floorNumber = floorNumber; }

    public Integer getTotalFloors() { return totalFloors; }
    public void setTotalFloors(Integer totalFloors) { this.totalFloors = totalFloors; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
