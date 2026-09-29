package com.indore.pathome.spaces.entity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runs the cover-invariant Flyway migration in a disposable PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named = "PATHOME_COVER_INVARIANT_POSTGRES_TEST", matches = "true")
class ActiveLessorDraftCoverInvariantPostgresTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "lessor_cover_test_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    static void prepareSchemaAndApplyMigration() throws Exception {
        try (Connection connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA);
            statement.execute("CREATE TABLE property_upload_drafts (draft_id VARCHAR(64) PRIMARY KEY, status VARCHAR(30) NOT NULL)");
            statement.execute("CREATE TABLE property_draft_media (id BIGSERIAL PRIMARY KEY, draft_id VARCHAR(64) NOT NULL, " +
                    "is_cover BOOLEAN NOT NULL DEFAULT FALSE, guest_owned BOOLEAN NOT NULL DEFAULT FALSE, landlord_user_id BIGINT)");
        }
        Flyway.configure().dataSource(BASE_URL, USERNAME, PASSWORD)
                .schemas(SCHEMA).defaultSchema(SCHEMA)
                .baselineOnMigrate(true).baselineVersion("30")
                .locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void activeDraftRejectsTwoCoversButAllowsAtomicReplacementAndPreservesSubmittedHistory() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            insertDraft(connection, "active-duplicate", "DRAFT");
            insertMedia(connection, "active-duplicate", true, false, 10L);
            insertMedia(connection, "active-duplicate", true, false, 10L);
            SQLException duplicate = assertThrows(SQLException.class, connection::commit);
            assertEquals("23505", duplicate.getSQLState());
            connection.rollback();
        }

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            insertDraft(connection, "active-replacement", "DRAFT");
            insertMedia(connection, "active-replacement", true, false, 10L);
            insertMedia(connection, "active-replacement", false, false, 10L);
            connection.commit();

            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE property_draft_media SET is_cover = FALSE WHERE id = " +
                        "(SELECT MIN(id) FROM property_draft_media WHERE draft_id = 'active-replacement')");
                statement.executeUpdate("UPDATE property_draft_media SET is_cover = TRUE WHERE id = " +
                        "(SELECT MAX(id) FROM property_draft_media WHERE draft_id = 'active-replacement')");
            }
            connection.commit();
        }
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM property_draft_media WHERE draft_id = 'active-replacement' AND is_cover")) {
            rows.next();
            assertEquals(1, rows.getInt(1));
        }

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            insertDraft(connection, "submitted-history", "SUBMITTED");
            insertMedia(connection, "submitted-history", true, false, 10L);
            insertMedia(connection, "submitted-history", true, false, 10L);
            connection.commit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE property_upload_drafts SET status = 'DRAFT' WHERE draft_id = 'submitted-history'");
            }
            assertEquals("23505", assertThrows(SQLException.class, connection::commit).getSQLState());
            connection.rollback();
        }
    }

    private static Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + SCHEMA);
        }
        return connection;
    }

    private static void insertDraft(Connection connection, String draftId, String status) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO property_upload_drafts (draft_id, status) VALUES (?, ?)")) {
            statement.setString(1, draftId);
            statement.setString(2, status);
            statement.executeUpdate();
        }
    }

    private static void insertMedia(Connection connection, String draftId, boolean cover,
                                    boolean guestOwned, long landlordUserId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO property_draft_media (draft_id, is_cover, guest_owned, landlord_user_id) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, draftId);
            statement.setBoolean(2, cover);
            statement.setBoolean(3, guestOwned);
            statement.setLong(4, landlordUserId);
            statement.executeUpdate();
        }
    }
}
