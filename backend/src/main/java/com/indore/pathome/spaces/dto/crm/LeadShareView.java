package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadShareMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LeadShareView(
        UUID id,
        Long leadId,
        Long shortlistId,
        Long shortlistVersion,
        Long contactPreferenceRevision,
        LeadShareMode mode,
        List<String> publicLinks,
        String previewText,
        String maskedDestination,
        Instant recordedAt) {
}
