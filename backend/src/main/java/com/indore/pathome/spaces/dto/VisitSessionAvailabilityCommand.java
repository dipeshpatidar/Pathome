package com.indore.pathome.spaces.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.indore.pathome.spaces.entity.VisitSessionItemConfirmationStatus;
import com.indore.pathome.spaces.entity.PropertyAvailabilitySource;
import java.time.OffsetDateTime;
public record VisitSessionAvailabilityCommand(
        Long expectedSessionVersion,
        VisitSessionItemConfirmationStatus status,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime availabilityStartAt,
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime availabilityEndAt,
        String availabilityZoneId,
        PropertyAvailabilitySource availabilitySource) {
    public VisitSessionAvailabilityCommand(Long expectedSessionVersion, VisitSessionItemConfirmationStatus status) {
        this(expectedSessionVersion, status, null, null, null, null);
    }

    public boolean hasAnyAvailabilityField() {
        return availabilityStartAt != null || availabilityEndAt != null
                || availabilityZoneId != null || availabilitySource != null;
    }
}
