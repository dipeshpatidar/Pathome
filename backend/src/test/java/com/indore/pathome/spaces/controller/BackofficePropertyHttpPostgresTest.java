package com.indore.pathome.spaces.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authored genuine HTTP/PostgreSQL acceptance for a City-scoped staff revision and direct publication.
 * Not executed in this source campaign; the disposable target attestation gate must authorize it first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=backoffice-http-test-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=backoffice-http-test-otp-secret",
        "cloudinary.api-key=backoffice-test-key",
        "cloudinary.api-secret=backoffice-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@Import(FreshIntegrationRuntimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = FreshIntegrationTarget.TEST_ENABLEMENT_FLAG, matches = "true")
class BackofficePropertyHttpPostgresTest {
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
    @Autowired private PropertyMediaAssetRepository media;
    @Autowired private PropertyPublicationVersionRepository publications;
    @Autowired private PropertyUploadDraftRepository drafts;
    @Autowired private PropertyRevisionEventRepository revisionEvents;
    @Autowired private DataSource dataSource;
    @LocalServerPort private int port;

    @Test
    void cityStaffRevisionPublishesAllSectionsAndPreservesStableMedia() throws Exception {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive).findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        User owner = user("backoffice-owner", Role.ROLE_LANDLORD);
        LessorProfile lessor = lessorProfiles.saveAndFlush(new LessorProfile(null, owner.getId(),
                "Owner", "9876500011", owner.getEmail(), LessorSourceType.SELF_SERVICE));
        RentalDetails listing = listing(owner, lessor, locality, "Original title");
        PropertyMediaAsset cover = media.saveAndFlush(new PropertyMediaAsset(listing.getId(),
                "https://example.test/original.jpg", MediaType.IMAGE, RoomTag.LIVING_ROOM,
                "Original living room"));
        cover.setIsPrimaryCover(true);
        cover.setSortOrder(0);
        cover = media.saveAndFlush(cover);
        Long originalMediaId = cover.getId();

        User staff = user("backoffice-staff", Role.ROLE_TENANT);
        EmployeeProfile profile = new EmployeeProfile(staff, "WFH_ADMIN", null, BigDecimal.ZERO);
        profile.setStaffActive(true);
        profiles.saveAndFlush(profile);
        grants.saveAndFlush(new StaffAccessGrant(staff, StaffCapability.PROPERTY_EDIT_PUBLISH,
                StaffScopeType.CITY, city, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "BACKOFFICE_HTTP_TEST", StaffGrantProvisioningSource.ADMIN_API));
        grants.saveAndFlush(new StaffAccessGrant(staff, StaffCapability.PROPERTY_AUDIT_READ,
                StaffScopeType.CITY, city, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "BACKOFFICE_HTTP_TEST", StaffGrantProvisioningSource.ADMIN_API));
        String token = jwt.generateToken(staff.getId(), staff.getEmail(), staff.getRole().name());

        HttpResponse<String> started = send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions",
                token, Map.of(), Map.of());
        assertEquals(200, started.statusCode(), started.body());
        JsonNode revision = json.readTree(started.body());
        String draftId = revision.path("draftId").asText();
        int draftVersion = revision.path("version").asInt();

        HttpResponse<String> basics = send("PUT",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/basics",
                token, Map.of("If-Match", String.valueOf(draftVersion)),
                Map.of("title", "Corrected title", "description", "Corrected description",
                        "bhkCount", "2BHK", "propertyType", "FLAT"));
        assertEquals(200, basics.statusCode(), basics.body());
        draftVersion = json.readTree(basics.body()).path("version").asInt();

        HttpResponse<String> commercial = send("PUT",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/commercial",
                token, Map.of("If-Match", String.valueOf(draftVersion)),
                Map.of("monthlyRent", "28750.00", "securityDeposit", "0.00",
                        "availableFrom", LocalDate.now().plusDays(10).toString(),
                        "maintenanceCharge", "1800.00", "brokerageAmount", "0.00",
                        "brokerageDays", 0, "securityDepositMonths", 0));
        assertEquals(200, commercial.statusCode(), commercial.body());
        draftVersion = json.readTree(commercial.body()).path("version").asInt();

        HttpResponse<String> details = send("PUT",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/details",
                token, Map.of("If-Match", String.valueOf(draftVersion)),
                Map.of("totalAreaSqFt", 905.5, "floorNumber", 4, "totalFloors", 9,
                        "furnishingStatus", "SEMI_FURNISHED", "amenities", "Lift and parking",
                        "bathroomCount", 2, "vastuFacing", "East", "bachelorAllowed", true,
                        "preferredTenant", "Working professionals"));
        assertEquals(200, details.statusCode(), details.body());
        draftVersion = json.readTree(details.body()).path("version").asInt();

        HttpResponse<String> address = send("PUT",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/address",
                token, Map.of("If-Match", String.valueOf(draftVersion)),
                Map.of("address", "Corrected private address", "landmark", "Near park",
                        "colony", "Palm Residency", "pincode", "452010"));
        assertEquals(200, address.statusCode(), address.body());
        draftVersion = json.readTree(address.body()).path("version").asInt();

        HttpResponse<String> listedMedia = send("GET",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/media",
                token, Map.of(), null);
        assertEquals(200, listedMedia.statusCode(), listedMedia.body());
        JsonNode seededMedia = json.readTree(listedMedia.body());
        assertEquals(1, seededMedia.size());
        String mediaId = seededMedia.get(0).path("mediaId").asText();
        assertEquals("UPLOADED", seededMedia.get(0).path("uploadStatus").asText());

        HttpResponse<String> presentedMedia = send("PUT",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/media",
                token, Map.of("If-Match", String.valueOf(draftVersion)),
                List.of(Map.of(
                        "mediaId", mediaId,
                        "roomTag", "LIVING_ROOM",
                        "caption", "Bright living room",
                        "sortOrder", 0,
                        "cover", true)));
        assertEquals(200, presentedMedia.statusCode(), presentedMedia.body());
        draftVersion = json.readTree(presentedMedia.body()).path("version").asInt();
        assertEquals("Bright living room",
                json.readTree(presentedMedia.body()).path("content").path("media")
                        .get(0).path("caption").asText());

        String operationId = UUID.randomUUID().toString();
        HttpResponse<String> published = send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/publish",
                token, Map.of("If-Match", String.valueOf(draftVersion),
                        "X-Listing-Version", String.valueOf(listing.getVersion()),
                        "Idempotency-Key", operationId),
                Map.of("reason", "Correct the reviewed property details"));
        assertEquals(200, published.statusCode(), published.body());
        JsonNode receipt = json.readTree(published.body());
        assertEquals(1, receipt.path("publicationVersion").asLong());

        RentalDetails saved = (RentalDetails) listings.findById(listing.getId()).orElseThrow();
        assertEquals("Corrected title", saved.getTitle());
        assertEquals(new BigDecimal("28750.00"), saved.getMonthlyRent());
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.getSecurityDeposit()));
        assertEquals("Corrected private address", saved.getAddress());
        assertNotNull(saved.getLastContentPublishedAt());
        assertEquals(originalMediaId, media.findByListingIdOrderByUploadedAtDesc(listing.getId())
                .get(0).getId());
        assertEquals("Bright living room",
                media.findByListingIdOrderByUploadedAtDesc(listing.getId()).get(0).getCaption());
        assertTrue(publications.findByListingIdAndPublicationVersion(listing.getId(), 1L).isPresent());
        assertEquals("PUBLISHED", receipt.path("status").asText());
        assertPublicationEvidence(listing.getId(), staff.getId(), draftId, 1L,
                listing.getVersion(), saved.getVersion(), "PUBLISHED");

        HttpResponse<String> replay = send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/publish",
                token, Map.of("If-Match", String.valueOf(draftVersion),
                        "X-Listing-Version", String.valueOf(listing.getVersion()),
                        "Idempotency-Key", operationId),
                Map.of("reason", "Correct the reviewed property details"));
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(1L, json.readTree(replay.body()).path("publicationVersion").asLong());
        assertEquals(1L, scalar("select count(*) from property_publication_versions where listing_id="
                + listing.getId() + " and publication_version=1"));
        assertEquals(1L, scalar("select count(*) from operational_audit_events "
                + "where action_code='PROPERTY_CONTENT_PUBLISHED' and target_id=" + listing.getId()));

        HttpResponse<String> changedReplay = send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/publish",
                token, Map.of("If-Match", String.valueOf(draftVersion),
                        "X-Listing-Version", String.valueOf(listing.getVersion()),
                        "Idempotency-Key", operationId),
                Map.of("reason", "A different publication intent"));
        assertEquals(409, changedReplay.statusCode(), changedReplay.body());

        HttpResponse<String> staleEdit = send("PUT",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/basics",
                token, Map.of("If-Match", String.valueOf(draftVersion)),
                Map.of("title", "Stale overwrite", "description", "Must not be applied",
                        "bhkCount", "2BHK", "propertyType", "FLAT"));
        assertEquals(409, staleEdit.statusCode(), staleEdit.body());

        HttpResponse<String> firstActivity = send("GET",
                "/api/v1/backoffice/properties/" + listing.getId() + "/activity?limit=1",
                token, Map.of(), null);
        assertEquals(200, firstActivity.statusCode(), firstActivity.body());
        assertEquals("no-store", firstActivity.headers().firstValue("Cache-Control").orElse(""));
        JsonNode firstPage = json.readTree(firstActivity.body());
        assertEquals(1, firstPage.path("items").size());
        assertFalse(firstPage.path("items").get(0).path("eventId").asText().isBlank());
        assertTrue(firstPage.path("hasMore").asBoolean());
        String cursor = firstPage.path("nextCursor").asText();
        assertFalse(cursor.isBlank());
        Set<String> eventIds = new HashSet<>();
        eventIds.add(firstPage.path("items").get(0).path("eventId").asText());
        boolean reachedFinalPage = false;
        for (int pageOrdinal = 1; pageOrdinal <= 20 && !cursor.isBlank(); pageOrdinal++) {
            HttpResponse<String> nextActivity = send("GET",
                    "/api/v1/backoffice/properties/" + listing.getId()
                            + "/activity?limit=1&cursor=" + cursor,
                    token, Map.of(), null);
            assertEquals(200, nextActivity.statusCode(), nextActivity.body());
            JsonNode page = json.readTree(nextActivity.body());
            assertEquals(pageOrdinal, page.path("page").asInt());
            assertEquals(1, page.path("items").size());
            assertTrue(eventIds.add(page.path("items").get(0).path("eventId").asText()));
            cursor = page.path("nextCursor").asText("");
            if (!page.path("hasMore").asBoolean()) {
                assertTrue(cursor.isBlank());
                reachedFinalPage = true;
                break;
            }
        }
        assertTrue(reachedFinalPage);
        assertTrue(eventIds.size() >= 3);
    }

    @Test
    void legalPausedRevisionPublishesWithoutChangingItsWorkflowState() throws Exception {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        User owner = user("paused-owner", Role.ROLE_LANDLORD);
        LessorProfile lessor = lessorProfiles.saveAndFlush(new LessorProfile(null, owner.getId(),
                "Paused Owner", null, owner.getEmail(), LessorSourceType.SELF_SERVICE));
        RentalDetails listing = listing(owner, lessor, locality, "Paused property");
        listing.setWorkflowStatus(ListingWorkflowStatus.PAUSED);
        listing = (RentalDetails) listings.saveAndFlush(listing);
        PropertyMediaAsset cover = media.saveAndFlush(new PropertyMediaAsset(listing.getId(),
                "https://example.test/paused.jpg", MediaType.IMAGE, RoomTag.BEDROOM,
                "Paused bedroom"));
        cover.setIsPrimaryCover(true);
        cover.setSortOrder(0);
        media.saveAndFlush(cover);

        User staff = user("paused-staff", Role.ROLE_TENANT);
        EmployeeProfile profile = new EmployeeProfile(staff, "WFH_ADMIN", null, BigDecimal.ZERO);
        profile.setStaffActive(true);
        profiles.saveAndFlush(profile);
        grants.saveAndFlush(new StaffAccessGrant(staff, StaffCapability.PROPERTY_EDIT_PUBLISH,
                StaffScopeType.CITY, city, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "BACKOFFICE_HTTP_TEST", StaffGrantProvisioningSource.ADMIN_API));
        String token = jwt.generateToken(staff.getId(), staff.getEmail(), staff.getRole().name());

        HttpResponse<String> started = send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions",
                token, Map.of(), Map.of());
        assertEquals(200, started.statusCode(), started.body());
        JsonNode revision = json.readTree(started.body());
        String draftId = revision.path("draftId").asText();
        int draftVersion = revision.path("version").asInt();

        HttpResponse<String> mediaRows = send("GET",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/media",
                token, Map.of(), null);
        assertEquals(200, mediaRows.statusCode(), mediaRows.body());
        String mediaId = json.readTree(mediaRows.body()).get(0).path("mediaId").asText();
        HttpResponse<String> presented = send("PUT",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/media",
                token, Map.of("If-Match", String.valueOf(draftVersion)),
                List.of(Map.of("mediaId", mediaId, "roomTag", "BEDROOM",
                        "caption", "Quiet bedroom", "sortOrder", 0, "cover", true)));
        assertEquals(200, presented.statusCode(), presented.body());
        draftVersion = json.readTree(presented.body()).path("version").asInt();

        HttpResponse<String> published = send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/publish",
                token, Map.of("If-Match", String.valueOf(draftVersion),
                        "X-Listing-Version", String.valueOf(listing.getVersion()),
                        "Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("reason", "Publish a correction while the property is paused"));
        assertEquals(200, published.statusCode(), published.body());
        assertEquals("PAUSED", json.readTree(published.body()).path("status").asText());
        Listing pausedSaved = listings.findById(listing.getId()).orElseThrow();
        assertEquals(ListingWorkflowStatus.PAUSED, pausedSaved.getWorkflowStatus());
        assertPublicationEvidence(listing.getId(), staff.getId(), draftId, 1L,
                listing.getVersion(), pausedSaved.getVersion(), "PAUSED");
    }

    @Test
    void missingInvalidAndUnprivilegedTokensNeverCreateARevision() throws Exception {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        User owner = user("security-owner", Role.ROLE_LANDLORD);
        LessorProfile lessor = lessorProfiles.saveAndFlush(new LessorProfile(null, owner.getId(),
                "Security Owner", null, owner.getEmail(), LessorSourceType.SELF_SERVICE));
        RentalDetails listing = listing(owner, lessor, locality, "Security fixture");
        String path = "/api/v1/backoffice/properties/" + listing.getId() + "/revisions";

        assertEquals(401, sendNoAuth("POST", path).statusCode());
        assertEquals(401, send("POST", path, "not-a-valid-token", Map.of(), Map.of())
                .statusCode());
        User tenant = user("security-tenant", Role.ROLE_TENANT);
        String tenantToken = jwt.generateToken(
                tenant.getId(), tenant.getEmail(), tenant.getRole().name());
        assertEquals(404, send("POST", path, tenantToken, Map.of(), Map.of()).statusCode());
        assertTrue(drafts.findOpenRevisions(listing.getId(),
                List.of("DRAFT", "REVIEW", "CHANGES_REQUIRED", "APPROVED")).isEmpty());
        assertEquals(0L, revisionEvents.latestRowId(listing.getId()));
        assertEquals(ListingWorkflowStatus.PUBLISHED,
                listings.findById(listing.getId()).orElseThrow().getWorkflowStatus());
    }

    @Test
    void inactiveExpiredAndWrongCityGrantsCannotStartRevision() throws Exception {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        SupportedCity otherCity = cities.findAll().stream()
                .filter(SupportedCity::isActive)
                .filter(candidate -> !candidate.getId().equals(city.getId()))
                .findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        User owner = user("scope-owner", Role.ROLE_LANDLORD);
        LessorProfile lessor = lessorProfiles.saveAndFlush(new LessorProfile(null, owner.getId(),
                "Scope Owner", null, owner.getEmail(), LessorSourceType.SELF_SERVICE));
        RentalDetails listing = listing(owner, lessor, locality, "Scope fixture");
        String path = "/api/v1/backoffice/properties/" + listing.getId() + "/revisions";

        User inactive = user("inactive-property-staff", Role.ROLE_TENANT);
        EmployeeProfile inactiveProfile = new EmployeeProfile(inactive, "WFH_ADMIN", null,
                BigDecimal.ZERO);
        inactiveProfile.setStaffActive(false);
        profiles.saveAndFlush(inactiveProfile);
        propertyGrant(inactive, city, Instant.now().minusSeconds(60), null);
        assertEquals(404, send("POST", path, token(inactive), Map.of(), Map.of()).statusCode());

        User expired = user("expired-property-staff", Role.ROLE_TENANT);
        EmployeeProfile expiredProfile = new EmployeeProfile(expired, "WFH_ADMIN", null,
                BigDecimal.ZERO);
        expiredProfile.setStaffActive(true);
        profiles.saveAndFlush(expiredProfile);
        propertyGrant(expired, city, Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(60));
        assertEquals(404, send("POST", path, token(expired), Map.of(), Map.of()).statusCode());

        User wrongCity = user("wrong-city-property-staff", Role.ROLE_TENANT);
        EmployeeProfile wrongCityProfile = new EmployeeProfile(wrongCity, "WFH_ADMIN", null,
                BigDecimal.ZERO);
        wrongCityProfile.setStaffActive(true);
        profiles.saveAndFlush(wrongCityProfile);
        propertyGrant(wrongCity, otherCity, Instant.now().minusSeconds(60), null);
        assertEquals(404, send("POST", path, token(wrongCity), Map.of(), Map.of()).statusCode());

        assertTrue(drafts.findOpenRevisions(listing.getId(),
                List.of("DRAFT", "REVIEW", "CHANGES_REQUIRED", "APPROVED")).isEmpty());
        assertEquals(0L, revisionEvents.latestRowId(listing.getId()));
    }

    @Test
    void concurrentPublicationCommandsAllowOneCommitAndRejectTheStaleLoser() throws Exception {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        User owner = user("publish-race-owner", Role.ROLE_LANDLORD);
        LessorProfile lessor = lessorProfiles.saveAndFlush(new LessorProfile(null, owner.getId(),
                "Publish Race Owner", null, owner.getEmail(), LessorSourceType.SELF_SERVICE));
        RentalDetails listing = listing(owner, lessor, locality, "Concurrent publication");
        PropertyMediaAsset cover = media.saveAndFlush(new PropertyMediaAsset(listing.getId(),
                "https://example.test/publish-race.jpg", MediaType.IMAGE, RoomTag.LIVING_ROOM,
                "Concurrent publication"));
        cover.setIsPrimaryCover(true);
        cover.setSortOrder(0);
        media.saveAndFlush(cover);

        User staff = user("publish-race-staff", Role.ROLE_TENANT);
        EmployeeProfile profile = new EmployeeProfile(staff, "WFH_ADMIN", null, BigDecimal.ZERO);
        profile.setStaffActive(true);
        profiles.saveAndFlush(profile);
        propertyGrant(staff, city, Instant.now().minusSeconds(60), null);
        String token = token(staff);

        HttpResponse<String> started = send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions",
                token, Map.of(), Map.of());
        assertEquals(200, started.statusCode(), started.body());
        JsonNode revision = json.readTree(started.body());
        String draftId = revision.path("draftId").asText();
        int draftVersion = revision.path("version").asInt();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first = executor.submit(() -> {
                start.await();
                return publish(token, listing, draftId, draftVersion);
            });
            Future<HttpResponse<String>> second = executor.submit(() -> {
                start.await();
                return publish(token, listing, draftId, draftVersion);
            });
            start.countDown();
            List<HttpResponse<String>> responses = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, responses.stream().filter(response -> response.statusCode() == 200).count());
            assertEquals(1, responses.stream().filter(response -> response.statusCode() == 409).count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1L, scalar("select count(*) from property_publication_versions where listing_id="
                + listing.getId()));
        assertEquals(1L, scalar("select count(*) from property_publication_outbox where listing_id="
                + listing.getId()));
        assertEquals(1L, scalar("select count(*) from property_command_receipts where aggregate_id="
                + listing.getId() + " and command_type='PROPERTY_PUBLISH'"));
        assertEquals("APPROVED", scalarText(
                "select status from property_upload_drafts where draft_id='" + draftId + "'"));
    }

    @Test
    void publicationAndRemovalDecisionCompeteWithoutDuplicateTerminalEffects() throws Exception {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        User owner = user("publish-removal-race-owner", Role.ROLE_LANDLORD);
        LessorProfile lessor = lessorProfiles.saveAndFlush(new LessorProfile(null, owner.getId(),
                "Publish Removal Race Owner", null, owner.getEmail(),
                LessorSourceType.SELF_SERVICE));
        RentalDetails listing = listing(owner, lessor, locality, "Publication removal race");
        PropertyMediaAsset cover = media.saveAndFlush(new PropertyMediaAsset(listing.getId(),
                "https://example.test/publish-removal-race.jpg", MediaType.IMAGE, RoomTag.LIVING_ROOM,
                "Publication removal race"));
        cover.setIsPrimaryCover(true);
        cover.setSortOrder(0);
        media.saveAndFlush(cover);

        User staff = user("publish-removal-race-staff", Role.ROLE_TENANT);
        EmployeeProfile staffProfile = new EmployeeProfile(staff, "WFH_ADMIN", null, BigDecimal.ZERO);
        staffProfile.setStaffActive(true);
        profiles.saveAndFlush(staffProfile);
        propertyGrant(staff, city, Instant.now().minusSeconds(60), null);
        User admin = user("publish-removal-race-admin", Role.ROLE_ADMIN);
        EmployeeProfile adminProfile = new EmployeeProfile(admin, "WFH_ADMIN", null, BigDecimal.ZERO);
        adminProfile.setStaffActive(true);
        profiles.saveAndFlush(adminProfile);
        grants.saveAndFlush(new StaffAccessGrant(admin, StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "BACKOFFICE_RACE_TEST",
                StaffGrantProvisioningSource.ADMIN_API));

        String staffToken = token(staff);
        HttpResponse<String> started = send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions",
                staffToken, Map.of(), Map.of());
        assertEquals(200, started.statusCode(), started.body());
        JsonNode revision = json.readTree(started.body());
        String draftId = revision.path("draftId").asText();
        int draftVersion = revision.path("version").asInt();
        HttpResponse<String> request = send("POST",
                "/api/v1/lessor/properties/" + listing.getId() + "/removal-requests",
                token(owner), Map.of("If-Match", String.valueOf(listing.getVersion())),
                Map.of("operationId", UUID.randomUUID().toString(),
                        "reason", "Publication removal race"));
        assertEquals(201, request.statusCode(), request.body());
        long requestId = json.readTree(request.body()).path("request").path("requestId").asLong();
        int requestVersion = json.readTree(request.body()).path("request").path("requestVersion").asInt();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> publication = executor.submit(() -> {
                start.await();
                return publish(staffToken, listing, draftId, draftVersion);
            });
            Future<HttpResponse<String>> removal = executor.submit(() -> {
                start.await();
                return send("POST",
                        "/api/v1/admin/property-removal-requests/" + requestId + "/decision",
                        token(admin), Map.of("If-Match", String.valueOf(requestVersion)),
                        Map.of("operationId", UUID.randomUUID().toString(),
                                "decision", "APPROVED",
                                "expectedListingVersion", listing.getVersion(),
                                "reason", "Concurrent removal decision"));
            });
            start.countDown();
            List<HttpResponse<String>> responses = List.of(
                    publication.get(20, TimeUnit.SECONDS), removal.get(20, TimeUnit.SECONDS));
            assertEquals(1, responses.stream().filter(response -> response.statusCode() == 200).count(),
                    responses.toString());
            assertEquals(1, responses.stream().filter(response -> response.statusCode() == 409).count(),
                    responses.toString());
        } finally {
            executor.shutdownNow();
        }

        long publicationEffects = scalar("select count(*) from property_publication_versions "
                + "where listing_id=" + listing.getId());
        long removalEffects = scalar("select count(*) from property_removal_requests "
                + "where id=" + requestId + " and request_status='APPROVED'");
        assertEquals(1L, publicationEffects + removalEffects);
    }

    private RentalDetails listing(User owner, LessorProfile lessor, Locality locality, String title) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle(title);
        listing.setDescription("Original description");
        listing.setPropertyType(PropertyType.FLAT);
        listing.setBhkCount("2BHK");
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        listing.setOwnerUserId(owner.getId());
        listing.setLessorProfileId(lessor.getId());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setCity(locality.getCity());
        listing.setSector(locality.getSectorName());
        listing.setAddress("Original private address");
        listing.setMonthlyRent(new BigDecimal("25000.00"));
        listing.setSecurityDeposit(new BigDecimal("50000.00"));
        listing.setAvailableFrom(LocalDateTime.now().plusDays(7));
        listing.setPublishedAt(LocalDateTime.now().minusDays(3));
        return (RentalDetails) listings.saveAndFlush(listing);
    }

    private void assertPublicationEvidence(long listingId, long actorId, String draftId,
                                           long publicationVersion, long previousListingVersion,
                                           long listingVersion,
                                           String workflowStatus) throws Exception {
        assertEquals(1L, scalar("select count(*) from property_publication_versions "
                + "where listing_id=" + listingId + " and publication_version=" + publicationVersion));
        assertEquals(actorId + "|" + draftId + "|STAFF_DIRECT|" + publicationVersion,
                scalarText("select actor_user_id || '|' || revision_id || '|' || event_kind || '|' "
                        + "|| publication_version from property_publication_versions where listing_id="
                        + listingId + " and publication_version=" + publicationVersion));
        assertEquals(1L, scalar("select count(*) from property_revision_events where listing_id="
                + listingId + " and draft_id='" + draftId + "' and event_type='PUBLISHED'"));
        assertEquals(workflowStatus, scalarText(
                "select workflow_status from listings where id=" + listingId));

        JsonNode audit = json.readTree(scalarText(
                "select details::text from operational_audit_events where action_code="
                        + "'PROPERTY_CONTENT_PUBLISHED' and target_type='LISTING' and target_id="
                        + listingId + " order by id desc limit 1"));
        assertEquals(publicationVersion, audit.path("publicationVersion").asLong());
        assertEquals(previousListingVersion, audit.path("previousVersion").asLong());
        assertEquals(listingVersion, audit.path("resultingVersion").asLong());
        assertEquals("PROPERTY_EDIT_PUBLISH", audit.path("capability").asText());
        assertEquals("CITY", audit.path("scopeType").asText());
        assertTrue(audit.path("cityId").canConvertToLong());
        assertEquals(actorId, scalar(
                "select actor_user_id from operational_audit_events where action_code="
                        + "'PROPERTY_CONTENT_PUBLISHED' and target_type='LISTING' and target_id="
                        + listingId + " order by id desc limit 1"));

        assertEquals(1L, scalar("select count(*) from property_publication_outbox where listing_id="
                + listingId + " and publication_version=" + publicationVersion));
        JsonNode outbox = json.readTree(scalarText(
                "select payload::text from property_publication_outbox where listing_id="
                        + listingId + " and publication_version=" + publicationVersion));
        assertEquals(listingId, outbox.path("listingId").asLong());
        assertEquals(publicationVersion, outbox.path("publicationVersion").asLong());
        assertEquals(listingVersion, outbox.path("listingVersion").asLong());
        assertEquals(0L, scalar("select count(*) from property_publication_outbox where listing_id="
                + listingId + " and dispatched_at is not null"));
    }

    private long scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private String scalarText(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private HttpResponse<String> publish(String token, RentalDetails listing, String draftId,
                                         int draftVersion) throws Exception {
        return send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/publish",
                token, Map.of("If-Match", String.valueOf(draftVersion),
                        "X-Listing-Version", String.valueOf(listing.getVersion()),
                        "Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("reason", "Concurrent publication test"));
    }

    private User user(String label, Role role) {
        User user = new User();
        user.setEmail(label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setPhoneNumber("98765" + String.format("%05d", Math.abs(label.hashCode()) % 100_000));
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private void propertyGrant(User user, SupportedCity city, Instant effectiveAt,
                               Instant expiresAt) {
        grants.saveAndFlush(new StaffAccessGrant(user, StaffCapability.PROPERTY_EDIT_PUBLISH,
                StaffScopeType.CITY, city, null, effectiveAt, expiresAt, null,
                Instant.now(), "BACKOFFICE_SCOPE_TEST", StaffGrantProvisioningSource.ADMIN_API));
    }

    private String token(User user) {
        return jwt.generateToken(user.getId(), user.getEmail(), user.getRole().name());
    }

    private HttpResponse<String> send(String method, String path, String token,
                                      Map<String, String> headers, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        headers.forEach(request::header);
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendNoAuth(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

