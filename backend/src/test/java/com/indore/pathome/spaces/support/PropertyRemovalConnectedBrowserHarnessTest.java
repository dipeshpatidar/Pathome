package com.indore.pathome.spaces.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.config.DataInitializer;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.service.CloudinaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-only lifetime for one genuine Spring/PostgreSQL plus Chromium Removal journey.
 * The browser password is supplied through the process environment and is never written to state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18080",
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "APP_FIRST_ADMIN_PROVISIONING_ENABLED=false",
        "app.jwt.secret=pathome-removal-browser-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=pathome-removal-browser-otp-secret",
        "cloudinary.api-key=removal-browser-test-key",
        "cloudinary.api-secret=removal-browser-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@Import(FreshIntegrationRuntimeConfiguration.class)
@ContextConfiguration(initializers = FreshIntegrationHarnessContextInitializer.class)
@EnabledIfEnvironmentVariable(named = "PATHOME_REMOVAL_BROWSER_HARNESS", matches = "true")
class PropertyRemovalConnectedBrowserHarnessTest {
    @Autowired private ApplicationContext context;
    @Autowired private CloudinaryService provider;
    @Autowired private DataInitializer initializer;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder passwords;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository profiles;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private LessorProfileRepository lessors;
    @Autowired private SupportedCityRepository cities;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @Autowired private PropertyRemovalRequestRepository removals;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void genuineRemovalBrowserJourneyCommitsApproveAndRejectOnce() throws Exception {
        assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> provider.findExistingResourceByUploadRequestId("removal-browser-guard", false));
        assertEquals(Boolean.FALSE, org.springframework.test.util.ReflectionTestUtils.getField(
                initializer, "firstAdminProvisioningEnabled"));

        Path stateFile = requiredPath("PATHOME_REMOVAL_BROWSER_STATE_FILE");
        Path stopFile = requiredPath("PATHOME_REMOVAL_BROWSER_STOP_FILE");
        Path receiptFile = requiredPath("PATHOME_REMOVAL_BROWSER_RECEIPT");
        assertFalse(Files.exists(stateFile), "browser state path must be new");
        assertFalse(Files.exists(receiptFile), "browser receipt path must be new");
        String password = System.getenv("PATHOME_BROWSER_TEST_PASSWORD");
        assertTrue(password != null && password.length() >= 12,
                "synthetic browser test password must be supplied through the environment");

        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        User admin = user("connected-removal-admin-" + suffix, Role.ROLE_ADMIN, password);
        EmployeeProfile profile = new EmployeeProfile(admin, "WFH_ADMIN", null, BigDecimal.ZERO);
        profile.setStaffActive(true);
        profiles.saveAndFlush(profile);
        grants.saveAndFlush(new StaffAccessGrant(admin, StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null,
                null, Instant.now(), "CONNECTED_BROWSER_TEST",
                StaffGrantProvisioningSource.ADMIN_API));
        User denied = user("connected-removal-denied-" + suffix, Role.ROLE_TENANT, password);

        Fixture approve = fixture("Connected approval fixture " + suffix, city, locality, password);
        Fixture reject = fixture("Connected rejection fixture " + suffix, city, locality, password);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("adminEmail", admin.getEmail());
        state.put("deniedEmail", denied.getEmail());
        state.put("approveRequestId", approve.request().getId());
        state.put("approveListingId", approve.listing().getId());
        state.put("approveTitle", approve.listing().getTitle());
        state.put("rejectRequestId", reject.request().getId());
        state.put("rejectListingId", reject.listing().getId());
        state.put("rejectTitle", reject.listing().getTitle());
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, json.writeValueAsString(state), StandardOpenOption.CREATE_NEW);

        int maxSeconds = Integer.parseInt(System.getenv().getOrDefault(
                "PATHOME_REMOVAL_BROWSER_MAX_SECONDS", "600"));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(maxSeconds));
        while (!Files.exists(stopFile) && Instant.now().isBefore(deadline)) {
            Thread.sleep(500);
        }
        assertTrue(Files.exists(stopFile), "connected browser batch did not signal completion");
        assertTrue(Files.isRegularFile(receiptFile), "connected browser receipt is missing");
        JsonNode receipt = json.readTree(Files.readString(receiptFile));
        assertEquals(0, receipt.path("failures").asInt(), receipt.toString());
        assertTrue(receipt.path("checks").asInt() >= 1);

        assertEquals(PropertyRemovalRequestStatus.APPROVED,
                removals.findById(approve.request().getId()).orElseThrow().getRequestStatus());
        assertEquals(PropertyRemovalRequestStatus.REJECTED,
                removals.findById(reject.request().getId()).orElseThrow().getRequestStatus());
        assertEquals(ListingWorkflowStatus.ARCHIVED,
                listings.findById(approve.listing().getId()).orElseThrow().getWorkflowStatus());
        assertEquals(ListingWorkflowStatus.PUBLISHED,
                listings.findById(reject.listing().getId()).orElseThrow().getWorkflowStatus());
        assertEquals(1L, countAudit("PROPERTY_REMOVAL_APPROVED", approve.request().getId()));
        assertEquals(1L, countAudit("PROPERTY_REMOVAL_REJECTED", reject.request().getId()));
        assertEquals(1L, countRevision("REMOVAL_APPROVED", approve.listing().getId()));
        assertEquals(1L, countRevision("REMOVAL_REJECTED", reject.listing().getId()));
        assertEquals(1L, countNotification("property-removal-" + approve.request().getId()
                + "-APPROVED"));
        assertEquals(1L, countNotification("property-removal-" + reject.request().getId()
                + "-REJECTED"));
    }

    private Fixture fixture(String title, SupportedCity city, Locality locality, String password) {
        User owner = user("connected-removal-owner-" + UUID.randomUUID(),
                Role.ROLE_LANDLORD, password);
        LessorProfile profile = lessors.saveAndFlush(new LessorProfile(null, owner.getId(),
                title, null, owner.getEmail(), LessorSourceType.SELF_SERVICE));
        RentalDetails listing = new RentalDetails();
        listing.setTitle(title);
        listing.setDescription("Connected browser fixture");
        listing.setPropertyType(PropertyType.FLAT);
        listing.setBhkCount("2BHK");
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        listing.setOwnerUserId(owner.getId());
        listing.setLessorProfileId(profile.getId());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setCity(locality.getCity());
        listing.setSector(locality.getSectorName());
        listing.setAddress("Synthetic connected address");
        listing.setMonthlyRent(new BigDecimal("25000.00"));
        listing.setSecurityDeposit(new BigDecimal("50000.00"));
        listing.setAvailableFrom(LocalDate.now().plusDays(7).atStartOfDay());
        listing.setPublishedAt(LocalDateTime.now().minusDays(2));
        listing = (RentalDetails) listings.saveAndFlush(listing);
        PropertyRemovalRequest request = removals.saveAndFlush(new PropertyRemovalRequest(
                listing.getId(), owner.getId(), UUID.randomUUID(),
                "connected-browser-" + UUID.randomUUID(), Instant.now(),
                listing.getVersion(), "Connected browser owner reason"));
        return new Fixture(listing, request);
    }

    private User user(String emailPrefix, Role role, String password) {
        User user = new User();
        user.setEmail(emailPrefix + "@example.test");
        user.setPasswordHash(passwords.encode(password));
        user.setFullName(emailPrefix);
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private long countAudit(String action, long requestId) {
        Long count = jdbc.queryForObject("select count(*) from operational_audit_events "
                        + "where target_type='PROPERTY_REMOVAL_REQUEST' and target_id=? "
                        + "and action_code=?", Long.class, requestId, action);
        return count == null ? -1 : count;
    }

    private long countRevision(String event, long listingId) {
        Long count = jdbc.queryForObject("select count(*) from property_revision_events "
                        + "where listing_id=? and event_type=?", Long.class, listingId, event);
        return count == null ? -1 : count;
    }

    private long countNotification(String eventKey) {
        Long count = jdbc.queryForObject("select count(*) from system_notifications "
                + "where event_key=?", Long.class, eventKey);
        return count == null ? -1 : count;
    }

    private static Path requiredPath(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be explicit");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private record Fixture(RentalDetails listing, PropertyRemovalRequest request) {}
}
