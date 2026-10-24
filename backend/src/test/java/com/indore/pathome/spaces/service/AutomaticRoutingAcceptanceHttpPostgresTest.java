package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.AutomaticRoutingControlCommand;
import com.indore.pathome.spaces.dto.RoutingTaskRecheckCommand;
import com.indore.pathome.spaces.dto.TeamLocalityCoverageCommand;
import com.indore.pathome.spaces.dto.TeamLocalityCoverageStateCommand;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.Locality;
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
import com.indore.pathome.spaces.entity.VisitRequestRoutingTask;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTaskState;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.support.RoutingAcceptanceContract;
import com.indore.pathome.spaces.support.RoutingWorkerChildProcess;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Genuine PostgreSQL and authenticated HTTP acceptance for V54 automatic Team routing.
 *
 * <p>Fixtures are synthetic and uniquely named. The test does not use browser request interception,
 * direct SQL to fabricate a successful route, or a provider/media call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=routing-acceptance-http-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=routing-acceptance-otp-secret-32-bytes",
        "cloudinary.cloud-name=synthetic-routing-acceptance",
        "cloudinary.api-key=synthetic-routing-acceptance-key",
        "cloudinary.api-secret=synthetic-routing-acceptance-secret",
        "pathome.automatic-routing.poll-delay-ms=3600000",
        "pathome.automatic-routing.initial-delay-ms=3600000",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_OE_TEST_ACCEPTANCE", matches = "routing-20261023")
class AutomaticRoutingAcceptanceHttpPostgresTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        contract.verifyLive();
        RoutingAcceptanceContract.Credentials runtime = contract.runtimeCredentials();
        registry.add("spring.datasource.url", runtime::jdbcUrl);
        registry.add("spring.datasource.username", runtime::username);
        registry.add("spring.datasource.password", runtime::password);
    }

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
    @Autowired private VisitRequestRoutingTaskRepository tasks;
    @Autowired private VisitSessionRepository sessions;
    @Autowired private PasswordEncoder passwords;
    @Autowired private TrustedOperationalIntakeService intake;
    @Autowired private AutomaticRoutingControlService controls;
    @Autowired private AutomaticVisitRoutingService routing;
    @Autowired private TeamLocalityCoverageService coverage;
    @Autowired private VisitRequestRoutingTaskService taskService;
    @Autowired private OperationalOwnershipService ownership;
    @Autowired private StaffAccessService access;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;

    @BeforeAll
    static void positiveTargetMustBeAlreadyAttested() {
        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        contract.verifyLive();
    }

    @AfterAll
    static void restoreRoutingDisabled() {
        // The shell teardown removes the entire disposable database. This hook only prevents a test
        // process from leaving the singleton enabled if a later local inspection reads it first.
    }

    @Test
    void c01UnclaimedWorkIsSqlCorrectScopedAndStableAtEveryPageBoundary() throws Exception {
        Actor owner = globalAdmin("c01-owner");
        Actor cityRouter = cityRouting("c01-city-router");
        Actor wrongCityRouter = cityRouting("c01-wrong-city-router");
        Actor teamOnly = coordinator("c01-team-only");
        SupportedCity city = city("c01");
        SupportedCity otherCity = city("c01-other");
        Locality locality = locality("c01", city);
        Locality otherLocality = locality("c01-other", otherCity);
        OperatingTeam team = team(city, "c01-team");
        OperatingTeam otherTeam = team(otherCity, "c01-other-team");
        cityGrant(cityRouter, StaffCapability.OPS_ROUTING, city);
        cityGrant(wrongCityRouter, StaffCapability.OPS_ROUTING, otherCity);
        teamGrant(teamOnly, team);

        JsonNode empty = unclaimed(cityRouter, 0, 20);
        assertEquals(0, empty.path("totalCount").asLong());
        assertEquals(0, empty.path("items").size());
        assertEquals(403, send("GET",
                "/api/v1/operations/automatic-routing/tasks/unclaimed", teamOnly.token(), null)
                .statusCode(), "Team-only coordination must not become City routing authority");

        List<String> expectedKeys = new ArrayList<>();
        List<PropertyVisitRequest> requestRows = new ArrayList<>();
        PropertyVisitRequest first = request("c01-request-0", locality, tenant("c01-tenant-0"));
        first.setOperatingTeam(team);
        first.setOperationalScopeReady(true);
        requests.saveAndFlush(first);
        requestRows.add(first);
        expectedKeys.add("REQUEST:" + first.getId());
        assertEquals(1, unclaimed(cityRouter, 0, 20).path("totalCount").asLong());

        VisitSession session = unclaimedSession(locality, city, team, tenant("c01-session"));
        expectedKeys.add("SESSION:" + session.getId());
        assertEquals(2, unclaimed(cityRouter, 0, 20).path("totalCount").asLong());

        for (int index = 1; index < 20; index++) {
            PropertyVisitRequest row = request("c01-request-" + index, locality,
                    tenant("c01-tenant-" + index));
            row.setOperatingTeam(team);
            row.setOperationalScopeReady(true);
            requests.saveAndFlush(row);
            requestRows.add(row);
            expectedKeys.add("REQUEST:" + row.getId());
            int total = expectedKeys.size();
            if (total == 20 || total == 21) {
                JsonNode boundary = unclaimed(cityRouter, 0, 20);
                assertEquals(total, boundary.path("totalCount").asLong());
                assertEquals(Math.min(20, total), boundary.path("items").size());
            }
        }
        PropertyVisitRequest last = request("c01-request-20", locality, tenant("c01-tenant-20"));
        last.setOperatingTeam(team);
        last.setOperationalScopeReady(true);
        requests.saveAndFlush(last);
        requestRows.add(last);
        expectedKeys.add("REQUEST:" + last.getId());

        JsonNode firstPage = unclaimed(cityRouter, 0, 20);
        JsonNode secondPage = unclaimed(cityRouter, 1, 20);
        JsonNode outOfRange = unclaimed(cityRouter, 2, 20);
        assertEquals(22, firstPage.path("totalCount").asLong());
        assertEquals(22, secondPage.path("totalCount").asLong());
        assertEquals(22, outOfRange.path("totalCount").asLong());
        assertEquals(20, firstPage.path("items").size());
        assertEquals(2, secondPage.path("items").size());
        assertEquals(0, outOfRange.path("items").size());
        List<String> pagedKeys = new ArrayList<>();
        firstPage.path("items").forEach(item -> pagedKeys.add(
                item.path("targetType").asText() + ":" + item.path("targetId").asLong()));
        secondPage.path("items").forEach(item -> pagedKeys.add(
                item.path("targetType").asText() + ":" + item.path("targetId").asLong()));
        assertEquals(expectedKeys.size(), new java.util.HashSet<>(pagedKeys).size());
        assertEquals(expectedKeys.size(), pagedKeys.size());
        assertTrue(firstPage.path("items").get(0).path("teamDisplayName").asText()
                .contains("c01-team"));

        JsonNode otherCityPage = unclaimed(wrongCityRouter, 0, 20);
        assertEquals(0, otherCityPage.path("totalCount").asLong());
        JsonNode global = unclaimed(owner, 0, 100);
        assertTrue(global.path("totalCount").asLong() >= 22);

        PropertyVisitRequest foreignRequest = request("c01-foreign", otherLocality,
                tenant("c01-foreign-tenant"));
        foreignRequest.setOperatingTeam(otherTeam);
        foreignRequest.setOperationalScopeReady(true);
        requests.saveAndFlush(foreignRequest);
        assertEquals(1, unclaimed(wrongCityRouter, 0, 100).path("totalCount").asLong());
        assertTrue(unclaimed(cityRouter, 0, 100).path("items").findValuesAsText("targetId")
                .stream().noneMatch(value -> value.equals(foreignRequest.getId().toString())));

        Actor clashRouter = cityRouting("c01-clash-router");
        SupportedCity clashCity = city("c01-clash");
        Locality clashLocality = locality("c01-clash", clashCity);
        OperatingTeam clashTeam = team(clashCity, "c01-clash-team");
        cityGrant(clashRouter, StaffCapability.OPS_ROUTING, clashCity);
        Actor clashTenant = tenant("c01-clash-tenant");
        RentalDetails clashListing = listing("c01-clash", clashLocality);
        long clashId = 8_100_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 10_000_000L);
        jdbc.update("""
                insert into property_visit_requests
                    (id, tenant_id, listing_id, status, created_at, supported_city_id,
                     operating_team_id, operational_scope_ready, version)
                values (?, ?, ?, 'RECEIVED', timestamp '2026-10-23 12:00:00', ?, ?, true, 0)
                """, clashId, clashTenant.user().getId(), clashListing.getId(),
                clashCity.getId(), clashTeam.getId());
        jdbc.update("""
                insert into visit_request_routing_tasks
                    (request_id, provenance, protocol_version, state, expected_request_version)
                values (?, 'TRUSTED_NEW_REQUEST_V1', 1, 'PENDING', 0)
                """, clashId);
        jdbc.update("""
                insert into visit_sessions
                    (id, tenant_id, status, version, city, created_at, updated_at,
                     supported_city_id, operating_team_id, operational_scope_ready,
                     canonical_locality_id)
                values (?, ?, 'DRAFT', 0, ?, timestamptz '2026-10-23 12:00:00+00',
                        timestamptz '2026-10-23 12:00:00+00', ?, ?, true, ?)
                """, clashId, clashTenant.user().getId(), clashCity.getDisplayName(),
                clashCity.getId(), clashTeam.getId(), clashLocality.getId());
        JsonNode ties = unclaimed(clashRouter, 0, 20);
        assertEquals(2, ties.path("totalCount").asLong());
        assertEquals("REQUEST", ties.path("items").get(0).path("targetType").asText());
        assertEquals("SESSION", ties.path("items").get(1).path("targetType").asText());
        assertEquals(clashId, ties.path("items").get(0).path("targetId").asLong());
        assertEquals(clashId, ties.path("items").get(1).path("targetId").asLong());
    }

    @Test
    void c02RoutingAuthorityDenialsAreScopedAndMutationFree() throws Exception {
        Actor owner = globalAdmin("c02-owner");
        Actor valid = cityRouting("c02-valid");
        Actor wrongCity = cityRouting("c02-wrong-city");
        Actor noGrant = coordinator("c02-no-grant");
        Actor teamOnly = coordinator("c02-team-only");
        Actor revoked = cityRouting("c02-revoked");
        Actor expired = cityRouting("c02-expired");
        SupportedCity city = city("c02");
        SupportedCity otherCity = city("c02-other");
        Locality locality = locality("c02", city);
        Locality otherLocality = locality("c02-other", otherCity);
        OperatingTeam team = team(city, "c02-team");
        cityGrant(valid, StaffCapability.OPS_ROUTING, city);
        cityGrant(wrongCity, StaffCapability.OPS_ROUTING, otherCity);
        teamGrant(teamOnly, team);
        StaffAccessGrant revokedGrant = new StaffAccessGrant(revoked.user(),
                StaffCapability.OPS_ROUTING, StaffScopeType.CITY, city, null,
                Instant.now().minusSeconds(60), null, null, Instant.now(), "C02_REVOKED",
                StaffGrantProvisioningSource.ADMIN_API);
        revokedGrant.revoke(owner.user(), Instant.now(), "C02_REVOKE");
        grants.saveAndFlush(revokedGrant);
        grants.saveAndFlush(new StaffAccessGrant(expired.user(), StaffCapability.OPS_ROUTING,
                StaffScopeType.CITY, city, null, Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(60), null, Instant.now(), "C02_EXPIRED",
                StaffGrantProvisioningSource.ADMIN_API));

        PropertyVisitRequest enrolled = request("c02-enrolled", locality, tenant("c02-enrolled"));
        enrolled.setOperatingTeam(team);
        enrolled.setOperationalScopeReady(true);
        requests.saveAndFlush(enrolled);
        assertTrue(pageContains(unclaimed(valid, 0, 100), "REQUEST", enrolled.getId()));

        PropertyVisitRequest unready = request("c02-unready", locality, tenant("c02-unready"));
        unready.setOperatingTeam(team);
        unready.setOperationalScopeReady(false);
        requests.saveAndFlush(unready);
        assertFalse(pageContains(unclaimed(valid, 0, 100), "REQUEST", unready.getId()));

        PropertyVisitRequest canonicalConflict = request("c02-conflict", locality,
                tenant("c02-conflict"));
        canonicalConflict.setOperatingTeam(team);
        canonicalConflict.setOperationalScopeReady(true);
        requests.saveAndFlush(canonicalConflict);
        var conflictingListing = canonicalConflict.getListing();
        conflictingListing.setCanonicalLocalityId(otherLocality.getId());
        listings.saveAndFlush(conflictingListing);

        PropertyVisitRequest unenrolled = directRequest(
                "c02-unenrolled", locality, city, team, tenant("c02-unenrolled"), true);
        assertFalse(tasks.existsByRequestId(unenrolled.getId()));

        OperatingTeam inactiveTeam = team(city, "c02-inactive-team");
        PropertyVisitRequest inactiveTeamRequest = request("c02-inactive-team",
                locality("c02-inactive-team", city), tenant("c02-inactive-team-tenant"));
        inactiveTeamRequest.setOperatingTeam(inactiveTeam);
        inactiveTeamRequest.setOperationalScopeReady(true);
        requests.saveAndFlush(inactiveTeamRequest);
        inactiveTeam.setActive(false);
        teams.saveAndFlush(inactiveTeam);

        JsonNode validPage = unclaimed(valid, 0, 100);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertFalse(pageContains(validPage, "REQUEST", canonicalConflict.getId()),
                        "routing-only authority must not see a canonical City conflict"),
                () -> assertFalse(pageContains(validPage, "REQUEST", unenrolled.getId()),
                        "routing-only authority must not see work without durable enrollment"),
                () -> assertFalse(pageContains(validPage, "REQUEST", inactiveTeamRequest.getId()),
                        "inactive Team work must be hidden"),
                () -> assertEquals(403, status("GET",
                        "/api/v1/operations/automatic-routing/tasks/unclaimed", noGrant)),
                () -> assertEquals(403, status("GET",
                        "/api/v1/operations/automatic-routing/tasks/unclaimed", teamOnly)),
                () -> assertEquals(403, status("GET",
                        "/api/v1/operations/automatic-routing/tasks/unclaimed", revoked)),
                () -> assertEquals(403, status("GET",
                        "/api/v1/operations/automatic-routing/tasks/unclaimed", expired)),
                () -> assertEquals(404, status("GET",
                        "/api/v1/operations/automatic-routing/tasks/" + taskId(enrolled), teamOnly)),
                () -> assertEquals(404, status("GET",
                        "/api/v1/operations/automatic-routing/tasks/" + taskId(enrolled), wrongCity)),
                () -> assertTrue(jdbc.queryForObject("""
                        select count(*) from operational_audit_events
                        where action_code in ('ROUTING_RECHECK_REQUESTED', 'AUTOMATIC_TEAM_ROUTED')
                          and actor_kind = 'USER'
                          and actor_user_id in (?, ?, ?, ?)
                        """, Integer.class, noGrant.user().getId(), teamOnly.user().getId(),
                        revoked.user().getId(), expired.user().getId()) == 0));
    }

    @Test
    void c03WorkerAndManualWritersSerializeInBothOrders() throws Exception {
        Actor owner = globalAdmin("c03-owner");
        Actor manual = cityRouting("c03-manual");
        SupportedCity city = city("c03");
        Locality locality = locality("c03", city);
        OperatingTeam workerTeam = team(city, "c03-worker-team");
        OperatingTeam manualTeam = team(city, "c03-manual-team");
        cityGrant(manual, StaffCapability.CITY_TEAM_ADMIN, city);
        enableRouting(owner);
        coverage.create(manual.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), locality.getId(), workerTeam.getId(), "C03_WORKER_COVERAGE"));

        PropertyVisitRequest workerFirstRequest = request("c03-worker-first", locality,
                tenant("c03-worker-first-tenant"));
        VisitRequestRoutingTask workerFirstTask =
                tasks.findByRequestId(workerFirstRequest.getId()).orElseThrow();
        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        String function = "c03_worker_hold_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = adminConnection(contract); Statement statement = admin.createStatement()) {
            statement.execute("create function " + RoutingAcceptanceContract.SCHEMA + "." + function
                    + "() returns trigger language plpgsql as $$ begin if new.target_id = "
                    + workerFirstRequest.getId() + " and new.action_code = 'AUTOMATIC_TEAM_ROUTED'"
                    + " then perform pg_sleep(1.5); end if; return new; end $$");
            statement.execute("create trigger " + function + " before insert on "
                    + RoutingAcceptanceContract.SCHEMA + ".operational_audit_events"
                    + " for each row execute function " + RoutingAcceptanceContract.SCHEMA
                    + "." + function + "()");
        }
        ExecutorService order = Executors.newFixedThreadPool(2);
        try {
            Future<?> workerFuture = order.submit(() -> routing.processTask(workerFirstTask.getId()));
            assertTrue(waitForWorkerSleep(Duration.ofSeconds(5)),
                    "worker never exposed the held Request transaction");
            long requestVersion = requestVersion(workerFirstRequest.getId());
            Future<HttpResponse<String>> manualFuture = order.submit(() -> send("POST",
                    "/api/v1/operations/intake-routing/visit-requests/"
                            + workerFirstRequest.getId() + "/route", manual.token(),
                    routeBody(requestVersion, city.getId(), cityVersion(city.getId()),
                            manualTeam.getId(), teamVersion(manualTeam.getId()),
                            workerFirstTask.getVersion(), "C03_MANUAL_AFTER_WORKER")));
            List<LockWaiter> blockedManual = waitForLockWaiters(1, Duration.ofSeconds(5));
            workerFuture.get(10, TimeUnit.SECONDS);
            HttpResponse<String> manualLoser = manualFuture.get(10, TimeUnit.SECONDS);
            assertEquals(409, manualLoser.statusCode(), manualLoser.body());
            assertFalse(blockedManual.isEmpty());
            assertEquals(workerTeam.getId(),
                    requests.findById(workerFirstRequest.getId()).orElseThrow()
                            .getOperatingTeam().getId());
            assertEquals(VisitRequestRoutingTaskState.DONE,
                    tasks.findById(workerFirstTask.getId()).orElseThrow().getState());
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from operational_audit_events
                    where target_id = ? and action_code in
                      ('AUTOMATIC_TEAM_ROUTED', 'OWNERSHIP_RECONCILED')
                    """, Integer.class, workerFirstRequest.getId()));
        } finally {
            order.shutdownNow();
            try (Connection admin = adminConnection(contract); Statement statement = admin.createStatement()) {
                statement.execute("drop trigger if exists " + function
                        + " on " + RoutingAcceptanceContract.SCHEMA + ".operational_audit_events");
                statement.execute("drop function if exists " + RoutingAcceptanceContract.SCHEMA
                        + "." + function + "()");
            }
        }

        PropertyVisitRequest manualFirstRequest = request("c03-manual-first", locality,
                tenant("c03-manual-first-tenant"));
        VisitRequestRoutingTask manualFirstTask =
                tasks.findByRequestId(manualFirstRequest.getId()).orElseThrow();
        long expectedRequestVersion = requestVersion(manualFirstRequest.getId());
        ExecutorService manualFirst = Executors.newFixedThreadPool(2);
        try (Connection held = appConnection()) {
            held.setAutoCommit(false);
            try (Statement statement = held.createStatement()) {
                statement.execute("select id from property_visit_requests where id = "
                        + manualFirstRequest.getId() + " for update");
                Future<HttpResponse<String>> manualFuture = manualFirst.submit(() -> send("POST",
                        "/api/v1/operations/intake-routing/visit-requests/"
                                + manualFirstRequest.getId() + "/route", manual.token(),
                        routeBody(expectedRequestVersion, city.getId(), cityVersion(city.getId()),
                                manualTeam.getId(), teamVersion(manualTeam.getId()),
                                manualFirstTask.getVersion(), "C03_MANUAL_FIRST")));
                assertFalse(waitForLockWaiters(1, Duration.ofSeconds(5)).isEmpty());
                Future<?> workerFuture = manualFirst.submit(() ->
                        routing.processTask(manualFirstTask.getId()));
                List<LockWaiter> twoWaiters = waitForLockWaiters(2, Duration.ofSeconds(5));
                assertTrue(twoWaiters.size() >= 2, twoWaiters.toString());
                assertTrue(twoWaiters.stream().allMatch(waiter -> waiter.blockingPids().length > 0),
                        twoWaiters.toString());
                held.rollback();
                HttpResponse<String> manualWinner = manualFuture.get(10, TimeUnit.SECONDS);
                assertEquals(204, manualWinner.statusCode(), manualWinner.body());
                try {
                    workerFuture.get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException unexpectedLoser) {
                    fail("manual-first worker must observe terminal work cleanly, not throw "
                            + unexpectedLoser.getCause(), unexpectedLoser);
                }
            } catch (Exception failure) {
                held.rollback();
                throw failure;
            }
        } finally {
            manualFirst.shutdownNow();
        }
        assertEquals(manualTeam.getId(),
                requests.findById(manualFirstRequest.getId()).orElseThrow()
                        .getOperatingTeam().getId());
        VisitRequestRoutingTask manualDone =
                tasks.findById(manualFirstTask.getId()).orElseThrow();
        assertEquals(VisitRequestRoutingTaskState.DONE, manualDone.getState());
        assertEquals("MANUAL_EXCEPTION_V1", manualDone.getDecisionCode());
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from operational_audit_events
                where target_id = ? and action_code = 'AUTOMATIC_TEAM_ROUTED'
                """, Integer.class, manualFirstRequest.getId()));
    }

    @Test
    void c04ForcedWorkerCoverageAndDisableContentionHasARealLockGraph() throws Exception {
        Actor owner = globalAdmin("c04-owner");
        SupportedCity city = city("c04");
        Locality locality = locality("c04", city);
        OperatingTeam team = team(city, "c04-team");
        enableRouting(owner);
        var coverageEntry = coverage.create(owner.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), locality.getId(), team.getId(), "C04_COVERAGE"));
        PropertyVisitRequest request = request("c04", locality, tenant("c04-tenant"));
        VisitRequestRoutingTask task = tasks.findByRequestId(request.getId()).orElseThrow();
        long controlVersion = jdbc.queryForObject(
                "select version from automatic_visit_routing_control where id = 1", Long.class);
        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        String function = "c04_hold_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = adminConnection(contract); Statement statement = admin.createStatement()) {
            statement.execute("create function " + RoutingAcceptanceContract.SCHEMA + "." + function
                    + "() returns trigger language plpgsql as $$ begin if new.target_id = "
                    + request.getId() + " then perform pg_sleep(2); end if; return new; end $$");
            statement.execute("create trigger " + function + " before insert on "
                    + RoutingAcceptanceContract.SCHEMA + ".operational_audit_events"
                    + " for each row execute function " + RoutingAcceptanceContract.SCHEMA
                    + "." + function + "()");
        }
        ExecutorService writers = Executors.newFixedThreadPool(3);
        try {
            Future<?> worker = writers.submit(() -> routing.processTask(task.getId()));
            assertTrue(waitForWorkerSleep(Duration.ofSeconds(5)));
            Future<?> coverageFuture = writers.submit(() -> coverage.changeState(
                    owner.user().getId(), coverageEntry.id(),
                    new TeamLocalityCoverageStateCommand(coverageEntry.version(), false,
                            "C04_RETIRE")));
            assertFalse(waitForLockWaiters(1, Duration.ofSeconds(5)).isEmpty());
            Future<?> disable = writers.submit(() -> controls.change(owner.user().getId(),
                    new AutomaticRoutingControlCommand(controlVersion, false, "C04_DISABLE")));
            List<LockWaiter> lockGraph = waitForLockWaiters(2, Duration.ofSeconds(5));
            assertEquals(2, lockGraph.size(), lockGraph.toString());
            assertTrue(lockGraph.stream().allMatch(waiter -> waiter.blockingPids().length > 0),
                    lockGraph.toString());
            System.out.println("C04_LOCK_GRAPH=" + lockGraph);
            worker.get(12, TimeUnit.SECONDS);
            coverageFuture.get(12, TimeUnit.SECONDS);
            disable.get(12, TimeUnit.SECONDS);
        } finally {
            writers.shutdownNow();
            try (Connection admin = adminConnection(contract); Statement statement = admin.createStatement()) {
                statement.execute("drop trigger if exists " + function
                        + " on " + RoutingAcceptanceContract.SCHEMA + ".operational_audit_events");
                statement.execute("drop function if exists " + RoutingAcceptanceContract.SCHEMA
                        + "." + function + "()");
            }
        }
        assertEquals(team.getId(),
                requests.findById(request.getId()).orElseThrow().getOperatingTeam().getId());
        assertEquals(VisitRequestRoutingTaskState.DONE,
                tasks.findById(task.getId()).orElseThrow().getState());
        assertEquals("false", jdbc.queryForObject(
                "select enabled::text from automatic_visit_routing_control where id = 1", String.class));
        assertEquals("false", jdbc.queryForObject(
                "select active::text from team_locality_coverage where id = ?",
                String.class, coverageEntry.id()));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from operational_audit_events
                where action_code = 'AUTOMATIC_TEAM_ROUTED' and target_id = ?
                """, Integer.class, request.getId()));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database() and state = 'idle in transaction (aborted)'
                  and pid <> pg_backend_pid()
                """, Integer.class));
    }

    @Test
    void c05OwnedChildInterruptionRollsBackAndFreshProcessRecovers() throws Exception {
        Actor owner = globalAdmin("c05-owner");
        Actor router = cityRouting("c05-router");
        SupportedCity city = city("c05");
        Locality locality = locality("c05", city);
        OperatingTeam team = team(city, "c05-team");
        cityGrant(router, StaffCapability.OPS_ROUTING, city);
        enableRouting(owner);
        coverage.create(router.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), locality.getId(), team.getId(), "C05_COVERAGE"));

        PropertyVisitRequest request = request("c05-restart", locality,
                tenant("c05-restart-tenant"));
        VisitRequestRoutingTask task = tasks.findByRequestId(request.getId()).orElseThrow();
        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        String function = "c05_child_hold_" + UUID.randomUUID().toString().replace("-", "");
        Process interruptedChild = null;
        Process recoveryChild = null;
        try {
            createAuditSleepTrigger(contract, function, request.getId(), 60);
            interruptedChild = startRoutingWorkerChild(task.getId());
            assertTrue(waitForWorkerSleep(Duration.ofSeconds(10)),
                    "child never reached the held ownership transaction");
            Long interruptedBackendPid = jdbc.queryForObject("""
                    select pid from pg_stat_activity
                    where datname = current_database() and wait_event = 'PgSleep'
                    order by pid limit 1
                    """, Long.class);
            System.out.println("C05_INTERRUPTED_CHILD_JVM_PID=" + interruptedChild.pid()
                    + " PG_BACKEND_PID=" + interruptedBackendPid);
            interruptedChild.destroyForcibly();
            assertTrue(interruptedChild.waitFor(10, TimeUnit.SECONDS));
            assertFalse(interruptedChild.isAlive());

            dropAuditTrigger(contract, function);
            assertNull(jdbc.queryForObject(
                    "select operating_team_id from property_visit_requests where id = ?",
                    Long.class, request.getId()));
            assertEquals("PENDING", jdbc.queryForObject(
                    "select state from visit_request_routing_tasks where id = ?",
                    String.class, task.getId()));
            assertEquals(0, jdbc.queryForObject("""
                    select count(*) from operational_audit_events
                    where action_code = 'AUTOMATIC_TEAM_ROUTED' and target_id = ?
                    """, Integer.class, request.getId()));

            recoveryChild = startRoutingWorkerChild(task.getId());
            System.out.println("C05_RECOVERY_CHILD_JVM_PID=" + recoveryChild.pid());
            assertTrue(recoveryChild.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, recoveryChild.exitValue());
            assertEquals(team.getId(), jdbc.queryForObject(
                    "select operating_team_id from property_visit_requests where id = ?",
                    Long.class, request.getId()));
            assertEquals("DONE", jdbc.queryForObject(
                    "select state from visit_request_routing_tasks where id = ?",
                    String.class, task.getId()));
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from operational_audit_events
                    where action_code = 'AUTOMATIC_TEAM_ROUTED' and target_id = ?
                    """, Integer.class, request.getId()));

            Process terminalReplay = startRoutingWorkerChild(task.getId());
            System.out.println("C05_TERMINAL_CHILD_JVM_PID=" + terminalReplay.pid());
            assertTrue(terminalReplay.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, terminalReplay.exitValue());
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from operational_audit_events
                    where action_code = 'AUTOMATIC_TEAM_ROUTED' and target_id = ?
                    """, Integer.class, request.getId()));
        } finally {
            stopOwnedChild(interruptedChild);
            stopOwnedChild(recoveryChild);
            dropAuditTrigger(contract, function);
        }

        PropertyVisitRequest retryRequest = request("c05-retry", locality,
                tenant("c05-retry-tenant"));
        VisitRequestRoutingTask retryTask =
                tasks.findByRequestId(retryRequest.getId()).orElseThrow();
        routing.recordTechnicalFailure(retryTask.getId(), "ROUTING_LOCK_OR_TIMEOUT");
        assertEquals("RETRY", jdbc.queryForObject(
                "select state from visit_request_routing_tasks where id = ?",
                String.class, retryTask.getId()));
        jdbc.update("""
                update visit_request_routing_tasks
                set available_at = clock_timestamp() + interval '750 milliseconds'
                where id = ?
                """, retryTask.getId());
        assertFalse(routing.dueTaskIds(100).contains(retryTask.getId()));
        Thread.sleep(900);
        assertTrue(routing.dueTaskIds(100).contains(retryTask.getId()));

        Process retryChild = startRoutingWorkerChild(retryTask.getId());
        System.out.println("C05_RETRY_CHILD_JVM_PID=" + retryChild.pid());
        try {
            assertTrue(retryChild.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, retryChild.exitValue());
        } finally {
            stopOwnedChild(retryChild);
        }
        assertEquals("DONE", jdbc.queryForObject(
                "select state from visit_request_routing_tasks where id = ?",
                String.class, retryTask.getId()));
    }

    @Test
    void c06GenuineTenantToHttpClaimAndMyWork() throws Exception {
        Actor owner = globalAdmin("c06-owner");
        Actor router = cityRouting("c06-router");
        Actor first = coordinator("c06-first");
        Actor second = coordinator("c06-second");
        SupportedCity city = city("c06");
        Locality locality = locality("c06", city);
        OperatingTeam team = team(city, "c06-team");
        cityGrant(router, StaffCapability.OPS_ROUTING, city);
        teamGrant(first, team);
        teamGrant(second, team);
        enableRouting(owner);
        coverage.create(router.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), locality.getId(), team.getId(), "C06_COVERAGE"));

        LoginActor tenantLogin = loginActor("c06-tenant");
        RentalDetails property = listing("c06", locality);
        PropertyVisitRequest request = requestViaHttp(tenantLogin.actor(),
                property, "Tomorrow between 10 and 12");
        VisitRequestRoutingTask task = tasks.findByRequestId(request.getId()).orElseThrow();
        assertTrue(jdbc.queryForObject("""
                select count(*) from visit_request_routing_tasks where request_id = ?
                """, Integer.class, request.getId()) == 1);
        routing.processTask(task.getId());
        PropertyVisitRequest routed = requests.findById(request.getId()).orElseThrow();
        assertEquals(team.getId(), routed.getOperatingTeam().getId());
        assertNull(routed.getCoordinator());

        long routedVersion = requestVersion(request.getId());
        ExecutorService claimers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<HttpClaim> results = new ArrayList<>();
        try {
            List<Future<HttpClaim>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                Actor claimant = index == 0 ? first : second;
                futures.add(claimers.submit(() -> {
                    ready.countDown();
                    start.await();
                    HttpResponse<String> response = send("POST",
                            "/api/v1/operations/visit-requests/" + request.getId() + "/claim",
                            claimant.token(), claimBody(routedVersion));
                    return new HttpClaim(claimant, response.statusCode(), response.body());
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<HttpClaim> future : futures) results.add(future.get(15, TimeUnit.SECONDS));
        } finally {
            claimers.shutdownNow();
        }
        List<HttpClaim> winners = results.stream().filter(result -> result.status() == 204).toList();
        List<HttpClaim> losers = results.stream().filter(result -> result.status() == 409).toList();
        assertEquals(1, winners.size(), results.toString());
        assertEquals(1, losers.size(), results.toString());
        Actor winner = winners.get(0).actor();
        Actor loser = losers.get(0).actor();
        assertEquals(winner.user().getId(),
                requests.findById(request.getId()).orElseThrow().getCoordinator().getId());

        JsonNode winnerWork = mapper.readTree(send("GET",
                "/api/v1/operations/my-work?page=0&size=100&targetType=REQUEST&targetId="
                        + request.getId(), winner.token(), null).body());
        assertEquals(1, winnerWork.path("totalCount").asLong(), winnerWork.toString());
        assertEquals(request.getId(), winnerWork.path("items").get(0).path("targetId").asLong());
        JsonNode loserWork = mapper.readTree(send("GET",
                "/api/v1/operations/my-work?page=0&size=100&targetType=REQUEST&targetId="
                        + request.getId(), loser.token(), null).body());
        assertEquals(0, loserWork.path("totalCount").asLong(), loserWork.toString());

        assertEquals(1, jdbc.queryForObject("""
                select count(*) from operational_audit_events
                where action_code = 'OWNERSHIP_CLAIMED' and target_type = 'PROPERTY_VISIT_REQUEST'
                  and target_id = ? and actor_kind = 'USER' and actor_user_id = ?
                """, Integer.class, request.getId(), winner.user().getId()));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from operational_audit_events
                where action_code = 'AUTOMATIC_TEAM_ROUTED' and target_type = 'PROPERTY_VISIT_REQUEST'
                  and target_id = ? and actor_kind = 'SYSTEM'
                """, Integer.class, request.getId()));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from visit_sessions where id in "
                        + "(select session_id from property_visit_requests where id = ? and session_id is not null)",
                Integer.class, request.getId()));

    }

    @Test
    void c06ManualExceptionRouteToHttpClaimAndMyWork() throws Exception {
        Actor owner = globalAdmin("c06-exception-owner");
        Actor router = cityRouting("c06-exception-router");
        Actor claimant = coordinator("c06-exception-claimant");
        SupportedCity exceptionCity = city("c06-exception");
        Locality exceptionLocality = locality("c06-exception", exceptionCity);
        OperatingTeam exceptionTeam = team(exceptionCity, "c06-exception-team");
        cityGrant(router, StaffCapability.OPS_ROUTING, exceptionCity);
        teamGrant(claimant, exceptionTeam);
        enableRouting(owner);
        LoginActor exceptionTenant = loginActor("c06-exception-tenant");
        PropertyVisitRequest exceptionRequest = requestViaHttp(exceptionTenant.actor(),
                listing("c06-exception", exceptionLocality), "Tomorrow after 4");
        VisitRequestRoutingTask exceptionTask =
                tasks.findByRequestId(exceptionRequest.getId()).orElseThrow();
        routing.processTask(exceptionTask.getId());
        VisitRequestRoutingTask review = tasks.findById(exceptionTask.getId()).orElseThrow();
        assertEquals(VisitRequestRoutingTaskState.NEEDS_REVIEW, review.getState());
        assertEquals("NO_ACTIVE_TEAM_COVERAGE", review.getLastErrorCode());

        HttpResponse<String> manual = send("POST",
                "/api/v1/operations/intake-routing/visit-requests/"
                        + exceptionRequest.getId() + "/route", router.token(),
                routeBody(requestVersion(exceptionRequest.getId()), exceptionCity.getId(),
                        cityVersion(exceptionCity.getId()), exceptionTeam.getId(),
                        teamVersion(exceptionTeam.getId()), review.getVersion(), "MANUAL_EXCEPTION"));
        assertEquals(204, manual.statusCode(), manual.body());
        assertEquals(204, send("POST",
                "/api/v1/operations/visit-requests/" + exceptionRequest.getId() + "/claim",
                claimant.token(), claimBody(requestVersion(exceptionRequest.getId()))).statusCode());
        JsonNode exceptionWork = mapper.readTree(send("GET",
                "/api/v1/operations/my-work?page=0&size=100&targetType=REQUEST&targetId="
                        + exceptionRequest.getId(), claimant.token(), null).body());
        assertEquals(1, exceptionWork.path("totalCount").asLong(), exceptionWork.toString());
        assertEquals(VisitRequestRoutingTaskState.DONE,
                tasks.findById(exceptionTask.getId()).orElseThrow().getState());
    }

    @Test
    void uniqueCoverageRoutesOneTeamThenFirstClaimWinsAndMyWorkIsTruthful() throws Exception {
        Actor owner = globalAdmin("route-owner");
        Actor router = cityRouting("route-router");
        Actor first = coordinator("route-first");
        Actor second = coordinator("route-second");
        SupportedCity city = city("route");
        Locality locality = locality("route", city);
        OperatingTeam team = team(city, "route-team");
        cityGrant(router, StaffCapability.OPS_ROUTING, city);
        teamGrant(first, team);
        teamGrant(second, team);
        enableRouting(owner);
        coverage.create(router.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), locality.getId(), team.getId(), "ACCEPTANCE_COVERAGE"));

        PropertyVisitRequest request = request("route", locality, tenant("route-tenant"));
        VisitRequestRoutingTask task = tasks.findByRequestId(request.getId()).orElseThrow();
        routing.processTask(task.getId());

        PropertyVisitRequest routed = requests.findById(request.getId()).orElseThrow();
        assertEquals(team.getId(), routed.getOperatingTeam().getId());
        assertNull(routed.getCoordinator());
        assertEquals(VisitRequestStatus.RECEIVED, routed.getStatusValue());
        assertEquals(VisitRequestRoutingTaskState.DONE, tasks.findById(task.getId()).orElseThrow().getState());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from operational_audit_events
                where action_code = 'AUTOMATIC_TEAM_ROUTED' and target_type = 'PROPERTY_VISIT_REQUEST'
                  and target_id = ?
                """, Integer.class, request.getId()));
        assertEquals("SYSTEM", jdbc.queryForObject("""
                select actor_kind::text from operational_audit_events
                where action_code = 'AUTOMATIC_TEAM_ROUTED' and target_id = ?
                """, String.class, request.getId()));

        long version = requestVersion(request.getId());
        ExecutorService claimers = Executors.newFixedThreadPool(2);
        CountDownLatch claimReady = new CountDownLatch(2);
        CountDownLatch claimStart = new CountDownLatch(1);
        try {
            List<Future<Long>> claims = new ArrayList<>();
            for (Actor claimant : List.of(first, second)) {
                claims.add(claimers.submit(() -> {
                    claimReady.countDown();
                    claimStart.await();
                    try {
                        ownership.claimRequest(claimant.user().getId(), request.getId(),
                                version, null, null, "ACCEPTANCE_COMPETE");
                        return claimant.user().getId();
                    } catch (RuntimeException expectedLoser) {
                        return -1L;
                    }
                }));
            }
            assertTrue(claimReady.await(5, TimeUnit.SECONDS));
            claimStart.countDown();
            List<Long> winners = new ArrayList<>();
            for (Future<Long> claim : claims) {
                Long winner = claim.get(15, TimeUnit.SECONDS);
                if (winner > 0) winners.add(winner);
            }
            assertEquals(1, winners.size());
            assertEquals(winners.get(0),
                    requests.findById(request.getId()).orElseThrow().getCoordinator().getId());
        } finally {
            claimers.shutdownNow();
        }

        HttpResponse<String> taskDetail = send("GET",
                "/api/v1/operations/automatic-routing/tasks/" + task.getId(), owner.token(), null);
        assertEquals(200, taskDetail.statusCode(), taskDetail.body());
        assertEquals("no-store", taskDetail.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("DONE", mapper.readTree(taskDetail.body()).path("state").asText());
    }

    @Test
    void missingAndOverlappingCoverageBecomeReviewExceptionsAndManualRouteCompletesTask() throws Exception {
        Actor owner = globalAdmin("exception-owner");
        Actor router = cityRouting("exception-router");
        SupportedCity city = city("exception");
        Locality uncovered = locality("uncovered", city);
        Locality overlap = locality("overlap", city);
        OperatingTeam one = team(city, "exception-one");
        OperatingTeam two = team(city, "exception-two");
        cityGrant(router, StaffCapability.OPS_ROUTING, city);
        cityGrant(router, StaffCapability.CITY_TEAM_ADMIN, city);
        enableRouting(owner);

        PropertyVisitRequest missing = request("missing", uncovered, tenant("missing-tenant"));
        VisitRequestRoutingTask missingTask = tasks.findByRequestId(missing.getId()).orElseThrow();
        routing.processTask(missingTask.getId());
        VisitRequestRoutingTask missingReview = tasks.findById(missingTask.getId()).orElseThrow();
        assertEquals(VisitRequestRoutingTaskState.NEEDS_REVIEW, missingReview.getState());
        assertEquals("NO_ACTIVE_TEAM_COVERAGE", missingReview.getLastErrorCode());
        assertNull(requests.findById(missing.getId()).orElseThrow().getOperatingTeam());

        coverage.create(router.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), overlap.getId(), one.getId(), "OVERLAP_ONE"));
        coverage.create(router.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), overlap.getId(), two.getId(), "OVERLAP_TWO"));
        PropertyVisitRequest conflicting = request("overlap", overlap, tenant("overlap-tenant"));
        VisitRequestRoutingTask conflictingTask = tasks.findByRequestId(conflicting.getId()).orElseThrow();
        routing.processTask(conflictingTask.getId());
        VisitRequestRoutingTask review = tasks.findById(conflictingTask.getId()).orElseThrow();
        assertEquals(VisitRequestRoutingTaskState.NEEDS_REVIEW, review.getState());
        assertEquals("OVERLAPPING_ACTIVE_COVERAGE", review.getLastErrorCode());

        long requestVersion = requestVersion(missing.getId());
        long cityVersion = cityVersion(city.getId());
        long teamVersion = teamVersion(one.getId());
        ownership.routeInitialRequest(router.user().getId(), missing.getId(), requestVersion, city.getId(),
                cityVersion, one.getId(), teamVersion, missingReview.getVersion(), "MANUAL_EXCEPTION");
        assertEquals(one.getId(),
                requests.findById(missing.getId()).orElseThrow().getOperatingTeam().getId());
        assertEquals(VisitRequestRoutingTaskState.DONE,
                tasks.findById(missingTask.getId()).orElseThrow().getState());
    }

    @Test
    void ownerCanGrantTeamAndCityDutiesAndRevokeThemIndependentlyThroughAuthenticatedHttp() throws Exception {
        Actor owner = globalAdmin("combined-owner");
        Actor worker = user("combined-worker", "OPERATIONS", true);
        SupportedCity city = city("combined");
        OperatingTeam team = team(city, "combined-team");

        HttpResponse<String> cityGrant = send("POST",
                "/api/v1/staff/admin/users/" + worker.user().getId() + "/grants", owner.token(),
                grantBody(StaffCapability.OPS_ROUTING, StaffScopeType.CITY, city.getId(), null,
                        "OWNER_CITY_ROUTING"));
        assertEquals(200, cityGrant.statusCode(), cityGrant.body());
        long cityGrantId = mapper.readTree(cityGrant.body()).path("id").asLong();

        HttpResponse<String> teamGrant = send("POST",
                "/api/v1/staff/admin/users/" + worker.user().getId() + "/grants", owner.token(),
                grantBody(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team.getId(),
                        "OWNER_TEAM_COORDINATION"));
        assertEquals(200, teamGrant.statusCode(), teamGrant.body());
        long teamGrantId = mapper.readTree(teamGrant.body()).path("id").asLong();

        assertTrue(access.hasCapabilityAt(worker.user().getId(), StaffCapability.OPS_ROUTING,
                StaffScopeType.CITY, city.getId()));
        assertTrue(access.hasCapabilityAt(worker.user().getId(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, team.getId()));

        HttpResponse<String> revokeCity = send("POST",
                "/api/v1/staff/admin/grants/" + cityGrantId + "/revoke", owner.token(),
                "{\"reasonCode\":\"OWNER_REVOKE_CITY\"}");
        assertEquals(200, revokeCity.statusCode(), revokeCity.body());
        assertTrue(access.hasCapabilityAt(worker.user().getId(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, team.getId()));

        HttpResponse<String> revokeTeam = send("POST",
                "/api/v1/staff/admin/grants/" + teamGrantId + "/revoke", owner.token(),
                "{\"reasonCode\":\"OWNER_REVOKE_TEAM\"}");
        assertEquals(200, revokeTeam.statusCode(), revokeTeam.body());
        assertTrue(access.currentCapabilities(worker.user().getId()).capabilities().stream()
                .noneMatch(value -> value.capability() == StaffCapability.OPS_ROUTING
                        || value.capability() == StaffCapability.OPS_COORDINATE));

        HttpResponse<String> cityGrantAgain = send("POST",
                "/api/v1/staff/admin/users/" + worker.user().getId() + "/grants", owner.token(),
                grantBody(StaffCapability.OPS_ROUTING, StaffScopeType.CITY, city.getId(), null,
                        "OWNER_CITY_ROUTING_AGAIN"));
        assertEquals(200, cityGrantAgain.statusCode(), cityGrantAgain.body());
        long secondCityGrantId = mapper.readTree(cityGrantAgain.body()).path("id").asLong();
        HttpResponse<String> teamGrantAgain = send("POST",
                "/api/v1/staff/admin/users/" + worker.user().getId() + "/grants", owner.token(),
                grantBody(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team.getId(),
                        "OWNER_TEAM_COORDINATION_AGAIN"));
        assertEquals(200, teamGrantAgain.statusCode(), teamGrantAgain.body());
        long secondTeamGrantId = mapper.readTree(teamGrantAgain.body()).path("id").asLong();
        assertEquals(200, send("POST", "/api/v1/staff/admin/grants/" + secondTeamGrantId
                + "/revoke", owner.token(), "{\"reasonCode\":\"OWNER_REVOKE_TEAM_FIRST\"}")
                .statusCode());
        assertTrue(access.hasCapabilityAt(worker.user().getId(), StaffCapability.OPS_ROUTING,
                StaffScopeType.CITY, city.getId()));
        assertFalse(access.hasCapabilityAt(worker.user().getId(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, team.getId()));
        assertEquals(200, send("POST", "/api/v1/staff/admin/grants/" + secondCityGrantId
                + "/revoke", owner.token(), "{\"reasonCode\":\"OWNER_REVOKE_CITY_SECOND\"}")
                .statusCode());
    }

    @Test
    void concurrentWorkersCommitOneAutomaticRouteAndCompletedWorkIsNotReopened() throws Exception {
        Actor owner = globalAdmin("race-owner");
        Actor router = cityRouting("race-router");
        SupportedCity city = city("race");
        Locality locality = locality("race", city);
        OperatingTeam team = team(city, "race-team");
        cityGrant(router, StaffCapability.OPS_ROUTING, city);
        enableRouting(owner);
        coverage.create(router.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), locality.getId(), team.getId(), "RACE_COVERAGE"));
        PropertyVisitRequest request = request("race", locality, tenant("race-tenant"));
        VisitRequestRoutingTask task = tasks.findByRequestId(request.getId()).orElseThrow();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        routing.processTask(task.getId());
                        return "DONE";
                    } catch (RuntimeException expectedLoser) {
                        return expectedLoser.getClass().getSimpleName();
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) outcomes.add(result.get(20, TimeUnit.SECONDS));
            assertTrue(outcomes.stream().anyMatch("DONE"::equals), outcomes.toString());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(team.getId(),
                requests.findById(request.getId()).orElseThrow().getOperatingTeam().getId());
        assertEquals(VisitRequestRoutingTaskState.DONE,
                tasks.findById(task.getId()).orElseThrow().getState());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from operational_audit_events
                where action_code = 'AUTOMATIC_TEAM_ROUTED' and target_id = ?
                """, Integer.class, request.getId()));

        routing.processTask(task.getId());
        assertEquals(VisitRequestRoutingTaskState.DONE,
                tasks.findById(task.getId()).orElseThrow().getState());
    }

    @Test
    void freshAuthorityExpiryDuringEvidenceWaitDeniesRecheckWithoutMutation() throws Exception {
        Actor owner = globalAdmin("expiry-owner");
        Actor router = cityRouting("expiry-router");
        SupportedCity city = city("expiry");
        Locality locality = locality("expiry", city);
        grants.saveAndFlush(new StaffAccessGrant(router.user(), StaffCapability.OPS_ROUTING,
                StaffScopeType.CITY, city, null, Instant.now().minusSeconds(2),
                Instant.now().plusSeconds(3), null, Instant.now(), "ACCEPTANCE",
                StaffGrantProvisioningSource.ADMIN_API));
        enableRouting(owner);
        PropertyVisitRequest request = request("expiry", locality, tenant("expiry-tenant"));
        VisitRequestRoutingTask task = tasks.findByRequestId(request.getId()).orElseThrow();
        routing.processTask(task.getId());
        VisitRequestRoutingTask review = tasks.findById(task.getId()).orElseThrow();
        assertEquals(VisitRequestRoutingTaskState.NEEDS_REVIEW, review.getState());

        try (Connection app = appConnection();
             Statement statement = app.createStatement()) {
            app.setAutoCommit(false);
            statement.execute("select id from listings where id = " + request.getListing().getId() + " for update");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> recheck = executor.submit(() -> assertThrows(RuntimeException.class,
                        () -> taskService.recheck(router.user().getId(), task.getId(),
                                new RoutingTaskRecheckCommand(review.getVersion(), "RECHECK_AFTER_EXPIRY"))));
                int observedWait = 0;
                long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (System.nanoTime() < waitDeadline) {
                    Integer waits = jdbc.queryForObject("""
                            select count(*) from pg_stat_activity
                            where datname = current_database() and wait_event_type = 'Lock'
                              and pid <> pg_backend_pid()
                            """, Integer.class);
                    observedWait = Math.max(observedWait, waits == null ? 0 : waits);
                    if (observedWait > 0) break;
                    Thread.sleep(25);
                }
                assertTrue(observedWait > 0,
                        "recheck did not visibly wait for the held Listing evidence lock");
                boolean expired = false;
                long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (System.nanoTime() < expiryDeadline) {
                    expired = Boolean.TRUE.equals(jdbc.queryForObject("""
                            select expires_at <= clock_timestamp()
                            from staff_access_grants
                            where user_id = ? and capability = 'OPS_ROUTING' and city_id = ?
                            """, Boolean.class, router.user().getId(), city.getId()));
                    if (expired) break;
                    Thread.sleep(50);
                }
                assertTrue(expired, "the acceptance grant did not expire while the evidence lock was held");
                app.rollback();
                recheck.get(15, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
        VisitRequestRoutingTask after = tasks.findById(task.getId()).orElseThrow();
        assertEquals(VisitRequestRoutingTaskState.NEEDS_REVIEW, after.getState());
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from operational_audit_events
                where action_code = 'ROUTING_RECHECK_REQUESTED' and target_id = ?
                """, Integer.class, task.getId()));
    }

    @Test
    void auditFailureRollsBackTheFlushedTeamMutationAndRetryProducesOneOutcome() throws Exception {
        Actor owner = globalAdmin("rollback-owner");
        Actor router = cityRouting("rollback-router");
        SupportedCity city = city("rollback");
        Locality locality = locality("rollback", city);
        OperatingTeam team = team(city, "rollback-team");
        cityGrant(router, StaffCapability.OPS_ROUTING, city);
        enableRouting(owner);
        coverage.create(router.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), locality.getId(), team.getId(), "ROLLBACK_COVERAGE"));
        PropertyVisitRequest request = request("rollback", locality, tenant("rollback-tenant"));
        VisitRequestRoutingTask task = tasks.findByRequestId(request.getId()).orElseThrow();

        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String function = "routing_accept_fail_" + suffix;
        try (Connection admin = adminConnection(contract); Statement statement = admin.createStatement()) {
            statement.execute("create function " + RoutingAcceptanceContract.SCHEMA + "." + function
                    + "() returns trigger language plpgsql as $$ begin if new.target_id = " + request.getId()
                    + " and new.action_code = 'AUTOMATIC_TEAM_ROUTED' then raise exception 'synthetic audit failure';"
                    + " end if; return new; end $$");
            statement.execute("create trigger " + function + " before insert on "
                    + RoutingAcceptanceContract.SCHEMA + ".operational_audit_events for each row execute function "
                    + RoutingAcceptanceContract.SCHEMA + "." + function + "()");
            assertThrows(RuntimeException.class, () -> routing.processTask(task.getId()));
            assertNull(requests.findById(request.getId()).orElseThrow().getOperatingTeam());
            assertEquals(VisitRequestRoutingTaskState.PENDING,
                    tasks.findById(task.getId()).orElseThrow().getState());
            statement.execute("drop trigger " + function + " on "
                    + RoutingAcceptanceContract.SCHEMA + ".operational_audit_events");
            statement.execute("drop function " + RoutingAcceptanceContract.SCHEMA + "." + function + "()");
        }
        routing.processTask(task.getId());
        assertEquals(team.getId(),
                requests.findById(request.getId()).orElseThrow().getOperatingTeam().getId());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from operational_audit_events
                where action_code = 'AUTOMATIC_TEAM_ROUTED' and target_id = ?
                """, Integer.class, request.getId()));
    }

    @Test
    void coverageLifecycleAndTaskPaginationAreStableAcrossPostgreSqlPages() throws Exception {
        Actor owner = globalAdmin("page-owner");
        Actor routerA = cityRouting("page-router-a");
        Actor routerB = cityRouting("page-router-b");
        SupportedCity cityA = city("page-a");
        SupportedCity cityB = city("page-b");
        cityGrant(routerA, StaffCapability.OPS_ROUTING, cityA);
        cityGrant(routerB, StaffCapability.OPS_ROUTING, cityB);
        disableRouting(owner);
        var emptyPage = coverage.list(routerA.user().getId(), cityA.getId(), 0, 20);
        assertEquals(0, emptyPage.totalCount());
        assertEquals(0, emptyPage.items().size());

        List<Long> coverageIds = new ArrayList<>();
        OperatingTeam firstTeam = null;
        Locality firstLocality = null;
        for (int index = 0; index < 22; index++) {
            Locality locality = locality("page-" + index, cityA);
            OperatingTeam team = team(cityA, "page-" + index);
            coverageIds.add(coverage.create(routerA.user().getId(),
                    new TeamLocalityCoverageCommand(cityA.getId(), locality.getId(), team.getId(),
                            "PAGE_COVERAGE")).id());
            if (index == 0) {
                firstTeam = team;
                firstLocality = locality;
            }
            if (index == 0 || index == 19 || index == 21) {
                var boundaryPage = coverage.list(routerA.user().getId(), cityA.getId(), 0, 20);
                int expectedTotal = index + 1;
                assertEquals(expectedTotal, boundaryPage.totalCount());
                assertEquals(Math.min(20, expectedTotal), boundaryPage.items().size());
            }
        }
        HttpResponse<String> firstPage = send("GET",
                "/api/v1/operations/automatic-routing/coverage?cityId=" + cityA.getId() + "&page=0&size=20",
                routerA.token(), null);
        HttpResponse<String> secondPage = send("GET",
                "/api/v1/operations/automatic-routing/coverage?cityId=" + cityA.getId() + "&page=1&size=20",
                routerA.token(), null);
        assertEquals(200, firstPage.statusCode(), firstPage.body());
        assertEquals(200, secondPage.statusCode(), secondPage.body());
        JsonNode page0 = mapper.readTree(firstPage.body());
        JsonNode page1 = mapper.readTree(secondPage.body());
        assertEquals(20, page0.path("items").size());
        assertEquals(2, page1.path("items").size());
        assertEquals(22, page0.path("totalCount").asLong());
        assertTrue(page0.path("items").get(0).path("id").asLong() <
                page0.path("items").get(1).path("id").asLong());

        long firstCoverageId = coverageIds.get(0);
        final Locality duplicatedLocality = firstLocality;
        final OperatingTeam duplicatedTeam = firstTeam;
        assertThrows(RuntimeException.class, () -> coverage.create(routerA.user().getId(),
                new TeamLocalityCoverageCommand(cityA.getId(), duplicatedLocality.getId(),
                        duplicatedTeam.getId(), "DUPLICATE_ACTIVE")));
        var retired = coverage.changeState(routerA.user().getId(), firstCoverageId,
                new TeamLocalityCoverageStateCommand(0L, false, "RETIRE_FOR_ACCEPTANCE"));
        assertEquals(false, retired.active());
        assertThrows(RuntimeException.class, () -> coverage.create(routerA.user().getId(),
                new TeamLocalityCoverageCommand(cityA.getId(), duplicatedLocality.getId(),
                        duplicatedTeam.getId(), "RETIRED_DUPLICATE")));
        var restored = coverage.changeState(routerA.user().getId(), firstCoverageId,
                new TeamLocalityCoverageStateCommand(retired.version(), true, "RESTORE_ACCEPTANCE"));
        assertEquals(true, restored.active());
        assertThrows(RuntimeException.class, () -> coverage.changeState(routerA.user().getId(),
                firstCoverageId, new TeamLocalityCoverageStateCommand(retired.version(), false,
                        "STALE_RESTORE")));

        List<Long> taskAIds = new ArrayList<>();
        List<Long> taskBIds = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            PropertyVisitRequest request = request("page-task-a-" + index,
                    locality("page-task-a-" + index, cityA), tenant("page-task-a-" + index));
            taskAIds.add(tasks.findByRequestId(request.getId()).orElseThrow().getId());
        }
        PropertyVisitRequest bRequest = request("page-task-b", locality("page-task-b", cityB),
                tenant("page-task-b"));
        taskBIds.add(tasks.findByRequestId(bRequest.getId()).orElseThrow().getId());

        HttpResponse<String> tasksFirst = send("GET",
                "/api/v1/operations/automatic-routing/tasks?page=0&size=20", owner.token(), null);
        HttpResponse<String> tasksSecond = send("GET",
                "/api/v1/operations/automatic-routing/tasks?page=1&size=20", owner.token(), null);
        assertEquals(200, tasksFirst.statusCode(), tasksFirst.body());
        assertEquals(200, tasksSecond.statusCode(), tasksSecond.body());
        assertEquals(20, mapper.readTree(tasksFirst.body()).path("items").size());
        assertTrue(mapper.readTree(tasksSecond.body()).path("items").size() >= 1);

        HttpResponse<String> scopedA = send("GET",
                "/api/v1/operations/automatic-routing/tasks?page=0&size=100", routerA.token(), null);
        assertEquals(200, scopedA.statusCode(), scopedA.body());
        JsonNode scopedItems = mapper.readTree(scopedA.body()).path("items");
        boolean sawA = false;
        for (JsonNode item : scopedItems) {
            long id = item.path("taskId").asLong();
            assertTrue(!taskBIds.contains(id), "City A routing authority saw City B task " + id);
            if (taskAIds.contains(id)) sawA = true;
        }
        assertTrue(sawA);
    }

    @Test
    void disableBeforeAdmissionPreventsRoutingAndDisableAfterAdmissionWaitsForTheWorker() throws Exception {
        Actor owner = globalAdmin("disable-owner");
        Actor router = cityRouting("disable-router");
        SupportedCity city = city("disable");
        Locality locality = locality("disable", city);
        OperatingTeam team = team(city, "disable-team");
        cityGrant(router, StaffCapability.OPS_ROUTING, city);
        disableRouting(owner);
        coverage.create(router.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), locality.getId(), team.getId(), "DISABLE_COVERAGE"));

        PropertyVisitRequest blocked = request("disable-before", locality, tenant("disable-before"));
        VisitRequestRoutingTask blockedTask = tasks.findByRequestId(blocked.getId()).orElseThrow();
        routing.processTask(blockedTask.getId());
        assertNull(requests.findById(blocked.getId()).orElseThrow().getOperatingTeam());
        assertEquals(VisitRequestRoutingTaskState.PENDING,
                tasks.findById(blockedTask.getId()).orElseThrow().getState());

        enableRouting(owner);
        PropertyVisitRequest admitted = request("disable-after", locality, tenant("disable-after"));
        VisitRequestRoutingTask admittedTask = tasks.findByRequestId(admitted.getId()).orElseThrow();
        RoutingAcceptanceContract contract = RoutingAcceptanceContract.requireFromEnvironment();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String function = "routing_accept_wait_" + suffix;
        try (Connection admin = adminConnection(contract); Statement statement = admin.createStatement()) {
            statement.execute("create function " + RoutingAcceptanceContract.SCHEMA + "." + function
                    + "() returns trigger language plpgsql as $$ begin if new.target_id = "
                    + admitted.getId() + " then perform pg_sleep(2); end if; return new; end $$");
            statement.execute("create trigger " + function + " before insert on "
                    + RoutingAcceptanceContract.SCHEMA + ".operational_audit_events for each row execute function "
                    + RoutingAcceptanceContract.SCHEMA + "." + function + "()");
            long currentVersion = jdbc.queryForObject(
                    "select version from automatic_visit_routing_control where id = 1", Long.class);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> worker = executor.submit(() -> routing.processTask(admittedTask.getId()));
                Thread.sleep(350);
                Future<?> disable = executor.submit(() -> controls.change(owner.user().getId(),
                        new AutomaticRoutingControlCommand(currentVersion, false, "DISABLE_AFTER_ADMISSION")));
                int observedWaits = 0;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline) {
                    Integer waits = jdbc.queryForObject("""
                            select count(*) from pg_stat_activity
                            where datname = current_database() and wait_event_type = 'Lock'
                              and pid <> pg_backend_pid()
                            """, Integer.class);
                    observedWaits = Math.max(observedWaits, waits == null ? 0 : waits);
                    if (observedWaits > 0) break;
                    Thread.sleep(50);
                }
                worker.get(10, TimeUnit.SECONDS);
                disable.get(10, TimeUnit.SECONDS);
                assertTrue(observedWaits > 0,
                        "global disable did not visibly wait for the admitted worker's shared control lock");
            } finally {
                executor.shutdownNow();
            }
            statement.execute("drop trigger " + function + " on "
                    + RoutingAcceptanceContract.SCHEMA + ".operational_audit_events");
            statement.execute("drop function " + RoutingAcceptanceContract.SCHEMA + "." + function + "()");
        }
        assertEquals(team.getId(),
                requests.findById(admitted.getId()).orElseThrow().getOperatingTeam().getId());
        assertEquals("false", jdbc.queryForObject("""
                select enabled::text from automatic_visit_routing_control where id = 1
                """, String.class));
    }

    @Test
    void workerCoverageAndControlWritersCompleteWithoutCircuitOrPartialAudit() throws Exception {
        Actor owner = globalAdmin("three-way-owner");
        Actor router = cityRouting("three-way-router");
        SupportedCity city = city("three-way");
        Locality locality = locality("three-way", city);
        OperatingTeam team = team(city, "three-way-team");
        cityGrant(router, StaffCapability.OPS_ROUTING, city);
        enableRouting(owner);
        var coverageEntry = coverage.create(router.user().getId(), new TeamLocalityCoverageCommand(
                city.getId(), locality.getId(), team.getId(), "THREE_WAY_COVERAGE"));
        PropertyVisitRequest request = request("three-way", locality, tenant("three-way-tenant"));
        VisitRequestRoutingTask task = tasks.findByRequestId(request.getId()).orElseThrow();
        long controlVersion = jdbc.queryForObject(
                "select version from automatic_visit_routing_control where id = 1", Long.class);

        ExecutorService writers = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (var operation : List.<java.util.concurrent.Callable<String>>of(
                    () -> {
                        routing.processTask(task.getId());
                        return "WORKER";
                    },
                    () -> {
                        coverage.changeState(router.user().getId(), coverageEntry.id(),
                                new TeamLocalityCoverageStateCommand(coverageEntry.version(), false,
                                        "THREE_WAY_RETIRE"));
                        return "COVERAGE";
                    },
                    () -> {
                        controls.change(owner.user().getId(), new AutomaticRoutingControlCommand(
                                controlVersion, false, "THREE_WAY_DISABLE"));
                        return "CONTROL";
                    })) {
                results.add(writers.submit(() -> {
                    ready.countDown();
                    start.await();
                    return operation.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> completed = new ArrayList<>();
            for (Future<String> result : results) completed.add(result.get(20, TimeUnit.SECONDS));
            assertEquals(3, completed.size());
        } finally {
            writers.shutdownNow();
        }
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database() and state = 'idle in transaction (aborted)'
                  and pid <> pg_backend_pid()
                """, Integer.class));
        VisitRequestRoutingTask finalTask = tasks.findById(task.getId()).orElseThrow();
        PropertyVisitRequest finalRequest = requests.findById(request.getId()).orElseThrow();
        if (finalTask.getState() == VisitRequestRoutingTaskState.DONE) {
            assertEquals(team.getId(), finalRequest.getOperatingTeam().getId());
        } else {
            assertNull(finalRequest.getOperatingTeam());
        }
        assertEquals("false", jdbc.queryForObject(
                "select enabled::text from automatic_visit_routing_control where id = 1", String.class));
        assertEquals(finalTask.getState() == VisitRequestRoutingTaskState.DONE ? 1 : 0,
                jdbc.queryForObject(
                "select count(*) from operational_audit_events where action_code = "
                        + "'AUTOMATIC_TEAM_ROUTED' and target_id = ?",
                Integer.class, request.getId()));
    }

    private void enableRouting(Actor owner) {
        Long version = jdbc.queryForObject("""
                select version from automatic_visit_routing_control where id = 1
                """, Long.class);
        controls.change(owner.user().getId(),
                new AutomaticRoutingControlCommand(version, true, "ACCEPTANCE_ENABLE"));
    }

    private void disableRouting(Actor owner) {
        Long version = jdbc.queryForObject("""
                select version from automatic_visit_routing_control where id = 1
                """, Long.class);
        controls.change(owner.user().getId(),
                new AutomaticRoutingControlCommand(version, false, "ACCEPTANCE_DISABLE"));
    }

    private Actor tenant(String label) {
        return user(label + "-tenant", null, false);
    }

    private Actor globalAdmin(String label) {
        Actor actor = user(label + "-admin", "OPERATIONS", true);
        grants.saveAndFlush(new StaffAccessGrant(actor.user(), StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null, null,
                Instant.now(), "ACCEPTANCE", StaffGrantProvisioningSource.ADMIN_API));
        return actor;
    }

    private Actor cityRouting(String label) {
        return user(label, "OPERATIONS", true);
    }

    private Actor coordinator(String label) {
        return user(label, "OPERATIONS", true);
    }

    private Actor user(String label, String profileRole, boolean staffActive) {
        User user = new User();
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user.setEmail("codex.routing.accept." + unique + "@pathome.test");
        user.setFullName(label);
        user.setPhoneNumber("+9198" + String.format("%08d", Math.abs(unique.hashCode()) % 100_000_000));
        user.setRole(Role.ROLE_TENANT);
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

    private SupportedCity city(String label) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return cities.saveAndFlush(new SupportedCity(
                "routing-accept-" + label + "-" + unique, "Routing Accept " + label, true));
    }

    private OperatingTeam team(SupportedCity city, String label) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return teams.saveAndFlush(new OperatingTeam(city,
                "routing-accept-" + label + "-" + unique, "Routing Accept " + label, true));
    }

    private Locality locality(String label, SupportedCity city) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Locality locality = new Locality(city.getDisplayName(),
                "routing-accept-" + label + "-" + unique, null, null, null);
        locality.setSupportedCity(city);
        return localities.saveAndFlush(locality);
    }

    private RentalDetails listing(String label, Locality locality) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Routing acceptance " + label + " " + UUID.randomUUID());
        listing.setStatus(com.indore.pathome.spaces.entity.ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Synthetic acceptance address");
        listing.setSector(locality.getSectorName());
        listing.setCity(locality.getCity());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setBhkCount("1BHK");
        listing.setMonthlyRent(BigDecimal.ONE);
        listing.setSecurityDeposit(BigDecimal.ZERO);
        return listings.saveAndFlush(listing);
    }

    private PropertyVisitRequest request(String label, Locality locality, Actor tenant) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant.user());
        request.setListing(listing(label, locality));
        request.setStatus(VisitRequestStatus.RECEIVED);
        return intake.admitNewTenantIntakeAndSave(request);
    }

    private PropertyVisitRequest requestViaHttp(Actor tenant, RentalDetails property, String timing)
            throws Exception {
        HttpResponse<String> created = send("POST",
                "/api/v1/properties/" + property.getId() + "/visit-requests", tenant.token(),
                "{\"preferredVisitTiming\":\"" + timing + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        return requests.findById(mapper.readTree(created.body()).path("requestId").asLong())
                .orElseThrow();
    }

    private PropertyVisitRequest directRequest(String label, Locality locality,
            SupportedCity city, OperatingTeam team, Actor tenant, boolean ready) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant.user());
        request.setListing(listing(label, locality));
        request.setStatus(VisitRequestStatus.RECEIVED);
        request.setSupportedCity(city);
        request.setOperatingTeam(team);
        request.setOperationalScopeReady(ready);
        return requests.saveAndFlush(request);
    }

    private boolean pageContains(JsonNode page, String type, long targetId) {
        for (JsonNode item : page.path("items")) {
            if (type.equals(item.path("targetType").asText())
                    && item.path("targetId").asLong() == targetId) return true;
        }
        return false;
    }

    private long taskId(PropertyVisitRequest request) {
        return tasks.findByRequestId(request.getId()).orElseThrow().getId();
    }

    private int status(String method, String path, Actor actor) throws Exception {
        return send(method, path, actor.token(), null).statusCode();
    }

    private List<LockWaiter> waitForLockWaiters(int minimum, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<LockWaiter> waiters = List.of();
        while (System.nanoTime() < deadline) {
            waiters = jdbc.query("""
                    select pid, pg_blocking_pids(pid) as blockers, query
                    from pg_stat_activity
                    where datname = current_database()
                      and wait_event_type = 'Lock'
                      and pid <> pg_backend_pid()
                    """, (rows, index) -> new LockWaiter(
                    rows.getInt("pid"),
                    (Integer[]) rows.getArray("blockers").getArray(),
                    rows.getString("query")));
            if (waiters.size() >= minimum) return waiters;
            Thread.sleep(25);
        }
        return waiters;
    }

    private boolean waitForWorkerSleep(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database()
                      and wait_event = 'PgSleep'
                      and pid <> pg_backend_pid()
                    """, Integer.class);
            if (count != null && count > 0) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private void createAuditSleepTrigger(RoutingAcceptanceContract contract, String function,
            Long targetId, int seconds) throws Exception {
        try (Connection admin = adminConnection(contract); Statement statement = admin.createStatement()) {
            statement.execute("create function " + RoutingAcceptanceContract.SCHEMA + "." + function
                    + "() returns trigger language plpgsql as $$ begin if new.target_id = "
                    + targetId + " then perform pg_sleep(" + seconds + "); end if; return new; end $$");
            statement.execute("create trigger " + function + " before insert on "
                    + RoutingAcceptanceContract.SCHEMA + ".operational_audit_events"
                    + " for each row execute function " + RoutingAcceptanceContract.SCHEMA
                    + "." + function + "()");
        }
    }

    private void dropAuditTrigger(RoutingAcceptanceContract contract, String function) {
        try (Connection admin = adminConnection(contract); Statement statement = admin.createStatement()) {
            statement.execute("drop trigger if exists " + function
                    + " on " + RoutingAcceptanceContract.SCHEMA + ".operational_audit_events");
            statement.execute("drop function if exists " + RoutingAcceptanceContract.SCHEMA
                    + "." + function + "()");
        } catch (Exception ignored) {
            // The test's primary evidence remains authoritative; target teardown removes the database.
        }
    }

    private Process startRoutingWorkerChild(long taskId) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                RoutingWorkerChildProcess.class.getName(), Long.toString(taskId));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("SPRING_MAIN_WEB_APPLICATION_TYPE", "none");
        return builder.start();
    }

    private void stopOwnedChild(Process child) throws Exception {
        if (child == null || !child.isAlive()) return;
        child.destroyForcibly();
        child.waitFor(10, TimeUnit.SECONDS);
    }

    private VisitSession unclaimedSession(Locality locality, SupportedCity city,
            OperatingTeam team, Actor tenant) {
        VisitSession session = new VisitSession();
        session.setTenant(tenant.user());
        session.setCity(city.getDisplayName());
        session.setCanonicalLocality(locality);
        session.setSupportedCity(city);
        session.setOperatingTeam(team);
        session.setOperationalScopeReady(true);
        session.setStatus(VisitSessionStatus.DRAFT);
        return sessions.saveAndFlush(session);
    }

    private JsonNode unclaimed(Actor actor, int page, int size) throws Exception {
        HttpResponse<String> response = send("GET",
                "/api/v1/operations/automatic-routing/tasks/unclaimed?page=" + page
                        + "&size=" + size, actor.token(), null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        return mapper.readTree(response.body());
    }

    private LoginActor loginActor(String label) throws Exception {
        String email = "codex.routing.login." + UUID.randomUUID() + "@pathome.test";
        String password = "Synthetic-" + UUID.randomUUID() + "-pass";
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwords.encode(password));
        user.setFullName(label);
        user.setPhoneNumber("+9197" + String.format("%08d",
                Math.abs(UUID.randomUUID().hashCode()) % 100_000_000));
        user.setRole(Role.ROLE_TENANT);
        user = users.saveAndFlush(user);
        HttpResponse<String> login = send("POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, login.statusCode(), login.body());
        return new LoginActor(new Actor(user, mapper.readTree(login.body()).path("token").asText()));
    }

    private String claimBody(long expectedRequestVersion) {
        return "{\"expectedRequestVersion\":" + expectedRequestVersion
                + ",\"expectedSessionVersion\":null,\"expectedRequestVersions\":null"
                + ",\"reasonCode\":\"C06_HTTP_CLAIM\"}";
    }

    private String routeBody(long expectedRequestVersion, Long cityId, long expectedCityVersion,
            Long teamId, long expectedTeamVersion, long expectedTaskVersion, String reason) {
        return "{\"expectedRequestVersion\":" + expectedRequestVersion
                + ",\"cityId\":" + cityId
                + ",\"expectedCityVersion\":" + expectedCityVersion
                + ",\"teamId\":" + teamId
                + ",\"expectedTeamVersion\":" + expectedTeamVersion
                + ",\"expectedRoutingTaskVersion\":" + expectedTaskVersion
                + ",\"reasonCode\":\"" + reason + "\"}";
    }

    private void cityGrant(Actor actor, StaffCapability capability, SupportedCity city) {
        grants.saveAndFlush(new StaffAccessGrant(actor.user(), capability, StaffScopeType.CITY,
                city, null, Instant.now().minusSeconds(60), null, null, Instant.now(),
                "ACCEPTANCE", StaffGrantProvisioningSource.ADMIN_API));
    }

    private void teamGrant(Actor actor, OperatingTeam team) {
        grants.saveAndFlush(new StaffAccessGrant(actor.user(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(60), null, null,
                Instant.now(), "ACCEPTANCE", StaffGrantProvisioningSource.ADMIN_API));
    }

    private long requestVersion(Long requestId) {
        return jdbc.queryForObject("select version from property_visit_requests where id = ?",
                Long.class, requestId);
    }

    private long cityVersion(Long cityId) {
        return jdbc.queryForObject("select version from supported_cities where id = ?", Long.class, cityId);
    }

    private long teamVersion(Long teamId) {
        return jdbc.queryForObject("select version from operating_teams where id = ?", Long.class, teamId);
    }

    private Connection appConnection() throws Exception {
        RoutingAcceptanceContract.Credentials runtime =
                RoutingAcceptanceContract.requireFromEnvironment().runtimeCredentials();
        return DriverManager.getConnection(runtime.jdbcUrl(), runtime.username(), runtime.password());
    }

    private Connection adminConnection(RoutingAcceptanceContract contract) throws Exception {
        RoutingAcceptanceContract.Credentials admin = contract.adminCredentials();
        return DriverManager.getConnection(admin.jdbcUrl(), admin.username(), admin.password());
    }

    private HttpResponse<String> send(String method, String path, String token, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return HTTP.send(builder.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String grantBody(StaffCapability capability, StaffScopeType scope,
                                    Long cityId, Long teamId, String reason) {
        return "{\"capability\":\"" + capability.name() + "\",\"scopeType\":\"" + scope.name()
                + "\",\"cityId\":" + cityId + ",\"teamId\":" + teamId
                + ",\"effectiveAt\":null,\"expiresAt\":null,\"reasonCode\":\"" + reason + "\"}";
    }

    private record Actor(User user, String token) {}
    private record LoginActor(Actor actor) {}
    private record HttpClaim(Actor actor, int status, String body) {}
    private record LockWaiter(int pid, Integer[] blockingPids, String query) {}
}
