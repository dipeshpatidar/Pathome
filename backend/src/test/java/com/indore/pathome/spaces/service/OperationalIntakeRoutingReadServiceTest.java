package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.IntakeRoutingCandidatesView;
import com.indore.pathome.spaces.dto.IntakeRoutingRequestPage;
import com.indore.pathome.spaces.dto.IntakeRoutingRequestView;
import com.indore.pathome.spaces.dto.StaffCapabilitiesResponse;
import com.indore.pathome.spaces.dto.StaffCapabilitySummary;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.repository.OperationalIntakeRoutingRepository;
import com.indore.pathome.spaces.repository.OperationalIntakeRoutingRepository.RoutingRow;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationalIntakeRoutingReadServiceTest {
    private final OperationalIntakeRoutingRepository routing = mock(OperationalIntakeRoutingRepository.class);
    private final SupportedCityRepository cities = mock(SupportedCityRepository.class);
    private final OperatingTeamRepository teams = mock(OperatingTeamRepository.class);
    private final StaffAccessService access = mock(StaffAccessService.class);
    private final VisitRequestRoutingTaskRepository routingTasks =
            mock(VisitRequestRoutingTaskRepository.class);
    private final OperationalIntakeRoutingReadService service =
            new OperationalIntakeRoutingReadService(routing, cities, teams, access, routingTasks);

    // ------------------------------------------------------------------ derived classification

    @Test
    void locationDerivationIsCanonicalUnresolvedOrConflicting() {
        assertEquals(IntakeRoutingRequestView.LocationState.CANONICAL,
                view(row(1L, 5L, 5L, null)).locationState());
        assertEquals(IntakeRoutingRequestView.LocationState.UNRESOLVED,
                view(row(1L, null, null, null)).locationState());
        assertEquals(IntakeRoutingRequestView.LocationState.UNRESOLVED,
                view(row(1L, null, 5L, null)).locationState());
        assertEquals(IntakeRoutingRequestView.LocationState.UNRESOLVED,
                view(row(1L, 5L, null, null)).locationState());
        assertEquals(IntakeRoutingRequestView.LocationState.CONFLICTING,
                view(row(2L, 1L, 5L, null)).locationState());
    }

    @Test
    void teamReadinessRequiresAnActiveSameCityTeamAndAReadyScope() {
        assertTrue(view(row(1L, 1L, 5L, null, 9L, true, true, true, 1L)).teamReady());
        assertFalse(view(row(1L, 1L, 5L, null, 9L, false, true, true, 1L)).teamReady());
        assertFalse(view(row(1L, 1L, 5L, null, 9L, true, false, true, 1L)).teamReady());
        assertFalse(view(row(1L, 1L, 5L, null, 9L, true, true, false, 1L)).teamReady());
        assertFalse(view(row(1L, 1L, 5L, null, 9L, true, true, true, 2L)).teamReady());
        assertFalse(view(row(1L, 1L, 5L, null, null, true, true, true, null)).teamReady());
        assertFalse(view(row(1L, null, 5L, null, 9L, true, true, true, 1L)).teamReady());
    }

    @Test
    void eligibilityRequiresAStandaloneUnclaimedReceivedRequest() {
        assertTrue(view(row(1L, 5L, 5L, "RECEIVED")).initialRoutingEligible());
        assertFalse(view(row(1L, 1L, 5L, "COORDINATING")).initialRoutingEligible());
        assertTrue(view(row(1L, 1L, 5L, "RECEIVED")).initialRoutingEligible());
        assertFalse(view(row(1L, 1L, 5L, "RECEIVED", 9L, true, true, true, 1L)).initialRoutingEligible(),
                "an already Team-routed Request is never an initial-routing candidate");
        assertFalse(view(row(1L, 1L, 5L, "RECEIVED", null, null, null, null, null, 4L, null, null))
                .initialRoutingEligible(), "a claimed Request is never an initial-routing candidate");
        assertFalse(view(row(1L, 1L, 5L, "RECEIVED", null, null, null, null, null, null, 33L, null))
                .initialRoutingEligible(), "a Session-linked Request is never an initial-routing candidate");
    }

    // ------------------------------------------------------------------ scoped reads

    @Test
    void globalStaffAdminReadsEveryEligibleRequestAcrossCities() {
        when(access.currentCapabilities(1L)).thenReturn(globalAdmin());
        when(routing.findEligibleRequestPage(any())).thenReturn(new PageImpl<>(List.of(row(1L, 1L, 5L, null))));

        IntakeRoutingRequestPage page = service.listEligibleRequests(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals(0, page.page());
        assertEquals(20, page.size());
        verify(routing).findEligibleRequestPage(any());
        verify(routing, never()).findEligibleCityScopedRequestPage(any(), any());
    }

    @Test
    void cityTeamAdminReadsOnlyItsAuthorizedCanonicallyResolvedCities() {
        when(access.currentCapabilities(2L)).thenReturn(cityAdmin(5L));
        when(routing.findEligibleCityScopedRequestPage(eq(List.of(5L)), any()))
                .thenReturn(new PageImpl<>(List.of(row(1L, 5L, 5L, null))));

        IntakeRoutingRequestPage page = service.listEligibleRequests(2L, 0, 20);

        assertEquals(1, page.items().size());
        verify(routing).findEligibleCityScopedRequestPage(List.of(5L), org.springframework.data.domain.PageRequest.of(0, 20));
        verify(routing, never()).findEligibleRequestPage(any());
    }

    @Test
    void routingOnlyStaffReadsOnlyDurablyEnrolledIntake() {
        when(access.currentCapabilities(9L)).thenReturn(routingOnly(5L));
        when(routing.findEligibleRoutingOnlyRequestPage(eq(List.of(5L)), any()))
                .thenReturn(new PageImpl<>(List.of(row(1L, 5L, 5L, null))));

        IntakeRoutingRequestPage page = service.listEligibleRequests(9L, 0, 20);

        assertEquals(1, page.items().size());
        verify(routing).findEligibleRoutingOnlyRequestPage(
                List.of(5L), org.springframework.data.domain.PageRequest.of(0, 20));
        verify(routing, never()).findEligibleCityScopedRequestPage(any(), any());
        verify(routing, never()).findEligibleRequestPage(any());
    }

    @Test
    void mixedBroadAndRoutingScopesKeepTheirDifferentHistoricalReadRules() {
        when(access.currentCapabilities(10L)).thenReturn(new StaffCapabilitiesResponse(true, List.of(
                summary(StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, 5L),
                summary(StaffCapability.OPS_ROUTING, StaffScopeType.CITY, 9L)), Instant.now()));
        when(routing.findEligibleMixedRequestPage(
                eq(List.of(5L)), eq(List.of(9L)), any()))
                .thenReturn(new PageImpl<>(List.of(row(1L, 5L, 5L, null))));
        when(routing.findRoutingRow(81L)).thenReturn(Optional.of(row(81L, 9L, 9L, "RECEIVED")));
        when(routingTasks.existsByRequestId(81L)).thenReturn(false);

        IntakeRoutingRequestPage page = service.listEligibleRequests(10L, 0, 20);
        assertEquals(1, page.items().size());
        assertThrows(EntityNotFoundException.class,
                () -> service.readCandidates(10L, 81L, 9L, 0, 20));
        verify(routing).findEligibleMixedRequestPage(
                List.of(5L), List.of(9L), org.springframework.data.domain.PageRequest.of(0, 20));
    }

    @Test
    void aCityAdminCannotReadOrNameAForeignRequestOrCity() {
        when(access.currentCapabilities(2L)).thenReturn(cityAdmin(5L));
        when(routing.findRoutingRow(81L)).thenReturn(Optional.of(row(81L, 9L, 9L, null, null, null, null, null, null)));

        assertThrows(EntityNotFoundException.class, () -> service.readCandidates(2L, 81L, 5L, 0, 20));
        assertThrows(EntityNotFoundException.class, () -> service.readCandidates(2L, 81L, 9L, 0, 20),
                "selecting an unauthorized City must not reveal the concealed Request");
        verify(teams, never()).findActiveTeamPageByCityId(any(), any());
    }

    @Test
    void aCityAdminCannotReadARequestWhoseCanonicalLocalityBelongsToAnotherCity() {
        // Persisted Request City 5, Listing canonical locality in City 9: the contradictory source is a
        // concealment for the City 5 administrator even though the persisted Request City is its own.
        when(access.currentCapabilities(2L)).thenReturn(cityAdmin(5L));
        when(routing.findRoutingRow(81L)).thenReturn(Optional.of(row(81L, 5L, 9L, "RECEIVED")));
        // Both Cities are fully readable, so only the canonical-source rule can produce the 404.
        stubReadableCity(5L);
        stubReadableCity(9L);

        assertThrows(EntityNotFoundException.class, () -> service.readCandidates(2L, 81L, 5L, 0, 20),
                "a contradictory canonical locality must be concealed, never silently resolved");
        assertThrows(EntityNotFoundException.class, () -> service.readCandidates(2L, 81L, 9L, 0, 20),
                "changing the selected City must not turn a contradictory source into an authorized one");
        verify(cities, never()).findByIdAndActiveTrue(any());
        verify(teams, never()).findActiveTeamPageByCityId(any(), any());
    }

    @Test
    void aCityAdminCannotReadARequestWhoseCanonicalLocalityIsMissing() {
        // Persisted Request City 5 with no canonical locality at all: unresolved intake is global-only, so
        // the City 5 administrator receives exactly the same concealed answer.
        when(access.currentCapabilities(2L)).thenReturn(cityAdmin(5L));
        when(routing.findRoutingRow(81L)).thenReturn(Optional.of(row(81L, 5L, null, "RECEIVED")));
        stubReadableCity(5L);

        assertThrows(EntityNotFoundException.class, () -> service.readCandidates(2L, 81L, 5L, 0, 20));
        verify(cities, never()).findByIdAndActiveTrue(any());
        verify(teams, never()).findActiveTeamPageByCityId(any(), any());
    }

    @Test
    void aCityAdminCannotManufactureSourceAuthorityByNamingASelectedCity() {
        // Persisted Request City 9, canonical locality City 9, caller authorized only for City 5. Naming
        // City 5 (authorized) or City 9 (the true source) in the URL must both conceal the Request.
        when(access.currentCapabilities(2L)).thenReturn(cityAdmin(5L));
        when(routing.findRoutingRow(81L)).thenReturn(Optional.of(row(81L, 9L, 9L, "RECEIVED")));
        stubReadableCity(5L);
        stubReadableCity(9L);

        assertThrows(EntityNotFoundException.class, () -> service.readCandidates(2L, 81L, 5L, 0, 20));
        assertThrows(EntityNotFoundException.class, () -> service.readCandidates(2L, 81L, 9L, 0, 20));
        verify(cities, never()).findByIdAndActiveTrue(any());
        verify(teams, never()).findActiveTeamPageByCityId(any(), any());
    }

    @Test
    void aCityAdminReadsItsAuthorizedCanonicallyResolvedRequest() {
        when(access.currentCapabilities(2L)).thenReturn(cityAdmin(5L));
        when(routing.findRoutingRow(81L)).thenReturn(Optional.of(
                row(81L, 5L, 5L, "RECEIVED", null, true, null, null, null)));
        stubReadableCity(5L);

        IntakeRoutingCandidatesView view = service.readCandidates(2L, 81L, 5L, 0, 20);

        assertEquals(81L, view.request().requestId());
        assertEquals(IntakeRoutingRequestView.LocationState.CANONICAL, view.request().locationState());
        assertEquals(5L, view.city().id());
        verify(teams).findActiveTeamPageByCityId(eq(5L), any());
    }

    @Test
    void routingOnlyStaffCanReadCanonicalTeamCandidatesInItsCity() {
        when(access.currentCapabilities(9L)).thenReturn(routingOnly(5L));
        when(routing.findRoutingRow(81L)).thenReturn(Optional.of(
                row(81L, 5L, 5L, "RECEIVED", null, true, null, null, null)));
        when(routingTasks.existsByRequestId(81L)).thenReturn(true);
        stubReadableCity(5L);

        IntakeRoutingCandidatesView view = service.readCandidates(9L, 81L, 5L, 0, 20);

        assertEquals(81L, view.request().requestId());
        assertEquals(5L, view.city().id());
        verify(teams).findActiveTeamPageByCityId(eq(5L), any());
    }

    @Test
    void routingOnlyStaffCannotReadAnUnenrolledHistoricalRequestByDirectId() {
        when(access.currentCapabilities(9L)).thenReturn(routingOnly(5L));
        when(routing.findRoutingRow(81L)).thenReturn(Optional.of(row(81L, 5L, 5L, "RECEIVED")));
        when(routingTasks.existsByRequestId(81L)).thenReturn(false);

        assertThrows(EntityNotFoundException.class, () -> service.readCandidates(9L, 81L, 5L, 0, 20));
        verify(teams, never()).findActiveTeamPageByCityId(any(), any());
    }

    @Test
    void routingOnlyStaffCannotReadAnEnrolledRequestThatIsNotOperationallyReady() {
        when(access.currentCapabilities(9L)).thenReturn(routingOnly(5L));
        when(routing.findRoutingRow(81L)).thenReturn(Optional.of(
                row(81L, 5L, 5L, "RECEIVED", null, false, null, null, null)));
        when(routingTasks.existsByRequestId(81L)).thenReturn(true);

        assertThrows(EntityNotFoundException.class, () -> service.readCandidates(9L, 81L, 5L, 0, 20));
        verify(teams, never()).findActiveTeamPageByCityId(any(), any());
    }

    @Test
    void globalStaffAdminKeepsItsContradictorySourceDiagnosticRead() {
        // The global diagnostic read is preserved unchanged: a contradictory source is still readable so
        // an operator can see why a Request cannot be routed, and global authority is never narrowed.
        when(access.currentCapabilities(1L)).thenReturn(globalAdmin());
        when(routing.findRoutingRow(81L)).thenReturn(Optional.of(row(81L, 5L, 9L, "RECEIVED")));
        stubReadableCity(5L);

        IntakeRoutingCandidatesView view = service.readCandidates(1L, 81L, 5L, 0, 20);

        assertEquals(IntakeRoutingRequestView.LocationState.CONFLICTING, view.request().locationState());
        assertEquals(5L, view.city().id());
    }

    @Test
    void missingRequestAndUnknownSelectedCityConcealEqually() {
        when(access.currentCapabilities(1L)).thenReturn(globalAdmin());
        when(routing.findRoutingRow(404L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.readCandidates(1L, 404L, 5L, 0, 20));
    }

    // ------------------------------------------------------------------ authority and validation

    @Test
    void callersWithoutCurrentRoutingAuthorityAreDenied() {
        when(access.currentCapabilities(3L)).thenReturn(new StaffCapabilitiesResponse(false, List.of(), Instant.now()));
        when(access.currentCapabilities(4L)).thenReturn(new StaffCapabilitiesResponse(true, List.of(
                summary(StaffCapability.OPS_INTAKE, StaffScopeType.CITY, 5L)), Instant.now()));
        when(access.currentCapabilities(6L)).thenReturn(new StaffCapabilitiesResponse(true, List.of(
                summary(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, 5L)), Instant.now()));
        when(access.currentCapabilities(7L)).thenReturn(new StaffCapabilitiesResponse(true, List.of(
                summary(StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, 5L)), Instant.now()));
        when(access.currentCapabilities(8L)).thenReturn(new StaffCapabilitiesResponse(true, List.of(), Instant.now()));

        for (Long actor : List.of(3L, 4L, 6L, 7L, 8L)) {
            assertThrows(AccessDeniedException.class, () -> service.listEligibleRequests(actor, 0, 20), "actor " + actor);
        }
    }

    @Test
    void malformedPagesIdentifiersAndSizesAreRejected() {
        when(access.currentCapabilities(1L)).thenReturn(globalAdmin());

        assertThrows(IllegalArgumentException.class, () -> service.listEligibleRequests(1L, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> service.listEligibleRequests(1L, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.listEligibleRequests(1L, 0, 101));
        assertThrows(IllegalArgumentException.class, () -> service.readCandidates(1L, 0L, 5L, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> service.readCandidates(1L, 81L, null, 0, 20));
    }

    // ------------------------------------------------------------------ harness

    private static IntakeRoutingRequestView view(RoutingRow row) {
        return OperationalIntakeRoutingReadService.toView(row);
    }

    /**
     * Makes a selected City fully readable with an empty Team page, so a 404 can only come from the
     * source-scope rule rather than from a missing City.
     */
    private void stubReadableCity(Long cityId) {
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(cityId);
        when(city.isActive()).thenReturn(true);
        when(cities.findByIdAndActiveTrue(cityId)).thenReturn(Optional.of(city));
        when(teams.findActiveTeamPageByCityId(eq(cityId), any())).thenReturn(new PageImpl<>(List.of()));
    }

    private static StaffCapabilitiesResponse globalAdmin() {
        return new StaffCapabilitiesResponse(true, List.of(
                summary(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null)), Instant.now());
    }

    private static StaffCapabilitiesResponse cityAdmin(Long cityId) {
        return new StaffCapabilitiesResponse(true, List.of(
                summary(StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, cityId)), Instant.now());
    }

    private static StaffCapabilitiesResponse routingOnly(Long cityId) {
        return new StaffCapabilitiesResponse(true, List.of(
                summary(StaffCapability.OPS_ROUTING, StaffScopeType.CITY, cityId)), Instant.now());
    }

    private static StaffCapabilitySummary summary(StaffCapability capability, StaffScopeType scopeType, Long cityId) {
        return new StaffCapabilitySummary(capability, scopeType, cityId, cityId == null ? null : "City " + cityId,
                null, null);
    }

    /** id, source City, locality City, status, team, scopeReady, cityActive, teamActive, teamCity. */
    private static RoutingRow row(Long id, Long cityId, Long localityCityId, String status) {
        return row(id, cityId, localityCityId, status, null, null, null, null, null);
    }

    private static RoutingRow row(Long id, Long cityId, Long localityCityId, String status, Long teamId,
            Boolean scopeReady, Boolean cityActive, Boolean teamActive, Long teamCityId) {
        return row(id, cityId, localityCityId, status, teamId, scopeReady, cityActive, teamActive, teamCityId,
                null, null, null);
    }

    private static RoutingRow row(Long id, Long cityId, Long localityCityId, String status, Long teamId,
            Boolean scopeReady, Boolean cityActive, Boolean teamActive, Long teamCityId,
            Long coordinatorUserId, Long sessionId, Long canonicalLocalityId) {
        return new FakeRow(id, 0L, status, LocalDateTime.now(), sessionId, 10L, "Listing " + id, cityId, teamId,
                coordinatorUserId, scopeReady, canonicalLocalityId, localityCityId, cityActive, teamActive, teamCityId);
    }

    private record FakeRow(Long id, Long version, String status, LocalDateTime createdAt, Long sessionId,
            Long listingId, String listingTitle, Long cityId, Long teamId, Long coordinatorUserId,
            Boolean scopeReady, Long canonicalLocalityId, Long localityCityId, Boolean cityActive,
            Boolean teamActive, Long teamCityId) implements RoutingRow {
        public Long getId() { return id; }
        public Long getVersion() { return version; }
        public String getStatus() { return status; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public Long getSessionId() { return sessionId; }
        public Long getListingId() { return listingId; }
        public String getListingTitle() { return listingTitle; }
        public Long getCityId() { return cityId; }
        public Long getTeamId() { return teamId; }
        public Long getCoordinatorUserId() { return coordinatorUserId; }
        public Boolean getScopeReady() { return scopeReady; }
        public Long getCanonicalLocalityId() { return canonicalLocalityId; }
        public Long getLocalityCityId() { return localityCityId; }
        public Boolean getCityActive() { return cityActive; }
        public Boolean getTeamActive() { return teamActive; }
        public Long getTeamCityId() { return teamCityId; }
    }
}
