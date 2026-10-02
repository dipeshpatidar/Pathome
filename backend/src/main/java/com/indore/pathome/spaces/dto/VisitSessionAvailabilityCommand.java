package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.VisitSessionItemConfirmationStatus;
import com.indore.pathome.spaces.entity.PropertyAvailabilitySource;
import java.time.OffsetDateTime;
public record VisitSessionAvailabilityCommand(
        Long expectedSessionVersion,
        VisitSessionItemConfirmationStatus status,
        OffsetDateTime availabilityStartAt,
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
