package com.indore.pathome.spaces.entity;

import com.indore.pathome.spaces.support.A1A7IsolatedTarget;
import com.indore.pathome.spaces.support.A1A7SchemaHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = A1A7IsolatedTarget.TEST_FLAG, matches = "true")
class A1A7MigrationPostgresTest {

    @Test
    void cleanV1ToV63MigrationHasExactLedgerAndSchemaObjects() throws Exception {
        A1A7IsolatedTarget.Target migrator =
                A1A7IsolatedTarget.require(
                        A1A7IsolatedTarget.MIGRATOR_ROLE, uniqueSchema("a1a7_migration_clean"));
        A1A7SchemaHarness.migrate(migrator, 63);
        A1A7SchemaHarness.verifyComplete(migrator);

        try (Connection connection = migrator.open()) {
            assertEquals(1L, scalar(connection,
                    "select count(*) from flyway_schema_history where version='1' and success"));
            assertEquals(62L, scalar(connection,
                    "select count(*) from flyway_schema_history where success "
                            + "and version::integer between 2 and 63"));
            assertEquals(0L, scalar(connection,
                    "select count(*) from flyway_schema_history where not success"));
            assertEquals(0L, scalar(connection,
                    "select count(*) from flyway_schema_history where version is not null "
                            + "and version::integer > 63"));
            assertEquals(1L, scalar(connection,
                    "select count(*) from pg_indexes where schemaname=? "
                            + "and indexname='uk_lead_phone_one_pending_generation'",
                    migrator.schema()));
            assertEquals(1L, scalar(connection,
                    "select count(*) from pg_constraint c "
                            + "join pg_class r on r.oid=c.conrelid "
                            + "join pg_namespace n on n.oid=r.relnamespace "
                            + "where c.conname='ck_lead_phone_failed_attempts' "
                            + "and n.nspname=?", migrator.schema()));
        }
    }

    @Test
    void isolatedUpgradePreservesV52SyntheticHistoricalRowsThroughV63() throws Exception {
        A1A7IsolatedTarget.Target migrator =
                A1A7IsolatedTarget.require(
                        A1A7IsolatedTarget.MIGRATOR_ROLE, uniqueSchema("a1a7_migration_upgrade"));
        A1A7SchemaHarness.migrate(migrator, 52);
        try (Connection connection = migrator.open()) {
            assertEquals(51L, scalar(connection,
                    "select count(*) from flyway_schema_history where success "
                            + "and version::integer between 2 and 52"));
            assertEquals(0L, scalar(connection,
                    "select count(*) from flyway_schema_history where version is not null "
                            + "and version::integer > 52"));
        }
        A1A7SchemaHarness.seedUpgrade52(migrator);
        A1A7SchemaHarness.migrate(migrator, 61);
        A1A7SchemaHarness.seedUpgrade61(migrator);
        A1A7SchemaHarness.migrate(migrator, 63);
        A1A7SchemaHarness.verifyComplete(migrator);
        try (Connection connection = migrator.open()) {
            assertEquals(1L, scalar(connection,
                    "select count(*) from lead_routing_queue "
                            + "where phone_number='+919700000001'"));
            assertEquals(1L, scalar(connection,
                    "select count(*) from lead_source_events "
                            + "where external_event_id='source-upgrade' "
                            + "and processing_state='PROCESSED'"));
            assertEquals(1L, scalar(connection,
                    "select count(*) from lead_visit_links "
                            + "where id='44444444-4444-4444-8444-444444444444' "
                            + "and listing_id is not null"));
            assertEquals(10L, scalar(connection,
                    "select count(*) from flyway_schema_history where success "
                            + "and version::integer between 54 and 63"));
            assertTrue(booleanScalar(connection,
                    "select to_regclass('lead_phone_challenges') is not null"));
            assertEquals(1L, scalar(connection,
                    "select count(*) from pg_constraint c "
                            + "join pg_class r on r.oid=c.conrelid "
                            + "join pg_namespace n on n.oid=r.relnamespace "
                            + "where c.conname='uk_lead_visit_link_lead_user_listing' "
                            + "and n.nspname=?", migrator.schema()));
        }
    }

    private static long scalar(Connection connection, String sql, Object... arguments)
            throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("scalar query returned no row");
                return rows.getLong(1);
            }
        }
    }

    private static boolean booleanScalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new AssertionError("boolean query returned no row");
            return rows.getBoolean(1);
        }
    }

    private static String uniqueSchema(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
