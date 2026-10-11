package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.OperationsVisitSessionView;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.LocationResolution;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionItem;
import com.indore.pathome.spaces.entity.VisitSessionItemConfirmationStatus;
import com.indore.pathome.spaces.entity.VisitSessionItemOrigin;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionItemRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2A-1 F1: transaction composition of the scoped Operations Session detail read.
 *
 * <p>{@code VisitOperationsService.getOperationsSession} promises one repeatable-read snapshot for
 * authorization, the authoritative Session row, the ownership/version tokens and the follow-up item
 * and locality reads. Spring's default {@code Propagation.REQUIRED} silently breaks that promise when
 * an outer caller already owns a READ_COMMITTED transaction: the detail operation joins that
 * transaction and its requested isolation is ignored, so the later statements of one response can
 * observe a different database snapshot than the statement that authorized the caller.
 *
 * <p>These tests drive the real Spring transactional proxy from inside a real READ_COMMITTED
 * transaction, commit a real ownership transfer from a separate connection at a deterministic point
 * inside the detail operation, and assert both the isolation the detail operation actually executed
 * at (read from the persistence context, not from the annotation) and the observable response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=nested-detail-isolation-test-jwt-secret-32-bytes-minimum",
        "pathome.visit.otp.hmac-secret=nested-detail-isolation-test-otp-secret-32-bytes-minimum",
        "cloudinary.api-key=nested-detail-test-key",
        "cloudinary.api-secret=nested-detail-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class OperationsSessionNestedTransactionIsolationPostgresTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "nested_isolation_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String READ_COMMITTED = "read committed";
    private static final String REPEATABLE_READ = "repeatable read";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            try {
                connection.setSchema(SCHEMA);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                flyway().target("40").load().migrate();
                flyway().load().migrate();
            } catch (Exception failure) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                throw failure;
            }
        }
        String schemaUrl = BASE_URL + (BASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(BASE_URL, USERNAME, PASSWORD).schemas(SCHEMA)
                .defaultSchema(SCHEMA).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Autowired private VisitOperationsService operations;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtUtils jwt;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private VisitSessionRepository sessions;
    @Autowired private VisitSessionItemRepository visitSessionItems;
    @PersistenceContext private EntityManager entityManager;
    @LocalServerPort private int httpPort;

    @AfterEach
    void clearBarrier() {
        SessionDetailBarrier.reset();
    }

    /**
     * An authorized outer READ_COMMITTED caller must still receive the Session detail from one
     * repeatable-read snapshot while a real ownership transfer commits inside the operation.
     */
    @Test
    void nestedReadCommittedCallerReceivesOneRepeatableReadAuthorizedSnapshot() throws Exception {
        Fixture fixture = fixture("nested-snapshot");
        long ownerId = fixture.owner().user().getId();
        long successorId = fixture.successor().user().getId();
        long authorizedVersion = fixture.session().getVersion();
        long authorizedChildVersion = fixture.child().getVersion();
        Instant authorizedUpdatedAt = fixture.session().getUpdatedAt();

        AtomicBoolean transferCommitted = new AtomicBoolean(false);
        AtomicReference<String> isolationInsideDetail = new AtomicReference<>();
        AtomicReference<String> jdbcIsolationInsideDetail = new AtomicReference<>();
        SessionDetailBarrier.beforeRead("findOwnershipVersionSnapshotById", () -> {
            isolationInsideDetail.set(transactionIsolation());
            jdbcIsolationInsideDetail.set(jdbcIsolation());
            if (transferCommitted.compareAndSet(false, true)) {
                commitConcurrentTransferAndItemRemoval(fixture.session().getId(), successorId,
                        fixture.item().getId());
            }
        });

        OperationsVisitSessionView view = inReadCommittedTransaction(() -> {
            assertEquals(READ_COMMITTED, transactionIsolation(),
                    "the caller's own transaction must be a real READ_COMMITTED transaction");
            assertTrue(AopUtils.isAopProxy(operations),
                    "the detail call must be routed through the Spring transactional proxy");
            return operations.getOperationsSession(ownerId, fixture.session().getId());
        });

        assertTrue(transferCommitted.get(), "the concurrency barrier must have fired inside the detail operation");
        assertEquals(REPEATABLE_READ, isolationInsideDetail.get(),
                "the Session detail operation must execute in its own repeatable-read transaction; "
                        + "observed persistence-context isolation was " + isolationInsideDetail.get()
                        + " (JDBC probe: " + jdbcIsolationInsideDetail.get() + ")");
        assertEquals(REPEATABLE_READ, jdbcIsolationInsideDetail.get(),
                "the JDBC connection used by the detail operation must be the same repeatable-read transaction");
        assertEquals(ownerId, view.coordinatorUserId(),
                "a snapshot that authorized the owner must never be served with a transferred coordinator");
        assertNotEquals(successorId, view.coordinatorUserId());
        assertEquals(authorizedVersion, view.version(),
                "version tokens must belong to the same snapshot as the authorized Session row");
        assertEquals(authorizedUpdatedAt, view.updatedAt());
        assertEquals(fixture.team().getId(), view.teamId());
        assertEquals(1, view.linkedRequestVersions().size());
        assertEquals(fixture.child().getId(), view.linkedRequestVersions().get(0).requestId());
        assertEquals(authorizedChildVersion, view.linkedRequestVersions().get(0).version(),
                "linked-Request tokens must belong to the authorized snapshot");
        assertEquals(1, view.items().size(), "item reads must belong to the authorized snapshot");
        assertEquals(fixture.item().getId(), view.items().get(0).itemId());
        assertNull(view.items().get(0).removedAt(),
                "a concurrent removal must not leak into an already authorized snapshot");
        assertEquals(fixture.locality().getCity(), view.items().get(0).city(),
                "locality reads must belong to the authorized snapshot");
        assertEquals(authorizedVersion + 1, currentSessionVersion(fixture.session().getId()),
                "the concurrent ownership transfer must really have committed");

        // The frozen HTTP semantics still hold once the transfer is committed and visible to everyone.
        String path = "/api/v1/operations/visit-sessions/" + fixture.session().getId();
        assertEquals(404, get(path, fixture.owner().token()).statusCode(),
                "the former owner must not keep Session detail access after a committed transfer");
        JsonNode successorDetail = read(path, fixture.successor().token());
        assertEquals(successorId, successorDetail.path("coordinatorUserId").asLong());
        JsonNode supervisorDetail = read(path, fixture.supervisor().token());
        assertEquals(successorId, supervisorDetail.path("coordinatorUserId").asLong(),
                "supervisor visibility must keep working");
        JsonNode adminDetail = read(path, fixture.admin().token());
        assertEquals(successorId, adminDetail.path("coordinatorUserId").asLong(),
                "staff-admin visibility must keep working");
    }

    /**
     * The item/locality reads that follow the authorized row must not drift to a post-transfer
     * snapshot: mixing the authorized ownership row with a concurrently removed item is exactly the
     * defect that a joined READ_COMMITTED transaction produces.
     */
    @Test
    void nestedReadCommittedCallerNeverMixesAuthorizedOwnershipWithDriftedItemReads() {
        Fixture fixture = fixture("nested-drift");
        long ownerId = fixture.owner().user().getId();
        long authorizedVersion = fixture.session().getVersion();

        AtomicBoolean transferCommitted = new AtomicBoolean(false);
        AtomicReference<String> isolationInsideDetail = new AtomicReference<>();
        SessionDetailBarrier.beforeRead("findBySessionIdOrderByPositionAsc", () -> {
            isolationInsideDetail.set(transactionIsolation());
            if (transferCommitted.compareAndSet(false, true)) {
                commitConcurrentTransferAndItemRemoval(fixture.session().getId(),
                        fixture.successor().user().getId(), fixture.item().getId());
            }
        });

        OperationsVisitSessionView view = inReadCommittedTransaction(
                () -> operations.getOperationsSession(ownerId, fixture.session().getId()));

        assertTrue(transferCommitted.get(), "the concurrency barrier must have fired inside the detail operation");
        assertEquals(REPEATABLE_READ, isolationInsideDetail.get(),
                "the Session detail operation must execute in its own repeatable-read transaction");
        assertEquals(authorizedVersion, view.version());
        assertEquals(ownerId, view.coordinatorUserId());
        assertEquals(1, view.items().size(),
                "the item read must stay inside the authorized snapshot instead of drifting mid-response");
        assertNull(view.items().get(0).removedAt(),
                "the response must never mix an authorized ownership row with a concurrently removed item");
        assertEquals(fixture.item().getId(), view.items().get(0).itemId());
    }

    /** Runs {@code body} inside one real READ_COMMITTED transaction and returns its result. */
    private <T> T inReadCommittedTransaction(Supplier<T> body) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template.execute(status -> body.get());
    }

    /** Isolation of the transaction the detail operation is currently executing in. */
    private String transactionIsolation() {
        return (String) entityManager.createNativeQuery("select current_setting('transaction_isolation')")
                .getSingleResult();
    }

    private String jdbcIsolation() {
        return jdbc.queryForObject("select current_setting('transaction_isolation')", String.class);
    }

    private long currentSessionVersion(long sessionId) {
        return jdbc.queryForObject("select version from visit_sessions where id = ?", Long.class, sessionId);
    }

    /**
     * Commits a real ownership transfer plus a real item removal on a separate connection, mirroring
     * the Session and its linked children exactly as {@code OperationalOwnershipService} does.
     */
    private void commitConcurrentTransferAndItemRemoval(long sessionId, long coordinatorUserId, long itemId) {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD)) {
            connection.setSchema(SCHEMA);
            connection.setAutoCommit(false);
            try (var sessionsUpdate = connection.prepareStatement(
                    "update visit_sessions set coordinator_user_id = ?, version = version + 1 where id = ?")) {
                sessionsUpdate.setLong(1, coordinatorUserId);
                sessionsUpdate.setLong(2, sessionId);
                sessionsUpdate.executeUpdate();
            }
            try (var requestsUpdate = connection.prepareStatement(
                    "update property_visit_requests set coordinator_user_id = ?, version = version + 1 "
                            + "where session_id = ?")) {
                requestsUpdate.setLong(1, coordinatorUserId);
                requestsUpdate.setLong(2, sessionId);
                requestsUpdate.executeUpdate();
            }
            try (var itemsUpdate = connection.prepareStatement(
                    "update visit_session_items set removed_at = now(), removal_reason = 'CONCURRENT_REMOVAL' "
                            + "where id = ?")) {
                itemsUpdate.setLong(1, itemId);
                itemsUpdate.executeUpdate();
            }
            connection.commit();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not commit the concurrent ownership transfer", failure);
        }
    }

    private HttpResponse<String> get(String path, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .GET().build();
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode read(String path, String token) throws Exception {
        HttpResponse<String> response = get(path, token);
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private Fixture fixture(String prefix) {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code(prefix), prefix + " City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code(prefix + "-team"),
                prefix + " Team", true));
        Locality locality = locality(city, prefix);
        Actor tenant = actor(prefix + "-tenant", Role.ROLE_TENANT, null, false);
        Actor owner = actor(prefix + "-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor successor = actor(prefix + "-successor", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor supervisor = actor(prefix + "-supervisor", Role.ROLE_TENANT, "SUPERVISOR", true);
        Actor admin = actor(prefix + "-admin", Role.ROLE_ADMIN, "OPERATIONS", true);
        grant(owner, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team, null);
        grant(successor, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team, null);
        grant(supervisor, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, team, null);
        grant(admin, StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null, null);

        VisitSession session = draftSession(tenant.user(), city, team, owner.user());
        PropertyVisitRequest child = linkedRequest(prefix + "-child", tenant.user(), city, team, locality,
                session, owner.user());
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(session);
        item.setListing(child.getListing());
        item.setPosition(1);
        item.setSourceRequest(child);
        item.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
        item.setAvailabilityConfirmedAt(Instant.now());
        item.setConfirmedBy(owner.user());
        visitSessionItems.saveAndFlush(item);
        return new Fixture(city, team, locality, tenant, owner, successor, supervisor, admin, session, child, item);
    }

    private Actor actor(String label, Role role, String profileRole, boolean staffActive) {
        User user = new User();
        user.setEmail("nested-isolation-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("+91987655" + String.format("%04d", (int) (Math.random() * 10000)));
        user.setRole(role);
        user = users.saveAndFlush(user);
        if (profileRole != null) {
            EmployeeProfile profile = employees.saveAndFlush(
                    new EmployeeProfile(user, profileRole, null, BigDecimal.ZERO));
            if (staffActive) {
                profile.setStaffActive(true);
                employees.saveAndFlush(profile);
            }
        }
        return new Actor(user, jwt.generateToken(user.getId(), user.getEmail(), user.getRole().name()));
    }

    private void grant(Actor actor, StaffCapability capability, StaffScopeType scopeType, SupportedCity city,
            OperatingTeam team, Instant expiresAt) {
        grants.saveAndFlush(new StaffAccessGrant(actor.user(), capability, scopeType, city, team,
                Instant.now().minusSeconds(60), expiresAt, null, Instant.now(), "TEST_ISOLATION",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private Locality locality(SupportedCity city, String prefix) {
        Locality locality = new Locality(city.getDisplayName(), prefix + " sector " + UUID.randomUUID(),
                null, null, null);
        locality.setSupportedCity(city);
        return localities.saveAndFlush(locality);
    }

    private Listing createListing(Locality locality, String title) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle(title);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Private address " + title);
        listing.setSector(locality.getSectorName());
        listing.setCity(locality.getCity());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setLocationResolution(LocationResolution.CANONICAL);
        listing.setLatitude(22.72);
        listing.setLongitude(75.88);
        listing.setBhkCount("1BHK");
        listing.setMonthlyRent(BigDecimal.valueOf(15000));
        listing.setSecurityDeposit(BigDecimal.valueOf(15000));
        return listings.saveAndFlush(listing);
    }

    private PropertyVisitRequest linkedRequest(String suffix, User tenant, SupportedCity city,
            OperatingTeam team, Locality locality, VisitSession session, User coordinator) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(createListing(locality, "Private title " + suffix + " " + UUID.randomUUID()));
        request.setStatus(VisitRequestStatus.COORDINATING);
        request.setSession(session);
        request.setSupportedCity(city);
        request.setOperatingTeam(team);
        request.setOperationalScopeReady(true);
        request.setCoordinator(coordinator);
        request.setTenantNote("Private tenant note " + suffix);
        return requests.saveAndFlush(request);
    }

    private VisitSession draftSession(User tenant, SupportedCity city, OperatingTeam team, User coordinator) {
        VisitSession session = new VisitSession();
        session.setTenant(tenant);
        session.setCity(city.getDisplayName());
        session.setSupportedCity(city);
        session.setOperatingTeam(team);
        session.setCoordinator(coordinator);
        session.setOperationalScopeReady(true);
        session.setStatus(VisitSessionStatus.DRAFT);
        return sessions.saveAndFlush(session);
    }

    private String code(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Actor(User user, String token) {}

    private record Fixture(SupportedCity city, OperatingTeam team, Locality locality, Actor tenant, Actor owner,
            Actor successor, Actor supervisor, Actor admin, VisitSession session, PropertyVisitRequest child,
            VisitSessionItem item) {}

    /**
     * Deterministic concurrency barrier in front of the two real Spring Data repositories the Session
     * detail read uses, so a real committed transfer can land exactly before a named repository read.
     * Production code is unaware of this seam.
     */
    @TestConfiguration
    static class SessionDetailBarrierConfiguration {
        @Bean
        @Primary
        VisitSessionRepository barrierVisitSessionRepository(
                @Qualifier("visitSessionRepository") VisitSessionRepository delegate) {
            return (VisitSessionRepository) SessionDetailBarrier.wrap(delegate, VisitSessionRepository.class);
        }

        @Bean
        @Primary
        VisitSessionItemRepository barrierVisitSessionItemRepository(
                @Qualifier("visitSessionItemRepository") VisitSessionItemRepository delegate) {
            return (VisitSessionItemRepository) SessionDetailBarrier.wrap(delegate, VisitSessionItemRepository.class);
        }
    }

    static final class SessionDetailBarrier {
        private static volatile String guardedMethod;
        private static volatile Runnable pendingHook;

        private SessionDetailBarrier() {}

        static Object wrap(Object delegate, Class<?> type) {
            return Proxy.newProxyInstance(SessionDetailBarrier.class.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, arguments) -> {
                        Runnable hook = pendingHook;
                        if (hook != null && method.getName().equals(guardedMethod)) {
                            pendingHook = null;
                            guardedMethod = null;
                            hook.run();
                        }
                        try {
                            return method.invoke(delegate, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        static void beforeRead(String methodName, Runnable hook) {
            guardedMethod = methodName;
            pendingHook = hook;
        }

        static void reset() {
            pendingHook = null;
            guardedMethod = null;
        }
    }
}
