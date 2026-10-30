package com.indore.pathome.spaces.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.removal.PropertyRemovalDtos;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.support.FreshIntegrationRuntimeConfiguration;
import com.indore.pathome.spaces.support.FreshIntegrationTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Genuine Owner request → Admin approval → preserved archive acceptance.
 *
 * <p>Authored but deliberately gated behind the repository's explicit disposable PostgreSQL
 * attestation. It is not executed by the ordinary source-verification campaign.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=property-removal-http-test-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=property-removal-http-test-otp-secret",
        "cloudinary.api-key=property-removal-test-key",
        "cloudinary.api-secret=property-removal-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@Import(FreshIntegrationRuntimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = FreshIntegrationTarget.TEST_ENABLEMENT_FLAG, matches = "true")
class PropertyRemovalHttpPostgresTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired private ObjectMapper json;
    @Autowired private JwtUtils jwt;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository profiles;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private LessorProfileRepository lessorProfiles;
    @Autowired private SupportedCityRepository cities;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @Autowired private PropertyRemovalRequestRepository removals;
    @Autowired private DataSource dataSource;
    @Autowired private PasswordEncoder passwordEncoder;
    @LocalServerPort private int port;

    @Test
    void ownerRequestThenAdminApprovalArchivesWithoutDetachingBusinessIdentity() throws Exception {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive).findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        User owner = user("removal-owner", Role.ROLE_LANDLORD);
        LessorProfile profile = new LessorProfile(null, owner.getId(), "Removal Owner",
                "9876500000", owner.getEmail(), LessorSourceType.SELF_SERVICE);
        profile = lessorProfiles.saveAndFlush(profile);
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Removal preservation fixture");
        listing.setDescription("Preserve this content");
        listing.setPropertyType(PropertyType.FLAT);
        listing.setBhkCount("2BHK");
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        listing.setOwnerUserId(owner.getId());
        listing.setLessorProfileId(profile.getId());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setCity(locality.getCity());
        listing.setSector(locality.getSectorName());
        listing.setAddress("Private address preserved");
        listing.setMonthlyRent(new BigDecimal("25000.00"));
        listing.setSecurityDeposit(new BigDecimal("50000.00"));
        listing.setAvailableFrom(LocalDate.now().plusDays(7).atStartOfDay());
        listing.setPublishedAt(LocalDateTime.now().minusDays(2));
        listing = (RentalDetails) listings.saveAndFlush(listing);

        User admin = user("removal-admin", Role.ROLE_ADMIN);
        EmployeeProfile adminProfile = new EmployeeProfile(admin, "LEGACY_ADMIN", null, BigDecimal.ZERO);
        adminProfile.setStaffActive(true);
        profiles.saveAndFlush(adminProfile);
        grants.saveAndFlush(new StaffAccessGrant(admin, StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "REMOVAL_HTTP_TEST", StaffGrantProvisioningSource.ADMIN_API));

        String ownerToken = jwt.generateToken(owner.getId(), owner.getEmail(), owner.getRole().name());
        String adminToken = jwt.generateToken(admin.getId(), admin.getEmail(), admin.getRole().name());
        String operationId = UUID.randomUUID().toString();
        HttpResponse<String> created = send("POST",
                "/api/v1/lessor/properties/" + listing.getId() + "/removal-requests",
                ownerToken, listing.getVersion(), null,
                json.writeValueAsString(java.util.Map.of("operationId", operationId,
                        "reason", "Moving out")));
        assertEquals(201, created.statusCode(), created.body());
        JsonNode createdBody = json.readTree(created.body()).path("request");
        long requestId = createdBody.path("requestId").asLong();
        assertEquals("PENDING", createdBody.path("requestStatus").asText());
        assertEquals(ListingStatus.ACTIVE, listings.findById(listing.getId()).orElseThrow().getStatus());

        HttpResponse<String> decided = send("POST",
                "/api/v1/admin/property-removal-requests/" + requestId + "/decision",
                adminToken, 0L, "Idempotency-Key=" + UUID.randomUUID(), json.writeValueAsString(
                        java.util.Map.of("operationId", UUID.randomUUID().toString(),
                                "decision", "APPROVED",
                                "expectedListingVersion", listing.getVersion(),
                                "reason", "Approved after review")));
        assertEquals(200, decided.statusCode(), decided.body());
        RentalDetails archived = (RentalDetails) listings.findById(listing.getId()).orElseThrow();
        assertEquals(ListingWorkflowStatus.ARCHIVED, archived.getWorkflowStatus());
        assertEquals(ListingStatus.CLOSED, archived.getStatus());
        assertEquals(owner.getId(), archived.getOwnerUserId());
        assertEquals(profile.getId(), archived.getLessorProfileId());
        assertEquals("Private address preserved", archived.getAddress());
        assertEquals(new BigDecimal("25000.00"), archived.getMonthlyRent());
        assertTrue(lessorProfiles.findById(profile.getId()).isPresent());
        PropertyRemovalRequest request = removals.findById(requestId).orElseThrow();
        assertEquals(PropertyRemovalRequestStatus.APPROVED, request.getRequestStatus());
        assertEquals(archived.getVersion(), request.getResultingListingVersion());
        assertEquals(archived.getVersion(),
                ((Number) request.getAuthorityEvidence().get("resultingVersion")).longValue());
    }

    @Test
    void realPasswordLoginProtectsOwnerHistoryFromMissingInvalidAndOtherUsers() throws Exception {
        OwnerFixture fx = ownerFixture("login-isolation");
        String ownerToken = login(fx.owner());
        JsonNode request = createPending(fx, ownerToken, "Owner history isolation");
        String path = "/api/v1/lessor/properties/" + fx.listing().getId()
                + "/removal-requests";

        HttpResponse<String> history = send("GET", path, ownerToken,
                fx.listing().getVersion(), null);
        assertEquals(200, history.statusCode(), history.body());
        assertEquals(request.path("requestId").asLong(),
                json.readTree(history.body()).path("items").get(0).path("requestId").asLong());

        assertEquals(401, sendNoAuth("GET", path).statusCode());
        assertEquals(401, send("GET", path, "not-a-valid-token",
                fx.listing().getVersion(), null).statusCode());

        OwnerFixture otherOwner = ownerFixture("login-other-owner");
        assertEquals(404, send("GET", path, jwt.generateToken(
                        otherOwner.owner().getId(), otherOwner.owner().getEmail(),
                        otherOwner.owner().getRole().name()),
                fx.listing().getVersion(), null).statusCode());
    }

    @Test
    void ownerCreateReplayWithdrawAndRecreateAreTruthful() throws Exception {
        OwnerFixture fx = ownerFixture("withdraw-recreate");
        String token = token(fx.owner());
        UUID firstOperation = UUID.randomUUID();
        JsonNode first = createRequest(fx, token, firstOperation, "First request", 201);
        long firstId = first.path("requestId").asLong();

        JsonNode replay = createRequest(fx, token, firstOperation, "First request", 200);
        assertEquals(firstId, replay.path("requestId").asLong());

        HttpResponse<String> mismatch = send("POST",
                "/api/v1/lessor/properties/" + fx.listing().getId() + "/removal-requests",
                token, fx.listing().getVersion(), null,
                json.writeValueAsString(Map.of("operationId", firstOperation,
                        "reason", "Changed details for the same operation")));
        assertEquals(409, mismatch.statusCode(), mismatch.body());

        HttpResponse<String> withdrawn = send("POST",
                "/api/v1/lessor/properties/" + fx.listing().getId()
                        + "/removal-requests/" + firstId + "/withdraw",
                token, first.path("requestVersion").asLong(), null,
                json.writeValueAsString(Map.of("operationId", UUID.randomUUID())));
        assertEquals(200, withdrawn.statusCode(), withdrawn.body());
        assertEquals("WITHDRAWN", json.readTree(withdrawn.body())
                .path("requestStatus").asText());

        JsonNode recreated = createPending(fx, token, "Recreated after withdrawal");
        assertNotEquals(firstId, recreated.path("requestId").asLong());
        assertEquals("PENDING", recreated.path("requestStatus").asText());
        assertEquals(ListingWorkflowStatus.PUBLISHED,
                listings.findById(fx.listing().getId()).orElseThrow().getWorkflowStatus());
    }

    @Test
    void sevenIncompleteAdminFactCombinationsAreDeniedWithoutMutation() throws Exception {
        OwnerFixture fx = ownerFixture("admin-three-facts");
        JsonNode request = createPending(fx, token(fx.owner()), "Decision authority matrix");
        long requestId = request.path("requestId").asLong();
        long auditBefore = scalar("select count(*) from operational_audit_events "
                + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id=" + requestId);

        for (int mask = 0; mask < 7; mask++) {
            boolean roleAdmin = (mask & 4) != 0;
            boolean activeProfile = (mask & 2) != 0;
            boolean globalGrant = (mask & 1) != 0;
            User admin = adminUser("admin-mask-" + mask, roleAdmin, activeProfile, globalGrant);
            HttpResponse<String> decision = decideRejected(
                    token(admin), requestId, fx.listing().getVersion(), UUID.randomUUID(),
                    "Mask " + mask);
            assertEquals(403, decision.statusCode(),
                    "mask " + mask + " unexpectedly reached decision: " + decision.body());
            assertEquals(PropertyRemovalRequestStatus.PENDING,
                    removals.findById(requestId).orElseThrow().getRequestStatus());
            assertEquals(auditBefore, scalar(
                    "select count(*) from operational_audit_events "
                            + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id="
                            + requestId));
        }
        assertEquals("PUBLISHED", listings.findById(fx.listing().getId()).orElseThrow()
                .getWorkflowStatus().name());
    }

    @Test
    void protectedQueueAdmitsOnlyTheCompleteAdminAuthorityTriple() throws Exception {
        OwnerFixture fx = ownerFixture("queue-authority-matrix");
        JsonNode pending = createPending(fx, token(fx.owner()), "Queue authority matrix");
        long requestId = pending.path("requestId").asLong();
        long auditBefore = scalar("select count(*) from operational_audit_events "
                + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id=" + requestId);

        for (int mask = 0; mask < 8; mask++) {
            boolean roleAdmin = (mask & 4) != 0;
            boolean activeProfile = (mask & 2) != 0;
            boolean globalGrant = (mask & 1) != 0;
            User candidate = adminUser("queue-mask-" + mask,
                    roleAdmin, activeProfile, globalGrant);
            HttpResponse<String> queue = send("GET",
                    "/api/v1/admin/property-removal-requests?status=PENDING&page=0&size=20",
                    token(candidate), 0L, null, null);
            HttpResponse<String> detail = send("GET",
                    "/api/v1/admin/property-removal-requests/" + requestId,
                    token(candidate), 0L, null, null);
            if (mask == 7) {
                assertEquals(200, queue.statusCode(), queue.body());
                assertTrue(json.readTree(queue.body()).path("items").findValuesAsText("requestId")
                        .contains(String.valueOf(requestId)));
                assertEquals("no-store", queue.headers().firstValue("Cache-Control").orElse(""));
                assertEquals(200, detail.statusCode(), detail.body());
                assertEquals("no-store", detail.headers().firstValue("Cache-Control").orElse(""));
                assertEquals(requestId,
                        json.readTree(detail.body()).path("request").path("requestId").asLong());
                assertEquals("Removal fixture queue-authority-matrix",
                        json.readTree(detail.body()).path("propertyTitle").asText());
            } else {
                assertEquals(403, queue.statusCode(),
                        "mask " + mask + " reached the protected queue: " + queue.body());
                assertEquals(403, detail.statusCode(),
                        "mask " + mask + " reached protected detail: " + detail.body());
                assertFalse(queue.body().contains("Queue authority matrix"));
                assertFalse(detail.body().contains("Queue authority matrix"));
            }
        }
        assertEquals(auditBefore, scalar("select count(*) from operational_audit_events "
                + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id=" + requestId));
        assertEquals(PropertyRemovalRequestStatus.PENDING,
                removals.findById(requestId).orElseThrow().getRequestStatus());
    }

    @Test
    void protectedReadsRejectTokenRoleClaimsAndEveryNonAdminPersistedRole() throws Exception {
        OwnerFixture fx = ownerFixture("read-role-matrix");
        JsonNode pending = createPending(fx, token(fx.owner()), "Read role matrix");
        long requestId = pending.path("requestId").asLong();
        String queuePath = "/api/v1/admin/property-removal-requests?status=PENDING&page=0&size=20";
        String detailPath = "/api/v1/admin/property-removal-requests/" + requestId;

        assertEquals(401, sendNoAuth("GET", queuePath).statusCode());
        assertEquals(401, sendNoAuth("GET", detailPath).statusCode());
        assertEquals(401, send("GET", queuePath, "not-a-valid-token", 0L, null, null)
                .statusCode());
        assertEquals(401, send("GET", detailPath, "not-a-valid-token", 0L, null, null)
                .statusCode());

        for (Role role : List.of(Role.ROLE_SUB_ADMIN, Role.ROLE_GROUND_BOY,
                Role.ROLE_BROKER, Role.ROLE_TENANT, Role.ROLE_LANDLORD)) {
            User candidate = user("read-role-" + role.name().toLowerCase(Locale.ROOT), role);
            activeProfile(candidate);
            saveGlobalGrant(candidate);
            assertEquals(403, send("GET", queuePath, token(candidate), 0L, null, null)
                    .statusCode(), role.name());
            assertEquals(403, send("GET", detailPath, token(candidate), 0L, null, null)
                    .statusCode(), role.name());
        }

        User tenantWithForgedAdminClaim = user("read-forged-admin-claim", Role.ROLE_TENANT);
        activeProfile(tenantWithForgedAdminClaim);
        saveGlobalGrant(tenantWithForgedAdminClaim);
        String forgedClaimToken = jwt.generateToken(tenantWithForgedAdminClaim.getId(),
                tenantWithForgedAdminClaim.getEmail(), Role.ROLE_ADMIN.name());
        assertEquals(403, send("GET", queuePath, forgedClaimToken, 0L, null, null)
                .statusCode());
        assertEquals(403, send("GET", detailPath, forgedClaimToken, 0L, null, null)
                .statusCode());
        User admin = activeAdmin("read-super-admin-claim");
        saveGlobalGrant(admin);
        String superAdminClaim = jwt.generateToken(admin.getId(), admin.getEmail(),
                "ROLE_SUPER_ADMIN");
        assertEquals(403, send("GET", queuePath, superAdminClaim, 0L, null, null)
                .statusCode());
        assertEquals(403, send("GET", detailPath, superAdminClaim, 0L, null, null)
                .statusCode());
        assertEquals(404, send("GET", "/api/v1/admin/property-removal-requests/999999999",
                token(adminUser("missing-request-admin", true, true, true)),
                0L, null, null).statusCode());
    }

    @Test
    void protectedReadsRejectRevokedExpiredFutureWrongScopeAndInactiveFacts() throws Exception {
        OwnerFixture fx = ownerFixture("read-grant-matrix");
        JsonNode pending = createPending(fx, token(fx.owner()), "Read grant matrix");
        long requestId = pending.path("requestId").asLong();

        User revoked = activeAdmin("read-revoked-admin");
        StaffAccessGrant revokedGrant = saveGlobalGrant(revoked);
        revokedGrant.revoke(revoked, Instant.now(), "READ_TEST_REVOKE");
        grants.saveAndFlush(revokedGrant);

        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        User expired = activeAdmin("read-expired-admin");
        grants.saveAndFlush(new StaffAccessGrant(expired, StaffCapability.PROPERTY_EDIT_PUBLISH,
                StaffScopeType.CITY, city, null, Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(60), null, Instant.now(), "READ_TEST_EXPIRED",
                StaffGrantProvisioningSource.ADMIN_API));

        User future = activeAdmin("read-future-admin");
        grants.saveAndFlush(new StaffAccessGrant(future, StaffCapability.PROPERTY_EDIT_PUBLISH,
                StaffScopeType.CITY, city, null, Instant.now().plusSeconds(120), null, null,
                Instant.now(), "READ_TEST_FUTURE",
                StaffGrantProvisioningSource.ADMIN_API));

        User wrongScope = activeAdmin("read-wrong-scope-admin");
        grants.saveAndFlush(new StaffAccessGrant(wrongScope,
                StaffCapability.PROPERTY_EDIT_PUBLISH, StaffScopeType.CITY, city, null,
                Instant.now().minusSeconds(60), null, null, Instant.now(),
                "READ_TEST_WRONG_SCOPE", StaffGrantProvisioningSource.ADMIN_API));

        User inactive = user("read-inactive-profile-admin", Role.ROLE_ADMIN);
        EmployeeProfile inactiveProfile = new EmployeeProfile(inactive, "WFH_ADMIN", null,
                BigDecimal.ZERO);
        inactiveProfile.setStaffActive(false);
        profiles.saveAndFlush(inactiveProfile);
        saveGlobalGrant(inactive);

        for (User candidate : List.of(revoked, expired, future, wrongScope, inactive)) {
            assertRemovalReadDenied(candidate, requestId);
        }
    }

    @Test
    void removalReadsDoNotWaitOnGrantLocksAndRereadAfterRevocation() throws Exception {
        OwnerFixture fx = ownerFixture("read-lock-interleave");
        JsonNode pending = createPending(fx, token(fx.owner()), "Read lock interleave");
        long requestId = pending.path("requestId").asLong();
        User admin = activeAdmin("read-lock-interleave-admin");
        StaffAccessGrant grant = saveGlobalGrant(admin);
        String token = token(admin);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (PreparedStatement statement = lock.prepareStatement(
                    "select id from staff_access_grants where id=? for update")) {
                statement.setLong(1, grant.getId());
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                }
            }
            Future<HttpResponse<String>> read = executor.submit(() -> send("GET",
                    "/api/v1/admin/property-removal-requests?status=PENDING&page=0&size=20",
                    token, 0L, null, null));
            HttpResponse<String> response = read.get(2, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(), response.body());
            lock.rollback();
        } finally {
            executor.shutdownNow();
        }

        grant.revoke(admin, Instant.now(), "POST_READ_REVOKE");
        grants.saveAndFlush(grant);
        assertRemovalReadDenied(admin, requestId);
    }

    @Test
    void decisionRechecksFreshAuthorityAfterWaitingOnTheListingLock() throws Exception {
        OwnerFixture fx = ownerFixture("decision-revocation-wait");
        JsonNode pending = createPending(fx, token(fx.owner()), "Decision revocation wait");
        long requestId = pending.path("requestId").asLong();
        User admin = activeAdmin("decision-revocation-admin");
        StaffAccessGrant grant = saveGlobalGrant(admin);
        long auditBefore = scalar("select count(*) from operational_audit_events "
                + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id=" + requestId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (Statement statement = lock.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select id from listings where id=" + fx.listing().getId()
                                 + " for update")) {
                assertTrue(rows.next());
            }
            Future<HttpResponse<String>> decision = executor.submit(() ->
                    decideRejected(token(admin), requestId, fx.listing().getVersion(),
                            UUID.randomUUID(), "Revoked while waiting"));
            assertTrue(waitForBlockedListingStatement(5),
                    "decision must wait on the Listing root before fresh authority is rechecked");
            grant.revoke(admin, Instant.now(), "LOCK_WAIT_REVOKE");
            grants.saveAndFlush(grant);
            lock.commit();
            assertEquals(403, decision.get(20, TimeUnit.SECONDS).statusCode());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(PropertyRemovalRequestStatus.PENDING,
                removals.findById(requestId).orElseThrow().getRequestStatus());
        assertEquals(ListingWorkflowStatus.PUBLISHED,
                listings.findById(fx.listing().getId()).orElseThrow().getWorkflowStatus());
        assertEquals(auditBefore, scalar("select count(*) from operational_audit_events "
                + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id=" + requestId));
        assertEquals(0L, scalar("select count(*) from property_revision_events "
                + "where listing_id=" + fx.listing().getId()
                + " and event_type in ('REMOVAL_REJECTED','REMOVAL_APPROVED')"));
        assertEquals(0L, scalar("select count(*) from system_notifications "
                + "where event_key like 'property-removal-" + requestId + "-%'"));
    }

    @Test
    void concurrentWithdrawalAndDecisionProduceOneTerminalWinner() throws Exception {
        OwnerFixture fx = ownerFixture("withdraw-decision-race");
        JsonNode pending = createPending(fx, token(fx.owner()), "Withdraw decision race");
        long requestId = pending.path("requestId").asLong();
        User admin = activeAdmin("withdraw-decision-admin");
        saveGlobalGrant(admin);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (Statement statement = lock.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select id from listings where id=" + fx.listing().getId()
                                 + " for update")) {
                assertTrue(rows.next());
            }
            Future<HttpResponse<String>> withdraw = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return send("POST", "/api/v1/lessor/properties/" + fx.listing().getId()
                                + "/removal-requests/" + requestId + "/withdraw",
                        token(fx.owner()), pending.path("requestVersion").asLong(), null,
                        json.writeValueAsString(Map.of("operationId", UUID.randomUUID())));
            });
            Future<HttpResponse<String>> decision = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return decideRejected(token(admin), requestId, fx.listing().getVersion(),
                        UUID.randomUUID(), "Concurrent rejection");
            });
            start.countDown();
            assertTrue(waitForBlockedListingStatements(2, 5),
                    "both terminal commands must queue behind the Listing root");
            lock.commit();
            List<Integer> statuses = new ArrayList<>(List.of(
                    withdraw.get(20, TimeUnit.SECONDS).statusCode(),
                    decision.get(20, TimeUnit.SECONDS).statusCode()));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
        } finally {
            executor.shutdownNow();
        }

        PropertyRemovalRequestStatus terminal = removals.findById(requestId)
                .orElseThrow().getRequestStatus();
        assertTrue(terminal == PropertyRemovalRequestStatus.WITHDRAWN
                || terminal == PropertyRemovalRequestStatus.REJECTED);
        assertEquals(1L, scalar("select count(*) from operational_audit_events "
                + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id=" + requestId
                + " and action_code in ('PROPERTY_REMOVAL_WITHDRAWN','PROPERTY_REMOVAL_REJECTED')"));
    }

    @Test
    void legacySharedGrantReadFailsInAReadOnlyTransaction() throws Exception {
        User admin = activeAdmin("legacy-read-negative-control");
        StaffAccessGrant grant = saveGlobalGrant(admin);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id from staff_access_grants where id=? for share")) {
                statement.setLong(1, grant.getId());
                SQLException failure = assertThrows(SQLException.class,
                        statement::executeQuery);
                assertEquals("25006", failure.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void ownerRequestActuallyWaitsForTheListingLockBeforeCommit() throws Exception {
        OwnerFixture fx = ownerFixture("real-lock-wait");
        String token = token(fx.owner());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (Statement statement = lock.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select id from listings where id=" + fx.listing().getId() + " for update")) {
                assertTrue(rows.next());
            }
            Future<HttpResponse<String>> pending = executor.submit(() -> send("POST",
                    "/api/v1/lessor/properties/" + fx.listing().getId() + "/removal-requests",
                    token, fx.listing().getVersion(), null,
                    json.writeValueAsString(Map.of("operationId", UUID.randomUUID(),
                            "reason", "Waited for the owned property lock"))));
            Thread.sleep(350);
            assertFalse(pending.isDone(),
                    "the HTTP command must wait while another transaction owns the listing lock");
            lock.commit();
            HttpResponse<String> response = pending.get(20, TimeUnit.SECONDS);
            assertEquals(201, response.statusCode(), response.body());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void twoConcurrentOwnerRequestsProduceOnePendingTransactionalWinner() throws Exception {
        OwnerFixture fx = ownerFixture("concurrent-create");
        String token = token(fx.owner());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return send("POST",
                        "/api/v1/lessor/properties/" + fx.listing().getId()
                                + "/removal-requests",
                        token, fx.listing().getVersion(), null,
                        json.writeValueAsString(Map.of("operationId", UUID.randomUUID(),
                                "reason", "Concurrent owner request A")));
            });
            Future<HttpResponse<String>> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return send("POST",
                        "/api/v1/lessor/properties/" + fx.listing().getId()
                                + "/removal-requests",
                        token, fx.listing().getVersion(), null,
                        json.writeValueAsString(Map.of("operationId", UUID.randomUUID(),
                                "reason", "Concurrent owner request B")));
            });
            start.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(
                    first.get(20, TimeUnit.SECONDS).statusCode(),
                    second.get(20, TimeUnit.SECONDS).statusCode()));
            Collections.sort(statuses);
            assertEquals(List.of(201, 409), statuses);
            assertEquals(1L, scalar("select count(*) from property_removal_requests "
                    + "where listing_id=" + fx.listing().getId()
                    + " and request_status='PENDING'"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedDecisionIsIdempotentAndTerminalFactsRemainImmutable() throws Exception {
        OwnerFixture fx = ownerFixture("rejection-replay");
        JsonNode pending = createPending(fx, token(fx.owner()), "Reject once");
        long requestId = pending.path("requestId").asLong();
        User admin = adminUser("reject-admin", true, true, true);
        UUID operationId = UUID.randomUUID();

        HttpResponse<String> first = decideRejected(token(admin), requestId,
                fx.listing().getVersion(), operationId, "Rejected after review");
        assertEquals(200, first.statusCode(), first.body());
        assertEquals("REJECTED", removals.findById(requestId).orElseThrow()
                .getRequestStatus().name());

        HttpResponse<String> replay = decideRejected(token(admin), requestId,
                fx.listing().getVersion(), operationId, "Rejected after review");
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals("REJECTED", json.readTree(replay.body()).path("requestStatus").asText());

        HttpResponse<String> changed = decideRejected(token(admin), requestId,
                fx.listing().getVersion(), UUID.randomUUID(), "Different terminal command");
        assertEquals(409, changed.statusCode(), changed.body());
        assertEquals(ListingWorkflowStatus.PUBLISHED,
                listings.findById(fx.listing().getId()).orElseThrow().getWorkflowStatus());
    }

    @Test
    void concurrentApproveAndRejectProduceOneTerminalWinner() throws Exception {
        OwnerFixture fx = ownerFixture("concurrent-approve-reject");
        JsonNode pending = createPending(fx, token(fx.owner()), "Concurrent terminal decision");
        User admin = adminUser("approve-reject-admin", true, true, true);

        List<Integer> statuses = raceDecisions(fx, admin, pending,
                PropertyRemovalDtos.DecisionType.APPROVED,
                PropertyRemovalDtos.DecisionType.REJECTED);

        assertEquals(List.of(200, 409), statuses);
        assertOneTerminalDecision(fx, pending, false);
    }

    @Test
    void twoConcurrentApprovalsArchiveExactlyOnce() throws Exception {
        OwnerFixture fx = ownerFixture("concurrent-two-approvals");
        JsonNode pending = createPending(fx, token(fx.owner()), "Concurrent approvals");
        User admin = adminUser("two-approval-admin", true, true, true);

        List<Integer> statuses = raceDecisions(fx, admin, pending,
                PropertyRemovalDtos.DecisionType.APPROVED,
                PropertyRemovalDtos.DecisionType.APPROVED);

        assertEquals(List.of(200, 409), statuses);
        PropertyRemovalRequest request = assertOneTerminalDecision(fx, pending, true);
        assertEquals(PropertyRemovalRequestStatus.APPROVED, request.getRequestStatus());
        assertEquals(1L, scalar("select count(*) from operational_audit_events "
                + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id="
                + request.getId() + " and action_code='PROPERTY_REMOVAL_APPROVED'"));
        RentalDetails archived = (RentalDetails) listings.findById(fx.listing().getId())
                .orElseThrow();
        assertEquals(ListingWorkflowStatus.ARCHIVED, archived.getWorkflowStatus());
        assertEquals(fx.listing().getVersion() + 1L, archived.getVersion());
    }

    private List<Integer> raceDecisions(OwnerFixture fixture, User admin, JsonNode pending,
                                        PropertyRemovalDtos.DecisionType firstDecision,
                                        PropertyRemovalDtos.DecisionType secondDecision)
            throws Exception {
        String token = token(admin);
        long requestId = pending.path("requestId").asLong();
        long listingVersion = fixture.listing().getVersion();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (Statement statement = lock.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select id from listings where id=" + fixture.listing().getId()
                                 + " for update")) {
                assertTrue(rows.next());
            }
            Future<HttpResponse<String>> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return decide(token, requestId, listingVersion, UUID.randomUUID(),
                        firstDecision, "Concurrent decision A");
            });
            Future<HttpResponse<String>> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return decide(token, requestId, listingVersion, UUID.randomUUID(),
                        secondDecision, "Concurrent decision B");
            });
            start.countDown();
            Thread.sleep(350);
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            lock.commit();
            List<Integer> statuses = new ArrayList<>(List.of(
                    first.get(20, TimeUnit.SECONDS).statusCode(),
                    second.get(20, TimeUnit.SECONDS).statusCode()));
            Collections.sort(statuses);
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private PropertyRemovalRequest assertOneTerminalDecision(
            OwnerFixture fixture, JsonNode pending, boolean mustBeApproved) throws Exception {
        long requestId = pending.path("requestId").asLong();
        PropertyRemovalRequest request = removals.findById(requestId).orElseThrow();
        assertNotEquals(PropertyRemovalRequestStatus.PENDING, request.getRequestStatus());
        if (mustBeApproved) {
            assertEquals(PropertyRemovalRequestStatus.APPROVED, request.getRequestStatus());
        }
        String action = "PROPERTY_REMOVAL_" + request.getRequestStatus().name();
        assertEquals(1L, scalar("select count(*) from operational_audit_events "
                + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id=" + requestId
                + " and action_code='" + action + "'"));
        assertEquals(1L, scalar("select count(*) from property_revision_events "
                + "where listing_id=" + fixture.listing().getId()
                + " and event_type='REMOVAL_" + request.getRequestStatus().name() + "'"));
        assertEquals(1L, scalar("select count(*) from system_notifications "
                + "where event_key='property-removal-" + requestId + "-"
                + request.getRequestStatus().name() + "'"));
        RentalDetails current = (RentalDetails) listings.findById(fixture.listing().getId())
                .orElseThrow();
        if (request.getRequestStatus() == PropertyRemovalRequestStatus.APPROVED) {
            assertEquals(ListingWorkflowStatus.ARCHIVED, current.getWorkflowStatus());
            assertEquals(ListingStatus.CLOSED, current.getStatus());
        } else {
            assertEquals(ListingWorkflowStatus.PUBLISHED, current.getWorkflowStatus());
            assertEquals(ListingStatus.ACTIVE, current.getStatus());
        }
        return request;
    }

    private User activeAdmin(String label) {
        User admin = user(label, Role.ROLE_ADMIN);
        activeProfile(admin);
        return admin;
    }

    private void activeProfile(User user) {
        EmployeeProfile profile = new EmployeeProfile(user, "WFH_ADMIN", null, BigDecimal.ZERO);
        profile.setStaffActive(true);
        profiles.saveAndFlush(profile);
    }

    private StaffAccessGrant saveGlobalGrant(User admin) {
        return grants.saveAndFlush(new StaffAccessGrant(admin, StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "REMOVAL_READ_TEST",
                StaffGrantProvisioningSource.ADMIN_API));
    }

    private void assertRemovalReadDenied(User candidate, long requestId) throws Exception {
        HttpResponse<String> queue = send("GET",
                "/api/v1/admin/property-removal-requests?status=PENDING&page=0&size=20",
                token(candidate), 0L, null, null);
        HttpResponse<String> detail = send("GET",
                "/api/v1/admin/property-removal-requests/" + requestId,
                token(candidate), 0L, null, null);
        assertEquals(403, queue.statusCode(), queue.body());
        assertEquals(403, detail.statusCode(), detail.body());
        assertFalse(queue.body().contains("Removal fixture"));
        assertFalse(detail.body().contains("Removal fixture"));
    }

    private boolean waitForBlockedListingStatement(int timeoutSeconds) throws Exception {
        return waitForBlockedListingStatements(1, timeoutSeconds);
    }

    private boolean waitForBlockedListingStatements(int expected, int timeoutSeconds)
            throws Exception {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select count(*) from pg_stat_activity "
                                 + "where datname=current_database() "
                                 + "and wait_event_type='Lock' "
                                 + "and lower(query) like '%listings%'")) {
                assertTrue(rows.next());
                if (rows.getLong(1) >= expected) return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private record OwnerFixture(User owner, LessorProfile profile, RentalDetails listing) {}

    private OwnerFixture ownerFixture(String label) {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        User owner = user(label + "-owner", Role.ROLE_LANDLORD);
        LessorProfile profile = lessorProfiles.saveAndFlush(new LessorProfile(null,
                owner.getId(), label, "9876500000", owner.getEmail(),
                LessorSourceType.SELF_SERVICE));
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Removal fixture " + label);
        listing.setDescription("Synthetic removal fixture");
        listing.setPropertyType(PropertyType.FLAT);
        listing.setBhkCount("2BHK");
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        listing.setOwnerUserId(owner.getId());
        listing.setLessorProfileId(profile.getId());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setCity(locality.getCity());
        listing.setSector(locality.getSectorName());
        listing.setAddress("Synthetic private address");
        listing.setMonthlyRent(new BigDecimal("25000.00"));
        listing.setSecurityDeposit(new BigDecimal("50000.00"));
        listing.setAvailableFrom(LocalDate.now().plusDays(7).atStartOfDay());
        listing.setPublishedAt(LocalDateTime.now().minusDays(2));
        listing = (RentalDetails) listings.saveAndFlush(listing);
        return new OwnerFixture(owner, profile, listing);
    }

    private User adminUser(String label, boolean roleAdmin, boolean activeProfile,
                           boolean globalGrant) {
        User admin = user(label, roleAdmin ? Role.ROLE_ADMIN : Role.ROLE_TENANT);
        if (activeProfile) {
            EmployeeProfile profile = new EmployeeProfile(admin, "WFH_ADMIN", null,
                    BigDecimal.ZERO);
            profile.setStaffActive(true);
            profiles.saveAndFlush(profile);
        }
        if (globalGrant) {
            grants.saveAndFlush(new StaffAccessGrant(admin, StaffCapability.STAFF_ADMIN,
                    StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null,
                    null, Instant.now(), "REMOVAL_HTTP_TEST",
                    StaffGrantProvisioningSource.ADMIN_API));
        }
        return admin;
    }

    private JsonNode createPending(OwnerFixture fixture, String token, String reason)
            throws Exception {
        return createRequest(fixture, token, UUID.randomUUID(), reason, 201);
    }

    private JsonNode createRequest(OwnerFixture fixture, String token, UUID operationId,
                                   String reason, int expectedStatus) throws Exception {
        HttpResponse<String> response = send("POST",
                "/api/v1/lessor/properties/" + fixture.listing().getId()
                        + "/removal-requests",
                token, fixture.listing().getVersion(), null,
                json.writeValueAsString(Map.of("operationId", operationId,
                        "reason", reason)));
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return json.readTree(response.body()).path("request");
    }

    private HttpResponse<String> decideRejected(String token, long requestId,
                                                long listingVersion, UUID operationId,
                                                String reason) throws Exception {
        return decide(token, requestId, listingVersion, operationId,
                PropertyRemovalDtos.DecisionType.REJECTED, reason);
    }

    private HttpResponse<String> decide(String token, long requestId, long listingVersion,
                                        UUID operationId,
                                        PropertyRemovalDtos.DecisionType decision,
                                        String reason) throws Exception {
        return send("POST", "/api/v1/admin/property-removal-requests/"
                        + requestId + "/decision",
                token, 0L, null, json.writeValueAsString(Map.of(
                        "operationId", operationId,
                        "decision", decision.name(),
                        "expectedListingVersion", listingVersion,
                        "reason", reason)));
    }

    private String login(User user) throws Exception {
        HttpResponse<String> response = sendNoAuth("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of(
                        "email", user.getEmail(),
                        "password", "PathomeTest!234")));
        assertEquals(200, response.statusCode(), response.body());
        String token = json.readTree(response.body()).path("token").asText();
        assertFalse(token.isBlank());
        return token;
    }

    private String token(User user) {
        return jwt.generateToken(user.getId(), user.getEmail(), user.getRole().name());
    }

    private long scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private HttpResponse<String> sendNoAuth(String method, String path) throws Exception {
        return sendNoAuth(method, path, null);
    }

    private HttpResponse<String> sendNoAuth(String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private User user(String label, Role role) {
        User user = new User();
        user.setEmail(label + "-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash(passwordEncoder.encode("PathomeTest!234"));
        user.setFullName(label);
        user.setPhoneNumber(null);
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private HttpResponse<String> send(String method, String path, String token, Long version,
                                      Object body) throws Exception {
        return send(method, path, token, version, null,
                body == null || body instanceof String
                        ? (String) body : json.writeValueAsString(body));
    }

    private HttpResponse<String> send(String method, String path, String token, Long version,
                                      String idempotencyHeader, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("If-Match", String.valueOf(version));
        if (idempotencyHeader != null) {
            String[] parts = idempotencyHeader.split("=", 2);
            request.header(parts[0], parts[1]);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

