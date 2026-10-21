package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand;
import com.indore.pathome.spaces.dto.TenantVisitAvailabilityCommand;
import com.indore.pathome.spaces.dto.TenantVisitAvailabilityView;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget;
import com.indore.pathome.spaces.support.DisposablePostgresTarget;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R1-OE-1 real PostgreSQL topology-retry proof.
 *
 * <p>The only test seam wraps the repository and pauses immediately after the real unlocked topology
 * discovery returns. A supported coordination transaction then commits before the old attempt locks,
 * proving the first attempt rolls back and a fresh attempt rediscovers Session→Request order. Production
 * classes are unchanged.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "server.address=127.0.0.1",
        "app.jwt.secret=tenant-topology-test-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=tenant-topology-test-otp-secret-32-bytes",
        "cloudinary.api-key=tenant-topology-test-key",
        "cloudinary.api-secret=tenant-topology-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@Import(TenantVisitAvailabilityTopologyPostgresTest.DiscoveryConfiguration.class)
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class TenantVisitAvailabilityTopologyPostgresTest {
    private static final String SCHEMA = "tenant_avail_topology_test_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static DisposablePostgresTarget.Verified verifiedTarget;
    private static boolean schemaCreated;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        verifiedTarget = DisposablePostgresTarget.verifyFromEnvironment();
        DisposableIntegrationTarget.Target target = verifiedTarget.target();
        try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), target.username(), target.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
            try {
                connection.setSchema(SCHEMA);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                flyway().target("40").load().migrate();
                flyway().load().migrate();
            } catch (Exception failure) {
                dropVerifiedSchema();
                throw failure;
            }
        }
        registry.add("spring.datasource.url", () -> verifiedTarget.target().schemaUrl(SCHEMA));
        registry.add("spring.datasource.username", () -> verifiedTarget.target().username());
        registry.add("spring.datasource.password", () -> verifiedTarget.target().password());
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(verifiedTarget.target().jdbcUrl(),
                        verifiedTarget.target().username(), verifiedTarget.target().password()).schemas(SCHEMA)
                .defaultSchema(SCHEMA).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        dropVerifiedSchema();
    }

    private static void dropVerifiedSchema() throws Exception {
        if (!schemaCreated || verifiedTarget == null) return;
        var current = DisposablePostgresTarget.verifyFromEnvironment();
        if (!current.runId().equals(verifiedTarget.runId()))
            throw new IllegalStateException("Refused cleanup: test-run identity changed");
        try (Connection connection = DriverManager.getConnection(verifiedTarget.target().jdbcUrl(),
                verifiedTarget.target().username(), verifiedTarget.target().password());
             Statement statement = connection.createStatement()) {
            try (PreparedStatement owner = connection.prepareStatement(
                    "select pg_get_userbyid(nspowner) = current_user from pg_namespace where nspname = ?")) {
                owner.setString(1, SCHEMA);
                try (var rows = owner.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1))
                        throw new IllegalStateException("Refused cleanup: schema ownership not verified");
                }
            }
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            schemaCreated = false;
        }
    }

    @Autowired private TenantVisitAvailabilityService availability;
    @Autowired private VisitOperationsService operations;
    @Autowired private OperationalOwnershipService ownership;
    @Autowired private UserRepository users;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private org.springframework.context.ApplicationContext context;

    @org.junit.jupiter.api.AfterEach
    void resetTestOnlyProbes() {
        AttemptProbe.reset();
        DiscoveryBarrier.reset();
    }

    @Test
    void postDiscoveryCoordinationCommitsAndTheFreshAttemptRediscoversCanonicalLocks() throws Exception {
        Fixture fixture = fixture("retry");
        PropertyVisitRequest request = request(fixture);
        TenantVisitAvailabilityCommand command = command(request.getVersion());
        var transactionBean = context.getBean(TenantVisitAvailabilityTransaction.class);
        Object transactionTarget = transactionBean;
        if (transactionBean instanceof org.springframework.aop.framework.Advised advised) {
            transactionTarget = advised.getTargetSource().getTarget();
        }
        var repositoryField = TenantVisitAvailabilityTransaction.class.getDeclaredField("requests");
        repositoryField.setAccessible(true);
        assertTrue(Proxy.isProxyClass(repositoryField.get(transactionTarget).getClass()),
                "the real service must receive the test-owned discovery interceptor");
        DiscoveryBarrier.arm(2);
        AtomicReference<TenantVisitAvailabilityView> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread update = new Thread(() -> {
            try {
                result.set(availability.update(fixture.tenant().getId(), request.getId(), command));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        update.setDaemon(true);
        update.start();

        DiscoveryBarrier.Gate first = DiscoveryBarrier.nextGate(15_000);
        assertNull(jdbc.queryForObject("select availability_start_at from property_visit_requests where id = ?",
                java.sql.Timestamp.class, request.getId()), "the paused attempt must not have written availability");
        var linked = operations.coordinateRequest(fixture.oe().getId(), request.getId(),
                new CoordinateVisitRequestCommand(request.getVersion(), null, null));
        assertNotNull(linked.sessionId());
        assertEquals(VisitRequestStatus.COORDINATING,
                requests.findById(request.getId()).orElseThrow().getStatusValue());
        DiscoveryBarrier.release(first);

        DiscoveryBarrier.Gate second = DiscoveryBarrier.nextGate(15_000);
        System.out.println("R1 topology evidence firstTx=" + first.transactionId()
                + " secondTx=" + second.transactionId() + " trace=" + DiscoveryBarrier.trace());
        assertNotEquals(first.transactionId(), second.transactionId(),
                "the retry must run in a genuinely fresh transaction");
        assertNull(jdbc.queryForObject("select availability_start_at from property_visit_requests where id = ?",
                java.sql.Timestamp.class, request.getId()),
                "the first locked attempt must have rolled back before rediscovery");
        try (Connection probe = DriverManager.getConnection(verifiedTarget.target().jdbcUrl(),
                verifiedTarget.target().username(), verifiedTarget.target().password())) {
            probe.setSchema(SCHEMA);
            probe.setAutoCommit(false);
            try (PreparedStatement lock = probe.prepareStatement(
                    "select id from property_visit_requests where id = ? for update nowait")) {
                lock.setLong(1, request.getId());
                lock.executeQuery().close();
                probe.rollback();
            }
        }
        DiscoveryBarrier.release(second);
        update.join(20_000);
        assertFalse(update.isAlive());
        System.out.println("R1 topology final trace=" + DiscoveryBarrier.trace());
        assertNotNull(failure.get(), "the linked retry must return a legal current-version refusal");
        assertInstanceOf(VisitOperationsConflictException.class, failure.get());
        assertTrue(failure.get().getMessage().contains("reload and retry"));
        assertNull(result.get());
        assertEquals(2, DiscoveryBarrier.discoveries.get(), "exactly two attempts were needed");
        assertNull(jdbc.queryForObject("select availability_start_at from property_visit_requests where id = ?",
                java.sql.Timestamp.class, request.getId()), "a version refusal must not partially mutate availability");
        String trace = DiscoveryBarrier.trace();
        assertTrue(trace.contains("topology:topologySession=null"), trace);
        assertTrue(trace.contains("topology:topologySession=1"), trace);
        assertTrue(trace.split("locked:requestSession=1", -1).length - 1 >= 2,
                "the coordinated link and the rediscovered retry must both take the locked Request root: " + trace);
        assertTrue(trace.indexOf("topology:topologySession=null")
                < trace.indexOf("topology:topologySession=1"), trace);
    }

    @Test
    void ambientTransactionIsRefusedBeforeRepositoryWorkAndOrdinaryErrorsAreNotRetried() throws Exception {
        Fixture fixture = fixture("ambient");
        PropertyVisitRequest request = request(fixture);
        TenantVisitAvailabilityCommand command = command(request.getVersion());
        DiscoveryBarrier.reset();
        TransactionTemplate template = new TransactionTemplate(transactions);
        IllegalTransactionStateException refused = assertThrows(IllegalTransactionStateException.class,
                () -> template.executeWithoutResult(status -> availability.update(
                        fixture.tenant().getId(), request.getId(), command)));
        String refusedMessage = refused.getMessage() == null ? "" : refused.getMessage().toLowerCase();
        assertTrue(refusedMessage.contains("never") || refusedMessage.contains("existing transaction"));
        assertEquals(0, DiscoveryBarrier.discoveries.get(),
                "Propagation.NEVER must refuse the actual Spring proxy before repository discovery");

        TenantVisitAvailabilityView normal = availability.update(
                fixture.tenant().getId(), request.getId(), command);
        assertNotNull(normal);
        assertEquals(1, DiscoveryBarrier.discoveries.get(), "the normal nonambient control attempts once");

        Fixture staleFixture = fixture("ambient-stale");
        PropertyVisitRequest stale = request(staleFixture);
        DiscoveryBarrier.reset();
        assertThrows(VisitOperationsConflictException.class,
                () -> availability.update(staleFixture.tenant().getId(), stale.getId(),
                        new TenantVisitAvailabilityCommand(stale.getVersion() + 100,
                                command.availabilityStartAt(), command.availabilityEndAt(),
                                command.availabilityZoneId(), command.preferredAt())));
        assertEquals(1, DiscoveryBarrier.discoveries.get(),
                "a version conflict is a legal refusal, not a topology retry");

        DiscoveryBarrier.reset();
        assertThrows(IllegalArgumentException.class,
                () -> availability.update(staleFixture.tenant().getId(), stale.getId(),
                        new TenantVisitAvailabilityCommand(stale.getVersion(),
                                command.availabilityStartAt(), command.availabilityEndAt(),
                                "Invalid/Zone", command.preferredAt())));
        assertEquals(1, DiscoveryBarrier.discoveries.get(),
                "validation failure must not be caught as a topology retry");

        DiscoveryBarrier.reset();
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> availability.update(fixture.tenant().getId(), stale.getId(), command(stale.getVersion())));
        assertEquals(1, DiscoveryBarrier.discoveries.get(), "foreign ownership must not be topology retried");

        User syntheticTenant = users.findById(staleFixture.tenant().getId()).orElseThrow();
        syntheticTenant.setRole(Role.ROLE_ADMIN);
        users.saveAndFlush(syntheticTenant);
        DiscoveryBarrier.reset();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> availability.update(syntheticTenant.getId(), stale.getId(), command(stale.getVersion())));
        assertEquals(1, DiscoveryBarrier.discoveries.get(), "persisted capability denial must not be topology retried");
    }

    @Test
    void defensiveTopologyFaultsRollbackFlushedWritesAndStopAtExactlyThreeAttempts() {
        // Deliberate test-only exceptions after real SQL flush, not fabricated legal relink workflows.
        for (int faults : new int[]{1, 2, 3}) {
            Fixture fixture = fixture("fault-" + faults);
            PropertyVisitRequest request = request(fixture);
            AttemptProbe.arm(Thread.currentThread(), faults, false);
            try {
                if (faults < 3) {
                    var result = availability.update(fixture.tenant().getId(), request.getId(), command(request.getVersion()));
                    assertEquals(request.getVersion() + 1, result.version());
                    assertNotNull(result.availabilityStartAt());
                } else {
                    assertThrows(VisitOperationsConflictException.class, () -> availability.update(
                            fixture.tenant().getId(), request.getId(), command(request.getVersion())));
                }
                int expected = Math.min(faults + 1, 3);
                assertEquals(expected, AttemptProbe.transactionIds.size(), "no fourth discovery/attempt");
                assertEquals(expected, AttemptProbe.flushes.get(), "each exception followed an actual write flush");
                assertEquals(expected, new java.util.HashSet<>(AttemptProbe.transactionIds).size(),
                        "every attempt must have a fresh PostgreSQL transaction identity");
                assertTrue(AttemptProbe.preAttemptVersions.stream().allMatch(v -> v.equals(request.getVersion())),
                        "failed attempts must roll back even their flushed version increments");
                PropertyVisitRequest saved = requests.findById(request.getId()).orElseThrow();
                assertEquals(request.getVersion() + (faults < 3 ? 1 : 0), saved.getVersion());
                assertEquals(faults < 3, saved.getAvailabilityStartAt() != null);
                System.out.println("R1 defensive topology faults=" + faults + " tx=" + AttemptProbe.transactionIds
                        + " preVersions=" + AttemptProbe.preAttemptVersions + " flushes=" + AttemptProbe.flushes);
            } finally { AttemptProbe.reset(); }
        }
    }

    @Test
    void actualLinkTransferAndCancellationSerializeWithTenantWrites() throws Exception {
        for (String kind : java.util.List.of("LINK", "TRANSFER", "CANCEL")) {
            for (boolean updateFirst : java.util.List.of(true, false)) {
                if (kind.equals("LINK") && !updateFirst) continue; // Existing real discovery/link retry test.
                Fixture fixture = fixture(kind.toLowerCase() + updateFirst);
                PropertyVisitRequest request = request(fixture);
                Long sessionId = kind.equals("CANCEL") ? operations.coordinateRequest(fixture.oe().getId(),
                        request.getId(), new CoordinateVisitRequestCommand(request.getVersion(), null, null)).sessionId() : null;
                PropertyVisitRequest opening = requests.findById(request.getId()).orElseThrow();
                Long sessionVersion = sessionId == null ? null : jdbc.queryForObject(
                        "select version from visit_sessions where id=?", Long.class, sessionId);
                User admin = users.saveAndFlush(user("race-admin", Role.ROLE_ADMIN, "Race Admin", null));
                User next = users.saveAndFlush(user("race-next", Role.ROLE_TENANT, "Race Next", null));
                for (User staff : java.util.List.of(admin, next)) {
                    EmployeeProfile profile = new EmployeeProfile(staff, "OPERATIONS", fixture.city().getDisplayName(), BigDecimal.ZERO);
                    profile.setStaffActive(true); employees.saveAndFlush(profile);
                }
                grants.saveAndFlush(new StaffAccessGrant(admin, StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL,
                        null, null, Instant.now().minusSeconds(60), null, null, Instant.now(), "R1_RACE", StaffGrantProvisioningSource.ADMIN_API));
                grants.saveAndFlush(new StaffAccessGrant(admin, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM,
                        null, fixture.team(), Instant.now().minusSeconds(60), null, null, Instant.now(), "R1_RACE", StaffGrantProvisioningSource.ADMIN_API));
                grants.saveAndFlush(new StaffAccessGrant(next, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM,
                        null, fixture.team(), Instant.now().minusSeconds(60), null, null, Instant.now(), "R1_RACE", StaffGrantProvisioningSource.ADMIN_API));
                AtomicReference<Throwable> updateFailure = new AtomicReference<>();
                AtomicReference<Throwable> mutationFailure = new AtomicReference<>();
                AtomicReference<TenantVisitAvailabilityView> answer = new AtomicReference<>();
                Hold mutationHold = new Hold();
                Thread update = new Thread(() -> {
                    try { answer.set(availability.update(fixture.tenant().getId(), request.getId(), command(opening.getVersion()))); }
                    catch (Throwable error) { updateFailure.set(error); }
                });
                Runnable mutate = () -> {
                    if (kind.equals("LINK")) operations.coordinateRequest(fixture.oe().getId(), request.getId(),
                            new CoordinateVisitRequestCommand(opening.getVersion(), null, null));
                    else if (kind.equals("TRANSFER")) ownership.assignRequestCoordinator(admin.getId(), request.getId(),
                            opening.getVersion(), null, null, next.getId(), "R1_RACE_TRANSFER");
                    else operations.cancel(admin.getId(), sessionId, sessionVersion);
                };
                Thread mutation = new Thread(() -> {
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(status -> {
                            mutate.run();
                            if (!updateFirst && !kind.equals("TRANSFER")) mutationHold.await(jdbc);
                        });
                    } catch (Throwable error) { mutationFailure.set(error); }
                });
                update.setDaemon(true); mutation.setDaemon(true);
                AttemptProbe.arm(update, 0, updateFirst);
                if (!updateFirst && kind.equals("TRANSFER")) {
                    AttemptProbe.mutationOwner = mutation;
                    AttemptProbe.mutationHold = mutationHold;
                }
                try {
                    (updateFirst ? update : mutation).start();
                    Hold first = updateFirst ? AttemptProbe.flushHold : mutationHold;
                    assertTrue(first.entered.await(10, TimeUnit.SECONDS), "the first real operation must reach its lock-held barrier");
                    (updateFirst ? mutation : update).start();
                    Long waiter = null;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (waiter == null && System.nanoTime() < deadline) {
                        waiter = jdbc.query("select pid from pg_stat_activity where datname=current_database() "
                                        + "and ? = any(pg_blocking_pids(pid)) limit 1",
                                rows -> rows.next() ? rows.getLong(1) : null, first.pid);
                        if (waiter == null) Thread.sleep(20);
                    }
                    assertNotNull(waiter, "the opposite operation must demonstrably wait on canonical domain locks");
                    System.out.println("R1 tenant race kind=" + kind + " updateFirst=" + updateFirst
                            + " blocker=" + first.pid + " waiter=" + waiter + " tx=" + first.tx);
                } finally { AttemptProbe.flushHold.release.countDown(); mutationHold.release.countDown(); }
                update.join(15000); mutation.join(15000);
                assertFalse(update.isAlive()); assertFalse(mutation.isAlive());
                AttemptProbe.reset();
                if (updateFirst) {
                    assertNull(updateFailure.get()); assertNotNull(answer.get());
                    if (kind.equals("CANCEL")) assertNull(mutationFailure.get());
                    else assertInstanceOf(VisitOperationsConflictException.class, mutationFailure.get());
                } else {
                    assertNull(mutationFailure.get());
                    assertInstanceOf(VisitOperationsConflictException.class, updateFailure.get());
                    assertNull(answer.get());
                }
                PropertyVisitRequest saved = requests.findById(request.getId()).orElseThrow();
                assertEquals(updateFirst, saved.getAvailabilityStartAt() != null, "no post-transition or partial tenant write");
                assertEquals("Synthetic topology timing", saved.getPreferredVisitTiming());
                if (kind.equals("CANCEL")) {
                    assertEquals(VisitRequestStatus.CANCELLED, saved.getStatusValue());
                    assertThrows(VisitOperationsConflictException.class, () -> availability.update(
                            fixture.tenant().getId(), request.getId(), command(saved.getVersion())));
                } else if (kind.equals("LINK")) {
                    operations.coordinateRequest(fixture.oe().getId(), request.getId(),
                            new CoordinateVisitRequestCommand(saved.getVersion(), null, null));
                } else if (updateFirst) {
                    ownership.assignRequestCoordinator(admin.getId(), request.getId(), saved.getVersion(),
                            null, null, next.getId(), "R1_RACE_TRANSFER_REVIEWED");
                    assertEquals(next.getId(), requests.findById(request.getId()).orElseThrow().getCoordinator().getId());
                } else {
                    assertNotNull(availability.update(fixture.tenant().getId(), request.getId(), command(saved.getVersion())));
                }
                assertEquals(0, jdbc.queryForObject("select count(*) from visit_scheduling_commands", Integer.class));
            }
        }
    }

    private Fixture fixture(String label) {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(
                ("topology-" + label + "-" + UUID.randomUUID().toString().substring(0, 6)).toLowerCase(),
                "Topology City " + label, true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city,
                ("topology-team-" + label + "-" + UUID.randomUUID().toString().substring(0, 6)).toLowerCase(),
                "Topology Team " + label, true));
        Locality locality = new Locality(city.getDisplayName(), "Topology sector " + UUID.randomUUID(),
                null, null, null);
        locality.setSupportedCity(city);
        locality = localities.saveAndFlush(locality);
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Topology listing " + UUID.randomUUID());
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Synthetic topology address");
        listing.setSector(locality.getSectorName());
        listing.setCity(locality.getCity());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setLocationResolution(LocationResolution.CANONICAL);
        listing.setLatitude(22.72);
        listing.setLongitude(75.88);
        listing.setBhkCount("2BHK");
        listing.setMonthlyRent(BigDecimal.valueOf(20000));
        listing.setSecurityDeposit(BigDecimal.valueOf(20000));
        listing = listings.saveAndFlush(listing);
        User tenant = users.saveAndFlush(user("topology-tenant-" + label, Role.ROLE_TENANT,
                "Topology Tenant", "9876543210"));
        User oe = users.saveAndFlush(user("topology-oe-" + label, Role.ROLE_TENANT,
                "Topology OE", null));
        EmployeeProfile profile = new EmployeeProfile(oe, "OPERATIONS", city.getDisplayName(), BigDecimal.ZERO);
        profile.setStaffActive(true);
        employees.saveAndFlush(profile);
        grants.saveAndFlush(new StaffAccessGrant(oe, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM,
                null, team, Instant.now().minusSeconds(60), null, null, Instant.now(),
                "R1_TOPOLOGY_FIXTURE", StaffGrantProvisioningSource.ADMIN_API));
        return new Fixture(city, team, listing, tenant, oe);
    }

    private PropertyVisitRequest request(Fixture fixture) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(fixture.tenant());
        request.setListing(fixture.listing());
        request.setStatus(VisitRequestStatus.RECEIVED);
        request.setSupportedCity(fixture.city());
        request.setOperatingTeam(fixture.team());
        request.setOperationalScopeReady(true);
        request.setCoordinator(fixture.oe());
        request.setPreferredVisitTiming("Synthetic topology timing");
        return requests.saveAndFlush(request);
    }

    private TenantVisitAvailabilityCommand command(long version) {
        String day = LocalDate.now(ZONE).plusDays(10).toString();
        return new TenantVisitAvailabilityCommand(version,
                java.time.OffsetDateTime.parse(day + "T10:00:00+05:30"),
                java.time.OffsetDateTime.parse(day + "T13:00:00+05:30"), "Asia/Kolkata", null);
    }

    private static User user(String prefix, Role role, String name, String phone) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(name);
        user.setPhoneNumber(phone);
        user.setRole(role);
        return user;
    }

    private record Fixture(SupportedCity city, OperatingTeam team, RentalDetails listing,
                           User tenant, User oe) {}

    @TestConfiguration
    static class DiscoveryConfiguration {
        @Bean
        @Primary
        PropertyVisitRequestRepository interceptionRepository(
                @Qualifier("propertyVisitRequestRepository") PropertyVisitRequestRepository delegate,
                JdbcTemplate jdbc) {
            return (PropertyVisitRequestRepository) Proxy.newProxyInstance(
                    PropertyVisitRequestRepository.class.getClassLoader(),
                    new Class<?>[]{PropertyVisitRequestRepository.class},
                    (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.invoke(delegate, arguments);
                        }
                        try {
                            Object result = method.invoke(delegate, arguments);
                            if (method.getName().equals("findTopologyById")) {
                                DiscoveryBarrier.record("topology", result);
                                DiscoveryBarrier.afterDiscovery(jdbc);
                                AttemptProbe.afterDiscovery(jdbc, arguments);
                            }
                            if (method.getName().equals("findLockedById")) {
                                DiscoveryBarrier.record("locked", result);
                                if (Thread.currentThread() == AttemptProbe.mutationOwner)
                                    AttemptProbe.mutationHold.await(jdbc);
                            }
                            if (method.getName().equals("flush")) AttemptProbe.afterFlush(jdbc);
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }

    static final class Hold {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Long pid;
        volatile Long tx;
        void await(JdbcTemplate jdbc) {
            pid = jdbc.queryForObject("select pg_backend_pid()", Long.class);
            tx = jdbc.queryForObject("select txid_current()", Long.class);
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("test-only hold timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(error);
            }
        }
    }

    static final class AttemptProbe {
        static volatile Thread owner;
        static volatile Thread mutationOwner;
        static volatile Hold mutationHold;
        static final AtomicInteger faults = new AtomicInteger();
        static final AtomicInteger flushes = new AtomicInteger();
        static final java.util.List<Long> transactionIds = new java.util.concurrent.CopyOnWriteArrayList<>();
        static final java.util.List<Long> preAttemptVersions = new java.util.concurrent.CopyOnWriteArrayList<>();
        static volatile Hold flushHold = new Hold();
        static volatile boolean holdFlush;
        static void arm(Thread thread, int count, boolean hold) {
            reset(); owner = thread; faults.set(count); holdFlush = hold;
        }
        static void reset() {
            flushHold.release.countDown();
            if (mutationHold != null) mutationHold.release.countDown();
            owner = null; mutationOwner = null; mutationHold = null; faults.set(0); flushes.set(0);
            transactionIds.clear(); preAttemptVersions.clear(); flushHold = new Hold(); holdFlush = false;
        }
        static void afterDiscovery(JdbcTemplate jdbc, Object[] arguments) {
            if (Thread.currentThread() != owner) return;
            transactionIds.add(jdbc.queryForObject("select txid_current()", Long.class));
            preAttemptVersions.add(jdbc.queryForObject("select version from property_visit_requests where id=?",
                    Long.class, arguments[0]));
        }
        static void afterFlush(JdbcTemplate jdbc) {
            if (Thread.currentThread() != owner) return;
            flushes.incrementAndGet();
            if (faults.getAndUpdate(value -> Math.max(0, value - 1)) > 0)
                throw new TenantVisitAvailabilityTransaction.TopologyChangedException();
            if (holdFlush) flushHold.await(jdbc);
        }
    }

    static final class DiscoveryBarrier {
        private static final AtomicInteger remaining = new AtomicInteger();
        private static final AtomicInteger discoveries = new AtomicInteger();
        private static final BlockingQueue<Gate> gates = new LinkedBlockingQueue<>();
        private static final AtomicReference<Throwable> barrierFailure = new AtomicReference<>();
        private static final java.util.concurrent.ConcurrentLinkedQueue<String> trace =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        static void reset() {
            remaining.set(0);
            discoveries.set(0);
            gates.clear();
            barrierFailure.set(null);
            trace.clear();
        }

        static void arm(int pauses) {
            reset();
            remaining.set(pauses);
        }

        static void afterDiscovery(JdbcTemplate jdbc) throws InterruptedException {
            discoveries.incrementAndGet();
            if (remaining.getAndUpdate(value -> value > 0 ? value - 1 : 0) <= 0) return;
            long transactionId;
            try {
                transactionId = jdbc.queryForObject("select txid_current()", Long.class);
            } catch (Throwable failure) {
                barrierFailure.set(failure);
                throw failure;
            }
            Gate gate = new Gate(transactionId);
            gates.add(gate);
            gate.await();
        }

        static Gate nextGate(long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                Gate gate = gates.peek();
                if (gate != null) return gate;
                Thread.sleep(25);
            }
            throw new AssertionError("the real unlocked discovery did not reach its gate; gates="
                    + gates.size() + " remaining=" + remaining.get() + "; "
                    + (barrierFailure.get() == null ? "no barrier failure" : barrierFailure.get().toString()));
        }

        static void release(Gate gate) {
            gates.remove(gate);
            gate.open();
        }

        static void record(String kind, Object result) {
            String detail = "null";
            if (result instanceof java.util.Optional<?> optional && optional.isPresent()) {
                Object value = optional.get();
                if (value instanceof PropertyVisitRequestRepository.RequestTopologyRow row) {
                    detail = "topologySession=" + row.getSessionId();
                } else if (value instanceof PropertyVisitRequest request) {
                    detail = "requestSession=" + (request.getSession() == null ? "null" : request.getSession().getId());
                }
            }
            trace.add(kind + ":" + detail);
        }

        static String trace() {
            return String.join(", ", trace);
        }

        static final class Gate {
            private final long transactionId;
            private final CountDownLatch release = new CountDownLatch(1);

            Gate(long transactionId) {
                this.transactionId = transactionId;
            }

            long transactionId() {
                return transactionId;
            }

            void await() throws InterruptedException {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("topology gate timed out");
                }
            }

            void open() {
                release.countDown();
            }
        }
    }
}
