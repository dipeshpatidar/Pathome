package com.indore.pathome.spaces.dto;

import java.time.OffsetDateTime;

public record TenantVisitAvailabilityCommand(
        Long expectedRequestVersion,
        OffsetDateTime availabilityStartAt,
        OffsetDateTime availabilityEndAt,
        String availabilityZoneId,
        OffsetDateTime preferredAt
) {}
