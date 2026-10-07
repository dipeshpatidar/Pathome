package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.security.JwtUtils;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=claimable-http-test-jwt-secret-32-bytes-minimum",
        "pathome.visit.otp.hmac-secret=claimable-http-test-otp-secret-32-bytes-minimum",
        "cloudinary.api-key=claimable-test-key",
        "cloudinary.api-secret=claimable-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class ClaimableWorkHttpPostgresTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "claimable_http_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

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

    @Autowired private ObjectMapper mapper;
    @Autowired private JwtUtils jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private VisitSessionRepository sessions;
    @Autowired private SystemNotificationRepository notifications;
    @LocalServerPort private int httpPort;

    @Test
    void realHttpDiscoveryClaimRaceAndAuthorizedReadActionsUseReturnedTokens() throws Exception {
        assertEquals(49, jdbc.queryForObject(
                "SELECT max(version::integer) FROM flyway_schema_history WHERE type='SQL' AND success", Integer.class));
        assertEquals(401, send("GET", "/api/v1/operations/claimable-work", null, null).statusCode());

        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("claim-city"), "Claim City", true));
        SupportedCity otherCity = cities.saveAndFlush(new SupportedCity(code("other-city"), "Other City", true));
        OperatingTeam teamA = teams.saveAndFlush(new OperatingTeam(city, code("team-a"), "Team A", true));
        OperatingTeam teamB = teams.saveAndFlush(new OperatingTeam(city, code("team-b"), "Team B", true));
        OperatingTeam otherTeam = teams.saveAndFlush(new OperatingTeam(city, code("other-team"), "Other Team", true));
        OperatingTeam distantTeam = teams.saveAndFlush(new OperatingTeam(otherCity, code("distant-team"), "Distant Team", true));
        Actor coordinatorA = activeCoordinator("coordinator-a");
        Actor coordinatorB = activeCoordinator("coordinator-b");
        Actor supervisor = activeCoordinator("supervisor");
        grant(coordinatorA, StaffCapability.OPS_COORDINATE, teamA);
        grants.saveAndFlush(new StaffAccessGrant(coordinatorA.user(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, teamA, Instant.now().minusSeconds(120), null,
                null, Instant.now(), "TEST_CLAIMABLE_REPEAT", StaffGrantProvisioningSource.ADMIN_API));
        grant(coordinatorB, StaffCapability.OPS_COORDINATE, teamA);
        grant(coordinatorB, StaffCapability.OPS_COORDINATE, teamB);
        grant(supervisor, StaffCapability.OPS_SUPERVISE, teamA);
        grant(supervisor, StaffCapability.OPS_SUPERVISE, teamB);
        Actor superviseOnly = activeCoordinator("supervise-only");
        grant(superviseOnly, StaffCapability.OPS_SUPERVISE, teamA);
        Actor globalAdmin = activeCoordinator("global-admin");
        grants.saveAndFlush(new StaffAccessGrant(globalAdmin.user(), StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "TEST_PHASE1E", StaffGrantProvisioningSource.ADMIN_API));
        Actor unprivilegedCustomer = actor("unprivileged-customer", Role.ROLE_TENANT, null);
        Actor tenant = actor("claimable-tenant", Role.ROLE_TENANT, null);

        Locality locality = localities.saveAndFlush(new Locality(city.getDisplayName(), "Claimable Sector " + UUID.randomUUID(),
                null, null, null));
        locality.setSupportedCity(city);
        locality = localities.saveAndFlush(locality);
        PropertyVisitRequest unlinked = request("claimable-unlinked", tenant, city, teamA, locality,
                null, VisitRequestStatus.RECEIVED);
        PropertyVisitRequest raceRequest = request("claim-race", tenant, city, teamA, locality,
                null, VisitRequestStatus.RECEIVED);
        PropertyVisitRequest sameCityOtherTeam = request("same-city-other-team", tenant, city, otherTeam,
                locality, null, VisitRequestStatus.RECEIVED);
        Locality otherLocality = localities.saveAndFlush(new Locality(otherCity.getDisplayName(), "Distant Sector "
                + UUID.randomUUID(), null, null, null));
        otherLocality.setSupportedCity(otherCity);
        otherLocality = localities.saveAndFlush(otherLocality);
        PropertyVisitRequest differentCity = request("different-city", tenant, otherCity, distantTeam,
                otherLocality, null, VisitRequestStatus.RECEIVED);

        VisitSession linkedSession = session("claimed-session", tenant, city, teamA);
        PropertyVisitRequest linkedOne = request("linked-one", tenant, city, teamA, locality,
                linkedSession, VisitRequestStatus.SCHEDULED);
        PropertyVisitRequest linkedTwo = request("linked-two", tenant, city, teamA, locality,
                linkedSession, VisitRequestStatus.CANCELLED);
        VisitSession zeroLinkedSession = session("zero-linked", tenant, city, teamA);

        HttpResponse<String> collectionResponse = send("GET", "/api/v1/operations/claimable-work?page=0&size=20",
                coordinatorA.token(), null);
        assertEquals(200, collectionResponse.statusCode(), collectionResponse.body());
        assertTrue(collectionResponse.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode collection = mapper.readTree(collectionResponse.body());
        assertEquals(4, collection.path("totalCount").asInt());
        assertEquals(1, collection.path("totalPages").asInt());
        assertEquals(4, collection.path("items").size());
        assertEquals("REQUEST", collection.path("items").get(0).path("targetType").asText());
        assertEquals("REQUEST", collection.path("items").get(1).path("targetType").asText());
        assertTrue(collection.path("items").get(0).path("targetId").asLong()
                < collection.path("items").get(1).path("targetId").asLong());
        assertEquals("SESSION", collection.path("items").get(2).path("targetType").asText());
        assertEquals("SESSION", collection.path("items").get(3).path("targetType").asText());
        JsonNode requestItem = findWork(collection.path("items"), "REQUEST", unlinked.getId());
        JsonNode sessionItem = findWork(collection.path("items"), "SESSION", linkedSession.getId());
        JsonNode zeroItem = findWork(collection.path("items"), "SESSION", zeroLinkedSession.getId());
        assertEquals(unlinked.getId(), requestItem.path("targetId").asLong());
        assertEquals(linkedSession.getId(), sessionItem.path("targetId").asLong());
        assertEquals(linkedOne.getId(), sessionItem.path("linkedRequestVersions").get(0).path("requestId").asLong());
        assertEquals(linkedTwo.getId(), sessionItem.path("linkedRequestVersions").get(1).path("requestId").asLong());
        assertEquals(0, requestItem.path("linkedRequestVersions").size());
        assertEquals(0, zeroItem.path("linkedRequestVersions").size());
        assertWorkAllowlist(requestItem);
        assertWorkAllowlist(sessionItem);
        assertFalse(collection.toString().toLowerCase().contains("private title"));
        assertFalse(collection.toString().toLowerCase().contains("private address"));
        assertFalse(collection.toString().toLowerCase().contains("tenantnote"));
        assertFalse(collection.toString().toLowerCase().contains("lessor"));

        assertEquals(403, send("GET", "/api/v1/operations/claimable-work", superviseOnly.token(), null).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/claimable-work", globalAdmin.token(), null).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/claimable-work", unprivilegedCustomer.token(), null).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/claimable-work?size=101", coordinatorA.token(), null).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/claimable-work?size=0", coordinatorA.token(), null).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/claimable-work?page=not-a-number",
                coordinatorA.token(), null).statusCode());
        JsonNode emptyPage = read("GET", "/api/v1/operations/claimable-work?page=20&size=1",
                coordinatorA.token(), 200);
        assertEquals(0, emptyPage.path("items").size());
        assertEquals(4, emptyPage.path("totalCount").asInt());
        assertEquals(404, send("GET", "/api/v1/operations/claimable-work/REQUEST/" + linkedOne.getId(),
                coordinatorA.token(), null).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/claimable-work/REQUEST/" + sameCityOtherTeam.getId(),
                coordinatorA.token(), null).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/claimable-work/REQUEST/" + differentCity.getId(),
                coordinatorA.token(), null).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/claimable-work/REQUEST/" + 999_999_999L,
                coordinatorA.token(), null).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/claimable-work/invalid/1",
                coordinatorA.token(), null).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/claimable-work/REQUEST/not-an-id",
                coordinatorA.token(), null).statusCode());

        long requestVersion = requestItem.path("expectedVersion").asLong();
        ObjectNode requestClaim = mapper.createObjectNode().put("expectedRequestVersion", requestVersion)
                .put("reasonCode", "CLAIM_WORK");
        assertEquals(204, send("POST", "/api/v1/operations/visit-requests/" + unlinked.getId() + "/claim",
                coordinatorA.token(), requestClaim).statusCode());
        assertEquals(coordinatorA.user().getId(), requests.findById(unlinked.getId()).orElseThrow()
                .getCoordinator().getId());

        HttpResponse<String> sessionMetadataResponse = send("GET",
                "/api/v1/operations/claimable-work/SESSION/" + linkedSession.getId(), coordinatorA.token(), null);
        assertEquals(200, sessionMetadataResponse.statusCode(), sessionMetadataResponse.body());
        assertTrue(sessionMetadataResponse.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode sessionRefresh = mapper.readTree(sessionMetadataResponse.body());
        ObjectNode sessionClaim = sessionCommand(sessionRefresh, "CLAIM_WORK");
        assertEquals(404, send("GET", "/api/v1/operations/visit-sessions/" + linkedSession.getId(),
                coordinatorB.token(), null).statusCode(), "claimable discovery must not grant normal detail visibility");
        int sessionClaimAuditsBeforeRejections = jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE action_code='OWNERSHIP_CLAIMED' AND target_type='VISIT_SESSION' AND target_id=?",
                Integer.class, linkedSession.getId());
        ObjectNode wrongSessionVersion = sessionCommand(sessionRefresh, "CLAIM_WORK")
                .put("expectedSessionVersion", sessionRefresh.path("expectedVersion").asLong() + 1);
        assertEquals(409, send("POST", "/api/v1/operations/visit-sessions/" + linkedSession.getId() + "/claim",
                coordinatorA.token(), wrongSessionVersion).statusCode());
        ObjectNode missingChild = sessionCommand(sessionRefresh, "CLAIM_WORK");
        String firstChildId = sessionRefresh.path("linkedRequestVersions").get(0).path("requestId").asText();
        ((ObjectNode) missingChild.path("expectedRequestVersions")).remove(firstChildId);
        assertEquals(409, send("POST", "/api/v1/operations/visit-sessions/" + linkedSession.getId() + "/claim",
                coordinatorA.token(), missingChild).statusCode());
        ObjectNode extraChild = sessionCommand(sessionRefresh, "CLAIM_WORK");
        ((ObjectNode) extraChild.path("expectedRequestVersions")).put("999999999", 0L);
        assertEquals(409, send("POST", "/api/v1/operations/visit-sessions/" + linkedSession.getId() + "/claim",
                coordinatorA.token(), extraChild).statusCode());
        ObjectNode nullChildVersion = sessionCommand(sessionRefresh, "CLAIM_WORK");
        ((ObjectNode) nullChildVersion.path("expectedRequestVersions")).putNull(firstChildId);
        assertEquals(409, send("POST", "/api/v1/operations/visit-sessions/" + linkedSession.getId() + "/claim",
                coordinatorA.token(), nullChildVersion).statusCode());
        ObjectNode staleChildVersion = sessionCommand(sessionRefresh, "CLAIM_WORK");
        ((ObjectNode) staleChildVersion.path("expectedRequestVersions")).put(firstChildId,
                sessionRefresh.path("linkedRequestVersions").get(0).path("version").asLong() + 1);
        assertEquals(409, send("POST", "/api/v1/operations/visit-sessions/" + linkedSession.getId() + "/claim",
                coordinatorA.token(), staleChildVersion).statusCode());
        assertEquals(sessionClaimAuditsBeforeRejections, jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE action_code='OWNERSHIP_CLAIMED' AND target_type='VISIT_SESSION' AND target_id=?",
                Integer.class, linkedSession.getId()));
        assertNull(sessions.findById(linkedSession.getId()).orElseThrow().getCoordinator());
        assertNull(requests.findById(linkedOne.getId()).orElseThrow().getCoordinator());
        assertNull(requests.findById(linkedTwo.getId()).orElseThrow().getCoordinator());
        assertEquals(204, send("POST", "/api/v1/operations/visit-sessions/" + linkedSession.getId() + "/claim",
                coordinatorA.token(), sessionClaim).statusCode());
        assertEquals(coordinatorA.user().getId(), sessions.findById(linkedSession.getId()).orElseThrow()
                .getCoordinator().getId());
        assertEquals(coordinatorA.user().getId(), requests.findById(linkedOne.getId()).orElseThrow()
                .getCoordinator().getId());
        assertEquals(coordinatorA.user().getId(), requests.findById(linkedTwo.getId()).orElseThrow()
                .getCoordinator().getId());
        assertEquals(404, send("GET", "/api/v1/operations/visit-sessions/" + linkedSession.getId(),
                coordinatorB.token(), null).statusCode(), "claimable discovery must not grant normal detail visibility");
        SystemNotification unrelatedOperational = new SystemNotification(TargetRole.EMPLOYEE,
                coordinatorB.user().getId().toString(), "Private operational notification", "Test", null,
                "VISIT_SESSION", "info");
        unrelatedOperational.setAuthorizationClass(NotificationAuthorizationClass.OPERATIONS_SESSION);
        unrelatedOperational.setOperationalSessionId(linkedSession.getId());
        notifications.saveAndFlush(unrelatedOperational);
        JsonNode coordinatorBNotifications = read("GET", "/api/v1/notifications", coordinatorB.token(), 200);
        assertFalse(coordinatorBNotifications.toString().contains("Private operational notification"));

        VisitSession linkageSession = session("linkage-race", tenant, city, teamA);
        PropertyVisitRequest linkageRace = request("linkage-race", tenant, city, teamA, locality,
                null, VisitRequestStatus.RECEIVED);
        JsonNode beforeLink = read("GET", "/api/v1/operations/claimable-work", coordinatorA.token(), 200);
        JsonNode staleRequestSnapshot = findWork(beforeLink.path("items"), "REQUEST", linkageRace.getId());
        linkageRace.setSession(linkageSession);
        requests.saveAndFlush(linkageRace);
        ObjectNode staleRequestClaim = mapper.createObjectNode()
                .put("expectedRequestVersion", staleRequestSnapshot.path("expectedVersion").asLong())
                .put("reasonCode", "CLAIM_WORK");
        assertEquals(409, send("POST", "/api/v1/operations/visit-requests/" + linkageRace.getId() + "/claim",
                coordinatorA.token(), staleRequestClaim).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/claimable-work/REQUEST/" + linkageRace.getId(),
                coordinatorA.token(), null).statusCode());
        JsonNode afterLink = read("GET", "/api/v1/operations/claimable-work", coordinatorA.token(), 200);
        assertFalse(hasWork(afterLink.path("items"), "REQUEST", linkageRace.getId()));
        assertEquals(1, countWork(afterLink.path("items"), "SESSION", linkageSession.getId()));
        JsonNode canonicalSession = findWork(afterLink.path("items"), "SESSION", linkageSession.getId());
        assertEquals(linkageRace.getId(), canonicalSession.path("linkedRequestVersions").get(0).path("requestId").asLong());
        ObjectNode canonicalClaim = sessionCommand(canonicalSession, "CLAIM_WORK");
        assertEquals(204, send("POST", "/api/v1/operations/visit-sessions/" + linkageSession.getId() + "/claim",
                coordinatorA.token(), canonicalClaim).statusCode());

        JsonNode raceSnapshotA = read("GET", "/api/v1/operations/claimable-work", coordinatorA.token(), 200);
        JsonNode raceSnapshotB = read("GET", "/api/v1/operations/claimable-work", coordinatorB.token(), 200);
        JsonNode raceItemA = findWork(raceSnapshotA.path("items"), "REQUEST", raceRequest.getId());
        JsonNode raceItemB = findWork(raceSnapshotB.path("items"), "REQUEST", raceRequest.getId());
        assertEquals(raceItemA.path("expectedVersion").asLong(), raceItemB.path("expectedVersion").asLong());
        int[] raceStatuses = raceClaims(coordinatorA, coordinatorB, raceRequest.getId(),
                raceItemA.path("expectedVersion").asLong(), raceItemB.path("expectedVersion").asLong());
        assertEquals(1, java.util.Arrays.stream(raceStatuses).filter(status -> status == 204).count());
        assertEquals(1, java.util.Arrays.stream(raceStatuses).filter(status -> status == 409).count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE action_code='OWNERSHIP_CLAIMED' AND target_type='PROPERTY_VISIT_REQUEST' AND target_id=?",
                Integer.class, raceRequest.getId()));
        JsonNode afterRace = read("GET", "/api/v1/operations/claimable-work", coordinatorA.token(), 200);
        assertFalse(hasWork(afterRace.path("items"), "REQUEST", raceRequest.getId()));
        assertFalse(hasWork(read("GET", "/api/v1/operations/claimable-work", coordinatorB.token(), 200)
                .path("items"), "REQUEST", raceRequest.getId()));

        JsonNode supervisorQueue = read("GET", "/api/v1/operations/visit-requests?page=0&size=100",
                supervisor.token(), 200);
        JsonNode queueRows = supervisorQueue.path("requests");
        assertFalse(hasRequest(queueRows, unlinked.getId()), "supervisor must not gain unlinked Request visibility");
        assertTrue(hasRequest(queueRows, linkedOne.getId()));
        assertEquals(linkedSession.getId(), requestFrom(queueRows, linkedOne.getId()).path("sessionId").asLong());

        JsonNode authorizedSession = read("GET", "/api/v1/operations/visit-sessions/" + linkedSession.getId(),
                supervisor.token(), 200);
        assertEquals(2, authorizedSession.path("linkedRequestVersions").size());
        assertEquals(linkedOne.getId(), authorizedSession.path("linkedRequestVersions").get(0).path("requestId").asLong());
        ObjectNode assignment = sessionCommand(authorizedSession, "ASSIGN_COORDINATOR")
                .put("coordinatorUserId", coordinatorB.user().getId());
        assertEquals(204, send("POST", "/api/v1/operations/visit-sessions/" + linkedSession.getId() + "/coordinator",
                supervisor.token(), assignment).statusCode());

        JsonNode beforeTransfer = read("GET", "/api/v1/operations/visit-sessions/" + linkedSession.getId(),
                supervisor.token(), 200);
        ObjectNode transfer = sessionCommand(beforeTransfer, "TRANSFER_TEAM")
                .put("destinationTeamId", teamB.getId()).put("coordinatorUserId", coordinatorB.user().getId());
        assertEquals(204, send("POST", "/api/v1/operations/visit-sessions/" + linkedSession.getId() + "/team-transfer",
                supervisor.token(), transfer).statusCode());

        JsonNode beforeRelease = read("GET", "/api/v1/operations/visit-sessions/" + linkedSession.getId(),
                coordinatorB.token(), 200);
        ObjectNode release = sessionCommand(beforeRelease, "RELEASE_COORDINATOR");
        assertEquals(204, send("POST", "/api/v1/operations/visit-sessions/" + linkedSession.getId() + "/release",
                coordinatorB.token(), release).statusCode());
        assertNull(sessions.findById(linkedSession.getId()).orElseThrow().getCoordinator());
        assertNull(requests.findById(linkedOne.getId()).orElseThrow().getCoordinator());
        assertNull(requests.findById(linkedTwo.getId()).orElseThrow().getCoordinator());
        assertTrue(supervisorQueue.path("requests").isArray());
    }

    @Test
    void inactiveScopesRequireActiveEndpointEligibilityButOtherActiveScopesPreserveNotFound() throws Exception {
        Actor inactiveTeamCoordinator = activeCoordinator("inactive-team-only");
        SupportedCity teamCity = cities.saveAndFlush(new SupportedCity(code("inactive-team-city"),
                "Inactive Team City", true));
        OperatingTeam inactiveOnlyTeam = teams.saveAndFlush(
                new OperatingTeam(teamCity, code("inactive-only-team"), "Inactive Only Team", true));
        grant(inactiveTeamCoordinator, StaffCapability.OPS_COORDINATE, inactiveOnlyTeam);
        Actor tenant = actor("inactive-scope-target", Role.ROLE_TENANT, null);
        Locality teamLocality = locality(teamCity);
        PropertyVisitRequest inactiveTeamTarget = request("inactive-team-target", tenant, teamCity,
                inactiveOnlyTeam, teamLocality, null, VisitRequestStatus.RECEIVED);
        inactiveOnlyTeam.setActive(false);
        teams.saveAndFlush(inactiveOnlyTeam);
        assertEquals(403, send("GET", "/api/v1/operations/claimable-work",
                inactiveTeamCoordinator.token(), null).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/claimable-work/REQUEST/" + inactiveTeamTarget.getId(),
                inactiveTeamCoordinator.token(), null).statusCode());

        Actor inactiveCityCoordinator = activeCoordinator("inactive-city-only");
        SupportedCity inactiveOnlyCity = cities.saveAndFlush(new SupportedCity(code("inactive-only-city"),
                "Inactive Only City", true));
        OperatingTeam teamInInactiveCity = teams.saveAndFlush(
                new OperatingTeam(inactiveOnlyCity, code("team-in-inactive-city"), "Team In Inactive City", true));
        grant(inactiveCityCoordinator, StaffCapability.OPS_COORDINATE, teamInInactiveCity);
        Locality cityLocality = locality(inactiveOnlyCity);
        PropertyVisitRequest inactiveCityTarget = request("inactive-city-target", tenant, inactiveOnlyCity,
                teamInInactiveCity, cityLocality, null, VisitRequestStatus.RECEIVED);
        inactiveOnlyCity.setActive(false);
        cities.saveAndFlush(inactiveOnlyCity);
        assertEquals(403, send("GET", "/api/v1/operations/claimable-work",
                inactiveCityCoordinator.token(), null).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/claimable-work/REQUEST/" + inactiveCityTarget.getId(),
                inactiveCityCoordinator.token(), null).statusCode());

        Actor mixedCoordinator = activeCoordinator("mixed-active-inactive");
        SupportedCity mixedCity = cities.saveAndFlush(new SupportedCity(code("mixed-scope-city"),
                "Mixed Scope City", true));
        OperatingTeam activeTeam = teams.saveAndFlush(
                new OperatingTeam(mixedCity, code("mixed-active-team"), "Mixed Active Team", true));
        OperatingTeam inactiveTeam = teams.saveAndFlush(
                new OperatingTeam(mixedCity, code("mixed-inactive-team"), "Mixed Inactive Team", true));
        grant(mixedCoordinator, StaffCapability.OPS_COORDINATE, activeTeam);
        grant(mixedCoordinator, StaffCapability.OPS_COORDINATE, inactiveTeam);
        Locality mixedLocality = locality(mixedCity);
        PropertyVisitRequest mixedInactiveTarget = request("mixed-inactive-target", tenant, mixedCity,
                inactiveTeam, mixedLocality, null, VisitRequestStatus.RECEIVED);
        inactiveTeam.setActive(false);
        teams.saveAndFlush(inactiveTeam);
        JsonNode mixedPage = read("GET", "/api/v1/operations/claimable-work", mixedCoordinator.token(), 200);
        assertEquals(0, mixedPage.path("totalCount").asInt());
        assertTrue(mixedPage.path("items").isEmpty());
        assertEquals(404, send("GET", "/api/v1/operations/claimable-work/REQUEST/" + mixedInactiveTarget.getId(),
                mixedCoordinator.token(), null).statusCode());

        Actor zeroWorkCoordinator = activeCoordinator("valid-zero-work");
        SupportedCity zeroWorkCity = cities.saveAndFlush(new SupportedCity(code("zero-work-city"),
                "Zero Work City", true));
        OperatingTeam zeroWorkTeam = teams.saveAndFlush(
                new OperatingTeam(zeroWorkCity, code("zero-work-team"), "Zero Work Team", true));
        grant(zeroWorkCoordinator, StaffCapability.OPS_COORDINATE, zeroWorkTeam);
        JsonNode zeroWorkPage = read("GET", "/api/v1/operations/claimable-work", zeroWorkCoordinator.token(), 200);
        assertEquals(0, zeroWorkPage.path("totalCount").asInt());
        assertTrue(zeroWorkPage.path("items").isEmpty());

        Actor noCoordinator = activeCoordinator("no-coordinator-grant");
        assertEquals(403, send("GET", "/api/v1/operations/claimable-work", noCoordinator.token(), null).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/claimable-work/REQUEST/" + inactiveTeamTarget.getId(),
                noCoordinator.token(), null).statusCode());
    }

    private Locality locality(SupportedCity city) {
        Locality locality = localities.saveAndFlush(new Locality(city.getDisplayName(),
                "Eligibility Sector " + UUID.randomUUID(), null, null, null));
        locality.setSupportedCity(city);
        return localities.saveAndFlush(locality);
    }

    private int[] raceClaims(Actor first, Actor second, long requestId, long firstVersion, long secondVersion)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> raceClaim(first, requestId, firstVersion, ready, start));
            var b = executor.submit(() -> raceClaim(second, requestId, secondVersion, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return new int[]{a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)};
        } finally {
            executor.shutdownNow();
        }
    }

    private int raceClaim(Actor actor, long requestId, long version, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("claim start timed out");
            ObjectNode command = mapper.createObjectNode().put("expectedRequestVersion", version)
                    .put("reasonCode", "CLAIM_WORK");
            return send("POST", "/api/v1/operations/visit-requests/" + requestId + "/claim",
                    actor.token(), command).statusCode();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ObjectNode sessionCommand(JsonNode snapshot, String reason) {
        long version = snapshot.has("expectedVersion")
                ? snapshot.path("expectedVersion").asLong() : snapshot.path("version").asLong();
        ObjectNode command = mapper.createObjectNode().put("expectedSessionVersion", version)
                .put("reasonCode", reason);
        ObjectNode versions = command.putObject("expectedRequestVersions");
        for (JsonNode child : snapshot.path("linkedRequestVersions")) {
            versions.put(child.path("requestId").asText(), child.path("version").asLong());
        }
        return command;
    }

    private void assertWorkAllowlist(JsonNode item) {
        Set<String> fields = new HashSet<>();
        item.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("targetType", "targetId", "expectedVersion", "cityId", "cityDisplayName",
                "teamId", "teamDisplayName", "status", "linkedRequestVersions"), fields);
        for (JsonNode child : item.path("linkedRequestVersions")) {
            Set<String> childFields = new HashSet<>();
            child.fieldNames().forEachRemaining(childFields::add);
            assertEquals(Set.of("requestId", "version"), childFields);
        }
    }

    private JsonNode findWork(JsonNode rows, String targetType, long id) {
        for (JsonNode row : rows) {
            if (targetType.equals(row.path("targetType").asText()) && row.path("targetId").asLong() == id) return row;
        }
        throw new AssertionError("Missing " + targetType + " target " + id + " in " + rows);
    }

    private boolean hasWork(JsonNode rows, String targetType, long id) {
        for (JsonNode row : rows) {
            if (targetType.equals(row.path("targetType").asText()) && row.path("targetId").asLong() == id) return true;
        }
        return false;
    }

    private int countWork(JsonNode rows, String targetType, long id) {
        int count = 0;
        for (JsonNode row : rows) {
            if (targetType.equals(row.path("targetType").asText()) && row.path("targetId").asLong() == id) count++;
        }
        return count;
    }

    private boolean hasRequest(JsonNode rows, long id) {
        for (JsonNode row : rows) if (row.path("requestId").asLong() == id) return true;
        return false;
    }

    private JsonNode requestFrom(JsonNode rows, long id) {
        for (JsonNode row : rows) if (row.path("requestId").asLong() == id) return row;
        throw new AssertionError("Missing Request " + id + " in " + rows);
    }

    private JsonNode read(String method, String path, String token, int expectedStatus) throws Exception {
        HttpResponse<String> response = send(method, path, token, null);
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String token, JsonNode body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + path));
            if (token != null) request.header("Authorization", "Bearer " + token);
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
            if (body != null) request.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            HttpRequest httpRequest = request.method(method, publisher).build();
            return HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Actor activeCoordinator(String label) {
        Actor actor = actor(label, Role.ROLE_TENANT, "OPERATIONS");
        EmployeeProfile profile = employees.findByUserId(actor.user().getId()).orElseThrow();
        profile.setStaffActive(true);
        employees.saveAndFlush(profile);
        return actor;
    }

    private Actor actor(String label, Role role, String profileRole) {
        User user = new User();
        user.setEmail("claimable-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("+91987654" + String.format("%04d", (int) (Math.random() * 10000)));
        user.setRole(role);
        user = users.saveAndFlush(user);
        if (profileRole != null) employees.saveAndFlush(new EmployeeProfile(user, profileRole, null, BigDecimal.ZERO));
        return new Actor(user, jwt.generateToken(user.getId(), user.getEmail(), user.getRole().name()));
    }

    private StaffAccessGrant grant(Actor actor, StaffCapability capability, OperatingTeam team) {
        return grants.saveAndFlush(new StaffAccessGrant(actor.user(), capability, StaffScopeType.TEAM, null, team,
                Instant.now().minusSeconds(60), null, null, Instant.now(), "TEST_CLAIMABLE",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private VisitSession session(String suffix, Actor tenant, SupportedCity city, OperatingTeam team) {
        VisitSession session = new VisitSession();
        session.setTenant(tenant.user());
        session.setCity(city.getDisplayName());
        session.setSupportedCity(city);
        session.setOperatingTeam(team);
        session.setOperationalScopeReady(true);
        return sessions.saveAndFlush(session);
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

    private PropertyVisitRequest request(String suffix, Actor tenant, SupportedCity city, OperatingTeam team,
            Locality locality, VisitSession session, VisitRequestStatus status) {
        Listing listing = createListing(locality, "Private title " + suffix + " " + UUID.randomUUID());
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant.user());
        request.setListing(listing);
        request.setStatus(status);
        request.setSession(session);
        request.setSupportedCity(city);
        request.setOperatingTeam(team);
        request.setOperationalScopeReady(true);
        request.setTenantNote("Private tenant note " + suffix);
        return requests.saveAndFlush(request);
    }

    private String code(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Actor(User user, String token) {}
}
