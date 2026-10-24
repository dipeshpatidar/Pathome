package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadSourceEvent;
import com.indore.pathome.spaces.entity.LeadSourceEventKind;
import com.indore.pathome.spaces.entity.LeadSourceEventState;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetaLeadInboxServiceTest {
    private final LeadSourceEventRepository sourceEvents = mock(LeadSourceEventRepository.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MetaLeadInboxService inbox = new MetaLeadInboxService(sourceEvents, clock, jdbc);

    @Test
    void identicalReplayDoesNotCreateASecondSourceEvent() {
        LeadSourceEvent existing = new LeadSourceEvent(UUID.randomUUID(), "META_LEADGEN", "page-1",
                "lead-1", LeadSourceEventKind.META_LEADGEN, "a".repeat(64), "EVENT_CANONICAL_V2",
                Instant.now(),
                LeadSourceEventState.QUARANTINED, "SOURCE_ACTIVATION_NOT_APPROVED");
        when(sourceEvents.findByProviderAndProviderAccountIdAndExternalEventId(
                "META_LEADGEN", "page-1", "lead-1")).thenReturn(Optional.of(existing));

        inbox.acceptMetaLead("page-1", "lead-1", "form-1", "a".repeat(64), "EVENT_CANONICAL_V2");

        verify(sourceEvents, never()).saveAndFlush(any());
    }

    @Test
    void sameExternalEventWithDifferentPayloadIsRejected() {
        LeadSourceEvent existing = new LeadSourceEvent(UUID.randomUUID(), "META_LEADGEN", "page-1",
                "lead-1", LeadSourceEventKind.META_LEADGEN, "a".repeat(64), "EVENT_CANONICAL_V2",
                Instant.now(),
                LeadSourceEventState.QUARANTINED, "SOURCE_ACTIVATION_NOT_APPROVED");
        when(sourceEvents.findByProviderAndProviderAccountIdAndExternalEventId(
                "META_LEADGEN", "page-1", "lead-1")).thenReturn(Optional.of(existing));

        assertThrows(VisitOperationsConflictException.class,
                () -> inbox.acceptMetaLead("page-1", "lead-1", "form-1", "b".repeat(64),
                        "EVENT_CANONICAL_V2"));
        verify(sourceEvents, never()).saveAndFlush(any());
    }

    @Test
    void legacyEnvelopeHashIsPreservedForExplicitReconciliationInsteadOfFalseTampering() {
        LeadSourceEvent existing = new LeadSourceEvent(UUID.randomUUID(), "META_LEADGEN", "page-1",
                "lead-1", LeadSourceEventKind.META_LEADGEN, "a".repeat(64), Instant.now(),
                LeadSourceEventState.QUARANTINED, "SOURCE_ACTIVATION_NOT_APPROVED");
        when(sourceEvents.findByProviderAndProviderAccountIdAndExternalEventId(
                "META_LEADGEN", "page-1", "lead-1")).thenReturn(Optional.of(existing));

        inbox.acceptMetaLead("page-1", "lead-1", "form-1", "b".repeat(64),
                "EVENT_CANONICAL_V2");

        verify(sourceEvents).saveAndFlush(existing);
    }
}
