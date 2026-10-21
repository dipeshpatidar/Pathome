package com.indore.pathome.spaces.dto.crm;

import java.time.Instant;

public record LeadCommandReceiptView(
        String state,
        Long resultLeadId,
        Instant recordedAt) {
}
