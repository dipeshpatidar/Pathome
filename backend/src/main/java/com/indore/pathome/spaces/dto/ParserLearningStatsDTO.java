package com.indore.pathome.spaces.dto;

public record ParserLearningStatsDTO(
        long quarantined,
        long pendingCuration,
        long approvedTraining,
        long approvedValidation,
        long approvedHoldout,
        long rejected) {
}
