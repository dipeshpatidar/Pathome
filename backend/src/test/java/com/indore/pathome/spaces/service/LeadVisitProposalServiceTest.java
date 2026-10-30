package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitProposalCreateCommand;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadContactPreferenceRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadShortlistItemRepository;
import com.indore.pathome.spaces.repository.LeadShortlistRepository;
import com.indore.pathome.spaces.repository.LeadVisitAssociationClaimRepository;
import com.indore.pathome.spaces.repository.LeadVisitContinuationRepository;
import com.indore.pathome.spaces.repository.LeadVisitProposalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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

class LeadVisitProposalServiceTest {
    @Test
    void foreignClosedLeadIsConcealedBeforeLifecycleAndVersionChecks() {
        Fixture fixture = fixture(LeadStatus.CLOSED);
        doThrow(new jakarta.persistence.EntityNotFoundException("Lead not found"))
                .when(fixture.leadAccess()).requireContactOwner(any(),
                        eq(fixture.lead()), eq(99L));

        var foreign = assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> fixture.service().issue(99L, 1L, UUID.randomUUID().toString(),
                        fixture.command(4L)));
        when(fixture.leads().findLockedById(2L)).thenReturn(Optional.empty());
        var absent = assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> fixture.service().issue(99L, 2L, UUID.randomUUID().toString(),
                        fixture.command(4L)));

        verify(fixture.leadAccess()).requireContactOwner(any(), eq(fixture.lead()), eq(99L));
        assertEquals(absent.getMessage(), foreign.getMessage());
    }

    @Test
    void currentOwnerWithStaleLeadVersionReceivesVisibleConflict() {
        Fixture fixture = fixture(LeadStatus.QUALIFYING);

        assertThrows(VisitOperationsConflictException.class,
                () -> fixture.service().issue(5L, 1L, UUID.randomUUID().toString(),
                        fixture.command(4L)));
    }

    private Fixture fixture(LeadStatus status) {
        LeadRoutingQueueRepository leads = mock(LeadRoutingQueueRepository.class);
        LeadShortlistRepository shortlists = mock(LeadShortlistRepository.class);
        LeadShortlistItemRepository shortlistItems = mock(LeadShortlistItemRepository.class);
        LeadContactPreferenceRepository preferences = mock(LeadContactPreferenceRepository.class);
        LeadVisitProposalRepository proposals = mock(LeadVisitProposalRepository.class);
        LeadVisitContinuationRepository continuations = mock(LeadVisitContinuationRepository.class);
        LeadVisitAssociationClaimRepository associationClaims =
                mock(LeadVisitAssociationClaimRepository.class);
        LeadActivityRepository activities = mock(LeadActivityRepository.class);
        LeadAssistanceCommandReceiptRepository receipts =
                mock(LeadAssistanceCommandReceiptRepository.class);
        LeadAccessService leadAccess = mock(LeadAccessService.class);
        StaffAccessService staffAccess = mock(StaffAccessService.class);
        OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
        OperationalAuditService audit = mock(OperationalAuditService.class);
        TransactionalCommandBounds bounds = mock(TransactionalCommandBounds.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        LeadPublicPropertyService publicProperties = mock(LeadPublicPropertyService.class);
        LeadAssistanceSecretService secrets = mock(LeadAssistanceSecretService.class);
        LeadDataProtection protection = mock(LeadDataProtection.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

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

        LeadVisitProposalService service = new LeadVisitProposalService(
                leads, shortlists, shortlistItems, preferences, proposals, continuations,
                associationClaims, activities, receipts, leadAccess, staffAccess, guards,
                audit, bounds, clock, publicProperties, secrets, protection,
                new ObjectMapper(), jdbc, "https://app.pathome.test");
        return new Fixture(service, lead, leads, leadAccess);
    }

    private record Fixture(
            LeadVisitProposalService service,
            LeadRoutingQueue lead,
            LeadRoutingQueueRepository leads,
            LeadAccessService leadAccess) {
        VisitProposalCreateCommand command(Long expectedLeadVersion) {
            return new VisitProposalCreateCommand(expectedLeadVersion, 11L, 21L, 31L,
                    41L, 51L);
        }
    }
}
