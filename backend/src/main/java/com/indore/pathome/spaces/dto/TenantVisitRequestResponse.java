package com.indore.pathome.spaces.dto;

import java.time.LocalDateTime;

/** Only tenant-facing facts already recorded by the visit-request domain. */
public record TenantVisitRequestResponse(
        Long requestId,
        Long propertyId,
        String propertyTitle,
        String propertyType,
        String bhk,
        String city,
        String sector,
        boolean propertyAvailable,
        String coverImageUrl,
        String preferredVisitTiming,
        String status,
        LocalDateTime requestedAt
) {}
