package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.StaffCapabilitiesResponse;
import com.indore.pathome.spaces.dto.StaffCapabilitySummary;
import com.indore.pathome.spaces.dto.TeamLocalityCoverageCommand;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.TeamLocalityCoverage;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.StaffAccessConflictException;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.TeamLocalityCoverageRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamLocalityCoverageServiceTest {

    @Test
    void grantExpiringWhileCoverageEvidenceWaitsCannotCreateCoverage() {
        Fixture fixture = fixture(true);
        when(fixture.access.currentCapabilities(7L)).thenReturn(
                routingCapabilities(),
                new StaffCapabilitiesResponse(true, List.of(), Instant.parse("2026-10-23T12:00:00Z")));

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                fixture.service.create(7L,
                        new TeamLocalityCoverageCommand(5L, 13L, 9L, "COVERAGE_CHANGE")));

        verify(fixture.coverages, never()).saveAndFlush(any());
        verify(fixture.access, times(2)).currentCapabilities(7L);
    }

    @Test
    void activeAndRetiredExistingPairsAreBothCreateConflicts() {
        for (boolean active : List.of(true, false)) {
            Fixture fixture = fixture(active);

            assertThrows(StaffAccessConflictException.class, () -> fixture.service.create(
                    7L, new TeamLocalityCoverageCommand(5L, 13L, 9L, "COVERAGE_CHANGE")));

            verify(fixture.coverages, never()).saveAndFlush(any());
        }
    }

    private static Fixture fixture(boolean existingActive) {
        TeamLocalityCoverageRepository coverages = mock(TeamLocalityCoverageRepository.class);
        SupportedCityRepository cities = mock(SupportedCityRepository.class);
        LocalityRepository localities = mock(LocalityRepository.class);
        OperatingTeamRepository teams = mock(OperatingTeamRepository.class);
        UserRepository users = mock(UserRepository.class);
        StaffAccessService access = mock(StaffAccessService.class);
        OperationalSecurityGuards guards = mock(OperationalSecurityGuards.class);
        OperationalAuditService audit = mock(OperationalAuditService.class);
        TransactionalCommandBounds bounds = mock(TransactionalCommandBounds.class);
        DatabaseClock clock = mock(DatabaseClock.class);
        EntityManager entityManager = mock(EntityManager.class);

        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(5L);
        when(city.isActive()).thenReturn(true);
        Locality locality = mock(Locality.class);
        when(locality.getId()).thenReturn(13L);
        when(locality.getSupportedCity()).thenReturn(city);
        OperatingTeam team = mock(OperatingTeam.class);
        when(team.getId()).thenReturn(9L);
        when(team.getCity()).thenReturn(city);
        when(team.isActive()).thenReturn(true);
        TeamLocalityCoverage existing = mock(TeamLocalityCoverage.class);
        when(existing.isActive()).thenReturn(existingActive);
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7L);

        when(access.currentCapabilities(7L)).thenReturn(new StaffCapabilitiesResponse(true,
                List.of(new StaffCapabilitySummary(StaffCapability.OPS_ROUTING, StaffScopeType.CITY,
                        5L, "Indore", null, null)), Instant.now()));
        when(access.hasCapabilityAt(7L, StaffCapability.OPS_ROUTING, StaffScopeType.CITY, 5L))
                .thenReturn(true);
        when(cities.findById(5L)).thenReturn(Optional.of(city));
        when(localities.findById(13L)).thenReturn(Optional.of(locality));
        when(teams.findById(9L)).thenReturn(Optional.of(team));
        when(cities.findLockedById(5L)).thenReturn(Optional.of(city));
        when(teams.findLockedById(9L)).thenReturn(Optional.of(team));
        when(localities.findLockedById(13L)).thenReturn(Optional.of(locality));
        when(users.getReferenceById(7L)).thenReturn(actor);
        when(coverages.findByCanonicalLocalityIdAndOperatingTeamId(13L, 9L))
                .thenReturn(Optional.of(existing));

        TeamLocalityCoverageService service = new TeamLocalityCoverageService(coverages, cities, localities,
                teams, users, access, guards, audit, bounds, clock, entityManager);
        return new Fixture(service, access, coverages);
    }

    private static StaffCapabilitiesResponse routingCapabilities() {
        return new StaffCapabilitiesResponse(true,
                List.of(new StaffCapabilitySummary(StaffCapability.OPS_ROUTING, StaffScopeType.CITY,
                        5L, "Indore", null, null)),
                Instant.parse("2026-10-23T12:00:00Z"));
    }

    private record Fixture(TeamLocalityCoverageService service,
                           StaffAccessService access,
                           TeamLocalityCoverageRepository coverages) {
    }
}
