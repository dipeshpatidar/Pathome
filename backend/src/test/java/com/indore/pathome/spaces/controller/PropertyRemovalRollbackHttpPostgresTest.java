package com.indore.pathome.spaces.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.service.NotificationService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

/**
 * Injects a failure after terminal removal persistence and proves whole-transaction rollback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=removal-rollback-test-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=removal-rollback-test-otp-secret",
        "cloudinary.api-key=removal-rollback-test-key",
        "cloudinary.api-secret=removal-rollback-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@Import(FreshIntegrationRuntimeConfiguration.class)
@EnabledIfEnvironmentVariable(named = FreshIntegrationTarget.TEST_ENABLEMENT_FLAG, matches = "true")
class PropertyRemovalRollbackHttpPostgresTest {
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
    @MockBean private NotificationService notifications;
    @LocalServerPort private int port;

    @Test
    void failureAfterTerminalFlushRollsBackRequestListingAuditAndActivity() throws Exception {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        User owner = user("rollback-owner", Role.ROLE_LANDLORD);
        LessorProfile profile = lessorProfiles.saveAndFlush(new LessorProfile(null,
                owner.getId(), "Rollback Owner", null, owner.getEmail(),
                LessorSourceType.SELF_SERVICE));
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Rollback fixture");
        listing.setDescription("Synthetic rollback fixture");
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
        listing.setPublishedAt(LocalDateTime.now().minusDays(1));
        listing = (RentalDetails) listings.saveAndFlush(listing);

        User admin = user("rollback-admin", Role.ROLE_ADMIN);
        EmployeeProfile adminProfile = new EmployeeProfile(admin, "WFH_ADMIN", null,
                BigDecimal.ZERO);
        adminProfile.setStaffActive(true);
        profiles.saveAndFlush(adminProfile);
        grants.saveAndFlush(new StaffAccessGrant(admin, StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "REMOVAL_ROLLBACK_TEST",
                StaffGrantProvisioningSource.ADMIN_API));

        String ownerToken = token(owner);
        HttpResponse<String> created = send("POST",
                "/api/v1/lessor/properties/" + listing.getId() + "/removal-requests",
                ownerToken, listing.getVersion(), json.writeValueAsString(Map.of(
                        "operationId", UUID.randomUUID(), "reason", "Rollback test")));
        assertEquals(201, created.statusCode(), created.body());
        long requestId = json.readTree(created.body()).path("request").path("requestId").asLong();
        long revisionsBefore = scalar("select count(*) from property_revision_events "
                + "where listing_id=" + listing.getId());

        doThrow(new IllegalStateException("injected failure after terminal flush"))
                .when(notifications).createNotificationWithEventKeyInCurrentTransaction(
                        any(), anyString(), anyString(), anyString(), any(), anyString(),
                        anyString(), any(), any(), anyString());

        HttpResponse<String> decision = send("POST",
                "/api/v1/admin/property-removal-requests/" + requestId + "/decision",
                token(admin), 0L, json.writeValueAsString(Map.of(
                        "operationId", UUID.randomUUID(),
                        "decision", "APPROVED",
                        "expectedListingVersion", listing.getVersion(),
                        "reason", "Must roll back")));
        assertEquals(500, decision.statusCode(), decision.body());

        PropertyRemovalRequest request = removals.findById(requestId).orElseThrow();
        assertEquals(PropertyRemovalRequestStatus.PENDING, request.getRequestStatus());
        RentalDetails current = (RentalDetails) listings.findById(listing.getId()).orElseThrow();
        assertEquals(ListingWorkflowStatus.PUBLISHED, current.getWorkflowStatus());
        assertEquals(ListingStatus.ACTIVE, current.getStatus());
        assertEquals(listing.getVersion(), current.getVersion());
        assertEquals(0L, scalar("select count(*) from operational_audit_events "
                + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id=" + requestId
                + " and action_code='PROPERTY_REMOVAL_APPROVED'"));
        assertEquals(revisionsBefore, scalar("select count(*) from property_revision_events "
                + "where listing_id=" + listing.getId()));
    }

    private User user(String label, Role role) {
        User user = new User();
        user.setEmail(label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(label);
        user.setRole(role);
        return users.saveAndFlush(user);
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

    private HttpResponse<String> send(String method, String path, String token,
                                      long version, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("If-Match", String.valueOf(version))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
