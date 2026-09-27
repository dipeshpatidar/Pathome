package com.indore.pathome.spaces.service.searchlearning;

import java.time.Instant;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.indore.pathome.spaces.service.searchlearning.AliasLifecycle.*;
import static com.indore.pathome.spaces.service.searchlearning.AliasPolicyDecision.Action.*;
import static com.indore.pathome.spaces.service.searchlearning.AliasPolicyDecision.Reason.*;

class AutonomousAliasPolicyTest {
    final AutonomousLearningProperties p=new AutonomousLearningProperties();
    final Instant now=Instant.parse("2026-09-27T12:00:00Z");
    final AliasEvidenceSnapshot.Evidence good=new AliasEvidenceSnapshot.Evidence(100,100,100,8,.01,.15,100);
    AliasEvidenceSnapshot snapshot(AliasLifecycle state, AliasEvidenceSnapshot.Evidence learning,
            AliasEvidenceSnapshot.Evidence shadow, AliasEvidenceSnapshot.Evidence health, boolean valid,boolean conflict,boolean lock) {
        return new AliasEvidenceSnapshot(state,"LOCALITY",valid,false,conflict,lock,now,now.minus(Duration.ofDays(4)),
                now.minus(Duration.ofDays(2)),learning,shadow,health,health);
    }
    AliasPolicyDecision decide(AliasEvidenceSnapshot s) { return new AutonomousAliasPolicy(p).evaluate(s); }
    @Test void observedWithoutEvidenceBuildsEvidence() {
        var d=decide(snapshot(OBSERVED,AliasEvidenceSnapshot.Evidence.empty(),good,good,true,false,false));
        assertEquals(BUILD_EVIDENCE,d.action()); assertTrue(d.reasons().contains(INSUFFICIENT_EVIDENCE));
    }
    @Test void qualifiedEvidenceEntersShadowBeforePromotion() {
        assertEquals(ENTER_SHADOW,decide(snapshot(EVIDENCE_BUILDING,good,good,good,true,false,false)).action());
        assertEquals(PROMOTE,decide(snapshot(SHADOW,good,good,good,true,false,false)).action());
    }
    @Test void invalidTargetAndConflictBlockPromotion() {
        assertTrue(decide(snapshot(SHADOW,good,good,good,false,false,false)).reasons().contains(INVALID_CANONICAL_TARGET));
        assertTrue(decide(snapshot(SHADOW,good,good,good,true,true,false)).reasons().contains(CONFLICTING_ACTIVE_ALIAS));
    }
    @Test void hundredEventsFromOneSessionCannotMatchIndependentEvidence() {
        var concentrated=new AliasEvidenceSnapshot.Evidence(100,100,1,8,1,.15,100);
        var d=decide(snapshot(OBSERVED,concentrated,good,good,true,false,false));
        assertEquals(BUILD_EVIDENCE,d.action());
        assertTrue(d.reasons().contains(INSUFFICIENT_UNIQUE_SESSIONS));
        assertTrue(d.reasons().contains(SUSPICIOUS_SESSION_CONCENTRATION));
        assertEquals(ENTER_SHADOW,decide(snapshot(OBSERVED,good,good,good,true,false,false)).action());
    }
    @Test void burstAndTimeDiversityBlock() {
        var burst=new AliasEvidenceSnapshot.Evidence(100,100,100,1,.01,1,100);
        var d=decide(snapshot(OBSERVED,burst,good,good,true,false,false));
        assertTrue(d.reasons().contains(BURST_CONCENTRATION)); assertTrue(d.reasons().contains(INSUFFICIENT_TIME_DIVERSITY));
    }
    @Test void ageIsIndependentMandatoryGate() {
        var s=snapshot(OBSERVED,good,good,good,true,false,false);
        var young=new AliasEvidenceSnapshot(s.state(),s.entityType(),true,false,false,false,now,now.minusSeconds(30),now,good,good,good,good);
        assertTrue(decide(young).reasons().contains(CANDIDATE_TOO_NEW));
    }
    @Test void competingTargetsAndConfidenceBlock() {
        var mixed=new AliasEvidenceSnapshot.Evidence(100,70,100,8,.01,.15,70);
        var d=decide(snapshot(SHADOW,mixed,good,good,true,false,false));
        assertEquals(EVIDENCE_BUILDING,d.nextState()); assertTrue(d.reasons().contains(COMPETING_TARGETS));
        assertTrue(d.reasons().contains(LOW_CONFIDENCE_BOUND));
    }
    @Test void shadowRequiresVolumeAgreementConfidence() {
        var small=new AliasEvidenceSnapshot.Evidence(3,3,3,4,.33,.25,3);
        assertTrue(decide(snapshot(SHADOW,good,small,good,true,false,false)).reasons().contains(INSUFFICIENT_SHADOW_VOLUME));
        var wrong=new AliasEvidenceSnapshot.Evidence(100,100,100,8,.01,.15,40);
        var d=decide(snapshot(SHADOW,good,wrong,good,true,false,false));
        assertEquals(KEEP_SHADOW,d.action()); assertTrue(d.reasons().contains(LOW_SHADOW_AGREEMENT));
        assertTrue(d.reasons().contains(LOW_SHADOW_CONFIDENCE));
    }
    @Test void oneBadEventCannotDisable() {
        var h=new AliasEvidenceSnapshot.Evidence(1,0,1,1,1,1,0);
        assertEquals(KEEP_ACTIVE,decide(snapshot(ACTIVE,good,good,h,true,false,false)).action());
    }
    @Test void degradationDisableRecoveryAndCooldown() {
        var h=new AliasEvidenceSnapshot.Evidence(30,10,30,4,.04,.25,10);
        assertEquals(DEGRADE,decide(snapshot(ACTIVE,good,good,h,true,false,false)).action());
        assertEquals(AUTO_DISABLE,decide(snapshot(DEGRADED,good,good,h,true,false,false)).action());
        assertEquals(RECOVER,decide(snapshot(DEGRADED,good,good,good,true,false,false)).action());
        assertEquals(WAIT_COOLDOWN,decide(snapshot(AUTO_DISABLED,good,good,good,true,false,false)).action());
        p.setReactivationCooldown(Duration.ofDays(1));
        assertEquals(RESET_FOR_RELEARNING,decide(snapshot(AUTO_DISABLED,good,good,good,true,false,false)).action());
    }
    @Test void staleCanonicalImmediatelyLosesProductionEffect() {
        assertEquals(AUTO_DISABLE,decide(snapshot(ACTIVE,good,good,good,false,false,false)).action());
    }
    @ParameterizedTest @EnumSource(AliasLifecycle.class)
    void manualLockAlwaysWins(AliasLifecycle state) {
        assertEquals(BLOCKED_MANUAL_DISABLE,decide(snapshot(state,good,good,good,true,false,true)).action());
    }
    @ParameterizedTest @EnumSource(AliasLifecycle.class)
    void killSwitchPausesEveryState(AliasLifecycle state) {
        p.setEnabled(false);
        var d=decide(snapshot(state,good,good,good,false,true,true));
        assertEquals(PAUSED,d.action()); assertEquals(state,d.nextState());
    }
    @Test void invalidConfigurationFailsClosed() {
        p.setDisableThreshold(.99); assertThrows(IllegalArgumentException.class,()->new AutonomousAliasPolicy(p));
    }
}
