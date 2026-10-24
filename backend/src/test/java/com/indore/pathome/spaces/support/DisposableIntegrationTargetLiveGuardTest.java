package com.indore.pathome.spaces.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.DESIGNATION_FLAG;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.SENTINEL_MARKER;
import static com.indore.pathome.spaces.support.DisposableIntegrationTarget.SENTINEL_TABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Live evidence that the guard positively verifies the real designated disposable database.
 *
 * <p>Runs only when the test runner is explicitly enabled, so an ordinary build never opens a
 * connection. The marker is re-read here through an independent read-only connection, so the evidence
 * shows the live database really carries it rather than the guard merely echoing its own constants.
 */
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class DisposableIntegrationTargetLiveGuardTest {

    @Test
    void theDesignatedDisposableTargetPassesTheLiveGuardAndCarriesExactlyOneMarker() throws Exception {
        DisposableIntegrationTarget.Target target = DisposableIntegrationTarget.verifyFromEnvironment();
        assertEquals(System.getenv(DESIGNATION_FLAG), target.database());
        assertEquals(1, liveMarkerCount(target));
    }

    private static int liveMarkerCount(DisposableIntegrationTarget.Target target) throws Exception {
        try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), target.username(),
                target.password())) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement("select count(*) from " + SENTINEL_TABLE
                    + " where env_marker = ? and db_name = current_database()")) {
                statement.setString(1, SENTINEL_MARKER);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
        }
    }
}
