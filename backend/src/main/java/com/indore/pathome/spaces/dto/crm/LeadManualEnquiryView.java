package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadSourceEventKind;
import com.indore.pathome.spaces.entity.LeadSourceEventState;

import java.time.Instant;

/**
 * Safe metadata for an immutable manual-enquiry source snapshot. The protected customer payload is
 * never projected through this view.
 */
public record LeadManualEnquiryView(
        LeadSourceEventKind eventKind,
        String channel,
        Instant capturedAt,
        LeadSourceEventState state,
        String snapshotVersion,
        boolean protectedPayloadRetained) {
}
