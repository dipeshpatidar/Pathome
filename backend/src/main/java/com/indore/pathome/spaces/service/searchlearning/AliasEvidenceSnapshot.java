package com.indore.pathome.spaces.service.searchlearning;

import java.time.Instant;

public record AliasEvidenceSnapshot(AliasLifecycle state, String entityType, boolean canonicalValid,
        boolean deterministicConflict, boolean activeConflict, boolean manualLock,
        Instant now, Instant firstEvidence, Instant stateSince,
        Evidence learning, Evidence shadow, Evidence health, Evidence sinceDegraded) {
    public record Evidence(long observations, long successes, long sessions, long timeBuckets,
            double maximumSessionShare, double burstShare, long agreements) {
        public static Evidence empty() { return new Evidence(0, 0, 0, 0, 0, 0, 0); }
        public double successRate() { return observations == 0 ? 0 : (double) successes / observations; }
        public double agreementRate() { return observations == 0 ? 0 : (double) agreements / observations; }
    }
}
