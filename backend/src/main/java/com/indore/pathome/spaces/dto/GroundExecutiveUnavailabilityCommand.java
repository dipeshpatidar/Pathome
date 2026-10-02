package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.GroundExecutiveUnavailableType;
import java.time.OffsetDateTime;

public record GroundExecutiveUnavailabilityCommand(
        Long expectedVersion,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String zoneId,
        GroundExecutiveUnavailableType intervalType
) {}
