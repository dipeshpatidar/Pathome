package com.indore.pathome.spaces.dto;

import java.math.BigDecimal;

/**
 * Tenant interest only. Preferred timing is not an appointment slot.
 */
public record CreatePropertyVisitRequest(
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        String preferredAreas,
        String moveInTiming,
        String preferredVisitTiming,
        String note
) {}
