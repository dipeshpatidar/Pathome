package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.RemoveVisitSessionItemCommand;
import com.indore.pathome.spaces.dto.StaffStateCommand;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2A A1a secure lessor-contact seam.
 *
 * <p>Real PostgreSQL: authorization is re-checked under the same City/Team/staff/grant guard locks the
 * frozen ownership model uses, only the currently owning Operations Executive can read a lessor's
 * name and mobile, contact is resolved exclusively through {@code Listing.lessorProfileId ->
 * LessorProfile}, and a synchronous audit row gates every successful reveal.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=lessor-contact-http-test-jwt-secret-32-bytes-minimum",
        "pathome.visit.otp.hmac-secret=lessor-contact-http-test-otp-secret-32-bytes-minimum",
        "cloudinary.api-key=lessor-contact-test-key",
        "cloudinary.api-secret=lessor-contact-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class OperationsLessorContactHttpPostgresTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "lessor_contact_http_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String LEGACY_PHONE = "+91 7000000000";
    /** Bounded, deterministic deadline for proving a real PostgreSQL lock wait (no arbitrary sleeps). */
    private static final long POSTGRES_LOCK_WAIT_TIMEOUT_SECONDS = 15;

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
    @Autowired private LessorProfileRepository lessorProfiles;
    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private VisitSessionRepository sessions;
    @Autowired private VisitSessionItemRepository visitSessionItems;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OperationalOwnershipService ownership;
    @Autowired private StaffAdministrationService staffAdmin;
    @Autowired private VisitOperationsService visitOperations;
    @LocalServerPort private int httpPort;

    @AfterEach
    void resetBarriers() {
        // Release any reveal a failed assertion may have left parked so no transaction leaks into the next test.
        ListingReadBarrier.release();
        ListingReadBarrier.reset();
        AuditFailureGuard.reset();
    }

    // ---------------------------------------------------------------------------------------------
    // A. Standalone Request: ownership, privacy and error semantics
    // ---------------------------------------------------------------------------------------------

    @Test
    void standaloneRequestContactIsOwnerScopedAndPrivacyBounded() throws Exception {
        SupportedCity cityA = cities.saveAndFlush(new SupportedCity(code("a"), "Contact City A", true));
        SupportedCity cityB = cities.saveAndFlush(new SupportedCity(code("b"), "Contact City B", true));
        OperatingTeam teamA1 = teams.saveAndFlush(new OperatingTeam(cityA, code("a1"), "Contact Team A1", true));
        OperatingTeam teamA2 = teams.saveAndFlush(new OperatingTeam(cityA, code("a2"), "Contact Team A2", true));
        OperatingTeam teamB1 = teams.saveAndFlush(new OperatingTeam(cityB, code("b1"), "Contact Team B1", true));
        Locality localityA1 = locality(cityA, "oc-a1");
        Locality localityA2 = locality(cityA, "oc-a2");
        Locality localityB1 = locality(cityB, "oc-b1");

        Actor tenant = actor("tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("lessor", Role.ROLE_TENANT, null, false);
        Actor owner = actor("owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor teammate = actor("teammate", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor noScope = actor("no-scope", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, teamA1);
        teamGrant(teammate, teamA1);

        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Asha Verma", "9876543210",
                LessorSourceType.SELF_SERVICE);
        Listing listing = listing(localityA1, "owned", lessorUser.user().getId(), profile);
        PropertyVisitRequest owned = standaloneRequest("owned", tenant.user(), cityA, teamA1, listing, owner.user());

        HttpResponse<String> response = send("GET", requestPath(owned.getId()), owner.token());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                "a live contact response must never be cached");
        JsonNode body = mapper.readTree(response.body());
        assertEquals(Set.of("listingId", "name", "mobile", "status"), fieldNames(body));
        assertEquals(listing.getId(), body.path("listingId").asLong());
        assertEquals("Asha Verma", body.path("name").asText());
        assertEquals("+91 9876543210", body.path("mobile").asText());
        assertEquals("CONTACT_AVAILABLE", body.path("status").asText());
        assertFalse(response.body().contains(LEGACY_PHONE),
                "the stale legacy listing owner phone must never be disclosed");
        assertEquals(1, auditCount("PROPERTY_VISIT_REQUEST", owned.getId()));

        // Another coordinator in the same Team but not the owner must not learn the target exists.
        assertEquals(404, send("GET", requestPath(owned.getId()), teammate.token()).statusCode());
        // A claimable (unclaimed) Request is not owned by anyone yet.
        PropertyVisitRequest claimable = standaloneRequest("claimable", tenant.user(), cityA, teamA1,
                listing(localityA2, "claimable", lessorUser.user().getId(), profile), null);
        assertEquals(404, send("GET", requestPath(claimable.getId()), owner.token()).statusCode());
        // A Request owned by a different coordinator is 404 for the caller but 200 for its owner.
        PropertyVisitRequest teammateOwned = standaloneRequest("teammate-owned", tenant.user(), cityA, teamA1,
                listing(localityA2, "teammate-owned", lessorUser.user().getId(), profile), teammate.user());
        assertEquals(404, send("GET", requestPath(teammateOwned.getId()), owner.token()).statusCode());
        assertEquals(200, send("GET", requestPath(teammateOwned.getId()), teammate.token()).statusCode());
        // A Request in a Team or City without a current caller grant is not visible.
        PropertyVisitRequest otherTeam = standaloneRequest("other-team", tenant.user(), cityA, teamA2,
                listing(localityA2, "other-team", lessorUser.user().getId(), profile), owner.user());
        assertEquals(404, send("GET", requestPath(otherTeam.getId()), owner.token()).statusCode());
        PropertyVisitRequest otherCity = standaloneRequest("other-city", tenant.user(), cityB, teamB1,
                listing(localityB1, "other-city", lessorUser.user().getId(), profile), owner.user());
        assertEquals(404, send("GET", requestPath(otherCity.getId()), owner.token()).statusCode());

        // A Request linked to a Visit Session is no longer an operable Request root.
        VisitSession session = draftSession("linked", tenant.user(), cityA, teamA1, owner.user());
        PropertyVisitRequest linked = linkedRequest("linked", tenant.user(), cityA, teamA1,
                listing(localityA1, "linked", lessorUser.user().getId(), profile), session, owner.user(),
                VisitRequestStatus.COORDINATING);
        assertEquals(404, send("GET", requestPath(linked.getId()), owner.token()).statusCode(),
                "a Session-rooted Request must not expose contact through the Request route");

        assertEquals(404, send("GET", requestPath(999_999_999L), owner.token()).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/visit-requests/not-an-id/lessor-contact",
                owner.token()).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/visit-requests/-5/lessor-contact",
                owner.token()).statusCode());

        HttpResponse<String> noAuthority = send("GET", requestPath(owned.getId()), noScope.token());
        assertEquals(403, noAuthority.statusCode(),
                "a caller without any usable OPS_COORDINATE Team authority is 403");
        assertEquals(403, send("GET", requestPath(owned.getId()), tenant.token()).statusCode());
        assertEquals(401, send("GET", requestPath(owned.getId()), null).statusCode());

        // Error responses never disclose digits and are never cacheable.
        for (HttpResponse<String> error : List.of(
                send("GET", requestPath(owned.getId()), teammate.token()),
                send("GET", requestPath(999_999_999L), owner.token()),
                send("GET", "/api/v1/operations/visit-requests/not-an-id/lessor-contact", owner.token()),
                noAuthority)) {
            assertFalse(error.body().contains("9876543210"), "errors must never leak contact digits");
            assertTrue(error.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                    "error responses for a PII route must never be cacheable");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // B. Canonical resolution: fail-closed and never legacy
    // ---------------------------------------------------------------------------------------------

    @Test
    void contactResolutionFailsClosedWithoutTrustedCanonicalProfile() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("res"), "Resolution City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("res-team"), "Resolution Team", true));
        Locality locality = locality(city, "res");
        Actor tenant = actor("res-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("res-lessor", Role.ROLE_TENANT, null, false);
        Actor invalidPhoneUser = actor("res-lessor-phone", Role.ROLE_TENANT, null, false);
        Actor placeholderUser = actor("res-lessor-placeholder", Role.ROLE_TENANT, null, false);
        Actor missingNameUser = actor("res-lessor-noname", Role.ROLE_TENANT, null, false);
        Actor otherUser = actor("res-other", Role.ROLE_TENANT, null, false);
        Actor owner = actor("res-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, team);

        LessorProfile good = lessorProfile(lessorUser.user().getId(), "Good Lessor", "9876500001",
                LessorSourceType.SELF_SERVICE);
        LessorProfile internal = lessorProfile(null, "Field Lessor", "9876500002", LessorSourceType.FIELD_TEAM);
        LessorProfile invalidPhone = lessorProfile(invalidPhoneUser.user().getId(), "Bad Phone", "12345",
                LessorSourceType.SELF_SERVICE);
        LessorProfile placeholder = lessorProfile(placeholderUser.user().getId(), "Lessor 4242", "9876500003",
                LessorSourceType.SELF_SERVICE);
        LessorProfile selfServiceUnlinked = lessorProfile(null, "Unlinked Self Service", "9876500004",
                LessorSourceType.SELF_SERVICE);
        LessorProfile missingName = lessorProfile(missingNameUser.user().getId(), null, "9876500005",
                LessorSourceType.SELF_SERVICE);

        assertUnavailable(requestContact(owner, tenant, city, team, locality, "no-association",
                listing(locality, "no-association", lessorUser.user().getId(), null)));
        assertUnavailable(requestContact(owner, tenant, city, team, locality, "invalid-phone",
                listing(locality, "invalid-phone", invalidPhoneUser.user().getId(), invalidPhone)));
        assertUnavailable(requestContact(owner, tenant, city, team, locality, "placeholder",
                listing(locality, "placeholder", placeholderUser.user().getId(), placeholder)));
        assertUnavailable(requestContact(owner, tenant, city, team, locality, "missing-name",
                listing(locality, "missing-name", missingNameUser.user().getId(), missingName)));
        assertUnavailable(requestContact(owner, tenant, city, team, locality, "identity-mismatch",
                listing(locality, "identity-mismatch", otherUser.user().getId(), good)));
        assertUnavailable(requestContact(owner, tenant, city, team, locality, "self-service-unlinked",
                listing(locality, "self-service-unlinked", null, selfServiceUnlinked)));

        // Internally sourced, explicitly linked profile without a User is trustworthy.
        JsonNode internalView = requestContact(owner, tenant, city, team, locality, "internal",
                listing(locality, "internal", null, internal));
        assertEquals("CONTACT_AVAILABLE", internalView.path("status").asText());
        assertEquals("Field Lessor", internalView.path("name").asText());
        assertEquals("+91 9876500002", internalView.path("mobile").asText());

        // Canonical profile changes are read live; the stale legacy listing phone is never a fallback.
        PropertyVisitRequest goodRequest = requestContactEntity(tenant, city, team, "good",
                listing(locality, "good", lessorUser.user().getId(), good), owner.user());
        JsonNode firstView = read("GET", requestPath(goodRequest.getId()), owner.token());
        assertEquals("+91 9876500001", firstView.path("mobile").asText());
        jdbc.update("update lessor_profiles set mobile_number = ? where id = ?", "+91 9876599999", good.getId());
        JsonNode secondView = read("GET", requestPath(goodRequest.getId()), owner.token());
        assertEquals("+91 9876599999", secondView.path("mobile").asText(),
                "the current canonical profile contact is disclosed, not a cached or legacy copy");
    }

    // ---------------------------------------------------------------------------------------------
    // C. Visit Session item: status, ownership, item liveness and multi-lessor isolation
    // ---------------------------------------------------------------------------------------------

    @Test
    void sessionItemContactHonoursRootStatusOwnershipAndItemLiveness() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("s"), "Session City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("s-team"), "Session Team", true));
        Locality locality = locality(city, "s");
        Actor tenant = actor("s-tenant", Role.ROLE_TENANT, null, false);
        Actor ge = actor("s-ge", Role.ROLE_GROUND_BOY, null, false);
        Actor lessorOneUser = actor("s-lessor-1", Role.ROLE_TENANT, null, false);
        Actor lessorTwoUser = actor("s-lessor-2", Role.ROLE_TENANT, null, false);
        Actor owner = actor("s-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor teammate = actor("s-teammate", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, team);
        teamGrant(teammate, team);

        LessorProfile lessorOne = lessorProfile(lessorOneUser.user().getId(), "Lessor One", "9811100001",
                LessorSourceType.SELF_SERVICE);
        LessorProfile lessorTwo = lessorProfile(lessorTwoUser.user().getId(), "Lessor Two", "9811100002",
                LessorSourceType.SELF_SERVICE);

        VisitSession session = draftSession("s-draft", tenant.user(), city, team, owner.user());
        PropertyVisitRequest childOne = linkedRequest("s-child-1", tenant.user(), city, team,
                listing(locality, "s-child-1", lessorOneUser.user().getId(), lessorOne), session, owner.user(),
                VisitRequestStatus.COORDINATING);
        PropertyVisitRequest childTwo = linkedRequest("s-child-2", tenant.user(), city, team,
                listing(locality, "s-child-2", lessorTwoUser.user().getId(), lessorTwo), session, owner.user(),
                VisitRequestStatus.COORDINATING);
        VisitSessionItem itemOne = item(session, childOne, 1);
        VisitSessionItem itemTwo = item(session, childTwo, 2);

        // Two lessors in one Session resolve independently and never cross-associate.
        JsonNode one = read("GET", itemPath(session.getId(), itemOne.getId()), owner.token());
        JsonNode two = read("GET", itemPath(session.getId(), itemTwo.getId()), owner.token());
        assertEquals("Lessor One", one.path("name").asText());
        assertEquals("+91 9811100001", one.path("mobile").asText());
        assertEquals("Lessor Two", two.path("name").asText());
        assertEquals("+91 9811100002", two.path("mobile").asText());
        assertEquals(1, auditCount("VISIT_SESSION_ITEM", itemOne.getId()));
        assertEquals(1, auditCount("VISIT_SESSION_ITEM", itemTwo.getId()));

        // The owning coordinator of a Session may read; a same-Team non-owner may not.
        assertEquals(404, send("GET", itemPath(session.getId(), itemOne.getId()), teammate.token()).statusCode());

        // Item belonging to another Session is 404.
        VisitSession other = draftSession("s-other", tenant.user(), city, team, owner.user());
        assertEquals(404, send("GET", itemPath(other.getId(), itemOne.getId()), owner.token()).statusCode());
        assertEquals(404, send("GET", itemPath(999_999_999L, itemOne.getId()), owner.token()).statusCode());
        assertEquals(404, send("GET", itemPath(session.getId(), 999_999_999L), owner.token()).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/visit-sessions/x/items/1/lessor-contact",
                owner.token()).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/visit-sessions/1/items/x/lessor-contact",
                owner.token()).statusCode());

        // Removed items never expose contact.
        VisitSessionItem removable = item(other, linkedRequest("s-removable", tenant.user(), city, team,
                listing(locality, "s-removable", lessorOneUser.user().getId(), lessorOne), other, owner.user(),
                VisitRequestStatus.COORDINATING), 1);
        jdbc.update("update visit_session_items set removed_at = now(), removal_reason = ? where id = ?",
                "NO_LONGER_RELEVANT", removable.getId());
        assertEquals(404, send("GET", itemPath(other.getId(), removable.getId()), owner.token()).statusCode());

        // Every approved active status allows a reveal; every terminal status denies it.
        int slot = 1;
        for (VisitSessionStatus status : List.of(VisitSessionStatus.DRAFT, VisitSessionStatus.SCHEDULED,
                VisitSessionStatus.STARTED, VisitSessionStatus.INTERRUPTED)) {
            VisitSession allowedSession = sessionInStatus(status, tenant.user(), city, team, owner.user(),
                    ge.user(), slot++);
            VisitSessionItem allowedItem = item(allowedSession, linkedRequest("allow-" + status, tenant.user(), city,
                    team, listing(locality, "allow-" + status, lessorOneUser.user().getId(), lessorOne),
                    allowedSession, owner.user(), VisitRequestStatus.COORDINATING), 1);
            assertEquals(200, send("GET", itemPath(allowedSession.getId(), allowedItem.getId()),
                    owner.token()).statusCode(), "status " + status + " must allow an owned reveal");
        }
        for (VisitSessionStatus status : List.of(VisitSessionStatus.COMPLETED, VisitSessionStatus.EXPIRED,
                VisitSessionStatus.CANCELLED, VisitSessionStatus.NO_SHOW, VisitSessionStatus.PROVISIONAL_NO_SHOW,
                VisitSessionStatus.REPAIR_REQUIRED)) {
            VisitSession deniedSession = sessionInStatus(status, tenant.user(), city, team, owner.user(),
                    ge.user(), slot++);
            VisitSessionItem deniedItem = item(deniedSession, linkedRequest("deny-" + status, tenant.user(), city,
                    team, listing(locality, "deny-" + status, lessorOneUser.user().getId(), lessorOne),
                    deniedSession, owner.user(), VisitRequestStatus.COORDINATING), 1);
            HttpResponse<String> denied = send("GET", itemPath(deniedSession.getId(), deniedItem.getId()),
                    owner.token());
            assertEquals(404, denied.statusCode(), "status " + status + " must deny contact");
            assertFalse(denied.body().contains("9811100001"), "a denied status must not leak contact");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // D. Audit
    // ---------------------------------------------------------------------------------------------

    @Test
    void auditRecordsRevealWithoutPiiAndFailureWithholdsContact() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("aud"), "Audit City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("aud-team"), "Audit Team", true));
        Locality locality = locality(city, "aud");
        Actor tenant = actor("aud-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("aud-lessor", Role.ROLE_TENANT, null, false);
        Actor owner = actor("aud-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, team);
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Audit Lessor", "9811122233",
                LessorSourceType.SELF_SERVICE);

        PropertyVisitRequest good = standaloneRequest("audit-good", tenant.user(), city, team,
                listing(locality, "audit-good", lessorUser.user().getId(), profile), owner.user());
        read("GET", requestPath(good.getId()), owner.token());
        Map<String, Object> event = jdbc.queryForMap("select action_code, reason_code, target_type, target_id, "
                + "actor_user_id, city_id, team_id, details::text as details from operational_audit_events "
                + "where target_type = 'PROPERTY_VISIT_REQUEST' and target_id = ? "
                + "and action_code = 'LESSOR_CONTACT_REVEALED'", good.getId());
        assertEquals("LESSOR_CONTACT_REVEALED", event.get("action_code"));
        assertEquals("COORDINATION_CONTACT", event.get("reason_code"));
        assertEquals("PROPERTY_VISIT_REQUEST", event.get("target_type"));
        assertEquals(owner.user().getId(), ((Number) event.get("actor_user_id")).longValue());
        assertEquals(city.getId(), ((Number) event.get("city_id")).longValue());
        assertEquals(team.getId(), ((Number) event.get("team_id")).longValue());
        String details = String.valueOf(event.get("details"));
        assertFalse(details.contains("9811122233"), "audit details must never contain the mobile number");
        assertFalse(details.contains("Audit Lessor"), "audit details must never contain the lessor name");
        assertEquals("{}", details);

        // An unavailable contact records no reveal event.
        PropertyVisitRequest noContact = standaloneRequest("audit-none", tenant.user(), city, team,
                listing(locality, "audit-none", lessorUser.user().getId(), null), owner.user());
        JsonNode unavailable = read("GET", requestPath(noContact.getId()), owner.token());
        assertEquals("CONTACT_UNAVAILABLE", unavailable.path("status").asText());
        assertEquals(0, auditCount("PROPERTY_VISIT_REQUEST", noContact.getId()));

        // A required audit write that fails must never let a 200 with contact through.
        PropertyVisitRequest guarded = standaloneRequest("audit-fail", tenant.user(), city, team,
                listing(locality, "audit-fail", lessorUser.user().getId(), profile), owner.user());
        AuditFailureGuard.failNextRecord();
        HttpResponse<String> failure = send("GET", requestPath(guarded.getId()), owner.token());
        assertTrue(failure.statusCode() >= 500, "audit failure must surface as a server error, not a partial reveal");
        assertFalse(failure.body().contains("9811122233"), "a failed reveal must never leak contact");
        assertEquals(0, auditCount("PROPERTY_VISIT_REQUEST", guarded.getId()),
                "the audit row is rolled back with the failed reveal");
    }

    // ---------------------------------------------------------------------------------------------
    // E. Concurrency: a live reveal serializes with ownership, grant, staff and scope changes
    // ---------------------------------------------------------------------------------------------

    @Test
    void contactRevealSerializesWithConcurrentOwnershipTransfer() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("cc"), "Concurrency City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("cc-team"), "Concurrency Team", true));
        Locality locality = locality(city, "cc");
        Actor tenant = actor("cc-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("cc-lessor", Role.ROLE_TENANT, null, false);
        Actor owner = actor("cc-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor successor = actor("cc-successor", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, team);
        teamGrant(successor, team);
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Concurrency Lessor", "9811199999",
                LessorSourceType.SELF_SERVICE);

        VisitSession session = draftSession("cc", tenant.user(), city, team, owner.user());
        VisitSessionItem item = item(session, linkedRequest("cc-child", tenant.user(), city, team,
                listing(locality, "cc-child", lessorUser.user().getId(), profile), session, owner.user(),
                VisitRequestStatus.COORDINATING), 1);

        // A reveal that has entered its critical section (Session root locked) must block a concurrent
        // ownership transfer until it commits: the transfer can never interleave with live PII.
        ListingReadBarrier.arm();
        AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
        Thread revealThread = new Thread(() -> reveal.set(send("GET", itemPath(session.getId(), item.getId()),
                owner.token())));
        revealThread.start();
        assertTrue(ListingReadBarrier.awaitEntered(10_000), "the reveal must reach its guarded critical section");
        int revealPid = parkedRevealBackendPid("concurrent ownership transfer");

        MutationTrace transferTrace = new MutationTrace("concurrent ownership transfer");
        AtomicInteger transferPid = new AtomicInteger();
        AtomicReference<Throwable> transferFailure = new AtomicReference<>();
        CountDownLatch transferDone = runAsync(() -> {
            transferTrace.stage(MutationTrace.WORKER_STARTED);
            commitCoordinatorTransfer(transferTrace, transferPid, session.getId(), successor.user().getId());
        }, transferFailure);
        BlockedBackend blocked = awaitCompetingLockWait("concurrent ownership transfer", revealPid,
                transferTrace, "visit_sessions");
        assertEquals(transferPid.get(), blocked.pid(),
                "the blocked backend must be the raw competing connection: " + blocked);

        ListingReadBarrier.release();
        revealThread.join(15_000);
        assertEquals(200, reveal.get().statusCode(), reveal.get().body());
        assertEquals("Concurrency Lessor", mapper.readTree(reveal.get().body()).path("name").asText());
        assertTrue(transferDone.await(15, TimeUnit.SECONDS), "the transfer proceeds once the reveal commits");
        assertNull(transferFailure.get(), String.valueOf(transferFailure.get()));
        transferTrace.stage("mutation completed");

        // After the committed transfer the former owner loses access immediately and the successor gains it.
        assertEquals(404, send("GET", itemPath(session.getId(), item.getId()), owner.token()).statusCode());
        assertEquals(200, send("GET", itemPath(session.getId(), item.getId()), successor.token()).statusCode());

        // Grant revocation withholds contact immediately.
        Actor revoked = actor("cc-revoked", Role.ROLE_TENANT, "OPERATIONS", true);
        StaffAccessGrant grant = teamGrant(revoked, team);
        grant.revoke(users.findById(revoked.user().getId()).orElseThrow(), Instant.now(), "TEST_REVOKED");
        grants.saveAndFlush(grant);
        PropertyVisitRequest revokeTarget = standaloneRequest("cc-revoke", tenant.user(), city, team,
                listing(locality, "cc-revoke", lessorUser.user().getId(), profile), revoked.user());
        assertNoPii(send("GET", requestPath(revokeTarget.getId()), revoked.token()));

        // Staff deactivation withholds contact immediately.
        jdbc.update("update employee_profiles set staff_active = false where user_id = ?", owner.user().getId());
        assertNoPii(send("GET", itemPath(session.getId(), item.getId()), owner.token()));

        // Team deactivation withholds contact immediately.
        jdbc.update("update operating_teams set active = false where id = ?", team.getId());
        assertNoPii(send("GET", itemPath(session.getId(), item.getId()), successor.token()));
    }

    // ---------------------------------------------------------------------------------------------
    // F. Deterministic concurrency evidence for the remaining A1a races. Every case uses real
    //    production commands or independent committed transactions, latch/barrier ordering, bounded
    //    waits and explicit before/after serialization assertions. No scenario is claimed verified
    //    unless its transaction ordering is actually proven.
    // ---------------------------------------------------------------------------------------------

    @Test
    void unauthenticatedContactResponsesAreUnauthorizedAndNeverCacheable() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("uh"), "Header City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("uh-team"), "Header Team", true));
        Locality locality = locality(city, "uh");
        Actor tenant = actor("uh-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("uh-lessor", Role.ROLE_TENANT, null, false);
        Actor owner = actor("uh-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, team);
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Header Lessor", "9822200009",
                LessorSourceType.SELF_SERVICE);
        PropertyVisitRequest request = standaloneRequest("uh", tenant.user(), city, team,
                listing(locality, "uh", lessorUser.user().getId(), profile), owner.user());
        VisitSession session = draftSession("uh", tenant.user(), city, team, owner.user());
        VisitSessionItem item = item(session, linkedRequest("uh-child", tenant.user(), city, team,
                listing(locality, "uh-child", lessorUser.user().getId(), profile), session, owner.user(),
                VisitRequestStatus.COORDINATING), 1);

        for (String path : List.of(requestPath(request.getId()), itemPath(session.getId(), item.getId()))) {
            HttpResponse<String> response = send("GET", path, null);
            assertEquals(401, response.statusCode(), response.body());
            assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                    "an unauthenticated lessor-contact response must never be cacheable");
            assertFalse(response.body().contains("9822200009"), "an unauthenticated response must never leak contact");
        }
    }

    @Test
    void requestSessionLinkageSerializesWithStandaloneRequestReveal() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("lk"), "Linkage City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("lk-team"), "Linkage Team", true));
        Locality locality = locality(city, "lk");
        Actor tenant = actor("lk-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("lk-lessor", Role.ROLE_TENANT, null, false);
        Actor oe = actor("lk-oe", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(oe, team);
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Linkage Lessor", "9822200005",
                LessorSourceType.SELF_SERVICE);
        VisitSession session = draftSession("lk", tenant.user(), city, team, oe.user());

        // Reveal wins: the reveal holds the Request-root FOR UPDATE lock, so a concurrent linkage cannot
        // commit underneath it. The linkage only lands once the reveal transaction has committed.
        PropertyVisitRequest request = standaloneRequest("lk", tenant.user(), city, team,
                listing(locality, "lk", lessorUser.user().getId(), profile), oe.user());
        ListingReadBarrier.arm();
        AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
        Thread revealThread = startParkedReveal(requestPath(request.getId()), oe.token(), reveal);
        int revealPid = parkedRevealBackendPid("Request->Session linkage");

        MutationTrace linkageTrace = new MutationTrace("Request->Session linkage");
        AtomicInteger linkagePid = new AtomicInteger();
        AtomicReference<Throwable> linkageFailure = new AtomicReference<>();
        CountDownLatch linkageDone = runAsync(() -> {
            linkageTrace.stage(MutationTrace.WORKER_STARTED);
            commitLinkRequestToSession(linkageTrace, linkagePid, request.getId(), session.getId());
        }, linkageFailure);
        BlockedBackend blocked = awaitCompetingLockWait("Request->Session linkage", revealPid, linkageTrace,
                "property_visit_requests");
        assertEquals(linkagePid.get(), blocked.pid(),
                "the blocked backend must be the raw competing connection: " + blocked);

        releaseAndAwait(revealThread, reveal);
        assertEquals(200, reveal.get().statusCode(), reveal.get().body());
        assertEquals("Linkage Lessor", mapper.readTree(reveal.get().body()).path("name").asText());
        assertTrue(linkageDone.await(15, TimeUnit.SECONDS), "the linkage must proceed once the reveal commits");
        assertNull(linkageFailure.get(), String.valueOf(linkageFailure.get()));
        linkageTrace.stage("mutation completed");
        assertEquals(session.getId(), jdbc.queryForObject(
                "select session_id from property_visit_requests where id = ?", Long.class, request.getId()));
        assertEquals(1, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));

        // Once the linkage has won, the standalone Request route is a privacy-safe 404 and never re-audits.
        assertPrivacySafeDenial(send("GET", requestPath(request.getId()), oe.token()), 404, "9822200005");
        assertEquals(1, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));

        PropertyVisitRequest second = standaloneRequest("lk2", tenant.user(), city, team,
                listing(locality, "lk2", lessorUser.user().getId(), profile), oe.user());
        commitLinkRequestToSession(new MutationTrace("direct linkage"), new AtomicInteger(), second.getId(),
                session.getId());
        assertPrivacySafeDenial(send("GET", requestPath(second.getId()), oe.token()), 404, "9822200005");
        assertEquals(0, auditCount("PROPERTY_VISIT_REQUEST", second.getId()));
    }

    @Test
    void sessionItemRemovalSerializesWithItemContactReveal() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("rm"), "Removal City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("rm-team"), "Removal Team", true));
        Locality locality = locality(city, "rm");
        Actor tenant = actor("rm-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("rm-lessor", Role.ROLE_TENANT, null, false);
        // The real removal command requires Pathome Admin operations authority.
        Actor oe = actor("rm-oe", Role.ROLE_ADMIN, "OPERATIONS", true);
        teamGrant(oe, team);
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Removal Lessor", "9822200006",
                LessorSourceType.SELF_SERVICE);
        VisitSession session = draftSession("rm", tenant.user(), city, team, oe.user());
        VisitSessionItem item = removableItem(session, linkedRequest("rm-child", tenant.user(), city, team,
                listing(locality, "rm-child", lessorUser.user().getId(), profile), session, oe.user(),
                VisitRequestStatus.COORDINATING), 1);

        // Reveal wins: the reveal holds the Session root lock, so the real removal command cannot interleave.
        ListingReadBarrier.arm();
        AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
        Thread revealThread = startParkedReveal(itemPath(session.getId(), item.getId()), oe.token(), reveal);
        int revealPid = parkedRevealBackendPid("item removal");
        long expectedSessionVersion = sessionVersion(session.getId());
        MutationTrace removalTrace = new MutationTrace("item removal");
        AtomicReference<Throwable> removalFailure = new AtomicReference<>();
        CountDownLatch removalDone = runAsync(() -> {
            removalTrace.stage(MutationTrace.WORKER_STARTED);
            visitOperations.removeItem(oe.user().getId(), session.getId(), item.getId(),
                    new RemoveVisitSessionItemCommand(expectedSessionVersion, "TEST_REMOVAL"));
        }, removalFailure);
        awaitCompetingLockWait("item removal", revealPid, removalTrace, "visit_sessions");

        releaseAndAwait(revealThread, reveal);
        assertEquals(200, reveal.get().statusCode(), reveal.get().body());
        assertEquals("Removal Lessor", mapper.readTree(reveal.get().body()).path("name").asText());
        assertTrue(removalDone.await(15, TimeUnit.SECONDS), "the removal must proceed once the reveal commits");
        assertNull(removalFailure.get(), String.valueOf(removalFailure.get()));
        removalTrace.stage("mutation completed");
        assertPrivacySafeDenial(send("GET", itemPath(session.getId(), item.getId()), oe.token()), 404, "9822200006");

        // Removal wins on a second item: once committed, that item can never disclose contact again.
        VisitSession secondSession = draftSession("rm2", tenant.user(), city, team, oe.user());
        VisitSessionItem secondItem = removableItem(secondSession, linkedRequest("rm-child-2", tenant.user(), city,
                team, listing(locality, "rm-child-2", lessorUser.user().getId(), profile), secondSession,
                oe.user(), VisitRequestStatus.COORDINATING), 1);
        visitOperations.removeItem(oe.user().getId(), secondSession.getId(), secondItem.getId(),
                new RemoveVisitSessionItemCommand(sessionVersion(secondSession.getId()), "TEST_REMOVAL"));
        assertPrivacySafeDenial(send("GET", itemPath(secondSession.getId(), secondItem.getId()), oe.token()),
                404, "9822200006");
    }

    @Test
    void grantRevocationSerializesWithStandaloneRequestReveal() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("rv"), "Revocation City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("rv-team"), "Revocation Team", true));
        Locality locality = locality(city, "rv");
        Actor tenant = actor("rv-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("rv-lessor", Role.ROLE_TENANT, null, false);
        Actor oe = actor("rv-oe", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor admin = actor("rv-admin", Role.ROLE_ADMIN, "OPERATIONS", true);
        StaffAccessGrant grant = teamGrant(oe, team);
        globalAdminGrant(admin);
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Revocation Lessor", "9822200002",
                LessorSourceType.SELF_SERVICE);
        PropertyVisitRequest request = standaloneRequest("rv", tenant.user(), city, team,
                listing(locality, "rv", lessorUser.user().getId(), profile), oe.user());

        ListingReadBarrier.arm();
        AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
        Thread revealThread = startParkedReveal(requestPath(request.getId()), oe.token(), reveal);
        int revealPid = parkedRevealBackendPid("grant revocation");

        MutationTrace revocationTrace = new MutationTrace("grant revocation");
        AtomicReference<Throwable> revocationFailure = new AtomicReference<>();
        CountDownLatch revocationDone = runAsync(() -> {
            revocationTrace.stage(MutationTrace.WORKER_STARTED);
            staffAdmin.revokeGrant(admin.user().getId(), grant.getId(), "TEST_REVOKE");
        }, revocationFailure);
        awaitCompetingLockWait("grant revocation", revealPid, revocationTrace, "supported_cities");

        releaseAndAwait(revealThread, reveal);
        assertEquals(200, reveal.get().statusCode(), reveal.get().body());
        assertTrue(revocationDone.await(15, TimeUnit.SECONDS), "the revocation must proceed once the reveal commits");
        assertNull(revocationFailure.get(), String.valueOf(revocationFailure.get()));
        revocationTrace.stage("mutation completed");

        // The committed revocation is never bypassed by the authorization established when the reveal began.
        assertPrivacySafeDenial(send("GET", requestPath(request.getId()), oe.token()), 403, "9822200002");
        assertEquals(1, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));
    }

    @Test
    void staffDeactivationSerializesWithStandaloneRequestReveal() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("sd"), "Staff City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("sd-team"), "Staff Team", true));
        Locality locality = locality(city, "sd");
        Actor tenant = actor("sd-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("sd-lessor", Role.ROLE_TENANT, null, false);
        Actor oe = actor("sd-oe", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor admin = actor("sd-admin", Role.ROLE_ADMIN, "OPERATIONS", true);
        teamGrant(oe, team);
        globalAdminGrant(admin);
        long profileVersion = employeeVersion(oe.user().getId());
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Staff Lessor", "9822200003",
                LessorSourceType.SELF_SERVICE);
        PropertyVisitRequest request = standaloneRequest("sd", tenant.user(), city, team,
                listing(locality, "sd", lessorUser.user().getId(), profile), oe.user());

        ListingReadBarrier.arm();
        AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
        Thread revealThread = startParkedReveal(requestPath(request.getId()), oe.token(), reveal);
        int revealPid = parkedRevealBackendPid("staff deactivation");

        MutationTrace deactivationTrace = new MutationTrace("staff deactivation");
        AtomicReference<Throwable> deactivationFailure = new AtomicReference<>();
        CountDownLatch deactivationDone = runAsync(() -> {
            deactivationTrace.stage(MutationTrace.WORKER_STARTED);
            staffAdmin.deactivateStaff(admin.user().getId(), oe.user().getId(),
                    new StaffStateCommand(profileVersion, "TEST_DEACTIVATE"));
        }, deactivationFailure);
        awaitCompetingLockWait("staff deactivation", revealPid, deactivationTrace, "supported_cities");

        releaseAndAwait(revealThread, reveal);
        assertEquals(200, reveal.get().statusCode(), reveal.get().body());
        assertTrue(deactivationDone.await(15, TimeUnit.SECONDS),
                "the staff deactivation must proceed once the reveal commits");
        assertNull(deactivationFailure.get(), String.valueOf(deactivationFailure.get()));
        deactivationTrace.stage("mutation completed");

        // An inactive staff profile can never back a successful reveal.
        assertPrivacySafeDenial(send("GET", requestPath(request.getId()), oe.token()), 403, "9822200003");
        assertEquals(false, jdbc.queryForObject("select staff_active from employee_profiles where user_id = ?",
                Boolean.class, oe.user().getId()));
    }

    @Test
    void teamDeactivationSerializesAndFreshActiveTeamCheckFailsClosed() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("td"), "Team City", true));
        OperatingTeam liveTeam = teams.saveAndFlush(new OperatingTeam(city, code("td-live"), "Team Live", true));
        OperatingTeam idleTeam = teams.saveAndFlush(new OperatingTeam(city, code("td-idle"), "Team Idle", true));
        Locality locality = locality(city, "td");
        Actor tenant = actor("td-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("td-lessor", Role.ROLE_TENANT, null, false);
        Actor coordinator = actor("td-coordinator", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor cityAdmin = actor("td-admin", Role.ROLE_ADMIN, "OPERATIONS", true);
        teamGrant(coordinator, liveTeam);
        // A second active-Team grant keeps coarse endpoint eligibility usable after the idle Team retires, so
        // the retired-Team case exercises the deep fresh active-Team check rather than the 403 eligibility gate.
        teamGrant(coordinator, idleTeam);
        cityGrant(cityAdmin, city);
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Team Lessor", "9822200004",
                LessorSourceType.SELF_SERVICE);

        VisitSession session = draftSession("td", tenant.user(), city, liveTeam, coordinator.user());
        VisitSessionItem item = item(session, linkedRequest("td-child", tenant.user(), city, liveTeam,
                listing(locality, "td-child", lessorUser.user().getId(), profile), session, coordinator.user(),
                VisitRequestStatus.COORDINATING), 1);

        ListingReadBarrier.arm();
        AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
        Thread revealThread = startParkedReveal(itemPath(session.getId(), item.getId()), coordinator.token(), reveal);
        int revealPid = parkedRevealBackendPid("Team deactivation");
        long cityVersion = cityVersion(city.getId());
        long liveTeamVersion = teamVersion(liveTeam.getId());
        MutationTrace deactivationTrace = new MutationTrace("Team deactivation");
        AtomicReference<Throwable> deactivationFailure = new AtomicReference<>();
        CountDownLatch deactivationDone = runAsync(() -> {
            deactivationTrace.stage(MutationTrace.WORKER_STARTED);
            ownership.deactivateTeam(cityAdmin.user().getId(), liveTeam.getId(), cityVersion, liveTeamVersion,
                    "TEST_TEAM_OFF");
        }, deactivationFailure);
        awaitCompetingLockWait("Team deactivation", revealPid, deactivationTrace, "supported_cities");

        releaseAndAwait(revealThread, reveal);
        assertEquals(200, reveal.get().statusCode(), reveal.get().body());
        assertTrue(deactivationDone.await(15, TimeUnit.SECONDS), "the deactivation attempt must resolve after the reveal");
        deactivationTrace.stage("mutation resolved");
        // The real command then runs, and the drain guard refuses to retire a Team that still holds live work.
        assertInstanceOf(VisitOperationsConflictException.class, deactivationFailure.get(),
                "a Team holding live work must never be retired: " + deactivationFailure.get());
        assertEquals(true, jdbc.queryForObject("select active from operating_teams where id = ?",
                Boolean.class, liveTeam.getId()));

        // Retire the idle Team through the real command while it holds no operational work, then plant a
        // currently owned Request in the retired Team (fixtures write rows directly).
        ownership.deactivateTeam(cityAdmin.user().getId(), idleTeam.getId(), cityVersion(city.getId()),
                teamVersion(idleTeam.getId()), "TEST_TEAM_IDLE_OFF");
        assertEquals(false, jdbc.queryForObject("select active from operating_teams where id = ?",
                Boolean.class, idleTeam.getId()));
        PropertyVisitRequest retiredTarget = standaloneRequest("td-retired", tenant.user(), city, idleTeam,
                listing(locality, "td-retired", lessorUser.user().getId(), profile), coordinator.user());
        assertPrivacySafeDenial(send("GET", requestPath(retiredTarget.getId()), coordinator.token()), 404,
                "9822200004");

        // Control: the identical shape in the still-active Team discloses contact, proving the 404 is exactly
        // the fresh active-Team check.
        PropertyVisitRequest activeTarget = standaloneRequest("td-active", tenant.user(), city, liveTeam,
                listing(locality, "td-active", lessorUser.user().getId(), profile), coordinator.user());
        HttpResponse<String> control = send("GET", requestPath(activeTarget.getId()), coordinator.token());
        assertEquals(200, control.statusCode(), control.body());
        assertEquals("Team Lessor", mapper.readTree(control.body()).path("name").asText());
    }

    @Test
    void cityDeactivationSerializesAndFreshActiveCityCheckFailsClosed() throws Exception {
        SupportedCity liveCity = cities.saveAndFlush(new SupportedCity(code("cd"), "City Live", true));
        OperatingTeam liveTeam = teams.saveAndFlush(new OperatingTeam(liveCity, code("cd-live"), "City Live Team", true));
        Locality liveLocality = locality(liveCity, "cd");
        Actor tenant = actor("cd-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("cd-lessor", Role.ROLE_TENANT, null, false);
        Actor coordinator = actor("cd-coordinator", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor cityAdmin = actor("cd-admin", Role.ROLE_ADMIN, "OPERATIONS", true);
        teamGrant(coordinator, liveTeam);
        cityGrant(cityAdmin, liveCity);
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "City Lessor", "9822200008",
                LessorSourceType.SELF_SERVICE);

        VisitSession session = draftSession("cd", tenant.user(), liveCity, liveTeam, coordinator.user());
        VisitSessionItem item = item(session, linkedRequest("cd-child", tenant.user(), liveCity, liveTeam,
                listing(liveLocality, "cd-child", lessorUser.user().getId(), profile), session, coordinator.user(),
                VisitRequestStatus.COORDINATING), 1);

        // A live reveal holds the City/Team guard rows, so a real City deactivation must wait for it inside
        // PostgreSQL before the drain guard then refuses to retire a City that still holds an active Team.
        ListingReadBarrier.arm();
        AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
        Thread revealThread = startParkedReveal(itemPath(session.getId(), item.getId()), coordinator.token(), reveal);
        int revealPid = parkedRevealBackendPid("City deactivation race");
        MutationTrace deactivationTrace = new MutationTrace("City deactivation");
        AtomicReference<Throwable> deactivationFailure = new AtomicReference<>();
        CountDownLatch deactivationDone = runAsync(() -> {
            deactivationTrace.stage(MutationTrace.WORKER_STARTED);
            ownership.deactivateCity(cityAdmin.user().getId(), liveCity.getId(),
                    cityVersion(liveCity.getId()), "TEST_CITY_OFF");
        }, deactivationFailure);
        awaitCompetingLockWait("City deactivation race", revealPid, deactivationTrace, "supported_cities");

        releaseAndAwait(revealThread, reveal);
        assertEquals(200, reveal.get().statusCode(), reveal.get().body());
        assertTrue(deactivationDone.await(15, TimeUnit.SECONDS), "the deactivation attempt must resolve after the reveal");
        deactivationTrace.stage("mutation resolved");
        assertInstanceOf(VisitOperationsConflictException.class, deactivationFailure.get(),
                "a City with an active Team must never be retired: " + deactivationFailure.get());
        assertEquals(true, jdbc.queryForObject("select active from supported_cities where id = ?",
                Boolean.class, liveCity.getId()));

        // ---------------------------------------------------------------------------------------------
        // Retired-City negative. The actor is an authorized, active coordinator holding an effective
        // OPS_COORDINATE@TEAM grant for the exact target Team, and the target Request is correctly owned by
        // that same coordinator, so the only change before the denial is the canonical City retiring.
        // ---------------------------------------------------------------------------------------------
        SupportedCity controlCity = cities.saveAndFlush(new SupportedCity(code("cd-ctl"), "City Control", true));
        OperatingTeam controlTeam = teams.saveAndFlush(
                new OperatingTeam(controlCity, code("cd-ctl-t"), "City Control Team", true));
        SupportedCity targetCity = cities.saveAndFlush(new SupportedCity(code("cd-off"), "City Retired", true));
        OperatingTeam targetTeam = teams.saveAndFlush(
                new OperatingTeam(targetCity, code("cd-off-t"), "City Retired Team", true));
        // The exact target-Team grant is the one under test. A second active-Team grant keeps only the coarse
        // eligibility gate usable after the target City retires, so the retired-City case exercises the deep
        // fresh active-City check rather than the 403 eligibility gate (same rationale as the Team case above).
        teamGrant(coordinator, controlTeam);
        teamGrant(coordinator, targetTeam);
        cityGrant(cityAdmin, targetCity);
        Locality targetLocality = locality(targetCity, "cdoff");
        PropertyVisitRequest target = standaloneRequest("cd-retired", tenant.user(), targetCity, targetTeam,
                listing(targetLocality, "cd-retired", lessorUser.user().getId(), profile), coordinator.user());

        // Positive control: while the target City and Team are active and the Request is correctly owned by the
        // exact-Team-granted coordinator, contact retrieval succeeds and writes the real reveal audit row.
        HttpResponse<String> granted = send("GET", requestPath(target.getId()), coordinator.token());
        assertEquals(200, granted.statusCode(), granted.body());
        JsonNode grantedBody = mapper.readTree(granted.body());
        assertEquals("City Lessor", grantedBody.path("name").asText());
        assertEquals("+91 9822200008", grantedBody.path("mobile").asText());
        assertEquals("CONTACT_AVAILABLE", grantedBody.path("status").asText());
        assertEquals(1, auditCount("PROPERTY_VISIT_REQUEST", target.getId()));

        // Historical/idle-work construction (fixture, not a production transition): the real Team/City retirement
        // commands run under operational drain guards that refuse to retire scope still holding nonterminal work,
        // so production retirement never coexists with live work. Recording the Request as terminal lets the
        // legitimate retirement chain run; it is restored to its approved RECEIVED state immediately afterwards.
        jdbc.update("update property_visit_requests set status = 'CANCELLED' where id = ?", target.getId());
        ownership.deactivateTeam(cityAdmin.user().getId(), targetTeam.getId(),
                cityVersion(targetCity.getId()), teamVersion(targetTeam.getId()), "TEST_CITY_TEAM_OFF");
        ownership.deactivateCity(cityAdmin.user().getId(), targetCity.getId(),
                cityVersion(targetCity.getId()), "TEST_CITY_OFF");

        // Historical/idle-work construction, clearly not a production transition: the canonical command chain
        // cannot leave an active Team inside an inactive City (City deactivation requires every Team inactive).
        // Re-activating the Team row removes the Team predicate as an alternative explanation and restoring the
        // Request to RECEIVED rejoins the approved read path, so the inactive City is the sole failing condition.
        jdbc.update("update operating_teams set active = true where id = ?", targetTeam.getId());
        jdbc.update("update property_visit_requests set status = 'RECEIVED' where id = ?", target.getId());
        assertEquals(false, jdbc.queryForObject("select active from supported_cities where id = ?",
                Boolean.class, targetCity.getId()), "the canonical City must be retired");
        assertEquals(true, jdbc.queryForObject("select active from operating_teams where id = ?",
                Boolean.class, targetTeam.getId()), "the Team predicate must not be an alternative explanation");
        assertEquals(targetCity.getId(), jdbc.queryForObject("select city_id from operating_teams where id = ?",
                Long.class, targetTeam.getId()), "the Team must still belong to the retired City");
        // The actor's authorization was never revoked or altered: the exact target-Team grant is still current
        // and the staff profile is still active, so the denial below cannot be lost access.
        assertEquals(1, jdbc.queryForObject("select count(*) from staff_access_grants "
                + "where user_id = ? and team_id = ? and revoked_at is null and expires_at is null",
                Integer.class, coordinator.user().getId(), targetTeam.getId()),
                "the exact target-Team grant must still be present, current and unrevoked");
        assertEquals(true, jdbc.queryForObject("select staff_active from employee_profiles where user_id = ?",
                Boolean.class, coordinator.user().getId()), "the coordinator must still be active staff");

        // The exact same owned Request, exact Team grant and ownership context now fail closed with a
        // privacy-safe 404 that can only be the fresh active-City predicate: the actor provably had access
        // to this very Request moments earlier, so the denial is the retired City and not lost authorization.
        HttpResponse<String> denied = send("GET", requestPath(target.getId()), coordinator.token());
        assertPrivacySafeDenial(denied, 404, "9822200008");
        assertEquals(1, auditCount("PROPERTY_VISIT_REQUEST", target.getId()),
                "a City-inactive denial must not write a reveal audit row");

        // Control: the coordinator's authority is otherwise intact, so the 404 above is City-scoped, not global.
        PropertyVisitRequest control = standaloneRequest("cd-control", tenant.user(), controlCity, controlTeam,
                listing(locality(controlCity, "cdctl"), "cd-control", lessorUser.user().getId(), profile),
                coordinator.user());
        HttpResponse<String> controlResponse = send("GET", requestPath(control.getId()), coordinator.token());
        assertEquals(200, controlResponse.statusCode(), controlResponse.body());
        assertEquals("City Lessor", mapper.readTree(controlResponse.body()).path("name").asText());
    }

    @Test
    void operationalOwnershipServiceTransferSerializesWithItemContactReveal() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("tr"), "Transfer City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("tr-team"), "Transfer Team", true));
        Locality locality = locality(city, "tr");
        Actor tenant = actor("tr-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("tr-lessor", Role.ROLE_TENANT, null, false);
        Actor owner = actor("tr-owner", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor successor = actor("tr-successor", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor supervisor = actor("tr-supervisor", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(owner, team);
        teamGrant(successor, team);
        teamGrant(supervisor, team, StaffCapability.OPS_SUPERVISE);
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Transfer Lessor", "9822200001",
                LessorSourceType.SELF_SERVICE);

        VisitSession session = draftSession("tr", tenant.user(), city, team, owner.user());
        VisitSessionItem item = item(session, linkedRequest("tr-child", tenant.user(), city, team,
                listing(locality, "tr-child", lessorUser.user().getId(), profile), session, owner.user(),
                VisitRequestStatus.COORDINATING), 1);
        long expectedSessionVersion = sessionVersion(session.getId());
        Map<Long, Long> expectedRequests = linkedVersions(session.getId());

        ListingReadBarrier.arm();
        AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
        Thread revealThread = startParkedReveal(itemPath(session.getId(), item.getId()), owner.token(), reveal);
        int revealPid = parkedRevealBackendPid("ownership-service transfer");

        MutationTrace transferTrace = new MutationTrace("ownership-service transfer");
        AtomicReference<Throwable> transferFailure = new AtomicReference<>();
        CountDownLatch transferDone = runAsync(() -> {
            transferTrace.stage(MutationTrace.WORKER_STARTED);
            ownership.assignCoordinator(supervisor.user().getId(), session.getId(), expectedSessionVersion,
                    expectedRequests, successor.user().getId(), "TEST_TRANSFER");
        }, transferFailure);
        awaitCompetingLockWait("ownership-service transfer", revealPid, transferTrace, "visit_sessions");

        releaseAndAwait(revealThread, reveal);
        assertEquals(200, reveal.get().statusCode(), reveal.get().body());
        assertEquals("Transfer Lessor", mapper.readTree(reveal.get().body()).path("name").asText());
        assertTrue(transferDone.await(15, TimeUnit.SECONDS),
                "the ownership-service transfer must proceed once the reveal commits");
        assertNull(transferFailure.get(), String.valueOf(transferFailure.get()));
        transferTrace.stage("mutation completed");

        // The committed production transfer flips ownership in one serialized step.
        assertPrivacySafeDenial(send("GET", itemPath(session.getId(), item.getId()), owner.token()), 404,
                "9822200001");
        HttpResponse<String> successorReveal = send("GET", itemPath(session.getId(), item.getId()), successor.token());
        assertEquals(200, successorReveal.statusCode(), successorReveal.body());
        assertEquals("Transfer Lessor", mapper.readTree(successorReveal.body()).path("name").asText());
    }

    @Test
    void grantExpiryIsEvaluatedFromFreshDatabaseTimeWhileARevealWaitsOnLocks() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("ex"), "Expiry City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("ex-team"), "Expiry Team", true));
        Locality locality = locality(city, "ex");
        Actor tenant = actor("ex-tenant", Role.ROLE_TENANT, null, false);
        Actor lessorUser = actor("ex-lessor", Role.ROLE_TENANT, null, false);
        Actor expiring = actor("ex-oe", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor stable = actor("ex-oe-stable", Role.ROLE_TENANT, "OPERATIONS", true);
        StaffAccessGrant stableGrant = teamGrant(stable, team);
        Instant expiresAt = databaseNow().plusSeconds(2);
        StaffAccessGrant expiringGrant = grants.saveAndFlush(new StaffAccessGrant(expiring.user(),
                StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(60),
                expiresAt, null, Instant.now(), "TEST_CONTACT", StaffGrantProvisioningSource.ADMIN_API));
        LessorProfile profile = lessorProfile(lessorUser.user().getId(), "Expiry Lessor", "9822200007",
                LessorSourceType.SELF_SERVICE);
        PropertyVisitRequest expiringTarget = standaloneRequest("ex", tenant.user(), city, team,
                listing(locality, "ex", lessorUser.user().getId(), profile), expiring.user());
        PropertyVisitRequest stableTarget = standaloneRequest("ex-stable", tenant.user(), city, team,
                listing(locality, "ex-stable", lessorUser.user().getId(), profile), stable.user());

        // Control: the reveal's coarse eligibility passes while its grant is effective, then PostgreSQL itself
        // reports it genuinely waiting on the grant row lock held by an independent transaction, not merely slow.
        try (HeldRowLock hold = holdGrantRow(stableGrant.getId())) {
            AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
            Thread thread = startReveal(requestPath(stableTarget.getId()), stable.token(), reveal);
            BlockedBackend blocked = awaitLockWaitBlockedBy(hold.pid(), "grant-row lock control");
            assertTrue(blocked.query().contains("staff_access_grants"),
                    "the control reveal must be waiting on the grant row lock: " + blocked.query());
            System.out.println("[A1a blocking evidence] grant-row lock control: reveal backend pid " + blocked.pid()
                    + " (" + blocked.state() + ") waiting on " + blocked.waitEventType() + "/" + blocked.waitEvent()
                    + " blocked by [" + blocked.blockers() + "] while running: " + blocked.query().trim());
            assertNull(reveal.get(), "the control reveal must still be blocked while the holder transaction is open");
            hold.rollback();
            thread.join(15_000);
            assertEquals(200, reveal.get().statusCode(), reveal.get().body());
        }

        // The reveal's coarse eligibility passes while the grant is effective and then parks on the grant row
        // lock. The authoritative database clock passes the expiry while it waits, and authorization is
        // re-evaluated from fresh database time once the lock is released.
        try (HeldRowLock hold = holdGrantRow(expiringGrant.getId())) {
            AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
            Thread thread = startReveal(requestPath(expiringTarget.getId()), expiring.token(), reveal);
            BlockedBackend blocked = awaitLockWaitBlockedBy(hold.pid(), "grant-row lock expiry");
            assertTrue(blocked.query().contains("staff_access_grants"),
                    "the reveal must be waiting on the grant row lock: " + blocked.query());
            System.out.println("[A1a blocking evidence] grant-row lock expiry: reveal backend pid " + blocked.pid()
                    + " (" + blocked.state() + ") waiting on " + blocked.waitEventType() + "/" + blocked.waitEvent()
                    + " blocked by [" + blocked.blockers() + "] while running: " + blocked.query().trim());
            assertNull(reveal.get(), "the reveal must be parked on the grant row lock while the grant is valid");
            awaitDatabaseTimeAfter(expiresAt);
            hold.rollback();
            thread.join(15_000);
            assertPrivacySafeDenial(reveal.get(), 404, "9822200007");
        }
        assertEquals(0, auditCount("PROPERTY_VISIT_REQUEST", expiringTarget.getId()));
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures and helpers
    // ---------------------------------------------------------------------------------------------

    private JsonNode requestContact(Actor owner, Actor tenant, SupportedCity city, OperatingTeam team,
            Locality locality, String suffix, Listing listing) throws Exception {
        PropertyVisitRequest request = requestContactEntity(tenant, city, team, suffix, listing, owner.user());
        return read("GET", requestPath(request.getId()), owner.token());
    }

    private PropertyVisitRequest requestContactEntity(Actor tenant, SupportedCity city, OperatingTeam team,
            String suffix, Listing listing, User coordinator) {
        return standaloneRequest(suffix, tenant.user(), city, team, listing, coordinator);
    }

    private PropertyVisitRequest standaloneRequest(String suffix, User tenant, SupportedCity city,
            OperatingTeam team, Listing listing, User coordinator) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(listing);
        request.setStatus(VisitRequestStatus.RECEIVED);
        request.setSupportedCity(city);
        request.setOperatingTeam(team);
        request.setOperationalScopeReady(true);
        request.setCoordinator(coordinator);
        request.setTenantNote("Private tenant note " + suffix);
        return requests.saveAndFlush(request);
    }

    private void assertUnavailable(JsonNode view) {
        assertEquals("CONTACT_UNAVAILABLE", view.path("status").asText());
        assertTrue(view.path("name").isNull(), "an unavailable contact must not leak a partial name");
        assertTrue(view.path("mobile").isNull(), "an unavailable contact must not leak a partial mobile");
    }

    private void assertNoPii(HttpResponse<String> response) {
        assertTrue(response.statusCode() == 403 || response.statusCode() == 404,
                "a lost authorization must be 403 or 404, was " + response.statusCode());
        assertFalse(response.body().contains("9811199999"), "a lost authorization must not leak contact");
    }

    private int auditCount(String targetType, long targetId) {
        Integer count = jdbc.queryForObject("select count(*) from operational_audit_events "
                + "where action_code = 'LESSOR_CONTACT_REVEALED' and target_type = ? and target_id = ?",
                Integer.class, targetType, targetId);
        return count == null ? 0 : count;
    }

    private String requestPath(long requestId) {
        return "/api/v1/operations/visit-requests/" + requestId + "/lessor-contact";
    }

    private String itemPath(long sessionId, long itemId) {
        return "/api/v1/operations/visit-sessions/" + sessionId + "/items/" + itemId + "/lessor-contact";
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return new HashSet<>(fields);
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
            HttpRequest httpRequest = request.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
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
        user.setEmail("lessor-contact-" + label + "-" + UUID.randomUUID() + "@example.test");
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

    private StaffAccessGrant teamGrant(Actor actor, OperatingTeam team) {
        return teamGrant(actor, team, StaffCapability.OPS_COORDINATE);
    }

    private StaffAccessGrant teamGrant(Actor actor, OperatingTeam team, StaffCapability capability) {
        return grants.saveAndFlush(new StaffAccessGrant(actor.user(), capability, StaffScopeType.TEAM, null, team,
                Instant.now().minusSeconds(60), null, null, Instant.now(), "TEST_CONTACT",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private StaffAccessGrant cityGrant(Actor actor, SupportedCity city) {
        return grants.saveAndFlush(new StaffAccessGrant(actor.user(), StaffCapability.CITY_TEAM_ADMIN,
                StaffScopeType.CITY, city, null, Instant.now().minusSeconds(60), null, null, Instant.now(),
                "TEST_CITY_ADMIN", StaffGrantProvisioningSource.ADMIN_API));
    }

    private StaffAccessGrant globalAdminGrant(Actor actor) {
        return grants.saveAndFlush(new StaffAccessGrant(actor.user(), StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null, null, Instant.now(),
                "TEST_GLOBAL_ADMIN", StaffGrantProvisioningSource.ADMIN_API));
    }

    /** A directly requested item that the real removal command accepts (recorded unavailable first). */
    private VisitSessionItem removableItem(VisitSession session, PropertyVisitRequest source, int position) {
        VisitSessionItem item = item(session, source, position);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.UNAVAILABLE);
        return visitSessionItems.saveAndFlush(item);
    }

    private LessorProfile lessorProfile(Long linkedUserId, String name, String mobile, LessorSourceType source) {
        return lessorProfiles.saveAndFlush(new LessorProfile(null, linkedUserId, name, mobile, null, source));
    }

    private Locality locality(SupportedCity city, String prefix) {
        Locality locality = new Locality(city.getDisplayName(), prefix + " sector " + UUID.randomUUID(),
                null, null, null);
        locality.setSupportedCity(city);
        return localities.saveAndFlush(locality);
    }

    private Listing listing(Locality locality, String title, Long ownerUserId, LessorProfile profile) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Listing " + title + " " + UUID.randomUUID());
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
        listing.setOwnerUserId(ownerUserId);
        listing.setOwnerName("Legacy Owner Name");
        listing.setOwnerPhoneNumber(LEGACY_PHONE);
        listing.setLessorProfileId(profile == null ? null : profile.getId());
        return listings.saveAndFlush(listing);
    }

    private PropertyVisitRequest linkedRequest(String suffix, User tenant, SupportedCity city, OperatingTeam team,
            Listing listing, VisitSession session, User coordinator, VisitRequestStatus status) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(listing);
        request.setStatus(status);
        request.setSession(session);
        request.setSupportedCity(city);
        request.setOperatingTeam(team);
        request.setOperationalScopeReady(true);
        request.setCoordinator(coordinator);
        request.setTenantNote("Private tenant note " + suffix);
        return requests.saveAndFlush(request);
    }

    private VisitSessionItem item(VisitSession session, PropertyVisitRequest source, int position) {
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(session);
        item.setListing(source.getListing());
        item.setPosition(position);
        item.setSourceRequest(source);
        item.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.PENDING);
        return visitSessionItems.saveAndFlush(item);
    }

    private VisitSession draftSession(String suffix, User tenant, SupportedCity city, OperatingTeam team,
            User coordinator) {
        VisitSession session = baseSession(tenant, city, team, coordinator);
        session.setStatus(VisitSessionStatus.DRAFT);
        return sessions.saveAndFlush(session);
    }

    /** Builds a valid Session in any approved or denied status; scheduled/started carry real reservations. */
    private VisitSession sessionInStatus(VisitSessionStatus status, User tenant, SupportedCity city,
            OperatingTeam team, User coordinator, User groundExecutive, long slotHours) {
        if (status != VisitSessionStatus.SCHEDULED && status != VisitSessionStatus.STARTED
                && status != VisitSessionStatus.REPAIR_REQUIRED) {
            VisitSession session = baseSession(tenant, city, team, coordinator);
            session.setStatus(status);
            return sessions.saveAndFlush(session);
        }
        VisitSession session = baseSession(tenant, city, team, coordinator);
        Instant scheduledAt = Instant.now().plusSeconds(slotHours * 3600);
        Instant reservedEnd = scheduledAt.plusSeconds(3600);
        session.setScheduledAt(scheduledAt);
        session.setZoneId("Asia/Kolkata");
        session.setDurationSnapshotMinutes(60);
        session.setReservedEndAt(reservedEnd);
        session.setRepresentative(groundExecutive);
        session.setAssignedAt(Instant.now());
        session.setStatus(status);
        if (status == VisitSessionStatus.STARTED) {
            session.setStartedAt(Instant.now());
            session.setExecutionDurationSnapshotMinutes(60);
            session.setExpectedEndAt(reservedEnd);
        }
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

    // --- Concurrency and ordering helpers --------------------------------------------------------

    private Thread startReveal(String path, String token, AtomicReference<HttpResponse<String>> holder) {
        Thread thread = new Thread(() -> holder.set(send("GET", path, token)), "lessor-contact-reveal");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Starts a reveal and blocks until it is provably parked inside its locked critical section. */
    private Thread startParkedReveal(String path, String token, AtomicReference<HttpResponse<String>> holder)
            throws InterruptedException {
        Thread thread = startReveal(path, token, holder);
        assertTrue(ListingReadBarrier.awaitEntered(10_000),
                "the reveal must reach its guarded critical section holding the root and guard locks");
        return thread;
    }

    private void releaseAndAwait(Thread revealThread, AtomicReference<HttpResponse<String>> holder)
            throws InterruptedException {
        ListingReadBarrier.release();
        revealThread.join(15_000);
        assertFalse(revealThread.isAlive(), "the reveal must complete once the barrier is released");
        assertNotNull(holder.get(), "the reveal must produce a response");
    }

    /**
     * Runs a mutation on an independent thread (and therefore its own database transaction) so the test can
     * prove it is serialized behind a reveal that already holds the authoritative root and guard locks.
     */
    private static CountDownLatch runAsync(Runnable action, AtomicReference<Throwable> failure) {
        CountDownLatch done = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }, "lessor-contact-mutation");
        thread.setDaemon(true);
        thread.start();
        return done;
    }

    /**
     * Captures the PostgreSQL backend serving the currently parked reveal. A parked reveal has an open
     * transaction whose statement has finished, so it is the unique {@code idle in transaction} client backend.
     */
    private int parkedRevealBackendPid(String label) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(POSTGRES_LOCK_WAIT_TIMEOUT_SECONDS);
        List<Integer> idle = List.of();
        while (System.nanoTime() < deadline) {
            idle = jdbc.queryForList("select pid from pg_stat_activity where datname = current_database() "
                    + "and backend_type = 'client backend' and state = 'idle in transaction' "
                    + "and pid <> pg_backend_pid() order by pid", Integer.class);
            if (idle.size() == 1) return idle.get(0);
            Thread.sleep(25);
        }
        fail("the parked reveal must be the unique idle-in-transaction backend for " + label
                + " but PostgreSQL reported " + idle + "; observed " + activitySnapshot());
        return -1;
    }

    /**
     * Deterministic replacement for an arbitrary sleep: polls PostgreSQL until the competing transaction is
     * provably blocked by {@code blockerPid}, then returns the blocked backend PostgreSQL itself reports.
     */
    private BlockedBackend awaitLockWaitBlockedBy(int blockerPid, String label) throws InterruptedException {
        String sql = "select a.pid, a.state, coalesce(a.wait_event_type, '-') as wait_event_type, "
                + "coalesce(a.wait_event, '-') as wait_event, (a.xact_start is not null) as in_transaction, "
                + "coalesce(array_to_string(pg_blocking_pids(a.pid), ','), '') as blockers, "
                + "coalesce(a.query, '') as query "
                + "from pg_stat_activity a "
                + "where a.datname = current_database() and a.pid <> pg_backend_pid() "
                + "and a.wait_event_type = 'Lock' and ? = any(pg_blocking_pids(a.pid))";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(POSTGRES_LOCK_WAIT_TIMEOUT_SECONDS);
        List<BlockedBackend> observed = List.of();
        while (System.nanoTime() < deadline) {
            observed = jdbc.query(sql, (rows, index) -> mapBlockedBackend(rows), blockerPid);
            if (observed.size() == 1) return observed.get(0);
            if (observed.size() > 1) {
                fail("PostgreSQL reported " + observed.size() + " backends blocked by " + blockerPid + " for "
                        + label + ", so the contention is ambiguous: " + observed);
            }
            Thread.sleep(25);
        }
        fail("PostgreSQL never reported a backend blocked by " + blockerPid + " for " + label
                + "; observed " + activitySnapshot());
        return null;
    }

    /**
     * Proves a competing worker actually reached contended database work: it started, entered an open
     * transaction, and issued the conflicting statement PostgreSQL now reports as waiting on a lock held by
     * {@code blockerPid}, targeting {@code expectedRelation}. The worker start, transaction entry, conflicting
     * operation and completion are recorded on the mutation trace so serialization is proven from observed
     * ordering rather than a fixed sleep. Returns the blocked backend PostgreSQL reported.
     */
    private BlockedBackend awaitCompetingLockWait(String label, int blockerPid, MutationTrace trace,
            String expectedRelation) throws InterruptedException {
        BlockedBackend blocked = awaitLockWaitBlockedBy(blockerPid, label);
        // Observed only after PostgreSQL reports the blocked statement, so the worker has provably started.
        assertTrue(trace.stages().contains(MutationTrace.WORKER_STARTED),
                "the competing worker must have started: " + trace.describe());
        assertTrue(blocked.inTransaction(),
                "the competing backend must be inside an open database transaction: " + blocked);
        assertFalse(blocked.query().isBlank(),
                "the competing backend must show the conflicting statement it is attempting: " + blocked);
        assertTrue(blocked.query().contains(expectedRelation),
                "the blocked statement must target " + expectedRelation + ": " + blocked.query());
        System.out.println("[A1a blocking evidence] " + label + ": competing backend pid " + blocked.pid()
                + " (" + blocked.state() + ") waiting on " + blocked.waitEventType() + "/" + blocked.waitEvent()
                + " blocked by [" + blocked.blockers() + "] while running: " + blocked.query().trim());
        trace.stage("PostgreSQL reported backend " + blocked.pid() + " waiting on " + blocked.waitEventType()
                + "/" + blocked.waitEvent() + " blocked by [" + blocked.blockers() + "] while attempting: "
                + blocked.query().trim());
        return blocked;
    }

    private static BlockedBackend mapBlockedBackend(ResultSet rows) throws java.sql.SQLException {
        return new BlockedBackend(rows.getInt("pid"), rows.getString("state"), rows.getString("wait_event_type"),
                rows.getString("wait_event"), rows.getBoolean("in_transaction"), rows.getString("blockers"),
                rows.getString("query"));
    }

    /** Compact, non-sensitive snapshot of every backend in the test database, for failure diagnostics. */
    private String activitySnapshot() {
        return jdbc.query("select pid, state, coalesce(wait_event_type, '-') as wait_event_type, "
                + "coalesce(wait_event, '-') as wait_event, "
                + "coalesce(array_to_string(pg_blocking_pids(pid), ','), '') as blockers, "
                + "left(coalesce(query, ''), 120) as query "
                + "from pg_stat_activity where datname = current_database() order by pid",
                (rows, index) -> "{" + rows.getInt("pid") + " " + rows.getString("state") + " "
                        + rows.getString("wait_event_type") + "/" + rows.getString("wait_event")
                        + " blockedBy=[" + rows.getString("blockers") + "] query=" + rows.getString("query") + "}")
                .toString();
    }

    private static int connectionBackendPid(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("select pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Records the observable stages of one competing (worker) transaction so the serialization proof names the
     * worker start, the database transaction entry, the conflicting operation, and the resolution explicitly.
     */
    static final class MutationTrace {
        static final String WORKER_STARTED = "worker started";
        private final String label;
        private final List<String> stages = Collections.synchronizedList(new ArrayList<>());

        MutationTrace(String label) {
            this.label = label;
        }

        void stage(String stage) {
            stages.add(stage);
        }

        List<String> stages() {
            synchronized (stages) {
                return new ArrayList<>(stages);
            }
        }

        String describe() {
            return label + " " + stages();
        }
    }

    /** A backend PostgreSQL itself reports as waiting on a lock, with its blocker relationship and statement. */
    private record BlockedBackend(int pid, String state, String waitEventType, String waitEvent,
            boolean inTransaction, String blockers, String query) {
        @Override
        public String toString() {
            return "pid=" + pid + " state=" + state + " wait=" + waitEventType + "/" + waitEvent
                    + " blockedBy=[" + blockers + "] inTransaction=" + inTransaction + " query=" + query;
        }
    }

    private void assertPrivacySafeDenial(HttpResponse<String> response, int expectedStatus, String pii) {
        assertNotNull(response, "the denied side must still produce a response");
        assertEquals(expectedStatus, response.statusCode(),
                "a lost authorization must fail closed with the frozen status");
        assertFalse(response.body().contains(pii), "a denied reveal must never disclose contact: " + response.body());
    }

    // --- Independent database transactions and clock helpers -------------------------------------

    private Instant databaseNow() {
        return jdbc.queryForObject("select clock_timestamp()", (rows, row) -> rows.getTimestamp(1).toInstant());
    }

    /** Blocks until the authoritative database clock passes {@code deadline} (bounded, database-driven). */
    private void awaitDatabaseTimeAfter(Instant deadline) throws InterruptedException {
        Instant bound = deadline.plusSeconds(20);
        while (databaseNow().isBefore(deadline)) {
            if (databaseNow().isAfter(bound)) fail("the authoritative database clock never passed " + deadline);
            Thread.sleep(50);
        }
    }

    private long sessionVersion(long sessionId) {
        return number("select version from visit_sessions where id = ?", sessionId);
    }

    private long teamVersion(long teamId) {
        return number("select version from operating_teams where id = ?", teamId);
    }

    private long cityVersion(long cityId) {
        return number("select version from supported_cities where id = ?", cityId);
    }

    private long employeeVersion(long userId) {
        return number("select version from employee_profiles where user_id = ?", userId);
    }

    private long number(String sql, long id) {
        Long value = jdbc.queryForObject(sql, Long.class, id);
        return value == null ? 0L : value;
    }

    private Map<Long, Long> linkedVersions(long sessionId) {
        return jdbc.query("select id, version from property_visit_requests where session_id = ? order by id",
                rows -> {
                    Map<Long, Long> versions = new LinkedHashMap<>();
                    while (rows.next()) versions.put(rows.getLong(1), rows.getLong(2));
                    return versions;
                }, sessionId);
    }

    /**
     * Commits a real Request->Session linkage on an independent connection, recording its transaction stages so
     * the test can prove the worker entered a database transaction and issued the conflicting statement.
     */
    private void commitLinkRequestToSession(MutationTrace trace, AtomicInteger backendPid, long requestId,
            long sessionId) {
        try (Connection connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD)) {
            connection.setSchema(SCHEMA);
            connection.setAutoCommit(false);
            backendPid.set(connectionBackendPid(connection));
            trace.stage("database transaction entered (competing backend " + backendPid.get() + ", autocommit=false)");
            try (var update = connection.prepareStatement("update property_visit_requests set session_id = ?, "
                    + "status = 'COORDINATING', version = version + 1 where id = ?")) {
                update.setLong(1, sessionId);
                update.setLong(2, requestId);
                trace.stage("conflicting UPDATE property_visit_requests issued");
                update.executeUpdate();
            }
            connection.commit();
            trace.stage("competing transaction committed");
        } catch (Exception failure) {
            throw new IllegalStateException("Could not commit the Request->Session linkage", failure);
        }
    }

    /** Opens an independent transaction holding a real FOR UPDATE row lock on one staff grant row. */
    private HeldRowLock holdGrantRow(long grantId) throws Exception {
        Connection connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
        connection.setSchema(SCHEMA);
        connection.setAutoCommit(false);
        int pid = connectionBackendPid(connection);
        try (var lock = connection.prepareStatement("select id from staff_access_grants where id = ? for update")) {
            lock.setLong(1, grantId);
            lock.executeQuery();
        }
        return new HeldRowLock(connection, pid);
    }

    /** Commits a real ownership transfer on a separate connection, mirroring Session and children. */
    private void commitCoordinatorTransfer(MutationTrace trace, AtomicInteger backendPid, long sessionId,
            long coordinatorUserId) {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD)) {
            connection.setSchema(SCHEMA);
            connection.setAutoCommit(false);
            backendPid.set(connectionBackendPid(connection));
            trace.stage("database transaction entered (competing backend " + backendPid.get() + ", autocommit=false)");
            try (var sessionsUpdate = connection.prepareStatement(
                    "update visit_sessions set coordinator_user_id = ?, version = version + 1 where id = ?")) {
                sessionsUpdate.setLong(1, coordinatorUserId);
                sessionsUpdate.setLong(2, sessionId);
                trace.stage("conflicting UPDATE visit_sessions issued");
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
            trace.stage("competing transaction committed");
        } catch (Exception failure) {
            throw new IllegalStateException("Could not commit the concurrent ownership transfer", failure);
        }
    }

    /** An independent connection holding a FOR UPDATE row lock, with its PostgreSQL backend PID. */
    private record HeldRowLock(Connection connection, int pid) implements AutoCloseable {
        void rollback() throws Exception {
            connection.rollback();
        }

        @Override
        public void close() throws Exception {
            connection.close();
        }
    }

    private record Actor(User user, String token) {}

    /**
     * Test-only seam: a faithful delegating proxy in front of the real {@code ListingRepository} lets a
     * test hold a reveal inside its locked critical section while a real transfer is attempted on a
     * separate connection. Production code is unaware of this.
     */
    @TestConfiguration
    static class ContactTestConfiguration {
        @Bean
        @Primary
        ListingRepository barrierListingRepository(@Qualifier("listingRepository") ListingRepository delegate) {
            return ListingReadBarrier.wrap(delegate);
        }

        @Bean
        @Primary
        OperationalAuditService guardedAuditService(OperationalAuditEventRepository events, DatabaseClock clock) {
            return new OperationalAuditService(events, clock) {
                @Override
                public OperationalAuditEvent recordUserEvent(User actor, String actionCode, String targetType,
                        Long targetId, String reasonCode, SupportedCity city, OperatingTeam team,
                        Map<String, Object> details) {
                    OperationalAuditEvent saved = super.recordUserEvent(actor, actionCode, targetType, targetId,
                            reasonCode, city, team, details);
                    if (AuditFailureGuard.failNext) {
                        AuditFailureGuard.failNext = false;
                        throw new IllegalStateException("audit write failed");
                    }
                    return saved;
                }
            };
        }
    }

    static final class ListingReadBarrier {
        private static volatile boolean armed;
        private static volatile CountDownLatch entered;
        private static volatile CountDownLatch release;

        private ListingReadBarrier() {}

        static void arm() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            armed = true;
        }

        static void reset() {
            armed = false;
            entered = null;
            release = null;
        }

        static boolean awaitEntered(long millis) throws InterruptedException {
            CountDownLatch latch = entered;
            return latch != null && latch.await(millis, TimeUnit.MILLISECONDS);
        }

        static void release() {
            CountDownLatch latch = release;
            if (latch != null) latch.countDown();
        }

        static ListingRepository wrap(ListingRepository delegate) {
            return (ListingRepository) Proxy.newProxyInstance(ListingReadBarrier.class.getClassLoader(),
                    new Class<?>[] {ListingRepository.class}, (proxy, method, arguments) -> {
                        if (armed && "findById".equals(method.getName())) {
                            armed = false;
                            CountDownLatch enteredLatch = entered;
                            CountDownLatch releaseLatch = release;
                            if (enteredLatch != null) enteredLatch.countDown();
                            if (releaseLatch != null) releaseLatch.await(10, TimeUnit.SECONDS);
                        }
                        try {
                            return method.invoke(delegate, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }

    static final class AuditFailureGuard {
        private static volatile boolean failNext;

        private AuditFailureGuard() {}

        static void failNextRecord() {
            failNext = true;
        }

        static void reset() {
            failNext = false;
        }
    }
}
