package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** Shared row guards for scope changes and 1C ownership commands. */
@Component
public class OperationalSecurityGuards {
    private final SupportedCityRepository cities;
    private final OperatingTeamRepository teams;
    private final EmployeeProfileRepository profiles;
    private final StaffAccessGrantRepository grants;
    private final JdbcTemplate jdbc;

    public OperationalSecurityGuards(SupportedCityRepository cities, OperatingTeamRepository teams,
                                     EmployeeProfileRepository profiles, StaffAccessGrantRepository grants,
                                     JdbcTemplate jdbc) {
        this.cities = cities;
        this.teams = teams;
        this.profiles = profiles;
        this.grants = grants;
        this.jdbc = jdbc;
    }

    public void acquire(Collection<Long> cityIds, Collection<Long> teamIds, Collection<Long> userIds) {
        for (Long cityId : sorted(cityIds)) {
            cities.findLockedById(cityId).orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
        }
        for (Long teamId : sorted(teamIds)) {
            teams.findLockedById(teamId).orElseThrow(() -> new EntityNotFoundException("Operating Team not found"));
        }
        List<Long> users = sorted(userIds);
        if (!users.isEmpty()) {
            profiles.findLockedByUserIdsOrderByUserId(users);
            grants.findLockedByUserIdsOrderById(users);
        }
    }

    public boolean cityIsActive(Long cityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select active from supported_cities where id = ?", Boolean.class, cityId));
    }

    public boolean teamIsActive(Long teamId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select active from operating_teams where id = ?", Boolean.class, teamId));
    }

    public Long cityVersion(Long cityId) {
        return jdbc.queryForObject("select version from supported_cities where id = ?", Long.class, cityId);
    }

    public Long teamVersion(Long teamId) {
        return jdbc.queryForObject("select version from operating_teams where id = ?", Long.class, teamId);
    }

    public Long teamCityId(Long teamId) {
        return jdbc.queryForObject("select city_id from operating_teams where id = ?", Long.class, teamId);
    }

    private static List<Long> sorted(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return new TreeSet<>(ids.stream().filter(id -> id != null && id > 0).toList()).stream().toList();
    }
}
