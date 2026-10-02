package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ConservativeTravelTimeEstimatorTest {
    private final ConservativeTravelTimeEstimator estimator =
            new ConservativeTravelTimeEstimator(new SchedulingRecommendationPolicy());

    @Test
    void usesNamedFallbacksWhenPropertyCoordinatesAreUnavailable() {
        Instant at = Instant.parse("2026-10-02T12:00:00Z");
        TravelPoint sameLocalityFrom = new TravelPoint("Example City", 4L, null, null, "PROPERTY");
        TravelPoint sameLocalityTo = new TravelPoint("Example City", 4L, null, null, "PROPERTY");
        TravelEstimate sameLocality = estimator.estimate(sameLocalityFrom, sameLocalityTo, at);
        assertEquals(Duration.ofMinutes(15), sameLocality.duration());
        assertEquals(TravelEstimate.Confidence.MEDIUM, sameLocality.confidence());
        assertEquals("CANONICAL_LOCALITY", sameLocality.originSource());

        TravelEstimate unknown = estimator.estimate(new TravelPoint(null, null, null, null, "UNKNOWN"),
                sameLocalityTo, at);
        assertEquals(Duration.ofMinutes(55), unknown.duration());
        assertEquals(TravelEstimate.Confidence.UNKNOWN, unknown.confidence());
        assertEquals("CONSERVATIVE_UNKNOWN_FALLBACK", unknown.originSource());
    }

    @Test
    void coordinatesProduceConservativeEstimateAndInvalidCoordinatesFallBack() {
        Instant at = Instant.parse("2026-10-02T12:00:00Z");
        TravelPoint same = new TravelPoint("Example City", 4L, 22.72, 75.88, "PROPERTY");
        TravelEstimate coordinateEstimate = estimator.estimate(same, same, at);
        assertEquals(Duration.ofMinutes(10), coordinateEstimate.duration());
        assertEquals(TravelEstimate.Confidence.MEDIUM, coordinateEstimate.confidence());

        TravelPoint invalid = new TravelPoint("Example City", 4L, Double.NaN, 75.88, "PROPERTY");
        assertEquals(Duration.ofMinutes(15), estimator.estimate(invalid, same, at).duration());
    }

    @Test
    void nearAntipodalCoordinatesRemainConservativelyFinite() {
        TravelPoint origin = new TravelPoint("North", null, 0.0, 0.0, "PROPERTY");
        TravelPoint destination = new TravelPoint("South", null, 0.00001, 179.99999, "PROPERTY");

        TravelEstimate estimate = estimator.estimate(origin, destination, Instant.EPOCH);

        assertTrue(estimate.duration().toMinutes() > 10_000);
        assertTrue(estimate.duration().toMinutes() < Integer.MAX_VALUE);
    }
}
