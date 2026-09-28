package com.indore.pathome.spaces.dto.lessor;

import java.time.LocalDateTime;

public record LandlordDraftResponse(
        String draftId,
        String status,
        int version,
        int completionPercent,
        LandlordDraftData data,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
