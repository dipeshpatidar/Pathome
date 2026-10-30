package com.indore.pathome.spaces.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class LeadPhoneChallengeTest {
    @Test
    void wrongAttemptsAreMonotonicAndExhaustAtFive() {
        LeadPhoneChallenge challenge = challenge();
        for (int attempt = 1; attempt <= 4; attempt++) {
            challenge.recordFailedAttempt();
            assertEquals(attempt, challenge.getFailedAttempts());
            assertEquals(LeadPhoneChallengeState.PENDING, challenge.getState());
        }
        challenge.recordFailedAttempt();
        assertEquals(5, challenge.getFailedAttempts());
        assertEquals(LeadPhoneChallengeState.EXHAUSTED, challenge.getState());
        challenge.recordFailedAttempt();
        assertEquals(5, challenge.getFailedAttempts());
    }

    @Test
    void verifiedProofAndTransportTruthRemainIndependent() {
        LeadPhoneChallenge challenge = challenge();
        challenge.verify(LeadPhoneEntryMethod.CUSTOMER_FORM,
                Instant.parse("2026-10-27T10:00:05Z"));
        challenge.updateDelivery(LeadPhoneDeliveryState.UNKNOWN);

        assertEquals(LeadPhoneChallengeState.VERIFIED, challenge.getState());
        assertEquals(LeadPhoneEntryMethod.CUSTOMER_FORM, challenge.getEntryMethod());
        assertEquals(LeadPhoneDeliveryState.UNKNOWN, challenge.getDeliveryState());
        assertThrows(IllegalStateException.class,
                () -> challenge.verify(LeadPhoneEntryMethod.OE_ENTERED,
                        Instant.parse("2026-10-27T10:00:06Z")));
    }

    @Test
    void laterAmbiguousDeliveryCannotDowngradeAcceptedTransportTruth() {
        LeadPhoneChallenge challenge = challenge();
        challenge.updateDelivery(LeadPhoneDeliveryState.ACCEPTED);
        challenge.updateDelivery(LeadPhoneDeliveryState.UNKNOWN);
        challenge.updateDelivery(LeadPhoneDeliveryState.FAILED);
        assertEquals(LeadPhoneDeliveryState.ACCEPTED, challenge.getDeliveryState());
    }

    private static LeadPhoneChallenge challenge() {
        return new LeadPhoneChallenge(UUID.randomUUID(), mock(LeadRoutingQueue.class),
                mock(LeadSourceEvent.class), "ENQUIRY_PHONE_POSSESSION",
                "fingerprint", 0L, "**********3210", 1, "context", "digest", "token",
                LeadPhoneDeliveryState.QUEUED, "SIMULATED", mock(User.class),
                Instant.parse("2026-10-27T10:00:00Z"),
                Instant.parse("2026-10-27T10:05:00Z"));
    }
}
