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
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadContactPreferenceRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
