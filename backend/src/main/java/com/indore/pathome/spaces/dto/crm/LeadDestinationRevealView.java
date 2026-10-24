package com.indore.pathome.spaces.dto.crm;

import java.time.Instant;

public record LeadDestinationRevealView(
        Long leadId,
        Long revision,
        String fullDestination,
        Instant recordedAt) {
}
