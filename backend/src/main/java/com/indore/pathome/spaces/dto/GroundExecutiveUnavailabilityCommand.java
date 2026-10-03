package com.indore.pathome.spaces.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.indore.pathome.spaces.entity.GroundExecutiveUnavailableType;
import java.time.OffsetDateTime;

public record GroundExecutiveUnavailabilityCommand(
        Long expectedVersion,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime startsAt,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime endsAt,
        String zoneId,
        GroundExecutiveUnavailableType intervalType
) {}
