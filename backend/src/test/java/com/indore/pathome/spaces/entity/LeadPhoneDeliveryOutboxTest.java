package com.indore.pathome.spaces.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LeadPhoneDeliveryOutboxTest {
    @Test
    void newOutboxEntryIsImmediatelyClaimable() {
        LeadPhoneDeliveryOutbox outbox = new LeadPhoneDeliveryOutbox(UUID.randomUUID(),
                mock(LeadPhoneChallenge.class), "fingerprint", "ciphertext", "iv", "key",
                "SIMULATED", Instant.parse("2026-10-27T10:00:00Z"));

        assertEquals(LeadPhoneDeliveryState.QUEUED, outbox.getState());
        assertEquals(0, outbox.getAttempts());
        assertEquals(null, outbox.getLeaseToken());
        assertEquals(null, outbox.getLeaseUntil());
    }

    @Test
    void outOfOrderFailureCannotDowngradeDeliveredEvidence() {
        LeadPhoneDeliveryOutbox outbox = new LeadPhoneDeliveryOutbox(UUID.randomUUID(),
                mock(LeadPhoneChallenge.class), "fingerprint", "ciphertext", "iv", "key",
                "SIMULATED", Instant.parse("2026-10-27T10:00:00Z"));
        outbox.dispatchAccepted(LeadPhoneDeliveryState.ACCEPTED, "provider-1",
                Instant.parse("2026-10-27T10:00:01Z"));
        outbox.dispatchAccepted(LeadPhoneDeliveryState.DELIVERED, "provider-1",
                Instant.parse("2026-10-27T10:00:02Z"));
        outbox.dispatchAccepted(LeadPhoneDeliveryState.FAILED, null,
                Instant.parse("2026-10-27T10:00:03Z"));

        assertEquals(LeadPhoneDeliveryState.DELIVERED, outbox.getState());
        assertEquals("provider-1", outbox.getProviderMessageId());
        assertTrue(outbox.isPayloadPurged());
    }

    @Test
    void claimAtomicallyMovesQueuedWorkToDispatchingWithOneFence() {
        LeadPhoneDeliveryOutbox outbox = new LeadPhoneDeliveryOutbox(UUID.randomUUID(),
                mock(LeadPhoneChallenge.class), "fingerprint", "ciphertext", "iv", "key",
                "SIMULATED", Instant.parse("2026-10-27T10:00:00Z"));
        UUID attempt = UUID.randomUUID();

        outbox.claim(attempt, Instant.parse("2026-10-27T10:00:45Z"),
                Instant.parse("2026-10-27T10:00:00Z"));

        assertEquals(LeadPhoneDeliveryState.DISPATCHING, outbox.getState());
        assertEquals(attempt, outbox.getLeaseToken());
        assertEquals(1, outbox.getAttempts());
        assertThrows(IllegalStateException.class, () -> outbox.claim(UUID.randomUUID(),
                Instant.parse("2026-10-27T10:00:45Z"),
                Instant.parse("2026-10-27T10:00:01Z")));
        assertFalse(outbox.isPayloadPurged());
    }

    @Test
    void ambiguousAttemptDestroysEnvelopeWithoutResettingLeaseEvidence() {
        LeadPhoneDeliveryOutbox outbox = new LeadPhoneDeliveryOutbox(UUID.randomUUID(),
                mock(LeadPhoneChallenge.class), "fingerprint", "ciphertext", "iv", "key",
                "SIMULATED", Instant.parse("2026-10-27T10:00:00Z"));
        outbox.claim(UUID.randomUUID(), Instant.parse("2026-10-27T10:00:45Z"),
                Instant.parse("2026-10-27T10:00:00Z"));

        outbox.markUnknown("DISPATCH_OUTCOME_UNKNOWN",
                Instant.parse("2026-10-27T10:00:46Z"));

        assertEquals(LeadPhoneDeliveryState.UNKNOWN, outbox.getState());
        assertTrue(outbox.isPayloadPurged());
        assertEquals(null, outbox.getLeaseToken());
    }
}
