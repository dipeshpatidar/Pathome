package com.indore.pathome.spaces.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ParsedPropertyDTO {
    private String bhk;
    private String type;
    private String status = "LIVE";
    private String city;
    private String sector;
    private String colony;
    private String rentVal;
    private Double rentAmount;
    private String brokerageVal;
    private String brokerageDays;
    private String areaSqFt;
    private String depositVal;
    private String bathrooms;
    private String furnishingStatus;
    private String possessionDate;
    private AvailabilityStatus availabilityStatus = AvailabilityStatus.UNSPECIFIED;
    private LocalDate availableFrom;
    private String address;
    private String state;
    private String pincode;
    private String landmark;
    private String description;
    private String ownerName;
    private String ownerPhone;
    private String vastuFacing;
    private List<String> amenities = new ArrayList<>();
    private List<String> missingFields = new ArrayList<>();
    private String title;
    private String label;
    private boolean savedToDatabase;
    private List<String> mediaUrls = new ArrayList<>();
    private int promptIndex = 1;
    private String rawPrompt;
    private boolean requiresReview;
    private boolean adminVerified;
    private List<String> conflicts = new ArrayList<>();
    private Map<String, String> sourceSnippets = new LinkedHashMap<>();
    private List<String> appliedAmendments = new ArrayList<>();
    private String learningExampleId;

    public ParsedPropertyDTO() {
    }

    public ParsedPropertyDTO(String bhk, String type, String city, String sector, String colony, String rentVal, Double rentAmount, String vastuFacing, List<String> amenities, String title, String label, boolean savedToDatabase) {
        this.bhk = bhk;
        this.type = type;
        this.city = city;
        this.sector = sector;
        this.colony = colony;
        this.rentVal = rentVal;
        this.rentAmount = rentAmount;
        this.vastuFacing = vastuFacing;
        this.amenities = amenities;
        this.title = title;
        this.label = label;
        this.savedToDatabase = savedToDatabase;
    }

    public ParsedPropertyDTO(String bhk, String type, String city, String sector, String colony, String rentVal, Double rentAmount, String brokerageVal, String areaSqFt, String depositVal, String ownerName, String ownerPhone, String vastuFacing, List<String> amenities, String title, String label, boolean savedToDatabase) {
        this.bhk = bhk;
        this.type = type;
        this.city = city;
        this.sector = sector;
        this.colony = colony;
        this.rentVal = rentVal;
        this.rentAmount = rentAmount;
        this.brokerageVal = brokerageVal;
        this.areaSqFt = areaSqFt;
        this.depositVal = depositVal;
        this.ownerName = ownerName;
        this.ownerPhone = ownerPhone;
        this.vastuFacing = vastuFacing;
        this.amenities = amenities;
        this.title = title;
        this.label = label;
        this.savedToDatabase = savedToDatabase;
    }

    public String getBhk() {
        return bhk;
    }

    public void setBhk(String bhk) {
        this.bhk = bhk;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getSector() {
        return sector;
    }

    public void setSector(String sector) {
        this.sector = sector;
    }

    public String getColony() {
        return colony;
    }

    public void setColony(String colony) {
        this.colony = colony;
    }

    public String getRentVal() {
        return rentVal;
    }

    public void setRentVal(String rentVal) {
        this.rentVal = rentVal;
    }

    public Double getRentAmount() {
        return rentAmount;
    }

    public void setRentAmount(Double rentAmount) {
        this.rentAmount = rentAmount;
    }

    public String getVastuFacing() {
        return vastuFacing;
    }

    public void setVastuFacing(String vastuFacing) {
        this.vastuFacing = vastuFacing;
    }

    public List<String> getAmenities() {
        return amenities;
    }

    public void setAmenities(List<String> amenities) {
        this.amenities = amenities;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getBrokerageVal() {
        return brokerageVal;
    }

    public void setBrokerageVal(String brokerageVal) {
        this.brokerageVal = brokerageVal;
    }

    public String getAreaSqFt() {
        return areaSqFt;
    }

    public void setAreaSqFt(String areaSqFt) {
        this.areaSqFt = areaSqFt;
    }

    public String getDepositVal() {
        return depositVal;
    }

    public void setDepositVal(String depositVal) {
        this.depositVal = depositVal;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getOwnerPhone() {
        return ownerPhone;
    }

    public void setOwnerPhone(String ownerPhone) {
        this.ownerPhone = ownerPhone;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getBrokerageDays() {
        return brokerageDays;
    }

    public void setBrokerageDays(String brokerageDays) {
        this.brokerageDays = brokerageDays;
    }

    public String getBathrooms() {
        return bathrooms;
    }

    public void setBathrooms(String bathrooms) {
        this.bathrooms = bathrooms;
    }

    public String getFurnishingStatus() {
        return furnishingStatus;
    }

    public void setFurnishingStatus(String furnishingStatus) {
        this.furnishingStatus = furnishingStatus;
    }

    public String getPossessionDate() {
        return possessionDate;
    }

    public void setPossessionDate(String possessionDate) {
        this.possessionDate = possessionDate;
    }

    public AvailabilityStatus getAvailabilityStatus() {
        return availabilityStatus;
    }

    public void setAvailabilityStatus(AvailabilityStatus availabilityStatus) {
        this.availabilityStatus = availabilityStatus != null
                ? availabilityStatus
                : AvailabilityStatus.UNSPECIFIED;
    }

    public LocalDate getAvailableFrom() {
        return availableFrom;
    }

    public void setAvailableFrom(LocalDate availableFrom) {
        this.availableFrom = availableFrom;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPincode() {
        return pincode;
    }

    public void setPincode(String pincode) {
        this.pincode = pincode;
    }

    public String getLandmark() {
        return landmark;
    }

    public void setLandmark(String landmark) {
        this.landmark = landmark;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getMissingFields() {
        return missingFields;
    }

    public void setMissingFields(List<String> missingFields) {
        this.missingFields = missingFields;
    }

    public boolean isSavedToDatabase() {
        return savedToDatabase;
    }

    public void setSavedToDatabase(boolean savedToDatabase) {
        this.savedToDatabase = savedToDatabase;
    }

    public List<String> getMediaUrls() {
        return mediaUrls;
    }

    public void setMediaUrls(List<String> mediaUrls) {
        this.mediaUrls = mediaUrls != null ? mediaUrls : new ArrayList<>();
    }

    public int getPromptIndex() {
        return promptIndex;
    }

    public void setPromptIndex(int promptIndex) {
        this.promptIndex = promptIndex;
    }

    public String getRawPrompt() {
        return rawPrompt;
    }

    public void setRawPrompt(String rawPrompt) {
        this.rawPrompt = rawPrompt;
    }

    public boolean isRequiresReview() {
        return requiresReview;
    }

    public void setRequiresReview(boolean requiresReview) {
        this.requiresReview = requiresReview;
    }

    public boolean isAdminVerified() {
        return adminVerified;
    }

    public void setAdminVerified(boolean adminVerified) {
        this.adminVerified = adminVerified;
    }

    public List<String> getConflicts() {
        return conflicts;
    }

    public void setConflicts(List<String> conflicts) {
        this.conflicts = conflicts != null ? conflicts : new ArrayList<>();
    }

    public Map<String, String> getSourceSnippets() {
        return sourceSnippets;
    }

    public void setSourceSnippets(Map<String, String> sourceSnippets) {
        this.sourceSnippets = sourceSnippets != null ? sourceSnippets : new LinkedHashMap<>();
    }

    public List<String> getAppliedAmendments() {
        return appliedAmendments;
    }

    public void setAppliedAmendments(List<String> appliedAmendments) {
        this.appliedAmendments = appliedAmendments != null ? appliedAmendments : new ArrayList<>();
    }

    public String getLearningExampleId() {
        return learningExampleId;
    }

    public void setLearningExampleId(String learningExampleId) {
        this.learningExampleId = learningExampleId;
    }
}
