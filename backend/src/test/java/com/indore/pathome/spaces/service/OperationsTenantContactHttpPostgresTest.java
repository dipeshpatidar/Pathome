package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget;
import com.indore.pathome.spaces.support.DisposablePostgresTarget;
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
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R1-OE-1 real HTTP/PostgreSQL tenant-contact seam.
 *
 * <p>Only the current owner can reveal, contact comes exclusively from the root tenant relationship,
 * missing contact fails closed, successful disclosure is audited in the same transaction, and a
 * concurrent ownership transfer is serialized behind the reveal's root lock.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "server.address=127.0.0.1",
        "app.jwt.secret=tenant-contact-http-test-jwt-secret-32-bytes-minimum",
        "pathome.visit.otp.hmac-secret=tenant-contact-http-test-otp-secret-32-bytes-minimum",
        "cloudinary.api-key=tenant-contact-test-key",
        "cloudinary.api-secret=tenant-contact-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class OperationsTenantContactHttpPostgresTest {
    private static final String SCHEMA = "tenant_contact_http_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static DisposablePostgresTarget.Verified verifiedTarget;
    private static boolean schemaCreated;
    private static final String TENANT_NAME = "Asha Verma";
    private static final String TENANT_PHONE = "9876543210";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
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
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private VisitSessionRepository sessions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OperationalOwnershipService ownership;
    @Autowired private org.springframework.context.ApplicationContext application;
    @LocalServerPort private int httpPort;

    @AfterEach
    void resetBarriers() {
        AuditBarrier.reset();
        AuditFailureGuard.reset();
    }

    @Test
    void requestContactIsOwnerScopedPrivacyBoundedAndAudited() throws Exception {
        SupportedCity cityA = cities.saveAndFlush(new SupportedCity(code("a"), "Contact City A", true));
        SupportedCity cityB = cities.saveAndFlush(new SupportedCity(code("b"), "Contact City B", true));
        OperatingTeam teamA1 = teams.saveAndFlush(new OperatingTeam(cityA, code("a1"), "Contact Team A1", true));
        OperatingTeam teamA2 = teams.saveAndFlush(new OperatingTeam(cityA, code("a2"), "Contact Team A2", true));
        OperatingTeam teamB1 = teams.saveAndFlush(new OperatingTeam(cityB, code("b1"), "Contact Team B1", true));
        Locality localityA1 = locality(cityA, "oc-a1");
        Locality localityA2 = locality(cityA, "oc-a2");
        Locality localityB1 = locality(cityB, "oc-b1");

        Actor tenant = actor("tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
        Actor owner = actor("owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        Actor teammate = actor("teammate", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        Actor noScope = actor("no-scope", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        teamGrant(owner, teamA1);
        teamGrant(teammate, teamA1);

        Listing listingA = listing(localityA1, "owned");
        PropertyVisitRequest owned = standaloneRequest(tenant.user(), cityA, teamA1, listingA, owner.user());

        HttpResponse<String> response = send("GET", requestPath(owned.getId()), owner.token());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode body = mapper.readTree(response.body());
        assertEquals(Set.of("requestId", "name", "mobile", "status"), fieldNames(body));
        assertEquals(owned.getId(), body.path("requestId").asLong());
        assertEquals(TENANT_NAME, body.path("name").asText());
        assertEquals("+91 " + TENANT_PHONE, body.path("mobile").asText());
        assertEquals("CONTACT_AVAILABLE", body.path("status").asText());
        assertEquals(1, auditCount("PROPERTY_VISIT_REQUEST", owned.getId()));
        JsonNode requestDetail = read("GET", "/api/v1/operations/visit-requests/" + owned.getId(), owner.token());
        assertEquals("Saturday afternoon", requestDetail.path("preferredVisitTiming").asText());

        assertEquals(404, send("GET", requestPath(owned.getId()), teammate.token()).statusCode());
        PropertyVisitRequest claimable = standaloneRequest(tenant.user(), cityA, teamA1,
                listing(localityA2, "claimable"), null);
        assertEquals(404, send("GET", requestPath(claimable.getId()), owner.token()).statusCode());
        PropertyVisitRequest otherTeam = standaloneRequest(tenant.user(), cityA, teamA2,
                listing(localityA2, "other-team"), owner.user());
        assertEquals(404, send("GET", requestPath(otherTeam.getId()), owner.token()).statusCode());
        PropertyVisitRequest otherCity = standaloneRequest(tenant.user(), cityB, teamB1,
                listing(localityB1, "other-city"), owner.user());
        assertEquals(404, send("GET", requestPath(otherCity.getId()), owner.token()).statusCode());

        VisitSession session = draftSession(tenant.user(), cityA, teamA1, owner.user());
        PropertyVisitRequest linked = linkedRequest(tenant.user(), cityA, teamA1,
                listing(localityA1, "linked"), session, owner.user());
        assertEquals(404, send("GET", requestPath(linked.getId()), owner.token()).statusCode());

        HttpResponse<String> noAuthority = send("GET", requestPath(owned.getId()), noScope.token());
        assertEquals(403, noAuthority.statusCode());
        assertEquals(403, send("GET", requestPath(owned.getId()), tenant.token()).statusCode());
        HttpResponse<String> unauthenticated = send("GET", requestPath(owned.getId()), null);
        assertEquals(401, unauthenticated.statusCode());
        assertTrue(unauthenticated.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                "the security filter's 401 must also be no-store");

        for (HttpResponse<String> error : List.of(
                send("GET", requestPath(owned.getId()), teammate.token()),
                send("GET", requestPath(999_999_999L), owner.token()),
                send("GET", "/api/v1/operations/visit-requests/not-an-id/tenant-contact", owner.token()),
                noAuthority, unauthenticated)) {
            assertFalse(error.body().contains(TENANT_PHONE), "errors must never leak tenant digits");
            assertFalse(error.body().contains(TENANT_NAME), "errors must never leak the tenant name");
            assertTrue(error.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                    "every tenant-contact error response must be no-store");
        }
    }

    @Test
    void sessionContactHonoursRevealableStatusesAndOwnership() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("s"), "Session City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("s-team"), "Session Team", true));
        Locality locality = locality(city, "s");
        Actor tenant = actor("s-tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
        Actor owner = actor("s-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        Actor teammate = actor("s-teammate", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        Actor ge = actor("s-ge", Role.ROLE_GROUND_BOY, null, false, null, null);
        teamGrant(owner, team);
        teamGrant(teammate, team);

        List<VisitSessionStatus> revealable = List.of(
                VisitSessionStatus.DRAFT, VisitSessionStatus.SCHEDULED,
                VisitSessionStatus.STARTED, VisitSessionStatus.INTERRUPTED);
        for (int index = 0; index < revealable.size(); index++) {
            VisitSessionStatus status = revealable.get(index);
            VisitSession session = sessionInStatus(status, tenant.user(), city, team, owner.user(), ge.user(),
                    8 + index * 2L);
            HttpResponse<String> response = send("GET", sessionPath(session.getId()), owner.token());
            assertEquals(200, response.statusCode(), status.name() + " " + response.body());
            JsonNode body = mapper.readTree(response.body());
            assertEquals(Set.of("sessionId", "name", "mobile", "status"), fieldNames(body));
            assertEquals(session.getId(), body.path("sessionId").asLong());
            assertEquals(TENANT_NAME, body.path("name").asText());
            assertEquals(1, auditCount("VISIT_SESSION", session.getId()), status.name());
            assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        }

        for (VisitSessionStatus status : List.of(
                VisitSessionStatus.COMPLETED, VisitSessionStatus.EXPIRED, VisitSessionStatus.CANCELLED,
                VisitSessionStatus.NO_SHOW, VisitSessionStatus.PROVISIONAL_NO_SHOW,
                VisitSessionStatus.REPAIR_REQUIRED)) {
            VisitSession session = sessionInStatus(status, tenant.user(), city, team, owner.user(), ge.user(), 9);
            assertEquals(404, send("GET", sessionPath(session.getId()), owner.token()).statusCode(),
                    status.name());
        }

        VisitSession owned = draftSession(tenant.user(), city, team, owner.user());
        PropertyVisitRequest linkedToOwned = linkedRequest(tenant.user(), city, team,
                listing(locality, "owned-linked"), owned, owner.user());
        linkedToOwned.setPreferredVisitTiming("Sunday morning");
        linkedToOwned = requests.saveAndFlush(linkedToOwned);
        assertEquals(404, send("GET", sessionPath(owned.getId()), teammate.token()).statusCode());
        assertEquals(401, send("GET", sessionPath(owned.getId()), null).statusCode());
        assertEquals(400, send("GET", "/api/v1/operations/visit-sessions/-1/tenant-contact",
                owner.token()).statusCode());
        JsonNode sessionDetail = read("GET", "/api/v1/operations/visit-sessions/" + owned.getId(),
                owner.token());
        assertEquals(1, sessionDetail.path("linkedRequestTimingFacts").size());
        assertEquals(linkedToOwned.getId(),
                sessionDetail.path("linkedRequestTimingFacts").get(0).path("requestId").asLong());
        assertEquals("Sunday morning",
                sessionDetail.path("linkedRequestTimingFacts").get(0).path("preferredVisitTiming").asText());
        assertEquals(linkedToOwned.getVersion(),
                sessionDetail.path("linkedRequestTimingFacts").get(0).path("version").asLong());
    }

    @Test
    void missingOrInvalidContactIsUnavailableWithNoFallbackAndNoAudit() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("u"), "Unavailable City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("u-team"), "Unavailable Team", true));
        Locality locality = locality(city, "u");
        Actor owner = actor("u-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        teamGrant(owner, team);

        for (Actor tenant : List.of(
                actor("u-no-phone", Role.ROLE_TENANT, null, false, "No Phone", null),
                actor("u-invalid-phone", Role.ROLE_TENANT, null, false, "Bad Phone", "12345"),
                actor("u-no-name", Role.ROLE_TENANT, null, false, null, TENANT_PHONE))) {
            PropertyVisitRequest request = standaloneRequest(tenant.user(), city, team,
                    listing(locality, "unavailable-" + tenant.user().getId()), owner.user());
            JsonNode body = read("GET", requestPath(request.getId()), owner.token());
            assertEquals("CONTACT_UNAVAILABLE", body.path("status").asText());
            assertTrue(body.path("name").isNull());
            assertTrue(body.path("mobile").isNull());
            assertEquals(0, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));
        }
    }

    @Test
    void auditFailureWithholdsContactAndLeavesNoAuditOrContactMutation() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("f"), "Failure City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("f-team"), "Failure Team", true));
        Locality locality = locality(city, "f");
        Actor tenant = actor("f-tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
        Actor owner = actor("f-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        teamGrant(owner, team);
        PropertyVisitRequest request = standaloneRequest(tenant.user(), city, team,
                listing(locality, "failure"), owner.user());

        AuditFailureGuard.failNextRecord();
        HttpResponse<String> response = send("GET", requestPath(request.getId()), owner.token());

        assertTrue(response.statusCode() >= 500, response.body());
        assertFalse(response.body().contains(TENANT_PHONE));
        assertEquals(0, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));
    }

    @Test
    void commitTimeAuditFailureWithholdsPiiAndRollsBackTheAuditInsert() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("commit"), "Commit Test City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("commit-team"), "Commit Test Team", true));
        Actor tenant = actor("commit-tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
        Actor owner = actor("commit-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        teamGrant(owner, team);
        PropertyVisitRequest request = standaloneRequest(tenant.user(), city, team,
                listing(locality(city, "commit"), "commit"), owner.user());
        assertEquals(200, send("GET", requestPath(request.getId()), owner.token()).statusCode());
        int before = auditCount("PROPERTY_VISIT_REQUEST", request.getId());
        jdbc.execute("CREATE FUNCTION r1_reject_audit_commit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.action_code = 'TENANT_CONTACT_REVEALED' AND NEW.target_id = '"
                + request.getId() + "' THEN RAISE EXCEPTION 'synthetic R1 commit failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER r1_audit_commit_failure AFTER INSERT ON operational_audit_events "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION r1_reject_audit_commit()");
        try {
            HttpResponse<String> response = send("GET", requestPath(request.getId()), owner.token());
            assertTrue(response.statusCode() >= 500, response.body());
            assertFalse(response.body().contains(TENANT_NAME));
            assertFalse(response.body().contains(TENANT_PHONE));
            assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            assertEquals(before, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));
        } finally {
            jdbc.execute("DROP TRIGGER r1_audit_commit_failure ON operational_audit_events");
            jdbc.execute("DROP FUNCTION r1_reject_audit_commit()");
        }
        assertEquals(200, send("GET", requestPath(request.getId()), owner.token()).statusCode());
        assertEquals(before + 1, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));
    }

    @Test
    void contactRevealSerializesWithConcurrentOwnershipTransfer() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("r"), "Race City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("r-team"), "Race Team", true));
        Locality locality = locality(city, "r");
        Actor tenant = actor("r-tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
        Actor owner = actor("r-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        Actor successor = actor("r-successor", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        teamGrant(owner, team);
        teamGrant(successor, team);
        grants.saveAndFlush(new StaffAccessGrant(owner.user(), StaffCapability.OPS_SUPERVISE,
                StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(60), null, null,
                Instant.now(), "TEST_TENANT_CONTACT", StaffGrantProvisioningSource.ADMIN_API));
        PropertyVisitRequest request = standaloneRequest(tenant.user(), city, team,
                listing(locality, "race"), owner.user());

        AuditBarrier.arm();
        AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
        Thread revealThread = new Thread(() -> reveal.set(send("GET", requestPath(request.getId()), owner.token())));
        revealThread.setDaemon(true);
        revealThread.start();
        assertTrue(AuditBarrier.awaitEntered(10_000), "the reveal must reach its locked audit section");

        AtomicReference<Throwable> transferFailure = new AtomicReference<>();
        CountDownLatch transferDone = new CountDownLatch(1);
        Thread transfer = new Thread(() -> {
            try {
                ownership.assignRequestCoordinator(owner.user().getId(), request.getId(),
                        request.getVersion(), null, null, successor.user().getId(), "TEST_TRANSFER");
            } catch (Throwable failure) {
                transferFailure.set(failure);
            } finally {
                transferDone.countDown();
            }
        });
        transfer.setDaemon(true);
        transfer.start();
        long blockingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean observedBlocker = false;
        while (System.nanoTime() < blockingDeadline && !observedBlocker) {
            observedBlocker = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists (select 1 from pg_stat_activity where datname=current_database() "
                            + "and pid<>pg_backend_pid() and cardinality(pg_blocking_pids(pid))>0 "
                            + "and query like '%property_visit_requests%')", Boolean.class));
            if (!observedBlocker) Thread.sleep(20);
        }
        assertTrue(observedBlocker, "PostgreSQL must report a blocked production ownership transaction");

        AuditBarrier.release();
        revealThread.join(15_000);
        assertTrue(transferDone.await(15, TimeUnit.SECONDS));
        assertNull(transferFailure.get());
        assertEquals(200, reveal.get().statusCode(), reveal.get().body());
        assertEquals(TENANT_NAME, mapper.readTree(reveal.get().body()).path("name").asText());

        assertEquals(404, send("GET", requestPath(request.getId()), owner.token()).statusCode());
        assertEquals(200, send("GET", requestPath(request.getId()), successor.token()).statusCode());
    }

    @Test
    void realTransferRevocationAndStaffDeactivationSerializeInBothOrders() throws Exception {
        var staff = application.getBean(StaffAdministrationService.class);
        for (String kind : List.of("TRANSFER", "REVOKE", "DEACTIVATE")) {
            for (boolean revealFirst : List.of(true, false)) {
                SupportedCity city = cities.saveAndFlush(new SupportedCity(code("security"), "Synthetic Security City", true));
                OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("security-team"), "Security Team", true));
                Actor tenant = actor("security-tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
                Actor owner = actor("security-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
                Actor successor = actor("security-next", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
                Actor admin = actor("security-admin", Role.ROLE_ADMIN, "ADMIN", true, null, null);
                teamGrant(owner, team); teamGrant(successor, team);
                grants.saveAndFlush(new StaffAccessGrant(admin.user(), StaffCapability.STAFF_ADMIN,
                        StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null, null,
                        Instant.now(), "R1_SECURITY_FIXTURE", StaffGrantProvisioningSource.ADMIN_API));
                grants.saveAndFlush(new StaffAccessGrant(admin.user(), StaffCapability.OPS_SUPERVISE,
                        StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(60), null, null,
                        Instant.now(), "R1_SECURITY_FIXTURE", StaffGrantProvisioningSource.ADMIN_API));
                var grant = grants.findEffectiveForUser(owner.user().getId()).stream().findFirst().orElseThrow();
                var profile = employees.findByUserId(owner.user().getId()).orElseThrow();
                PropertyVisitRequest request = standaloneRequest(tenant.user(), city, team,
                        listing(locality(city, "security"), "security"), owner.user());
                AtomicReference<Throwable> mutationError = new AtomicReference<>();
                AtomicReference<HttpResponse<String>> answer = new AtomicReference<>();
                Thread mutate = new Thread(() -> {
                    try {
                        switch (kind) {
                            case "TRANSFER" -> ownership.assignRequestCoordinator(admin.user().getId(), request.getId(),
                                    request.getVersion(), null, null, successor.user().getId(), "R1_TEST_TRANSFER");
                            case "REVOKE" -> staff.revokeGrant(admin.user().getId(), grant.getId(), "R1_TEST_REVOKE");
                            default -> staff.deactivateStaff(admin.user().getId(), owner.user().getId(),
                                    new com.indore.pathome.spaces.dto.StaffStateCommand(profile.getVersion(), "R1_TEST_DEACTIVATE"));
                        }
                    } catch (Throwable error) { mutationError.set(error); }
                });
                Thread reveal = new Thread(() -> answer.set(send("GET", requestPath(request.getId()), owner.token())));
                mutate.setDaemon(true); reveal.setDaemon(true); AuditBarrier.arm();
                try {
                    (revealFirst ? reveal : mutate).start();
                    assertTrue(AuditBarrier.awaitEntered(5000), kind + " first transaction must reach the audit barrier");
                    (revealFirst ? mutate : reveal).start();
                    assertTrue(observeAnyPostgresBlocker(), kind + " must have an actual PostgreSQL blocker");
                    System.out.println("R1 actual contact blocker: " + kind + " revealFirst=" + revealFirst);
                } finally { AuditBarrier.release(); }
                reveal.join(10000); mutate.join(10000);
                assertFalse(reveal.isAlive()); assertFalse(mutate.isAlive()); assertNull(mutationError.get());
                assertNotNull(answer.get());
                if (revealFirst) assertEquals(200, answer.get().statusCode(), answer.get().body());
                else {
                    assertTrue(Set.of(403, 404).contains(answer.get().statusCode()), answer.get().body());
                    assertFalse(answer.get().body().contains(TENANT_NAME));
                    assertFalse(answer.get().body().contains(TENANT_PHONE));
                }
                assertEquals(kind.equals("TRANSFER") ? 404 : 403, send("GET", requestPath(request.getId()), owner.token()).statusCode());
                if (kind.equals("TRANSFER")) assertEquals(200, send("GET", requestPath(request.getId()), successor.token()).statusCode());
                assertEquals(revealFirst ? 1 : 0, auditCount("PROPERTY_VISIT_REQUEST", request.getId())
                        - (kind.equals("TRANSFER") ? 1 : 0), "denied reveal must not create disclosure audit");
                AuditBarrier.reset();
            }
        }
    }

    @Test
    void expiredGrantBeforeAdmissionIsDeniedWithoutPiiOrAuditAndUnexpiredControlSucceeds() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("expiry"), "Expiry City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("expiry-team"), "Expiry Team", true));
        Actor tenant = actor("expiry-tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
        Actor owner = actor("expiry-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        grants.saveAndFlush(new StaffAccessGrant(owner.user(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600), null, Instant.now().minusSeconds(7200),
                "R1_EXPIRED_GRANT", StaffGrantProvisioningSource.ADMIN_API));
        PropertyVisitRequest request = standaloneRequest(tenant.user(), city, team,
                listing(locality(city, "expiry"), "expiry"), owner.user());

        HttpResponse<String> denied = send("GET", requestPath(request.getId()), owner.token());
        assertEquals(403, denied.statusCode(), denied.body());
        assertFalse(denied.body().contains(TENANT_NAME));
        assertFalse(denied.body().contains(TENANT_PHONE));
        assertEquals(0, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));

        teamGrant(owner, team);
        HttpResponse<String> allowed = send("GET", requestPath(request.getId()), owner.token());
        assertEquals(200, allowed.statusCode(), allowed.body());
        assertEquals(TENANT_NAME, mapper.readTree(allowed.body()).path("name").asText());
        assertEquals(1, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));
    }

    @Test
    void grantExpiryWhileBlockedBeforeAdmissionDeniesWithoutPiiOrAudit() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("lockexp"), "Lock Expiry City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("lockexp-team"),
                "Lock Expiry Team", true));
        Actor tenant = actor("lockexp-tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
        Actor owner = actor("lockexp-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        Instant expiresAt = Instant.now().plusSeconds(5);
        grants.saveAndFlush(new StaffAccessGrant(owner.user(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(60), expiresAt, null,
                Instant.now().minusSeconds(60), "R1_EXPIRY_LOCK_RACE",
                StaffGrantProvisioningSource.ADMIN_API));
        PropertyVisitRequest request = standaloneRequest(tenant.user(), city, team,
                listing(locality(city, "lockexp"), "lockexp"), owner.user());
        assertFalse(grants.findEffectiveForUser(owner.user().getId()).isEmpty(),
                "the grant must be genuinely effective before the lock wait begins");
        String expiresBefore = jdbc.queryForObject(
                "select expires_at::text from staff_access_grants where user_id = ? "
                        + "and capability = 'OPS_COORDINATE' order by id desc limit 1",
                String.class, owner.user().getId());

        AtomicReference<HttpResponse<String>> reveal = new AtomicReference<>();
        Thread revealThread = new Thread(() -> reveal.set(send("GET", requestPath(request.getId()), owner.token())));
        revealThread.setDaemon(true);
        Long blockerPid;
        try (Connection blocker = DriverManager.getConnection(verifiedTarget.target().jdbcUrl(),
                verifiedTarget.target().username(), verifiedTarget.target().password())) {
            blocker.setSchema(SCHEMA);
            blocker.setAutoCommit(false);
            try (PreparedStatement pid = blocker.prepareStatement("select pg_backend_pid()");
                 var rows = pid.executeQuery()) {
                rows.next();
                blockerPid = rows.getLong(1);
            }
            try (PreparedStatement lock = blocker.prepareStatement(
                    "select id from property_visit_requests where id = ? for update")) {
                lock.setLong(1, request.getId());
                lock.executeQuery().close();
            }
            revealThread.start();
            Long revealPid = null;
            long blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < blockedDeadline && revealPid == null) {
                revealPid = jdbc.query("select pid from pg_stat_activity where datname = current_database() "
                                + "and pid <> pg_backend_pid() and wait_event_type = 'Lock' "
                                + "and ? = any(pg_blocking_pids(pid)) "
                                + "and query like '%property_visit_requests%' limit 1",
                        rows -> rows.next() ? rows.getLong(1) : null, blockerPid);
                if (revealPid == null) Thread.sleep(25);
            }
            assertNotNull(revealPid, "the reveal must be demonstrably blocked on the canonical Request lock");
            String dbTimeBefore = jdbc.queryForObject("select clock_timestamp()::text", String.class);
            long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean expiredWhileBlocked = false;
            while (System.nanoTime() < expiryDeadline && !expiredWhileBlocked) {
                expiredWhileBlocked = Boolean.TRUE.equals(jdbc.queryForObject(
                        "select clock_timestamp() >= ?", Boolean.class, Timestamp.from(expiresAt)));
                if (!expiredWhileBlocked) Thread.sleep(50);
            }
            String dbTimeAfter = jdbc.queryForObject("select clock_timestamp()::text", String.class);
            assertTrue(expiredWhileBlocked,
                    "the fixed grant expiry must pass while the reveal is blocked: before=" + dbTimeBefore
                            + " after=" + dbTimeAfter + " expires=" + expiresAt);
            assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists (select 1 from pg_stat_activity where pid = ? and wait_event_type = 'Lock')",
                    Boolean.class, revealPid)), "the same reveal backend must still be blocked at expiry");
            System.out.println("R1 expiry-lock evidence revealPid=" + revealPid + " blockerPid=" + blockerPid
                    + " dbBefore=" + dbTimeBefore + " dbAfter=" + dbTimeAfter + " expiresAt=" + expiresAt);
            blocker.rollback();
        }
        revealThread.join(15_000);
        assertFalse(revealThread.isAlive());
        assertNotNull(reveal.get());
        assertTrue(java.util.Set.of(403, 404).contains(reveal.get().statusCode()), reveal.get().body());
        assertFalse(reveal.get().body().contains(TENANT_NAME));
        assertFalse(reveal.get().body().contains(TENANT_PHONE));
        assertEquals(0, auditCount("PROPERTY_VISIT_REQUEST", request.getId()),
                "final fresh admission must create no unauthorized disclosure audit");
        assertEquals(expiresBefore, jdbc.queryForObject(
                "select expires_at::text from staff_access_grants where user_id = ? "
                        + "and capability = 'OPS_COORDINATE' order by id desc limit 1",
                String.class, owner.user().getId()), "the test must not rewrite the grant expiry");
    }

    @Test
    void admittedRevealHeldPastGrantExpiryKeepsFrozenAdmissionSemantics() throws Exception {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(code("admitted"), "Admitted City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("admitted-team"),
                "Admitted Team", true));
        Actor tenant = actor("admitted-tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
        Actor owner = actor("admitted-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
        Instant expiresAt = Instant.now().plusSeconds(5);
        grants.saveAndFlush(new StaffAccessGrant(owner.user(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(60), expiresAt, null,
                Instant.now().minusSeconds(60), "R1_ADMITTED_EXPIRY",
                StaffGrantProvisioningSource.ADMIN_API));
        PropertyVisitRequest request = standaloneRequest(tenant.user(), city, team,
                listing(locality(city, "admitted"), "admitted"), owner.user());
        AtomicReference<HttpResponse<String>> answer = new AtomicReference<>();
        Thread reveal = new Thread(() -> answer.set(send("GET", requestPath(request.getId()), owner.token())));
        reveal.setDaemon(true);
        AuditBarrier.arm();
        try {
            reveal.start();
            assertTrue(AuditBarrier.awaitEntered(8000),
                    "the reveal must complete authorization and reach its locked audit section");
            long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean expired = false;
            while (System.nanoTime() < expiryDeadline && !expired) {
                expired = Boolean.TRUE.equals(jdbc.queryForObject(
                        "select clock_timestamp() >= ?", Boolean.class, Timestamp.from(expiresAt)));
                if (!expired) Thread.sleep(50);
            }
            assertTrue(expired, "the grant must expire after admission but before the audit commit");
            assertTrue(reveal.isAlive(), "the already-admitted reveal must still be held open");
        } finally {
            AuditBarrier.release();
        }
        reveal.join(15_000);
        assertFalse(reveal.isAlive());
        assertNotNull(answer.get());
        assertEquals(200, answer.get().statusCode(), answer.get().body());
        assertEquals(TENANT_NAME, mapper.readTree(answer.get().body()).path("name").asText());
        assertEquals(1, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));
    }

    @Test
    void governedCityAndTeamLifecycleSerializesWithRevealAndRefusesActiveDrainInBothOrders() throws Exception {
        for (boolean revealFirst : List.of(true, false)) {
            SupportedCity city = cities.saveAndFlush(new SupportedCity(code("life"), "Lifecycle City", true));
            OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("life-team"), "Lifecycle Team", true));
            Actor tenant = actor("life-tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
            Actor owner = actor("life-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
            Actor admin = actor("life-admin", Role.ROLE_ADMIN, "ADMIN", true, null, null);
            teamGrant(owner, team);
            grants.saveAndFlush(new StaffAccessGrant(admin.user(), StaffCapability.STAFF_ADMIN,
                    StaffScopeType.GLOBAL, null, null, Instant.now().minusSeconds(60), null, null,
                    Instant.now(), "R1_LIFECYCLE_FIXTURE", StaffGrantProvisioningSource.ADMIN_API));
            PropertyVisitRequest request = standaloneRequest(tenant.user(), city, team,
                    listing(locality(city, "life"), "life"), owner.user());
            long cityVersion = cities.findById(city.getId()).orElseThrow().getVersion();
            long teamVersion = teams.findById(team.getId()).orElseThrow().getVersion();

            AtomicReference<HttpResponse<String>> revealAnswer = new AtomicReference<>();
            AtomicReference<Throwable> lifecycleFailure = new AtomicReference<>();
            Thread reveal = new Thread(() -> revealAnswer.set(send("GET", requestPath(request.getId()), owner.token())));
            Thread lifecycle = new Thread(() -> {
                try {
                    ownership.deactivateTeam(admin.user().getId(), team.getId(), cityVersion, teamVersion,
                            "R1_TEST_ACTIVE_DRAIN");
                } catch (Throwable failure) {
                    lifecycleFailure.set(failure);
                }
            });
            reveal.setDaemon(true);
            lifecycle.setDaemon(true);
            AuditBarrier.arm();
            try {
                if (revealFirst) {
                    reveal.start();
                    assertTrue(AuditBarrier.awaitEntered(8000), "reveal must reach its locked audit section");
                    lifecycle.start();
                    assertTrue(observeAnyPostgresBlocker(), "Team deactivation must wait on the reveal's locks");
                } else {
                    lifecycle.start();
                    lifecycle.join(15000);
                    assertFalse(lifecycle.isAlive());
                    reveal.start();
                }
            } finally {
                AuditBarrier.release();
            }
            reveal.join(15000);
            if (revealFirst) lifecycle.join(15000);
            assertFalse(reveal.isAlive());
            assertFalse(lifecycle.isAlive());
            assertNotNull(lifecycleFailure.get(), "active operational work must block Team drain");
            assertTrue(lifecycleFailure.get() instanceof VisitOperationsConflictException,
                    lifecycleFailure.get().toString());
            assertEquals(200, revealAnswer.get().statusCode(), revealAnswer.get().body());
            assertEquals(TENANT_NAME, mapper.readTree(revealAnswer.get().body()).path("name").asText());
            assertEquals(1, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));
            assertTrue(teams.findById(team.getId()).orElseThrow().isActive());
            assertThrows(VisitOperationsConflictException.class, () -> ownership.deactivateCity(
                    admin.user().getId(), city.getId(), cities.findById(city.getId()).orElseThrow().getVersion(),
                    "R1_TEST_ACTIVE_CITY_DRAIN"));
            AuditBarrier.reset();
        }
    }

    private boolean observeAnyPostgresBlocker() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from pg_stat_activity "
                    + "where datname=current_database() and pid<>pg_backend_pid() "
                    + "and cardinality(pg_blocking_pids(pid))>0)", Boolean.class))) return true;
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void contactAndSupportedRequestLinkSerializeInBothOrders() throws Exception {
        var operations = application.getBean(VisitOperationsService.class);
        for (boolean revealFirst : List.of(true, false)) {
            SupportedCity city = cities.saveAndFlush(new SupportedCity(code("linkrace"), "Link Race City", true));
            OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code("linkrace-team"), "Link Race Team", true));
            Actor tenant = actor("linkrace-tenant", Role.ROLE_TENANT, null, false, TENANT_NAME, TENANT_PHONE);
            Actor owner = actor("linkrace-owner", Role.ROLE_TENANT, "OPERATIONS", true, null, null);
            teamGrant(owner, team);
            PropertyVisitRequest request = standaloneRequest(tenant.user(), city, team,
                    listing(locality(city, "linkrace"), "linkrace"), owner.user());
            AtomicReference<HttpResponse<String>> answer = new AtomicReference<>();
            AtomicReference<com.indore.pathome.spaces.dto.OperationsVisitSessionView> linked = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reveal = new Thread(() -> answer.set(send("GET", requestPath(request.getId()), owner.token())));
            Thread coordinate = new Thread(() -> {
                try {
                    linked.set(operations.coordinateRequest(owner.user().getId(), request.getId(),
                            new com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand(request.getVersion(), null, null)));
                } catch (Throwable error) { failure.set(error); }
            });
            reveal.setDaemon(true); coordinate.setDaemon(true);
            AuditBarrier.arm();
            try {
                (revealFirst ? reveal : coordinate).start();
                assertTrue(AuditBarrier.awaitEntered(8000));
                assertEquals(revealFirst ? "TENANT_CONTACT_REVEALED" : "REQUEST_SESSION_LINKED", AuditBarrier.firstAction);
                (revealFirst ? coordinate : reveal).start();
                Long waiter = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (waiter == null && System.nanoTime() < deadline) {
                    waiter = jdbc.query("select pid from pg_stat_activity where datname=current_database() "
                                    + "and ? = any(pg_blocking_pids(pid)) limit 1",
                            rows -> rows.next() ? rows.getLong(1) : null, AuditBarrier.firstPid);
                    if (waiter == null) Thread.sleep(20);
                }
                assertNotNull(waiter, "the second production operation must wait on the first operation's actual locks");
                System.out.println("R1 contact-link revealFirst=" + revealFirst + " blocker="
                        + AuditBarrier.firstPid + " waiter=" + waiter + " actor=" + owner.user().getId()
                        + " request=" + request.getId());
            } finally { AuditBarrier.release(); }
            reveal.join(15000); coordinate.join(15000);
            assertFalse(reveal.isAlive()); assertFalse(coordinate.isAlive());
            assertNull(failure.get()); assertNotNull(linked.get()); assertNotNull(answer.get());
            assertEquals(revealFirst ? 200 : 404, answer.get().statusCode(), answer.get().body());
            if (!revealFirst) {
                assertFalse(answer.get().body().contains(TENANT_NAME));
                assertFalse(answer.get().body().contains(TENANT_PHONE));
            }
            assertEquals(revealFirst ? 1 : 0, auditCount("PROPERTY_VISIT_REQUEST", request.getId()));
            assertEquals(404, send("GET", requestPath(request.getId()), owner.token()).statusCode());
            assertEquals(200, send("GET", sessionPath(linked.get().sessionId()), owner.token()).statusCode(),
                    "the correctly owned Session must remain a positive contact control");
            assertEquals(1, auditCount("VISIT_SESSION", linked.get().sessionId()));
            AuditBarrier.reset();
        }
    }

    private PropertyVisitRequest standaloneRequest(User tenant, SupportedCity city, OperatingTeam team,
                                                   Listing listing, User coordinator) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(listing);
        request.setStatus(VisitRequestStatus.RECEIVED);
        request.setSupportedCity(city);
        request.setOperatingTeam(team);
        request.setOperationalScopeReady(true);
        request.setCoordinator(coordinator);
        request.setPreferredVisitTiming("Saturday afternoon");
        return requests.saveAndFlush(request);
    }

    private PropertyVisitRequest linkedRequest(User tenant, SupportedCity city, OperatingTeam team,
                                                Listing listing, VisitSession session, User coordinator) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(listing);
        request.setStatus(VisitRequestStatus.COORDINATING);
        request.setSession(session);
        request.setSupportedCity(city);
        request.setOperatingTeam(team);
        request.setOperationalScopeReady(true);
        request.setCoordinator(coordinator);
        return requests.saveAndFlush(request);
    }

    private VisitSession draftSession(User tenant, SupportedCity city, OperatingTeam team, User coordinator) {
        VisitSession session = baseSession(tenant, city, team, coordinator);
        session.setStatus(VisitSessionStatus.DRAFT);
        return sessions.saveAndFlush(session);
    }

    private VisitSession sessionInStatus(VisitSessionStatus status, User tenant, SupportedCity city,
                                          OperatingTeam team, User coordinator, User groundExecutive,
                                          long slotHours) {
        VisitSession session = baseSession(tenant, city, team, coordinator);
        if (status == VisitSessionStatus.SCHEDULED || status == VisitSessionStatus.STARTED) {
            Instant scheduledAt = Instant.now().plusSeconds(slotHours * 3600);
            session.setScheduledAt(scheduledAt);
            session.setZoneId("Asia/Kolkata");
            session.setDurationSnapshotMinutes(60);
            session.setReservedEndAt(scheduledAt.plusSeconds(3600));
            session.setRepresentative(groundExecutive);
            session.setAssignedAt(Instant.now());
            if (status == VisitSessionStatus.STARTED) {
                session.setStartedAt(Instant.now());
                session.setExecutionDurationSnapshotMinutes(60);
                session.setExpectedEndAt(scheduledAt.plusSeconds(3600));
            }
        }
        session.setStatus(status);
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

    private Actor actor(String label, Role role, String profileRole, boolean staffActive,
                        String fullName, String phone) {
        User user = new User();
        user.setEmail("tenant-contact-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(fullName);
        user.setPhoneNumber(phone);
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

    private void teamGrant(Actor actor, OperatingTeam team) {
        grants.saveAndFlush(new StaffAccessGrant(actor.user(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(60), null, null,
                Instant.now(), "TEST_TENANT_CONTACT", StaffGrantProvisioningSource.ADMIN_API));
    }

    private Locality locality(SupportedCity city, String prefix) {
        Locality locality = new Locality(city.getDisplayName(), prefix + " sector " + UUID.randomUUID(),
                null, null, null);
        locality.setSupportedCity(city);
        return localities.saveAndFlush(locality);
    }

    private Listing listing(Locality locality, String title) {
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
        return listings.saveAndFlush(listing);
    }

    private int auditCount(String targetType, long targetId) {
        Integer count = jdbc.queryForObject("select count(*) from operational_audit_events "
                        + "where action_code = 'TENANT_CONTACT_REVEALED' and target_type = ? and target_id = ?",
                Integer.class, targetType, targetId);
        return count == null ? 0 : count;
    }

    private String requestPath(long requestId) {
        return "/api/v1/operations/visit-requests/" + requestId + "/tenant-contact";
    }

    private String sessionPath(long sessionId) {
        return "/api/v1/operations/visit-sessions/" + sessionId + "/tenant-contact";
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private JsonNode read(String method, String path, String token) throws Exception {
        HttpResponse<String> response = send(method, path, token);
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
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

    private static String code(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Actor(User user, String token) {}

    @TestConfiguration
    static class ContactTestConfiguration {
        @Bean
        @Primary
        OperationalAuditService guardedAuditService(OperationalAuditEventRepository events, DatabaseClock clock,
                                                     JdbcTemplate jdbc) {
            return new OperationalAuditService(events, clock) {
                @Override
                public OperationalAuditEvent recordUserEvent(User actor, String actionCode, String targetType,
                        Long targetId, String reasonCode, SupportedCity city, OperatingTeam team,
                        Map<String, Object> details) {
                    if (AuditBarrier.armed) {
                        if (AuditBarrier.entered.getCount() > 0) {
                            AuditBarrier.firstPid = jdbc.queryForObject("select pg_backend_pid()", Long.class);
                            AuditBarrier.firstAction = actionCode;
                        }
                        AuditBarrier.entered.countDown();
                        try {
                            AuditBarrier.release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("audit barrier interrupted", interrupted);
                        }
                    }
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

    static final class AuditBarrier {
        private static volatile boolean armed;
        private static volatile CountDownLatch entered;
        private static volatile CountDownLatch release;
        private static volatile Long firstPid;
        private static volatile String firstAction;

        private AuditBarrier() {}

        static void arm() {
            firstPid = null;
            firstAction = null;
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            armed = true;
        }

        static boolean awaitEntered(long millis) throws InterruptedException {
            CountDownLatch latch = entered;
            return latch != null && latch.await(millis, TimeUnit.MILLISECONDS);
        }

        static void release() {
            CountDownLatch latch = release;
            if (latch != null) latch.countDown();
        }

        static void reset() {
            armed = false;
            release();
            entered = null;
            release = null;
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
