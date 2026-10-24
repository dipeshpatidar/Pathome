package com.indore.pathome.spaces.dto.crm;

import com.indore.pathome.spaces.entity.LeadShareEventType;

import java.util.UUID;

public record LeadShareEventCommand(UUID eventId, LeadShareEventType type) {
}
