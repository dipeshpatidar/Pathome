package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadShareEventCommand;
import com.indore.pathome.spaces.dto.crm.LeadShareEventView;
import com.indore.pathome.spaces.dto.crm.LeadSharePrepareCommand;
import com.indore.pathome.spaces.dto.crm.LeadShareHistoryPage;
import com.indore.pathome.spaces.dto.crm.LeadShareView;
import com.indore.pathome.spaces.entity.LeadCommandReceipt;
import com.indore.pathome.spaces.entity.LeadConsentEvidenceMethod;
import com.indore.pathome.spaces.entity.LeadConsentStatus;
import com.indore.pathome.spaces.entity.LeadContactChannel;
import com.indore.pathome.spaces.entity.LeadContactPreference;
import com.indore.pathome.spaces.entity.LeadDestinationChoice;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadShare;
import com.indore.pathome.spaces.entity.LeadShareEvent;
import com.indore.pathome.spaces.entity.LeadShareEventType;
import com.indore.pathome.spaces.entity.LeadShortlist;
import com.indore.pathome.spaces.entity.LeadShortlistItem;
import com.indore.pathome.spaces.entity.LeadShareMode;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.LeadDataProtectionUnavailableException;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadContactPreferenceRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadShareEventRepository;
import com.indore.pathome.spaces.repository.LeadShareRepository;
import com.indore.pathome.spaces.repository.LeadShortlistItemRepository;
import com.indore.pathome.spaces.repository.LeadShortlistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadShareServiceTest {
    private final LeadRoutingQueueRepository leads = mock(LeadRoutingQueueRepository.class);
    private final LeadShortlistRepository shortlists = mock(LeadShortlistRepository.class);
    private final LeadShortlistItemRepository shortlistItems = mock(LeadShortlistItemRepository.class);
    private final LeadContactPreferenceRepository preferences =
            mock(LeadContactPreferenceRepository.class);
    private final LeadShareRepository shares = mock(LeadShareRepository.class);
    private final LeadShareEventRepository shareEvents = mock(LeadShareEventRepository.class);
    private final LeadActivityRepository activities = mock(LeadActivityRepository.class);
    private final LeadCommandReceiptRepository receipts = mock(LeadCommandReceiptRepository.class);
    private final LeadAccessService access = mock(LeadAccessService.class);
    private final StaffAccessService staff = mock(StaffAccessService.class);
    private final OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
    private final OperationalAuditService audit = mock(OperationalAuditService.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final LeadPublicPropertyService publicProperties = mock(LeadPublicPropertyService.class);
    private final LeadDataProtection protection = new LeadDataProtection(
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()),
            "primary");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LeadService readableLeads = mock(LeadService.class);
    private final LeadShareService service = serviceWithOrigin("https://pathome.test");

    private LeadShareService serviceWithOrigin(String origin) {
        return new LeadShareService(leads, shortlists, shortlistItems,
                preferences, shares, shareEvents, readableLeads, activities, receipts, access, staff, guards, audit,
                clock, publicProperties, protection, jdbc, new ObjectMapper(), origin);
    }

    @Test
    void preparedShareUsesOrderedCurrentPublicLinksAndOnlyAMaskedDestination() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        UUID operationId = UUID.randomUUID();
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(operationId, 3L, 20L,
                0L, 1L, LeadShareMode.MANUAL_BUSINESS_HANDOFF);
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicProperties(fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(shares.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LeadShareView view = service.prepare(7L, 10L, command);

        assertEquals(List.of("https://pathome.test/property/11"), view.publicLinks());
        assertEquals("********3210", view.maskedDestination());
        assertEquals(false, view.previewText().contains("+919876543210"));
        verify(receipts).saveAndFlush(any());
        verify(activities).save(any());
    }

    @Test
    void missingPublicOriginIsRejectedBeforeShareOrReceiptPersistenceAndCanRecover() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        UUID operationId = UUID.randomUUID();
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(operationId, 3L, 20L,
                0L, 1L, LeadShareMode.MANUAL_BUSINESS_HANDOFF);
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicProperties(fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(shares.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LeadShareService unavailable = serviceWithOrigin("");
        LeadDataProtectionUnavailableException failure = assertThrows(
                LeadDataProtectionUnavailableException.class,
                () -> unavailable.prepare(7L, 10L, command));

        assertTrue(failure.getMessage().contains("origin is not configured"));
        verify(shares, never()).saveAndFlush(any());
        verify(receipts, never()).saveAndFlush(any());

        LeadShareView recovered = service.prepare(7L, 10L, command);
        assertEquals(List.of("https://pathome.test/property/11"), recovered.publicLinks());
        verify(shares, times(1)).saveAndFlush(any());
        verify(receipts, times(1)).saveAndFlush(any());
    }

    @Test
    void revokedOrDisputedAgreementBlocksSharePreparation() {
        Fixture fixture = fixture(LeadConsentStatus.REVOKED);
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(UUID.randomUUID(), 3L, 20L,
                0L, 1L, LeadShareMode.COPY_LINKS);

        assertThrows(VisitOperationsConflictException.class,
                () -> service.prepare(7L, 10L, command));
        verify(shares, never()).saveAndFlush(any());
    }

    @Test
    void unchangedExactReplayReturnsTheStoredPreviewWithoutWritingAgain() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        AtomicReference<LeadPublicPropertyService.EligibleProperty> current = new AtomicReference<>(
                new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L));
        when(publicProperties.requireCurrentPublicProperties(fixture.lead(), List.of(11L)))
                .thenAnswer(invocation -> List.of(current.get()));
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(UUID.randomUUID(), 3L, 20L,
                0L, 1L, LeadShareMode.COPY_LINKS);

        PreparedShare prepared = prepareAndInstallReplay(fixture, command);
        LeadShareView replay = service.prepare(7L, 10L, command);

        assertEquals(prepared.view(), replay);
        assertEquals(prepared.view().previewText(), prepared.share().getPreviewText());
        verify(shares, times(1)).saveAndFlush(any());
        verify(activities, times(1)).save(any());
    }

    @Test
    void changedTitleMakesAnExactReplayStale() {
        assertChangedCustomerVisibleFactIsRejected(
                new LeadPublicPropertyService.EligibleProperty(
                        11L, "Changed title", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 5L));
    }

    @Test
    void changedRentMakesAnExactReplayStale() {
        assertChangedCustomerVisibleFactIsRejected(
                new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("19000.00"), 5L));
    }

    @Test
    void changedLocationMakesAnExactReplayStale() {
        assertChangedCustomerVisibleFactIsRejected(
                new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Palasia", "2 BHK",
                        new BigDecimal("18000.00"), 5L));
    }

    @Test
    void changedBhkMakesAnExactReplayStale() {
        assertChangedCustomerVisibleFactIsRejected(
                new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "3 BHK",
                        new BigDecimal("18000.00"), 5L));
    }

    @Test
    void newHandoffAttemptFailsClosedForRevokedOrDisputedConsent() {
        for (LeadConsentStatus status : List.of(
                LeadConsentStatus.REVOKED, LeadConsentStatus.DISPUTED)) {
            Fixture fixture = fixture(status);
            LeadShare share = storedShare(fixture);
            UUID eventId = UUID.randomUUID();
            when(shares.findById(share.getId())).thenReturn(Optional.of(share));
            when(shareEvents.findById(eventId)).thenReturn(Optional.empty());

            assertThrows(VisitOperationsConflictException.class,
                    () -> service.recordEvent(7L, 10L, share.getId(),
                            new LeadShareEventCommand(eventId,
                                    LeadShareEventType.HANDOFF_ATTEMPTED)));
        }
        verify(shareEvents, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
    }

    @Test
    void newHandoffAttemptRejectsChangedPublicFactsWithoutRecordingAnAttempt() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadShare share = storedShare(fixture);
        UUID eventId = UUID.randomUUID();
        when(shares.findById(share.getId())).thenReturn(Optional.of(share));
        when(shareEvents.findById(eventId)).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Changed title", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 5L)));

        assertThrows(VisitOperationsConflictException.class,
                () -> service.recordEvent(7L, 10L, share.getId(),
                        new LeadShareEventCommand(eventId,
                                LeadShareEventType.HANDOFF_ATTEMPTED)));
        verify(shareEvents, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
    }

    @Test
    void validNewHandoffAttemptRecordsExactlyOneAuthoritativeEvent() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadShare share = storedShare(fixture);
        UUID eventId = UUID.randomUUID();
        when(shares.findById(share.getId())).thenReturn(Optional.of(share));
        when(shareEvents.findById(eventId)).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(shareEvents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LeadShareEventView recorded = service.recordEvent(7L, 10L, share.getId(),
                new LeadShareEventCommand(eventId, LeadShareEventType.HANDOFF_ATTEMPTED));

        assertEquals(eventId, recorded.id());
        assertEquals(LeadShareEventType.HANDOFF_ATTEMPTED, recorded.type());
        verify(shareEvents, times(1)).saveAndFlush(any());
        verify(activities, times(1)).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = LeadShareEventType.class,
            names = {"HANDOFF_ATTEMPTED", "CLIPBOARD_COPIED"})
    void finalAdmissionRejectsAuthorityLostAfterInitialAuthorization(LeadShareEventType type) {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadShare share = storedShare(fixture);
        UUID eventId = UUID.randomUUID();
        LeadAccessContext initialAuthority =
                new LeadAccessContext(false, List.of(5L), List.of(), List.of(), List.of());
        LeadAccessContext expiredAuthority =
                new LeadAccessContext(false, List.of(), List.of(), List.of(), List.of());
        when(access.context(7L)).thenReturn(initialAuthority, expiredAuthority);
        doThrow(LeadAccessContext.hidden())
                .when(access).requireEditable(eq(expiredAuthority), eq(fixture.lead()), eq(7L));
        when(shares.findById(share.getId())).thenReturn(Optional.of(share));
        when(shareEvents.findById(eventId)).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(shareEvents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(LeadAccessContext.hidden().getClass(),
                () -> service.recordEvent(7L, 10L, share.getId(),
                        new LeadShareEventCommand(eventId, type)));

        verify(access, times(2)).context(7L);
        verify(shareEvents, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
    }

    @Test
    void reusedEventIdWithChangedPayloadRemainsAConflict() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadShare share = storedShare(fixture);
        UUID eventId = UUID.randomUUID();
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7L);
        LeadShareEvent existing = new LeadShareEvent(
                eventId, share, actor, LeadShareEventType.CLIPBOARD_COPIED,
                Instant.parse("2026-10-22T04:30:00Z"));
        when(shares.findById(share.getId())).thenReturn(Optional.of(share));
        when(shareEvents.findById(eventId)).thenReturn(Optional.of(existing));

        assertThrows(VisitOperationsConflictException.class,
                () -> service.recordEvent(7L, 10L, share.getId(),
                        new LeadShareEventCommand(eventId,
                                LeadShareEventType.HANDOFF_ATTEMPTED)));

        verify(shareEvents, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
    }

    @Test
    void supervisorCanReadHistoryButCannotReceiveNewActionReadiness() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7L);
        LeadContactPreference preference =
                preferences.findFirstByLead_IdOrderByRevisionDesc(10L).orElseThrow();
        LeadShare stored = new LeadShare(UUID.randomUUID(), fixture.lead(), fixture.shortlist(),
                0L, 1L, LeadShareMode.COPY_LINKS,
                "[\"https://pathome.test/property/11\"]",
                "Pathome property options\n1. Synthetic Home - Indore / Vijay Nagar - 2 BHK"
                        + " - INR 18000.00 / month\nhttps://pathome.test/property/11",
                "********3210", Instant.parse("2026-10-22T04:30:00Z"), actor);
        when(readableLeads.requireReadableLead(7L, 10L)).thenReturn(fixture.lead());
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(), List.of(), List.of(), List.of(9L)));
        when(shares.findAllByLead_IdOrderByRecordedAtDescIdDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(stored)));
        when(shareEvents.findAllByShare_IdInOrderByOccurredAtAscIdAsc(any())).thenReturn(List.of());
        when(shortlists.findAllById(any())).thenReturn(List.of(fixture.shortlist()));
        when(shortlistItems.findAllByShortlist_IdInOrderByShortlist_IdAscPositionAsc(any()))
                .thenReturn(List.of(new LeadShortlistItem(fixture.shortlist(), 11L, 0)));
        when(publicProperties.currentPublicPropertiesById(any(), any(), anyBoolean()))
                .thenReturn(Map.of());
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L))
                .thenReturn(Optional.of(preference));

        LeadShareHistoryPage page = service.history(7L, 10L, 0, 10);

        assertFalse(page.items().get(0).currentAuthorityMatches());
        assertFalse(page.items().get(0).actionReady());
        verify(shares, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
    }

    @Test
    void twentyShareHistoryPageUsesOneBoundedPropertyAndEventBatch() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7L);
        LeadContactPreference preference =
                preferences.findFirstByLead_IdOrderByRevisionDesc(10L).orElseThrow();
        List<LeadShare> stored = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new LeadShare(UUID.randomUUID(), fixture.lead(),
                        fixture.shortlist(), 0L, 1L, LeadShareMode.COPY_LINKS,
                        "[]", "snapshot-" + index, "********3210",
                        Instant.parse("2026-10-22T04:30:00Z").plusSeconds(index), actor))
                .toList();
        List<LeadShortlistItem> items = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new LeadShortlistItem(fixture.shortlist(), 100L + index, index))
                .toList();
        when(readableLeads.requireReadableLead(7L, 10L)).thenReturn(fixture.lead());
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(), List.of(), List.of(), List.of(9L)));
        when(shares.findAllByLead_IdOrderByRecordedAtDescIdDesc(any(), any()))
                .thenReturn(new PageImpl<>(stored));
        when(shareEvents.findAllByShare_IdInOrderByOccurredAtAscIdAsc(any()))
                .thenReturn(List.of());
        when(shortlists.findAllById(any())).thenReturn(List.of(fixture.shortlist()));
        when(shortlistItems.findAllByShortlist_IdInOrderByShortlist_IdAscPositionAsc(any()))
                .thenReturn(items);
        when(publicProperties.currentPublicPropertiesById(any(), any(), anyBoolean()))
                .thenReturn(Map.of());
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L))
                .thenReturn(Optional.of(preference));

        LeadShareHistoryPage page = service.history(7L, 10L, 0, 20);

        assertEquals(20, page.items().size());
        verify(shareEvents, times(1)).findAllByShare_IdInOrderByOccurredAtAscIdAsc(any());
        verify(shortlists, times(1)).findAllById(any());
        verify(shortlistItems, times(1))
                .findAllByShortlist_IdInOrderByShortlist_IdAscPositionAsc(any());
        verify(publicProperties, times(1)).currentPublicPropertiesById(any(), any(), anyBoolean());
    }

    private void assertChangedCustomerVisibleFactIsRejected(
            LeadPublicPropertyService.EligibleProperty changed) {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        AtomicReference<LeadPublicPropertyService.EligibleProperty> current =
                new AtomicReference<>(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L));
        when(publicProperties.requireCurrentPublicProperties(fixture.lead(), List.of(11L)))
                .thenAnswer(invocation -> List.of(current.get()));
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(UUID.randomUUID(), 3L, 20L,
                0L, 1L, LeadShareMode.COPY_LINKS);

        PreparedShare prepared = prepareAndInstallReplay(fixture, command);
        current.set(changed);

        assertThrows(VisitOperationsConflictException.class,
                () -> service.prepare(7L, 10L, command));
        assertEquals(prepared.view().previewText(), prepared.share().getPreviewText());
        verify(shares, times(1)).saveAndFlush(any());
    }

    private PreparedShare prepareAndInstallReplay(Fixture fixture, LeadSharePrepareCommand command) {
        AtomicReference<LeadCommandReceipt> receipt = new AtomicReference<>();
        AtomicReference<LeadShare> stored = new AtomicReference<>();
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, command.operationId(), "LEAD_SHARE_PREPARE"))
                .thenAnswer(invocation -> Optional.ofNullable(receipt.get()));
        when(shares.saveAndFlush(any())).thenAnswer(invocation -> {
            LeadShare share = invocation.getArgument(0);
            stored.set(share);
            return share;
        });
        when(receipts.saveAndFlush(any())).thenAnswer(invocation -> {
            LeadCommandReceipt saved = invocation.getArgument(0);
            receipt.set(saved);
            return saved;
        });
        when(shares.findById(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(shortlists.findById(20L)).thenReturn(Optional.of(fixture.shortlist()));

        LeadShareView view = service.prepare(7L, 10L, command);
        return new PreparedShare(view, stored.get());
    }

    private LeadShare storedShare(Fixture fixture) {
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7L);
        return new LeadShare(UUID.randomUUID(), fixture.lead(), fixture.shortlist(), 0L, 1L,
                LeadShareMode.MANUAL_BUSINESS_HANDOFF,
                "[\"https://pathome.test/property/11\"]",
                "Pathome property options\n1. Synthetic Home - Indore / Vijay Nagar - 2 BHK"
                        + " - INR 18000.00 / month\nhttps://pathome.test/property/11",
                "********3210", Instant.parse("2026-10-22T04:30:00Z"), actor);
    }

    private Fixture fixture(LeadConsentStatus consentStatus) {
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        LeadRoutingQueue lead = mock(LeadRoutingQueue.class);
        when(lead.getId()).thenReturn(10L);
        when(lead.getVersion()).thenReturn(3L);
        when(lead.getStatus()).thenReturn(LeadStatus.QUALIFYING);
        when(lead.getSupportedCity()).thenReturn(city);
        LeadShortlist shortlist = mock(LeadShortlist.class);
        when(shortlist.getId()).thenReturn(20L);
        when(shortlist.getVersion()).thenReturn(0L);
        when(shortlist.getLead()).thenReturn(lead);
        LeadShortlistItem item = mock(LeadShortlistItem.class);
        when(item.getListingId()).thenReturn(11L);
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7L);
        LeadDataProtection.ProtectedValue protectedValue = protection.protect("+919876543210");
        LeadContactPreference preference = new LeadContactPreference(lead, 1L,
                LeadContactChannel.WHATSAPP, LeadDestinationChoice.SAME, "+91",
                protectedValue.ciphertext(), protectedValue.iv(), protectedValue.keyId(),
                protectedValue.fingerprint(), consentStatus, "property-sharing-v1",
                LeadConsentEvidenceMethod.CUSTOMER_VERBAL, Instant.now(), actor);
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        when(shortlists.findLockedById(20L)).thenReturn(Optional.of(shortlist));
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(5L), List.of(), List.of(), List.of()));
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L))
                .thenReturn(Optional.of(preference));
        when(shortlistItems.findAllByShortlist_IdOrderByPositionAsc(20L)).thenReturn(List.of(item));
        when(staff.requireAttributableUser(7L)).thenReturn(actor);
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:30:00Z"));
        return new Fixture(lead, shortlist);
    }

    private record Fixture(LeadRoutingQueue lead, LeadShortlist shortlist) {}
    private record PreparedShare(LeadShareView view, LeadShare share) {}
}
