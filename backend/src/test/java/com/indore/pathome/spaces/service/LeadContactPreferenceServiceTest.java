package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadContactPreferenceCommand;
import com.indore.pathome.spaces.dto.crm.LeadContactPreferenceView;
import com.indore.pathome.spaces.entity.LeadConsentEvidenceMethod;
import com.indore.pathome.spaces.entity.LeadConsentStatus;
import com.indore.pathome.spaces.entity.LeadContactChannel;
import com.indore.pathome.spaces.entity.LeadContactPreference;
import com.indore.pathome.spaces.entity.LeadDestinationChoice;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadContactPreferenceRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadContactPreferenceServiceTest {
    private final LeadRoutingQueueRepository leads = mock(LeadRoutingQueueRepository.class);
    private final LeadContactPreferenceRepository preferences =
            mock(LeadContactPreferenceRepository.class);
    private final LeadActivityRepository activities = mock(LeadActivityRepository.class);
    private final LeadCommandReceiptRepository receipts = mock(LeadCommandReceiptRepository.class);
    private final LeadAccessService access = mock(LeadAccessService.class);
    private final StaffAccessService staff = mock(StaffAccessService.class);
    private final OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
    private final OperationalAuditService audit = mock(OperationalAuditService.class);
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final LeadDataProtection protection = new LeadDataProtection(
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()),
            "primary");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LeadContactPreferenceService service = new LeadContactPreferenceService(
            leads, preferences, activities, receipts, access, staff, guards, audit, clock,
            protection, jdbc, new ObjectMapper());

    @Test
    void sameDestinationUsesTheLeadNumberAndStoresOnlyCiphertext() {
        LeadRoutingQueue lead = lead();
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(5L), List.of(), List.of(), List.of()));
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L)).thenReturn(Optional.empty());
        when(preferences.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(leads.saveAndFlush(lead)).thenReturn(lead);
        User actor = mock(User.class);
        when(staff.requireAttributableUser(7L)).thenReturn(actor);
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:30:00Z"));
        LeadContactPreferenceCommand command = new LeadContactPreferenceCommand(2L, null,
                LeadContactChannel.WHATSAPP, LeadDestinationChoice.SAME, null, null,
                LeadConsentStatus.GRANTED, "property-sharing-v1",
                LeadConsentEvidenceMethod.CUSTOMER_DIGITAL);

        LeadContactPreferenceView view = service.record(7L, 10L, command);

        assertEquals(1L, view.revision());
        assertEquals(LeadConsentStatus.GRANTED, view.consentStatus());
        assertNotEquals("+919876543210", view.maskedDestination());
        assertEquals("********3210", view.maskedDestination());
        verify(preferences).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                value -> value.getDestinationCiphertext() != null
                        && !value.getDestinationCiphertext().contains("9876543210")));
    }

    @Test
    void consentIsNeverImplicitlyGrantedWithoutCustomerEvidence() {
        LeadRoutingQueue lead = lead();
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        when(access.context(7L)).thenReturn(
                new LeadAccessContext(false, List.of(5L), List.of(), List.of(), List.of()));
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L)).thenReturn(Optional.empty());
        LeadContactPreferenceCommand command = new LeadContactPreferenceCommand(2L, null,
                LeadContactChannel.WHATSAPP, LeadDestinationChoice.SAME, null, null,
                LeadConsentStatus.GRANTED, "property-sharing-v1", null);

        assertThrows(IllegalArgumentException.class, () -> service.record(7L, 10L, command));
        verify(preferences, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(LeadDestinationChoice.class)
    void cityIntakeCanEditUnassignedLeadButCannotRecordSensitiveConsent(
            LeadDestinationChoice destinationChoice) {
        LeadRoutingQueue lead = lead();
        LeadContactPreferenceService ownerOnlyService = serviceWithRealAccess();
        StaffAccessGrant intake = intakeGrant();
        when(staff.effectiveGrants(7L)).thenReturn(List.of(intake));
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        User actor = owner();
        when(staff.requireAttributableUser(7L)).thenReturn(actor);
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L)).thenReturn(Optional.empty());
        when(preferences.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(leads.saveAndFlush(lead)).thenReturn(lead);
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:30:00Z"));
        LeadContactPreferenceCommand command = contactCommand(destinationChoice);

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> ownerOnlyService.record(7L, 10L, command));

        verify(preferences, never()).saveAndFlush(any());
        verify(activities, never()).save(any());
        verify(receipts, never()).saveAndFlush(any());
        verify(leads, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(LeadDestinationChoice.class)
    void currentOwnerStillRecordsConsentThroughTheOwnerOnlyAdmission(
            LeadDestinationChoice destinationChoice) {
        LeadRoutingQueue lead = ownedLead();
        LeadContactPreferenceService ownerOnlyService = serviceWithRealAccess();
        StaffAccessGrant coordinate = coordinatorGrant();
        when(staff.effectiveGrants(7L)).thenReturn(List.of(coordinate));
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L)).thenReturn(Optional.empty());
        when(preferences.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(leads.saveAndFlush(lead)).thenReturn(lead);
        User owner = owner();
        when(staff.requireAttributableUser(7L)).thenReturn(owner);
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:30:00Z"));
        LeadContactPreferenceCommand command = contactCommand(destinationChoice);

        LeadContactPreferenceView view = ownerOnlyService.record(7L, 10L, command);

        assertEquals(LeadConsentStatus.GRANTED, view.consentStatus());
        verify(preferences).saveAndFlush(any());
    }

    @Test
    void exactConsentReplayAfterOwnershipIsLostReturnsNoHistoricalPreference() {
        LeadRoutingQueue lead = ownedLead();
        LeadContactPreferenceService ownerOnlyService = serviceWithRealAccess();
        StaffAccessGrant coordinate = coordinatorGrant();
        StaffAccessGrant intake = intakeGrant();
        AtomicBoolean ownerActive = new AtomicBoolean(true);
        when(staff.effectiveGrants(7L)).thenAnswer(
                invocation -> List.of(ownerActive.get() ? coordinate : intake));
        User actor = owner();
        when(staff.requireAttributableUser(7L)).thenReturn(actor);
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        when(leads.saveAndFlush(lead)).thenReturn(lead);
        when(preferences.findFirstByLead_IdOrderByRevisionDesc(10L)).thenReturn(Optional.empty());
        when(preferences.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicReference<com.indore.pathome.spaces.entity.LeadCommandReceipt> receipt =
                new AtomicReference<>();
        when(receipts.findByActorIdAndOperationIdAndAction(
                eq(7L), any(), eq(LeadContactPreferenceService.COMMAND_ACTION)))
                .thenAnswer(invocation -> Optional.ofNullable(receipt.get()));
        when(receipts.saveAndFlush(any())).thenAnswer(invocation -> {
            com.indore.pathome.spaces.entity.LeadCommandReceipt saved =
                    invocation.getArgument(0);
            receipt.set(saved);
            return saved;
        });
        when(clock.now()).thenReturn(Instant.parse("2026-10-22T04:30:00Z"));
        UUID operationId = UUID.randomUUID();
        LeadContactPreferenceCommand command = new LeadContactPreferenceCommand(2L, null,
                LeadContactChannel.WHATSAPP, LeadDestinationChoice.SAME, null, null,
                LeadConsentStatus.GRANTED, "property-sharing-v1",
                LeadConsentEvidenceMethod.CUSTOMER_DIGITAL);

        ownerOnlyService.record(7L, 10L, operationId.toString(), command);
        ownerActive.set(false);

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> ownerOnlyService.record(7L, 10L, operationId.toString(), command));
        verify(preferences, times(1)).saveAndFlush(any());
        verify(receipts, times(1)).saveAndFlush(any());
    }

    @Test
    void ownerAdmissionReadsCurrentGrantsOnlyAfterSecurityGuards() {
        LeadRoutingQueue lead = ownedLead();
        LeadAccessContext ownerContext =
                new LeadAccessContext(false, List.of(), List.of(), List.of(9L), List.of());
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        when(access.context(7L)).thenReturn(ownerContext);
        org.mockito.Mockito.doThrow(LeadAccessContext.hidden())
                .when(access).requireContactOwner(eq(ownerContext), eq(lead), eq(7L));
        LeadContactPreferenceCommand command = contactCommand(LeadDestinationChoice.SAME);

        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> service.record(7L, 10L, command));

        org.mockito.InOrder order = inOrder(guards, access);
        order.verify(guards).acquire(List.of(5L), List.of(9L), List.of(7L));
        order.verify(access).context(7L);
    }

    private static LeadContactPreferenceCommand contactCommand(
            LeadDestinationChoice destinationChoice) {
        return new LeadContactPreferenceCommand(2L, null, LeadContactChannel.WHATSAPP,
                destinationChoice,
                destinationChoice == LeadDestinationChoice.DIFFERENT ? "+91" : null,
                destinationChoice == LeadDestinationChoice.DIFFERENT ? "9876543210" : null,
                LeadConsentStatus.GRANTED, "property-sharing-v1",
                LeadConsentEvidenceMethod.CUSTOMER_DIGITAL);
    }

    @Test
    void foreignOrMalformedReceiptResultIsConcealedBeforePayloadComparison() {
        LeadRoutingQueue lead = ownedLead();
        when(leads.findLockedById(10L)).thenReturn(Optional.of(lead));
        LeadRoutingQueue foreign = mock(LeadRoutingQueue.class);
        when(foreign.getId()).thenReturn(99L);
        LeadContactPreference preference = mock(LeadContactPreference.class);
        when(preference.getLead()).thenReturn(foreign);
        when(preferences.findById(20L)).thenReturn(Optional.of(preference));
        LeadContactPreferenceCommand command = new LeadContactPreferenceCommand(2L, null,
                LeadContactChannel.WHATSAPP, LeadDestinationChoice.SAME, null, null,
                LeadConsentStatus.GRANTED, "property-sharing-v1", LeadConsentEvidenceMethod.CUSTOMER_DIGITAL);
        for (String reference : java.util.Arrays.asList(null, "bad", "preference:20:revision:1:leadVersion:2",
                "preference:999:revision:1:leadVersion:2")) {
            UUID key = UUID.randomUUID();
            var receipt = new com.indore.pathome.spaces.entity.LeadCommandReceipt(mock(User.class), key,
                    LeadContactPreferenceService.COMMAND_ACTION, "different", reference, Instant.now());
            when(receipts.findByActorIdAndOperationIdAndAction(7L,key,LeadContactPreferenceService.COMMAND_ACTION))
                    .thenReturn(Optional.of(receipt));
            assertThrows(jakarta.persistence.EntityNotFoundException.class,
                    () -> service.record(7L,10L,key.toString(),command));
        }
        verify(preferences,never()).saveAndFlush(any());
        verify(receipts,never()).saveAndFlush(any());
    }

    private LeadContactPreferenceService serviceWithRealAccess() {
        LeadAccessService realAccess = new LeadAccessService(staff);
        return new LeadContactPreferenceService(
                leads, preferences, activities, receipts, realAccess, staff, guards, audit, clock,
                protection, jdbc, new ObjectMapper());
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

    private LeadRoutingQueue ownedLead() {
        LeadRoutingQueue lead = lead();
        com.indore.pathome.spaces.entity.OperatingTeam team = mock(
                com.indore.pathome.spaces.entity.OperatingTeam.class);
        when(team.getId()).thenReturn(9L);
        when(lead.getOperatingTeam()).thenReturn(team);
        User owner = owner();
        when(lead.getOwner()).thenReturn(owner);
        return lead;
    }

    private static User owner() {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(7L);
        return owner;
    }

    private static LeadRoutingQueue lead() {
        LeadRoutingQueue lead = mock(LeadRoutingQueue.class);
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        when(lead.getId()).thenReturn(10L);
        when(lead.getVersion()).thenReturn(2L);
        when(lead.getStatus()).thenReturn(LeadStatus.NEW);
        when(lead.getSupportedCity()).thenReturn(city);
        when(lead.getContactPhoneNormalized()).thenReturn("+919876543210");
        return lead;
    }
}
