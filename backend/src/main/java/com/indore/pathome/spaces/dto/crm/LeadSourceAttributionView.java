package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadSourceEventKind;
import com.indore.pathome.spaces.entity.LeadSourceEventState;

import java.time.Instant;
import java.util.UUID;

public record LeadSourceAttributionView(
        UUID sourceEventId,
        LeadSourceEventKind eventKind,
        String channel,
        String campaignRef,
        String referralRef,
        Instant receivedAt,
        LeadSourceEventState state,
        String fingerprintVersion,
        LeadManualEnquiryView manualEnquiry) {
}
