package com.indore.pathome.spaces.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

/**
 * R1-only composition over {@link DisposableIntegrationTarget}: the accepted static/sentinel guard
 * plus explicit checks that the live connection is the designated database, user, loopback server and
 * run identity.
 *
 * <p>This is a test-only verifier. It opens a read-only connection after every static rule has passed
 * and before Flyway, schema creation or any Spring datasource property is published. It never prints a
 * password. Cleanup may address only a schema created after this returned successfully.
 */
public final class DisposablePostgresTarget {
    public static final String APPROVED_R1_DATABASE = "pathome_r1_test_20261018";
    public static final String RUN_ID_ENV = "PATHOME_OE_TEST_RUN_ID";
    public static final String RUN_DIR_ENV = "PATHOME_OE_TEST_RUN_DIR";
    public static final String RUN_ID_FILE = "run-id";

    /** R1-specific refusal codes, above the existing guard's 10-20 range. */
    public static final int REFUSED_RUN_IDENTITY = 21;
    public static final int REFUSED_LIVE_USER = 22;
    public static final int REFUSED_LIVE_SERVER = 23;
    public static final int REFUSED_LIVE_SENTINEL = 24;

    private static final Set<String> LOOPBACK_SERVER_ADDRESSES = Set.of("127.0.0.1", "::1");

    public record Verified(DisposableIntegrationTarget.Target target, String runId) {}

    /** One read-only snapshot of the live target's identity. */
    public record LiveFacts(String currentDatabase, String currentUser, String serverAddress,
                            int markerCount, String sentinelRunId) {}

    @FunctionalInterface
    public interface LiveProbe {
        LiveFacts read(DisposableIntegrationTarget.Target target) throws SQLException;
    }

    private DisposablePostgresTarget() {}

    public static Verified verifyFromEnvironment() {
        Map<String, String> environment = System.getenv();
        return verify(environment, new JdbcLiveProbe());
    }

    /** Full fail-closed verification with an injectable probe, so dangerous targets never need a connection. */
    public static Verified verify(Map<String, String> environment, LiveProbe probe) {
        DisposableIntegrationTarget.Target target =
                DisposableIntegrationTarget.requireR1StaticTarget(environment);
        String runId = expectedRunId(environment);
        LiveFacts facts;
        try {
            facts = probe.read(target);
        } catch (SQLException failure) {
            throw refuse(DisposableIntegrationTarget.REFUSED_CONNECTION_FAILED,
                    "the designated disposable target could not be verified: "
                            + failure.getClass().getSimpleName() + ".");
        }
        if (!target.database().equals(facts.currentDatabase())) {
            throw refuse(DisposableIntegrationTarget.REFUSED_LIVE_IDENTITY,
                    "the connection resolved to database '" + facts.currentDatabase()
                            + "', not the designated disposable database.");
        }
        if (!target.username().equals(facts.currentUser())) {
            throw refuse(REFUSED_LIVE_USER,
                    "the connection authenticated as an unexpected database user.");
        }
        if (facts.serverAddress() == null || !LOOPBACK_SERVER_ADDRESSES.contains(facts.serverAddress())) {
            throw refuse(REFUSED_LIVE_SERVER,
                    "the connection resolved to a non-loopback PostgreSQL server.");
        }
        if (facts.markerCount() != 1 || runId.equals(facts.sentinelRunId()) == false) {
            throw refuse(REFUSED_LIVE_SENTINEL,
                    "the disposable sentinel or its run identity does not match this run.");
        }
        return new Verified(target, runId);
    }

    private static String expectedRunId(Map<String, String> environment) {
        String explicit = trimmed(environment.get(RUN_ID_ENV));
        if (explicit != null) {
            requireRunShape(explicit);
            return explicit;
        }
        String runDirectory = trimmed(environment.get(RUN_DIR_ENV));
        if (runDirectory == null || !runDirectory.startsWith("/private/tmp/")) {
            throw refuse(REFUSED_RUN_IDENTITY,
                    "no explicit run identity is available; set " + RUN_ID_ENV
                            + " or an approved " + RUN_DIR_ENV + ".");
        }
        try {
            String fromFile = trimmed(Files.readString(Path.of(runDirectory, RUN_ID_FILE)));
            if (fromFile == null) {
                throw refuse(REFUSED_RUN_IDENTITY, "the run identity file is empty.");
            }
            requireRunShape(fromFile);
            return fromFile;
        } catch (java.io.IOException failure) {
            throw refuse(REFUSED_RUN_IDENTITY, "the run identity file could not be read.");
        }
    }

    private static void requireRunShape(String runId) {
        if (!runId.matches("^[a-z0-9-]{16,64}$")) {
            throw refuse(REFUSED_RUN_IDENTITY, "the run identity has an unexpected shape.");
        }
    }

    private static String trimmed(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static DisposableIntegrationTarget.RefusedException refuse(int code, String reason) {
        return new DisposableIntegrationTarget.RefusedException(code, reason);
    }

    /** Read-only identity and sentinel verification; no DDL, no schema creation, no Flyway. */
    private static final class JdbcLiveProbe implements LiveProbe {
        @Override
        public LiveFacts read(DisposableIntegrationTarget.Target target) throws SQLException {
            try (Connection connection = DriverManager.getConnection(
                    target.jdbcUrl(), target.username(), target.password())) {
                connection.setReadOnly(true);
                connection.setAutoCommit(true);
                String database;
                String user;
                String server;
                try (PreparedStatement statement = connection.prepareStatement(
                        "select current_database(), current_user, host(inet_server_addr())");
                     ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    database = rows.getString(1);
                    user = rows.getString(2);
                    server = rows.getString(3);
                }
                int markers;
                String runId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "select count(*), min(run_id) from "
                                + DisposableIntegrationTarget.SENTINEL_TABLE
                                + " where env_marker = ? and db_name = current_database()")) {
                    statement.setString(1, DisposableIntegrationTarget.SENTINEL_MARKER);
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        markers = rows.getInt(1);
                        runId = rows.getString(2);
                    }
                }
                return new LiveFacts(database, user, server, markers, runId);
            }
        }
    }
}
