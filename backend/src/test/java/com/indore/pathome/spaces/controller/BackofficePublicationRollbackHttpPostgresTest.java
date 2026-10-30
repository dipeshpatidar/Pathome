package com.indore.pathome.spaces.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.service.OperationalAuditService;
import com.indore.pathome.spaces.support.FreshIntegrationRuntimeConfiguration;
import com.indore.pathome.spaces.support.FreshIntegrationTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Injects an audit failure after publication writes and proves one transaction rolls back. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=backoffice-publication-rollback-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=backoffice-publication-rollback-otp",
        "cloudinary.api-key=backoffice-rollback-key",
        "cloudinary.api-secret=backoffice-rollback-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@Import(FreshIntegrationRuntimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = FreshIntegrationTarget.TEST_ENABLEMENT_FLAG, matches = "true")
class BackofficePublicationRollbackHttpPostgresTest {
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
    @Autowired private DataSource dataSource;
    @MockBean private OperationalAuditService audit;
    @LocalServerPort private int port;

    @Test
    void auditFailureAfterContentPublicationWritesRollsBackEveryEffect() throws Exception {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        User owner = user("publication-rollback-owner", Role.ROLE_LANDLORD);
        LessorProfile lessor = lessorProfiles.saveAndFlush(new LessorProfile(null, owner.getId(),
                "Rollback Owner", null, owner.getEmail(), LessorSourceType.SELF_SERVICE));
        RentalDetails listing = listing(owner, lessor, locality, "Original rollback title");
        PropertyMediaAsset cover = media.saveAndFlush(new PropertyMediaAsset(listing.getId(),
                "https://example.test/rollback.jpg", MediaType.IMAGE, RoomTag.LIVING_ROOM,
                "Original rollback caption"));
        cover.setIsPrimaryCover(true);
        cover.setSortOrder(0);
        media.saveAndFlush(cover);

        User staff = user("publication-rollback-staff", Role.ROLE_TENANT);
        EmployeeProfile profile = new EmployeeProfile(staff, "WFH_ADMIN", null, BigDecimal.ZERO);
        profile.setStaffActive(true);
        profiles.saveAndFlush(profile);
        grants.saveAndFlush(new StaffAccessGrant(staff, StaffCapability.PROPERTY_EDIT_PUBLISH,
                StaffScopeType.CITY, city, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "PUBLICATION_ROLLBACK_TEST",
                StaffGrantProvisioningSource.ADMIN_API));

        String token = jwt.generateToken(staff.getId(), staff.getEmail(), staff.getRole().name());
        HttpResponse<String> started = send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions",
                token, Map.of(), Map.of());
        assertEquals(200, started.statusCode(), started.body());
        JsonNode revision = json.readTree(started.body());
        String draftId = revision.path("draftId").asText();
        int draftVersion = revision.path("version").asInt();
        HttpResponse<String> saved = send("PUT",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/basics",
                token, Map.of("If-Match", String.valueOf(draftVersion)),
                Map.of("title", "Must roll back", "description", "Must roll back",
                        "bhkCount", "2BHK", "propertyType", "FLAT"));
        assertEquals(200, saved.statusCode(), saved.body());
        draftVersion = json.readTree(saved.body()).path("version").asInt();
        when(audit.recordUserEvent(any(User.class), eq("PROPERTY_CONTENT_PUBLISHED"),
                eq("LISTING"), eq(listing.getId()), anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Injected publication audit failure"));

        HttpResponse<String> published = send("POST",
                "/api/v1/backoffice/properties/" + listing.getId() + "/revisions/"
                        + draftId + "/publish",
                token, Map.of("If-Match", String.valueOf(draftVersion),
                        "X-Listing-Version", String.valueOf(listing.getVersion()),
                        "Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("reason", "Rollback verification"));
        assertEquals(500, published.statusCode(), published.body());

        RentalDetails current = (RentalDetails) listings.findById(listing.getId()).orElseThrow();
        assertEquals("Original rollback title", current.getTitle());
        assertEquals(listing.getVersion(), current.getVersion());
        assertEquals(0L, scalar("select count(*) from property_publication_versions where listing_id="
                + listing.getId()));
        assertEquals(0L, scalar("select count(*) from property_publication_outbox where listing_id="
                + listing.getId()));
        assertEquals(0L, scalar("select count(*) from property_command_receipts where aggregate_id="
                + listing.getId() + " and command_type='PROPERTY_PUBLISH'"));
        assertEquals(0L, scalar("select count(*) from property_revision_events where listing_id="
                + listing.getId() + " and event_type='PUBLISHED'"));
        assertEquals("DRAFT", scalarText(
                "select status from property_upload_drafts where draft_id='" + draftId + "'"));
        assertEquals("Original rollback caption", media.findByListingIdOrderByUploadedAtDesc(
                listing.getId()).get(0).getCaption());
        assertTrue(scalar("select count(*) from operational_audit_events where action_code="
                + "'PROPERTY_CONTENT_PUBLISHED' and target_id=" + listing.getId()) == 0);
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
        listing.setAddress("Original address");
        listing.setMonthlyRent(new BigDecimal("25000.00"));
        listing.setSecurityDeposit(new BigDecimal("50000.00"));
        listing.setAvailableFrom(LocalDateTime.now().plusDays(7));
        listing.setPublishedAt(LocalDateTime.now().minusDays(3));
        return (RentalDetails) listings.saveAndFlush(listing);
    }

    private User user(String label, Role role) {
        User user = new User();
        user.setEmail(label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setRole(role);
        return users.saveAndFlush(user);
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
}
