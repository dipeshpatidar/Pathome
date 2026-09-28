package com.indore.pathome.spaces.dto.lessor;

import com.indore.pathome.spaces.entity.PropertyType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record LandlordDraftSummary(
        String draftId,
        String title,
        String status,
        int completionPercent,
        LocalDateTime updatedAt,
        PropertyType propertyType,
        String bhkCount,
        String city,
        String locality,
        BigDecimal monthlyRent,
        String coverUrl
) {}
