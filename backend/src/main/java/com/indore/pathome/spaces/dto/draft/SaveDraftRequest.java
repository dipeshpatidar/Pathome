package com.indore.pathome.spaces.dto.draft;

public record SaveDraftRequest(
        String draftId,
        String draftType,
        String status,
        String titleSummary,
        Integer itemCount,
        Integer version,
        String payload
) {}
