package com.indore.pathome.spaces.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pathome.scheduling.recommendation")
public class SchedulingRecommendationPolicy {
    private String version = "2cb-v1";
    private int candidateLimit = 5;
    private int candidateStepMinutes = 15;
    private int maximumCandidateStarts = 256;
    private int maximumPlanningWindowDays = 14;
    private int maximumGroundExecutives = 100;
    private int maximumItineraryStops = 20;
    private int maximumReservationRows = 5000;
    private int stopDwellMinutes = 15;
    private int sameLocalityTravelMinutes = 5;
    private int sameCityTravelMinutes = 20;
    private int unknownTravelMinutes = 45;
    private int travelBufferMinutes = 10;
    private int maximumLocationAgeSeconds = 90;
    private int maximumLocationAccuracyMeters = 100;
    private int maximumLocationFutureRelevanceMinutes = 120;
    private double coordinateDetourFactor = 1.5;
    private double conservativeTravelSpeedKph = 15.0;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public int getCandidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int candidateLimit) { this.candidateLimit = candidateLimit; }
    public int getCandidateStepMinutes() { return candidateStepMinutes; }
    public void setCandidateStepMinutes(int candidateStepMinutes) { this.candidateStepMinutes = candidateStepMinutes; }
    public int getMaximumCandidateStarts() { return maximumCandidateStarts; }
    public void setMaximumCandidateStarts(int maximumCandidateStarts) { this.maximumCandidateStarts = maximumCandidateStarts; }
    public int getMaximumPlanningWindowDays() { return maximumPlanningWindowDays; }
    public void setMaximumPlanningWindowDays(int maximumPlanningWindowDays) { this.maximumPlanningWindowDays = maximumPlanningWindowDays; }
    public int getMaximumGroundExecutives() { return maximumGroundExecutives; }
    public void setMaximumGroundExecutives(int maximumGroundExecutives) { this.maximumGroundExecutives = maximumGroundExecutives; }
    public int getMaximumItineraryStops() { return maximumItineraryStops; }
    public void setMaximumItineraryStops(int maximumItineraryStops) { this.maximumItineraryStops = maximumItineraryStops; }
    public int getMaximumReservationRows() { return maximumReservationRows; }
    public void setMaximumReservationRows(int maximumReservationRows) { this.maximumReservationRows = maximumReservationRows; }
    public int getStopDwellMinutes() { return stopDwellMinutes; }
    public void setStopDwellMinutes(int stopDwellMinutes) { this.stopDwellMinutes = stopDwellMinutes; }
    public int getSameLocalityTravelMinutes() { return sameLocalityTravelMinutes; }
    public void setSameLocalityTravelMinutes(int sameLocalityTravelMinutes) { this.sameLocalityTravelMinutes = sameLocalityTravelMinutes; }
    public int getSameCityTravelMinutes() { return sameCityTravelMinutes; }
    public void setSameCityTravelMinutes(int sameCityTravelMinutes) { this.sameCityTravelMinutes = sameCityTravelMinutes; }
    public int getUnknownTravelMinutes() { return unknownTravelMinutes; }
    public void setUnknownTravelMinutes(int unknownTravelMinutes) { this.unknownTravelMinutes = unknownTravelMinutes; }
    public int getTravelBufferMinutes() { return travelBufferMinutes; }
    public void setTravelBufferMinutes(int travelBufferMinutes) { this.travelBufferMinutes = travelBufferMinutes; }
    public int getMaximumLocationAgeSeconds() { return maximumLocationAgeSeconds; }
    public void setMaximumLocationAgeSeconds(int maximumLocationAgeSeconds) { this.maximumLocationAgeSeconds = maximumLocationAgeSeconds; }
    public int getMaximumLocationAccuracyMeters() { return maximumLocationAccuracyMeters; }
    public void setMaximumLocationAccuracyMeters(int maximumLocationAccuracyMeters) { this.maximumLocationAccuracyMeters = maximumLocationAccuracyMeters; }
    public int getMaximumLocationFutureRelevanceMinutes() { return maximumLocationFutureRelevanceMinutes; }
    public void setMaximumLocationFutureRelevanceMinutes(int maximumLocationFutureRelevanceMinutes) { this.maximumLocationFutureRelevanceMinutes = maximumLocationFutureRelevanceMinutes; }
    public double getCoordinateDetourFactor() { return coordinateDetourFactor; }
    public void setCoordinateDetourFactor(double coordinateDetourFactor) { this.coordinateDetourFactor = coordinateDetourFactor; }
    public double getConservativeTravelSpeedKph() { return conservativeTravelSpeedKph; }
    public void setConservativeTravelSpeedKph(double conservativeTravelSpeedKph) { this.conservativeTravelSpeedKph = conservativeTravelSpeedKph; }

    public void validate() {
        if (version == null || version.isBlank() || version.length() > 40)
            throw new IllegalStateException("Scheduling policy version must contain 1 to 40 characters");
        if (candidateLimit < 1 || candidateLimit > 100
                || candidateStepMinutes < 1
                || maximumCandidateStarts < 1 || maximumCandidateStarts > 1000
                || candidateLimit > maximumCandidateStarts
                || maximumPlanningWindowDays < 1 || maximumPlanningWindowDays > 30
                || maximumGroundExecutives < 1 || maximumGroundExecutives > 250
                || maximumItineraryStops < 1 || maximumItineraryStops > 20
                || maximumReservationRows < 1 || maximumReservationRows > 10000
                || stopDwellMinutes < 1
                || sameLocalityTravelMinutes < 0 || sameCityTravelMinutes < 0
                || unknownTravelMinutes < 0 || travelBufferMinutes < 1
                || maximumLocationAgeSeconds < 0 || maximumLocationAccuracyMeters < 1
                || maximumLocationFutureRelevanceMinutes < 0)
            throw new IllegalStateException("Scheduling recommendation bounds are outside supported limits");
        if (!Double.isFinite(coordinateDetourFactor) || coordinateDetourFactor < 1.0
                || !Double.isFinite(conservativeTravelSpeedKph) || conservativeTravelSpeedKph <= 0.0)
            throw new IllegalStateException("Scheduling travel assumptions must be finite and conservative");
        try {
            Math.addExact(unknownTravelMinutes, travelBufferMinutes);
            Math.addExact(sameLocalityTravelMinutes, travelBufferMinutes);
            Math.addExact(sameCityTravelMinutes, travelBufferMinutes);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("Scheduling travel fallbacks exceed supported limits", ex);
        }
    }
}
