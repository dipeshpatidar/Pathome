package com.indore.pathome.spaces.entity;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the supported pre-Flyway version-1 legacy baseline through the current migration chain. */
@EnabledIfEnvironmentVariable(named = "PATHOME_PACKAGE5_FLYWAY_TEST", matches = "true")
class PathomeSupportedFlywayBaselinePostgresTest {
    private static final String LATEST_VERSION = "41";

    @Test
    void supportedLegacyBaselineUpgradesThroughLatestMigrationWithoutInventingOldOutcomes() throws Exception {
        String url = System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
        String username = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
        String password = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
        String schema = "pathome_package5_flyway_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                assertEquals(0, count(connection, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='rental_details' "
                        + "AND column_name='preferred_tenant'"),
                        "preferred_tenant was introduced by V10 and must not exist in the V1 baseline");
                statement.execute("INSERT INTO users(email,full_name,role,free_visits_remaining) "
                        + "VALUES ('package5-legacy-tenant@example.test','Legacy Tenant','ROLE_TENANT',5)");
                statement.execute("INSERT INTO listings(title,address,city,sector,latitude,longitude,listing_type,status,property_type,bhk_count) "
                        + "VALUES ('Legacy listing','Legacy address','Legacy City','Central',22.0,75.0,'RENT','ACTIVE','FLAT','1BHK')");
                statement.execute("INSERT INTO rental_details(id,available_from) "
                        + "SELECT id,CURRENT_DATE FROM listings WHERE title='Legacy listing'");
                statement.execute("INSERT INTO property_media_assets(listing_id,room_tag,media_url) "
                        + "SELECT id,'GENERAL','https://example.test/legacy.jpg' FROM listings WHERE title='Legacy listing'");

                Flyway beforeV10 = flyway(url, username, password, schema).target("9").load();
                var preV10 = beforeV10.migrate();
                assertTrue(preV10.success);
                assertEquals("9", preV10.targetSchemaVersion.toString());
                assertEquals(0, count(connection, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='rental_details' "
                        + "AND column_name='preferred_tenant'"),
                        "the column must remain absent before V10 runs");

                Flyway throughBaseline32 = flyway(url, username, password, schema).target("32").load();
                var first = throughBaseline32.migrate();
                assertTrue(first.success);
                assertEquals("32", first.targetSchemaVersion.toString());
                assertEquals(1, count(connection, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='rental_details' "
                        + "AND column_name='preferred_tenant'"),
                        "V10 must add preferred_tenant to the supported legacy baseline");
                assertEquals(1, count(connection, "SELECT count(*) FROM listings WHERE title='Legacy listing' "
                        + "AND rental_mode='LONG_TERM_RENTAL' AND workflow_status='PUBLISHED'"));
                assertEquals(1, count(connection, "SELECT count(*) FROM property_media_assets "
                        + "WHERE upload_request_id IS NULL AND room_tag='GENERAL'"));

                Flyway throughV40 = flyway(url, username, password, schema).target("40").load();
                var second = throughV40.migrate();
                assertTrue(second.success);
                assertEquals("40", second.targetSchemaVersion.toString());
                long tenantId = scalarLong(connection,
                        "SELECT id FROM users WHERE email='package5-legacy-tenant@example.test'");
                statement.execute("INSERT INTO visit_sessions(tenant_id,status,city) VALUES (" + tenantId
                        + ",'COMPLETED','Legacy City')");

                Flyway latest = flyway(url, username, password, schema).load();
                var third = latest.migrate();
                assertTrue(third.success);
                assertEquals(LATEST_VERSION, third.targetSchemaVersion.toString());
                assertDoesNotThrow(latest::validate);
                assertEquals(1, count(connection, "SELECT count(*) FROM visit_session_outcome_reports r "
                        + "JOIN visit_sessions s ON s.id=r.session_id WHERE s.status='COMPLETED' "
                        + "AND r.state='LEGACY_UNRECORDED' AND r.scope_source='LEGACY_COMPLETED'"));
                assertEquals(0, count(connection, "SELECT count(*) FROM visit_session_item_outcomes"));
                assertEquals(40, count(connection, "SELECT count(*) FROM flyway_schema_history "
                        + "WHERE type='SQL' AND success AND version::integer BETWEEN 2 AND 41"));
                assertEquals("1", text(connection,
                        "SELECT version FROM flyway_schema_history WHERE type='BASELINE'"));
                assertEquals(1, count(connection, "SELECT count(*) FROM visit_policy WHERE id=1"));
            } finally {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static FluentConfiguration flyway(String url, String username, String password, String schema) {
        return Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1");
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
