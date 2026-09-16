package com.indore.pathome.spaces.dto;

public record ParserFieldReviewDTO(
        String fieldName,
        String predictedValue,
        String reviewedValue,
        String sourceText,
        Integer sourceStart,
        Integer sourceEnd,
        boolean corrected,
        boolean evidenceSupported,
        boolean valueValid,
        boolean trainingEligible,
        String exclusionReason) {
}
