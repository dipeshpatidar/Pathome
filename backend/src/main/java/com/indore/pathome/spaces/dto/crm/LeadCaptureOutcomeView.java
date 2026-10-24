package com.indore.pathome.spaces.dto.crm;

public record LeadCaptureOutcomeView(
        String resolution,
        Long leadId,
        Long leadVersion,
        String reasonCode,
        LeadManualEnquiryView manualEnquiry) {
}
