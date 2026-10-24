package com.indore.pathome.spaces.support;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Test-only connection boundary for a disposable Spring context.
 *
 * <p>Every borrowed connection is rebound to the exact run-owned schema and checked against the
 * positively designated database before it is released to application code. PostgreSQL's
 * suite-specific role is the durable write boundary: it owns only that schema and has no write
 * privilege in {@code public}. This remains true if a test bypasses the JDBC wrapper or changes the
 * search path. Rejected connections are always closed.
 */
public final class RunOwnedSchemaDataSource extends AbstractDataSource {
    private static final List<String> STATEMENT_FACTORIES =
            List.of("createStatement", "prepareStatement", "prepareCall");

    private final DataSource delegate;
    private final String expectedDatabase;
    private final String expectedSchema;
    private final boolean allowCatalogFallbackForFramework;

    public RunOwnedSchemaDataSource(DataSource delegate, String expectedDatabase,
                                    String expectedSchema) {
        this(delegate, expectedDatabase, expectedSchema, false);
    }

    private RunOwnedSchemaDataSource(DataSource delegate, String expectedDatabase,
                                     String expectedSchema, boolean allowCatalogFallbackForFramework) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.expectedDatabase = requireIdentifier(expectedDatabase, "database");
        this.expectedSchema = requireIdentifier(expectedSchema, "schema");
        this.allowCatalogFallbackForFramework = allowCatalogFallbackForFramework;
    }

    /**
     * Flyway bootstrap only. The exact test schema remains first and {@code current_schema()} is
     * verified; {@code public} is visible solely so an existing extension operator class can resolve.
     * Application datasources never use this mode.
     */
    public static RunOwnedSchemaDataSource forFrameworkBootstrap(
            DataSource delegate, String expectedDatabase, String expectedSchema) {
        return new RunOwnedSchemaDataSource(delegate, expectedDatabase, expectedSchema, true);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return guarded(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return guarded(delegate.getConnection(username, password));
    }

    /** Releases a pooled delegate after the suite has dropped only its recorded schema. */
    public void close() {
        if (delegate instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Test teardown must not mask the test result with an already-closed pool.
            }
        }
    }

    private Connection guarded(Connection connection) throws SQLException {
        boolean ready = false;
        try {
            connection.setSchema(expectedSchema);
            if (allowCatalogFallbackForFramework) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set search_path to \"" + expectedSchema + "\", public");
                }
            }
            verifyFacts(connection);
            Connection proxy = connectionProxy(connection);
            ready = true;
            return proxy;
        } finally {
            if (!ready) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // The original refusal remains the actionable failure.
                }
            }
        }
    }

    private void verifyFacts(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select current_database(), current_schema(), "
                             + "(select count(*) from public.pathome_oe_test_env "
                             + "where env_marker = 'DISPOSABLE' and db_name = current_database())")) {
            if (!rows.next()) {
                throw refuse("the connection facts could not be read");
            }
            String database = rows.getString(1);
            String schema = rows.getString(2);
            int markers = rows.getInt(3);
            if (!expectedDatabase.equals(database)) {
                throw refuse("the connection resolved to database '" + database + "'");
            }
            if (!expectedSchema.equals(schema)) {
                throw refuse("the connection resolved to schema '" + schema + "'");
            }
            if (markers != 1) {
                throw refuse("the connection did not resolve to exactly one disposable marker");
            }
        }
    }

    private Connection connectionProxy(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                RunOwnedSchemaDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler(connection));
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection delegateConnection;

        private ConnectionHandler(Connection delegateConnection) {
            this.delegateConnection = delegateConnection;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "RunOwnedSchemaConnection[" + expectedSchema + "]";
                    default -> method.invoke(delegateConnection, arguments);
                };
            }
            if ("setSchema".equals(name)) {
                if (arguments == null || arguments.length != 1
                        || !expectedSchema.equals(arguments[0])) {
                    throw refuse("the application attempted to change the run-owned schema");
                }
                return invokeDelegate(method, arguments);
            }
            if ("isWrapperFor".equals(name)
                    && arguments != null && arguments.length == 1
                    && arguments[0] == Connection.class) {
                return true;
            }
            if ("isWrapperFor".equals(name)
                    && arguments != null && arguments.length == 1
                    && arguments[0] instanceof Class<?> requested) {
                return requested.isAssignableFrom(Connection.class);
            }
            if ("unwrap".equals(name)) {
                throw refuse("connection unwrapping would bypass the run-owned schema guard");
            }
            if (STATEMENT_FACTORIES.contains(name)) {
                verifyFacts(delegateConnection);
                Object created = invokeDelegate(method, arguments);
                return statementProxy(created);
            }
            if ("getMetaData".equals(name)) {
                verifyFacts(delegateConnection);
                return metadataProxy((DatabaseMetaData) invokeDelegate(method, arguments));
            }
            return invokeDelegate(method, arguments);
        }

        private Object invokeDelegate(Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(delegateConnection, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private Object statementProxy(Object statement) {
        if (statement == null) return null;
        if (isGuardedStatement(statement)) return statement;
        Class<?> contract = statement instanceof CallableStatement
                ? CallableStatement.class
                : statement instanceof PreparedStatement
                    ? PreparedStatement.class
                    : Statement.class;
        return Proxy.newProxyInstance(RunOwnedSchemaDataSource.class.getClassLoader(),
                new Class<?>[]{contract}, new StatementHandler(statement));
    }

    private final class StatementHandler implements InvocationHandler {
        private final Object delegateStatement;

        private StatementHandler(Object delegateStatement) {
            this.delegateStatement = rawStatement(delegateStatement);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "RunOwnedSchemaStatement[" + expectedSchema + "]";
                    default -> method.invoke(delegateStatement, arguments);
                };
            }
            if ("getConnection".equals(method.getName())) {
                Connection connection = (Connection) invokeDelegateMethod(
                        delegateStatement, method, arguments);
                verifyFacts(connection);
                return connectionProxy(connection);
            }
            if ("isWrapperFor".equals(method.getName())
                    && arguments != null && arguments.length == 1
                    && arguments[0] instanceof Class<?> requested) {
                return requested.isInstance(proxy);
            }
            if ("unwrap".equals(method.getName())) {
                throw refuse("statement unwrapping would expose a raw JDBC statement");
            }
            Object result = invokeDelegateMethod(delegateStatement, method, arguments);
            return guardResult(result, (Statement) proxy);
        }
    }

    private static boolean isGuardedStatement(Object candidate) {
        return candidate != null
                && Proxy.isProxyClass(candidate.getClass())
                && Proxy.getInvocationHandler(candidate) instanceof StatementHandler;
    }

    private static Object rawStatement(Object candidate) {
        Object current = candidate;
        while (current != null && Proxy.isProxyClass(current.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(current);
            if (!(handler instanceof StatementHandler statementHandler)) break;
            current = statementHandler.delegateStatement;
        }
        return current;
    }

    private Object resultSetProxy(ResultSet resultSet, Statement statement) {
        if (resultSet == null) return null;
        if (isGuardedResultSet(resultSet)) return resultSet;
        return Proxy.newProxyInstance(RunOwnedSchemaDataSource.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, new ResultSetHandler(resultSet, statement));
    }

    private static boolean isGuardedResultSet(Object candidate) {
        return candidate != null
                && Proxy.isProxyClass(candidate.getClass())
                && Proxy.getInvocationHandler(candidate) instanceof ResultSetHandler;
    }

    private Object guardResult(Object result, Statement statement) {
        return result instanceof ResultSet resultSet
                ? resultSetProxy(resultSet, statement)
                : result;
    }

    private final class ResultSetHandler implements InvocationHandler {
        private final ResultSet delegateResultSet;
        private final Statement guardedStatement;

        private ResultSetHandler(ResultSet delegateResultSet, Statement guardedStatement) {
            this.delegateResultSet = delegateResultSet;
            this.guardedStatement = guardedStatement;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "RunOwnedSchemaResultSet[" + expectedSchema + "]";
                    default -> method.invoke(delegateResultSet, arguments);
                };
            }
            if ("getStatement".equals(method.getName())) return guardedStatement;
            if ("unwrap".equals(method.getName())) {
                throw refuse("result-set unwrapping would expose a raw JDBC statement");
            }
            if ("isWrapperFor".equals(method.getName())
                    && arguments != null && arguments.length == 1
                    && arguments[0] instanceof Class<?> requested) {
                return requested.isInstance(proxy);
            }
            Object result = invokeDelegateMethod(delegateResultSet, method, arguments);
            return guardResult(result, guardedStatement);
        }
    }

    private DatabaseMetaData metadataProxy(DatabaseMetaData metadata) {
        if (metadata == null) return null;
        return (DatabaseMetaData) Proxy.newProxyInstance(
                RunOwnedSchemaDataSource.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "RunOwnedSchemaMetadata[" + expectedSchema + "]";
                            default -> method.invoke(metadata, arguments);
                        };
                    }
                    if ("getConnection".equals(method.getName())) {
                        Connection connection = (Connection) invokeDelegateMethod(
                                metadata, method, arguments);
                        verifyFacts(connection);
                        return connectionProxy(connection);
                    }
                    if ("unwrap".equals(method.getName())) {
                        throw refuse("metadata unwrapping would expose a raw JDBC connection");
                    }
                    if ("isWrapperFor".equals(method.getName())
                            && arguments != null && arguments.length == 1
                            && arguments[0] instanceof Class<?> requested) {
                        return requested.isInstance(proxy);
                    }
                    Object result = invokeDelegateMethod(metadata, method, arguments);
                    if (result instanceof ResultSet resultSet) {
                        Statement rawStatement = null;
                        try {
                            rawStatement = resultSet.getStatement();
                        } catch (SQLException ignored) {
                            // A metadata result without a statement remains safe: getStatement is null.
                        }
                        return resultSetProxy(resultSet,
                                rawStatement == null ? null : (Statement) statementProxy(rawStatement));
                    }
                    return result;
                });
    }

    private static Object invokeDelegateMethod(Object target, Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static String requireIdentifier(String value, String label) {
        if (value == null || !value.matches("[a-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("Run-owned " + label + " identifier is invalid");
        }
        return value;
    }

    private static SQLException refuse(String reason) {
        return new SQLException("REFUSED run-owned schema boundary: " + reason);
    }
}
