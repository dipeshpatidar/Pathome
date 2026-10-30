package com.indore.pathome.spaces.entity;

import com.indore.pathome.spaces.support.FreshIntegrationSchemaHarness;
import com.indore.pathome.spaces.support.FreshIntegrationTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Disabled Gate I migration launcher for the exact fresh cohorts.
 *
 * <p>It is never selected by Gate P commands. The historical V49 test remains unchanged and this
 * launcher refuses its environment flag before any manifest, secret, or JDBC operation.</p>
 */
@EnabledIfEnvironmentVariable(
        named = FreshIntegrationTarget.TEST_ENABLEMENT_FLAG, matches = "true")
class FreshIntegrationMigrationPostgresTest {
    @BeforeEach
    void refuseHistoricalFlywayCampaign() {
        assertFalse("true".equals(System.getenv("PATHOME_PACKAGE5_FLYWAY_TEST")),
                "the historical V49 campaign must never be inherited by the fresh V60 launcher");
    }

    @Test
    void cleanCohortRunsLegacyBaseline1ThroughV60AndValidates() throws Exception {
        FreshIntegrationTarget.Target target = FreshIntegrationTarget.requireFromEnvironment(
                FreshIntegrationTarget.Stage.INTEGRATION,
                FreshIntegrationTarget.Cohort.CLEAN);
        FreshIntegrationSchemaHarness.prepareClean(target);

        try (Connection connection = DriverManager.getConnection(
                target.jdbcUrl(), target.role(), target.password());
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(connection,
                    "select count(*) from public.flyway_schema_history where version='1' and success"),
                    "the explicit supported baseline must be version 1");
            assertEquals(59, scalar(connection,
                    "select count(*) from public.flyway_schema_history "
                            + "where success and version::integer between 2 and 60"),
                    "exactly V2-V60 must be applied");
            assertEquals(0, scalar(connection,
                    "select count(*) from public.flyway_schema_history "
                            + "where version is not null and version::integer > 60"),
                    "no migration above the approved ceiling may be applied");
            assertEquals(1, scalar(connection,
                    "select count(*) from pg_indexes "
                            + "where schemaname='public' "
                            + "and indexname='idx_rental_available_from'"),
                    "the unchanged V3 migration must create its public availability index");
            assertEquals(2, scalar(connection,
                    "select count(*) from pg_extension ext "
                            + "join pg_namespace nsp on nsp.oid=ext.extnamespace "
                            + "where nsp.nspname='it_extensions' "
                            + "and ext.extname in ('pg_trgm','btree_gist')"),
                    "prerequisite extensions must retain their approved namespace");
            assertV58Capabilities(connection);
            assertV60Ownership(connection);
            statement.execute("set search_path to public, it_extensions, pg_catalog");
        }
    }

    @Test
    void upgradeCohortRunsV1To54ThenOnlyV55To60() throws Exception {
        FreshIntegrationTarget.Target target = FreshIntegrationTarget.requireFromEnvironment(
                FreshIntegrationTarget.Stage.INTEGRATION,
                FreshIntegrationTarget.Cohort.UPGRADE);
        FreshIntegrationSchemaHarness.prepareUpgradeTo54(target);
        FreshIntegrationSchemaHarness.applyV55To60(target);

        try (Connection connection = DriverManager.getConnection(
                target.jdbcUrl(), target.role(), target.password())) {
            assertEquals(53, scalar(connection,
                    "select count(*) from public.flyway_schema_history "
                            + "where success and version::integer between 2 and 54"),
                    "V2-V54 must complete before the upgrade slice");
            assertEquals(6, scalar(connection,
                    "select count(*) from public.flyway_schema_history "
                            + "where success and version::integer between 55 and 60"),
                    "only V55-V60 may be applied by the upgrade slice");
            assertEquals(1, scalar(connection,
                    "select count(*) from pg_indexes "
                            + "where schemaname='public' "
                            + "and indexname='idx_publication_listing_time_id'"),
                    "V57 must create its exact activity index");
            assertV58Capabilities(connection);
            assertV60Ownership(connection);
        }
    }

    @Test
    void businessCohortRunsOnlyAsMigratorAndPublishesCompletionReceipt() throws Exception {
        FreshIntegrationTarget.Target target = FreshIntegrationTarget.requireFromEnvironment(
                FreshIntegrationTarget.Stage.INTEGRATION,
                FreshIntegrationTarget.Cohort.BUSINESS,
                FreshIntegrationTarget.MIGRATOR_ROLE);
        FreshIntegrationSchemaHarness.prepareBusiness(target);

        try (Connection connection = DriverManager.getConnection(
                target.jdbcUrl(), target.role(), target.password())) {
            assertEquals(59, scalar(connection,
                    "select count(*) from public.flyway_schema_history "
                            + "where success and version::integer between 2 and 60"),
                    "the business cohort must complete the same V60 chain");
            assertEquals(1, scalar(connection,
                    "select count(*) from pg_indexes "
                            + "where schemaname='public' "
                            + "and indexname='idx_publication_listing_time_id'"),
                    "the business cohort must carry the V57 activity index");
            assertV58Capabilities(connection);
            assertV60Ownership(connection);
        }
    }

    private static void assertV58Capabilities(Connection connection) throws Exception {
        String capability = scalarText(connection,
                "select pg_get_constraintdef(oid) from pg_constraint "
                        + "where conrelid='public.staff_access_grants'::regclass "
                        + "and conname='ck_staff_grants_capability'");
        String scope = scalarText(connection,
                "select pg_get_constraintdef(oid) from pg_constraint "
                        + "where conrelid='public.staff_access_grants'::regclass "
                        + "and conname='ck_staff_grants_capability_scope'");
        for (String value : new String[]{
                "PROPERTY_EDIT_PUBLISH", "PROPERTY_AUDIT_READ", "PROPERTY_LIFECYCLE"}) {
            if (!capability.contains(value) || !scope.contains(value)) {
                throw new AssertionError("V58 property capability is absent: " + value);
            }
        }
    }

    private static void assertV60Ownership(Connection connection) throws Exception {
        String draft = scalarText(connection,
                "select pg_get_constraintdef(oid) from pg_constraint "
                        + "where conrelid='public.property_upload_drafts'::regclass "
                        + "and conname='ck_draft_one_owner'");
        String media = scalarText(connection,
                "select pg_get_constraintdef(oid) from pg_constraint "
                        + "where conrelid='public.property_draft_media'::regclass "
                        + "and conname='ck_media_one_owner'");
        if (!draft.contains("STAFF_DIRECT") || !draft.contains("staff_maker_user_id")
                || !media.contains("staff_maker_user_id")) {
            throw new AssertionError("V59 staff-direct ownership constraints are absent");
        }
        if (!draft.contains("IS DISTINCT FROM")
                || draft.contains("revision_mode)::text = 'STAFF_DIRECT'")) {
            throw new AssertionError("V60 draft ownership CHECK is not SQL-null-safe");
        }
        assertEquals(1, scalar(connection,
                "select count(*) from pg_indexes where schemaname='public' "
                        + "and indexname='idx_pdm_staff_draft_order'"),
                "V59 must create its staff media ordering index");
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("migration verification query returned no row");
            }
            return rows.getLong(1);
        }
    }

    private static String scalarText(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("migration verification query returned no row");
            }
            return rows.getString(1);
        }
    }
}
