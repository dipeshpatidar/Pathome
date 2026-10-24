package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadShareEventType;

import java.time.Instant;
import java.util.UUID;

public record LeadShareEventView(
        UUID id,
        UUID shareId,
        LeadShareEventType type,
        Instant occurredAt) {
}
