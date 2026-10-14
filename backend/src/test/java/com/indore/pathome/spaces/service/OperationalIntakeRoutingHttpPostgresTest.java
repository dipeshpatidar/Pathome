package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
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
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Package 3B acceptance: human-authorized initial intake routing over real HTTP against real PostgreSQL.
 *
 * <p>Covers the adversarial matrix - global and City Team Admin source-scope authorization, mandatory
 * explicit City/Team selection, canonical and unresolved-source rules, Request/City/Team version checks,
 * existing Request-root reconciliation and lock ordering, concurrent routing, stale versions,
 * bounded-wait rollback, concealed-resource responses, routing audit evidence, the absence of any Visit
 * Session/scheduling/entitlement side effect, same-Team visibility with cross-Team isolation, and a real
 * OE Claim/Release after authorized routing.
 *
 * <p><strong>Fixture provenance.</strong> Requests are created through the genuine tenant intake HTTP
 * route and persisted Listings/Requests are built through the production JPA layer, exactly like the
 * accepted Package 1C governance suite. The genuine <em>fresh lessor publication</em> path remains a
 * separate, still-unapproved dependency (narrow listing-review authority), so this suite does not claim
 * it: it proves the approved routing contract, not the publication approval gate.
 *
 * <p>Runs only against the designated disposable database (see tools/oe-integration-env). The class
 * defends itself when invoked directly through Maven: {@link DisposableIntegrationTarget} runs the whole
 * fail-closed policy before any connection that could create a schema is opened.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=p3b-routing-http-test-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=p3b-routing-http-test-otp-secret-32-bytes",
        "cloudinary.api-key=p3b-routing-test-key",
        "cloudinary.api-secret=p3b-routing-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class OperationalIntakeRoutingHttpPostgresTest {
    private static final String SCHEMA = "p3b_routing_http_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final long MINIMUM_BOUNDED_WAIT_MILLIS = 3_000L;
    private static final long MAXIMUM_BOUNDED_WAIT_MILLIS = 25_000L;

    private static DisposableIntegrationTarget.Target target;
    private static boolean schemaCreated;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        target = DisposableIntegrationTarget.verifyFromEnvironment();
        try (var connection = connect();
             Statement statement = connection.createStatement()) {
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
        registry.add("spring.datasource.url", OperationalIntakeRoutingHttpPostgresTest::schemaUrl);
        registry.add("spring.datasource.username", () -> target.username());
        registry.add("spring.datasource.password", () -> target.password());
    }

    @AfterAll
    static void dropSchemaAfterTheSuite() throws Exception {
        dropSchema();
    }

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

    private static String schemaUrl() {
        return target.schemaUrl(SCHEMA);
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(target.jdbcUrl(), target.username(), target.password()).schemas(SCHEMA)
                .defaultSchema(SCHEMA).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
    }

    @Autowired private ObjectMapper mapper;
    @Autowired private JwtUtils jwt;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private StaffAccessGrantRepository grantRows;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private OperationalOwnershipService ownership;
    @Autowired private TrustedOperationalIntakeService intake;
    @Autowired private VisitOperationsService visitOperations;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int httpPort;

    // ================================================================== global routing, no side effects

    @Test
    void genuineTenantIntakePlusGlobalRoutingPersistsOwnershipWithoutPrematureEffects() throws Exception {
        Actor admin = globalAdmin("global-route");
        SupportedCity city = activeCity("global-route");
        Locality locality = locality("global-route", city);
        Actor tenant = tenant("global-route-tenant");

        PropertyVisitRequest request = requestViaHttp(tenant, activeListing("global-route", locality));
        Long requestId = request.getId();
        long requestVersion = requestVersion(requestId);
        assertEquals(city.getId(), requestCityId(requestId),
                "genuine intake must derive the canonical City from the persisted Listing locality");
        assertTrue(scopeReady(requestId));

        OperatingTeam first = governedTeam(admin, city, "p3b-global-a", "Global A");
        governedTeam(admin, city, "p3b-global-b", "Global B");
        OperatingTeam retired = teams.saveAndFlush(new OperatingTeam(city,
                "p3b-global-retired", "Retired", true));
        retired.setActive(false);
        teams.saveAndFlush(retired);
        long cityVersionBefore = cityVersion(city.getId());
        long teamVersionBefore = teamVersion(first.getId());
        Map<String, Long> effectsBefore = operationalEffectCounts();
        int tenantVisitsBefore = tenantFreeVisits(tenant.user().getId());

        JsonNode candidates = mapper.readTree(send("GET",
                "/api/v1/operations/intake-routing/visit-requests?page=0&size=100", admin.token(), null).body());
        JsonNode listed = itemFor(candidates.path("items"), requestId);
        assertNotNull(listed, "global Admin must discover the eligible standalone Request");
        assertEquals("CANONICAL", listed.path("locationState").asText());
        assertTrue(listed.path("initialRoutingEligible").asBoolean());
        assertTrue(listed.path("standalone").asBoolean());
        assertFalse(listed.path("teamReady").asBoolean(), "a city-only Request is never Team-ready");
        assertFalse(listed.path("coordinatorAssigned").asBoolean());

        JsonNode read = candidatesFor(admin, requestId, city.getId());
        assertEquals(city.getId(), read.path("city").path("id").asLong());
        assertEquals(2, read.path("teams").path("items").size(),
                "only active same-City Teams are eligible destinations");
        read.path("teams").path("items").forEach(team -> {
            assertEquals(city.getId(), team.path("cityId").asLong());
            assertTrue(team.path("active").asBoolean());
        });
        assertEquals("no-store", readCacheControl(admin, requestId, city.getId()));

        HttpResponse<String> routed = send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersionBefore, first.getId(), teamVersionBefore,
                        "INITIAL_ROUTING"));
        assertEquals(204, routed.statusCode(), routed.body());
        assertEquals("no-store", routed.headers().firstValue("Cache-Control").orElse("missing"));

        assertEquals(city.getId(), requestCityId(requestId));
        assertEquals(first.getId(), requestTeamId(requestId));
        assertNull(requestCoordinatorId(requestId));
        assertTrue(scopeReady(requestId));
        assertEquals("RECEIVED", requestStatus(requestId));
        assertNull(requestSessionId(requestId));
        assertEquals(requestVersion + 1, requestVersion(requestId),
                "routing advances the Request's own optimistic version");
        assertEquals(cityVersionBefore, cityVersion(city.getId()), "routing never rewrites the City version");
        assertEquals(teamVersionBefore, teamVersion(first.getId()), "routing never rewrites the Team version");

        Map<String, Object> audit = routingAudit(requestId);
        assertEquals(admin.user().getId(), audit.get("actor_user_id"));
        assertEquals("INITIAL_ROUTING", audit.get("reason_code"));
        assertEquals(city.getId(), ((Number) audit.get("city_id")).longValue());
        assertEquals(first.getId(), ((Number) audit.get("team_id")).longValue());
        String details = String.valueOf(audit.get("details"));
        assertTrue(details.contains("\"routingMode\": \"INITIAL\""), details);
        assertTrue(details.contains("\"expectedRequestVersion\""), details);
        assertTrue(details.contains("\"resultingRequestVersion\""), details);
        assertTrue(details.contains("\"expectedCityVersion\""), details);
        assertTrue(details.contains("\"expectedTeamVersion\""), details);
        assertTrue(details.contains("\"unresolvedSource\": false"), details);
        assertTrue(details.contains("\"operationalScopeReady\": true"), details);

        assertEquals(effectsBefore, operationalEffectCounts(),
                "routing must not create a Session, schedule a visit or touch entitlement");
        assertEquals(tenantVisitsBefore, tenantFreeVisits(tenant.user().getId()));
    }

    // ================================================================== source-scope authorization

    @Test
    void cityTeamAdminScopeIsEnforcedAndUnknownCityCaptureIsImpossible() throws Exception {
        Actor admin = globalAdmin("scope-admin");
        Actor cityAdminA = cityAdmin("scope-a");
        SupportedCity cityA = activeCity("scope-a");
        SupportedCity cityB = activeCity("scope-b");
        cityScoped(cityAdminA, StaffCapability.CITY_TEAM_ADMIN, cityA);
        Locality localityA = locality("scope-a", cityA);
        Locality localityB = locality("scope-b", cityB);
        OperatingTeam teamA = governedTeam(admin, cityA, "p3b-scope-a", "Scope A");
        OperatingTeam teamB = governedTeam(admin, cityB, "p3b-scope-b", "Scope B");

        Actor tenant = tenant("scope-tenant");
        PropertyVisitRequest resolvedA = requestViaHttp(tenant, activeListing("scope-a-resolved", localityA));
        PropertyVisitRequest resolvedB = requestViaHttp(tenant("scope-tenant-b"),
                activeListing("scope-b-resolved", localityB));
        PropertyVisitRequest unresolved = intake("scope-unresolved", activeListing("scope-unresolved", null),
                tenant("scope-tenant-c"));
        PropertyVisitRequest conflicting = requestViaHttp(tenant("scope-tenant-d"),
                activeListing("scope-conflict", localityA));
        conflicting.setSupportedCity(cityB);
        requests.saveAndFlush(conflicting);
        assertEquals(cityB.getId(), requestCityId(conflicting.getId()));

        JsonNode page = mapper.readTree(send("GET",
                "/api/v1/operations/intake-routing/visit-requests?page=0&size=100", cityAdminA.token(), null).body());
        assertNotNull(itemFor(page.path("items"), resolvedA.getId()));
        assertNull(itemFor(page.path("items"), unresolved.getId()), "unresolved intake is global-only");
        assertNull(itemFor(page.path("items"), resolvedB.getId()), "another City's intake is concealed");
        assertNull(itemFor(page.path("items"), conflicting.getId()), "contradictory intake is concealed");

        assertEquals(404, send("GET", candidatesPath(unresolved.getId(), cityA.getId()),
                cityAdminA.token(), null).statusCode());
        assertEquals(404, send("GET", candidatesPath(resolvedB.getId(), cityB.getId()),
                cityAdminA.token(), null).statusCode());
        assertEquals(404, send("GET", candidatesPath(resolvedA.getId(), cityB.getId()),
                cityAdminA.token(), null).statusCode());

        long unresolvedVersion = requestVersion(unresolved.getId());
        assertEquals(404, send("POST", routePath(unresolved.getId()), cityAdminA.token(),
                routeBody(unresolvedVersion, cityA.getId(), cityVersion(cityA.getId()), teamA.getId(),
                        teamVersion(teamA.getId()), "INITIAL_ROUTING")).statusCode());
        assertEquals(404, send("POST", routePath(resolvedB.getId()), cityAdminA.token(),
                routeBody(requestVersion(resolvedB.getId()), cityB.getId(), cityVersion(cityB.getId()),
                        teamB.getId(), teamVersion(teamB.getId()), "INITIAL_ROUTING")).statusCode());
        assertEquals(404, send("POST", routePath(resolvedA.getId()), cityAdminA.token(),
                routeBody(requestVersion(resolvedA.getId()), cityB.getId(), cityVersion(cityB.getId()),
                        teamB.getId(), teamVersion(teamB.getId()), "INITIAL_ROUTING")).statusCode());
        assertNull(requestTeamId(unresolved.getId()));
        assertNull(requestTeamId(resolvedB.getId()));
        assertNull(requestTeamId(resolvedA.getId()));

        assertThrows(AccessDeniedException.class, () -> ownership.reconcileRequest(cityAdminA.user().getId(),
                unresolved.getId(), unresolvedVersion, null, null, cityA.getId(), teamA.getId(), null, true,
                cityVersion(cityA.getId()), teamVersion(teamA.getId()), "INITIAL_ROUTING"));
        assertNull(requestTeamId(unresolved.getId()));

        assertEquals(204, send("POST", routePath(resolvedA.getId()), cityAdminA.token(),
                routeBody(requestVersion(resolvedA.getId()), cityA.getId(), cityVersion(cityA.getId()),
                        teamA.getId(), teamVersion(teamA.getId()), "INITIAL_ROUTING")).statusCode());
        assertEquals(teamA.getId(), requestTeamId(resolvedA.getId()));
        assertNull(requestTeamId(conflicting.getId()));
    }

    @Test
    void everyCallerWithoutCurrentRoutingAuthorityIsDenied() throws Exception {
        Actor admin = globalAdmin("deny-admin");
        SupportedCity city = activeCity("deny");
        Locality locality = locality("deny", city);
        PropertyVisitRequest request = requestViaHttp(tenant("deny-tenant"), activeListing("deny", locality));
        Long requestId = request.getId();
        String routeBody = routeBody(requestVersion(requestId), city.getId(), cityVersion(city.getId()),
                1L, 0L, "INITIAL_ROUTING");

        Actor plainTenant = tenant("deny-plain-tenant");
        Actor noGrant = user("deny-nogrant", Role.ROLE_TENANT, "OPERATIONS", true);
        Actor inactiveStaff = user("deny-inactive", Role.ROLE_TENANT, "OPERATIONS", false);
        Actor legacyAdmin = user("deny-legacy-admin", Role.ROLE_ADMIN, null, null);
        Actor intakeOnly = user("deny-intake", Role.ROLE_TENANT, "OPERATIONS", true);
        cityScoped(intakeOnly, StaffCapability.OPS_INTAKE, city);
        Actor supervisor = user("deny-supervisor", Role.ROLE_TENANT, "OPERATIONS", true);
        teamScoped(supervisor, StaffCapability.OPS_SUPERVISE, city);

        assertEquals(401, send("GET", "/api/v1/operations/intake-routing/visit-requests", null, null).statusCode());
        assertEquals(401, send("GET", candidatesPath(requestId, city.getId()), null, null).statusCode());
        assertEquals(401, send("POST", routePath(requestId), null, routeBody).statusCode());

        for (Actor caller : List.of(plainTenant, noGrant, inactiveStaff, legacyAdmin, intakeOnly, supervisor)) {
            String label = caller.user().getFullName();
            assertEquals(403, send("GET", "/api/v1/operations/intake-routing/visit-requests",
                    caller.token(), null).statusCode(), label);
            assertEquals(403, send("GET", candidatesPath(requestId, city.getId()),
                    caller.token(), null).statusCode(), label);
            assertEquals(403, send("POST", routePath(requestId), caller.token(), routeBody).statusCode(), label);
        }
        assertNull(requestTeamId(requestId));
        assertEquals(200, send("GET", "/api/v1/operations/intake-routing/visit-requests",
                admin.token(), null).statusCode());
    }

    /**
     * Direct candidate-detail read: the source must be canonically resolved to exactly one City the
     * caller is authorized for. A persisted Request City that disagrees with the Listing's canonical
     * locality, or that has no canonical locality at all, is a concealment for the City administrator
     * of the persisted City - the exact defect an independent review reproduced. The global diagnostic
     * read, the legitimate City-scoped read and the collection visibility are all preserved.
     */
    @Test
    void crossCityCandidateDetailReadsAreConcealedByCanonicalSourceScope() throws Exception {
        Actor admin = globalAdmin("candscope-admin");
        Actor cityAdminB = cityAdmin("candscope-b");
        SupportedCity cityA = activeCity("candscope-a");
        SupportedCity cityB = activeCity("candscope-b");
        cityScoped(cityAdminB, StaffCapability.CITY_TEAM_ADMIN, cityB);
        Locality localityA = locality("candscope-a", cityA);
        Locality localityB = locality("candscope-b", cityB);
        OperatingTeam teamA = governedTeam(admin, cityA, "p3b-candscope-a", "Candidate Scope A");
        OperatingTeam teamB = governedTeam(admin, cityB, "p3b-candscope-b", "Candidate Scope B");

        // Persisted Request City B, canonical locality City A: contradictory evidence.
        Listing conflictListing = activeListing("candscope-conflict", localityA);
        PropertyVisitRequest conflicting = requestViaHttp(tenant("candscope-conflict-tenant"), conflictListing);
        conflicting.setSupportedCity(cityB);
        requests.saveAndFlush(conflicting);
        assertEquals(cityB.getId(), requestCityId(conflicting.getId()));

        // Persisted Request City B, canonical locality missing entirely.
        Listing orphanListing = activeListing("candscope-orphan", localityA);
        PropertyVisitRequest orphan = requestViaHttp(tenant("candscope-orphan-tenant"), orphanListing);
        orphan.setSupportedCity(cityB);
        requests.saveAndFlush(orphan);
        orphanListing.setCanonicalLocalityId(null);
        listings.saveAndFlush(orphanListing);
        assertEquals(cityB.getId(), requestCityId(orphan.getId()));

        // Persisted Request City A, canonical locality City B: the mirror image of the defect.
        Listing reversedListing = activeListing("candscope-reversed", localityB);
        PropertyVisitRequest reversed = requestViaHttp(tenant("candscope-reversed-tenant"), reversedListing);
        reversed.setSupportedCity(cityA);
        requests.saveAndFlush(reversed);

        // Genuine controls: a consistent City B source (authorized) and a consistent City A source (not).
        PropertyVisitRequest resolvedB = requestViaHttp(tenant("candscope-resolved-b"),
                activeListing("candscope-resolved-b", localityB));
        PropertyVisitRequest resolvedA = requestViaHttp(tenant("candscope-resolved-a"),
                activeListing("candscope-resolved-a", localityA));
        assertEquals(cityB.getId(), requestCityId(resolvedB.getId()));
        assertEquals(cityA.getId(), requestCityId(resolvedA.getId()));

        // The direct request-ID URL must not bypass collection visibility: none of the contradictory,
        // unresolved or foreign sources appear in the City B administrator's own collection.
        JsonNode collectionB = mapper.readTree(send("GET",
                "/api/v1/operations/intake-routing/visit-requests?page=0&size=100", cityAdminB.token(), null).body());
        assertNotNull(itemFor(collectionB.path("items"), resolvedB.getId()));
        assertNull(itemFor(collectionB.path("items"), conflicting.getId()),
                "contradictory intake is never a City-scoped candidate");
        assertNull(itemFor(collectionB.path("items"), orphan.getId()));
        assertNull(itemFor(collectionB.path("items"), reversed.getId()));
        assertNull(itemFor(collectionB.path("items"), resolvedA.getId()));

        // Contradictory source: concealed for the administrator whose persisted City is B, whichever
        // City the URL names, and without leaking any Listing or Team metadata.
        assertConcealedCandidateRead(cityAdminB, conflicting.getId(), cityB.getId(), conflictListing, teamB, teamA);
        assertConcealedCandidateRead(cityAdminB, conflicting.getId(), cityA.getId(), conflictListing, teamB, teamA);

        // Missing canonical locality: concealed identically.
        assertConcealedCandidateRead(cityAdminB, orphan.getId(), cityB.getId(), orphanListing, teamB, teamA);

        // Mirror image: persisted City A with City B-consistent locality is concealed for the City B admin.
        assertConcealedCandidateRead(cityAdminB, reversed.getId(), cityB.getId(), reversedListing, teamB, teamA);

        // A consistent City A source stays concealed, and naming the caller's own authorized City in the
        // URL cannot manufacture authority over a foreign source.
        assertConcealedCandidateRead(cityAdminB, resolvedA.getId(), cityB.getId(), null, teamB, teamA);
        assertConcealedCandidateRead(cityAdminB, resolvedA.getId(), cityA.getId(), null, teamB, teamA);

        // The concealed 404 is indistinguishable from a genuinely missing Request, headers included.
        HttpResponse<String> concealed = send("GET", candidatesPath(conflicting.getId(), cityB.getId()),
                cityAdminB.token(), null);
        HttpResponse<String> missing = send("GET", candidatesPath(9_000_000_001L, cityB.getId()),
                cityAdminB.token(), null);
        assertEquals(404, missing.statusCode(), missing.body());
        assertTrue(concealed.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                "a concealed read is never cacheable: " + concealed.headers().map());
        assertEquals(missing.headers().firstValue("Cache-Control").orElse("missing"),
                concealed.headers().firstValue("Cache-Control").orElse("missing"),
                "concealed and missing reads must be indistinguishable");
        assertEquals(mapper.readTree(missing.body()).path("error").asText(),
                mapper.readTree(concealed.body()).path("error").asText());
        assertEquals(mapper.readTree(missing.body()).path("message").asText(),
                mapper.readTree(concealed.body()).path("message").asText());

        // The legitimate City-scoped read is preserved: an authorized, canonically resolved source.
        JsonNode authorized = candidatesFor(cityAdminB, resolvedB.getId(), cityB.getId());
        assertEquals(cityB.getId(), authorized.path("city").path("id").asLong());
        assertEquals("CANONICAL", authorized.path("request").path("locationState").asText());
        assertEquals(1, authorized.path("teams").path("items").size());
        assertEquals(teamB.getId(), authorized.path("teams").path("items").get(0).path("id").asLong());
        assertEquals("no-store", readCacheControl(cityAdminB, resolvedB.getId(), cityB.getId()));

        // The frozen global diagnostic read is preserved unchanged, contradictions included.
        JsonNode globalConflict = candidatesFor(admin, conflicting.getId(), cityB.getId());
        assertEquals("CONFLICTING", globalConflict.path("request").path("locationState").asText());
        assertEquals(cityB.getId(), globalConflict.path("city").path("id").asLong());
        JsonNode globalOrphan = candidatesFor(admin, orphan.getId(), cityB.getId());
        assertEquals("UNRESOLVED", globalOrphan.path("request").path("locationState").asText());
        assertEquals("no-store", readCacheControl(admin, conflicting.getId(), cityB.getId()));

        // No read changed any persisted ownership fact.
        assertNull(requestTeamId(conflicting.getId()));
        assertNull(requestTeamId(orphan.getId()));
        assertNull(requestTeamId(reversed.getId()));
        assertNull(requestTeamId(resolvedB.getId()));
        assertEquals(0L, routingAuditCount(conflicting.getId()));
    }

    /** Asserts one candidate-detail read answers the concealment envelope and leaks no metadata. */
    private void assertConcealedCandidateRead(Actor caller, Long requestId, Long selectedCityId,
            Listing forbiddenListing, OperatingTeam... forbiddenTeams) throws Exception {
        HttpResponse<String> response = send("GET", candidatesPath(requestId, selectedCityId),
                caller.token(), null);
        assertEquals(404, response.statusCode(),
                "the source must be concealed for " + caller.user().getFullName() + ": " + response.body());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                "a concealed read is never cacheable: " + response.headers().map());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(404, body.path("status").asInt(), response.body());
        assertEquals("Visit Request not found", body.path("message").asText(), response.body());
        if (forbiddenListing != null) {
            assertFalse(response.body().contains(forbiddenListing.getTitle()),
                    "a concealed read must not disclose the Listing: " + response.body());
        }
        for (OperatingTeam team : forbiddenTeams) {
            assertFalse(response.body().contains(team.getCode()),
                    "a concealed read must not disclose a Team: " + response.body());
            assertFalse(response.body().contains(team.getDisplayName()), response.body());
        }
    }

    // ================================================================== explicit selection + versions

    @Test
    void explicitSelectionIsMandatoryAndEveryVersionIsChecked() throws Exception {
        Actor admin = globalAdmin("version-admin");
        SupportedCity city = activeCity("version");
        Locality locality = locality("version", city);
        PropertyVisitRequest request = requestViaHttp(tenant("version-tenant"), activeListing("version", locality));
        Long requestId = request.getId();
        long requestVersion = requestVersion(requestId);
        long cityVersion = cityVersion(city.getId());
        OperatingTeam team = governedTeam(admin, city, "p3b-version", "Version");
        long teamVersion = teamVersion(team.getId());

        assertEquals(400, send("POST", routePath(requestId), admin.token(),
                "{\"cityId\":" + city.getId() + ",\"expectedCityVersion\":" + cityVersion + ",\"teamId\":"
                        + team.getId() + ",\"expectedTeamVersion\":" + teamVersion
                        + ",\"reasonCode\":\"INITIAL_ROUTING\"}").statusCode());
        assertEquals(400, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, null, cityVersion, team.getId(), teamVersion, "INITIAL_ROUTING")).statusCode());
        assertEquals(400, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), null, team.getId(), teamVersion, "INITIAL_ROUTING")).statusCode());
        assertEquals(400, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, null, teamVersion, "INITIAL_ROUTING")).statusCode());
        assertEquals(400, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, team.getId(), null, "INITIAL_ROUTING")).statusCode());
        assertEquals(400, send("POST", routePath(requestId), admin.token(),
                routeBody(-1L, city.getId(), cityVersion, team.getId(), teamVersion, "INITIAL_ROUTING")).statusCode());
        assertEquals(400, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, team.getId(), teamVersion, "initial")).statusCode());
        assertEquals(400, send("POST", "/api/v1/operations/intake-routing/visit-requests/not-a-number/route",
                admin.token(), routeBody(requestVersion, city.getId(), cityVersion, team.getId(), teamVersion,
                        "INITIAL_ROUTING")).statusCode());
        assertEquals(400, send("POST", routePath(requestId), admin.token(), null).statusCode());
        assertEquals(400, send("POST", routePath(requestId), admin.token(), "{").statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/intake-routing/visit-requests?page=-1",
                admin.token(), null).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/intake-routing/visit-requests?size=101",
                admin.token(), null).statusCode());
        assertEquals(400, send("GET", candidatesPath(requestId, city.getId()) + "&page=abc",
                admin.token(), null).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/intake-routing/visit-requests/" + requestId
                + "/candidates", admin.token(), null).statusCode(),
                "the explicit City is mandatory: a missing cityId is a client error, never a server error");
        assertNull(requestTeamId(requestId));

        assertEquals(404, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, 9_999_999L, cityVersion, team.getId(), teamVersion,
                        "INITIAL_ROUTING")).statusCode());
        assertEquals(404, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, 9_999_999L, teamVersion,
                        "INITIAL_ROUTING")).statusCode());
        SupportedCity sleepingCity = activeCity("version-sleeping");
        sleepingCity.setActive(false);
        cities.saveAndFlush(sleepingCity);
        assertEquals(409, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, sleepingCity.getId(), cityVersion(sleepingCity.getId()), team.getId(),
                        teamVersion, "INITIAL_ROUTING")).statusCode());
        assertNull(requestTeamId(requestId));

        assertEquals(409, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion + 5, city.getId(), cityVersion, team.getId(), teamVersion,
                        "INITIAL_ROUTING")).statusCode());
        assertEquals(409, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion + 5, team.getId(), teamVersion,
                        "INITIAL_ROUTING")).statusCode());
        assertEquals(409, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, team.getId(), teamVersion + 5,
                        "INITIAL_ROUTING")).statusCode());
        assertNull(requestTeamId(requestId));

        OperatingTeam inactiveTeam = teams.saveAndFlush(new OperatingTeam(city,
                "p3b-version-inactive", "Inactive", true));
        inactiveTeam.setActive(false);
        teams.saveAndFlush(inactiveTeam);
        assertEquals(409, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, inactiveTeam.getId(),
                        teamVersion(inactiveTeam.getId()), "INITIAL_ROUTING")).statusCode());
        SupportedCity otherCity = activeCity("version-other");
        OperatingTeam otherTeam = governedTeam(admin, otherCity, "p3b-version-other", "Other");
        assertEquals(409, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, otherTeam.getId(),
                        teamVersion(otherTeam.getId()), "INITIAL_ROUTING")).statusCode());
        assertNull(requestTeamId(requestId));

        assertEquals(204, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, team.getId(), teamVersion,
                        "INITIAL_ROUTING")).statusCode());
        assertEquals(team.getId(), requestTeamId(requestId));
        assertEquals(1L, routingAuditCount(requestId));
        assertEquals(409, send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, team.getId(), teamVersion,
                        "INITIAL_ROUTING")).statusCode());
        assertEquals(1L, routingAuditCount(requestId));
    }

    @Test
    void zeroOneAndManyTeamsAreAllExplicitAndNeverAutoSelected() throws Exception {
        Actor admin = globalAdmin("cardinality-admin");
        SupportedCity emptyCity = activeCity("cardinality-empty");
        Locality emptyLocality = locality("cardinality-empty", emptyCity);
        PropertyVisitRequest emptyRequest = requestViaHttp(tenant("cardinality-tenant-a"),
                activeListing("cardinality-empty", emptyLocality));
        long emptyRequestVersion = requestVersion(emptyRequest.getId());
        long emptyCityVersion = cityVersion(emptyCity.getId());

        JsonNode zeroTeams = candidatesFor(admin, emptyRequest.getId(), emptyCity.getId());
        assertEquals(0, zeroTeams.path("teams").path("items").size());
        assertEquals(0L, zeroTeams.path("teams").path("totalCount").asLong());
        assertEquals(404, send("POST", routePath(emptyRequest.getId()), admin.token(),
                routeBody(emptyRequestVersion, emptyCity.getId(), emptyCityVersion, 9_999_999L, 0L,
                        "INITIAL_ROUTING")).statusCode(),
                "with zero Teams there is no destination and nothing is auto-created");
        assertNull(requestTeamId(emptyRequest.getId()));

        SupportedCity city = activeCity("cardinality-one");
        Locality locality = locality("cardinality-one", city);
        PropertyVisitRequest request = requestViaHttp(tenant("cardinality-tenant-b"),
                activeListing("cardinality-one", locality));
        long requestVersion = requestVersion(request.getId());
        long cityVersion = cityVersion(city.getId());
        OperatingTeam onlyTeam = governedTeam(admin, city, "p3b-cardinality-only", "Only");
        JsonNode oneTeam = candidatesFor(admin, request.getId(), city.getId());
        assertEquals(1, oneTeam.path("teams").path("items").size());
        assertEquals(onlyTeam.getId(), oneTeam.path("teams").path("items").get(0).path("id").asLong());

        assertEquals(400, send("POST", routePath(request.getId()), admin.token(),
                "{\"expectedRequestVersion\":" + requestVersion + ",\"cityId\":" + city.getId()
                        + ",\"expectedCityVersion\":" + cityVersion + ",\"expectedTeamVersion\":"
                        + teamVersion(onlyTeam.getId()) + ",\"reasonCode\":\"INITIAL_ROUTING\"}").statusCode());
        assertNull(requestTeamId(request.getId()));
        assertEquals(204, send("POST", routePath(request.getId()), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, onlyTeam.getId(),
                        teamVersion(onlyTeam.getId()), "INITIAL_ROUTING")).statusCode());
        assertEquals(onlyTeam.getId(), requestTeamId(request.getId()));

        SupportedCity manyCity = activeCity("cardinality-many");
        Locality manyLocality = locality("cardinality-many", manyCity);
        PropertyVisitRequest manyRequest = requestViaHttp(tenant("cardinality-tenant-c"),
                activeListing("cardinality-many", manyLocality));
        governedTeam(admin, manyCity, "p3b-many-a", "Many A");
        governedTeam(admin, manyCity, "p3b-many-b", "Many B");
        governedTeam(admin, manyCity, "p3b-many-c", "Many C");
        JsonNode manyTeams = candidatesFor(admin, manyRequest.getId(), manyCity.getId());
        assertEquals(3, manyTeams.path("teams").path("items").size());
        assertEquals(3L, manyTeams.path("teams").path("totalCount").asLong());
        assertEquals(1, mapper.readTree(send("GET",
                candidatesPath(manyRequest.getId(), manyCity.getId()) + "&page=0&size=1",
                admin.token(), null).body()).path("teams").path("items").size(),
                "a bounded page is returned rather than every Team");
    }

    // ================================================================== lifecycle, concurrency, atomicity

    @Test
    void nonEligibleRequestsAreRejectedByTheInitialRoutingPath() throws Exception {
        Actor admin = globalAdmin("lifecycle-admin");
        SupportedCity city = activeCity("lifecycle");
        Locality locality = locality("lifecycle", city);
        OperatingTeam team = governedTeam(admin, city, "p3b-lifecycle", "Lifecycle");
        long cityVersion = cityVersion(city.getId());
        long teamVersion = teamVersion(team.getId());
        Actor tenant = tenant("lifecycle-tenant");

        PropertyVisitRequest claimed = requestViaHttp(tenant, activeListing("lifecycle-claimed", locality));
        // The ownership-shape guard requires a coordinator to belong to a Team, so a claimed Request is
        // always a Team-routed Request too; both are refused by the initial-routing path.
        claimed.setOperatingTeam(team);
        claimed.setCoordinator(users.findById(admin.user().getId()).orElseThrow());
        requests.saveAndFlush(claimed);
        assertEquals(409, route(admin, claimed.getId(), city, cityVersion, team, teamVersion));
        assertNull(requestSessionId(claimed.getId()));

        PropertyVisitRequest teamRouted = requestViaHttp(tenant, activeListing("lifecycle-team", locality));
        teamRouted.setOperatingTeam(team);
        requests.saveAndFlush(teamRouted);
        assertEquals(409, route(admin, teamRouted.getId(), city, cityVersion, team, teamVersion));

        PropertyVisitRequest terminal = requestViaHttp(tenant, activeListing("lifecycle-terminal", locality));
        terminal.setStatus(VisitRequestStatus.COORDINATING);
        requests.saveAndFlush(terminal);
        assertEquals(409, route(admin, terminal.getId(), city, cityVersion, team, teamVersion));

        PropertyVisitRequest linked = requestViaHttp(tenant, activeListing("lifecycle-linked", locality));
        Actor legacyAdmin = user("lifecycle-legacy-admin", Role.ROLE_ADMIN, null, null);
        visitOperations.coordinateRequest(legacyAdmin.user().getId(), linked.getId(),
                new CoordinateVisitRequestCommand(requestVersion(linked.getId()), null, null));
        assertNotNull(requestSessionId(linked.getId()));
        assertEquals(409, route(admin, linked.getId(), city, cityVersion, team, teamVersion));
        assertNull(requestTeamId(linked.getId()), "a Session-linked Request is never reconciled here");

        for (Long requestId : List.of(claimed.getId(), teamRouted.getId(), terminal.getId())) {
            assertEquals(0L, routingAuditCount(requestId));
        }
    }

    @Test
    void concurrentRoutingCommitsExactlyOneMutationAndOneAuditEvent() throws Exception {
        Actor admin = globalAdmin("concurrent-admin");
        SupportedCity city = activeCity("concurrent");
        Locality locality = locality("concurrent", city);
        PropertyVisitRequest request = requestViaHttp(tenant("concurrent-tenant"),
                activeListing("concurrent", locality));
        long requestVersion = requestVersion(request.getId());
        long cityVersion = cityVersion(city.getId());
        OperatingTeam team = governedTeam(admin, city, "p3b-concurrent", "Concurrent");
        long teamVersion = teamVersion(team.getId());
        String body = routeBody(requestVersion, city.getId(), cityVersion, team.getId(), teamVersion,
                "INITIAL_ROUTING");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                tasks.add(() -> {
                    start.await();
                    return send("POST", routePath(request.getId()), admin.token(), body).statusCode();
                });
            }
            List<Future<Integer>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Integer> future : futures) statuses.add(future.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        statuses.sort(Integer::compareTo);
        assertEquals(List.of(204, 409), statuses);
        assertEquals(team.getId(), requestTeamId(request.getId()));
        assertEquals(1L, routingAuditCount(request.getId()));
        assertEquals(requestVersion + 1, requestVersion(request.getId()));
    }

    @Test
    void routingFailureRollsBackTheOwnershipMutationCompletely() throws Exception {
        Actor admin = globalAdmin("atomic-admin");
        SupportedCity city = activeCity("atomic");
        Locality locality = locality("atomic", city);
        PropertyVisitRequest request = requestViaHttp(tenant("atomic-tenant"), activeListing("atomic", locality));
        long requestVersion = requestVersion(request.getId());
        Long intakeCityId = requestCityId(request.getId());
        long cityVersion = cityVersion(city.getId());
        OperatingTeam team = governedTeam(admin, city, "p3b-atomic", "Atomic");

        jdbc.execute("create or replace function " + SCHEMA + ".p3b_reject_routing_audit() returns trigger "
                + "language plpgsql as $$ begin if new.action_code = 'OWNERSHIP_RECONCILED' "
                + "and new.target_id = " + request.getId() + " then raise exception 'P3B_TEST_AUDIT_FAILURE'; "
                + "end if; return new; end $$");
        jdbc.execute("create trigger p3b_reject_routing_audit before insert on " + SCHEMA
                + ".operational_audit_events for each row execute function "
                + SCHEMA + ".p3b_reject_routing_audit()");
        try {
            assertEquals(500, route(admin, request.getId(), city, cityVersion, team, teamVersion(team.getId())));
            assertNull(requestTeamId(request.getId()));
            assertEquals(intakeCityId, requestCityId(request.getId()),
                    "the rollback must restore the intake-derived City exactly");
            assertEquals(requestVersion, requestVersion(request.getId()));
            assertEquals(0L, routingAuditCount(request.getId()));
        } finally {
            jdbc.execute("drop trigger if exists p3b_reject_routing_audit on " + SCHEMA
                    + ".operational_audit_events");
            jdbc.execute("drop function if exists " + SCHEMA + ".p3b_reject_routing_audit()");
        }

        assertEquals(204, route(admin, request.getId(), city, cityVersion, team, teamVersion(team.getId())));
        assertEquals(team.getId(), requestTeamId(request.getId()));
        assertEquals(1L, routingAuditCount(request.getId()));
    }

    @Test
    void aBusyDatabaseFailsBoundedSanitizedAndWithoutPartialState() throws Exception {
        Actor admin = globalAdmin("busy-admin");
        SupportedCity city = activeCity("busy");
        Locality locality = locality("busy", city);
        PropertyVisitRequest request = requestViaHttp(tenant("busy-tenant"), activeListing("busy", locality));
        long requestVersion = requestVersion(request.getId());
        long cityVersion = cityVersion(city.getId());
        OperatingTeam team = governedTeam(admin, city, "p3b-busy", "Busy");
        String body = routeBody(requestVersion, city.getId(), cityVersion, team.getId(), teamVersion(team.getId()),
                "INITIAL_ROUTING");

        Connection cityHolder = cityRowLockHolder(city.getId());
        long cityElapsed;
        try {
            long started = System.nanoTime();
            HttpResponse<String> blocked = send("POST", routePath(request.getId()), admin.token(), body);
            cityElapsed = elapsedMillis(started);
            assertEquals(503, blocked.statusCode(), blocked.body());
            assertSanitizedUnavailable(blocked);
        } finally {
            release(cityHolder);
        }
        assertBoundedWait(cityElapsed);
        assertNull(requestTeamId(request.getId()));
        assertEquals(0L, routingAuditCount(request.getId()));

        Connection requestHolder = requestRowLockHolder(request.getId());
        long requestElapsed;
        try {
            long started = System.nanoTime();
            HttpResponse<String> blocked = send("POST", routePath(request.getId()), admin.token(), body);
            requestElapsed = elapsedMillis(started);
            assertEquals(503, blocked.statusCode(), blocked.body());
        } finally {
            release(requestHolder);
        }
        assertBoundedWait(requestElapsed);
        assertNull(requestTeamId(request.getId()));

        assertEquals(204, send("POST", routePath(request.getId()), admin.token(), body).statusCode());
        assertEquals(team.getId(), requestTeamId(request.getId()));
        assertEquals(1L, routingAuditCount(request.getId()));
    }

    // ================================================================== same-Team isolation and OE work

    @Test
    void sameTeamVisibilityCrossTeamIsolationAndRealClaimReleaseHoldAfterRouting() throws Exception {
        Actor admin = globalAdmin("work-admin");
        SupportedCity city = activeCity("work");
        Locality locality = locality("work", city);
        Actor tenant = tenant("work-tenant");
        PropertyVisitRequest request = requestViaHttp(tenant, activeListing("work", locality));
        long requestVersion = requestVersion(request.getId());
        long cityVersion = cityVersion(city.getId());
        OperatingTeam teamA = governedTeam(admin, city, "p3b-work-a", "Work A");
        OperatingTeam teamB = governedTeam(admin, city, "p3b-work-b", "Work B");

        Actor peerA = user("work-peer-a", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(peerA, StaffCapability.OPS_COORDINATE, teamA);
        Actor peerB = user("work-peer-b", Role.ROLE_TENANT, "OPERATIONS", true);
        teamGrant(peerB, StaffCapability.OPS_COORDINATE, teamB);

        assertEquals(0, claimableCount(peerA), "unrouted intake is nobody's claimable work");

        assertEquals(204, send("POST", routePath(request.getId()), admin.token(),
                routeBody(requestVersion, city.getId(), cityVersion, teamA.getId(), teamVersion(teamA.getId()),
                        "INITIAL_ROUTING")).statusCode());
        long routedVersion = requestVersion(request.getId());

        JsonNode teamAWork = claimableItem(peerA, request.getId());
        assertNotNull(teamAWork, "the routed Request is claimable metadata for its own Team");
        assertEquals(routedVersion, teamAWork.path("expectedVersion").asLong());
        assertEquals(teamA.getId(), teamAWork.path("teamId").asLong());
        assertNull(claimableItem(peerB, request.getId()), "another Team must not see this work");

        assertEquals(404, send("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/claim",
                peerB.token(), claimBody(routedVersion)).statusCode());
        assertEquals(0L, rowCount("select count(*) from property_visit_requests where id = ? "
                + "and coordinator_user_id is not null", request.getId()));

        Map<String, Long> effectsBeforeClaim = operationalEffectCounts();
        int tenantVisitsBefore = tenantFreeVisits(tenant.user().getId());

        assertEquals(204, send("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/claim",
                peerA.token(), claimBody(routedVersion)).statusCode());
        assertEquals(peerA.user().getId(), requestCoordinatorId(request.getId()));
        assertEquals(409, send("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/claim",
                peerA.token(), claimBody(requestVersion(request.getId()))).statusCode(),
                "a second claim must conflict rather than silently reassign");
        assertNull(claimableItem(peerA, request.getId()), "claimed work leaves the claimable pool");

        assertEquals(204, send("POST", "/api/v1/operations/visit-requests/" + request.getId() + "/release",
                peerA.token(), releaseBody(requestVersion(request.getId()))).statusCode());
        assertNull(requestCoordinatorId(request.getId()));

        assertEquals(effectsBeforeClaim, operationalEffectCounts(),
                "Claim and Release must not create a Session, schedule a visit or consume entitlement");
        assertEquals(tenantVisitsBefore, tenantFreeVisits(tenant.user().getId()));
    }

    // ================================================================== harness

    private record Actor(User user, String token) {}

    private int route(Actor admin, Long requestId, SupportedCity city, long cityVersion, OperatingTeam team,
            long teamVersion) throws Exception {
        return send("POST", routePath(requestId), admin.token(),
                routeBody(requestVersion(requestId), city.getId(), cityVersion, team.getId(), teamVersion,
                        "INITIAL_ROUTING")).statusCode();
    }

    private PropertyVisitRequest requestViaHttp(Actor tenant, Listing listing) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/properties/" + listing.getId() + "/visit-requests",
                tenant.token(), "{\"preferredVisitTiming\":\"Tomorrow 10 AM\"}");
        assertEquals(201, created.statusCode(), created.body());
        long requestId = mapper.readTree(created.body()).path("requestId").asLong();
        return requests.findById(requestId).orElseThrow();
    }

    private PropertyVisitRequest intake(String label, Listing listing, Actor tenant) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant.user());
        request.setListing(listing);
        request.setStatus(VisitRequestStatus.RECEIVED);
        return intake.admitAndSave(request);
    }

    private Actor user(String label, Role role, String profileRole, Boolean staffActive) {
        User user = new User();
        user.setEmail("p3b-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("+91987566" + String.format("%04d", Math.abs(label.hashCode()) % 10_000));
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

    private Actor tenant(String label) {
        return user(label, Role.ROLE_TENANT, null, null);
    }

    private Actor globalAdmin(String label) {
        Actor admin = user(label + "-admin", Role.ROLE_TENANT, "OPERATIONS", true);
        grantRows.saveAndFlush(new StaffAccessGrant(admin.user(), StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null, null, Instant.now(),
                "TEST_P3B", StaffGrantProvisioningSource.ADMIN_API));
        return admin;
    }

    private Actor cityAdmin(String label) {
        return user(label + "-cityadmin", Role.ROLE_TENANT, "OPERATIONS", true);
    }

    private void cityScoped(Actor actor, StaffCapability capability, SupportedCity city) {
        grantRows.saveAndFlush(new StaffAccessGrant(actor.user(), capability, StaffScopeType.CITY, city, null,
                Instant.now().minusSeconds(60), null, null, Instant.now(), "TEST_P3B",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private StaffAccessGrant teamScoped(Actor actor, StaffCapability capability, SupportedCity city) {
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city,
                "p3b-grant-" + UUID.randomUUID().toString().substring(0, 8), "Grant Team", true));
        return teamGrant(actor, capability, team);
    }

    private StaffAccessGrant teamGrant(Actor actor, StaffCapability capability, OperatingTeam team) {
        return grantRows.saveAndFlush(new StaffAccessGrant(actor.user(), capability, StaffScopeType.TEAM,
                null, team, Instant.now().minusSeconds(60), null, null, Instant.now(), "TEST_P3B",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private SupportedCity activeCity(String label) {
        return cities.saveAndFlush(new SupportedCity("p3b-" + label + "-"
                + UUID.randomUUID().toString().substring(0, 8), "P3B " + label, true));
    }

    private Locality locality(String label, SupportedCity city) {
        Locality locality = new Locality(city.getDisplayName(), "p3b-" + label + "-"
                + UUID.randomUUID().toString().substring(0, 8), null, null, null);
        locality.setSupportedCity(city);
        return localities.saveAndFlush(locality);
    }

    private Listing activeListing(String label, Locality localityOrNull) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle("P3B " + label + " " + UUID.randomUUID());
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("P3B test address");
        listing.setSector(localityOrNull == null ? "Unresolved" : localityOrNull.getSectorName());
        listing.setCity(localityOrNull == null ? "Unresolved" : localityOrNull.getCity());
        listing.setCanonicalLocalityId(localityOrNull == null ? null : localityOrNull.getId());
        listing.setBhkCount("1BHK");
        listing.setMonthlyRent(BigDecimal.ONE);
        listing.setSecurityDeposit(BigDecimal.ZERO);
        return listings.saveAndFlush(listing);
    }

    /** Creates the Team through the genuine governed Package 3A API, then reads it back. */
    private OperatingTeam governedTeam(Actor admin, SupportedCity city, String code, String name) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/staff/admin/cities/" + city.getId()
                + "/operating-teams", admin.token(), "{\"code\":" + json(code) + ",\"displayName\":" + json(name)
                + ",\"expectedCityVersion\":" + cityVersion(city.getId())
                + ",\"reasonCode\":\"TEAM_ONBOARDING\"}");
        assertEquals(201, created.statusCode(), created.body());
        return teams.findById(mapper.readTree(created.body()).path("id").asLong()).orElseThrow();
    }

    private JsonNode candidatesFor(Actor actor, Long requestId, Long cityId) throws Exception {
        HttpResponse<String> response = send("GET", candidatesPath(requestId, cityId), actor.token(), null);
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private String readCacheControl(Actor actor, Long requestId, Long cityId) throws Exception {
        return send("GET", candidatesPath(requestId, cityId), actor.token(), null)
                .headers().firstValue("Cache-Control").orElse("missing");
    }

    private JsonNode claimableItem(Actor actor, Long requestId) throws Exception {
        JsonNode page = mapper.readTree(send("GET", "/api/v1/operations/claimable-work?page=0&size=100",
                actor.token(), null).body());
        return itemFor(page.path("items"), requestId);
    }

    private int claimableCount(Actor actor) throws Exception {
        JsonNode page = mapper.readTree(send("GET", "/api/v1/operations/claimable-work?page=0&size=100",
                actor.token(), null).body());
        return page.path("items").size();
    }

    private static JsonNode itemFor(JsonNode items, Long requestId) {
        for (JsonNode item : items) {
            if (item.path("targetId").asLong() == requestId || item.path("requestId").asLong() == requestId) {
                return item;
            }
        }
        return null;
    }

    private static String candidatesPath(Long requestId, Long cityId) {
        return "/api/v1/operations/intake-routing/visit-requests/" + requestId + "/candidates?cityId=" + cityId;
    }

    private static String routePath(Long requestId) {
        return "/api/v1/operations/intake-routing/visit-requests/" + requestId + "/route";
    }

    private static String routeBody(Long expectedRequestVersion, Long cityId, Long expectedCityVersion,
            Long teamId, Long expectedTeamVersion, String reasonCode) {
        return "{\"expectedRequestVersion\":" + expectedRequestVersion + ",\"cityId\":" + cityId
                + ",\"expectedCityVersion\":" + expectedCityVersion + ",\"teamId\":" + teamId
                + ",\"expectedTeamVersion\":" + expectedTeamVersion + ",\"reasonCode\":" + json(reasonCode) + "}";
    }

    private static String claimBody(long expectedRequestVersion) {
        return "{\"expectedRequestVersion\":" + expectedRequestVersion + ",\"reasonCode\":\"CLAIM_WORK\"}";
    }

    private static String releaseBody(long expectedRequestVersion) {
        return "{\"expectedRequestVersion\":" + expectedRequestVersion + ",\"reasonCode\":\"RELEASE_WORK\"}";
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

    private long requestVersion(Long requestId) {
        return jdbc.queryForObject("select version from property_visit_requests where id = ?", Long.class, requestId);
    }

    private Long requestCityId(Long requestId) {
        return jdbc.queryForObject("select supported_city_id from property_visit_requests where id = ?",
                Long.class, requestId);
    }

    private Long requestTeamId(Long requestId) {
        return jdbc.queryForObject("select operating_team_id from property_visit_requests where id = ?",
                Long.class, requestId);
    }

    private Long requestCoordinatorId(Long requestId) {
        return jdbc.queryForObject("select coordinator_user_id from property_visit_requests where id = ?",
                Long.class, requestId);
    }

    private Long requestSessionId(Long requestId) {
        return jdbc.queryForObject("select session_id from property_visit_requests where id = ?",
                Long.class, requestId);
    }

    private String requestStatus(Long requestId) {
        return jdbc.queryForObject("select status from property_visit_requests where id = ?", String.class, requestId);
    }

    private boolean scopeReady(Long requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select operational_scope_ready from property_visit_requests where id = ?", Boolean.class, requestId));
    }

    private long cityVersion(Long cityId) {
        return jdbc.queryForObject("select version from supported_cities where id = ?", Long.class, cityId);
    }

    private long teamVersion(Long teamId) {
        return jdbc.queryForObject("select version from operating_teams where id = ?", Long.class, teamId);
    }

    private int tenantFreeVisits(Long userId) {
        return jdbc.queryForObject("select free_visits_remaining from users where id = ?", Integer.class, userId);
    }

    private Map<String, Object> routingAudit(Long requestId) {
        return jdbc.queryForMap("select * from operational_audit_events where action_code = 'OWNERSHIP_RECONCILED' "
                + "and target_type = 'PROPERTY_VISIT_REQUEST' and target_id = ?", requestId);
    }

    private long routingAuditCount(Long requestId) {
        return rowCount("select count(*) from operational_audit_events where action_code = 'OWNERSHIP_RECONCILED' "
                + "and target_type = 'PROPERTY_VISIT_REQUEST' and target_id = ?", requestId);
    }

    /** Every persisted table a premature "routing == visit" effect would have to touch. */
    private Map<String, Long> operationalEffectCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("visit_sessions", rowCount("select count(*) from visit_sessions"));
        counts.put("visit_session_items", rowCount("select count(*) from visit_session_items"));
        counts.put("visit_scheduling_decisions", rowCount("select count(*) from visit_scheduling_decisions"));
        counts.put("tenant_visit_entitlement_accounts",
                rowCount("select count(*) from tenant_visit_entitlement_accounts"));
        counts.put("visit_entitlement_ledger", rowCount("select count(*) from visit_entitlement_ledger"));
        counts.put("visit_notification_outbox", rowCount("select count(*) from visit_notification_outbox"));
        return counts;
    }

    private long rowCount(String sql, Object... arguments) {
        Long count = jdbc.queryForObject(sql, Long.class, arguments);
        return count == null ? 0L : count;
    }

    private Connection cityRowLockHolder(long cityId) throws Exception {
        Connection holder = DriverManager.getConnection(schemaUrl(), target.username(), target.password());
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.execute("select id from supported_cities where id = " + cityId + " for update");
        }
        return holder;
    }

    private Connection requestRowLockHolder(long requestId) throws Exception {
        Connection holder = DriverManager.getConnection(schemaUrl(), target.username(), target.password());
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.execute("select id from property_visit_requests where id = " + requestId + " for update");
        }
        return holder;
    }

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
            // Best effort.
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static void assertBoundedWait(long elapsedMillis) {
        assertTrue(elapsedMillis >= MINIMUM_BOUNDED_WAIT_MILLIS,
                "the command must wait for its lock instead of failing instantly (waited " + elapsedMillis + "ms)");
        assertTrue(elapsedMillis <= MAXIMUM_BOUNDED_WAIT_MILLIS,
                "the command must fail within its bounded wait rather than hang (waited " + elapsedMillis + "ms)");
    }

    private void assertSanitizedUnavailable(HttpResponse<String> response) throws Exception {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse("missing"));
        JsonNode body = mapper.readTree(response.body());
        assertEquals(503, body.path("status").asInt(), response.body());
        assertEquals("Service Unavailable", body.path("error").asText(), response.body());
        assertEquals("The operation could not be completed because the database was busy. Please try again shortly.",
                body.path("message").asText(), response.body());
        assertTrue(body.path("path").asText().endsWith("/route"), response.body());
        for (String forbidden : List.of("property_visit_requests", "supported_cities", "operating_teams",
                "operational_audit_events", "SQLSTATE", "55P03", "57014", "SQLException", "canceling statement",
                "ERROR:", "jdbc:", "org.postgresql", "pathome_db", "pathome_oe_test_integration",
                "select ", "insert ", "update ")) {
            assertFalse(response.body().contains(forbidden),
                    "the 503 response must not leak '" + forbidden + "': " + response.body());
        }
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
