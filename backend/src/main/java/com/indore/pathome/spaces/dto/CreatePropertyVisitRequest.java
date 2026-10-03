package com.indore.pathome.spaces.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime availabilityStartAt,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime availabilityEndAt,
        String availabilityZoneId,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime preferredAt
) {
    public CreatePropertyVisitRequest(BigDecimal budgetMin, BigDecimal budgetMax, String preferredAreas,
                                      String moveInTiming, String preferredVisitTiming, String note) {
        this(budgetMin, budgetMax, preferredAreas, moveInTiming, preferredVisitTiming, note,
                null, null, null, null);
    }
}
