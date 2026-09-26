package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.ListingType;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.RoomTag;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lightweight discovery/list projection for public property cards.
 *
 * <p>Contains exactly one cover image URL for above-the-fold rendering.
 * The browser must not need to receive the full gallery collection to render a discovery card.
 * Full gallery metadata is delivered only by the detail endpoint.</p>
 *
 * <p>Do not add owner, address, coordinate, or operational fields here.</p>
 */
public record PublicDiscoveryResponse(
        Long id,
        String title,
        ListingType listingType,
        PropertyType propertyType,
        String city,
        String sector,
        String bhk,
        String furnishingStatus,
        String vastuFacing,
        Double totalAreaSqFt,
        BigDecimal monthlyRent,
        BigDecimal securityDeposit,
        BigDecimal maintenanceCharge,
        Boolean bachelorAllowed,
        String preferredTenant,
        LocalDateTime availableFrom,
        /** One best public cover image URL, or null when no permanent media exists. */
        String coverImageUrl,
        /** Stored tag of that exact cover image, or null for legacy/no-media listings. */
        RoomTag coverRoomTag,
        /** Total count of ALL public media items (images + videos). Used for "N photos" badge. */
        int mediaCount,
        /** True when at least one VIDEO_WALKTHROUGH exists for this listing. */
        boolean hasVideo,
        /** True when there is a subsequent page — client should show "Show More". */
        boolean hasMore
) {}
