package com.indore.pathome.spaces.dto.crm;

import java.util.UUID;

public record LeadSourceAttachCommand(
        Long expectedVersion,
        UUID eventId,
        Decision decision,
        String reasonCode) {

    public enum Decision {
        ATTACH_ENQUIRY,
        KEEP_DISTINCT
    }
}
