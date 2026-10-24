package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@EnabledIfEnvironmentVariable(named = "PATHOME_OE_TEST_ACCEPTANCE", matches = "routing-20261023")
class RoutingAcceptanceContractLiveTest {
    @Test
    void exactTargetRolesAndRuntimePrivilegesAreLiveVerified() throws Exception {
        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        contract.verifyLive();
        assertEquals(RoutingAcceptanceContract.DATABASE, contract.runtimeTarget().database());
        assertEquals(RoutingAcceptanceContract.RUNTIME_USER,
                contract.runtimeCredentials().username());
        assertEquals(RoutingAcceptanceContract.MIGRATOR_USER,
                contract.migratorCredentials().username());

        RoutingAcceptanceContract.Credentials runtime = contract.runtimeCredentials();
        try (Connection connection = DriverManager.getConnection(
                runtime.jdbcUrl(), runtime.username(), runtime.password());
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "select has_schema_privilege(current_user, current_schema(), 'CREATE')")) {
                rows.next();
                assertFalse(rows.getBoolean(1));
            }
            try (ResultSet rows = statement.executeQuery("""
                    select count(*) from pg_roles
                    where rolname = current_user and not rolsuper and not rolcreatedb and not rolcreaterole
                    """)) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}
