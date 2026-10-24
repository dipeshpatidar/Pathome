package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadSourceExceptionView;
import com.indore.pathome.spaces.entity.LeadSourceEvent;
import com.indore.pathome.spaces.entity.LeadSourceEventKind;
import com.indore.pathome.spaces.entity.LeadSourceEventState;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadSourceProcessingServiceTest {
    private final LeadSourceEventRepository events = mock(LeadSourceEventRepository.class);
    private final StaffAccessService staff = mock(StaffAccessService.class);
    private final MetaLeadSourceClient client = mock(MetaLeadSourceClient.class);
    private final LeadDataProtection protection = new LeadDataProtection(
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()),
            "primary");
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final TransactionTemplate transactions = transactionTemplate();
    private final EntityManager entityManager = mock(EntityManager.class);
    private final LeadSourceProcessingService service = new LeadSourceProcessingService(
            events, staff, client, protection, new ObjectMapper(), clock, transactions,
            entityManager);

    @Test
    void controlledExtractionIsRetainedEncryptedAndAwaitsDeliberateResolution() {
        UUID id = UUID.randomUUID();
        LeadSourceEvent event = new LeadSourceEvent(id, "META_LEADGEN", "page-1", "lead-1",
                LeadSourceEventKind.META_LEADGEN, "a".repeat(64), "EVENT_CANONICAL_V2",
                Instant.now(), LeadSourceEventState.QUARANTINED, "SOURCE_ACTIVATION_NOT_APPROVED");
        event.setProviderFormId("form-1");
        when(events.findLockedById(id)).thenReturn(Optional.of(event));
        when(client.isConfigured()).thenReturn(true);
        when(client.fetchLeadPayload("page-1", "lead-1", "form-1"))
                .thenReturn("{\"customerName\":\"Synthetic\"}");
        when(events.saveAndFlush(event)).thenReturn(event);
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:30:00Z"));

        LeadSourceExceptionView view = service.retry(7L, id);

        assertEquals(LeadSourceEventState.AWAITING_REVIEW, view.state());
        assertEquals(1, view.attempts());
        assertEquals(true, view.extractedPayloadRetained());
        assertEquals("MANUAL_RESOLUTION_REQUIRED", view.errorCode());
    }

    @Test
    void nonGlobalActorsCannotReadOrRetrySourceExceptions() {
        doThrow(new AccessDeniedException("Global Staff Admin diagnostics are required"))
                .when(staff).requireGlobalAdmin(7L);
        assertThrows(AccessDeniedException.class, () -> service.retry(7L, UUID.randomUUID()));
        verify(client, never()).fetchLeadPayload(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void anExpiredWorkerCannotOverwriteACompletedRecoveryLease() throws Exception {
        UUID id = UUID.randomUUID();
        LeadSourceEvent event = sourceEvent(id);
        Instant firstClaim = Instant.parse("2026-10-22T04:30:00Z");
        CountDownLatch staleFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseStaleFetch = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();

        when(events.findLockedById(id)).thenReturn(Optional.of(event));
        when(events.saveAndFlush(event)).thenReturn(event);
        when(client.isConfigured()).thenReturn(true);
        when(clock.now()).thenReturn(firstClaim, firstClaim.plusSeconds(360), firstClaim.plusSeconds(360));
        when(client.fetchLeadPayload("page-1", "lead-1", "form-1")).thenAnswer(invocation -> {
            if (fetches.incrementAndGet() == 1) {
                staleFetchStarted.countDown();
                if (!releaseStaleFetch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("The stale source worker was not released");
                }
                return "{\"customerName\":\"Stale worker result\"}";
            }
            return "{\"customerName\":\"Recovered worker result\"}";
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<LeadSourceExceptionView> stale = executor.submit(() -> service.retry(7L, id));
            assertEquals(true, staleFetchStarted.await(5, TimeUnit.SECONDS));

            LeadSourceExceptionView recovered = service.retry(7L, id);
            assertEquals(LeadSourceEventState.AWAITING_REVIEW, recovered.state());
            assertEquals(2, recovered.attempts());

            releaseStaleFetch.countDown();
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> stale.get(5, TimeUnit.SECONDS));
            assertInstanceOf(VisitOperationsConflictException.class, refused.getCause());
            assertEquals(LeadSourceEventState.AWAITING_REVIEW, event.getProcessingState());
            verify(events, times(3)).saveAndFlush(event);
        } finally {
            releaseStaleFetch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void staleFailureAfterANewerSuccessCannotQuarantineTheRecoveredResult() throws Exception {
        UUID id = UUID.randomUUID();
        LeadSourceEvent event = sourceEvent(id);
        Instant firstClaim = Instant.parse("2026-10-22T04:30:00Z");
        CountDownLatch staleFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseStaleFetch = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();

        when(events.findLockedById(id)).thenReturn(Optional.of(event));
        when(events.saveAndFlush(event)).thenReturn(event);
        when(client.isConfigured()).thenReturn(true);
        when(clock.now()).thenReturn(firstClaim, firstClaim.plusSeconds(360),
                firstClaim.plusSeconds(360), firstClaim.plusSeconds(360));
        when(client.fetchLeadPayload("page-1", "lead-1", "form-1")).thenAnswer(invocation -> {
            if (fetches.incrementAndGet() == 1) {
                staleFetchStarted.countDown();
                if (!releaseStaleFetch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("The stale source worker was not released");
                }
                throw new IllegalStateException("Stale extraction failed");
            }
            return "{\"customerName\":\"Recovered worker result\"}";
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<LeadSourceExceptionView> stale = executor.submit(() -> service.retry(7L, id));
            assertEquals(true, staleFetchStarted.await(5, TimeUnit.SECONDS));

            LeadSourceExceptionView recovered = service.retry(7L, id);
            assertEquals(LeadSourceEventState.AWAITING_REVIEW, recovered.state());
            assertEquals(2, recovered.attempts());

            releaseStaleFetch.countDown();
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> stale.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, refused.getCause());
            assertEquals(LeadSourceEventState.AWAITING_REVIEW, event.getProcessingState());
            assertEquals("MANUAL_RESOLUTION_REQUIRED", event.getErrorCode());
        } finally {
            releaseStaleFetch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retryStopsAtTheConfiguredBoundedAttemptLimit() {
        UUID id = UUID.randomUUID();
        LeadSourceEvent event = sourceEvent(id);
        event.incrementAttempts();
        event.incrementAttempts();
        event.incrementAttempts();
        event.setErrorCode("SOURCE_RETRY_EXHAUSTED");
        when(events.findLockedById(id)).thenReturn(Optional.of(event));
        when(client.isConfigured()).thenReturn(true);

        assertThrows(VisitOperationsConflictException.class, () -> service.retry(7L, id));
        verify(client, never()).fetchLeadPayload(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(events, never()).saveAndFlush(any());
        assertEquals(LeadSourceEventState.QUARANTINED, event.getProcessingState());
        assertEquals("SOURCE_RETRY_EXHAUSTED", event.getErrorCode());
    }

    @Test
    void expiredThirdClaimDurablyQuarantinesBeforeConflictAndNeverFetchesAgain() {
        UUID id = UUID.randomUUID();
        LeadSourceEvent event = sourceEvent(id);
        event.incrementAttempts();
        event.incrementAttempts();
        event.incrementAttempts();
        Instant now = Instant.parse("2026-10-22T04:40:00Z");
        event.setProcessingState(LeadSourceEventState.PROCESSING);
        event.lease(UUID.randomUUID(), now.minusSeconds(1));
        when(events.findLockedById(id)).thenReturn(Optional.of(event));
        when(events.saveAndFlush(event)).thenReturn(event);
        when(client.isConfigured()).thenReturn(true);
        when(clock.now()).thenReturn(now);

        assertThrows(VisitOperationsConflictException.class, () -> service.retry(7L, id));

        assertEquals(LeadSourceEventState.QUARANTINED, event.getProcessingState());
        assertEquals("SOURCE_RETRY_EXHAUSTED", event.getErrorCode());
        assertEquals(3, event.getAttempts());
        assertEquals(null, event.getLeaseToken());
        assertEquals(null, event.getLeaseUntil());
        verify(events).saveAndFlush(event);
        verify(client, never()).fetchLeadPayload(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void liveThirdClaimIsNotPrematurelyQuarantined() {
        UUID id = UUID.randomUUID();
        LeadSourceEvent event = sourceEvent(id);
        event.incrementAttempts();
        event.incrementAttempts();
        event.incrementAttempts();
        Instant now = Instant.parse("2026-10-22T04:40:00Z");
        UUID leaseToken = UUID.randomUUID();
        event.setProcessingState(LeadSourceEventState.PROCESSING);
        event.lease(leaseToken, now.plusSeconds(120));
        when(events.findLockedById(id)).thenReturn(Optional.of(event));
        when(client.isConfigured()).thenReturn(true);
        when(clock.now()).thenReturn(now);

        assertThrows(VisitOperationsConflictException.class, () -> service.retry(7L, id));

        assertEquals(LeadSourceEventState.PROCESSING, event.getProcessingState());
        assertEquals(leaseToken, event.getLeaseToken());
        verify(events, never()).saveAndFlush(any());
        verify(client, never()).fetchLeadPayload(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void threeFailedClaimsUseExactlyThreeAttemptsAndPersistExhaustion() {
        UUID id = UUID.randomUUID();
        LeadSourceEvent event = sourceEvent(id);
        when(events.findLockedById(id)).thenReturn(Optional.of(event));
        when(events.saveAndFlush(event)).thenReturn(event);
        when(client.isConfigured()).thenReturn(true);
        when(client.fetchLeadPayload("page-1", "lead-1", "form-1")).thenReturn(" ");
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:40:00Z"));

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThrows(IllegalStateException.class, () -> service.retry(7L, id));
            assertEquals(attempt, event.getAttempts());
            assertEquals(LeadSourceEventState.QUARANTINED, event.getProcessingState());
        }
        assertThrows(VisitOperationsConflictException.class, () -> service.retry(7L, id));

        assertEquals(3, event.getAttempts());
        assertEquals("SOURCE_RETRY_EXHAUSTED", event.getErrorCode());
        verify(client, times(3)).fetchLeadPayload("page-1", "lead-1", "form-1");
    }

    @Test
    void claimAndCompletionHoldAuthorityBeforeTakingTheSourceEventLock() {
        UUID id = UUID.randomUUID();
        LeadSourceEvent event = sourceEvent(id);
        when(events.findLockedById(id)).thenReturn(Optional.of(event));
        when(events.saveAndFlush(event)).thenReturn(event);
        when(client.isConfigured()).thenReturn(true);
        when(client.fetchLeadPayload("page-1", "lead-1", "form-1"))
                .thenReturn("{\"customerName\":\"Synthetic\"}");
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:40:00Z"));

        service.retry(7L, id);

        var order = inOrder(staff, events);
        order.verify(staff).holdGlobalAdminEvidence(7L);
        order.verify(events).findLockedById(id);
        order.verify(staff).holdGlobalAdminEvidence(7L);
        order.verify(events).findLockedById(id);
    }

    @Test
    void encryptionFailureCleansUpTheCurrentLeaseWithoutRetainingPlaintext() {
        UUID id = UUID.randomUUID();
        LeadSourceEvent event = sourceEvent(id);
        LeadDataProtection failingProtection = mock(LeadDataProtection.class);
        LeadSourceProcessingService failingService = new LeadSourceProcessingService(
                events, staff, client, failingProtection, new ObjectMapper(), clock,
                transactions, entityManager);
        when(events.findLockedById(id)).thenReturn(Optional.of(event));
        when(events.saveAndFlush(event)).thenReturn(event);
        when(client.isConfigured()).thenReturn(true);
        when(client.fetchLeadPayload("page-1", "lead-1", "form-1"))
                .thenReturn("{\"customerName\":\"Protected synthetic payload\"}");
        when(failingProtection.protect(
                "{\"customerName\":\"Protected synthetic payload\"}"))
                .thenThrow(new IllegalStateException("Controlled encryption failure"));
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:40:00Z"));

        assertThrows(IllegalStateException.class, () -> failingService.retry(7L, id));

        assertEquals(LeadSourceEventState.QUARANTINED, event.getProcessingState());
        assertEquals("SOURCE_EXTRACTION_FAILED", event.getErrorCode());
        assertEquals(null, event.getLeaseToken());
        assertEquals(false, event.hasProtectedExtractedPayload());
    }

    private static LeadSourceEvent sourceEvent(UUID id) {
        LeadSourceEvent event = new LeadSourceEvent(id, "META_LEADGEN", "page-1", "lead-1",
                LeadSourceEventKind.META_LEADGEN, "a".repeat(64), "EVENT_CANONICAL_V2",
                Instant.now(), LeadSourceEventState.QUARANTINED, "SOURCE_ACTIVATION_NOT_APPROVED");
        event.setProviderFormId("form-1");
        return event;
    }

    private static TransactionTemplate transactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(template).executeWithoutResult(any());
        return template;
    }
}
