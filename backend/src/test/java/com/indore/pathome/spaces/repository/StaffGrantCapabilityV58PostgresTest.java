package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.support.FreshIntegrationTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL acceptance for the forward-only V58 staff-grant capability correction.
 */
@EnabledIfEnvironmentVariable(
        named = FreshIntegrationTarget.TEST_ENABLEMENT_FLAG, matches = "true")
class StaffGrantCapabilityV58PostgresTest {
    private static final List<StaffCapability> PROPERTY_CAPABILITIES = List.of(
            StaffCapability.PROPERTY_EDIT_PUBLISH,
            StaffCapability.PROPERTY_AUDIT_READ,
            StaffCapability.PROPERTY_LIFECYCLE);

    @Test
    void eachPropertyCapabilityIsAllowedOnlyAtCityScope() throws Exception {
        FreshIntegrationTarget.Target target = FreshIntegrationTarget.requireFromEnvironment(
                FreshIntegrationTarget.Stage.INTEGRATION,
                FreshIntegrationTarget.Cohort.BUSINESS,
                FreshIntegrationTarget.MIGRATOR_ROLE);
        try (Connection connection = DriverManager.getConnection(
                target.jdbcUrl(), target.role(), target.password())) {
            long userId = insertUser(connection, "grant-capability");
            long cityId = scalar(connection,
                    "select id from public.supported_cities where active order by id limit 1");
            Long teamId = optionalScalar(connection,
                    "select id from public.operating_teams order by id limit 1");

            for (StaffCapability capability : PROPERTY_CAPABILITIES) {
                long grantId = insertGrant(connection, userId, capability,
                        StaffScopeType.CITY, cityId, null);
                assertTrue(grantId > 0);
                assertThrows(SQLException.class,
                        () -> insertGrant(connection, userId, capability,
                                StaffScopeType.GLOBAL, null, null));
                if (teamId != null) {
                    long selectedTeamId = teamId;
                    assertThrows(SQLException.class,
                            () -> insertGrant(connection, userId, capability,
                                    StaffScopeType.TEAM, null, selectedTeamId));
                }
            }

            assertThrows(SQLException.class,
                    () -> insertRawGrant(connection, userId, "PROPERTY_UNKNOWN",
                            "CITY", cityId));
            assertThrows(SQLException.class,
                    () -> insertRawGrant(connection, userId,
                            StaffCapability.PROPERTY_EDIT_PUBLISH.name(),
                            "INVALID_SCOPE", cityId));
            assertEquals(3L, scalar(connection,
                    "select count(*) from public.staff_access_grants "
                            + "where user_id=" + userId + " and capability in "
                            + "('PROPERTY_EDIT_PUBLISH','PROPERTY_AUDIT_READ','PROPERTY_LIFECYCLE')"));
        }
    }

    private static long insertUser(Connection connection, String label) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into public.users "
                        + "(email, full_name, role, free_visits_remaining, created_at) "
                        + "values (?, ?, ?, 5, current_timestamp) returning id")) {
            statement.setString(1, label + "-" + UUID.randomUUID() + "@example.test");
            statement.setString(2, label);
            statement.setString(3, Role.ROLE_TENANT.name());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static long insertGrant(Connection connection, long userId,
                                    StaffCapability capability, StaffScopeType scope,
                                    Long cityId, Long teamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into public.staff_access_grants "
                        + "(user_id, capability, scope_type, city_id, team_id, effective_at, "
                        + "created_at, grant_reason_code, provisioning_source) "
                        + "values (?, ?, ?, ?, ?, current_timestamp, current_timestamp, "
                        + "'V58_POSTGRES_TEST', ?) returning id")) {
            statement.setLong(1, userId);
            statement.setString(2, capability.name());
            statement.setString(3, scope.name());
            setNullableLong(statement, 4, cityId);
            setNullableLong(statement, 5, teamId);
            statement.setString(6, StaffGrantProvisioningSource.ADMIN_API.name());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static long insertRawGrant(Connection connection, long userId, String capability,
                                       String scope, long cityId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into public.staff_access_grants "
                        + "(user_id, capability, scope_type, city_id, effective_at, created_at, "
                        + "grant_reason_code, provisioning_source) "
                        + "values (?, ?, ?, ?, current_timestamp, current_timestamp, "
                        + "'V58_POSTGRES_NEGATIVE', 'ADMIN_API') returning id")) {
            statement.setLong(1, userId);
            statement.setString(2, capability);
            statement.setString(3, scope);
            statement.setLong(4, cityId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static Long optionalScalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : null;
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
