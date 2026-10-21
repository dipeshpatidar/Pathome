package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.security.JwtUtils;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2A-1 read foundation: coordinator-owned My Work roots, claimed standalone Request continuity,
 * scoped Request detail and the additive Session-detail coordination fields.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=my-work-http-test-jwt-secret-32-bytes-minimum",
        "pathome.visit.otp.hmac-secret=my-work-http-test-otp-secret-32-bytes-minimum",
        "cloudinary.api-key=my-work-test-key",
        "cloudinary.api-secret=my-work-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class OperationsMyWorkHttpPostgresTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "my_work_http_test_" + UUID.randomUUID().toString().replace("-", "");
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
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int httpPort;

    @AfterEach
    void clearReadBarrier() {
        SessionReadBarrier.reset();
    }

    @Test
    void additiveFiltersValidateAtHttpAndStayInsideTheAuthorizedScope() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("filt"), "Filter City", true));
        SupportedCity foreignCity = cities.saveAndFlush(new SupportedCity(code("filt-away"), "Foreign City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("filt-team"), "Filter Team", true));
        OperatingTeam foreignTeam = teams.saveAndFlush(
                new OperatingTeam(foreignCity, code("filt-away-team"), "Foreign Team", true));
        Locality locality = locality(city, "Filter");
        Actor tenant = actor("filter-tenant", Role.ROLE_TENANT, null, false);
        Actor owner = actor("filter-owner", Role.ROLE_ADMIN, "OPERATIONS", true);
        teamGrant(owner, team, Instant.now().minusSeconds(60), null);
        PropertyVisitRequest request = standaloneRequest("filter-request", tenant.user(), city, team, locality,
                owner.user(), VisitRequestStatus.RECEIVED);
        VisitSession draft = draftSession("filter-draft", tenant.user(), city, team, owner.user());

        JsonNode filtered = read("GET",
                "/api/v1/operations/my-work?page=0&size=20&targetType=REQUEST&status=RECEIVED", owner.token());
        assertEquals(1, filtered.path("totalCount").asInt());
        assertEquals(2, filtered.path("unfilteredTotalCount").asInt());
        assertEquals(1, filtered.path("items").size());
        assertEquals(request.getId(), filtered.path("items").get(0).path("targetId").asLong());

        JsonNode exact = read("GET",
                "/api/v1/operations/my-work?page=0&size=20&targetType=SESSION&targetId=" + draft.getId(),
                owner.token());
        assertEquals(1, exact.path("totalCount").asInt());
        assertEquals(draft.getId(), exact.path("items").get(0).path("targetId").asLong());

        JsonNode foreign = read("GET",
                "/api/v1/operations/my-work?page=0&size=20&cityId=" + foreignCity.getId()
                        + "&teamId=" + foreignTeam.getId(), owner.token());
        assertEquals(0, foreign.path("totalCount").asInt());
        assertEquals(2, foreign.path("unfilteredTotalCount").asInt());
        assertEquals(0, foreign.path("items").size());

        assertEquals(400, send("GET",
                "/api/v1/operations/my-work?status=RECEIVED", owner.token()).statusCode());
        assertEquals(400, send("GET",
                "/api/v1/operations/my-work?targetType=REQUEST&status=DRAFT", owner.token()).statusCode());
        assertEquals(400, send("GET",
                "/api/v1/operations/my-work?targetType=REQUEST&targetId=abc", owner.token()).statusCode());
        assertEquals(400, send("GET",
                "/api/v1/operations/my-work?cityId=-1", owner.token()).statusCode());
    }

    @Test
    void eligibilityRequiresCurrentActiveCoordinatorScope() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("elig"), "Eligibility City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("elig-team"), "Eligibility Team", true));
        SupportedCity inactiveCity = cities.saveAndFlush(new SupportedCity(code("elig-off"), "Inactive City", true));
        OperatingTeam teamInInactiveCity = teams.saveAndFlush(
                new OperatingTeam(inactiveCity, code("elig-off-team"), "Inactive City Team", true));
        OperatingTeam inactiveTeam = teams.saveAndFlush(
                new OperatingTeam(city, code("elig-sleeping-team"), "Inactive Team", true));

        assertEquals(401, send("GET", "/api/v1/operations/my-work", null).statusCode());

        Actor customer = actor("customer", Role.ROLE_TENANT, null, false);
        assertEquals(403, send("GET", "/api/v1/operations/my-work", customer.token()).statusCode());

        Actor noGrantStaff = actor("no-grant", Role.ROLE_TENANT, "OPERATIONS", true);
        assertEquals(403, send("GET", "/api/v1/operations/my-work", noGrantStaff.token()).statusCode());

        Actor inactiveProfile = actor("inactive-profile", Role.ROLE_TENANT, "OPERATIONS", false);
        teamGrant(inactiveProfile, team, Instant.now().minusSeconds(60), null);
        assertEquals(403, send("GET", "/api/v1/operations/my-work", inactiveProfile.token()).statusCode());

        Actor revoked = actor("revoked", Role.ROLE_TENANT, "OPERATIONS", true);
        StaffAccessGrant revokedGrant = teamGrant(revoked, team, Instant.now().minusSeconds(60), null);
        revokedGrant.revoke(users.findById(revoked.user().getId()).orElseThrow(), Instant.now(), "TEST_REVOKED");
        grants.saveAndFlush(revokedGrant);
        assertEquals(403, send("GET", "/api/v1/operations/my-work", revoked.token()).statusCode());

        Actor expired = actor("expired", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(expired, team, Instant.now().minusSeconds(600), Instant.now().minusSeconds(60));
        assertEquals(403, send("GET", "/api/v1/operations/my-work", expired.token()).statusCode());

        Actor future = actor("future", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(future, team, Instant.now().plusSeconds(3600), null);
        assertEquals(403, send("GET", "/api/v1/operations/my-work", future.token()).statusCode());

        Actor inactiveTeamOnly = actor("inactive-team", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(inactiveTeamOnly, inactiveTeam, Instant.now().minusSeconds(60), null);
        inactiveTeam.setActive(false);
        teams.saveAndFlush(inactiveTeam);
        assertEquals(403, send("GET", "/api/v1/operations/my-work", inactiveTeamOnly.token()).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/my-work?attentionOnly=true",
                inactiveTeamOnly.token()).statusCode());

        Actor inactiveCityOnly = actor("inactive-city", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(inactiveCityOnly, teamInInactiveCity, Instant.now().minusSeconds(60), null);
        inactiveCity.setActive(false);
        cities.saveAndFlush(inactiveCity);
        assertEquals(403, send("GET", "/api/v1/operations/my-work", inactiveCityOnly.token()).statusCode());

        Actor emptyCoordinator = actor("empty", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(emptyCoordinator, team, Instant.now().minusSeconds(60), null);
        HttpResponse<String> emptyPage = send("GET", "/api/v1/operations/my-work", emptyCoordinator.token());
        assertEquals(200, emptyPage.statusCode(), emptyPage.body());
        assertTrue(emptyPage.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode empty = mapper.readTree(emptyPage.body());
        assertEquals(0, empty.path("totalCount").asInt());
        assertEquals(0, empty.path("totalPages").asInt());
        assertTrue(empty.path("items").isEmpty());
        assertEquals(0, empty.path("page").asInt());
        assertEquals(20, empty.path("size").asInt());

        assertEquals(400, send("GET", "/api/v1/operations/my-work?size=0", emptyCoordinator.token()).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/my-work?size=101", emptyCoordinator.token()).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/my-work?page=-1", emptyCoordinator.token()).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/my-work?page=abc", emptyCoordinator.token()).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/my-work?attentionOnly=not-a-boolean",
                emptyCoordinator.token()).statusCode());
    }

    @Test
    void myWorkRootsAreOwnerScopedCollapsedAndTruthfullyFiltered() throws Exception {
        SupportedCity cityA = cities.saveAndFlush(new SupportedCity(code("city-a"), "My Work City A", true));
        SupportedCity cityB = cities.saveAndFlush(new SupportedCity(code("city-b"), "My Work City B", true));
        OperatingTeam teamA1 = teams.saveAndFlush(new OperatingTeam(cityA, code("team-a1"), "Team A1", true));
        OperatingTeam teamA2 = teams.saveAndFlush(new OperatingTeam(cityA, code("team-a2"), "Team A2", true));
        OperatingTeam teamB1 = teams.saveAndFlush(new OperatingTeam(cityB, code("team-b1"), "Team B1", true));
        Locality localityA = locality(cityA, "my-work-a");
        Locality localityB = locality(cityB, "my-work-b");

        Actor tenant = actor("tenant", Role.ROLE_TENANT, null, false);
        Actor ge = actor("ge", Role.ROLE_GROUND_BOY, null, false);
        Actor owner = actor("owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor teammate = actor("teammate", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor otherCityCoordinator = actor("other-city", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, teamA1, Instant.now().minusSeconds(60), null);
        // A duplicate effective grant for the same Team must never duplicate a root.
        teamGrant(owner, teamA1, Instant.now().minusSeconds(120), null);
        teamGrant(teammate, teamA1, Instant.now().minusSeconds(60), null);
        teamGrant(otherCityCoordinator, teamB1, Instant.now().minusSeconds(60), null);

        PropertyVisitRequest ownedReceived = standaloneRequest("owned-received", tenant.user(), cityA, teamA1,
                localityA, owner.user(), VisitRequestStatus.RECEIVED);
        PropertyVisitRequest ownedUnavailable = standaloneRequest("owned-unavailable", tenant.user(), cityA, teamA1,
                localityA, owner.user(), VisitRequestStatus.UNAVAILABLE);
        PropertyVisitRequest claimableUnlinked = standaloneRequest("claimable", tenant.user(), cityA, teamA1,
                localityA, null, VisitRequestStatus.RECEIVED);
        PropertyVisitRequest teammateOwned = standaloneRequest("teammate-owned", tenant.user(), cityA, teamA1,
                localityA, teammate.user(), VisitRequestStatus.RECEIVED);
        PropertyVisitRequest ownerCrossTeam = standaloneRequest("owner-cross-team", tenant.user(), cityA, teamA2,
                localityA, owner.user(), VisitRequestStatus.RECEIVED);
        PropertyVisitRequest ownerCrossCity = standaloneRequest("owner-cross-city", tenant.user(), cityB, teamB1,
                localityB, owner.user(), VisitRequestStatus.RECEIVED);

        Instant executionChangedAt = Instant.now().minusSeconds(30);
        VisitSession sessionRoot = scheduledSession("root", tenant.user(), cityA, teamA1, owner.user(), ge.user(),
                "NOT_REQUIRED", VisitSessionStatus.SCHEDULED, executionChangedAt, 1);
        PropertyVisitRequest rootChildOne = linkedRequest("root-child-1", tenant.user(), cityA, teamA1, localityA,
                sessionRoot, owner.user(), VisitRequestStatus.COORDINATING);
        PropertyVisitRequest rootChildTwo = linkedRequest("root-child-2", tenant.user(), cityA, teamA1, localityA,
                sessionRoot, owner.user(), VisitRequestStatus.CANCELLED);
        rootChildOne.setPreferredAreas("version bump");
        rootChildOne = requests.saveAndFlush(rootChildOne);
        assertEquals(1L, rootChildOne.getVersion(), "the first linked Request must carry a distinct version token");

        VisitSession draftSession = draftSession("draft", tenant.user(), cityA, teamA1, owner.user());
        VisitSession pendingConfirmation = scheduledSession("pending-confirmation", tenant.user(), cityA, teamA1,
                owner.user(), ge.user(), "PENDING", VisitSessionStatus.SCHEDULED, null, 2);
        VisitSession interrupted = scheduledSession("interrupted", tenant.user(), cityA, teamA1, owner.user(),
                ge.user(), "CONFIRMED", VisitSessionStatus.INTERRUPTED, null, 3);
        VisitSession completed = scheduledSession("completed", tenant.user(), cityA, teamA1, owner.user(),
                ge.user(), "CONFIRMED", VisitSessionStatus.COMPLETED, null, 4);
        VisitSession teammateSession = draftSession("teammate-session", tenant.user(), cityA, teamA1,
                teammate.user());

        JsonNode page = read("GET", "/api/v1/operations/my-work?page=0&size=100", owner.token());
        assertEquals(7, page.path("totalCount").asInt(),
                "only roots currently owned by the caller are counted: " + page);
        assertEquals(7, page.path("items").size());
        assertEquals(1, page.path("totalPages").asInt());

        assertEquals(1, countRoot(page.path("items"), "REQUEST", ownedReceived.getId()));
        assertEquals(1, countRoot(page.path("items"), "REQUEST", ownedUnavailable.getId()),
                "an UNAVAILABLE standalone Request stays visible as owned work history");
        assertFalse(hasRoot(page.path("items"), "REQUEST", claimableUnlinked.getId()),
                "unclaimed work must not enter My Work");
        assertFalse(hasRoot(page.path("items"), "REQUEST", teammateOwned.getId()));
        assertFalse(hasRoot(page.path("items"), "REQUEST", ownerCrossTeam.getId()),
                "a Team without a current grant must stay hidden");
        assertFalse(hasRoot(page.path("items"), "REQUEST", ownerCrossCity.getId()),
                "a City without a current grant must stay hidden");
        assertFalse(hasRoot(page.path("items"), "REQUEST", rootChildOne.getId()),
                "a linked Request must not appear as a REQUEST root");
        assertFalse(hasRoot(page.path("items"), "REQUEST", rootChildTwo.getId()));

        assertEquals(1, countRoot(page.path("items"), "SESSION", sessionRoot.getId()));
        assertEquals(1, countRoot(page.path("items"), "SESSION", draftSession.getId()));
        assertEquals(1, countRoot(page.path("items"), "SESSION", pendingConfirmation.getId()));
        assertEquals(1, countRoot(page.path("items"), "SESSION", interrupted.getId()));
        assertEquals(1, countRoot(page.path("items"), "SESSION", completed.getId()));
        assertFalse(hasRoot(page.path("items"), "SESSION", teammateSession.getId()),
                "a teammate coordinator must not receive another coordinator's My Work");

        JsonNode sessionRootItem = findRoot(page.path("items"), "SESSION", sessionRoot.getId());
        JsonNode linked = sessionRootItem.path("linkedRequestVersions");
        assertEquals(2, linked.size(), "the complete linked Request version set is required regardless of child status");
        assertEquals(rootChildOne.getId(), linked.get(0).path("requestId").asLong());
        assertEquals(rootChildTwo.getId(), linked.get(1).path("requestId").asLong());
        assertEquals(rootChildOne.getVersion(), linked.get(0).path("version").asLong());
        assertEquals(rootChildTwo.getVersion(), linked.get(1).path("version").asLong());
        assertEquals(sessionRoot.getVersion(), sessionRootItem.path("expectedVersion").asLong());
        assertEquals(teamA1.getId(), sessionRootItem.path("teamId").asLong());
        assertEquals(cityA.getId(), sessionRootItem.path("cityId").asLong());
        assertEquals("NOT_REQUIRED", sessionRootItem.path("tenantConfirmationState").asText());
        assertFalse(sessionRootItem.path("updatedAt").isNull());
        assertFalse(sessionRootItem.path("scheduledAt").isNull());

        JsonNode requestItem = findRoot(page.path("items"), "REQUEST", ownedReceived.getId());
        assertEquals(ownedReceived.getVersion(), requestItem.path("expectedVersion").asLong());
        assertEquals("REQUEST", requestItem.path("targetType").asText());
        assertTrue(requestItem.path("tenantConfirmationState").isNull());
        assertTrue(requestItem.path("scheduledAt").isNull());
        assertTrue(requestItem.path("updatedAt").isNull(),
                "property_visit_requests stores no updated_at column, so My Work must not fabricate one");
        assertTrue(requestItem.path("linkedRequestVersions").isEmpty());
        assertEquals(teamA1.getId(), requestItem.path("teamId").asLong());

        assertWorkAllowlist(page.path("items"));
        String body = page.toString();
        assertFalse(body.contains("Private title"));
        assertFalse(body.contains("Private address"));
        assertFalse(body.contains("Private tenant note"));
        assertFalse(body.toLowerCase().contains("lessor"));

        assertDeterministicOrdering(page.path("items"));
        JsonNode repeat = read("GET", "/api/v1/operations/my-work?page=0&size=100", owner.token());
        assertEquals(page.path("items").toString(), repeat.path("items").toString(),
                "root ordering must be deterministic");

        JsonNode attention = read("GET", "/api/v1/operations/my-work?page=0&size=100&attentionOnly=true",
                owner.token());
        assertEquals(4, attention.path("totalCount").asInt(), "hidden work must never be counted: " + attention);
        assertEquals(4, attention.path("items").size());
        assertEquals(1, countRoot(attention.path("items"), "REQUEST", ownedReceived.getId()));
        assertFalse(hasRoot(attention.path("items"), "REQUEST", ownedUnavailable.getId()),
                "UNAVAILABLE is terminal for the owner and must not create false actionable work");
        assertEquals(1, countRoot(attention.path("items"), "SESSION", draftSession.getId()));
        assertEquals(1, countRoot(attention.path("items"), "SESSION", pendingConfirmation.getId()));
        assertEquals(1, countRoot(attention.path("items"), "SESSION", interrupted.getId()));
        assertFalse(hasRoot(attention.path("items"), "SESSION", sessionRoot.getId()),
                "a scheduled Visit without an outstanding tenant decision is not needing attention");
        assertFalse(hasRoot(attention.path("items"), "SESSION", completed.getId()));
        assertFalse(hasRoot(attention.path("items"), "SESSION", teammateSession.getId()));
        assertFalse(hasRoot(attention.path("items"), "REQUEST", claimableUnlinked.getId()));

        JsonNode firstPage = read("GET", "/api/v1/operations/my-work?page=0&size=3", owner.token());
        JsonNode secondPage = read("GET", "/api/v1/operations/my-work?page=1&size=3", owner.token());
        JsonNode thirdPage = read("GET", "/api/v1/operations/my-work?page=2&size=3", owner.token());
        assertEquals(7, firstPage.path("totalCount").asInt());
        assertEquals(7, secondPage.path("totalCount").asInt());
        assertEquals(7, thirdPage.path("totalCount").asInt());
        assertEquals(3, firstPage.path("totalPages").asInt());
        assertEquals(3, firstPage.path("items").size());
        assertEquals(1, thirdPage.path("items").size());
        List<String> pagedRoots = new ArrayList<>();
        firstPage.path("items").forEach(item -> pagedRoots.add(rootKey(item)));
        secondPage.path("items").forEach(item -> pagedRoots.add(rootKey(item)));
        thirdPage.path("items").forEach(item -> pagedRoots.add(rootKey(item)));
        Set<String> distinctPaged = new LinkedHashSet<>(pagedRoots);
        assertEquals(pagedRoots.size(), distinctPaged.size(), "a paged root must never be returned twice");
        assertEquals(7, distinctPaged.size(), "pages must cover every owned root exactly once");
        Set<String> unpagedRoots = new LinkedHashSet<>();
        page.path("items").forEach(item -> unpagedRoots.add(rootKey(item)));
        assertEquals(unpagedRoots, distinctPaged, "page results must match the unpaged predicate");

        JsonNode teammatePage = read("GET", "/api/v1/operations/my-work?page=0&size=100", teammate.token());
        assertEquals(2, teammatePage.path("totalCount").asInt());
        assertEquals(1, countRoot(teammatePage.path("items"), "REQUEST", teammateOwned.getId()));
        assertEquals(1, countRoot(teammatePage.path("items"), "SESSION", teammateSession.getId()));
        for (String hiddenKey : List.of("REQUEST:" + ownedReceived.getId(), "REQUEST:" + ownedUnavailable.getId(),
                "SESSION:" + sessionRoot.getId(), "SESSION:" + draftSession.getId())) {
            assertFalse(rootKeys(teammatePage.path("items")).contains(hiddenKey),
                    "teammate must not receive " + hiddenKey);
        }
        assertEquals(2, read("GET", "/api/v1/operations/my-work?attentionOnly=true", teammate.token())
                .path("totalCount").asInt());

        assertEquals(0, read("GET", "/api/v1/operations/my-work", otherCityCoordinator.token())
                .path("totalCount").asInt(), "another City coordinator must not see transferred-away work");
    }

    @Test
    void standaloneRequestDetailIsOwnerScopedAndPrivacyBounded() throws Exception {
        SupportedCity cityA = cities.saveAndFlush(new SupportedCity(code("detail-a"), "Detail City A", true));
        SupportedCity cityB = cities.saveAndFlush(new SupportedCity(code("detail-b"), "Detail City B", true));
        OperatingTeam teamA1 = teams.saveAndFlush(new OperatingTeam(cityA, code("detail-team-a1"), "Detail Team A1", true));
        OperatingTeam teamA2 = teams.saveAndFlush(new OperatingTeam(cityA, code("detail-team-a2"), "Detail Team A2", true));
        OperatingTeam teamB1 = teams.saveAndFlush(new OperatingTeam(cityB, code("detail-team-b1"), "Detail Team B1", true));
        Locality localityA = locality(cityA, "detail-a");

        Actor tenant = actor("detail-tenant", Role.ROLE_TENANT, null, false);
        Actor ge = actor("detail-ge", Role.ROLE_GROUND_BOY, null, false);
        Actor owner = actor("detail-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor teammate = actor("detail-teammate", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor crossTeam = actor("detail-cross-team", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor crossCity = actor("detail-cross-city", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor noScope = actor("detail-no-scope", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, teamA1, Instant.now().minusSeconds(60), null);
        teamGrant(teammate, teamA1, Instant.now().minusSeconds(60), null);
        teamGrant(crossTeam, teamA2, Instant.now().minusSeconds(60), null);
        teamGrant(crossCity, teamB1, Instant.now().minusSeconds(60), null);

        Instant availabilityStart = Instant.now().plusSeconds(3600);
        Instant availabilityEnd = availabilityStart.plusSeconds(7200);
        Instant preferredAt = availabilityStart.plusSeconds(600);
        PropertyVisitRequest owned = standaloneRequest("detail-owned", tenant.user(), cityA, teamA1,
                localityA, owner.user(), VisitRequestStatus.RECEIVED);
        owned.setAvailabilityStartAt(availabilityStart);
        owned.setAvailabilityEndAt(availabilityEnd);
        owned.setAvailabilityZoneId("Asia/Kolkata");
        owned.setPreferredAt(preferredAt);
        owned = requests.saveAndFlush(owned);
        // created_at is TIMESTAMP WITHOUT TIME ZONE; pin an exact stored wall-clock value so the
        // response contract can be asserted exactly instead of merely non-null.
        LocalDateTime exactCreatedAt = LocalDateTime.of(2026, 3, 4, 5, 6, 7);
        jdbc.update("update property_visit_requests set created_at = ? where id = ?",
                exactCreatedAt, owned.getId());

        VisitSession session = draftSession("detail-session", tenant.user(), cityA, teamA1, owner.user());
        PropertyVisitRequest linked = linkedRequest("detail-linked", tenant.user(), cityA, teamA1, localityA,
                session, owner.user(), VisitRequestStatus.COORDINATING);
        PropertyVisitRequest teammateOwned = standaloneRequest("detail-teammate-owned", tenant.user(), cityA, teamA1,
                localityA, teammate.user(), VisitRequestStatus.RECEIVED);
        PropertyVisitRequest crossTeamOwned = standaloneRequest("detail-cross-team-owned", tenant.user(), cityA, teamA2,
                localityA, crossTeam.user(), VisitRequestStatus.RECEIVED);
        Locality localityB = locality(cityB, "detail-b");
        PropertyVisitRequest crossCityOwned = standaloneRequest("detail-cross-city-owned", tenant.user(), cityB, teamB1,
                localityB, crossCity.user(), VisitRequestStatus.RECEIVED);

        JsonNode detail = read("GET", "/api/v1/operations/visit-requests/" + owned.getId(), owner.token());
        assertEquals(Set.of("requestId", "status", "version", "createdAt", "cityId", "cityDisplayName",
                "teamId", "teamDisplayName", "listingId", "listingTitle", "listingAddress", "listingStatus",
                "listingCity", "sector", "availabilityStartAt", "availabilityEndAt", "availabilityZoneId",
                "preferredAt", "preferredVisitTiming", "sessionId"), fieldNames(detail));
        assertEquals(owned.getId(), detail.path("requestId").asLong());
        assertEquals("RECEIVED", detail.path("status").asText());
        assertEquals(owned.getVersion(), detail.path("version").asLong());
        assertEquals("2026-03-04T05:06:07", detail.path("createdAt").asText(),
                "created_at must keep its stored local-time meaning instead of an invented absolute instant");
        assertEquals(cityA.getId(), detail.path("cityId").asLong());
        assertEquals("Detail City A", detail.path("cityDisplayName").asText());
        assertEquals(teamA1.getId(), detail.path("teamId").asLong());
        assertEquals("Detail Team A1", detail.path("teamDisplayName").asText());
        assertEquals(owned.getListing().getId(), detail.path("listingId").asLong());
        assertEquals("ACTIVE", detail.path("listingStatus").asText());
        assertEquals("Detail City A", detail.path("listingCity").asText());
        assertEquals(Instant.parse(detail.path("availabilityStartAt").asText()), availabilityStart);
        assertEquals(Instant.parse(detail.path("availabilityEndAt").asText()), availabilityEnd);
        assertEquals("Asia/Kolkata", detail.path("availabilityZoneId").asText());
        assertEquals(Instant.parse(detail.path("preferredAt").asText()), preferredAt);
        assertTrue(detail.path("sessionId").isNull());
        String detailBody = detail.toString();
        assertFalse(detailBody.contains("Private tenant note"));
        assertFalse(detailBody.toLowerCase().contains("budget"));
        assertFalse(detailBody.toLowerCase().contains("rent"));
        assertFalse(detailBody.toLowerCase().contains("lessor"));

        assertEquals(404, send("GET", "/api/v1/operations/visit-requests/" + owned.getId(),
                teammate.token()).statusCode(),
                "a same-Team coordinator without ownership must not learn the target exists");
        assertEquals(404, send("GET", "/api/v1/operations/visit-requests/" + teammateOwned.getId(),
                owner.token()).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/visit-requests/" + crossTeamOwned.getId(),
                owner.token()).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/visit-requests/" + crossCityOwned.getId(),
                owner.token()).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/visit-requests/" + linked.getId(),
                owner.token()).statusCode(),
                "a Session-rooted Request must not be readable as a standalone Request");
        assertEquals(404, send("GET", "/api/v1/operations/visit-requests/999999999", owner.token()).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/visit-requests/not-an-id",
                owner.token()).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/visit-requests/" + owned.getId(),
                noScope.token()).statusCode(),
                "endpoint ineligibility is 403 even for an existing target");
        assertEquals(403, send("GET", "/api/v1/operations/visit-requests/999999999",
                noScope.token()).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/visit-requests/" + owned.getId(),
                tenant.token()).statusCode());
    }

    @Test
    void sessionDetailAddsCoordinationFieldsWithoutChangingVisibility() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("session-detail"), "Session Detail City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("session-detail-team"),
                "Session Detail Team", true));
        Locality locality = locality(city, "session-detail");
        Actor tenant = actor("session-detail-tenant", Role.ROLE_TENANT, null, false);
        Actor ge = actor("session-detail-ge", Role.ROLE_GROUND_BOY, null, false);
        Actor owner = actor("session-detail-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor teammate = actor("session-detail-teammate", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor noScope = actor("session-detail-no-scope", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, team, Instant.now().minusSeconds(60), null);
        teamGrant(teammate, team, Instant.now().minusSeconds(60), null);

        Instant executionChangedAt = Instant.now().minusSeconds(45);
        VisitSession session = scheduledSession("additive", tenant.user(), city, team, owner.user(), ge.user(),
                "PENDING", VisitSessionStatus.SCHEDULED, executionChangedAt, 1);
        PropertyVisitRequest child = linkedRequest("session-detail-child", tenant.user(), city, team, locality,
                session, owner.user(), VisitRequestStatus.SCHEDULED);
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

        JsonNode detail = read("GET", "/api/v1/operations/visit-sessions/" + session.getId(), owner.token());
        assertEquals(session.getId(), detail.path("sessionId").asLong());
        assertEquals(session.getVersion(), detail.path("version").asLong());
        assertEquals("SCHEDULED", detail.path("status").asText());
        assertEquals(team.getId(), detail.path("teamId").asLong());
        assertEquals("Session Detail Team", detail.path("teamDisplayName").asText());
        assertEquals(owner.user().getId(), detail.path("coordinatorUserId").asLong());
        assertFalse(detail.path("updatedAt").isNull());
        assertEquals("PENDING", detail.path("tenantConfirmationState").asText());
        assertTrue(detail.path("startedAt").isNull());
        assertTrue(detail.path("expiresAt").isNull());
        assertEquals(Instant.parse(detail.path("executionStateChangedAt").asText()), executionChangedAt);
        assertEquals(ge.user().getId(), detail.path("representativeUserId").asLong());
        assertFalse(detail.path("assignedAt").isNull());
        assertFalse(detail.path("scheduledAt").isNull());
        assertEquals(60, detail.path("durationMinutes").asInt());
        assertEquals(1, detail.path("linkedRequestVersions").size());
        assertEquals(child.getId(), detail.path("linkedRequestVersions").get(0).path("requestId").asLong());
        assertEquals(child.getVersion(), detail.path("linkedRequestVersions").get(0).path("version").asLong());
        assertEquals(1, detail.path("items").size());
        assertEquals(child.getListing().getId(), detail.path("items").get(0).path("listingId").asLong());
        assertEquals(child.getId(), detail.path("items").get(0).path("sourceRequestId").asLong());
        assertEquals("ACTIVE", detail.path("items").get(0).path("listingStatus").asText());
        assertTrue(detail.path("items").get(0).path("coverImageUrl").isNull(),
                "an ACTIVE listing with no media must not receive a fabricated cover");

        assertEquals(404, send("GET", "/api/v1/operations/visit-sessions/" + session.getId(),
                teammate.token()).statusCode(),
                "existing Session visibility must not broaden to same-Team coordinators");
        assertEquals(403, send("GET", "/api/v1/operations/visit-sessions/" + session.getId(),
                noScope.token()).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/visit-sessions/" + session.getId(),
                tenant.token()).statusCode());
        assertEquals(403, send("GET", "/api/v1/operations/visit-sessions/999999999",
                noScope.token()).statusCode());
        assertEquals(404, send("GET", "/api/v1/operations/visit-sessions/999999999",
                owner.token()).statusCode());
    }

    @Test
    void sessionDetailNeverServesPostTransferOwnershipToTheFormerOwner() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("race-a"), "Race City A", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("race-team"), "Race Team", true));
        Locality locality = locality(city, "race-a");
        Actor tenant = actor("race-a-tenant", Role.ROLE_TENANT, null, false);
        Actor owner = actor("race-a-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor successor = actor("race-a-successor", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, team, Instant.now().minusSeconds(60), null);
        teamGrant(successor, team, Instant.now().minusSeconds(60), null);
        VisitSession session = draftSession("race-a", tenant.user(), city, team, owner.user());
        linkedRequest("race-a-child", tenant.user(), city, team, locality, session, owner.user(),
                VisitRequestStatus.COORDINATING);
        long authorizedVersion = session.getVersion();

        // The vulnerable interleaving: a real, committed ownership transfer lands between the
        // authorization read and an unrestricted by-id Session read issued by the same request.
        AtomicBoolean unrestrictedReadIntercepted = new AtomicBoolean(false);
        SessionReadBarrier.beforeRead("findById", () -> {
            if (unrestrictedReadIntercepted.compareAndSet(false, true)) {
                commitCoordinatorTransfer(session.getId(), successor.user().getId());
            }
        });

        HttpResponse<String> response = send("GET", "/api/v1/operations/visit-sessions/" + session.getId(),
                owner.token());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(owner.user().getId(), body.path("coordinatorUserId").asLong(),
                "a committed transfer must never be served to the formerly authorized coordinator");
        assertEquals(authorizedVersion, body.path("version").asLong());
        assertFalse(unrestrictedReadIntercepted.get(),
                "the scoped Session detail must not perform an unrestricted by-id Session read");
    }

    @Test
    void sessionDetailServesOneConsistentAuthorizedSnapshotUnderConcurrentTransfer() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("race-b"), "Race City B", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("race-b-team"), "Race B Team", true));
        Locality locality = locality(city, "race-b");
        Actor tenant = actor("race-b-tenant", Role.ROLE_TENANT, null, false);
        Actor owner = actor("race-b-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor successor = actor("race-b-successor", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, team, Instant.now().minusSeconds(60), null);
        teamGrant(successor, team, Instant.now().minusSeconds(60), null);
        VisitSession session = draftSession("race-b", tenant.user(), city, team, owner.user());
        linkedRequest("race-b-child", tenant.user(), city, team, locality, session, owner.user(),
                VisitRequestStatus.COORDINATING);
        long authorizedVersion = session.getVersion();

        // The transfer commits after authorization has already been granted to the owner, so the
        // response must still be one consistent snapshot of the state the caller was authorized for.
        AtomicBoolean transferCommitted = new AtomicBoolean(false);
        SessionReadBarrier.beforeRead("findOwnershipVersionSnapshotById", () -> {
            if (transferCommitted.compareAndSet(false, true)) {
                commitCoordinatorTransfer(session.getId(), successor.user().getId());
            }
        });

        HttpResponse<String> response = send("GET", "/api/v1/operations/visit-sessions/" + session.getId(),
                owner.token());
        assertTrue(transferCommitted.get(), "the concurrency barrier must have fired inside the request");
        assertEquals(200, response.statusCode(),
                "an authorized read must serve its consistent snapshot instead of drifting mid-request");
        JsonNode body = mapper.readTree(response.body());
        assertEquals(owner.user().getId(), body.path("coordinatorUserId").asLong());
        assertEquals(authorizedVersion, body.path("version").asLong(),
                "version tokens must belong to the same snapshot as the authorized Session row");
    }

    @Test
    void committedTransferRevokesFormerOwnerDetailAccessImmediately() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("race-c"), "Race City C", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("race-c-team"), "Race C Team", true));
        Locality locality = locality(city, "race-c");
        Actor tenant = actor("race-c-tenant", Role.ROLE_TENANT, null, false);
        Actor owner = actor("race-c-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor successor = actor("race-c-successor", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, team, Instant.now().minusSeconds(60), null);
        teamGrant(successor, team, Instant.now().minusSeconds(60), null);
        VisitSession session = draftSession("race-c", tenant.user(), city, team, owner.user());
        linkedRequest("race-c-child", tenant.user(), city, team, locality, session, owner.user(),
                VisitRequestStatus.COORDINATING);
        String path = "/api/v1/operations/visit-sessions/" + session.getId();
        assertEquals(200, send("GET", path, owner.token()).statusCode());

        commitCoordinatorTransfer(session.getId(), successor.user().getId());

        assertEquals(404, send("GET", path, owner.token()).statusCode(),
                "stale ownership must not keep Session detail access");
        JsonNode successorDetail = read("GET", path, successor.token());
        assertEquals(successor.user().getId(), successorDetail.path("coordinatorUserId").asLong());
    }

    /** Commits a real ownership transfer on a separate connection, mirroring Session and children. */
    private void commitCoordinatorTransfer(long sessionId, long coordinatorUserId) {
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
            connection.commit();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not commit the concurrent ownership transfer", failure);
        }
    }

    private void assertDeterministicOrdering(JsonNode items) {
        String previousType = null;
        long previousId = -1;
        int requestsSeen = 0;
        int sessionsSeen = 0;
        for (JsonNode item : items) {
            String type = item.path("targetType").asText();
            long id = item.path("targetId").asLong();
            if ("SESSION".equals(type)) {
                sessionsSeen++;
                assertTrue(requestsSeen > 0 || sessionsSeen == 1, "roots must be ordered within their type");
            } else {
                requestsSeen++;
                assertEquals(0, sessionsSeen, "Request roots must precede Session roots");
            }
            if (type.equals(previousType)) {
                assertTrue(id > previousId, "roots must order by ascending targetId within a root type");
            }
            previousType = type;
            previousId = id;
        }
        assertTrue(requestsSeen > 0 && sessionsSeen > 0, "the fixture must exercise both root types");
    }

    private void assertWorkAllowlist(JsonNode items) {
        for (JsonNode item : items) {
            assertEquals(Set.of("targetType", "targetId", "expectedVersion", "cityId", "cityDisplayName",
                    "teamId", "teamDisplayName", "status", "updatedAt", "scheduledAt",
                    "tenantConfirmationState", "linkedRequestVersions"), fieldNames(item));
            for (JsonNode child : item.path("linkedRequestVersions")) {
                assertEquals(Set.of("requestId", "version"), fieldNames(child));
            }
        }
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private String rootKey(JsonNode item) {
        return item.path("targetType").asText() + ":" + item.path("targetId").asLong();
    }

    private Set<String> rootKeys(JsonNode items) {
        Set<String> keys = new LinkedHashSet<>();
        items.forEach(item -> keys.add(rootKey(item)));
        return keys;
    }

    private boolean hasRoot(JsonNode items, String targetType, long id) {
        return countRoot(items, targetType, id) > 0;
    }

    private int countRoot(JsonNode items, String targetType, long id) {
        int count = 0;
        for (JsonNode item : items) {
            if (targetType.equals(item.path("targetType").asText()) && item.path("targetId").asLong() == id) count++;
        }
        return count;
    }

    private JsonNode findRoot(JsonNode items, String targetType, long id) {
        for (JsonNode item : items) {
            if (targetType.equals(item.path("targetType").asText()) && item.path("targetId").asLong() == id) return item;
        }
        throw new AssertionError("Missing " + targetType + " root " + id + " in " + items);
    }

    private JsonNode read(String method, String path, String token) throws Exception {
        HttpResponse<String> response = send(method, path, token);
        assertEquals(200, response.statusCode(), response.body());
        return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String token) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + path));
            if (token != null) request.header("Authorization", "Bearer " + token);
            HttpRequest httpRequest = request
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            return HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Actor actor(String label, Role role, String profileRole, boolean staffActive) {
        User user = new User();
        user.setEmail("my-work-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("+91987654" + String.format("%04d", (int) (Math.random() * 10000)));
        user.setRole(role);
        user = users.saveAndFlush(user);
        if (profileRole != null) {
            EmployeeProfile profile = employees.saveAndFlush(new EmployeeProfile(user, profileRole, null, BigDecimal.ZERO));
            if (staffActive) {
                profile.setStaffActive(true);
                employees.saveAndFlush(profile);
            }
        }
        return new Actor(user, jwt.generateToken(user.getId(), user.getEmail(), user.getRole().name()));
    }

    private StaffAccessGrant teamGrant(Actor actor, OperatingTeam team, Instant effectiveAt, Instant expiresAt) {
        return grants.saveAndFlush(new StaffAccessGrant(actor.user(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team, effectiveAt, expiresAt, null, Instant.now(), "TEST_MYWORK",
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

    private PropertyVisitRequest standaloneRequest(String suffix, User tenant, SupportedCity city,
            OperatingTeam team, Locality locality, User coordinator, VisitRequestStatus status) {
        return saveRequest(suffix, tenant, city, team, locality, null, coordinator, status);
    }

    private PropertyVisitRequest linkedRequest(String suffix, User tenant, SupportedCity city,
            OperatingTeam team, Locality locality, VisitSession session, User coordinator,
            VisitRequestStatus status) {
        return saveRequest(suffix, tenant, city, team, locality, session, coordinator, status);
    }

    private PropertyVisitRequest saveRequest(String suffix, User tenant, SupportedCity city,
            OperatingTeam team, Locality locality, VisitSession session, User coordinator,
            VisitRequestStatus status) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(createListing(locality, "Private title " + suffix + " " + UUID.randomUUID()));
        request.setStatus(status);
        request.setSession(session);
        request.setSupportedCity(city);
        request.setOperatingTeam(team);
        request.setOperationalScopeReady(true);
        request.setCoordinator(coordinator);
        request.setTenantNote("Private tenant note " + suffix);
        return requests.saveAndFlush(request);
    }

    private VisitSession draftSession(String suffix, User tenant, SupportedCity city, OperatingTeam team,
            User coordinator) {
        VisitSession session = baseSession(tenant, city, team, coordinator);
        session.setStatus(VisitSessionStatus.DRAFT);
        return sessions.saveAndFlush(session);
    }

    private VisitSession scheduledSession(String suffix, User tenant, SupportedCity city, OperatingTeam team,
            User coordinator, User groundExecutive, String tenantConfirmationState, VisitSessionStatus status,
            Instant executionStateChangedAt, long slotHours) {
        VisitSession session = baseSession(tenant, city, team, coordinator);
        // Distinct, non-overlapping reservations: the schema enforces GE reservation exclusivity.
        Instant scheduledAt = Instant.now().plusSeconds(slotHours * 3600);
        session.setScheduledAt(scheduledAt);
        session.setZoneId("Asia/Kolkata");
        session.setDurationSnapshotMinutes(60);
        session.setReservedEndAt(scheduledAt.plusSeconds(3600));
        session.setRepresentative(groundExecutive);
        session.setAssignedAt(Instant.now());
        session.setStatus(status);
        session.setTenantConfirmationState(tenantConfirmationState);
        session.setExecutionStateChangedAt(executionStateChangedAt);
        return sessions.saveAndFlush(session);
    }

    private VisitSession baseSession(User tenant, SupportedCity city, OperatingTeam team, User coordinator) {
        VisitSession session = new VisitSession();
        session.setTenant(tenant);
        session.setCity(city.getDisplayName());
        session.setSupportedCity(city);
        session.setOperatingTeam(team);
        session.setCoordinator(coordinator);
        session.setOperationalScopeReady(true);
        return session;
    }

    private String code(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Deterministic concurrency barrier for the Session detail read path.
     *
     * The test context injects a faithful delegating proxy in front of the real Spring Data
     * repository so a test can run a real, committed ownership transfer exactly before a named
     * repository read executes. Nothing in production code is aware of this seam.
     */
    @TestConfiguration
    static class SessionReadBarrierConfiguration {
        @Bean
        @Primary
        VisitSessionRepository barrierVisitSessionRepository(
                @Qualifier("visitSessionRepository") VisitSessionRepository delegate) {
            return SessionReadBarrier.wrap(delegate);
        }
    }

    static final class SessionReadBarrier {
        private static volatile String guardedMethod;
        private static volatile Runnable pendingHook;

        private SessionReadBarrier() {}

        static VisitSessionRepository wrap(VisitSessionRepository delegate) {
            return (VisitSessionRepository) Proxy.newProxyInstance(SessionReadBarrier.class.getClassLoader(),
                    new Class<?>[] {VisitSessionRepository.class}, (proxy, method, arguments) -> {
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

    private record Actor(User user, String token) {}
}
