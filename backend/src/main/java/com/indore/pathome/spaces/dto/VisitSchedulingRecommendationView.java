package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.List;

public record VisitSchedulingRecommendationView(
        Long sessionId,
        Long sessionVersion,
        Instant recommendationGeneratedAt,
        String policyVersion,
        RecommendationStatus status,
        boolean candidateSearchTruncated,
        List<String> diagnostics,
        List<RecommendationRejection> rejectedGroundExecutives,
        List<RecommendationCandidate> candidates,
        String mode,
        String nextCursor,
        Integer pageSize,
        Integer scannedCandidateCount) {
    public VisitSchedulingRecommendationView(Long sessionId, Long sessionVersion,
            Instant recommendationGeneratedAt, String policyVersion, RecommendationStatus status,
            boolean candidateSearchTruncated, List<String> diagnostics,
            List<RecommendationRejection> rejectedGroundExecutives,
            List<RecommendationCandidate> candidates) {
        this(sessionId, sessionVersion, recommendationGeneratedAt, policyVersion, status,
                candidateSearchTruncated, diagnostics, rejectedGroundExecutives, candidates,
                "RECOMMENDATIONS", null, null, null);
    }
}
