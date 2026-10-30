package com.indore.pathome.spaces.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = A1A7IsolatedTarget.TEST_FLAG, matches = "true")
class DcTargetQualificationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TABLES = List.of(
            "property_visit_requests",
            "visit_request_routing_tasks",
            "lead_assistance_command_receipts",
            "operational_audit_events"
    );
    private static final List<String> SEQUENCES = List.of(
            "property_visit_requests_id_seq",
            "visit_request_routing_tasks_id_seq",
            "operational_audit_events_id_seq"
    );

    @Test
    void qualifiesFreshDcSchemaOnTheAuthorizedA1A7Target() throws Exception {
        String schema = System.getenv(A1A7IsolatedTarget.SCHEMA_FLAG);
        assertTrue(schema != null && schema.matches("^[a-z][a-z0-9_]{0,62}$"));
        requirePrivatePath(A1A7IsolatedTarget.CLUSTER, "rwx------");
        requirePrivatePath(A1A7IsolatedTarget.SECRETS, "rw-------");

        A1A7IsolatedTarget.Target migrator = A1A7IsolatedTarget.require(
                A1A7IsolatedTarget.MIGRATOR_ROLE, schema);
        List<String> existingSchemas;
        try (Connection connection = migrator.open()) {
            assertEquals(A1A7IsolatedTarget.DATABASE,
                    scalar(connection, "select current_database()", String.class));
            assertEquals(A1A7IsolatedTarget.RUN_UUID,
                    scalar(connection, "select run_uuid::text from it_control.run_identity",
                            String.class));
            assertEquals(A1A7IsolatedTarget.MARKER,
                    scalar(connection,
                            "select disposable_marker from it_control.run_identity",
                            String.class));
            assertEquals(migrator.systemIdentifier(),
                    scalar(connection,
                            "select pg_system_identifier from it_control.run_identity",
                            String.class));
            assertEquals(0L, scalar(connection,
                    "select count(*) from pg_namespace where nspname=?", Long.class, schema),
                    "the D-C schema must not pre-exist");
            existingSchemas = strings(connection,
                    "select nspname from pg_namespace "
                            + "where nspname like 'dc_%' order by nspname");
        }

        A1A7SchemaHarness.migrate(migrator, 63);
        A1A7SchemaHarness.verifyComplete(migrator);

        A1A7IsolatedTarget.Target runtime = A1A7IsolatedTarget.require(
                A1A7IsolatedTarget.RUNTIME_ROLE, schema);
        Map<String, Object> tablePrivileges = new LinkedHashMap<>();
        Map<String, Boolean> sequencePrivileges = new LinkedHashMap<>();
        try (Connection connection = runtime.open()) {
            assertEquals(A1A7IsolatedTarget.DATABASE,
                    scalar(connection, "select current_database()", String.class));
            assertEquals(A1A7IsolatedTarget.RUNTIME_ROLE,
                    scalar(connection, "select current_user", String.class));
            assertFalse(booleanScalar(connection,
                    "select has_schema_privilege(current_user, current_schema(), 'CREATE')"));
            assertFalse(booleanScalar(connection,
                    "select has_database_privilege(current_user, current_database(), 'CREATE')"));
            for (String table : TABLES) {
                String qualified = schema + "." + table;
                Map<String, Boolean> privileges = new LinkedHashMap<>();
                for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                    boolean allowed = booleanScalar(connection,
                            "select has_table_privilege(current_user, ?, ?)",
                            qualified, privilege);
                    privileges.put(privilege, allowed);
                }
                tablePrivileges.put(table, privileges);
                for (String privilege : List.of("SELECT", "INSERT")) {
                    assertTrue(privileges.get(privilege),
                            qualified + " requires " + privilege);
                }
            }
            for (String sequence : SEQUENCES) {
                boolean allowed = booleanScalar(connection,
                        "select has_sequence_privilege(current_user, ?, 'USAGE')",
                        schema + "." + sequence);
                sequencePrivileges.put(sequence, allowed);
                assertTrue(allowed, sequence + " requires USAGE");
            }
        }

        try (Connection connection = migrator.open()) {
            assertEquals(63L, scalar(connection,
                    "select count(*) from " + schema
                            + ".flyway_schema_history where success and version is not null",
                    Long.class));
            assertEquals(0L, scalar(connection,
                    "select count(*) from " + schema
                            + ".flyway_schema_history where not success",
                    Long.class));
            assertEquals(A1A7IsolatedTarget.MIGRATOR_ROLE,
                    scalar(connection,
                            "select pg_get_userbyid(nspowner) from pg_namespace "
                                    + "where nspname=?", String.class, schema));
            for (String existing : existingSchemas) {
                assertTrue(booleanScalar(connection,
                        "select exists(select 1 from pg_namespace where nspname=?)",
                        existing), "historical schema disappeared: " + existing);
            }
        }

        Path evidence = Path.of(A1A7IsolatedTarget.evidenceDirectory())
                .resolve("custody").resolve("target-qualification.json");
        Files.writeString(evidence, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.ofEntries(
                        Map.entry("schema", schema),
                        Map.entry("database", A1A7IsolatedTarget.DATABASE),
                        Map.entry("runUuid", A1A7IsolatedTarget.RUN_UUID),
                        Map.entry("marker", A1A7IsolatedTarget.MARKER),
                        Map.entry("migratorRole", A1A7IsolatedTarget.MIGRATOR_ROLE),
                        Map.entry("runtimeRole", A1A7IsolatedTarget.RUNTIME_ROLE),
                        Map.entry("runnerRole", A1A7IsolatedTarget.RUNNER_ROLE),
                        Map.entry("flywaySuccessful", 63),
                        Map.entry("preexistingDcSchemas", existingSchemas),
                        Map.entry("tablePrivileges", tablePrivileges),
                        Map.entry("sequencePrivileges", sequencePrivileges)
                )) + "\n", StandardCharsets.UTF_8);
    }

    private static void requirePrivatePath(Path path, String expectedPermissions) throws Exception {
        assertTrue(path.isAbsolute());
        assertFalse(Files.isSymbolicLink(path));
        assertTrue(Files.exists(path, LinkOption.NOFOLLOW_LINKS));
        assertEquals(expectedPermissions,
                PosixFilePermissions.toString(Files.getPosixFilePermissions(
                        path, LinkOption.NOFOLLOW_LINKS)));
    }

    private static <T> T scalar(Connection connection, String sql, Class<T> type,
                                Object... arguments) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("scalar query returned no row");
                return type.cast(rows.getObject(1));
            }
        }
    }

    private static boolean booleanScalar(Connection connection, String sql,
                                         Object... arguments) throws SQLException {
        return Boolean.TRUE.equals(scalar(connection, sql, Boolean.class, arguments));
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            var values = new java.util.ArrayList<String>();
            while (rows.next()) values.add(rows.getString(1));
            return List.copyOf(values);
        }
    }
}
