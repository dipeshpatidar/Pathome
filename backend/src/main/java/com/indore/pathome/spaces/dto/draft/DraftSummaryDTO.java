package com.indore.pathome.spaces.dto.draft;

import java.time.LocalDateTime;

public record DraftSummaryDTO(
        String draftId,
        String draftType,
        String status,
        String titleSummary,
        int itemCount,
        int version,
        int mediaCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
