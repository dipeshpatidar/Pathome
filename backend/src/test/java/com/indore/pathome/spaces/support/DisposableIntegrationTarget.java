package com.indore.pathome.spaces.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed guard for Package 3A PostgreSQL tests: nothing may create a schema, run DDL, provision a
 * fixture or start a mutable Spring application context until the target is positively proven to be the
 * approved disposable integration database.
 *
 * <p>This is the Java half of one policy. Its constants, rules and refusal codes mirror
 * {@code tools/oe-integration-env/lib/db-guard.sh} and {@code tools/oe-integration-env/oe-test.env}; the
 * wrapper's acceptance preflight asserts the same designation, port and marker, so the two halves cannot
 * drift into different policies. Refusal codes 10-19 match the shell guard; 20 is the 3A addition for the
 * test-runner enablement flag.
 *
 * <p>The guard is deliberately independent of the wrapper: a direct {@code mvn} invocation with no
 * designation, a foreign host, {@code pathome_db}, a malformed JDBC URL or a database without the live
 * disposable marker is refused before any connection that could mutate anything is opened. Only a
 * read-only connection may be opened, and only after every static rule has passed.
 */
public final class DisposableIntegrationTarget {
    public static final String TEST_ENABLEMENT_FLAG = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST";
    public static final String DISPOSABLE_OPT_IN_FLAG = "PATHOME_OE_TEST_ALLOW_DISPOSABLE";
    public static final String DESIGNATION_FLAG = "PATHOME_OE_TEST_DB";
    public static final String HOST_FLAG = "PATHOME_OE_TEST_DB_HOST";
    public static final String PORT_FLAG = "PATHOME_OE_TEST_DB_PORT";
    public static final String DATASOURCE_URL_FLAG = "SPRING_DATASOURCE_URL";
    public static final String DATASOURCE_USERNAME_FLAG = "SPRING_DATASOURCE_USERNAME";
    public static final String DATASOURCE_PASSWORD_FLAG = "SPRING_DATASOURCE_PASSWORD";

    /** The one approved Package 3A disposable database (see oe-test.env). */
    public static final String APPROVED_DATABASE = "pathome_oe_test_integration";
    /** The one approved disposable port. A port is never defaulted. */
    public static final String APPROVED_PORT = "5432";
    /**
     * Loopback forms the canonical JDBC URL can address. The shell guard also accepts the bare {@code ::1}
     * alias for psql; here only URL-usable forms are accepted, so this list is never wider than the shell's
     * and an unusable alias is refused rather than silently defaulted.
     */
    public static final Set<String> APPROVED_LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    public static final String SENTINEL_TABLE = "pathome_oe_test_env";
    public static final String SENTINEL_MARKER = "DISPOSABLE";
    public static final String DISPOSABLE_NAME_PATTERN = "^pathome_oe_test_[a-z0-9_]{1,40}$";
    public static final Set<String> PROTECTED_DATABASES = Set.of("pathome_db", "pathome_autonomous_v2_test",
            "postgres", "template0", "template1", "divyavastu_db", "hotel_booking");
    public static final Set<String> AMBIGUOUS_TOKENS = Set.of("prod", "production", "stage", "staging", "live",
            "main", "master", "dev", "demo", "uat");

    /** Stable refusal codes; 10-19 mirror lib/db-guard.sh, 20 is the 3A test-runner enablement rule. */
    public static final int REFUSED_NOT_OPTED_IN = 10;
    public static final int REFUSED_NO_DESIGNATION = 11;
    public static final int REFUSED_ENDPOINT = 12;
    public static final int REFUSED_NAME = 13;
    public static final int REFUSED_PROTECTED_NAME = 14;
    public static final int REFUSED_AMBIGUOUS_NAME = 15;
    public static final int REFUSED_LIVE_IDENTITY = 16;
    public static final int REFUSED_MISSING_MARKER = 17;
    public static final int REFUSED_CONNECTION_FAILED = 18;
    public static final int REFUSED_DATASOURCE_MISMATCH = 19;
    public static final int REFUSED_TEST_NOT_ENABLED = 20;

    /** A fully validated target. Every URL this guard produces addresses this exact proven database. */
    public record Target(String jdbcUrl, String database, String host, String port, String username,
                         String password) {
        /** The same proven target with the disposable test schema selected. */
        public String schemaUrl(String schema) {
            return jdbcUrl + "?currentSchema=" + schema;
        }
    }

    /** Live, read-only facts about the connected database. */
    public record LiveFacts(String currentDatabase, int disposableMarkerCount) {}

    /** Opens the read-only verification connection; injectable so the refusal policy is unit-testable. */
    @FunctionalInterface
    public interface LiveProbe {
        LiveFacts read(Target target) throws SQLException;
    }

    /** A fail-closed refusal carrying the same stable code the shell guard would report. */
    public static final class RefusedException extends IllegalStateException {
        private final int code;

        RefusedException(int code, String reason) {
            super("REFUSED [" + code + "] " + reason);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    private static final Pattern JDBC_URL =
            Pattern.compile("^jdbc:postgresql://(\\[[^\\]]+\\]|[^/:?]+):(\\d{1,5})/([^/?]+)$");

    private DisposableIntegrationTarget() {}

    /** Full guard against the real environment: static rules, then live identity and marker. */
    public static Target verifyFromEnvironment() {
        Map<String, String> environment = System.getenv();
        Target target = requireApprovedStaticTarget(environment);
        requireLiveDisposableFacts(target, new JdbcLiveProbe());
        return target;
    }

    /** Full guard with an injectable live probe, so refusals can be proven without a connection. */
    public static Target verify(Map<String, String> environment, LiveProbe probe) {
        Target target = requireApprovedStaticTarget(environment);
        requireLiveDisposableFacts(target, probe);
        return target;
    }

    /**
     * Static rules only: no connection is opened, so a rejected configuration can never reach the
     * server. Everything is explicit - nothing is defaulted, and the approved name and port are pinned.
     */
    public static Target requireApprovedStaticTarget(Map<String, String> environment) {
        return requireApprovedStaticTarget(environment, APPROVED_DATABASE);
    }

    /** Exact owner-approved R1 target; the older Package 3A policy remains unchanged. */
    static Target requireR1StaticTarget(Map<String, String> environment) {
        return requireApprovedStaticTarget(environment, DisposablePostgresTarget.APPROVED_R1_DATABASE);
    }

    private static Target requireApprovedStaticTarget(Map<String, String> environment, String approvedDatabase) {
        if (!"true".equals(environment.get(TEST_ENABLEMENT_FLAG))) {
            throw refuse(REFUSED_TEST_NOT_ENABLED, "the Package 3A PostgreSQL tests are not enabled; set "
                    + TEST_ENABLEMENT_FLAG + "=true explicitly.");
        }
        if (!"true".equals(environment.get(DISPOSABLE_OPT_IN_FLAG))) {
            throw refuse(REFUSED_NOT_OPTED_IN, "the disposable integration target is not opted in; set "
                    + DISPOSABLE_OPT_IN_FLAG + "=true to confirm a throwaway database is intended.");
        }

        String designation = trimmed(environment.get(DESIGNATION_FLAG));
        if (designation == null) {
            throw refuse(REFUSED_NO_DESIGNATION, "no disposable database is designated; set "
                    + DESIGNATION_FLAG + " to the exact approved target and never rely on a default.");
        }

        String host = trimmed(environment.get(HOST_FLAG));
        if (host == null || !APPROVED_LOOPBACK_HOSTS.contains(host)) {
            throw refuse(REFUSED_ENDPOINT, "host '" + host + "' is not an approved loopback address; a remote "
                    + "target can never be positively identified as disposable.");
        }
        String port = trimmed(environment.get(PORT_FLAG));
        if (!APPROVED_PORT.equals(port)) {
            throw refuse(REFUSED_ENDPOINT, "port '" + port + "' is not the approved disposable port "
                    + APPROVED_PORT + "; the port is never defaulted.");
        }

        requireDesignatedDisposableName(designation, approvedDatabase);

        String configuredUrl = trimmed(environment.get(DATASOURCE_URL_FLAG));
        if (configuredUrl == null) {
            throw refuse(REFUSED_NO_DESIGNATION, "no test datasource is configured; set "
                    + DATASOURCE_URL_FLAG + " explicitly to the designated disposable database.");
        }
        String username = trimmed(environment.get(DATASOURCE_USERNAME_FLAG));
        if (username == null) {
            throw refuse(REFUSED_NO_DESIGNATION, "no test datasource user is configured; set "
                    + DATASOURCE_USERNAME_FLAG + " explicitly.");
        }

        requireConfiguredDatasource(configuredUrl, approvedDatabase);

        String password = environment.get(DATASOURCE_PASSWORD_FLAG);
        return new Target("jdbc:postgresql://" + host + ":" + port + "/" + designation, designation, host, port,
                username, password == null ? "" : password);
    }

    private static void requireDesignatedDisposableName(String designation, String approvedDatabase) {
        if (!Pattern.matches(DISPOSABLE_NAME_PATTERN, designation)
                && !(DisposablePostgresTarget.APPROVED_R1_DATABASE.equals(approvedDatabase)
                && approvedDatabase.equals(designation))) {
            throw refuse(REFUSED_NAME, "database '" + designation + "' does not match the disposable integration "
                    + "name pattern " + DISPOSABLE_NAME_PATTERN + ".");
        }
        if (PROTECTED_DATABASES.contains(designation)) {
            throw refuse(REFUSED_PROTECTED_NAME, "database '" + designation + "' is a protected local database "
                    + "and is never a valid integration target.");
        }
        for (String token : AMBIGUOUS_TOKENS) {
            if (designation.contains(token)) {
                throw refuse(REFUSED_AMBIGUOUS_NAME, "database '" + designation + "' contains the environment "
                        + "token '" + token + "', which makes its identity ambiguous.");
            }
        }
        if (!approvedDatabase.equals(designation)) {
            throw refuse(REFUSED_NAME, "database '" + designation + "' is not the approved Package 3A disposable "
                    + "database '" + approvedDatabase + "'.");
        }
    }

    private static void requireConfiguredDatasource(String configuredUrl, String approvedDatabase) {
        // Any query string is an override: the datasource must address exactly the designated database.
        if (configuredUrl.contains("?") || configuredUrl.contains("#")) {
            throw refuse(REFUSED_DATASOURCE_MISMATCH, "Configured JDBC URL carries query parameters or "
                    + "an override; the test datasource must address exactly the designated disposable database "
                    + "with no extra parameters.");
        }
        Matcher parsed = JDBC_URL.matcher(configuredUrl);
        if (!parsed.matches()) {
            throw refuse(REFUSED_NAME, "Configured JDBC URL is malformed; it must be exactly "
                    + "jdbc:postgresql://<host>:<port>/<database>.");
        }
        String urlHost = parsed.group(1);
        String urlPort = parsed.group(2);
        String urlDatabase = parsed.group(3);
        if (!APPROVED_LOOPBACK_HOSTS.contains(urlHost) || !APPROVED_PORT.equals(urlPort)) {
            throw refuse(REFUSED_ENDPOINT, "Configured JDBC URL does not address the approved "
                    + "loopback endpoint on port " + APPROVED_PORT + ".");
        }
        // The designated name is already approved above, so this single comparison proves the configured
        // datasource and the designated database are the same target; the live guard then proves the
        // connection really resolves to that same database.
        if (!approvedDatabase.equals(urlDatabase)) {
            throw refuse(REFUSED_NAME, "Configured JDBC URL does not address the approved "
                    + "disposable database '" + approvedDatabase + "'.");
        }
    }

    private static void requireLiveDisposableFacts(Target target, LiveProbe probe) {
        LiveFacts facts;
        try {
            facts = probe.read(target);
        } catch (SQLException failure) {
            throw refuse(REFUSED_CONNECTION_FAILED, "the designated disposable database '" + target.database()
                    + "' on " + target.host() + ":" + target.port() + " could not be verified: "
                    + failure.getClass().getSimpleName() + ".");
        }
        if (!APPROVED_DATABASE.equals(facts.currentDatabase())) {
            throw refuse(REFUSED_LIVE_IDENTITY, "the connection resolved to database '" + facts.currentDatabase()
                    + "' but '" + APPROVED_DATABASE + "' was designated.");
        }
        if (facts.disposableMarkerCount() != 1) {
            throw refuse(REFUSED_MISSING_MARKER, "database '" + APPROVED_DATABASE + "' does not carry exactly one "
                    + SENTINEL_MARKER + " marker in " + SENTINEL_TABLE + " (matches="
                    + facts.disposableMarkerCount() + "). It is not proven disposable.");
        }
    }

    private static RefusedException refuse(int code, String reason) {
        return new RefusedException(code, reason);
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Read-only live verification: identity and marker only, on a read-only connection. */
    private static final class JdbcLiveProbe implements LiveProbe {
        @Override
        public LiveFacts read(Target target) throws SQLException {
            try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), target.username(),
                    target.password())) {
                connection.setReadOnly(true);
                connection.setAutoCommit(true);
                String currentDatabase;
                try (PreparedStatement statement = connection.prepareStatement("select current_database()");
                     ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    currentDatabase = rows.getString(1);
                }
                int markers;
                try (PreparedStatement statement = connection.prepareStatement("select count(*) from "
                        + SENTINEL_TABLE + " where env_marker = ? and db_name = current_database()")) {
                    statement.setString(1, SENTINEL_MARKER);
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        markers = rows.getInt(1);
                    }
                }
                return new LiveFacts(currentDatabase, markers);
            }
        }
    }
}
