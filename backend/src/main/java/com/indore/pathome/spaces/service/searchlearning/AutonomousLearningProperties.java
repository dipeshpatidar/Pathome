package com.indore.pathome.spaces.service.searchlearning;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Central policy defaults. Invalid settings fail closed before any autonomous mutation. */
@Component
@ConfigurationProperties("search.learning.autonomous")
public class AutonomousLearningProperties {
    public static final String POLICY_VERSION = "AUTONOMOUS_V2";
    private boolean enabled = true;
    private Set<String> entityAllowlist = Set.of("LOCALITY");
    private int minimumEvidence = 30, minimumUniqueSessions = 30, minimumTimeBuckets = 4;
    private int sessionContributionCap = 1, minimumShadowEvaluations = 30;
    private int minimumHealthEvidence = 20, evaluationBatchSize = 100;
    private double minimumTargetDominance = .95, confidenceLevel = .95, minimumConfidenceLowerBound = .85;
    private double minimumShadowAgreement = .95, degradationThreshold = .80, disableThreshold = .60;
    private double recoveryThreshold = .90, burstThreshold = .35, maximumSessionShare = .10;
    private Duration minimumCandidateAge = Duration.ofHours(48), evidenceWindow = Duration.ofDays(30);
    private Duration timeBucket = Duration.ofHours(6), burstWindow = Duration.ofHours(1);
    private Duration recentHealthWindow = Duration.ofDays(7), reactivationCooldown = Duration.ofDays(7);
    private Duration degradationCooldown = Duration.ofHours(24);
    private Duration evaluationSchedule = Duration.ofMinutes(15), initialDelay = Duration.ofMinutes(2);

    public void validate() {
        if (!Set.of("LOCALITY").containsAll(entityAllowlist) || entityAllowlist.isEmpty()
                || minimumEvidence < 1 || minimumUniqueSessions < 2 || minimumTimeBuckets < 2
                || sessionContributionCap < 1 || minimumShadowEvaluations < 1 || minimumHealthEvidence < 2
                || evaluationBatchSize < 1 || evaluationBatchSize > 10000
                || minimumUniqueSessions > minimumEvidence
                || !(confidenceLevel == .90 || confidenceLevel == .95 || confidenceLevel == .99)
                || !(disableThreshold < degradationThreshold && degradationThreshold < recoveryThreshold)) {
            throw new IllegalArgumentException("Invalid autonomous learning policy");
        }
        for (double v : new double[]{minimumTargetDominance, minimumConfidenceLowerBound, minimumShadowAgreement,
                degradationThreshold, disableThreshold, recoveryThreshold, burstThreshold, maximumSessionShare}) {
            if (!Double.isFinite(v) || v <= 0 || v > 1) throw new IllegalArgumentException("Invalid policy probability");
        }
        for (Duration d : new Duration[]{minimumCandidateAge, evidenceWindow, timeBucket, burstWindow,
                recentHealthWindow, reactivationCooldown, degradationCooldown, evaluationSchedule, initialDelay}) {
            if (d == null || d.toSeconds() < 1) throw new IllegalArgumentException("Invalid policy duration");
        }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Set<String> getEntityAllowlist() { return entityAllowlist; }
    public void setEntityAllowlist(Set<String> value) { entityAllowlist = value; }
    public int getMinimumEvidence() { return minimumEvidence; }
    public void setMinimumEvidence(int value) { minimumEvidence = value; }
    public int getMinimumUniqueSessions() { return minimumUniqueSessions; }
    public void setMinimumUniqueSessions(int value) { minimumUniqueSessions = value; }
    public int getMinimumTimeBuckets() { return minimumTimeBuckets; }
    public void setMinimumTimeBuckets(int value) { minimumTimeBuckets = value; }
    public int getSessionContributionCap() { return sessionContributionCap; }
    public void setSessionContributionCap(int value) { sessionContributionCap = value; }
    public int getMinimumShadowEvaluations() { return minimumShadowEvaluations; }
    public void setMinimumShadowEvaluations(int value) { minimumShadowEvaluations = value; }
    public int getMinimumHealthEvidence() { return minimumHealthEvidence; }
    public void setMinimumHealthEvidence(int value) { minimumHealthEvidence = value; }
    public int getEvaluationBatchSize() { return evaluationBatchSize; }
    public void setEvaluationBatchSize(int value) { evaluationBatchSize = value; }
    public double getMinimumTargetDominance() { return minimumTargetDominance; }
    public void setMinimumTargetDominance(double value) { minimumTargetDominance = value; }
    public double getConfidenceLevel() { return confidenceLevel; }
    public void setConfidenceLevel(double value) { confidenceLevel = value; }
    public double getMinimumConfidenceLowerBound() { return minimumConfidenceLowerBound; }
    public void setMinimumConfidenceLowerBound(double value) { minimumConfidenceLowerBound = value; }
    public double getMinimumShadowAgreement() { return minimumShadowAgreement; }
    public void setMinimumShadowAgreement(double value) { minimumShadowAgreement = value; }
    public double getDegradationThreshold() { return degradationThreshold; }
    public void setDegradationThreshold(double value) { degradationThreshold = value; }
    public double getDisableThreshold() { return disableThreshold; }
    public void setDisableThreshold(double value) { disableThreshold = value; }
    public double getRecoveryThreshold() { return recoveryThreshold; }
    public void setRecoveryThreshold(double value) { recoveryThreshold = value; }
    public double getBurstThreshold() { return burstThreshold; }
    public void setBurstThreshold(double value) { burstThreshold = value; }
    public double getMaximumSessionShare() { return maximumSessionShare; }
    public void setMaximumSessionShare(double value) { maximumSessionShare = value; }
    public Duration getMinimumCandidateAge() { return minimumCandidateAge; }
    public void setMinimumCandidateAge(Duration value) { minimumCandidateAge = value; }
    public Duration getEvidenceWindow() { return evidenceWindow; }
    public void setEvidenceWindow(Duration value) { evidenceWindow = value; }
    public Duration getTimeBucket() { return timeBucket; }
    public void setTimeBucket(Duration value) { timeBucket = value; }
    public Duration getBurstWindow() { return burstWindow; }
    public void setBurstWindow(Duration value) { burstWindow = value; }
    public Duration getRecentHealthWindow() { return recentHealthWindow; }
    public void setRecentHealthWindow(Duration value) { recentHealthWindow = value; }
    public Duration getReactivationCooldown() { return reactivationCooldown; }
    public void setReactivationCooldown(Duration value) { reactivationCooldown = value; }
    public Duration getDegradationCooldown() { return degradationCooldown; }
    public void setDegradationCooldown(Duration value) { degradationCooldown = value; }
    public Duration getEvaluationSchedule() { return evaluationSchedule; }
    public void setEvaluationSchedule(Duration value) { evaluationSchedule = value; }
    public Duration getInitialDelay() { return initialDelay; }
    public void setInitialDelay(Duration value) { initialDelay = value; }
}
