package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indore.pathome.spaces.dto.ApproveVisitRecommendationCommand;
import com.indore.pathome.spaces.dto.OperationsVisitSessionView;
import com.indore.pathome.spaces.dto.VisitSchedulingApprovalView;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.support.DisposableIntegrationTarget;
import com.indore.pathome.spaces.support.DisposablePostgresTarget;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E8: a persisted B09/V50 receipt written before the additive linked-timing field must remain
 * readable and replayable without rewriting its raw JSON or creating duplicate effects.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "server.address=127.0.0.1",
        "app.jwt.secret=receipt-history-postgres-jwt-secret-32-bytes",
        "pathome.visit.otp.hmac-secret=receipt-history-postgres-otp-secret-32-bytes",
        "cloudinary.api-key=receipt-history-test-key",
        "cloudinary.api-secret=receipt-history-test-secret",
        "pathome.visit.outbox.poll-delay-ms=3600000",
        "pathome.visit.no-show-settlement-delay-ms=3600000",
        "pathome.visit.entitlement-reservation-poll-delay-ms=3600000",
        "pathome.visit.overrun-alert-poll-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class VisitSchedulingReceiptPostgresTest {
    private static final String SCHEMA = "receipt_history_test_" + UUID.randomUUID().toString().replace("-", "");
    private static DisposablePostgresTarget.Verified verifiedTarget;
    private static boolean schemaCreated;

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
        return Flyway.configure()
                .dataSource(verifiedTarget.target().jdbcUrl(), verifiedTarget.target().username(),
                        verifiedTarget.target().password())
                .schemas(SCHEMA).defaultSchema(SCHEMA).locations("classpath:db/migration")
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
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private VisitOperationsService operations;
    @Autowired private VisitSchedulingCommandStore receipts;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @Autowired private VisitSessionRepository sessions;

    @Test
    void historicalPersistedReceiptReadsAndReplaysWithoutMutation() throws Exception {
        Fixture fixture = fixture();
        User oe = fixture.owner();
        VisitSession session = new VisitSession();
        session.setTenant(fixture.tenant());
        session.setCity(fixture.city().getDisplayName());
        session.setSupportedCity(fixture.city());
        session.setOperatingTeam(fixture.team());
        session.setCoordinator(oe);
        session.setOperationalScopeReady(true);
        session.setStatus(VisitSessionStatus.DRAFT);
        session = sessions.saveAndFlush(session);
        final long sessionId = session.getId();
        final long ownerId = oe.getId();

        OperationsVisitSessionView view = operations.getOperationsSession(oe.getId(), session.getId());
        UUID commandId = UUID.randomUUID();
        Instant scheduledAt = Instant.parse("2027-02-14T05:00:00Z");
        ApproveVisitRecommendationCommand command = new ApproveVisitRecommendationCommand(
                view.version(), 4207L, scheduledAt, "Asia/Kolkata", null, commandId);
        VisitSchedulingApprovalView historical = new VisitSchedulingApprovalView(
                view, 71L, false, 4207L, Instant.parse("2026-10-18T04:30:00Z"));

        ObjectNode receiptJson = mapper.valueToTree(historical);
        ObjectNode sessionJson = (ObjectNode) receiptJson.path("session");
        assertTrue(sessionJson.has("linkedRequestTimingFacts"), "fixture starts from the additive field");
        sessionJson.remove("linkedRequestTimingFacts");
        sessionJson.put("status", "SCHEDULED");
        sessionJson.put("scheduledAt", "2027-02-14T05:00:00Z");
        sessionJson.put("reservedEndAt", "2027-02-14T07:00:00Z");
        sessionJson.put("durationMinutes", 120);
        sessionJson.put("zoneId", "Asia/Kolkata");
        sessionJson.put("representativeUserId", 4207);
        sessionJson.put("assignedAt", "2026-10-18T04:30:00Z");
        sessionJson.put("tenantConfirmationState", "PENDING");
        String raw = mapper.writeValueAsString(receiptJson);
        assertFalse(raw.contains("linkedRequestTimingFacts"));

        String canonical = mapper.writeValueAsString(command);
        jdbc.update("insert into visit_scheduling_commands"
                        + "(actor_user_id,command_id,session_id,operation,canonical_payload,response_json) "
                        + "values (?,?,?,?,?,?)",
                oe.getId(), commandId, session.getId(), "OE_SELECTION", canonical, raw);

        String rawBefore = jdbc.queryForObject(
                "select response_json from visit_scheduling_commands where actor_user_id=? and command_id=?",
                String.class, oe.getId(), commandId);
        Map<String, Object> countsBefore = sideEffectCounts();

        var read = operations.schedulingCommand(oe.getId(), session.getId(), commandId);
        assertEquals("COMPLETED", read.state());
        assertNotNull(read.approval());
        assertEquals(71L, read.approval().decisionId());
        assertNull(read.approval().session().linkedRequestTimingFacts());
        assertEquals(VisitSessionStatus.SCHEDULED, read.approval().session().status());
        assertEquals(visitStatus(oe.getId(), session.getId()), VisitSessionStatus.DRAFT,
                "reading a historical receipt never mutates the current Session");

        VisitSchedulingApprovalView replay = new TransactionTemplate(transactions).execute(status -> {
            try {
                return receipts.begin(ownerId, sessionId, command);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertNotNull(replay);
        assertEquals(71L, replay.decisionId());
        assertNull(replay.session().linkedRequestTimingFacts());
        assertThrows(VisitOperationsConflictException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            try {
                return receipts.begin(ownerId, sessionId, new ApproveVisitRecommendationCommand(
                        view.version(), 4208L, scheduledAt, "Asia/Kolkata", null, commandId));
            } catch (VisitOperationsConflictException conflict) {
                throw conflict;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }));
        VisitSchedulingApprovalView foreign = new TransactionTemplate(transactions).execute(status ->
                receipts.find(fixture.nonowner().getId(), sessionId, commandId));
        assertNull(foreign, "a wrong actor can never read another actor’s receipt");

        jdbc.update("update visit_sessions set status='CANCELLED' where id=?", session.getId());
        var currentCancelled = operations.schedulingCommand(oe.getId(), session.getId(), commandId);
        assertEquals(VisitSessionStatus.SCHEDULED, currentCancelled.approval().session().status(),
                "the original acceptance remains the original acceptance");
        assertEquals(VisitSessionStatus.CANCELLED, visitStatus(oe.getId(), session.getId()),
                "current lifecycle truth remains separate");

        String rawAfter = jdbc.queryForObject(
                "select response_json from visit_scheduling_commands where actor_user_id=? and command_id=?",
                String.class, oe.getId(), commandId);
        assertEquals(raw, rawBefore);
        assertEquals(rawBefore, rawAfter);
        assertEquals(sha256(rawBefore), sha256(rawAfter));
        assertTrue(rawAfter.contains("\"decisionId\":71"));
        assertFalse(rawAfter.contains("linkedRequestTimingFacts"));
        assertEquals(countsBefore, sideEffectCounts(),
                "read/replay creates no reservation, credit, decision or outbox row");
    }

    private Map<String, Object> sideEffectCounts() {
        return jdbc.queryForMap("select "
                + "(select count(*) from visit_scheduling_decisions) as decisions, "
                + "(select count(*) from visit_notification_outbox) as outbox, "
                + "(select count(*) from visit_entitlement_ledger) as credits");
    }

    private VisitSessionStatus visitStatus(long actorId, long sessionId) {
        return operations.getOperationsSession(actorId, sessionId).status();
    }

    private Fixture fixture() {
        SupportedCity city = cities.saveAndFlush(new SupportedCity(
                ("receipt-city-" + UUID.randomUUID().toString().substring(0, 6)).toLowerCase(),
                "Receipt City", true));
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city,
                ("receipt-team-" + UUID.randomUUID().toString().substring(0, 6)).toLowerCase(),
                "Receipt Team", true));
        Locality locality = new Locality(city.getDisplayName(), "receipt sector " + UUID.randomUUID(),
                null, null, null);
        locality.setSupportedCity(city);
        locality = localities.saveAndFlush(locality);
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Receipt listing " + UUID.randomUUID());
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Synthetic receipt address");
        listing.setSector(locality.getSectorName());
        listing.setCity(locality.getCity());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setLocationResolution(LocationResolution.CANONICAL);
        listing.setLatitude(22.72);
        listing.setLongitude(75.88);
        listing.setBhkCount("2BHK");
        listing.setMonthlyRent(BigDecimal.valueOf(20000));
        listing.setSecurityDeposit(BigDecimal.valueOf(20000));
        listings.saveAndFlush(listing);
        User tenant = users.saveAndFlush(user("receipt-tenant", Role.ROLE_TENANT));
        User owner = users.saveAndFlush(user("receipt-owner", Role.ROLE_TENANT));
        User nonowner = users.saveAndFlush(user("receipt-nonowner", Role.ROLE_TENANT));
        EmployeeProfile profile = employees.saveAndFlush(new EmployeeProfile(owner, "OPERATIONS", null, BigDecimal.ZERO));
        profile.setStaffActive(true);
        employees.saveAndFlush(profile);
        grants.saveAndFlush(new StaffAccessGrant(owner, StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team, Instant.now().minusSeconds(60), null, null,
                Instant.now(), "RECEIPT_HISTORY_FIXTURE", StaffGrantProvisioningSource.ADMIN_API));
        return new Fixture(city, team, tenant, owner, nonowner);
    }

    private User user(String prefix, Role role) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName("Synthetic " + prefix);
        user.setPhoneNumber(null);
        user.setRole(role);
        return user;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record Fixture(SupportedCity city, OperatingTeam team, User tenant, User owner, User nonowner) {}
}
