package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.security.JwtUtils;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2A-3A: the currently owning Operations Executive may run exactly six owned Visit Operations
 * actions through real HTTP against real PostgreSQL, while Pathome Admin authority and every frozen
 * lifecycle, ownership, mirror, lock-order, notification and entitlement behaviour is preserved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=owned-ops-http-test-jwt-secret-32-bytes-minimum",
        "pathome.visit.otp.hmac-secret=owned-ops-http-test-otp-secret-32-bytes-minimum",
        "cloudinary.api-key=owned-ops-test-key",
        "cloudinary.api-secret=owned-ops-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class OwnedOperationsHttpPostgresTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "owned_ops_http_test_" + UUID.randomUUID().toString().replace("-", "");
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
        registry.add("spring.datasource.url", OwnedOperationsHttpPostgresTest::schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    private static String schemaUrl() {
        return BASE_URL + (BASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
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
    @Autowired private OperationalOwnershipService ownership;
    @Autowired private StaffAdministrationService staffAdministration;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SchedulingRecommendationPolicy recommendationPolicy;
    @LocalServerPort private int httpPort;

    @Test
    void oeSchedulingRoutesRequireCurrentOwnedScopeAndHideForeignSessions() throws Exception {
        Fixture own = fixture("oe-scheduling-http");
        Fixture other = fixture("oe-scheduling-foreign");
        VisitSession owned = session("oe-scheduling", own, VisitSessionStatus.DRAFT, own.owner().user(), 0);
        VisitSession foreign = session("oe-scheduling", other, VisitSessionStatus.DRAFT, other.owner().user(), 0);
        Actor admin = admin("oe-scheduling-admin");
        String ownPath = "/api/v1/operations/visit-sessions/" + owned.getId() + "/oe-recommendations";
        String foreignPath = "/api/v1/operations/visit-sessions/" + foreign.getId() + "/oe-recommendations";
        String body = "{\"expectedSessionVersion\":" + sessionVersion(owned.getId()) + "}";

        assertEquals(401, send("POST", ownPath, null, body).statusCode());
        assertEquals(403, send("POST", ownPath, admin.token(), body).statusCode());
        HttpResponse<String> recommendation = send("POST", ownPath, own.owner().token(), body);
        assertEquals(200, recommendation.statusCode());
        assertEquals(owned.getId(), mapper.readTree(recommendation.body()).path("sessionId").asLong());
        HttpResponse<String> search = send("POST", ownPath + "?all=true&q=Ravi", own.owner().token(), body);
        assertEquals(200, search.statusCode());
        assertEquals("DISCOVERY", mapper.readTree(search.body()).path("mode").asText());
        assertEquals(0, mapper.readTree(search.body()).path("candidates").size());
        assertEquals(400, send("POST", ownPath + "?q=Ravi", own.owner().token(), body).statusCode());
        assertEquals(400, send("POST", ownPath + "?all=true&size=51", own.owner().token(), body).statusCode());
        assertEquals(400, send("POST", ownPath + "?all=true&cursor=malformed",
                own.owner().token(), body).statusCode());
        HttpResponse<String> hidden = send("POST", foreignPath, own.owner().token(), body);
        HttpResponse<String> hiddenSearch = send("POST", foreignPath + "?all=true&q=Ravi",
                own.owner().token(), body);
        HttpResponse<String> absent = send("POST",
                "/api/v1/operations/visit-sessions/" + Long.MAX_VALUE + "/oe-recommendations",
                own.owner().token(), body);
        assertEquals(404, hidden.statusCode());
        assertEquals(404, hiddenSearch.statusCode());
        assertEquals(absent.statusCode(), hidden.statusCode());
        assertEquals(mapper.readTree(absent.body()).path("message").asText(),
                mapper.readTree(hidden.body()).path("message").asText());
    }

    @Test
    void oeCanBookSearchedGeWhenFirstHundredRecommendationCandidatesAreInfeasible() throws Exception {
        Fixture fixture = fixture("oe-empty-http");
        Actor admin = admin("oe-empty-http-admin");
        VisitSession session = session("oe-empty-http", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        PropertyVisitRequest request = linkedRequest("oe-empty-http", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        Instant start = Instant.now().plus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Instant end = start.plus(Duration.ofHours(2));
        request.setAvailabilityStartAt(start);
        request.setAvailabilityEndAt(end);
        request.setAvailabilityZoneId("Asia/Kolkata");
        request.setPreferredAt(start);
        requests.saveAndFlush(request);
        VisitSessionItem item = directItem(session, request, request.getListing(), 1);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
        item.setConfirmedBy(admin.user());
        item.setAvailabilityConfirmedAt(Instant.now());
        item.setAvailabilityStartAt(start);
        item.setAvailabilityEndAt(end);
        item.setAvailabilityZoneId("Asia/Kolkata");
        item.setAvailabilitySource(PropertyAvailabilitySource.PHONE);
        visitSessionItems.saveAndFlush(item);
        Actor eligible = null;
        assertEquals(100, recommendationPolicy.getMaximumGroundExecutives());
        for (int index = 0; index <= 100; index++) {
            Actor ge = actor("oe-empty-http-ge-" + index, Role.ROLE_GROUND_BOY, "GROUND_BOY", true);
            long profileId = employees.findByUserId(ge.user().getId()).orElseThrow().getId();
            jdbc.update("INSERT INTO ground_executive_scheduling_profiles "
                    + "(employee_profile_id, scheduling_active, updated_by_user_id) VALUES (?, true, ?)",
                    profileId, admin.user().getId());
            jdbc.update("INSERT INTO ground_executive_coverage (employee_profile_id, city, created_by_user_id) "
                    + "VALUES (?, ?, ?)", profileId, fixture.city().getDisplayName(), admin.user().getId());
            if (index == 100) {
                eligible = ge;
                jdbc.update("INSERT INTO ground_executive_shifts "
                        + "(employee_profile_id, starts_at, ends_at, zone_id, created_by_user_id, updated_by_user_id) "
                        + "VALUES (?, ?, ?, 'Asia/Kolkata', ?, ?)", profileId,
                        java.sql.Timestamp.from(start.minus(Duration.ofHours(4))),
                        java.sql.Timestamp.from(end.plus(Duration.ofHours(4))), admin.user().getId(), admin.user().getId());
            }
        }
        assertNotNull(eligible);
        String path = "/api/v1/operations/visit-sessions/" + session.getId() + "/oe-recommendations";
        long version = sessionVersion(session.getId());
        String body = mapper.writeValueAsString(Map.of("expectedSessionVersion", version));
        HttpResponse<String> baseline = send("POST", path, fixture.owner().token(), body);
        assertEquals(200, baseline.statusCode(), baseline.body());
        assertEquals(0, mapper.readTree(baseline.body()).path("candidates").size());
        assertEquals("NO_FEASIBLE_TIME", mapper.readTree(baseline.body()).path("status").asText());
        assertTrue(mapper.readTree(baseline.body()).path("candidateSearchTruncated").asBoolean());
        HttpResponse<String> search = send("POST", path + "?all=true&q=" + eligible.user().getId(),
                fixture.owner().token(), body);
        assertEquals(200, search.statusCode(), search.body());
        JsonNode candidates = mapper.readTree(search.body()).path("candidates");
        assertEquals(1, candidates.size());
        JsonNode selected = candidates.get(0);
        Map<String, Object> selection = new java.util.HashMap<>();
        selection.put("expectedSessionVersion", version);
        selection.put("groundExecutiveUserId", eligible.user().getId());
        selection.put("scheduledAt", selected.path("scheduledAt").asText());
        selection.put("zoneId", selected.path("zoneId").asText());
        assertEquals(400, send("POST", path + "/selection", fixture.owner().token(),
                mapper.writeValueAsString(selection)).statusCode());
        selection.put("overrideReason", "   ");
        assertEquals(400, send("POST", path + "/selection", fixture.owner().token(),
                mapper.writeValueAsString(selection)).statusCode());
        selection.put("overrideReason", "Eligible GE found beyond the recommendation pool");
        HttpResponse<String> booked = send("POST", path + "/selection", fixture.owner().token(),
                mapper.writeValueAsString(selection));
        assertEquals(200, booked.statusCode(), booked.body());
        JsonNode result = mapper.readTree(booked.body());
        assertEquals("SCHEDULED", result.path("session").path("status").asText());
        assertEquals(eligible.user().getId().longValue(), result.path("session").path("representativeUserId").asLong());
        assertTrue(result.path("override").asBoolean());
        assertTrue(result.path("topRecommendedGroundExecutiveUserId").isNull());
        assertTrue(result.path("topRecommendedAt").isNull());
        Map<String, Object> audit = jdbc.queryForMap("SELECT * FROM visit_scheduling_decisions WHERE id = ?",
                result.path("decisionId").asLong());
        assertNull(audit.get("recommended_ground_executive_user_id"));
        assertNull(audit.get("recommended_scheduled_at"));
        assertEquals(eligible.user().getId(), audit.get("selected_ground_executive_user_id"));
        assertEquals(fixture.owner().user().getId(), audit.get("approved_by_user_id"));
        assertEquals(selection.get("overrideReason"), audit.get("override_reason"));
        assertEquals(Boolean.TRUE, audit.get("was_override"));
        assertNull(sessions.findById(session.getId()).orElseThrow().getStartedAt());
    }

    // ------------------------------------------------------------------ admission (401 / 403)

    @Test
    void unauthenticatedAndUnauthorizedCallersNeverReachOwnedTargets() throws Exception {
        Fixture fixture = fixture("admit");
        Actor customer = actor("admit-customer", Role.ROLE_TENANT, null, false);
        Actor noGrant = actor("admit-nogrant", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor superviseOnly = actor("admit-supervise", Role.ROLE_TENANT, "OPERATIONS", true);
        superviseGrant(superviseOnly, fixture.team());
        Actor revoked = actor("admit-revoked", Role.ROLE_TENANT, "OPERATIONS", true);
        revokeNow(coordinatorGrant(revoked, fixture.team()));
        Actor expired = actor("admit-expired", Role.ROLE_TENANT, "OPERATIONS", true);
        coordinatorGrant(expired, fixture.team(), Instant.now().minusSeconds(600), Instant.now().minusSeconds(60));
        Actor future = actor("admit-future", Role.ROLE_TENANT, "OPERATIONS", true);
        coordinatorGrant(future, fixture.team(), Instant.now().plusSeconds(3600), null);
        Actor inactiveProfile = actor("admit-inactive-profile", Role.ROLE_TENANT, "OPERATIONS", false);
        coordinatorGrant(inactiveProfile, fixture.team());
        Actor inactiveTeamOnly = actor("admit-inactive-team", Role.ROLE_TENANT, "OPERATIONS", true);
        OperatingTeam sleepingTeam = teams.saveAndFlush(
                new OperatingTeam(fixture.city(), code("admit-sleeping"), "Sleeping Team", true));
        coordinatorGrant(inactiveTeamOnly, sleepingTeam);
        sleepingTeam.setActive(false);
        teams.saveAndFlush(sleepingTeam);
        Actor inactiveCityOnly = actor("admit-inactive-city", Role.ROLE_TENANT, "OPERATIONS", true);
        SupportedCity sleepingCity = cities.saveAndFlush(
                new SupportedCity(code("admit-sleeping-city"), "Sleeping City", true));
        OperatingTeam teamInSleepingCity = teams.saveAndFlush(
                new OperatingTeam(sleepingCity, code("admit-sleeping-city-team"), "Sleeping City Team", true));
        coordinatorGrant(inactiveCityOnly, teamInSleepingCity);
        sleepingCity.setActive(false);
        cities.saveAndFlush(sleepingCity);

        PropertyVisitRequest request = standaloneRequest("admit", fixture, VisitRequestStatus.RECEIVED,
                fixture.owner().user());
        VisitSession session = session("admit", fixture, VisitSessionStatus.SCHEDULED, fixture.owner().user(), 7);
        Listing probe = listing(fixture.locality(), "Admission probe", ListingStatus.ACTIVE);
        long requestVersion = requestVersion(request.getId());
        long sessionVersion = sessionVersion(session.getId());

        // 401 for every owned route without a verified identity.
        assertEquals(401, coordinate(null, request.getId(), requestVersion, null, null).statusCode());
        assertEquals(401, unavailable(null, request.getId(), requestVersion).statusCode());
        assertEquals(401, addItem(null, session.getId(), sessionVersion, 999_999L, probe.getId(), "OE_ADDED").statusCode());
        assertEquals(401, removeItem(null, session.getId(), sessionVersion, probe.getId()).statusCode());
        assertEquals(401, reorder(null, session.getId(), sessionVersion, List.of(probe.getId())).statusCode());
        assertEquals(401, availability(null, session.getId(), sessionVersion, probe.getId(), "CONFIRMED").statusCode());

        // 403 for every caller without a usable OPS_COORDINATE@TEAM grant, before any target read.
        for (Actor candidate : List.of(customer, noGrant, superviseOnly, revoked, expired, future,
                inactiveProfile, inactiveTeamOnly, inactiveCityOnly)) {
            String label = candidate.user().getFullName();
            assertEquals(403, coordinate(candidate, request.getId(), requestVersion, null, null).statusCode(), label);
            assertEquals(403, unavailable(candidate, request.getId(), requestVersion).statusCode(), label);
            assertEquals(403, addItem(candidate, session.getId(), sessionVersion, 999_999L, probe.getId(), "OE_ADDED").statusCode(), label);
            assertEquals(403, removeItem(candidate, session.getId(), sessionVersion, probe.getId()).statusCode(), label);
            assertEquals(403, reorder(candidate, session.getId(), sessionVersion, List.of(probe.getId())).statusCode(), label);
            assertEquals(403, availability(candidate, session.getId(), sessionVersion, probe.getId(), "CONFIRMED").statusCode(), label);
        }
        assertRequestStillReceived(request.getId());
        assertSessionVersion(session.getId(), sessionVersion);
        assertEquals(0L, jdbc.queryForObject("select count(*) from visit_session_items where session_id = ?",
                Long.class, session.getId()));
    }

    // ------------------------------------------------------------------ frozen Admin authority

    @Test
    void adminKeepsItsPreviousAuthorityOnEveryOwnedRoute() throws Exception {
        Fixture fixture = fixture("admin");
        Actor admin = actor("admin-root", Role.ROLE_ADMIN, null, false);
        Actor stranger = actor("admin-stranger", Role.ROLE_TENANT, "OPERATIONS", true);

        // Admin coordinates a Request it does not own and holds no staff capability for.
        PropertyVisitRequest foreign = standaloneRequest("admin-foreign", fixture,
                VisitRequestStatus.RECEIVED, fixture.owner().user());
        HttpResponse<String> coordinated = coordinate(admin, foreign.getId(), requestVersion(foreign.getId()), null, null);
        assertEquals(200, coordinated.statusCode(), coordinated.body());
        assertEquals(VisitRequestStatus.COORDINATING, requests.findById(foreign.getId()).orElseThrow().getStatusValue());

        // Admin re-issues the same command on the now-linked Request and keeps the idempotent 200.
        HttpResponse<String> repeated = coordinate(admin, foreign.getId(), 0L, null, null);
        assertEquals(200, repeated.statusCode(), repeated.body());
        assertEquals(mapper.readTree(coordinated.body()).get("sessionId").asLong(),
                mapper.readTree(repeated.body()).get("sessionId").asLong());

        // Admin marks a Request it does not own unavailable.
        PropertyVisitRequest target = standaloneRequest("admin-unavailable", fixture,
                VisitRequestStatus.RECEIVED, stranger.user());
        assertEquals(200, unavailable(admin, target.getId(), requestVersion(target.getId())).statusCode());
        assertEquals(VisitRequestStatus.UNAVAILABLE, requests.findById(target.getId()).orElseThrow().getStatusValue());

        // Admin still edits the itinerary of a SCHEDULED Session it does not own: an executive may not.
        VisitSession scheduled = session("admin-scheduled", fixture, VisitSessionStatus.SCHEDULED, stranger.user(), 11);
        PropertyVisitRequest origin = linkedRequest("admin-origin", fixture, scheduled,
                VisitRequestStatus.SCHEDULED, stranger.user());
        VisitSessionItem retained = confirmedDerivedItem(scheduled, origin,
                listing(fixture.locality(), "Admin retained", ListingStatus.ACTIVE), 1, admin.user());
        VisitSessionItem removable = derivedItem(scheduled, origin,
                listing(fixture.locality(), "Admin removable", ListingStatus.ACTIVE), 2);
        long version = sessionVersion(scheduled.getId());
        HttpResponse<String> removed = removeItem(admin, scheduled.getId(), version, removable.getId());
        assertEquals(200, removed.statusCode(), removed.body());
        assertNotNull(visitSessionItems.findById(removable.getId()).orElseThrow().getRemovedAt());
        assertNull(visitSessionItems.findById(retained.getId()).orElseThrow().getRemovedAt());

        // Admin still assembles, reorders and confirms the itinerary of a DRAFT Session it does not own.
        VisitSession foreignDraft = session("admin-draft", fixture, VisitSessionStatus.DRAFT, stranger.user(), 0);
        PropertyVisitRequest draftOrigin = linkedRequest("admin-draft-linked", fixture, foreignDraft,
                VisitRequestStatus.COORDINATING, stranger.user());
        VisitSessionItem existing = derivedItem(foreignDraft, draftOrigin,
                listing(fixture.locality(), "Admin draft one", ListingStatus.ACTIVE), 1);
        HttpResponse<String> added = addItem(admin, foreignDraft.getId(), sessionVersion(foreignDraft.getId()),
                draftOrigin.getId(),
                listing(fixture.locality(), "Admin draft two", ListingStatus.ACTIVE).getId(), "OE_ADDED");
        assertEquals(200, added.statusCode(), added.body());
        long addedItemId = 0L;
        for (JsonNode item : mapper.readTree(added.body()).get("items")) {
            if (item.get("itemId").asLong() != existing.getId()) addedItemId = item.get("itemId").asLong();
        }
        assertTrue(addedItemId > 0);
        assertEquals(200, reorder(admin, foreignDraft.getId(), sessionVersion(foreignDraft.getId()),
                List.of(addedItemId, existing.getId())).statusCode());
        assertEquals(200, availability(admin, foreignDraft.getId(), sessionVersion(foreignDraft.getId()),
                addedItemId, "CONFIRMED").statusCode());
        assertEquals(VisitSessionItemConfirmationStatus.CONFIRMED,
                visitSessionItems.findById(addedItemId).orElseThrow().getConfirmationStatus());
    }

    // ------------------------------------------------------------------ the six owned actions

    @Test
    void owningCoordinatorCoordinatesAnUnlinkedRequestWithCanonicalMirror() throws Exception {
        Fixture fixture = fixture("coordinate");
        Listing requested = listing(fixture.locality(), "Coordinate listing", ListingStatus.ACTIVE);
        PropertyVisitRequest request = standaloneRequest("coordinate", fixture, requested,
                VisitRequestStatus.RECEIVED, fixture.owner().user());
        long expectedRequestVersion = requestVersion(request.getId());

        HttpResponse<String> response = coordinate(fixture.owner(), request.getId(), expectedRequestVersion, null, null);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode view = mapper.readTree(response.body());
        long sessionId = view.get("sessionId").asLong();
        VisitSession session = sessions.findById(sessionId).orElseThrow();

        assertEquals(fixture.tenant().getId(), session.getTenant().getId());
        assertEquals(fixture.city().getId(), session.getSupportedCity().getId());
        assertEquals(fixture.team().getId(), session.getOperatingTeam().getId());
        assertEquals(fixture.owner().user().getId(), session.getCoordinator().getId());
        assertTrue(session.isOperationalScopeReady());
        assertEquals(VisitSessionStatus.DRAFT, session.getStatus());
        assertEquals(VisitRequestStatus.COORDINATING, requests.findById(request.getId()).orElseThrow().getStatusValue());
        assertEquals(fixture.owner().user().getId(), view.get("coordinatorUserId").asLong());
        assertEquals(1, view.get("items").size());
        assertEquals("TENANT_REQUESTED", view.get("items").get(0).get("origin").asText());
        assertEquals(request.getId(), view.get("items").get(0).get("sourceRequestId").asLong());
        assertEquals(requested.getId(), view.get("items").get(0).get("listingId").asLong());
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from operational_audit_events where action_code = 'REQUEST_SESSION_LINKED' "
                        + "and target_type = 'VISIT_SESSION' and target_id = ? and actor_user_id = ?",
                Long.class, sessionId, fixture.owner().user().getId()));
    }

    @Test
    void owningCoordinatorAttachesAnOwnedRequestToAnOwnedDraftSession() throws Exception {
        Fixture fixture = fixture("attach");
        VisitSession session = session("attach", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        Listing requested = listing(fixture.locality(), "Attach listing", ListingStatus.ACTIVE);
        PropertyVisitRequest request = standaloneRequest("attach", fixture, requested,
                VisitRequestStatus.RECEIVED, fixture.owner().user());

        HttpResponse<String> response = coordinate(fixture.owner(), request.getId(), requestVersion(request.getId()),
                session.getId(), sessionVersion(session.getId()));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode view = mapper.readTree(response.body());
        assertEquals(session.getId(), view.get("sessionId").asLong());
        assertEquals(1, view.get("items").size());
        assertEquals(request.getId(), view.get("items").get(0).get("sourceRequestId").asLong());
        assertEquals(session.getId(), requests.findById(request.getId()).orElseThrow().getSession().getId());
        assertEquals(VisitRequestStatus.COORDINATING, requests.findById(request.getId()).orElseThrow().getStatusValue());
    }

    @Test
    void owningCoordinatorMarksAnOwnedRequestUnavailable() throws Exception {
        Fixture fixture = fixture("unavailable");
        PropertyVisitRequest request = standaloneRequest("unavailable", fixture,
                VisitRequestStatus.RECEIVED, fixture.owner().user());

        HttpResponse<String> response = unavailable(fixture.owner(), request.getId(), requestVersion(request.getId()));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("UNAVAILABLE", mapper.readTree(response.body()).get("status").asText());
        assertEquals(VisitRequestStatus.UNAVAILABLE, requests.findById(request.getId()).orElseThrow().getStatusValue());
        assertNull(requests.findById(request.getId()).orElseThrow().getSession());
    }

    @Test
    void owningCoordinatorAddsAndRemovesOwnedDraftItineraryItems() throws Exception {
        Fixture fixture = fixture("items");
        VisitSession session = session("items", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        PropertyVisitRequest origin = linkedRequest("items", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        Listing nearby = listing(fixture.locality(), "Nearby listing", ListingStatus.ACTIVE);

        HttpResponse<String> added = addItem(fixture.owner(), session.getId(), sessionVersion(session.getId()),
                origin.getId(), nearby.getId(), "OE_ADDED");
        assertEquals(200, added.statusCode(), added.body());
        JsonNode item = mapper.readTree(added.body()).get("items").get(0);
        assertEquals("OE_ADDED", item.get("origin").asText());
        assertEquals(origin.getId(), item.get("derivedFromRequestId").asLong());
        assertTrue(item.get("sourceRequestId").isNull());

        HttpResponse<String> removed = removeItem(fixture.owner(), session.getId(), sessionVersion(session.getId()),
                item.get("itemId").asLong());
        assertEquals(200, removed.statusCode(), removed.body());
        JsonNode removedItem = mapper.readTree(removed.body()).get("items").get(0);
        assertFalse(removedItem.get("removedAt").isNull());
        assertEquals(fixture.owner().user().getId(), removedItem.get("removedByUserId").asLong());
    }

    @Test
    void owningCoordinatorReordersOwnedDraftItineraryItems() throws Exception {
        Fixture fixture = fixture("reorder");
        VisitSession session = session("reorder", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        PropertyVisitRequest origin = linkedRequest("reorder", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        VisitSessionItem first = derivedItem(session, origin,
                listing(fixture.locality(), "Reorder one", ListingStatus.ACTIVE), 1);
        VisitSessionItem second = derivedItem(session, origin,
                listing(fixture.locality(), "Reorder two", ListingStatus.ACTIVE), 2);

        HttpResponse<String> response = reorder(fixture.owner(), session.getId(), sessionVersion(session.getId()),
                List.of(second.getId(), first.getId()));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode items = mapper.readTree(response.body()).get("items");
        assertEquals(second.getId(), items.get(0).get("itemId").asLong());
        assertEquals(1, items.get(0).get("position").asInt());
        assertEquals(first.getId(), items.get(1).get("itemId").asLong());
        assertEquals(2, items.get(1).get("position").asInt());
    }

    @Test
    void owningCoordinatorRecordsPropertyAvailabilityAndLinkedRequestConsequence() throws Exception {
        Fixture fixture = fixture("availability");
        VisitSession session = session("availability", fixture, VisitSessionStatus.DRAFT,
                fixture.owner().user(), 0);
        PropertyVisitRequest origin = linkedRequest("availability", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        VisitSessionItem windowed = derivedItem(session, origin,
                listing(fixture.locality(), "Availability listed", ListingStatus.ACTIVE), 1);
        // A directly requested property carries the linked-Request consequence; a derived one never does.
        VisitSessionItem declined = directItem(session, origin, origin.getListing(), 2);

        HttpResponse<String> confirmed = availabilityWindow(fixture.owner(), session.getId(), sessionVersion(session.getId()),
                windowed.getId(), "CONFIRMED", "PHONE");
        assertEquals(200, confirmed.statusCode(), confirmed.body());
        JsonNode confirmedItem = itemById(mapper.readTree(confirmed.body()), windowed.getId());
        assertEquals("CONFIRMED", confirmedItem.get("confirmationStatus").asText());
        assertEquals("PHONE", confirmedItem.get("propertyAvailabilitySource").asText());
        assertEquals(fixture.owner().user().getId(), confirmedItem.get("availabilityConfirmedByUserId").asLong());

        // Recording the executive's report of the lessor statement is never authenticated lessor
        // confirmation: the source stays the executive's own channel and no lessor reference is set.
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from visit_session_items where id = ? and lessor_confirmation_reference is not null",
                Long.class, windowed.getId()));

        // An unavailable item still propagates the existing linked-Request consequence.
        HttpResponse<String> unavailable = availability(fixture.owner(), session.getId(), sessionVersion(session.getId()),
                declined.getId(), "UNAVAILABLE");
        assertEquals(200, unavailable.statusCode(), unavailable.body());
        assertEquals(VisitSessionItemConfirmationStatus.UNAVAILABLE,
                visitSessionItems.findById(declined.getId()).orElseThrow().getConfirmationStatus());
        assertEquals(VisitRequestStatus.UNAVAILABLE, requests.findById(origin.getId()).orElseThrow().getStatusValue());
    }

    // ------------------------------------------------------------------ ownership and scope isolation

    @Test
    void otherCoordinatorCannotReachOrMutateTheRoot() throws Exception {
        Fixture fixture = fixture("other");
        Actor other = coordinator("other-coordinator", fixture.team());
        PropertyVisitRequest request = standaloneRequest("other", fixture,
                VisitRequestStatus.RECEIVED, fixture.owner().user());
        VisitSession session = session("other", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        PropertyVisitRequest origin = linkedRequest("other-linked", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        VisitSessionItem protectedItem = derivedItem(session, origin,
                listing(fixture.locality(), "Other listed", ListingStatus.ACTIVE), 1);
        long requestVersion = requestVersion(request.getId());
        long sessionVersion = sessionVersion(session.getId());

        assertNotFound("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/coordinate", other,
                "{\"expectedRequestVersion\":" + requestVersion + "}");
        assertNotFound("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/unavailable", other,
                "{\"expectedRequestVersion\":" + requestVersion + "}");
        assertNotFound("POST", "/api/v1/operations/visit-sessions/" + session.getId() + "/items", other,
                "{\"expectedSessionVersion\":" + sessionVersion + ",\"listingId\":"
                        + listing(fixture.locality(), "Other probe", ListingStatus.ACTIVE).getId()
                        + ",\"originatingRequestId\":" + origin.getId() + ",\"origin\":\"OE_ADDED\"}");
        assertNotFound("DELETE", "/api/v1/operations/visit-sessions/" + session.getId() + "/items/"
                + protectedItem.getId(), other, "{\"expectedSessionVersion\":" + sessionVersion + "}");
        assertNotFound("PUT", "/api/v1/operations/visit-sessions/" + session.getId() + "/items/order", other,
                "{\"expectedSessionVersion\":" + sessionVersion + ",\"itemIds\":[" + protectedItem.getId() + "]}");
        assertNotFound("PUT", "/api/v1/operations/visit-sessions/" + session.getId() + "/items/"
                + protectedItem.getId() + "/availability", other,
                "{\"expectedSessionVersion\":" + sessionVersion + ",\"status\":\"CONFIRMED\"}");
        assertRequestStillReceived(request.getId());
        assertSessionVersion(session.getId(), sessionVersion);
        assertNull(visitSessionItems.findById(protectedItem.getId()).orElseThrow().getRemovedAt());
    }

    @Test
    void wrongTeamOrCityCoordinatorCannotReachTheRoot() throws Exception {
        Fixture fixture = fixture("scoped");
        SupportedCity otherCity = cities.saveAndFlush(new SupportedCity(code("scoped-city"), "Other Scoped City", true));
        OperatingTeam siblingTeam = teams.saveAndFlush(
                new OperatingTeam(fixture.city(), code("scoped-team"), "Sibling Team", true));
        OperatingTeam otherCityTeam = teams.saveAndFlush(
                new OperatingTeam(otherCity, code("scoped-city-team"), "Other City Team", true));
        Actor siblingTeamExecutive = coordinator("scoped-sibling", siblingTeam);
        Actor otherCityExecutive = coordinator("scoped-city-exec", otherCityTeam);

        PropertyVisitRequest request = standaloneRequest("scoped", fixture,
                VisitRequestStatus.RECEIVED, fixture.owner().user());
        VisitSession session = session("scoped", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        long sessionVersion = sessionVersion(session.getId());

        assertNotFound("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/coordinate",
                siblingTeamExecutive, "{\"expectedRequestVersion\":" + requestVersion(request.getId()) + "}");
        assertNotFound("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/unavailable",
                otherCityExecutive, "{\"expectedRequestVersion\":" + requestVersion(request.getId()) + "}");
        assertNotFound("PUT", "/api/v1/operations/visit-sessions/" + session.getId() + "/items/order",
                siblingTeamExecutive, "{\"expectedSessionVersion\":" + sessionVersion + ",\"itemIds\":[]}");
        assertRequestStillReceived(request.getId());
        assertSessionVersion(session.getId(), sessionVersion);
    }

    @Test
    void linkedRequestIsHiddenFromStandaloneExecutiveAuthority() throws Exception {
        Fixture fixture = fixture("linked");
        VisitSession session = session("linked", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        PropertyVisitRequest request = linkedRequest("linked", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        long version = requestVersion(request.getId());

        assertNotFound("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/coordinate",
                fixture.owner(), "{\"expectedRequestVersion\":" + version + "}");
        assertNotFound("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/unavailable",
                fixture.owner(), "{\"expectedRequestVersion\":" + version + "}");
        assertEquals(VisitRequestStatus.COORDINATING, requests.findById(request.getId()).orElseThrow().getStatusValue());
        assertEquals(version, requestVersion(request.getId()));
    }

    @Test
    void attachRejectsTenantMismatchAfterOwnershipIsProven() throws Exception {
        Fixture fixture = fixture("tenant");
        User otherTenant = tenant("tenant-other");
        VisitSession session = sessionOf(otherTenant, fixture, VisitSessionStatus.DRAFT, fixture.owner().user());
        PropertyVisitRequest request = standaloneRequest("tenant", fixture,
                VisitRequestStatus.RECEIVED, fixture.owner().user());

        HttpResponse<String> response = coordinate(fixture.owner(), request.getId(), requestVersion(request.getId()),
                session.getId(), sessionVersion(session.getId()));
        assertEquals(403, response.statusCode(), response.body());
        assertNull(requests.findById(request.getId()).orElseThrow().getSession());
    }

    // ------------------------------------------------------------------ version and lifecycle conflicts

    @Test
    void staleRequestAndSessionVersionsConflictOnlyAfterAuthority() throws Exception {
        Fixture fixture = fixture("stale");
        Actor other = coordinator("stale-other", fixture.team());
        PropertyVisitRequest request = standaloneRequest("stale", fixture,
                VisitRequestStatus.RECEIVED, fixture.owner().user());
        VisitSession session = session("stale", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        PropertyVisitRequest origin = linkedRequest("stale-linked", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        VisitSessionItem item = derivedItem(session, origin,
                listing(fixture.locality(), "Stale listed", ListingStatus.ACTIVE), 1);
        long staleRequestVersion = requestVersion(request.getId()) + 7;
        long staleSessionVersion = sessionVersion(session.getId()) + 7;

        assertEquals(409, coordinate(fixture.owner(), request.getId(), staleRequestVersion, null, null).statusCode());
        assertEquals(409, unavailable(fixture.owner(), request.getId(), staleRequestVersion).statusCode());
        assertEquals(409, removeItem(fixture.owner(), session.getId(), staleSessionVersion, item.getId()).statusCode());
        assertEquals(409, reorder(fixture.owner(), session.getId(), staleSessionVersion, List.of(item.getId())).statusCode());
        assertEquals(409, availability(fixture.owner(), session.getId(), staleSessionVersion, item.getId(), "CONFIRMED").statusCode());
        // The same stale command from a non-owner stays hidden, so versions never leak through this route.
        assertNotFound("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/coordinate",
                other, "{\"expectedRequestVersion\":" + staleRequestVersion + "}");
        assertRequestStillReceived(request.getId());
        assertSessionVersion(session.getId(), staleSessionVersion - 7);
        assertEquals(VisitSessionItemConfirmationStatus.PENDING,
                visitSessionItems.findById(item.getId()).orElseThrow().getConfirmationStatus());
    }

    @Test
    void ownedScheduledSessionItineraryIsReadOnlyForExecutives() throws Exception {
        Fixture fixture = fixture("scheduled");
        VisitSession session = session("scheduled", fixture, VisitSessionStatus.SCHEDULED, fixture.owner().user(), 13);
        PropertyVisitRequest origin = linkedRequest("scheduled-linked", fixture, session,
                VisitRequestStatus.SCHEDULED, fixture.owner().user());
        VisitSessionItem first = confirmedDerivedItem(session, origin,
                listing(fixture.locality(), "Scheduled one", ListingStatus.ACTIVE), 1, fixture.owner().user());
        VisitSessionItem second = derivedItem(session, origin,
                listing(fixture.locality(), "Scheduled two", ListingStatus.ACTIVE), 2);
        long version = sessionVersion(session.getId());

        assertEquals(409, addItem(fixture.owner(), session.getId(), version, origin.getId(),
                listing(fixture.locality(), "Scheduled extra", ListingStatus.ACTIVE).getId(), "OE_ADDED").statusCode());
        assertEquals(409, removeItem(fixture.owner(), session.getId(), version, second.getId()).statusCode());
        assertEquals(409, reorder(fixture.owner(), session.getId(), version, List.of(second.getId(), first.getId())).statusCode());
        assertEquals(409, availability(fixture.owner(), session.getId(), version, second.getId(), "CONFIRMED").statusCode());
        assertNull(visitSessionItems.findById(second.getId()).orElseThrow().getRemovedAt());
        assertEquals(2, visitSessionItems.findById(second.getId()).orElseThrow().getPosition());
        assertEquals(VisitSessionStatus.SCHEDULED, sessions.findById(session.getId()).orElseThrow().getStatus());
        assertSessionVersion(session.getId(), version);
    }

    @Test
    void invalidStatusAndDuplicateOrIneligiblePropertyFailClosed() throws Exception {
        Fixture fixture = fixture("invalid");
        PropertyVisitRequest withdrawn = standaloneRequest("invalid-withdrawn", fixture,
                VisitRequestStatus.UNAVAILABLE, fixture.owner().user());
        assertEquals(409, coordinate(fixture.owner(), withdrawn.getId(), requestVersion(withdrawn.getId()), null, null).statusCode());

        VisitSession session = session("invalid", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        PropertyVisitRequest origin = linkedRequest("invalid-linked", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        Listing present = listing(fixture.locality(), "Invalid present", ListingStatus.ACTIVE);
        derivedItem(session, origin, present, 1);

        assertEquals(409, addItem(fixture.owner(), session.getId(), sessionVersion(session.getId()), origin.getId(),
                present.getId(), "OE_ADDED").statusCode());
        Listing pending = listing(fixture.locality(), "Invalid pending", ListingStatus.PENDING);
        assertEquals(404, addItem(fixture.owner(), session.getId(), sessionVersion(session.getId()), origin.getId(),
                pending.getId(), "OE_ADDED").statusCode());
        Locality foreignLocality = locality(
                cities.saveAndFlush(new SupportedCity(code("invalid-city"), "Invalid Foreign City", true)), "invalid");
        Listing foreign = listing(foreignLocality, "Invalid foreign", ListingStatus.ACTIVE);
        assertEquals(409, addItem(fixture.owner(), session.getId(), sessionVersion(session.getId()), origin.getId(),
                foreign.getId(), "OE_ADDED").statusCode());
    }

    @Test
    void repeatCommandsAreIdempotentOnlyForTheCurrentOwner() throws Exception {
        Fixture fixture = fixture("repeat");
        Actor successor = coordinator("repeat-successor", fixture.team());
        Actor supervisor = supervisor("repeat-supervisor", fixture.team());

        PropertyVisitRequest request = standaloneRequest("repeat", fixture,
                VisitRequestStatus.RECEIVED, fixture.owner().user());
        assertEquals(200, unavailable(fixture.owner(), request.getId(), requestVersion(request.getId())).statusCode());
        // The already-completed repeat is a no-op only while current ownership is still proven.
        assertEquals(200, unavailable(fixture.owner(), request.getId(), requestVersion(request.getId())).statusCode());
        jdbc.update("update property_visit_requests set coordinator_user_id = ? where id = ?",
                successor.user().getId(), request.getId());
        assertNotFound("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/unavailable",
                fixture.owner(), "{\"expectedRequestVersion\":" + requestVersion(request.getId()) + "}");

        VisitSession session = session("repeat-session", fixture, VisitSessionStatus.DRAFT,
                fixture.owner().user(), 0);
        PropertyVisitRequest origin = linkedRequest("repeat-session-linked", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        VisitSessionItem removable = derivedItem(session, origin,
                listing(fixture.locality(), "Repeat removable", ListingStatus.ACTIVE), 1);
        assertEquals(200, removeItem(fixture.owner(), session.getId(), sessionVersion(session.getId()), removable.getId()).statusCode());
        assertEquals(200, removeItem(fixture.owner(), session.getId(), sessionVersion(session.getId()), removable.getId()).statusCode());
        ownership.assignCoordinator(supervisor.user().getId(), session.getId(), sessionVersion(session.getId()),
                Map.of(origin.getId(), requestVersion(origin.getId())), successor.user().getId(), "TEST_TRANSFER");
        assertNotFound("DELETE", "/api/v1/operations/visit-sessions/" + session.getId() + "/items/"
                + removable.getId(), fixture.owner(),
                "{\"expectedSessionVersion\":" + sessionVersion(session.getId()) + "}");
    }

    // ------------------------------------------------------------------ truthful, side-effect free

    @Test
    void unansweredLessorNeverBecomesUnavailableWithoutAnExplicitCommand() throws Exception {
        Fixture fixture = fixture("truthful");
        PropertyVisitRequest request = standaloneRequest("truthful", fixture,
                VisitRequestStatus.RECEIVED, fixture.owner().user());
        long version = requestVersion(request.getId());

        // Reading a lessor contact is only a precursor to a phone call; it must never record an outcome.
        HttpResponse<String> contact = send("GET",
                "/api/v1/operations/visit-requests/" + request.getId() + "/lessor-contact",
                fixture.owner().token(), null);
        assertTrue(contact.statusCode() == 200 || contact.statusCode() == 404, contact.body());
        assertEquals(VisitRequestStatus.RECEIVED, requests.findById(request.getId()).orElseThrow().getStatusValue());
        assertEquals(version, requestVersion(request.getId()));

        // A stale unavailable command changes nothing: no fabricated contact outcome, no new status.
        assertEquals(409, unavailable(fixture.owner(), request.getId(), version + 5).statusCode());
        assertEquals(VisitRequestStatus.RECEIVED, requests.findById(request.getId()).orElseThrow().getStatusValue());

        // Only the explicit, version-guarded command records unavailability.
        assertEquals(200, unavailable(fixture.owner(), request.getId(), version).statusCode());
        assertEquals(VisitRequestStatus.UNAVAILABLE, requests.findById(request.getId()).orElseThrow().getStatusValue());
    }

    @Test
    void ownedActionsDoNotTouchBookingNotificationsOtpOrEntitlements() throws Exception {
        Fixture fixture = fixture("frozen");
        long outboxBefore = count("visit_notification_outbox");
        long ledgerBefore = count("visit_entitlement_ledger");
        long decisionsBefore = count("visit_scheduling_decisions");

        VisitSession draft = session("frozen", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        PropertyVisitRequest origin = linkedRequest("frozen-linked", fixture, draft,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        Listing nearby = listing(fixture.locality(), "Frozen nearby", ListingStatus.ACTIVE);
        HttpResponse<String> added = addItem(fixture.owner(), draft.getId(), sessionVersion(draft.getId()),
                origin.getId(), nearby.getId(), "LESSOR_SUGGESTED");
        assertEquals(200, added.statusCode(), added.body());
        long itemId = mapper.readTree(added.body()).get("items").get(0).get("itemId").asLong();
        assertEquals(200, availability(fixture.owner(), draft.getId(), sessionVersion(draft.getId()), itemId, "CONFIRMED").statusCode());
        assertEquals(200, removeItem(fixture.owner(), draft.getId(), sessionVersion(draft.getId()), itemId).statusCode());

        PropertyVisitRequest standalone = standaloneRequest("frozen-request", fixture,
                VisitRequestStatus.RECEIVED, fixture.owner().user());
        assertEquals(200, unavailable(fixture.owner(), standalone.getId(), requestVersion(standalone.getId())).statusCode());

        // No scheduling, booking, OTP, entitlement or notification side effect is produced.
        VisitSession reloaded = sessions.findById(draft.getId()).orElseThrow();
        assertEquals(VisitSessionStatus.DRAFT, reloaded.getStatus());
        assertNull(reloaded.getScheduledAt());
        assertNull(reloaded.getReservedEndAt());
        assertNull(reloaded.getRepresentative());
        assertNull(reloaded.getEntitlementConsumedAt());
        assertEquals(decisionsBefore, count("visit_scheduling_decisions"));
        assertEquals(ledgerBefore, count("visit_entitlement_ledger"));
        assertEquals(outboxBefore, count("visit_notification_outbox"));
    }

    // ------------------------------------------- originating Request privacy (owned generation)

    /**
     * The owned Add Nearby Property action names an originating Request. A Request genuinely linked to
     * the owned Session must still assemble the item, while a Request that does not exist and a Request
     * that exists outside that exact Session must be one indistinguishable privacy-safe denial: same
     * status, same payload, no target information, and no lock, audit, notification or Session
     * mutation on the denied paths.
     */
    @Test
    void ownedAddNearbyPropertyNeverDistinguishesMissingFromForeignOriginatingRequest() throws Exception {
        Fixture fixture = fixture("origin-privacy");
        VisitSession session = session("origin-privacy", fixture, VisitSessionStatus.DRAFT,
                fixture.owner().user(), 0);
        PropertyVisitRequest linked = linkedRequest("origin-privacy-linked", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        // Comparable foreign linkage: same tenant, same City, same Team, same coordinator, and a
        // Request status the action accepts. Only the Session it belongs to differs.
        VisitSession comparableSession = session("origin-privacy-comparable", fixture, VisitSessionStatus.DRAFT,
                fixture.owner().user(), 0);
        PropertyVisitRequest comparableForeign = linkedRequest("origin-privacy-comparable-linked", fixture,
                comparableSession, VisitRequestStatus.COORDINATING, fixture.owner().user());
        // A Request of another tenant, City and Team, linked to its own Session.
        Fixture other = fixture("origin-privacy-other");
        VisitSession foreignSession = session("origin-privacy-foreign", other, VisitSessionStatus.DRAFT,
                other.owner().user(), 0);
        PropertyVisitRequest foreign = linkedRequest("origin-privacy-foreign-linked", other, foreignSession,
                VisitRequestStatus.COORDINATING, other.owner().user());
        // A standalone Request the caller owns, but which is not linked to this Session.
        PropertyVisitRequest standalone = standaloneRequest("origin-privacy-standalone", fixture,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        long missingId = 9876543210L;

        long auditBefore = count("operational_audit_events");
        long outboxBefore = count("visit_notification_outbox");
        long itemsBefore = count("visit_session_items");
        long foreignVersion = requestVersion(foreign.getId());
        long comparableVersion = requestVersion(comparableForeign.getId());
        long standaloneVersion = requestVersion(standalone.getId());

        // A. A Request genuinely linked to the owned Session still assembles the item.
        HttpResponse<String> added = addItem(fixture.owner(), session.getId(), sessionVersion(session.getId()),
                linked.getId(), listing(fixture.locality(), "Privacy nearby", ListingStatus.ACTIVE).getId(),
                "OE_ADDED");
        assertEquals(200, added.statusCode(), added.body());
        JsonNode addedItem = mapper.readTree(added.body()).get("items").get(0);
        assertEquals(linked.getId(), addedItem.get("derivedFromRequestId").asLong());

        long versionAfterAdd = sessionVersion(session.getId());
        HttpResponse<String> missing = addItem(fixture.owner(), session.getId(), versionAfterAdd, missingId,
                listing(fixture.locality(), "Privacy missing", ListingStatus.ACTIVE).getId(), "OE_ADDED");
        HttpResponse<String> comparableDenied = addItem(fixture.owner(), session.getId(), versionAfterAdd,
                comparableForeign.getId(),
                listing(fixture.locality(), "Privacy comparable", ListingStatus.ACTIVE).getId(), "OE_ADDED");
        HttpResponse<String> foreignDenied = addItem(fixture.owner(), session.getId(), versionAfterAdd,
                foreign.getId(), listing(fixture.locality(), "Privacy other team", ListingStatus.ACTIVE).getId(),
                "OE_ADDED");
        HttpResponse<String> standaloneDenied = addItem(fixture.owner(), session.getId(), versionAfterAdd,
                standalone.getId(), listing(fixture.locality(), "Privacy unlinked", ListingStatus.ACTIVE).getId(),
                "OE_ADDED");

        // B/C/D. Missing and inaccessible originating Requests are one and the same denial.
        JsonNode expected = withoutTimestamp(mapper.readTree(missing.body()));
        System.out.println("[originating-request-evidence] missing=" + missing.body()
                + " | same-tenant-foreign=" + comparableDenied.body()
                + " | other-team-foreign=" + foreignDenied.body()
                + " | standalone=" + standaloneDenied.body());
        assertEquals(404, missing.statusCode(), missing.body());
        for (HttpResponse<String> denial : List.of(comparableDenied, foreignDenied, standaloneDenied)) {
            assertEquals(404, denial.statusCode(), denial.body());
            // Identical apart from the response timestamp: nothing in the payload can distinguish the
            // Request that exists from the Request that does not.
            assertEquals(expected, withoutTimestamp(mapper.readTree(denial.body())), denial.body());
        }
        // The denial names no identifier at all, so it is not an existence oracle for the Request.
        assertFalse(expected.get("message").asText().matches(".*\\d.*"), expected.toString());

        // Neither denial mutates a Session, an item, an audit trail or a notification.
        assertEquals(itemsBefore + 1, count("visit_session_items"));
        assertEquals(auditBefore, count("operational_audit_events"));
        assertEquals(outboxBefore, count("visit_notification_outbox"));
        assertSessionVersion(session.getId(), versionAfterAdd);
        assertSessionVersion(comparableSession.getId(), 0L);
        assertEquals(comparableSession.getId(),
                requests.findById(comparableForeign.getId()).orElseThrow().getSession().getId());
        assertEquals(foreignSession.getId(),
                requests.findById(foreign.getId()).orElseThrow().getSession().getId());
        assertEquals(VisitRequestStatus.COORDINATING,
                requests.findById(foreign.getId()).orElseThrow().getStatusValue());
        assertNull(requests.findById(standalone.getId()).orElseThrow().getSession());
        assertEquals(comparableVersion, requestVersion(comparableForeign.getId()));
        assertEquals(foreignVersion, requestVersion(foreign.getId()));
        assertEquals(standaloneVersion, requestVersion(standalone.getId()));
    }

    /**
     * A Request outside the exact Session must never enter the action's Request-lock footprint: while a
     * real PostgreSQL transaction holds that row, the denial still completes immediately and the row is
     * left untouched. The in-Session control proves the footprint assertion is not vacuous, and the
     * blocked statement it records proves the locked lookup is Session-scoped in SQL.
     */
    @Test
    void ownedAddNearbyPropertyNeverLocksAForeignOriginatingRequest() throws Exception {
        Fixture fixture = fixture("origin-lock");
        VisitSession session = session("origin-lock", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        PropertyVisitRequest linked = linkedRequest("origin-lock-linked", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        VisitSession otherSession = session("origin-lock-other", fixture, VisitSessionStatus.DRAFT,
                fixture.owner().user(), 0);
        PropertyVisitRequest foreign = linkedRequest("origin-lock-foreign", fixture, otherSession,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        long version = sessionVersion(session.getId());
        long foreignVersion = requestVersion(foreign.getId());
        long itemsBefore = count("visit_session_items");

        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Connection holder = rawConnection()) {
            long holderPid = lockRow(holder, "property_visit_requests", foreign.getId());
            Future<HttpResponse<String>> denied = workers.submit((Callable<HttpResponse<String>>) () ->
                    addItem(fixture.owner(), session.getId(), version, foreign.getId(),
                            listing(fixture.locality(), "Lock foreign", ListingStatus.ACTIVE).getId(), "OE_ADDED"));
            HttpResponse<String> response;
            try {
                response = denied.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                throw new AssertionError("the owned action queued behind a foreign originating Request row "
                        + "lock: " + lockWaits(), timeout);
            }
            assertEquals(404, response.statusCode(), response.body());
            assertNotBlockedBy(holderPid);
            holder.rollback();
        } finally {
            workers.shutdownNow();
        }
        PropertyVisitRequest untouched = requests.findById(foreign.getId()).orElseThrow();
        assertEquals(otherSession.getId(), untouched.getSession().getId());
        assertEquals(VisitRequestStatus.COORDINATING, untouched.getStatusValue());
        assertEquals(foreignVersion, untouched.getVersion());
        assertEquals(itemsBefore, count("visit_session_items"));
        assertSessionVersion(session.getId(), version);

        // Control: the genuinely linked Request *is* locked, and the statement that waits on it proves
        // the lookup is scoped by the owning Session rather than filtered in Java.
        ExecutorService control = Executors.newSingleThreadExecutor();
        try (Connection holder = rawConnection()) {
            long holderPid = lockRow(holder, "property_visit_requests", linked.getId());
            Future<HttpResponse<String>> action = control.submit((Callable<HttpResponse<String>>) () ->
                    addItem(fixture.owner(), session.getId(), version, linked.getId(),
                            listing(fixture.locality(), "Lock linked", ListingStatus.ACTIVE).getId(), "OE_ADDED"));
            long actionPid = awaitBlockedBy(holderPid, "property_visit_requests");
            String statement = waitingStatement(actionPid);
            assertTrue(statement.contains("property_visit_requests"), statement);
            assertTrue(statement.contains("session_id"), statement);
            System.out.println("[originating-request-lock-sql] "
                    + statement.replaceAll("\\s+", " ").trim());
            recordEvidence("originating-request-lock-footprint", holderPid);
            holder.commit();
            HttpResponse<String> response = action.get(30, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(linked.getId(),
                    mapper.readTree(response.body()).get("items").get(0).get("derivedFromRequestId").asLong());
        } finally {
            control.shutdownNow();
        }
        assertEquals(itemsBefore + 1, count("visit_session_items"));
    }

    /**
     * A concurrent Request -&gt; Session linkage must not invert the owned action's lock order and must
     * not leak. While the action waits on the Session root it holds no Request lock, so the linkage
     * commits freely; once the action is unblocked its Session-scoped lookup no longer matches and the
     * action fails closed with the generic 404 instead of assembling an item from a Request that left.
     */
    @Test
    void concurrentOriginatingRequestLinkageCannotInvertOwnedActionLockOrder() throws Exception {
        Fixture fixture = fixture("origin-race");
        VisitSession session = session("origin-race", fixture, VisitSessionStatus.DRAFT, fixture.owner().user(), 0);
        PropertyVisitRequest origin = linkedRequest("origin-race-linked", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        VisitSession otherSession = session("origin-race-other", fixture, VisitSessionStatus.DRAFT,
                fixture.owner().user(), 0);
        long version = sessionVersion(session.getId());
        long itemsBefore = count("visit_session_items");
        long auditBefore = count("operational_audit_events");

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Connection sessionHolder = rawConnection(); Connection link = rawConnection()) {
            long sessionHolderPid = lockRow(sessionHolder, "visit_sessions", session.getId());
            Future<HttpResponse<String>> action = workers.submit((Callable<HttpResponse<String>>) () ->
                    addItem(fixture.owner(), session.getId(), version, origin.getId(),
                            listing(fixture.locality(), "Race nearby", ListingStatus.ACTIVE).getId(), "OE_ADDED"));
            long actionPid = awaitBlockedBy(sessionHolderPid, "visit_sessions");
            System.out.println("[owned-ops-lock-evidence] originating-request-lock-order-session-first-first-wait="
                    + waitingStatement(actionPid).replaceAll("\\s+", " ").trim());

            // The queued action holds no Request lock yet, so the linkage is free to win.
            Future<Integer> linkage = workers.submit(() -> {
                try (PreparedStatement update = link.prepareStatement(
                        "update property_visit_requests set session_id = ? where id = ?")) {
                    update.setLong(1, otherSession.getId());
                    update.setLong(2, origin.getId());
                    return update.executeUpdate();
                }
            });
            try {
                assertEquals(1, linkage.get(15, TimeUnit.SECONDS));
            } catch (TimeoutException timeout) {
                throw new AssertionError("the pending owned action already held a Request row lock: "
                        + lockWaits(), timeout);
            }
            link.commit();
            recordEvidence("originating-request-lock-order-session-first", sessionHolderPid);

            sessionHolder.commit();
            HttpResponse<String> response = action.get(30, TimeUnit.SECONDS);
            assertEquals(404, response.statusCode(), response.body());
            assertNotBlockedBy(actionPid);
        } finally {
            workers.shutdownNow();
        }
        assertEquals(otherSession.getId(), requests.findById(origin.getId()).orElseThrow().getSession().getId());
        assertEquals(itemsBefore, count("visit_session_items"));
        assertEquals(auditBefore, count("operational_audit_events"));
        assertSessionVersion(session.getId(), version);
        assertSessionVersion(otherSession.getId(), 0L);
    }

    /**
     * Pathome Admin authority is untouched: an Admin still assembles items from the Session's own
     * originating Request, and still keeps its previous, deliberately distinguishable outcomes for a
     * Request outside the Session (403) and for one that does not exist (404).
     */
    @Test
    void adminKeepsItsPreviousOriginatingRequestBehaviour() throws Exception {
        Fixture fixture = fixture("origin-admin");
        Actor admin = actor("origin-admin-root", Role.ROLE_ADMIN, null, false);
        VisitSession session = session("origin-admin", fixture, VisitSessionStatus.DRAFT,
                fixture.owner().user(), 0);
        PropertyVisitRequest linked = linkedRequest("origin-admin-linked", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        VisitSession otherSession = session("origin-admin-other", fixture, VisitSessionStatus.DRAFT,
                fixture.owner().user(), 0);
        PropertyVisitRequest foreign = linkedRequest("origin-admin-foreign", fixture, otherSession,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        PropertyVisitRequest standalone = standaloneRequest("origin-admin-standalone", fixture,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        long itemsBefore = count("visit_session_items");

        HttpResponse<String> added = addItem(admin, session.getId(), sessionVersion(session.getId()),
                linked.getId(), listing(fixture.locality(), "Admin nearby", ListingStatus.ACTIVE).getId(),
                "OE_ADDED");
        assertEquals(200, added.statusCode(), added.body());
        assertEquals(linked.getId(),
                mapper.readTree(added.body()).get("items").get(0).get("derivedFromRequestId").asLong());

        long version = sessionVersion(session.getId());
        assertEquals(403, addItem(admin, session.getId(), version, foreign.getId(),
                listing(fixture.locality(), "Admin foreign", ListingStatus.ACTIVE).getId(), "OE_ADDED")
                .statusCode());
        assertEquals(403, addItem(admin, session.getId(), version, standalone.getId(),
                listing(fixture.locality(), "Admin standalone", ListingStatus.ACTIVE).getId(), "OE_ADDED")
                .statusCode());
        assertEquals(404, addItem(admin, session.getId(), version, 9876543210L,
                listing(fixture.locality(), "Admin missing", ListingStatus.ACTIVE).getId(), "OE_ADDED")
                .statusCode());
        assertEquals(itemsBefore + 1, count("visit_session_items"));
        assertSessionVersion(session.getId(), version);
        assertEquals(otherSession.getId(), requests.findById(foreign.getId()).orElseThrow().getSession().getId());
        assertNull(requests.findById(standalone.getId()).orElseThrow().getSession());
    }

    // ------------------------------------------------------------------ lock order and concurrency

    @Test
    void ownershipTransferSerializesWithOwnedRequestActionAndHidesTheFormerOwner() throws Exception {
        Fixture fixture = fixture("race-transfer");
        Actor successor = coordinator("race-successor", fixture.team());
        Actor supervisor = supervisor("race-supervisor", fixture.team());
        PropertyVisitRequest request = standaloneRequest("race-transfer", fixture,
                VisitRequestStatus.RECEIVED, fixture.owner().user());
        long version = requestVersion(request.getId());

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Connection holder = rawConnection()) {
            long holderPid = lockRow(holder, "property_visit_requests", request.getId());
            Future<Object> transfer = workers.submit(() -> {
                ownership.assignRequestCoordinator(supervisor.user().getId(), request.getId(), version,
                        null, null, successor.user().getId(), "TEST_TRANSFER");
                return null;
            });
            long transferPid = awaitBlockedBy(holderPid, "property_visit_requests");
            Future<HttpResponse<String>> action = workers.submit((Callable<HttpResponse<String>>) () ->
                    unavailable(fixture.owner(), request.getId(), version));
            // The enabled action queues behind the transfer that already waits on the Request root.
            String queuedAction = awaitQueuedBehind(List.of(holderPid, transferPid), transferPid);
            assertTrue(queuedAction.contains("property_visit_requests"), queuedAction);
            recordEvidence("ownership-transfer-vs-unavailable", holderPid);

            holder.commit();
            transfer.get(30, TimeUnit.SECONDS);
            HttpResponse<String> response = action.get(30, TimeUnit.SECONDS);
            // The real ownership transfer won the locked Request root, so the former owner is hidden
            // and the Request was never mutated through the stale standalone authority.
            assertEquals(404, response.statusCode(), response.body());
        } finally {
            workers.shutdownNow();
        }
        PropertyVisitRequest reloaded = requests.findById(request.getId()).orElseThrow();
        assertEquals(successor.user().getId(), reloaded.getCoordinator().getId());
        assertEquals(VisitRequestStatus.RECEIVED, reloaded.getStatusValue());
    }

    @Test
    void grantRevocationSerializesWithOwnedSessionActionAndFailsClosed() throws Exception {
        Fixture fixture = fixture("race-revocation");
        Actor securityAdmin = admin("race-admin");
        StaffAccessGrant ownerGrant = grants.findAllByUserIdOrderByCreatedAtDesc(fixture.owner().user().getId())
                .stream().filter(grant -> grant.getCapability() == StaffCapability.OPS_COORDINATE)
                .findFirst().orElseThrow();
        VisitSession session = session("race-revocation", fixture, VisitSessionStatus.DRAFT,
                fixture.owner().user(), 0);
        PropertyVisitRequest origin = linkedRequest("race-revocation-linked", fixture, session,
                VisitRequestStatus.COORDINATING, fixture.owner().user());
        VisitSessionItem removable = derivedItem(session, origin,
                listing(fixture.locality(), "Race removable", ListingStatus.ACTIVE), 1);
        long version = sessionVersion(session.getId());

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Connection holder = rawConnection()) {
            long holderPid = lockRow(holder, "staff_access_grants", ownerGrant.getId());
            Future<Object> revocation = workers.submit(() -> {
                staffAdministration.revokeGrant(securityAdmin.user().getId(), ownerGrant.getId(), "TEST_REVOKED");
                return null;
            });
            long revocationPid = awaitBlockedBy(holderPid, "staff_access_grants");
            Future<HttpResponse<String>> action = workers.submit((Callable<HttpResponse<String>>) () ->
                    removeItem(fixture.owner(), session.getId(), version, removable.getId()));
            // The enabled action parks behind the revocation's already-held City/Team/staff guard rows,
            // so it cannot read capability state until the revocation has committed.
            String blockedActionQuery = awaitQueuedBehind(List.of(revocationPid), revocationPid);
            assertTrue(blockedActionQuery.contains("supported_cities")
                            || blockedActionQuery.contains("operating_teams")
                            || blockedActionQuery.contains("employee_profiles")
                            || blockedActionQuery.contains("staff_access_grants"),
                    blockedActionQuery);
            recordEvidence("grant-revocation-vs-remove-item", holderPid);

            holder.commit();
            revocation.get(30, TimeUnit.SECONDS);
            HttpResponse<String> response = action.get(30, TimeUnit.SECONDS);
            assertEquals(404, response.statusCode(), response.body());
        } finally {
            workers.shutdownNow();
        }
        assertNotNull(grants.findById(ownerGrant.getId()).orElseThrow().getRevokedAt());
        assertNull(visitSessionItems.findById(removable.getId()).orElseThrow().getRemovedAt());
        assertSessionVersion(session.getId(), version);
        // Losing the grant also loses coarse admission entirely.
        assertEquals(403, removeItem(fixture.owner(), session.getId(), version, removable.getId()).statusCode());
    }

    // ------------------------------------------------------------------ harness

    private record Fixture(SupportedCity city, OperatingTeam team, Locality locality, User tenant, Actor owner) {}

    private Fixture fixture(String prefix) {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code(prefix + "-city"), prefix + " City", true));
        OperatingTeam team = teams.saveAndFlush(
                new OperatingTeam(city, code(prefix + "-team"), prefix + " Team", true));
        return new Fixture(city, team, locality(city, prefix), tenant(prefix),
                coordinator(prefix + "-owner", team));
    }

    private Listing listing(Locality locality, String title, ListingStatus status) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle(title + " " + UUID.randomUUID());
        listing.setStatus(status);
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

    private Locality locality(SupportedCity city, String prefix) {
        Locality locality = new Locality(city.getDisplayName(), prefix + " sector " + UUID.randomUUID(),
                null, null, null);
        locality.setSupportedCity(city);
        return localities.saveAndFlush(locality);
    }

    private PropertyVisitRequest standaloneRequest(String suffix, Fixture fixture, VisitRequestStatus status,
            User coordinator) {
        return standaloneRequest(suffix, fixture, listing(fixture.locality(), suffix, ListingStatus.ACTIVE),
                status, coordinator);
    }

    private PropertyVisitRequest standaloneRequest(String suffix, Fixture fixture, Listing listing,
            VisitRequestStatus status, User coordinator) {
        return buildRequest(suffix, fixture, listing, null, status, coordinator);
    }

    private PropertyVisitRequest linkedRequest(String suffix, Fixture fixture, VisitSession session,
            VisitRequestStatus status, User coordinator) {
        return buildRequest(suffix, fixture, listing(fixture.locality(), suffix, ListingStatus.ACTIVE),
                session, status, coordinator);
    }

    private PropertyVisitRequest buildRequest(String suffix, Fixture fixture, Listing listing, VisitSession session,
            VisitRequestStatus status, User coordinator) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(fixture.tenant());
        request.setListing(listing);
        request.setStatus(status);
        request.setSession(session);
        request.setSupportedCity(fixture.city());
        request.setOperatingTeam(fixture.team());
        request.setOperationalScopeReady(true);
        request.setCoordinator(coordinator);
        request.setTenantNote("Private tenant note " + suffix);
        return requests.saveAndFlush(request);
    }

    private VisitSession session(String suffix, Fixture fixture, VisitSessionStatus status, User coordinator,
            long slotHours) {
        return sessionOf(fixture.tenant(), fixture, status, coordinator, slotHours);
    }

    private VisitSession sessionOf(User tenant, Fixture fixture, VisitSessionStatus status, User coordinator) {
        return sessionOf(tenant, fixture, status, coordinator, 0);
    }

    private VisitSession sessionOf(User tenant, Fixture fixture, VisitSessionStatus status, User coordinator,
            long slotHours) {
        VisitSession session = new VisitSession();
        session.setTenant(tenant);
        session.setCity(fixture.city().getDisplayName());
        session.setAreaName(fixture.locality().getSectorName());
        session.setCanonicalLocality(fixture.locality());
        session.setSupportedCity(fixture.city());
        session.setOperatingTeam(fixture.team());
        session.setCoordinator(coordinator);
        session.setOperationalScopeReady(true);
        if (status == VisitSessionStatus.SCHEDULED) {
            Instant scheduledAt = Instant.now().plusSeconds(3600 * (24 + slotHours));
            session.setScheduledAt(scheduledAt);
            session.setZoneId("Asia/Kolkata");
            session.setDurationSnapshotMinutes(60);
            session.setReservedEndAt(scheduledAt.plusSeconds(3600));
            session.setRepresentative(
                    actor("ge-" + UUID.randomUUID(), Role.ROLE_GROUND_BOY, "GROUND_BOY", true).user());
            session.setAssignedAt(Instant.now());
        }
        session.setStatus(status);
        return sessions.saveAndFlush(session);
    }

    private VisitSessionItem derivedItem(VisitSession session, PropertyVisitRequest origin, Listing listing,
            int position) {
        return saveItem(session, origin, listing, position, VisitSessionItemConfirmationStatus.PENDING, null);
    }

    private VisitSessionItem directItem(VisitSession session, PropertyVisitRequest source, Listing listing,
            int position) {
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(session);
        item.setListing(listing);
        item.setPosition(position);
        item.setSourceRequest(source);
        item.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        return visitSessionItems.saveAndFlush(item);
    }

    private VisitSessionItem confirmedDerivedItem(VisitSession session, PropertyVisitRequest origin, Listing listing,
            int position, User confirmedBy) {
        return saveItem(session, origin, listing, position, VisitSessionItemConfirmationStatus.CONFIRMED, confirmedBy);
    }

    private VisitSessionItem saveItem(VisitSession session, PropertyVisitRequest origin, Listing listing, int position,
            VisitSessionItemConfirmationStatus status, User confirmedBy) {
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(session);
        item.setListing(listing);
        item.setPosition(position);
        item.setDerivedFromRequest(origin);
        item.setOrigin(VisitSessionItemOrigin.OE_ADDED);
        item.setConfirmationStatus(status);
        if (status == VisitSessionItemConfirmationStatus.CONFIRMED) {
            item.setAvailabilityConfirmedAt(Instant.now());
            item.setConfirmedBy(confirmedBy);
        }
        return visitSessionItems.saveAndFlush(item);
    }

    private User tenant(String label) {
        return actor(label + "-tenant", Role.ROLE_TENANT, null, false).user();
    }

    private String code(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Actor admin(String label) {
        Actor admin = actor(label, Role.ROLE_ADMIN, "OPERATIONS", true);
        grants.saveAndFlush(new StaffAccessGrant(admin.user(), StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL,
                null, null, Instant.now().minusSeconds(60), null, null, Instant.now(), "TEST_ADMIN",
                StaffGrantProvisioningSource.ADMIN_API));
        return admin;
    }

    private Actor coordinator(String label, OperatingTeam team) {
        Actor coordinator = actor(label, Role.ROLE_TENANT, "OPERATIONS", true);
        coordinatorGrant(coordinator, team);
        return coordinator;
    }

    private Actor supervisor(String label, OperatingTeam team) {
        Actor supervisor = actor(label, Role.ROLE_TENANT, "OPERATIONS", true);
        superviseGrant(supervisor, team);
        return supervisor;
    }

    private Actor actor(String label, Role role, String profileRole, boolean staffActive) {
        User user = new User();
        user.setEmail("owned-ops-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("+91987654" + String.format("%04d", (int) (Math.random() * 10000)));
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

    private StaffAccessGrant coordinatorGrant(Actor actor, OperatingTeam team) {
        return coordinatorGrant(actor, team, Instant.now().minusSeconds(60), null);
    }

    private StaffAccessGrant coordinatorGrant(Actor actor, OperatingTeam team, Instant effectiveAt, Instant expiresAt) {
        return grants.saveAndFlush(new StaffAccessGrant(actor.user(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team, effectiveAt, expiresAt, null, Instant.now(), "TEST_OWNED",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private void superviseGrant(Actor actor, OperatingTeam team) {
        grants.saveAndFlush(new StaffAccessGrant(actor.user(), StaffCapability.OPS_SUPERVISE,
                StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(60), null, null, Instant.now(),
                "TEST_OWNED", StaffGrantProvisioningSource.ADMIN_API));
    }

    private void revokeNow(StaffAccessGrant grant) {
        grant.revoke(users.findById(grant.getUser().getId()).orElseThrow(), Instant.now(), "TEST_REVOKED");
        grants.saveAndFlush(grant);
    }

    private long requestVersion(long requestId) {
        return requests.findById(requestId).orElseThrow().getVersion();
    }

    private long sessionVersion(long sessionId) {
        return sessions.findById(sessionId).orElseThrow().getVersion();
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0 : value;
    }

    private JsonNode itemById(JsonNode sessionView, long itemId) {
        for (JsonNode item : sessionView.get("items")) {
            if (item.get("itemId").asLong() == itemId) return item;
        }
        throw new AssertionError("Missing item " + itemId + " in " + sessionView);
    }

    private void assertRequestStillReceived(long requestId) {
        PropertyVisitRequest request = requests.findById(requestId).orElseThrow();
        assertEquals(VisitRequestStatus.RECEIVED, request.getStatusValue());
        assertNull(request.getSession());
    }

    private void assertSessionVersion(long sessionId, long expectedVersion) {
        assertEquals(expectedVersion, sessionVersion(sessionId));
    }

    private void assertNotFound(String method, String path, Actor actor, String body) throws Exception {
        HttpResponse<String> response = send(method, path, actor.token(), body);
        assertEquals(404, response.statusCode(), method + " " + path + " -> " + response.body());
    }

    private HttpResponse<String> coordinate(Actor actor, long requestId, long expectedRequestVersion,
            Long sessionId, Long expectedSessionVersion) throws Exception {
        return send("POST", "/api/v1/operations/visit-requests/" + requestId + "/coordinate",
                actor == null ? null : actor.token(), "{\"expectedRequestVersion\":" + expectedRequestVersion
                        + ",\"sessionId\":" + sessionId + ",\"expectedSessionVersion\":" + expectedSessionVersion + "}");
    }

    private HttpResponse<String> unavailable(Actor actor, long requestId, long expectedRequestVersion) throws Exception {
        return send("POST", "/api/v1/operations/visit-requests/" + requestId + "/unavailable",
                actor == null ? null : actor.token(),
                "{\"expectedRequestVersion\":" + expectedRequestVersion + "}");
    }

    private HttpResponse<String> addItem(Actor actor, long sessionId, long expectedSessionVersion,
            Long originatingRequestId, Long listingId, String origin) throws Exception {
        return send("POST", "/api/v1/operations/visit-sessions/" + sessionId + "/items",
                actor == null ? null : actor.token(), "{\"expectedSessionVersion\":" + expectedSessionVersion
                        + ",\"listingId\":" + listingId + ",\"originatingRequestId\":" + originatingRequestId
                        + ",\"origin\":\"" + origin + "\"}");
    }

    private HttpResponse<String> removeItem(Actor actor, long sessionId, long expectedSessionVersion, long itemId)
            throws Exception {
        return send("DELETE", "/api/v1/operations/visit-sessions/" + sessionId + "/items/" + itemId,
                actor == null ? null : actor.token(),
                "{\"expectedSessionVersion\":" + expectedSessionVersion + "}");
    }

    private HttpResponse<String> reorder(Actor actor, long sessionId, long expectedSessionVersion, List<Long> itemIds)
            throws Exception {
        return send("PUT", "/api/v1/operations/visit-sessions/" + sessionId + "/items/order",
                actor == null ? null : actor.token(), "{\"expectedSessionVersion\":" + expectedSessionVersion
                        + ",\"itemIds\":" + itemIds + "}");
    }

    private HttpResponse<String> availability(Actor actor, long sessionId, long expectedSessionVersion, long itemId,
            String status) throws Exception {
        return send("PUT", "/api/v1/operations/visit-sessions/" + sessionId + "/items/" + itemId
                        + "/availability", actor == null ? null : actor.token(),
                "{\"expectedSessionVersion\":" + expectedSessionVersion + ",\"status\":\"" + status + "\"}");
    }

    private HttpResponse<String> availabilityWindow(Actor actor, long sessionId, long expectedSessionVersion,
            long itemId, String status, String source) throws Exception {
        return send("PUT", "/api/v1/operations/visit-sessions/" + sessionId + "/items/" + itemId
                        + "/availability", actor == null ? null : actor.token(),
                "{\"expectedSessionVersion\":" + expectedSessionVersion + ",\"status\":\"" + status
                        + "\",\"availabilityStartAt\":\"2026-12-01T09:00:00+05:30\""
                        + ",\"availabilityEndAt\":\"2026-12-01T11:00:00+05:30\""
                        + ",\"availabilityZoneId\":\"Asia/Kolkata\",\"availabilitySource\":\"" + source + "\"}");
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        try {
            return HTTP.send(request.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Connection rawConnection() throws Exception {
        Connection connection = DriverManager.getConnection(schemaUrl(), USERNAME, PASSWORD);
        connection.setAutoCommit(false);
        return connection;
    }

    /** Holds a real row lock on the authoritative root and returns the holding backend's pid. */
    private long lockRow(Connection holder, String table, long id) throws Exception {
        long pid;
        try (PreparedStatement pidStatement = holder.prepareStatement("select pg_backend_pid()");
             ResultSet pidResult = pidStatement.executeQuery()) {
            assertTrue(pidResult.next());
            pid = pidResult.getLong(1);
        }
        try (PreparedStatement lock = holder.prepareStatement(
                "select id from " + table + " where id = ? for update")) {
            lock.setLong(1, id);
            try (ResultSet locked = lock.executeQuery()) {
                assertTrue(locked.next(), "root row " + id + " must exist to be locked");
            }
        }
        return pid;
    }

    private List<Map<String, Object>> lockWaits() {
        return jdbc.queryForList("select a.pid as pid, a.query as query, "
                + "coalesce(array_to_string(pg_blocking_pids(a.pid), ','), '') as blockers "
                + "from pg_stat_activity a where a.wait_event_type = 'Lock' and a.pid <> pg_backend_pid()");
    }

    /** Fails when any other PostgreSQL backend is waiting on a row lock held by the given backend. */
    private void assertNotBlockedBy(long holderPid) {
        for (Map<String, Object> row : lockWaits()) {
            assertFalse(blockedBy(row, holderPid), "unexpected lock wait: " + row);
        }
    }

    /** The statement a live backend is currently waiting on, as PostgreSQL sees it. */
    private String waitingStatement(long pid) {
        String statement = jdbc.queryForObject("select query from pg_stat_activity where pid = ?", String.class, pid);
        return statement == null ? "" : statement;
    }

    /** The response payload without its own timestamp, so two denials can be compared exactly. */
    private JsonNode withoutTimestamp(JsonNode payload) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = payload.deepCopy();
        copy.remove("timestamp");
        return copy;
    }

    /** Waits for a real PostgreSQL backend to be blocked by the given blocker on the given relation. */
    private long awaitBlockedBy(long blockerPid, String relation) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            for (Map<String, Object> row : lockWaits()) {
                if (blockedBy(row, blockerPid) && String.valueOf(row.get("query")).contains(relation)) {
                    return ((Number) row.get("pid")).longValue();
                }
            }
            Thread.sleep(25);
        }
        fail("no backend was blocked by " + blockerPid + " on " + relation + "; observed " + lockWaits());
        return -1L;
    }

    /**
     * Waits for a distinct backend that is queued behind any of the given blockers (PostgreSQL
     * reports the backend immediately ahead in a row-lock queue, not only the lock holder) and
     * returns the statement it is waiting on.
     */
    private String awaitQueuedBehind(List<Long> blockers, long excludedPid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            for (Map<String, Object> row : lockWaits()) {
                long pid = ((Number) row.get("pid")).longValue();
                if (pid == excludedPid) continue;
                for (Long blocker : blockers) {
                    if (blockedBy(row, blocker)) return String.valueOf(row.get("query"));
                }
            }
            Thread.sleep(25);
        }
        fail("no backend was queued behind " + blockers + "; observed " + lockWaits());
        return "";
    }

    private static boolean blockedBy(Map<String, Object> row, long blockerPid) {
        String blockers = String.valueOf(row.get("blockers"));
        return ("," + blockers + ",").contains("," + blockerPid + ",");
    }

    /** Records the observed real PostgreSQL lock-wait evidence for the report, with no fixed sleeps. */
    private void recordEvidence(String scenario, long holderPid) {
        StringBuilder evidence = new StringBuilder("[owned-ops-lock-evidence] ").append(scenario)
                .append(" row-lock holder=").append(holderPid);
        for (Map<String, Object> row : lockWaits()) {
            evidence.append(" | pid=").append(row.get("pid"))
                    .append(" blockers=").append(row.get("blockers"))
                    .append(" waiting_on=").append(relationOf(String.valueOf(row.get("query"))));
        }
        System.out.println(evidence);
    }

    private static String relationOf(String query) {
        int index = query.indexOf(" from ");
        if (index < 0) return query;
        String tail = query.substring(index + 6).trim();
        int space = tail.indexOf(' ');
        return space < 0 ? tail : tail.substring(0, space);
    }

    private record Actor(User user, String token) {}
}
