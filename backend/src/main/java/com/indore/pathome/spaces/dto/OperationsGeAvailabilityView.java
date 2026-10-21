package com.indore.pathome.spaces.dto;

import java.time.Instant;
import java.util.List;

/**
 * Restricted, plan-specific Ground Executive availability information for the currently owning
 * Operations Executive. This is not an organization roster and never carries contact details,
 * private shift/leave data or another customer's booking facts.
 */
public record OperationsGeAvailabilityView(
        Long sessionId,
        Long sessionVersion,
        Instant evaluatedAt,
        String schedulingPolicyVersion,
        Long visitPolicyVersion,
        String planInputFingerprint,
        RecommendationStatus planStatus,
        boolean planReady,
        List<String> planExplanationCodes,
        PlanTimingView timing,
        boolean evaluationBounded,
        List<GeAvailabilityEntry> entries,
        int pageSize,
        int returnedEntryCount,
        String nextCursor) {

    public record PlanTimingView(
            Integer configuredStopDwellMinutes,
            Integer maximumSessionDurationMinutes,
            Integer calculatedItineraryDurationMinutes,
            String durationScope,
            TenantWindow tenantWindow,
            List<StopWindow> stops,
            List<TravelAllowance> betweenStopTravel,
            boolean planningWindowBounded) {}

    public record TenantWindow(Instant startAt, Instant endAt, String zoneId, List<Instant> preferredTimes) {}

    public record StopWindow(
            int position,
            Long itemId,
            Long listingId,
            String confirmationStatus,
            Instant availabilityStartAt,
            Instant availabilityEndAt,
            String zoneId,
            String availabilitySource,
            Instant availabilityConfirmedAt,
            Integer offsetMinutes) {}

    public record TravelAllowance(
            int fromPosition,
            int toPosition,
            int minutes,
            String source,
            String confidence) {}

    public record GeAvailabilityEntry(
            Long groundExecutiveUserId,
            String displayName,
            GeAvailabilityResultCode resultCode) {}
}
