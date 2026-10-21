package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.OperationsGeAvailabilityView;
import com.indore.pathome.spaces.dto.GeAvailabilityResultCode;
import com.indore.pathome.spaces.dto.RecommendationRejectionReason;
import com.indore.pathome.spaces.dto.RecommendationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coarse reason precedence and bounded search-input rules for the restricted availability view. */
class OperationsGeAvailabilityServiceTest {

    @Test
    void planReadinessTakesPrecedenceOverEveryPerGeOutcome() {
        assertEquals(GeAvailabilityResultCode.PLAN_NOT_READY, OperationsGeAvailabilityService.classify(
                false, 7L, Set.of(7L),
                Map.of(7L, List.of(RecommendationRejectionReason.COVERAGE_MISMATCH)), true));
    }

    @Test
    void aValidFeasibleOptionTakesPrecedenceOverRejectionAndBoundedResidual() {
        assertEquals(GeAvailabilityResultCode.FEASIBLE_OPTION_FOUND, OperationsGeAvailabilityService.classify(
                true, 7L, Set.of(7L),
                Map.of(7L, List.of(RecommendationRejectionReason.NO_FEASIBLE_TIME)), true));
    }

    @Test
    void definiteCoverageMismatchBeatsIncompleteEvaluation() {
        assertEquals(GeAvailabilityResultCode.COVERAGE_NOT_SUITABLE, OperationsGeAvailabilityService.classify(
                true, 8L, Set.of(7L),
                Map.of(8L, List.of(RecommendationRejectionReason.COVERAGE_MISMATCH)), true));
    }

    @Test
    void boundedEvaluationMergesPersonalBlockersIntoAnIncompleteConclusion() {
        assertEquals(GeAvailabilityResultCode.EVALUATION_INCOMPLETE, OperationsGeAvailabilityService.classify(
                true, 8L, Set.of(7L),
                Map.of(8L, List.of(RecommendationRejectionReason.SHIFT_MISMATCH,
                        RecommendationRejectionReason.UNAVAILABILITY_CONFLICT,
                        RecommendationRejectionReason.RESERVATION_OVERLAP)), true));
    }

    @Test
    void completeEvaluationWithNoFeasibleOptionUsesTheGenericSafeCode() {
        assertEquals(GeAvailabilityResultCode.NO_FEASIBLE_OPTION_FOUND, OperationsGeAvailabilityService.classify(
                true, 8L, Set.of(7L),
                Map.of(8L, List.of(RecommendationRejectionReason.NEXT_LEG_TRAVEL_INFEASIBLE)), false));
    }

    @Test
    void planReadinessUsesActualBackendStatusesAndGlobalCauses() {
        assertEquals(false, OperationsGeAvailabilityService.planReady(
                RecommendationStatus.INSUFFICIENT_TENANT_AVAILABILITY, List.of()));
        assertEquals(false, OperationsGeAvailabilityService.planReady(
                RecommendationStatus.PROPERTY_AVAILABILITY_INCOMPLETE, List.of()));
        assertEquals(false, OperationsGeAvailabilityService.planReady(
                RecommendationStatus.SESSION_NOT_SCHEDULABLE, List.of("ACTIVE_ITEM_PENDING_CONFIRMATION")));
        assertEquals(false, OperationsGeAvailabilityService.planReady(
                RecommendationStatus.NO_FEASIBLE_TIME, List.of("ITINERARY_EXCEEDS_SESSION_DURATION_POLICY")));
        assertEquals(false, OperationsGeAvailabilityService.planReady(
                RecommendationStatus.NO_FEASIBLE_TIME, List.of("TENANT_WINDOW_EXPIRED")));
        assertEquals(true, OperationsGeAvailabilityService.planReady(
                RecommendationStatus.NO_FEASIBLE_TIME, List.of()));
        assertEquals(true, OperationsGeAvailabilityService.planReady(
                RecommendationStatus.NO_ELIGIBLE_GE, List.of("NO_ACTIVE_CITY_COVERAGE")));
        assertEquals(true, OperationsGeAvailabilityService.planReady(
                RecommendationStatus.LOCATION_UNAVAILABLE_FALLBACK_USED, List.of()));
    }

    @Test
    void geDerivedFeasibleStatusesNeverReachTheRestrictedEnvelope() throws Exception {
        assertEquals(RecommendationStatus.RECOMMENDATION_AVAILABLE,
                OperationsGeAvailabilityService.publicPlanStatus(
                        RecommendationStatus.LOCATION_UNAVAILABLE_FALLBACK_USED, true));
        assertEquals(RecommendationStatus.RECOMMENDATION_AVAILABLE,
                OperationsGeAvailabilityService.publicPlanStatus(
                        RecommendationStatus.TRAVEL_ESTIMATE_UNCERTAIN, true));

        for (RecommendationStatus status : List.of(
                RecommendationStatus.RECOMMENDATION_AVAILABLE,
                RecommendationStatus.NO_FEASIBLE_TIME,
                RecommendationStatus.INSUFFICIENT_TENANT_AVAILABILITY,
                RecommendationStatus.PROPERTY_AVAILABILITY_INCOMPLETE,
                RecommendationStatus.SESSION_NOT_SCHEDULABLE,
                RecommendationStatus.RECOMMENDATION_STALE,
                RecommendationStatus.CONFLICT)) {
            assertEquals(status, OperationsGeAvailabilityService.publicPlanStatus(status, false), status.name());
        }
        assertEquals(RecommendationStatus.RECOMMENDATION_AVAILABLE,
                OperationsGeAvailabilityService.publicPlanStatus(
                        RecommendationStatus.NO_ELIGIBLE_GE, true));
        assertEquals(RecommendationStatus.NO_ELIGIBLE_GE,
                OperationsGeAvailabilityService.publicPlanStatus(
                        RecommendationStatus.NO_ELIGIBLE_GE, false));
        assertEquals(null, OperationsGeAvailabilityService.publicPlanStatus(null, false),
                "an absent internal status is rendered as not returned, never echoed");

        /*
         * Whole-envelope control. The raw evaluator status is serialized into the same record to
         * prove the forbidden-status scan below is discriminating; the projected record must not
         * contain it anywhere.
         */
        OperationsGeAvailabilityView raw = availabilityWithStatus(
                RecommendationStatus.LOCATION_UNAVAILABLE_FALLBACK_USED);
        String rawJson = new ObjectMapper().writeValueAsString(raw);
        assertTrue(rawJson.contains("LOCATION_UNAVAILABLE_FALLBACK_USED"));

        OperationsGeAvailabilityView projected = availabilityWithStatus(
                OperationsGeAvailabilityService.publicPlanStatus(
                        RecommendationStatus.LOCATION_UNAVAILABLE_FALLBACK_USED, true));
        String projectedJson = new ObjectMapper().writeValueAsString(projected);
        for (String forbidden : List.of(
                "LOCATION_UNAVAILABLE_FALLBACK_USED",
                "TRAVEL_ESTIMATE_UNCERTAIN",
                "scannedCandidateCount",
                "hiddenCandidateTotal")) {
            assertFalse(projectedJson.contains(forbidden), forbidden);
        }
        assertTrue(projectedJson.contains("RECOMMENDATION_AVAILABLE"));
    }

    private static OperationsGeAvailabilityView availabilityWithStatus(RecommendationStatus status) {
        return new OperationsGeAvailabilityView(
                1L, 2L, null, "policy", 3L, "fingerprint", status, true,
                List.of(), null, false,
                List.of(new OperationsGeAvailabilityView.GeAvailabilityEntry(
                        7L, "Recorded Name", GeAvailabilityResultCode.FEASIBLE_OPTION_FOUND)),
                1, 1, null);
    }

    @Test
    void blankSearchIsAbsentAndOverlongOrControlSearchIsRefused() {
        assertEquals("", OperationsGeAvailabilityService.normalizeAvailabilityQuery(null));
        assertEquals("", OperationsGeAvailabilityService.normalizeAvailabilityQuery("   "));
        assertEquals("ravi", OperationsGeAvailabilityService.normalizeAvailabilityQuery(" Ravi "));
        assertThrows(IllegalArgumentException.class,
                () -> OperationsGeAvailabilityService.normalizeAvailabilityQuery("a".repeat(81)));
        assertThrows(IllegalArgumentException.class,
                () -> OperationsGeAvailabilityService.normalizeAvailabilityQuery("ravi\nkumar"));
    }

    @Test
    void literalSearchEscapesEveryWildcardCharacter() {
        assertEquals("%ravi!%!_!!x%", VisitSchedulingRecommendationService.discoveryNamePattern("ravi%_!x"));
    }

    @Test
    void safePlanExplanationsDropsPrivateAndGeSpecificDiagnostics() {
        assertEquals(List.of("CONFIRMED_PROPERTY_WINDOW_REQUIRED",
                        "RESERVATION_SEARCH_BOUND_EXCEEDED"),
                OperationsGeAvailabilityService.safePlanExplanations(List.of(
                        "GE_7_COVERAGE_MISMATCH",
                        "UNAVAILABILITY_CONFLICT",
                        "CONFIRMED_PROPERTY_WINDOW_REQUIRED",
                        "RESERVATION_SEARCH_BOUND_EXCEEDED")));
    }

    @Test
    void serializedAvailabilityProjectionCannotCarryRawPrivateReasons() throws Exception {
        assertEquals(3, OperationsGeAvailabilityView.GeAvailabilityEntry.class
                .getRecordComponents().length, "the public entry has exactly ID, name and coarse code");
        assertEquals(5, GeAvailabilityResultCode.values().length, "only the five approved codes exist");

        OperationsGeAvailabilityView safe = new OperationsGeAvailabilityView(
                1L, 2L, null, "policy", 3L, "fingerprint",
                RecommendationStatus.RECOMMENDATION_AVAILABLE, true,
                List.of("RESERVATION_SEARCH_BOUND_EXCEEDED"), null, false,
                List.of(new OperationsGeAvailabilityView.GeAvailabilityEntry(
                        7L, "Recorded Name", GeAvailabilityResultCode.NO_FEASIBLE_OPTION_FOUND)),
                20, 1, null);
        String json = new ObjectMapper().writeValueAsString(safe);
        for (String privateReason : List.of("UNAVAILABILITY_CONFLICT", "SHIFT_MISMATCH",
                "RESERVATION_OVERLAP", "COVERAGE_MISMATCH", "TENANT_WINDOW_MISMATCH")) {
            assertFalse(json.contains(privateReason), privateReason);
        }
        assertFalse(json.contains("phone"));
        assertFalse(json.contains("email"));
        assertTrue(json.contains("NO_FEASIBLE_OPTION_FOUND"));

        /*
         * Discriminating leak control: the same forbidden-reason scan must catch a deliberately
         * leaky test-only record. If the production assertion above stops being meaningful, this
         * control fails too.
         */
        String leakyJson = new ObjectMapper().writeValueAsString(
                new LeakyEntry(7L, "Recorded Name", "NO_FEASIBLE_OPTION_FOUND", "UNAVAILABILITY_CONFLICT"));
        assertTrue(leakyJson.contains("UNAVAILABILITY_CONFLICT"));
    }

    private record LeakyEntry(Long groundExecutiveUserId, String displayName, String resultCode,
            String rawPrivateReason) {}
}
