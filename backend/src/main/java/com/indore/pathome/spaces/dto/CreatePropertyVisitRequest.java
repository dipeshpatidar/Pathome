package com.indore.pathome.spaces.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Tenant interest only. Preferred timing is not an appointment slot.
 */
public record CreatePropertyVisitRequest(
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        String preferredAreas,
        String moveInTiming,
        String preferredVisitTiming,
        String note,
        OffsetDateTime availabilityStartAt,
        OffsetDateTime availabilityEndAt,
        String availabilityZoneId,
        OffsetDateTime preferredAt
) {
    public CreatePropertyVisitRequest(BigDecimal budgetMin, BigDecimal budgetMax, String preferredAreas,
                                      String moveInTiming, String preferredVisitTiming, String note) {
        this(budgetMin, budgetMax, preferredAreas, moveInTiming, preferredVisitTiming, note,
                null, null, null, null);
    }
}
