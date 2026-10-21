package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionItemConfirmationStatus;
import com.indore.pathome.spaces.entity.VisitSessionItemOrigin;
import com.indore.pathome.spaces.entity.ListingStatus;

import java.time.Instant;

public record VisitSessionItemView(
        Long itemId,
        Long listingId,
        String title,
        String address,
        String city,
        String sector,
        Integer position,
        Long sourceRequestId,
        Long derivedFromRequestId,
        VisitSessionItemOrigin origin,
        VisitSessionItemConfirmationStatus confirmationStatus,
        Instant availabilityConfirmedAt,
        Instant removedAt,
        Long removedByUserId,
        String removalReason,
        Instant propertyAvailabilityStartAt,
        Instant propertyAvailabilityEndAt,
        String propertyAvailabilityZoneId,
        com.indore.pathome.spaces.entity.PropertyAvailabilitySource propertyAvailabilitySource,
        Long availabilityConfirmedByUserId,
        /** Safe public cover for an ACTIVE listing only; null when no authorized image exists. */
        String coverImageUrl,
        ListingStatus listingStatus) {}
