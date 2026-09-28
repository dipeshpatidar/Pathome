package com.indore.pathome.spaces.dto.lessor;

import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.RentalMode;
import com.indore.pathome.spaces.entity.LocationResolution;

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
                           String address, String landmark, LocationResolution resolutionType,
                           String provider, String providerPlaceId, String selectionToken) {
        public Location(String city, Long canonicalLocalityId, String localityInput,
                        String address, String landmark) {
            this(city, canonicalLocalityId, localityInput, address, landmark,
                    canonicalLocalityId == null ? null : LocationResolution.CANONICAL, null, null, null);
        }
    }
    public record Details(LocalDate availableFrom, String furnishingStatus, Double totalAreaSqFt,
                          Integer floorNumber, Integer totalFloors, String amenities, String description) {}
}
