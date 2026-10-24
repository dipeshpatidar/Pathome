package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class OwnedTestSchemaRolePostgresTest {
    @Test
    void normalProvisionAndCleanupAreOwnershipConfirmed() throws Exception {
        DisposableIntegrationTarget.Target target =
                DisposableIntegrationTarget.verifyFromEnvironment();
        OwnedTestSchemaRole owned = OwnedTestSchemaRole.provision(target, "p_schema_lifecycle");
        String schema = owned.credentials().schema();
        String role = owned.credentials().username();

        assertTrue(exists("schema", schema));
        assertTrue(exists("role", role));
        owned.close();

        assertFalse(exists("schema", schema));
        assertFalse(exists("role", role));
        owned.close();
    }

    @Test
    void existingSchemaCollisionRollsBackTheNewRoleAndLeavesForeignSchemaIntact()
            throws Exception {
        DisposableIntegrationTarget.Target target =
                DisposableIntegrationTarget.verifyFromEnvironment();
        String foreignSchema = "p_foreign_collision_" + suffix();
        String attemptedRole = "pathome_suite_" + suffix().substring(0, 20);
        createForeignSchema(foreignSchema);
        try {
            Map<String, String> environment = new HashMap<>(System.getenv());
            String adminUser = environment.get("PATHOME_OE_TEST_DB_ADMIN_USER");
            String adminPassword = environment.getOrDefault("PATHOME_OE_TEST_DB_ADMIN_PASSWORD", "");
            OwnedTestSchemaRole.Identity identity =
                    new OwnedTestSchemaRole.Identity(foreignSchema, attemptedRole, "b".repeat(48));

            assertThrows(SQLException.class, () -> OwnedTestSchemaRole.provision(
                    target, "p_collision_attempt", environment,
                    () -> DriverManager.getConnection(target.jdbcUrl(), adminUser, adminPassword),
                    credentials -> {}, identity));

            assertTrue(exists("schema", foreignSchema));
            assertFalse(exists("role", attemptedRole));
        } finally {
            dropForeignSchema(foreignSchema);
        }
        assertFalse(exists("schema", foreignSchema));
    }

    @Test
    void parallelSuitesDeleteOnlyTheirOwnGeneratedResources() throws Exception {
        DisposableIntegrationTarget.Target target =
                DisposableIntegrationTarget.verifyFromEnvironment();
        String foreignSchema = "p_foreign_parallel_" + suffix();
        createForeignSchema(foreignSchema);
        OwnedTestSchemaRole first = null;
        OwnedTestSchemaRole second = null;
        try {
            first = OwnedTestSchemaRole.provision(target, "p_parallel_first");
            second = OwnedTestSchemaRole.provision(target, "p_parallel_second");
            String firstSchema = first.credentials().schema();
            String secondSchema = second.credentials().schema();

            first.close();
            assertFalse(exists("schema", firstSchema));
            assertTrue(exists("schema", secondSchema));
            assertTrue(exists("schema", foreignSchema));

            second.close();
            assertFalse(exists("schema", secondSchema));
            assertTrue(exists("schema", foreignSchema));
        } finally {
            if (first != null) first.close();
            if (second != null) second.close();
            dropForeignSchema(foreignSchema);
        }
    }

    private static boolean exists(String kind, String identifier) throws SQLException {
        String sql = "schema".equals(kind)
                ? "select count(*) from pg_namespace where nspname = ?"
                : "select count(*) from pg_roles where rolname = ?";
        try (Connection connection = admin();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identifier);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private static void createForeignSchema(String schema) throws SQLException {
        try (Connection connection = app();
             Statement statement = connection.createStatement()) {
            statement.execute("create schema \"" + schema + "\"");
        }
    }

    private static void dropForeignSchema(String schema) throws SQLException {
        try (Connection connection = app();
             Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists \"" + schema + "\" cascade");
        }
    }

    private static Connection admin() throws SQLException {
        Map<String, String> environment = System.getenv();
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/" + environment.get("PATHOME_OE_TEST_DB"),
                environment.get("PATHOME_OE_TEST_DB_ADMIN_USER"),
                environment.getOrDefault("PATHOME_OE_TEST_DB_ADMIN_PASSWORD", ""));
    }

    private static Connection app() throws SQLException {
        Map<String, String> environment = System.getenv();
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/" + environment.get("PATHOME_OE_TEST_DB"),
                environment.get("PATHOME_OE_TEST_DB_APP_USER"),
                environment.getOrDefault("PATHOME_OE_TEST_DB_PASSWORD", ""));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
