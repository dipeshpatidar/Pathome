package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.RoutingTaskRecheckCommand;
import com.indore.pathome.spaces.dto.StaffCapabilitiesResponse;
import com.indore.pathome.spaces.dto.StaffCapabilitySummary;
import com.indore.pathome.spaces.dto.VisitRequestRoutingTaskView;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTask;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTaskState;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository.RoutingTaskRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisitRequestRoutingTaskServiceTest {

    @Test
    void grantExpiringWhileEvidenceWaitsCannotRequeueOrAudit() {
        Fixture fixture = fixture();
        when(fixture.access.currentCapabilities(7L)).thenReturn(
                capabilities(true), capabilities(false));

        assertThrows(AccessDeniedException.class, () -> fixture.service.recheck(
                7L, 31L, new RoutingTaskRecheckCommand(2L, "ROUTING_RECHECK")));

        verify(fixture.task, never()).requeue(any());
        verify(fixture.audit, never()).recordUserEvent(any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void unexpiredAuthorityAdmitsAfterEvidenceLocks() {
        Fixture fixture = fixture();
        when(fixture.access.currentCapabilities(7L)).thenReturn(
                capabilities(true), capabilities(true));

        VisitRequestRoutingTaskView view = fixture.service.recheck(
                7L, 31L, new RoutingTaskRecheckCommand(2L, "ROUTING_RECHECK"));

        verify(fixture.task).requeue(fixture.now);
        verify(fixture.audit).recordUserEvent(any(), any(), any(), any(), any(),
                any(), any(), any());
        org.junit.jupiter.api.Assertions.assertEquals(31L, view.taskId());
    }

    @Test
    void survivingRoutingAuthorityIsEvaluatedFromTheFreshScope() {
        Fixture fixture = fixture();
        when(fixture.access.currentCapabilities(7L)).thenReturn(
                capabilities(StaffCapability.CITY_TEAM_ADMIN),
                capabilities(StaffCapability.OPS_ROUTING));

        fixture.service.recheck(7L, 31L,
                new RoutingTaskRecheckCommand(2L, "ROUTING_RECHECK"));

        verify(fixture.task).requeue(fixture.now);
    }

    @Test
    void survivingCityAdministrationIsAlsoEvaluatedFromTheFreshScope() {
        Fixture fixture = fixture();
        when(fixture.access.currentCapabilities(7L)).thenReturn(
                capabilities(StaffCapability.OPS_ROUTING),
                capabilities(StaffCapability.CITY_TEAM_ADMIN));

        fixture.service.recheck(7L, 31L,
                new RoutingTaskRecheckCommand(2L, "ROUTING_RECHECK"));

        verify(fixture.task).requeue(fixture.now);
    }

    /** Isolated legacy adapter: it admits from the pre-evidence scope and therefore mutates. */
    @Test
    void negativeControlLegacyPreEvidenceAdapterReachesTheMutation() {
        Fixture fixture = fixture();
        when(fixture.access.currentCapabilities(7L)).thenReturn(
                capabilities(true), capabilities(false));
        IntakeRoutingAuthority.Scope initialScope =
                IntakeRoutingAuthority.resolve(fixture.access, 7L);
        Runnable legacyAdapter = () -> {
            if (initialScope.hasRoutingAuthority(5L)) fixture.task.requeue(fixture.now);
        };

        legacyAdapter.run();

        verify(fixture.task).requeue(fixture.now);
    }

    @Test
    void unauthorizedDirectRecordReadIsConcealedAsMissingBeforeRowLookup() {
        Fixture fixture = fixture();
        when(fixture.access.currentCapabilities(7L)).thenReturn(capabilities(false));

        assertThrows(EntityNotFoundException.class, () -> fixture.service.read(7L, 31L));

        verify(fixture.tasks, never()).findRoutingTaskRow(any());
    }

    private static Fixture fixture() {
        VisitRequestRoutingTaskRepository tasks = mock(VisitRequestRoutingTaskRepository.class);
        PropertyVisitRequestRepository requests = mock(PropertyVisitRequestRepository.class);
        StaffAccessService access = mock(StaffAccessService.class);
        OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
        OperationalAuditService audit = mock(OperationalAuditService.class);
        ListingRepository listings = mock(ListingRepository.class);
        LocalityRepository localities = mock(LocalityRepository.class);
        TransactionalCommandBounds bounds = mock(TransactionalCommandBounds.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        EntityManager entityManager = mock(EntityManager.class);

        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        when(clock.now()).thenReturn(now);
        RoutingTaskRow row = mock(RoutingTaskRow.class);
        when(row.getId()).thenReturn(31L);
        when(row.getRequestId()).thenReturn(41L);
        when(row.getRequestVersion()).thenReturn(3L);
        when(row.getRequestStatus()).thenReturn("RECEIVED");
        when(row.getCityId()).thenReturn(5L);
        when(row.getLocalityCityId()).thenReturn(5L);
        when(row.getLocalityId()).thenReturn(13L);
        when(row.getListingId()).thenReturn(21L);
        when(row.getScopeReady()).thenReturn(true);
        when(row.getTaskVersion()).thenReturn(2L);
        when(row.getTaskState()).thenReturn("NEEDS_REVIEW");
        when(row.getCityDisplayName()).thenReturn("Indore");
        when(row.getLocalityDisplayName()).thenReturn("Vijay Nagar");
        when(row.getListingTitle()).thenReturn("Sunlit 3 BHK");
        when(tasks.findRoutingTaskRow(31L)).thenReturn(Optional.of(row));

        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        PropertyVisitRequest request = mock(PropertyVisitRequest.class);
        when(request.getSupportedCity()).thenReturn(city);
        when(requests.findSessionIdByRequestId(41L)).thenReturn(Optional.empty());
        when(requests.findLockedById(41L)).thenReturn(Optional.of(request));

        VisitRequestRoutingTask task = mock(VisitRequestRoutingTask.class);
        when(task.getId()).thenReturn(31L);
        when(task.getVersion()).thenReturn(2L);
        when(task.getState()).thenReturn(VisitRequestRoutingTaskState.NEEDS_REVIEW);
        when(task.getRequest()).thenReturn(request);
        when(tasks.findLockedById(31L)).thenReturn(Optional.of(task));
        when(listings.lockForSchedulingById(21L)).thenReturn(Optional.of(21L));
        when(localities.findLockedById(13L)).thenReturn(Optional.of(mock(Locality.class)));
        when(access.requireAttributableUser(7L)).thenReturn(mock(User.class));

        VisitRequestRoutingTaskService service = new VisitRequestRoutingTaskService(tasks, requests, access,
                guards, audit, listings, localities, bounds, clock, entityManager);
        return new Fixture(service, access, task, audit, tasks, now);
    }

    private static StaffCapabilitiesResponse capabilities(boolean allowed) {
        return allowed
                ? capabilities(StaffCapability.OPS_ROUTING)
                : new StaffCapabilitiesResponse(true, List.of(), Instant.parse("2026-10-23T12:00:00Z"));
    }

    private static StaffCapabilitiesResponse capabilities(StaffCapability capability) {
        return new StaffCapabilitiesResponse(true,
                List.of(new StaffCapabilitySummary(capability, StaffScopeType.CITY,
                        5L, "Indore", null, null)),
                Instant.parse("2026-10-23T12:00:00Z"));
    }

    private record Fixture(VisitRequestRoutingTaskService service,
                           StaffAccessService access,
                           VisitRequestRoutingTask task,
                           OperationalAuditService audit,
                           VisitRequestRoutingTaskRepository tasks,
                           Instant now) {
    }
}
