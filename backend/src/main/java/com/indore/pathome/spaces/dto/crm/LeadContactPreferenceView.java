package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadConsentEvidenceMethod;
import com.indore.pathome.spaces.entity.LeadConsentStatus;
import com.indore.pathome.spaces.entity.LeadContactChannel;
import com.indore.pathome.spaces.entity.LeadDestinationChoice;

import java.time.Instant;

public record LeadContactPreferenceView(
        Long leadId,
        Long revision,
        Long leadVersion,
        LeadContactChannel channel,
        LeadDestinationChoice destinationChoice,
        String maskedDestination,
        LeadConsentStatus consentStatus,
        String consentPurpose,
        String consentWordingVersion,
        LeadConsentEvidenceMethod evidenceMethod,
        Instant recordedAt) {
}
