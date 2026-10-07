package com.indore.pathome.spaces.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** One clean Flyway + Hibernate validation startup on disposable PostgreSQL schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=1",
        "APP_FIRST_ADMIN_PROVISIONING_ENABLED=false",
        "app.jwt.secret=parser-field-migration-test-jwt-secret-32-bytes-minimum",
        "pathome.visit.otp.hmac-secret=parser-field-migration-test-otp-secret-32-bytes-minimum",
        "cloudinary.api-key=parser-field-test-key",
        "cloudinary.api-secret=parser-field-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "PATHOME_PARSER_REVIEW_STARTUP_TEST", matches = "true")
class ParserFieldReviewConfiguredStartupPostgresTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "pathome_parser_review_startup_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void disposableDatasource(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            try {
                connection.setSchema(SCHEMA);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
            } catch (Exception failure) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                throw failure;
            }
        }

        // PostgreSQL's shared pg_trgm extension is installed in public on this test database.
        // Keep the disposable schema first while making its extension operator class visible.
        String schemaUrl = BASE_URL + (BASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropDisposableSchema() throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;

    @Test
    void cleanFlywaySchemaValidatesAndApplicationContextStartsWithoutBootstrapOrFabricatedData() {
        assertNotNull(flyway);
        assertEquals(49, jdbc.queryForObject(
                "SELECT max(version::integer) FROM flyway_schema_history WHERE type='SQL' AND success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema=current_schema() AND table_name='parser_field_reviews'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM parser_field_reviews", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM parser_training_examples", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM parser_model_versions", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema=current_schema() AND table_name='tenant_visit_logs'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tenant_visit_logs", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema=current_schema() AND table_name='property_media_assets' "
                + "AND column_name IN ('latitude','longitude') AND data_type='double precision'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM staff_access_grants", Integer.class),
                "first-Admin bootstrap remains disabled for this startup");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM property_visit_requests WHERE operational_scope_ready "
                + "OR supported_city_id IS NOT NULL OR operating_team_id IS NOT NULL OR coordinator_user_id IS NOT NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM visit_sessions WHERE operational_scope_ready "
                + "OR supported_city_id IS NOT NULL OR operating_team_id IS NOT NULL OR coordinator_user_id IS NOT NULL", Integer.class));
    }
}
