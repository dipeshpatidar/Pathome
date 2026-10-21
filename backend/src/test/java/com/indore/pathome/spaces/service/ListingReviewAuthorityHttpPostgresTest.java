package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.LocationResolution;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.RentalMode;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.service.ListingReviewAuthority.Reviewer;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance for the approved listing-review authority extension, over real HTTP against real PostgreSQL.
 *
 * <p>Covers the adversarial matrix: anonymous, ordinary tenant, sub-administrator, a staff profile with
 * no grant, and every narrower capability; a revoked, not-yet-effective and inactive-profile grant; the
 * preserved legacy {@code ROLE_ADMIN} path; a live {@code STAFF_ADMIN@GLOBAL} staff administrator driving
 * the full review, canonical-locality linking and publication; the three review operations that stay
 * {@code ROLE_ADMIN}-only; the rest of {@code /api/v1/admin/**}; cross-City locality refusal; invalid
 * state transitions and retries; publication without a canonical locality; concurrent approvals; and a
 * grant revoked while the listing row lock is held.
 *
 * <p>It also drives the service <em>directly</em>, with the verdict as the only credential, because the
 * controller annotations are not what a caller invoking a bean is refused by: a credential supplied that
 * is not the authenticated request, a verdict replayed in a later request or for another principal
 * (including a second legacy administrator, and the same staff User's next credential), a genuine live
 * staff verdict aimed at the three operations that stay {@code ROLE_ADMIN}-only, and one stale verdict
 * offered to every review service entry point in turn.
 *
 * <p><strong>Fixture provenance.</strong> Identities, listings and drafts are built through the
 * production JPA layer inside one disposable test schema, exactly like the accepted Package 1C/3A
 * governance suites; no published listing is fabricated and no canonical column is written by hand. The
 * only canonical City and locality rows used are the migration-seeded ones. Grants are created and
 * revoked through the genuine {@code /api/v1/staff/admin/**} API, so the governance audit trail under
 * test is the real one.
 *
 * <p>The re-decision under the listing row lock relies on the application's default PostgreSQL READ
 * COMMITTED isolation: the guarded re-read must observe a revocation committed while this request was
 * waiting for the lock, which is exactly what the test below asserts.
 *
 * <p>Runs only against the designated disposable database (see tools/oe-integration-env).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=p4-listing-review-http-test-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=p4-listing-review-http-test-otp-secret-32b",
        "cloudinary.api-key=p4-listing-review-test-key",
        "cloudinary.api-secret=p4-listing-review-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class ListingReviewAuthorityHttpPostgresTest {
    private static final String SCHEMA =
            "p4_listing_review_http_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String QUEUE = "/api/v1/admin/lessor-review/listings?page=0";
    private static final String REVISIONS = "/api/v1/admin/lessor-review/revisions?page=0";
    private static final String UNRELATED_ADMIN_PATH = "/api/v1/admin/drafts";

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
        registry.add("spring.datasource.url", ListingReviewAuthorityHttpPostgresTest::schemaUrl);
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
    @Autowired private PropertyUploadDraftRepository drafts;
    @Autowired private PropertyMediaAssetRepository mediaAssets;
    @Autowired private PropertyDraftMediaRepository draftMedia;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LandlordReviewService reviews;
    @Autowired private ListingReviewAuthority reviewAuthority;
    @LocalServerPort private int httpPort;

    // ============================================================= refusal matrix

    @Test
    void anonymousCallersAreRefusedEveryApprovedOperation() throws Exception {
        assertEquals(401, send("GET", QUEUE, null, null).statusCode());
        assertEquals(401, send("GET", REVISIONS, null, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/admin/lessor-review/listings/1", null, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/admin/lessor-review/revisions/draft-x", null, null).statusCode());
        assertEquals(401, send("POST", startPath(1L), null, null).statusCode());
        assertEquals(401, send("POST", approvePath(1L), null, null).statusCode());
        assertEquals(401, send("POST", linkPath(1L), null, "{\"canonicalLocalityId\":1}").statusCode());
    }

    @Test
    void ordinaryTenantsAndSubAdministratorsAreRefusedEveryApprovedOperation() throws Exception {
        Actor tenant = actor("refuse-tenant", Role.ROLE_TENANT, null, null);
        Actor subAdmin = actor("refuse-subadmin", Role.ROLE_SUB_ADMIN, null, null);

        for (Actor caller : List.of(tenant, subAdmin)) {
            assertEquals(403, send("GET", QUEUE, caller.token(), null).statusCode(), caller.user().getRole().name());
            assertEquals(403, send("GET", REVISIONS, caller.token(), null).statusCode());
            assertEquals(403, send("GET", "/api/v1/admin/lessor-review/listings/1", caller.token(), null).statusCode());
            assertEquals(403, send("POST", startPath(1L), caller.token(), null).statusCode());
            assertEquals(403, send("POST", approvePath(1L), caller.token(), null).statusCode());
            assertEquals(403, send("POST", linkPath(1L), caller.token(), "{\"canonicalLocalityId\":1}")
                    .statusCode());
        }
    }

    @Test
    void narrowerCapabilitiesAndMissingGrantsCannotSubstituteForTheGlobalGrant() throws Exception {
        SupportedCity city = canonicalCity("indore");
        OperatingTeam team = team(city, "p4-narrow-team");
        Actor noGrant = staffSubject("narrow-nogrant", true);
        Actor cityAdmin = staffSubject("narrow-city", true);
        Actor oe = staffSubject("narrow-oe", true);
        cityScoped(cityAdmin, StaffCapability.CITY_TEAM_ADMIN, city);
        teamScoped(oe, StaffCapability.OPS_COORDINATE, team);

        for (Actor caller : List.of(noGrant, cityAdmin, oe)) {
            assertEquals(403, send("GET", QUEUE, caller.token(), null).statusCode(), caller.user().getEmail());
            assertEquals(403, send("GET", REVISIONS, caller.token(), null).statusCode());
            assertEquals(403, send("POST", startPath(1L), caller.token(), null).statusCode());
            assertEquals(403, send("POST", approvePath(1L), caller.token(), null).statusCode());
            assertEquals(403, send("POST", linkPath(1L), caller.token(), "{\"canonicalLocalityId\":1}")
                    .statusCode());
        }
    }

    @Test
    void inactiveProfilesAndRevokedAndNotYetEffectiveGrantsAreRefused() throws Exception {
        Actor provisioner = globalStaffAdmin("refuse-provisioner");
        SupportedCity city = canonicalCity("indore");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("refuse", provisioner.user(), locality);
        long listingId = listing.getId();

        // Revoked through the genuine staff-administration API, so the governance audit trail is real.
        StaffReviewer revoked = grantedStaffReviewer(provisioner, "refuse-revoked");
        assertEquals(200, send("POST", startPath(listingId), revoked.actor().token(), null).statusCode());
        assertEquals(200, revokeGrant(provisioner, revoked.grantId(), "P4_REVIEW_AUTHORITY_REVOKED").statusCode());
        assertEquals(403, send("GET", QUEUE, revoked.actor().token(), null).statusCode());
        assertEquals(403, send("POST", startPath(listingId), revoked.actor().token(), null).statusCode());

        // A global grant on an inactive staff profile is not authority.
        StaffReviewer inactive = grantedStaffReviewer(provisioner, "refuse-inactive");
        deactivateStaffProfile(inactive.actor());
        assertEquals(403, send("GET", QUEUE, inactive.actor().token(), null).statusCode());
        assertEquals(403, send("POST", approvePath(listingId), inactive.actor().token(), null).statusCode());

        // A global grant that is not effective yet is not authority either.
        Actor notYetEffective = staffSubject("refuse-future", true);
        grantRows.saveAndFlush(new StaffAccessGrant(notYetEffective.user(), StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().plusSeconds(3600), null, null, Instant.now(),
                "TEST_P4", StaffGrantProvisioningSource.ADMIN_API));
        assertEquals(403, send("GET", QUEUE, notYetEffective.token(), null).statusCode());
        assertEquals(403, send("POST", startPath(listingId), notYetEffective.token(), null).statusCode());

        assertEquals("UNDER_REVIEW", workflowStatus(listingId),
                "no refused caller may have advanced the listing");
        assertNull(publishedAt(listingId));
    }

    @Test
    void theGovernanceAuditTrailOfTheReviewAuthorityItselfIsRealAndComplete() throws Exception {
        Actor provisioner = globalStaffAdmin("audit-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "audit-reviewer");
        assertEquals(200, revokeGrant(provisioner, reviewer.grantId(), "P4_AUDIT_REVOCATION").statusCode());

        assertEquals(1L, auditCount("STAFF_ACCESS_GRANT_CREATED", reviewer.grantId()));
        assertEquals(1L, auditCount("STAFF_ACCESS_GRANT_REVOKED", reviewer.grantId()));
        assertEquals("P4_AUDIT_REVOCATION", jdbc.queryForObject(
                "select reason_code from operational_audit_events where action_code = 'STAFF_ACCESS_GRANT_REVOKED' "
                        + "and target_type = 'STAFF_ACCESS_GRANT' and target_id = ?", String.class,
                reviewer.grantId()));
        // The authority exists only through the audited staff-administration surface: the review endpoints
        // themselves never write, extend or repair a grant, so they cannot create unaudited authority.
        assertEquals(0L, grantRows.findAll().stream()
                .filter(grant -> grant.getUser().getId().equals(reviewer.actor().user().getId()))
                .filter(grant -> grant.getRevokedAt() == null).count());
    }

    // ============================================================= preserved legacy admin

    @Test
    void theLegacyAdminAuthorityKeepsEveryOperationIncludingTheNonApprovedOnes() throws Exception {
        Actor admin = legacyAdmin("legacy");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("legacy", admin.user(), locality);
        long listingId = listing.getId();

        assertEquals(200, send("GET", QUEUE, admin.token(), null).statusCode());
        assertEquals(200, send("GET", REVISIONS, admin.token(), null).statusCode());
        assertEquals(200, send("GET", "/api/v1/admin/lessor-review/listings/" + listingId, admin.token(), null)
                .statusCode());
        assertEquals(200, send("POST", startPath(listingId), admin.token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), admin.token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        assertEquals(200, send("POST", approvePath(listingId), admin.token(), null).statusCode());

        assertEquals("PUBLISHED", workflowStatus(listingId));
        assertEquals("ACTIVE", listingStatus(listingId));
        assertNotNull(publishedAt(listingId));

        // request-changes, revision approve and revision request-changes remain ROLE_ADMIN-only: the
        // administrator is admitted by the authorization layer, and the revision reads stay available.
        RentalDetails second = submittedListing("legacy-second", admin.user(), locality);
        assertEquals(200, send("POST", startPath(second.getId()), admin.token(), null).statusCode());
        assertEquals(200, send("POST", changesPath(second.getId()), admin.token(),
                "{\"note\":\"Please clarify the available date.\"}").statusCode());

        PropertyUploadDraft draft = reviewDraft("legacy", admin.user(), listingId, listingVersion(listingId));
        assertEquals(200, send("GET", "/api/v1/admin/lessor-review/revisions/" + draft.getDraftId(),
                admin.token(), null).statusCode());
        assertAdmittedByAuthorization(send("POST",
                "/api/v1/admin/lessor-review/revisions/" + draft.getDraftId() + "/approve", admin.token(), null));
        assertAdmittedByAuthorization(send("POST", "/api/v1/admin/lessor-review/revisions/" + draft.getDraftId()
                + "/request-changes", admin.token(),
                "{\"note\":\"Please clarify the available date.\"}"));
    }

    // ============================================================= global staff admin

    @Test
    void aLiveGlobalStaffAdminCompletesReviewLinkingAndPublication() throws Exception {
        Actor provisioner = globalStaffAdmin("staff-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "staff");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("staff", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        PropertyUploadDraft draft = reviewDraft("staff", reviewer.actor().user(), listingId, 0L);

        assertEquals(200, send("GET", QUEUE, reviewer.actor().token(), null).statusCode());
        assertEquals(403, send("GET", REVISIONS, reviewer.actor().token(), null).statusCode());
        JsonNode detail = body(send("GET", "/api/v1/admin/lessor-review/listings/" + listingId,
                reviewer.actor().token(), null));
        assertEquals(listingId, detail.path("listingId").asLong());
        assertEquals(403, send("GET", "/api/v1/admin/lessor-review/revisions/" + draft.getDraftId(),
                reviewer.actor().token(), null).statusCode());

        JsonNode started = body(send("POST", startPath(listingId), reviewer.actor().token(), null));
        assertEquals("UNDER_REVIEW", started.path("status").asText());

        JsonNode linked = body(send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}"));
        assertEquals("UNDER_REVIEW", linked.path("status").asText());
        assertEquals(locality.getId(), canonicalLocalityId(listingId));
        assertEquals("CANONICAL", jdbc.queryForObject(
                "select location_resolution from listings where id = ?", String.class, listingId));

        JsonNode approved = body(send("POST", approvePath(listingId), reviewer.actor().token(), null));
        assertEquals("PUBLISHED", approved.path("status").asText());
        assertEquals("PUBLISHED", workflowStatus(listingId));
        assertEquals("ACTIVE", listingStatus(listingId));
        assertNotNull(publishedAt(listingId));

        // The three review operations that are not part of the extension stay ROLE_ADMIN-only.
        assertEquals(403, send("POST", changesPath(listingId), reviewer.actor().token(),
                "{\"note\":\"Please clarify the available date.\"}").statusCode());
        assertEquals(403, send("POST", "/api/v1/admin/lessor-review/revisions/" + draft.getDraftId() + "/approve",
                reviewer.actor().token(), null).statusCode());
        assertEquals(403, send("POST", "/api/v1/admin/lessor-review/revisions/" + draft.getDraftId()
                + "/request-changes", reviewer.actor().token(),
                "{\"note\":\"Please clarify the available date.\"}").statusCode());
    }

    @Test
    void aGlobalStaffAdminGainsNoGeneralAdministrativeAccess() throws Exception {
        Actor provisioner = globalStaffAdmin("scope-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "scope");

        assertEquals(403, send("GET", UNRELATED_ADMIN_PATH, reviewer.actor().token(), null).statusCode());
        assertEquals(403, send("GET", "/api/v1/admin/failed-uploads", reviewer.actor().token(), null).statusCode());
        assertEquals(403, send("GET", "/api/v1/admin/drafts/anything", reviewer.actor().token(), null).statusCode());
        assertEquals(403, send("POST", UNRELATED_ADMIN_PATH, reviewer.actor().token(), null).statusCode());
    }

    // ============================================================= state, canonical and concurrency rules

    @Test
    void crossCityLocalityLinkingIsRefusedAndLeavesTheListingUntouched() throws Exception {
        Actor provisioner = globalStaffAdmin("crosscity-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "crosscity");
        Locality indoreLocality = canonicalLocality("Indore");
        Locality bhopalLocality = canonicalLocality("Bhopal");
        RentalDetails listing = submittedListing("crosscity", reviewer.actor().user(), indoreLocality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertNull(canonicalLocalityId(listingId));

        HttpResponse<String> refused = send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + bhopalLocality.getId() + "}");
        assertEquals(400, refused.statusCode(), refused.body());

        assertNull(canonicalLocalityId(listingId), "a foreign-City locality must never be linked");
        assertEquals("UNDER_REVIEW", workflowStatus(listingId));
        assertEquals("PENDING", listingStatus(listingId));
        assertEquals(indoreLocality.getSectorName(), sector(listingId));
    }

    @Test
    void invalidStateTransitionsAndRetriesAreRefusedWithoutMutation() throws Exception {
        Actor provisioner = globalStaffAdmin("state-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "state");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("state", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        long versionBefore = listingVersion(listingId);

        // Publication without a canonical locality is refused and changes nothing. The rule under test is
        // that no state may change: this pre-existing refusal is delivered as a sanitized 500 (see the
        // suite report - the rule is enforced, the status mapping is not part of the approved scope), so
        // only the refusal itself and the absence of any mutation are asserted here.
        HttpResponse<String> unresolved = send("POST", approvePath(listingId), reviewer.actor().token(), null);
        assertTrue(unresolved.statusCode() >= 400,
                "publishing without a canonical locality must be refused: " + unresolved.statusCode());
        assertEquals("SUBMITTED", workflowStatus(listingId));
        assertNull(publishedAt(listingId));
        assertEquals(versionBefore, listingVersion(listingId));

        // With a canonical locality linked, publishing a SUBMITTED listing still skips review: refused.
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long linkedVersion = listingVersion(listingId);
        assertEquals(409, send("POST", approvePath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals("SUBMITTED", workflowStatus(listingId));
        assertEquals("PENDING", listingStatus(listingId));
        assertNull(publishedAt(listingId));
        assertEquals(linkedVersion, listingVersion(listingId));

        // Starting review after publication, and re-approving, are conflicts with no mutation.
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        long underReviewVersion = listingVersion(listingId);
        assertEquals(409, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(underReviewVersion, listingVersion(listingId));
        assertEquals(200, send("POST", approvePath(listingId), reviewer.actor().token(), null).statusCode());
        long publishedVersion = listingVersion(listingId);
        assertEquals(409, send("POST", approvePath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(409, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(publishedVersion, listingVersion(listingId), "a retry must not mutate anything");
        assertEquals("PUBLISHED", workflowStatus(listingId));
        assertEquals(publishedVersion, underReviewVersion + 1, "publication advances the listing exactly once");
    }

    @Test
    void concurrentApprovalsPublishExactlyOnce() throws Exception {
        Actor provisioner = globalStaffAdmin("concurrent-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "concurrent");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("concurrent", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long before = listingVersion(listingId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            List<Future<Integer>> results = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    ready.await();
                    return send("POST", approvePath(listingId), reviewer.actor().token(), null).statusCode();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) statuses.add(result.get(60, TimeUnit.SECONDS));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 409), statuses,
                    "exactly one concurrent approval may publish; the other must conflict");
        } finally {
            pool.shutdownNow();
        }

        assertEquals("PUBLISHED", workflowStatus(listingId));
        assertEquals("ACTIVE", listingStatus(listingId));
        assertNotNull(publishedAt(listingId));
        assertEquals(before + 1, listingVersion(listingId),
                "exactly one approval may have mutated the listing");
    }

    /**
     * A revocation that has already committed when the approval takes its evidence hold is simply not
     * effective authority any more: the hold finds no effective grant, and the guarded re-decision
     * refuses. This is the "revocation first" leg in its simplest form; the concurrent form, where the
     * revocation commits while the approval is already parked inside its transaction, is proven below.
     */
    @Test
    void aGrantRevokedBeforeTheHoldIsTakenIsRefusedUnderTheGuard() throws Exception {
        Actor provisioner = globalStaffAdmin("lockrace-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "lockrace");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("lockrace", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long versionBefore = listingVersion(listingId);

        HttpResponse<String> revoked = revokeGrant(provisioner, reviewer.grantId(),
                "P4_AUTHORITY_REVOKED_UNDER_GUARD");
        assertEquals(200, revoked.statusCode(), revoked.body());

        HttpResponse<String> response = send("POST", approvePath(listingId), reviewer.actor().token(), null);
        assertEquals(403, response.statusCode(),
                "the guarded re-decision must refuse a grant revoked before the approval decided: "
                        + response.body());

        assertEquals("UNDER_REVIEW", workflowStatus(listingId));
        assertEquals("PENDING", listingStatus(listingId));
        assertNull(publishedAt(listingId));
        assertEquals(versionBefore, listingVersion(listingId), "the refused approval must not have mutated");
        assertEquals(0L, auditRowsFor("LISTING_PUBLISHED", "LISTING", listingId));
        assertEquals(1L, auditCount("STAFF_ACCESS_GRANT_REVOKED", reviewer.grantId()),
                "the revocation itself is still recorded exactly once");
    }

    // ======================================================= forgeable-reviewer failure class

    @Test
    void aDirectServiceCallerCannotPublishWithACredentialItBuiltItself() throws Exception {
        Actor provisioner = globalStaffAdmin("forged-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "forged");
        Actor tenant = actor("forged-caller", Role.ROLE_TENANT, null, null);
        Actor admin = legacyAdmin("forged-borrowed");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("forged", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long versionBefore = listingVersion(listingId);

        // A verdict the caller legitimately minted in the administrator's own request, and the credentials
        // it would rather be holding: one it wrote itself, and one naming a real staff administrator whose
        // grant is live. What is actually authenticated for this call is an ordinary tenant.
        Reviewer borrowedAdministrator;
        Authentication selfBuiltAdministrator = actorCredential(admin);
        Authentication borrowedLiveStaff = credential("ROLE_TENANT", reviewer.actor().user().getId());
        installPrincipal(actorCredential(admin));
        borrowedAdministrator = reviewAuthority.require(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(borrowedAdministrator.legacyAdmin());
        installPrincipal(credential("ROLE_TENANT", tenant.user().getId()));
        try {
            // The entry decision is taken for the authenticated request and for no other credential, so a
            // credential the caller supplies is refused before any authority is even consulted.
            assertThrows(AccessDeniedException.class, () -> reviewAuthority.require(selfBuiltAdministrator));
            assertThrows(AccessDeniedException.class, () -> reviewAuthority.require(borrowedLiveStaff));
            assertThrows(AccessDeniedException.class,
                    () -> reviewAuthority.requireLegacyAdministrator(selfBuiltAdministrator));
            assertThrows(AccessDeniedException.class, () -> reviewAuthority.require(null));

            // And a verdict minted for another authenticated request is refused under the guard.
            assertThrows(AccessDeniedException.class,
                    () -> reviews.decideListing(listingId, ListingWorkflowStatus.PUBLISHED, null,
                            borrowedAdministrator),
                    "a verdict the caller supplied itself must never publish the listing");
            assertThrows(AccessDeniedException.class,
                    () -> reviews.decideListing(listingId, ListingWorkflowStatus.PUBLISHED, null, null));
            assertThrows(AccessDeniedException.class,
                    () -> reviews.linkLocality(listingId, locality.getId(), borrowedAdministrator));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals("UNDER_REVIEW", workflowStatus(listingId));
        assertEquals("PENDING", listingStatus(listingId));
        assertNull(publishedAt(listingId));
        assertEquals(versionBefore, listingVersion(listingId), "a refused direct call must not mutate");
        assertEquals(locality.getId(), canonicalLocalityId(listingId));
    }

    /**
     * The direct-service form of the three operations that stay {@code ROLE_ADMIN}-only. The HTTP rule
     * refuses a staff administrator on the controller annotation; a caller that invokes the service
     * directly skips that annotation entirely, and the service boundary has to refuse it on its own.
     */
    @Test
    void theThreeLegacyOnlyOperationsRefuseAGenuineLiveStaffVerdictAtTheServiceBoundary() throws Exception {
        Actor provisioner = globalStaffAdmin("legacyonly-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "legacyonly");
        Actor owner = actor("legacyonly-owner", Role.ROLE_TENANT, null, null);
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("legacyonly", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long listingVersionBefore = listingVersion(listingId);

        RentalDetails published = submittedListing("legacyonly-published", owner.user(), locality);
        published.setCanonicalLocalityId(locality.getId());
        published.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        published.setStatus(ListingStatus.ACTIVE);
        published.setPublishedAt(LocalDateTime.now());
        listings.saveAndFlush(published);
        PropertyUploadDraft draft = reviewDraft("legacyonly", owner.user(), published.getId(), published.getVersion());
        long publishedVersionBefore = listingVersion(published.getId());

        // A genuine, current, correctly minted STAFF_ADMIN@GLOBAL verdict: the very verdict the seven
        // approved operations are driven by, and no authority at all for these three.
        Authentication staffCredential = credential("ROLE_TENANT", reviewer.actor().user().getId());
        installPrincipal(staffCredential);
        try {
            Reviewer staffVerdict = reviewAuthority.require(staffCredential);
            assertFalse(staffVerdict.legacyAdmin());

            assertThrows(AccessDeniedException.class, () -> reviews.decideListing(listingId,
                    ListingWorkflowStatus.CHANGES_REQUIRED, "Please clarify the available date.", staffVerdict));
            assertThrows(AccessDeniedException.class,
                    () -> reviews.approveRevision(draft.getDraftId(), staffVerdict));
            assertThrows(AccessDeniedException.class, () -> reviews.requestRevisionChanges(draft.getDraftId(),
                    "Please clarify the available date.", staffVerdict));

            // Starting review is part of the extension, so the same verdict still works there - the refusal
            // above is about the operation, not about the verdict being unusable.
            assertEquals(200, send("POST", approvePath(listingId), reviewer.actor().token(), null).statusCode());
            assertEquals("PUBLISHED", workflowStatus(listingId));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertNull(listingReviewNote(listingId), "requesting listing changes must have changed nothing");
        assertEquals("REVIEW", draftStatus(draft.getDraftId()));
        assertNull(draftReviewNote(draft.getDraftId()));
        assertEquals(publishedVersionBefore, listingVersion(published.getId()),
                "a refused revision decision must not have touched the published property");
        assertEquals(listingVersionBefore + 1, listingVersion(listingId),
                "publishing through the extension still advances the listing exactly once");
    }

    /**
     * A verdict belongs to one request. The same staff User's next credential, another principal, and a
     * second administrator whose credential carries exactly the same authorities are all refused, and the
     * listings stay exactly as they were.
     */
    @Test
    void aReviewVerdictIsRefusedInAnotherRequestForTheSameStaffUserAndBetweenTwoAdministrators() throws Exception {
        Actor provisioner = globalStaffAdmin("replay-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "replay");
        Actor admin = legacyAdmin("replay-admin");
        Locality locality = canonicalLocality("Indore");

        RentalDetails staffListing = submittedListing("replay-staff", reviewer.actor().user(), locality);
        long staffListingId = staffListing.getId();
        assertEquals(200, send("POST", startPath(staffListingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(staffListingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long staffListingVersion = listingVersion(staffListingId);

        RentalDetails adminListing = submittedListing("replay-admin", admin.user(), locality);
        long adminListingId = adminListing.getId();
        assertEquals(200, send("POST", startPath(adminListingId), admin.token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(adminListingId), admin.token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long adminListingVersion = listingVersion(adminListingId);

        // The staff administrator's verdict, and the first administrator's, each taken in its own request.
        RequestVerdict staffRequest = decidedInEarlierRequest(
                credential("ROLE_TENANT", reviewer.actor().user().getId()));
        RequestVerdict administratorRequest = decidedInEarlierRequest(actorCredential(admin));
        assertFalse(staffRequest.verdict().legacyAdmin());
        assertTrue(administratorRequest.verdict().legacyAdmin());

        // 1. The same staff User's next credential: the database facts are unchanged, the verdict is not.
        Authentication nextStaffRequest = credential("ROLE_TENANT", reviewer.actor().user().getId());
        installPrincipal(nextStaffRequest);
        try {
            assertNotSame(staffRequest.credential(), nextStaffRequest);
            assertThrows(AccessDeniedException.class, () -> reviewAuthority.revalidate(staffRequest.verdict()));
            assertThrows(AccessDeniedException.class, () -> reviews.decideListing(staffListingId,
                    ListingWorkflowStatus.PUBLISHED, null, staffRequest.verdict()));
            assertThrows(AccessDeniedException.class, () -> reviews.linkLocality(staffListingId,
                    locality.getId(), staffRequest.verdict()));
        } finally {
            SecurityContextHolder.clearContext();
        }

        // 2. A second administrator, whose credential carries exactly the legacy ROLE_ADMIN authority the
        //    legacy rule consults - and still not the request the verdict was decided for.
        Actor secondAdmin = legacyAdmin("replay-second-admin");
        Authentication secondAdminRequest = actorCredential(secondAdmin);
        installPrincipal(secondAdminRequest);
        try {
            assertNotSame(administratorRequest.credential(), secondAdminRequest);
            assertThrows(AccessDeniedException.class,
                    () -> reviewAuthority.revalidateLegacyAdministrator(administratorRequest.verdict()));
            assertThrows(AccessDeniedException.class, () -> reviews.decideListing(adminListingId,
                    ListingWorkflowStatus.PUBLISHED, null, administratorRequest.verdict()));
            assertThrows(AccessDeniedException.class, () -> reviews.decideListing(adminListingId,
                    ListingWorkflowStatus.CHANGES_REQUIRED, "Please clarify the available date.",
                    administratorRequest.verdict()));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals("UNDER_REVIEW", workflowStatus(staffListingId));
        assertNull(publishedAt(staffListingId));
        assertEquals(staffListingVersion, listingVersion(staffListingId));
        assertEquals(locality.getId(), canonicalLocalityId(staffListingId));
        assertEquals("UNDER_REVIEW", workflowStatus(adminListingId));
        assertNull(publishedAt(adminListingId));
        assertEquals(adminListingVersion, listingVersion(adminListingId));
        assertNull(listingReviewNote(adminListingId));
    }

    /**
     * The failure class itself, across every review service entry point: one stale verdict - legitimately
     * minted, for a principal whose authority is still live - against an authenticated request it does not
     * belong to. Only the service is exercised here; nothing in this test relies on a controller
     * annotation to refuse anything.
     */
    @Test
    void everyReviewServiceEntryPointRefusesAVerdictFromAnEarlierRequest() throws Exception {
        Actor admin = legacyAdmin("entrypoint-admin");
        Actor owner = actor("entrypoint-owner", Role.ROLE_TENANT, null, null);
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("entrypoint", owner.user(), locality);
        long listingId = listing.getId();
        long listingVersionBefore = listingVersion(listingId);
        PropertyUploadDraft draft = reviewDraft("entrypoint", owner.user(), listingId, listingVersionBefore);

        Reviewer staleVerdict = decidedInEarlierRequest(actorCredential(admin)).verdict();
        assertTrue(staleVerdict.legacyAdmin());

        // An administrator is authenticated for this call too - the verdict is refused for belonging to
        // another request, not for the authority behind it having lapsed.
        installPrincipal(actorCredential(admin));
        try {
            assertThrows(AccessDeniedException.class, () -> reviews.listings(0, staleVerdict));
            assertThrows(AccessDeniedException.class, () -> reviews.revisions(0, staleVerdict));
            assertThrows(AccessDeniedException.class, () -> reviews.listingDetail(listingId, staleVerdict));
            assertThrows(AccessDeniedException.class, () -> reviews.revisionDetail(draft.getDraftId(), staleVerdict));
            assertThrows(AccessDeniedException.class, () -> reviews.decideListing(listingId,
                    ListingWorkflowStatus.UNDER_REVIEW, null, staleVerdict));
            assertThrows(AccessDeniedException.class, () -> reviews.decideListing(listingId,
                    ListingWorkflowStatus.CHANGES_REQUIRED, "Please clarify the available date.", staleVerdict));
            assertThrows(AccessDeniedException.class,
                    () -> reviews.linkLocality(listingId, locality.getId(), staleVerdict));
            assertThrows(AccessDeniedException.class, () -> reviews.approveRevision(draft.getDraftId(), staleVerdict));
            assertThrows(AccessDeniedException.class, () -> reviews.requestRevisionChanges(draft.getDraftId(),
                    "Please clarify the available date.", staleVerdict));
            assertThrows(AccessDeniedException.class, () -> reviews.decideListing(listingId,
                    ListingWorkflowStatus.PUBLISHED, null, null));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals("SUBMITTED", workflowStatus(listingId));
        assertEquals("PENDING", listingStatus(listingId));
        assertNull(publishedAt(listingId));
        assertNull(canonicalLocalityId(listingId));
        assertNull(listingReviewNote(listingId));
        assertEquals(listingVersionBefore, listingVersion(listingId),
                "no entry point may have mutated the listing");
        assertEquals("REVIEW", draftStatus(draft.getDraftId()));
        assertNull(draftReviewNote(draft.getDraftId()));
    }

    // ======================================================= canonical-locality relinking

    @Test
    void relinkingToADifferentLocalityInTheSameCityIsRefusedAndChangesNothing() throws Exception {
        Actor provisioner = globalStaffAdmin("relink-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "relink");
        Locality linked = canonicalLocality("Indore");
        Locality other = anotherLocalityIn(linked.getCity(), linked.getId());
        assertTrue(!other.getSectorName().equalsIgnoreCase(linked.getSectorName()));
        RentalDetails listing = submittedListing("relink", reviewer.actor().user(), linked);
        long listingId = listing.getId();
        PropertyMediaAsset photo = attachedPhoto(listingId);
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + linked.getId() + "}").statusCode());
        long versionAfterFirstLink = listingVersion(listingId);
        assertEquals(linked.getSectorName(), sector(listingId));
        assertEquals(linked.getSectorName(), mediaSector(photo.getId()));

        HttpResponse<String> refused = send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + other.getId() + "}");
        assertEquals(409, refused.statusCode(), refused.body());
        JsonNode conflict = mapper.readTree(refused.body());
        assertEquals(409, conflict.path("status").asInt());
        assertEquals("Conflict", conflict.path("error").asText());
        assertEquals("/api/v1/admin/lessor-review/listings/" + listingId + "/link-locality",
                conflict.path("path").asText());

        assertEquals(linked.getId(), canonicalLocalityId(listingId), "the first link must survive");
        assertEquals(linked.getSectorName(), sector(listingId));
        assertEquals("CANONICAL", jdbc.queryForObject(
                "select location_resolution from listings where id = ?", String.class, listingId));
        assertEquals(linked.getSectorName(), mediaSector(photo.getId()),
                "linked media evidence must survive the refusal");
        assertEquals("UNDER_REVIEW", workflowStatus(listingId));
        assertEquals("PENDING", listingStatus(listingId));
        assertNull(publishedAt(listingId));
        assertEquals(versionAfterFirstLink, listingVersion(listingId), "a refusal must not advance the listing");

        // The review detail a client reloads reports the locality that is actually linked, so a stale
        // client can resynchronise instead of relinking.
        JsonNode detail = body(send("GET", "/api/v1/admin/lessor-review/listings/" + listingId,
                reviewer.actor().token(), null));
        assertEquals(linked.getId(), detail.path("data").path("location").path("canonicalLocalityId").asLong());
        assertEquals(linked.getSectorName(), detail.path("data").path("location").path("localityInput").asText());

        // A locality in another City is still refused by the City rule, and the linked locality survives.
        Locality foreign = canonicalLocality("Bhopal");
        assertEquals(400, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + foreign.getId() + "}").statusCode());
        assertEquals(linked.getId(), canonicalLocalityId(listingId));
        assertEquals(versionAfterFirstLink, listingVersion(listingId));

        // The documented repeat of the very same link still succeeds, and publication still follows it.
        JsonNode repeated = body(send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + linked.getId() + "}"));
        assertEquals("UNDER_REVIEW", repeated.path("status").asText());
        assertEquals(linked.getId(), canonicalLocalityId(listingId));
        long versionBeforeApproval = listingVersion(listingId);
        assertEquals(200, send("POST", approvePath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals("PUBLISHED", workflowStatus(listingId));
        assertEquals("ACTIVE", listingStatus(listingId));
        assertNotNull(publishedAt(listingId));
        assertEquals(versionBeforeApproval + 1, listingVersion(listingId),
                "publication still advances the listing exactly once");
        assertEquals(linked.getId(), canonicalLocalityId(listingId));
    }

    @Test
    void concurrentRelinkingToDifferentLocalitiesLinksExactlyOne() throws Exception {
        Actor provisioner = globalStaffAdmin("relink-race-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "relink-race");
        Locality first = canonicalLocality("Indore");
        Locality second = anotherLocalityIn(first.getCity(), first.getId());
        RentalDetails listing = submittedListing("relink-race", reviewer.actor().user(), first);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        long before = listingVersion(listingId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            List<Future<Integer>> results = new ArrayList<>();
            for (Long localityId : List.of(first.getId(), second.getId())) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    ready.await();
                    return send("POST", linkPath(listingId), reviewer.actor().token(),
                            "{\"canonicalLocalityId\":" + localityId + "}").statusCode();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) statuses.add(result.get(60, TimeUnit.SECONDS));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 409), statuses,
                    "exactly one concurrent relink may commit; the other must conflict");
        } finally {
            pool.shutdownNow();
        }

        Long linkedLocalityId = canonicalLocalityId(listingId);
        assertTrue(linkedLocalityId.equals(first.getId()) || linkedLocalityId.equals(second.getId()),
                "the listing must remain linked to one of the two contested localities");
        assertEquals(before + 1, listingVersion(listingId),
                "exactly one concurrent relink may have mutated the listing");
        assertEquals("UNDER_REVIEW", workflowStatus(listingId));
        assertEquals("PENDING", listingStatus(listingId));
        assertNull(publishedAt(listingId));
    }

    // ============================================================ C: ordered revocation (concurrency)

    /**
     * The ordering guarantee, publication side, over real concurrent transactions. A revocation that
     * arrives while an approval is inside its transaction cannot commit: the approval already holds the
     * actor's authority evidence {@code FOR SHARE}, so the revocation is blocked on that evidence until
     * the approval commits. The publication therefore commits first, and the revocation - committing
     * second - governs every later operation and nothing already committed.
     */
    @Test
    void anApprovalHoldsTheAuthorityEvidenceSoARevocationArrivingSecondWaitsAndAppliesOnlyAfterwards()
            throws Exception {
        Actor provisioner = globalStaffAdmin("order-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "order");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("order", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long versionBefore = listingVersion(listingId);
        long deadlocksBefore = deadlockCount();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection holder = listingRowLockHolder(listingId);
        try {
            Future<HttpResponse<String>> approval = pool.submit(
                    () -> send("POST", approvePath(listingId), reviewer.actor().token(), null));
            // The approval is parked on the Listing lock, so it has already taken its evidence hold.
            awaitSessionWaitingOn("from listings", Duration.ofSeconds(10));

            Future<HttpResponse<String>> revocation = pool.submit(
                    () -> revokeGrant(provisioner, reviewer.grantId(), "P4_ORDERED_REVOCATION"));
            // The governed revocation is blocked on the evidence this undecided approval holds.
            awaitSessionWaitingOn("employee_profiles|staff_access_grants", Duration.ofSeconds(4));
            release(holder);
            holder = null;

            HttpResponse<String> approved = approval.get(60, TimeUnit.SECONDS);
            assertEquals(200, approved.statusCode(), approved.body());
            HttpResponse<String> revoked = revocation.get(60, TimeUnit.SECONDS);
            assertEquals(200, revoked.statusCode(), revoked.body());
        } finally {
            release(holder);
            pool.shutdownNow();
        }

        assertEquals("PUBLISHED", workflowStatus(listingId));
        assertEquals("ACTIVE", listingStatus(listingId));
        assertNotNull(publishedAt(listingId));
        assertEquals(versionBefore + 1, listingVersion(listingId), "publication advanced the listing once");
        assertEquals(1L, auditRowsFor("LISTING_PUBLISHED", "LISTING", listingId));
        assertEquals(1L, auditCount("STAFF_ACCESS_GRANT_REVOKED", reviewer.grantId()));
        // The revocation committed second, so it applies to the next operation and not to the committed one.
        assertEquals(403, send("GET", QUEUE, reviewer.actor().token(), null).statusCode());
        assertEquals(deadlocksBefore, deadlockCount(), "the evidence hold must not provoke a deadlock");
    }

    /**
     * The ordering guarantee, revocation side. A revocation commits while the approval is inside its
     * transaction and already waiting for the evidence row; the wait re-evaluates the committed row, so
     * the approval observes the revocation, is refused, and commits nothing.
     */
    @Test
    void aRevocationThatCommitsFirstIsObservedUnderTheHoldAndRefusesThePublication() throws Exception {
        Actor provisioner = globalStaffAdmin("first-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "first");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("first", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long versionBefore = listingVersion(listingId);
        long deadlocksBefore = deadlockCount();

        ExecutorService pool = Executors.newFixedThreadPool(1);
        Connection revocation = grantRevocationHolder(reviewer.grantId(), provisioner.user().getId());
        try {
            Future<HttpResponse<String>> approval = pool.submit(
                    () -> send("POST", approvePath(listingId), reviewer.actor().token(), null));
            awaitSessionWaitingOn("staff_access_grants", Duration.ofSeconds(10));
            revocation.commit();

            HttpResponse<String> refused = approval.get(60, TimeUnit.SECONDS);
            assertEquals(403, refused.statusCode(),
                    "a revocation that committed first must refuse the publication: " + refused.body());
        } finally {
            release(revocation);
            pool.shutdownNow();
        }

        assertEquals("UNDER_REVIEW", workflowStatus(listingId));
        assertEquals("PENDING", listingStatus(listingId));
        assertNull(publishedAt(listingId));
        assertEquals(versionBefore, listingVersion(listingId), "the refused approval must not have mutated");
        assertEquals(0L, auditRowsFor("LISTING_PUBLISHED", "LISTING", listingId));
        assertEquals(deadlocksBefore, deadlockCount());
    }

    /** The same guarantee for the other governance fact the decision rests on: staff profile activation. */
    @Test
    void aStaffDeactivationThatCommitsFirstIsObservedUnderTheHoldAndRefusesThePublication() throws Exception {
        Actor provisioner = globalStaffAdmin("deactivate-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "deactivate");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("deactivate", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long versionBefore = listingVersion(listingId);
        long deadlocksBefore = deadlockCount();

        ExecutorService pool = Executors.newFixedThreadPool(1);
        Connection deactivation = staffDeactivationHolder(reviewer.actor().user().getId());
        try {
            Future<HttpResponse<String>> approval = pool.submit(
                    () -> send("POST", approvePath(listingId), reviewer.actor().token(), null));
            awaitSessionWaitingOn("employee_profiles", Duration.ofSeconds(10));
            deactivation.commit();

            HttpResponse<String> refused = approval.get(60, TimeUnit.SECONDS);
            assertEquals(403, refused.statusCode(),
                    "a staff deactivation that committed first must refuse the publication: " + refused.body());
        } finally {
            release(deactivation);
            pool.shutdownNow();
        }

        assertEquals("UNDER_REVIEW", workflowStatus(listingId));
        assertNull(publishedAt(listingId));
        assertEquals(versionBefore, listingVersion(listingId));
        assertEquals(0L, auditRowsFor("LISTING_PUBLISHED", "LISTING", listingId));
        assertEquals(deadlocksBefore, deadlockCount());
    }

    /**
     * Revoking one of two independently valid effective grants does not deny an actor whose other grant
     * still confers the capability: the decision is re-made from every effective grant, not one row.
     */
    @Test
    void anotherEffectiveGlobalGrantKeepsTheActorAuthorizedWhenOneIsRevoked() throws Exception {
        Actor provisioner = globalStaffAdmin("two-grant-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "two-grant");
        grantRows.saveAndFlush(new StaffAccessGrant(reviewer.actor().user(), StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null, null, Instant.now(),
                "TEST_P4", StaffGrantProvisioningSource.ADMIN_API));
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("two-grant", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());

        assertEquals(200, revokeGrant(provisioner, reviewer.grantId(), "P4_ONE_OF_TWO_REVOKED").statusCode());
        assertEquals(200, send("POST", approvePath(listingId), reviewer.actor().token(), null).statusCode());

        assertEquals("PUBLISHED", workflowStatus(listingId));
        assertEquals("ACTIVE", listingStatus(listingId));
        assertEquals(1L, auditRowsFor("LISTING_PUBLISHED", "LISTING", listingId));
        assertEquals(1L, auditCount("STAFF_ACCESS_GRANT_REVOKED", reviewer.grantId()));
    }

    // ==================================================== D: durable, atomic review attribution

    /**
     * Every one of the six approved review actions, driven over real HTTP, leaves exactly one immutable
     * audit row: the real acting User, the numeric Listing or draft row, a database timestamp, the
     * workflow states and optimistic versions either side of the decision, and the canonical locality
     * where the action rests on one.
     */
    @Test
    void everyReviewMutationWritesOneAttributableImmutableAuditEvent() throws Exception {
        Actor provisioner = globalStaffAdmin("audit-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "audit");
        Actor admin = legacyAdmin("audit-admin");
        Locality locality = canonicalLocality("Indore");
        Instant windowStart = Instant.now().minusSeconds(5);

        // The four extension actions, by the staff administrator whose live grant is the authority.
        RentalDetails staffListing = submittedListing("audit-staff", reviewer.actor().user(), locality);
        long staffListingId = staffListing.getId();
        long staffStartVersion = listingVersion(staffListingId);
        assertEquals(200, send("POST", startPath(staffListingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(staffListingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long staffLinkedVersion = listingVersion(staffListingId);
        assertEquals(200, send("POST", approvePath(staffListingId), reviewer.actor().token(), null).statusCode());

        // The two listing actions that stay ROLE_ADMIN-only, by the legacy administrator.
        RentalDetails adminListing = submittedListing("audit-admin", admin.user(), locality);
        long adminListingId = adminListing.getId();
        long adminStartVersion = listingVersion(adminListingId);
        assertEquals(200, send("POST", startPath(adminListingId), admin.token(), null).statusCode());
        assertEquals(200, send("POST", changesPath(adminListingId), admin.token(),
                "{\"note\":\"Please clarify the available date.\"}").statusCode());

        // Both revision actions, on complete drafts bound to the published listing. The first draft leaves
        // review before the second is created: a published property admits one open revision at a time.
        PropertyUploadDraft requested = completeRevisionDraft("audit-requested", reviewer.actor().user(),
                staffListingId, staffLinkedVersion, locality);
        assertEquals(200, send("POST", "/api/v1/admin/lessor-review/revisions/" + requested.getDraftId()
                + "/request-changes", admin.token(),
                "{\"note\":\"Please clarify the available date.\"}").statusCode());
        PropertyUploadDraft approved = completeRevisionDraft("audit-approved", reviewer.actor().user(),
                staffListingId, listingVersion(staffListingId), locality);
        assertEquals(200, send("POST", "/api/v1/admin/lessor-review/revisions/" + approved.getDraftId()
                + "/approve", admin.token(), null).statusCode());
        Instant windowEnd = Instant.now().plusSeconds(5);

        assertEquals(1L, auditRowsForBetween("LISTING_REVIEW_STARTED", "LISTING", staffListingId,
                windowStart, windowEnd), "the audit timestamp must be the database's own");
        assertEquals(reviewer.actor().user().getId(),
                auditActorUserId("LISTING_REVIEW_STARTED", "LISTING", staffListingId));
        assertEquals("ADMIN_REVIEW_DECISION",
                auditReasonCode("LISTING_REVIEW_STARTED", "LISTING", staffListingId));
        JsonNode started = auditDetails("LISTING_REVIEW_STARTED", "LISTING", staffListingId);
        assertEquals("SUBMITTED", started.path("fromStatus").asText());
        assertEquals("UNDER_REVIEW", started.path("toStatus").asText());
        assertEquals(staffStartVersion, started.path("previousVersion").asLong());
        assertEquals(staffStartVersion + 1, started.path("resultingVersion").asLong());

        assertEquals(1L, auditRowsFor("LISTING_LOCALITY_LINKED", "LISTING", staffListingId));
        assertEquals(reviewer.actor().user().getId(),
                auditActorUserId("LISTING_LOCALITY_LINKED", "LISTING", staffListingId));
        JsonNode linked = auditDetails("LISTING_LOCALITY_LINKED", "LISTING", staffListingId);
        assertEquals(locality.getId(), linked.path("canonicalLocalityId").asLong());
        assertTrue(linked.path("previousCanonicalLocalityId").isMissingNode(),
                "the listing had no locality before this link, so none may be claimed");
        assertEquals(staffStartVersion + 1, linked.path("previousVersion").asLong());

        assertEquals(1L, auditRowsFor("LISTING_PUBLISHED", "LISTING", staffListingId));
        assertEquals(reviewer.actor().user().getId(),
                auditActorUserId("LISTING_PUBLISHED", "LISTING", staffListingId));
        JsonNode published = auditDetails("LISTING_PUBLISHED", "LISTING", staffListingId);
        assertEquals("UNDER_REVIEW", published.path("fromStatus").asText());
        assertEquals("PUBLISHED", published.path("toStatus").asText());
        assertEquals(locality.getId(), published.path("canonicalLocalityId").asLong());
        assertEquals(staffLinkedVersion, published.path("previousVersion").asLong());
        assertEquals(staffLinkedVersion + 1, published.path("resultingVersion").asLong());

        assertEquals(1L, auditRowsFor("LISTING_CHANGES_REQUESTED", "LISTING", adminListingId));
        assertEquals(admin.user().getId(),
                auditActorUserId("LISTING_CHANGES_REQUESTED", "LISTING", adminListingId));
        JsonNode changes = auditDetails("LISTING_CHANGES_REQUESTED", "LISTING", adminListingId);
        assertEquals("UNDER_REVIEW", changes.path("fromStatus").asText());
        assertEquals("CHANGES_REQUIRED", changes.path("toStatus").asText());
        assertEquals("ADMIN_REVIEW_DECISION",
                auditReasonCode("LISTING_CHANGES_REQUESTED", "LISTING", adminListingId));
        assertFalse(changes.toString().contains("Please clarify"),
                "a review note must never enter the immutable audit details");

        assertEquals(1L, auditRowsFor("LISTING_REVIEW_STARTED", "LISTING", adminListingId));
        assertEquals(admin.user().getId(),
                auditActorUserId("LISTING_REVIEW_STARTED", "LISTING", adminListingId));
        assertEquals(adminStartVersion + 1, auditDetails("LISTING_REVIEW_STARTED", "LISTING", adminListingId)
                .path("resultingVersion").asLong());

        assertEquals(1L, auditRowsFor("REVISION_CHANGES_REQUESTED", "PROPERTY_UPLOAD_DRAFT", requested.getId()));
        assertEquals(admin.user().getId(),
                auditActorUserId("REVISION_CHANGES_REQUESTED", "PROPERTY_UPLOAD_DRAFT", requested.getId()));
        JsonNode revisionRequested = auditDetails("REVISION_CHANGES_REQUESTED", "PROPERTY_UPLOAD_DRAFT",
                requested.getId());
        assertEquals("REVIEW", revisionRequested.path("fromStatus").asText());
        assertEquals("CHANGES_REQUIRED", revisionRequested.path("toStatus").asText());
        assertEquals("ADMIN_REVISION_DECISION", auditReasonCode("REVISION_CHANGES_REQUESTED",
                "PROPERTY_UPLOAD_DRAFT", requested.getId()));

        assertEquals(1L, auditRowsFor("REVISION_APPROVED", "PROPERTY_UPLOAD_DRAFT", approved.getId()));
        assertEquals(admin.user().getId(),
                auditActorUserId("REVISION_APPROVED", "PROPERTY_UPLOAD_DRAFT", approved.getId()));
        JsonNode revisionApproved = auditDetails("REVISION_APPROVED", "PROPERTY_UPLOAD_DRAFT", approved.getId());
        assertEquals("REVIEW", revisionApproved.path("fromStatus").asText());
        assertEquals("APPROVED", revisionApproved.path("toStatus").asText());
        assertEquals(locality.getId(), revisionApproved.path("canonicalLocalityId").asLong());
        assertEquals("ADMIN_REVISION_DECISION", auditReasonCode("REVISION_APPROVED", "PROPERTY_UPLOAD_DRAFT",
                approved.getId()));
        // The revision really landed: the same draft row is no longer awaiting review.
        assertEquals("APPROVED", draftStatus(approved.getDraftId()));
    }

    /**
     * The rows D writes inherit the operational audit trail's immutability: a recorded review decision
     * cannot be rewritten or erased afterwards, by this application or by anything else.
     */
    @Test
    void aRecordedReviewDecisionCannotBeRewrittenOrDeleted() throws Exception {
        Actor provisioner = globalStaffAdmin("immutable-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "immutable");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("immutable", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(1L, auditRowsFor("LISTING_REVIEW_STARTED", "LISTING", listingId));

        assertThrows(DataAccessException.class, () -> jdbc.update("update operational_audit_events "
                        + "set reason_code = 'REWRITTEN' where action_code = ? and target_id = ?",
                "LISTING_REVIEW_STARTED", listingId));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from operational_audit_events "
                + "where action_code = ? and target_id = ?", "LISTING_REVIEW_STARTED", listingId));

        assertEquals(1L, auditRowsFor("LISTING_REVIEW_STARTED", "LISTING", listingId));
        assertEquals("ADMIN_REVIEW_DECISION",
                auditReasonCode("LISTING_REVIEW_STARTED", "LISTING", listingId));
    }

    /**
     * The documented no-op and refusal paths fabricate nothing: a refused staff verdict on a legacy-only
     * action, a conflicting transition and a rejected note all leave the audit table untouched.
     */
    @Test
    void aRefusedOrConflictedReviewActionWritesNoAuditRow() throws Exception {
        Actor provisioner = globalStaffAdmin("noaudit-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "noaudit");
        Actor admin = legacyAdmin("noaudit-admin");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("noaudit", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        assertEquals(200, send("POST", approvePath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(1L, auditRowsFor("LISTING_PUBLISHED", "LISTING", listingId));
        long versionAfterPublication = listingVersion(listingId);

        // A staff administrator is refused on the three legacy-only operations, none of which is recorded.
        assertEquals(403, send("POST", changesPath(listingId), reviewer.actor().token(),
                "{\"note\":\"Please clarify the available date.\"}").statusCode());
        PropertyUploadDraft draft = completeRevisionDraft("noaudit", admin.user(), listingId,
                versionAfterPublication, locality);
        assertEquals(403, send("POST", "/api/v1/admin/lessor-review/revisions/" + draft.getDraftId() + "/approve",
                reviewer.actor().token(), null).statusCode());
        assertEquals(403, send("POST", "/api/v1/admin/lessor-review/revisions/" + draft.getDraftId()
                + "/request-changes", reviewer.actor().token(),
                "{\"note\":\"Please clarify the available date.\"}").statusCode());
        assertEquals(0L, auditRowsFor("LISTING_CHANGES_REQUESTED", "LISTING", listingId));
        assertEquals(0L, auditRowsFor("REVISION_APPROVED", "PROPERTY_UPLOAD_DRAFT", draft.getId()));
        assertEquals(0L, auditRowsFor("REVISION_CHANGES_REQUESTED", "PROPERTY_UPLOAD_DRAFT", draft.getId()));

        // A conflicting transition, and a note too short to be a decision, are both refused and unrecorded.
        assertEquals(409, send("POST", approvePath(listingId), admin.token(), null).statusCode());
        assertEquals(400, send("POST", changesPath(listingId), admin.token(),
                "{\"note\":\"too short\"}").statusCode());
        assertEquals(1L, auditRowsFor("LISTING_PUBLISHED", "LISTING", listingId));
        assertEquals(0L, auditRowsFor("LISTING_CHANGES_REQUESTED", "LISTING", listingId));
        assertEquals("REVIEW", draftStatus(draft.getDraftId()));
        assertEquals(versionAfterPublication, listingVersion(listingId));
    }

    /**
     * Atomicity over real PostgreSQL: the audit write is made to fail at the database, and the review
     * mutation it describes must roll back with it. The listing is left exactly as it was, and the same
     * command then succeeds once the failing trigger is removed - so the rollback was the audit's doing
     * rather than a coincidental failure of the command.
     */
    @Test
    void theAuditWriteAndTheReviewMutationCommitOrRollBackTogether() throws Exception {
        Actor provisioner = globalStaffAdmin("atomic-provisioner");
        StaffReviewer reviewer = grantedStaffReviewer(provisioner, "atomic");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("atomic", reviewer.actor().user(), locality);
        long listingId = listing.getId();
        assertEquals(200, send("POST", startPath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals(200, send("POST", linkPath(listingId), reviewer.actor().token(),
                "{\"canonicalLocalityId\":" + locality.getId() + "}").statusCode());
        long versionBefore = listingVersion(listingId);
        long deadlocksBefore = deadlockCount();

        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION p4_fail_review_audit() RETURNS trigger AS $$ "
                    + "BEGIN IF NEW.action_code = 'LISTING_PUBLISHED' THEN "
                    + "RAISE EXCEPTION 'p4 forced audit failure'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER p4_fail_review_audit BEFORE INSERT ON operational_audit_events "
                    + "FOR EACH ROW EXECUTE FUNCTION p4_fail_review_audit()");

            HttpResponse<String> failed = send("POST", approvePath(listingId), reviewer.actor().token(), null);
            assertTrue(failed.statusCode() >= 500,
                    "the forced audit failure must surface as a server error: " + failed.statusCode());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS p4_fail_review_audit ON operational_audit_events");
            jdbc.execute("DROP FUNCTION IF EXISTS p4_fail_review_audit()");
        }

        assertEquals("UNDER_REVIEW", workflowStatus(listingId), "the mutation must have rolled back with the audit");
        assertEquals("PENDING", listingStatus(listingId));
        assertNull(publishedAt(listingId));
        assertEquals(versionBefore, listingVersion(listingId));
        assertEquals(0L, auditRowsFor("LISTING_PUBLISHED", "LISTING", listingId));
        assertEquals(deadlocksBefore, deadlockCount());

        assertEquals(200, send("POST", approvePath(listingId), reviewer.actor().token(), null).statusCode());
        assertEquals("PUBLISHED", workflowStatus(listingId));
        assertEquals(versionBefore + 1, listingVersion(listingId));
        assertEquals(1L, auditRowsFor("LISTING_PUBLISHED", "LISTING", listingId));
    }

    // ===================================== final acceptance: no-op audit and bounded review failures

    @Test
    void identicalLocalityRetriesDoNotCreateMutationAuditEvents() throws Exception {
        Actor reviewer = globalStaffAdmin("noop-acceptance");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("noop-acceptance", reviewer.user(), locality);
        long id = listing.getId();
        assertEquals(200, send("POST", startPath(id), reviewer.token(), null).statusCode());
        assertEquals(200, link(id, locality, reviewer).statusCode());
        attachedPhoto(id);
        // A media repair is material even when the Listing version does not advance.
        assertEquals(200, link(id, locality, reviewer).statusCode());
        String before = reviewSnapshot(id);
        long version = listingVersion(id);
        long events = auditRowsFor("LISTING_LOCALITY_LINKED", "LISTING", id);

        assertEquals(200, link(id, locality, reviewer).statusCode());

        assertEquals(before, reviewSnapshot(id), "an exact retry must preserve Listing, media and audit rows");
        assertEquals(version, listingVersion(id));
        assertEquals(events, auditRowsFor("LISTING_LOCALITY_LINKED", "LISTING", id));
    }

    @Test
    void sameLocalityRetriesAuditGenuineListingAndMediaRepairsExactlyOnce() throws Exception {
        Actor reviewer = globalStaffAdmin("repair-acceptance");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("repair-acceptance", reviewer.user(), locality);
        long id = listing.getId();
        assertEquals(200, link(id, locality, reviewer).statusCode());
        long firstVersion = listingVersion(id);
        assertEquals(1L, auditRowsFor("LISTING_LOCALITY_LINKED", "LISTING", id));
        RentalDetails stale = (RentalDetails) listings.findById(id).orElseThrow();
        stale.setSector("Unconfirmed");
        stale.setTitle("2BHK flat in Unconfirmed");
        stale.setLocationResolution(LocationResolution.MANUAL_PENDING);
        stale.setLocationProvider("MAPTILER");
        stale.setLocationProviderPlaceId("test-place");
        listings.saveAndFlush(stale);
        long beforeRepair = listingVersion(id);
        PropertyMediaAsset photo = attachedPhoto(id);

        assertEquals(200, link(id, locality, reviewer).statusCode());
        assertEquals(beforeRepair + 1, listingVersion(id));
        assertTrue(beforeRepair > firstVersion);
        assertEquals(locality.getSectorName(), sector(id));
        assertEquals("2BHK flat in " + locality.getSectorName(), jdbc.queryForObject(
                "select title from listings where id=?", String.class, id));
        assertNull(jdbc.queryForObject("select location_provider from listings where id=?", String.class, id));
        assertNull(jdbc.queryForObject("select location_provider_place_id from listings where id=?", String.class, id));
        assertEquals(locality.getSectorName(), mediaSector(photo.getId()));
        assertEquals(2L, auditRowsFor("LISTING_LOCALITY_LINKED", "LISTING", id));
        JsonNode repaired = auditDetails("LISTING_LOCALITY_LINKED", "LISTING", id);
        assertEquals(beforeRepair, repaired.path("previousVersion").asLong());
        assertEquals(beforeRepair + 1, repaired.path("resultingVersion").asLong());

        PropertyMediaAsset extra = attachedPhoto(id);
        long beforeMediaRepair = listingVersion(id);
        assertEquals(200, link(id, locality, reviewer).statusCode());
        assertEquals(beforeMediaRepair, listingVersion(id), "media-only repair must not invent a Listing update");
        assertEquals(locality.getSectorName(), mediaSector(extra.getId()));
        assertEquals(3L, auditRowsFor("LISTING_LOCALITY_LINKED", "LISTING", id));
        JsonNode mediaRepair = auditDetails("LISTING_LOCALITY_LINKED", "LISTING", id);
        assertEquals(beforeMediaRepair, mediaRepair.path("previousVersion").asLong());
        assertEquals(beforeMediaRepair, mediaRepair.path("resultingVersion").asLong());
        assertEquals(reviewer.user().getId(), auditActorUserId("LISTING_LOCALITY_LINKED", "LISTING", id));
        String snapshot = reviewSnapshot(id);
        assertEquals(200, link(id, locality, reviewer).statusCode());
        assertEquals(snapshot, reviewSnapshot(id));
    }

    @Test
    void concurrentIdenticalLinksAndRetriesRecordOnlyTheMaterialLink() throws Exception {
        Actor reviewer = globalStaffAdmin("same-link-race");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("same-link-race", reviewer.user(), locality);
        long id = listing.getId();
        long beforeVersion = listingVersion(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 2; round++) {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<HttpResponse<String>>> commands = new ArrayList<>();
                for (int i = 0; i < 2; i++) commands.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return link(id, locality, reviewer);
                }));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                for (Future<HttpResponse<String>> command : commands) {
                    HttpResponse<String> response = command.get(15, TimeUnit.SECONDS);
                    assertEquals(200, response.statusCode(), response.body());
                }
                assertEquals(beforeVersion + 1, listingVersion(id));
                assertEquals(1L, auditRowsFor("LISTING_LOCALITY_LINKED", "LISTING", id));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aLocalityAuditFailureRollsBackBothFirstLinkAndMediaOnlyRepair() throws Exception {
        Actor reviewer = globalStaffAdmin("link-atomic");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("link-atomic", reviewer.user(), locality);
        long id = listing.getId();
        PropertyMediaAsset photo = attachedPhoto(id);
        for (int phase = 0; phase < 2; phase++) {
            String before = reviewSnapshot(id);
            try {
                installLinkAuditDelayOrFailure(id, "RAISE EXCEPTION 'forced locality audit failure';");
                HttpResponse<String> failed = link(id, locality, reviewer);
                assertEquals(500, failed.statusCode(), failed.body());
            } finally {
                removeLinkAuditDelayOrFailure();
            }
            assertEquals(before, reviewSnapshot(id), "Listing, media and audit must roll back together");
            assertEquals(200, link(id, locality, reviewer).statusCode());
            assertEquals(phase + 1L, auditRowsFor("LISTING_LOCALITY_LINKED", "LISTING", id));
            assertEquals(locality.getSectorName(), mediaSector(photo.getId()));
            if (phase == 0) photo = attachedPhoto(id);
        }
        String beforeRetry = reviewSnapshot(id);
        assertEquals(200, link(id, locality, reviewer).statusCode());
        assertEquals(beforeRetry, reviewSnapshot(id));
    }

    @Test
    void heldReviewLocksReturnSanitized503AndRollBackEveryMutationFamily() throws Exception {
        Actor reviewer = globalStaffAdmin("review-timeout");
        Actor admin = legacyAdmin("legacy-timeout");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("review-timeout", reviewer.user(), locality);
        long id = listing.getId();
        attachedPhoto(id);
        // Exercise the original five-second Listing-lock reproduction first, then the other families.
        assertEquals(200, send("POST", startPath(id), reviewer.token(), null).statusCode());
        assertEquals(200, link(id, locality, reviewer).statusCode());
        String[][] listingCommands = {
                {approvePath(id), null, reviewer.token()},
                {linkPath(id), "{\"canonicalLocalityId\":" + locality.getId() + "}", reviewer.token()},
                {changesPath(id), "{\"note\":\"Please clarify the available date.\"}", admin.token()}
        };
        for (String[] command : listingCommands) {
            String before = reviewSnapshot(id);
            try (Connection holder = listingRowLockHolder(id)) {
                assertReviewTimeout(command[0], command[2], command[1], 3_000, 15_000);
            }
            assertEquals(before, reviewSnapshot(id));
        }
        PropertyUploadDraft draft = reviewDraft("review-timeout", reviewer.user(), id, listingVersion(id));
        String[][] revisionCommands = {
                {"/api/v1/admin/lessor-review/revisions/" + draft.getDraftId() + "/approve", null},
                {"/api/v1/admin/lessor-review/revisions/" + draft.getDraftId() + "/request-changes",
                        "{\"note\":\"Please clarify the available date.\"}"}
        };
        for (String[] command : revisionCommands) {
            String before = reviewSnapshot(id);
            try (Connection holder = DriverManager.getConnection(schemaUrl(), target.username(), target.password())) {
                holder.setAutoCommit(false);
                try (Statement statement = holder.createStatement()) {
                    statement.execute("select id from property_upload_drafts where id=" + draft.getId() + " for update");
                }
                assertReviewTimeout(command[0], admin.token(), command[1], 3_000, 15_000);
            }
            assertEquals(before, reviewSnapshot(id));
        }
        assertEquals(200, send("POST", approvePath(id), reviewer.token(), null).statusCode());
        assertEquals(1L, auditRowsFor("LISTING_PUBLISHED", "LISTING", id));
    }

    @Test
    void statementTimeoutDuringLocalityAuditReturns503AndRollsBackListingAndMedia() throws Exception {
        Actor reviewer = globalStaffAdmin("statement-timeout");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("statement-timeout", reviewer.user(), locality);
        long id = listing.getId();
        attachedPhoto(id);
        String before = reviewSnapshot(id);
        try {
            installLinkAuditDelayOrFailure(id, "PERFORM pg_sleep(12);");
            assertReviewTimeout(linkPath(id), reviewer.token(),
                    "{\"canonicalLocalityId\":" + locality.getId() + "}", 9_000, 18_000);
        } finally {
            removeLinkAuditDelayOrFailure();
        }
        assertEquals(before, reviewSnapshot(id));
        assertEquals(200, link(id, locality, reviewer).statusCode());
        assertEquals(1L, auditRowsFor("LISTING_LOCALITY_LINKED", "LISTING", id));
    }

    @Test
    void operatorCancellationOfReviewAuditStays500AndRollsBackWithoutTimeoutMisclassification() throws Exception {
        Actor reviewer = globalStaffAdmin("review-cancel");
        Locality locality = canonicalLocality("Indore");
        RentalDetails listing = submittedListing("review-cancel", reviewer.user(), locality);
        long id = listing.getId();
        attachedPhoto(id);
        String before = reviewSnapshot(id);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            installLinkAuditDelayOrFailure(id, "PERFORM pg_sleep(30);");
            Future<HttpResponse<String>> command = pool.submit(() -> link(id, locality, reviewer));
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            Integer pid = null;
            while (System.nanoTime() < deadline && pid == null) {
                List<Integer> pids = jdbc.queryForList("select distinct a.pid from pg_stat_activity a "
                        + "join pg_locks l on l.pid=a.pid where a.datname=current_database() "
                        + "and l.relation='operational_audit_events'::regclass and a.wait_event='PgSleep'",
                        Integer.class);
                if (!pids.isEmpty()) pid = pids.get(0);
                else Thread.sleep(20);
            }
            assertNotNull(pid, "the review must be observed in the test-schema audit trigger before cancellation");
            assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("select pg_cancel_backend(?)", Boolean.class, pid)));
            HttpResponse<String> canceled = command.get(15, TimeUnit.SECONDS);
            assertEquals(500, canceled.statusCode(), canceled.body());
            assertFalse(canceled.body().contains("database was busy"));
            assertFalse(canceled.body().contains("user request"));
        } finally {
            pool.shutdownNow();
            removeLinkAuditDelayOrFailure();
        }
        assertEquals(before, reviewSnapshot(id));
    }

    private HttpResponse<String> link(long id, Locality locality, Actor reviewer) throws Exception {
        return send("POST", linkPath(id), reviewer.token(), "{\"canonicalLocalityId\":" + locality.getId() + "}");
    }

    /** Exact persisted evidence, including Listing/draft versions, every media row and target audit row. */
    private String reviewSnapshot(long listingId) {
        return jdbc.queryForObject("select jsonb_build_object('listing',(select to_jsonb(l) from listings l where id=?),"
                + "'media',(select jsonb_agg(to_jsonb(m) order by id) from property_media_assets m where listing_id=?),"
                + "'drafts',(select jsonb_agg(to_jsonb(d) order by id) from property_upload_drafts d where published_property_id=?),"
                + "'audit',(select jsonb_agg(to_jsonb(a) order by id) from operational_audit_events a "
                + "where (target_type='LISTING' and target_id=?) or (target_type='PROPERTY_UPLOAD_DRAFT' "
                + "and target_id in (select id from property_upload_drafts where published_property_id=?))))::text",
                String.class, listingId, listingId, listingId, listingId, listingId);
    }

    private void assertReviewTimeout(String path, String token, String payload, long minMillis, long maxMillis)
            throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = send("POST", path, token, payload);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.println("REVIEW_TIMEOUT_EVIDENCE path=" + path + " elapsed_ms=" + elapsed
                + " status=" + response.statusCode() + " body=" + response.body());
        assertTrue(elapsed >= minMillis && elapsed < maxMillis, "transaction-local timeout elapsed=" + elapsed);
        assertEquals(503, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        JsonNode body = mapper.readTree(response.body());
        assertEquals(503, body.path("status").asInt());
        assertEquals("Service Unavailable", body.path("error").asText());
        assertEquals("The operation could not be completed because the database was busy. Please try again shortly.",
                body.path("message").asText());
        assertEquals(path, body.path("path").asText());
        assertTrue(body.hasNonNull("timestamp"));
        assertEquals(5, body.size(), "no SQLSTATE, query, driver or stack fields may escape");
    }

    private void installLinkAuditDelayOrFailure(long listingId, String operation) {
        jdbc.execute("CREATE FUNCTION p4_link_audit_probe() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.action_code='LISTING_LOCALITY_LINKED' AND NEW.target_id=" + listingId
                + " THEN " + operation + " END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER p4_link_audit_probe BEFORE INSERT ON operational_audit_events "
                + "FOR EACH ROW EXECUTE FUNCTION p4_link_audit_probe()");
    }

    private void removeLinkAuditDelayOrFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS p4_link_audit_probe ON operational_audit_events");
        jdbc.execute("DROP FUNCTION IF EXISTS p4_link_audit_probe()");
    }

    // ==================================================================== harness

    private record Actor(User user, String token) {}

    private record StaffReviewer(Actor actor, Long grantId) {}

    /** A verdict together with the credential of the request that decided it. */
    private record RequestVerdict(Authentication credential, Reviewer verdict) {}

    /** Decides the authority in a request of its own and leaves no principal installed behind it. */
    private RequestVerdict decidedInEarlierRequest(Authentication credential) {
        installPrincipal(credential);
        try {
            return new RequestVerdict(credential, reviewAuthority.require(credential));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private SupportedCity canonicalCity(String code) {
        return cities.findAllByActiveTrueOrderByDisplayNameAsc().stream()
                .filter(city -> code.equalsIgnoreCase(city.getCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the migration must seed an active " + code + " City"));
    }

    private Locality canonicalLocality(String cityName) {
        return localities.findFirstByCityIgnoreCaseOrderByCityAscIdAsc(cityName)
                .orElseThrow(() -> new IllegalStateException("the migration must seed a " + cityName + " locality"));
    }

    /** A second seeded locality of the same City, read through the production repository. */
    private Locality anotherLocalityIn(String city, Long excludingLocalityId) {
        Long id = jdbc.queryForObject("select id from localities where city = ? and id <> ? order by id limit 1",
                Long.class, city, excludingLocalityId);
        return localities.findById(id)
                .orElseThrow(() -> new IllegalStateException("the second " + city + " locality must exist"));
    }

    /** A photo already attached to the listing, carrying the location evidence a link refreshes. */
    private PropertyMediaAsset attachedPhoto(long listingId) {
        PropertyMediaAsset photo = new PropertyMediaAsset(listingId,
                "https://res.cloudinary.com/pathome-test/image/upload/p4-relink.jpg",
                com.indore.pathome.spaces.entity.MediaType.IMAGE, RoomTag.GENERAL, "P4 relink photo");
        photo.setCity("Indore");
        photo.setSector("Unlinked");
        return mediaAssets.saveAndFlush(photo);
    }

    private String mediaSector(Long mediaId) {
        return jdbc.queryForObject("select sector from property_media_assets where id = ?", String.class, mediaId);
    }

    /** A credential built by the caller itself, in the shape the container establishes for a real login. */
    private static Authentication credential(String authority, Long userId) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "codex.lessor.forged@pathome.test", null, List.of(new SimpleGrantedAuthority(authority)));
        token.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), userId));
        return token;
    }

    private static void installPrincipal(Authentication authentication) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private Actor actor(String label, Role role, String profileRole, Boolean staffActive) {
        User user = new User();
        user.setEmail("p4-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName("P4 " + label);
        user.setPhoneNumber("+91987555" + String.format("%04d", Math.abs(label.hashCode()) % 10_000));
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

    /** A tenant with an active staff profile but no capability at all. */
    private Actor staffSubject(String label, boolean staffActive) {
        return actor(label, Role.ROLE_TENANT, "OPERATIONS", staffActive);
    }

    /** A repository-provisioned global Staff Admin, used only as the actor of staff-admin API calls. */
    private Actor globalStaffAdmin(String label) {
        Actor admin = staffSubject(label + "-admin", true);
        grantRows.saveAndFlush(new StaffAccessGrant(admin.user(), StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null, null, Instant.now(),
                "TEST_P4", StaffGrantProvisioningSource.ADMIN_API));
        return admin;
    }

    /** A genuine legacy uploader/reviewer: current ADMIN role, active profile and global grant. */
    private Actor legacyAdmin(String label) {
        Actor admin = actor(label, Role.ROLE_ADMIN, "LEGACY_ADMIN", true);
        grantRows.saveAndFlush(new StaffAccessGrant(admin.user(), StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null, null, Instant.now(),
                "TEST_P1", StaffGrantProvisioningSource.ADMIN_API));
        return admin;
    }

    private static Authentication actorCredential(Actor actor) {
        return credential(actor.user().getEmail(), "ROLE_ADMIN", actor.user().getId());
    }

    private static Authentication credential(String username, String authority, Long userId) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(authority)));
        token.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), userId));
        return token;
    }

    /** A global Staff Admin granted through the genuine staff-administration API. */
    private StaffReviewer grantedStaffReviewer(Actor provisioner, String label) throws Exception {
        Actor reviewer = staffSubject(label + "-reviewer", true);
        HttpResponse<String> created = send("POST",
                "/api/v1/staff/admin/users/" + reviewer.user().getId() + "/grants", provisioner.token(),
                "{\"capability\":\"STAFF_ADMIN\",\"scopeType\":\"GLOBAL\",\"reasonCode\":\"P4_REVIEW_AUTHORITY\"}");
        assertEquals(200, created.statusCode(), created.body());
        JsonNode body = mapper.readTree(created.body());
        assertEquals("STAFF_ADMIN", body.path("capability").asText());
        assertEquals("GLOBAL", body.path("scopeType").asText());
        assertTrue(body.path("revokedAt").isNull());
        return new StaffReviewer(reviewer, body.path("id").asLong());
    }

    private HttpResponse<String> revokeGrant(Actor provisioner, Long grantId, String reasonCode) throws Exception {
        return send("POST", "/api/v1/staff/admin/grants/" + grantId + "/revoke", provisioner.token(),
                "{\"reasonCode\":\"" + reasonCode + "\"}");
    }

    private void deactivateStaffProfile(Actor subject) {
        EmployeeProfile profile = employees.findByUserId(subject.user().getId()).orElseThrow();
        profile.setStaffActive(false);
        employees.saveAndFlush(profile);
    }

    private void cityScoped(Actor subject, StaffCapability capability, SupportedCity city) {
        grantRows.saveAndFlush(new StaffAccessGrant(subject.user(), capability, StaffScopeType.CITY, city, null,
                Instant.now().minusSeconds(60), null, null, Instant.now(), "TEST_P4",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private void teamScoped(Actor subject, StaffCapability capability, OperatingTeam team) {
        grantRows.saveAndFlush(new StaffAccessGrant(subject.user(), capability, StaffScopeType.TEAM, null, team,
                Instant.now().minusSeconds(60), null, null, Instant.now(), "TEST_P4",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private OperatingTeam team(SupportedCity city, String code) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return teams.saveAndFlush(new OperatingTeam(city, code + "-" + suffix, "P4 " + code, true));
    }

    /** A submitted long-term rental in the review workflow, awaiting administrator action. */
    private RentalDetails submittedListing(String label, User owner, Locality locality) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle("P4 " + label + " " + UUID.randomUUID());
        listing.setOwnerUserId(owner.getId());
        listing.setPropertyType(PropertyType.FLAT);
        listing.setBhkCount("2BHK");
        listing.setRentalMode(RentalMode.LONG_TERM_RENTAL);
        listing.setCity(locality.getCity());
        listing.setSector(locality.getSectorName());
        listing.setAddress("P4 test address");
        listing.setMonthlyRent(new BigDecimal("18000"));
        listing.setSecurityDeposit(new BigDecimal("36000"));
        listing.setLocationResolution(LocationResolution.MANUAL_PENDING);
        listing.setWorkflowStatus(ListingWorkflowStatus.SUBMITTED);
        listing.setStatus(ListingStatus.PENDING);
        return listings.saveAndFlush(listing);
    }

    private PropertyUploadDraft reviewDraft(String label, User owner, long publishedPropertyId,
                                            long revisionBaseVersion) {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("p4-" + label + "-" + UUID.randomUUID());
        draft.setLandlordUserId(owner.getId());
        draft.setPublishedPropertyId(publishedPropertyId);
        draft.setRevisionBaseVersion(revisionBaseVersion);
        draft.setStatus("REVIEW");
        draft.setTitleSummary("P4 " + label + " revision");
        draft.setPayload("{}");
        return drafts.saveAndFlush(draft);
    }

    /**
     * A complete, review-ready revision draft: a real payload written the way the production draft service
     * writes it, plus the single uploaded cover photo the completeness rule requires.
     */
    private PropertyUploadDraft completeRevisionDraft(String label, User owner, long publishedPropertyId,
                                                      long revisionBaseVersion, Locality locality) throws Exception {
        String draftId = "p4-" + label + "-" + UUID.randomUUID();
        LandlordDraftData data = new LandlordDraftData(
                new LandlordDraftData.Basics(PropertyType.FLAT, RentalMode.LONG_TERM_RENTAL, "2BHK"),
                new LandlordDraftData.Pricing(new BigDecimal("21000"), new BigDecimal("42000")),
                new LandlordDraftData.Location(locality.getCity(), locality.getId(), locality.getSectorName(),
                        "14 Review Test Road", "Beside the review test landmark"),
                new LandlordDraftData.Details(LocalDate.now().plusDays(7), "UNFURNISHED", null, null, null,
                        "Power backup", "A complete revision for the review audit acceptance."));
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(draftId);
        draft.setLandlordUserId(owner.getId());
        draft.setPublishedPropertyId(publishedPropertyId);
        draft.setRevisionBaseVersion(revisionBaseVersion);
        draft.setStatus("REVIEW");
        draft.setTitleSummary("P4 " + label + " revision");
        draft.setPayload(mapper.writeValueAsString(data));
        PropertyUploadDraft saved = drafts.saveAndFlush(draft);

        PropertyDraftMedia cover = new PropertyDraftMedia();
        cover.setMediaId("p4-" + label + "-cover-" + UUID.randomUUID());
        cover.setDraftId(draftId);
        cover.setLandlordUserId(owner.getId());
        cover.setOriginalFilename("cover.jpg");
        cover.setContentType("image/jpeg");
        cover.setCloudinaryUrl("https://res.cloudinary.com/pathome-test/image/upload/p4-review-cover.jpg");
        cover.setUploadStatus("UPLOADED");
        cover.setIsCover(true);
        cover.setSortOrder(0);
        cover.setRoomTag("GENERAL");
        draftMedia.saveAndFlush(cover);
        return saved;
    }

    private static String startPath(long listingId) {
        return "/api/v1/admin/lessor-review/listings/" + listingId + "/start-review";
    }

    private static String approvePath(long listingId) {
        return "/api/v1/admin/lessor-review/listings/" + listingId + "/approve";
    }

    private static String linkPath(long listingId) {
        return "/api/v1/admin/lessor-review/listings/" + listingId + "/link-locality";
    }

    private static String changesPath(long listingId) {
        return "/api/v1/admin/lessor-review/listings/" + listingId + "/request-changes";
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    /**
     * What these non-approved endpoints have to prove here is admission, not the revision workflow: an
     * administrator must not be refused by the authorization layer.
     */
    private static void assertAdmittedByAuthorization(HttpResponse<String> response) {
        assertTrue(response.statusCode() != 401 && response.statusCode() != 403,
                "a legacy administrator must be admitted: " + response.statusCode() + " " + response.body());
    }

    private String workflowStatus(long listingId) {
        return jdbc.queryForObject("select workflow_status from listings where id = ?", String.class, listingId);
    }

    private String listingStatus(long listingId) {
        return jdbc.queryForObject("select status from listings where id = ?", String.class, listingId);
    }

    private Long canonicalLocalityId(long listingId) {
        return jdbc.queryForObject("select canonical_locality_id from listings where id = ?", Long.class, listingId);
    }

    private String sector(long listingId) {
        return jdbc.queryForObject("select sector from listings where id = ?", String.class, listingId);
    }

    private String listingReviewNote(long listingId) {
        return jdbc.queryForObject("select review_note from listings where id = ?", String.class, listingId);
    }

    private String draftStatus(String draftId) {
        return jdbc.queryForObject("select status from property_upload_drafts where draft_id = ?",
                String.class, draftId);
    }

    private String draftReviewNote(String draftId) {
        return jdbc.queryForObject("select review_note from property_upload_drafts where draft_id = ?",
                String.class, draftId);
    }

    private LocalDateTime publishedAt(long listingId) {
        return jdbc.queryForObject("select published_at from listings where id = ?", LocalDateTime.class, listingId);
    }

    private long listingVersion(long listingId) {
        Long version = jdbc.queryForObject("select version from listings where id = ?", Long.class, listingId);
        return version == null ? 0L : version;
    }

    private long auditCount(String actionCode, Long targetId) {
        Long count = jdbc.queryForObject("select count(*) from operational_audit_events where action_code = ? "
                + "and target_type = 'STAFF_ACCESS_GRANT' and target_id = ?", Long.class, actionCode, targetId);
        return count == null ? 0L : count;
    }

    /** How many audit rows this exact action recorded against this exact numeric target. */
    private long auditRowsFor(String actionCode, String targetType, long targetId) {
        Long count = jdbc.queryForObject("select count(*) from operational_audit_events where action_code = ? "
                + "and target_type = ? and target_id = ?", Long.class, actionCode, targetType, targetId);
        return count == null ? 0L : count;
    }

    /** The same count restricted to the database timestamps inside the window the action ran in. */
    private long auditRowsForBetween(String actionCode, String targetType, long targetId,
                                     Instant from, Instant to) {
        Long count = jdbc.queryForObject("select count(*) from operational_audit_events where action_code = ? "
                        + "and target_type = ? and target_id = ? and occurred_at between ? and ?",
                Long.class, actionCode, targetType, targetId, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0L : count;
    }

    private Long auditActorUserId(String actionCode, String targetType, long targetId) {
        return jdbc.queryForObject("select actor_user_id from operational_audit_events where action_code = ? "
                + "and target_type = ? and target_id = ? order by id desc limit 1",
                Long.class, actionCode, targetType, targetId);
    }

    private String auditReasonCode(String actionCode, String targetType, long targetId) {
        return jdbc.queryForObject("select reason_code from operational_audit_events where action_code = ? "
                + "and target_type = ? and target_id = ? order by id desc limit 1",
                String.class, actionCode, targetType, targetId);
    }

    private JsonNode auditDetails(String actionCode, String targetType, long targetId) throws Exception {
        String json = jdbc.queryForObject("select details::text from operational_audit_events where action_code = ? "
                + "and target_type = ? and target_id = ? order by id desc limit 1",
                String.class, actionCode, targetType, targetId);
        return mapper.readTree(json);
    }

    /** PostgreSQL's own deadlock counter for this database, so provable rather than asserted by hand. */
    private long deadlockCount() {
        Long count = jdbc.queryForObject("select deadlocks from pg_stat_database where datname = current_database()",
                Long.class);
        return count == null ? 0L : count;
    }

    /** Waits until some session on this database is genuinely blocked on a lock held by another one. */
    private void awaitSessionWaitingOn(String queryRegex, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where datname = current_database() and wait_event_type = 'Lock' and query ~ ?",
                    Long.class, queryRegex);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25L);
        }
        throw new AssertionError("no session was observed waiting on a lock matching /" + queryRegex + "/ within "
                + timeout + "; the expected blocking evidence was not produced");
    }

    /** A real PostgreSQL transaction holding the revocation the governed API performs, uncommitted. */
    private Connection grantRevocationHolder(long grantId, long revokedByUserId) throws Exception {
        Connection holder = DriverManager.getConnection(schemaUrl(), target.username(), target.password());
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.execute("update staff_access_grants set revoked_at = clock_timestamp(), "
                    + "revoked_by_user_id = " + revokedByUserId + ", revoke_reason_code = 'P4_REVOKE_FIRST' "
                    + "where id = " + grantId);
        }
        return holder;
    }

    /** A real PostgreSQL transaction holding the staff deactivation that governance performs, uncommitted. */
    private Connection staffDeactivationHolder(long userId) throws Exception {
        Connection holder = DriverManager.getConnection(schemaUrl(), target.username(), target.password());
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.execute("update employee_profiles set staff_active = false where user_id = " + userId);
        }
        return holder;
    }

    private Connection listingRowLockHolder(long listingId) throws Exception {
        Connection holder = DriverManager.getConnection(schemaUrl(), target.username(), target.password());
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.execute("select id from listings where id = " + listingId + " for update");
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
