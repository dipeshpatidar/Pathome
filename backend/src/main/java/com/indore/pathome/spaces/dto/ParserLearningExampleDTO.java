package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.ParserDatasetPartition;
import com.indore.pathome.spaces.entity.ParserInputSource;
import com.indore.pathome.spaces.entity.ParserReviewStatus;

import java.time.LocalDateTime;
import java.util.List;

public record ParserLearningExampleDTO(
        String id,
        String batchId,
        int propertyIndex,
        ParserInputSource inputSource,
        ParserReviewStatus reviewStatus,
        ParserDatasetPartition datasetPartition,
        String rawPrompt,
        String parserVersion,
        Long publishedListingId,
        String reviewedBy,
        String exclusionReason,
        int labelCount,
        int eligibleLabelCount,
        LocalDateTime createdAt,
        LocalDateTime publishedAt,
        LocalDateTime curatedAt,
        List<ParserFieldReviewDTO> fields) {
}
