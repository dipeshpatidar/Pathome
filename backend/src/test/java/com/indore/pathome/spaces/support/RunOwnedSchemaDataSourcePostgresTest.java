package com.indore.pathome.spaces.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class RunOwnedSchemaDataSourcePostgresTest {
    private static final String SCHEMA_PREFIX = "p4_run_owned_guard";
    private static DisposableIntegrationTarget.Target target;
    private static OwnedTestSchemaRole suiteRole;
    private static String schema;
    private static HikariDataSource pool;
    private static RunOwnedSchemaDataSource guarded;

    @BeforeAll
    static void createOwnedSchema() throws Exception {
        target = DisposableIntegrationTarget.verifyFromEnvironment();
        suiteRole = OwnedTestSchemaRole.provision(target, SCHEMA_PREFIX);
        OwnedTestSchemaRole.Credentials database = suiteRole.credentials();
        schema = database.schema();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(database.jdbcUrl());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(2);
        pool = new HikariDataSource(config);
        guarded = new RunOwnedSchemaDataSource(pool, target.database(), schema);
    }

    @AfterAll
    static void dropOwnedSchema() throws Exception {
        if (guarded != null) guarded.close();
        if (suiteRole != null) suiteRole.close();
    }

    @Test
    void positiveBorrowIsPinnedToTheExactOwnedSchema() throws Exception {
        try (Connection connection = guarded.getConnection()) {
            assertEquals(target.database(), connection.getCatalog());
            assertEquals(schema, connection.getSchema());
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery("select 1").close();
            }
        }
    }

    @Test
    void pooledConnectionReborrowIsRepinnedAfterSessionDrift() throws Exception {
        try (Connection connection = guarded.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("set search_path to public");
        }
        try (Connection connection = guarded.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select current_schema()")) {
            assertTrue(rows.next(), "the reborrowed connection must return a schema");
            assertEquals(schema, rows.getString(1));
            assertThrows(SQLException.class, () -> connection.unwrap(Connection.class));
        }
    }

    @Test
    void startupStyleSearchPathEscapeIsRejectedBeforeTheNextStatementExecutes()
            throws Exception {
        try (Connection connection = guarded.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("set search_path to public");
            assertThrows(SQLException.class,
                    () -> statement.execute("create table guard_escape_probe(id integer)"));
        }
    }

    @Test
    void aMissingSchemaCannotFallBackToPublic() throws Exception {
        RunOwnedSchemaDataSource missing = new RunOwnedSchemaDataSource(
                pool, target.database(), schema + "_missing");
        assertThrows(SQLException.class, missing::getConnection);
    }

    @Test
    void postgresqlDeniesQualifiedPublicWritesEvenWhenTheWrapperIsBypassed() throws Exception {
        OwnedTestSchemaRole.Credentials database = suiteRole.credentials();
        try (Connection raw = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
             Statement statement = raw.createStatement()) {
            statement.execute("set search_path to public");
            assertThrows(SQLException.class, () -> statement.execute(
                    "create table pathome_forbidden_public_probe(id integer)"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "update public.pathome_oe_test_env set guard_version = guard_version"));
        }
    }

    @Test
    void resultSetAndMetadataPathsDoNotReturnRawConnections() throws Exception {
        try (Connection connection = guarded.getConnection()) {
            assertThrows(SQLException.class, () -> connection.unwrap(Connection.class));
            assertThrows(SQLException.class,
                    () -> connection.getMetaData().getConnection().unwrap(Connection.class));
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("select 1")) {
                assertThrows(SQLException.class,
                        () -> rows.getStatement().unwrap(Statement.class));
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table guard_metadata_probe(id integer)");
            }
            try (ResultSet tables = connection.getMetaData().getTables(
                    null, schema, "guard_metadata_probe", new String[]{"TABLE"})) {
                assertEquals(true, tables.next());
                Statement metadataStatement = tables.getStatement();
                if (metadataStatement != null) {
                    assertThrows(SQLException.class,
                            () -> metadataStatement.unwrap(Statement.class));
                    assertThrows(SQLException.class,
                            () -> metadataStatement.getConnection().unwrap(Connection.class));
                }
            }
            try (ResultSet columns = connection.getMetaData().getColumns(
                    null, schema, "guard_metadata_probe", "%")) {
                assertEquals(true, columns.next());
                assertEquals("ID", columns.getString("COLUMN_NAME").toUpperCase());
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("drop table guard_metadata_probe");
            }
        }
    }

    @Test
    void callableStatementAndMultiStatementPathsStayInsideTheOwnedSchema() throws Exception {
        try (Connection connection = guarded.getConnection();
             var callable = connection.prepareCall("select current_schema()")) {
            try (var rows = callable.executeQuery()) {
                assertEquals(true, rows.next());
                assertEquals(schema, rows.getString(1));
            }
            try (Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.execute(
                        "create table callable_escape_probe(id integer); "
                                + "create table public.forbidden_second_statement(id integer)"));
            }
        }
    }

    @Test
    void transactionScopedRefcursorIsGuardedTypedAndClosesSafely() throws Exception {
        String quotedSchema = "\"" + schema + "\"";
        String function = quotedSchema + ".g10_open_cursor";
        try (Connection connection = guarded.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("create function " + function + "() returns refcursor "
                        + "language plpgsql as $$ declare cursor_ref refcursor; begin "
                        + "open cursor_ref for select 42::integer as answer, "
                        + "current_schema() as schema_name; "
                        + "return cursor_ref; end $$");
            }

            try (CallableStatement callable = connection.prepareCall(
                    "{ ? = call " + function + "() }")) {
                callable.registerOutParameter(1, Types.OTHER);
                callable.execute();
                assertGuardedRefcursor(callable, (ResultSet) callable.getObject(1));
            }

            try (CallableStatement callable = connection.prepareCall(
                    "{ ? = call " + function + "() }")) {
                callable.registerOutParameter(1, Types.OTHER);
                callable.execute();
                assertGuardedRefcursor(callable, callable.getObject(1, ResultSet.class));
            }
            connection.rollback();
        }
    }

    private static void assertGuardedRefcursor(CallableStatement callable, ResultSet cursor)
            throws SQLException {
        assertTrue(Proxy.isProxyClass(cursor.getClass()));
        assertSame(callable, cursor.getStatement());
        assertThrows(SQLException.class, () -> cursor.unwrap(ResultSet.class));
        assertThrows(SQLException.class,
                () -> cursor.getStatement().unwrap(Statement.class));
        assertThrows(SQLException.class,
                () -> cursor.getStatement().getConnection().unwrap(Connection.class));
        try (cursor) {
            assertTrue(cursor.next());
            assertEquals(42, cursor.getObject("answer"));
            assertEquals(42, cursor.getObject("answer", Integer.class));
            assertEquals(42, cursor.getInt("answer"));
            assertThrows(SQLException.class,
                    () -> cursor.getObject("schema_name", Integer.class));
            assertEquals(schema, cursor.getString("schema_name"));
        }
    }
}
