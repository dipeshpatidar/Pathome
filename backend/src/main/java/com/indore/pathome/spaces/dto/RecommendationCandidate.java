package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.List;

public record RecommendationCandidate(
        Long groundExecutiveUserId,
        Instant scheduledAt,
        Instant reservedEndAt,
        Integer durationMinutes,
        String zoneId,
        int rank,
        String feasibilityStatus,
        String locationAssessment,
        String originSource,
        String travelConfidence,
        int futureReservationCount,
        int futureReservedMinutes,
        List<RecommendationReason> reasons,
        String groundExecutiveName) {
    public RecommendationCandidate(Long groundExecutiveUserId, Instant scheduledAt, Instant reservedEndAt,
            Integer durationMinutes, String zoneId, int rank, String feasibilityStatus,
            String locationAssessment, String originSource, String travelConfidence,
            int futureReservationCount, int futureReservedMinutes, List<RecommendationReason> reasons) {
        this(groundExecutiveUserId, scheduledAt, reservedEndAt, durationMinutes, zoneId, rank,
                feasibilityStatus, locationAssessment, originSource, travelConfidence,
                futureReservationCount, futureReservedMinutes, reasons, null);
    }
}
