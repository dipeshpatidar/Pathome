package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadSourceExceptionPage;
import com.indore.pathome.spaces.dto.crm.LeadSourceExceptionView;
import com.indore.pathome.spaces.entity.LeadSourceEvent;
import com.indore.pathome.spaces.entity.LeadSourceEventState;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

@Service
public class LeadSourceProcessingService {
    private static final int MAX_EXTRACTED_PAYLOAD_CHARS = 1_000_000;
    private static final int MAX_ATTEMPTS = 3;

    private final LeadSourceEventRepository sourceEvents;
    private final StaffAccessService staffAccess;
    private final MetaLeadSourceClient sourceClient;
    private final LeadDataProtection protection;
    private final ObjectMapper objectMapper;
    private final DatabaseClock databaseClock;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;

    public LeadSourceProcessingService(LeadSourceEventRepository sourceEvents,
                                       StaffAccessService staffAccess,
                                       MetaLeadSourceClient sourceClient,
                                       LeadDataProtection protection,
                                       ObjectMapper objectMapper,
                                       DatabaseClock databaseClock,
                                       TransactionTemplate transactions,
                                       EntityManager entityManager) {
        this.sourceEvents = sourceEvents;
        this.staffAccess = staffAccess;
        this.sourceClient = sourceClient;
        this.protection = protection;
        this.objectMapper = objectMapper;
        this.databaseClock = databaseClock;
        this.transactions = transactions;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public LeadSourceExceptionPage exceptions(Long actorId, int page, int size) {
        requireGlobalDiagnostics(actorId);
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be nonnegative and size must be 1..100");
        }
        Page<LeadSourceEvent> events = sourceEvents.findExceptionPage(PageRequest.of(page, size));
        return new LeadSourceExceptionPage(events.getContent().stream().map(this::view).toList(),
                events.getTotalElements(), page, size, events.getTotalPages());
    }

    public LeadSourceExceptionView retry(Long actorId, UUID eventId) {
        requireGlobalDiagnostics(actorId);
        if (!sourceClient.isConfigured()) {
            throw new com.indore.pathome.spaces.exception.LeadDataProtectionUnavailableException(
                    "Meta source extraction is not activated for this environment");
        }
        SourceClaim sourceClaim = transactions.execute(status -> claim(actorId, eventId));
        if (sourceClaim.job() == null) {
            throw new VisitOperationsConflictException(
                    "Source extraction retry limit reached; manual reconciliation is required");
        }
        SourceJob job = sourceClaim.job();
        String payload;
        try {
            payload = sourceClient.fetchLeadPayload(job.accountId(), job.externalId(), job.formId());
        } catch (RuntimeException failure) {
            throw failClaim(actorId, eventId, job.leaseToken(), failure);
        }
        if (payload == null || payload.isBlank() || payload.length() > MAX_EXTRACTED_PAYLOAD_CHARS) {
            throw failClaim(actorId, eventId, job.leaseToken(),
                    new IllegalStateException("Source extraction returned an invalid payload"));
        }
        try {
            if (!objectMapper.readTree(payload).isObject()) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw failClaim(actorId, eventId, job.leaseToken(),
                    new IllegalStateException("Source extraction returned malformed data"));
        }
        LeadDataProtection.ProtectedValue protectedPayload;
        try {
            protectedPayload = protection.protect(payload);
        } catch (RuntimeException failure) {
            throw failClaim(actorId, eventId, job.leaseToken(), failure);
        }
        try {
            return transactions.execute(status ->
                    complete(actorId, eventId, job.leaseToken(), protectedPayload));
        } catch (RuntimeException failure) {
            throw failClaim(actorId, eventId, job.leaseToken(), failure);
        }
    }

    private SourceClaim claim(Long actorId, UUID eventId) {
        holdAndRequireGlobalDiagnostics(actorId);
        entityManager.clear();
        LeadSourceEvent event = sourceEvents.findLockedById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("Source event not found"));
        requireGlobalDiagnostics(actorId);
        if (event.getProcessingState() == LeadSourceEventState.PROCESSED
                || event.getProcessingState() == LeadSourceEventState.AWAITING_REVIEW) {
            throw new VisitOperationsConflictException("Processed source events cannot be retried");
        }
        Instant now = databaseClock.now();
        if (event.getProcessingState() == LeadSourceEventState.PROCESSING
                && event.getLeaseUntil() != null && event.getLeaseUntil().isAfter(now)) {
            throw new VisitOperationsConflictException("This source event is already being processed");
        }
        if (event.getAttempts() >= MAX_ATTEMPTS) {
            if (event.getProcessingState() == LeadSourceEventState.PROCESSING) {
                quarantineExhausted(event, now);
                sourceEvents.saveAndFlush(event);
            }
            return new SourceClaim(null);
        }
        if (event.getProviderFormId() == null || event.getProviderFormId().isBlank()) {
            throw new VisitOperationsConflictException(
                    "This source event has no retained form identity and requires manual reconciliation");
        }
        event.incrementAttempts();
        event.setProcessingState(LeadSourceEventState.PROCESSING);
        event.setErrorCode(null);
        event.setAvailableAt(now);
        event.lease(UUID.randomUUID(), now.plus(Duration.ofMinutes(5)));
        event.touch(now);
        sourceEvents.saveAndFlush(event);
        return new SourceClaim(new SourceJob(event.getId(), event.getProviderAccountId(),
                event.getExternalEventId(), event.getProviderFormId(), event.getLeaseToken()));
    }

    private LeadSourceExceptionView complete(Long actorId, UUID eventId, UUID leaseToken,
                                             LeadDataProtection.ProtectedValue protectedPayload) {
        holdAndRequireGlobalDiagnostics(actorId);
        entityManager.clear();
        LeadSourceEvent event = sourceEvents.findLockedById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("Source event not found"));
        requireGlobalDiagnostics(actorId);
        requireCurrentLease(event, leaseToken);
        event.setExtractedPayload(protectedPayload.ciphertext(), protectedPayload.iv(),
                protectedPayload.keyId());
        event.setProcessingState(LeadSourceEventState.AWAITING_REVIEW);
        event.setErrorCode("MANUAL_RESOLUTION_REQUIRED");
        event.clearLease();
        event.touch(databaseClock.now());
        sourceEvents.saveAndFlush(event);
        return view(event);
    }

    private void markFailed(Long actorId, UUID eventId, UUID leaseToken) {
        holdAndRequireGlobalDiagnostics(actorId);
        entityManager.clear();
        LeadSourceEvent event = sourceEvents.findLockedById(eventId).orElse(null);
        requireGlobalDiagnostics(actorId);
        if (event == null || !hasCurrentLease(event, leaseToken)) return;
        event.setProcessingState(LeadSourceEventState.QUARANTINED);
        event.setErrorCode(event.getAttempts() >= MAX_ATTEMPTS
                ? "SOURCE_RETRY_EXHAUSTED" : "SOURCE_EXTRACTION_FAILED");
        Instant now = databaseClock.now();
        event.setAvailableAt(now);
        event.clearLease();
        event.touch(now);
        sourceEvents.saveAndFlush(event);
    }

    private RuntimeException failClaim(Long actorId, UUID eventId, UUID leaseToken,
                                       RuntimeException failure) {
        try {
            transactions.executeWithoutResult(status -> markFailed(actorId, eventId, leaseToken));
        } catch (RuntimeException cleanupFailure) {
            if (cleanupFailure instanceof AccessDeniedException) {
                return cleanupFailure;
            }
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        return failure;
    }

    private void quarantineExhausted(LeadSourceEvent event, Instant now) {
        event.setProcessingState(LeadSourceEventState.QUARANTINED);
        event.setErrorCode("SOURCE_RETRY_EXHAUSTED");
        event.setAvailableAt(now);
        event.clearLease();
        event.touch(now);
    }

    private static void requireCurrentLease(LeadSourceEvent event, UUID leaseToken) {
        if (!hasCurrentLease(event, leaseToken)) {
            throw new VisitOperationsConflictException(
                    "This source event was recovered by a newer processing lease");
        }
    }

    private static boolean hasCurrentLease(LeadSourceEvent event, UUID leaseToken) {
        return leaseToken != null
                && event.getProcessingState() == LeadSourceEventState.PROCESSING
                && leaseToken.equals(event.getLeaseToken());
    }

    private void requireGlobalDiagnostics(Long actorId) {
        staffAccess.requireGlobalAdmin(actorId);
    }

    private void holdAndRequireGlobalDiagnostics(Long actorId) {
        staffAccess.holdGlobalAdminEvidence(actorId);
        requireGlobalDiagnostics(actorId);
    }

    private LeadSourceExceptionView view(LeadSourceEvent event) {
        return new LeadSourceExceptionView(event.getId(), event.getEventKind(),
                event.getSourceChannel(), event.getReceivedAt(), event.getProcessingState(),
                event.getAttempts(), event.getErrorCode(), event.getFingerprintVersion(),
                event.getLead() == null ? null : event.getLead().getId(),
                event.getResolutionLead() == null ? null : event.getResolutionLead().getId(),
                event.hasProtectedExtractedPayload());
    }

    private record SourceJob(UUID eventId, String accountId, String externalId, String formId,
                             UUID leaseToken) {}
    private record SourceClaim(SourceJob job) {}
}
