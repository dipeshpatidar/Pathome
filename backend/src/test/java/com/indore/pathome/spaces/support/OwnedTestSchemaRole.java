package com.indore.pathome.spaces.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Provisions one transactionally created, run-owned PostgreSQL schema and login for one test suite.
 *
 * <p>The generated identity and positive disposable marker are verified before both provisioning and
 * cleanup. Cleanup is limited to resources whose exact identity and ownership are proven immediately
 * before a transactional {@code DROP}. If creation or cleanup outcome is ambiguous, resources are left
 * intact and destructive cleanup is refused.
 */
public final class OwnedTestSchemaRole implements AutoCloseable {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern SCHEMA_PREFIX = Pattern.compile("[a-z][a-z0-9_]{0,43}");
    private static final Pattern PASSWORD = Pattern.compile("[a-f0-9]{48}");
    private static final Pattern RUN_ID = Pattern.compile("[a-z0-9][a-z0-9-]{15,63}");
    private static final String ADMIN_USER = "PATHOME_OE_TEST_DB_ADMIN_USER";
    private static final String ADMIN_PASSWORD = "PATHOME_OE_TEST_DB_ADMIN_PASSWORD";
    private static final String RUN_ID_ENV = "PATHOME_OE_TEST_RUN_ID";
    private static final String LEGACY_RUN_ID_ENV = "OE_RUN_ID";
    private static final String RUN_DIR_ENV = "PATHOME_OE_TEST_RUN_DIR";
    private static final String RUN_ID_FILE = "run-id";
    private static final String GUARD_VERSION = "1.0.0";

    public record Credentials(String jdbcUrl, String database, String schema, String username,
                              String password) {
        public String schemaUrl() {
            return jdbcUrl + "?currentSchema=" + schema;
        }
    }

    @FunctionalInterface
    interface AdminConnectionFactory {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    interface RestrictedIdentityVerifier {
        void verify(Credentials credentials) throws SQLException;
    }

    record Identity(String schema, String role, String password) {}

    private final DisposableIntegrationTarget.Target target;
    private final String runId;
    private final String role;
    private final Credentials credentials;
    private final AdminConnectionFactory adminConnections;
    private boolean roleCreated;
    private boolean schemaCreated;
    private boolean cleanupUnsafe;
    private boolean closed;

    private OwnedTestSchemaRole(DisposableIntegrationTarget.Target target, String runId,
                                String role, String schema,
                                String password, AdminConnectionFactory adminConnections) {
        this.target = target;
        this.runId = runId;
        this.role = role;
        this.credentials = new Credentials(target.jdbcUrl(), target.database(), schema, role, password);
        this.adminConnections = adminConnections;
    }

    public Credentials credentials() {
        return credentials;
    }

    public static OwnedTestSchemaRole provision(DisposableIntegrationTarget.Target target,
                                                String requestedSchemaPrefix) throws SQLException {
        Map<String, String> environment = System.getenv();
        String adminUser = requireAdminUser(environment);
        String adminPassword = environment.getOrDefault(ADMIN_PASSWORD, "");
        AdminConnectionFactory factory = () -> DriverManager.getConnection(
                target.jdbcUrl(), adminUser, adminPassword);
        return provision(target, requestedSchemaPrefix, environment, factory,
                credentials -> verifyRestrictedIdentity(credentials), generatedIdentity(
                        requestedSchemaPrefix));
    }

    static OwnedTestSchemaRole provision(
            DisposableIntegrationTarget.Target target,
            String requestedSchemaPrefix,
            Map<String, String> environment,
            AdminConnectionFactory adminConnections,
            RestrictedIdentityVerifier restrictedVerifier,
            Identity identity) throws SQLException {
        requireTarget(target);
        if (requestedSchemaPrefix == null || !SCHEMA_PREFIX.matcher(requestedSchemaPrefix).matches()) {
            throw new IllegalArgumentException("Suite schema prefix is invalid");
        }
        String adminUser = requireAdminUser(environment);
        String adminPassword = environment.getOrDefault(ADMIN_PASSWORD, "");
        String runId = expectedRunId(environment);
        requireIdentity(identity);

        OwnedTestSchemaRole owned = new OwnedTestSchemaRole(target, runId,
                identity.role(), identity.schema(), identity.password(), adminConnections);
        try {
            owned.bootstrap();
            restrictedVerifier.verify(owned.credentials);
            return owned;
        } catch (SQLException | RuntimeException failure) {
            owned.closeQuietly();
            throw failure;
        }
    }

    private void bootstrap() throws SQLException {
        boolean createdRole = false;
        boolean createdSchema = false;
        try (Connection admin = adminConnections.open()) {
            admin.setAutoCommit(false);
            try {
                verifyAdministrator(admin);
                String roleSql = quoted(role);
                String schemaSql = quoted(credentials.schema());
                String databaseSql = quoted(credentials.database());

                // These are database-local defaults. They are part of the same transaction so a
                // provisioning failure cannot leave a partially applied suite boundary.
                try (Statement statement = admin.createStatement()) {
                    statement.execute("revoke create on schema public from public");
                    statement.execute("revoke temp on database " + databaseSql + " from public");
                    statement.execute("create role " + roleSql
                            + " login password '" + credentials.password() + "'"
                            + " nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls");
                    createdRole = true;
                    statement.execute("revoke all on schema public from " + roleSql);
                    statement.execute("grant connect on database " + databaseSql + " to " + roleSql);
                    statement.execute("grant temp on database " + databaseSql + " to " + roleSql);
                    statement.execute("grant usage on schema public to " + roleSql);
                    statement.execute("grant select on table public.pathome_oe_test_env to " + roleSql);
                    statement.execute("create schema " + schemaSql + " authorization " + roleSql);
                    createdSchema = true;
                    statement.execute("revoke all on schema " + schemaSql + " from public");
                }
                admin.commit();
                roleCreated = createdRole;
                schemaCreated = createdSchema;
            } catch (SQLException | RuntimeException failure) {
                try {
                    admin.rollback();
                } catch (SQLException rollbackFailure) {
                    cleanupUnsafe = true;
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private void verifyAdministrator(Connection admin) throws SQLException {
        try (PreparedStatement statement = admin.prepareStatement(
                "select current_database(), r.rolsuper or r.rolcreaterole "
                        + "from pg_roles r where r.rolname = current_user");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || !target.database().equals(rows.getString(1)) || !rows.getBoolean(2)) {
                throw refuse("administrator or database is invalid");
            }
        }
        try (PreparedStatement statement = admin.prepareStatement(
                "select count(*), min(run_id) from public.pathome_oe_test_env "
                        + "where env_marker = 'DISPOSABLE' and db_name = current_database() "
                        + "and guard_version = ?")) {
            statement.setString(1, GUARD_VERSION);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1 || !runId.equals(rows.getString(2))) {
                    throw refuse("disposable marker or run identity is invalid");
                }
            }
        }
    }

    private static void verifyRestrictedIdentity(Credentials database) throws SQLException {
        try (Connection restricted = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password())) {
            restricted.setReadOnly(true);
            try (PreparedStatement statement = restricted.prepareStatement(
                    "select current_user = ?, current_database() = ?, "
                            + "has_schema_privilege(current_user, ?, 'CREATE'), "
                            + "has_schema_privilege(current_user, 'public', 'CREATE'), "
                            + "has_schema_privilege(current_user, 'public', 'USAGE'), "
                            + "has_table_privilege(current_user, 'public.pathome_oe_test_env', 'SELECT'), "
                            + "has_table_privilege(current_user, 'public.pathome_oe_test_env', 'INSERT'), "
                            + "has_table_privilege(current_user, 'public.pathome_oe_test_env', 'UPDATE'), "
                            + "has_table_privilege(current_user, 'public.pathome_oe_test_env', 'DELETE'), "
                            + "has_database_privilege(current_user, ?, 'CREATE'), "
                            + "has_database_privilege(current_user, ?, 'TEMP'), "
                            + "(select count(*) from pg_auth_members m "
                            + "join pg_roles member on member.oid = m.member "
                            + "where member.rolname = current_user), "
                            + "(select count(*) from pg_namespace n "
                            + "join pg_roles owner on owner.oid = n.nspowner "
                            + "where n.nspname = ? and owner.rolname = current_user), "
                            + "(select not rolsuper and not rolcreatedb and not rolcreaterole "
                            + "from pg_roles where rolname = current_user)")) {
                statement.setString(1, database.username());
                statement.setString(2, database.database());
                statement.setString(3, database.schema());
                statement.setString(4, database.database());
                statement.setString(5, database.database());
                statement.setString(6, database.schema());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()
                            || !rows.getBoolean(1)
                            || !rows.getBoolean(2)
                            || !rows.getBoolean(3)
                            || rows.getBoolean(4)
                            || !rows.getBoolean(5)
                            || !rows.getBoolean(6)
                            || rows.getBoolean(7)
                            || rows.getBoolean(8)
                            || rows.getBoolean(9)
                            || rows.getBoolean(10)
                            || !rows.getBoolean(11)
                            || rows.getLong(12) != 0
                            || rows.getLong(13) != 1
                            || !rows.getBoolean(14)) {
                        throw new SQLException(
                                "REFUSED suite-role verification: PostgreSQL privileges or ownership exceed the owned schema");
                    }
                }
            }
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (closed) return;
        if (cleanupUnsafe) {
            throw refuse("cleanup eligibility is uncertain; owned resources were left intact");
        }
        if (!roleCreated && !schemaCreated) {
            closed = true;
            return;
        }

        try (Connection admin = adminConnections.open()) {
            admin.setAutoCommit(false);
            try {
                verifyAdministrator(admin);
                if (schemaCreated) requireSchemaOwnedBySuiteRole(admin);
                if (roleCreated) requireExactRole(admin);

                try (Statement statement = admin.createStatement()) {
                    if (schemaCreated) {
                        statement.execute("drop schema " + quoted(credentials.schema()) + " cascade");
                    }
                    if (roleCreated) {
                        // PostgreSQL requires these exact grants to be removed before the generated
                        // login can be dropped. They are the grants this suite created; no other
                        // role's privileges are addressed.
                        statement.execute("revoke all privileges on table public.pathome_oe_test_env from "
                                + quoted(role));
                        statement.execute("revoke all privileges on schema public from " + quoted(role));
                        statement.execute("revoke all privileges on database " + quoted(credentials.database())
                                + " from " + quoted(role));
                        statement.execute("drop role " + quoted(role));
                    }
                }
                admin.commit();
                schemaCreated = false;
                roleCreated = false;
                closed = true;
            } catch (SQLException | RuntimeException failure) {
                try {
                    admin.rollback();
                } catch (SQLException rollbackFailure) {
                    cleanupUnsafe = true;
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private void requireSchemaOwnedBySuiteRole(Connection admin) throws SQLException {
        try (PreparedStatement statement = admin.prepareStatement(
                "select owner.rolname from pg_namespace n "
                        + "join pg_roles owner on owner.oid = n.nspowner where n.nspname = ?")) {
            statement.setString(1, credentials.schema());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !role.equals(rows.getString(1))) {
                    throw refuse("schema ownership cannot be proven; destructive cleanup refused");
                }
            }
        }
    }

    private void requireExactRole(Connection admin) throws SQLException {
        try (PreparedStatement statement = admin.prepareStatement(
                "select rolname from pg_roles where rolname = ?")) {
            statement.setString(1, role);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !role.equals(rows.getString(1))) {
                    throw refuse("suite role identity cannot be proven; destructive cleanup refused");
                }
            }
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (Exception ignored) {
            // Preserve the original provisioning failure. Safe cleanup is attempted independently.
        }
    }

    private static String requireAdminUser(Map<String, String> environment) {
        String adminUser = environment.get(ADMIN_USER);
        if (adminUser == null || !IDENTIFIER.matcher(adminUser).matches()) {
            throw new IllegalStateException("Disposable administrator identity is missing or invalid");
        }
        return adminUser;
    }

    private static String expectedRunId(Map<String, String> environment) {
        String explicit = trimmed(environment.get(RUN_ID_ENV));
        if (explicit == null) explicit = trimmed(environment.get(LEGACY_RUN_ID_ENV));
        if (explicit != null) {
            requireRunId(explicit);
            return explicit;
        }
        String runDirectory = trimmed(environment.get(RUN_DIR_ENV));
        if (runDirectory == null || !runDirectory.startsWith("/private/tmp/")) {
            throw refuseState("expected run identity is missing");
        }
        try {
            String fromFile = trimmed(Files.readString(Path.of(runDirectory, RUN_ID_FILE)));
            if (fromFile == null) throw refuseState("expected run identity is empty");
            requireRunId(fromFile);
            return fromFile;
        } catch (IOException failure) {
            throw refuseState("expected run identity could not be read");
        }
    }

    private static void requireRunId(String runId) {
        if (!RUN_ID.matcher(runId).matches()) {
            throw refuseState("expected run identity has an invalid shape");
        }
    }

    private static Identity generatedIdentity(String requestedSchemaPrefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String role = "pathome_suite_" + suffix.substring(0, 20);
        String schema = requestedSchemaPrefix + "_" + suffix.substring(20, 32);
        String password = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new Identity(schema, role, password);
    }

    private static void requireIdentity(Identity identity) {
        if (identity == null || !IDENTIFIER.matcher(identity.role()).matches()
                || !IDENTIFIER.matcher(identity.schema()).matches()
                || !PASSWORD.matcher(identity.password()).matches()) {
            throw new IllegalStateException("Generated suite identity is invalid");
        }
    }

    private static void requireTarget(DisposableIntegrationTarget.Target target) {
        if (target == null
                || !DisposableIntegrationTarget.APPROVED_DATABASES.contains(target.database())) {
            throw new IllegalArgumentException("Only a positively verified disposable target may be used");
        }
    }

    private static String trimmed(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static String quoted(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("SQL identifier is invalid");
        }
        return "\"" + identifier + "\"";
    }

    private static SQLException refuse(String reason) {
        return new SQLException("REFUSED suite-schema lifecycle: " + reason);
    }

    private static IllegalStateException refuseState(String reason) {
        return new IllegalStateException("REFUSED suite-schema lifecycle: " + reason);
    }
}
