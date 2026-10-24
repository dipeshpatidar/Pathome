package com.indore.pathome.spaces.dto.crm;

import java.time.Instant;

public record LeadActivityCommand(
        Long expectedVersion,
        Type type,
        String note,
        Instant followUpAt,
        String followUpZoneId) {

    public enum Type {
        CALL_LOGGED,
        NOTE_ADDED
    }
}
