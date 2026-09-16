package com.indore.pathome.spaces.dto;

import com.indore.pathome.spaces.entity.ParserModelStatus;

import java.time.LocalDateTime;

public record ParserModelVersionDTO(
        String modelVersion,
        String artifactPath,
        String artifactChecksum,
        String datasetFingerprint,
        String metricsJson,
        int holdoutExampleCount,
        ParserModelStatus modelStatus,
        String decisionReason,
        LocalDateTime createdAt,
        LocalDateTime activatedAt) {
}
