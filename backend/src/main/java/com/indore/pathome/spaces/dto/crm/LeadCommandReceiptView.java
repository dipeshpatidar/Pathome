package com.indore.pathome.spaces.dto.crm;

import java.time.Instant;

public record LeadCommandReceiptView(
        String state,
        String action,
        String resultReference,
        Instant recordedAt) {
}
