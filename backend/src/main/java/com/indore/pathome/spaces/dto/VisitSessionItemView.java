package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionItemConfirmationStatus;
import com.indore.pathome.spaces.entity.VisitSessionItemOrigin;

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
        String removalReason) {}
