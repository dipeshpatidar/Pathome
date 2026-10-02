package com.indore.pathome.spaces.dto;

import java.time.Instant;

public record TenantVisitAvailabilityView(
        Long requestId,
        Long version,
        Instant availabilityStartAt,
        Instant availabilityEndAt,
        String availabilityZoneId,
        Instant preferredAt
) {}
