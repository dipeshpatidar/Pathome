package com.indore.pathome.spaces.service.searchlearning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static com.indore.pathome.spaces.service.searchlearning.AliasPolicyDecision.Action.*;
import static com.indore.pathome.spaces.service.searchlearning.AliasPolicyDecision.Reason.*;
import static com.indore.pathome.spaces.service.searchlearning.AliasLifecycle.*;

/** Pure mandatory gates: no persistence, clocks, network or weighted master score. */
public final class AutonomousAliasPolicy {
    private final AutonomousLearningProperties p;
    public AutonomousAliasPolicy(AutonomousLearningProperties p) { p.validate(); this.p = p; }
    public AliasPolicyDecision evaluate(AliasEvidenceSnapshot s) {
        double bound = WilsonConfidence.lowerBound(s.learning().successes(), s.learning().observations(), p.getConfidenceLevel());
        if (!p.isEnabled()) return decision(PAUSED, s.state(), bound, DISABLED_BY_CONFIGURATION);
        if (s.manualLock() || s.state() == MANUALLY_DISABLED)
            return decision(BLOCKED_MANUAL_DISABLE, MANUALLY_DISABLED, bound, MANUAL_DISABLE_LOCK);
        if (s.state() == ACTIVE || s.state() == DEGRADED) {
            if (!s.canonicalValid() || s.deterministicConflict() || s.activeConflict())
                return decision(AUTO_DISABLE, AUTO_DISABLED, bound, !s.canonicalValid() ? INVALID_CANONICAL_TARGET
                        : s.deterministicConflict() ? DETERMINISTIC_CONFLICT : CONFLICTING_ACTIVE_ALIAS);
            var h = s.health();
            if (h.observations() < p.getMinimumHealthEvidence() || h.sessions() < p.getMinimumHealthEvidence())
                return decision(KEEP_ACTIVE, s.state(), bound, INSUFFICIENT_RECENT_HEALTH);
            if (s.state() == ACTIVE && h.successRate() < p.getDegradationThreshold())
                return decision(DEGRADE, DEGRADED, bound, RECENT_HEALTH_DEGRADED);
            if (s.state() == DEGRADED) {
                if (h.successRate() >= p.getRecoveryThreshold()) return decision(RECOVER, ACTIVE, bound, RECENT_HEALTH_RECOVERED);
                if (Duration.between(s.stateSince(), s.now()).compareTo(p.getDegradationCooldown()) >= 0
                        && s.sinceDegraded().sessions() >= p.getMinimumHealthEvidence()
                        && s.sinceDegraded().successRate() < p.getDisableThreshold()
                        && h.successRate() < p.getDisableThreshold())
                    return decision(AUTO_DISABLE, AUTO_DISABLED, bound, RECENT_HEALTH_UNHEALTHY);
            }
            return decision(KEEP_ACTIVE, s.state(), bound, HEALTHY);
        }
        if (s.state() == AUTO_DISABLED) {
            if (Duration.between(s.stateSince(), s.now()).compareTo(p.getReactivationCooldown()) < 0)
                return decision(WAIT_COOLDOWN, AUTO_DISABLED, bound, COOLDOWN);
            return decision(RESET_FOR_RELEARNING, EVIDENCE_BUILDING, bound, FRESH_EVIDENCE_REQUIRED);
        }
        List<AliasPolicyDecision.Reason> reasons = new ArrayList<>();
        if (!p.getEntityAllowlist().contains(s.entityType())) reasons.add(UNSUPPORTED_ENTITY);
        if (!s.canonicalValid()) reasons.add(INVALID_CANONICAL_TARGET);
        if (s.deterministicConflict()) reasons.add(DETERMINISTIC_CONFLICT);
        if (s.activeConflict()) reasons.add(CONFLICTING_ACTIVE_ALIAS);
        var e = s.learning();
        if (e.observations() < p.getMinimumEvidence()) reasons.add(INSUFFICIENT_EVIDENCE);
        if (e.sessions() < p.getMinimumUniqueSessions()) reasons.add(INSUFFICIENT_UNIQUE_SESSIONS);
        if (s.firstEvidence() == null || Duration.between(s.firstEvidence(), s.now()).compareTo(p.getMinimumCandidateAge()) < 0)
            reasons.add(CANDIDATE_TOO_NEW);
        if (e.timeBuckets() < p.getMinimumTimeBuckets()) reasons.add(INSUFFICIENT_TIME_DIVERSITY);
        if (e.successRate() < p.getMinimumTargetDominance()) reasons.add(COMPETING_TARGETS);
        if (bound < p.getMinimumConfidenceLowerBound()) reasons.add(LOW_CONFIDENCE_BOUND);
        if (e.maximumSessionShare() > p.getMaximumSessionShare()) reasons.add(SUSPICIOUS_SESSION_CONCENTRATION);
        if (e.burstShare() > p.getBurstThreshold()) reasons.add(BURST_CONCENTRATION);
        if (!reasons.isEmpty()) return new AliasPolicyDecision(BUILD_EVIDENCE, EVIDENCE_BUILDING, List.copyOf(reasons), bound);
        if (s.state() != SHADOW) return decision(ENTER_SHADOW, SHADOW, bound, HEALTHY_FOR_PROMOTION);
        var shadow = s.shadow();
        if (shadow.observations() < p.getMinimumShadowEvaluations() || shadow.sessions() < p.getMinimumShadowEvaluations())
            reasons.add(INSUFFICIENT_SHADOW_VOLUME);
        if (shadow.agreementRate() < p.getMinimumShadowAgreement()) reasons.add(LOW_SHADOW_AGREEMENT);
        if (WilsonConfidence.lowerBound(shadow.agreements(), shadow.observations(), p.getConfidenceLevel()) < p.getMinimumConfidenceLowerBound())
            reasons.add(LOW_SHADOW_CONFIDENCE);
        if (shadow.maximumSessionShare() > p.getMaximumSessionShare()) reasons.add(SUSPICIOUS_SESSION_CONCENTRATION);
        if (shadow.burstShare() > p.getBurstThreshold()) reasons.add(BURST_CONCENTRATION);
        if (shadow.timeBuckets() < p.getMinimumTimeBuckets()) reasons.add(INSUFFICIENT_TIME_DIVERSITY);
        return reasons.isEmpty() ? decision(PROMOTE, ACTIVE, bound, HEALTHY_FOR_PROMOTION)
                : new AliasPolicyDecision(KEEP_SHADOW, SHADOW, List.copyOf(reasons), bound);
    }
    private AliasPolicyDecision decision(AliasPolicyDecision.Action a, AliasLifecycle state, double b, AliasPolicyDecision.Reason r) {
        return new AliasPolicyDecision(a, state, List.of(r), b);
    }
}
