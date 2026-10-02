package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ScheduleVisitSessionCommand;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({VisitOperationsService.class, VisitOperationsAuthorizationService.class,
        VisitSessionNotificationListener.class, NotificationService.class})
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
}
