package com.indore.pathome.spaces.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "property_media_assets", indexes = {
    @Index(name = "idx_media_listing_id", columnList = "listingId"),
    @Index(name = "idx_media_sector", columnList = "sector"),
    @Index(name = "idx_media_listing_upload_request", columnList = "listingId, uploadRequestId", unique = true)
})
public class PropertyMediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long listingId;

    @Column(nullable = false, length = 1000)
    private String mediaUrl;

    private String cloudinaryPublicId;

    @Column(length = 80)
    private String uploadRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaType mediaType = MediaType.IMAGE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomTag roomTag = RoomTag.GENERAL;

    private String caption;

    private Boolean isPrimaryCover = false;

    private String sector;

    private String city;

    private String priceTag;

    private Double latitude;

    private Double longitude;

    private String vastuFacing;

    private String verificationStatus;

    private LocalDateTime uploadedAt = LocalDateTime.now();

    public PropertyMediaAsset() {}

    public PropertyMediaAsset(Long listingId, String mediaUrl, MediaType mediaType, RoomTag roomTag, String caption) {
        this.listingId = listingId;
        this.mediaUrl = mediaUrl;
        this.mediaType = mediaType;
        this.roomTag = roomTag;
        this.caption = caption;
    }

    // Getters and Setters
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

    public String getMediaUrl() {
        return mediaUrl;
    }

    public void setMediaUrl(String mediaUrl) {
        this.mediaUrl = mediaUrl;
    }

    public String getCloudinaryPublicId() {
        return cloudinaryPublicId;
    }

    public void setCloudinaryPublicId(String cloudinaryPublicId) {
        this.cloudinaryPublicId = cloudinaryPublicId;
    }

    public String getUploadRequestId() {
        return uploadRequestId;
    }

    public void setUploadRequestId(String uploadRequestId) {
        this.uploadRequestId = uploadRequestId;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public void setMediaType(MediaType mediaType) {
        this.mediaType = mediaType;
    }

    public RoomTag getRoomTag() {
        return roomTag;
    }

    public void setRoomTag(RoomTag roomTag) {
        this.roomTag = roomTag;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public Boolean getIsPrimaryCover() {
        return isPrimaryCover;
    }

    public void setIsPrimaryCover(Boolean isPrimaryCover) {
        this.isPrimaryCover = isPrimaryCover;
    }

    public String getSector() {
        return sector;
    }

    public void setSector(String sector) {
        this.sector = sector;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getPriceTag() {
        return priceTag;
    }

    public void setPriceTag(String priceTag) {
        this.priceTag = priceTag;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getVastuFacing() {
        return vastuFacing;
    }

    public void setVastuFacing(String vastuFacing) {
        this.vastuFacing = vastuFacing;
    }

    public String getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(String verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
