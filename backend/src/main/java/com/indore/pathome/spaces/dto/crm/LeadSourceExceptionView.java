package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadSourceEventKind;
import com.indore.pathome.spaces.entity.LeadSourceEventState;

import java.time.Instant;
import java.util.UUID;

public record LeadSourceExceptionView(
        UUID eventId,
        LeadSourceEventKind eventKind,
        String sourceChannel,
        Instant receivedAt,
        LeadSourceEventState state,
        int attempts,
        String errorCode,
        String fingerprintVersion,
        Long attachedLeadId,
        Long resolutionLeadId,
        boolean extractedPayloadRetained) {
}
