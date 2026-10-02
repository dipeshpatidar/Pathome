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
        java.util.List<String> diagnostics,
        List<RecommendationRejection> rejectedGroundExecutives,
        List<RecommendationCandidate> candidates) {}
