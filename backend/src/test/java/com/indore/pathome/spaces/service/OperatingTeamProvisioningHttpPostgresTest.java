package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Package 3A acceptance: governed Operating Team provisioning over real HTTP against real
 * PostgreSQL, including the authorization matrix, retained-code retries, concurrent creation, the
 * City-deactivation race, audit atomicity and every "active City / active Team" invariant.
 *
 * <p>Runs only against the designated disposable database (see tools/oe-integration-env). The
 * class defends itself when invoked directly through Maven: {@link DisposableIntegrationTarget} runs the
 * whole fail-closed policy (explicit enablement, exact approved name, approved loopback host and port, a
 * strict JDBC URL, a live identity check and the live disposable marker) before any connection that could
 * create a schema is opened, and no schema or DDL ever runs when any check fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=p3a-provisioning-http-test-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=p3a-provisioning-http-test-otp-secret-32-bytes",
        "cloudinary.api-key=p3a-provisioning-test-key",
        "cloudinary.api-secret=p3a-provisioning-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class OperatingTeamProvisioningHttpPostgresTest {
    private static final String SCHEMA = "p3a_team_http_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** The command transaction bounds its lock waits to about five seconds; CI tolerance is applied. */
    private static final long BOUNDED_LOCK_TIMEOUT_MILLIS = 5_000L;
    private static final long MINIMUM_BOUNDED_WAIT_MILLIS = 3_000L;
    private static final long MAXIMUM_BOUNDED_WAIT_MILLIS = 25_000L;
    private static final long GOVERNANCE_ADVISORY_LOCK_KEY_1 = 1346454344L;
    private static final long GOVERNANCE_ADVISORY_LOCK_KEY_2 = 1196387922L;

    private static DisposableIntegrationTarget.Target target;
    private static boolean schemaCreated;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        // Fail-closed: the complete guard must pass before any connection that could mutate anything is
        // opened. Nothing here is defaulted, and the guard never opens a mutable connection itself.
        target = DisposableIntegrationTarget.verifyFromEnvironment();
        try (var connection = connect();
             Statement statement = connection.createStatement()) {
            // The very connection that is about to run DDL must itself resolve to the verified target.
            assertEquals(target.database(), currentDatabase(connection));
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
            try {
                connection.setSchema(SCHEMA);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                flyway().target("40").load().migrate();
                flyway().load().migrate();
            } catch (Exception failure) {
                dropSchema();
                throw failure;
            }
        }
        registry.add("spring.datasource.url", OperatingTeamProvisioningHttpPostgresTest::schemaUrl);
        registry.add("spring.datasource.username", () -> target.username());
        registry.add("spring.datasource.password", () -> target.password());
    }

    private static String schemaUrl() {
        return target.schemaUrl(SCHEMA);
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(target.jdbcUrl(), target.username(), target.password()).schemas(SCHEMA)
                .defaultSchema(SCHEMA).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
    }

    @AfterAll
    static void dropSchemaAfterTheSuite() throws Exception {
        dropSchema();
    }

    /** Drops the test schema only when this class really created it on a fully verified target. */
    private static void dropSchema() throws Exception {
        if (!schemaCreated || target == null) return;
        try (var connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            schemaCreated = false;
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(target.jdbcUrl(), target.username(), target.password());
    }

    private static String currentDatabase(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select current_database()")) {
            rows.next();
            return rows.getString(1);
        }
    }

    @Autowired private ObjectMapper mapper;
    @Autowired private JwtUtils jwt;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private StaffAccessGrantRepository grantRows;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private OperationalOwnershipService ownership;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @LocalServerPort private int httpPort;

    // ------------------------------------------------------------------ creation contract

    @Test
    void governedCreationPersistsExactlyOneActiveTeamWithBoundedAuditEvidence() throws Exception {
        Actor admin = globalAdmin("create");
        SupportedCity city = activeCity("create");
        String code = "p3a-create";
        long expectedCityVersion = cityVersion(city.getId());

        HttpResponse<String> created = send("POST", teamsPath(city.getId()), admin.token(),
                createBody(code, "  Creation Team  ", expectedCityVersion, "TEAM_ONBOARDING"));
        assertEquals(201, created.statusCode(), created.body());
        JsonNode body = mapper.readTree(created.body());
        assertTrue(body.path("id").asLong() > 0);
        assertEquals(city.getId(), body.path("cityId").asLong());
        assertEquals(code, body.path("code").asText());
        assertEquals("Creation Team", body.path("displayName").asText());
        assertTrue(body.path("active").asBoolean());
        assertEquals(0L, body.path("version").asLong());
        assertFalse(body.path("createdAt").isNull());
        assertTrue(created.headers().firstValue("Location").orElse("")
                .endsWith("/api/v1/staff/admin/cities/" + city.getId() + "/operating-teams"));
        assertEquals("no-store", created.headers().firstValue("Cache-Control").orElse("missing"));

        assertEquals(1L, rowCount("select count(*) from operating_teams where city_id = ? and code = ?",
                city.getId(), code));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select active from operating_teams where city_id = ? and code = ?", Boolean.class,
                city.getId(), code));
        long teamId = body.path("id").asLong();
        assertEquals(1L, rowCount("select count(*) from operational_audit_events where action_code = "
                + "'OPERATING_TEAM_CREATED' and target_type = 'OPERATING_TEAM' and target_id = ?", teamId));
        var audit = jdbc.queryForMap("select * from operational_audit_events where action_code = "
                + "'OPERATING_TEAM_CREATED' and target_id = ?", teamId);
        assertEquals(admin.user().getId(), audit.get("actor_user_id"));
        assertEquals("TEAM_ONBOARDING", audit.get("reason_code"));
        assertTrue(String.valueOf(audit.get("details")).contains("\"expectedCityVersion\""));

        // The command carries no state the client may choose: extra fields are ignored, and the
        // persisted Team is active under this City.
        HttpResponse<String> withExtraFields = send("POST", teamsPath(city.getId()), admin.token(),
                "{\"code\":\"p3a-extra\",\"displayName\":\"Extra Team\",\"expectedCityVersion\":"
                        + expectedCityVersion + ",\"reasonCode\":\"TEAM_ONBOARDING\",\"active\":false,"
                        + "\"createdByUserId\":1,\"grantIds\":[1,2],\"operatingTeamId\":99}");
        assertEquals(201, withExtraFields.statusCode(), withExtraFields.body());
        assertTrue(mapper.readTree(withExtraFields.body()).path("active").asBoolean());
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select active from operating_teams where city_id = ? and code = ?", Boolean.class,
                city.getId(), "p3a-extra"));
    }

    @Test
    void repeatedAndRetainedCodesConflictWithoutASecondTeamOrAuditEvent() throws Exception {
        Actor admin = globalAdmin("retry");
        SupportedCity city = activeCity("retry");
        String body = createBody("p3a-retry", "Retry Team", cityVersion(city.getId()), "TEAM_ONBOARDING");

        HttpResponse<String> created = send("POST", teamsPath(city.getId()), admin.token(), body);
        assertEquals(201, created.statusCode(), created.body());
        long teamId = mapper.readTree(created.body()).path("id").asLong();
        assertEquals(409, send("POST", teamsPath(city.getId()), admin.token(), body).statusCode());
        assertEquals(1L, rowCount("select count(*) from operating_teams where city_id = ? and code = ?",
                city.getId(), "p3a-retry"));
        assertEquals(1L, creationAuditCount(teamId));

        // A retained inactive code is still a conflict: creation never silently reactivates a Team.
        OperatingTeam retained = teams.findByCityIdAndCode(city.getId(), "p3a-retained").orElse(null);
        if (retained == null) {
            retained = teams.saveAndFlush(new OperatingTeam(city, "p3a-retained", "Retained Team", true));
        }
        retained.setActive(false);
        teams.saveAndFlush(retained);
        assertEquals(409, send("POST", teamsPath(city.getId()), admin.token(),
                createBody("p3a-retained", "Retained Team Again", cityVersion(city.getId()),
                        "TEAM_ONBOARDING")).statusCode());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "select active from operating_teams where city_id = ? and code = ?", Boolean.class,
                city.getId(), "p3a-retained"));
        // The rejected creation produced no audit event of its own.
        assertEquals(0L, creationAuditCount(retained.getId()));
    }

    @Test
    void concurrentSameCodeCreationCommitsOneTeamAndOneAuditEvent() throws Exception {
        Actor admin = globalAdmin("race-create");
        SupportedCity city = activeCity("race-create");
        String body = createBody("p3a-race-create", "Race Create Team", cityVersion(city.getId()),
                "TEAM_ONBOARDING");
        long expectedCityVersionBefore = cityVersion(city.getId());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                tasks.add(() -> {
                    start.await();
                    return send("POST", teamsPath(city.getId()), admin.token(), body).statusCode();
                });
            }
            List<Future<Integer>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) statuses.add(future.get(60, TimeUnit.SECONDS));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(201, 409), statuses);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1L, rowCount("select count(*) from operating_teams where city_id = ? and code = ?",
                city.getId(), "p3a-race-create"));
        long raceTeamId = jdbc.queryForObject(
                "select id from operating_teams where city_id = ? and code = ?", Long.class,
                city.getId(), "p3a-race-create");
        assertEquals(1L, creationAuditCount(raceTeamId));
        // Creating a child Team never rewrites the City's own version.
        assertEquals(expectedCityVersionBefore, cityVersion(city.getId()));
    }

    // ------------------------------------------------------------------ authorization matrix

    @Test
    void everyCallerWithoutCurrentGlobalStaffAdminAuthorityIsDenied() throws Exception {
        SupportedCity city = activeCity("deny");
        Actor globalAdmin = globalAdmin("deny-admin");
        Actor tenant = user("deny-tenant", Role.ROLE_TENANT, null, null);
        Actor noGrant = user("deny-nogrant", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor inactiveStaff = user("deny-inactive", Role.ROLE_TENANT, "OPERATIONS", false);
        Actor legacyAdmin = user("deny-legacy-admin", Role.ROLE_ADMIN, null, null);
        Actor cityAdmin = user("deny-city-admin", Role.ROLE_TENANT, "OPERATIONS", true);
        cityScoped(cityAdmin, StaffCapability.CITY_TEAM_ADMIN, city);
        Actor intake = user("deny-intake", Role.ROLE_TENANT, "OPERATIONS", true);
        cityScoped(intake, StaffCapability.OPS_INTAKE, city);
        Actor coordinator = user("deny-coordinator", Role.ROLE_TENANT, "OPERATIONS", true);
        teamScoped(coordinator, StaffCapability.OPS_COORDINATE, city);
        Actor revoked = user("deny-revoked", Role.ROLE_TENANT, "OPERATIONS", true);
        StaffAccessGrant revokedGrant = teamScoped(revoked, StaffCapability.OPS_COORDINATE, city);
        revokedGrant.revoke(users.findById(coordinator.user().getId()).orElseThrow(), Instant.now(), "TEST_REVOKE");
        grantRows.saveAndFlush(revokedGrant);
        Actor futureAdmin = user("deny-future-admin", Role.ROLE_TENANT, "OPERATIONS", true);
        globalAdminGrant(futureAdmin, Instant.now().plusSeconds(3600));
        Actor revokedAdmin = user("deny-revoked-admin", Role.ROLE_TENANT, "OPERATIONS", true);
        StaffAccessGrant adminGrant = globalAdminGrant(revokedAdmin, Instant.now().minusSeconds(60));
        adminGrant.revoke(globalAdmin.user(), Instant.now(), "TEST_REVOKE");
        grantRows.saveAndFlush(adminGrant);
        Actor inactiveAdmin = user("deny-inactive-admin", Role.ROLE_TENANT, "OPERATIONS", false);
        globalAdminGrant(inactiveAdmin, Instant.now().minusSeconds(60));

        String probe = createBody("p3a-denied-probe", "Denied Probe", cityVersion(city.getId()),
                "TEAM_ONBOARDING");

        assertEquals(401, send("GET", citiesPath(), null, null).statusCode());
        assertEquals(401, send("GET", teamsPath(city.getId()), null, null).statusCode());
        assertEquals(401, send("POST", teamsPath(city.getId()), null, probe).statusCode());

        for (Actor caller : List.of(tenant, noGrant, inactiveStaff, legacyAdmin, cityAdmin, intake, coordinator,
                revoked, futureAdmin, revokedAdmin, inactiveAdmin)) {
            String label = caller.user().getFullName();
            assertEquals(403, send("GET", citiesPath(), caller.token(), null).statusCode(), label);
            assertEquals(403, send("GET", teamsPath(city.getId()), caller.token(), null).statusCode(), label);
            assertEquals(403, send("POST", teamsPath(city.getId()), caller.token(), probe).statusCode(), label);
        }
        assertEquals(0L, rowCount("select count(*) from operating_teams where code = 'p3a-denied-probe'"));
        assertEquals(200, send("GET", citiesPath(), globalAdmin.token(), null).statusCode());
    }

    // ------------------------------------------------------------------ validation

    @Test
    void malformedCommandsAndPagesAreRejectedWithoutCreatingState() throws Exception {
        Actor admin = globalAdmin("validate");
        SupportedCity city = activeCity("validate");
        long version = cityVersion(city.getId());
        String path = teamsPath(city.getId());

        assertEquals(400, send("POST", path, admin.token(),
                createBody("P3A-UPPER", "Team", version, "TEAM_ONBOARDING")).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                createBody("p3a_bad", "Team", version, "TEAM_ONBOARDING")).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                createBody("", "Team", version, "TEAM_ONBOARDING")).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                createBody("a".repeat(65), "Team", version, "TEAM_ONBOARDING")).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                createBody("p3a-name", "   ", version, "TEAM_ONBOARDING")).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                createBody("p3a-name", "a".repeat(161), version, "TEAM_ONBOARDING")).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                createBody("p3a-name", "Team\u0007Bell", version, "TEAM_ONBOARDING")).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                createBody("p3a-version", "Team", null, "TEAM_ONBOARDING")).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                createBody("p3a-version", "Team", -1L, "TEAM_ONBOARDING")).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                createBody("p3a-reason", "Team", version, "team_onboarding")).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                createBody("p3a-reason", "Team", version, null)).statusCode());
        assertEquals(400, send("POST", path, admin.token(),
                "{\"displayName\":\"Team\",\"expectedCityVersion\":" + version
                        + ",\"reasonCode\":\"TEAM_ONBOARDING\"}").statusCode());
        assertEquals(400, send("POST", "/api/v1/staff/admin/cities/not-a-number/operating-teams",
                admin.token(), createBody("p3a-bad-id", "Team", version, "TEAM_ONBOARDING")).statusCode());
        assertEquals(400, send("POST", path, admin.token(), "{").statusCode());
        assertEquals(400, send("POST", path, admin.token(), null).statusCode());

        assertEquals(400, send("GET", citiesPath() + "?page=-1", admin.token(), null).statusCode());
        assertEquals(400, send("GET", citiesPath() + "?page=abc", admin.token(), null).statusCode());
        assertEquals(400, send("GET", citiesPath() + "?size=0", admin.token(), null).statusCode());
        assertEquals(400, send("GET", citiesPath() + "?size=101", admin.token(), null).statusCode());
        assertEquals(400, send("GET", path + "?size=1000", admin.token(), null).statusCode());

        assertEquals(0L, rowCount("select count(*) from operating_teams where city_id = ?", city.getId()));
    }

    @Test
    void unknownInactiveAndStaleCityStatesFailClosed() throws Exception {
        Actor admin = globalAdmin("states");
        SupportedCity active = activeCity("states");
        SupportedCity sleeping = activeCity("states-sleeping");
        sleeping.setActive(false);
        cities.saveAndFlush(sleeping);

        assertEquals(404, send("POST", teamsPath(9_999_999L), admin.token(),
                createBody("p3a-missing-city", "Team", 0L, "TEAM_ONBOARDING")).statusCode());
        assertEquals(404, send("GET", teamsPath(9_999_999L), admin.token(), null).statusCode());
        assertEquals(409, send("POST", teamsPath(sleeping.getId()), admin.token(),
                createBody("p3a-sleeping-city", "Team", cityVersion(sleeping.getId()),
                        "TEAM_ONBOARDING")).statusCode());
        assertEquals(409, send("POST", teamsPath(active.getId()), admin.token(),
                createBody("p3a-stale-version", "Team", cityVersion(active.getId()) + 1,
                        "TEAM_ONBOARDING")).statusCode());
        assertEquals(404, send("GET", "/api/v1/staff/admin/cities/9999999/operating-teams",
                admin.token(), null).statusCode());

        assertEquals(0L, rowCount("select count(*) from operating_teams where city_id = ?", sleeping.getId()));
        assertEquals(0L, rowCount("select count(*) from operating_teams where city_id = ?", active.getId()));
    }

    // ------------------------------------------------------------------ concurrency invariant

    @Test
    void cityDeactivationCannotCommitWhileANewlyCreatedTeamStaysActive() throws Exception {
        Actor admin = globalAdmin("city-race");
        SupportedCity city = activeCity("city-race");
        long expectedCityVersion = cityVersion(city.getId());

        // The superseded ordering enumerated Teams before the City lock was taken. That snapshot is
        // reproduced here (pre-lock read) so the control below can show it omits the concurrent Team.
        int preLockEnumeration = teams.findAllByCityIdOrderByIdAsc(city.getId()).size();

        Connection holder = DriverManager.getConnection(schemaUrl(), target.username(), target.password());
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.execute("select id from supported_cities where id = " + city.getId() + " for update");

            CountDownLatch started = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<String> deactivation = pool.submit(() -> {
                    started.countDown();
                    try {
                        ownership.deactivateCity(admin.user().getId(), city.getId(), expectedCityVersion,
                                "P3A_CITY_DEACTIVATION_RACE");
                        return "COMMITTED";
                    } catch (VisitOperationsConflictException conflict) {
                        return "CONFLICT: " + conflict.getMessage();
                    }
                });
                started.await(30, TimeUnit.SECONDS);
                awaitBlockedDeactivation(10_000);

                // A governed creation would take the same City guard; this commits the Team row the
                // lock release makes visible, exactly like a creator that won the guard first.
                statement.execute("insert into operating_teams (city_id, code, display_name, active, version,"
                        + " created_at, updated_at) values (" + city.getId() + ", 'p3a-race-team',"
                        + " 'Race Team', true, 0, now(), now())");
                holder.commit();

                String outcome = deactivation.get(60, TimeUnit.SECONDS);
                assertTrue(outcome.startsWith("CONFLICT"), outcome);
            } finally {
                pool.shutdownNow();
            }
        } finally {
            holder.close();
        }

        // Control: the pre-lock enumeration really is stale, and the post-lock enumeration the fixed
        // code performs sees the concurrently committed Team.
        int postLockEnumeration = teams.findAllByCityIdOrderByIdAsc(city.getId()).size();
        assertTrue(postLockEnumeration > preLockEnumeration,
                "expected the concurrent Team to appear only after the City lock");
        assertEquals(Boolean.TRUE, jdbc.queryForObject("select active from supported_cities where id = ?",
                Boolean.class, city.getId()));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select active from operating_teams where city_id = ? and code = 'p3a-race-team'",
                Boolean.class, city.getId()));
        assertEquals(0L, rowCount("select count(*) from supported_cities c where c.active = false and exists "
                + "(select 1 from operating_teams t where t.city_id = c.id and t.active = true)"));
    }

    // ------------------------------------------------------------------ bounded command waits

    @Test
    void governanceAdvisoryLockContentionFailsBoundedSanitizedAndWithoutPartialState() throws Exception {
        Actor admin = globalAdmin("wait-governance");
        SupportedCity city = activeCity("wait-governance");
        String code = "p3a-wait-governance";
        String body = createBody(code, "Wait Governance", cityVersion(city.getId()), "TEAM_ONBOARDING");

        Connection holder = governanceLockHolder();
        long elapsed;
        try {
            long started = System.nanoTime();
            HttpResponse<String> blocked = send("POST", teamsPath(city.getId()), admin.token(), body);
            elapsed = elapsedMillis(started);
            assertEquals(503, blocked.statusCode(), blocked.body());
            assertSanitizedUnavailable(blocked);
        } finally {
            release(holder);
        }
        assertBoundedWait(elapsed);

        // The blocked command committed nothing: no Team and no creation audit event for the code.
        assertEquals(0L, rowCount("select count(*) from operating_teams where city_id = ? and code = ?",
                city.getId(), code));
        assertEquals(0L, creationAuditCountForCode(city.getId(), code));

        // The explicit retry after the blocker is gone creates exactly one Team and exactly one audit event.
        HttpResponse<String> retried = send("POST", teamsPath(city.getId()), admin.token(), body);
        assertEquals(201, retried.statusCode(), retried.body());
        long teamId = mapper.readTree(retried.body()).path("id").asLong();
        assertEquals(1L, rowCount("select count(*) from operating_teams where city_id = ? and code = ?",
                city.getId(), code));
        assertEquals(1L, creationAuditCount(teamId));
        assertEquals(1L, creationAuditCountForCode(city.getId(), code));
    }

    @Test
    void cityRowLockContentionFailsBoundedSanitizedAndWithoutPartialState() throws Exception {
        Actor admin = globalAdmin("wait-city");
        SupportedCity city = activeCity("wait-city");
        String code = "p3a-wait-city";
        String body = createBody(code, "Wait City", cityVersion(city.getId()), "TEAM_ONBOARDING");

        Connection holder = cityRowLockHolder(city.getId());
        long elapsed;
        try {
            long started = System.nanoTime();
            HttpResponse<String> blocked = send("POST", teamsPath(city.getId()), admin.token(), body);
            elapsed = elapsedMillis(started);
            assertEquals(503, blocked.statusCode(), blocked.body());
            assertSanitizedUnavailable(blocked);
        } finally {
            release(holder);
        }
        assertBoundedWait(elapsed);

        assertEquals(0L, rowCount("select count(*) from operating_teams where city_id = ? and code = ?",
                city.getId(), code));
        assertEquals(0L, creationAuditCountForCode(city.getId(), code));

        HttpResponse<String> retried = send("POST", teamsPath(city.getId()), admin.token(), body);
        assertEquals(201, retried.statusCode(), retried.body());
        long teamId = mapper.readTree(retried.body()).path("id").asLong();
        assertEquals(1L, rowCount("select count(*) from operating_teams where city_id = ? and code = ?",
                city.getId(), code));
        assertEquals(1L, creationAuditCount(teamId));
    }

    @Test
    void theBoundedWaitsAreTransactionLocalAndNeverLeakIntoAnotherTransaction() throws Exception {
        Actor admin = globalAdmin("waits-locality");
        SupportedCity city = activeCity("waits-locality");

        // A command transaction demonstrably applied the bounds: a held governance lock makes it fail bounded.
        Connection holder = governanceLockHolder();
        try {
            assertEquals(503, send("POST", teamsPath(city.getId()), admin.token(),
                    createBody("p3a-waits-locality", "Locality", cityVersion(city.getId()), "TEAM_ONBOARDING"))
                    .statusCode());
        } finally {
            release(holder);
        }

        // A later transaction on the pool sees the untouched session defaults, so nothing leaked.
        assertEquals("0", jdbc.queryForObject("select current_setting('lock_timeout')", String.class));
        assertEquals("0", jdbc.queryForObject("select current_setting('statement_timeout')", String.class));

        // ... and it can still wait longer than the command's own lock bound without being aborted.
        Connection cityHolder = cityRowLockHolder(city.getId());
        long waited;
        try {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Long> waiter = pool.submit(() -> new TransactionTemplate(transactionManager)
                        .execute(status -> {
                            long started = System.nanoTime();
                            jdbc.queryForObject("select id from supported_cities where id = ? for update",
                                    Long.class, city.getId());
                            return elapsedMillis(started);
                        }));
                Thread.sleep(BOUNDED_LOCK_TIMEOUT_MILLIS + 1_500L);
                release(cityHolder);
                cityHolder = null;
                waited = waiter.get(60, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        } finally {
            release(cityHolder);
        }
        assertTrue(waited > BOUNDED_LOCK_TIMEOUT_MILLIS,
                "a later transaction must not inherit the command's bounded lock wait (waited " + waited + "ms)");
    }

    @Test
    void boundedWaitsNeverRewriteAuthorizationOrConflictSemantics() throws Exception {
        Actor admin = globalAdmin("scoped-status");
        Actor tenant = user("scoped-status-tenant", Role.ROLE_TENANT, null, null);
        SupportedCity city = activeCity("scoped-status");
        long version = cityVersion(city.getId());
        String body = createBody("p3a-scoped-status", "Scoped Status", version, "TEAM_ONBOARDING");

        assertEquals(201, send("POST", teamsPath(city.getId()), admin.token(), body).statusCode());
        // Ordinary duplicate-code and stale-version conflicts stay 409.
        assertEquals(409, send("POST", teamsPath(city.getId()), admin.token(), body).statusCode());
        assertEquals(409, send("POST", teamsPath(city.getId()), admin.token(),
                createBody("p3a-scoped-status-2", "Scoped Status 2", version + 1, "TEAM_ONBOARDING")).statusCode());
        // Authorization failures stay 401/403.
        assertEquals(401, send("POST", teamsPath(city.getId()), null, body).statusCode());
        assertEquals(403, send("POST", teamsPath(city.getId()), tenant.token(), body).statusCode());

        // Even while the governance advisory lock is held, authorization and validation failures stay
        // 401/403/400 and are never rewritten as a bounded-wait 503.
        Connection holder = governanceLockHolder();
        try {
            assertEquals(401, send("POST", teamsPath(city.getId()), null, body).statusCode());
            assertEquals(403, send("POST", teamsPath(city.getId()), tenant.token(), body).statusCode());
            assertEquals(400, send("POST", teamsPath(city.getId()), admin.token(),
                    createBody("P3A-SCOPED-STATUS-3", "Scoped Status 3", version, "TEAM_ONBOARDING")).statusCode());
        } finally {
            release(holder);
        }
        assertEquals(0L, rowCount("select count(*) from operating_teams where code = 'p3a-scoped-status-2'"));
    }

    @Test
    void anExpiredGlobalStaffAdminGrantCannotExistSoAuthorityCannotSilentlyLapseByExpiry() throws Exception {
        SupportedCity city = activeCity("expired-admin");
        Actor actor = user("expired-admin", Role.ROLE_TENANT, "OPERATIONS", true);

        // ck_staff_grants_admin_no_expiry: a STAFF_ADMIN@GLOBAL grant may never carry an expiry, so the
        // "expired effective STAFF_ADMIN@GLOBAL" state is structurally impossible. Authority can only lapse
        // by revocation or staff deactivation, and both are already refused with 403 by the matrix above.
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into staff_access_grants (user_id, capability, scope_type, effective_at, expires_at, "
                        + "created_at, grant_reason_code, provisioning_source) values (?, 'STAFF_ADMIN', 'GLOBAL', "
                        + "now() - interval '2 hours', now() - interval '1 hour', now(), 'TEST_P3A', 'ADMIN_API')",
                actor.user().getId()));

        assertEquals(403, send("POST", teamsPath(city.getId()), actor.token(),
                createBody("p3a-expired-admin", "Expired Admin", cityVersion(city.getId()), "TEAM_ONBOARDING"))
                .statusCode());
        assertEquals(0L, rowCount("select count(*) from operating_teams where code = 'p3a-expired-admin'"));
    }

    // ------------------------------------------------------------------ API City-deactivation race

    @Test
    void governedCreationRacingCityDeactivationThroughTheRealApiHoldsItsInvariantInBothOrders() throws Exception {
        // Order A: the deactivation is already queued for the City guard when the governed creation arrives.
        assertApiRaceOutcome(RaceOrder.DEACTIVATION_FIRST);
        // Order B: the governed creation is already queued for the City guard when the deactivation arrives.
        assertApiRaceOutcome(RaceOrder.CREATION_FIRST);
        // Neither order may ever leave an inactive City holding an active Team.
        assertEquals(0L, rowCount("select count(*) from supported_cities c where c.active = false and exists "
                + "(select 1 from operating_teams t where t.city_id = c.id and t.active = true)"));
    }

    private enum RaceOrder { DEACTIVATION_FIRST, CREATION_FIRST }

    private void assertApiRaceOutcome(RaceOrder order) throws Exception {
        // Canonical lowercase slug: the City code must be hyphenated, never underscored.
        String label = "api-race-" + order.name().toLowerCase().replace('_', '-');
        Actor admin = globalAdmin(label);
        SupportedCity city = activeCity(label);
        long expectedCityVersion = cityVersion(city.getId());
        String code = "p3a-" + label;

        Connection holder = cityRowLockHolder(city.getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> deactivation = () -> {
                try {
                    ownership.deactivateCity(admin.user().getId(), city.getId(), expectedCityVersion,
                            "P3A_CITY_DEACTIVATION_RACE");
                    return "COMMITTED";
                } catch (VisitOperationsConflictException conflict) {
                    return "CONFLICT";
                }
            };
            Callable<Integer> creation = () -> send("POST", teamsPath(city.getId()), admin.token(),
                    createBody(code, "Api Race", expectedCityVersion, "TEAM_ONBOARDING")).statusCode();

            Future<String> deactivationOutcome;
            Future<Integer> creationStatus;
            if (order == RaceOrder.DEACTIVATION_FIRST) {
                deactivationOutcome = pool.submit(deactivation);
                awaitCityGuardWaiters(1);
                creationStatus = pool.submit(creation);
                awaitCityGuardWaiters(2);
            } else {
                creationStatus = pool.submit(creation);
                awaitCityGuardWaiters(1);
                deactivationOutcome = pool.submit(deactivation);
                awaitCityGuardWaiters(2);
            }
            release(holder);
            holder = null;

            int status = creationStatus.get(60, TimeUnit.SECONDS);
            String outcome = deactivationOutcome.get(60, TimeUnit.SECONDS);
            if (order == RaceOrder.DEACTIVATION_FIRST) {
                // The City deactivation won the guard: the City is inactive and the late creation conflicts.
                assertEquals("COMMITTED", outcome);
                assertEquals(409, status);
                assertEquals(Boolean.FALSE, jdbc.queryForObject("select active from supported_cities where id = ?",
                        Boolean.class, city.getId()));
                assertEquals(0L, rowCount("select count(*) from operating_teams where city_id = ?", city.getId()));
            } else {
                // The governed creation won the guard: the City stays active and the deactivation conflicts.
                assertEquals(201, status);
                assertEquals("CONFLICT", outcome);
                assertEquals(Boolean.TRUE, jdbc.queryForObject("select active from supported_cities where id = ?",
                        Boolean.class, city.getId()));
                assertEquals(Boolean.TRUE, jdbc.queryForObject(
                        "select active from operating_teams where city_id = ? and code = ?", Boolean.class,
                        city.getId(), code));
                assertEquals(1L, creationAuditCountForCode(city.getId(), code));
            }
        } finally {
            release(holder);
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ audit atomicity

    @Test
    void auditFailureRollsBackTheTeamCreation() throws Exception {
        Actor admin = globalAdmin("atomic");
        SupportedCity city = activeCity("atomic");
        String body = createBody("p3a-atomic", "Atomic Team", cityVersion(city.getId()), "TEAM_ONBOARDING");

        jdbc.execute("create or replace function " + SCHEMA + ".p3a_reject_team_audit() returns trigger "
                + "language plpgsql as $$ begin if new.action_code = 'OPERATING_TEAM_CREATED' then "
                + "raise exception 'P3A_TEST_AUDIT_FAILURE'; end if; return new; end $$");
        jdbc.execute("create trigger p3a_reject_team_audit before insert on " + SCHEMA
                + ".operational_audit_events for each row execute function "
                + SCHEMA + ".p3a_reject_team_audit()");
        try {
            assertEquals(500, send("POST", teamsPath(city.getId()), admin.token(), body).statusCode());
            assertEquals(0L, rowCount("select count(*) from operating_teams where city_id = ? and code = ?",
                    city.getId(), "p3a-atomic"));
        } finally {
            jdbc.execute("drop trigger if exists p3a_reject_team_audit on " + SCHEMA
                    + ".operational_audit_events");
            jdbc.execute("drop function if exists " + SCHEMA + ".p3a_reject_team_audit()");
        }

        assertEquals(201, send("POST", teamsPath(city.getId()), admin.token(), body).statusCode());
        assertEquals(1L, rowCount("select count(*) from operating_teams where city_id = ? and code = ?",
                city.getId(), "p3a-atomic"));
    }

    // ------------------------------------------------------------------ bounded reads

    @Test
    void collectionReadsAreActiveScopedDeterministicPagedAndUncached() throws Exception {
        Actor admin = globalAdmin("reads");
        SupportedCity city = activeCity("reads");
        SupportedCity inactiveCity = activeCity("reads-inactive");
        inactiveCity.setActive(false);
        cities.saveAndFlush(inactiveCity);
        assertEquals(201, send("POST", teamsPath(city.getId()), admin.token(),
                createBody("p3a-reads-a", "Reads A", cityVersion(city.getId()), "TEAM_ONBOARDING")).statusCode());
        assertEquals(201, send("POST", teamsPath(city.getId()), admin.token(),
                createBody("p3a-reads-b", "Reads B", cityVersion(city.getId()), "TEAM_ONBOARDING")).statusCode());
        OperatingTeam retired = teams.findByCityIdAndCode(city.getId(), "p3a-reads-b").orElseThrow();
        retired.setActive(false);
        teams.saveAndFlush(retired);

        HttpResponse<String> citiesPage = send("GET", citiesPath() + "?page=0&size=100", admin.token(), null);
        assertEquals(200, citiesPage.statusCode(), citiesPage.body());
        assertEquals("no-store", citiesPage.headers().firstValue("Cache-Control").orElse("missing"));
        JsonNode citiesBody = mapper.readTree(citiesPage.body());
        assertEquals(0, citiesBody.path("page").asInt());
        assertEquals(100, citiesBody.path("size").asInt());
        assertTrue(citiesBody.path("totalCount").asLong() >= 1);
        List<Long> returnedCityIds = new ArrayList<>();
        citiesBody.path("items").forEach(item -> {
            returnedCityIds.add(item.path("id").asLong());
            assertTrue(item.path("active").asBoolean());
        });
        assertTrue(returnedCityIds.contains(city.getId()));
        assertFalse(returnedCityIds.contains(inactiveCity.getId()));

        HttpResponse<String> teamsPage = send("GET", teamsPath(city.getId()) + "?page=0&size=1",
                admin.token(), null);
        assertEquals(200, teamsPage.statusCode(), teamsPage.body());
        assertEquals("no-store", teamsPage.headers().firstValue("Cache-Control").orElse("missing"));
        JsonNode teamsBody = mapper.readTree(teamsPage.body());
        assertEquals(city.getId(), teamsBody.path("city").path("id").asLong());
        assertEquals(1, teamsBody.path("items").size());
        assertEquals(2L, teamsBody.path("totalCount").asLong());
        assertEquals(2L, teamsBody.path("totalPages").asLong());
        assertEquals("p3a-reads-a", teamsBody.path("items").get(0).path("code").asText());

        JsonNode secondPage = mapper.readTree(send("GET", teamsPath(city.getId()) + "?page=1&size=1",
                admin.token(), null).body());
        assertEquals("p3a-reads-b", secondPage.path("items").get(0).path("code").asText());
        assertFalse(secondPage.path("items").get(0).path("active").asBoolean());

        JsonNode beyondRange = mapper.readTree(send("GET", teamsPath(city.getId()) + "?page=50&size=20",
                admin.token(), null).body());
        assertEquals(0, beyondRange.path("items").size());
        assertEquals(2L, beyondRange.path("totalCount").asLong());
    }

    // ------------------------------------------------------------------ harness

    private record Actor(User user, String token) {}

    private Actor user(String label, Role role, String profileRole, Boolean staffActive) {
        User user = new User();
        user.setEmail("p3a-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("+91987655" + String.format("%04d", Math.abs(label.hashCode()) % 10_000));
        user.setRole(role);
        user = users.saveAndFlush(user);
        if (profileRole != null) {
            EmployeeProfile profile = employees.saveAndFlush(
                    new EmployeeProfile(user, profileRole, null, BigDecimal.ZERO));
            if (Boolean.TRUE.equals(staffActive)) {
                profile.setStaffActive(true);
                employees.saveAndFlush(profile);
            }
        }
        return new Actor(user, jwt.generateToken(user.getId(), user.getEmail(), user.getRole().name()));
    }

    private Actor globalAdmin(String label) {
        Actor admin = user(label + "-admin", Role.ROLE_ADMIN, "OPERATIONS", true);
        globalAdminGrant(admin, Instant.now().minusSeconds(60));
        return admin;
    }

    private StaffAccessGrant globalAdminGrant(Actor actor, Instant effectiveAt) {
        return grantRows.saveAndFlush(new StaffAccessGrant(actor.user(), StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, effectiveAt, null, null, Instant.now(), "TEST_P3A",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private void cityScoped(Actor actor, StaffCapability capability, SupportedCity city) {
        grantRows.saveAndFlush(new StaffAccessGrant(actor.user(), capability, StaffScopeType.CITY, city, null,
                Instant.now().minusSeconds(60), null, null, Instant.now(), "TEST_P3A",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private StaffAccessGrant teamScoped(Actor actor, StaffCapability capability, SupportedCity city) {
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city,
                "p3a-grant-" + UUID.randomUUID().toString().substring(0, 8), "Grant Team", true));
        return grantRows.saveAndFlush(new StaffAccessGrant(actor.user(), capability, StaffScopeType.TEAM,
                null, team, Instant.now().minusSeconds(60), null, null, Instant.now(), "TEST_P3A",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private SupportedCity activeCity(String label) {
        return cities.saveAndFlush(new SupportedCity("p3a-" + label + "-"
                + UUID.randomUUID().toString().substring(0, 8), "P3A " + label, true));
    }

    private String citiesPath() {
        return "/api/v1/staff/admin/operating-teams/cities";
    }

    private String teamsPath(long cityId) {
        return "/api/v1/staff/admin/cities/" + cityId + "/operating-teams";
    }

    private static String createBody(String code, String displayName, Long expectedCityVersion, String reasonCode) {
        return "{\"code\":" + json(code) + ",\"displayName\":" + json(displayName)
                + ",\"expectedCityVersion\":" + expectedCityVersion + ",\"reasonCode\":" + json(reasonCode) + "}";
    }

    private static String json(String value) {
        if (value == null) return "null";
        StringBuilder escaped = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private long cityVersion(long cityId) {
        return jdbc.queryForObject("select version from supported_cities where id = ?", Long.class, cityId);
    }

    private long rowCount(String sql, Object... arguments) {
        Long count = jdbc.queryForObject(sql, Long.class, arguments);
        return count == null ? 0L : count;
    }

    private long creationAuditCount(long teamId) {
        return rowCount("select count(*) from operational_audit_events where action_code = "
                + "'OPERATING_TEAM_CREATED' and target_type = 'OPERATING_TEAM' and target_id = ?", teamId);
    }

    private long creationAuditCountForCode(long cityId, String code) {
        return rowCount("select count(*) from operational_audit_events a join operating_teams t "
                + "on t.id = a.target_id where a.action_code = 'OPERATING_TEAM_CREATED' and t.city_id = ? "
                + "and t.code = ?", cityId, code);
    }

    /** Holds the governance advisory lock on a second disposable connection until the holder is released. */
    private Connection governanceLockHolder() throws Exception {
        Connection holder = DriverManager.getConnection(schemaUrl(), target.username(), target.password());
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.execute("select pg_advisory_xact_lock(" + GOVERNANCE_ADVISORY_LOCK_KEY_1 + ", "
                    + GOVERNANCE_ADVISORY_LOCK_KEY_2 + ")");
        }
        return holder;
    }

    /** Holds the City row lock on a second disposable connection until the holder is released. */
    private Connection cityRowLockHolder(long cityId) throws Exception {
        Connection holder = DriverManager.getConnection(schemaUrl(), target.username(), target.password());
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.execute("select id from supported_cities where id = " + cityId + " for update");
        }
        return holder;
    }

    /** Always releases a lock holder, including when the test fails. */
    private static void release(Connection connection) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (Exception ignored) {
            // A holder that already lost its connection has released every lock it held.
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // Closing is best effort; the lock is released by the rollback above.
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /** Bounded, not millisecond-exact: the command must wait for its bound and then fail well inside the run. */
    private static void assertBoundedWait(long elapsedMillis) {
        assertTrue(elapsedMillis >= MINIMUM_BOUNDED_WAIT_MILLIS,
                "the command must wait for the lock instead of failing instantly (waited " + elapsedMillis + "ms)");
        assertTrue(elapsedMillis <= MAXIMUM_BOUNDED_WAIT_MILLIS,
                "the command must fail within its bounded wait rather than hang (waited " + elapsedMillis + "ms)");
    }

    private void assertSanitizedUnavailable(HttpResponse<String> response) throws Exception {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse("missing"));
        JsonNode body = mapper.readTree(response.body());
        assertEquals(503, body.path("status").asInt(), response.body());
        assertEquals("Service Unavailable", body.path("error").asText(), response.body());
        assertEquals("The Team could not be created because the database was busy. Please try again shortly.",
                body.path("message").asText(), response.body());
        assertTrue(body.path("path").asText().endsWith("/operating-teams"), response.body());
        for (String forbidden : List.of("supported_cities", "operating_teams", "staff_access_grants", "SQLSTATE",
                "55P03", "57014", "SQLException", "canceling statement", "ERROR:", "jdbc:", "org.postgresql",
                "pg_advisory", "pathome_db", "pathome_oe_test_integration", "select ", "insert ")) {
            assertFalse(response.body().contains(forbidden),
                    "the 503 response must not leak '" + forbidden + "': " + response.body());
        }
    }

    /** Waits until exactly the expected number of sessions are blocked on the City row lock. */
    private void awaitCityGuardWaiters(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000L;
        try (Connection probe = DriverManager.getConnection(schemaUrl(), target.username(), target.password());
             Statement statement = probe.createStatement()) {
            while (System.currentTimeMillis() < deadline) {
                try (ResultSet rows = statement.executeQuery(
                        "select count(*) from pg_stat_activity where datname = current_database() "
                                + "and wait_event_type = 'Lock' and query like '%supported_cities%'")) {
                    rows.next();
                    if (rows.getInt(1) >= expected) return;
                }
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException(
                "expected " + expected + " session(s) blocked on the City guard, none arrived in time");
    }

    private void awaitBlockedDeactivation(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try (Connection probe = DriverManager.getConnection(schemaUrl(), target.username(), target.password());
             Statement statement = probe.createStatement()) {
            while (System.currentTimeMillis() < deadline) {
                try (var rows = statement.executeQuery("select count(*) from pg_stat_activity where datname = "
                        + "current_database() and wait_event_type = 'Lock' and query like '%supported_cities%'")) {
                    rows.next();
                    if (rows.getLong(1) > 0) return;
                }
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("The City deactivation never blocked on the held City row lock");
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        try {
            return HTTP.send(request.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .timeout(Duration.ofSeconds(60))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
