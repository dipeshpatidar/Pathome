package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadIntent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LeadCaptureCommand(
        Long cityId,
        Long teamId,
        String customerName,
        String contactPhone,
        String customerSaid,
        LeadIntent intent,
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
        List<Object> preferredLocalityIds,
        Instant followUpAt,
        String followUpZoneId,
        String sourceChannel,
        String campaignRef,
    String referralRef,
    DuplicateDecision duplicateDecision,
    Long existingLeadId,
    Long expectedExistingLeadVersion,
    String reasonCode) {

    public enum DuplicateDecision {
        ATTACH_ENQUIRY,
        KEEP_DISTINCT
    }
}
