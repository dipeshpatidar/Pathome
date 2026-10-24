package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwnedTestSchemaRoleTest {
    private static final String DATABASE = DisposableIntegrationTarget.G10_APPROVED_DATABASE;
    private static final String RUN_ID = "17cdb6d3-ef16-4b67-9f53-73725c859a41";
    private static final String ROLE = "pathome_suite_11111111111111111111";
    private static final String PASSWORD = "a".repeat(48);

    @Test
    void freshProvisionCreatesAndRemovesOnlyItsExactOwnedResources() throws Exception {
        AdminHarness database = new AdminHarness(ROLE);
        OwnedTestSchemaRole owned = provision(database, "p_fresh", ROLE, RUN_ID);

        owned.close();
        owned.close();

        assertEquals(1, database.count("create schema"));
        assertEquals(1, database.count("drop schema"));
        assertEquals(1, database.count("drop role"));
    }

    @Test
    void schemaCollisionRollsBackAndNeverIssuesDestructiveCleanup() {
        AdminHarness database = new AdminHarness(ROLE);
        database.failOn("create schema");

        SQLException failure = assertThrows(SQLException.class,
                () -> provision(database, "p_collision", ROLE, RUN_ID));

        assertTrue(failure.getMessage().contains("collision"));
        assertFalse(database.executed("drop schema"));
        assertFalse(database.executed("drop role"));
    }

    @Test
    void laterSetupFailureCleansOnlyTheOwnershipConfirmedResources() throws Exception {
        AdminHarness database = new AdminHarness(ROLE);

        assertThrows(IllegalStateException.class, () -> OwnedTestSchemaRole.provision(
                target(), "p_late_failure", environment(RUN_ID), database::open,
                credentials -> {
                    throw new IllegalStateException("restricted verification failed");
                },
                identity("p_late_failure_owned", ROLE)));

        assertEquals(1, database.count("drop schema"));
        assertEquals(1, database.count("drop role"));
    }

    @Test
    void wrongRunIdAndMarkerAreRefusedBeforeCreationOrCleanup() {
        AdminHarness wrongRun = new AdminHarness(ROLE);
        wrongRun.sentinelRunId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        SQLException runFailure = assertThrows(SQLException.class,
                () -> provision(wrongRun, "p_wrong_run", ROLE, RUN_ID));
        assertTrue(runFailure.getMessage().contains("run identity"));
        assertFalse(wrongRun.executed("create schema"));
        assertFalse(wrongRun.executed("drop schema"));

        AdminHarness wrongMarker = new AdminHarness(ROLE);
        wrongMarker.markerCount = 0;
        SQLException markerFailure = assertThrows(SQLException.class,
                () -> provision(wrongMarker, "p_wrong_marker", ROLE, RUN_ID));
        assertTrue(markerFailure.getMessage().contains("marker"));
        assertFalse(wrongMarker.executed("create schema"));
        assertFalse(wrongMarker.executed("drop schema"));
    }

    @Test
    void wrongSchemaOwnerRefusesDestructiveCleanupAndPreservesEvidence() throws Exception {
        AdminHarness database = new AdminHarness(ROLE);
        OwnedTestSchemaRole owned = provision(database, "p_foreign_owner", ROLE, RUN_ID);
        database.schemaOwner = "foreign_suite";

        SQLException failure = assertThrows(SQLException.class, owned::close);

        assertTrue(failure.getMessage().contains("ownership"));
        assertFalse(database.executed("drop schema"));
        assertFalse(database.executed("drop role"));
    }

    @Test
    void uncertainCreationOrCommitNeverTriggersSpeculativeDrop() {
        AdminHarness commitUnknown = new AdminHarness(ROLE);
        commitUnknown.failCounter = 1;
        assertThrows(SQLException.class,
                () -> provision(commitUnknown, "p_commit_unknown", ROLE, RUN_ID));
        assertFalse(commitUnknown.executed("drop schema"));
        assertFalse(commitUnknown.executed("drop role"));

        AdminHarness rollbackUnknown = new AdminHarness(ROLE);
        rollbackUnknown.failOn("create schema");
        rollbackUnknown.rollbackFailure = true;
        assertThrows(SQLException.class,
                () -> provision(rollbackUnknown, "p_rollback_unknown", ROLE, RUN_ID));
        assertFalse(rollbackUnknown.executed("drop schema"));
        assertFalse(rollbackUnknown.executed("drop role"));
    }

    @Test
    void interruptedCleanupRollsBackAndCanBeRetried() throws Exception {
        AdminHarness database = new AdminHarness(ROLE);
        OwnedTestSchemaRole owned = provision(database, "p_interrupted", ROLE, RUN_ID);
        database.failOn("drop role");

        assertThrows(SQLException.class, owned::close);
        database.failFragment = null;

        assertDoesNotThrow(owned::close);
        assertEquals(2, database.count("drop schema"));
        assertEquals(2, database.count("drop role"));
    }

    @Test
    void parallelSuitesNeverShareOrDeleteEachOthersGeneratedIdentity() throws Exception {
        AdminHarness firstDatabase = new AdminHarness(ROLE);
        AdminHarness secondDatabase = new AdminHarness("pathome_suite_22222222222222222222");
        OwnedTestSchemaRole first = provision(
                firstDatabase, "p_parallel_a", ROLE, RUN_ID);
        OwnedTestSchemaRole second = provision(
                secondDatabase, "p_parallel_b", "pathome_suite_22222222222222222222", RUN_ID);

        assertNotEquals(first.credentials().schema(), second.credentials().schema());
        assertNotEquals(first.credentials().username(), second.credentials().username());
        first.close();
        second.close();

        assertEquals(1, firstDatabase.count("drop schema"));
        assertEquals(1, secondDatabase.count("drop schema"));
        assertTrue(firstDatabase.executed(first.credentials().schema()));
        assertFalse(firstDatabase.executed(second.credentials().schema()));
        assertTrue(secondDatabase.executed(second.credentials().schema()));
        assertFalse(secondDatabase.executed(first.credentials().schema()));
    }

    @Test
    void bootstrapPrivilegeDenialHasNoFallback() {
        AdminHarness database = new AdminHarness(ROLE);
        database.administrator = false;

        SQLException failure = assertThrows(SQLException.class,
                () -> provision(database, "p_denied", ROLE, RUN_ID));

        assertTrue(failure.getMessage().contains("administrator"));
        assertFalse(database.executed("create"));
        assertFalse(database.executed("drop"));
    }

    private OwnedTestSchemaRole provision(
            AdminHarness database, String prefix, String role, String runId) throws SQLException {
        return OwnedTestSchemaRole.provision(
                target(), prefix, environment(runId), database::open,
                credentials -> {}, identity(prefix + "_owned", role));
    }

    private static DisposableIntegrationTarget.Target target() {
        return new DisposableIntegrationTarget.Target(
                "jdbc:postgresql://127.0.0.1:5432/" + DATABASE,
                DATABASE, "127.0.0.1", "5432", "pathome", "");
    }

    private static Map<String, String> environment(String runId) {
        return Map.of(
                "PATHOME_OE_TEST_DB_ADMIN_USER", "postgres",
                "PATHOME_OE_TEST_DB_ADMIN_PASSWORD", "",
                "PATHOME_OE_TEST_RUN_ID", runId);
    }

    private static OwnedTestSchemaRole.Identity identity(String schema, String role) {
        return new OwnedTestSchemaRole.Identity(schema, role, PASSWORD);
    }

    private static final class AdminHarness {
        private final Connection connection = mock(Connection.class);
        private final List<String> statements = new ArrayList<>();
        private final String role;
        private String sentinelRunId = RUN_ID;
        private String schemaOwner;
        private String failFragment;
        private boolean rollbackFailure;
        private boolean administrator = true;
        private int markerCount = 1;
        private int commitCounter;
        private int failCounter = -1;

        private AdminHarness(String role) {
            this.role = role;
            this.schemaOwner = role;
            try {
                when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
                        prepared(invocation.getArgument(0)));
                when(connection.createStatement()).thenAnswer(invocation -> statement());
                org.mockito.Mockito.doAnswer(invocation -> {
                    commitCounter += 1;
                    if (failCounter == commitCounter) throw new SQLException("commit outcome unknown");
                    return null;
                }).when(connection).commit();
                org.mockito.Mockito.doAnswer(invocation -> {
                    if (rollbackFailure) throw new SQLException("rollback outcome unknown");
                    return null;
                }).when(connection).rollback();
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        }

        private Connection open() {
            return connection;
        }

        private void failOn(String fragment) {
            failFragment = fragment;
        }

        private boolean executed(String fragment) {
            return statements.stream().anyMatch(value -> value.contains(fragment));
        }

        private long count(String fragment) {
            return statements.stream().filter(value -> value.contains(fragment)).count();
        }

        private Statement statement() {
            Statement statement = mock(Statement.class);
            try {
                when(statement.execute(anyString())).thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    statements.add(sql);
                    if (failFragment != null && sql.contains(failFragment)) {
                        if (sql.startsWith("create schema")) {
                            throw new SQLException("schema name collision");
                        }
                        throw new SQLException("interrupted cleanup");
                    }
                    return false;
                });
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
            return statement;
        }

        private PreparedStatement prepared(String sql) {
            PreparedStatement statement = mock(PreparedStatement.class);
            try {
                when(statement.executeQuery()).thenAnswer(invocation -> result(sql));
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
            return statement;
        }

        private ResultSet result(String sql) {
            ResultSet rows = mock(ResultSet.class);
            try {
                when(rows.next()).thenReturn(true);
                if (sql.contains("r.rolsuper")) {
                    when(rows.getString(1)).thenReturn(DATABASE);
                    when(rows.getBoolean(2)).thenReturn(administrator);
                } else if (sql.contains("min(run_id)")) {
                    when(rows.getInt(1)).thenReturn(markerCount);
                    when(rows.getString(2)).thenReturn(sentinelRunId);
                } else if (sql.contains("n.nspowner")) {
                    when(rows.getString(1)).thenReturn(schemaOwner);
                } else if (sql.contains("select rolname")) {
                    when(rows.getString(1)).thenReturn(role);
                }
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
            return rows;
        }
    }
}
