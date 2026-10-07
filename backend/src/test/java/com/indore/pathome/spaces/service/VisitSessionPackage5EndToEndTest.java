package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.*;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.service.field.LocationSnapshotProvider;
import com.indore.pathome.spaces.service.field.NoLocationSnapshotProvider;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller-to-database Package 1–4 flows on real PostgreSQL; only external location/GPS is absent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=package-five-integration-test-jwt-secret-32-bytes-minimum",
        "pathome.visit.otp.hmac-secret=package-five-integration-test-otp-secret-32-bytes-minimum",
        "cloudinary.api-key=package-five-test-key",
        "cloudinary.api-secret=package-five-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PATHOME_PACKAGE5_E2E_TEST", matches = "true")
class VisitSessionPackage5EndToEndTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "pathome_package5_e2e_" + UUID.randomUUID().toString().replace("-", "");
    private static final String LEGACY_TENANT_EMAIL = "package5-legacy-tenant@example.test";
    private static Long legacyTenantId;
    private static Long legacySessionId;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            try {
                connection.setSchema(SCHEMA);
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
                flyway().target("40").load().migrate();
                statement.execute("INSERT INTO users(email,full_name,role) VALUES ('" + LEGACY_TENANT_EMAIL
                        + "','Legacy tenant','ROLE_TENANT')");
                try (var rows = statement.executeQuery("SELECT id FROM users WHERE email='" + LEGACY_TENANT_EMAIL + "'")) {
                    if (!rows.next()) throw new IllegalStateException("Could not create legacy tenant fixture");
                    legacyTenantId = rows.getLong(1);
                }
                statement.execute("INSERT INTO visit_sessions(tenant_id,status,city) VALUES (" + legacyTenantId
                        + ",'COMPLETED','Legacy City')");
                try (var rows = statement.executeQuery("SELECT id FROM visit_sessions WHERE tenant_id=" + legacyTenantId)) {
                    if (!rows.next()) throw new IllegalStateException("Could not create legacy completed visit fixture");
                    legacySessionId = rows.getLong(1);
                }
                flyway().load().migrate();
            } catch (Exception failure) {
                statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                throw failure;
            }
        }
        String schemaUrl = BASE_URL + (BASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(BASE_URL, USERNAME, PASSWORD).schemas(SCHEMA)
                .defaultSchema(SCHEMA).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtUtils jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private LocalityRepository localities;
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private GroundExecutiveSchedulingProfileRepository schedulingProfiles;
    @Autowired private GroundExecutiveCoverageRepository coverage;
    @Autowired private GroundExecutiveShiftRepository shifts;
    @Autowired private VisitSessionRepository sessions;
    @Autowired private VisitSessionItemRepository items;
    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private VisitNotificationOutboxWorker outboxWorker;
    @Autowired private LocationSnapshotProvider locations;
    @Autowired private PlatformTransactionManager transactionManager;
    @LocalServerPort private int httpPort;

    @BeforeEach
    void assertRealMigrationSchemaIsAtV49WithoutOwnershipBackfill() {
        assertEquals(49, jdbc.queryForObject(
                "SELECT max(version::integer) FROM flyway_schema_history WHERE type='SQL' AND success", Integer.class));
        assertEquals(1, count("SELECT count(*) FROM visit_policy WHERE id=1"));
        assertEquals(0, count("SELECT count(*) FROM visit_sessions WHERE operational_scope_ready=TRUE "
                + "OR supported_city_id IS NOT NULL OR operating_team_id IS NOT NULL OR coordinator_user_id IS NOT NULL"));
        assertEquals(0, count("SELECT count(*) FROM property_visit_requests WHERE operational_scope_ready=TRUE "
                + "OR supported_city_id IS NOT NULL OR operating_team_id IS NOT NULL OR coordinator_user_id IS NOT NULL"));
    }

    @Test
    void legacyCompletedVisitIsReportedWithoutInventedOutcomes() throws Exception {
        User tenant = users.findById(legacyTenantId).orElseThrow();
        String token = jwt.generateToken(tenant.getId(), tenant.getEmail(), tenant.getRole().name());
        JsonNode outcome = get("/api/v1/tenant/visit-sessions/" + legacySessionId + "/outcome", token, 200);
        assertEquals("RESULTS_NOT_RECORDED", outcome.path("lifecycle").asText());
        assertEquals("RESULTS_NOT_RECORDED", outcome.path("outcomeSummary").asText());
        assertFalse(outcome.path("properties").elements().hasNext());
        assertEquals("LEGACY_UNRECORDED", text("SELECT state FROM visit_session_outcome_reports WHERE session_id=?", legacySessionId));
        assertEquals(0, count("SELECT count(*) FROM visit_session_item_outcomes WHERE session_id=?", legacySessionId));

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort
                        + "/api/v1/tenant/visit-sessions/" + legacySessionId + "/outcome"))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "the authenticated endpoint must be served over the real HTTP listener");
        assertTrue(mapper.readTree(response.body()).path("lifecycle").asText().equals("RESULTS_NOT_RECORDED"));
    }

    @Test
    void goldenControllerFlowCreatesStartsFinishesAndPublishesTruthfulTenantOutcome() throws Exception {
        assertInstanceOf(NoLocationSnapshotProvider.class, locations,
                "the E2E must use the production no-live-location fallback, not a mocked provider");
        Workflow flow = createScheduledWorkflow(2);
        assertEquals("LOCATION_UNAVAILABLE_FALLBACK_USED", flow.recommendationStatus());
        assertEquals(VisitSessionStatus.SCHEDULED, sessions.findById(flow.sessionId()).orElseThrow().getStatus());
        assertEquals(1, count("SELECT count(*) FROM visit_entitlement_ledger WHERE session_id=? AND event_type='RESERVE'", flow.sessionId()));
        assertEquals(1, count("SELECT count(*) FROM visit_entitlement_ledger WHERE idempotency_key=?",
                "INITIAL_GRANT:" + flow.tenantUserId()));
        assertEquals(1, count("SELECT count(*) FROM tenant_visit_entitlement_accounts WHERE user_id=? AND available_credits=4 AND reserved_credits=1",
                flow.tenantUserId()));

        JsonNode assigned = get("/api/v1/ground/visit-sessions?page=0&size=20", flow.geToken(), 200);
        assertTrue(containsSession(assigned, flow.sessionId()));
        assertFalse(containsSession(get("/api/v1/ground/visit-sessions?page=0&size=20", flow.otherGeToken(), 200), flow.sessionId()));
        JsonNode started = startVisit(flow);
        assertEquals("STARTED", started.path("status").asText());
        assertEquals(1, count("SELECT count(*) FROM visit_entitlement_ledger WHERE session_id=? AND event_type='CONSUME'", flow.sessionId()));
        JsonNode report = get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/outcome-report", flow.geToken(), 200);
        assertEquals(2, report.path("items").size());
        report = recordOutcome(flow, report, flow.itemIds().get(0), "VISITED", null, null);
        report = recordOutcome(flow, report, flow.itemIds().get(1), "SKIPPED", "PROPERTY_UNAVAILABLE", "Internal door access note");
        JsonNode completed = post("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/complete-with-outcomes",
                flow.geToken(), new CompleteVisitSessionWithOutcomesCommand(report.path("sessionVersion").asLong(),
                        report.path("reportVersion").asLong(), UUID.randomUUID()), 200);
        assertEquals("COMPLETED", completed.path("sessionState").asText());
        assertEquals("FINALIZED", completed.path("reportState").asText());

        JsonNode tenantHistory = get("/api/v1/tenant/visit-sessions/outcomes?page=0&size=20", flow.tenantToken(), 200);
        JsonNode tenantView = sessionFrom(tenantHistory.path("sessions"), flow.sessionId());
        assertEquals("PARTLY_VIEWED", tenantView.path("outcomeSummary").asText());
        assertTrue(tenantView.path("properties").toString().contains("OPERATIONS") == false);
        assertFalse(tenantHistory.toString().contains("Internal door access note"));
        assertEquals("ACTIVE", listings.findById(flow.listingIds().get(1)).orElseThrow().getStatus().name());
        assertEquals(1, count("SELECT count(*) FROM visit_notification_outbox WHERE event_key=? AND event_type='VISIT_OUTCOME_READY'",
                "VISIT_OUTCOME_READY:" + flow.sessionId()));
        assertEquals(0, count("SELECT count(*) FROM visit_notification_outbox WHERE event_key LIKE ? AND event_type='VISIT_OUTCOME_UPDATED'",
                "VISIT_OUTCOME_UPDATED:" + flow.sessionId() + ":%"));
        assertEquals(1, count("SELECT count(*) FROM visit_execution_events WHERE session_id=? AND event_type='OUTCOME_SCOPE_CAPTURED'",
                flow.sessionId()));
        assertEquals(1, count("SELECT count(*) FROM visit_execution_events WHERE session_id=? AND event_type='OUTCOME_REPORT_FINALIZED'",
                flow.sessionId()));
        assertEquals(1, count("SELECT count(*) FROM visit_entitlement_ledger WHERE session_id=? AND event_type='CONSUME'", flow.sessionId()));
        assertEquals(0, count("SELECT count(*) FROM visit_entitlement_ledger WHERE session_id=? AND event_type IN ('RELEASE','RESTORE','OE_OPERATIONAL_RESTORE')",
                flow.sessionId()));

        JsonNode operationsDetail = get("/api/v1/operations/visit-outcomes/" + flow.sessionId(), flow.opsToken(), 200);
        JsonNode firstItem = findById(operationsDetail.path("properties"), "itemId", flow.itemIds().get(0));
        UUID firstCorrectionId = UUID.randomUUID();
        CorrectVisitOutcomeCommand firstCorrection = new CorrectVisitOutcomeCommand(flow.itemIds().get(1),
                VisitSessionItemOutcomeState.VISITED, null, null,
                "Field records confirm the second stop was viewed", operationsDetail.path("reportVersion").asLong(),
                findById(operationsDetail.path("properties"), "itemId", flow.itemIds().get(1)).path("version").asLong(),
                firstCorrectionId);
        JsonNode afterFirstCorrection = post("/api/v1/operations/visit-outcomes/" + flow.sessionId() + "/corrections",
                flow.opsToken(), firstCorrection, 200);
        assertEquals("ALL_VIEWED", afterFirstCorrection.path("summary").asText());
        JsonNode refreshedFirstItem = findById(afterFirstCorrection.path("properties"), "itemId", flow.itemIds().get(0));
        UUID secondCorrectionId = UUID.randomUUID();
        CorrectVisitOutcomeCommand secondCorrection = new CorrectVisitOutcomeCommand(flow.itemIds().get(0),
                VisitSessionItemOutcomeState.SKIPPED, VisitSessionItemSkipReason.TENANT_DECLINED, null,
                "Tenant clarified that the first stop was declined",
                afterFirstCorrection.path("reportVersion").asLong(), refreshedFirstItem.path("version").asLong(),
                secondCorrectionId);
        JsonNode afterSecondCorrection = post("/api/v1/operations/visit-outcomes/" + flow.sessionId() + "/corrections",
                flow.opsToken(), secondCorrection, 200);
        assertEquals("PARTLY_VIEWED", afterSecondCorrection.path("summary").asText());
        post("/api/v1/operations/visit-outcomes/" + flow.sessionId() + "/corrections",
                flow.opsToken(), secondCorrection, 200);
        String correctionKeyPattern = "VISIT_OUTCOME_UPDATED:" + flow.sessionId() + ":%";
        assertEquals(2, count("SELECT count(*) FROM visit_notification_outbox WHERE event_key LIKE ?", correctionKeyPattern));
        assertEquals(2, count("SELECT count(DISTINCT event_key) FROM visit_notification_outbox WHERE event_key LIKE ?",
                correctionKeyPattern));
        assertEquals(2, count("SELECT count(*) FROM visit_execution_events WHERE session_id=? AND event_type='OUTCOME_ITEM_CORRECTED'",
                flow.sessionId()));
        assertEquals("PARTLY_VIEWED", get("/api/v1/tenant/visit-sessions/" + flow.sessionId() + "/outcome",
                flow.tenantToken(), 200).path("outcomeSummary").asText());
        assertEquals(1, count("SELECT count(*) FROM visit_entitlement_ledger WHERE session_id=? AND event_type='CONSUME'",
                flow.sessionId()));
        assertEquals(403, post("/api/v1/operations/visit-outcomes/" + flow.sessionId() + "/corrections",
                flow.tenantToken(), secondCorrection, 403).path("status").asInt());
    }

    @Test
    void directFinishLeavesOpenReportPendingAndCurrentGeCanRecoverPartialOutcomes() throws Exception {
        Workflow flow = createScheduledWorkflow(2);
        startVisit(flow);
        JsonNode finished = post("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/finish", flow.geToken(), null, 200);
        assertEquals("COMPLETED", finished.path("status").asText());
        assertEquals("OPEN", text("SELECT state FROM visit_session_outcome_reports WHERE session_id=?", flow.sessionId()));
        JsonNode pendingTenant = get("/api/v1/tenant/visit-sessions/" + flow.sessionId() + "/outcome", flow.tenantToken(), 200);
        assertEquals("DETAILS_PENDING", pendingTenant.path("lifecycle").asText());
        assertTrue(pendingTenant.path("properties").findValuesAsText("outcome").stream().allMatch("PENDING"::equals));
        JsonNode pendingGe = get("/api/v1/ground/visit-sessions/pending-outcomes?page=0&size=20", flow.geToken(), 200);
        assertTrue(containsSession(pendingGe, flow.sessionId()));

        JsonNode report = get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/outcome-report", flow.geToken(), 200);
        report = recordOutcome(flow, report, flow.itemIds().get(0), "VISITED", null, null);
        assertEquals("OPEN", report.path("reportState").asText());
        assertEquals(1, (int) java.util.stream.StreamSupport.stream(report.path("items").spliterator(), false)
                .filter(item -> "UNRECORDED".equals(item.path("outcome").asText())).count());
        assertEquals(409, post("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/complete-with-outcomes",
                flow.geToken(), new CompleteVisitSessionWithOutcomesCommand(report.path("sessionVersion").asLong(),
                        report.path("reportVersion").asLong(), UUID.randomUUID()), 409).path("status").asInt());
        report = recordOutcome(flow, report, flow.itemIds().get(1), "SKIPPED", "ACCESS_DENIED", null);
        JsonNode finalized = post("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/complete-with-outcomes",
                flow.geToken(), new CompleteVisitSessionWithOutcomesCommand(report.path("sessionVersion").asLong(),
                        report.path("reportVersion").asLong(), UUID.randomUUID()), 200);
        assertEquals("FINALIZED", finalized.path("reportState").asText());
        JsonNode tenant = get("/api/v1/tenant/visit-sessions/" + flow.sessionId() + "/outcome", flow.tenantToken(), 200);
        assertEquals("PARTLY_VIEWED", tenant.path("outcomeSummary").asText());
    }

    @Test
    void noneViewedAndPropertyUnavailableRemainTruthfulWithoutChangingConsumedEntitlement() throws Exception {
        Workflow flow = createScheduledWorkflow(2);
        startVisit(flow);
        JsonNode report = get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/outcome-report", flow.geToken(), 200);
        report = recordOutcome(flow, report, flow.itemIds().get(0), "SKIPPED", "PROPERTY_UNAVAILABLE", "Internal access detail");
        report = recordOutcome(flow, report, flow.itemIds().get(1), "SKIPPED", "TENANT_DECLINED", null);
        JsonNode finalized = post("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/complete-with-outcomes",
                flow.geToken(), new CompleteVisitSessionWithOutcomesCommand(report.path("sessionVersion").asLong(),
                        report.path("reportVersion").asLong(), UUID.randomUUID()), 200);
        assertEquals("NONE_VIEWED", finalized.path("summary").asText());
        JsonNode tenant = get("/api/v1/tenant/visit-sessions/" + flow.sessionId() + "/outcome", flow.tenantToken(), 200);
        assertEquals("NONE_VIEWED", tenant.path("outcomeSummary").asText());
        assertFalse(tenant.toString().contains("Internal access detail"));
        assertEquals("ACTIVE", listings.findById(flow.listingIds().get(0)).orElseThrow().getStatus().name());
        assertEquals(1, count("SELECT count(*) FROM visit_entitlement_ledger WHERE session_id=? AND event_type='CONSUME'", flow.sessionId()));
        assertEquals(0, count("SELECT count(*) FROM visit_entitlement_ledger WHERE session_id=? AND event_type IN ('RELEASE','RESTORE','OE_OPERATIONAL_RESTORE')",
                flow.sessionId()));
        JsonNode operationsQueue = get("/api/v1/operations/visit-outcomes/exceptions?page=0&size=50", flow.opsToken(), 200);
        assertTrue(containsSession(operationsQueue, flow.sessionId()));
        JsonNode exception = sessionFrom(operationsQueue.path("content"), flow.sessionId());
        assertEquals("FINALIZED", exception.path("reportState").asText());
        assertEquals(0, exception.path("visitedCount").asInt());
        assertEquals(0, exception.path("unrecordedCount").asInt());
    }

    @Test
    void reassignmentRescheduleAndAccountIsolationUseCurrentDatabaseBackedAuthority() throws Exception {
        Workflow flow = createScheduledWorkflow(1);
        Long formerGe = flow.assignedGeId();
        VisitSession current = sessions.findById(flow.sessionId()).orElseThrow();
        JsonNode reassigned = post("/api/v1/operations/visit-sessions/" + flow.sessionId() + "/assignment", flow.opsToken(),
                new AssignGroundExecutiveCommand(current.getVersion(), flow.otherGeId()), 200);
        assertEquals(flow.otherGeId(), reassigned.path("representativeUserId").asLong());
        assertEquals(404, get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/execution", flow.geToken(), 404)
                .path("status").asInt());
        assertEquals(403, post("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/arrived", flow.geToken(), null, 403)
                .path("status").asInt());
        assertEquals(404, get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/start-code-status", flow.geToken(), 404)
                .path("status").asInt());
        assertEquals(404, get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/outcome-report", flow.geToken(), 404)
                .path("status").asInt());
        JsonNode currentGeView = get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/execution", flow.otherGeToken(), 200);
        assertEquals(flow.sessionId(), currentGeView.path("sessionId").asLong());
        assertEquals(404, get("/api/v1/tenant/visit-sessions/" + flow.sessionId() + "/outcome", flow.otherTenantToken(), 404)
                .path("status").asInt());

        VisitSession afterAssignment = sessions.findById(flow.sessionId()).orElseThrow();
        Instant replacement = afterAssignment.getScheduledAt().plus(Duration.ofMinutes(15));
        JsonNode proposed = put("/api/v1/operations/visit-sessions/" + flow.sessionId() + "/schedule", flow.opsToken(),
                new RescheduleVisitSessionCommand(afterAssignment.getVersion(), replacement, afterAssignment.getZoneId()), 200);
        assertEquals("PENDING", text("SELECT tenant_confirmation_state FROM visit_sessions WHERE id=?", flow.sessionId()));
        assertEquals(409, post("/api/v1/tenant/visit-sessions/" + flow.sessionId() + "/confirmation", flow.tenantToken(),
                new TenantVisitConfirmationCommand("CONFIRM", null, UUID.randomUUID(), proposed.path("version").asLong()), 409)
                .path("status").asInt());
        JsonNode accepted = post("/api/v1/tenant/visit-sessions/" + flow.sessionId() + "/confirmation", flow.tenantToken(),
                new TenantVisitConfirmationCommand("ACCEPT_RESCHEDULE", null, UUID.randomUUID(), proposed.path("version").asLong()), 200);
        assertEquals("CONFIRMED", accepted.path("tenantConfirmationState").asText());
        assertEquals(replacement, jdbc.queryForObject("SELECT scheduled_at FROM visit_sessions WHERE id=?",
                java.sql.Timestamp.class, flow.sessionId()).toInstant());
        assertEquals(flow.sessionId(), get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/tenant-contact",
                flow.otherGeToken(), 200).path("sessionId").asLong());

        assertEquals(403, get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/tenant-contact", flow.geToken(), 403)
                .path("status").asInt());
        assertEquals(403, get("/api/v1/operations/visit-outcomes/exceptions?page=0&size=20", flow.tenantToken(), 403)
                .path("status").asInt());
        assertNotEquals(formerGe, flow.otherGeId());
        assertEquals(1, count("SELECT count(*) FROM visit_entitlement_ledger WHERE session_id=? AND event_type='RESERVE'", flow.sessionId()));
        assertEquals(0, count("SELECT count(*) FROM visit_entitlement_ledger WHERE session_id=? AND event_type='RELEASE'", flow.sessionId()));
    }

    @Test
    void concurrentOtpStartConsumesOneCreditAndCreatesOnePhysicalStart() throws Exception {
        Workflow flow = createScheduledWorkflow(1);
        JsonNode code = arriveAndIssueCode(flow);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> concurrentStart(flow, code, UUID.randomUUID(), ready, gate));
            var second = pool.submit(() -> concurrentStart(flow, code, UUID.randomUUID(), ready, gate));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            gate.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
            assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
            assertEquals(1, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
        assertEquals("STARTED", text("SELECT status FROM visit_sessions WHERE id=?", flow.sessionId()));
        assertEquals(1, count("SELECT count(*) FROM visit_entitlement_ledger WHERE session_id=? AND event_type='CONSUME'", flow.sessionId()));
        assertEquals(1, count("SELECT count(*) FROM visit_execution_events WHERE session_id=? AND event_type='STARTED'", flow.sessionId()));
        assertEquals(1, count("SELECT count(*) FROM visit_session_outcome_reports WHERE session_id=?", flow.sessionId()));
    }

    @Test
    void outboxDispatchFailureRetriesAndPersistsOneDurableNotification() throws Exception {
        Workflow flow = createScheduledWorkflow(1);
        startVisit(flow);
        JsonNode report = get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/outcome-report", flow.geToken(), 200);
        report = recordOutcome(flow, report, flow.itemIds().get(0), "VISITED", null, null);
        post("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/complete-with-outcomes", flow.geToken(),
                new CompleteVisitSessionWithOutcomesCommand(report.path("sessionVersion").asLong(),
                        report.path("reportVersion").asLong(), UUID.randomUUID()), 200);

        String eventKey = "VISIT_OUTCOME_READY:" + flow.sessionId();
        String createFunction = "CREATE FUNCTION package5_fail_outcome_notification() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.event_key='" + eventKey + "' THEN RAISE EXCEPTION 'temporary notification storage outage'; "
                + "END IF; RETURN NEW; END $$";
        jdbc.execute(createFunction);
        jdbc.execute("CREATE TRIGGER package5_fail_outcome_notification BEFORE INSERT ON system_notifications "
                + "FOR EACH ROW EXECUTE FUNCTION package5_fail_outcome_notification()");
        try {
            outboxWorker.deliverBatch();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS package5_fail_outcome_notification ON system_notifications");
            jdbc.execute("DROP FUNCTION IF EXISTS package5_fail_outcome_notification()");
        }
        assertEquals("FAILED", text("SELECT state FROM visit_notification_outbox WHERE event_key=?", eventKey));
        assertEquals(1, value("SELECT attempts FROM visit_notification_outbox WHERE event_key=?", eventKey));
        jdbc.update("UPDATE visit_notification_outbox SET available_at=current_timestamp WHERE event_key=?", eventKey);
        outboxWorker.deliverBatch();
        assertEquals("SENT", text("SELECT state FROM visit_notification_outbox WHERE event_key=?", eventKey));
        assertEquals(1, count("SELECT count(*) FROM system_notifications WHERE event_key=?", eventKey));
        assertEquals(2, value("SELECT attempts FROM visit_notification_outbox WHERE event_key=?", eventKey));
    }

    private Workflow createScheduledWorkflow(int propertyCount) throws Exception {
        Actors actors = createActors();
        String city = "Package Five City " + UUID.randomUUID();
        Locality locality = localities.saveAndFlush(new Locality(city, "Central", null, null, null));
        Instant now = Instant.now().plus(Duration.ofMinutes(2)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Instant preferred = Instant.now().plusSeconds(45);
        Instant windowStart = Instant.now().plusSeconds(15);
        Instant windowEnd = now.plus(Duration.ofHours(6));
        // The shift already includes the production conservative unknown-origin travel buffer.
        Instant shiftStart = Instant.now().minus(Duration.ofHours(2));
        configureGe(actors.ge(), actors.operations().user(), city, shiftStart, windowEnd);
        configureGe(actors.otherGe(), actors.operations().user(), city, shiftStart, windowEnd);

        List<Long> listingIds = new ArrayList<>();
        List<Long> requestIds = new ArrayList<>();
        for (int index = 0; index < propertyCount; index++) {
            Listing listing = createListing(locality, "E2E property " + index);
            listingIds.add(listing.getId());
            CreatePropertyVisitRequest command = new CreatePropertyVisitRequest(BigDecimal.valueOf(10000),
                    BigDecimal.valueOf(50000), "Central", "Within a month", "Flexible weekday evening", null,
                    OffsetDateTime.ofInstant(windowStart, ZoneId.of("Asia/Kolkata")),
                    OffsetDateTime.ofInstant(windowEnd, ZoneId.of("Asia/Kolkata")), "Asia/Kolkata",
                    OffsetDateTime.ofInstant(preferred, ZoneId.of("Asia/Kolkata")));
            JsonNode received = post("/api/v1/properties/" + listing.getId() + "/visit-requests",
                    actors.tenant().token(), command, 201);
            requestIds.add(received.path("requestId").asLong());
        }

        JsonNode opsQueue = get("/api/v1/operations/visit-requests?status=RECEIVED&page=0&size=50",
                actors.operations().token(), 200);
        Long sessionId = null;
        JsonNode sessionView = null;
        List<Long> itemIds = new ArrayList<>();
        for (int index = 0; index < requestIds.size(); index++) {
            JsonNode requestRow = findById(opsQueue.path("requests"), "requestId", requestIds.get(index));
            CoordinateVisitRequestCommand coordinate = new CoordinateVisitRequestCommand(requestRow.path("version").asLong(),
                    sessionId, sessionView == null ? null : sessionView.path("version").asLong());
            sessionView = post("/api/v1/operations/visit-requests/" + requestIds.get(index) + "/coordinate",
                    actors.operations().token(), coordinate, 200);
            sessionId = sessionView.path("sessionId").asLong();
            itemIds.add(findById(sessionView.path("items"), "listingId", listingIds.get(index)).path("itemId").asLong());
        }
        for (Long itemId : itemIds) {
            Instant currentStart = Instant.now().plusSeconds(15);
            Instant currentEnd = Instant.now().plus(Duration.ofHours(6));
            VisitSessionAvailabilityCommand availability = new VisitSessionAvailabilityCommand(
                    sessionView.path("version").asLong(), VisitSessionItemConfirmationStatus.CONFIRMED,
                    OffsetDateTime.ofInstant(currentStart, ZoneId.of("Asia/Kolkata")),
                    OffsetDateTime.ofInstant(currentEnd, ZoneId.of("Asia/Kolkata")),
                    "Asia/Kolkata", PropertyAvailabilitySource.PHONE);
            sessionView = put("/api/v1/operations/visit-sessions/" + sessionId + "/items/" + itemId + "/availability",
                    actors.operations().token(), availability, 200);
        }

        JsonNode recommendation = post("/api/v1/operations/visit-sessions/" + sessionId + "/recommendations",
                actors.operations().token(), new RecommendationRequest(sessionView.path("version").asLong()), 200);
        assertFalse(recommendation.path("candidates").isEmpty(), recommendation.toString());
        JsonNode candidate = recommendation.path("candidates").get(0);
        JsonNode approved = post("/api/v1/operations/visit-sessions/" + sessionId + "/recommendations/approval",
                actors.operations().token(), new ApproveVisitRecommendationCommand(sessionView.path("version").asLong(),
                        candidate.path("groundExecutiveUserId").asLong(), Instant.parse(candidate.path("scheduledAt").asText()),
                        candidate.path("zoneId").asText(), null), 200);
        Long assignedGeId = approved.path("session").path("representativeUserId").asLong();
        Actor assigned = assignedGeId.equals(actors.ge().user().getId()) ? actors.ge() : actors.otherGe();
        Actor otherGe = assignedGeId.equals(actors.ge().user().getId()) ? actors.otherGe() : actors.ge();
        JsonNode tenantAccepted = post("/api/v1/tenant/visit-sessions/" + sessionId + "/confirmation",
                actors.tenant().token(), new TenantVisitConfirmationCommand("CONFIRM", null,
                        UUID.randomUUID(), approved.path("session").path("version").asLong()), 200);
        assertEquals("CONFIRMED", tenantAccepted.path("tenantConfirmationState").asText());
        JsonNode geAssigned = get("/api/v1/ground/visit-sessions/" + sessionId, assigned.token(), 200);
        assertEquals(itemIds.size(), geAssigned.path("items").size());
        return new Workflow(actors.tenant().user().getId(), actors.tenant().token(), actors.otherTenant().token(), actors.operations().token(),
                assigned.token(), otherGe.token(), sessionId, itemIds, listingIds, assignedGeId,
                otherGe.user().getId(), recommendation.path("status").asText());
    }

    private JsonNode startVisit(Workflow flow) throws Exception {
        JsonNode code = arriveAndIssueCode(flow);
        return post("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/start", flow.geToken(),
                new VisitOtpStartCommand(code.path("generation").asInt(), code.path("code").asText(), UUID.randomUUID()), 200);
    }

    private JsonNode arriveAndIssueCode(Workflow flow) throws Exception {
        awaitConfirmedWindow(flow.sessionId());
        post("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/arrived", flow.geToken(), null, 200);
        post("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/contact-attempts", flow.geToken(),
                new GroundVisitContactCommand("CONNECTED", null, null, UUID.randomUUID()), 200);
        JsonNode contact = get("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/tenant-contact", flow.geToken(), 200);
        assertEquals(flow.sessionId(), contact.path("sessionId").asLong());
        return post("/api/v1/tenant/visit-sessions/" + flow.sessionId() + "/start-code", flow.tenantToken(), null, 200);
    }

    private void awaitConfirmedWindow(Long sessionId) throws InterruptedException {
        java.sql.Timestamp availableAt = jdbc.queryForObject(
                "SELECT greatest(max(i.availability_start_at), max(s.scheduled_at)) "
                        + "FROM visit_session_items i JOIN visit_sessions s ON s.id=i.session_id "
                        + "WHERE i.session_id=? AND i.removed_at IS NULL",
                java.sql.Timestamp.class, sessionId);
        if (availableAt == null) throw new AssertionError("Confirmed item availability is missing");
        long remainingMillis = availableAt.toInstant().toEpochMilli() - System.currentTimeMillis();
        if (remainingMillis > 0) Thread.sleep(remainingMillis);
    }

    private JsonNode recordOutcome(Workflow flow, JsonNode report, Long itemId,
            String outcome, String skipReason, String privateNote) throws Exception {
        JsonNode item = findById(report.path("items"), "itemId", itemId);
        RecordVisitSessionItemOutcomeCommand command = new RecordVisitSessionItemOutcomeCommand(
                VisitSessionItemOutcomeState.valueOf(outcome), skipReason == null ? null : VisitSessionItemSkipReason.valueOf(skipReason),
                privateNote, report.path("sessionVersion").asLong(), report.path("reportVersion").asLong(),
                item.path("itemVersion").asLong(), UUID.randomUUID());
        return put("/api/v1/ground/visit-sessions/" + flow.sessionId() + "/items/" + itemId + "/outcome",
                flow.geToken(), command, 200);
    }

    private int concurrentStart(Workflow flow, JsonNode code, UUID operationId,
            CountDownLatch ready, CountDownLatch gate) throws Exception {
        ready.countDown();
        if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent START gate timed out");
        MvcResult result = mvc.perform(request(HttpMethod.POST, "/api/v1/ground/visit-sessions/" + flow.sessionId() + "/start")
                        .header("Authorization", "Bearer " + flow.geToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(new VisitOtpStartCommand(code.path("generation").asInt(),
                                code.path("code").asText(), operationId))))
                .andReturn();
        return result.getResponse().getStatus();
    }

    private Actors createActors() {
        Actor tenant = actor("tenant", Role.ROLE_TENANT, null);
        Actor otherTenant = actor("other-tenant", Role.ROLE_TENANT, null);
        Actor operations = actor("operations", Role.ROLE_BROKER, "WFH_ADMIN");
        Actor ge = actor("ge", Role.ROLE_GROUND_BOY, "GROUND_BOY");
        Actor otherGe = actor("other-ge", Role.ROLE_GROUND_BOY, "GROUND_BOY");
        return new Actors(tenant, otherTenant, operations, ge, otherGe);
    }

    private Actor actor(String label, Role role, String employeeRole) {
        User user = new User();
        user.setEmail("p5-" + label + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName("Package 5 " + label);
        user.setPhoneNumber("+91987654" + String.format("%04d", (int) (Math.random() * 10000)));
        user.setRole(role);
        user.setFreeVisitsRemaining(5);
        user = users.saveAndFlush(user);
        if (employeeRole != null) employees.saveAndFlush(new EmployeeProfile(user, employeeRole, null, BigDecimal.ZERO));
        return new Actor(user, jwt.generateToken(user.getId(), user.getEmail(), role.name()));
    }

    private void configureGe(Actor ge, User updater, String city, Instant shiftStart, Instant shiftEnd) {
        GroundExecutiveSchedulingProfile profile = new TransactionTemplate(transactionManager).execute(status -> {
            EmployeeProfile employee = employees.findByUserId(ge.user().getId()).orElseThrow();
            GroundExecutiveSchedulingProfile newProfile = new GroundExecutiveSchedulingProfile();
            newProfile.setEmployeeProfile(employee);
            newProfile.setSchedulingActive(true);
            newProfile.setUpdatedBy(updater);
            return schedulingProfiles.saveAndFlush(newProfile);
        });
        GroundExecutiveCoverage coveredCity = new GroundExecutiveCoverage();
        coveredCity.setSchedulingProfile(profile);
        coveredCity.setCity(city);
        coveredCity.setCreatedBy(updater);
        coverage.saveAndFlush(coveredCity);
        GroundExecutiveShift shift = new GroundExecutiveShift();
        shift.setSchedulingProfile(profile);
        shift.setStartsAt(shiftStart);
        shift.setEndsAt(shiftEnd);
        shift.setZoneId("Asia/Kolkata");
        shift.setCreatedBy(updater);
        shift.setUpdatedBy(updater);
        shifts.saveAndFlush(shift);
    }

    private Listing createListing(Locality locality, String title) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle(title);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Package 5 address");
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

    private JsonNode get(String path, String token, int expectedStatus) throws Exception {
        return invoke(HttpMethod.GET, path, token, null, expectedStatus);
    }

    private JsonNode post(String path, String token, Object body, int expectedStatus) throws Exception {
        return invoke(HttpMethod.POST, path, token, body, expectedStatus);
    }

    private JsonNode put(String path, String token, Object body, int expectedStatus) throws Exception {
        return invoke(HttpMethod.PUT, path, token, body, expectedStatus);
    }

    private JsonNode invoke(HttpMethod method, String path, String token, Object body, int expectedStatus) throws Exception {
        var builder = request(method, path).header("Authorization", "Bearer " + token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
        MvcResult result = mvc.perform(builder).andReturn();
        String response = result.getResponse().getContentAsString();
        assertEquals(expectedStatus, result.getResponse().getStatus(), response);
        return response.isBlank() ? mapper.createObjectNode().put("status", expectedStatus) : mapper.readTree(response);
    }

    private JsonNode findById(JsonNode rows, String field, long id) {
        for (JsonNode row : rows) if (row.path(field).asLong(Long.MIN_VALUE) == id) return row;
        throw new AssertionError("No row matched " + field + "=" + id + " in " + rows);
    }

    private JsonNode sessionFrom(JsonNode rows, long id) { return findById(rows, "sessionId", id); }

    private boolean containsSession(JsonNode page, long id) {
        JsonNode rows = page.has("sessions") ? page.path("sessions") : page.has("content") ? page.path("content") : page;
        for (JsonNode row : rows) if (row.path("sessionId").asLong(Long.MIN_VALUE) == id) return true;
        return false;
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private int value(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private String text(String sql, Object... arguments) { return jdbc.queryForObject(sql, String.class, arguments); }

    private record Actor(User user, String token) {}
    private record Actors(Actor tenant, Actor otherTenant, Actor operations, Actor ge, Actor otherGe) {}
    private record Workflow(Long tenantUserId, String tenantToken, String otherTenantToken, String opsToken, String geToken,
            String otherGeToken, Long sessionId, List<Long> itemIds, List<Long> listingIds,
            Long assignedGeId, Long otherGeId, String recommendationStatus) {}
}
