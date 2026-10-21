package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadIntent;
import com.indore.pathome.spaces.entity.LeadStatus;

import java.time.Instant;
import java.util.List;

public record LeadSummaryView(
        Long id,
        Long version,
        String customerName,
        String maskedContact,
        LeadStatus status,
        LeadIntent intent,
        Long cityId,
        String cityName,
        Long teamId,
        String teamName,
        Long ownerUserId,
        String ownerDisplayName,
        Instant followUpAt,
        String followUpZoneId,
        Instant lastActivityAt,
        List<String> qualificationGaps) {
}
