package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ScheduleVisitSessionCommand;
import com.indore.pathome.spaces.dto.RecommendationRequest;
import com.indore.pathome.spaces.dto.RecommendationStatus;
import com.indore.pathome.spaces.dto.ApproveVisitRecommendationCommand;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({VisitOperationsService.class, VisitOperationsAuthorizationService.class,
        VisitSessionNotificationListener.class, NotificationService.class, VisitSchedulingRecommendationService.class,
        ConservativeTravelTimeEstimator.class, SchedulingRecommendationPolicy.class})
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

        RecommendationScenario workloadScenario = recommendationScenario(false);
        createFutureWorkloadReservation(workloadScenario);
        var workloadResult = operations.recommend(workloadScenario.admin().getId(), workloadScenario.sessionId(),
                new RecommendationRequest(0L));
        assertEquals(workloadScenario.geB().getId(), workloadResult.candidates().get(0).groundExecutiveUserId());
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
                PageRequest.of(0, 20));

        List<VisitSession> previous = sessions.findNearestActiveReservationsBefore(geIds,
                List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED), windowStart);
        List<VisitSession> next = sessions.findNearestActiveReservationsAfter(geIds,
                List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED), windowEnd);

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
        future.setScheduledAt(scenario.desiredAt().plusSeconds(120 * 60L));
        future.setZoneId("Asia/Kolkata");
        future.setRepresentative(scenario.geA());
        future.setAssignedAt(Instant.now());
        future.setDurationSnapshotMinutes(30);
        future.setReservedEndAt(scenario.desiredAt().plusSeconds(150 * 60L));
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
    private record RecommendationScenario(User admin, User tenant, User geA, User geB,
            Long sessionId, Instant desiredAt, Locality locality) {}
}
