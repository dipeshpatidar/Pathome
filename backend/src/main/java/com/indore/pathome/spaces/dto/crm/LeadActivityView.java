package com.indore.pathome.spaces.dto.crm;

import java.time.Instant;

public record LeadActivityView(Long id, String type, Instant occurredAt, String note) {
}
