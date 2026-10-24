package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.crm.LeadShortlistCommand;
import com.indore.pathome.spaces.dto.crm.LeadShortlistView;
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
        verify(publicProperties).requireCurrentPublicProperties(lead, List.of(11L, 12L));
        verify(items, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void duplicatePropertiesAreRejectedInsteadOfSilentlyCollapsed() {
        LeadRoutingQueue lead = lead();
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

        verify(publicProperties, never()).requireCurrentPublicProperties(any(), any());
        verify(shortlists, never()).saveAndFlush(any());
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
