package com.indore.pathome.spaces.dto.draft;

import java.time.LocalDateTime;

public record DraftMediaDTO(
        String mediaId,
        String draftId,
        String cardId,
        String originalFilename,
        Long fileSizeBytes,
        String contentType,
        String roomTag,
        Boolean isCover,
        String previewUrl,
        LocalDateTime createdAt
) {}
