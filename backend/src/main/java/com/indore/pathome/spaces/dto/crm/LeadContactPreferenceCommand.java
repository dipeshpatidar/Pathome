package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadConsentEvidenceMethod;
import com.indore.pathome.spaces.entity.LeadConsentStatus;
import com.indore.pathome.spaces.entity.LeadContactChannel;
import com.indore.pathome.spaces.entity.LeadDestinationChoice;

public record LeadContactPreferenceCommand(
        Long expectedLeadVersion,
        Long expectedPreferenceRevision,
        LeadContactChannel channel,
        LeadDestinationChoice destinationChoice,
        String countryCallingCode,
        String number,
        LeadConsentStatus consentStatus,
        String consentWordingVersion,
        LeadConsentEvidenceMethod evidenceMethod) {
}
