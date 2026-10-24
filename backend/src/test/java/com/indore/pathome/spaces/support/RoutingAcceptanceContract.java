package com.indore.pathome.spaces.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One fail-closed contract for the owner-approved routing PostgreSQL acceptance run.
 *
 * <p>The literal target file is the source of truth. Java, shell and Node guards pin its exact path,
 * effective environment and live PostgreSQL facts. Secrets are accepted only from the process
 * environment and are never read from the target file or written to evidence.
 */
public final class RoutingAcceptanceContract {
    public static final String TARGET_FILE_FLAG = "PATHOME_OE_TEST_TARGET_FILE";
    public static final String REPOSITORY_ROOT_FLAG = "OE_REPO_ROOT";
    public static final String ACCEPTANCE_FLAG = "PATHOME_OE_TEST_ACCEPTANCE";
    public static final String ACCEPTANCE_ID = "routing-20261023";
    public static final String DATABASE = "pathome_oe_test_c07_correction_20261024";
    public static final String HOST = "127.0.0.1";
    public static final String PORT = "5432";
    public static final String ADMIN_USER = "postgres";
    public static final String MIGRATOR_USER = "routing_c07_correction_20261024_01_migrator";
    public static final String RUNTIME_USER = "routing_c07_correction_20261024_01_app";
    public static final String RUN_DIRECTORY = "/private/tmp/pathome-routing-c07-correction-20261024-01";
    public static final String RUN_ID = "routing-c07-correction-20261024-01";
    public static final String SCHEMA = "routing_c07_correction_20261024_01";
    public static final String FIXTURE_PREFIX = "routing_c07_correction_20261024_01_";
    public static final String TARGET_FILE = "tools/oe-integration-env/routing-acceptance-target.env";

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{15,63}");
    private static final Set<String> ALLOWED_KEYS = Set.of(
            ACCEPTANCE_FLAG,
            "PATHOME_OE_TEST_ALLOW_DISPOSABLE",
            "PATHOME_OE_TEST_DB",
            "PATHOME_OE_TEST_DB_HOST",
            "PATHOME_OE_TEST_DB_PORT",
            "PATHOME_OE_TEST_DB_ADMIN_USER",
            "PATHOME_OE_TEST_DB_MIGRATOR_USER",
            "PATHOME_OE_TEST_DB_APP_USER",
            "PATHOME_OE_TEST_RUN_DIR",
            "PATHOME_OE_TEST_RUN_ID",
            "PATHOME_OE_TEST_SCHEMA",
            "PATHOME_OE_TEST_FIXTURE_PREFIX");

    public record Credentials(String jdbcUrl, String database, String schema,
                              String username, String password) {}

    private final Path sourceFile;

    private RoutingAcceptanceContract(Path sourceFile) {
        this.sourceFile = sourceFile;
    }

    public Path sourceFile() {
        return sourceFile;
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DATABASE;
    }

    public Credentials runtimeCredentials() {
        return credentials(RUNTIME_USER, "PATHOME_OE_TEST_DB_PASSWORD");
    }

    public Credentials migratorCredentials() {
        return credentials(MIGRATOR_USER, "PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD");
    }

    public Credentials adminCredentials() {
        return new Credentials(jdbcUrl(), DATABASE, SCHEMA, ADMIN_USER,
                System.getenv().getOrDefault("PATHOME_OE_TEST_DB_ADMIN_PASSWORD", ""));
    }

    public DisposableIntegrationTarget.Target runtimeTarget() {
        Credentials credentials = runtimeCredentials();
        return new DisposableIntegrationTarget.Target(jdbcUrl(), DATABASE, HOST, PORT,
                credentials.username(), credentials.password());
    }

    public static RoutingAcceptanceContract requireFromEnvironment() {
        Map<String, String> environment = System.getenv();
        String repositoryRoot = environment.get(REPOSITORY_ROOT_FLAG);
        if (repositoryRoot == null || repositoryRoot.isBlank()) {
            throw refuse("repository root is not designated for routing acceptance");
        }
        Path expected = Path.of(repositoryRoot).toAbsolutePath().normalize().resolve(TARGET_FILE);
        Path configured = requiredPath(environment.get(TARGET_FILE_FLAG));
        if (!configured.equals(expected.normalize())) {
            throw refuse("routing acceptance target file path is not the repository source of truth");
        }
        try {
            if (Files.isSymbolicLink(configured)
                    || !Files.isRegularFile(configured, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getOwner(configured).getName().equals(System.getProperty("user.name"))) {
                throw refuse("routing acceptance target file is symlinked or owned by another user");
            }
        } catch (IOException failure) {
            throw refuse("routing acceptance target file cannot be inspected");
        }
        requirePrivatePermissions(configured);
        Map<String, String> target = parse(configured);
        validateTarget(target);
        requireEnvironmentMatches(environment, target);
        validateCredentials(environment);
        return new RoutingAcceptanceContract(configured);
    }

    public void verifyLive() {
        Credentials runtime = runtimeCredentials();
        try (Connection connection = DriverManager.getConnection(
                runtime.jdbcUrl(), runtime.username(), runtime.password())) {
            connection.setReadOnly(true);
            requireRuntimeConnection(connection);
        } catch (SQLException failure) {
            throw refuse("live routing acceptance target verification failed: "
                    + failure.getClass().getSimpleName());
        }
    }

    private Credentials credentials(String username, String passwordKey) {
        Map<String, String> environment = System.getenv();
        return new Credentials(jdbcUrl(), DATABASE, SCHEMA, username,
                environment.getOrDefault(passwordKey, ""));
    }

    private static void requireRuntimeConnection(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select current_database(), current_user, host(inet_server_addr()), inet_server_port(),
                       current_schema(),
                       (select count(*) from public.pathome_oe_test_env
                          where env_marker = 'DISPOSABLE' and db_name = current_database()),
                       (select min(run_id) from public.pathome_oe_test_env
                          where env_marker = 'DISPOSABLE' and db_name = current_database()),
                       (select owner.rolname from pg_namespace n
                          join pg_roles owner on owner.oid = n.nspowner where n.nspname = ?),
                       has_schema_privilege(current_user, ?, 'CREATE'),
                       has_schema_privilege(current_user, 'public', 'CREATE'),
                       has_schema_privilege(current_user, 'public', 'USAGE'),
                       has_table_privilege(current_user, 'public.pathome_oe_test_env', 'SELECT'),
                       has_table_privilege(current_user, 'public.pathome_oe_test_env', 'INSERT'),
                       has_database_privilege(current_user, current_database(), 'CREATE'),
                       (select count(*) from pg_auth_members m join pg_roles member on member.oid = m.member
                          where member.rolname = current_user),
                       (select not rolsuper and not rolcreatedb and not rolcreaterole and not rolinherit
                                   and not rolreplication and not rolbypassrls
                          from pg_roles where rolname = current_user)
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()
                        || !DATABASE.equals(rows.getString(1))
                        || !RUNTIME_USER.equals(rows.getString(2))
                        || !HOST.equals(rows.getString(3))
                        || rows.getInt(4) != 5432
                        || !SCHEMA.equals(rows.getString(5))
                        || rows.getInt(6) != 1
                        || !RUN_ID.equals(rows.getString(7))
                        || !MIGRATOR_USER.equals(rows.getString(8))
                        || rows.getBoolean(9)
                        || rows.getBoolean(10)
                        || !rows.getBoolean(11)
                        || !rows.getBoolean(12)
                        || rows.getBoolean(13)
                        || rows.getBoolean(14)
                        || rows.getLong(15) != 0
                        || !rows.getBoolean(16)) {
                    throw refuse("runtime role, schema, marker or privilege facts are not approved");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from pg_roles
                where rolname = ? and not rolsuper and not rolcreatedb and not rolcreaterole
                  and not rolinherit and not rolreplication and not rolbypassrls
                """)) {
            statement.setString(1, MIGRATOR_USER);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1) {
                    throw refuse("migrator role facts are not approved");
                }
            }
        }
    }

    private static Map<String, String> parse(Path file) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int separator = line.indexOf('=');
                if (separator < 1 || line.chars().anyMatch(ch -> ch == '`' || ch == '$'
                        || ch == '"' || ch == '\'' || ch == ';' || ch == '\\')) {
                    throw refuse("routing acceptance target file contains a nonliteral line");
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (!ALLOWED_KEYS.contains(key) || values.putIfAbsent(key, value) != null) {
                    throw refuse("routing acceptance target file contains an unknown or duplicate key");
                }
            }
        } catch (IOException failure) {
            throw refuse("routing acceptance target file could not be read");
        }
        return values;
    }

    private static void validateTarget(Map<String, String> values) {
        require(values, ACCEPTANCE_FLAG, ACCEPTANCE_ID);
        require(values, "PATHOME_OE_TEST_ALLOW_DISPOSABLE", "true");
        require(values, "PATHOME_OE_TEST_DB", DATABASE);
        require(values, "PATHOME_OE_TEST_DB_HOST", HOST);
        require(values, "PATHOME_OE_TEST_DB_PORT", PORT);
        require(values, "PATHOME_OE_TEST_DB_ADMIN_USER", ADMIN_USER);
        require(values, "PATHOME_OE_TEST_DB_MIGRATOR_USER", MIGRATOR_USER);
        require(values, "PATHOME_OE_TEST_DB_APP_USER", RUNTIME_USER);
        require(values, "PATHOME_OE_TEST_RUN_DIR", RUN_DIRECTORY);
        require(values, "PATHOME_OE_TEST_RUN_ID", RUN_ID);
        require(values, "PATHOME_OE_TEST_SCHEMA", SCHEMA);
        require(values, "PATHOME_OE_TEST_FIXTURE_PREFIX", FIXTURE_PREFIX);
        for (String role : List.of(ADMIN_USER, MIGRATOR_USER, RUNTIME_USER)) {
            if (!IDENTIFIER.matcher(role).matches()) throw refuse("routing acceptance role is invalid");
        }
        if (!RUN_ID_PATTERN.matcher(RUN_ID).matches()) throw refuse("routing acceptance run ID is invalid");
    }

    private static void requireEnvironmentMatches(Map<String, String> environment,
                                                  Map<String, String> target) {
        for (Map.Entry<String, String> entry : target.entrySet()) {
            if (!entry.getValue().equals(environment.get(entry.getKey()))) {
                throw refuse("effective routing acceptance environment differs from target source");
            }
        }
        String jdbc = environment.get("SPRING_DATASOURCE_URL");
        if (!("jdbc:postgresql://" + HOST + ":" + PORT + "/" + DATABASE).equals(jdbc)) {
            throw refuse("JDBC URL differs from the exact routing acceptance target");
        }
        if (!RUNTIME_USER.equals(environment.get("SPRING_DATASOURCE_USERNAME"))) {
            throw refuse("application datasource user is not the restricted runtime role");
        }
        if (!MIGRATOR_USER.equals(environment.get("SPRING_FLYWAY_USER"))) {
            throw refuse("Flyway user is not the restricted migrator role");
        }
    }

    private static void validateCredentials(Map<String, String> environment) {
        String runtime = environment.getOrDefault("PATHOME_OE_TEST_DB_PASSWORD", "");
        String migrator = environment.getOrDefault("PATHOME_OE_TEST_DB_MIGRATOR_PASSWORD", "");
        if (!runtime.matches("[a-f0-9]{48}") || !migrator.matches("[a-f0-9]{48}")
                || runtime.equals(migrator)) {
            throw refuse("runtime and migrator credentials are missing, invalid or shared");
        }
    }

    private static void requirePrivatePermissions(Path file) {
        try {
            if (!Files.getPosixFilePermissions(file).equals(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))) {
                throw refuse("routing acceptance target file must be owner-only mode 600");
            }
        } catch (IOException failure) {
            throw refuse("routing acceptance target file permissions cannot be inspected");
        }
    }

    private static Path requiredPath(String value) {
        if (value == null || value.isBlank()) throw refuse("routing acceptance target file is not designated");
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void require(Map<String, String> values, String key, String expected) {
        if (!expected.equals(values.get(key))) {
            throw refuse("routing acceptance target value differs for " + key);
        }
    }

    private static IllegalStateException refuse(String reason) {
        return new IllegalStateException("REFUSED routing acceptance: " + reason);
    }
}
