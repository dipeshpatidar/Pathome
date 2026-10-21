package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadIntent;
import com.indore.pathome.spaces.entity.LeadLegacyProvenance;
import com.indore.pathome.spaces.entity.LeadStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LeadDetailView(
        LeadSummaryView summary,
        String customerSaid,
        String propertyType,
        String bhk,
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        String currency,
        LocalDate targetMoveInDate,
        String furnishing,
        String tenantProfile,
        Boolean parkingRequired,
        Boolean petsAllowed,
        Boolean liftRequired,
        String floorRequirement,
        List<Long> preferredLocalityIds,
        boolean linkedCustomerAccount,
        LeadLegacyProvenance provenance) {
}
