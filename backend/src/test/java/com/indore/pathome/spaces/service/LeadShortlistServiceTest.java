package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.crm.LeadShortlistCommand;
import com.indore.pathome.spaces.dto.crm.LeadShortlistView;
import com.indore.pathome.spaces.entity.LeadCommandReceipt;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadShortlist;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadShortlistItemRepository;
import com.indore.pathome.spaces.repository.LeadShortlistRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadShortlistServiceTest {
    private final LeadRoutingQueueRepository leads = mock(LeadRoutingQueueRepository.class);
    private final LeadShortlistRepository shortlists = mock(LeadShortlistRepository.class);
    private final LeadShortlistItemRepository items = mock(LeadShortlistItemRepository.class);
    private final LeadActivityRepository activities = mock(LeadActivityRepository.class);
    private final LeadCommandReceiptRepository receipts = mock(LeadCommandReceiptRepository.class);
    private final LeadAccessService access = mock(LeadAccessService.class);
    private final StaffAccessService staff = mock(StaffAccessService.class);
    private final OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
    private final OperationalAuditService audit = mock(OperationalAuditService.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final LeadPublicPropertyService publicProperties = mock(LeadPublicPropertyService.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbc =
            mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final LeadShortlistService service = new LeadShortlistService(leads, shortlists, items,
            activities, receipts, access, staff, guards, audit, clock, publicProperties, jdbc,
            new ObjectMapper());

    @Test
    void createPersistsTheReviewedOrderAfterCurrentPublicEligibilityValidation() {
        LeadRoutingQueue lead = lead();
        when(leads.findById(10L)).thenReturn(Optional.of(lead));
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(5L), List.of(), List.of(), List.of()));
        when(shortlists.saveAndFlush(any())).thenAnswer(invocation -> {
            LeadShortlist saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 20L);
            return saved;
        });
        when(leads.saveAndFlush(lead)).thenReturn(lead);
        User actor = mock(User.class);
        when(staff.requireAttributableUser(7L)).thenReturn(actor);
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:30:00Z"));
        List<Object> listingIds = List.of(11, 12);

        LeadShortlistView view = service.create(7L, 10L, UUID.randomUUID().toString(),
                new LeadShortlistCommand(3L, null, listingIds));

        assertEquals(List.of(11L, 12L), view.listingIds());
        verify(publicProperties).requireCurrentPublicPropertiesForUpdate(lead, List.of(11L, 12L));
        verify(items, org.mockito.Mockito.times(2)).save(any());
        var order = inOrder(publicProperties, access);
        order.verify(publicProperties).requireCurrentPublicPropertiesForUpdate(
                lead, List.of(11L, 12L));
        order.verify(access).context(7L);
    }

    @Test
    void postLockEditableDenialPreventsCreateAndReceiptWrites() {
        LeadRoutingQueue lead = lead();
        when(leads.findById(10L)).thenReturn(Optional.of(lead));
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(5L), List.of(), List.of(), List.of()));
        doNothing().doThrow(LeadAccessContext.hidden())
                .when(access).requireEditable(any(), eq(lead), eq(7L));

        assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> service.create(
                7L, 10L, UUID.randomUUID().toString(),
                new LeadShortlistCommand(3L, null, List.of(11))));

        verify(shortlists, never()).saveAndFlush(any());
        verify(items, never()).save(any());
        verify(receipts, never()).saveAndFlush(any());
    }

    @Test
    void unreadableReceiptIsConcealedBeforeAnyReceiptLookup() {
        LeadRoutingQueue lead = lead();
        when(leads.findById(10L)).thenReturn(Optional.of(lead));
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(9L), List.of(), List.of(), List.of()));
        doThrow(LeadAccessContext.hidden())
                .when(access).requireReadable(any(), eq(lead), eq(7L));

        assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> service.create(
                7L, 10L, UUID.randomUUID().toString(),
                new LeadShortlistCommand(3L, null, List.of(11))));

        verify(receipts, never()).findByActorIdAndOperationIdAndAction(any(), any(), any());
        verify(leads, never()).findLockedById(any());
    }

    @Test
    void readableNonEditableReceiptConflictIsCheckedAfterCurrentReadAuthority() {
        LeadRoutingQueue lead = lead();
        UUID operationId = UUID.randomUUID();
        LeadCommandReceipt receipt = mock(LeadCommandReceipt.class);
        when(receipt.getPayloadHash()).thenReturn("another-payload");
        when(receipt.getOperationId()).thenReturn(operationId);
        when(receipt.getAction()).thenReturn("LEAD_SHORTLIST_CREATE");
        when(receipt.getResultReference()).thenReturn("20");
        LeadShortlist stored = new LeadShortlist(lead, Instant.now());
        when(shortlists.findById(20L)).thenReturn(Optional.of(stored));
        when(leads.findById(10L)).thenReturn(Optional.of(lead));
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(), List.of(), List.of(5L), List.of()));
        when(receipts.findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHORTLIST_CREATE")).thenReturn(Optional.of(receipt));

        assertThrows(com.indore.pathome.spaces.exception.VisitOperationsConflictException.class,
                () -> service.create(7L, 10L, operationId.toString(),
                        new LeadShortlistCommand(3L, null, List.of(11))));

        var order = inOrder(leads, access, receipts);
        order.verify(leads).findById(10L);
        order.verify(access).requireReadable(any(), eq(lead), eq(7L));
        order.verify(receipts).findByActorIdAndOperationIdAndAction(
                7L, operationId, "LEAD_SHORTLIST_CREATE");
        verify(leads, never()).findLockedById(any());
        verify(shortlists, never()).saveAndFlush(any());
    }

    @Test
    void duplicatePropertiesAreRejectedInsteadOfSilentlyCollapsed() {
        LeadRoutingQueue lead = lead();
        when(leads.findById(10L)).thenReturn(Optional.of(lead));
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(5L), List.of(), List.of(), List.of()));
        when(shortlists.saveAndFlush(any())).thenAnswer(invocation -> {
            LeadShortlist saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 20L);
            return saved;
        });
        when(leads.saveAndFlush(lead)).thenReturn(lead);
        when(staff.requireAttributableUser(7L)).thenReturn(mock(User.class));
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:30:00Z"));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                7L, 10L, UUID.randomUUID().toString(),
                new LeadShortlistCommand(3L, null, List.of(11, 11))));

        verify(publicProperties, never()).requireCurrentPublicPropertiesForUpdate(any(), any());
        verify(shortlists, never()).saveAndFlush(any());
    }

    @Test
    void actualReceiptLeadIsAuthorizedBeforePayloadHashAndWithoutDomainLocks() {
        LeadRoutingQueue request = lead();
        LeadRoutingQueue storedLead = mock(LeadRoutingQueue.class);
        when(storedLead.getId()).thenReturn(99L);
        when(leads.findById(10L)).thenReturn(Optional.of(request));
        when(leads.findById(99L)).thenReturn(Optional.of(storedLead));
        UUID key = UUID.randomUUID();
        LeadCommandReceipt receipt = new LeadCommandReceipt(mock(User.class), key,
                "LEAD_SHORTLIST_CREATE", "different", "20", Instant.now());
        when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHORTLIST_CREATE"))
                .thenReturn(Optional.of(receipt));
        when(shortlists.findById(20L)).thenReturn(Optional.of(new LeadShortlist(storedLead, Instant.now())));
        doThrow(LeadAccessContext.hidden()).when(access).requireReadable(any(), eq(storedLead), eq(7L));
        assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> service.create(
                7L, 10L, key.toString(), new LeadShortlistCommand(3L, null, List.of(11))));
        verify(access).requireReadable(any(), eq(request), eq(7L));
        verify(access).requireReadable(any(), eq(storedLead), eq(7L));
        verify(leads, never()).findLockedById(any());
        verify(shortlists, never()).findLockedById(any());
        verify(shortlists, never()).saveAndFlush(any());
    }

    @Test
    void malformedOrOrphanReceiptBindingFailsClosedBeforeConflict() {
        for (String reference : java.util.Arrays.asList(null, "", "bad", "-1", "0", "9223372036854775808", "999")) {
            UUID key = UUID.randomUUID();
            LeadRoutingQueue current = lead();
            when(leads.findById(10L)).thenReturn(Optional.of(current));
            LeadCommandReceipt receipt = new LeadCommandReceipt(mock(User.class), key,
                    "LEAD_SHORTLIST_CREATE", "different", reference, Instant.now());
            when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHORTLIST_CREATE"))
                    .thenReturn(Optional.of(receipt));
            assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> service.create(
                    7L, 10L, key.toString(), new LeadShortlistCommand(3L, null, List.of(11))));
        }
        verify(shortlists, never()).saveAndFlush(any());
    }

    @Test
    void replayReadFailureAfterResolutionHasNoWritesAndCanRetry() {
        LeadRoutingQueue lead=lead();when(leads.findById(10L)).thenReturn(Optional.of(lead));
        UUID key=UUID.randomUUID();var command=new LeadShortlistCommand(3L,null,List.of(11));
        String hash=ReflectionTestUtils.invokeMethod(service,"payloadHash",10L,command);
        LeadShortlist shortlist=new LeadShortlist(lead,Instant.now());
        ReflectionTestUtils.setField(shortlist,"id",20L);
        when(shortlists.findById(20L)).thenReturn(Optional.of(shortlist));
        when(receipts.findByActorIdAndOperationIdAndAction(7L,key,"LEAD_SHORTLIST_CREATE"))
                .thenReturn(Optional.of(new LeadCommandReceipt(mock(User.class),key,"LEAD_SHORTLIST_CREATE",hash,"20",Instant.now())));
        when(items.findAllByShortlist_IdOrderByPositionAsc(20L)).thenThrow(new IllegalStateException("injected read failure"));
        assertThrows(IllegalStateException.class,()->service.create(7L,10L,key.toString(),command));
        org.mockito.Mockito.doReturn(List.of()).when(items).findAllByShortlist_IdOrderByPositionAsc(20L);
        assertEquals(20L,service.create(7L,10L,key.toString(),command).id());
        verify(shortlists,never()).saveAndFlush(any());verify(receipts,never()).saveAndFlush(any());
        verify(activities,never()).save(any());verify(items,never()).save(any());verify(leads,never()).saveAndFlush(any());
    }

    @Test
    void wrongActionOrOperationBindingFailsClosed() {
        LeadRoutingQueue current=lead();when(leads.findById(10L)).thenReturn(Optional.of(current));
        for(boolean wrongAction:List.of(true,false)) {
            UUID key=UUID.randomUUID();
            var receipt=new LeadCommandReceipt(mock(User.class),wrongAction?key:UUID.randomUUID(),
                    wrongAction?"LEAD_SHARE_PREPARE":"LEAD_SHORTLIST_CREATE","different","20",Instant.now());
            when(receipts.findByActorIdAndOperationIdAndAction(7L,key,"LEAD_SHORTLIST_CREATE")).thenReturn(Optional.of(receipt));
            assertThrows(jakarta.persistence.EntityNotFoundException.class,()->service.create(7L,10L,key.toString(),new LeadShortlistCommand(3L,null,List.of())));
        }
        verify(shortlists,never()).findById(any());
    }


    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> storedNumericCorpus() {
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        var invalid = java.util.Arrays.asList(null, "", " ", "\t", "\n", "+20", "-20", "２０", "٢٠",
                "२०", "2０", "2٠", "0", "-0", "00000000000000000000", "0".repeat(100) + "20",
                "00000000000000000020", "9223372036854775808", "-9223372036854775808",
                "20x", "2e1", "20.0", "20\n", "20\r\n", "2_0", "2,0", "20\u00a0", "20\u200b",
                "20\u0000", "\ud83d\ude00", "9".repeat(1000));
        for (int i = 0; i < invalid.size(); i++) for (boolean changed : List.of(false, true)) {
            cases.add(org.junit.jupiter.params.provider.Arguments.of("invalid-" + i, invalid.get(i), changed));
        }
        return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} changed={2}")
    @org.junit.jupiter.params.provider.MethodSource("storedNumericCorpus")
    void storedNumericGrammarMatchesSelectorBeforeReplayOrConflict(String label, String reference,
                                                                  boolean changed) {
        LeadRoutingQueue lead = lead();
        when(leads.findById(10L)).thenReturn(Optional.of(lead));
        LeadShortlist stored = new LeadShortlist(lead, Instant.now());
        ReflectionTestUtils.setField(stored, "id", 20L);
        when(shortlists.findById(20L)).thenReturn(Optional.of(stored));
        UUID key = UUID.randomUUID();
        var command = new LeadShortlistCommand(3L, null, List.of(11));
        String hash = ReflectionTestUtils.invokeMethod(service, "payloadHash", 10L, command);
        var receipt = new LeadCommandReceipt(mock(User.class), key, "LEAD_SHORTLIST_CREATE",
                changed ? "different" : hash, reference, Instant.now());
        when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHORTLIST_CREATE"))
                .thenReturn(Optional.of(receipt));
        assertThrows(jakarta.persistence.EntityNotFoundException.class, () ->
                ReflectionTestUtils.invokeMethod(LeadService.class, "parseLeadReference", reference));
        Object observed;
        try { observed = service.create(7L, 10L, key.toString(), command); }
        catch (RuntimeException failure) { observed = failure; }
        System.out.println("NUMERIC_LEAF " + label + " changed=" + changed + " observed=" + observed);
        org.junit.jupiter.api.Assertions.assertInstanceOf(jakarta.persistence.EntityNotFoundException.class, observed);
        verify(shortlists, never()).findById(any());
        verify(receipts, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(audit);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"1", "20", "020", "0000000000000000020", "9223372036854775807"})
    void acceptedNumericGrammarKeepsExactReplayAndAuthorizedConflict(String reference) {
        LeadRoutingQueue lead = lead();
        when(leads.findById(10L)).thenReturn(Optional.of(lead));
        long id = Long.parseLong(reference);
        LeadShortlist stored = new LeadShortlist(lead, Instant.now());
        ReflectionTestUtils.setField(stored, "id", id);
        when(shortlists.findById(id)).thenReturn(Optional.of(stored));
        UUID key = UUID.randomUUID();
        var command = new LeadShortlistCommand(3L, null, List.of(11));
        String hash = ReflectionTestUtils.invokeMethod(service, "payloadHash", 10L, command);
        when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHORTLIST_CREATE"))
                .thenReturn(Optional.of(new LeadCommandReceipt(mock(User.class), key,
                        "LEAD_SHORTLIST_CREATE", hash, reference, Instant.now())));
        assertEquals(id, service.create(7L, 10L, key.toString(), command).id());
        assertThrows(com.indore.pathome.spaces.exception.VisitOperationsConflictException.class,
                () -> service.create(7L, 10L, key.toString(), new LeadShortlistCommand(4L, null, List.of(11))));
        verify(receipts, never()).saveAndFlush(any());
        verify(leads, never()).findLockedById(any());
    }

    @Test
    void replayUsesTheSingleValidatedReferenceEvenIfReceiptGetterChanges() {
        LeadRoutingQueue lead = lead();
        when(leads.findById(10L)).thenReturn(Optional.of(lead));
        LeadShortlist stored = new LeadShortlist(lead, Instant.now());
        ReflectionTestUtils.setField(stored, "id", 20L);
        when(shortlists.findById(20L)).thenReturn(Optional.of(stored));
        UUID key = UUID.randomUUID();
        var command = new LeadShortlistCommand(3L, null, List.of(11));
        String hash = ReflectionTestUtils.invokeMethod(service, "payloadHash", 10L, command);
        LeadCommandReceipt receipt = mock(LeadCommandReceipt.class);
        when(receipt.getOperationId()).thenReturn(key);
        when(receipt.getAction()).thenReturn("LEAD_SHORTLIST_CREATE");
        when(receipt.getPayloadHash()).thenReturn(hash);
        when(receipt.getResultReference()).thenReturn("20", "21");
        when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHORTLIST_CREATE"))
                .thenReturn(Optional.of(receipt));
        assertEquals(20L, service.create(7L, 10L, key.toString(), command).id());
        verify(receipt, org.mockito.Mockito.times(1)).getResultReference();
        verify(shortlists, never()).findById(21L);
    }

    @Test
    void canonicalShortlistRepositoryFailureRemainsInfrastructureFailure() {
        LeadRoutingQueue current = lead();
        when(leads.findById(10L)).thenReturn(Optional.of(current));
        UUID key = UUID.randomUUID();
        when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHORTLIST_CREATE"))
                .thenReturn(Optional.of(new LeadCommandReceipt(mock(User.class), key,
                        "LEAD_SHORTLIST_CREATE", "different", "20", Instant.now())));
        when(shortlists.findById(20L)).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("Synthetic unavailable"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () ->
                service.create(7L, 10L, key.toString(), new LeadShortlistCommand(3L, null, List.of())));
        verify(receipts, never()).saveAndFlush(any());
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void currentStoredLeadMustAuthorizeBothExactAndChangedReplay(boolean changed) {
        LeadRoutingQueue request = lead(), storedLead = mock(LeadRoutingQueue.class);
        when(storedLead.getId()).thenReturn(99L);
        when(leads.findById(10L)).thenReturn(Optional.of(request));
        when(leads.findById(99L)).thenReturn(Optional.of(storedLead));
        LeadShortlist stored = new LeadShortlist(storedLead, Instant.now());
        ReflectionTestUtils.setField(stored, "id", 20L);
        when(shortlists.findById(20L)).thenReturn(Optional.of(stored));
        UUID key = UUID.randomUUID();
        var command = new LeadShortlistCommand(3L, null, List.of(11));
        String hash = ReflectionTestUtils.invokeMethod(service, "payloadHash", 10L, command);
        when(receipts.findByActorIdAndOperationIdAndAction(7L, key, "LEAD_SHORTLIST_CREATE"))
                .thenReturn(Optional.of(new LeadCommandReceipt(mock(User.class), key,
                        "LEAD_SHORTLIST_CREATE", changed ? "different" : hash, "20", Instant.now())));
        doThrow(LeadAccessContext.hidden()).when(access).requireReadable(any(), eq(storedLead), eq(7L));
        assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> service.create(7L, 10L, key.toString(), command));
        verify(receipts, never()).saveAndFlush(any());
    }

    private static LeadRoutingQueue lead() {
        LeadRoutingQueue lead = mock(LeadRoutingQueue.class);
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        when(lead.getId()).thenReturn(10L);
        when(lead.getVersion()).thenReturn(3L);
        when(lead.getStatus()).thenReturn(LeadStatus.NEW);
        when(lead.getSupportedCity()).thenReturn(city);
        return lead;
    }
}
