package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.AutomaticVisitRoutingControl;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.OperationalAuditEvent;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.TeamLocalityCoverage;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTask;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTaskState;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.repository.AutomaticVisitRoutingControlRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.TeamLocalityCoverageRepository;
import com.indore.pathome.spaces.repository.TeamLocalityCoverageRepository.ActiveCoverageRow;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomaticVisitRoutingServiceTest {

    @Test
    void oneVerifiedActiveCoverageRoutesTheTeamWithoutAssigningAUserOrChangingStatus() {
        Fixture fixture = fixture(List.of(coverageRow(31L, 9L, 4L)));

        fixture.service.processTask(7L);
        fixture.service.processTask(7L);

        assertEquals(fixture.team, fixture.request.getOperatingTeam());
        assertEquals("RECEIVED", fixture.request.getStatus());
        assertNull(fixture.request.getCoordinator());
        assertEquals(VisitRequestRoutingTaskState.DONE, fixture.task.getState());
        verify(fixture.audit, times(1)).recordSystemEvent(
                org.mockito.ArgumentMatchers.eq("AUTOMATIC_TEAM_ROUTED"),
                org.mockito.ArgumentMatchers.eq("PROPERTY_VISIT_REQUEST"),
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq("UNIQUE_CANONICAL_COVERAGE_V1"),
                org.mockito.ArgumentMatchers.eq(fixture.city),
                org.mockito.ArgumentMatchers.eq(fixture.team),
                any());
    }

    @Test
    void missingCoverageCreatesADurableBusinessExceptionWithoutGuessingATeam() {
        Fixture fixture = fixture(List.of());

        fixture.service.processTask(7L);

        assertNull(fixture.request.getOperatingTeam());
        assertEquals(VisitRequestRoutingTaskState.NEEDS_REVIEW, fixture.task.getState());
        assertEquals("NO_ACTIVE_TEAM_COVERAGE", fixture.task.getLastErrorCode());
        verify(fixture.audit, never()).recordSystemEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void overlappingCoverageNeverRoutesEvenWhenBothTeamsAreActive() {
        Fixture fixture = fixture(List.of(coverageRow(31L, 9L, 4L), coverageRow(32L, 12L, 2L)));

        fixture.service.processTask(7L);

        assertNull(fixture.request.getOperatingTeam());
        assertEquals(VisitRequestRoutingTaskState.NEEDS_REVIEW, fixture.task.getState());
        assertEquals("OVERLAPPING_ACTIVE_COVERAGE", fixture.task.getLastErrorCode());
        verify(fixture.audit, never()).recordSystemEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleRequestVersionBecomesReviewInsteadOfUsingAStalePreview() {
        Fixture fixture = fixture(List.of(coverageRow(31L, 9L, 4L)));
        fixture.request.setVersion(1L);

        fixture.service.processTask(7L);

        assertNull(fixture.request.getOperatingTeam());
        assertEquals(VisitRequestRoutingTaskState.NEEDS_REVIEW, fixture.task.getState());
        assertEquals("STALE_REQUEST_VERSION", fixture.task.getLastErrorCode());
        verify(fixture.audit, never()).recordSystemEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void retryDiagnosticAppliesBoundsBeforeItsTaskLockAndNeverReopensCompletedWork() {
        Fixture fixture = fixture(List.of(coverageRow(31L, 9L, 4L)));
        InOrder order = inOrder(fixture.bounds, fixture.tasks);

        fixture.service.recordTechnicalFailure(7L, "ROUTING_LOCK_OR_TIMEOUT");

        order.verify(fixture.bounds).apply();
        order.verify(fixture.tasks).findLockedById(7L);
        assertEquals(VisitRequestRoutingTaskState.RETRY, fixture.task.getState());

        Instant completedAt = Instant.parse("2026-10-23T12:00:01Z");
        fixture.task.completeManual(fixture.team, 1L, 88L, completedAt);
        fixture.service.recordTechnicalFailure(7L, "ROUTING_TECHNICAL_FAILURE");

        assertEquals(VisitRequestRoutingTaskState.DONE, fixture.task.getState());
        assertEquals(completedAt, fixture.task.getCompletedAt());
    }

    @Test
    void manualWinnerObservedAfterRequestWaitIsANoOpWithoutStaleEntityOrRewriting() {
        Fixture fixture = fixture(List.of(coverageRow(31L, 9L, 4L)));
        VisitRequestRoutingTask discovered = mock(VisitRequestRoutingTask.class);
        VisitRequestRoutingTask completedByManual = mock(VisitRequestRoutingTask.class);
        when(discovered.getState()).thenReturn(VisitRequestRoutingTaskState.PENDING);
        when(discovered.getRequest()).thenReturn(fixture.request);
        when(completedByManual.getState()).thenReturn(VisitRequestRoutingTaskState.DONE);
        when(completedByManual.getDecisionCode()).thenReturn("MANUAL_EXCEPTION_V1");
        when(fixture.tasks.findById(7L)).thenReturn(Optional.of(discovered));
        when(fixture.tasks.findLockedById(7L)).thenReturn(Optional.of(completedByManual));

        fixture.service.processTask(7L);

        verify(fixture.entityManager).clear();
        verify(fixture.audit, never()).recordSystemEvent(any(), any(), any(), any(), any(), any(), any());
        verify(completedByManual, never()).skip(any(), any());
        verify(completedByManual, never()).complete(any(), any(), any(), any(), any());
    }

    private static Fixture fixture(List<ActiveCoverageRow> initialRows) {
        VisitRequestRoutingTaskRepository tasks = mock(VisitRequestRoutingTaskRepository.class);
        PropertyVisitRequestRepository requests = mock(PropertyVisitRequestRepository.class);
        VisitSessionRepository sessions = mock(VisitSessionRepository.class);
        ListingRepository listings = mock(ListingRepository.class);
        LocalityRepository localities = mock(LocalityRepository.class);
        SupportedCityRepository cities = mock(SupportedCityRepository.class);
        OperatingTeamRepository teams = mock(OperatingTeamRepository.class);
        TeamLocalityCoverageRepository coverages = mock(TeamLocalityCoverageRepository.class);
        AutomaticVisitRoutingControlRepository controls = mock(AutomaticVisitRoutingControlRepository.class);
        OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
        OperationalAuditService audit = mock(OperationalAuditService.class);
        TransactionalCommandBounds bounds = mock(TransactionalCommandBounds.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        EntityManager entityManager = mock(EntityManager.class);

        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        when(city.isActive()).thenReturn(true);
        OperatingTeam team = mock(OperatingTeam.class);
        when(team.getId()).thenReturn(9L);
        when(team.isActive()).thenReturn(true);
        when(team.getCity()).thenReturn(city);
        Locality locality = mock(Locality.class);
        when(locality.getSupportedCity()).thenReturn(city);
        Listing listing = mock(Listing.class);
        when(listing.getId()).thenReturn(21L);
        when(listing.getCanonicalLocalityId()).thenReturn(13L);

        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(41L);
        request.setVersion(0L);
        request.setListing(listing);
        request.setSupportedCity(city);
        request.setOperationalScopeReady(true);
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        VisitRequestRoutingTask task = new VisitRequestRoutingTask(request, now);

        AutomaticVisitRoutingControl control = mock(AutomaticVisitRoutingControl.class);
        when(control.isEnabled()).thenReturn(true);
        when(control.getProtocolVersion()).thenReturn(1);
        when(control.getVersion()).thenReturn(2L);
        when(clock.now()).thenReturn(now);
        when(tasks.findById(7L)).thenReturn(Optional.of(task));
        when(tasks.findLockedById(7L)).thenReturn(Optional.of(task));
        when(requests.findSessionIdByRequestId(41L)).thenReturn(Optional.empty());
        when(requests.findLockedById(41L)).thenReturn(Optional.of(request));
        when(controls.findShared()).thenReturn(Optional.of(control));
        when(cities.findLockedById(5L)).thenReturn(Optional.of(city));
        when(listings.lockForSchedulingById(21L)).thenReturn(Optional.of(21L));
        when(listings.findCanonicalLocalityIdById(21L)).thenReturn(Optional.of(13L));
        when(localities.findById(13L)).thenReturn(Optional.of(locality));
        when(localities.findLockedById(13L)).thenReturn(Optional.of(locality));
        when(teams.findLockedById(9L)).thenReturn(Optional.of(team));
        when(coverages.findActiveRoutingCandidates(5L, 13L)).thenReturn(initialRows);
        TeamLocalityCoverage selectedCoverage = mock(TeamLocalityCoverage.class);
        when(selectedCoverage.isActive()).thenReturn(true);
        when(selectedCoverage.getVersion()).thenReturn(4L);
        when(coverages.findLockedById(31L)).thenReturn(Optional.of(selectedCoverage));
        OperationalAuditEvent auditEvent = mock(OperationalAuditEvent.class);
        when(auditEvent.getId()).thenReturn(88L);
        when(audit.recordSystemEvent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(auditEvent);

        AutomaticVisitRoutingService service = new AutomaticVisitRoutingService(tasks, requests, sessions,
                listings, localities, cities, teams, coverages, controls, guards, audit, bounds, clock,
                entityManager);
        return new Fixture(service, request, task, city, team, audit, tasks, bounds, entityManager);
    }

    private static ActiveCoverageRow coverageRow(Long coverageId, Long teamId, Long version) {
        ActiveCoverageRow row = mock(ActiveCoverageRow.class);
        when(row.getId()).thenReturn(coverageId);
        when(row.getTeamId()).thenReturn(teamId);
        when(row.getCoverageVersion()).thenReturn(version);
        return row;
    }

    private record Fixture(AutomaticVisitRoutingService service, PropertyVisitRequest request,
                           VisitRequestRoutingTask task, SupportedCity city, OperatingTeam team,
                           OperationalAuditService audit, VisitRequestRoutingTaskRepository tasks,
                           TransactionalCommandBounds bounds, EntityManager entityManager) {
    }
}
