package com.indore.pathome.spaces.entity;

import com.indore.pathome.spaces.support.A1A7IsolatedTarget;
import com.indore.pathome.spaces.support.A1A7SchemaHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = A1A7IsolatedTarget.TEST_FLAG, matches = "true")
class LeadPhoneOutboxV63PostgresTest {
    private static final String V63_RESOURCE =
            "db/migration/V63__lead_phone_outbox_delivered_state.sql";
    private static final List<String> SIX_STATES = List.of(
            "QUEUED", "DISPATCHING", "ACCEPTED", "FAILED", "UNKNOWN", "SUPPRESSED");
    private static final Pattern QUOTED_STATE = Pattern.compile("'([A-Z]+)'");
    private static final List<TableProjection> FIXTURE_TABLES = List.of(
            new TableProjection("users", "u.*", "users u",
                    "u.email in ('a1a7-upgrade-fixture@example.test',"
                            + "'a1a7-upgrade-lessor@example.test')", "u.id"),
            new TableProjection("lead_routing_queue", "q.*", "lead_routing_queue q",
                    "q.phone_number='+919700000001'", "q.id"),
            new TableProjection("lead_source_events", "s.*", "lead_source_events s",
                    "s.external_event_id='source-upgrade'", "s.id"),
            new TableProjection("lead_phone_challenges", "c.*", "lead_phone_challenges c",
                    "c.number_fingerprint='v63-matrix'", "c.id"),
            new TableProjection("lead_phone_delivery_outbox", "o.*",
                    "lead_phone_delivery_outbox o join lead_phone_challenges c "
                            + "on c.id=o.challenge_id",
                    "c.number_fingerprint='v63-matrix'", "o.id"),
            new TableProjection("property_visit_requests", "r.*", "property_visit_requests r",
                    "r.preferred_areas='V63 synthetic preservation'", "r.id"),
            new TableProjection("visit_sessions", "s.*", "visit_sessions s",
                    "s.area_name='V63 synthetic session'", "s.id"),
            new TableProjection("tenant_visit_entitlement_accounts", "a.*",
                    "tenant_visit_entitlement_accounts a",
                    "a.user_id=(select id from users where "
                            + "email='a1a7-upgrade-fixture@example.test')",
                    "a.id"),
            new TableProjection("visit_entitlement_ledger", "l.*",
                    "visit_entitlement_ledger l",
                    "l.idempotency_key='v63-entitlement'", "l.id"),
            new TableProjection("operational_audit_events", "e.*",
                    "operational_audit_events e",
                    "e.reason_code='V63_SYNTHETIC_PRESERVATION'", "e.id"),
            new TableProjection("lead_shortlists", "s.*", "lead_shortlists s",
                    "s.lead_id=(select id from lead_routing_queue "
                            + "where phone_number='+919700000001')",
                    "s.id"),
            new TableProjection("lead_shortlist_items", "i.*", "lead_shortlist_items i",
                    "i.shortlist_id=(select id from lead_shortlists where lead_id="
                            + "(select id from lead_routing_queue "
                            + "where phone_number='+919700000001'))",
                    "i.shortlist_id,i.listing_id,i.position"),
            new TableProjection("lead_visit_proposals", "p.*", "lead_visit_proposals p",
                    "p.id='33333333-3333-4333-8333-333333333333'", "p.id"),
            new TableProjection("lead_visit_links", "l.*", "lead_visit_links l",
                    "l.id='44444444-4444-4444-8444-444444444444'", "l.id"),
            new TableProjection("listings", "l.*", "listings l",
                    "l.title='Synthetic Upgrade Property'", "l.id"),
            new TableProjection("rental_details", "r.*", "rental_details r",
                    "r.id=(select id from listings "
                            + "where title='Synthetic Upgrade Property')", "r.id"));

    @Test
    void cleanV63LedgerAndConstraintAreExact() throws Exception {
        A1A7IsolatedTarget.Target target = target("n2_v63_clean");
        A1A7SchemaHarness.migrate(target, 63);
        A1A7SchemaHarness.verifyComplete(target);

        try (Connection connection = target.open()) {
            assertEquals(1L, scalarLong(connection,
                    "select count(*) from flyway_schema_history "
                            + "where version='1' and success"));
            assertEquals(62L, scalarLong(connection,
                    "select count(*) from flyway_schema_history "
                            + "where success and version::integer between 2 and 63"));
            assertEquals(63L, scalarLong(connection,
                    "select max(version::integer) from flyway_schema_history where success"));
            assertEquals(0L, scalarLong(connection,
                    "select count(*) from flyway_schema_history "
                            + "where version is not null and version::integer > 63"));
            assertEquals(sevenStates(), checkedStates(connection));
        }
    }

    @Test
    void populatedV62UpgradePreservesRowsAndLinkedFacts() throws Exception {
        A1A7IsolatedTarget.Target target = target("n2_v63_preserve");
        seedHistoricalBase(target);
        seedLinkedVisitFacts(target);
        seedOutboxMatrix(target);
        FixtureSnapshot before = fixtureSnapshot(target);
        writeProjectionInventory(target);

        A1A7SchemaHarness.migrate(target, 63);

        FixtureSnapshot after = fixtureSnapshot(target);
        assertFixtureEqual(before, after);
        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            assertEquals(sevenStates(), checkedStates(connection));
            assertEquals(1L, scalarLong(connection,
                    "select count(*) from flyway_schema_history "
                            + "where version='63' and success"));
            assertEquals(6L, scalarLong(connection,
                    "select count(*) from lead_phone_delivery_outbox o "
                            + "join lead_phone_challenges c on c.id=o.challenge_id "
                            + "where c.number_fingerprint='v63-matrix'"));
            assertEquals(1L, scalarLong(connection,
                    "select count(*) from lead_phone_challenges "
                            + "where number_fingerprint='v63-matrix' and state='VERIFIED'"));
        }
    }

    @Test
    void deliveredIsRejectedBeforeV63AndPersistsAfterV63() throws Exception {
        A1A7IsolatedTarget.Target target = target("n2_v63_constraint");
        seedHistoricalBase(target);
        UUID challengeId = seedChallenge(target, 7, "v63-delivered");
        UUID queuedId = UUID.randomUUID();

        try (Connection connection = target.open()) {
            assertSqlState("23514", () -> insertOutbox(connection, challengeId,
                    "DELIVERED", 0));
            insertOutboxWithId(connection, queuedId, challengeId, "QUEUED", 0);
            assertSqlState("23514", () -> runnerUpdate(connection,
                    "update lead_phone_delivery_outbox set state='DELIVERED' "
                            + "where id=?::uuid", queuedId.toString()));
            assertFalse(checkedStates(connection).contains("DELIVERED"));
        }

        A1A7SchemaHarness.migrate(target, 63);

        UUID insertChallenge = seedChallenge(target, 9, "v63-delivered-insert");
        try (Connection connection = target.open()) {
            connection.setAutoCommit(false);
            insertOutbox(connection, insertChallenge, "DELIVERED", 0);
            execute(connection, "update lead_phone_delivery_outbox "
                    + "set state='DELIVERED' where id=?::uuid", queuedId.toString());
            connection.commit();
        }
        try (Connection fresh = target.open()) {
            assertEquals(2L, scalarLong(fresh,
                    "select count(*) from lead_phone_delivery_outbox "
                            + "where challenge_id in (?::uuid,?::uuid) "
                            + "and state='DELIVERED'",
                    challengeId.toString(), insertChallenge.toString()));
            assertEquals(sevenStates(), checkedStates(fresh));
            UUID invalidChallenge = seedChallenge(target, 10, "v63-invalid-state");
            assertSqlState("23514", () -> insertOutbox(fresh, invalidChallenge,
                    "", 0));
            assertSqlState("23514", () -> insertOutbox(fresh, invalidChallenge,
                    "delivered", 0));
            assertSqlState("23514", () -> insertOutbox(fresh, invalidChallenge,
                    "RANDOM", 0));
            assertSqlState("23502", () -> insertOutbox(fresh, invalidChallenge,
                    null, 0));
            assertSqlState("23505", () -> insertOutbox(fresh, insertChallenge,
                    "ACCEPTED", 1));
            UUID missingChallenge = UUID.randomUUID();
            assertSqlState("23503", () -> insertOutbox(fresh, missingChallenge,
                    "ACCEPTED", 0));
            UUID attemptsChallenge = seedChallenge(target, 8, "v63-attempts");
            assertSqlState("23514", () -> insertOutbox(fresh, attemptsChallenge,
                    "ACCEPTED", 6));
            assertEquals(1L, scalarLong(fresh,
                    "select count(*) from pg_constraint c "
                            + "join pg_class r on r.oid=c.conrelid "
                            + "join pg_namespace n on n.oid=r.relnamespace "
                            + "where c.conname='ck_lead_phone_outbox_attempts' "
                            + "and n.nspname=current_schema()"));
            assertEquals(1L, scalarLong(fresh,
                    "select count(*) from pg_indexes where schemaname=current_schema() "
                            + "and indexname='uk_lead_phone_outbox_challenge'"));
        }
    }

    @Test
    void injectedMigrationFailureRollsBackConstraintAndRows() throws Exception {
        A1A7IsolatedTarget.Target target = target("n2_v63_rollback");
        seedHistoricalBase(target);
        seedLinkedVisitFacts(target);
        seedOutboxMatrix(target);
        FixtureSnapshot before = fixtureSnapshot(target);
        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            connection.setAutoCommit(false);
            try {
                applyV63(connection, target.schema());
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            DO $$ BEGIN
                                RAISE EXCEPTION 'V63_SYNTHETIC_FAILURE';
                            END $$;
                            """);
                }
            } catch (SQLException expected) {
                connection.rollback();
            }
        }
        FixtureSnapshot after = fixtureSnapshot(target);
        assertFixtureEqual(before, after);
        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            assertEquals(SIX_STATES, checkedStates(connection));
            assertEquals(0L, scalarLong(connection,
                    "select count(*) from flyway_schema_history "
                            + "where version='63' and success"));
        }
    }

    @Test
    void boundedDdlLockTimeoutLeavesSchemaUnchangedThenRetriesCleanly() throws Exception {
        A1A7IsolatedTarget.Target target = target("n2_v63_lock_timeout");
        seedHistoricalBase(target);
        seedLinkedVisitFacts(target);
        seedOutboxMatrix(target);
        FixtureSnapshot before = fixtureSnapshot(target);

        AtomicLong waiterPid = new AtomicLong();
        AtomicReference<String> witness = new AtomicReference<>();
        CompletableFuture<SQLException> ddlFailure;
        try (Connection blocker = target.open()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.execute("lock table lead_phone_delivery_outbox "
                        + "in access exclusive mode");
            }
            long blockerPid = scalarLong(blocker, "select pg_backend_pid()");
            ddlFailure = CompletableFuture.supplyAsync(() -> {
                try (Connection ddl = target.open()) {
                    ddl.setAutoCommit(false);
                    try (Statement statement = ddl.createStatement()) {
                        waiterPid.set(scalarLong(ddl, "select pg_backend_pid()"));
                        statement.execute("set local lock_timeout='750ms'");
                    }
                    applyV63(ddl, target.schema());
                    ddl.rollback();
                    return null;
                } catch (Exception exception) {
                    SQLException sqlException = rootSqlException(exception);
                    if (sqlException != null) return sqlException;
                    throw new RuntimeException(exception);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try (Connection observer = target.open()) {
                while (System.nanoTime() < deadline && witness.get() == null) {
                    String row = scalarString(observer,
                            "select pid || '|' || wait_event_type || '|' || wait_event "
                                    + "|| '|' || array_to_string(pg_blocking_pids(pid), ',') "
                                    + "from pg_stat_activity "
                                    + "where datname=current_database() "
                                    + "and wait_event_type='Lock' and ? = any(pg_blocking_pids(pid))",
                            blockerPid);
                    if (row != null) witness.set(row);
                }
            }
            assertNotNull(witness.get(), "DDL waiter did not block on PostgreSQL");
            SQLException failure = ddlFailure.get(10, TimeUnit.SECONDS);
            assertNotNull(failure, "DDL should time out while the lock is held");
            assertEquals("55P03", failure.getSQLState());
            blocker.rollback();
        }

        FixtureSnapshot afterTimeout = fixtureSnapshot(target);
        assertFixtureEqual(before, afterTimeout);
        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            assertEquals(SIX_STATES, checkedStates(connection));
            assertEquals(6L, scalarLong(connection,
                    "select count(*) from lead_phone_delivery_outbox o "
                            + "join lead_phone_challenges c on c.id=o.challenge_id "
                            + "where c.number_fingerprint='v63-matrix'"));
            assertEquals(0L, scalarLong(connection,
                    "select count(*) from flyway_schema_history "
                            + "where version='63' and success"));
        }
        A1A7SchemaHarness.migrate(target, 63);
        FixtureSnapshot afterRetry = fixtureSnapshot(target);
        assertFixtureEqual(before, afterRetry);
        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            assertEquals(sevenStates(), checkedStates(connection));
            assertEquals(1L, scalarLong(connection,
                    "select count(*) from flyway_schema_history "
                            + "where version='63' and success"));
        }
        System.out.println("V63_LOCK_WAIT=" + witness.get()
                + " waiter_pid=" + waiterPid.get() + " outcome=55P03 retry=PASS");
    }

    @Test
    void sameIdOutboxAndEntitlementCorruptionFailFullProjectionButNotIdOnly()
            throws Exception {
        A1A7IsolatedTarget.Target target = target("n2_v63_mutation_control");
        seedHistoricalBase(target);
        seedLinkedVisitFacts(target);
        seedOutboxMatrix(target);
        FixtureSnapshot baseline = fixtureSnapshot(target);

        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            connection.setAutoCommit(false);
            execute(connection, """
                    update lead_phone_delivery_outbox
                    set last_error_code='V63_SYNTHETIC_NON_ID_MUTATION'
                    where challenge_id=(
                        select id from lead_phone_challenges
                        where number_fingerprint='v63-matrix' order by id limit 1
                    )
                    """);
            FixtureSnapshot mutated = fixtureSnapshot(connection, target);
            assertFixtureMismatch(baseline, mutated, "outbox non-ID mutation");
            assertEquals(idProjection(baseline), idProjection(mutated),
                    "ID-only projection must remain blind to a non-ID mutation");
            connection.rollback();
        }
        assertFixtureEqual(baseline, fixtureSnapshot(target));

        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            connection.setAutoCommit(false);
            execute(connection, """
                    update tenant_visit_entitlement_accounts
                    set available_credits=available_credits+1
                    where user_id=(select id from users
                        where email='a1a7-upgrade-fixture@example.test')
                    """);
            FixtureSnapshot mutated = fixtureSnapshot(connection, target);
            assertFixtureMismatch(baseline, mutated, "entitlement non-ID mutation");
            assertEquals(idProjection(baseline), idProjection(mutated),
                    "ID-only projection must remain blind to entitlement corruption");
            connection.rollback();
        }
        assertFixtureEqual(baseline, fixtureSnapshot(target),
                "rolled-back synthetic corruption must remain uncommitted");
    }

    @Test
    void comparatorCoversNullEmptyOrderingMissingDuplicateAndTypedPrecision()
            throws Exception {
        A1A7IsolatedTarget.Target typedTarget = target("n2_v63_typed_comparator");
        A1A7SchemaHarness.migrate(typedTarget, 63);
        try (Connection utc = typedTarget.open();
             Connection kolkata = typedTarget.open()) {
            setSearchPath(utc, typedTarget.schema());
            setSearchPath(kolkata, typedTarget.schema());
            execute(utc, "set timezone='UTC'");
            execute(kolkata, "set timezone='Asia/Kolkata'");
            String projection = """
                    select '2026-10-28 12:34:56.123456+05:30'::timestamptz as instant_value,
                           '2026-10-28 12:34:56.123456'::timestamp as local_value,
                           12.3400::numeric(10,4) as scaled_value,
                           '\\x00ff10'::bytea as binary_value,
                           '{"z":1,"a":[true,null,"x"]}'::jsonb as json_value,
                           array['first',null,'third']::text[] as array_value,
                           '11111111-2222-4333-8444-555555555555'::uuid as uuid_value,
                           null::text as null_value
                    """;
            ProjectionSnapshot utcSnapshot = typedProjectionSnapshot(
                    utc, "typed-boundary", projection, "instant_value");
            ProjectionSnapshot kolkataSnapshot = typedProjectionSnapshot(
                    kolkata, "typed-boundary", projection, "instant_value");
            assertEquals(utcSnapshot, kolkataSnapshot,
                    "typed comparator must canonicalize timestamptz instants across zones");
            assertEquals(1, utcSnapshot.rows().size());
            assertTrue(utcSnapshot.rows().get(0).values().stream()
                    .anyMatch(cell -> cell.value().value() == null));

            ProjectionSnapshot empty = typedProjectionSnapshot(utc, "empty-boundary",
                    "select 1::integer as value where false", "value");
            assertEquals(0, empty.rows().size());
        }

        A1A7IsolatedTarget.Target fixtureTarget = target("n2_v63_comparator_fixture");
        seedHistoricalBase(fixtureTarget);
        seedLinkedVisitFacts(fixtureTarget);
        seedOutboxMatrix(fixtureTarget);
        FixtureSnapshot baseline = fixtureSnapshot(fixtureTarget);
        try (Connection connection = fixtureTarget.open()) {
            setSearchPath(connection, fixtureTarget.schema());
            connection.setAutoCommit(false);
            execute(connection, "delete from lead_phone_delivery_outbox where id=("
                    + "select o.id from lead_phone_delivery_outbox o "
                    + "join lead_phone_challenges c on c.id=o.challenge_id "
                    + "where c.number_fingerprint='v63-matrix' order by o.id limit 1)");
            assertFixtureMismatch(baseline, fixtureSnapshot(connection, fixtureTarget),
                    "missing outbox row");
            connection.rollback();
        }

        TableSnapshot outbox = baseline.tables().get("lead_phone_delivery_outbox");
        List<RowSnapshot> duplicated = new ArrayList<>(outbox.rows());
        duplicated.add(outbox.rows().get(0));
        TableSnapshot duplicateTable = new TableSnapshot(
                outbox.name(), duplicated);
        Map<String, TableSnapshot> duplicateTables =
                new LinkedHashMap<>(baseline.tables());
        duplicateTables.put(outbox.name(), duplicateTable);
        assertFixtureMismatch(baseline,
                new FixtureSnapshot(Map.copyOf(duplicateTables)),
                "duplicate outbox row");

        UUID extraChallenge = seedChallenge(fixtureTarget, 200, "v63-matrix");
        try (Connection connection = fixtureTarget.open()) {
            setSearchPath(connection, fixtureTarget.schema());
            insertOutbox(connection, extraChallenge, "QUEUED", 0);
        }
        assertFixtureMismatch(baseline, fixtureSnapshot(fixtureTarget),
                "extra outbox row");

        UUID missingSchema = UUID.randomUUID();
        String schema = "v63_missing_" + missingSchema.toString().replace("-", "")
                .substring(0, 12);
        assertThrows(IllegalStateException.class, () -> fixtureSnapshot(
                A1A7IsolatedTarget.require(A1A7IsolatedTarget.MIGRATOR_ROLE, schema)));
    }

    private static A1A7IsolatedTarget.Target target(String prefix) {
        return A1A7IsolatedTarget.require(A1A7IsolatedTarget.MIGRATOR_ROLE,
                prefix + "_" + UUID.randomUUID().toString().replace("-", "")
                        .substring(0, 12));
    }

    private static void seedHistoricalBase(A1A7IsolatedTarget.Target target) {
        A1A7SchemaHarness.migrate(target, 52);
        A1A7SchemaHarness.seedUpgrade52(target);
        A1A7SchemaHarness.migrate(target, 61);
        A1A7SchemaHarness.seedUpgrade61(target);
        A1A7SchemaHarness.migrate(target, 62);
    }

    private static void seedLinkedVisitFacts(A1A7IsolatedTarget.Target target)
            throws Exception {
        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            long tenantId = scalarLong(connection,
                    "select id from users where email='a1a7-upgrade-fixture@example.test'");
            long cityId = scalarLong(connection,
                    "select id from supported_cities where code='v62-upgrade'");
            long teamId = scalarLong(connection,
                    "select id from operating_teams where code='v62-upgrade-team'");
            long localityId = scalarLong(connection,
                    "select id from localities where city='Synthetic Upgrade City' limit 1");
            long sessionId = insertReturningLong(connection,
                    "insert into visit_sessions(tenant_id,status,city,area_name,"
                            + "canonical_locality_id,created_at,updated_at) "
                            + "values (?,'DRAFT','Synthetic Upgrade City',"
                            + "'V63 synthetic session',?,clock_timestamp(),clock_timestamp()) "
                            + "returning id",
                    tenantId, localityId);
            execute(connection,
                    "update property_visit_requests set session_id=?, "
                            + "preferred_areas='V63 synthetic preservation' "
                            + "where tenant_id=?",
                    sessionId, tenantId);
            long accountId = insertReturningLong(connection,
                    "insert into tenant_visit_entitlement_accounts("
                            + "user_id,available_credits,reserved_credits) "
                            + "values (?,5,1) returning id",
                    tenantId);
            execute(connection,
                    "insert into visit_entitlement_ledger(account_id,user_id,session_id,"
                            + "event_type,available_delta,reserved_delta,actor_user_id,"
                            + "reason_code,idempotency_key,occurred_at) "
                            + "values (?,?,?,'INITIAL_GRANT',5,0,?,?,?,clock_timestamp())",
                    accountId, tenantId, sessionId, tenantId,
                    "V63_SYNTHETIC_ENTITLEMENT", "v63-entitlement");
            execute(connection,
                    "insert into operational_audit_events(actor_kind,actor_user_id,"
                            + "action_code,target_type,target_id,city_id,team_id,"
                            + "operation_id,reason_code,details) "
                            + "values ('USER',?,'V63_PRESERVATION','LEAD',?,?,?,?,?,?::jsonb)",
                    tenantId, tenantId, cityId, teamId, "v63-preservation",
                    "V63_SYNTHETIC_PRESERVATION", "{\"synthetic\":true}");
        }
    }

    private static void seedOutboxMatrix(A1A7IsolatedTarget.Target target)
            throws Exception {
        for (int index = 0; index < SIX_STATES.size(); index++) {
            UUID challengeId = seedChallenge(target, index + 20, "v63-matrix");
            try (Connection connection = target.open()) {
                setSearchPath(connection, target.schema());
                insertOutbox(connection, challengeId, SIX_STATES.get(index),
                        "DISPATCHING".equals(SIX_STATES.get(index)) ? 1 : 0);
            }
        }
        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            execute(connection, """
                    update lead_phone_challenges
                    set state='VERIFIED',entry_method='OE_ENTERED',
                        verified_at=clock_timestamp(),version=version+1
                    where id=(
                        select id from lead_phone_challenges
                        where number_fingerprint='v63-matrix'
                        order by created_at,id limit 1
                    )
                    """);
        }
    }

    private static UUID seedChallenge(A1A7IsolatedTarget.Target target, int ordinal,
                                      String fingerprint) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            long leadId = scalarLong(connection,
                    "select id from lead_routing_queue "
                            + "where phone_number='+919700000001'");
            UUID sourceId = UUID.fromString(scalarString(connection,
                    "select id::text from lead_source_events "
                            + "where external_event_id='source-upgrade'"));
            long userId = scalarLong(connection,
                    "select id from users where email='a1a7-upgrade-fixture@example.test'");
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into lead_phone_challenges(
                        id,lead_id,source_event_id,purpose,number_fingerprint,
                        masked_destination,contact_phone_revision,generation,
                        context_digest,verification_digest,confirmation_token_hash,
                        state,delivery_state,delivery_provider,failed_attempts,
                        entry_method,created_by_user_id,created_at,expires_at,version)
                    values (?::uuid,?,?,'ENQUIRY_PHONE_POSSESSION',?,?, ?,?, ?,?,?,
                        'PENDING','QUEUED','SIMULATED',0,NULL,?,
                        clock_timestamp(),clock_timestamp()+interval '10 minutes',0)
                    """)) {
                statement.setString(1, id.toString());
                statement.setLong(2, leadId);
                statement.setObject(3, sourceId);
                statement.setString(4, fingerprint);
                statement.setString(5, "********" + String.format("%04d", ordinal));
                statement.setLong(6, ordinal);
                statement.setInt(7, ordinal);
                statement.setString(8, repeat(ordinal, 'a'));
                statement.setString(9, repeat(ordinal, 'b'));
                statement.setString(10, repeat(ordinal, 'c'));
                statement.setLong(11, userId);
                statement.executeUpdate();
            }
        }
        return id;
    }

    private static void insertOutbox(Connection connection, UUID challengeId,
                                     String state, int attempts) throws SQLException {
        insertOutboxWithId(connection, UUID.randomUUID(), challengeId, state, attempts);
    }

    private static void insertOutboxWithId(Connection connection, UUID outboxId,
                                           UUID challengeId, String state, int attempts)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into lead_phone_delivery_outbox(
                    id,challenge_id,destination_fingerprint,payload_ciphertext,payload_iv,
                    payload_key_version,provider_name,state,attempts,available_at,
                    lease_until,lease_token,provider_message_id,last_error_code,
                    created_at,updated_at)
                values (?::uuid,?::uuid,'v63-matrix','ciphertext','iv','v63','SIMULATED',?,
                    ?,clock_timestamp(),
                    case when ?='DISPATCHING' then clock_timestamp()+interval '1 hour' else null end,
                    case when ?='DISPATCHING' then gen_random_uuid() else null end,
                    null,null,clock_timestamp(),clock_timestamp())
                """)) {
            statement.setString(1, outboxId.toString());
            statement.setString(2, challengeId.toString());
            statement.setString(3, state);
            statement.setInt(4, attempts);
            statement.setString(5, state);
            statement.setString(6, state);
            statement.executeUpdate();
        }
    }

    private static void applyV63(Connection connection, String schema) throws Exception {
        setSearchPath(connection, schema);
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(V63_RESOURCE));
    }

    private static FixtureSnapshot fixtureSnapshot(A1A7IsolatedTarget.Target target)
            throws Exception {
        try (Connection connection = target.open()) {
            return fixtureSnapshot(connection, target);
        }
    }

    private static FixtureSnapshot fixtureSnapshot(Connection connection,
                                                    A1A7IsolatedTarget.Target target)
            throws Exception {
        setSearchPath(connection, target.schema());
        requireSnapshotIdentity(connection, target);
        Map<String, TableSnapshot> tables = new LinkedHashMap<>();
        for (TableProjection projection : FIXTURE_TABLES) {
            String sql = "select " + projection.selectSql()
                    + " from " + projection.fromSql()
                    + " where " + projection.whereSql()
                    + " order by " + projection.orderSql();
            tables.put(projection.name(),
                    new TableSnapshot(projection.name(), queryRows(connection, sql)));
        }
        return new FixtureSnapshot(Map.copyOf(tables));
    }

    private static ProjectionSnapshot typedProjectionSnapshot(
            Connection connection, String name, String sql, String orderBy) throws Exception {
        return new ProjectionSnapshot(name,
                queryRows(connection, "select * from (" + sql + ") projection order by " + orderBy));
    }

    private static List<RowSnapshot> queryRows(Connection connection, String sql)
            throws Exception {
        List<RowSnapshot> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            ResultSetMetaData metadata = result.getMetaData();
            while (result.next()) {
                List<CellSnapshot> cells = new ArrayList<>(metadata.getColumnCount());
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    String typeName = metadata.getColumnTypeName(column);
                    int precision = metadata.getPrecision(column);
                    int scale = metadata.getScale(column);
                    Object raw = result.getObject(column);
                    Object value = canonicalValue(raw, typeName);
                    cells.add(new CellSnapshot(metadata.getColumnLabel(column),
                            new TypedValue(typeName,
                                    precision > 0 ? precision : null,
                                    scale >= 0 ? scale : null,
                                    value)));
                }
                rows.add(new RowSnapshot(List.copyOf(cells)));
            }
        }
        return List.copyOf(rows);
    }

    private static Object canonicalValue(Object raw, String typeName) throws SQLException {
        if (raw == null) return null;
        if (raw instanceof byte[] bytes) return HexFormat.of().formatHex(bytes);
        if (raw instanceof Array array) {
            Object values = array.getArray();
            if (values instanceof Object[] elements) {
                List<Object> canonical = new ArrayList<>(elements.length);
                for (Object element : elements) {
                    canonical.add(canonicalValue(element, typeName));
                }
                return Collections.unmodifiableList(canonical);
            }
            return values;
        }
        if (raw instanceof org.postgresql.util.PGobject pgObject) {
            return pgObject.getType() + ":" + pgObject.getValue();
        }
        if (raw instanceof OffsetDateTime value) return value.toInstant().toString();
        if (raw instanceof ZonedDateTime value) return value.toInstant().toString();
        if (raw instanceof Instant value) return value.toString();
        if (raw instanceof java.sql.Timestamp value) return value.toInstant().toString();
        if (raw instanceof LocalDateTime value) return value.toString();
        if (raw instanceof LocalDate value) return value.toString();
        if (raw instanceof BigDecimal || raw instanceof BigInteger
                || raw instanceof Number || raw instanceof Boolean
                || raw instanceof String || raw instanceof UUID) {
            return raw;
        }
        return typeName + ":" + raw;
    }

    private static void requireSnapshotIdentity(Connection connection,
                                                A1A7IsolatedTarget.Target target)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select current_database(),current_user,current_schema()");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()
                    || !A1A7IsolatedTarget.DATABASE.equals(rows.getString(1))
                    || !A1A7IsolatedTarget.MIGRATOR_ROLE.equals(rows.getString(2))
                    || !target.schema().equals(rows.getString(3))) {
                throw new IllegalStateException(
                        "snapshot database/role/schema identity mismatch");
            }
        }
    }

    private static void assertFixtureEqual(FixtureSnapshot expected, FixtureSnapshot actual) {
        assertFixtureEqual(expected, actual, "fixture preservation");
    }

    private static void assertFixtureEqual(FixtureSnapshot expected, FixtureSnapshot actual,
                                           String label) {
        for (String table : expected.tables().keySet()) {
            TableSnapshot before = expected.tables().get(table);
            TableSnapshot after = actual.tables().get(table);
            if (after == null) throw mismatch(label + " missing table " + table);
            if (before.rows().size() != after.rows().size()) {
                throw mismatch(label + " row-count mismatch table=" + table
                        + " expected=" + before.rows().size()
                        + " actual=" + after.rows().size());
            }
            for (int row = 0; row < before.rows().size(); row++) {
                List<CellSnapshot> beforeCells = before.rows().get(row).values();
                List<CellSnapshot> afterCells = after.rows().get(row).values();
                if (beforeCells.size() != afterCells.size()) {
                    throw mismatch(label + " column-count mismatch table=" + table
                            + " row=" + row);
                }
                for (int column = 0; column < beforeCells.size(); column++) {
                    if (!beforeCells.get(column).equals(afterCells.get(column))) {
                        throw mismatch(label + " value mismatch table=" + table
                                + " row=" + row + " column="
                                + beforeCells.get(column).column());
                    }
                }
            }
        }
        for (String table : actual.tables().keySet()) {
            if (!expected.tables().containsKey(table)) {
                throw mismatch(label + " unexpected table " + table);
            }
        }
    }

    private static void assertFixtureMismatch(FixtureSnapshot expected, FixtureSnapshot actual,
                                              String label) {
        AssertionError mismatch = assertThrows(AssertionError.class,
                () -> assertFixtureEqual(expected, actual, label));
        assertTrue(mismatch.getMessage().contains(label), mismatch.getMessage());
    }

    private static AssertionError mismatch(String message) {
        return new AssertionError("REDACTED_FIXTURE_MISMATCH: " + message);
    }

    private static Map<String, List<List<Object>>> idProjection(FixtureSnapshot snapshot) {
        Map<String, List<List<Object>>> result = new LinkedHashMap<>();
        for (TableSnapshot table : snapshot.tables().values()) {
            List<List<Object>> rows = new ArrayList<>();
            for (RowSnapshot row : table.rows()) {
                List<Object> identities = new ArrayList<>();
                for (CellSnapshot cell : row.values()) {
                    if ("id".equals(cell.column()) || cell.column().endsWith("_id")) {
                        identities.add(cell.value().value());
                    }
                }
                rows.add(Collections.unmodifiableList(identities));
            }
            result.put(table.name(), List.copyOf(rows));
        }
        return Map.copyOf(result);
    }

    private static void writeProjectionInventory(A1A7IsolatedTarget.Target target)
            throws Exception {
        StringBuilder inventory = new StringBuilder(
                "# V63 complete persisted-column projection inventory\n");
        inventory.append("database=").append(A1A7IsolatedTarget.DATABASE)
                .append(" schema=").append(target.schema()).append('\n');
        try (Connection connection = target.open()) {
            setSearchPath(connection, target.schema());
            requireSnapshotIdentity(connection, target);
            for (TableProjection projection : FIXTURE_TABLES) {
                inventory.append("\n[").append(projection.name()).append("]\n");
                try (PreparedStatement statement = connection.prepareStatement("""
                        select column_name,data_type,udt_name,is_nullable,
                               numeric_precision,numeric_scale,datetime_precision
                        from information_schema.columns
                        where table_schema=current_schema() and table_name=?
                        order by ordinal_position
                        """)) {
                    statement.setString(1, projection.name());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            inventory.append(rows.getString(1)).append('|')
                                    .append(rows.getString(2)).append('|')
                                    .append(rows.getString(3)).append('|')
                                    .append(rows.getString(4)).append('|')
                                    .append(rows.getString(5)).append('|')
                                    .append(rows.getString(6)).append('|')
                                    .append(rows.getString(7)).append('\n');
                        }
                    }
                }
            }
        }
        Path tests = Path.of(A1A7IsolatedTarget.evidenceDirectory()).resolve("tests");
        Files.createDirectories(tests);
        Files.writeString(tests.resolve("v63-fixture-projection-inventory.txt"),
                inventory.toString());
    }

    private static List<String> checkedStates(Connection connection) throws SQLException {
        String definition = scalarString(connection,
                "select pg_get_constraintdef(c.oid) from pg_constraint c "
                        + "join pg_class r on r.oid=c.conrelid "
                        + "join pg_namespace n on n.oid=r.relnamespace "
                        + "where c.conname='ck_lead_phone_outbox_state' "
                        + "and n.nspname=current_schema()");
        Matcher matcher = QUOTED_STATE.matcher(definition);
        List<String> states = new java.util.ArrayList<>();
        while (matcher.find()) states.add(matcher.group(1));
        return states;
    }

    private static List<String> sevenStates() {
        return List.of("QUEUED", "DISPATCHING", "ACCEPTED", "DELIVERED",
                "FAILED", "UNKNOWN", "SUPPRESSED");
    }

    private static void assertSqlState(String expected, SqlOperation operation)
            throws Exception {
        SQLException failure = assertThrows(SQLException.class, operation::run);
        assertEquals(expected, failure.getSQLState(), failure.getMessage());
    }

    private static SQLException rootSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) return sqlException;
            current = current.getCause();
        }
        return null;
    }

    private static void runnerUpdate(Connection connection, String sql, Object... values)
            throws SQLException {
        execute(connection, sql, values);
    }

    private static void execute(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static long insertReturningLong(Connection connection, String sql,
                                            Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("insert returned no row");
                return rows.getLong(1);
            }
        }
    }

    private static long scalarLong(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new AssertionError("scalar query returned no row");
                return rows.getLong(1);
            }
        }
    }

    private static String scalarString(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return rows.getString(1);
            }
        }
    }

    private static void setSearchPath(Connection connection, String schema)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema + ",it_extensions,pg_catalog");
        }
    }

    private static String repeat(int ordinal, char value) {
        return String.valueOf(value).repeat(40).substring(0, 36)
                + String.format("%04d", ordinal);
    }

    private record TableProjection(String name, String selectSql, String fromSql,
                                   String whereSql, String orderSql) {}
    private record TypedValue(String typeName, Integer precision, Integer scale, Object value) {}
    private record CellSnapshot(String column, TypedValue value) {}
    private record RowSnapshot(List<CellSnapshot> values) {}
    private record TableSnapshot(String name, List<RowSnapshot> rows) {}
    private record ProjectionSnapshot(String name, List<RowSnapshot> rows) {}
    private record FixtureSnapshot(Map<String, TableSnapshot> tables) {}

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws Exception;
    }
}
