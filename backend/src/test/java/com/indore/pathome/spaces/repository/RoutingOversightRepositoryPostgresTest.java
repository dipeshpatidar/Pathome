package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.support.RoutingAcceptanceContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Executes the oversight CTE against the genuine PostgreSQL target so an empty result cannot hide an
 * invalid column reference. The broken alias is evaluated only in memory and is never written to
 * production source.
 */
@EnabledIfEnvironmentVariable(named = "PATHOME_OE_TEST_ACCEPTANCE", matches = "routing-20261023")
class RoutingOversightRepositoryPostgresTest {
    @Test
    void correctedCteExecutesAndTheOldAliasFailsEvenForAnEmptyResult() {
        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        contract.verifyLive();
        RoutingAcceptanceContract.Credentials runtime = contract.runtimeCredentials();
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(runtime.jdbcUrl(), runtime.username(), runtime.password()));
        MapSqlParameterSource parameters = new MapSqlParameterSource("userId", Long.MAX_VALUE);

        Long corrected = jdbc.queryForObject(
                RoutingOversightRepository.AUTHORIZED_TEAMS + " SELECT count(*) FROM targets",
                parameters, Long.class);
        assertEquals(0L, corrected);

        String oldAliasSql = RoutingOversightRepository.AUTHORIZED_TEAMS
                .replace("t.team_display_name", "t.display_name")
                + " SELECT count(*) FROM targets";
        DataAccessException failure = assertThrows(DataAccessException.class,
                () -> jdbc.queryForObject(oldAliasSql, Map.of("userId", Long.MAX_VALUE), Long.class));
        assertEquals(true, failure.getMostSpecificCause().getMessage()
                .contains("column t.display_name does not exist"));
    }
}
