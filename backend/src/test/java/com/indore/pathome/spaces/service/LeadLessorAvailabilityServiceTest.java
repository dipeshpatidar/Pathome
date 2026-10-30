package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AvailabilityCreateCommand;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadLessorAvailabilityEnquiryRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadShortlistItemRepository;
import com.indore.pathome.spaces.repository.LeadShortlistRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadLessorAvailabilityServiceTest {
    @Test
    void foreignClosedLeadIsConcealedBeforeLifecycleAndVersionChecks() {
        Fixture fixture = fixture(LeadStatus.CLOSED);
        doThrow(new jakarta.persistence.EntityNotFoundException("Lead not found"))
                .when(fixture.leadAccess()).requireContactOwner(any(),
                        eq(fixture.lead()), eq(99L));

        var foreign = assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> fixture.service().create(99L, 1L, UUID.randomUUID().toString(),
                        fixture.command(4L)));
        when(fixture.leads().findLockedById(2L)).thenReturn(Optional.empty());
        var absent = assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> fixture.service().create(99L, 2L, UUID.randomUUID().toString(),
                        fixture.command(4L)));

        verify(fixture.leadAccess()).requireContactOwner(any(), eq(fixture.lead()), eq(99L));
        assertEquals(absent.getMessage(), foreign.getMessage());
    }

    @Test
    void currentOwnerWithStaleLeadVersionReceivesVisibleConflict() {
        Fixture fixture = fixture(LeadStatus.QUALIFYING);

        assertThrows(VisitOperationsConflictException.class,
                () -> fixture.service().create(5L, 1L, UUID.randomUUID().toString(),
                        fixture.command(4L)));
    }

    private Fixture fixture(LeadStatus status) {
        LeadRoutingQueueRepository leads = mock(LeadRoutingQueueRepository.class);
        LeadShortlistRepository shortlists = mock(LeadShortlistRepository.class);
        LeadShortlistItemRepository shortlistItems = mock(LeadShortlistItemRepository.class);
        LeadSourceEventRepository sourceEvents = mock(LeadSourceEventRepository.class);
        LeadLessorAvailabilityEnquiryRepository enquiries =
                mock(LeadLessorAvailabilityEnquiryRepository.class);
        LeadActivityRepository activities = mock(LeadActivityRepository.class);
        LeadAssistanceCommandReceiptRepository receipts =
                mock(LeadAssistanceCommandReceiptRepository.class);
        LeadPhoneVerificationService phoneVerification = mock(LeadPhoneVerificationService.class);
        LeadAccessService leadAccess = mock(LeadAccessService.class);
        StaffAccessService staffAccess = mock(StaffAccessService.class);
        OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
        OperationalAuditService audit = mock(OperationalAuditService.class);
        TransactionalCommandBounds bounds = mock(TransactionalCommandBounds.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        LeadDataProtection protection = mock(LeadDataProtection.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EntityManager entityManager = mock(EntityManager.class);

        User owner = new User();
        owner.setId(5L);
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(1L);
        OperatingTeam team = mock(OperatingTeam.class);
        when(team.getId()).thenReturn(2L);
        LeadRoutingQueue lead = mock(LeadRoutingQueue.class);
        when(lead.getId()).thenReturn(1L);
        when(lead.getStatus()).thenReturn(status);
        when(lead.getVersion()).thenReturn(5L);
        when(lead.getOwner()).thenReturn(owner);
        when(lead.getSupportedCity()).thenReturn(city);
        when(lead.getOperatingTeam()).thenReturn(team);
        when(leads.findLockedById(1L)).thenReturn(Optional.of(lead));
        when(guards.cityIsActive(1L)).thenReturn(true);
        when(guards.teamIsActive(2L)).thenReturn(true);
        when(guards.teamCityId(2L)).thenReturn(1L);
        when(leadAccess.context(5L)).thenReturn(new LeadAccessContext(false,
                List.of(), List.of(), List.of(2L), List.of()));
        when(clock.now()).thenReturn(Instant.parse("2026-10-27T10:00:00Z"));

        LeadLessorAvailabilityService service = new LeadLessorAvailabilityService(
                leads, shortlists, shortlistItems, sourceEvents, enquiries, activities,
                receipts, phoneVerification, leadAccess, staffAccess, guards, audit,
                bounds, clock, protection, jdbc, new ObjectMapper(), entityManager);
        return new Fixture(service, lead, leads, leadAccess, guards);
    }

    private record Fixture(
            LeadLessorAvailabilityService service,
            LeadRoutingQueue lead,
            LeadRoutingQueueRepository leads,
            LeadAccessService leadAccess,
            OperationalSecurityGuards guards) {
        AvailabilityCreateCommand command(Long expectedLeadVersion) {
            return new AvailabilityCreateCommand(11L, 21L, UUID.randomUUID(),
                    expectedLeadVersion, 31L, 41L, true);
        }
    }
}
