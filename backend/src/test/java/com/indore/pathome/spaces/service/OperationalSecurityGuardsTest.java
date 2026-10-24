package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationalSecurityGuardsTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OperationalSecurityGuards guards = new OperationalSecurityGuards(
            mock(SupportedCityRepository.class), mock(OperatingTeamRepository.class),
            mock(EmployeeProfileRepository.class), mock(StaffAccessGrantRepository.class), jdbc);

    @Test
    void duplicateLookupUsesSortedCityTeamActorProfileGrantUserLockOrder() {
        List<String> statements = new ArrayList<>();
        List<List<Object>> arguments = new ArrayList<>();
        when(jdbc.queryForList(anyString(), eq(Long.class), any())).thenAnswer(invocation -> {
            statements.add(invocation.getArgument(0));
            List<Object> bound = new ArrayList<>();
            Object sqlArgument = invocation.getArgument(2);
            if (sqlArgument instanceof Object[] values) {
                bound.addAll(List.of(values));
            } else {
                bound.add(sqlArgument);
            }
            arguments.add(bound);
            return List.of(1L);
        });

        guards.acquireDuplicateLookup(List.of(8L, 3L), List.of(20L, 11L), 7L);

        assertEquals(List.of(
                "select id from supported_cities where id = ? for share",
                "select id from supported_cities where id = ? for share",
                "select id from operating_teams where id = ? for share",
                "select id from operating_teams where id = ? for share",
                "select id from employee_profiles where user_id = ? for update",
                "select id from staff_access_grants where user_id = ? order by id for update",
                "select id from users where id = ? for update"), statements);
        assertEquals(List.of(List.of(3L), List.of(8L), List.of(11L), List.of(20L),
                List.of(7L), List.of(7L), List.of(7L)), arguments);
    }
}
