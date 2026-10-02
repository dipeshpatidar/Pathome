package com.indore.pathome.spaces.service;

import java.time.Instant;

public record VisitSessionNotificationEvent(
        Type type,
        Long sessionId,
        Long tenantUserId,
        Long groundExecutiveUserId,
        Long formerGroundExecutiveUserId,
        Long version,
        Instant scheduledAt,
        String zoneId) {
    public enum Type { SCHEDULED, RESCHEDULED, CANCELLED, ASSIGNED, REASSIGNED, ITINERARY_CHANGED }
}
