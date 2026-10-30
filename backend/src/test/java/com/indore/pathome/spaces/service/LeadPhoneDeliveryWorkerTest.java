package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class LeadPhoneDeliveryWorkerTest {
    private final LeadPhoneDeliveryClaimService claims = mock(LeadPhoneDeliveryClaimService.class);
    private final LeadPhoneDeliveryCompletionService completions =
            mock(LeadPhoneDeliveryCompletionService.class);
    private final LeadPhoneDeliveryProvider provider = mock(LeadPhoneDeliveryProvider.class);
    private final LeadPhoneDeliveryWorker worker =
            new LeadPhoneDeliveryWorker(claims, completions, provider);

    @Test
    void providerTimeoutIsRecordedUnknownRatherThanVerifiedOrResent() {
        LeadPhoneDeliveryWorker.DispatchTicket ticket = ticket();
        when(provider.enabled()).thenReturn(true);
        when(claims.claimNext()).thenReturn(ticket, (LeadPhoneDeliveryWorker.DispatchTicket) null);
        when(provider.deliver(any())).thenThrow(new IllegalStateException("timeout"));

        worker.dispatchDue();

        verify(completions).complete(eq(ticket.outboxId()),
                eq(LeadPhoneDeliveryState.UNKNOWN), eq(null),
                eq("PROVIDER_OUTCOME_UNKNOWN"), eq(ticket.attemptId()));
        verify(claims, times(2)).claimNext();
    }

    @Test
    void definitiveProviderFailureIsPersistedWithoutChallengeVerification() {
        LeadPhoneDeliveryWorker.DispatchTicket ticket = ticket();
        when(provider.enabled()).thenReturn(true);
        when(claims.claimNext()).thenReturn(ticket, (LeadPhoneDeliveryWorker.DispatchTicket) null);
        when(provider.deliver(any())).thenReturn(
                new LeadPhoneDeliveryProvider.DeliveryResult(
                        LeadPhoneDeliveryState.FAILED, null));

        worker.dispatchDue();

        verify(completions).complete(eq(ticket.outboxId()),
                eq(LeadPhoneDeliveryState.FAILED), eq(null), eq(null),
                eq(ticket.attemptId()));
    }

    @Test
    void safelyRetiredDueRowDoesNotPreventLaterQueuedDispatch() {
        LeadPhoneDeliveryWorker.DispatchTicket ticket = ticket();
        when(provider.enabled()).thenReturn(true);
        when(claims.claimNext()).thenReturn(null, ticket, null);
        when(claims.hasDueWork()).thenReturn(true, false);
        when(provider.deliver(any())).thenReturn(
                new LeadPhoneDeliveryProvider.DeliveryResult(
                        LeadPhoneDeliveryState.ACCEPTED, "simulated-message"));

        worker.dispatchDue();

        verify(provider).deliver(any());
        verify(completions).complete(eq(ticket.outboxId()),
                eq(LeadPhoneDeliveryState.ACCEPTED), eq("simulated-message"), eq(null),
                eq(ticket.attemptId()));
    }

    @Test
    void nullOrMalformedProviderResultIsUnknownAndCannotVerifyTheChallenge() {
        LeadPhoneDeliveryWorker.DispatchTicket ticket = ticket();
        when(provider.enabled()).thenReturn(true);
        when(claims.claimNext()).thenReturn(ticket, (LeadPhoneDeliveryWorker.DispatchTicket) null);
        when(provider.deliver(any())).thenReturn(null);

        worker.dispatchDue();

        verify(completions).complete(eq(ticket.outboxId()),
                eq(LeadPhoneDeliveryState.UNKNOWN), eq(null),
                eq("PROVIDER_OUTCOME_MALFORMED"), eq(ticket.attemptId()));
    }

    @Test
    void dispatchIsBoundedToTenTicketsEvenWhenMoreWorkRemains() {
        LeadPhoneDeliveryWorker.DispatchTicket ticket = ticket();
        when(provider.enabled()).thenReturn(true);
        when(claims.claimNext()).thenReturn(ticket);
        when(provider.deliver(any())).thenReturn(
                new LeadPhoneDeliveryProvider.DeliveryResult(
                        LeadPhoneDeliveryState.ACCEPTED, "simulated-message"));

        worker.dispatchDue();

        verify(claims, times(10)).claimNext();
        verify(provider, times(10)).deliver(any());
        verify(completions, times(10)).complete(any(), any(), any(), any(), any());
    }

    @Test
    void retiredCandidateSpinIsBoundedToOneHundredInspections() {
        when(provider.enabled()).thenReturn(true);
        when(claims.claimNext()).thenReturn(null);
        when(claims.hasDueWork()).thenReturn(true);

        worker.dispatchDue();

        verify(claims, times(100)).claimNext();
        verify(claims, times(100)).hasDueWork();
        verify(provider, never()).deliver(any());
    }

    @Test
    void anAuthoritativeEmptyQueueStopsAfterTheFirstInspection() {
        when(provider.enabled()).thenReturn(true);
        when(claims.claimNext()).thenReturn(null);
        when(claims.hasDueWork()).thenReturn(false);

        worker.dispatchDue();

        verify(claims).claimNext();
        verify(claims).hasDueWork();
        verify(provider, never()).deliver(any());
    }

    @Test
    void completionFailureDoesNotHaltLaterEligibleWork() {
        LeadPhoneDeliveryWorker.DispatchTicket first = ticket();
        LeadPhoneDeliveryWorker.DispatchTicket second = ticket();
        when(provider.enabled()).thenReturn(true);
        when(claims.claimNext()).thenReturn(first, second,
                (LeadPhoneDeliveryWorker.DispatchTicket) null);
        when(claims.hasDueWork()).thenReturn(false);
        when(provider.deliver(any())).thenReturn(
                new LeadPhoneDeliveryProvider.DeliveryResult(
                        LeadPhoneDeliveryState.ACCEPTED, "simulated-message"));
        org.mockito.Mockito.doThrow(new IllegalStateException("completion database failure"))
                .doNothing()
                .when(completions).complete(eq(first.outboxId()), any(), any(), any(),
                        eq(first.attemptId()));

        worker.dispatchDue();

        verify(provider, times(2)).deliver(any());
        verify(completions, times(2)).complete(any(), any(), any(), any(), any());
    }

    private static LeadPhoneDeliveryWorker.DispatchTicket ticket() {
        return new LeadPhoneDeliveryWorker.DispatchTicket(UUID.randomUUID(), UUID.randomUUID(),
                1L, "+919876543210", "000123", "https://app.pathome.test/verify",
                UUID.randomUUID());
    }
}
