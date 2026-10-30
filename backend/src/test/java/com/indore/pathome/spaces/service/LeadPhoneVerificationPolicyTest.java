package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeadPhoneVerificationPolicyTest {
    @Test
    void approvedTimingAndAttemptBoundsMatchOwnerContract() {
        assertEquals(Duration.ofMinutes(5), LeadPhoneVerificationPolicy.CHALLENGE_LIFETIME);
        assertEquals(Duration.ofHours(24), LeadPhoneVerificationPolicy.PROOF_LIFETIME);
        assertEquals(Duration.ofSeconds(60), LeadPhoneVerificationPolicy.RESEND_COOLDOWN);
        assertEquals(5, LeadPhoneVerificationPolicy.MAX_FAILED_ATTEMPTS);
    }

    @Test
    void actorAndDestinationLimitsAreExclusiveAtBoundaryAcrossNodes() {
        assertTrue(LeadPhoneVerificationPolicy.actorSendAllowed(19, 199));
        assertFalse(LeadPhoneVerificationPolicy.actorSendAllowed(20, 0));
        assertFalse(LeadPhoneVerificationPolicy.actorSendAllowed(0, 200));

        assertTrue(LeadPhoneVerificationPolicy.destinationSendAllowed(2, 9));
        assertFalse(LeadPhoneVerificationPolicy.destinationSendAllowed(3, 0));
        assertFalse(LeadPhoneVerificationPolicy.destinationSendAllowed(0, 10));
    }
}
