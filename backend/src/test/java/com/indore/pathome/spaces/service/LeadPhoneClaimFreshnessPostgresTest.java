package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadPhoneChallenge;
import com.indore.pathome.spaces.entity.LeadPhoneChallengeState;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryOutbox;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import com.indore.pathome.spaces.repository.LeadPhoneChallengeRepository;
import com.indore.pathome.spaces.repository.LeadPhoneDeliveryOutboxRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import com.indore.pathome.spaces.support.A1A7HarnessContextInitializer;
import com.indore.pathome.spaces.support.A1A7IsolatedTarget;
import com.indore.pathome.spaces.support.A1A7RuntimeConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.default_schema=${PATHOME_A1_A7_SCHEMA}",
        "spring.jpa.show-sql=false",
        "APP_FIRST_ADMIN_PROVISIONING_ENABLED=false",
        "app.jwt.secret=pathome-n2-v63-concurrency-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=pathome-n2-v63-visit-otp-secret-32-bytes",
        "pathome.crm.contact-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "pathome.crm.contact-encryption-key-id=n2-v63-primary",
        "pathome.crm.public-origin=https://pathome.test",
        "pathome.crm.meta-source-enabled=false",
        "pathome.crm.phone-verification-simulator-enabled=true",
        "pathome.crm.secure-cookies=false",
        "pathome.crm.phone-delivery-poll-delay-ms=3600000",
        "cloudinary.api-key=n2-v63-test-key",
        "cloudinary.api-secret=n2-v63-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000",
        "search.learning.autonomous.enabled=false"
})
@Import(A1A7RuntimeConfiguration.class)
@ContextConfiguration(initializers = A1A7HarnessContextInitializer.class)
@EnabledIfEnvironmentVariable(named = A1A7IsolatedTarget.TEST_FLAG, matches = "true")
class LeadPhoneClaimFreshnessPostgresTest {
    @Autowired private LeadPhoneDeliveryClaimService claims;
    @Autowired private LeadPhoneDeliveryCompletionService completions;
    @Autowired private LeadPhoneDeliveryOutboxRepository outboxes;
    @Autowired private LeadPhoneChallengeRepository challenges;
    @Autowired private LeadRoutingQueueRepository leads;
    @Autowired private LeadSourceEventRepository sources;
    @Autowired private DatabaseClock clock;
    @Autowired private LeadDataProtection protection;
    @Autowired private LeadPhoneDeliveryProvider provider;
    @Autowired private StaffAccessService staffAccess;
    @Autowired private OperationalSecurityGuards guards;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("a1a7RunnerJdbc") private JdbcTemplate runner;

    @BeforeEach
    void isolateDeliveryRows() {
        runner.update("""
                update lead_phone_delivery_outbox
                set state='SUPPRESSED', lease_token=null, lease_until=null,
                    payload_ciphertext='', payload_iv='', payload_key_version='PURGED',
                    updated_at=clock_timestamp()
                where state in ('QUEUED','DISPATCHING')
                """);
    }

    @ParameterizedTest
    @EnumSource(value = LeadPhoneChallengeState.class, names = {"PENDING", "VERIFIED"})
    void hydratedExpiredDispatchPreservesCompetingAcceptedCommit(
            LeadPhoneChallengeState proofState) throws Exception {
        World world = world("accepted-" + proofState.name().toLowerCase(),
                LeadPhoneDeliveryState.DISPATCHING, true);
        if (proofState == LeadPhoneChallengeState.VERIFIED) {
            runner.update("""
                    update lead_phone_challenges
                    set state='VERIFIED', delivery_state='QUEUED',
                        entry_method='OE_ENTERED', verified_at=clock_timestamp(),
                        version=version+1
                    where id=?::uuid
                    """, world.challengeId.toString());
        }

        RaceResult race = hydrateBlockAndComplete(world,
                LeadPhoneDeliveryState.ACCEPTED, null);

        assertNull(race.staleTicket());
        assertTrue(race.witness().contains("a_commit_completed_before_domain_locks=true"));
        assertEquals("ACCEPTED", text("select state from lead_phone_delivery_outbox "
                + "where id=?::uuid", world.outboxId.toString()));
        assertEquals(proofState == LeadPhoneChallengeState.VERIFIED
                        ? "QUEUED" : "ACCEPTED",
                text("select delivery_state from lead_phone_challenges "
                        + "where id=?::uuid", world.challengeId.toString()));
        assertEquals(proofState.name(), text("select state from lead_phone_challenges "
                + "where id=?::uuid", world.challengeId.toString()));
        assertEquals(1, integer("select attempts from lead_phone_delivery_outbox "
                + "where id=?::uuid", world.outboxId.toString()));
        assertEquals("provider-ACCEPTED", text(
                "select provider_message_id from lead_phone_delivery_outbox "
                        + "where id=?::uuid", world.outboxId.toString()));
        assertTrue(bool("select payload_ciphertext='' and payload_iv='' "
                + "from lead_phone_delivery_outbox where id=?::uuid",
                world.outboxId.toString()));
        if (proofState == LeadPhoneChallengeState.VERIFIED) {
            assertNotNull(text("select verified_at::text from lead_phone_challenges "
                    + "where id=?::uuid", world.challengeId.toString()));
        }
        writeWitness("N2-C01-C02-" + proofState,
                "stale_state=DISPATCHING\ncommitted=ACCEPTED\n"
                        + "challenge=" + proofState + "\n" + race.witness());
    }

    @ParameterizedTest
    @EnumSource(value = LeadPhoneDeliveryState.class, names = {
            "ACCEPTED", "DELIVERED", "FAILED", "UNKNOWN", "SUPPRESSED"
    })
    void hydratedExpiredDispatchPreservesEveryCommittedTerminalState(
            LeadPhoneDeliveryState terminal) throws Exception {
        World world = world("terminal-" + terminal.name().toLowerCase(),
                LeadPhoneDeliveryState.DISPATCHING, true);

        RaceResult race = hydrateBlockAndComplete(world, terminal, "SYNTHETIC_TERMINAL");

        assertNull(race.staleTicket());
        assertNotNull(race.witness());
        assertEquals(terminal.name(), text(
                "select state from lead_phone_delivery_outbox where id=?::uuid",
                world.outboxId.toString()));
        assertEquals(terminal.name(), text(
                "select delivery_state from lead_phone_challenges where id=?::uuid",
                world.challengeId.toString()));
        assertEquals(1, integer("select attempts from lead_phone_delivery_outbox "
                + "where id=?::uuid", world.outboxId.toString()));
        assertNull(text("select lease_token::text from lead_phone_delivery_outbox "
                + "where id=?::uuid", world.outboxId.toString()));
        assertTrue(bool("select payload_ciphertext='' and payload_iv='' "
                + "from lead_phone_delivery_outbox where id=?::uuid",
                world.outboxId.toString()));
        assertTrue(bool("select provider_message_id is not distinct from "
                        + "case when ?='SUPPRESSED' then null else 'provider-' || ? end "
                        + "from lead_phone_delivery_outbox where id=?::uuid",
                terminal.name(), terminal.name(), world.outboxId.toString()));
        writeWitness("N2-C03-" + terminal,
                "stale_state=DISPATCHING\ncommitted=" + terminal + "\n"
                        + race.witness());
    }

    @Test
    void hydratedExpiredDispatchPreservesCompetingActiveLease() throws Exception {
        World world = world("active-lease", LeadPhoneDeliveryState.DISPATCHING, true);
        UUID freshToken = UUID.randomUUID();
        RaceResult race = hydrateBlockAndUpdateActiveLease(world, freshToken);

        assertNull(race.staleTicket());
        assertNotNull(race.witness());
        assertEquals("DISPATCHING", text(
                "select state from lead_phone_delivery_outbox where id=?::uuid",
                world.outboxId.toString()));
        assertEquals(freshToken.toString(), text(
                "select lease_token::text from lead_phone_delivery_outbox where id=?::uuid",
                world.outboxId.toString()));
        assertEquals(2, integer("select attempts from lead_phone_delivery_outbox "
                + "where id=?::uuid", world.outboxId.toString()));
        assertEquals("PENDING", text("select state from lead_phone_challenges "
                + "where id=?::uuid", world.challengeId.toString()));
        assertEquals("QUEUED", text("select delivery_state from lead_phone_challenges "
                + "where id=?::uuid", world.challengeId.toString()));
        writeWitness("N2-C04",
                "stale_state=DISPATCHING\nfresh_state=DISPATCHING\n"
                        + "fresh_token=" + freshToken + "\n" + race.witness());
    }

    @Test
    void expiredLegacyQueuedLeaseAndMalformedLeaseFailClosed() throws Exception {
        World legacy = world("legacy-queued", LeadPhoneDeliveryState.QUEUED, true);
        assertNull(claims.claimNext());
        assertEquals("UNKNOWN", text(
                "select state from lead_phone_delivery_outbox where id=?::uuid",
                legacy.outboxId.toString()));
        assertTrue(bool("select payload_ciphertext='' and payload_iv='' "
                + "from lead_phone_delivery_outbox where id=?::uuid",
                legacy.outboxId.toString()));

        World malformed = world("malformed-lease",
                LeadPhoneDeliveryState.DISPATCHING, false);
        runner.update("update lead_phone_delivery_outbox set lease_until=null "
                + "where id=?::uuid", malformed.outboxId.toString());
        assertNull(claims.claimNext());
        assertEquals("DISPATCHING", text(
                "select state from lead_phone_delivery_outbox where id=?::uuid",
                malformed.outboxId.toString()));
        assertEquals("PENDING", text("select state from lead_phone_challenges "
                + "where id=?::uuid", malformed.challengeId.toString()));
    }

    @Test
    void missingOutboxAfterDueSelectionReturnsNoTicket() throws Exception {
        World world = world("missing-after-select", LeadPhoneDeliveryState.QUEUED, false);
        runner.update("delete from lead_phone_delivery_outbox where id=?::uuid",
                world.outboxId.toString());

        assertNull(claims.claimNext());
        assertEquals("PENDING", text("select state from lead_phone_challenges "
                + "where id=?::uuid", world.challengeId.toString()));
        assertEquals(0L, runner.queryForObject(
                "select count(*) from lead_phone_delivery_outbox where challenge_id=?::uuid",
                Long.class, world.challengeId.toString()));
    }

    @Test
    void hydratedClaimWaitsOnLeadDomainLockAndCapturesBlockerPids() throws Exception {
        World world = world("domain-lock", LeadPhoneDeliveryState.QUEUED, false);
        A1A7IsolatedTarget.Target runnerTarget =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.RUNNER_ROLE);
        AtomicLong blockerPid = new AtomicLong();
        CountDownLatch blockerReady = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        try (Connection claimant = runnerTarget.open();
             Connection holder = runnerTarget.open()) {
            claimant.setAutoCommit(false);
            setSearchPath(claimant, runnerTarget.schema());
            // B has hydrated the stale challenge/outbox before attempting its first domain lock.
            try (PreparedStatement statement = claimant.prepareStatement(
                    "select o.state,c.state from lead_phone_delivery_outbox o "
                            + "join lead_phone_challenges c on c.id=o.challenge_id "
                            + "where o.id=?::uuid")) {
                statement.setString(1, world.outboxId.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                }
            }
            AtomicLong waiterPid = new AtomicLong(scalarLong(claimant, "select pg_backend_pid()"));
            CompletableFuture<Void> blocker = CompletableFuture.runAsync(() -> {
                try {
                    holder.setAutoCommit(false);
                    setSearchPath(holder, runnerTarget.schema());
                    blockerPid.set(scalarLong(holder, "select pg_backend_pid()"));
                    try (PreparedStatement statement = holder.prepareStatement(
                            "select id from lead_routing_queue where id=? for update")) {
                        statement.setLong(1, world.leadId);
                        try (ResultSet rows = statement.executeQuery()) {
                            assertTrue(rows.next());
                        }
                    }
                    blockerReady.countDown();
                    releaseBlocker.await(20, TimeUnit.SECONDS);
                    holder.rollback();
                } catch (Throwable throwable) {
                    blockerReady.countDown();
                    if (throwable instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("domain blocker failed", throwable);
                }
            });
            assertTrue(blockerReady.await(10, TimeUnit.SECONDS));
            CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
                try (PreparedStatement statement = claimant.prepareStatement(
                        "select id from lead_routing_queue where id=? for update")) {
                    statement.setLong(1, world.leadId);
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                    }
                } catch (Exception exception) {
                    throw new IllegalStateException("domain-lock waiter failed", exception);
                }
            });
            String witness = awaitBlocked(blockerPid.get(), waiterPid, 10_000);
            releaseBlocker.countDown();
            blocker.get(20, TimeUnit.SECONDS);
            waiter.get(20, TimeUnit.SECONDS);
            claimant.rollback();
            assertTrue(witness.contains("blocker_pid=" + blockerPid.get()));
            assertTrue(witness.contains("waiter_pid=" + waiterPid.get()));
            writeWitness("N2-C06-domain-lock", witness);
        }
    }

    private RaceResult hydrateBlockAndComplete(World world,
                                               LeadPhoneDeliveryState result,
                                               String errorCode) throws Exception {
        return runHydratedRace(world, aPid -> {
            completions.complete(world.outboxId, result,
                    result == LeadPhoneDeliveryState.SUPPRESSED
                            ? null : "provider-" + result,
                    errorCode, world.attemptId);
        });
    }

    private RaceResult hydrateBlockAndUpdateActiveLease(World world, UUID freshToken)
            throws Exception {
        return runHydratedRace(world, aPid -> {
            entityManager.createNativeQuery("""
                    update lead_phone_delivery_outbox
                    set state='DISPATCHING',attempts=attempts+1,
                        lease_token=:token,
                        lease_until=clock_timestamp()+interval '45 seconds',
                        updated_at=clock_timestamp()
                    where id=:outbox
                    """)
                    .setParameter("token", freshToken)
                    .setParameter("outbox", world.outboxId)
                    .executeUpdate();
        });
    }

    private RaceResult runHydratedRace(World world, BlockingCommit update)
            throws Exception {
        AtomicLong aPid = new AtomicLong();
        AtomicReference<LeadPhoneDeliveryWorker.DispatchTicket> staleTicket =
                new AtomicReference<>();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        LeadPhoneChallengeRepository paused = pauseAfterChallengeHydration(challenges, () -> {
            CompletableFuture<Void> committed = CompletableFuture.runAsync(() ->
                    transactions.executeWithoutResult(status -> {
                        aPid.set(((Number) entityManager.createNativeQuery(
                                "select pg_backend_pid()").getSingleResult()).longValue());
                        update.run(aPid.get());
                    }));
            try {
                committed.get(20, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "A could not commit while B was paused after hydration", exception);
            }
        });
        transactions.executeWithoutResult(status ->
                staleTicket.set(claimService(paused).claimNext()));
        String witness = "a_pid=" + aPid.get()
                + " after_hydration_pause=true"
                + " a_commit_completed_before_domain_locks=true"
                + " observed_at=" + Instant.now();
        return new RaceResult(staleTicket.get(), witness, aPid.get());
    }

    private LeadPhoneDeliveryClaimService claimService(
            LeadPhoneChallengeRepository challengeRepository) {
        return new LeadPhoneDeliveryClaimService(outboxes, challengeRepository, leads, sources,
                clock, protection, provider, staffAccess, guards, mapper, entityManager);
    }

    private LeadPhoneChallengeRepository pauseAfterChallengeHydration(
            LeadPhoneChallengeRepository delegate,
            Runnable afterHydration) {
        return (LeadPhoneChallengeRepository) Proxy.newProxyInstance(
                LeadPhoneChallengeRepository.class.getClassLoader(),
                new Class<?>[]{LeadPhoneChallengeRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "PausedLeadPhoneChallengeRepository";
                            default -> method.invoke(delegate, arguments);
                        };
                    }
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if ("findById".equals(method.getName())) {
                            afterHydration.run();
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private String awaitBlocked(long blockerPid, AtomicLong waiterPid, long timeoutMillis)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        A1A7IsolatedTarget.Target observer =
                A1A7IsolatedTarget.requireFromEnvironment(A1A7IsolatedTarget.RUNNER_ROLE);
        try (Connection connection = observer.open();
             PreparedStatement statement = connection.prepareStatement(
                     "select pid || '|' || wait_event_type || '|' || wait_event || '|' || "
                             + "array_to_string(pg_blocking_pids(pid),',') "
                             + "from pg_stat_activity where datname=current_database() "
                             + "and wait_event_type='Lock' "
                             + "and ? = any(pg_blocking_pids(pid)) order by pid limit 1")) {
            while (System.nanoTime() < deadline) {
                statement.setLong(1, blockerPid);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        String observed = rows.getString(1);
                        waiterPid.set(Long.parseLong(observed.substring(0,
                                observed.indexOf('|'))));
                        return "blocker_pid=" + blockerPid + " waiter_pid=" + waiterPid.get()
                                + " observed=" + observed
                                + " observed_at=" + Instant.now();
                    }
                }
                Thread.sleep(50);
            }
        }
        String activity = runner.queryForObject("""
                select coalesce(string_agg(pid || '|' || state || '|' ||
                    coalesce(wait_event_type,'') || '|' || coalesce(wait_event,'') || '|' ||
                    array_to_string(pg_blocking_pids(pid),','),';'),'none')
                from pg_stat_activity where pid=? or pid=?
                """, String.class, waiterPid.get(), blockerPid);
        throw new AssertionError("Claimant did not wait on the PostgreSQL domain lock; "
                + "blocker_pid=" + blockerPid + " waiter_pid=" + waiterPid
                + " activity=" + activity);
    }

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new AssertionError("scalar query returned no row");
            return rows.getLong(1);
        }
    }

    private static void setSearchPath(Connection connection, String schema)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema + ",it_extensions,pg_catalog");
        }
    }

    private World world(String label, LeadPhoneDeliveryState state, boolean expiredLease) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long city = runner.queryForObject(
                "insert into supported_cities(code,display_name,active) values (?,?,true) "
                        + "returning id", Long.class, "n2-" + label + "-" + suffix,
                "Synthetic N2 " + label);
        long team = runner.queryForObject(
                "insert into operating_teams(city_id,code,display_name,active) "
                        + "values (?,?,?,true) returning id",
                Long.class, city, "n2-team-" + label + "-" + suffix,
                "Synthetic N2 Team " + label);
        long owner = runner.queryForObject(
                "insert into users(email,full_name,phone_number,role) "
                        + "values (?,?,?,'ROLE_TENANT') returning id",
                Long.class, "n2-" + suffix + "@example.test", "Synthetic N2 " + label,
                "+91988" + suffix.substring(0, 7));
        long lead = runner.queryForObject("""
                insert into lead_routing_queue(
                    tenant_name,phone_number,contact_phone_normalized,status,intent,
                    legacy_provenance,currency,supported_city_id,operating_team_id,owner_user_id)
                values ('Synthetic N2 Lead',? ,?,'QUALIFYING','RENT',
                        'VERIFIED_MANUAL','INR',?,?,?) returning id
                """, Long.class, "+91988" + suffix.substring(0, 7),
                "+91988" + suffix.substring(0, 7), city, team, owner);
        UUID source = runner.queryForObject("""
                insert into lead_source_events(
                    id,provider,provider_account_id,external_event_id,event_kind,payload_hash,
                    lead_id,processing_state,available_at,received_at,updated_at)
                values (gen_random_uuid(),'MANUAL','n2-v63','source-'||?,'MANUAL_PHONE',
                        repeat('a',64),?,
                        'PROCESSED',clock_timestamp(),clock_timestamp(),clock_timestamp())
                returning id
                """, UUID.class, suffix, lead);
        UUID challenge = UUID.randomUUID();
        String challengeHex = challenge.toString().replace("-", "");
        runner.update("""
                insert into lead_phone_challenges(
                    id,lead_id,source_event_id,purpose,number_fingerprint,masked_destination,
                    contact_phone_revision,generation,context_digest,verification_digest,
                    confirmation_token_hash,state,delivery_state,delivery_provider,
                    failed_attempts,entry_method,created_by_user_id,created_at,expires_at,version)
                values (?::uuid,?,?,'ENQUIRY_PHONE_POSSESSION',repeat('f',64),'********'||right(?,4),
                    0,1,?,?,?, 'PENDING','QUEUED','SIMULATED',0,null,?,
                    clock_timestamp(),clock_timestamp()+interval '10 minutes',0)
                """, challenge.toString(), lead, source, suffix,
                repeat('b'), repeat('c'), challengeHex + challengeHex, owner);
        UUID outbox = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        boolean hasLease = state == LeadPhoneDeliveryState.DISPATCHING
                || (state == LeadPhoneDeliveryState.QUEUED && expiredLease);
        String lease = !hasLease ? "null" : expiredLease
                ? "clock_timestamp()-interval '1 second'"
                : "clock_timestamp()+interval '45 seconds'";
        String outboxSql = """
                insert into lead_phone_delivery_outbox(
                    id,challenge_id,destination_fingerprint,payload_ciphertext,payload_iv,
                    payload_key_version,provider_name,state,attempts,available_at,
                    lease_until,lease_token,provider_message_id,last_error_code,created_at,updated_at)
                values (?::uuid,?::uuid,repeat('f',64),'ciphertext','iv','v1','SIMULATED',?,
                    ?,clock_timestamp()-interval '1 minute',%s,%s,null,null,
                    clock_timestamp(),clock_timestamp())
                """.formatted(lease, hasLease ? "?::uuid" : "null");
        if (hasLease) {
            runner.update(outboxSql, outbox.toString(), challenge.toString(), state.name(),
                    hasLease ? 1 : 0, attempt.toString());
        } else {
            runner.update(outboxSql, outbox.toString(), challenge.toString(), state.name(), 0);
        }
        return new World(city, team, owner, lead, source, challenge, outbox, attempt);
    }

    private String text(String sql, Object... values) {
        return runner.queryForObject(sql, String.class, values);
    }

    private int integer(String sql, Object... values) {
        Number value = runner.queryForObject(sql, Number.class, values);
        return value == null ? 0 : value.intValue();
    }

    private boolean bool(String sql, Object... values) {
        Boolean value = runner.queryForObject(sql, Boolean.class, values);
        return Boolean.TRUE.equals(value);
    }

    private static String repeat(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static void writeWitness(String id, String content) {
        try {
            Path directory = Path.of(A1A7IsolatedTarget.evidenceDirectory(), "lock-witnesses");
            Files.createDirectories(directory);
            Path target = directory.resolve(id + ".txt");
            Files.writeString(target, content + "\n", StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("lock witness could not be written", exception);
        }
    }

    private record World(long cityId, long teamId, long ownerId, long leadId, UUID sourceId,
                         UUID challengeId, UUID outboxId, UUID attemptId) {}
    private record RaceResult(LeadPhoneDeliveryWorker.DispatchTicket staleTicket,
                              String witness, long aPid) {}

    @FunctionalInterface
    private interface BlockingCommit {
        void run(long aPid);
    }
}
