package com.indore.pathome.spaces.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ConservativeTravelTimeEstimator implements TravelTimeEstimator {
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private final SchedulingRecommendationPolicy policy;

    public ConservativeTravelTimeEstimator(SchedulingRecommendationPolicy policy) {
        this.policy = policy;
        this.policy.validate();
    }

    @Override
    public TravelEstimate estimate(TravelPoint origin, TravelPoint destination, Instant departureAt) {
        int minutes;
        TravelEstimate.Confidence confidence;
        String source;
        if (hasCoordinates(origin) && hasCoordinates(destination)) {
            double roadDistanceKm = haversineKm(origin.latitude(), origin.longitude(),
                    destination.latitude(), destination.longitude()) * policy.getCoordinateDetourFactor();
            minutes = (int) Math.ceil(roadDistanceKm / policy.getConservativeTravelSpeedKph() * 60.0);
            confidence = TravelEstimate.Confidence.MEDIUM;
            source = origin.source() == null ? "PROPERTY_COORDINATES" : origin.source();
        } else if (origin.localityId() != null && origin.localityId().equals(destination.localityId())) {
            minutes = policy.getSameLocalityTravelMinutes();
            confidence = TravelEstimate.Confidence.MEDIUM;
            source = "CANONICAL_LOCALITY";
        } else if (sameCity(origin.city(), destination.city())) {
            minutes = policy.getSameCityTravelMinutes();
            confidence = TravelEstimate.Confidence.LOW;
            source = "CITY_FALLBACK";
        } else {
            minutes = policy.getUnknownTravelMinutes();
            confidence = TravelEstimate.Confidence.UNKNOWN;
            source = "CONSERVATIVE_UNKNOWN_FALLBACK";
        }
        return new TravelEstimate(Duration.ofMinutes(minutes + policy.getTravelBufferMinutes()), confidence, source);
    }

    static double haversineKm(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latDistance = Math.toRadians(latitudeB - latitudeA);
        double lonDistance = Math.toRadians(longitudeB - longitudeA);
        double value = Math.pow(Math.sin(latDistance / 2), 2)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.pow(Math.sin(lonDistance / 2), 2);
        value = Math.max(0.0, Math.min(1.0, value));
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
    }

    private boolean hasCoordinates(TravelPoint point) {
        return point != null && validCoordinate(point.latitude(), point.longitude());
    }

    private boolean validCoordinate(Double latitude, Double longitude) {
        return latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    private boolean sameCity(String first, String second) {
        return first != null && second != null && first.trim().equalsIgnoreCase(second.trim());
    }
}
