package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Scoped read for a currently-owned standalone Request. Structured timing only:
 * tenant free text, budgets, lessor identity and outcome notes stay out of this contract.
 *
 * {@code createdAt} mirrors {@code property_visit_requests.created_at}, which PostgreSQL stores
 * without a time zone, so it is exposed with the same local-time meaning as the existing
 * Operations Request queue contract instead of an invented absolute instant.
 */
public record OperationsStandaloneRequestDetailView(
        Long requestId,
        String status,
        Long version,
        LocalDateTime createdAt,
        Long cityId,
        String cityDisplayName,
        Long teamId,
        String teamDisplayName,
        Long listingId,
        String listingTitle,
        String listingAddress,
        String listingStatus,
        String listingCity,
        String sector,
        Instant availabilityStartAt,
        Instant availabilityEndAt,
        String availabilityZoneId,
        Instant preferredAt,
        Long sessionId) {}
