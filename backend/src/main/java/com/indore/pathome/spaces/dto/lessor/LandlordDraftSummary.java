package com.indore.pathome.spaces.dto.lessor;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.indore.pathome.spaces.entity.PropertyType;
import java.math.BigDecimal;
import java.time.Instant;

public record LandlordDraftSummary(
        String draftId,
        String title,
        String status,
        int completionPercent,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant updatedAt,
        PropertyType propertyType,
        String bhkCount,
        String city,
        String locality,
        BigDecimal monthlyRent,
        String coverUrl
) {}
