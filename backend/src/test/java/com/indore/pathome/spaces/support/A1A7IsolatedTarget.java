package com.indore.pathome.spaces.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed binding for the single owner-approved N2/V63 PostgreSQL cluster.
 *
 * <p>There is deliberately no default host, port, database, role, schema, or credential file.
 * Every connection is checked against the sealed {@code it_control} custody marker.</p>
 */
public final class A1A7IsolatedTarget {
    public static final String TEST_FLAG = "PATHOME_A1_A7_ISOLATED_TEST";
    public static final String SECRETS_FLAG = "PATHOME_A1_A7_SECRETS_FILE";
    public static final String SCHEMA_FLAG = "PATHOME_A1_A7_SCHEMA";
    public static final String EVIDENCE_FLAG = "PATHOME_A1_A7_EVIDENCE_DIR";

    public static final Path CLUSTER = Path.of(
            "/private/tmp/pathome-n2-v63-db-20261028-3cbccf2d");
    public static final Path SECRETS = Path.of(
            "/private/tmp/pathome-n2-v63-db-20261028-3cbccf2d.custody/secrets.env");
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 55436;
    public static final String DATABASE = "pathome_n2_v63_it_20261028";
    public static final String MIGRATOR_ROLE = "pathome_n2_v63_migrator_20261028";
    public static final String RUNTIME_ROLE = "pathome_n2_v63_runtime_20261028";
    public static final String RUNNER_ROLE = "pathome_n2_v63_runner_20261028";
    public static final String RUN_UUID = "e9bf522b-9236-4cda-8964-b4fb3f23e217";
    public static final String MARKER = "PATHOME_N2_V63_SOURCE_ONLY_DISPOSABLE_20261028";

    private static final String MIGRATOR_PASSWORD_KEY = "PATHOME_A1_A7_MIGRATOR_PASSWORD";
    private static final String RUNTIME_PASSWORD_KEY = "PATHOME_A1_A7_RUNTIME_PASSWORD";
    private static final String RUNNER_PASSWORD_KEY = "PATHOME_A1_A7_RUNNER_PASSWORD";
    private static final String SYSTEM_IDENTIFIER_KEY = "PATHOME_A1_A7_SYSTEM_IDENTIFIER";
    private static final Pattern SYSTEM_IDENTIFIER_PATTERN = Pattern.compile("^[0-9]{10,20}$");
    private static final Pattern SCHEMA = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final Set<String> FORBIDDEN_ENVIRONMENT = Set.of(
            "PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD", "PGSERVICE",
            "PGOPTIONS", "PGSERVICEFILE", "PGPASSFILE", "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "DATABASE_URL", "TEST_DATABASE_URL", "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST",
            "PATHOME_OE_TEST_ALLOW_DISPOSABLE", "PATHOME_OE_TEST_DB",
            "PATHOME_OE_TEST_DB_HOST", "PATHOME_OE_TEST_DB_PORT",
            "PATHOME_PACKAGE5_FLYWAY_TEST", "PATHOME_FRESH_INTEGRATION_TEST",
            "PATHOME_FRESH_INTEGRATION_MANIFEST", "PATHOME_DB_A01_LEAD_TEST",
            "PATHOME_DB_A01_LEAD_MANIFEST");

    private A1A7IsolatedTarget() {}

    public record Target(
            String schema,
            String role,
            String password,
            Path secretsFile,
            String systemIdentifier) {
        public String jdbcUrl() {
            return "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DATABASE
                    + "?currentSchema=" + schema;
        }

        public Connection open() throws SQLException {
            return DriverManager.getConnection(jdbcUrl(), role, password);
        }

        @Override
        public String toString() {
            return "A1A7IsolatedTarget[database=" + DATABASE + ", port=" + PORT
                    + ", role=" + role + ", schema=" + schema + ", secret=<redacted>]";
        }
    }

    public static Target requireFromEnvironment(String role) {
        return require(role, System.getenv(SCHEMA_FLAG));
    }

    public static Target require(String role, String schema) {
        Map<String, String> environment = System.getenv();
        if (!"true".equals(environment.get(TEST_FLAG))) {
            throw refuse("the explicit A1-A7 isolated test flag is absent");
        }
        for (String name : FORBIDDEN_ENVIRONMENT) {
            if (environment.containsKey(name)) {
                throw refuse("inherited database/test setting " + name + " is forbidden");
            }
        }
        if (!SECRETS.toString().equals(environment.get(SECRETS_FLAG))) {
            throw refuse("the credential file is not the exact A1-A7 custody file");
        }
        if (schema == null || !SCHEMA.matcher(schema).matches()) {
            throw refuse("the scenario schema is absent or malformed");
        }
        if (!Set.of(MIGRATOR_ROLE, RUNTIME_ROLE, RUNNER_ROLE).contains(role)) {
            throw refuse("the requested role is not one of the three approved roles");
        }
        requireClusterPath();
        Map<String, String> secrets = readSecrets(SECRETS);
        String password = switch (role) {
            case MIGRATOR_ROLE -> secrets.get(MIGRATOR_PASSWORD_KEY);
            case RUNTIME_ROLE -> secrets.get(RUNTIME_PASSWORD_KEY);
            case RUNNER_ROLE -> secrets.get(RUNNER_PASSWORD_KEY);
            default -> throw refuse("unsupported role");
        };
        if (password == null || password.length() < 32) {
            throw refuse("the requested role credential is unavailable or too short");
        }
        String systemIdentifier = secrets.get(SYSTEM_IDENTIFIER_KEY);
        if (systemIdentifier == null
                || !SYSTEM_IDENTIFIER_PATTERN.matcher(systemIdentifier).matches()) {
            throw refuse("the PostgreSQL system identifier is absent or malformed");
        }
        Target target = new Target(schema, role, password, SECRETS, systemIdentifier);
        verifyLiveIdentity(target);
        return target;
    }

    public static String evidenceDirectory() {
        String value = System.getenv(EVIDENCE_FLAG);
        if (value == null || value.isBlank()) {
            throw refuse("the evidence directory is absent");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw refuse("the evidence directory is unavailable");
        }
        return path.toString();
    }

    public static void verifyLiveIdentity(Target target) {
        try (Connection connection = target.open()) {
            require(DATABASE.equals(scalar(connection, "select current_database()")),
                    "database identity");
            require(roleOf(target).equals(scalar(connection, "select current_user")),
                    "role identity");
            require((HOST + "/32").equals(scalar(connection, "select inet_server_addr()::text")),
                    "loopback server identity");
            require(String.valueOf(PORT).equals(
                    scalar(connection, "select inet_server_port()::text")), "port identity");
            boolean superuser = booleanScalar(connection,
                    "select current_setting('is_superuser')::boolean");
            boolean ownsDatabase = booleanScalar(connection,
                    "select pg_get_userbyid(datdba)=current_user from pg_database "
                            + "where datname=current_database()");
            requireSafeDatabasePrivileges(target.role(), superuser, ownsDatabase);
            require(MARKER.equals(scalar(connection,
                    "select disposable_marker from it_control.run_identity")),
                    "custody marker");
            require(RUN_UUID.equals(scalar(connection,
                    "select run_uuid::text from it_control.run_identity")), "run UUID");
            require(target.systemIdentifier().equals(scalar(connection,
                    "select pg_system_identifier from it_control.run_identity")),
                    "PostgreSQL system identifier");
        } catch (SQLException exception) {
            throw refuse("live target identity could not be verified", exception);
        }
    }

    private static String roleOf(Target target) {
        return target.role();
    }

    private static Map<String, String> readSecrets(Path path) {
        require(path.isAbsolute() && !Files.isSymbolicLink(path)
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "secret file");
        try {
            require(permissions(path).equals(PosixFilePermissions.fromString("rw-------")),
                    "secret file permissions");
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    values.put(line.substring(0, equals), line.substring(equals + 1));
                }
            }
            return values;
        } catch (IOException exception) {
            throw refuse("secret file could not be read", exception);
        }
    }

    private static void requireClusterPath() {
        require(Files.isDirectory(CLUSTER, LinkOption.NOFOLLOW_LINKS),
                "approved cluster directory");
        require(!Files.isSymbolicLink(CLUSTER), "symlinked cluster directory");
        try {
            require(CLUSTER.toRealPath().equals(CLUSTER.toAbsolutePath().normalize()),
                    "cluster real path");
            require(permissions(CLUSTER).equals(PosixFilePermissions.fromString("rwx------")),
                    "cluster permissions");
        } catch (IOException exception) {
            throw refuse("cluster path could not be verified", exception);
        }
    }

    private static Set<PosixFilePermission> permissions(Path path) {
        try {
            return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw refuse("filesystem permissions could not be read", exception);
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("identity query returned no row");
            return rows.getString(1);
        }
    }

    private static boolean booleanScalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw refuse("boolean identity query returned no row");
            return readBoolean(rows);
        }
    }

    static boolean readBoolean(ResultSet rows) throws SQLException {
        boolean value = rows.getBoolean(1);
        if (rows.wasNull()) throw refuse("boolean identity query returned NULL");
        return value;
    }

    static void requireSafeDatabasePrivileges(String role, boolean superuser,
                                              boolean ownsDatabase) {
        require(!superuser, "role must not be superuser");
        if (MIGRATOR_ROLE.equals(role)) {
            require(ownsDatabase, "migrator role must own the designated database");
        } else {
            require(!ownsDatabase, "non-migrator role must not own the database");
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw refuse(label + " does not match the approved custody");
    }

    private static IllegalStateException refuse(String message) {
        return new IllegalStateException("REFUSED A1-A7 isolated target: " + message);
    }

    private static IllegalStateException refuse(String message, Throwable cause) {
        return new IllegalStateException("REFUSED A1-A7 isolated target: " + message, cause);
    }
}
