package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunOwnedSchemaDataSourceTest {
    @Test
    void rejectsAWrongDatabaseAndClosesTheConnectionBeforeReturningIt() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = connection("pathome_db", "p4_crm_http_test_owned", 1);
        when(delegate.getConnection()).thenReturn(connection);

        RunOwnedSchemaDataSource guarded = new RunOwnedSchemaDataSource(
                delegate, "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned");

        assertThrows(SQLException.class, guarded::getConnection);
        verify(connection).close();
    }

    @Test
    void rejectsAPublicFallbackAndClosesTheConnection() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = connection("pathome_oe_test_g10_20261022", "public", 1);
        when(delegate.getConnection()).thenReturn(connection);

        RunOwnedSchemaDataSource guarded = new RunOwnedSchemaDataSource(
                delegate, "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned");

        assertThrows(SQLException.class, guarded::getConnection);
        verify(connection).close();
    }

    @Test
    void refusesAnApplicationSchemaChange() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = connection("pathome_oe_test_g10_20261022",
                "p4_crm_http_test_owned", 1);
        when(delegate.getConnection()).thenReturn(connection);
        RunOwnedSchemaDataSource guarded = new RunOwnedSchemaDataSource(
                delegate, "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned");

        Connection borrowed = guarded.getConnection();
        assertThrows(SQLException.class, () -> borrowed.setSchema("public"));
        borrowed.close();
    }

    @Test
    void rawStatementUnwrapThroughAResultSetCannotExposeTheDelegate() throws Exception {
        RunOwnedSchemaDataSource guarded = validDataSource();
        try (Connection borrowed = guarded.getConnection();
             Statement statement = borrowed.createStatement()) {
            assertThrows(SQLException.class, () -> statement.unwrap(Statement.class));
            try (ResultSet rows = statement.executeQuery("select 1")) {
                Statement exposed = rows.getStatement();
                assertInstanceOf(Statement.class, exposed);
                assertThrows(SQLException.class, () -> exposed.unwrap(Statement.class));
            }
        }
    }

    @Test
    void metadataConnectionAndCallableStatementRemainProxyTyped() throws Exception {
        RunOwnedSchemaDataSource guarded = validDataSource();
        try (Connection borrowed = guarded.getConnection()) {
            assertThrows(SQLException.class, () -> borrowed.unwrap(Connection.class));
            DatabaseMetaData metadata = borrowed.getMetaData();
            Connection fromMetadata = metadata.getConnection();
            assertThrows(SQLException.class, () -> fromMetadata.unwrap(Connection.class));

            CallableStatement callable = borrowed.prepareCall("select 1");
            assertInstanceOf(CallableStatement.class, callable);
            assertThrows(SQLException.class, () -> callable.unwrap(CallableStatement.class));
        }
    }

    @Test
    void metadataResultSetsCannotExposeTheirRawStatementOrConnection() throws Exception {
        RunOwnedSchemaDataSource guarded = validDataSource();
        try (Connection borrowed = guarded.getConnection()) {
            DatabaseMetaData metadata = borrowed.getMetaData();
            try (ResultSet tables = metadata.getTables(
                    null, null, "%", new String[]{"TABLE"})) {
                Statement statement = tables.getStatement();
                assertInstanceOf(Statement.class, statement);
                assertThrows(SQLException.class, () -> statement.unwrap(Statement.class));
                assertThrows(SQLException.class,
                        () -> statement.getConnection().unwrap(Connection.class));
            }
            try (ResultSet columns = metadata.getColumns(null, null, "%", "%")) {
                Statement statement = columns.getStatement();
                assertInstanceOf(Statement.class, statement);
                assertThrows(SQLException.class, () -> statement.unwrap(Statement.class));
            }
        }
    }

    @Test
    void metadataResultSetStatementIsAlwaysTheGuardedRepresentation() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = connection(
                "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned", 1);
        DatabaseMetaData metadata = connection.getMetaData();
        Statement rawMetadataStatement = mock(Statement.class);
        ResultSet rawTables = mock(ResultSet.class);
        when(rawTables.getStatement()).thenReturn(rawMetadataStatement);
        when(rawMetadataStatement.getConnection()).thenReturn(connection);
        when(metadata.getTables(isNull(), isNull(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(rawTables);
        when(delegate.getConnection()).thenReturn(connection);

        RunOwnedSchemaDataSource guarded = new RunOwnedSchemaDataSource(
                delegate, "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned");
        try (Connection borrowed = guarded.getConnection();
             ResultSet tables = borrowed.getMetaData().getTables(
                     null, null, "%", new String[]{"TABLE"})) {
            Statement statement = tables.getStatement();
            assertInstanceOf(Statement.class, statement);
            org.junit.jupiter.api.Assertions.assertTrue(Proxy.isProxyClass(statement.getClass()));
            assertThrows(SQLException.class, () -> statement.getConnection().unwrap(Connection.class));
        }
    }

    @Test
    void callableAndNestedCursorResultsCannotExposeRawJdbcHandles() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = connection(
                "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned", 1);
        CallableStatement rawCallable = mock(CallableStatement.class);
        ResultSet outer = mock(ResultSet.class);
        ResultSet nested = mock(ResultSet.class);
        ResultSet deepest = mock(ResultSet.class);
        when(connection.prepareCall(anyString())).thenReturn(rawCallable);
        when(rawCallable.getConnection()).thenReturn(connection);
        when(rawCallable.getObject(1)).thenReturn(outer);
        when(outer.getStatement()).thenReturn(rawCallable);
        when(outer.getObject(2, ResultSet.class)).thenReturn(nested);
        when(nested.getStatement()).thenReturn(rawCallable);
        when(nested.getObject("deepest")).thenReturn(deepest);
        when(deepest.getStatement()).thenReturn(rawCallable);
        when(delegate.getConnection()).thenReturn(connection);

        RunOwnedSchemaDataSource guarded = new RunOwnedSchemaDataSource(
                delegate, "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned");
        try (Connection borrowed = guarded.getConnection();
             CallableStatement callable = borrowed.prepareCall("select guarded_cursor()");
             ResultSet first = (ResultSet) callable.getObject(1);
             ResultSet second = first.getObject(2, ResultSet.class);
             ResultSet third = (ResultSet) second.getObject("deepest")) {
            assertGuardedCursor(first, callable);
            assertGuardedCursor(second, callable);
            assertGuardedCursor(third, callable);

            when(rawCallable.getObject(2)).thenReturn(first);
            assertSame(first, callable.getObject(2),
                    "an already guarded cursor must not be wrapped again");
        }
        verify(outer).close();
        verify(nested).close();
        verify(deepest).close();
    }

    @Test
    void cursorReturnHandlingPreservesNullScalarsConversionsAndDriverExceptions()
            throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = connection(
                "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned", 1);
        CallableStatement rawCallable = mock(CallableStatement.class);
        ResultSet cursor = mock(ResultSet.class);
        Map<String, Class<?>> conversions = Map.of("answer", Integer.class);
        SQLException driverFailure = new SQLException("unsupported conversion");
        when(connection.prepareCall(anyString())).thenReturn(rawCallable);
        when(rawCallable.getConnection()).thenReturn(connection);
        when(rawCallable.getObject(1)).thenReturn(null);
        when(rawCallable.getObject(2)).thenReturn("scalar");
        when(rawCallable.getObject(3, Integer.class)).thenReturn(42);
        when(rawCallable.getObject(4, conversions)).thenReturn("converted");
        when(rawCallable.getObject(5, ResultSet.class)).thenReturn(cursor);
        when(rawCallable.getObject(6, Integer.class)).thenThrow(driverFailure);
        when(cursor.getStatement()).thenReturn(rawCallable);
        when(delegate.getConnection()).thenReturn(connection);

        RunOwnedSchemaDataSource guarded = new RunOwnedSchemaDataSource(
                delegate, "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned");
        try (Connection borrowed = guarded.getConnection();
             CallableStatement callable = borrowed.prepareCall("select guarded_values()")) {
            assertNull(callable.getObject(1));
            assertEquals("scalar", callable.getObject(2));
            assertEquals(42, callable.getObject(3, Integer.class));
            assertEquals("converted", callable.getObject(4, conversions));
            ResultSet guardedCursor = callable.getObject(5, ResultSet.class);
            assertGuardedCursor(guardedCursor, callable);
            assertSame(driverFailure,
                    assertThrows(SQLException.class,
                            () -> callable.getObject(6, Integer.class)));
        }
    }

    @Test
    void generatedKeysRemainGuardedAndCloseThroughTheDelegate() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement rawStatement = mock(Statement.class);
        ResultSet facts = mock(ResultSet.class);
        ResultSet generatedKeys = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(rawStatement);
        when(rawStatement.executeQuery(anyString())).thenReturn(facts);
        when(rawStatement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(rawStatement.getConnection()).thenReturn(connection);
        when(generatedKeys.getStatement()).thenReturn(rawStatement);
        when(facts.next()).thenReturn(true);
        when(facts.getString(1)).thenReturn("pathome_oe_test_g10_20261022");
        when(facts.getString(2)).thenReturn("p4_crm_http_test_owned");
        when(facts.getInt(3)).thenReturn(1);
        when(delegate.getConnection()).thenReturn(connection);

        RunOwnedSchemaDataSource guarded = new RunOwnedSchemaDataSource(
                delegate, "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned");
        try (Connection borrowed = guarded.getConnection();
             Statement statement = borrowed.createStatement();
             ResultSet keys = statement.getGeneratedKeys()) {
            assertGuardedCursor(keys, statement);
        }
        verify(generatedKeys).close();
    }

    private static void assertGuardedCursor(ResultSet cursor, Statement expectedStatement)
            throws SQLException {
        assertTrue(Proxy.isProxyClass(cursor.getClass()));
        SQLException unwrapFailure = assertThrows(SQLException.class,
                () -> cursor.unwrap(ResultSet.class));
        assertTrue(unwrapFailure.getMessage().contains("result-set unwrapping"));
        Statement statement = cursor.getStatement();
        assertSame(expectedStatement, statement);
        assertTrue(Proxy.isProxyClass(statement.getClass()));
        assertThrows(SQLException.class, () -> statement.unwrap(Statement.class));
        assertThrows(SQLException.class,
                () -> statement.getConnection().unwrap(Connection.class));
    }

    private static Connection connection(String database, String schema, int markers)
            throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        CallableStatement callable = mock(CallableStatement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareCall(anyString())).thenReturn(callable);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getConnection()).thenReturn(connection);
        ResultSet metadataRows = mock(ResultSet.class);
        when(metadataRows.getStatement()).thenReturn(statement);
        when(metadata.getTables(isNull(), isNull(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(metadataRows);
        when(metadata.getColumns(isNull(), isNull(), anyString(), anyString()))
                .thenReturn(metadataRows);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(statement.getConnection()).thenReturn(connection);
        when(callable.getConnection()).thenReturn(connection);
        when(rows.next()).thenReturn(true);
        when(rows.getStatement()).thenReturn(statement);
        when(rows.getString(1)).thenReturn(database);
        when(rows.getString(2)).thenReturn(schema);
        when(rows.getInt(3)).thenReturn(markers);
        return connection;
    }

    private static RunOwnedSchemaDataSource validDataSource() throws SQLException {
        DataSource delegate = mock(DataSource.class);
        Connection connection = connection(
                "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned", 1);
        when(delegate.getConnection()).thenReturn(connection);
        return new RunOwnedSchemaDataSource(
                delegate, "pathome_oe_test_g10_20261022", "p4_crm_http_test_owned");
    }
}
