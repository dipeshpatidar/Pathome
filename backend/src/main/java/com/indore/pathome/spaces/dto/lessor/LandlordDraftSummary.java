package com.indore.pathome.spaces.dto.lessor;

import java.time.LocalDateTime;

public record LandlordDraftSummary(
        String draftId,
        String title,
        String status,
        int completionPercent,
        LocalDateTime updatedAt
) {}
