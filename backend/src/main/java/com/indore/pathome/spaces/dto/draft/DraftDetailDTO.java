package com.indore.pathome.spaces.dto.draft;

import java.time.LocalDateTime;
import java.util.List;

public record DraftDetailDTO(
        String draftId,
        String draftType,
        String status,
        String titleSummary,
        int itemCount,
        int version,
        String payload,
        List<DraftMediaDTO> media,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
