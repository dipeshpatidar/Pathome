package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadStatus;

public record LeadDuplicateCandidateView(
        Long leadId,
        Long version,
        String maskedContact,
        String customerName,
        LeadStatus status,
        Long cityId,
        Long teamId,
        boolean currentlyOwnedByActor) {
}
