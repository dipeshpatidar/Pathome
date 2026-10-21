package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadLegacyProvenance;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeadAccessServiceTest {
    private final StaffAccessService access = mock(StaffAccessService.class);
    private final LeadAccessService leads = new LeadAccessService(access);

    @Test
    void eachApprovedCapabilityProducesOnlyItsIntendedLeadScope() {
        User actor = actor(7L);
        SupportedCity cityFive = city(5L);
        SupportedCity citySix = city(6L);
        OperatingTeam teamEight = team(8L, 5L);
        OperatingTeam teamNine = team(9L, 5L);
        StaffAccessGrant intake = grant(StaffCapability.OPS_INTAKE, StaffScopeType.CITY, cityFive, null);
        StaffAccessGrant cityAdmin = grant(StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, citySix, null);
        StaffAccessGrant coordinate = grant(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, teamEight);
        StaffAccessGrant supervise = grant(StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, teamNine);
        when(access.requireAttributableUser(7L)).thenReturn(actor);
        when(access.effectiveGrants(7L)).thenReturn(List.of(intake, cityAdmin, coordinate, supervise));

        LeadAccessContext context = leads.context(7L);

        assertFalse(context.globalAdmin());
        assertTrue(context.intakeCityIds().equals(List.of(5L)));
        assertTrue(context.routingCityIds().equals(List.of(6L)));
        assertTrue(context.ownedTeamIds().equals(List.of(8L)));
        assertTrue(context.supervisedTeamIds().equals(List.of(9L)));
    }

    @Test
    void callerWithoutAnyApprovedLeadCapabilityIsDeniedBeforeAQuery() {
        User actor = actor(7L);
        when(access.requireAttributableUser(7L)).thenReturn(actor);
        when(access.effectiveGrants(7L)).thenReturn(List.of());
        assertThrows(AccessDeniedException.class, () -> leads.context(7L));
    }

    @Test
    void supervisorCanReadButCannotEditContactOrActAsTheOwner() {
        LeadAccessContext context = new LeadAccessContext(false, List.of(), List.of(),
                List.of(), List.of(9L));
        LeadRoutingQueue lead = lead(10L, 9L, 44L, LeadLegacyProvenance.VERIFIED_MANUAL);

        assertTrue(context.canRead(lead));
        assertFalse(context.canEdit(lead, 7L));
        assertFalse(context.canRevealContact(lead, 7L));
        assertTrue(context.canAssign(9L));
    }

    @Test
    void coordinatorCanRevealOnlyItsCurrentlyOwnedTeamLead() {
        LeadAccessContext context = new LeadAccessContext(false, List.of(), List.of(),
                List.of(9L), List.of());
        LeadRoutingQueue owned = lead(10L, 9L, 7L, LeadLegacyProvenance.VERIFIED_MANUAL);
        OperatingTeam foreignTeam = mock(OperatingTeam.class);
        when(foreignTeam.getId()).thenReturn(10L);
        LeadRoutingQueue foreign = mock(LeadRoutingQueue.class);
        when(foreign.getLegacyProvenance()).thenReturn(LeadLegacyProvenance.VERIFIED_MANUAL);
        when(foreign.getOperatingTeam()).thenReturn(foreignTeam);

        assertTrue(context.canEdit(owned, 7L));
        assertTrue(context.canRevealContact(owned, 7L));
        assertFalse(context.canRead(foreign));
    }

    @Test
    void legacyUnverifiedRowsAreGlobalDiagnosticsOnly() {
        LeadAccessContext intake = new LeadAccessContext(false, List.of(5L), List.of(),
                List.of(), List.of());
        LeadRoutingQueue legacy = lead(10L, null, null, LeadLegacyProvenance.LEGACY_UNVERIFIED);
        legacy.setSupportedCity(city(5L));

        assertFalse(intake.canRead(legacy));
        assertTrue(new LeadAccessContext(true, List.of(), List.of(), List.of(), List.of()).canRead(legacy));
    }

    private static User actor(Long id) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        return user;
    }

    private static StaffAccessGrant grant(StaffCapability capability, StaffScopeType scopeType,
                                          SupportedCity city, OperatingTeam team) {
        StaffAccessGrant grant = mock(StaffAccessGrant.class);
        when(grant.getCapability()).thenReturn(capability);
        when(grant.getScopeType()).thenReturn(scopeType);
        when(grant.getCity()).thenReturn(city);
        when(grant.getTeam()).thenReturn(team);
        return grant;
    }

    private static SupportedCity city(Long id) {
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(id);
        return city;
    }

    private static OperatingTeam team(Long id, Long cityId) {
        SupportedCity city = mock(SupportedCity.class);
        when(city.getId()).thenReturn(cityId);
        OperatingTeam team = mock(OperatingTeam.class);
        when(team.getId()).thenReturn(id);
        when(team.getCity()).thenReturn(city);
        return team;
    }

    private static LeadRoutingQueue lead(Long id, Long teamId, Long ownerId, LeadLegacyProvenance provenance) {
        LeadRoutingQueue lead = mock(LeadRoutingQueue.class);
        when(lead.getId()).thenReturn(id);
        when(lead.getLegacyProvenance()).thenReturn(provenance);
        if (teamId != null) {
            OperatingTeam team = mock(OperatingTeam.class);
            when(team.getId()).thenReturn(teamId);
            when(lead.getOperatingTeam()).thenReturn(team);
        }
        if (ownerId != null) {
            User owner = mock(User.class);
            when(owner.getId()).thenReturn(ownerId);
            when(lead.getOwner()).thenReturn(owner);
        }
        return lead;
    }
}
