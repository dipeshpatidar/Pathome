package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget;
import com.indore.pathome.spaces.support.DisposablePostgresTarget;
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
import java.sql.Statement;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R1-OE-1 real HTTP/PostgreSQL availability lifecycle.
 *
 * <p>Proves tenant-self ownership, RECEIVED/standalone and COORDINATING/DRAFT eligibility, refusal of
 * post-booking states, stale-version conflicts, all-null clearing, PostgreSQL read-back and
 * serialization against a concurrent Session schedule.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "server.address=127.0.0.1",
        "app.jwt.secret=tenant-availability-http-test-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=tenant-availability-http-test-otp-secret-32-bytes",
        "cloudinary.api-key=tenant-availability-test-key",
        "cloudinary.api-secret=tenant-availability-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class TenantVisitAvailabilityHttpPostgresTest {
    private static final String SCHEMA = "tenant_availability_http_test_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static DisposablePostgresTarget.Verified verifiedTarget;
    private static boolean schemaCreated;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        // Fail closed before any JDBC connection, Flyway run or schema DDL.
        verifiedTarget = DisposablePostgresTarget.verifyFromEnvironment();
        DisposableIntegrationTarget.Target target = verifiedTarget.target();
        try (var connection = DriverManager.getConnection(target.jdbcUrl(), target.username(), target.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            schemaCreated = true;
            try {
                connection.setSchema(SCHEMA);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                flyway().target("40").load().migrate();
                flyway().load().migrate();
            } catch (Exception failure) {
                dropVerifiedSchema();
                throw failure;
            }
        }
        registry.add("spring.datasource.url", () -> verifiedTarget.target().schemaUrl(SCHEMA));
        registry.add("spring.datasource.username", () -> verifiedTarget.target().username());
        registry.add("spring.datasource.password", () -> verifiedTarget.target().password());
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(verifiedTarget.target().jdbcUrl(),
                        verifiedTarget.target().username(), verifiedTarget.target().password()).schemas(SCHEMA)
                .defaultSchema(SCHEMA).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        dropVerifiedSchema();
    }

    private static void dropVerifiedSchema() throws Exception {
        if (!schemaCreated || verifiedTarget == null) return;
        var current = DisposablePostgresTarget.verifyFromEnvironment();
        if (!current.runId().equals(verifiedTarget.runId()))
            throw new IllegalStateException("Refused cleanup: test-run identity changed");
        try (var connection = DriverManager.getConnection(verifiedTarget.target().jdbcUrl(),
                verifiedTarget.target().username(), verifiedTarget.target().password());
             Statement statement = connection.createStatement()) {
            try (var owner = connection.prepareStatement(
                    "select pg_get_userbyid(nspowner) = current_user from pg_namespace where nspname = ?")) {
                owner.setString(1, SCHEMA);
                try (var rows = owner.executeQuery()) {
                    if (!rows.next() || !rows.getBoolean(1))
                        throw new IllegalStateException("Refused cleanup: schema ownership not verified");
                }
            }
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            schemaCreated = false;
        }
    }

    @Autowired private ObjectMapper mapper;
    @Autowired private JwtUtils jwt;
    @Autowired private UserRepository users;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private VisitSessionRepository sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.context.ApplicationContext application;
    @LocalServerPort private int httpPort;

    private final String start = LocalDate.now(ZONE).plusDays(5)
            .atTime(10, 0).atZone(ZONE).toOffsetDateTime().toString();
    private final String end = LocalDate.now(ZONE).plusDays(5)
            .atTime(13, 0).atZone(ZONE).toOffsetDateTime().toString();
    private final String preferred = LocalDate.now(ZONE).plusDays(5)
            .atTime(11, 0).atZone(ZONE).toOffsetDateTime().toString();

    @Test
    void standaloneReceivedAndLinkedDraftAreEditableButScheduledIsNot() throws Exception {
        Fixture fixture = fixture("lifecycle");
        PropertyVisitRequest request = standalone(fixture, null, VisitRequestStatus.RECEIVED);

        HttpResponse<String> first = update(request.getId(), fixture.tenantToken(), request.getVersion(), start, end, preferred);
        assertEquals(200, first.statusCode(), first.body());
        JsonNode firstBody = mapper.readTree(first.body());
        assertEquals(request.getId(), firstBody.path("requestId").asLong());
        assertEquals(1L, firstBody.path("version").asLong());
        assertEquals("Asia/Kolkata", firstBody.path("availabilityZoneId").asText());

        JsonNode history = read("GET", "/api/v1/tenant/visit-requests", fixture.tenantToken());
        JsonNode historyRow = history.path("requests").get(0);
        assertEquals("Saturday afternoon", historyRow.path("preferredVisitTiming").asText());
        assertEquals(1L, historyRow.path("version").asLong());
        assertEquals("Asia/Kolkata", historyRow.path("availabilityZoneId").asText());

        request = requests.findById(request.getId()).orElseThrow();
        VisitSession draft = session(fixture, VisitSessionStatus.DRAFT);
        link(request, draft, VisitRequestStatus.COORDINATING);
        request = requests.findById(request.getId()).orElseThrow();
        HttpResponse<String> linked = update(request.getId(), fixture.tenantToken(), request.getVersion(), start, end, preferred);
        assertEquals(200, linked.statusCode(), linked.body());

        request = requests.findById(request.getId()).orElseThrow();
        VisitSession scheduled = session(fixture, VisitSessionStatus.SCHEDULED);
        link(request, scheduled, VisitRequestStatus.COORDINATING);
        PropertyVisitRequest scheduledRequest = requests.findById(request.getId()).orElseThrow();
        String beforeStart = jdbc.queryForObject(
                "select availability_start_at::text from property_visit_requests where id = ?",
                String.class, request.getId());

        HttpResponse<String> refused = update(request.getId(), fixture.tenantToken(),
                scheduledRequest.getVersion(), start, end, preferred);
        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals(beforeStart, jdbc.queryForObject(
                "select availability_start_at::text from property_visit_requests where id = ?",
                String.class, request.getId()), "a refused post-booking update must not mutate availability");
    }

    @Test
    void versionConflictForeignTenantAndExplicitClearAreHandledTruthfully() throws Exception {
        Fixture fixture = fixture("conflict");
        PropertyVisitRequest request = standalone(fixture, null, VisitRequestStatus.RECEIVED);
        HttpResponse<String> first = update(request.getId(), fixture.tenantToken(), request.getVersion(), start, end, preferred);
        assertEquals(200, first.statusCode(), first.body());
        long version = mapper.readTree(first.body()).path("version").asLong();

        HttpResponse<String> stale = update(request.getId(), fixture.tenantToken(), version - 1, start, end, preferred);
        assertEquals(409, stale.statusCode(), stale.body());

        User foreign = users.saveAndFlush(user("foreign-" + UUID.randomUUID(), Role.ROLE_TENANT,
                "Foreign Tenant", "9999999999"));
        String foreignToken = jwt.generateToken(foreign.getId(), foreign.getEmail(), foreign.getRole().name());
        assertEquals(404, update(request.getId(), foreignToken, version, start, end, preferred).statusCode());

        HttpResponse<String> cleared = update(request.getId(), fixture.tenantToken(), version, null, null, null);
        assertEquals(200, cleared.statusCode(), cleared.body());
        JsonNode body = mapper.readTree(cleared.body());
        assertTrue(body.path("availabilityStartAt").isNull());
        assertTrue(body.path("availabilityEndAt").isNull());
        assertTrue(body.path("availabilityZoneId").isNull());
        assertTrue(body.path("preferredAt").isNull());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from property_visit_requests where id = ? and availability_start_at is not null",
                Integer.class, request.getId()));
    }

    @Test
    void initialRequestCreationPersistsStructuredAvailabilityAndStillAcceptsLegacyText() throws Exception {
        Fixture fixture = fixture("creation");
        Map<String, Object> structured = new java.util.LinkedHashMap<>();
        structured.put("preferredVisitTiming", "Saturday afternoon");
        structured.put("availabilityStartAt", start);
        structured.put("availabilityEndAt", end);
        structured.put("availabilityZoneId", "Asia/Kolkata");
        structured.put("preferredAt", preferred);

        HttpResponse<String> created = send("POST",
                "/api/v1/properties/" + fixture.listing().getId() + "/visit-requests",
                fixture.tenantToken(), structured);
        assertEquals(201, created.statusCode(), created.body());
        long requestId = mapper.readTree(created.body()).path("requestId").asLong();

        JsonNode history = read("GET", "/api/v1/tenant/visit-requests", fixture.tenantToken());
        JsonNode row = null;
        for (JsonNode candidate : history.path("requests")) {
            if (candidate.path("requestId").asLong() == requestId) row = candidate;
        }
        assertNotNull(row, created.body());
        assertEquals("Saturday afternoon", row.path("preferredVisitTiming").asText());
        assertEquals("Asia/Kolkata", row.path("availabilityZoneId").asText());
        assertFalse(row.path("availabilityStartAt").isNull());

        User legacyTenant = users.saveAndFlush(user("legacy-" + UUID.randomUUID(), Role.ROLE_TENANT,
                "Legacy Tenant", "9888888888"));
        String legacyToken = jwt.generateToken(legacyTenant.getId(), legacyTenant.getEmail(),
                legacyTenant.getRole().name());
        Map<String, Object> legacy = Map.of("preferredVisitTiming", "Next weekend");
        HttpResponse<String> legacyCreated = send("POST",
                "/api/v1/properties/" + fixture.listing().getId() + "/visit-requests",
                legacyToken, legacy);
        assertEquals(201, legacyCreated.statusCode(), legacyCreated.body());
        long legacyId = mapper.readTree(legacyCreated.body()).path("requestId").asLong();
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from property_visit_requests where id = ? and availability_start_at is not null",
                Integer.class, legacyId));
    }

    @Test
    void concurrentSessionScheduleSerializesBeforeTheTenantUpdate() throws Exception {
        Fixture fixture = fixture("race");
        VisitSession draft = session(fixture, VisitSessionStatus.DRAFT);
        PropertyVisitRequest request = standalone(fixture, draft, VisitRequestStatus.COORDINATING);
        long version = request.getVersion();

        try (Connection schedule = DriverManager.getConnection(verifiedTarget.target().jdbcUrl(),
                verifiedTarget.target().username(), verifiedTarget.target().password())) {
            schedule.setSchema(SCHEMA);
            schedule.setAutoCommit(false);
            try (var lock = schedule.prepareStatement("select id from visit_sessions where id = ? for update")) {
                lock.setLong(1, draft.getId());
                lock.executeQuery().close();
            }
            try (var updateStatus = schedule.prepareStatement(
                    "update visit_sessions set status = 'SCHEDULED', version = version + 1, "
                            + "scheduled_at = now() + interval '2 days', zone_id = 'Asia/Kolkata', "
                            + "duration_snapshot_minutes = 60, "
                            + "reserved_end_at = now() + interval '2 days' + interval '1 hour', "
                            + "representative_user_id = tenant_id, assigned_at = now() where id = ?")) {
                updateStatus.setLong(1, draft.getId());
                updateStatus.executeUpdate();
            }

            AtomicReference<HttpResponse<String>> answer = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread tenantUpdate = new Thread(() -> {
                try {
                    answer.set(update(request.getId(), fixture.tenantToken(), version, start, end, preferred));
                } finally {
                    done.countDown();
                }
            });
            tenantUpdate.setDaemon(true);
            tenantUpdate.start();
            assertFalse(done.await(600, TimeUnit.MILLISECONDS),
                    "the tenant update must wait for the Session root lock");

            schedule.commit();
            assertTrue(done.await(15, TimeUnit.SECONDS));
            tenantUpdate.join(15_000);
            assertEquals(409, answer.get().statusCode(), answer.get().body());
        }
        assertNull(jdbc.queryForObject(
                "select availability_start_at from property_visit_requests where id = ?",
                java.sql.Timestamp.class, request.getId()));
    }

    /** Holds only this verified, loopback-bound test context for one genuine mounted journey. */
    @Test
    @EnabledIfEnvironmentVariable(named = "PATHOME_R1_GENUINE_BROWSER", matches = "true")
    void genuineMountedCrossRoleJourney() throws Exception {
        java.nio.file.Path evidence = java.nio.file.Path.of(System.getenv("PATHOME_R1_EVIDENCE_DIR"));
        Fixture fixture = fixture("genuine");
        User admin = users.saveAndFlush(user("r1-admin", Role.ROLE_ADMIN, "Synthetic R1 Admin", null));
        User oe = users.saveAndFlush(user("r1-oe", Role.ROLE_TENANT, "Synthetic R1 OE", null));
        User nonowner = users.saveAndFlush(user("r1-nonowner", Role.ROLE_TENANT, "Synthetic R1 Nonowner", null));
        User ge = users.saveAndFlush(user("r1-ge", Role.ROLE_GROUND_BOY, "Synthetic R1 GE", null));
        EmployeeProfileRepository employees = application.getBean(EmployeeProfileRepository.class);
        StaffAccessGrantRepository grants = application.getBean(StaffAccessGrantRepository.class);
        for (User staff : java.util.List.of(admin, oe, nonowner, ge)) {
            EmployeeProfile profile = new EmployeeProfile(staff,
                    staff == ge ? "GROUND_BOY" : staff == admin ? "ADMIN" : "OPERATIONS",
                    fixture.city().getDisplayName(), BigDecimal.ZERO);
            profile.setStaffActive(true);
            employees.saveAndFlush(profile);
            if (staff != ge) grants.saveAndFlush(new StaffAccessGrant(staff,
                    staff == admin ? StaffCapability.STAFF_ADMIN : StaffCapability.OPS_COORDINATE,
                    staff == admin ? StaffScopeType.GLOBAL : StaffScopeType.TEAM, null,
                    staff == admin ? null : fixture.team(), Instant.now().minusSeconds(60), null, null,
                    Instant.now(), "R1_SYNTHETIC_FIXTURE", StaffGrantProvisioningSource.ADMIN_API));
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("origin", "http://127.0.0.1:" + httpPort);
        data.put("database", verifiedTarget.target().database());
        data.put("schema", SCHEMA);
        data.put("runId", verifiedTarget.runId());
        data.put("propertyId", fixture.listing().getId());
        for (String key : java.util.List.of("secondaryPropertyId", "thirdPropertyId")) {
            RentalDetails property = new RentalDetails();
            property.setTitle("Synthetic R1 " + key); property.setStatus(ListingStatus.ACTIVE);
            property.setPropertyType(PropertyType.FLAT); property.setAddress("Synthetic R1 fixture address");
            property.setCity(fixture.listing().getCity()); property.setSector(fixture.listing().getSector());
            property.setCanonicalLocalityId(fixture.listing().getCanonicalLocalityId());
            property.setLocationResolution(LocationResolution.CANONICAL);
            property.setLatitude(22.72); property.setLongitude(75.88); property.setBhkCount("2BHK");
            property.setMonthlyRent(BigDecimal.valueOf(20000)); property.setSecurityDeposit(BigDecimal.valueOf(20000));
            data.put(key, listings.saveAndFlush(property).getId());
        }
        data.put("cityId", fixture.city().getId()); data.put("city", fixture.city().getDisplayName());
        data.put("teamId", fixture.team().getId());
        data.put("start", start); data.put("end", end);
        for (Map.Entry<String, User> actor : Map.of("tenant", fixture.tenant(), "admin", admin,
                "oe", oe, "nonowner", nonowner, "ge", ge).entrySet()) {
            User u = actor.getValue();
            data.put(actor.getKey(), Map.of("id", u.getId(), "role", u.getRole().name(),
                    "token", jwt.generateToken(u.getId(), u.getEmail(), u.getRole().name())));
        }
        String rejectedStart = java.time.OffsetDateTime.parse(start).toLocalDate()
                .atTime(12, 0).atZone(ZONE).toOffsetDateTime().toString();
        jdbc.execute("CREATE OR REPLACE FUNCTION r1_reject_tenant_put() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN IF NEW.availability_start_at = TIMESTAMPTZ '"
                + rejectedStart + "' THEN RAISE EXCEPTION 'synthetic R1 persistence failure'; "
                + "END IF; RETURN NEW; END $$");
        jdbc.execute("DROP TRIGGER IF EXISTS r1_reject_tenant_put_trigger ON property_visit_requests");
        jdbc.execute("CREATE TRIGGER r1_reject_tenant_put_trigger BEFORE UPDATE ON property_visit_requests "
                + "FOR EACH ROW EXECUTE FUNCTION r1_reject_tenant_put()");
        java.nio.file.Path credentials = evidence.resolve("genuine-private.json");
        java.nio.file.Files.createFile(credentials,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        java.nio.file.Files.writeString(credentials, mapper.writeValueAsString(data));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(420);
            java.nio.file.Path done = evidence.resolve("genuine-done.json");
            while (!java.nio.file.Files.exists(done) && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(java.nio.file.Files.exists(done), "Genuine browser deadline exceeded");
            JsonNode result = mapper.readTree(java.nio.file.Files.readString(done));
            Map<String, Object> facts = jdbc.queryForMap("select "
                    + "(select count(*) from visit_scheduling_commands) as commands, "
                    + "(select count(*) from visit_scheduling_decisions) as decisions, "
                    + "(select count(*) from visit_notification_outbox) as outbox");
            java.nio.file.Files.writeString(evidence.resolve("genuine-db-summary.json"), mapper.writeValueAsString(facts));
            assertTrue(result.path("ok").asBoolean(), result.path("error").asText());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS r1_reject_tenant_put_trigger ON property_visit_requests");
            jdbc.execute("DROP FUNCTION IF EXISTS r1_reject_tenant_put()");
            java.nio.file.Files.deleteIfExists(credentials);
        }
    }

    @Test
    void multipleLinkedTimingFactsRemainBoundToTheirOwnRequestsAfterRemovalAndReplacement() throws Exception {
        Fixture f = fixture("multi");
        User oe = users.saveAndFlush(user("multi-oe", Role.ROLE_TENANT, "Synthetic Multi OE", null));
        var employees = application.getBean(EmployeeProfileRepository.class);
        var profile = new EmployeeProfile(oe, "OPERATIONS", f.city().getDisplayName(), BigDecimal.ZERO);
        profile.setStaffActive(true); employees.saveAndFlush(profile);
        application.getBean(StaffAccessGrantRepository.class).saveAndFlush(new StaffAccessGrant(oe,
                StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, f.team(), Instant.now().minusSeconds(60),
                null, null, Instant.now(), "R1_MULTI_FIXTURE", StaffGrantProvisioningSource.ADMIN_API));
        var ownership = application.getBean(OperationalOwnershipService.class);
        var ops = application.getBean(VisitOperationsService.class);
        PropertyVisitRequest first = standalone(f, null, VisitRequestStatus.RECEIVED);
        PropertyVisitRequest second = new PropertyVisitRequest();
        second.setTenant(f.tenant()); second.setListing(additionalListing(f, "second"));
        second.setStatus(VisitRequestStatus.RECEIVED); second.setSupportedCity(f.city());
        second.setOperatingTeam(f.team()); second.setOperationalScopeReady(true);
        second.setPreferredVisitTiming("Distinct legacy original timing"); second = requests.saveAndFlush(second);
        assertEquals(200, update(first.getId(), f.tenantToken(), first.getVersion(), start, end, preferred).statusCode());
        first = requests.findById(first.getId()).orElseThrow();
        ownership.claimRequest(oe.getId(), first.getId(), first.getVersion(), null, null, "TEST_CLAIM");
        first = requests.findById(first.getId()).orElseThrow();
        var session = ops.coordinateRequest(oe.getId(), first.getId(),
                new com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand(first.getVersion(), null, null));
        ownership.claimRequest(oe.getId(), second.getId(), second.getVersion(), null, null, "TEST_CLAIM");
        second = requests.findById(second.getId()).orElseThrow();
        session = ops.coordinateRequest(oe.getId(), second.getId(),
                new com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand(second.getVersion(), session.sessionId(), session.version()));
        assertEquals(2, session.linkedRequestTimingFacts().size());
        var a = session.linkedRequestTimingFacts().get(0); var b = session.linkedRequestTimingFacts().get(1);
        assertEquals(first.getId(), a.requestId()); assertEquals("Saturday afternoon", a.preferredVisitTiming());
        assertEquals(Instant.parse(java.time.OffsetDateTime.parse(start).toInstant().toString()), a.availabilityStartAt());
        assertEquals(second.getId(), b.requestId()); assertEquals("Distinct legacy original timing", b.preferredVisitTiming());
        assertNull(b.availabilityStartAt()); assertNull(b.availabilityZoneId());
        assertNotEquals(a.version(), b.version());
        final Long secondId = second.getId();
        var item = session.items().stream().filter(x -> secondId.equals(x.sourceRequestId())).findFirst().orElseThrow();
        session = ops.setAvailability(oe.getId(), session.sessionId(), item.itemId(),
                new com.indore.pathome.spaces.dto.VisitSessionAvailabilityCommand(session.version(), VisitSessionItemConfirmationStatus.UNAVAILABLE));
        session = ops.removeItem(oe.getId(), session.sessionId(), item.itemId(),
                new com.indore.pathome.spaces.dto.RemoveVisitSessionItemCommand(session.version(), "Synthetic unavailable property"));
        session = ops.addNearbyProperty(oe.getId(), session.sessionId(),
                new com.indore.pathome.spaces.dto.AddVisitSessionItemCommand(session.version(),
                        additionalListing(f, "replacement").getId(), second.getId(), VisitSessionItemOrigin.OE_ADDED));
        var read = ops.getOperationsSession(oe.getId(), session.sessionId());
        assertEquals(2, read.linkedRequestTimingFacts().size());
        for (var fact : read.linkedRequestTimingFacts()) {
            assertEquals(requests.findById(fact.requestId()).orElseThrow().getVersion(), fact.version());
            if (fact.requestId().equals(secondId)) {
                assertEquals("Distinct legacy original timing", fact.preferredVisitTiming()); assertNull(fact.availabilityStartAt());
            } else assertEquals("Saturday afternoon", fact.preferredVisitTiming());
        }
    }

    @Test
    void preciseNonHourAndFoldInstantsPersistAndInvalidPermutationsLeaveNoChanges() throws Exception {
        Fixture f = fixture("precision");
        PropertyVisitRequest request = standalone(f, null, VisitRequestStatus.RECEIVED);
        for (String[] input : new String[][] {
                {"2027-02-14T23:30:00.123456789+05:45", "2027-02-15T02:30:00.654321+05:45", "Asia/Kathmandu"},
                {"2026-11-01T01:30:00.123456-04:00", "2026-11-01T03:30:00-05:00", "America/New_York"},
                {"2026-11-01T01:30:00.123456-05:00", "2026-11-01T03:30:00-05:00", "America/New_York"}}) {
            request = requests.findById(request.getId()).orElseThrow();
            var response = send("PUT", "/api/v1/tenant/visit-requests/" + request.getId() + "/availability", f.tenantToken(),
                    Map.of("expectedRequestVersion", request.getVersion(), "availabilityStartAt", input[0],
                            "availabilityEndAt", input[1], "availabilityZoneId", input[2]));
            assertEquals(200, response.statusCode(), response.body());
            PropertyVisitRequest stored = requests.findById(request.getId()).orElseThrow();
            Instant submitted = java.time.OffsetDateTime.parse(input[0]).toInstant();
            Instant rounded = submitted.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            assertEquals(rounded, stored.getAvailabilityStartAt()); assertEquals(input[2], stored.getAvailabilityZoneId());
        }
        request = requests.findById(request.getId()).orElseThrow();
        long version = request.getVersion(); Instant saved = request.getAvailabilityStartAt();
        for (String[] input : new String[][] {
                {"2027-03-14T02:30:00-05:00", "2027-03-14T04:30:00-04:00", "America/New_York"},
                {"2027-02-14T10:00:00+05:30", "2027-02-14T09:00:00+05:30", "Asia/Kolkata"},
                {"2027-02-14T10:00:00+05:30", "2027-02-14T11:00:00+05:30", "Invalid/Zone"}}) {
            var response = send("PUT", "/api/v1/tenant/visit-requests/" + request.getId() + "/availability", f.tenantToken(),
                    Map.of("expectedRequestVersion", version, "availabilityStartAt", input[0],
                            "availabilityEndAt", input[1], "availabilityZoneId", input[2]));
            assertEquals(400, response.statusCode(), response.body());
            var unchanged = requests.findById(request.getId()).orElseThrow();
            assertEquals(version, unchanged.getVersion()); assertEquals(saved, unchanged.getAvailabilityStartAt());
        }
    }

    @Test
    void additiveLessorCapabilityPersistedRoleAndConcurrentChangedWritesAreAuthoritative() throws Exception {
        Fixture f = fixture("capability");
        LessorProfile lessor = new LessorProfile(); lessor.setLinkedUserId(f.tenant().getId());
        lessor.setDisplayName("Synthetic additive lessor"); lessor.setSourceType(LessorSourceType.SELF_SERVICE);
        application.getBean(LessorProfileRepository.class).saveAndFlush(lessor);
        PropertyVisitRequest request = standalone(f, null, VisitRequestStatus.RECEIVED);
        final long requestId = request.getId(); final long expected = request.getVersion();
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicReference<HttpResponse<String>> a = new AtomicReference<>(), b = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try { startTogether.await(); a.set(update(requestId, f.tenantToken(), expected, start, end, null)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        String alternateStart = start.replace("10:00", "11:00");
        Thread second = new Thread(() -> {
            try { startTogether.await(); b.set(update(requestId, f.tenantToken(), expected, alternateStart, end, null)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        first.setDaemon(true); second.setDaemon(true); first.start(); second.start(); startTogether.countDown();
        first.join(10000); second.join(10000); assertNotNull(a.get()); assertNotNull(b.get());
        assertEquals(java.util.Set.of(200, 409), java.util.Set.of(a.get().statusCode(), b.get().statusCode()));
        request = requests.findById(requestId).orElseThrow(); assertEquals(expected + 1, request.getVersion());
        assertTrue(java.util.Set.of(java.time.OffsetDateTime.parse(start).toInstant(),
                java.time.OffsetDateTime.parse(alternateStart).toInstant()).contains(request.getAvailabilityStartAt()));
        assertEquals(Role.ROLE_TENANT, users.findById(f.tenant().getId()).orElseThrow().getRole());
        User persistedAdmin = users.saveAndFlush(user("persisted-admin", Role.ROLE_ADMIN, "Synthetic wrong persisted capability", null));
        PropertyVisitRequest forbidden = new PropertyVisitRequest(); forbidden.setTenant(persistedAdmin);
        forbidden.setListing(additionalListing(f, "forbidden")); forbidden.setStatus(VisitRequestStatus.RECEIVED);
        forbidden.setSupportedCity(f.city()); forbidden.setOperatingTeam(f.team()); forbidden.setOperationalScopeReady(true);
        forbidden.setPreferredVisitTiming("Synthetic role negative"); forbidden = requests.saveAndFlush(forbidden);
        String misleadingTenantJwt = jwt.generateToken(persistedAdmin.getId(), persistedAdmin.getEmail(), Role.ROLE_TENANT.name());
        assertEquals(403, update(forbidden.getId(), misleadingTenantJwt, forbidden.getVersion(), start, end, null).statusCode());
        assertNull(requests.findById(forbidden.getId()).orElseThrow().getAvailabilityStartAt());
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_scheduling_commands", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_notification_outbox", Integer.class));
    }

    private RentalDetails additionalListing(Fixture f, String name) {
        RentalDetails listing = new RentalDetails(); listing.setTitle("Synthetic R1 " + name);
        listing.setStatus(ListingStatus.ACTIVE); listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Synthetic fixture address"); listing.setSector(f.listing().getSector());
        listing.setCity(f.listing().getCity()); listing.setCanonicalLocalityId(f.listing().getCanonicalLocalityId());
        listing.setLocationResolution(LocationResolution.CANONICAL); listing.setLatitude(22.72); listing.setLongitude(75.88);
        listing.setBhkCount("2BHK"); listing.setMonthlyRent(BigDecimal.valueOf(20000)); listing.setSecurityDeposit(BigDecimal.valueOf(20000));
        return listings.saveAndFlush(listing);
    }

    private Fixture fixture(String label) {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(
                ("city-" + label + "-" + UUID.randomUUID().toString().substring(0, 6)).toLowerCase(),
                "Availability City " + label, true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city,
                ("team-" + label + "-" + UUID.randomUUID().toString().substring(0, 6)).toLowerCase(),
                "Availability Team " + label, true));
        Locality locality = new Locality(city.getDisplayName(), label + " sector " + UUID.randomUUID(),
                null, null, null);
        locality.setSupportedCity(city);
        locality = localities.saveAndFlush(locality);
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Availability listing " + UUID.randomUUID());
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Private address " + label);
        listing.setSector(locality.getSectorName());
        listing.setCity(locality.getCity());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setLocationResolution(LocationResolution.CANONICAL);
        listing.setLatitude(22.72);
        listing.setLongitude(75.88);
        listing.setBhkCount("2BHK");
        listing.setMonthlyRent(BigDecimal.valueOf(20000));
        listing.setSecurityDeposit(BigDecimal.valueOf(20000));
        listing = listings.saveAndFlush(listing);
        User tenant = users.saveAndFlush(user("tenant-" + label + "-" + UUID.randomUUID(),
                Role.ROLE_TENANT, "Asha Verma", "9876543210"));
        String token = jwt.generateToken(tenant.getId(), tenant.getEmail(), tenant.getRole().name());
        return new Fixture(city, team, listing, tenant, token);
    }

    private PropertyVisitRequest standalone(Fixture fixture, VisitSession session, VisitRequestStatus status) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(fixture.tenant());
        request.setListing(fixture.listing());
        request.setStatus(status);
        request.setSession(session);
        request.setSupportedCity(fixture.city());
        request.setOperatingTeam(fixture.team());
        request.setOperationalScopeReady(true);
        request.setPreferredVisitTiming("Saturday afternoon");
        return requests.saveAndFlush(request);
    }

    private VisitSession session(Fixture fixture, VisitSessionStatus status) {
        VisitSession session = new VisitSession();
        session.setTenant(fixture.tenant());
        session.setCity(fixture.city().getDisplayName());
        session.setSupportedCity(fixture.city());
        session.setOperatingTeam(fixture.team());
        if (status == VisitSessionStatus.SCHEDULED || status == VisitSessionStatus.STARTED) {
            Instant scheduledAt = Instant.now().plusSeconds(36_000);
            session.setScheduledAt(scheduledAt);
            session.setZoneId("Asia/Kolkata");
            session.setDurationSnapshotMinutes(60);
            session.setReservedEndAt(scheduledAt.plusSeconds(3600));
            session.setRepresentative(fixture.tenant());
            session.setAssignedAt(Instant.now());
            if (status == VisitSessionStatus.STARTED) {
                session.setStartedAt(Instant.now());
                session.setExecutionDurationSnapshotMinutes(60);
                session.setExpectedEndAt(scheduledAt.plusSeconds(3600));
            }
        }
        session.setStatus(status);
        session.setOperationalScopeReady(true);
        return sessions.saveAndFlush(session);
    }

    private void link(PropertyVisitRequest request, VisitSession session, VisitRequestStatus status) {
        request.setSession(session);
        request.setStatus(status);
        requests.saveAndFlush(request);
    }

    private User user(String prefix, Role role, String name, String phone) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(name);
        user.setPhoneNumber(phone);
        user.setRole(role);
        return user;
    }

    private HttpResponse<String> update(long requestId, String token, long version,
                                         String availabilityStartAt, String availabilityEndAt,
                                         String preferredAt) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("expectedRequestVersion", version);
        body.put("availabilityStartAt", availabilityStartAt);
        body.put("availabilityEndAt", availabilityEndAt);
        body.put("availabilityZoneId", availabilityStartAt == null ? null : "Asia/Kolkata");
        body.put("preferredAt", preferredAt);
        return send("PUT", "/api/v1/tenant/visit-requests/" + requestId + "/availability", token, body);
    }

    private JsonNode read(String method, String path, String token) throws Exception {
        HttpResponse<String> response = send(method, path, token, null);
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + path));
            if (token != null) request.header("Authorization", "Bearer " + token);
            HttpRequest httpRequest = request.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json(body)))
                    .build();
            return HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Fixture(SupportedCity city, OperatingTeam team, RentalDetails listing,
                           User tenant, String tenantToken) {}
}
