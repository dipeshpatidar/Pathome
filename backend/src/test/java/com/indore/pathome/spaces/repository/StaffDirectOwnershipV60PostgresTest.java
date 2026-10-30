package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.support.FreshIntegrationTarget;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real PostgreSQL ownership matrix for V59 media and the V60 null-safe draft CHECK. Every write is isolated by a transaction-scoped
 * savepoint and rolled back; no cohort reset, migration rollback or historical source change occurs.
 */
@EnabledIfEnvironmentVariable(
        named = FreshIntegrationTarget.TEST_ENABLEMENT_FLAG, matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaffDirectOwnershipV60PostgresTest {
    private static final String DRAFT_INSERT = """
            insert into public.property_upload_drafts (
                draft_id, admin_id, landlord_user_id, lessor_profile_id,
                staff_maker_user_id, revision_mode, guest_token_hash, guest_expires_at,
                draft_type, status, item_count, version, payload, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, 'SINGLE', 'DRAFT', 1, 1, '{}', now(), now())
            """;
    private static final String MEDIA_INSERT = """
            insert into public.property_draft_media (
                media_id, draft_id, admin_id, landlord_user_id, guest_owned,
                staff_maker_user_id, original_filename, file_size_bytes, content_type,
                cloudinary_url, upload_status, sort_order, is_cover, reused_from_listing,
                created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, 'fixture.jpg', 1, 'image/jpeg',
                      'https://example.test/fixture.jpg', 'UPLOADED', 0, false, false, now(), now())
            """;

    private Connection connection;
    private Long userId;
    private Long otherUserId;
    private Long lessorProfileId;

    @BeforeAll
    void connectAsApprovedMigrator() throws Exception {
        FreshIntegrationTarget.Target target = FreshIntegrationTarget.requireFromEnvironment(
                FreshIntegrationTarget.Stage.INTEGRATION,
                FreshIntegrationTarget.Cohort.BUSINESS,
                FreshIntegrationTarget.MIGRATOR_ROLE);
        connection = DriverManager.getConnection(
                target.jdbcUrl(), target.role(), target.password());
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("set search_path to public, it_extensions, pg_catalog");
        }
        userId = firstId("select id from public.users order by id");
        otherUserId = firstId("select id from public.users where id <> " + userId
                + " order by id");
        lessorProfileId = firstId("select id from public.lessor_profiles order by id");
    }

    @AfterAll
    void close() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void v60ConstraintSourceAndV59MediaArtifactsArePresent() throws Exception {
        String draftConstraint = text("""
                select pg_get_constraintdef(oid) from pg_constraint
                 where conrelid='public.property_upload_drafts'::regclass
                   and conname='ck_draft_one_owner'
                """);
        String mediaConstraint = text("""
                select pg_get_constraintdef(oid) from pg_constraint
                 where conrelid='public.property_draft_media'::regclass
                   and conname='ck_media_one_owner'
                """);
        assertEquals(true, draftConstraint.contains("STAFF_DIRECT"));
        assertEquals(true, draftConstraint.contains("staff_maker_user_id"));
        assertEquals(true, draftConstraint.contains("IS DISTINCT FROM"));
        assertEquals(false, draftConstraint.contains(
                "revision_mode)::text = 'STAFF_DIRECT'"));
        assertEquals(true, mediaConstraint.contains("staff_maker_user_id"));
        assertEquals(1L, scalar("""
                select count(*) from pg_indexes where schemaname='public'
                   and indexname='idx_pdm_staff_draft_order'
                """));
        assertEquals(1L, scalar("""
                select count(*) from pg_constraint
                 where conrelid='public.property_draft_media'::regclass
                   and conname='fk_pdm_staff_maker'
                """));
    }

    @Test
    void draftOwnerPermutationsAreEnforcedWithNamedConstraints() throws Exception {
        Savepoint rollback = connection.setSavepoint();
        try {
            expectAccepted(DRAFT_INSERT, draft(
                    null, null, lessorProfileId, userId, "STAFF_DIRECT", null, null));
            expectAccepted(DRAFT_INSERT, draft(
                    "admin-owner", null, lessorProfileId, null, null, null, null));
            expectAccepted(DRAFT_INSERT, draft(
                    null, otherUserId, lessorProfileId, null, null, null, null));
            expectAccepted(DRAFT_INSERT, draft(
                    null, null, lessorProfileId, null, null, "a".repeat(64),
                    LocalDateTime.parse("2026-12-01T00:00:00")));

            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    null, null, lessorProfileId, null, null, null, null));
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    "admin-owner", null, lessorProfileId, userId, "STAFF_DIRECT", null, null));
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    null, otherUserId, lessorProfileId, userId, "STAFF_DIRECT", null, null));
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    null, null, lessorProfileId, userId, "STAFF_DIRECT", "b".repeat(64),
                    LocalDateTime.parse("2026-12-01T00:00:00")));
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    "admin-owner", otherUserId, lessorProfileId, null, null, null, null));
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    null, otherUserId, lessorProfileId, null, null, "c".repeat(64),
                    LocalDateTime.parse("2026-12-01T00:00:00")));
        } finally {
            connection.rollback(rollback);
        }
    }

    @Test
    void malformedModesAndGuestExpiryCannotEscapeTheCheck() throws Exception {
        Savepoint rollback = connection.setSavepoint();
        try {
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    null, null, lessorProfileId, null, "STAFF_DIRECT", null, null));
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    null, null, null, userId, "STAFF_DIRECT", null, null));
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    null, null, lessorProfileId, userId, null, null, null));
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    "admin-owner", null, lessorProfileId, null, "STAFF_DIRECT", null, null));
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    null, null, lessorProfileId, null, null, "d".repeat(64), null));
            expectAccepted(DRAFT_INSERT, draft(
                    null, null, lessorProfileId, null, "OTHER_LEGACY_MODE", "e".repeat(64),
                    LocalDateTime.parse("2026-12-01T00:00:00")));
        } finally {
            connection.rollback(rollback);
        }
    }

    @Test
    void mediaOwnerPermutationsAreEnforcedAndRepeatTheDraftMatrix() throws Exception {
        Savepoint rollback = connection.setSavepoint();
        try {
            String parent = insertStaffDraft();
            expectAccepted(MEDIA_INSERT, media(parent, null, null, false, userId));
            expectAccepted(MEDIA_INSERT, media(parent, "admin-owner", null, false, null));
            expectAccepted(MEDIA_INSERT, media(parent, null, otherUserId, false, null));
            expectAccepted(MEDIA_INSERT, media(parent, null, null, true, null));

            expectCheckViolation(MEDIA_INSERT, "ck_media_one_owner",
                    media(parent, null, null, false, null));
            expectCheckViolation(MEDIA_INSERT, "ck_media_one_owner",
                    media(parent, "admin-owner", null, false, userId));
            expectCheckViolation(MEDIA_INSERT, "ck_media_one_owner",
                    media(parent, null, otherUserId, false, userId));
            expectCheckViolation(MEDIA_INSERT, "ck_media_one_owner",
                    media(parent, null, null, true, userId));
            expectCheckViolation(MEDIA_INSERT, "ck_media_one_owner",
                    media(parent, "admin-owner", otherUserId, false, null));
            expectCheckViolation(MEDIA_INSERT, "ck_media_one_owner",
                    media(parent, "admin-owner", null, true, null));
        } finally {
            connection.rollback(rollback);
        }
    }

    @Test
    void permissiveV59MutantAcceptsNullModeAndRestoredV60RejectsIt() throws Exception {
        Savepoint rollback = connection.setSavepoint();
        try {
            insertStaffDraft();
            execute("""
                    alter table public.property_upload_drafts
                    drop constraint ck_draft_one_owner
                    """);
            execute("""
                    alter table public.property_upload_drafts
                    add constraint ck_draft_one_owner check (
                        (
                            staff_maker_user_id is not null
                            and lessor_profile_id is not null
                            and revision_mode = 'STAFF_DIRECT'
                            and admin_id is null
                            and landlord_user_id is null
                            and guest_token_hash is null
                            and guest_expires_at is null
                        )
                        or
                        (
                            staff_maker_user_id is null
                            and revision_mode is distinct from 'STAFF_DIRECT'
                            and (
                                (case when admin_id is not null then 1 else 0 end) +
                                (case when landlord_user_id is not null then 1 else 0 end) +
                                (case when guest_token_hash is not null then 1 else 0 end)
                            ) = 1
                            and (guest_token_hash is null or guest_expires_at is not null)
                        )
                    ) not valid
                    """);
            expectAccepted(DRAFT_INSERT, draft(
                    null, null, lessorProfileId, userId, null, null, null));

            execute("""
                    alter table public.property_upload_drafts
                    drop constraint ck_draft_one_owner
                    """);
            execute("""
                    alter table public.property_upload_drafts
                    add constraint ck_draft_one_owner check (
                        (
                            staff_maker_user_id is not null
                            and lessor_profile_id is not null
                            and revision_mode is not distinct from 'STAFF_DIRECT'
                            and admin_id is null
                            and landlord_user_id is null
                            and guest_token_hash is null
                            and guest_expires_at is null
                        )
                        or
                        (
                            staff_maker_user_id is null
                            and revision_mode is distinct from 'STAFF_DIRECT'
                            and (
                                (case when admin_id is not null then 1 else 0 end) +
                                (case when landlord_user_id is not null then 1 else 0 end) +
                                (case when guest_token_hash is not null then 1 else 0 end)
                            ) = 1
                            and (guest_token_hash is null or guest_expires_at is not null)
                        )
                    )
                    """);
            expectCheckViolation(DRAFT_INSERT, "ck_draft_one_owner", draft(
                    null, null, lessorProfileId, userId, null, null, null));
            expectAccepted(DRAFT_INSERT, draft(
                    null, null, lessorProfileId, userId, "STAFF_DIRECT", null, null));
        } finally {
            connection.rollback(rollback);
        }
    }

    private String insertStaffDraft() throws SQLException {
        return insertDraft(draft(
                null, null, lessorProfileId, userId, "STAFF_DIRECT", null, null));
    }

    private String insertDraft(Object[] values) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement(DRAFT_INSERT)) {
            bind(statement, values);
            statement.executeUpdate();
            return String.valueOf(values[0]);
        } catch (SQLException failure) {
            connection.rollback(savepoint);
            throw failure;
        }
    }

    private Object[] draft(String admin, Long landlord, Long lessor, Long staff,
                           String mode, String guestHash, Object guestExpiry) {
        return new Object[]{"draft-" + UUID.randomUUID(), admin, landlord, lessor, staff,
                mode, guestHash, guestExpiry};
    }

    private Object[] media(String parentDraftId, String admin, Long landlord,
                           boolean guestOwned, Long staff) {
        return new Object[]{"media-" + UUID.randomUUID(), parentDraftId, admin, landlord,
                guestOwned, staff};
    }

    private void expectAccepted(String sql, Object[] values) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            assertEquals(1, statement.executeUpdate());
            connection.rollback(savepoint);
        } catch (SQLException failure) {
            connection.rollback(savepoint);
            throw failure;
        }
    }

    private void expectCheckViolation(String sql, String constraint, Object[] values)
            throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    SQLException.class, statement::executeUpdate);
            assertEquals("23514", failure.getSQLState());
            assertNotNull(failure.getMessage());
            assertEquals(true, failure.getMessage().contains(constraint), failure.getMessage());
        } finally {
            connection.rollback(savepoint);
        }
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertEquals(true, rows.next());
            return rows.getLong(1);
        }
    }

    private Long firstId(String sql) throws SQLException {
        return scalar(sql);
    }

    private String text(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertEquals(true, rows.next());
            return rows.getString(1);
        }
    }
}
