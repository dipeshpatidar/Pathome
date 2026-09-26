package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.ListingType;
import com.indore.pathome.spaces.entity.PropertyType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Explicit discovery projection. Do not add owner, address, coordinate, or operational fields here.
 */
public record PublicPropertyResponse(
        Long id,
        String title,
        String description,
        ListingType listingType,
        PropertyType propertyType,
        String city,
        String sector,
        String bhk,
        String furnishingStatus,
        String vastuFacing,
        String amenities,
        Double totalAreaSqFt,
        Integer bathroomCount,
        Integer floorNumber,
        Integer totalFloors,
        BigDecimal monthlyRent,
        BigDecimal securityDeposit,
        BigDecimal maintenanceCharge,
        Boolean bachelorAllowed,
        String preferredTenant,
        LocalDateTime availableFrom,
        List<PublicPropertyMediaResponse> media
) {}
