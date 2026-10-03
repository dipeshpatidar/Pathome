package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ScheduleVisitSessionCommand;
import com.indore.pathome.spaces.dto.RecommendationRequest;
import com.indore.pathome.spaces.dto.RecommendationStatus;
import com.indore.pathome.spaces.dto.ApproveVisitRecommendationCommand;
import com.indore.pathome.spaces.dto.VisitOtpStartCommand;
import com.indore.pathome.spaces.dto.RecordVisitSessionItemOutcomeCommand;
import com.indore.pathome.spaces.dto.CompleteVisitSessionWithOutcomesCommand;
import com.indore.pathome.spaces.dto.CorrectVisitOutcomeCommand;
import com.indore.pathome.spaces.dto.OperationsVisitOutcomeDetailView;
import com.indore.pathome.spaces.dto.GroundVisitContactCommand;
import com.indore.pathome.spaces.dto.GroundVisitMoreTimeCommand;
import com.indore.pathome.spaces.dto.RescheduleVisitSessionCommand;
import com.indore.pathome.spaces.dto.VisitEntitlementRestoreCommand;
import com.indore.pathome.spaces.dto.TenantVisitConfirmationCommand;
import com.indore.pathome.spaces.dto.ExpectedVisitSessionVersion;
import com.indore.pathome.spaces.config.VisitExecutionProperties;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.service.field.FieldResourceKey;
import com.indore.pathome.spaces.service.field.LocationAssessment;
import com.indore.pathome.spaces.service.field.LocationSnapshot;
import com.indore.pathome.spaces.service.field.LocationSnapshotProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({VisitOperationsService.class, VisitOperationsAuthorizationService.class,
        VisitSessionNotificationListener.class, NotificationService.class, VisitSchedulingRecommendationService.class,
        ConservativeTravelTimeEstimator.class, SchedulingRecommendationPolicy.class,
        VisitExecutionService.class, VisitExecutionProperties.class, VisitEntitlementStore.class,
        VisitSessionOutcomeService.class,
        VisitSessionOutcomeCompletionService.class,
        VisitEntitlementOperationsService.class, VisitNoShowSettlementWorker.class,
        VisitOtpCrypto.class, InAppVisitOtpDeliveryProvider.class, VisitNotificationOutboxWorker.class,
        VisitRepairOperationsService.class})
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class VisitOperationsPostgresIntegrationTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "visit_ops_test_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        String schemaUrl = BASE_URL + (BASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("pathome.visit.otp.hmac-secret", () -> "postgres-test-only-secret-value-32-bytes-minimum");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Autowired private VisitOperationsService operations;
    @Autowired private VisitSessionRepository sessions;
    @Autowired private UserRepository users;
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private ListingRepository listings;
    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private VisitSessionItemRepository items;
    @Autowired private SystemNotificationRepository notifications;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VisitSchedulingDecisionRepository decisions;
    @Autowired private VisitSchedulingRecommendationService recommendations;
    @Autowired private VisitExecutionService execution;
    @Autowired private VisitSessionOutcomeService outcomeService;
    @Autowired private VisitExecutionProperties executionProperties;
    @Autowired private VisitSessionOutcomeCompletionService outcomeCompletionService;
    @Autowired private VisitEntitlementStore entitlements;
    @Autowired private VisitEntitlementOperationsService entitlementOperations;
    @Autowired private VisitRepairOperationsService repairOperations;
    @Autowired private VisitNoShowSettlementWorker noShowSettlementWorker;
    @Autowired private VisitNotificationOutboxWorker outboxWorker;
    @Autowired private GroundExecutiveSchedulingProfileRepository schedulingProfiles;
    @Autowired private GroundExecutiveCoverageRepository coverage;
    @Autowired private GroundExecutiveShiftRepository shifts;
    @Autowired private GroundExecutiveUnavailabilityRepository unavailability;
    @Autowired private LocalityRepository localities;
    @Autowired private VisitPolicyRepository visitPolicies;
    @Autowired private SchedulingRecommendationPolicy recommendationPolicy;
    @Autowired private ConservativeTravelTimeEstimator travelEstimator;
    @MockBean private LocationSnapshotProvider locations;

    @BeforeEach
    void enableDatabaseOverlapConstraint() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");
        jdbc.execute("ALTER TABLE visit_sessions DROP CONSTRAINT IF EXISTS ex_visit_session_ge_reservation_overlap");
        jdbc.execute("ALTER TABLE visit_sessions ADD CONSTRAINT ex_visit_session_ge_reservation_overlap "
                + "EXCLUDE USING gist (representative_user_id WITH =, "
                + "tstzrange(scheduled_at, reserved_end_at, '[)') WITH &&) "
                + "WHERE (status IN ('SCHEDULED', 'STARTED'))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tenant_visit_entitlement_accounts (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL UNIQUE REFERENCES users(id), available_credits INTEGER NOT NULL, reserved_credits INTEGER NOT NULL DEFAULT 0, reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS visit_entitlement_ledger (id BIGSERIAL PRIMARY KEY, account_id BIGINT NOT NULL REFERENCES tenant_visit_entitlement_accounts(id), user_id BIGINT NOT NULL REFERENCES users(id), session_id BIGINT REFERENCES visit_sessions(id), event_type VARCHAR(32) NOT NULL, available_delta INTEGER NOT NULL, reserved_delta INTEGER NOT NULL, actor_user_id BIGINT REFERENCES users(id), reason_code VARCHAR(64), idempotency_key VARCHAR(160) NOT NULL UNIQUE, occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS visit_start_challenges (id BIGSERIAL PRIMARY KEY, session_id BIGINT NOT NULL UNIQUE REFERENCES visit_sessions(id), tenant_id BIGINT NOT NULL REFERENCES users(id), ground_executive_user_id BIGINT NOT NULL REFERENCES users(id), generation INTEGER NOT NULL, key_id VARCHAR(64) NOT NULL, digest BYTEA NOT NULL, issued_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL, next_issue_allowed_at TIMESTAMPTZ NOT NULL, consumed_at TIMESTAMPTZ, invalidated_at TIMESTAMPTZ, failed_attempts INTEGER NOT NULL DEFAULT 0, locked_until TIMESTAMPTZ, version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS visit_execution_events (id BIGSERIAL PRIMARY KEY, session_id BIGINT NOT NULL REFERENCES visit_sessions(id), actor_user_id BIGINT REFERENCES users(id), event_type VARCHAR(40) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, reason_code VARCHAR(64), metadata JSONB NOT NULL DEFAULT '{}'::jsonb, idempotency_key VARCHAR(160) NOT NULL UNIQUE)");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentOverlappingApprovalsForOneGeAllowOnlyOneCommit() throws Exception {
        Actors actors = actors();
        Long firstSessionId = draftSession(actors, "Concurrency A");
        Long secondSessionId = draftSession(actors, "Concurrency B");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> approve(actors, firstSessionId,
                    Instant.parse("2099-10-02T11:00:00Z"), ready, start));
            var second = executor.submit(() -> approve(actors, secondSessionId,
                    Instant.parse("2099-10-02T11:15:00Z"), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> results = new ArrayList<>(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
            assertEquals(1, results.stream().filter("COMMITTED"::equals).count());
            assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, sessions.countByRepresentativeIdAndStatus(actors.ground().getId(), VisitSessionStatus.SCHEDULED));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void adjacentReservationCanCommitAndCancellationReleasesInterval() {
        Actors actors = actors();
        Long firstSessionId = draftSession(actors, "Adjacent A");
        Long secondSessionId = draftSession(actors, "Adjacent B");
        Instant firstStart = Instant.parse("2099-10-03T11:00:00Z");
        operations.schedule(actors.admin().getId(), firstSessionId,
                new ScheduleVisitSessionCommand(0L, firstStart, "Asia/Kolkata", actors.ground().getId(), 30));
        operations.schedule(actors.admin().getId(), secondSessionId,
                new ScheduleVisitSessionCommand(0L, firstStart.plusSeconds(30 * 60L), "Asia/Kolkata", actors.ground().getId(), 30));
        assertEquals(2, sessions.countByRepresentativeIdAndStatus(actors.ground().getId(), VisitSessionStatus.SCHEDULED));

        Long thirdSessionId = draftSession(actors, "Released interval");
        VisitSession second = sessions.findById(secondSessionId).orElseThrow();
        operations.cancel(actors.admin().getId(), secondSessionId, second.getVersion());
        operations.schedule(actors.admin().getId(), thirdSessionId,
                new ScheduleVisitSessionCommand(0L, firstStart.plusSeconds(30 * 60L), "Asia/Kolkata", actors.ground().getId(), 30));
        assertEquals(2, sessions.countByRepresentativeIdAndStatus(actors.ground().getId(), VisitSessionStatus.SCHEDULED));
        assertEquals(Instant.parse("2099-10-03T12:00:00Z"), sessions.findById(secondSessionId).orElseThrow().getReservedEndAt());
    }

    @Test
    void reservationProjectionExposesExistingPackage2bBookingFields() {
        Actors actors = actors();
        Long sessionId = draftSession(actors, "Reservation projection");
        Instant start = Instant.parse("2099-10-05T11:00:00Z");
        operations.schedule(actors.admin().getId(), sessionId,
                new ScheduleVisitSessionCommand(0L, start, "Asia/Kolkata", actors.ground().getId(), 45));

        List<com.indore.pathome.spaces.dto.GroundExecutiveReservation> reservations =
                sessions.findActiveReservationsOverlapping(actors.ground().getId(),
                        List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED),
                        start.plusSeconds(10 * 60L), start.plusSeconds(60 * 60L));

        assertEquals(1, reservations.size());
        assertEquals(sessionId, reservations.get(0).sessionId());
        assertEquals(actors.ground().getId(), reservations.get(0).groundExecutiveUserId());
        assertEquals(start, reservations.get(0).reservedStartAt());
        assertEquals(start.plusSeconds(45 * 60L), reservations.get(0).reservedEndAt());
        assertEquals(VisitSessionStatus.SCHEDULED, reservations.get(0).status());
        assertTrue(sessions.findActiveReservationsOverlapping(actors.ground().getId(),
                List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED),
                start.plusSeconds(45 * 60L), start.plusSeconds(60 * 60L)).isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void realScheduleTransactionRollbackSuppressesNotificationsAndCommitPersistsThem() {
        Actors actors = actors();
        Long sessionId = draftSession(actors, "Transactional notification");
        Instant scheduledAt = Instant.parse("2099-10-04T11:00:00Z");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            operations.schedule(actors.admin().getId(), sessionId,
                    new ScheduleVisitSessionCommand(0L, scheduledAt, "Asia/Kolkata", actors.ground().getId(), 30));
            throw new IllegalStateException("rollback booking after event publication");
        }));

        String tenantEventKey = "VISIT_SESSION_SCHEDULED:" + sessionId + ":v1:" + actors.tenant().getId();
        String groundEventKey = "VISIT_SESSION_ASSIGNED:" + sessionId + ":v1:" + actors.ground().getId();
        assertEquals(VisitSessionStatus.DRAFT, sessions.findById(sessionId).orElseThrow().getStatus());
        assertTrue(notifications.findByEventKey(tenantEventKey).isEmpty());
        assertTrue(notifications.findByEventKey(groundEventKey).isEmpty());

        operations.schedule(actors.admin().getId(), sessionId,
                new ScheduleVisitSessionCommand(0L, scheduledAt, "Asia/Kolkata", actors.ground().getId(), 30));
        assertEquals(VisitSessionStatus.SCHEDULED, sessions.findById(sessionId).orElseThrow().getStatus());
        assertTrue(notifications.findByEventKey(tenantEventKey).isPresent());
        assertTrue(notifications.findByEventKey(groundEventKey).isPresent());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void afterCommitNotificationIsAbsentOnRollbackAndPresentOnceOnCommit() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String eventKey = "VISIT_SESSION_SCHEDULED:99001:v1:99002";
        VisitSessionNotificationEvent event = new VisitSessionNotificationEvent(
                VisitSessionNotificationEvent.Type.SCHEDULED, 99001L, 99002L, 99003L, null, 1L,
                Instant.parse("2099-10-04T11:00:00Z"), "Asia/Kolkata");

        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            events.publishEvent(event);
            throw new IllegalStateException("rollback test");
        }));
        assertTrue(notifications.findByEventKey(eventKey).isEmpty());

        tx.executeWithoutResult(status -> events.publishEvent(event));
        tx.executeWithoutResult(status -> events.publishEvent(event));
        assertTrue(notifications.findByEventKey(eventKey).isPresent());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM system_notifications WHERE event_key = ?", Integer.class, eventKey));

        VisitSessionNotificationEvent rescheduled = new VisitSessionNotificationEvent(
                VisitSessionNotificationEvent.Type.RESCHEDULED, 99001L, 99002L, 99003L, null, 2L,
                Instant.parse("2099-10-04T12:00:00Z"), "Asia/Kolkata");
        VisitSessionNotificationEvent cancelled = new VisitSessionNotificationEvent(
                VisitSessionNotificationEvent.Type.CANCELLED, 99001L, 99002L, 99003L, null, 3L, null, null);
        VisitSessionNotificationEvent reassigned = new VisitSessionNotificationEvent(
                VisitSessionNotificationEvent.Type.REASSIGNED, 99001L, 99002L, 99004L, 99003L, 4L,
                Instant.parse("2099-10-04T12:00:00Z"), "Asia/Kolkata");
        VisitSessionNotificationEvent itineraryChanged = new VisitSessionNotificationEvent(
                VisitSessionNotificationEvent.Type.ITINERARY_CHANGED, 99001L, 99002L, 99004L, null, 5L,
                Instant.parse("2099-10-04T12:00:00Z"), "Asia/Kolkata");
        tx.executeWithoutResult(status -> events.publishEvent(rescheduled));
        tx.executeWithoutResult(status -> events.publishEvent(cancelled));
        tx.executeWithoutResult(status -> events.publishEvent(reassigned));
        tx.executeWithoutResult(status -> events.publishEvent(itineraryChanged));

        assertTrue(notifications.findByEventKey("VISIT_SESSION_RESCHEDULED:99001:v2:99003").isPresent());
        assertTrue(notifications.findByEventKey("VISIT_SESSION_CANCELLED:99001:v3:99003").isPresent());
        assertTrue(notifications.findByEventKey("VISIT_SESSION_ASSIGNED:99001:v4:99004").isPresent());
        assertTrue(notifications.findByEventKey("VISIT_SESSION_REASSIGNED_FROM:99001:v4:99003").isPresent());
        assertTrue(notifications.findByEventKey("VISIT_SESSION_ITINERARY_CHANGED:99001:v5:99004").isPresent());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void recommendationPrefersFeasibleItineraryAndApprovalPersistsDecisionWithBooking() {
        RecommendationScenario scenario = recommendationScenario(true);
        when(locations.latestFor(any(), any()))
                .thenReturn(java.util.Optional.empty());
        when(locations.latestFor(eq(new FieldResourceKey("INTERNAL_GE", scenario.geA().getId().toString())), any()))
                .thenReturn(java.util.Optional.of(new LocationSnapshot(22.72, 75.88,
                        Instant.now().minusSeconds(10), Instant.now(), 8.0, LocationAssessment.USABLE, "FRESH")));

        var recommendation = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));
        var repeatedRecommendation = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));
        assertEquals(recommendation.candidates(), repeatedRecommendation.candidates());

        assertTrue(recommendation.status() == RecommendationStatus.RECOMMENDATION_AVAILABLE
                || recommendation.status() == RecommendationStatus.TRAVEL_ESTIMATE_UNCERTAIN
                || recommendation.status() == RecommendationStatus.LOCATION_UNAVAILABLE_FALLBACK_USED,
                recommendation.toString());
        assertFalse(recommendation.candidates().isEmpty());
        assertEquals(scenario.geB().getId(), recommendation.candidates().get(0).groundExecutiveUserId());
        assertEquals(scenario.desiredAt(), recommendation.candidates().get(0).scheduledAt());
        assertEquals("Asia/Kolkata", recommendation.candidates().get(0).zoneId());
        assertEquals("FEASIBLE", recommendation.candidates().get(0).feasibilityStatus());
        assertTrue(recommendation.candidates().get(0).reasons().contains(
                com.indore.pathome.spaces.dto.RecommendationReason.LOCATION_UNAVAILABLE_FALLBACK_USED));
        assertTrue(recommendation.rejectedGroundExecutives().stream()
                .anyMatch(rejected -> rejected.groundExecutiveUserId().equals(scenario.geA().getId())
                        && rejected.feasibilityStatus().equals("INELIGIBLE")
                        && rejected.reasons().contains(
                                com.indore.pathome.spaces.dto.RecommendationRejectionReason.FIRST_LEG_TRAVEL_INFEASIBLE)));

        var approval = operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                new ApproveVisitRecommendationCommand(0L, scenario.geB().getId(), scenario.desiredAt(),
                        "Asia/Kolkata", null));

        assertEquals(VisitSessionStatus.SCHEDULED, approval.session().status());
        assertFalse(approval.override());
        assertEquals(scenario.geB().getId(), sessions.findById(scenario.sessionId()).orElseThrow()
                .getRepresentative().getId());
        VisitSchedulingDecision decision = decisions.findById(approval.decisionId()).orElseThrow();
        assertEquals(scenario.geB().getId(), decision.getRecommendedGroundExecutive().getId());
        assertEquals(scenario.geB().getId(), decision.getSelectedGroundExecutive().getId());
        assertFalse(decision.isOverride());
        assertNull(decision.getOverrideReason());
        assertEquals(1, decisions.countBySessionId(scenario.sessionId()));
        assertTrue(notifications.findByEventKey("VISIT_SESSION_SCHEDULED:" + scenario.sessionId()
                + ":v1:" + scenario.tenant().getId()).isPresent());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void liveRepairEvaluatorAcceptsOnlyTimesInsideTenantPropertyAndShiftFeasibility() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var recommendation = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));
        assertEquals(RecommendationStatus.LOCATION_UNAVAILABLE_FALLBACK_USED, recommendation.status());
        assertFalse(recommendation.candidates().isEmpty(), "missing live GPS must use conservative scheduling fallback");
        var planned = recommendation.candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geB().getId()))
                .findFirst().orElseThrow();
        Instant safeShift = planned.scheduledAt().plusSeconds(5 * 60L);
        Instant outsideTenantWindow = scenario.desiredAt().plusSeconds(75 * 60L);
        VisitSession scheduled = new TransactionTemplate(transactionManager).execute(status -> {
            VisitSession session = sessions.findLockedById(scenario.sessionId()).orElseThrow();
            for (PropertyVisitRequest request : requests.findLockedBySessionIdOrderByIdAsc(session.getId()))
                request.setStatus(VisitRequestStatus.SCHEDULED);
            session.setStatus(VisitSessionStatus.SCHEDULED);
            session.setScheduledAt(planned.scheduledAt());
            session.setZoneId(planned.zoneId());
            session.setRepresentative(users.getReferenceById(scenario.geB().getId()));
            session.setAssignedAt(Instant.now());
            session.setDurationSnapshotMinutes(planned.durationMinutes());
            session.setReservedEndAt(planned.reservedEndAt());
            return sessions.saveAndFlush(session);
        });

        var feasible = recommendations.assessLiveRepair(scheduled, List.of(safeShift), true);
        assertTrue(feasible.stream().anyMatch(candidate -> candidate.geId().equals(scenario.geB().getId())
                && candidate.start().equals(safeShift)));
        assertTrue(recommendations.assessLiveRepair(scheduled, List.of(outsideTenantWindow), true).stream()
                .noneMatch(candidate -> candidate.start().equals(outsideTenantWindow)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void liveRepairEvaluatorFindsAlternateGeAtConfirmedTimeWhenOriginalGeConflicts() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var planned = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        VisitSession repair = new TransactionTemplate(transactionManager).execute(status -> {
            VisitSession session = sessions.findLockedById(scenario.sessionId()).orElseThrow();
            for (PropertyVisitRequest request : requests.findLockedBySessionIdOrderByIdAsc(session.getId()))
                request.setStatus(VisitRequestStatus.SCHEDULED);
            session.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
            session.setRepairState("REQUIRED");
            session.setScheduledAt(planned.scheduledAt());
            session.setZoneId(planned.zoneId());
            session.setRepresentative(users.getReferenceById(scenario.geA().getId()));
            session.setAssignedAt(Instant.now());
            session.setDurationSnapshotMinutes(planned.durationMinutes());
            session.setReservedEndAt(planned.reservedEndAt());
            return sessions.saveAndFlush(session);
        });
        createNeighborReservation(scenario, scenario.geA(), "Original GE conflict", 22.72, 75.88,
                planned.scheduledAt(), planned.durationMinutes(), VisitSessionStatus.SCHEDULED);

        var candidates = recommendations.assessLiveRepair(repair, List.of(planned.scheduledAt()), true);

        assertFalse(candidates.stream().anyMatch(candidate -> candidate.geId().equals(scenario.geA().getId())
                && candidate.start().equals(planned.scheduledAt())));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.geId().equals(scenario.geB().getId())
                && candidate.start().equals(planned.scheduledAt())));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repairRecommendationPreviewIsReadOnlyForCandidatesEmptyResultsAndFailures() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var original = operations.recommend(scenario.admin().getId(), scenario.sessionId(), new RecommendationRequest(0L))
                .candidates().get(0);
        makeRepairRequired(scenario, original);
        VisitSession repair = sessions.findById(scenario.sessionId()).orElseThrow();
        long queuedBefore = repairOperations.list(scenario.admin().getId(), 0, 100).totalElements();

        var preview = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(repair.getVersion()));
        assertFalse(preview.candidates().isEmpty());
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertTrue(entitlements.hasReservation(scenario.sessionId()));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            schedulingProfiles.findLockedByUserId(scenario.geA().getId()).orElseThrow().setSchedulingActive(false);
            schedulingProfiles.findLockedByUserId(scenario.geB().getId()).orElseThrow().setSchedulingActive(false);
        });
        var empty = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(repair.getVersion()));
        assertTrue(empty.candidates().isEmpty());
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertThrows(VisitOperationsConflictException.class, () -> operations.recommend(scenario.admin().getId(),
                scenario.sessionId(), new RecommendationRequest(repair.getVersion() - 1)));
        var queue = repairOperations.list(scenario.admin().getId(), 0, 100);
        assertEquals(queuedBefore, queue.totalElements());
        assertTrue(queue.items().stream().anyMatch(item -> item.sessionId().equals(scenario.sessionId())));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RESERVE'",
                Integer.class, scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RELEASE'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repairApprovalIsAtomicAndStaleApprovalKeepsCaseAndHold() {
        RecommendationScenario staleScenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var staleInitial = operations.recommend(staleScenario.admin().getId(), staleScenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        makeRepairRequired(staleScenario, staleInitial);
        VisitSession stale = sessions.findById(staleScenario.sessionId()).orElseThrow();
        var stalePreview = operations.recommend(staleScenario.admin().getId(), staleScenario.sessionId(),
                new RecommendationRequest(stale.getVersion())).candidates().get(0);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> schedulingProfiles
                .findLockedByUserId(stalePreview.groundExecutiveUserId()).orElseThrow().setSchedulingActive(false));
        assertThrows(VisitOperationsConflictException.class, () -> operations.approveRecommendation(
                staleScenario.admin().getId(), staleScenario.sessionId(), new ApproveVisitRecommendationCommand(
                        stale.getVersion(), stalePreview.groundExecutiveUserId(), stalePreview.scheduledAt(),
                        stalePreview.zoneId(), null)));
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, sessions.findById(staleScenario.sessionId()).orElseThrow().getStatus());
        assertTrue(entitlements.hasReservation(staleScenario.sessionId()));

        RecommendationScenario successScenario = recommendationScenario(false);
        var successInitial = operations.recommend(successScenario.admin().getId(), successScenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        makeRepairRequired(successScenario, successInitial);
        VisitSession pendingRepair = sessions.findById(successScenario.sessionId()).orElseThrow();
        var option = operations.recommend(successScenario.admin().getId(), successScenario.sessionId(),
                new RecommendationRequest(pendingRepair.getVersion())).candidates().get(0);
        operations.approveRecommendation(successScenario.admin().getId(), successScenario.sessionId(),
                new ApproveVisitRecommendationCommand(pendingRepair.getVersion(), option.groundExecutiveUserId(),
                        option.scheduledAt(), option.zoneId(), null));
        VisitSession booked = sessions.findById(successScenario.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.SCHEDULED, booked.getStatus());
        assertEquals("NONE", booked.getRepairState());
        assertEquals("PENDING", booked.getTenantConfirmationState());
        assertTrue(entitlements.hasReservation(successScenario.sessionId()));
        assertTrue(repairOperations.list(successScenario.admin().getId(), 0, 100).items().stream()
                .noneMatch(item -> item.sessionId().equals(successScenario.sessionId())));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void proposedRescheduleAcceptanceRevalidatesAndGenericConfirmCannotStrandProposal() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var initialRecommendations = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));
        var original = initialRecommendations.candidates().get(0);
        Instant replacementAt = original.scheduledAt().plus(Duration.ofMinutes(1));
        scheduleForExecution(scenario, users.findById(original.groundExecutiveUserId()).orElseThrow(),
                original.scheduledAt(), original.durationMinutes(), false);
        assertTrue(entitlements.reserve(scenario.tenant().getId(), scenario.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        VisitSession confirmedBooking = sessions.findById(scenario.sessionId()).orElseThrow();
        operations.reschedule(scenario.admin().getId(), scenario.sessionId(), new RescheduleVisitSessionCommand(
                confirmedBooking.getVersion(), replacementAt, original.zoneId()));
        VisitSession proposed = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals("PENDING", proposed.getTenantConfirmationState());
        assertEquals("NONE", proposed.getRepairState());
        UUID operationId = UUID.randomUUID();
        TenantVisitConfirmationCommand accept = new TenantVisitConfirmationCommand("ACCEPT_RESCHEDULE", null,
                operationId, proposed.getVersion());

        assertThrows(VisitOperationsConflictException.class, () -> execution.confirmTenant(scenario.tenant().getId(),
                scenario.sessionId(), new TenantVisitConfirmationCommand("CONFIRM", null, UUID.randomUUID(), proposed.getVersion())));
        VisitSession stillProposed = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals("PENDING", stillProposed.getTenantConfirmationState());
        assertEquals("NONE", stillProposed.getRepairState());
        var accepted = execution.confirmTenant(scenario.tenant().getId(), scenario.sessionId(), accept);
        assertEquals("CONFIRMED", accepted.tenantConfirmationState());
        assertEquals("NONE", accepted.repairState());
        execution.confirmTenant(scenario.tenant().getId(), scenario.sessionId(), accept);
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "TENANT_CONFIRMATION:" + scenario.sessionId() + ":" + operationId));
        assertTrue(entitlements.hasReservation(scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RESERVE'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void operationsRescheduleCannotBeGenericConfirmedWhenItBecomesInfeasible() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var recommendationsView = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));
        var original = recommendationsView.candidates().get(0);
        Instant replacementAt = original.scheduledAt().plus(Duration.ofMinutes(1));
        scheduleForExecution(scenario, users.findById(original.groundExecutiveUserId()).orElseThrow(),
                original.scheduledAt(), original.durationMinutes(), false);
        assertTrue(entitlements.reserve(scenario.tenant().getId(), scenario.sessionId(),
                Instant.now().plus(Duration.ofDays(7))));
        VisitSession booked = sessions.findById(scenario.sessionId()).orElseThrow();
        operations.reschedule(scenario.admin().getId(), scenario.sessionId(), new RescheduleVisitSessionCommand(
                booked.getVersion(), replacementAt, original.zoneId()));
        VisitSession pending = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals("PENDING", pending.getTenantConfirmationState());

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                schedulingProfiles.findLockedByUserId(original.groundExecutiveUserId()).orElseThrow()
                        .setSchedulingActive(false));
        assertThrows(VisitOperationsConflictException.class, () -> execution.confirmTenant(scenario.tenant().getId(),
                scenario.sessionId(), new TenantVisitConfirmationCommand("CONFIRM", null, UUID.randomUUID(), pending.getVersion())));
        VisitSession stillPending = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals("PENDING", stillPending.getTenantConfirmationState());
        var rejected = execution.confirmTenant(scenario.tenant().getId(), scenario.sessionId(),
                new TenantVisitConfirmationCommand("ACCEPT_RESCHEDULE", null, UUID.randomUUID(), stillPending.getVersion()));
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, rejected.status());
        assertEquals("PENDING", rejected.tenantConfirmationState());
        assertTrue(entitlements.hasReservation(scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RESERVE'",
                Integer.class, scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RELEASE'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void genericConfirmStillWorksWithoutPendingAppointmentProposal() {
        RecommendationScenario scenario = recommendationScenario(false);
        var candidate = operations.recommend(scenario.admin().getId(), scenario.sessionId(), new RecommendationRequest(0L))
                .candidates().get(0);
        scheduleForExecution(scenario, users.findById(candidate.groundExecutiveUserId()).orElseThrow(),
                candidate.scheduledAt(), candidate.durationMinutes(), false);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            VisitSession session = sessions.findLockedById(scenario.sessionId()).orElseThrow();
            session.setTenantConfirmationState("NOT_REQUIRED");
            session.setTenantConfirmedAt(null);
            session.setTenantConfirmedBy(null);
            sessions.saveAndFlush(session);
        });
        VisitSession current = sessions.findById(scenario.sessionId()).orElseThrow();
        var confirmed = execution.confirmTenant(scenario.tenant().getId(), scenario.sessionId(),
                new TenantVisitConfirmationCommand("CONFIRM", null, UUID.randomUUID(), current.getVersion()));
        assertEquals("CONFIRMED", confirmed.tenantConfirmationState());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiredAndChangedProposalCannotBeConfirmedAndKeepsItsHold() {
        RecommendationScenario expiredScenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var expiredCandidate = operations.recommend(expiredScenario.admin().getId(), expiredScenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        User expiredGe = users.findById(expiredCandidate.groundExecutiveUserId()).orElseThrow();
        scheduleForExecution(expiredScenario, expiredGe, expiredCandidate.scheduledAt(),
                expiredCandidate.durationMinutes(), false);
        assertTrue(entitlements.reserve(expiredScenario.tenant().getId(), expiredScenario.sessionId(),
                Instant.now().plus(Duration.ofDays(7))));
        VisitSession expiredProposal = markProposalPending(expiredScenario.sessionId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            VisitSession session = sessions.findLockedById(expiredScenario.sessionId()).orElseThrow();
            Instant start = Instant.now().minus(Duration.ofHours(2));
            session.setScheduledAt(start);
            session.setReservedEndAt(start.plus(Duration.ofMinutes(session.getDurationSnapshotMinutes())));
            sessions.saveAndFlush(session);
        });
        VisitSession refreshedExpiredProposal = sessions.findById(expiredScenario.sessionId()).orElseThrow();
        var expired = execution.confirmTenant(expiredScenario.tenant().getId(), expiredScenario.sessionId(),
                new TenantVisitConfirmationCommand("ACCEPT_RESCHEDULE", null, UUID.randomUUID(), refreshedExpiredProposal.getVersion()));
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, expired.status());
        assertEquals("PENDING", expired.tenantConfirmationState());
        assertTrue(entitlements.hasReservation(expiredScenario.sessionId()));

        RecommendationScenario unavailableScenario = recommendationScenario(false);
        var unavailableCandidate = operations.recommend(unavailableScenario.admin().getId(), unavailableScenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        scheduleForExecution(unavailableScenario, users.findById(unavailableCandidate.groundExecutiveUserId()).orElseThrow(),
                unavailableCandidate.scheduledAt(), unavailableCandidate.durationMinutes(), false);
        assertTrue(entitlements.reserve(unavailableScenario.tenant().getId(), unavailableScenario.sessionId(),
                Instant.now().plus(Duration.ofDays(7))));
        VisitSession unavailableProposal = markProposalPending(unavailableScenario.sessionId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> schedulingProfiles
                .findLockedByUserId(unavailableCandidate.groundExecutiveUserId()).orElseThrow().setSchedulingActive(false));
        var unavailable = execution.confirmTenant(unavailableScenario.tenant().getId(), unavailableScenario.sessionId(),
                new TenantVisitConfirmationCommand("ACCEPT_RESCHEDULE", null, UUID.randomUUID(), unavailableProposal.getVersion()));
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, unavailable.status());
        assertEquals("PENDING", unavailable.tenantConfirmationState());
        assertTrue(entitlements.hasReservation(unavailableScenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void propertyWindowChangedAfterProposalCannotBeConfirmed() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var candidate = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        scheduleForExecution(scenario, users.findById(candidate.groundExecutiveUserId()).orElseThrow(),
                candidate.scheduledAt(), candidate.durationMinutes(), false);
        assertTrue(entitlements.reserve(scenario.tenant().getId(), scenario.sessionId(),
                Instant.now().plus(Duration.ofDays(7))));
        VisitSession proposal = markProposalPending(scenario.sessionId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (VisitSessionItem item : items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(scenario.sessionId()))
                item.setAvailabilityEndAt(candidate.scheduledAt().plus(Duration.ofMinutes(1)));
        });

        var result = execution.confirmTenant(scenario.tenant().getId(), scenario.sessionId(),
                new TenantVisitConfirmationCommand("ACCEPT_RESCHEDULE", null, UUID.randomUUID(), proposal.getVersion()));
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, result.status());
        assertEquals("PENDING", result.tenantConfirmationState());
        assertEquals("REQUIRED", result.repairState());
        assertTrue(entitlements.hasReservation(scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RELEASE'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentOtpStartIsOneLogicalStartAndConsumesOneEntitlement() throws Exception {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var planned = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                new ApproveVisitRecommendationCommand(0L, scenario.geA().getId(), planned.scheduledAt(),
                        "Asia/Kolkata", null));
        Instant lateStartSlot = Instant.now().minusSeconds(5 * 60L).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        scheduleForExecution(scenario, scenario.geA(), lateStartSlot, planned.durationMinutes(), true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        UUID operationId = UUID.randomUUID();
        VisitOtpStartCommand command = new VisitOtpStartCommand(code.generation(), code.code(), operationId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> start(ready, gate, scenario.geA().getId(), scenario.sessionId(), command));
            var second = executor.submit(() -> start(ready, gate, scenario.geA().getId(), scenario.sessionId(), command));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            gate.countDown();
            assertEquals("STARTED", first.get(15, TimeUnit.SECONDS));
            assertEquals("STARTED", second.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(VisitSessionStatus.STARTED,
                execution.start(scenario.geA().getId(), scenario.sessionId(), command).status());

        VisitSession started = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.STARTED, started.getStatus());
        assertEquals(operationId, started.getStartOperationId());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "CONSUME:" + scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where idempotency_key=?",
                Integer.class, "START:" + operationId));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_session_outcome_reports where session_id=? and state='OPEN'",
                Integer.class, scenario.sessionId()));
        assertEquals(jdbc.queryForObject("select count(*) from visit_session_items where session_id=? "
                        + "and removed_at is null and confirmation_status='CONFIRMED'", Integer.class, scenario.sessionId()),
                jdbc.queryForObject("select count(*) from visit_session_item_outcomes where session_id=?", Integer.class, scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? "
                        + "and event_type='OUTCOME_SCOPE_CAPTURED'", Integer.class, scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "VISIT_STARTED:" + scenario.sessionId() + ":" + operationId));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void outcomeScopeFailureRollsBackStartAndEntitlementConsumption() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var planned = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                new ApproveVisitRecommendationCommand(0L, scenario.geA().getId(), planned.scheduledAt(),
                        "Asia/Kolkata", null));
        Instant startAt = Instant.now().minusSeconds(5 * 60L).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        scheduleForExecution(scenario, scenario.geA(), startAt, planned.durationMinutes(), true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());

        jdbc.execute("CREATE FUNCTION fail_outcome_scope_insert() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced outcome-scope failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_outcome_scope BEFORE INSERT ON visit_session_item_outcomes "
                + "FOR EACH ROW EXECUTE FUNCTION fail_outcome_scope_insert()");
        try {
            assertThrows(RuntimeException.class, () -> execution.start(scenario.geA().getId(), scenario.sessionId(),
                    new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID())));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_outcome_scope ON visit_session_item_outcomes");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_outcome_scope_insert()");
        }

        assertEquals(VisitSessionStatus.SCHEDULED, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertTrue(entitlements.hasReservation(scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_session_outcome_reports where session_id=?",
                Integer.class, scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_session_item_outcomes where session_id=?",
                Integer.class, scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? "
                        + "and event_type in ('STARTED','OUTCOME_SCOPE_CAPTURED')", Integer.class, scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_start_challenges where session_id=? and consumed_at is null",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void itemOutcomeWritesAreIdempotentAndDirectFinishLeavesReportOpenUntilFinalized() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var planned = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                new ApproveVisitRecommendationCommand(0L, scenario.geA().getId(), planned.scheduledAt(),
                        "Asia/Kolkata", null));
        Instant startAt = Instant.now().minusSeconds(5 * 60L).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        scheduleForExecution(scenario, scenario.geA(), startAt, planned.durationMinutes(), true);
        addPreStartNonExecutionItems(scenario);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        assertEquals(VisitSessionStatus.STARTED, execution.start(scenario.geA().getId(), scenario.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID())).status());

        var initial = outcomeService.getGroundOutcomeReport(scenario.geA().getId(), scenario.sessionId());
        assertEquals(2, initial.items().size());
        assertTrue(initial.items().stream().allMatch(item -> item.outcome() == VisitSessionItemOutcomeState.UNRECORDED));
        var tenantPending = outcomeService.getTenantOutcomeReport(scenario.tenant().getId(), scenario.sessionId());
        assertEquals("IN_PROGRESS", tenantPending.lifecycle());
        assertNull(tenantPending.viewedProperties());
        assertTrue(tenantPending.properties().stream().allMatch(item -> "PENDING".equals(item.outcome())));
        var tenantHistory = outcomeService.listTenantOutcomeHistory(scenario.tenant().getId(), 0, 20);
        assertTrue(tenantHistory.sessions().stream().anyMatch(item -> item.sessionId().equals(scenario.sessionId())));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> outcomeService.getGroundOutcomeReport(scenario.geB().getId(), scenario.sessionId()));
        assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> outcomeService.recordItemOutcome(
                scenario.geB().getId(), scenario.sessionId(), initial.items().get(0).itemId(),
                new RecordVisitSessionItemOutcomeCommand(VisitSessionItemOutcomeState.VISITED,
                        null, null, initial.sessionVersion(), initial.reportVersion(),
                        initial.items().get(0).itemVersion(), UUID.randomUUID())));
        User otherTenant = user("outcome-other-tenant", Role.ROLE_TENANT);
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> outcomeService.getTenantOutcomeReport(otherTenant.getId(), scenario.sessionId()));
        assertFalse(outcomeService.listTenantOutcomeHistory(otherTenant.getId(), 0, 20).sessions().stream()
                .anyMatch(item -> item.sessionId().equals(scenario.sessionId())));
        var first = initial.items().get(0);
        String capturedTitle = first.title();
        Listing editedListing = items.findBySessionIdOrderByPositionAsc(scenario.sessionId()).stream()
                .filter(item -> item.getId().equals(first.itemId())).findFirst().orElseThrow().getListing();
        editedListing.setTitle("Changed after START");
        editedListing.setStatus(ListingStatus.CLOSED);
        listings.saveAndFlush(editedListing);
        assertEquals(2, jdbc.queryForObject("select count(*) from visit_session_item_outcomes where session_id=?",
                Integer.class, scenario.sessionId()));
        assertEquals(capturedTitle, outcomeService.getGroundOutcomeReport(scenario.geA().getId(), scenario.sessionId())
                .items().stream().filter(item -> item.itemId().equals(first.itemId())).findFirst().orElseThrow().title());
        var firstCommand = new RecordVisitSessionItemOutcomeCommand(VisitSessionItemOutcomeState.VISITED,
                null, null, initial.sessionVersion(), initial.reportVersion(), first.itemVersion(), UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> outcomeService.recordItemOutcome(
                scenario.geA().getId(), scenario.sessionId(), first.itemId(),
                new RecordVisitSessionItemOutcomeCommand(VisitSessionItemOutcomeState.SKIPPED, null, null,
                        initial.sessionVersion(), initial.reportVersion(), first.itemVersion(), UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class, () -> outcomeService.recordItemOutcome(
                scenario.geA().getId(), scenario.sessionId(), first.itemId(),
                new RecordVisitSessionItemOutcomeCommand(VisitSessionItemOutcomeState.SKIPPED,
                        VisitSessionItemSkipReason.OTHER, null, initial.sessionVersion(), initial.reportVersion(),
                        first.itemVersion(), UUID.randomUUID())));
        var afterFirst = outcomeService.recordItemOutcome(scenario.geA().getId(), scenario.sessionId(),
                first.itemId(), firstCommand);
        outcomeService.recordItemOutcome(scenario.geA().getId(), scenario.sessionId(), first.itemId(), firstCommand);
        assertThrows(VisitOperationsConflictException.class, () -> outcomeService.recordItemOutcome(
                scenario.geA().getId(), scenario.sessionId(), first.itemId(),
                new RecordVisitSessionItemOutcomeCommand(VisitSessionItemOutcomeState.SKIPPED,
                        VisitSessionItemSkipReason.TENANT_DECLINED, null, initial.sessionVersion(),
                        initial.reportVersion(), first.itemVersion(), firstCommand.operationId())));
        assertThrows(VisitOperationsConflictException.class, () -> outcomeService.recordItemOutcome(
                scenario.geA().getId(), scenario.sessionId(), first.itemId(),
                new RecordVisitSessionItemOutcomeCommand(VisitSessionItemOutcomeState.VISITED,
                        null, null, initial.sessionVersion(), initial.reportVersion(), first.itemVersion(), UUID.randomUUID())));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? "
                        + "and idempotency_key like ?", Integer.class, scenario.sessionId(),
                "ITEM_OUTCOME:" + scenario.sessionId() + ":" + first.itemId() + ":%"));

        var finished = execution.finish(scenario.geA().getId(), scenario.sessionId());
        assertEquals(VisitSessionStatus.COMPLETED, finished.status());
        assertEquals("OPEN", jdbc.queryForObject("select state from visit_session_outcome_reports where session_id=?",
                String.class, scenario.sessionId()));
        var pendingTenantView = outcomeService.getTenantOutcomeReport(scenario.tenant().getId(), scenario.sessionId());
        assertEquals("DETAILS_PENDING", pendingTenantView.lifecycle());
        assertEquals(2, pendingTenantView.properties().size());
        assertTrue(pendingTenantView.properties().stream().allMatch(item -> "PENDING".equals(item.outcome())));

        VisitSession currentSession = sessions.findById(scenario.sessionId()).orElseThrow();
        assertThrows(VisitOperationsConflictException.class, () -> outcomeService.finalizeReport(
                scenario.geA().getId(), scenario.sessionId(), currentSession.getVersion(),
                afterFirst.reportVersion(), UUID.randomUUID()));
        var second = afterFirst.items().stream().filter(item -> !item.itemId().equals(first.itemId())).findFirst().orElseThrow();
        var skipped = outcomeService.recordItemOutcome(scenario.geA().getId(), scenario.sessionId(), second.itemId(),
                new RecordVisitSessionItemOutcomeCommand(VisitSessionItemOutcomeState.SKIPPED,
                        VisitSessionItemSkipReason.OTHER, "Access was not available", currentSession.getVersion(),
                        afterFirst.reportVersion(), second.itemVersion(), UUID.randomUUID()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "VISIT_OUTCOME_READY:" + scenario.sessionId()));
        var finalized = outcomeService.finalizeReport(scenario.geA().getId(), scenario.sessionId(),
                currentSession.getVersion(), skipped.reportVersion(), UUID.randomUUID());
        assertEquals(VisitSessionOutcomeReportState.FINALIZED, finalized.reportState());
        assertEquals("PARTLY_VIEWED", finalized.summary());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, scenario.sessionId()));
        var tenantView = outcomeService.getTenantOutcomeReport(scenario.tenant().getId(), scenario.sessionId());
        assertEquals("PARTIALLY_COMPLETED", tenantView.lifecycle());
        assertEquals(2, tenantView.properties().size());
        var tenantSkipped = tenantView.properties().stream().filter(item -> "NOT_VIEWED".equals(item.outcome()))
                .findFirst().orElseThrow();
        assertEquals("GE reported: This property was not viewed.", tenantSkipped.reasonLabel());
        assertEquals("GE_REPORTED", tenantSkipped.attribution());
        assertFalse(tenantSkipped.reasonLabel().contains("Access was not available"));
        assertFalse(java.util.Arrays.stream(tenantSkipped.getClass().getRecordComponents())
                .anyMatch(component -> component.getName().toLowerCase().contains("note")
                        || component.getName().toLowerCase().contains("actor")));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "VISIT_OUTCOME_READY:" + scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? "
                        + "and event_type='OUTCOME_REPORT_FINALIZED'", Integer.class, scenario.sessionId()));

        OperationsVisitOutcomeDetailView opsBefore = outcomeService.getOperationsOutcomeDetail(
                scenario.admin().getId(), scenario.sessionId());
        var correctionItem = finalized.items().stream().filter(item -> item.itemId().equals(second.itemId()))
                .findFirst().orElseThrow();
        CorrectVisitOutcomeCommand correction = new CorrectVisitOutcomeCommand(second.itemId(),
                VisitSessionItemOutcomeState.VISITED, null, null, "Field log confirms the property was viewed",
                finalized.reportVersion(), correctionItem.itemVersion(), UUID.randomUUID());
        String listingStatusBeforeCorrection = jdbc.queryForObject("select l.status from listings l join visit_session_items i "
                + "on i.listing_id=l.id where i.id=?", String.class, second.itemId());
        assertThrows(IllegalArgumentException.class, () -> outcomeService.correctOutcome(scenario.admin().getId(),
                scenario.sessionId(), new CorrectVisitOutcomeCommand(second.itemId(),
                        VisitSessionItemOutcomeState.VISITED, null, null, " ",
                        finalized.reportVersion(), correctionItem.itemVersion(), UUID.randomUUID())));
        var corrected = outcomeService.correctOutcome(scenario.admin().getId(), scenario.sessionId(), correction);
        assertEquals("ALL_VIEWED", corrected.summary());
        assertEquals("SKIPPED", opsBefore.properties().stream().filter(item -> item.itemId().equals(second.itemId()))
                .findFirst().orElseThrow().outcome());
        assertEquals("ALL_VIEWED", outcomeService.getTenantOutcomeReport(scenario.tenant().getId(), scenario.sessionId())
                .outcomeSummary());
        var correctedTenantProperty = outcomeService.getTenantOutcomeReport(scenario.tenant().getId(), scenario.sessionId())
                .properties().stream().filter(item -> item.position() == correctionItem.position()).findFirst().orElseThrow();
        assertEquals("VIEWED", correctedTenantProperty.outcome());
        assertEquals("OPERATIONS_UPDATED", correctedTenantProperty.attribution());
        assertNotNull(correctedTenantProperty.correctedAt());
        assertEquals(1, corrected.correctionHistory().size());
        assertEquals("SKIPPED", corrected.correctionHistory().get(0).previousOutcome());
        assertEquals("VISITED", corrected.correctionHistory().get(0).correctedOutcome());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? "
                + "and event_type='OUTCOME_ITEM_CORRECTED' and metadata->>'previousPrivateNote'='Access was not available'",
                Integer.class, scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key like ?",
                Integer.class, "VISIT_OUTCOME_UPDATED:" + scenario.sessionId() + ":%"));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, scenario.sessionId()));
        assertEquals(listingStatusBeforeCorrection, jdbc.queryForObject("select l.status from listings l join visit_session_items i "
                + "on i.listing_id=l.id where i.id=?", String.class, second.itemId()));
        assertThrows(VisitOperationsConflictException.class, () -> outcomeService.correctOutcome(
                scenario.admin().getId(), scenario.sessionId(), new CorrectVisitOutcomeCommand(second.itemId(),
                        VisitSessionItemOutcomeState.SKIPPED, VisitSessionItemSkipReason.PROPERTY_UNAVAILABLE, null,
                        "Stale operator correction", correction.expectedReportVersion(), correction.expectedItemVersion(), UUID.randomUUID())));
        assertThrows(AccessDeniedException.class, () -> outcomeService.correctOutcome(scenario.geA().getId(),
                scenario.sessionId(), correction));
        outcomeService.correctOutcome(scenario.admin().getId(), scenario.sessionId(), correction);
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='OUTCOME_ITEM_CORRECTED'",
                Integer.class, scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key like ?",
                Integer.class, "VISIT_OUTCOME_UPDATED:" + scenario.sessionId() + ":%"));

        jdbc.update("delete from visit_session_item_outcomes where session_id=?", scenario.sessionId());
        jdbc.update("update visit_session_outcome_reports set state='LEGACY_UNRECORDED',scope_source='LEGACY_COMPLETED',"
                + "scope_captured_at=null,finalized_at=null,finalized_by_user_id=null where session_id=?", scenario.sessionId());
        var legacy = outcomeService.getTenantOutcomeReport(scenario.tenant().getId(), scenario.sessionId());
        assertEquals("RESULTS_NOT_RECORDED", legacy.lifecycle());
        assertEquals("RESULTS_NOT_RECORDED", legacy.outcomeSummary());
        assertTrue(legacy.properties().isEmpty());
        assertNull(legacy.totalProperties());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void privateNoteOnlyOperationsCorrectionPreservesTenantAttributionAndTimestamp() {
        FinalizedOutcomeFixture fixture = finalizedOutcomeFixture();
        var tenantBefore = outcomeService.getTenantOutcomeReport(fixture.scenario().tenant().getId(),
                fixture.scenario().sessionId());
        var propertyBefore = tenantBefore.properties().stream()
                .filter(item -> "NOT_VIEWED".equals(item.outcome())).findFirst().orElseThrow();
        assertEquals("GE_REPORTED", propertyBefore.attribution());

        OperationsVisitOutcomeDetailView operationsView = outcomeService.getOperationsOutcomeDetail(
                fixture.scenario().admin().getId(), fixture.scenario().sessionId());
        var item = operationsView.properties().stream()
                .filter(row -> "SKIPPED".equals(row.outcome())).findFirst().orElseThrow();
        CorrectVisitOutcomeCommand internalOnlyCorrection = new CorrectVisitOutcomeCommand(item.itemId(),
                VisitSessionItemOutcomeState.SKIPPED, VisitSessionItemSkipReason.PROPERTY_UNAVAILABLE,
                "Internal access note updated", "Internal note was reconciled with the field log",
                operationsView.reportVersion(), item.version(), UUID.randomUUID());

        OperationsVisitOutcomeDetailView corrected = outcomeService.correctOutcome(
                fixture.scenario().admin().getId(), fixture.scenario().sessionId(), internalOnlyCorrection);
        var tenantAfter = outcomeService.getTenantOutcomeReport(fixture.scenario().tenant().getId(),
                fixture.scenario().sessionId());
        var propertyAfter = tenantAfter.properties().stream()
                .filter(row -> row.position().equals(propertyBefore.position())).findFirst().orElseThrow();

        assertEquals(propertyBefore.outcome(), propertyAfter.outcome());
        assertEquals(propertyBefore.reasonLabel(), propertyAfter.reasonLabel());
        assertEquals("GE_REPORTED", propertyAfter.attribution());
        assertNull(propertyAfter.correctedAt());
        assertEquals(tenantBefore.lastUpdatedAt(), tenantAfter.lastUpdatedAt());
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key like ?",
                Integer.class, "VISIT_OUTCOME_UPDATED:" + fixture.scenario().sessionId() + ":%"));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? "
                        + "and event_type='OUTCOME_ITEM_CORRECTED' and metadata->>'correctedPrivateNote'=?",
                Integer.class, fixture.scenario().sessionId(), "Internal access note updated"));
        assertEquals(1, corrected.correctionHistory().size());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameOperationIdOnDifferentItemsCreatesDistinctUpdateIntentsAndExactRetryDedupes() {
        FinalizedOutcomeFixture fixture = finalizedOutcomeFixture();
        Long sessionId = fixture.scenario().sessionId();
        Long actorId = fixture.scenario().admin().getId();
        UUID operationId = UUID.randomUUID();
        OperationsVisitOutcomeDetailView initial = outcomeService.getOperationsOutcomeDetail(actorId, sessionId);
        var firstItem = initial.properties().stream().filter(row -> "VISITED".equals(row.outcome()))
                .findFirst().orElseThrow();
        CorrectVisitOutcomeCommand firstCorrection = new CorrectVisitOutcomeCommand(firstItem.itemId(),
                VisitSessionItemOutcomeState.SKIPPED, VisitSessionItemSkipReason.ACCESS_DENIED, null,
                "Field evidence confirms this property was not accessible", initial.reportVersion(),
                firstItem.version(), operationId);
        OperationsVisitOutcomeDetailView afterFirst = outcomeService.correctOutcome(actorId, sessionId, firstCorrection);

        var secondItem = afterFirst.properties().stream()
                .filter(row -> "SKIPPED".equals(row.outcome()) && !row.itemId().equals(firstItem.itemId()))
                .findFirst().orElseThrow();
        CorrectVisitOutcomeCommand secondCorrection = new CorrectVisitOutcomeCommand(secondItem.itemId(),
                VisitSessionItemOutcomeState.VISITED, null, null,
                "Field evidence confirms this property was viewed", afterFirst.reportVersion(),
                secondItem.version(), operationId);
        outcomeService.correctOutcome(actorId, sessionId, secondCorrection);

        String eventKeyPattern = "VISIT_OUTCOME_UPDATED:" + sessionId + ":%";
        assertEquals(2, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key like ?",
                Integer.class, eventKeyPattern));
        assertEquals(2, jdbc.queryForObject("select count(distinct event_key) from visit_notification_outbox where event_key like ?",
                Integer.class, eventKeyPattern));
        assertEquals(2, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? "
                        + "and event_type='OUTCOME_ITEM_CORRECTED'", Integer.class, sessionId));

        outcomeService.correctOutcome(actorId, sessionId, firstCorrection);
        assertThrows(VisitOperationsConflictException.class, () -> outcomeService.correctOutcome(actorId, sessionId,
                new CorrectVisitOutcomeCommand(firstItem.itemId(), VisitSessionItemOutcomeState.VISITED, null, null,
                        "Conflicting payload for the same operation", firstCorrection.expectedReportVersion(),
                        firstCorrection.expectedItemVersion(), operationId)));
        assertEquals(2, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key like ?",
                Integer.class, eventKeyPattern));
        assertEquals(2, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? "
                        + "and event_type='OUTCOME_ITEM_CORRECTED'", Integer.class, sessionId));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void materialCorrectionToOneVisitDoesNotChangeAnotherVisitHistoryTimestamp() {
        RecommendationScenario firstScenario = recommendationScenario(false);
        FinalizedOutcomeFixture visitA = finalizedOutcomeFixture(firstScenario);
        RecommendationScenario secondScenario = additionalRecommendationSession(firstScenario);
        FinalizedOutcomeFixture visitB = finalizedOutcomeFixture(secondScenario);
        Long tenantId = firstScenario.tenant().getId();

        var before = outcomeService.listTenantOutcomeHistory(tenantId, 0, 20);
        var visitABefore = before.sessions().stream().filter(view -> view.sessionId().equals(visitA.scenario().sessionId()))
                .findFirst().orElseThrow();
        var visitBBefore = before.sessions().stream().filter(view -> view.sessionId().equals(visitB.scenario().sessionId()))
                .findFirst().orElseThrow();

        OperationsVisitOutcomeDetailView opsView = outcomeService.getOperationsOutcomeDetail(
                firstScenario.admin().getId(), visitB.scenario().sessionId());
        var item = opsView.properties().stream().filter(row -> "VISITED".equals(row.outcome()))
                .findFirst().orElseThrow();
        outcomeService.correctOutcome(firstScenario.admin().getId(), visitB.scenario().sessionId(),
                new CorrectVisitOutcomeCommand(item.itemId(), VisitSessionItemOutcomeState.SKIPPED,
                        VisitSessionItemSkipReason.ACCESS_DENIED, null,
                        "Field review confirmed access was unavailable", opsView.reportVersion(),
                        item.version(), UUID.randomUUID()));

        var after = outcomeService.listTenantOutcomeHistory(tenantId, 0, 20);
        var visitAAfter = after.sessions().stream().filter(view -> view.sessionId().equals(visitA.scenario().sessionId()))
                .findFirst().orElseThrow();
        var visitBAfter = after.sessions().stream().filter(view -> view.sessionId().equals(visitB.scenario().sessionId()))
                .findFirst().orElseThrow();

        assertEquals(visitABefore.lifecycle(), visitAAfter.lifecycle());
        assertEquals(visitABefore.outcomeSummary(), visitAAfter.outcomeSummary());
        assertEquals(visitABefore.properties(), visitAAfter.properties());
        assertEquals(visitABefore.lastUpdatedAt(), visitAAfter.lastUpdatedAt());
        assertTrue(visitBAfter.lastUpdatedAt().isAfter(visitBBefore.lastUpdatedAt()));
        var correctedProperty = visitBAfter.properties().stream()
                .filter(property -> property.position().equals(item.position())).findFirst().orElseThrow();
        assertEquals("NOT_VIEWED", correctedProperty.outcome());
        assertEquals("OPERATIONS_UPDATED", correctedProperty.attribution());
        assertEquals(visitBAfter.lastUpdatedAt(), correctedProperty.correctedAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void combinedCompletionRejectsMissingItemsThenFinishesAndFinalizesAtomically() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var planned = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                new ApproveVisitRecommendationCommand(0L, scenario.geA().getId(), planned.scheduledAt(),
                        "Asia/Kolkata", null));
        Instant startAt = Instant.now().minusSeconds(5 * 60L).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        scheduleForExecution(scenario, scenario.geA(), startAt, planned.durationMinutes(), true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        execution.start(scenario.geA().getId(), scenario.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID()));

        var report = outcomeService.getGroundOutcomeReport(scenario.geA().getId(), scenario.sessionId());
        var incomplete = new CompleteVisitSessionWithOutcomesCommand(report.sessionVersion(), report.reportVersion(), UUID.randomUUID());
        assertThrows(VisitOperationsConflictException.class, () -> outcomeCompletionService.complete(
                scenario.geA().getId(), scenario.sessionId(), incomplete));
        assertEquals(VisitSessionStatus.STARTED, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertEquals("OPEN", jdbc.queryForObject("select state from visit_session_outcome_reports where session_id=?",
                String.class, scenario.sessionId()));

        for (var item : report.items()) {
            report = outcomeService.recordItemOutcome(scenario.geA().getId(), scenario.sessionId(), item.itemId(),
                    new RecordVisitSessionItemOutcomeCommand(VisitSessionItemOutcomeState.VISITED,
                            null, null, report.sessionVersion(), report.reportVersion(), item.itemVersion(), UUID.randomUUID()));
        }
        var command = new CompleteVisitSessionWithOutcomesCommand(report.sessionVersion(), report.reportVersion(), UUID.randomUUID());
        jdbc.execute("create function reject_outcome_finalize_for_test() returns trigger language plpgsql as $$ "
                + "begin if NEW.event_type='OUTCOME_REPORT_FINALIZED' then raise exception 'forced finalization failure'; "
                + "end if; return NEW; end; $$");
        jdbc.execute("create trigger reject_outcome_finalize_for_test before insert on visit_execution_events "
                + "for each row execute function reject_outcome_finalize_for_test()");
        try {
            assertThrows(RuntimeException.class, () -> outcomeCompletionService.complete(
                    scenario.geA().getId(), scenario.sessionId(), command));
        } finally {
            jdbc.execute("drop trigger if exists reject_outcome_finalize_for_test on visit_execution_events");
            jdbc.execute("drop function if exists reject_outcome_finalize_for_test()");
        }
        assertEquals(VisitSessionStatus.STARTED, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='VISIT_FINISHED'",
                Integer.class, scenario.sessionId()));
        assertEquals("OPEN", jdbc.queryForObject("select state from visit_session_outcome_reports where session_id=?",
                String.class, scenario.sessionId()));
        var completed = outcomeCompletionService.complete(scenario.geA().getId(), scenario.sessionId(), command);
        assertEquals("COMPLETED", completed.sessionState());
        assertEquals(VisitSessionOutcomeReportState.FINALIZED, completed.reportState());
        assertEquals("ALL_VIEWED", completed.summary());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='VISIT_FINISHED'",
                Integer.class, scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='OUTCOME_REPORT_FINALIZED'",
                Integer.class, scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? "
                + "and event_type in ('RELEASE','RESTORE','FORFEIT_NO_SHOW')", Integer.class, scenario.sessionId()));
        assertEquals(VisitSessionOutcomeReportState.FINALIZED,
                outcomeCompletionService.complete(scenario.geA().getId(), scenario.sessionId(), command).reportState());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='OUTCOME_REPORT_FINALIZED'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void pendingQueueAndOutcomeWritesFollowCurrentAssignmentAfterReassignment() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var planned = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                new ApproveVisitRecommendationCommand(0L, scenario.geA().getId(), planned.scheduledAt(),
                        "Asia/Kolkata", null));
        Instant startAt = Instant.now().minusSeconds(5 * 60L).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        scheduleForExecution(scenario, scenario.geA(), startAt, planned.durationMinutes(), true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        execution.start(scenario.geA().getId(), scenario.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID()));
        execution.finish(scenario.geA().getId(), scenario.sessionId());

        var oldGeQueue = outcomeService.listGroundPendingOutcomes(scenario.geA().getId(), 0, 20);
        assertTrue(oldGeQueue.getContent().stream().anyMatch(item -> item.sessionId().equals(scenario.sessionId())
                && item.pendingPropertyCount() == 2L && item.totalPropertyCount() == 2L));
        assertFalse(outcomeService.listGroundPendingOutcomes(scenario.geB().getId(), 0, 20).getContent().stream()
                .anyMatch(item -> item.sessionId().equals(scenario.sessionId())));

        // Simulate an authoritative assignment change after the former GE has loaded the report.
        jdbc.update("update visit_sessions set representative_user_id=? where id=?",
                scenario.geB().getId(), scenario.sessionId());
        assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> outcomeService.getGroundOutcomeReport(
                scenario.geA().getId(), scenario.sessionId()));
        var current = outcomeService.getGroundOutcomeReport(scenario.geB().getId(), scenario.sessionId());
        assertEquals(2, current.items().size());
        assertThrows(jakarta.persistence.EntityNotFoundException.class, () -> outcomeService.recordItemOutcome(scenario.geA().getId(),
                scenario.sessionId(), current.items().get(0).itemId(), new RecordVisitSessionItemOutcomeCommand(
                        VisitSessionItemOutcomeState.VISITED, null, null, current.sessionVersion(),
                        current.reportVersion(), current.items().get(0).itemVersion(), UUID.randomUUID())));
        assertFalse(outcomeService.listGroundPendingOutcomes(scenario.geA().getId(), 0, 20).getContent().stream()
                .anyMatch(item -> item.sessionId().equals(scenario.sessionId())));
        var currentQueue = outcomeService.listGroundPendingOutcomes(scenario.geB().getId(), 0, 20);
        assertTrue(currentQueue.getContent().stream().anyMatch(item -> item.sessionId().equals(scenario.sessionId())));
        var updated = current;
        for (var item : current.items()) {
            updated = outcomeService.recordItemOutcome(scenario.geB().getId(), scenario.sessionId(), item.itemId(),
                    new RecordVisitSessionItemOutcomeCommand(VisitSessionItemOutcomeState.SKIPPED,
                            VisitSessionItemSkipReason.TENANT_DECLINED, null, updated.sessionVersion(),
                            updated.reportVersion(), item.itemVersion(), UUID.randomUUID()));
        }
        var finalized = outcomeCompletionService.complete(scenario.geB().getId(), scenario.sessionId(),
                new CompleteVisitSessionWithOutcomesCommand(updated.sessionVersion(), updated.reportVersion(), UUID.randomUUID()));
        assertEquals("NONE_VIEWED", finalized.summary());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='VISIT_FINISHED'",
                Integer.class, scenario.sessionId()));
        assertFalse(outcomeService.listGroundPendingOutcomes(scenario.geB().getId(), 0, 20).getContent().stream()
                .anyMatch(item -> item.sessionId().equals(scenario.sessionId())));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void operationsQueueUsesConfiguredGraceAndCorrectionsCanCompleteOpenReport() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var planned = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                new ApproveVisitRecommendationCommand(0L, scenario.geA().getId(), planned.scheduledAt(),
                        "Asia/Kolkata", null));
        Instant startAt = Instant.now().minusSeconds(5 * 60L).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        scheduleForExecution(scenario, scenario.geA(), startAt, planned.durationMinutes(), true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        execution.start(scenario.geA().getId(), scenario.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID()));
        execution.finish(scenario.geA().getId(), scenario.sessionId());

        assertFalse(outcomeService.listOperationsOutcomeExceptions(scenario.admin().getId(), 0, 20).getContent().stream()
                .anyMatch(item -> item.sessionId().equals(scenario.sessionId())), "fresh OPEN reports stay in the GE grace window");
        jdbc.update("update visit_sessions set finished_at=? where id=?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(70))), scenario.sessionId());
        var overdue = outcomeService.listOperationsOutcomeExceptions(scenario.admin().getId(), 0, 20);
        var exception = overdue.getContent().stream().filter(item -> item.sessionId().equals(scenario.sessionId()))
                .findFirst().orElseThrow();
        assertEquals(60, executionProperties.getIncompleteOutcomeGraceMinutes());
        assertEquals(2L, exception.unrecordedCount());
        assertNotNull(exception.overdueSince());
        assertThrows(AccessDeniedException.class,
                () -> outcomeService.listOperationsOutcomeExceptions(scenario.geA().getId(), 0, 20));
        User subAdmin = user("outcome-subadmin", Role.ROLE_SUB_ADMIN);
        assertThrows(AccessDeniedException.class,
                () -> outcomeService.getOperationsOutcomeDetail(subAdmin.getId(), scenario.sessionId()));
        assertThrows(AccessDeniedException.class,
                () -> outcomeService.getOperationsOutcomeDetail(scenario.tenant().getId(), scenario.sessionId()));

        OperationsVisitOutcomeDetailView detail = outcomeService.getOperationsOutcomeDetail(
                scenario.admin().getId(), scenario.sessionId());
        var first = detail.properties().get(0);
        var afterFirst = outcomeService.correctOutcome(scenario.admin().getId(), scenario.sessionId(),
                new CorrectVisitOutcomeCommand(first.itemId(), VisitSessionItemOutcomeState.VISITED,
                        null, null, "Field reconciliation confirms this property was viewed",
                        detail.reportVersion(), first.version(), UUID.randomUUID()));
        assertEquals("OPEN", afterFirst.reportState());
        var remaining = afterFirst.properties().stream()
                .filter(item -> item.outcome().equals("UNRECORDED")).findFirst().orElseThrow();
        var finalized = outcomeService.correctOutcome(scenario.admin().getId(), scenario.sessionId(),
                new CorrectVisitOutcomeCommand(remaining.itemId(), VisitSessionItemOutcomeState.SKIPPED,
                        VisitSessionItemSkipReason.ACCESS_DENIED, null, "Operations reconciled the field report",
                        afterFirst.reportVersion(), remaining.version(), UUID.randomUUID()));
        assertEquals("FINALIZED", finalized.reportState());
        assertEquals("PARTLY_VIEWED", finalized.summary());
        assertEquals(0, finalized.pendingProperties());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? "
                + "and event_type='OUTCOME_REPORT_FINALIZED' and reason_code='OPERATIONS_CORRECTION'",
                Integer.class, scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "VISIT_OUTCOME_READY:" + scenario.sessionId()));
        assertEquals("PARTLY_VIEWED", outcomeService.getTenantOutcomeReport(scenario.tenant().getId(), scenario.sessionId())
                .outcomeSummary());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void successfulStartCapturesFourConfirmedItemsAndExcludesPreStartRemoval() {
        RecommendationScenario scenario = recommendationScenario(false);
        addTwoPreStartConfirmedItems(scenario);
        Instant startAt = Instant.now().minusSeconds(5 * 60L).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        scheduleForExecution(scenario, scenario.geA(), startAt, 180, true);
        Long removedItemId = addPreStartNonExecutionItems(scenario);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());

        assertEquals(VisitSessionStatus.STARTED, execution.start(scenario.geA().getId(), scenario.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID())).status());
        assertEquals(4, jdbc.queryForObject("select count(*) from visit_session_item_outcomes where session_id=?",
                Integer.class, scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_session_item_outcomes where item_id=?",
                Integer.class, removedItemId));
        assertEquals(4, jdbc.queryForObject("select count(*) from visit_session_items where session_id=? "
                        + "and removed_at is null and confirmation_status='CONFIRMED'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void zeroViewedReportFinalizesTruthfullyWithoutChangingEntitlementAndAppearsInOperationsQueue() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var planned = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                new ApproveVisitRecommendationCommand(0L, scenario.geA().getId(), planned.scheduledAt(),
                        "Asia/Kolkata", null));
        Instant startAt = Instant.now().minusSeconds(5 * 60L).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        scheduleForExecution(scenario, scenario.geA(), startAt, planned.durationMinutes(), true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        execution.start(scenario.geA().getId(), scenario.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID()));

        var report = outcomeService.getGroundOutcomeReport(scenario.geA().getId(), scenario.sessionId());
        for (var item : report.items()) {
            report = outcomeService.recordItemOutcome(scenario.geA().getId(), scenario.sessionId(), item.itemId(),
                    new RecordVisitSessionItemOutcomeCommand(VisitSessionItemOutcomeState.SKIPPED,
                            VisitSessionItemSkipReason.PROPERTY_UNAVAILABLE, null, report.sessionVersion(),
                            report.reportVersion(), item.itemVersion(), UUID.randomUUID()));
        }
        execution.finish(scenario.geA().getId(), scenario.sessionId());
        VisitSession current = sessions.findById(scenario.sessionId()).orElseThrow();
        var finalized = outcomeService.finalizeReport(scenario.geA().getId(), scenario.sessionId(),
                current.getVersion(), report.reportVersion(), UUID.randomUUID());

        assertEquals("NONE_VIEWED", finalized.summary());
        assertEquals("NO_PROPERTIES_VIEWED", outcomeService.getTenantOutcomeReport(
                scenario.tenant().getId(), scenario.sessionId()).lifecycle());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? "
                        + "and event_type in ('RELEASE','RESTORE','FORFEIT_NO_SHOW')", Integer.class, scenario.sessionId()));

        var queue = outcomeService.listOperationsOutcomeExceptions(scenario.admin().getId(), 0, 20);
        var exception = queue.getContent().stream().filter(item -> item.sessionId().equals(scenario.sessionId()))
                .findFirst().orElseThrow();
        assertEquals(2L, exception.itemCount());
        assertEquals(0L, exception.unrecordedCount());
        assertEquals(0L, exception.visitedCount());
        assertThrows(AccessDeniedException.class, () -> outcomeService.listOperationsOutcomeExceptions(
                scenario.tenant().getId(), 0, 20));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleOutboxClaimIsReclaimedAndCompetingWorkersPersistOneNotification() throws Exception {
        User tenant = user("outbox-tenant", Role.ROLE_TENANT);
        String eventKey = "OUTBOX_RECLAIM:" + UUID.randomUUID();
        jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message,state,attempts,claimed_at) values (?,?, 'TENANT','TEST','Retry test','Durable retry test','PROCESSING',1,?)",
                eventKey, tenant.getId(), java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> deliverOutbox(ready, gate));
            var second = executor.submit(() -> deliverOutbox(ready, gate));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            gate.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals("SENT", jdbc.queryForObject("select state from visit_notification_outbox where event_key=?",
                String.class, eventKey));
        assertEquals(2, jdbc.queryForObject("select attempts from visit_notification_outbox where event_key=?",
                Integer.class, eventKey));
        assertTrue(notifications.findByEventKey(eventKey).isPresent());
        assertEquals(1, jdbc.queryForObject("select count(*) from system_notifications where event_key=?",
                Integer.class, eventKey));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void outboxRetryAfterNotificationCommitBeforeAcknowledgementDoesNotDuplicateDelivery() {
        User tenant = user("outbox-crash-tenant", Role.ROLE_TENANT);
        String eventKey = "OUTBOX_CRASH:" + UUID.randomUUID();
        jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message,state,attempts,claimed_at,available_at) values (?,?, 'TENANT','TEST','Crash recovery','Deliver once','QUEUED',0,null,?)",
                eventKey, tenant.getId(), java.sql.Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")));
        outboxWorker.deliverBatch();
        assertEquals(1, jdbc.queryForObject("select count(*) from system_notifications where event_key=?",
                Integer.class, eventKey));
        jdbc.update("update visit_notification_outbox set state='PROCESSING',claimed_at=? where event_key=?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))), eventKey);
        outboxWorker.deliverBatch();
        assertEquals("SENT", jdbc.queryForObject("select state from visit_notification_outbox where event_key=?",
                String.class, eventKey));
        assertEquals(1, jdbc.queryForObject("select count(*) from system_notifications where event_key=?",
                Integer.class, eventKey));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void provisionalNoShowRequiresSpacedUnansweredContactEvidenceAndKeepsDisputeWindow() {
        RecommendationScenario scenario = recommendationScenario(false);
        var plan = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        Instant scheduledAt = Instant.now().minus(Duration.ofMinutes(20));
        scheduleForExecution(scenario, scenario.geA(), scheduledAt, plan.durationMinutes(), false);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());

        assertThrows(VisitOperationsConflictException.class,
                () -> execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId()));
        assertEquals(VisitSessionStatus.SCHEDULED, sessions.findById(scenario.sessionId()).orElseThrow().getStatus(),
                "arrival without contact evidence must not create a no-show state");

        Instant firstAttempt = Instant.now().minus(Duration.ofMinutes(10));
        Instant secondAttempt = firstAttempt.plus(Duration.ofMinutes(5));
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,occurred_at,reason_code,metadata,idempotency_key) values (?,?,'CONTACT_ATTEMPT',?,'TENANT_UNREACHABLE','{\"outcome\":\"NO_ANSWER\"}'::jsonb,?)",
                scenario.sessionId(), scenario.geA().getId(), java.sql.Timestamp.from(firstAttempt), "NO_ANSWER:" + UUID.randomUUID());
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,occurred_at,reason_code,metadata,idempotency_key) values (?,?,'CONTACT_ATTEMPT',?,'TENANT_UNREACHABLE','{\"outcome\":\"NO_ANSWER\"}'::jsonb,?)",
                scenario.sessionId(), scenario.geA().getId(), java.sql.Timestamp.from(secondAttempt), "NO_ANSWER:" + UUID.randomUUID());

        var result = execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId());
        VisitSession noShowReview = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.PROVISIONAL_NO_SHOW, result.status());
        assertTrue(noShowReview.getNoShowDisputeUntil().isAfter(Instant.now()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW'",
                Integer.class, scenario.sessionId()));
        jdbc.update("update visit_sessions set no_show_dispute_until=? where id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), scenario.sessionId());
        noShowSettlementWorker.settleBatch();
        VisitSession finalized = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.NO_SHOW, finalized.getStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW'",
                Integer.class, scenario.sessionId()));
        assertThrows(VisitOperationsConflictException.class,
                () -> execution.reportContact(scenario.geA().getId(), scenario.sessionId(),
                        new GroundVisitContactCommand("CONNECTED", null, "OTHER_APPROVED", UUID.randomUUID())));
        assertEquals(VisitSessionStatus.NO_SHOW, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW'",
                Integer.class, scenario.sessionId()));
        Integer availableBeforeRestore = jdbc.queryForObject(
                "select available_credits from tenant_visit_entitlement_accounts where user_id=?", Integer.class,
                scenario.tenant().getId());
        var restore = new VisitEntitlementRestoreCommand(finalized.getVersion(),
                "APPROVED_OPERATIONAL_EXCEPTION", UUID.randomUUID());
        entitlementOperations.restoreInterruptedSession(scenario.admin().getId(), scenario.sessionId(), restore);
        entitlementOperations.restoreInterruptedSession(scenario.admin().getId(), scenario.sessionId(), restore);
        assertEquals(availableBeforeRestore + 1, jdbc.queryForObject(
                "select available_credits from tenant_visit_entitlement_accounts where user_id=?", Integer.class,
                scenario.tenant().getId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='OE_OPERATIONAL_RESTORE'",
                Integer.class, scenario.sessionId()));
        assertThrows(VisitOperationsConflictException.class, () -> entitlementOperations.restoreInterruptedSession(
                scenario.admin().getId(), scenario.sessionId(), new VisitEntitlementRestoreCommand(
                        finalized.getVersion(), "APPROVED_OPERATIONAL_EXCEPTION", UUID.randomUUID())));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void successfulContactDuringProvisionalNoShowStopsForfeitureAndPreservesEvidence() {
        RecommendationScenario scenario = recommendationScenario(false);
        scheduleForExecution(scenario, scenario.geA(), Instant.now().minus(Duration.ofMinutes(30)), 45, true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        Instant first = Instant.now().minus(Duration.ofMinutes(15));
        insertContactEvidence(scenario, first, "NO_ANSWER");
        insertContactEvidence(scenario, first.plus(Duration.ofMinutes(6)), "NO_ANSWER");
        assertEquals(VisitSessionStatus.PROVISIONAL_NO_SHOW,
                execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId()).status());

        var recovered = execution.reportContact(scenario.geA().getId(), scenario.sessionId(),
                new GroundVisitContactCommand("CONNECTED", null, "OTHER_APPROVED", UUID.randomUUID()));
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, recovered.status());
        assertEquals("REQUIRED", recovered.repairState());
        assertThrows(VisitOperationsConflictException.class,
                () -> execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId()),
                "superseded unanswered evidence must not reopen no-show settlement");
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RESERVE'",
                Integer.class, scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW'",
                Integer.class, scenario.sessionId()));
        assertEquals(2, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='CONTACT_ATTEMPT' and metadata->>'outcome'='NO_ANSWER'",
                Integer.class, scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='CONTACT_ATTEMPT' and metadata->>'outcome'='CONNECTED'",
                Integer.class, scenario.sessionId()));
        jdbc.update("update visit_sessions set no_show_dispute_until=? where id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), scenario.sessionId());
        noShowSettlementWorker.settleBatch();
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tenantConfirmationDuringProvisionalNoShowStopsForfeitureAndKeepsHold() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var plan = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        scheduleForExecution(scenario, scenario.geA(), Instant.now().minus(Duration.ofMinutes(30)),
                plan.durationMinutes(), true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        Instant first = Instant.now().minus(Duration.ofMinutes(15));
        insertContactEvidence(scenario, first, "NO_ANSWER");
        insertContactEvidence(scenario, first.plus(Duration.ofMinutes(6)), "NO_ANSWER");
        execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId());

        var response = execution.reportContact(scenario.geA().getId(), scenario.sessionId(),
                new GroundVisitContactCommand("TENANT_CONFIRMED", scenario.desiredAt(), "OTHER_APPROVED", UUID.randomUUID()));

        assertTrue(response.status() == VisitSessionStatus.SCHEDULED
                || response.status() == VisitSessionStatus.REPAIR_REQUIRED);
        assertNotEquals(VisitSessionStatus.PROVISIONAL_NO_SHOW, response.status());
        assertNotEquals(VisitSessionStatus.NO_SHOW, response.status());
        assertTrue(entitlements.hasReservation(scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW'",
                Integer.class, scenario.sessionId()));
        jdbc.update("update visit_sessions set no_show_dispute_until=? where id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), scenario.sessionId());
        noShowSettlementWorker.settleBatch();
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tenantNoShowDisputeBeforeDeadlinePreventsSettlement() {
        RecommendationScenario scenario = recommendationScenario(false);
        scheduleForExecution(scenario, scenario.geA(), Instant.now().minus(Duration.ofMinutes(30)), 45, true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        Instant first = Instant.now().minus(Duration.ofMinutes(15));
        insertContactEvidence(scenario, first, "NO_ANSWER");
        insertContactEvidence(scenario, first.plus(Duration.ofMinutes(6)), "NO_ANSWER");
        execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId());
        VisitSession pending = sessions.findById(scenario.sessionId()).orElseThrow();
        var disputed = execution.confirmTenant(scenario.tenant().getId(), scenario.sessionId(),
                new TenantVisitConfirmationCommand("DISPUTE_NO_SHOW", null, UUID.randomUUID(), pending.getVersion()));
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, disputed.status());
        jdbc.update("update visit_sessions set no_show_dispute_until=? where id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), scenario.sessionId());
        noShowSettlementWorker.settleBatch();
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW'",
                Integer.class, scenario.sessionId()));
        assertTrue(entitlements.hasReservation(scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void travelFeasibilityChangedAfterProposalCannotBeConfirmed() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var candidate = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        User ground = users.findById(candidate.groundExecutiveUserId()).orElseThrow();
        scheduleForExecution(scenario, ground, candidate.scheduledAt(), candidate.durationMinutes(), false);
        assertTrue(entitlements.reserve(scenario.tenant().getId(), scenario.sessionId(),
                Instant.now().plus(Duration.ofDays(7))));
        VisitSession proposal = markProposalPending(scenario.sessionId());
        createNeighborReservation(scenario, ground, "New travel-constrained appointment", 22.72, 76.18,
                candidate.reservedEndAt().plusSeconds(60), 30, VisitSessionStatus.SCHEDULED);

        var rejected = execution.confirmTenant(scenario.tenant().getId(), scenario.sessionId(),
                new TenantVisitConfirmationCommand("ACCEPT_RESCHEDULE", null, UUID.randomUUID(), proposal.getVersion()));

        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, rejected.status());
        assertEquals("PENDING", rejected.tenantConfirmationState());
        assertTrue(entitlements.hasReservation(scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='ACCEPT_RESCHEDULE'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void provisionalNoShowSettlementRacesSuccessfulContactAsOneLockedOutcome() throws Exception {
        RecommendationScenario scenario = recommendationScenario(false);
        scheduleForExecution(scenario, scenario.geA(), Instant.now().minus(Duration.ofMinutes(30)), 45, true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        Instant first = Instant.now().minus(Duration.ofMinutes(15));
        insertContactEvidence(scenario, first, "NO_ANSWER");
        insertContactEvidence(scenario, first.plus(Duration.ofMinutes(6)), "NO_ANSWER");
        execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId());
        jdbc.update("update visit_sessions set no_show_dispute_until=? where id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), scenario.sessionId());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var contact = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("contact gate timed out");
                    return execution.reportContact(scenario.geA().getId(), scenario.sessionId(),
                            new GroundVisitContactCommand("CONNECTED", null, null, UUID.randomUUID())).status().name();
                } catch (VisitOperationsConflictException closed) {
                    return "CONTACT_REJECTED_AFTER_SETTLEMENT";
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            var settlement = executor.submit(() -> {
                ready.countDown();
                try {
                    if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("settlement gate timed out");
                    noShowSettlementWorker.settleBatch();
                    return "SETTLED";
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            gate.countDown();
            contact.get(15, TimeUnit.SECONDS);
            settlement.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        VisitSession finalSession = sessions.findById(scenario.sessionId()).orElseThrow();
        int forfeitures = jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW'",
                Integer.class, scenario.sessionId());
        int connected = jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='CONTACT_ATTEMPT' and metadata->>'outcome'='CONNECTED'",
                Integer.class, scenario.sessionId());
        if (finalSession.getStatus() == VisitSessionStatus.REPAIR_REQUIRED) {
            assertEquals(0, forfeitures);
            assertEquals(1, connected);
        } else {
            assertEquals(VisitSessionStatus.NO_SHOW, finalSession.getStatus());
            assertEquals(1, forfeitures);
            assertEquals(0, connected);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void pendingAppointmentCannotIssueCodeStartOrBecomeNoShowUntilTenantAccepts() {
        RecommendationScenario scenario = recommendationScenario(false);
        var plan = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        Instant slot = Instant.now().minus(Duration.ofMinutes(20));
        scheduleForExecution(scenario, scenario.geA(), slot, plan.durationMinutes(), true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        setExactAvailability(scenario, Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(4)));
        VisitSession beforeReschedule = sessions.findById(scenario.sessionId()).orElseThrow();
        operations.reschedule(scenario.admin().getId(), scenario.sessionId(),
                new RescheduleVisitSessionCommand(beforeReschedule.getVersion(),
                        Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                                .plus(Duration.ofMinutes(3)), "Asia/Kolkata"));
        assertEquals("PENDING", sessions.findById(scenario.sessionId()).orElseThrow().getTenantConfirmationState());
        assertThrows(VisitOperationsConflictException.class,
                () -> execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId()));
        assertThrows(VisitOperationsConflictException.class,
                () -> execution.start(scenario.geA().getId(), scenario.sessionId(),
                        new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID())));
        assertThrows(VisitOperationsConflictException.class,
                () -> execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, scenario.sessionId()));
        VisitSession pending = sessions.findById(scenario.sessionId()).orElseThrow();
        var accepted = execution.confirmTenant(scenario.tenant().getId(), scenario.sessionId(),
                new TenantVisitConfirmationCommand("ACCEPT_RESCHEDULE", null, UUID.randomUUID(), pending.getVersion()));
        assertEquals(VisitSessionStatus.SCHEDULED, accepted.status());
        assertEquals("CONFIRMED", accepted.tenantConfirmationState());
        jdbc.update("update visit_start_challenges set next_issue_allowed_at=? where session_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), scenario.sessionId());
        var acceptedCode = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        assertEquals(VisitSessionStatus.STARTED, execution.start(scenario.geA().getId(), scenario.sessionId(),
                new VisitOtpStartCommand(acceptedCode.generation(), acceptedCode.code(), UUID.randomUUID())).status());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualStartOutsideConfirmedWindowRoutesToRepairWithoutConsumingCredit() {
        RecommendationScenario scenario = recommendationScenario(false);
        Instant slot = Instant.now().minus(Duration.ofMinutes(5));
        scheduleForExecution(scenario, scenario.geA(), slot, 45, true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        setExactAvailability(scenario, slot, Instant.now().plus(Duration.ofMinutes(5)));
        var result = execution.start(scenario.geA().getId(), scenario.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID()));
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, result.status());
        assertEquals("REPAIR_REQUIRED", result.resultCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, scenario.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void actualExecutionWindowHonorsExactOpeningAndClosingBoundaries() {
        RecommendationScenario scenario = recommendationScenario(false);
        Instant opening = Instant.now().plus(Duration.ofMinutes(10)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant closing = opening.plus(Duration.ofMinutes(90));
        scheduleForExecution(scenario, scenario.geA(), opening, 90, true);
        setExactAvailability(scenario, opening, closing);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (PropertyVisitRequest request : requests.findLockedBySessionIdOrderByIdAsc(scenario.sessionId())) {
                request.setAvailabilityStartAt(opening.minus(Duration.ofHours(1)));
                request.setAvailabilityEndAt(closing.plus(Duration.ofHours(1)));
            }
            VisitSessionItem firstStop = items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(scenario.sessionId()).get(0);
            firstStop.setAvailabilityEndAt(opening.plus(Duration.ofMinutes(recommendationPolicy.getStopDwellMinutes())));
        });
        VisitSession session = sessions.findById(scenario.sessionId()).orElseThrow();
        assertTrue(recommendations.actualExecutionWindowsValid(session, opening, closing));
        assertFalse(recommendations.actualExecutionWindowsValid(session,
                opening.minusSeconds(1), closing.minusSeconds(1)));
        assertFalse(recommendations.actualExecutionWindowsValid(session,
                opening.plusSeconds(1), closing.plusSeconds(1)));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (VisitSessionItem item : items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(scenario.sessionId())) {
                item.setAvailabilityStartAt(opening.minus(Duration.ofHours(1)));
                item.setAvailabilityEndAt(closing.plus(Duration.ofHours(1)));
            }
            for (PropertyVisitRequest request : requests.findLockedBySessionIdOrderByIdAsc(scenario.sessionId())) {
                request.setAvailabilityStartAt(opening);
                request.setAvailabilityEndAt(closing);
            }
        });
        assertTrue(recommendations.actualExecutionWindowsValid(session, opening, closing));
        assertFalse(recommendations.actualExecutionWindowsValid(session,
                opening.minusSeconds(1), closing.minusSeconds(1)));
        assertFalse(recommendations.actualExecutionWindowsValid(session,
                opening.plusSeconds(1), closing.plusSeconds(1)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void activeStartedVisitCannotRestoreUntilInterruptionIsDocumented() {
        RecommendationScenario scenario = recommendationScenario(false);
        scheduleForExecution(scenario, scenario.geA(), Instant.now().minus(Duration.ofMinutes(5)), 90, true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        execution.start(scenario.geA().getId(), scenario.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID()));
        VisitSession started = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.STARTED, started.getStatus());
        assertThrows(VisitOperationsConflictException.class, () -> entitlementOperations.restoreInterruptedSession(
                scenario.admin().getId(), scenario.sessionId(), new VisitEntitlementRestoreCommand(
                        started.getVersion(), "GE_FAILURE", UUID.randomUUID())));
        UUID interruptionId = UUID.randomUUID();
        var interruption = new VisitEntitlementRestoreCommand(started.getVersion(), "GE_FAILURE", interruptionId);
        entitlementOperations.documentInterruption(scenario.admin().getId(), scenario.sessionId(), interruption);
        entitlementOperations.documentInterruption(scenario.admin().getId(), scenario.sessionId(), interruption);
        VisitSession interrupted = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.INTERRUPTED, interrupted.getStatus());
        Integer balanceBeforeRestore = jdbc.queryForObject(
                "select available_credits from tenant_visit_entitlement_accounts where user_id=?",
                Integer.class, scenario.tenant().getId());
        UUID restorationId = UUID.randomUUID();
        var restoration = new VisitEntitlementRestoreCommand(interrupted.getVersion(), "GE_FAILURE", restorationId);
        entitlementOperations.restoreInterruptedSession(scenario.admin().getId(), scenario.sessionId(), restoration);
        entitlementOperations.restoreInterruptedSession(scenario.admin().getId(), scenario.sessionId(), restoration);
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='OE_OPERATIONAL_RESTORE'",
                Integer.class, scenario.sessionId()));
        assertEquals(balanceBeforeRestore + 1, jdbc.queryForObject(
                "select available_credits from tenant_visit_entitlement_accounts where user_id=?",
                Integer.class, scenario.tenant().getId()));
        assertThrows(VisitOperationsConflictException.class, () -> entitlementOperations.restoreInterruptedSession(
                scenario.admin().getId(), scenario.sessionId(), new VisitEntitlementRestoreCommand(
                        interrupted.getVersion(), "GE_FAILURE", UUID.randomUUID())));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void declinedPendingProposalClearsPendingStateOnceAndPreservesHold() {
        RecommendationScenario scenario = recommendationScenario(false);
        scheduleForExecution(scenario, scenario.geA(), Instant.now().minus(Duration.ofMinutes(5)), 90, true);
        assertTrue(entitlements.reserve(scenario.tenant().getId(), scenario.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        jdbc.update("update visit_sessions set tenant_confirmation_state='PENDING' where id=?", scenario.sessionId());
        UUID operationId = UUID.randomUUID();
        GroundVisitContactCommand decline = new GroundVisitContactCommand(
                "TENANT_DECLINED_RESCHEDULE", null, "TENANT_LATE", operationId);
        var first = execution.reportContact(scenario.geA().getId(), scenario.sessionId(), decline);
        var repeat = execution.reportContact(scenario.geA().getId(), scenario.sessionId(), decline);
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, first.status());
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, repeat.status());
        assertEquals("REJECTED", sessions.findById(scenario.sessionId()).orElseThrow().getTenantConfirmationState());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where idempotency_key=?",
                Integer.class, "CONTACT:" + scenario.sessionId() + ":" + operationId));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "GE_DECLINED_RESCHEDULE:" + scenario.sessionId() + ":" + operationId));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void consentedAlternateGeRescheduleLeavesFormerGeUnrelatedReservationIntact() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        Instant formerTime = Instant.now().minus(Duration.ofMinutes(5));
        scheduleForExecution(scenario, scenario.geA(), formerTime, 45, false);
        VisitSession unrelated = createNeighborReservation(scenario, scenario.geA(),
                "Unrelated GE A booking", 22.72, 75.88, scenario.desiredAt(), 45, VisitSessionStatus.SCHEDULED);
        Long unrelatedVersion = unrelated.getVersion();
        UUID operationId = UUID.randomUUID();
        var result = execution.reportContact(scenario.geA().getId(), scenario.sessionId(),
                new GroundVisitContactCommand("TENANT_ACCEPTED_RESCHEDULE", scenario.desiredAt(),
                        "TENANT_LATE", operationId));
        VisitSession moved = sessions.findById(scenario.sessionId()).orElseThrow();
        VisitSession undisturbed = sessions.findById(unrelated.getId()).orElseThrow();
        assertEquals(VisitSessionStatus.SCHEDULED, result.status());
        assertEquals(scenario.geB().getId(), moved.getRepresentative().getId());
        assertEquals(scenario.desiredAt(), moved.getScheduledAt());
        assertEquals(VisitSessionStatus.SCHEDULED, undisturbed.getStatus());
        assertEquals(unrelatedVersion, undisturbed.getVersion());
        assertEquals(scenario.geA().getId(), undisturbed.getRepresentative().getId());
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='REPAIR_REQUIRED'",
                Integer.class, unrelated.getId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "ASSISTED_RESCHEDULE_FORMER_GE:" + operationId));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "ASSISTED_RESCHEDULE_NEW_GE:" + operationId));
        assertNoV36Overlaps();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void startCodeStatusExpiresAtServerBoundary() {
        RecommendationScenario scenario = recommendationScenario(false);
        scheduleForExecution(scenario, scenario.geA(), Instant.now().minus(Duration.ofMinutes(5)), 90, true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        assertTrue(execution.getStartCodeStatus(scenario.geA().getId(), scenario.sessionId()).challengeAvailable());
        jdbc.update("update visit_start_challenges set expires_at=? where session_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), scenario.sessionId());
        assertFalse(execution.getStartCodeStatus(scenario.geA().getId(), scenario.sessionId()).challengeAvailable());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void legacyUnverifiedStartCanBeReopenedByOperationsWithHistoryEventPreserved() {
        RecommendationScenario scenario = recommendationScenario(false);
        Instant originalStart = Instant.now().minus(Duration.ofHours(2));
        scheduleForExecution(scenario, scenario.geA(), originalStart, 45, true);
        jdbc.update("update visit_sessions set status='REPAIR_REQUIRED',repair_state='REQUIRED',tenant_confirmation_state='PENDING',started_at=?,execution_duration_snapshot_minutes=45,expected_end_at=? where id=?",
                java.sql.Timestamp.from(originalStart.plus(Duration.ofMinutes(5))),
                java.sql.Timestamp.from(originalStart.plus(Duration.ofMinutes(50))), scenario.sessionId());
        jdbc.update("insert into visit_execution_events(session_id,event_type,reason_code,metadata,idempotency_key) values (?,'LEGACY_START_REVIEW','PRE_PACKAGE_3_START_UNVERIFIED',?::jsonb,?)",
                scenario.sessionId(), "{\"legacyStartedAt\":\"" + originalStart.plus(Duration.ofMinutes(5)) + "\"}",
                "LEGACY_START_REVIEW:" + scenario.sessionId());
        VisitSession pending = sessions.findById(scenario.sessionId()).orElseThrow();
        var reopened = repairOperations.reopen(scenario.admin().getId(), scenario.sessionId(),
                new ExpectedVisitSessionVersion(pending.getVersion()));
        assertNull(reopened.previouslyScheduledAt());
        VisitSession draft = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.DRAFT, draft.getStatus());
        assertNull(draft.getStartedAt());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='LEGACY_START_REVIEW' and metadata->>'legacyStartedAt' is not null",
                Integer.class, scenario.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void operationsCanPageToAndReopenTheTwentyFirstRepairCase() {
        RecommendationScenario scenario = recommendationScenario(false);
        long priorCases = repairOperations.list(scenario.admin().getId(), 0, 20).totalElements();
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            VisitSession repair = new VisitSession();
            repair.setTenant(scenario.tenant());
            repair.setCity(scenario.locality().getCity());
            repair.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
            repair.setRepairState("REQUIRED");
            ids.add(sessions.saveAndFlush(repair).getId());
        }
        var firstPage = repairOperations.list(scenario.admin().getId(), 0, 20);
        int laterPageNumber = (int) ((priorCases + 20) / 20);
        var laterPage = repairOperations.list(scenario.admin().getId(), laterPageNumber, 20);
        assertEquals(20, firstPage.items().size());
        assertTrue(laterPageNumber >= 1);
        assertEquals(priorCases + 21, laterPage.totalElements());
        var last = laterPage.items().stream().filter(item -> item.sessionId().equals(ids.get(20)))
                .findFirst().orElseThrow();
        repairOperations.reopen(scenario.admin().getId(), last.sessionId(),
                new ExpectedVisitSessionVersion(last.version()));
        assertEquals(VisitSessionStatus.DRAFT, sessions.findById(last.sessionId()).orElseThrow().getStatus());
        assertEquals(priorCases + 20, repairOperations.list(scenario.admin().getId(), laterPageNumber, 20).totalElements());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void laterSuccessfulContactSupersedesOldNoAnswerEvidence() {
        RecommendationScenario scenario = recommendationScenario(false);
        Instant slot = Instant.now().minus(Duration.ofMinutes(30));
        scheduleForExecution(scenario, scenario.geA(), slot, 45, true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        Instant first = Instant.now().minus(Duration.ofMinutes(12));
        insertContactEvidence(scenario, first, "NO_ANSWER");
        insertContactEvidence(scenario, first.plus(Duration.ofMinutes(5)), "NO_ANSWER");
        insertContactEvidence(scenario, first.plus(Duration.ofMinutes(6)), "CONNECTED");
        assertThrows(VisitOperationsConflictException.class,
                () -> execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId()));
        assertEquals(VisitSessionStatus.SCHEDULED, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW'",
                Integer.class, scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tenantConfirmationSupersedesOldUnansweredEvidenceUntilFreshAttemptsAccumulate() {
        RecommendationScenario scenario = recommendationScenario(false);
        scheduleForExecution(scenario, scenario.geA(), Instant.now().minus(Duration.ofMinutes(30)), 45, true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        Instant first = Instant.now().minus(Duration.ofMinutes(15));
        insertContactEvidence(scenario, first, "NO_ANSWER");
        insertContactEvidence(scenario, first.plus(Duration.ofMinutes(5)), "NO_ANSWER");
        Instant confirmed = first.plus(Duration.ofMinutes(6));
        jdbc.update("update visit_sessions set tenant_confirmed_at=? where id=?",
                java.sql.Timestamp.from(confirmed), scenario.sessionId());
        assertThrows(VisitOperationsConflictException.class,
                () -> execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId()));
        insertContactEvidence(scenario, confirmed.plusSeconds(30), "NO_ANSWER");
        insertContactEvidence(scenario, confirmed.plus(Duration.ofMinutes(6)), "NO_ANSWER");
        assertEquals(VisitSessionStatus.PROVISIONAL_NO_SHOW,
                execution.markProvisionalNoShow(scenario.geA().getId(), scenario.sessionId()).status());
    }

    private void insertContactEvidence(RecommendationScenario scenario, Instant at, String outcome) {
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,occurred_at,reason_code,metadata,idempotency_key) values (?,?,'CONTACT_ATTEMPT',?,'TENANT_UNREACHABLE',?::jsonb,?)",
                scenario.sessionId(), scenario.geA().getId(), java.sql.Timestamp.from(at),
                "{\"outcome\":\"" + outcome + "\"}", "EVIDENCE:" + UUID.randomUUID());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void consentedRescheduleConfirmsOnlyARevalidatedPropertyWindowTime() {
        RecommendationScenario valid = recommendationScenario(false);
        var validPlan = operations.recommend(valid.admin().getId(), valid.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(valid.geA().getId()))
                .findFirst().orElseThrow();
        scheduleForExecution(valid, valid.geA(), Instant.now().minus(Duration.ofMinutes(5)),
                validPlan.durationMinutes(), false);
        assertTrue(entitlements.reserve(valid.tenant().getId(), valid.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        UUID validOperation = UUID.randomUUID();
        var confirmed = execution.reportContact(valid.geA().getId(), valid.sessionId(),
                new GroundVisitContactCommand("TENANT_ACCEPTED_RESCHEDULE", validPlan.scheduledAt(),
                        "TENANT_LATE", validOperation));
        assertEquals(VisitSessionStatus.SCHEDULED, confirmed.status());
        assertEquals(validPlan.scheduledAt(), confirmed.scheduledAt());
        assertEquals("CONFIRMED", confirmed.tenantConfirmationState());
        assertEquals("NONE", confirmed.repairState());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "ASSISTED_RESCHEDULE_CONFIRMED:" + validOperation));

        RecommendationScenario shortDelay = recommendationScenario(false);
        var shortPlan = operations.recommend(shortDelay.admin().getId(), shortDelay.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(shortDelay.geA().getId()))
                .findFirst().orElseThrow();
        Instant tenMinutesLate = shortPlan.scheduledAt();
        scheduleForExecution(shortDelay, shortDelay.geA(), tenMinutesLate.minus(Duration.ofMinutes(10)),
                shortPlan.durationMinutes(), false);
        assertTrue(entitlements.reserve(shortDelay.tenant().getId(), shortDelay.sessionId(),
                Instant.now().plus(Duration.ofDays(7))));
        var shortConfirmation = execution.reportContact(shortDelay.geA().getId(), shortDelay.sessionId(),
                new GroundVisitContactCommand("TENANT_CONFIRMED", tenMinutesLate, "TENANT_LATE", UUID.randomUUID()));
        assertEquals(VisitSessionStatus.SCHEDULED, shortConfirmation.status());
        assertEquals(tenMinutesLate, shortConfirmation.scheduledAt());
        assertEquals("CONFIRMED", shortConfirmation.tenantConfirmationState());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + shortDelay.sessionId()));

        RecommendationScenario longDelay = recommendationScenario(false);
        var longPlan = operations.recommend(longDelay.admin().getId(), longDelay.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(longDelay.geA().getId()))
                .findFirst().orElseThrow();
        scheduleForExecution(longDelay, longDelay.geA(), longPlan.scheduledAt(), longPlan.durationMinutes(), false);
        assertTrue(entitlements.reserve(longDelay.tenant().getId(), longDelay.sessionId(),
                Instant.now().plus(Duration.ofDays(7))));
        Instant fortyMinutesLate = longPlan.scheduledAt().plus(Duration.ofMinutes(40));
        var longRecovery = execution.reportContact(longDelay.geA().getId(), longDelay.sessionId(),
                new GroundVisitContactCommand("TENANT_CONFIRMED", fortyMinutesLate, "TENANT_LATE", UUID.randomUUID()));
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, longRecovery.status());
        assertEquals("REQUIRED", longRecovery.repairState());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + longDelay.sessionId()), "material delay must retain the existing hold");

        RecommendationScenario outsideProperty = recommendationScenario(false);
        var outsidePlan = operations.recommend(outsideProperty.admin().getId(), outsideProperty.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(outsideProperty.geA().getId()))
                .findFirst().orElseThrow();
        scheduleForExecution(outsideProperty, outsideProperty.geA(), Instant.now().minus(Duration.ofMinutes(5)),
                outsidePlan.durationMinutes(), false);
        assertTrue(entitlements.reserve(outsideProperty.tenant().getId(), outsideProperty.sessionId(),
                Instant.now().plus(Duration.ofDays(7))));
        Instant outsideConfirmedWindow = outsideProperty.desiredAt().plus(Duration.ofMinutes(100));
        UUID invalidOperation = UUID.randomUUID();
        var needsOperations = execution.reportContact(outsideProperty.geA().getId(), outsideProperty.sessionId(),
                new GroundVisitContactCommand("TENANT_ACCEPTED_RESCHEDULE", outsideConfirmedWindow,
                        "TENANT_LATE", invalidOperation));
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, needsOperations.status());
        assertEquals("REQUIRED", needsOperations.repairState());
        assertEquals("PENDING", needsOperations.tenantConfirmationState());
        assertEquals(outsideConfirmedWindow, needsOperations.tenantEtaAt());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "RESCHEDULE_REPAIR_REQUIRED:" + invalidOperation));
    }

    private void deliverOutbox(CountDownLatch ready, CountDownLatch gate) {
        ready.countDown();
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Outbox worker gate timed out");
            outboxWorker.deliverBatch();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lateCurrentStartShiftsOnlyInfeasibleDownstreamAndLeavesBufferedBookingUnchanged() {
        RecommendationScenario current = recommendationScenario(false);
        RecommendationScenario downstream = additionalRecommendationSession(current);
        RecommendationScenario buffered = additionalRecommendationSession(current);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var currentPlan = operations.recommend(current.admin().getId(), current.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        var downstreamPlan = operations.recommend(downstream.admin().getId(), downstream.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        Instant currentStart = Instant.now().minusSeconds(10 * 60L)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Instant downstreamStart = currentStart.plus(Duration.ofMinutes(currentPlan.durationMinutes() + 20L));
        Instant bufferedStart = downstreamStart.plus(Duration.ofMinutes(downstreamPlan.durationMinutes() + 30L));
        scheduleForExecution(current, current.geA(), currentStart, currentPlan.durationMinutes(), true);
        scheduleForExecution(downstream, current.geA(), downstreamStart, downstreamPlan.durationMinutes(), true);
        scheduleForExecution(buffered, current.geA(), bufferedStart, downstreamPlan.durationMinutes(), true);
        assertTrue(entitlements.reserve(current.tenant().getId(), current.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        assertTrue(entitlements.reserve(current.tenant().getId(), downstream.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        assertTrue(entitlements.reserve(current.tenant().getId(), buffered.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        GroundExecutiveSchedulingProfile alternateProfile = schedulingProfiles
                .findByGroundExecutiveUserId(current.geB().getId()).orElseThrow();
        alternateProfile.setSchedulingActive(false);
        schedulingProfiles.saveAndFlush(alternateProfile);

        execution.markArrived(current.geA().getId(), current.sessionId());
        var code = execution.issueStartCode(current.tenant().getId(), current.sessionId());
        UUID operationId = UUID.randomUUID();
        var started = execution.start(current.geA().getId(), current.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), operationId));

        assertEquals(VisitSessionStatus.STARTED, started.status());
        VisitSession repaired = sessions.findById(downstream.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.SCHEDULED, repaired.getStatus());
        assertEquals("NONE", repaired.getRepairState());
        assertEquals("CONFIRMED", repaired.getTenantConfirmationState(),
                "an already-confirmed booking must remain confirmed after its safe small automatic shift");
        var unchangedCandidates = recommendations.assessLiveRepair(repaired, List.of(downstreamStart), false,
                current.geA().getId(), 5);
        assertFalse(unchangedCandidates.stream().anyMatch(candidate -> candidate.geId().equals(current.geA().getId())
                && candidate.start().equals(downstreamStart)), "current GE should not retain this infeasible time: "
                + unchangedCandidates);
        long appliedShift = Duration.between(downstreamStart, repaired.getScheduledAt()).toMinutes();
        assertTrue(appliedShift > 0 && appliedShift <= 10, "the downstream repair should apply a safe small shift; shift="
                + appliedShift + ", current end=" + started.expectedEndAt() + ", downstream=" + repaired.getScheduledAt());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "LIVE_REPAIR_SHIFTED:" + operationId + ":" + downstream.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + downstream.sessionId()));
        VisitSession bufferedSession = sessions.findById(buffered.sessionId()).orElseThrow();
        assertEquals(bufferedStart, bufferedSession.getScheduledAt(), "a later feasible booking should remain unchanged");
        assertEquals("NONE", bufferedSession.getRepairState());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lateStartKeepsPendingDownstreamRepairPendingUntilCurrentProposalIsAccepted() {
        RecommendationScenario current = recommendationScenario(false);
        RecommendationScenario downstream = additionalRecommendationSession(current);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var currentPlan = operations.recommend(current.admin().getId(), current.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        var downstreamPlan = operations.recommend(downstream.admin().getId(), downstream.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        Instant currentStart = Instant.now().minus(Duration.ofMinutes(10))
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Instant downstreamStart = currentStart.plus(Duration.ofMinutes(currentPlan.durationMinutes() + 20L));
        scheduleForExecution(current, current.geA(), currentStart, currentPlan.durationMinutes(), true);
        scheduleForExecution(downstream, current.geA(), downstreamStart, downstreamPlan.durationMinutes(), true);
        assertTrue(entitlements.reserve(current.tenant().getId(), current.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        assertTrue(entitlements.reserve(downstream.tenant().getId(), downstream.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        VisitSession confirmedDownstream = sessions.findById(downstream.sessionId()).orElseThrow();
        operations.reschedule(downstream.admin().getId(), downstream.sessionId(), new RescheduleVisitSessionCommand(
                confirmedDownstream.getVersion(), downstreamStart.plus(Duration.ofMinutes(1)), "Asia/Kolkata"));
        VisitSession pending = sessions.findById(downstream.sessionId()).orElseThrow();
        assertEquals("PENDING", pending.getTenantConfirmationState());

        GroundExecutiveSchedulingProfile alternate = schedulingProfiles.findByGroundExecutiveUserId(current.geB().getId()).orElseThrow();
        alternate.setSchedulingActive(false);
        schedulingProfiles.saveAndFlush(alternate);
        execution.markArrived(current.geA().getId(), current.sessionId());
        var code = execution.issueStartCode(current.tenant().getId(), current.sessionId());
        UUID startOperation = UUID.randomUUID();
        var started = execution.start(current.geA().getId(), current.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), startOperation));
        assertEquals(VisitSessionStatus.STARTED, started.status());

        VisitSession repaired = sessions.findById(downstream.sessionId()).orElseThrow();
        long shiftMinutes = Duration.between(pending.getScheduledAt(), repaired.getScheduledAt()).toMinutes();
        assertTrue(shiftMinutes > 0 && shiftMinutes <= 10, "expected a safe nonmaterial shift, got " + shiftMinutes);
        assertEquals(VisitSessionStatus.SCHEDULED, repaired.getStatus());
        assertEquals("PENDING", repaired.getTenantConfirmationState());
        assertEquals("NONE", repaired.getRepairState());
        assertNull(repaired.getTenantConfirmedAt());
        assertTrue(entitlements.hasReservation(repaired.getId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RESERVE'",
                Integer.class, repaired.getId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type in ('CONSUME','RELEASE')",
                Integer.class, repaired.getId()));
        String proposalKey = "LIVE_REPAIR_PROPOSED:" + startOperation + ":" + repaired.getId();
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=? and event_type='VISIT_TIME_PROPOSED'",
                Integer.class, proposalKey));
        String tenantMessage = jdbc.queryForObject("select message from visit_notification_outbox where event_key=?",
                String.class, proposalKey);
        assertTrue(tenantMessage.contains("not confirmed"));

        var accepted = execution.confirmTenant(downstream.tenant().getId(), repaired.getId(),
                new TenantVisitConfirmationCommand("ACCEPT_RESCHEDULE", null, UUID.randomUUID(), repaired.getVersion()));
        assertEquals("CONFIRMED", accepted.tenantConfirmationState());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RESERVE'",
                Integer.class, repaired.getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void liveRepairReassignsHardWindowVisitAndFormerGeLosesAccess() {
        LateStartPair pair = prepareLateStartPair(true);
        UUID operationId = UUID.randomUUID();
        var started = execution.start(pair.current().geA().getId(), pair.current().sessionId(),
                new VisitOtpStartCommand(pair.startCode().generation(), pair.startCode().code(), operationId));
        assertEquals(VisitSessionStatus.STARTED, started.status());

        VisitSession repaired = sessions.findById(pair.downstream().sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.SCHEDULED, repaired.getStatus());
        assertEquals(pair.downstream().geB().getId(), repaired.getRepresentative().getId());
        assertEquals(pair.downstreamStart(), repaired.getScheduledAt(), "the hard property/tenant window must keep the confirmed time");
        assertEquals("NONE", repaired.getRepairState());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?", Integer.class,
                "LIVE_REPAIR_REASSIGNED:" + operationId + ":" + pair.downstream().sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?", Integer.class,
                "LIVE_REPAIR_FORMER_GE:" + operationId + ":" + pair.downstream().sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?", Integer.class,
                "LIVE_REPAIR_NEW_GE:" + operationId + ":" + pair.downstream().sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + pair.downstream().sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, pair.downstream().sessionId()));

        assertThrows(AccessDeniedException.class,
                () -> execution.getTenantContact(pair.current().geA().getId(), repaired.getId()));
        assertThrows(AccessDeniedException.class,
                () -> execution.markArrived(pair.current().geA().getId(), repaired.getId()));
        assertThrows(AccessDeniedException.class, () -> execution.start(pair.current().geA().getId(), repaired.getId(),
                new VisitOtpStartCommand(1, "123456", UUID.randomUUID())));
        assertThrows(jakarta.persistence.EntityNotFoundException.class,
                () -> execution.getAssignedExecution(pair.current().geA().getId(), repaired.getId()));
        assertEquals(repaired.getId(), execution.getAssignedExecution(pair.current().geB().getId(), repaired.getId()).sessionId());
        assertEquals(repaired.getId(), execution.getTenantContact(pair.current().geB().getId(), repaired.getId()).sessionId());
        assertNoV36Overlaps();
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, pair.current().sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void liveRepairAlternateGeKeepsPendingDownstreamProposalUnconfirmed() {
        LateStartPair pair = prepareLateStartPair(true);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            VisitSession session = sessions.findLockedById(pair.downstream().sessionId()).orElseThrow();
            session.setTenantConfirmationState("PENDING");
            session.setTenantConfirmedAt(null);
            session.setTenantConfirmedBy(null);
            sessions.saveAndFlush(session);
        });
        VisitSession pending = sessions.findById(pair.downstream().sessionId()).orElseThrow();
        assertEquals("PENDING", pending.getTenantConfirmationState());

        UUID operationId = UUID.randomUUID();
        var started = execution.start(pair.current().geA().getId(), pair.current().sessionId(),
                new VisitOtpStartCommand(pair.startCode().generation(), pair.startCode().code(), operationId));
        assertEquals(VisitSessionStatus.STARTED, started.status());
        VisitSession repaired = sessions.findById(pair.downstream().sessionId()).orElseThrow();
        assertEquals(pair.downstream().geB().getId(), repaired.getRepresentative().getId());
        assertEquals(pair.downstreamStart(), repaired.getScheduledAt());
        assertEquals("PENDING", repaired.getTenantConfirmationState());
        assertNull(repaired.getTenantConfirmedAt());
        String proposalKey = "LIVE_REPAIR_PROPOSED:" + operationId + ":" + repaired.getId();
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=? and event_type='VISIT_TIME_PROPOSED'",
                Integer.class, proposalKey));
        assertTrue(jdbc.queryForObject("select message from visit_notification_outbox where event_key=?", String.class,
                proposalKey).contains("not confirmed"));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RESERVE'",
                Integer.class, repaired.getId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type in ('CONSUME','RELEASE')",
                Integer.class, repaired.getId()));
        assertThrows(AccessDeniedException.class,
                () -> execution.getTenantContact(pair.current().geA().getId(), repaired.getId()));
        assertEquals(repaired.getId(), execution.getAssignedExecution(pair.downstream().geB().getId(), repaired.getId()).sessionId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void unrecoverableDownstreamIsEscalatedWithoutCancellingOrReleasingItsHold() {
        LateStartPair pair = prepareLateStartPair(false);
        UUID operationId = UUID.randomUUID();
        var started = execution.start(pair.current().geA().getId(), pair.current().sessionId(),
                new VisitOtpStartCommand(pair.startCode().generation(), pair.startCode().code(), operationId));
        assertEquals(VisitSessionStatus.STARTED, started.status());

        VisitSession unresolved = sessions.findById(pair.downstream().sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, unresolved.getStatus());
        assertEquals("REQUIRED", unresolved.getRepairState());
        assertEquals(pair.downstreamStart(), unresolved.getScheduledAt());
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, pair.downstream().sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + pair.downstream().sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "VISIT_REPAIR_REQUIRED:" + unresolved.getId() + ":" + operationId));
        Integer operationsIntents = jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key like ?",
                Integer.class, "ASSISTED_RESCHEDULE:" + unresolved.getId() + ":" + operationId + ":%");
        Integer uniqueOperationsIntents = jdbc.queryForObject("select count(distinct event_key) from visit_notification_outbox where event_key like ?",
                Integer.class, "ASSISTED_RESCHEDULE:" + unresolved.getId() + ":" + operationId + ":%");
        assertNotNull(operationsIntents);
        assertTrue(operationsIntents > 0);
        assertEquals(operationsIntents, uniqueOperationsIntents, "Operations intent must be deduplicated per recipient");
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, pair.current().sessionId()));
        assertNoV36Overlaps();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void startedGroundExecutiveIsNeverDisplacedAndLateVisitMovesToAlternate() {
        RecommendationScenario target = recommendationScenario(false);
        RecommendationScenario active = additionalRecommendationSession(target);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var targetPlan = operations.recommend(target.admin().getId(), target.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(target.geA().getId()))
                .findFirst().orElseThrow();
        var activePlan = operations.recommend(active.admin().getId(), active.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(target.geA().getId()))
                .findFirst().orElseThrow();
        Instant activeStart = Instant.now().minus(Duration.ofMinutes(10));
        scheduleForExecution(active, target.geA(), activeStart, activePlan.durationMinutes(), true);
        scheduleForExecution(target, target.geA(), target.desiredAt(), targetPlan.durationMinutes(), true);
        execution.markArrived(active.geA().getId(), active.sessionId());
        var activeCode = execution.issueStartCode(active.tenant().getId(), active.sessionId());
        var activeStarted = execution.start(active.geA().getId(), active.sessionId(),
                new VisitOtpStartCommand(activeCode.generation(), activeCode.code(), UUID.randomUUID()));
        assertEquals(VisitSessionStatus.STARTED, activeStarted.status());
        execution.markArrived(target.geA().getId(), target.sessionId());
        var targetCode = execution.issueStartCode(target.tenant().getId(), target.sessionId());
        UUID reassignmentOperation = UUID.randomUUID();

        var recovered = execution.start(target.geA().getId(), target.sessionId(),
                new VisitOtpStartCommand(targetCode.generation(), targetCode.code(), reassignmentOperation));
        VisitSession uninterrupted = sessions.findById(active.sessionId()).orElseThrow();
        VisitSession reassigned = sessions.findById(target.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.STARTED, uninterrupted.getStatus());
        assertEquals(target.geA().getId(), uninterrupted.getRepresentative().getId());
        assertEquals("ALTERNATE_GE_ASSIGNED", recovered.resultCode());
        assertEquals(VisitSessionStatus.SCHEDULED, reassigned.getStatus());
        assertEquals(target.geB().getId(), reassigned.getRepresentative().getId());
        assertEquals(target.desiredAt(), reassigned.getScheduledAt());
        assertThrows(AccessDeniedException.class, () -> execution.markArrived(target.geA().getId(), target.sessionId()));
        assertEquals(target.sessionId(), execution.getAssignedExecution(target.geB().getId(), target.sessionId()).sessionId());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?", Integer.class,
                "GE_REMOVED:" + target.sessionId() + ":" + reassignmentOperation));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?", Integer.class,
                "GE_ASSIGNED:" + target.sessionId() + ":" + reassignmentOperation));
        assertNoV36Overlaps();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void startedGroundExecutiveWithoutAlternateQueuesImmediateRecoveryForLateVisit() {
        RecommendationScenario target = recommendationScenario(false);
        RecommendationScenario active = additionalRecommendationSession(target);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var targetPlan = operations.recommend(target.admin().getId(), target.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(target.geA().getId()))
                .findFirst().orElseThrow();
        var activePlan = operations.recommend(active.admin().getId(), active.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(target.geA().getId()))
                .findFirst().orElseThrow();
        scheduleForExecution(active, target.geA(), Instant.now().minus(Duration.ofMinutes(10)), activePlan.durationMinutes(), true);
        scheduleForExecution(target, target.geA(), target.desiredAt(), targetPlan.durationMinutes(), true);
        assertTrue(entitlements.reserve(active.tenant().getId(), active.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        assertTrue(entitlements.reserve(target.tenant().getId(), target.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        execution.markArrived(active.geA().getId(), active.sessionId());
        var activeCode = execution.issueStartCode(active.tenant().getId(), active.sessionId());
        assertEquals(VisitSessionStatus.STARTED, execution.start(active.geA().getId(), active.sessionId(),
                new VisitOtpStartCommand(activeCode.generation(), activeCode.code(), UUID.randomUUID())).status());

        execution.markArrived(target.geA().getId(), target.sessionId());
        var targetCode = execution.issueStartCode(target.tenant().getId(), target.sessionId());
        GroundExecutiveSchedulingProfile alternate = schedulingProfiles.findByGroundExecutiveUserId(target.geB().getId()).orElseThrow();
        alternate.setSchedulingActive(false);
        schedulingProfiles.saveAndFlush(alternate);
        UUID operationId = UUID.randomUUID();

        var recovery = execution.start(target.geA().getId(), target.sessionId(),
                new VisitOtpStartCommand(targetCode.generation(), targetCode.code(), operationId));
        VisitSession undisturbed = sessions.findById(active.sessionId()).orElseThrow();
        VisitSession assisted = sessions.findById(target.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.STARTED, undisturbed.getStatus());
        assertEquals(target.geA().getId(), undisturbed.getRepresentative().getId());
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, assisted.getStatus());
        assertEquals("REQUIRED", assisted.getRepairState());
        assertEquals(target.desiredAt(), assisted.getScheduledAt());
        assertEquals("REPAIR_REQUIRED", recovery.resultCode());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + target.sessionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, target.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?", Integer.class,
                "ACTIVE_GE_RECOVERY:" + operationId));
        assertTrue(jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key like ?", Integer.class,
                "ASSISTED_RESCHEDULE:" + target.sessionId() + ":" + operationId + ":%") > 0);
        assertNoV36Overlaps();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void needMoreTimeRepairsDownstreamReservationInPostgres() {
        RecommendationScenario current = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var currentPlan = operations.recommend(current.admin().getId(), current.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        scheduleForExecution(current, current.geA(), Instant.now().minus(Duration.ofMinutes(10)), currentPlan.durationMinutes(), true);
        assertTrue(entitlements.reserve(current.tenant().getId(), current.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        execution.markArrived(current.geA().getId(), current.sessionId());
        var code = execution.issueStartCode(current.tenant().getId(), current.sessionId());
        UUID startOperation = UUID.randomUUID();
        var started = execution.start(current.geA().getId(), current.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), startOperation));
        assertEquals(VisitSessionStatus.STARTED, started.status());

        RecommendationScenario downstream = additionalRecommendationSession(current);
        var downstreamPlan = operations.recommend(downstream.admin().getId(), downstream.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        Instant oldStart = started.expectedEndAt();
        scheduleForExecution(downstream, current.geA(), oldStart, downstreamPlan.durationMinutes(), true);
        assertTrue(entitlements.reserve(downstream.tenant().getId(), downstream.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        UUID extensionOperation = UUID.randomUUID();
        var extended = execution.needMoreTime(current.geA().getId(), current.sessionId(),
                new GroundVisitMoreTimeCommand(5, extensionOperation));
        VisitSession repaired = sessions.findById(downstream.sessionId()).orElseThrow();
        assertEquals(started.expectedEndAt().plus(Duration.ofMinutes(5)), extended.expectedEndAt());
        assertEquals(VisitSessionStatus.SCHEDULED, repaired.getStatus());
        assertTrue(repaired.getScheduledAt().isAfter(oldStart), "downstream reservation must move beyond the extended active visit");
        assertEquals("NONE", repaired.getRepairState());
        assertTrue(jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key in (?,?)", Integer.class,
                "LIVE_REPAIR_SHIFTED:" + extensionOperation + ":" + repaired.getId(),
                "LIVE_REPAIR_REASSIGNED:" + extensionOperation + ":" + repaired.getId()) >= 1);
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + repaired.getId()));
        assertNoV36Overlaps();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void needMoreTimeKeepsPendingDownstreamSmallRepairUnconfirmed() {
        RecommendationScenario current = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var currentPlan = operations.recommend(current.admin().getId(), current.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        scheduleForExecution(current, current.geA(), Instant.now().minus(Duration.ofMinutes(10)), currentPlan.durationMinutes(), true);
        assertTrue(entitlements.reserve(current.tenant().getId(), current.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        execution.markArrived(current.geA().getId(), current.sessionId());
        var code = execution.issueStartCode(current.tenant().getId(), current.sessionId());
        var started = execution.start(current.geA().getId(), current.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID()));
        assertEquals(VisitSessionStatus.STARTED, started.status());

        RecommendationScenario downstream = additionalRecommendationSession(current);
        var downstreamPlan = operations.recommend(downstream.admin().getId(), downstream.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        Instant originalEnd = started.expectedEndAt();
        Instant oldAppointment = originalEnd.plus(Duration.ofMinutes(21));
        scheduleForExecution(downstream, current.geA(), oldAppointment, downstreamPlan.durationMinutes(), true);
        assertTrue(entitlements.reserve(downstream.tenant().getId(), downstream.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        VisitSession confirmed = sessions.findById(downstream.sessionId()).orElseThrow();
        Instant pendingTime = originalEnd.plus(Duration.ofMinutes(20));
        operations.reschedule(downstream.admin().getId(), downstream.sessionId(),
                new RescheduleVisitSessionCommand(confirmed.getVersion(), pendingTime, "Asia/Kolkata"));
        VisitSession pending = sessions.findById(downstream.sessionId()).orElseThrow();
        assertEquals("PENDING", pending.getTenantConfirmationState());

        UUID operationId = UUID.randomUUID();
        var extended = execution.needMoreTime(current.geA().getId(), current.sessionId(),
                new GroundVisitMoreTimeCommand(15, operationId));
        VisitSession repaired = sessions.findById(downstream.sessionId()).orElseThrow();
        long shiftMinutes = Duration.between(pendingTime, repaired.getScheduledAt()).toMinutes();
        assertTrue(shiftMinutes >= 0 && shiftMinutes <= 10, "expected a safe small repair, got " + shiftMinutes);
        assertEquals(originalEnd.plus(Duration.ofMinutes(15)), extended.expectedEndAt());
        assertEquals("PENDING", repaired.getTenantConfirmationState());
        assertEquals(VisitSessionStatus.SCHEDULED, repaired.getStatus());
        assertNull(repaired.getTenantConfirmedAt());
        String proposalKey = "LIVE_REPAIR_PROPOSED:" + operationId + ":" + repaired.getId();
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=? and event_type='VISIT_TIME_PROPOSED'",
                Integer.class, proposalKey));
        assertTrue(jdbc.queryForObject("select message from visit_notification_outbox where event_key=?", String.class,
                proposalKey).contains("not confirmed"));
        assertTrue(entitlements.hasReservation(repaired.getId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='RESERVE'",
                Integer.class, repaired.getId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type in ('CONSUME','RELEASE')",
                Integer.class, repaired.getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void needMoreTimeEscalatesDownstreamWhenNoFeasibleRepairExists() {
        RecommendationScenario current = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var currentPlan = operations.recommend(current.admin().getId(), current.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        scheduleForExecution(current, current.geA(), Instant.now().minus(Duration.ofMinutes(10)), currentPlan.durationMinutes(), true);
        assertTrue(entitlements.reserve(current.tenant().getId(), current.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        execution.markArrived(current.geA().getId(), current.sessionId());
        var code = execution.issueStartCode(current.tenant().getId(), current.sessionId());
        var started = execution.start(current.geA().getId(), current.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID()));
        assertEquals(VisitSessionStatus.STARTED, started.status());

        RecommendationScenario downstream = additionalRecommendationSession(current);
        var downstreamPlan = operations.recommend(downstream.admin().getId(), downstream.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        Instant originalEnd = started.expectedEndAt();
        scheduleForExecution(downstream, current.geA(), originalEnd, downstreamPlan.durationMinutes(), true);
        setExactAvailability(downstream, originalEnd,
                originalEnd.plus(Duration.ofMinutes(downstreamPlan.durationMinutes())));
        assertTrue(entitlements.reserve(downstream.tenant().getId(), downstream.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        GroundExecutiveSchedulingProfile alternate = schedulingProfiles.findByGroundExecutiveUserId(current.geB().getId()).orElseThrow();
        alternate.setSchedulingActive(false);
        schedulingProfiles.saveAndFlush(alternate);

        UUID operationId = UUID.randomUUID();
        var extended = execution.needMoreTime(current.geA().getId(), current.sessionId(),
                new GroundVisitMoreTimeCommand(5, operationId));
        VisitSession unresolved = sessions.findById(downstream.sessionId()).orElseThrow();
        assertEquals(originalEnd.plus(Duration.ofMinutes(5)), extended.expectedEndAt());
        assertEquals(VisitSessionStatus.REPAIR_REQUIRED, unresolved.getStatus());
        assertEquals("REQUIRED", unresolved.getRepairState());
        assertEquals(originalEnd, unresolved.getScheduledAt());
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?", Integer.class,
                "VISIT_REPAIR_REQUIRED:" + unresolved.getId() + ":" + operationId));
        Integer operationsIntents = jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key like ?",
                Integer.class, "ASSISTED_RESCHEDULE:" + unresolved.getId() + ":" + operationId + ":%");
        assertNotNull(operationsIntents);
        assertTrue(operationsIntents > 0);
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "RESERVE:" + unresolved.getId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where session_id=? and event_type='CONSUME'",
                Integer.class, unresolved.getId()));
        assertNoV36Overlaps();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void operationsEditBetweenPlanAndApplyRollsBackStaleAttemptAndRetriesOnce() throws Exception {
        RecommendationScenario current = recommendationScenario(false);
        RecommendationScenario downstream = additionalRecommendationSession(current);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var currentPlan = operations.recommend(current.admin().getId(), current.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        var downstreamPlan = operations.recommend(downstream.admin().getId(), downstream.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        Instant currentStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant downstreamStart = currentStart.plus(Duration.ofMinutes(currentPlan.durationMinutes() + 20L));
        scheduleForExecution(current, current.geA(), currentStart, currentPlan.durationMinutes(), true);
        scheduleForExecution(downstream, current.geA(), downstreamStart, downstreamPlan.durationMinutes(), true);
        assertTrue(entitlements.reserve(current.tenant().getId(), current.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        assertTrue(entitlements.reserve(downstream.tenant().getId(), downstream.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        execution.markArrived(current.geA().getId(), current.sessionId());
        var code = execution.issueStartCode(current.tenant().getId(), current.sessionId());
        VisitSession before = sessions.findById(downstream.sessionId()).orElseThrow();
        Instant operationsTime = downstreamStart.plus(Duration.ofMinutes(20));

        jdbc.execute("CREATE SEQUENCE visit_repair_retry_probe_seq START WITH 1");
        jdbc.execute("CREATE FUNCTION visit_repair_retry_probe_fn() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.status='STARTED' AND OLD.status IS DISTINCT FROM 'STARTED' THEN "
                + "PERFORM nextval('visit_repair_retry_probe_seq'); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER visit_repair_retry_probe BEFORE UPDATE OF status ON visit_sessions "
                + "FOR EACH ROW EXECUTE FUNCTION visit_repair_retry_probe_fn()");

        CountDownLatch operationsLocked = new CountDownLatch(1);
        CountDownLatch operationsCommit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> operationsFailure = new AtomicReference<>();
        try {
            var edit = pool.submit(() -> {
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(txStatus -> {
                        VisitSession locked = sessions.findLockedById(downstream.sessionId()).orElseThrow();
                        operationsLocked.countDown();
                        try {
                            if (!operationsCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Operation edit gate timed out");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                        operations.reschedule(current.admin().getId(), downstream.sessionId(),
                                new RescheduleVisitSessionCommand(locked.getVersion(), operationsTime, "Asia/Kolkata"));
                    });
                } catch (Throwable error) { operationsFailure.set(error); throw error; }
            });
            assertTrue(operationsLocked.await(5, TimeUnit.SECONDS));
            UUID operationId = UUID.randomUUID();
            var start = pool.submit(() -> execution.start(current.geA().getId(), current.sessionId(),
                    new VisitOtpStartCommand(code.generation(), code.code(), operationId)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            boolean waitingOnPostgresLock = false;
            while (System.nanoTime() < deadline && !waitingOnPostgresLock) {
                Integer count = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                                + "and wait_event_type='Lock' and query ilike '%visit_sessions%'", Integer.class);
                waitingOnPostgresLock = count != null && count > 0;
                if (!waitingOnPostgresLock) Thread.sleep(50);
            }
            assertTrue(waitingOnPostgresLock, "the repair apply attempt must reach PostgreSQL row-lock wait after planning");
            operationsCommit.countDown();
            edit.get(15, TimeUnit.SECONDS);
            assertNull(operationsFailure.get());
            assertEquals(VisitSessionStatus.STARTED, start.get(20, TimeUnit.SECONDS).status());

            VisitSession after = sessions.findById(downstream.sessionId()).orElseThrow();
            assertEquals(operationsTime, after.getScheduledAt(), "retry must respect the committed Operations schedule");
            assertEquals(before.getVersion() + 1, after.getVersion(), "the stale attempt must not write a second version");
            assertEquals(0, jdbc.queryForObject("select count(*) from visit_execution_events where idempotency_key like ?",
                    Integer.class, "REPAIR:" + operationId + ":%"));
            assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where idempotency_key=?",
                    Integer.class, "START:" + operationId));
            assertEquals(0, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key like 'LIVE_REPAIR_%' and event_key like ?",
                    Integer.class, "%" + operationId + "%"), "the stale attempt must not publish repair notification intents");
            assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                    Integer.class, "CONSUME:" + current.sessionId()));
            assertEquals(1, jdbc.queryForObject("select last_value from visit_repair_retry_probe_seq", Integer.class),
                    "the stale attempt must roll back before the single successful START transition");
            assertEquals(VisitSessionStatus.STARTED, sessions.findById(current.sessionId()).orElseThrow().getStatus());
            assertNoV36Overlaps();
        } finally {
            operationsCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void postgresOverlapSqlStateAtStartRollsBackAndRetriesOnce() {
        RecommendationScenario current = recommendationScenario(false);
        Instant plannedStart = Instant.now().minus(Duration.ofMinutes(10));
        int duration = 60;
        scheduleForExecution(current, current.geA(), plannedStart, duration, true);
        execution.markArrived(current.geA().getId(), current.sessionId());
        var code = execution.issueStartCode(current.tenant().getId(), current.sessionId());

        jdbc.execute("CREATE SEQUENCE visit_start_overlap_pause_seq START WITH 1");
        jdbc.execute("CREATE FUNCTION visit_start_overlap_pause_fn() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.id=" + current.sessionId() + " AND NEW.status='STARTED' AND OLD.status<>'STARTED' "
                + "AND nextval('visit_start_overlap_pause_seq')=1 THEN RAISE EXCEPTION USING ERRCODE='23P01', "
                + "MESSAGE='simulated concurrent GE reservation'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER visit_start_overlap_pause BEFORE UPDATE OF status ON visit_sessions "
                + "FOR EACH ROW EXECUTE FUNCTION visit_start_overlap_pause_fn()");
        UUID operationId = UUID.randomUUID();
        assertEquals(VisitSessionStatus.STARTED, execution.start(current.geA().getId(), current.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), operationId)).status());
        assertEquals(2, jdbc.queryForObject("select last_value from visit_start_overlap_pause_seq", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                Integer.class, "CONSUME:" + current.sessionId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_execution_events where idempotency_key=?",
                Integer.class, "START:" + operationId));
        assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                Integer.class, "VISIT_STARTED:" + current.sessionId() + ":" + operationId));
        assertNoV36Overlaps();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentBookingCannotPreemptAValidStartAfterTheGeIsLocked() throws Exception {
        RecommendationScenario current = recommendationScenario(false);
        RecommendationScenario future = additionalRecommendationSession(current);
        Instant plannedStart = Instant.now().minus(Duration.ofMinutes(10));
        scheduleForExecution(current, current.geA(), plannedStart, 60, true);
        Instant futureStart = plannedStart.plus(Duration.ofMinutes(120));
        scheduleForExecution(future, current.geA(), futureStart, 60, true);
        execution.markArrived(current.geA().getId(), current.sessionId());
        var code = execution.issueStartCode(current.tenant().getId(), current.sessionId());
        jdbc.execute("CREATE FUNCTION visit_start_lock_pause_fn() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.session_id=" + current.sessionId() + " AND NEW.event_type='CONSUME' "
                + "THEN PERFORM pg_sleep(2); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER visit_start_lock_pause AFTER INSERT ON visit_entitlement_ledger "
                + "FOR EACH ROW EXECUTE FUNCTION visit_start_lock_pause_fn()");
        var pool = Executors.newSingleThreadExecutor();
        UUID operationId = UUID.randomUUID();
        try {
            var start = pool.submit(() -> execution.start(current.geA().getId(), current.sessionId(),
                    new VisitOtpStartCommand(code.generation(), code.code(), operationId)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            boolean planned = false;
            while (System.nanoTime() < deadline && !planned) {
                Integer sleeping = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                        + "and wait_event='PgSleep' and query ilike '%visit_entitlement_ledger%'", Integer.class);
                planned = sleeping != null && sleeping > 0;
                if (!planned) Thread.sleep(20);
            }
            assertTrue(planned);
            Instant conflictingStart = plannedStart.plus(Duration.ofMinutes(62));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                    "update visit_sessions set scheduled_at=?,reserved_end_at=?,version=version+1 where id=?",
                    java.sql.Timestamp.from(conflictingStart),
                    java.sql.Timestamp.from(conflictingStart.plus(Duration.ofMinutes(60))), future.sessionId()));
            assertEquals(VisitSessionStatus.STARTED, start.get(15, TimeUnit.SECONDS).status());
            assertEquals(futureStart, sessions.findById(future.sessionId()).orElseThrow().getScheduledAt());
            assertEquals(1, jdbc.queryForObject("select count(*) from visit_entitlement_ledger where idempotency_key=?",
                    Integer.class, "CONSUME:" + current.sessionId()));
            assertEquals(1, jdbc.queryForObject("select count(*) from visit_notification_outbox where event_key=?",
                    Integer.class, "VISIT_STARTED:" + current.sessionId() + ":" + operationId));
            assertNoV36Overlaps();
        } finally {
            pool.shutdownNow();
        }
    }

    private LateStartPair prepareLateStartPair(boolean alternateAvailable) {
        RecommendationScenario current = recommendationScenario(false);
        RecommendationScenario downstream = additionalRecommendationSession(current);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var currentPlan = operations.recommend(current.admin().getId(), current.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        var downstreamPlan = operations.recommend(downstream.admin().getId(), downstream.sessionId(), new RecommendationRequest(0L))
                .candidates().stream().filter(candidate -> candidate.groundExecutiveUserId().equals(current.geA().getId()))
                .findFirst().orElseThrow();
        Instant currentStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant downstreamStart = currentStart.plus(Duration.ofMinutes(currentPlan.durationMinutes() + 20L));
        scheduleForExecution(current, current.geA(), currentStart, currentPlan.durationMinutes(), true);
        scheduleForExecution(downstream, current.geA(), downstreamStart, downstreamPlan.durationMinutes(), true);
        setExactAvailability(downstream, downstreamStart, downstreamStart.plus(Duration.ofMinutes(downstreamPlan.durationMinutes())));
        assertTrue(entitlements.reserve(current.tenant().getId(), current.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        assertTrue(entitlements.reserve(downstream.tenant().getId(), downstream.sessionId(), Instant.now().plus(Duration.ofDays(7))));
        execution.markArrived(downstream.geA().getId(), downstream.sessionId());
        if (!alternateAvailable) {
            GroundExecutiveSchedulingProfile profile = schedulingProfiles.findByGroundExecutiveUserId(current.geB().getId()).orElseThrow();
            profile.setSchedulingActive(false);
            schedulingProfiles.saveAndFlush(profile);
        }
        execution.markArrived(current.geA().getId(), current.sessionId());
        var startCode = execution.issueStartCode(current.tenant().getId(), current.sessionId());
        return new LateStartPair(current, downstream, startCode, downstreamStart);
    }

    private void setExactAvailability(RecommendationScenario scenario, Instant start, Instant end) {
        new TransactionTemplate(transactionManager).executeWithoutResult(txStatus -> {
            for (PropertyVisitRequest request : requests.findLockedBySessionIdOrderByIdAsc(scenario.sessionId())) {
                request.setAvailabilityStartAt(start);
                request.setAvailabilityEndAt(end);
            }
            for (VisitSessionItem item : items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(scenario.sessionId())) {
                item.setAvailabilityStartAt(start);
                item.setAvailabilityEndAt(end);
            }
            requests.flush();
            items.flush();
        });
    }

    private void assertNoV36Overlaps() {
        Integer conflicts = jdbc.queryForObject("select count(*) from visit_sessions a join visit_sessions b "
                + "on a.id<b.id and a.representative_user_id=b.representative_user_id "
                + "and tstzrange(a.scheduled_at,a.reserved_end_at,'[)') && tstzrange(b.scheduled_at,b.reserved_end_at,'[)') "
                + "where a.status in ('SCHEDULED','STARTED') and b.status in ('SCHEDULED','STARTED')", Integer.class);
        assertEquals(0, conflicts);
    }

    private record LateStartPair(RecommendationScenario current, RecommendationScenario downstream,
            com.indore.pathome.spaces.dto.VisitStartCodeView startCode, Instant downstreamStart) {}

    private String start(CountDownLatch ready, CountDownLatch gate, Long geId, Long sessionId,
            VisitOtpStartCommand command) {
        ready.countDown();
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("OTP start gate timed out");
            return execution.start(geId, sessionId, command).status().name();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private void scheduleForExecution(RecommendationScenario scenario, User ground, Instant start,
            int durationMinutes, boolean widenAvailability) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            VisitSession session = sessions.findLockedById(scenario.sessionId()).orElseThrow();
            session.setStatus(VisitSessionStatus.SCHEDULED);
            session.setScheduledAt(start);
            session.setZoneId("Asia/Kolkata");
            session.setRepresentative(users.getReferenceById(ground.getId()));
            session.setAssignedAt(Instant.now());
            session.setDurationSnapshotMinutes(durationMinutes);
            session.setReservedEndAt(start.plus(Duration.ofMinutes(durationMinutes)));
            session.setTenantConfirmationState("CONFIRMED");
            for (PropertyVisitRequest request : requests.findLockedBySessionIdOrderByIdAsc(session.getId())) {
                request.setStatus(VisitRequestStatus.SCHEDULED);
                if (widenAvailability) {
                    request.setAvailabilityStartAt(Instant.now().minus(Duration.ofHours(1)));
                    request.setAvailabilityEndAt(Instant.now().plus(Duration.ofHours(4)));
                }
            }
            if (widenAvailability) {
                for (VisitSessionItem item : items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(session.getId())) {
                    item.setAvailabilityStartAt(Instant.now().minus(Duration.ofHours(1)));
                    item.setAvailabilityEndAt(Instant.now().plus(Duration.ofHours(4)));
                }
            }
            sessions.saveAndFlush(session);
        });
    }

    private Long addPreStartNonExecutionItems(RecommendationScenario scenario) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            VisitSession session = sessions.findLockedById(scenario.sessionId()).orElseThrow();
            Listing removedListing = recommendationListing("Removed before START", scenario.locality(), 22.721, 75.881);
            int position = items.findBySessionIdOrderByPositionAsc(session.getId()).stream()
                    .mapToInt(VisitSessionItem::getPosition).max().orElse(0) + 1;
            VisitSessionItem removed = new VisitSessionItem();
            removed.setSession(session);
            removed.setListing(removedListing);
            removed.setPosition(position);
            PropertyVisitRequest source = requests.findBySessionIdOrderByCreatedAtAscIdAsc(session.getId()).get(0);
            removed.setDerivedFromRequest(source);
            removed.setOrigin(VisitSessionItemOrigin.OE_ADDED);
            removed.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
            removed.setAvailabilityConfirmedAt(Instant.now());
            removed.setConfirmedBy(scenario.admin());
            removed.setRemovedAt(Instant.now());
            removed.setRemovedBy(scenario.admin());
            removed.setRemovalReason("Removed before execution");
            return items.saveAndFlush(removed).getId();
        });
    }

    private void addTwoPreStartConfirmedItems(RecommendationScenario scenario) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            VisitSession session = sessions.findLockedById(scenario.sessionId()).orElseThrow();
            Instant desiredAt = scenario.desiredAt();
            Instant tenantStart = desiredAt.minus(Duration.ofHours(1));
            Instant tenantEnd = desiredAt.plus(Duration.ofHours(4));
            for (PropertyVisitRequest existing : requests.findLockedBySessionIdOrderByIdAsc(session.getId())) {
                existing.setAvailabilityStartAt(tenantStart);
                existing.setAvailabilityEndAt(tenantEnd);
            }
            for (VisitSessionItem existing : items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(session.getId())) {
                existing.setAvailabilityStartAt(tenantStart);
                existing.setAvailabilityEndAt(tenantEnd);
            }
            for (int index = 0; index < 2; index++) {
                int position = index + 3;
                Listing listing = recommendationListing("Additional confirmed property " + index,
                        scenario.locality(), 22.74 + index * 0.001, 75.90 + index * 0.001);
                PropertyVisitRequest request = new PropertyVisitRequest();
                request.setTenant(scenario.tenant());
                request.setListing(listing);
                request.setSession(session);
                request.setStatus(VisitRequestStatus.COORDINATING);
                request.setAvailabilityStartAt(tenantStart);
                request.setAvailabilityEndAt(tenantEnd);
                request.setAvailabilityZoneId("Asia/Kolkata");
                request.setPreferredAt(desiredAt);
                request.setVersion(null);
                request = requests.saveAndFlush(request);
                items.saveAndFlush(confirmedItem(session, listing, request, scenario.locality(), scenario.admin(),
                        desiredAt.minusSeconds(30 * 60L), desiredAt.plusSeconds(90 * 60L), position));
            }
        });
    }

    private void makeRepairRequired(RecommendationScenario scenario,
            com.indore.pathome.spaces.dto.RecommendationCandidate candidate) {
        User ground = users.findById(candidate.groundExecutiveUserId()).orElseThrow();
        scheduleForExecution(scenario, ground, candidate.scheduledAt(), candidate.durationMinutes(), false);
        assertTrue(entitlements.reserve(scenario.tenant().getId(), scenario.sessionId(),
                Instant.now().plus(Duration.ofDays(7))));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            VisitSession session = sessions.findLockedById(scenario.sessionId()).orElseThrow();
            session.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
            session.setTenantConfirmationState("PENDING");
            session.setRepairState("REQUIRED");
            session.setRepairOperationId(UUID.randomUUID());
            sessions.saveAndFlush(session);
        });
    }

    private VisitSession markProposalPending(Long sessionId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            VisitSession session = sessions.findLockedById(sessionId).orElseThrow();
            session.setTenantConfirmationState("PENDING");
            session.setRepairState("PROPOSED");
            session.setRepairOperationId(UUID.randomUUID());
            return sessions.saveAndFlush(session);
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void legacyTenantAndMissingPropertyWindowsReturnStructuredStatuses() {
        RecommendationScenario legacyTenant = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        List<PropertyVisitRequest> tenantRequests =
                requests.findBySessionIdOrderByCreatedAtAscIdAsc(legacyTenant.sessionId());
        tenantRequests.forEach(request -> {
            request.setAvailabilityStartAt(null);
            request.setAvailabilityEndAt(null);
            request.setAvailabilityZoneId(null);
            request.setPreferredAt(null);
        });
        requests.saveAllAndFlush(tenantRequests);
        var legacyResult = operations.recommend(legacyTenant.admin().getId(), legacyTenant.sessionId(),
                new RecommendationRequest(0L));
        assertEquals(RecommendationStatus.INSUFFICIENT_TENANT_AVAILABILITY, legacyResult.status());
        assertTrue(legacyResult.candidates().isEmpty());

        RecommendationScenario incompleteProperty = recommendationScenario(false);
        List<VisitSessionItem> itemsWithoutPropertyWindow =
                items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(incompleteProperty.sessionId());
        VisitSessionItem incompleteStop = itemsWithoutPropertyWindow.get(1);
        incompleteStop.setAvailabilityStartAt(null);
        incompleteStop.setAvailabilityEndAt(null);
        incompleteStop.setAvailabilityZoneId(null);
        incompleteStop.setAvailabilitySource(null);
        items.saveAllAndFlush(itemsWithoutPropertyWindow);
        var propertyResult = operations.recommend(incompleteProperty.admin().getId(), incompleteProperty.sessionId(),
                new RecommendationRequest(0L));
        assertEquals(RecommendationStatus.PROPERTY_AVAILABILITY_INCOMPLETE, propertyResult.status());
        assertTrue(propertyResult.candidates().isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tenantWindowMustContainTheEntirePlannedItinerary() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        Instant tooEarlyEnd = scenario.desiredAt().plusSeconds(20 * 60L);
        List<PropertyVisitRequest> tenantRequests = requests.findBySessionIdOrderByCreatedAtAscIdAsc(scenario.sessionId());
        tenantRequests.forEach(request -> request.setAvailabilityEndAt(tooEarlyEnd));
        requests.saveAllAndFlush(tenantRequests);

        var result = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));

        assertEquals(RecommendationStatus.NO_FEASIBLE_TIME, result.status());
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.rejectedGroundExecutives().stream()
                .allMatch(rejected -> rejected.reasons().contains(
                        com.indore.pathome.spaces.dto.RecommendationRejectionReason.TENANT_WINDOW_MISMATCH)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void coverageRemainsHardEligibilityAcrossEveryItineraryStop() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        Locality secondLocality = localities.saveAndFlush(new Locality(scenario.locality().getCity(), "East", null, null, null));
        VisitSessionItem secondStop = items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(scenario.sessionId()).get(1);
        secondStop.getListing().setCanonicalLocalityId(secondLocality.getId());
        secondStop.getListing().setSector(secondLocality.getSectorName());
        listings.saveAndFlush(secondStop.getListing());

        List<GroundExecutiveCoverage> coverageRows = coverage.findBySchedulingProfileEmployeeProfileIdIn(List.of(
                schedulingProfiles.findByGroundExecutiveUserId(scenario.geA().getId()).orElseThrow().getEmployeeProfileId(),
                schedulingProfiles.findByGroundExecutiveUserId(scenario.geB().getId()).orElseThrow().getEmployeeProfileId()));
        coverageRows.forEach(row -> row.setLocality(scenario.locality()));
        coverage.saveAllAndFlush(coverageRows);

        var result = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));

        assertEquals(RecommendationStatus.NO_ELIGIBLE_GE, result.status());
        assertTrue(result.candidates().isEmpty());
        assertEquals(2, result.rejectedGroundExecutives().size());
        assertTrue(result.rejectedGroundExecutives().stream()
                .allMatch(rejected -> rejected.reasons().contains(
                        com.indore.pathome.spaces.dto.RecommendationRejectionReason.COVERAGE_MISMATCH)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void locationProviderFailureUsesConservativeFallbackInsteadOfFailingRecommendation() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenThrow(new IllegalStateException("location source unavailable"));

        var result = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));

        assertFalse(result.candidates().isEmpty());
        assertTrue(result.candidates().stream().allMatch(candidate -> candidate.reasons().contains(
                com.indore.pathome.spaces.dto.RecommendationReason.LOCATION_UNAVAILABLE_FALLBACK_USED)));
        assertTrue(result.candidates().stream().allMatch(candidate -> candidate.reasons().contains(
                com.indore.pathome.spaces.dto.RecommendationReason.TRAVEL_ESTIMATE_UNCERTAIN)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tenantCannotReadOrApproveInternalRecommendationsBySessionId() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());

        assertThrows(AccessDeniedException.class, () -> operations.recommend(scenario.tenant().getId(),
                scenario.sessionId(), new RecommendationRequest(0L)));
        assertThrows(AccessDeniedException.class, () -> operations.approveRecommendation(scenario.tenant().getId(),
                scenario.sessionId(), new ApproveVisitRecommendationCommand(0L, scenario.geA().getId(),
                        scenario.desiredAt(), "Asia/Kolkata", null)));
        assertEquals(VisitSessionStatus.DRAFT, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertEquals(0, decisions.countBySessionId(scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleAndNonFiniteAccuracySnapshotsDegradeToFallback() {
        RecommendationScenario stale = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        when(locations.latestFor(eq(new FieldResourceKey("INTERNAL_GE", stale.geA().getId().toString())), any()))
                .thenReturn(java.util.Optional.of(new LocationSnapshot(22.72, 75.88,
                        Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3590),
                        8.0, LocationAssessment.USABLE, "OLD_FIX")));
        var staleResult = operations.recommend(stale.admin().getId(), stale.sessionId(),
                new RecommendationRequest(0L));
        assertTrue(staleResult.candidates().stream().anyMatch(candidate -> candidate.groundExecutiveUserId()
                .equals(stale.geA().getId()) && candidate.reasons().contains(
                        com.indore.pathome.spaces.dto.RecommendationReason.LOCATION_DEGRADED_FALLBACK_USED)));

        RecommendationScenario nonFiniteAccuracy = recommendationScenario(false);
        when(locations.latestFor(eq(new FieldResourceKey("INTERNAL_GE", nonFiniteAccuracy.geA().getId().toString())), any()))
                .thenReturn(java.util.Optional.of(new LocationSnapshot(22.72, 75.88,
                        Instant.now().minusSeconds(5), Instant.now(), Double.NaN,
                        LocationAssessment.USABLE, "INVALID_ACCURACY")));
        var invalidAccuracyResult = operations.recommend(nonFiniteAccuracy.admin().getId(),
                nonFiniteAccuracy.sessionId(), new RecommendationRequest(0L));
        assertTrue(invalidAccuracyResult.candidates().stream().anyMatch(candidate ->
                candidate.reasons().contains(
                        com.indore.pathome.spaces.dto.RecommendationReason.LOCATION_DEGRADED_FALLBACK_USED)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void feasibleNonTopTimeRequiresReasonAndPersistsOverride() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var top = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        Instant alternate = top.scheduledAt().plusSeconds(15 * 60L);

        assertThrows(IllegalArgumentException.class, () -> operations.approveRecommendation(
                scenario.admin().getId(), scenario.sessionId(), new ApproveVisitRecommendationCommand(0L,
                        top.groundExecutiveUserId(), alternate, "Asia/Kolkata", null)));
        assertEquals(VisitSessionStatus.DRAFT, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        var approved = operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                new ApproveVisitRecommendationCommand(0L, top.groundExecutiveUserId(), alternate,
                        "Asia/Kolkata", "Tenant confirmed the later feasible time"));

        assertTrue(approved.override());
        VisitSchedulingDecision decision = decisions.findById(approved.decisionId()).orElseThrow();
        assertTrue(decision.isOverride());
        assertEquals("Tenant confirmed the later feasible time", decision.getOverrideReason());
        assertEquals(alternate, decision.getSelectedScheduledAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shiftAndBreakAreHardEligibilityFilters() {
        RecommendationScenario shiftScenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        GroundExecutiveSchedulingProfile shiftProfile =
                schedulingProfiles.findByGroundExecutiveUserId(shiftScenario.geB().getId()).orElseThrow();
        GroundExecutiveShift shift = shifts.findOverlappingWindow(shiftProfile.getEmployeeProfileId(),
                shiftScenario.desiredAt().minusSeconds(4 * 60 * 60L),
                shiftScenario.desiredAt().plusSeconds(4 * 60 * 60L)).get(0);
        shift.setEndsAt(shiftScenario.desiredAt().plusSeconds(10 * 60L));
        shifts.saveAndFlush(shift);
        var offShiftResult = operations.recommend(shiftScenario.admin().getId(), shiftScenario.sessionId(),
                new RecommendationRequest(0L));
        assertTrue(offShiftResult.candidates().stream().noneMatch(candidate ->
                candidate.groundExecutiveUserId().equals(shiftScenario.geB().getId())));
        assertTrue(offShiftResult.rejectedGroundExecutives().stream().anyMatch(rejected ->
                rejected.groundExecutiveUserId().equals(shiftScenario.geB().getId())
                        && rejected.reasons().contains(
                                com.indore.pathome.spaces.dto.RecommendationRejectionReason.SHIFT_MISMATCH)));

        RecommendationScenario breakScenario = recommendationScenario(false);
        GroundExecutiveSchedulingProfile breakProfile =
                schedulingProfiles.findByGroundExecutiveUserId(breakScenario.geB().getId()).orElseThrow();
        GroundExecutiveUnavailability breakPeriod = new GroundExecutiveUnavailability();
        breakPeriod.setSchedulingProfile(breakProfile);
        breakPeriod.setStartsAt(breakScenario.desiredAt().plusSeconds(5 * 60L));
        breakPeriod.setEndsAt(breakScenario.desiredAt().plusSeconds(15 * 60L));
        breakPeriod.setZoneId("Asia/Kolkata");
        breakPeriod.setIntervalType(GroundExecutiveUnavailableType.BREAK);
        breakPeriod.setCreatedBy(users.getReferenceById(breakScenario.admin().getId()));
        breakPeriod.setUpdatedBy(users.getReferenceById(breakScenario.admin().getId()));
        unavailability.saveAndFlush(breakPeriod);
        var breakResult = operations.recommend(breakScenario.admin().getId(), breakScenario.sessionId(),
                new RecommendationRequest(0L));
        assertTrue(breakResult.candidates().stream().noneMatch(candidate ->
                candidate.groundExecutiveUserId().equals(breakScenario.geB().getId())));
        assertTrue(breakResult.rejectedGroundExecutives().stream().anyMatch(rejected ->
                rejected.groundExecutiveUserId().equals(breakScenario.geB().getId())
                        && rejected.reasons().contains(
                                com.indore.pathome.spaces.dto.RecommendationRejectionReason.UNAVAILABILITY_CONFLICT)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void localityFitAndFutureWorkloadAreAppliedAfterFeasibility() {
        RecommendationScenario areaScenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        Long areaProfileId = schedulingProfiles.findByGroundExecutiveUserId(areaScenario.geA().getId())
                .orElseThrow().getEmployeeProfileId();
        List<GroundExecutiveCoverage> areaRows =
                coverage.findBySchedulingProfileEmployeeProfileIdIn(List.of(areaProfileId));
        areaRows.forEach(row -> row.setLocality(areaScenario.locality()));
        coverage.saveAllAndFlush(areaRows);
        var areaResult = operations.recommend(areaScenario.admin().getId(), areaScenario.sessionId(),
                new RecommendationRequest(0L));
        assertEquals(areaScenario.geA().getId(), areaResult.candidates().get(0).groundExecutiveUserId());
        assertTrue(areaResult.candidates().get(0).reasons().contains(
                com.indore.pathome.spaces.dto.RecommendationReason.LOCALITY_COVERAGE_MATCH));

        Instant nextDayWorkloadSlot = Instant.now().atZone(java.time.ZoneId.of("Asia/Kolkata"))
                .plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0).toInstant();
        RecommendationScenario workloadScenario = withRecommendationWindow(
                recommendationScenario(false), nextDayWorkloadSlot);
        createFutureWorkloadReservation(workloadScenario);
        var workloadResult = operations.recommend(workloadScenario.admin().getId(), workloadScenario.sessionId(),
                new RecommendationRequest(0L));
        assertEquals(workloadScenario.geB().getId(), workloadResult.candidates().get(0).groundExecutiveUserId(),
                workloadResult.candidates().toString());
        var higherWorkloadGe = workloadResult.candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(workloadScenario.geA().getId()))
                .findFirst().orElseThrow();
        assertEquals(1, higherWorkloadGe.futureReservationCount());
        assertEquals(30, higherWorkloadGe.futureReservedMinutes());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void nextReservationTravelCanMakeAnOtherwiseOpenSlotIneligible() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        createNextLegConstrainedReservation(scenario);

        var result = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));

        assertFalse(result.candidates().stream().anyMatch(candidate ->
                candidate.groundExecutiveUserId().equals(scenario.geB().getId())));
        assertTrue(result.rejectedGroundExecutives().stream().anyMatch(rejected ->
                rejected.groundExecutiveUserId().equals(scenario.geB().getId())
                        && rejected.reasons().contains(
                        com.indore.pathome.spaces.dto.RecommendationRejectionReason.NEXT_LEG_TRAVEL_INFEASIBLE)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void distantPreviousReservationOutsideOldMarginMakesCandidateInfeasible() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        Instant planningStart = scenario.desiredAt().minusSeconds(15 * 60L);
        Instant oldLookupStart = planningStart.minus(oldReservationLookupMargin());
        Instant previousEnd = oldLookupStart.minusSeconds(60);
        VisitSession previous = createNeighborReservation(scenario, scenario.geB(), "Distant previous reservation",
                22.72, 75.58, previousEnd.minusSeconds(30 * 60L), 30, VisitSessionStatus.SCHEDULED);
        assertTrue(previous.getReservedEndAt().isBefore(oldLookupStart));

        TravelEstimate travel = travelEstimator.estimate(
                new TravelPoint(scenario.locality().getCity(), scenario.locality().getId(), 22.72, 75.58, "PREVIOUS_VISIT"),
                new TravelPoint(scenario.locality().getCity(), scenario.locality().getId(), 22.72, 75.88, "PROPERTY"),
                previous.getReservedEndAt());
        assertTrue(previous.getReservedEndAt().plus(travel.duration()).isAfter(scenario.desiredAt()));

        var result = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));

        assertFalse(result.candidates().stream().anyMatch(candidate ->
                candidate.groundExecutiveUserId().equals(scenario.geB().getId())));
        assertTrue(result.rejectedGroundExecutives().stream().anyMatch(rejected ->
                rejected.groundExecutiveUserId().equals(scenario.geB().getId())
                        && rejected.reasons().contains(
                                com.indore.pathome.spaces.dto.RecommendationRejectionReason.FIRST_LEG_TRAVEL_INFEASIBLE)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void distantNextReservationOutsideOldMarginMakesCandidateInfeasible() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var beforeNeighbor = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geB().getId()))
                .findFirst().orElseThrow();
        Instant oldLookupEnd = scenario.desiredAt().plusSeconds(60 * 60L)
                .plus(oldReservationLookupMargin()).plusSeconds(1);
        List<VisitSessionItem> currentItinerary = items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(scenario.sessionId());
        Listing lastStop = currentItinerary.get(currentItinerary.size() - 1).getListing();
        VisitSession next = createNeighborReservation(scenario, scenario.geB(), "Distant next reservation",
                22.72, 76.18, oldLookupEnd, 30, VisitSessionStatus.SCHEDULED);
        assertFalse(next.getScheduledAt().isBefore(oldLookupEnd));

        TravelEstimate travel = travelEstimator.estimate(
                new TravelPoint(scenario.locality().getCity(), scenario.locality().getId(),
                        lastStop.getLatitude(), lastStop.getLongitude(), "PROPERTY"),
                new TravelPoint(scenario.locality().getCity(), scenario.locality().getId(),
                        22.72, 76.18, "PROPERTY"), beforeNeighbor.reservedEndAt());
        assertTrue(beforeNeighbor.reservedEndAt().plus(travel.duration()).isAfter(next.getScheduledAt()));

        var result = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));

        assertFalse(result.candidates().stream().anyMatch(candidate ->
                candidate.groundExecutiveUserId().equals(scenario.geB().getId())));
        assertTrue(result.rejectedGroundExecutives().stream().anyMatch(rejected ->
                rejected.groundExecutiveUserId().equals(scenario.geB().getId())
                        && rejected.reasons().contains(
                                com.indore.pathome.spaces.dto.RecommendationRejectionReason.NEXT_LEG_TRAVEL_INFEASIBLE)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void approvalReloadsDistantNeighborAndRollsBackWithoutBookingHistoryOrNotification() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var candidate = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        Instant planningStart = scenario.desiredAt().minusSeconds(15 * 60L);
        Instant oldLookupStart = planningStart.minus(oldReservationLookupMargin());
        VisitSession neighbor = createNeighborReservation(scenario,
                users.findById(candidate.groundExecutiveUserId()).orElseThrow(), "Neighbor added after recommendation",
                22.72, 75.58, oldLookupStart.minusSeconds(60 + 30 * 60L), 30,
                VisitSessionStatus.SCHEDULED);
        assertTrue(neighbor.getReservedEndAt().isBefore(oldLookupStart));

        assertThrows(VisitOperationsConflictException.class, () -> operations.approveRecommendation(
                scenario.admin().getId(), scenario.sessionId(), new ApproveVisitRecommendationCommand(0L,
                        candidate.groundExecutiveUserId(), candidate.scheduledAt(), candidate.zoneId(), null)));

        VisitSession unchanged = sessions.findById(scenario.sessionId()).orElseThrow();
        assertEquals(VisitSessionStatus.DRAFT, unchanged.getStatus());
        assertNull(unchanged.getScheduledAt());
        assertNull(unchanged.getReservedEndAt());
        assertNull(unchanged.getRepresentative());
        assertEquals(0, decisions.countBySessionId(scenario.sessionId()));
        assertEquals(1, sessions.countByRepresentativeIdAndStatus(candidate.groundExecutiveUserId(),
                VisitSessionStatus.SCHEDULED));
        assertTrue(notifications.findByEventKey("VISIT_SESSION_SCHEDULED:" + scenario.sessionId()
                + ":v1:" + scenario.tenant().getId()).isEmpty());
        assertTrue(notifications.findByEventKey("VISIT_SESSION_ASSIGNED:" + scenario.sessionId()
                + ":v1:" + candidate.groundExecutiveUserId()).isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void exactTravelArrivalBoundariesRemainFeasibleWithBothNeighbors() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var initial = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(candidate -> candidate.groundExecutiveUserId().equals(scenario.geB().getId()))
                .findFirst().orElseThrow();
        List<VisitSessionItem> itinerary = items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(scenario.sessionId());
        Listing first = itinerary.get(0).getListing();
        Listing last = itinerary.get(itinerary.size() - 1).getListing();
        TravelPoint firstPoint = new TravelPoint(scenario.locality().getCity(), scenario.locality().getId(),
                first.getLatitude(), first.getLongitude(), "PROPERTY");
        TravelPoint lastPoint = new TravelPoint(scenario.locality().getCity(), scenario.locality().getId(),
                last.getLatitude(), last.getLongitude(), "PROPERTY");
        Duration previousTravel = travelEstimator.estimate(firstPoint, firstPoint, initial.scheduledAt()).duration();
        Duration nextTravel = travelEstimator.estimate(lastPoint, lastPoint, initial.reservedEndAt()).duration();
        Instant previousEnd = initial.scheduledAt().minus(previousTravel);
        Instant nextStart = initial.reservedEndAt().plus(nextTravel);
        createNeighborReservation(scenario, scenario.geB(), "Exact previous travel boundary",
                first.getLatitude(), first.getLongitude(), previousEnd.minusSeconds(30 * 60L), 30,
                VisitSessionStatus.SCHEDULED);
        createNeighborReservation(scenario, scenario.geB(), "Exact next travel boundary",
                last.getLatitude(), last.getLongitude(), nextStart, 30, VisitSessionStatus.SCHEDULED);

        var result = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));

        var stillFeasible = result.candidates().stream().filter(candidate ->
                candidate.groundExecutiveUserId().equals(scenario.geB().getId())
                        && candidate.scheduledAt().equals(initial.scheduledAt())).findFirst().orElseThrow();
        assertEquals(initial.reservedEndAt(), stillFeasible.reservedEndAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancelledDistantNeighborDoesNotBlockRecommendation() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        Instant planningStart = scenario.desiredAt().minusSeconds(15 * 60L);
        Instant oldLookupStart = planningStart.minus(oldReservationLookupMargin());
        createNeighborReservation(scenario, scenario.geB(), "Cancelled distant previous reservation",
                22.72, 75.58, oldLookupStart.minusSeconds(60 + 30 * 60L), 30,
                VisitSessionStatus.CANCELLED);

        var result = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));

        assertTrue(result.candidates().stream().anyMatch(candidate ->
                candidate.groundExecutiveUserId().equals(scenario.geB().getId())));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void batchedNeighborQueriesReturnPerGeNearestRowsAtInclusiveBoundaries() {
        RecommendationScenario scenario = recommendationScenario(false);
        Instant windowStart = scenario.desiredAt().minusSeconds(15 * 60L);
        Instant windowEnd = scenario.desiredAt().plusSeconds(60 * 60L);
        VisitSession previousFarA = createNeighborReservation(scenario, scenario.geA(), "Far previous A",
                22.72, 75.58, windowStart.minusSeconds(90 * 60L), 30, VisitSessionStatus.SCHEDULED);
        VisitSession previousA = createNeighborReservation(scenario, scenario.geA(), "Boundary previous A",
                22.72, 75.58, windowStart.minusSeconds(30 * 60L), 30, VisitSessionStatus.SCHEDULED);
        VisitSession previousB = createNeighborReservation(scenario, scenario.geB(), "Previous B",
                22.72, 75.58, windowStart.minusSeconds(30 * 60L), 30, VisitSessionStatus.SCHEDULED);
        VisitSession nextA = createNeighborReservation(scenario, scenario.geA(), "Next A",
                22.72, 75.88, windowEnd.plusSeconds(30 * 60L), 30, VisitSessionStatus.SCHEDULED);
        VisitSession nextB = createNeighborReservation(scenario, scenario.geB(), "Boundary next B",
                22.72, 75.88, windowEnd, 30, VisitSessionStatus.SCHEDULED);
        VisitSession nextFarB = createNeighborReservation(scenario, scenario.geB(), "Far next B",
                22.72, 75.88, windowEnd.plusSeconds(60 * 60L), 30, VisitSessionStatus.SCHEDULED);
        List<Long> geIds = List.of(scenario.geA().getId(), scenario.geB().getId());
        List<VisitSession> inWindow = sessions.findActiveItinerariesForRepresentatives(geIds,
                List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED), windowStart, windowEnd,
                null, PageRequest.of(0, 20));

        List<VisitSession> previous = sessions.findNearestActiveReservationsBefore(geIds,
                List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED), windowStart, null);
        List<VisitSession> next = sessions.findNearestActiveReservationsAfter(geIds,
                List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED), windowEnd, null);

        assertTrue(inWindow.isEmpty());
        assertEquals(2, previous.size());
        assertTrue(previous.stream().anyMatch(value -> value.getId().equals(previousA.getId())));
        assertTrue(previous.stream().anyMatch(value -> value.getId().equals(previousB.getId())));
        assertFalse(previous.stream().anyMatch(value -> value.getId().equals(previousFarA.getId())));
        assertEquals(2, previous.stream().map(value -> value.getRepresentative().getId()).distinct().count());
        assertEquals(2, next.size());
        assertTrue(next.stream().anyMatch(value -> value.getId().equals(nextA.getId())));
        assertTrue(next.stream().anyMatch(value -> value.getId().equals(nextB.getId())));
        assertFalse(next.stream().anyMatch(value -> value.getId().equals(nextFarB.getId())));
        assertEquals(2, next.stream().map(value -> value.getRepresentative().getId()).distinct().count());
        assertFalse(previous.stream().map(VisitSession::getId).toList()
                .containsAll(next.stream().map(VisitSession::getId).toList()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void incompleteInWindowReservationReadStillFailsClosed() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        Instant firstStart = scenario.desiredAt().minusSeconds(10 * 60L);
        createNeighborReservation(scenario, scenario.geA(), "In-window reservation one",
                22.72, 75.88, firstStart, 20, VisitSessionStatus.SCHEDULED);
        createNeighborReservation(scenario, scenario.geA(), "In-window reservation two",
                22.72, 75.88, firstStart.plusSeconds(30 * 60L), 20, VisitSessionStatus.SCHEDULED);
        int maximum = recommendationPolicy.getMaximumReservationRows();
        recommendationPolicy.setMaximumReservationRows(1);
        try {
            var result = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                    new RecommendationRequest(0L));
            assertEquals(RecommendationStatus.NO_FEASIBLE_TIME, result.status());
            assertTrue(result.candidateSearchTruncated());
            assertTrue(result.diagnostics().contains("RESERVATION_SEARCH_BOUND_EXCEEDED"));
            assertTrue(result.candidates().isEmpty());
        } finally {
            recommendationPolicy.setMaximumReservationRows(maximum);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void changedPropertyWindowMakesRecommendationStaleWithoutBookingOrDecision() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any()))
                .thenReturn(java.util.Optional.empty());
        var recommendation = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L));
        var selected = recommendation.candidates().get(0);

        List<VisitSessionItem> itinerary = items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(scenario.sessionId());
        VisitSessionItem lastStop = itinerary.get(itinerary.size() - 1);
        lastStop.setAvailabilityEndAt(selected.scheduledAt().plusSeconds(5 * 60L));
        items.saveAndFlush(lastStop);

        assertThrows(VisitOperationsConflictException.class, () -> operations.approveRecommendation(
                scenario.admin().getId(), scenario.sessionId(), new ApproveVisitRecommendationCommand(0L,
                        selected.groundExecutiveUserId(), selected.scheduledAt(), "Asia/Kolkata", null)));
        assertEquals(VisitSessionStatus.DRAFT, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertEquals(0, decisions.countBySessionId(scenario.sessionId()));
        assertTrue(notifications.findByEventKey("VISIT_SESSION_SCHEDULED:" + scenario.sessionId()
                + ":v1:" + scenario.tenant().getId()).isEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentApprovalsOfOneRecommendationCommitOnlyOneDecision() throws Exception {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var candidate = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        User secondAdmin = user("recommend-admin-race", Role.ROLE_ADMIN);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> approveRecommendation(scenario.admin().getId(), scenario.sessionId(), candidate,
                    ready, start));
            var second = executor.submit(() -> approveRecommendation(secondAdmin.getId(), scenario.sessionId(), candidate,
                    ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("COMMITTED"::equals).count());
            assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(VisitSessionStatus.SCHEDULED, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertEquals(1, decisions.countBySessionId(scenario.sessionId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRecommendationsForDifferentSessionsCannotBookTheSameGeTime() throws Exception {
        RecommendationScenario firstScenario = recommendationScenario(false);
        RecommendationScenario secondScenario = additionalRecommendationSession(firstScenario);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var firstCandidate = operations.recommend(firstScenario.admin().getId(), firstScenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        var secondCandidate = operations.recommend(secondScenario.admin().getId(), secondScenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        assertEquals(firstCandidate.groundExecutiveUserId(), secondCandidate.groundExecutiveUserId());
        assertEquals(firstCandidate.scheduledAt(), secondCandidate.scheduledAt());

        User secondAdmin = user("recommend-admin-cross-session-race", Role.ROLE_ADMIN);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> approveRecommendation(firstScenario.admin().getId(),
                    firstScenario.sessionId(), firstCandidate, ready, start));
            var second = executor.submit(() -> approveRecommendation(secondAdmin.getId(),
                    secondScenario.sessionId(), secondCandidate, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("COMMITTED"::equals).count());
            assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, decisions.countBySessionId(firstScenario.sessionId())
                + decisions.countBySessionId(secondScenario.sessionId()));
        assertEquals(1, sessions.countByRepresentativeIdAndStatus(firstCandidate.groundExecutiveUserId(),
                VisitSessionStatus.SCHEDULED));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollingBackRecommendationApprovalLeavesNoBookingDecisionOrNotification() {
        RecommendationScenario scenario = recommendationScenario(false);
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var candidate = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().get(0);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                    new ApproveVisitRecommendationCommand(0L, candidate.groundExecutiveUserId(),
                            candidate.scheduledAt(), "Asia/Kolkata", null));
            throw new IllegalStateException("rollback recommendation approval");
        }));

        assertEquals(VisitSessionStatus.DRAFT, sessions.findById(scenario.sessionId()).orElseThrow().getStatus());
        assertEquals(0, decisions.countBySessionId(scenario.sessionId()));
        assertTrue(notifications.findByEventKey("VISIT_SESSION_SCHEDULED:" + scenario.sessionId()
                + ":v1:" + scenario.tenant().getId()).isEmpty());
        assertTrue(notifications.findByEventKey("VISIT_SESSION_ASSIGNED:" + scenario.sessionId()
                + ":v1:" + candidate.groundExecutiveUserId()).isEmpty());
    }

    private FinalizedOutcomeFixture finalizedOutcomeFixture() {
        return finalizedOutcomeFixture(recommendationScenario(false));
    }

    private FinalizedOutcomeFixture finalizedOutcomeFixture(RecommendationScenario scenario) {
        when(locations.latestFor(any(), any())).thenReturn(java.util.Optional.empty());
        var candidate = operations.recommend(scenario.admin().getId(), scenario.sessionId(),
                new RecommendationRequest(0L)).candidates().stream()
                .filter(item -> item.groundExecutiveUserId().equals(scenario.geA().getId()))
                .findFirst().orElseThrow();
        operations.approveRecommendation(scenario.admin().getId(), scenario.sessionId(),
                new ApproveVisitRecommendationCommand(0L, scenario.geA().getId(), candidate.scheduledAt(),
                        "Asia/Kolkata", null));
        Instant startAt = Instant.now().minusSeconds(5 * 60L)
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        scheduleForExecution(scenario, scenario.geA(), startAt, candidate.durationMinutes(), true);
        execution.markArrived(scenario.geA().getId(), scenario.sessionId());
        var code = execution.issueStartCode(scenario.tenant().getId(), scenario.sessionId());
        execution.start(scenario.geA().getId(), scenario.sessionId(),
                new VisitOtpStartCommand(code.generation(), code.code(), UUID.randomUUID()));

        var outcomes = outcomeService.getGroundOutcomeReport(scenario.geA().getId(), scenario.sessionId());
        for (int index = 0; index < outcomes.items().size(); index++) {
            var item = outcomes.items().get(index);
            boolean viewed = index == 0;
            outcomes = outcomeService.recordItemOutcome(scenario.geA().getId(), scenario.sessionId(), item.itemId(),
                    new RecordVisitSessionItemOutcomeCommand(viewed ? VisitSessionItemOutcomeState.VISITED
                            : VisitSessionItemOutcomeState.SKIPPED,
                            viewed ? null : VisitSessionItemSkipReason.PROPERTY_UNAVAILABLE, null,
                            outcomes.sessionVersion(), outcomes.reportVersion(), item.itemVersion(), UUID.randomUUID()));
        }
        execution.finish(scenario.geA().getId(), scenario.sessionId());
        VisitSession session = sessions.findById(scenario.sessionId()).orElseThrow();
        outcomes = outcomeService.finalizeReport(scenario.geA().getId(), scenario.sessionId(),
                session.getVersion(), outcomes.reportVersion(), UUID.randomUUID());
        assertEquals(VisitSessionOutcomeReportState.FINALIZED, outcomes.reportState());
        return new FinalizedOutcomeFixture(scenario, outcomes);
    }

    private RecommendationScenario recommendationScenario(boolean withAmitPriorVisit) {
        User admin = user("recommend-admin", Role.ROLE_ADMIN);
        User tenant = user("recommend-tenant", Role.ROLE_TENANT);
        User geA = user("recommend-amit", Role.ROLE_GROUND_BOY);
        User geB = user("recommend-rohit", Role.ROLE_GROUND_BOY);
        EmployeeProfile employeeA = employees.saveAndFlush(new EmployeeProfile(geA, "GROUND_BOY", null, BigDecimal.ZERO));
        EmployeeProfile employeeB = employees.saveAndFlush(new EmployeeProfile(geB, "GROUND_BOY", null, BigDecimal.ZERO));
        GroundExecutiveSchedulingProfile profileA = schedulingProfile(employeeA, admin);
        GroundExecutiveSchedulingProfile profileB = schedulingProfile(employeeB, admin);
        String recommendationCity = "Recommendation City " + UUID.randomUUID();
        Locality locality = localities.saveAndFlush(new Locality(recommendationCity, "Central", null, null, null));
        saveCityCoverage(profileA, admin, recommendationCity);
        saveCityCoverage(profileB, admin, recommendationCity);

        Instant desiredAt = Instant.now().plusSeconds(90 * 60L).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Instant shiftStart = desiredAt.minusSeconds(4 * 60 * 60L);
        Instant shiftEnd = desiredAt.plusSeconds(4 * 60 * 60L);
        saveShift(profileA, admin, shiftStart, shiftEnd);
        saveShift(profileB, admin, shiftStart, shiftEnd);

        if (withAmitPriorVisit) createAmitPriorReservation(admin, tenant, geA, locality, desiredAt);

        VisitSession session = new VisitSession();
        session.setTenant(tenant);
        session.setCity(recommendationCity);
        session.setAreaName("Central");
        session.setCanonicalLocality(locality);
        session.setVersion(null);
        session = sessions.saveAndFlush(session);
        Long sessionId = session.getId();
        Instant tenantStart = desiredAt.minusSeconds(15 * 60L);
        Instant tenantEnd = desiredAt.plusSeconds(60 * 60L);
        for (int index = 0; index < 2; index++) {
            Listing listing = recommendationListing("Recommendation property " + index, locality,
                    22.72 + index * 0.001, 75.88 + index * 0.001);
            PropertyVisitRequest request = new PropertyVisitRequest();
            request.setTenant(tenant);
            request.setListing(listing);
            request.setSession(session);
            request.setStatus(VisitRequestStatus.COORDINATING);
            request.setAvailabilityStartAt(tenantStart);
            request.setAvailabilityEndAt(tenantEnd);
            request.setAvailabilityZoneId("Asia/Kolkata");
            request.setPreferredAt(desiredAt);
            request.setVersion(null);
            request = requests.saveAndFlush(request);
            VisitSessionItem item = confirmedItem(session, listing, request, locality, admin,
                    desiredAt.minusSeconds(30 * 60L), desiredAt.plusSeconds(90 * 60L), index + 1);
            items.saveAndFlush(item);
        }
        return new RecommendationScenario(admin, tenant, geA, geB, sessionId, desiredAt, locality);
    }

    private RecommendationScenario additionalRecommendationSession(RecommendationScenario existing) {
        VisitSession session = new VisitSession();
        session.setTenant(existing.tenant());
        session.setCity(existing.locality().getCity());
        session.setAreaName("Central");
        session.setCanonicalLocality(existing.locality());
        session = sessions.saveAndFlush(session);
        Long sessionId = session.getId();
        Instant desiredAt = existing.desiredAt();
        Instant tenantStart = desiredAt.minusSeconds(15 * 60L);
        Instant tenantEnd = desiredAt.plusSeconds(60 * 60L);
        for (int index = 0; index < 2; index++) {
            Listing listing = recommendationListing("Second recommendation property " + index, existing.locality(),
                    22.73 + index * 0.001, 75.89 + index * 0.001);
            PropertyVisitRequest request = new PropertyVisitRequest();
            request.setTenant(existing.tenant());
            request.setListing(listing);
            request.setSession(session);
            request.setStatus(VisitRequestStatus.COORDINATING);
            request.setAvailabilityStartAt(tenantStart);
            request.setAvailabilityEndAt(tenantEnd);
            request.setAvailabilityZoneId("Asia/Kolkata");
            request.setPreferredAt(desiredAt);
            request.setVersion(null);
            request = requests.saveAndFlush(request);
            items.saveAndFlush(confirmedItem(session, listing, request, existing.locality(), existing.admin(),
                    desiredAt.minusSeconds(30 * 60L), desiredAt.plusSeconds(90 * 60L), index + 1));
        }
        return new RecommendationScenario(existing.admin(), existing.tenant(), existing.geA(), existing.geB(),
                sessionId, desiredAt, existing.locality());
    }

    private GroundExecutiveSchedulingProfile schedulingProfile(EmployeeProfile employee, User admin) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            GroundExecutiveSchedulingProfile profile = new GroundExecutiveSchedulingProfile();
            profile.setEmployeeProfile(employees.findById(employee.getId()).orElseThrow());
            profile.setSchedulingActive(true);
            profile.setUpdatedBy(users.getReferenceById(admin.getId()));
            return schedulingProfiles.saveAndFlush(profile);
        });
    }

    private void saveCityCoverage(GroundExecutiveSchedulingProfile profile, User admin, String city) {
        GroundExecutiveCoverage entry = new GroundExecutiveCoverage();
        entry.setSchedulingProfile(profile);
        entry.setCity(city);
        entry.setCreatedBy(admin);
        coverage.saveAndFlush(entry);
    }

    private void saveShift(GroundExecutiveSchedulingProfile profile, User admin, Instant start, Instant end) {
        GroundExecutiveShift shift = new GroundExecutiveShift();
        shift.setSchedulingProfile(profile);
        shift.setStartsAt(start);
        shift.setEndsAt(end);
        shift.setZoneId("Asia/Kolkata");
        shift.setCreatedBy(admin);
        shift.setUpdatedBy(admin);
        shifts.saveAndFlush(shift);
    }

    private Listing recommendationListing(String title, Locality locality, double latitude, double longitude) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle(title);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Test address");
        listing.setSector(locality.getSectorName());
        listing.setCity(locality.getCity());
        listing.setCanonicalLocalityId(locality.getId());
        listing.setLocationResolution(LocationResolution.CANONICAL);
        listing.setLatitude(latitude);
        listing.setLongitude(longitude);
        listing.setBhkCount("1BHK");
        listing.setMonthlyRent(BigDecimal.ONE);
        listing.setSecurityDeposit(BigDecimal.ZERO);
        return (Listing) listings.saveAndFlush(listing);
    }

    private VisitSessionItem confirmedItem(VisitSession session, Listing listing, PropertyVisitRequest request,
            Locality locality, User confirmer, Instant availableStart, Instant availableEnd, int position) {
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(session);
        item.setListing(listing);
        item.setPosition(position);
        item.setSourceRequest(request);
        item.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
        item.setAvailabilityConfirmedAt(Instant.now());
        item.setConfirmedBy(confirmer);
        item.setAvailabilityStartAt(availableStart);
        item.setAvailabilityEndAt(availableEnd);
        item.setAvailabilityZoneId("Asia/Kolkata");
        item.setAvailabilitySource(PropertyAvailabilitySource.PHONE);
        return item;
    }

    private void createAmitPriorReservation(User admin, User tenant, User geA, Locality locality, Instant desiredAt) {
        Listing previousListing = recommendationListing("Amit previous property", locality, 22.72, 75.78);
        VisitSession previous = new VisitSession();
        previous.setTenant(tenant);
        previous.setCity(locality.getCity());
        previous.setCanonicalLocality(locality);
        previous.setStatus(VisitSessionStatus.SCHEDULED);
        previous.setScheduledAt(desiredAt.minusSeconds(35 * 60L));
        previous.setZoneId("Asia/Kolkata");
        previous.setRepresentative(geA);
        previous.setAssignedAt(Instant.now());
        previous.setDurationSnapshotMinutes(30);
        previous.setReservedEndAt(desiredAt.minusSeconds(5 * 60L));
        previous = sessions.saveAndFlush(previous);
        PropertyVisitRequest previousRequest = new PropertyVisitRequest();
        previousRequest.setTenant(tenant);
        previousRequest.setListing(previousListing);
        previousRequest.setSession(previous);
        previousRequest.setStatus(VisitRequestStatus.SCHEDULED);
        previousRequest = requests.saveAndFlush(previousRequest);
        VisitSessionItem previousItem = new VisitSessionItem();
        previousItem.setSession(previous);
        previousItem.setListing(previousListing);
        previousItem.setPosition(1);
        previousItem.setSourceRequest(previousRequest);
        previousItem.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        previousItem.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
        previousItem.setAvailabilityConfirmedAt(Instant.now());
        previousItem.setConfirmedBy(admin);
        items.saveAndFlush(previousItem);
    }

    private void createFutureWorkloadReservation(RecommendationScenario scenario) {
        Listing nextListing = recommendationListing("Future workload property", scenario.locality(), 22.721, 75.881);
        VisitSession future = new VisitSession();
        future.setTenant(scenario.tenant());
        future.setCity(scenario.locality().getCity());
        future.setCanonicalLocality(scenario.locality());
        future.setStatus(VisitSessionStatus.SCHEDULED);
        future.setScheduledAt(scenario.desiredAt().plusSeconds(70 * 60L));
        future.setZoneId("Asia/Kolkata");
        future.setRepresentative(scenario.geA());
        future.setAssignedAt(Instant.now());
        future.setDurationSnapshotMinutes(30);
        future.setReservedEndAt(scenario.desiredAt().plusSeconds(100 * 60L));
        future = sessions.saveAndFlush(future);
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(scenario.tenant());
        request.setListing(nextListing);
        request.setSession(future);
        request.setStatus(VisitRequestStatus.SCHEDULED);
        request = requests.saveAndFlush(request);
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(future);
        item.setListing(nextListing);
        item.setPosition(1);
        item.setSourceRequest(request);
        item.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
        item.setAvailabilityConfirmedAt(Instant.now());
        item.setConfirmedBy(scenario.admin());
        items.saveAndFlush(item);
    }

    private RecommendationScenario withRecommendationWindow(RecommendationScenario scenario, Instant preferredAt) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            VisitSession session = sessions.findLockedById(scenario.sessionId()).orElseThrow();
            for (PropertyVisitRequest request : requests.findLockedBySessionIdOrderByIdAsc(session.getId())) {
                request.setAvailabilityStartAt(preferredAt.minus(Duration.ofMinutes(15)));
                request.setAvailabilityEndAt(preferredAt.plus(Duration.ofMinutes(120)));
                request.setPreferredAt(preferredAt);
            }
            for (VisitSessionItem item : items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(session.getId())) {
                item.setAvailabilityStartAt(preferredAt.minus(Duration.ofMinutes(30)));
                item.setAvailabilityEndAt(preferredAt.plus(Duration.ofMinutes(90)));
            }
            for (Long geId : List.of(scenario.geA().getId(), scenario.geB().getId())) {
                GroundExecutiveSchedulingProfile profile = schedulingProfiles.findByGroundExecutiveUserId(geId).orElseThrow();
                saveShift(profile, scenario.admin(), preferredAt.minus(Duration.ofHours(4)),
                        preferredAt.plus(Duration.ofHours(4)));
            }
        });
        return new RecommendationScenario(scenario.admin(), scenario.tenant(), scenario.geA(), scenario.geB(),
                scenario.sessionId(), preferredAt, scenario.locality());
    }

    private Duration oldReservationLookupMargin() {
        int maximumDuration = visitPolicies.findById(VisitPolicy.SINGLETON_ID)
                .map(VisitPolicy::getMaxVisitSessionDurationMinutes)
                .orElse(VisitPolicy.DEFAULT_MAX_SESSION_DURATION_MINUTES);
        return Duration.ofMinutes(maximumDuration + recommendationPolicy.getUnknownTravelMinutes()
                + recommendationPolicy.getTravelBufferMinutes());
    }

    private VisitSession createNeighborReservation(RecommendationScenario scenario, User groundExecutive,
            String title, double latitude, double longitude, Instant start, int durationMinutes,
            VisitSessionStatus status) {
        Listing listing = recommendationListing(title, scenario.locality(), latitude, longitude);
        VisitSession neighbor = new VisitSession();
        neighbor.setTenant(scenario.tenant());
        neighbor.setCity(scenario.locality().getCity());
        neighbor.setCanonicalLocality(scenario.locality());
        neighbor.setStatus(status);
        neighbor.setScheduledAt(start);
        neighbor.setZoneId("Asia/Kolkata");
        neighbor.setRepresentative(groundExecutive);
        neighbor.setAssignedAt(Instant.now());
        neighbor.setDurationSnapshotMinutes(durationMinutes);
        neighbor.setReservedEndAt(start.plusSeconds(durationMinutes * 60L));
        neighbor = sessions.saveAndFlush(neighbor);

        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(scenario.tenant());
        request.setListing(listing);
        request.setSession(neighbor);
        request.setStatus(status == VisitSessionStatus.CANCELLED
                ? VisitRequestStatus.CANCELLED : VisitRequestStatus.SCHEDULED);
        request = requests.saveAndFlush(request);

        VisitSessionItem item = new VisitSessionItem();
        item.setSession(neighbor);
        item.setListing(listing);
        item.setPosition(1);
        item.setSourceRequest(request);
        item.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
        item.setAvailabilityConfirmedAt(Instant.now());
        item.setConfirmedBy(scenario.admin());
        items.saveAndFlush(item);
        return neighbor;
    }

    private void createNextLegConstrainedReservation(RecommendationScenario scenario) {
        Listing nextListing = recommendationListing("Far next appointment property", scenario.locality(), 22.72, 75.78);
        VisitSession future = new VisitSession();
        future.setTenant(scenario.tenant());
        future.setCity(scenario.locality().getCity());
        future.setCanonicalLocality(scenario.locality());
        future.setStatus(VisitSessionStatus.SCHEDULED);
        future.setScheduledAt(scenario.desiredAt().plusSeconds(60 * 60L));
        future.setZoneId("Asia/Kolkata");
        future.setRepresentative(scenario.geB());
        future.setAssignedAt(Instant.now());
        future.setDurationSnapshotMinutes(30);
        future.setReservedEndAt(scenario.desiredAt().plusSeconds(90 * 60L));
        future = sessions.saveAndFlush(future);
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(scenario.tenant());
        request.setListing(nextListing);
        request.setSession(future);
        request.setStatus(VisitRequestStatus.SCHEDULED);
        request = requests.saveAndFlush(request);
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(future);
        item.setListing(nextListing);
        item.setPosition(1);
        item.setSourceRequest(request);
        item.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
        item.setAvailabilityConfirmedAt(Instant.now());
        item.setConfirmedBy(scenario.admin());
        items.saveAndFlush(item);
    }

    private String approve(Actors actors, Long sessionId, Instant start,
                           CountDownLatch ready, CountDownLatch gate) {
        ready.countDown();
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("approval start gate timed out");
            operations.schedule(actors.admin().getId(), sessionId,
                    new ScheduleVisitSessionCommand(0L, start, "Asia/Kolkata", actors.ground().getId(), 30));
            return "COMMITTED";
        } catch (VisitOperationsConflictException expected) {
            return "CONFLICT";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private String approveRecommendation(Long actorId, Long sessionId,
            com.indore.pathome.spaces.dto.RecommendationCandidate candidate,
            CountDownLatch ready, CountDownLatch gate) {
        ready.countDown();
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("approval start gate timed out");
            operations.approveRecommendation(actorId, sessionId, new ApproveVisitRecommendationCommand(0L,
                    candidate.groundExecutiveUserId(), candidate.scheduledAt(), "Asia/Kolkata", null));
            return "COMMITTED";
        } catch (VisitOperationsConflictException expected) {
            return "CONFLICT";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private Actors actors() {
        User admin = user("admin", Role.ROLE_ADMIN);
        User tenant = user("tenant", Role.ROLE_TENANT);
        User ground = user("ground", Role.ROLE_GROUND_BOY);
        EmployeeProfile profile = new EmployeeProfile(ground, "GROUND_BOY", null, BigDecimal.ZERO);
        employees.saveAndFlush(profile);
        return new Actors(admin, tenant, ground);
    }

    private User user(String prefix, Role role) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        user.setFullName(prefix);
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private Long draftSession(Actors actors, String title) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle(title);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Test address");
        listing.setSector("Test sector");
        listing.setCity("Example City");
        listing.setBhkCount("1BHK");
        listing.setMonthlyRent(BigDecimal.ONE);
        listing.setSecurityDeposit(BigDecimal.ZERO);
        listing = (RentalDetails) listings.saveAndFlush(listing);

        VisitSession session = new VisitSession();
        session.setTenant(actors.tenant());
        session.setCity("Example City");
        session.setVersion(null);
        session = sessions.saveAndFlush(session);

        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(actors.tenant());
        request.setListing(listing);
        request.setSession(session);
        request.setStatus(VisitRequestStatus.COORDINATING);
        request.setVersion(null);
        request = requests.saveAndFlush(request);

        VisitSessionItem item = new VisitSessionItem();
        item.setSession(session);
        item.setListing(listing);
        item.setPosition(1);
        item.setSourceRequest(request);
        item.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
        item.setAvailabilityConfirmedAt(Instant.parse("2026-10-01T00:00:00Z"));
        item.setConfirmedBy(actors.admin());
        items.saveAndFlush(item);
        return session.getId();
    }

    private record Actors(User admin, User tenant, User ground) {}
    private record FinalizedOutcomeFixture(RecommendationScenario scenario,
            com.indore.pathome.spaces.dto.GroundVisitSessionOutcomeView outcomes) {}
    private record RecommendationScenario(User admin, User tenant, User geA, User geB,
            Long sessionId, Instant desiredAt, Locality locality) {}
}
