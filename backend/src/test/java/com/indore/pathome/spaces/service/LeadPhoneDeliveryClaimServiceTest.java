package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.LeadPhoneChallenge;
import com.indore.pathome.spaces.entity.LeadPhoneChallengeState;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryOutbox;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import com.indore.pathome.spaces.entity.LeadPhoneEntryMethod;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadSourceEvent;
import com.indore.pathome.spaces.entity.LeadSourceEventState;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LeadPhoneChallengeRepository;
import com.indore.pathome.spaces.repository.LeadPhoneDeliveryOutboxRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadPhoneDeliveryClaimServiceTest {
    @Test
    void challengeExpiredDuringSecurityGuardWaitIsSuppressedBeforeDispatch() {
        Fixture fixture = fixture();
        fixture.advanceClockWhenGuardsAcquire(fixture.now().plusSeconds(360));

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertEquals(null, ticket);
        assertEquals(LeadPhoneChallengeState.CANCELLED, fixture.challenge().getState());
        assertEquals(LeadPhoneDeliveryState.SUPPRESSED, fixture.outboxRow().getState());
        assertTrue(fixture.outboxRow().isPayloadPurged());
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void validChallengeUsesPostGuardTimeForLeaseAndStillDispatches() {
        Fixture fixture = fixture();
        Instant postGuard = fixture.now().plusSeconds(30);
        fixture.advanceClockWhenGuardsAcquire(postGuard);

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNotNull(ticket);
        assertEquals(LeadPhoneDeliveryState.DISPATCHING, fixture.outboxRow().getState());
        assertEquals(postGuard.plusSeconds(45), fixture.outboxRow().getLeaseUntil());
        verify(fixture.provider()).enabled();
    }

    @Test
    void closedLeadIsSuppressedEvenWhenItsDueOutboxRemainsQueued() {
        Fixture fixture = fixture();
        when(fixture.lead().getStatus()).thenReturn(LeadStatus.CLOSED);

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertEquals(null, ticket);
        assertEquals(LeadPhoneChallengeState.CANCELLED, fixture.challenge().getState());
        assertEquals(LeadPhoneDeliveryState.SUPPRESSED, fixture.outboxRow().getState());
        assertTrue(fixture.outboxRow().isPayloadPurged());
        verify(fixture.provider(), never()).enabled();
    }

    @ParameterizedTest
    @EnumSource(value = LeadPhoneChallengeState.class, names = {"PENDING", "VERIFIED"})
    void afterHydrationAcceptedCommitWinsWithoutChangingChallengeProof(
            LeadPhoneChallengeState freshChallengeState) {
        Fixture fixture = fixture();
        Instant committedAt = fixture.now().plusSeconds(5);
        expireHydratedDispatch(fixture);
        fixture.commitFreshStateOnRefresh(() -> {
            if (freshChallengeState == LeadPhoneChallengeState.VERIFIED) {
                fixture.challenge().verify(LeadPhoneEntryMethod.OE_ENTERED,
                        committedAt.minusSeconds(1));
            }
            fixture.challenge().updateDelivery(LeadPhoneDeliveryState.ACCEPTED);
            fixture.outboxRow().dispatchAccepted(LeadPhoneDeliveryState.ACCEPTED,
                    "provider-race", committedAt);
        });

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        assertEquals(LeadPhoneDeliveryState.ACCEPTED, fixture.outboxRow().getState());
        assertEquals("provider-race", fixture.outboxRow().getProviderMessageId());
        assertEquals(1, fixture.outboxRow().getAttempts());
        assertNull(fixture.outboxRow().getLeaseToken());
        assertEquals(freshChallengeState, fixture.challenge().getState());
        assertEquals(LeadPhoneDeliveryState.ACCEPTED,
                fixture.challenge().getDeliveryState());
        assertTrue(fixture.outboxRow().isPayloadPurged());
        verify(fixture.provider(), never()).enabled();
    }

    @ParameterizedTest
    @EnumSource(value = LeadPhoneDeliveryState.class, names = {
            "ACCEPTED", "DELIVERED", "FAILED", "UNKNOWN", "SUPPRESSED"
    })
    void staleDiscoveryPreservesEveryCommittedTerminalOutcome(LeadPhoneDeliveryState terminal) {
        Fixture fixture = fixture();
        if (terminal == LeadPhoneDeliveryState.SUPPRESSED) {
            fixture.challenge().cancel(fixture.now());
            fixture.challenge().updateDelivery(terminal);
            fixture.outboxRow().markSuppressed("TERMINAL_CONTROL", fixture.now());
        } else {
            fixture.challenge().updateDelivery(terminal);
            fixture.outboxRow().dispatchAccepted(terminal, "provider-terminal", fixture.now());
        }
        LeadPhoneChallengeState challengeState = fixture.challenge().getState();
        LeadPhoneDeliveryState challengeDelivery = fixture.challenge().getDeliveryState();
        Instant challengeCancelledAt = fixture.challenge().getCancelledAt();
        Instant outboxUpdatedAt = fixture.outboxRow().getUpdatedAt();
        String providerMessageId = fixture.outboxRow().getProviderMessageId();
        int attempts = fixture.outboxRow().getAttempts();

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        assertEquals(terminal, fixture.outboxRow().getState());
        assertEquals(challengeState, fixture.challenge().getState());
        assertEquals(challengeDelivery, fixture.challenge().getDeliveryState());
        assertEquals(challengeCancelledAt, fixture.challenge().getCancelledAt());
        assertEquals(outboxUpdatedAt, fixture.outboxRow().getUpdatedAt());
        assertEquals(providerMessageId, fixture.outboxRow().getProviderMessageId());
        assertEquals(attempts, fixture.outboxRow().getAttempts());
        verify(fixture.provider(), never()).enabled();
    }

    @ParameterizedTest
    @EnumSource(value = LeadPhoneDeliveryState.class, names = {
            "ACCEPTED", "DELIVERED", "FAILED", "UNKNOWN", "SUPPRESSED"
    })
    void afterHydrationTerminalCommitIsNotRewritten(LeadPhoneDeliveryState terminal) {
        Fixture fixture = fixture();
        Instant committedAt = fixture.now().plusSeconds(6);
        expireHydratedDispatch(fixture);
        fixture.commitFreshStateOnRefresh(() -> {
            fixture.challenge().verify(LeadPhoneEntryMethod.OE_ENTERED,
                    committedAt.minusSeconds(1));
            fixture.challenge().updateDelivery(terminal);
            if (terminal == LeadPhoneDeliveryState.SUPPRESSED) {
                fixture.outboxRow().markSuppressed("A_COMMITTED_SUPPRESSED", committedAt);
            } else {
                fixture.outboxRow().dispatchAccepted(terminal, "provider-" + terminal,
                        committedAt);
            }
        });

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        assertEquals(terminal, fixture.outboxRow().getState());
        assertEquals(terminal, fixture.challenge().getDeliveryState());
        assertEquals(LeadPhoneChallengeState.VERIFIED, fixture.challenge().getState());
        assertEquals(committedAt.minusSeconds(1), fixture.challenge().getVerifiedAt());
        assertNull(fixture.challenge().getCancelledAt());
        assertEquals(1, fixture.outboxRow().getAttempts());
        assertNull(fixture.outboxRow().getLeaseToken());
        assertNull(fixture.outboxRow().getLeaseUntil());
        assertTrue(fixture.outboxRow().isPayloadPurged());
        if (terminal != LeadPhoneDeliveryState.SUPPRESSED) {
            assertEquals("provider-" + terminal,
                    fixture.outboxRow().getProviderMessageId());
        }
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void staleDiscoveryCannotStealOrRewriteAnActiveDispatchLease() {
        Fixture fixture = fixture();
        UUID leaseToken = UUID.randomUUID();
        Instant leaseUntil = fixture.now().plusSeconds(45);
        fixture.outboxRow().claim(leaseToken, leaseUntil, fixture.now());

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        assertEquals(LeadPhoneDeliveryState.DISPATCHING, fixture.outboxRow().getState());
        assertEquals(leaseToken, fixture.outboxRow().getLeaseToken());
        assertEquals(leaseUntil, fixture.outboxRow().getLeaseUntil());
        assertEquals(1, fixture.outboxRow().getAttempts());
        assertEquals(LeadPhoneChallengeState.PENDING, fixture.challenge().getState());
        assertEquals(LeadPhoneDeliveryState.QUEUED, fixture.challenge().getDeliveryState());
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void afterHydrationRefreshRevealsActiveLeaseWithoutStealingIt() {
        Fixture fixture = fixture();
        UUID leaseToken = UUID.randomUUID();
        Instant leaseUntil = fixture.now().plusSeconds(45);
        fixture.outboxRow().claim(leaseToken, leaseUntil, fixture.now());

        AtomicBoolean refreshed = new AtomicBoolean();
        LeadPhoneDeliveryOutbox staleProjection = spy(fixture.outboxRow());
        doAnswer(ignored -> refreshed.get()
                ? fixture.outboxRow().getState()
                : LeadPhoneDeliveryState.DISPATCHING)
                .when(staleProjection).getState();
        doAnswer(ignored -> refreshed.get()
                ? fixture.outboxRow().getLeaseUntil()
                : fixture.now().minusSeconds(1))
                .when(staleProjection).getLeaseUntil();
        fixture.returnLockedOutbox(staleProjection);
        fixture.commitFreshStateOnRefresh(() -> refreshed.set(true));

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertTrue(refreshed.get());
        assertNull(ticket);
        assertEquals(LeadPhoneDeliveryState.DISPATCHING, fixture.outboxRow().getState());
        assertEquals(leaseToken, fixture.outboxRow().getLeaseToken());
        assertEquals(leaseUntil, fixture.outboxRow().getLeaseUntil());
        assertEquals(1, fixture.outboxRow().getAttempts());
        assertEquals(LeadPhoneChallengeState.PENDING, fixture.challenge().getState());
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void staleTerminalProjectionCannotAbandonFreshQueuedWork() {
        Fixture fixture = fixture();
        AtomicBoolean refreshed = new AtomicBoolean();
        LeadPhoneDeliveryOutbox staleProjection = spy(fixture.outboxRow());
        doAnswer(ignored -> refreshed.get()
                ? fixture.outboxRow().getState()
                : LeadPhoneDeliveryState.ACCEPTED)
                .when(staleProjection).getState();
        fixture.returnLockedOutbox(staleProjection);
        fixture.commitFreshStateOnRefresh(() -> refreshed.set(true));

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertTrue(refreshed.get());
        assertNotNull(ticket);
        verify(staleProjection).claim(any(UUID.class), any(Instant.class),
                any(Instant.class));
        assertNotNull(staleProjection.getLeaseUntil());
        assertEquals(1, staleProjection.getAttempts());
        verify(fixture.provider()).enabled();
    }

    @Test
    void exactLeaseBoundaryIsTreatedAsExpiredAndNeverResent() {
        Fixture fixture = fixture();
        fixture.outboxRow().claim(UUID.randomUUID(), fixture.now(),
                fixture.now().minusSeconds(45));

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        assertEquals(LeadPhoneDeliveryState.UNKNOWN, fixture.outboxRow().getState());
        assertEquals(LeadPhoneDeliveryState.UNKNOWN,
                fixture.challenge().getDeliveryState());
        assertEquals(LeadPhoneChallengeState.PENDING, fixture.challenge().getState());
        assertNull(fixture.outboxRow().getLeaseToken());
        assertNull(fixture.outboxRow().getLeaseUntil());
        assertTrue(fixture.outboxRow().isPayloadPurged());
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void legacyQueuedRowWithExpiredLeaseIsRetiredAsUnknown() {
        Fixture fixture = fixture();
        fixture.outboxRow().claim(UUID.randomUUID(), fixture.now().minusSeconds(1),
                fixture.now().minusSeconds(45));
        LeadPhoneDeliveryOutbox legacyProjection = spy(fixture.outboxRow());
        doAnswer(ignored -> LeadPhoneDeliveryState.QUEUED)
                .when(legacyProjection).getState();
        doAnswer(ignored -> fixture.now().minusSeconds(1))
                .when(legacyProjection).getLeaseUntil();
        fixture.returnLockedOutbox(legacyProjection);

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        verify(legacyProjection).markUnknown(eq("DISPATCH_OUTCOME_UNKNOWN"),
                any(Instant.class));
        assertEquals(LeadPhoneDeliveryState.UNKNOWN,
                fixture.challenge().getDeliveryState());
        assertTrue(legacyProjection.isPayloadPurged());
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void dispatchingRowWithoutLeaseFailsClosedAsUnknown() {
        Fixture fixture = fixture();
        fixture.outboxRow().claim(UUID.randomUUID(), fixture.now().plusSeconds(45),
                fixture.now());
        LeadPhoneDeliveryOutbox malformedProjection = spy(fixture.outboxRow());
        doAnswer(ignored -> null).when(malformedProjection).getLeaseUntil();
        fixture.returnLockedOutbox(malformedProjection);

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        verify(malformedProjection).markUnknown(eq("DISPATCH_OUTCOME_UNKNOWN"),
                any(Instant.class));
        assertEquals(LeadPhoneDeliveryState.UNKNOWN,
                fixture.challenge().getDeliveryState());
        assertTrue(malformedProjection.isPayloadPurged());
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void missingChallengeRetiresOutboxWithoutLeakingOrWritingState() {
        Fixture fixture = fixture();
        when(fixture.challenges().findById(fixture.challenge().getId()))
                .thenReturn(Optional.empty());

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        verify(fixture.outboxRepository()).deleteById(fixture.outboxRow().getId());
        verify(fixture.challenges(), never()).saveAndFlush(any());
        verify(fixture.outboxRepository(), never()).saveAndFlush(any());
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void outboxDisappearingBeforeLockedReadReturnsWithoutMutation() {
        Fixture fixture = fixture();
        when(fixture.outboxRepository().findLockedById(fixture.outboxRow().getId()))
                .thenReturn(Optional.empty());

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        verify(fixture.challenges(), never()).saveAndFlush(any());
        verify(fixture.outboxRepository(), never()).saveAndFlush(any());
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void challengeDisappearingAfterSeedRetiresOutboxWithoutContextWrite() {
        Fixture fixture = fixture();
        when(fixture.challenges().findLockedById(fixture.challenge().getId()))
                .thenReturn(Optional.empty());

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        verify(fixture.outboxRepository()).deleteById(fixture.outboxRow().getId());
        verify(fixture.challenges(), never()).saveAndFlush(any());
        verify(fixture.outboxRepository(), never()).saveAndFlush(any());
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void dueIdWithoutHydratableOutboxReturnsWithoutSideEffects() {
        Fixture fixture = fixture();
        when(fixture.outboxRepository().findById(fixture.outboxRow().getId()))
                .thenReturn(Optional.empty());

        LeadPhoneDeliveryWorker.DispatchTicket ticket = fixture.service().claimNext();

        assertNull(ticket);
        verify(fixture.challenges(), never()).saveAndFlush(any());
        verify(fixture.outboxRepository(), never()).saveAndFlush(any());
        verify(fixture.outboxRepository(), never()).deleteById(any(UUID.class));
        verify(fixture.provider(), never()).enabled();
    }

    @Test
    void lockedDomainEntitiesAreRefreshedBeforeTerminalClassification() {
        Fixture fixture = fixture();

        fixture.service().claimNext();

        InOrder order = inOrder(fixture.leads(), fixture.sourceEvents(),
                fixture.challenges(), fixture.outboxRepository(), fixture.entityManager());
        order.verify(fixture.leads()).findLockedById(1L);
        order.verify(fixture.sourceEvents()).findLockedById(fixture.sourceId());
        order.verify(fixture.challenges()).findLockedById(fixture.challenge().getId());
        order.verify(fixture.outboxRepository()).findLockedById(fixture.outboxRow().getId());
        order.verify(fixture.entityManager()).refresh(fixture.challenge(),
                LockModeType.PESSIMISTIC_WRITE);
        order.verify(fixture.entityManager()).refresh(fixture.outboxRow(),
                LockModeType.PESSIMISTIC_WRITE);
        assertFalse(fixture.outboxRow().isPayloadPurged());
    }

    @Test
    void refreshFailureFailsClosedBeforeAnyStateWrite() {
        Fixture fixture = fixture();
        expireHydratedDispatch(fixture);
        doThrow(new PersistenceException("refresh failed"))
                .when(fixture.entityManager()).refresh(any(Object.class),
                        eq(LockModeType.PESSIMISTIC_WRITE));

        assertThrows(PersistenceException.class, fixture.service()::claimNext);

        verify(fixture.challenges(), never()).saveAndFlush(any());
        verify(fixture.outboxRepository(), never()).saveAndFlush(any());
        assertFalse(fixture.outboxRow().isPayloadPurged());
        verify(fixture.provider(), never()).enabled();
    }

    private static void expireHydratedDispatch(Fixture fixture) {
        fixture.outboxRow().claim(UUID.randomUUID(), fixture.now().minusSeconds(1),
                fixture.now().minusSeconds(46));
    }

    private Fixture fixture() {
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        LeadPhoneDeliveryOutboxRepository outbox = mock(LeadPhoneDeliveryOutboxRepository.class);
        LeadPhoneChallengeRepository challenges = mock(LeadPhoneChallengeRepository.class);
        LeadRoutingQueueRepository leads = mock(LeadRoutingQueueRepository.class);
        LeadSourceEventRepository sourceEvents = mock(LeadSourceEventRepository.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        LeadDataProtection protection = mock(LeadDataProtection.class);
        LeadPhoneDeliveryProvider provider = mock(LeadPhoneDeliveryProvider.class);
        StaffAccessService staffAccess = mock(StaffAccessService.class);
        OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
        EntityManager entityManager = mock(EntityManager.class);

        User owner = new User();
        owner.setId(5L);
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(1L);
        OperatingTeam team = mock(OperatingTeam.class);
        when(team.getId()).thenReturn(2L);
        LeadRoutingQueue lead = mock(LeadRoutingQueue.class);
        when(lead.getId()).thenReturn(1L);
        when(lead.getStatus()).thenReturn(LeadStatus.QUALIFYING);
        when(lead.getOwner()).thenReturn(owner);
        when(lead.getSupportedCity()).thenReturn(city);
        when(lead.getOperatingTeam()).thenReturn(team);
        when(lead.getContactPhoneRevision()).thenReturn(0L);
        when(lead.getContactPhoneNormalized()).thenReturn("+919876543210");
        when(leads.findLockedById(1L)).thenReturn(Optional.of(lead));

        LeadSourceEvent source = mock(LeadSourceEvent.class);
        UUID sourceId = UUID.randomUUID();
        when(source.getId()).thenReturn(sourceId);
        when(source.getLead()).thenReturn(lead);
        when(source.getProcessingState()).thenReturn(LeadSourceEventState.PROCESSED);
        when(sourceEvents.findLockedById(sourceId)).thenReturn(Optional.of(source));

        LeadPhoneChallenge challenge = new LeadPhoneChallenge(UUID.randomUUID(), lead, source,
                LeadPhoneVerificationService.PURPOSE, "fingerprint", 0L, "**********3210",
                1, "context", "digest", "token", LeadPhoneDeliveryState.QUEUED,
                "SIMULATED", owner, now.minusSeconds(10), now.plusSeconds(300));
        UUID challengeId = challenge.getId();
        LeadPhoneDeliveryOutbox outboxRow = new LeadPhoneDeliveryOutbox(UUID.randomUUID(),
                challenge, "fingerprint", "ciphertext", "iv", "key", "SIMULATED", now);
        UUID outboxId = outboxRow.getId();
        when(outbox.findDueIds(any(), any())).thenReturn(List.of(outboxId));
        when(outbox.findById(outboxId)).thenReturn(Optional.of(outboxRow));
        when(outbox.findLockedById(outboxId)).thenReturn(Optional.of(outboxRow));
        when(challenges.findById(challengeId)).thenReturn(Optional.of(challenge));
        when(challenges.findLockedById(challengeId)).thenReturn(Optional.of(challenge));
        when(protection.keyedDigest(anyString(), anyString())).thenReturn("fingerprint");
        when(protection.reveal(anyString(), anyString(), anyString())).thenReturn("""
                {"code":"000123","confirmationUrl":"https://app.pathome.test/verify"}
                """);
        when(provider.enabled()).thenReturn(true);
        when(provider.name()).thenReturn("SIMULATED");
        when(guards.cityIsActive(1L)).thenReturn(true);
        when(guards.teamIsActive(2L)).thenReturn(true);
        when(guards.teamCityId(2L)).thenReturn(1L);
        when(staffAccess.hasCapabilityAt(5L, StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, 2L)).thenReturn(true);

        AtomicBoolean guardsAcquired = new AtomicBoolean();
        Instant postGuard = now.plusSeconds(30);
        when(clock.now()).thenAnswer(ignored -> guardsAcquired.get() ? postGuard : now);
        doAnswer(ignored -> {
            guardsAcquired.set(true);
            return null;
        }).when(guards).acquire(any(), any(), any());

        LeadPhoneDeliveryClaimService service = new LeadPhoneDeliveryClaimService(
                outbox, challenges, leads, sourceEvents, clock, protection, provider,
                staffAccess, guards, new ObjectMapper(), entityManager);
        return new Fixture(now, service, lead, source, sourceId, challenge, outboxRow,
                provider, guardsAcquired, clock, outbox, challenges, leads, sourceEvents,
                entityManager);
    }

    private record Fixture(
            Instant now,
            LeadPhoneDeliveryClaimService service,
            LeadRoutingQueue lead,
            LeadSourceEvent source,
            UUID sourceId,
            LeadPhoneChallenge challenge,
            LeadPhoneDeliveryOutbox outboxRow,
            LeadPhoneDeliveryProvider provider,
            AtomicBoolean guardsAcquired,
            DatabaseClock clock,
            LeadPhoneDeliveryOutboxRepository outboxRepository,
            LeadPhoneChallengeRepository challenges,
            LeadRoutingQueueRepository leads,
            LeadSourceEventRepository sourceEvents,
            EntityManager entityManager) {
        void advanceClockWhenGuardsAcquire(Instant postGuard) {
            when(clock.now()).thenAnswer(ignored -> guardsAcquired.get() ? postGuard : now);
        }

        void commitFreshStateOnRefresh(Runnable freshState) {
            AtomicBoolean applied = new AtomicBoolean();
            doAnswer(ignored -> {
                if (applied.compareAndSet(false, true)) freshState.run();
                return null;
            }).when(entityManager).refresh(any(Object.class),
                    eq(LockModeType.PESSIMISTIC_WRITE));
        }

        void returnLockedOutbox(LeadPhoneDeliveryOutbox projection) {
            when(outboxRepository.findById(outboxRow.getId()))
                    .thenReturn(Optional.of(projection));
            when(outboxRepository.findLockedById(outboxRow.getId()))
                    .thenReturn(Optional.of(projection));
        }
    }
}
