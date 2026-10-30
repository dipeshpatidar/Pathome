package com.indore.pathome.spaces.dto.backoffice;

import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.RoomTag;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Typed staff-safe content contract. Canonical location, ownership, lifecycle and provider facts
 * deliberately do not appear in this record and therefore cannot be mass-assigned through it.
 */
public record StaffPropertySnapshot(
        String title,
        String titleProvenance,
        String description,
        PropertyType propertyType,
        String bhkCount,
        BigDecimal monthlyRent,
        BigDecimal securityDeposit,
        LocalDate availableFrom,
        BigDecimal maintenanceCharge,
        BigDecimal brokerageAmount,
        Integer brokerageDays,
        Integer securityDepositMonths,
        Double totalAreaSqFt,
        Integer floorNumber,
        Integer totalFloors,
        String furnishingStatus,
        String amenities,
        Integer bathroomCount,
        String vastuFacing,
        Boolean bachelorAllowed,
        String preferredTenant,
        String address,
        String landmark,
        String colony,
        String pincode,
        List<Media> media
) {
    public StaffPropertySnapshot {
        titleProvenance = titleProvenance == null ? "LEGACY_UNKNOWN" : titleProvenance;
    }

    public record Media(
            Long sourceMediaAssetId,
            String mediaId,
            String url,
            MediaType mediaType,
            RoomTag roomTag,
            String caption,
            Integer sortOrder,
            boolean cover,
            String uploadStatus
    ) {}
}

