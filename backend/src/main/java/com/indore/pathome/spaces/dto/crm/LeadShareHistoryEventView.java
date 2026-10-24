package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadShareEventType;

import java.time.Instant;
import java.util.UUID;

public record LeadShareHistoryEventView(
        UUID eventId,
        LeadShareEventType type,
        Instant occurredAt) {
}
