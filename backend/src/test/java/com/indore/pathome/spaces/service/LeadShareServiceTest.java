package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadShareEventCommand;
import com.indore.pathome.spaces.dto.crm.LeadShareEventView;
import com.indore.pathome.spaces.dto.crm.LeadSharePrepareCommand;
import com.indore.pathome.spaces.dto.crm.LeadShareHistoryPage;
import com.indore.pathome.spaces.dto.crm.LeadShareHistoryView;
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
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
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
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
        return serviceWithAccessAndOrigin(access, origin);
    }

    private LeadShareService serviceWithRealAccess(String origin) {
        return serviceWithAccessAndOrigin(new LeadAccessService(staff), origin);
    }

    private LeadShareService serviceWithAccessAndOrigin(LeadAccessService accessService,
                                                        String origin) {
        return new LeadShareService(leads, shortlists, shortlistItems,
                preferences, shares, shareEvents, readableLeads, activities, receipts, accessService,
                staff, guards, audit, clock, publicProperties, protection, jdbc, new ObjectMapper(),
                origin);
    }

    @Test
    void foreignOrMalformedShareReceiptIsConcealedBeforePayloadComparison() {
        fixture(LeadConsentStatus.GRANTED);
        LeadRoutingQueue foreign = mock(LeadRoutingQueue.class);
        when(foreign.getId()).thenReturn(99L);
        LeadShare share = mock(LeadShare.class); when(share.getLead()).thenReturn(foreign);
        UUID foreignId = UUID.randomUUID();
        when(shares.findById(foreignId)).thenReturn(Optional.of(share));
        for (String reference : java.util.Arrays.asList(null, "bad", foreignId.toString(), UUID.randomUUID().toString())) {
            UUID key = UUID.randomUUID();
            var receipt = new LeadCommandReceipt(mock(User.class),key,"LEAD_SHARE_PREPARE","different",reference,Instant.now());
            when(receipts.findByActorIdAndOperationIdAndAction(7L,key,"LEAD_SHARE_PREPARE"))
                    .thenReturn(Optional.of(receipt));
            var command = new LeadSharePrepareCommand(key,3L,20L,0L,1L,LeadShareMode.COPY_LINKS);
            assertThrows(jakarta.persistence.EntityNotFoundException.class,()->service.prepare(7L,10L,command));
        }
        verify(shares,never()).saveAndFlush(any());
        verify(receipts,never()).saveAndFlush(any());
    }

    @Test
    void eventFromAnotherShareIsConcealedBeforeEventConflictDisclosure() {
        Fixture fixture=fixture(LeadConsentStatus.GRANTED);
        LeadShare current=storedShare(fixture), foreign=storedShare(fixture);
        when(shares.findById(current.getId())).thenReturn(Optional.of(current));
        UUID key=UUID.randomUUID(); LeadShareEvent stored=mock(LeadShareEvent.class);
        when(stored.getShare()).thenReturn(foreign);
        when(shareEvents.findById(key)).thenReturn(Optional.of(stored));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,()->service.recordEvent(7L,10L,current.getId(),
                new LeadShareEventCommand(key,LeadShareEventType.CLIPBOARD_COPIED)));
        verify(shareEvents,never()).saveAndFlush(any());verify(activities,never()).save(any());
    }

    private static StaffAccessGrant intakeGrant() {
        StaffAccessGrant grant = mock(StaffAccessGrant.class);
        when(grant.getCapability()).thenReturn(StaffCapability.OPS_INTAKE);
        when(grant.getScopeType()).thenReturn(StaffScopeType.CITY);
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        when(grant.getCity()).thenReturn(city);
        return grant;
    }

    private static StaffAccessGrant coordinatorGrant() {
        StaffAccessGrant grant = mock(StaffAccessGrant.class);
        when(grant.getCapability()).thenReturn(StaffCapability.OPS_COORDINATE);
        when(grant.getScopeType()).thenReturn(StaffScopeType.TEAM);
        com.indore.pathome.spaces.entity.OperatingTeam team = mock(
                com.indore.pathome.spaces.entity.OperatingTeam.class);
        when(team.getId()).thenReturn(9L);
        when(grant.getTeam()).thenReturn(team);
        return grant;
    }

    private static User owner() {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(7L);
        return owner;
    }

    @ParameterizedTest
    @EnumSource(LeadShareMode.class)
    void preparedShareUsesOrderedCurrentPublicLinksAndOnlyAMaskedDestination(
            LeadShareMode mode) {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        UUID operationId = UUID.randomUUID();
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(operationId, 3L, 20L,
                0L, 1L, mode);
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(shares.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LeadShareView view = service.prepare(7L, 10L, command);

        assertEquals(List.of("https://pathome.test/property/11"), view.publicLinks());
        assertEquals("********3210", view.maskedDestination());
        assertEquals(false, view.previewText().contains("+919876543210"));
        assertEquals(brandedSinglePropertyPreview(), view.previewText());
        verify(receipts).saveAndFlush(any());
        verify(activities).save(any());
    }

    @Test
    void brandedPreviewPreservesShortlistOrderAndOmitsUnknownOptionalFacts() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        UUID operationId = UUID.randomUUID();
        when(shortlistItems.findAllByShortlist_IdOrderByPositionAsc(20L))
                .thenReturn(List.of(
                        new LeadShortlistItem(fixture.shortlist(), 11L, 0),
                        new LeadShortlistItem(fixture.shortlist(), 12L, 1)));
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L, 12L)))
                .thenReturn(List.of(
                        new LeadPublicPropertyService.EligibleProperty(
                                11L, "2 BHK Flat in Vijay Nagar, Indore",
                                "Vijay Nagar, Indore", "Vijay Nagar", "Indore",
                                "2 BHK", new BigDecimal("18000.00"), 900.0, 4L),
                        new LeadPublicPropertyService.EligibleProperty(
                                12L, "Rental home in Palasia, Indore",
                                "Palasia, Indore", "Palasia", "Indore",
                                null, null, null, 5L)));
        when(shares.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LeadShareView view = service.prepare(7L, 10L,
                new LeadSharePrepareCommand(operationId, 3L, 20L, 0L, 1L,
                        LeadShareMode.COPY_LINKS));

        assertEquals(List.of(
                "https://pathome.test/property/11",
                "https://pathome.test/property/12"), view.publicLinks());
        assertEquals("""
                🏡 Your shortlisted homes from Pathome
                1. 2 BHK Flat in Vijay Nagar, Indore
                Vijay Nagar, Indore
                Rent: INR 18,000 / month · 2 BHK · 900 sq ft
                View property details: https://pathome.test/property/11

                2. Rental home in Palasia, Indore
                Palasia, Indore
                View property details: https://pathome.test/property/12

                Interested? Open Pathome to request a visit.
                Pathome — Your Dreams, Our Efforts.""", view.previewText());
        assertEquals(false, view.previewText().contains("Availab"));
        assertEquals(false, view.previewText().contains("+91"));
    }

    @Test
    void ownerIsReevaluatedAfterTheListingLockBeforeAnyShareWrite() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        UUID operationId = UUID.randomUUID();
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(operationId, 3L, 20L,
                0L, 1L, LeadShareMode.MANUAL_BUSINESS_HANDOFF);
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(shares.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.prepare(7L, 10L, command);

        var order = inOrder(publicProperties, access);
        order.verify(publicProperties).requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L));
        order.verify(access).context(7L);
    }

    @Test
    void postLockOwnerDenialPreventsShareActivityAuditAndReceiptWrites() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        UUID operationId = UUID.randomUUID();
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(operationId, 3L, 20L,
                0L, 1L, LeadShareMode.MANUAL_BUSINESS_HANDOFF);
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        doNothing().doNothing().doThrow(LeadAccessContext.hidden())
                .when(access).requireContactOwner(any(), eq(fixture.lead()), eq(7L));

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.prepare(7L, 10L, command));

        verify(shares, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
        verify(audit, never()).recordUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void missingPublicOriginIsRejectedBeforeShareOrReceiptPersistenceAndCanRecover() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        UUID operationId = UUID.randomUUID();
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(operationId, 3L, 20L,
                0L, 1L, LeadShareMode.MANUAL_BUSINESS_HANDOFF);
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
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

    @ParameterizedTest
    @EnumSource(value = LeadConsentStatus.class, names = {"REVOKED", "DISPUTED"})
    void withdrawnOrDisputedAgreementBlocksSharePreparation(LeadConsentStatus consentStatus) {
        Fixture fixture = fixture(consentStatus);
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(UUID.randomUUID(), 3L, 20L,
                0L, 1L, LeadShareMode.COPY_LINKS);

        assertThrows(VisitOperationsConflictException.class,
                () -> service.prepare(7L, 10L, command));
        verify(shares, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = LeadDestinationChoice.class, names = {"SAME", "DIFFERENT"})
    void manualHandoffPermitsOnlyAnAgreedWhatsAppDestination(
            LeadDestinationChoice destinationChoice) {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED,
                LeadContactChannel.WHATSAPP, destinationChoice);
        UUID operationId = UUID.randomUUID();
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(shares.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LeadShareView view = service.prepare(7L, 10L, new LeadSharePrepareCommand(
                operationId, 3L, 20L, 0L, 1L, LeadShareMode.MANUAL_BUSINESS_HANDOFF));

        assertEquals("********3210", view.maskedDestination());
        verify(shares).saveAndFlush(any());
        verify(receipts).saveAndFlush(any());
    }

    @Test
    void manualHandoffRejectsAnotherChannelEvenWhenItHasAUsableDestination() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED,
                LeadContactChannel.OTHER, LeadDestinationChoice.SAME);
        UUID operationId = UUID.randomUUID();
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));

        VisitOperationsConflictException failure = assertThrows(
                VisitOperationsConflictException.class,
                () -> service.prepare(7L, 10L, new LeadSharePrepareCommand(
                        operationId, 3L, 20L, 0L, 1L,
                        LeadShareMode.MANUAL_BUSINESS_HANDOFF)));

        assertTrue(failure.getMessage().contains("WhatsApp"));
        verify(shares, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void copyLinksKeepsTheApprovedAlternativeChannelBehavior() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED,
                LeadContactChannel.OTHER, LeadDestinationChoice.NONE);
        UUID operationId = UUID.randomUUID();
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(shares.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LeadShareView view = service.prepare(7L, 10L, new LeadSharePrepareCommand(
                operationId, 3L, 20L, 0L, 1L, LeadShareMode.COPY_LINKS));

        assertEquals(LeadShareMode.COPY_LINKS, view.mode());
        assertEquals(null, view.maskedDestination());
        verify(shares).saveAndFlush(any());
        verify(receipts).saveAndFlush(any());
    }

    @Test
    void nonPropertySharingPurposeIsRejectedBeforeAnyShareWrite() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadContactPreference preference =
                preferences.findFirstByLead_IdOrderByRevisionDesc(10L).orElseThrow();
        ReflectionTestUtils.setField(preference, "consentPurpose", "AUTHENTICATION");
        UUID operationId = UUID.randomUUID();
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));

        VisitOperationsConflictException failure = assertThrows(
                VisitOperationsConflictException.class,
                () -> service.prepare(7L, 10L, new LeadSharePrepareCommand(
                        operationId, 3L, 20L, 0L, 1L,
                        LeadShareMode.MANUAL_BUSINESS_HANDOFF)));

        assertTrue(failure.getMessage().contains("PROPERTY_SHARING"));
        verify(shares, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void staleContactPreferenceRevisionIsRejectedBeforeAnyShareWrite() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        UUID operationId = UUID.randomUUID();
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());

        VisitOperationsConflictException failure = assertThrows(
                VisitOperationsConflictException.class,
                () -> service.prepare(7L, 10L, new LeadSharePrepareCommand(
                        operationId, 3L, 20L, 0L, 0L,
                        LeadShareMode.MANUAL_BUSINESS_HANDOFF)));

        assertTrue(failure.getMessage().contains("no longer current"));
        verify(shares, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void exactManualReplayRejectsAChangedNonWhatsAppChannelWithoutWritingAgain() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadShare stored = storedShare(fixture);
        UUID operationId = UUID.randomUUID();
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(
                operationId, 3L, 20L, 0L, 1L, LeadShareMode.MANUAL_BUSINESS_HANDOFF);
        String hash = ReflectionTestUtils.invokeMethod(service, "payloadHash", 10L, command);
        LeadContactPreference changed = fixture(LeadConsentStatus.GRANTED,
                LeadContactChannel.OTHER, LeadDestinationChoice.SAME)
                .preference();
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE"))
                .thenReturn(Optional.of(new LeadCommandReceipt(
                        mock(User.class), operationId, "LEAD_SHARE_PREPARE",
                        hash, stored.getId().toString(), Instant.now())));
        when(shares.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(shortlists.findById(20L)).thenReturn(Optional.of(fixture.shortlist()));
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L))
                .thenReturn(Optional.of(changed));

        VisitOperationsConflictException failure = assertThrows(
                VisitOperationsConflictException.class,
                () -> service.prepare(7L, 10L, command));

        assertTrue(failure.getMessage().contains("WhatsApp"));
        verify(shares, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
        verify(receipts, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = LeadShareEventType.class,
            names = {"HANDOFF_ATTEMPTED", "CLIPBOARD_COPIED"})
    void finalActionReadinessRejectsChangedNonWhatsAppChannelWithoutAnEvent(
            LeadShareEventType type) {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadShare stored = storedShare(fixture);
        UUID eventId = UUID.randomUUID();
        Fixture changed = fixture(LeadConsentStatus.GRANTED,
                LeadContactChannel.OTHER, LeadDestinationChoice.SAME);
        when(shares.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(shareEvents.findById(eventId)).thenReturn(Optional.empty());
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L))
                .thenReturn(Optional.of(changed.preference()));

        VisitOperationsConflictException failure = assertThrows(
                VisitOperationsConflictException.class,
                () -> service.recordEvent(7L, 10L, stored.getId(),
                        new LeadShareEventCommand(eventId, type)));

        assertTrue(failure.getMessage().contains("WhatsApp"));
        verify(shareEvents, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
    }

    @Test
    void historySeparatesGrantedConsentFromAChangedNonWhatsAppDestination() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadShare stored = storedShare(fixture);
        Fixture changed = fixture(LeadConsentStatus.GRANTED,
                LeadContactChannel.OTHER, LeadDestinationChoice.SAME);
        com.indore.pathome.spaces.entity.OperatingTeam team = mock(
                com.indore.pathome.spaces.entity.OperatingTeam.class);
        when(team.getId()).thenReturn(9L);
        User actor = owner();
        when(fixture.lead().getOperatingTeam()).thenReturn(team);
        when(fixture.lead().getOwner()).thenReturn(actor);
        when(readableLeads.requireReadableLead(7L, 10L)).thenReturn(fixture.lead());
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(5L), List.of(), List.of(9L), List.of()));
        when(shares.findAllByLead_IdOrderByRecordedAtDescIdDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(stored)));
        when(shareEvents.findAllByShare_IdInOrderByOccurredAtAscIdAsc(any())).thenReturn(List.of());
        when(shortlists.findAllById(any())).thenReturn(List.of(fixture.shortlist()));
        when(shortlistItems.findAllByShortlist_IdInOrderByShortlist_IdAscPositionAsc(any()))
                .thenReturn(List.of(new LeadShortlistItem(fixture.shortlist(), 11L, 0)));
        when(publicProperties.currentPublicPropertiesById(any(), any(), anyBoolean()))
                .thenReturn(Map.of(11L, new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L))
                .thenReturn(Optional.of(changed.preference()));

        LeadShareHistoryPage page = service.history(7L, 10L, 0, 10);
        LeadShareHistoryView item = page.items().get(0);

        assertTrue(item.currentAuthorityMatches());
        assertTrue(item.currentConsentMatches());
        assertFalse(item.currentDestinationMatches());
        assertFalse(item.actionReady());
        verify(shares, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
    }

    @Test
    void unchangedExactReplayReturnsTheStoredPreviewWithoutWritingAgain() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        AtomicReference<LeadPublicPropertyService.EligibleProperty> current = new AtomicReference<>(
                new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L));
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenAnswer(invocation -> List.of(current.get()));
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

    @ParameterizedTest
    @EnumSource(LeadShareEventType.class)
    void validNewShareFactRecordsExactlyOneAuthoritativeEvent(LeadShareEventType type) {
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
                new LeadShareEventCommand(eventId, type));

        assertEquals(eventId, recorded.id());
        assertEquals(type, recorded.type());
        verify(shareEvents, times(1)).saveAndFlush(any());
        verify(activities, times(1)).save(any());
    }

    @ParameterizedTest
    @EnumSource(LeadShareMode.class)
    void cityIntakeCannotPrepareAShareForAnEditableUnassignedLead(LeadShareMode mode) {
        fixture(LeadConsentStatus.GRANTED);
        LeadShareService ownerOnlyService = serviceWithRealAccess("https://pathome.test");
        UUID operationId = UUID.randomUUID();
        StaffAccessGrant intake = intakeGrant();
        User owner = owner();
        when(staff.effectiveGrants(7L)).thenReturn(List.of(intake));
        when(staff.requireAttributableUser(7L)).thenReturn(owner);
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                any(), eq(List.of(11L)))).thenReturn(
                List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(shares.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(operationId, 3L, 20L,
                0L, 1L, mode);

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> ownerOnlyService.prepare(7L, 10L, command));

        verify(shares, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
        verify(receipts, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(LeadShareEventType.class)
    void cityIntakeCannotRecordAShareActivityForAnEditableUnassignedLead(
            LeadShareEventType type) {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadShare share = storedShare(fixture);
        UUID eventId = UUID.randomUUID();
        LeadShareService ownerOnlyService = serviceWithRealAccess("https://pathome.test");
        StaffAccessGrant intake = intakeGrant();
        User owner = owner();
        when(staff.effectiveGrants(7L)).thenReturn(List.of(intake));
        when(staff.requireAttributableUser(7L)).thenReturn(owner);
        when(shares.findById(share.getId())).thenReturn(Optional.of(share));
        when(shareEvents.findById(eventId)).thenReturn(Optional.empty());
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                any(), eq(List.of(11L)))).thenReturn(
                List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(shareEvents.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> ownerOnlyService.recordEvent(7L, 10L, share.getId(),
                        new LeadShareEventCommand(eventId, type)));

        verify(shareEvents, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
    }

    @Test
    void changedPayloadReplayDoesNotDiscloseConflictAfterOwnershipIsLost() {
        fixture(LeadConsentStatus.GRANTED);
        LeadShareService ownerOnlyService = serviceWithRealAccess("https://pathome.test");
        UUID operationId = UUID.randomUUID();
        LeadCommandReceipt receipt = mock(LeadCommandReceipt.class);
        when(receipt.getPayloadHash()).thenReturn("stored-payload");
        StaffAccessGrant intake = intakeGrant();
        User owner = owner();
        when(staff.effectiveGrants(7L)).thenReturn(List.of(intake));
        when(staff.requireAttributableUser(7L)).thenReturn(owner);
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHARE_PREPARE")).thenReturn(Optional.of(receipt));
        LeadSharePrepareCommand changed = new LeadSharePrepareCommand(operationId, 3L, 20L,
                0L, 1L, LeadShareMode.COPY_LINKS);

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> ownerOnlyService.prepare(7L, 10L, changed));

        verify(shares, never()).findById(any());
        verify(shares, never()).saveAndFlush(any());
    }

    @Test
    void exactReplayAfterOwnershipIsLostReturnsNoHistoricalPreparedShare() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        com.indore.pathome.spaces.entity.OperatingTeam team = mock(
                com.indore.pathome.spaces.entity.OperatingTeam.class);
        when(team.getId()).thenReturn(9L);
        when(fixture.lead().getOperatingTeam()).thenReturn(team);
        User owner = owner();
        when(fixture.lead().getOwner()).thenReturn(owner);
        LeadShareService ownerOnlyService = serviceWithRealAccess("https://pathome.test");
        StaffAccessGrant coordinate = coordinatorGrant();
        StaffAccessGrant intake = intakeGrant();
        java.util.concurrent.atomic.AtomicBoolean ownerActive = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(staff.effectiveGrants(7L)).thenAnswer(
                invocation -> List.of(ownerActive.get() ? coordinate : intake));
        when(staff.requireAttributableUser(7L)).thenReturn(owner);
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L))).thenReturn(
                List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        when(publicProperties.requireCurrentPublicProperties(
                fixture.lead(), List.of(11L))).thenReturn(
                List.of(new LeadPublicPropertyService.EligibleProperty(
                        11L, "Synthetic Home", "Indore / Vijay Nagar", "2 BHK",
                        new BigDecimal("18000.00"), 4L)));
        AtomicReference<LeadCommandReceipt> receipt = new AtomicReference<>();
        AtomicReference<LeadShare> stored = new AtomicReference<>();
        when(receipts.findByActorIdAndOperationIdAndAction(
                eq(7L), any(), eq("LEAD_SHARE_PREPARE")))
                .thenAnswer(invocation -> Optional.ofNullable(receipt.get()));
        when(receipts.saveAndFlush(any())).thenAnswer(invocation -> {
            LeadCommandReceipt saved = invocation.getArgument(0);
            receipt.set(saved);
            return saved;
        });
        when(shares.saveAndFlush(any())).thenAnswer(invocation -> {
            LeadShare saved = invocation.getArgument(0);
            stored.set(saved);
            return saved;
        });
        when(shares.findById(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        LeadSharePrepareCommand command = new LeadSharePrepareCommand(
                UUID.randomUUID(), 3L, 20L, 0L, 1L, LeadShareMode.COPY_LINKS);

        ownerOnlyService.prepare(7L, 10L, command);
        ownerActive.set(false);

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> ownerOnlyService.prepare(7L, 10L, command));
        verify(shares, times(1)).saveAndFlush(any());
        verify(receipts, times(1)).saveAndFlush(any());
        verify(shares, never()).findById(any());
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

    @ParameterizedTest
    @EnumSource(value = LeadShareEventType.class,
            names = {"HANDOFF_ATTEMPTED", "CLIPBOARD_COPIED"})
    void finalAdmissionRejectsOwnerLossAfterReadinessLocks(LeadShareEventType type) {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadShare share = storedShare(fixture);
        UUID eventId = UUID.randomUUID();
        LeadAccessContext initialAuthority =
                new LeadAccessContext(false, List.of(), List.of(), List.of(9L), List.of());
        LeadAccessContext lostOwnerAuthority =
                new LeadAccessContext(false, List.of(5L), List.of(), List.of(), List.of());
        when(access.context(7L)).thenReturn(initialAuthority, lostOwnerAuthority);
        doThrow(LeadAccessContext.hidden())
                .when(access).requireContactOwner(
                        eq(lostOwnerAuthority), eq(fixture.lead()), eq(7L));
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
    void eventReplayAfterOwnershipIsLostReturnsNoHistoricalShareFact() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        LeadShare share = storedShare(fixture);
        UUID eventId = UUID.randomUUID();
        User actor = owner();
        LeadShareEvent existing = new LeadShareEvent(
                eventId, share, actor, LeadShareEventType.CLIPBOARD_COPIED,
                Instant.parse("2026-10-22T04:30:00Z"));
        LeadShareService ownerOnlyService = serviceWithRealAccess("https://pathome.test");
        StaffAccessGrant intake = intakeGrant();
        when(staff.effectiveGrants(7L)).thenReturn(List.of(intake));
        when(staff.requireAttributableUser(7L)).thenReturn(actor);
        when(shares.findById(share.getId())).thenReturn(Optional.of(share));
        when(shareEvents.findById(eventId)).thenReturn(Optional.of(existing));

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> ownerOnlyService.recordEvent(7L, 10L, share.getId(),
                        new LeadShareEventCommand(eventId,
                                LeadShareEventType.CLIPBOARD_COPIED)));

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
                brandedSinglePropertyPreview(),
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
    void cityIntakeCanReadSanitizedHistoryButCannotReceiveActionReadiness() {
        Fixture fixture = fixture(LeadConsentStatus.GRANTED);
        User actor = owner();
        LeadContactPreference preference =
                preferences.findFirstByLead_IdOrderByRevisionDesc(10L).orElseThrow();
        LeadShare stored = storedShare(fixture);
        LeadShareService ownerOnlyService = serviceWithRealAccess("https://pathome.test");
        StaffAccessGrant intake = intakeGrant();
        when(staff.effectiveGrants(7L)).thenReturn(List.of(intake));
        when(staff.requireAttributableUser(7L)).thenReturn(actor);
        when(readableLeads.requireReadableLead(7L, 10L)).thenReturn(fixture.lead());
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

        LeadShareHistoryPage page = ownerOnlyService.history(7L, 10L, 0, 10);

        assertFalse(page.items().get(0).currentAuthorityMatches());
        assertFalse(page.items().get(0).actionReady());
        assertTrue(page.items().get(0).maskedDestination().contains("3210"));
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
        when(publicProperties.requireCurrentPublicPropertiesForUpdate(
                fixture.lead(), List.of(11L)))
                .thenAnswer(invocation -> List.of(current.get()));
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


    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> storedUuidCorpus() {
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        var invalid = java.util.Arrays.asList(null, "", " ", "\t\n", "1-1-1-1-1", "000000001-1-1-1-1",
                "000000001-0001-0001-0001-000000000001", "1-1-1-1", "1-1-1-1-1-1",
                "00000001000100010001000000000001", "00000001--0001-0001-0001-000000000001",
                "{00000001-0001-0001-0001-000000000001}", "urn:uuid:00000001-0001-0001-0001-000000000001",
                "00000001-0001-0001-0001-00000000000g", "0000000１-0001-0001-0001-000000000001",
                "0000000١-0001-0001-0001-000000000001", "00000001-0001-0001-0001-000000000001\n",
                " 00000001-0001-0001-0001-000000000001", "00000001-0001-0001-0001-000000000001\u0000",
                "00000001\u20100001-0001-0001-000000000001", "\ud83d\ude00", "f".repeat(1000));
        for (int i = 0; i < invalid.size(); i++) for (boolean changed : List.of(false, true)) {
            cases.add(org.junit.jupiter.params.provider.Arguments.of("invalid-" + i, invalid.get(i), changed));
        }
        return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} changed={2}")
    @org.junit.jupiter.params.provider.MethodSource("storedUuidCorpus")
    void storedUuidGrammarMatchesSelectorBeforeReplayOrConflict(String label, String reference,
                                                               boolean changed) {
        Fixture f = fixture(LeadConsentStatus.GRANTED);
        LeadShare stored = storedShare(f);
        UUID id = UUID.fromString("00000001-0001-0001-0001-000000000001");
        ReflectionTestUtils.setField(stored, "id", id);
        when(shares.findById(id)).thenReturn(Optional.of(stored));
        when(shortlists.findById(20L)).thenReturn(Optional.of(f.shortlist()));
        when(publicProperties.requireCurrentPublicProperties(f.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(11L,
                        "Synthetic Home", "Indore / Vijay Nagar", "2 BHK", new BigDecimal("18000.00"), 4L)));
        UUID key = UUID.randomUUID();
        var command = new LeadSharePrepareCommand(key, 3L, 20L, 0L, 1L, LeadShareMode.MANUAL_BUSINESS_HANDOFF);
        String hash = ReflectionTestUtils.invokeMethod(service, "payloadHash", 10L, command);
        when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHARE_PREPARE"))
                .thenReturn(Optional.of(new LeadCommandReceipt(mock(User.class), key, "LEAD_SHARE_PREPARE",
                        changed ? "different" : hash, reference, Instant.now())));
        assertThrows(jakarta.persistence.EntityNotFoundException.class, () ->
                ReflectionTestUtils.invokeMethod(LeadService.class, "parseStoredShareReference", reference));
        Object observed;
        try { observed = service.prepare(7L, 10L, command); }
        catch (RuntimeException failure) { observed = failure; }
        System.out.println("UUID_LEAF " + label + " changed=" + changed + " observed=" + observed);
        org.junit.jupiter.api.Assertions.assertInstanceOf(jakarta.persistence.EntityNotFoundException.class, observed);
        verify(shares, never()).findById(any());
        verify(shares, never()).saveAndFlush(any());
        verify(receipts, never()).saveAndFlush(any());
        org.mockito.Mockito.verifyNoInteractions(audit);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"abcdef01-abcd-abcd-abcd-abcdefabcdef", "ABCDEF01-ABCD-ABCD-ABCD-ABCDEFABCDEF"})
    void canonicalUuidCaseKeepsExactReplayAndAuthorizedConflict(String reference) {
        Fixture f = fixture(LeadConsentStatus.GRANTED);
        LeadShare stored = storedShare(f);
        UUID id = UUID.fromString(reference);
        ReflectionTestUtils.setField(stored, "id", id);
        when(shares.findById(id)).thenReturn(Optional.of(stored));
        when(shortlists.findById(20L)).thenReturn(Optional.of(f.shortlist()));
        when(publicProperties.requireCurrentPublicProperties(f.lead(), List.of(11L)))
                .thenReturn(List.of(new LeadPublicPropertyService.EligibleProperty(11L,
                        "Synthetic Home", "Indore / Vijay Nagar", "2 BHK", new BigDecimal("18000.00"), 4L)));
        UUID key = UUID.randomUUID();
        var command = new LeadSharePrepareCommand(key, 3L, 20L, 0L, 1L, LeadShareMode.MANUAL_BUSINESS_HANDOFF);
        String hash = ReflectionTestUtils.invokeMethod(service, "payloadHash", 10L, command);
        when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHARE_PREPARE"))
                .thenReturn(Optional.of(new LeadCommandReceipt(mock(User.class), key,
                        "LEAD_SHARE_PREPARE", hash, reference, Instant.now())));
        assertEquals(id, service.prepare(7L, 10L, command).id());
        assertThrows(VisitOperationsConflictException.class, () -> service.prepare(7L, 10L,
                new LeadSharePrepareCommand(key, 4L, 20L, 0L, 1L, LeadShareMode.COPY_LINKS)));
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void canonicalShareRepositoryFailureRemainsInfrastructureFailure() {
        fixture(LeadConsentStatus.GRANTED);
        UUID key = UUID.randomUUID(), id = UUID.randomUUID();
        when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHARE_PREPARE"))
                .thenReturn(Optional.of(new LeadCommandReceipt(mock(User.class), key,
                        "LEAD_SHARE_PREPARE", "different", id.toString(), Instant.now())));
        when(shares.findById(id)).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("Synthetic unavailable"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> service.prepare(
                7L, 10L, new LeadSharePrepareCommand(key, 3L, 20L, 0L, 1L, LeadShareMode.COPY_LINKS)));
        verify(receipts, never()).saveAndFlush(any());
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void storedShareAssociationMustHideBothExactAndChangedReplay(boolean changed) {
        fixture(LeadConsentStatus.GRANTED);
        UUID key = UUID.randomUUID(), id = UUID.randomUUID();
        var command = new LeadSharePrepareCommand(key, 3L, 20L, 0L, 1L, LeadShareMode.COPY_LINKS);
        String hash = ReflectionTestUtils.invokeMethod(service, "payloadHash", 10L, command);
        LeadRoutingQueue foreign = mock(LeadRoutingQueue.class);
        when(foreign.getId()).thenReturn(99L);
        LeadShare stored = mock(LeadShare.class);
        when(stored.getLead()).thenReturn(foreign);
        when(shares.findById(id)).thenReturn(Optional.of(stored));
        when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHARE_PREPARE"))
                .thenReturn(Optional.of(new LeadCommandReceipt(mock(User.class), key,
                        "LEAD_SHARE_PREPARE", changed ? "different" : hash, id.toString(), Instant.now())));
        assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> service.prepare(7L, 10L, command));
        verify(stored, never()).getShortlist();
        verify(receipts, never()).saveAndFlush(any());
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
                brandedSinglePropertyPreview(),
                "********3210", Instant.parse("2026-10-22T04:30:00Z"), actor);
    }

    private static String brandedSinglePropertyPreview() {
        return """
                🏡 Your shortlisted home from Pathome
                Synthetic Home
                Indore / Vijay Nagar
                Rent: INR 18,000 / month · 2 BHK
                View property details: https://pathome.test/property/11
                Interested? Open Pathome to request a visit.
                Pathome — Your Dreams, Our Efforts.""";
    }

    private Fixture fixture(LeadConsentStatus consentStatus) {
        return fixture(consentStatus, LeadContactChannel.WHATSAPP,
                LeadDestinationChoice.SAME);
    }

    private Fixture fixture(LeadConsentStatus consentStatus, LeadContactChannel channel,
                            LeadDestinationChoice destinationChoice) {
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
                channel, destinationChoice,
                destinationChoice == LeadDestinationChoice.DIFFERENT ? "+91" : null,
                destinationChoice == LeadDestinationChoice.NONE ? null : protectedValue.ciphertext(),
                destinationChoice == LeadDestinationChoice.NONE ? null : protectedValue.iv(),
                destinationChoice == LeadDestinationChoice.NONE ? null : protectedValue.keyId(),
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
        return new Fixture(lead, shortlist, channel, destinationChoice, preference);
    }

    private record Fixture(LeadRoutingQueue lead, LeadShortlist shortlist,
                           LeadContactChannel channel, LeadDestinationChoice destinationChoice,
                           LeadContactPreference preferenceValue) {
        LeadContactPreference preference() { return preferenceValue; }
    }
    private record PreparedShare(LeadShareView view, LeadShare share) {}
}
