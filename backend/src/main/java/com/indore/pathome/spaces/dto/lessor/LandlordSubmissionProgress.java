package com.indore.pathome.spaces.dto.lessor;

/** Private, ephemeral progress for one authenticated lessor draft submission. */
public record LandlordSubmissionProgress(
        String submissionId,
        String status,
        int percent,
        int processedMedia,
        int totalMedia,
        String messageCode,
        boolean completed,
        boolean failed) {}
