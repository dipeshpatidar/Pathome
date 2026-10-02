package com.indore.pathome.spaces.dto;

import java.time.OffsetDateTime;

public record GroundExecutiveShiftCommand(
        Long expectedVersion,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String zoneId
) {}
