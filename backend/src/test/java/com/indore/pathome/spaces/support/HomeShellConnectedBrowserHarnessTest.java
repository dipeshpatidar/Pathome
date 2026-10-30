package com.indore.pathome.spaces.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.config.DataInitializer;
import com.indore.pathome.spaces.entity.LessorProfile;
import com.indore.pathome.spaces.entity.LessorSourceType;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One guarded, test-only Home-shell GET and browser campaign over the attested disposable runtime.
 *
 * <p>The runtime role creates ordinary synthetic users/listings. The two missing Home-shell read
 * relations are seeded through the already-approved migrator selector and a single transaction so
 * the runtime is never broadened to INSERT or DELETE on those tables. The browser password remains
 * process input and is never written to state or evidence.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18080",
        "spring.task.scheduling.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "APP_FIRST_ADMIN_PROVISIONING_ENABLED=false",
        "app.jwt.secret=pathome-home-shell-browser-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=pathome-home-shell-browser-otp-secret",
        "cloudinary.api-key=home-shell-browser-test-key",
        "cloudinary.api-secret=home-shell-browser-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@Import(FreshIntegrationRuntimeConfiguration.class)
@ContextConfiguration(initializers = FreshIntegrationHarnessContextInitializer.class)
@EnabledIfEnvironmentVariable(named = "PATHOME_HOME_SHELL_BROWSER_HARNESS", matches = "true")
class HomeShellConnectedBrowserHarnessTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BUSINESS_CONTAINER =
            "2356041416c3b4df2abd449d8b5e1aeeaf113d0bdcd5752f8f6a0051e79791fb";
    private static final String BUSINESS_DATABASE = "pathome_it_20261025_01_business";

    @Autowired private ApplicationContext context;
    @Autowired private CloudinaryService provider;
    @Autowired private DataInitializer initializer;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder passwords;
    @Autowired private UserRepository users;
    @Autowired private LessorProfileRepository lessors;
    @Autowired private SupportedCityRepository cities;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void genuineHomeShellGetContractIsOwnerScopedAndPaginated() throws Exception {
        assertRuntimeGuards();
        World world = createWorld();

        Login populated = login(world.populatedTenant());
        Login alternate = login(world.alternateTenant());
        Login empty = login(world.emptyTenant());
        Login denied = login(world.deniedUser());

        JsonNode populatedFavorites = authorizedJson(
                "/api/v1/favorites/properties?page=0&size=6", populated.token(), 200);
        assertEquals(6, populatedFavorites.path("properties").size());
        assertEquals(0, populatedFavorites.path("page").asInt());
        assertTrue(populatedFavorites.path("hasMore").asBoolean());
        assertFavoritePage(populatedFavorites, world.populatedFavoriteIds().subList(0, 6));

        JsonNode populatedFavoritesTail = authorizedJson(
                "/api/v1/favorites/properties?page=1&size=6", populated.token(), 200);
        assertFalse(populatedFavoritesTail.path("hasMore").asBoolean());
        assertFavoritePage(populatedFavoritesTail,
                world.populatedFavoriteIds().subList(6, 7));
        assertNoOverlap(populatedFavorites, populatedFavoritesTail, "properties");

        JsonNode alternateFavorites = authorizedJson(
                "/api/v1/favorites/properties?page=0&size=6", alternate.token(), 200);
        assertEquals(6, alternateFavorites.path("properties").size());
        assertFavoritePage(alternateFavorites, world.alternateFavoriteIds().subList(0, 6));

        JsonNode emptyFavorites = authorizedJson(
                "/api/v1/favorites/properties?page=0&size=6", empty.token(), 200);
        assertEquals(0, emptyFavorites.path("properties").size());
        assertFalse(emptyFavorites.path("hasMore").asBoolean());

        JsonNode populatedVisits = authorizedJson(
                "/api/v1/tenant/visit-requests?page=0&userId=" + empty.userId(),
                populated.token(), 200);
        assertEquals(populated.userId(), populatedVisits.path("userId").asLong());
        assertEquals(8, populatedVisits.path("requests").size());
        assertEquals(9, populatedVisits.path("totalCount").asLong());
        assertEquals(0, populatedVisits.path("page").asInt());
        assertTrue(populatedVisits.path("hasMore").asBoolean());
        assertVisitPage(populatedVisits, world.populatedVisitPropertyIds().subList(0, 8));

        JsonNode populatedVisitsTail = authorizedJson(
                "/api/v1/tenant/visit-requests?page=1", populated.token(), 200);
        assertEquals(1, populatedVisitsTail.path("requests").size());
        assertFalse(populatedVisitsTail.path("hasMore").asBoolean());
        assertVisitPage(populatedVisitsTail, world.populatedVisitPropertyIds().subList(8, 9));
        assertNoOverlap(populatedVisits, populatedVisitsTail, "requests");

        JsonNode alternateVisits = authorizedJson(
                "/api/v1/tenant/visit-requests?page=0", alternate.token(), 200);
        assertEquals(alternate.userId(), alternateVisits.path("userId").asLong());
        assertEquals(9, alternateVisits.path("totalCount").asLong());
        assertVisitPage(alternateVisits, world.alternateVisitPropertyIds().subList(0, 8));

        JsonNode emptyVisits = authorizedJson(
                "/api/v1/tenant/visit-requests?page=0", empty.token(), 200);
        assertEquals(empty.userId(), emptyVisits.path("userId").asLong());
        assertEquals(0, emptyVisits.path("requests").size());
        assertEquals(0, emptyVisits.path("totalCount").asLong());
        assertFalse(emptyVisits.path("hasMore").asBoolean());

        assertEquals(401, send("GET", "/api/v1/favorites/properties?page=0&size=6", null)
                .statusCode());
        assertEquals(401, send("GET", "/api/v1/tenant/visit-requests?page=0", null)
                .statusCode());
        assertEquals(401, send("GET", "/api/v1/favorites/properties?page=0&size=6",
                "not-a-valid-token").statusCode());
        assertEquals(403, send("GET", "/api/v1/favorites/properties?page=0&size=6",
                denied.token()).statusCode());
        assertEquals(403, send("GET", "/api/v1/tenant/visit-requests?page=0",
                denied.token()).statusCode());

        assertEquals(8L, scalar("select count(*) from user_favorites where user_id=?",
                world.populatedTenant().getId()));
        assertEquals(0L, scalar("select count(*) from user_favorites where user_id=?",
                world.emptyTenant().getId()));
        assertEquals(8L, scalar("select count(*) from user_favorites where user_id=?",
                world.alternateTenant().getId()));
        assertEquals(9L, scalar("select count(*) from property_visit_requests where tenant_id=?",
                world.populatedTenant().getId()));
        assertEquals(0L, scalar("select count(*) from property_visit_requests where tenant_id=?",
                world.emptyTenant().getId()));
        assertEquals(9L, scalar("select count(*) from property_visit_requests where tenant_id=?",
                world.alternateTenant().getId()));
    }

    @Test
    void genuineTenantVisitHistoryAuthorizedRead() throws Exception {
        assertRuntimeGuards();
        World world = createWorld();
        Login populated = login(world.populatedTenant());
        JsonNode page = authorizedJson("/api/v1/tenant/visit-requests?page=0",
                populated.token(), 200);
        assertEquals(populated.userId(), page.path("userId").asLong());
        assertEquals(9, page.path("totalCount").asLong());
    }

    @Test
    void genuineConnectedHomeShellBrowserJourneyPreservesAccountTruth() throws Exception {
        assertRuntimeGuards();
        World world = createWorld();
        Path stateFile = requiredPath("PATHOME_HOME_SHELL_BROWSER_STATE_FILE");
        Path stopFile = requiredPath("PATHOME_HOME_SHELL_BROWSER_STOP_FILE");
        Path receiptFile = requiredPath("PATHOME_HOME_SHELL_BROWSER_RECEIPT");
        assertFalse(Files.exists(stateFile), "browser state path must be new");
        assertFalse(Files.exists(receiptFile), "browser receipt path must be new");

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("populatedEmail", world.populatedTenant().getEmail());
        state.put("alternateEmail", world.alternateTenant().getEmail());
        state.put("emptyEmail", world.emptyTenant().getEmail());
        state.put("deniedEmail", world.deniedUser().getEmail());
        state.put("populatedFavoriteIds", world.populatedFavoriteEntries().stream()
                .map(HomeReadEntry::id).toList());
        state.put("populatedFavoriteTitles", world.populatedFavoriteEntries().stream()
                .map(HomeReadEntry::title).toList());
        state.put("alternateFavoriteIds", world.alternateFavoriteEntries().stream()
                .map(HomeReadEntry::id).toList());
        state.put("alternateFavoriteTitles", world.alternateFavoriteEntries().stream()
                .map(HomeReadEntry::title).toList());
        state.put("populatedVisitEntries", world.populatedVisitEntries().stream()
                .map(entry -> Map.of("requestId", entry.requestId(),
                        "propertyId", entry.id(), "title", entry.title()))
                .toList());
        state.put("alternateVisitEntries", world.alternateVisitEntries().stream()
                .map(entry -> Map.of("requestId", entry.requestId(),
                        "propertyId", entry.id(), "title", entry.title()))
                .toList());
        Files.createDirectories(stateFile.getParent());
        Files.writeString(stateFile, json.writeValueAsString(state), StandardOpenOption.CREATE_NEW);

        int maxSeconds = Integer.parseInt(System.getenv().getOrDefault(
                "PATHOME_HOME_SHELL_BROWSER_MAX_SECONDS", "600"));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(maxSeconds));
        while (!Files.exists(stopFile) && Instant.now().isBefore(deadline)) {
            Thread.sleep(500);
        }
        assertTrue(Files.exists(stopFile), "connected Home-shell browser batch did not complete");
        assertTrue(Files.isRegularFile(receiptFile), "connected Home-shell receipt is missing");
        JsonNode receipt = json.readTree(Files.readString(receiptFile));
        assertEquals(0, receipt.path("failures").asInt(), receipt.toString());
        assertTrue(receipt.path("checks").asInt() >= 1);
    }

    private void assertRuntimeGuards() {
        assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> provider.findExistingResourceByUploadRequestId("home-shell-guard", false));
        assertEquals(Boolean.FALSE, org.springframework.test.util.ReflectionTestUtils.getField(
                initializer, "firstAdminProvisioningEnabled"));
    }

    private World createWorld() throws Exception {
        SupportedCity city = cities.findAll().stream().filter(SupportedCity::isActive)
                .findFirst().orElseThrow();
        Locality locality = localities.findBySupportedCityIdOrderBySectorNameAscIdAsc(city.getId())
                .stream().findFirst().orElseThrow();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String password = browserPassword();

        User populatedTenant = user("home-shell-populated-" + suffix, Role.ROLE_TENANT, password);
        User alternateTenant = user("home-shell-alternate-" + suffix, Role.ROLE_TENANT, password);
        User emptyTenant = user("home-shell-empty-" + suffix, Role.ROLE_TENANT, password);
        User deniedUser = user("home-shell-denied-" + suffix, Role.ROLE_LANDLORD, password);
        LessorProfile ownerProfile = lessors.saveAndFlush(new LessorProfile(null,
                deniedUser.getId(), "Home Shell Synthetic Owner", null, deniedUser.getEmail(),
                LessorSourceType.SELF_SERVICE));

        List<RentalDetails> populatedActive = new ArrayList<>();
        List<RentalDetails> alternateActive = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            populatedActive.add(listing("Home Shell saved " + index + " " + suffix, city, locality,
                    deniedUser, ownerProfile, ListingStatus.ACTIVE));
            alternateActive.add(listing("Home Shell saved " + index + " " + suffix, city, locality,
                    deniedUser, ownerProfile, ListingStatus.ACTIVE));
        }
        RentalDetails inactive = listing("Home Shell inactive " + suffix, city, locality,
                deniedUser, ownerProfile, ListingStatus.PENDING);

        List<Long> populatedFavoriteIds = populatedActive.stream().limit(7)
                .map(RentalDetails::getId).toList();
        List<Long> populatedRequestedIds = populatedActive.stream().limit(9)
                .map(RentalDetails::getId).toList();
        List<Long> alternateFavoriteIds = alternateActive.stream().limit(7)
                .map(RentalDetails::getId).toList();
        List<Long> alternateRequestedIds = alternateActive.stream().limit(9)
                .map(RentalDetails::getId).toList();
        seedHomeReadFixtures(populatedTenant.getId(), populatedFavoriteIds, inactive.getId(),
                populatedRequestedIds);
        seedHomeReadFixtures(alternateTenant.getId(), alternateFavoriteIds, inactive.getId(),
                alternateRequestedIds);
        List<Long> populatedFavoriteIdsDescending = populatedFavoriteIds.stream()
                .sorted(Comparator.reverseOrder())
                .toList();
        List<Long> populatedVisitPropertyIdsDescending = populatedRequestedIds.stream()
                .sorted(Comparator.reverseOrder())
                .toList();
        List<Long> alternateFavoriteIdsDescending = alternateFavoriteIds.stream()
                .sorted(Comparator.reverseOrder())
                .toList();
        List<Long> alternateVisitPropertyIdsDescending = alternateRequestedIds.stream()
                .sorted(Comparator.reverseOrder())
                .toList();
        Map<Long, String> populatedTitles = populatedActive.stream().collect(Collectors.toMap(
                RentalDetails::getId, RentalDetails::getTitle));
        Map<Long, String> alternateTitles = alternateActive.stream().collect(Collectors.toMap(
                RentalDetails::getId, RentalDetails::getTitle));
        return new World(
                populatedTenant,
                alternateTenant,
                emptyTenant,
                deniedUser,
                inactive.getId(),
                populatedFavoriteIdsDescending.stream()
                        .map(id -> new HomeReadEntry(id, populatedTitles.get(id))).toList(),
                alternateFavoriteIdsDescending.stream()
                        .map(id -> new HomeReadEntry(id, alternateTitles.get(id))).toList(),
                requestEntries(populatedTenant.getId(), populatedTitles),
                requestEntries(alternateTenant.getId(), alternateTitles));
    }

    private RentalDetails listing(String title, SupportedCity city, Locality locality, User owner,
            LessorProfile profile, ListingStatus status) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle(title);
        listing.setDescription("Synthetic Home-shell GET fixture");
        listing.setPropertyType(PropertyType.FLAT);
        listing.setBhkCount("2BHK");
        listing.setStatus(status);
        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        listing.setOwnerUserId(owner.getId());
        listing.setLessorProfileId(profile.getId());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setCity(locality.getCity());
        listing.setSector(locality.getSectorName());
        listing.setAddress("Synthetic Home-shell address");
        listing.setMonthlyRent(new BigDecimal("25000.00"));
        listing.setSecurityDeposit(new BigDecimal("50000.00"));
        listing.setAvailableFrom(LocalDate.now().plusDays(7).atStartOfDay());
        listing.setPublishedAt(LocalDateTime.now().minusDays(2));
        return (RentalDetails) listings.saveAndFlush(listing);
    }

    private User user(String emailPrefix, Role role, String password) {
        User user = new User();
        user.setEmail(emailPrefix + "@example.test");
        user.setPasswordHash(passwords.encode(password));
        user.setFullName(emailPrefix);
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    /**
     * The migrator owns fixture creation for exactly the two approved Home read relations. The
     * runtime role receives SELECT only and never sees this credential.
     */
    private void seedHomeReadFixtures(long tenantId, List<Long> activeFavoriteIds,
            long inactiveFavoriteId, List<Long> requestListingIds) throws Exception {
        String container = dockerOutput("inspect", "--format", "{{.Id}}",
                "pathome-it-backoffice-20261025-01").trim();
        assertEquals(BUSINESS_CONTAINER, container,
                "refusing fixture seed against an unattested container");
        List<String> values = new ArrayList<>();
        activeFavoriteIds.forEach(id -> values.add("(" + tenantId + "," + id + ")"));
        values.add("(" + tenantId + "," + inactiveFavoriteId + ")");
        List<String> requestValues = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            requestValues.add("(" + tenantId + "," + requestListingIds.get(index)
                    + ",'Synthetic timing " + (index + 1) + "','RECEIVED',now()-interval '"
                    + (9 - index) + " day')");
        }
        String sql = """
                SET search_path TO public, it_extensions, pg_catalog;
                DO $$ BEGIN
                  IF current_database() <> 'pathome_it_20261025_01_business'
                     OR current_user <> 'itp_20261025_01_migrator' THEN
                    RAISE EXCEPTION 'Home-shell fixture seed target refused';
                  END IF;
                END $$;
                BEGIN;
                INSERT INTO user_favorites (user_id, listing_id) VALUES
                %s;
                INSERT INTO property_visit_requests
                  (tenant_id, listing_id, preferred_visit_timing, status, created_at)
                VALUES
                %s;
                COMMIT;
                """.formatted(String.join(",", values), String.join(",", requestValues));
        dockerOutput("exec", "--user", "0", "--env", "PGPASSFILE=/run/pathome-roles.pgpass",
                "pathome-it-backoffice-20261025-01", "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
                "-U", "itp_20261025_01_migrator", "-d", BUSINESS_DATABASE,
                "-h", "127.0.0.1", "-c", sql);
    }

    private List<HomeReadEntry> requestEntries(long tenantId, Map<Long, String> titles) {
        return jdbc.query("""
                        select id, listing_id
                        from property_visit_requests
                        where tenant_id=?
                        order by created_at desc, id desc
                        """,
                (resultSet, rowNumber) -> new HomeReadEntry(
                        resultSet.getLong("listing_id"),
                        titles.get(resultSet.getLong("listing_id")),
                        resultSet.getLong("id")),
                tenantId);
    }

    private String dockerOutput(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("--context");
        command.add("colima");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "docker command timed out");
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private Login login(User user) throws Exception {
        String body = json.writeValueAsString(Map.of("email", user.getEmail(),
                "password", browserPassword()));
        HttpResponse<String> response = rawSend("POST", "/api/v1/auth/login", null, body);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode payload = json.readTree(response.body());
        assertEquals(user.getId(), payload.path("userId").asLong());
        return new Login(payload.path("token").asText(), payload.path("userId").asLong());
    }

    private JsonNode authorizedJson(String path, String token, int expectedStatus) throws Exception {
        HttpResponse<String> response = send("GET", path, token);
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        return rawSend(method, path, token, null);
    }

    private HttpResponse<String> rawSend(String method, String path, String token, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:18080" + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertFavoritePage(JsonNode page, List<Long> expectedIds) {
        List<Long> actual = new ArrayList<>();
        page.path("properties").forEach(item -> actual.add(item.path("id").asLong()));
        assertEquals(expectedIds, actual);
    }

    private void assertVisitPage(JsonNode page, List<Long> expectedIds) {
        List<Long> actual = new ArrayList<>();
        page.path("requests").forEach(item -> actual.add(item.path("propertyId").asLong()));
        assertEquals(expectedIds, actual);
    }

    private void assertNoOverlap(JsonNode first, JsonNode second, String collection) {
        List<Long> firstIds = new ArrayList<>();
        List<Long> secondIds = new ArrayList<>();
        first.path(collection).forEach(item -> firstIds.add(
                item.has("id") ? item.path("id").asLong() : item.path("requestId").asLong()));
        second.path(collection).forEach(item -> secondIds.add(
                item.has("id") ? item.path("id").asLong() : item.path("requestId").asLong()));
        firstIds.retainAll(secondIds);
        assertTrue(firstIds.isEmpty(), "paginated read repeated rows: " + firstIds);
    }

    private long scalar(String sql, long userId) {
        Long value = jdbc.queryForObject(sql, Long.class, userId);
        return value == null ? -1 : value;
    }

    private static String browserPassword() {
        String password = System.getenv("PATHOME_BROWSER_TEST_PASSWORD");
        assertNotNull(password, "synthetic browser password must be supplied");
        assertTrue(password.length() >= 12, "synthetic browser password must be at least 12 characters");
        return password;
    }

    private static Path requiredPath(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be explicit");
        return Path.of(value).toAbsolutePath().normalize();
    }

    private record Login(String token, long userId) {}

    private record HomeReadEntry(long id, String title, long requestId) {
        private HomeReadEntry(long id, String title) {
            this(id, title, -1);
        }
    }

    private record World(User populatedTenant, User alternateTenant, User emptyTenant,
            User deniedUser, long inactiveFavoriteId,
            List<HomeReadEntry> populatedFavoriteEntries,
            List<HomeReadEntry> alternateFavoriteEntries,
            List<HomeReadEntry> populatedVisitEntries,
            List<HomeReadEntry> alternateVisitEntries) {
        private List<Long> populatedFavoriteIds() {
            return populatedFavoriteEntries.stream().map(HomeReadEntry::id).toList();
        }

        private List<Long> alternateFavoriteIds() {
            return alternateFavoriteEntries.stream().map(HomeReadEntry::id).toList();
        }

        private List<Long> populatedVisitPropertyIds() {
            return populatedVisitEntries.stream().map(HomeReadEntry::id).toList();
        }

        private List<Long> alternateVisitPropertyIds() {
            return alternateVisitEntries.stream().map(HomeReadEntry::id).toList();
        }
    }
}
