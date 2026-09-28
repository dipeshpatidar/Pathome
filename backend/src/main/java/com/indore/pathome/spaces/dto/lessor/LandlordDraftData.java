package com.indore.pathome.spaces.dto.lessor;

import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.RentalMode;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LandlordDraftData(
        Basics basics,
        Pricing pricing,
        Location location,
        Details details
) {
    public record Basics(PropertyType propertyType, RentalMode rentalMode, String bhkCount) {}
    public record Pricing(BigDecimal monthlyRent, BigDecimal securityDeposit) {}
    public record Location(String city, Long canonicalLocalityId, String localityInput,
                           String address, String landmark) {}
    public record Details(LocalDate availableFrom, String furnishingStatus, Double totalAreaSqFt,
                          Integer floorNumber, Integer totalFloors, String amenities, String description) {}
}
