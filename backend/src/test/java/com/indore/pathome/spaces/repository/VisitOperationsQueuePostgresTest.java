package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.service.NotificationService;
import com.indore.pathome.spaces.service.OperationalNotificationAuthorizationService;
import com.indore.pathome.spaces.service.OperationalSecurityGuards;
import com.indore.pathome.spaces.service.VisitNotificationOutboxWorker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "PATHOME_VISIT_OPERATIONS_POSTGRES_TEST", matches = "true")
class VisitOperationsQueuePostgresTest {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String USERNAME = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");
    private static final String SCHEMA = "visit_queue_test_" + UUID.randomUUID().toString().replace("-", "");

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
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var connection = DriverManager.getConnection(BASE_URL, USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Autowired private PropertyVisitRequestRepository requests;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private LocalityRepository localities;
    @Autowired private EmployeeProfileRepository employees;
    @Autowired private LessorProfileRepository lessors;
    @Autowired private StaffAccessGrantRepository grants;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private VisitSessionRepository sessions;
    @Autowired private SystemNotificationRepository notifications;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void nativeOperationsQueueMapsFiltersCountsAndPagesOnPostgres() {
        User globalAdmin = staff("queue-global-admin", Role.ROLE_ADMIN);
        activateStaff(globalAdmin);
        grant(globalAdmin, StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null,
                Instant.now().minusSeconds(60), null);

        Locality canonical = localities.saveAndFlush(new Locality("Canonical City", "Central", null, null, null));
        LocalDateTime sameCreatedAt = LocalDateTime.of(2026, 1, 2, 10, 0);
        PropertyVisitRequest receivedOne = request("One", VisitRequestStatus.RECEIVED, sameCreatedAt,
                listing("Direct city", null));
        receivedOne.getListing().setCanonicalLocalityId(canonical.getId());
        receivedOne.getListing().setCity("Legacy city");
        receivedOne.setListing(listings.saveAndFlush(receivedOne.getListing()));
        requests.saveAndFlush(receivedOne);

        PropertyVisitRequest receivedTwo = request("Two", VisitRequestStatus.RECEIVED, sameCreatedAt,
                listing("Other city", null));
        receivedTwo = persist(receivedTwo);
        PropertyVisitRequest coordinating = persist(request("Three", VisitRequestStatus.COORDINATING,
                sameCreatedAt.plusDays(1), listing("Other city", null)));
        PropertyVisitRequest unavailable = persist(request("Four", VisitRequestStatus.UNAVAILABLE,
                sameCreatedAt.plusDays(2), listing("Other city", null)));

        Page<PropertyVisitRequestRepository.OperationsQueueRow> unfiltered = requests.findVisibleOperationsQueue(
                globalAdmin.getId(), null, null, PageRequest.of(0, 2));
        assertEquals(4, unfiltered.getTotalElements());
        assertEquals(2, unfiltered.getTotalPages());
        assertEquals(receivedTwo.getId(), unfiltered.getContent().get(0).getId());
        assertEquals(receivedOne.getId(), unfiltered.getContent().get(1).getId());
        PropertyVisitRequestRepository.OperationsQueueRow mapped = unfiltered.getContent().get(1);
        assertEquals(receivedOne.getId(), mapped.getId());
        assertEquals("RECEIVED", mapped.getStatus());
        assertEquals(receivedOne.getVersion(), mapped.getVersion());
        assertEquals(sameCreatedAt, mapped.getCreatedAt());
        assertEquals(receivedOne.getListing().getId(), mapped.getListingId());
        assertEquals("Direct city", mapped.getListingTitle());
        assertEquals("Canonical City", mapped.getCity());
        assertEquals("Test sector", mapped.getSector());

        Page<PropertyVisitRequestRepository.OperationsQueueRow> nextPage = requests.findVisibleOperationsQueue(
                globalAdmin.getId(), null, null, PageRequest.of(1, 2));
        assertEquals(coordinating.getId(), nextPage.getContent().get(0).getId());
        assertEquals(unavailable.getId(), nextPage.getContent().get(1).getId());

        Page<PropertyVisitRequestRepository.OperationsQueueRow> byStatus = requests.findVisibleOperationsQueue(
                globalAdmin.getId(), "RECEIVED", null, PageRequest.of(0, 10));
        assertEquals(2, byStatus.getTotalElements());
        assertTrue(byStatus.getContent().stream().allMatch(row -> "RECEIVED".equals(row.getStatus())));

        Page<PropertyVisitRequestRepository.OperationsQueueRow> byCanonicalCity = requests.findVisibleOperationsQueue(
                globalAdmin.getId(), null, " canonical city ", PageRequest.of(0, 10));
        assertEquals(1, byCanonicalCity.getTotalElements());
        assertEquals(receivedOne.getId(), byCanonicalCity.getContent().get(0).getId());

        Page<PropertyVisitRequestRepository.OperationsQueueRow> nullFilters = requests.findVisibleOperationsQueue(
                globalAdmin.getId(), null, null, PageRequest.of(0, 10));
        assertEquals(4, nullFilters.getTotalElements());
        assertEquals("Direct city", nullFilters.getContent().get(1).getListingTitle());
    }

    @Test
    void scopedQueueFiltersBeforePaginationAndEnforcesCapabilityRelationships() {
        SupportedCity north = city("north");
        SupportedCity south = city("south");
        OperatingTeam northOne = team(north, "north-one");
        OperatingTeam northTwo = team(north, "north-two");
        OperatingTeam southOne = team(south, "south-one");

        User intake = staff("intake", Role.ROLE_TENANT);
        User coordinator = staff("coordinator", Role.ROLE_TENANT);
        User supervisor = staff("supervisor", Role.ROLE_TENANT);
        User cityAdmin = staff("city-admin", Role.ROLE_TENANT);
        User cityAdminWithSupervisor = staff("city-admin-with-supervisor", Role.ROLE_TENANT);
        User globalAdmin = staff("global-admin", Role.ROLE_TENANT);
        User otherCoordinator = staff("other-coordinator", Role.ROLE_TENANT);
        activateStaff(intake);
        activateStaff(coordinator);
        activateStaff(supervisor);
        activateStaff(cityAdmin);
        activateStaff(cityAdminWithSupervisor);
        activateStaff(globalAdmin);
        grant(intake, StaffCapability.OPS_INTAKE, StaffScopeType.CITY, north, null,
                Instant.now().minusSeconds(60), null);
        grant(coordinator, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, northOne,
                Instant.now().minusSeconds(60), null);
        grant(supervisor, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, northOne,
                Instant.now().minusSeconds(60), null);
        grant(cityAdmin, StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, north, null,
                Instant.now().minusSeconds(60), null);
        grant(cityAdminWithSupervisor, StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, north, null,
                Instant.now().minusSeconds(60), null);
        grant(cityAdminWithSupervisor, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, northOne,
                Instant.now().minusSeconds(60), null);
        grant(globalAdmin, StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null,
                Instant.now().minusSeconds(60), null);

        LocalDateTime created = LocalDateTime.of(2026, 2, 1, 10, 0);
        for (int i = 0; i < 5; i++) scopedRequest("intake-north-" + i, VisitRequestStatus.RECEIVED,
                created.plusMinutes(i), north, null, null, null, true);
        for (int i = 0; i < 15; i++) scopedRequest("intake-south-" + i, VisitRequestStatus.RECEIVED,
                created.plusMinutes(10 + i), south, null, null, null, true);

        VisitSession own = scopedSession("coordinator-own", north, northOne, coordinator);
        VisitSession teammate = scopedSession("coordinator-teammate", north, northOne, otherCoordinator);
        VisitSession otherTeam = scopedSession("coordinator-other-team", north, northTwo, otherCoordinator);
        scopedRequest("linked-own", VisitRequestStatus.COORDINATING, created.plusHours(1), north, northOne,
                coordinator, own, true);
        scopedRequest("linked-teammate", VisitRequestStatus.COORDINATING, created.plusHours(2), north, northOne,
                otherCoordinator, teammate, true);
        scopedRequest("linked-other-team", VisitRequestStatus.COORDINATING, created.plusHours(3), north, northTwo,
                otherCoordinator, otherTeam, true);

        var intakeFirstPage = requests.findVisibleOperationsQueue(intake.getId(), null, null, PageRequest.of(0, 10));
        assertEquals(5, intakeFirstPage.getTotalElements());
        assertEquals(5, intakeFirstPage.getContent().size(), "scoping must happen before pagination");
        assertEquals(1, intakeFirstPage.getTotalPages());
        assertTrue(requests.findVisibleOperationsQueue(intake.getId(), null, null, PageRequest.of(1, 10))
                .isEmpty());

        assertEquals(1, requests.findVisibleOperationsQueue(coordinator.getId(), "COORDINATING", null,
                PageRequest.of(0, 10)).getTotalElements());
        assertEquals(2, requests.findVisibleOperationsQueue(supervisor.getId(), "COORDINATING", null,
                PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, requests.findVisibleOperationsQueue(cityAdmin.getId(), null, null,
                PageRequest.of(0, 20)).getTotalElements());
        assertEquals(2, requests.findVisibleOperationsQueue(cityAdminWithSupervisor.getId(), "COORDINATING", null,
                PageRequest.of(0, 20)).getTotalElements(), "ordinary access follows the independent supervisor grant");
        assertEquals(23, requests.findVisibleOperationsQueue(globalAdmin.getId(), null, null,
                PageRequest.of(0, 30)).getTotalElements());

        assertTrue(sessions.findVisibleToStaffById(coordinator.getId(), own.getId()).isPresent());
        assertTrue(sessions.findVisibleToStaffById(coordinator.getId(), teammate.getId()).isEmpty());
        assertTrue(sessions.findVisibleToStaffById(supervisor.getId(), teammate.getId()).isPresent());
        assertTrue(sessions.findVisibleToStaffById(supervisor.getId(), otherTeam.getId()).isEmpty());
        assertTrue(sessions.findVisibleToStaffById(cityAdmin.getId(), otherTeam.getId()).isEmpty());
        assertTrue(sessions.findVisibleToStaffById(cityAdminWithSupervisor.getId(), teammate.getId()).isPresent());
        assertTrue(sessions.findVisibleToStaffById(cityAdminWithSupervisor.getId(), otherTeam.getId()).isEmpty());
        assertTrue(sessions.findVisibleToStaffById(globalAdmin.getId(), otherTeam.getId()).isPresent());

        northOne.setActive(false);
        teams.saveAndFlush(northOne);
        assertTrue(sessions.findVisibleToStaffById(supervisor.getId(), own.getId()).isEmpty());
        assertEquals(0, requests.findVisibleOperationsQueue(cityAdmin.getId(), "COORDINATING", null,
                PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, requests.findVisibleOperationsQueue(cityAdminWithSupervisor.getId(), "COORDINATING", null,
                PageRequest.of(0, 10)).getTotalElements(), "inactive Team work is hidden even with a supervisor grant");
        northOne.setActive(true);
        teams.saveAndFlush(northOne);

        StaffAccessGrant coordinatorGrant = grants.findEffectiveForUser(coordinator.getId()).get(0);
        coordinatorGrant.revoke(globalAdmin, Instant.now(), "TEST_REVOKED");
        grants.saveAndFlush(coordinatorGrant);
        assertEquals(0, requests.findVisibleOperationsQueue(coordinator.getId(), "COORDINATING", null,
                PageRequest.of(0, 10)).getTotalElements());
        assertTrue(sessions.findVisibleToStaffById(coordinator.getId(), own.getId()).isEmpty());

        User expired = staff("expired", Role.ROLE_TENANT);
        activateStaff(expired);
        Instant now = Instant.now();
        grant(expired, StaffCapability.OPS_INTAKE, StaffScopeType.CITY, north, null,
                now.minusSeconds(120), now.minusSeconds(60));
        assertEquals(0, requests.findVisibleOperationsQueue(expired.getId(), null, null,
                PageRequest.of(0, 10)).getTotalElements());

        north.setActive(false);
        cities.saveAndFlush(north);
        assertEquals(0, requests.findVisibleOperationsQueue(intake.getId(), null, null,
                PageRequest.of(0, 10)).getTotalElements());
        assertEquals(23, requests.findVisibleOperationsQueue(globalAdmin.getId(), null, null,
                PageRequest.of(0, 30)).getTotalElements());
    }

    @Test
    void notificationFeedCountAndReadRequireRecipientAndCurrentSessionVisibility() {
        SupportedCity north = city("notify-north");
        SupportedCity south = city("notify-south");
        OperatingTeam northTeam = team(north, "notify-north-team");
        OperatingTeam southTeam = team(south, "notify-south-team");
        User coordinator = staff("notify-coordinator", Role.ROLE_TENANT);
        User revokedCoordinator = staff("notify-revoked", Role.ROLE_TENANT);
        User tenant = staff("notify-tenant", Role.ROLE_TENANT);
        User lessor = staff("notify-lessor", Role.ROLE_LANDLORD);
        User admin = staff("notify-admin", Role.ROLE_ADMIN);
        activateStaff(coordinator);
        activateStaff(revokedCoordinator);
        grant(coordinator, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, northTeam,
                Instant.now().minusSeconds(60), null);
        StaffAccessGrant revocable = grant(revokedCoordinator, StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, northTeam, Instant.now().minusSeconds(60), null);
        VisitSession coordinatorSession = scopedSession("notify-coordinator", north, northTeam, coordinator);
        VisitSession transferableSession = scopedSession("notify-transfer", north, northTeam, revokedCoordinator);
        VisitSession transferAfterGrantSession = scopedSession("notify-transfer-after-grant", north, northTeam, coordinator);
        var liveAuthorization = notificationAuthorization();
        assertTrue(liveAuthorization.lockAndCanDeliver(coordinator.getId(), coordinatorSession.getId(), "EMPLOYEE"));
        assertTrue(liveAuthorization.lockAndCanDeliver(revokedCoordinator.getId(), transferableSession.getId(), "EMPLOYEE"));

        SystemNotification ownOperational = notification(TargetRole.EMPLOYEE, coordinator.getId(),
                NotificationAuthorizationClass.OPERATIONS_SESSION, coordinatorSession.getId(), "own operational");
        SystemNotification alsoOwnOperational = notification(TargetRole.EMPLOYEE, coordinator.getId(),
                NotificationAuthorizationClass.OPERATIONS_SESSION, transferAfterGrantSession.getId(), "also own operational");
        SystemNotification revokedRecipientNotice = notification(TargetRole.EMPLOYEE, revokedCoordinator.getId(),
                NotificationAuthorizationClass.OPERATIONS_SESSION, transferableSession.getId(), "revoked recipient");
        SystemNotification quarantined = notification(TargetRole.EMPLOYEE, coordinator.getId(),
                NotificationAuthorizationClass.STAFF_LEGACY_QUARANTINED, null, "quarantined staff notice");
        SystemNotification missingSession = notification(TargetRole.EMPLOYEE, coordinator.getId(), NotificationAuthorizationClass.OPERATIONS_SESSION,
                null, "missing session reference");

        assertEquals(2, notifications.findCurrentlyVisibleForUser(coordinator.getId()).size());
        assertTrue(notifications.findCurrentlyVisibleForUser(coordinator.getId()).containsAll(
                List.of(ownOperational, alsoOwnOperational)));
        assertEquals(2, notifications.countCurrentlyVisibleUnreadForUser(coordinator.getId()));
        assertEquals(List.of(revokedRecipientNotice), notifications.findCurrentlyVisibleForUser(revokedCoordinator.getId()));
        assertEquals(1, notifications.markVisibleReadForUser(ownOperational.getId(), coordinator.getId(),
                LocalDateTime.now()));
        assertEquals(1, notifications.countCurrentlyVisibleUnreadForUser(coordinator.getId()));
        assertEquals(1, notifications.markAllCurrentlyVisibleReadForUser(coordinator.getId(), LocalDateTime.now()));
        assertEquals(0, notifications.countCurrentlyVisibleUnreadForUser(coordinator.getId()));
        assertFalse(notifications.findById(quarantined.getId()).orElseThrow().isRead());
        assertFalse(notifications.findById(missingSession.getId()).orElseThrow().isRead());

        revocable.revoke(admin, Instant.now(), "TEST_REVOKED");
        grants.saveAndFlush(revocable);
        assertFalse(liveAuthorization.lockAndCanDeliver(revokedCoordinator.getId(), transferableSession.getId(), "EMPLOYEE"));
        assertTrue(notifications.findCurrentlyVisibleForUser(revokedCoordinator.getId()).isEmpty());
        assertEquals(0, notifications.countCurrentlyVisibleUnreadForUser(revokedCoordinator.getId()));
        assertEquals(0, notifications.markVisibleReadForUser(
                revokedRecipientNotice.getId(), revokedCoordinator.getId(), LocalDateTime.now()));
        assertEquals(0, notifications.markAllCurrentlyVisibleReadForUser(revokedCoordinator.getId(), LocalDateTime.now()));
        assertFalse(notifications.findById(revokedRecipientNotice.getId()).orElseThrow().isRead());

        SystemNotification transferred = notification(TargetRole.EMPLOYEE, revokedCoordinator.getId(),
                NotificationAuthorizationClass.OPERATIONS_SESSION, transferableSession.getId(), "transferred");
        assertFalse(notifications.findCurrentlyVisibleForUser(revokedCoordinator.getId()).contains(transferred));

        transferAfterGrantSession.setSupportedCity(south);
        transferAfterGrantSession.setOperatingTeam(southTeam);
        sessions.saveAndFlush(transferAfterGrantSession);
        assertFalse(liveAuthorization.lockAndCanDeliver(coordinator.getId(), transferAfterGrantSession.getId(), "EMPLOYEE"));
        assertFalse(notifications.findCurrentlyVisibleForUser(coordinator.getId()).contains(alsoOwnOperational));
        assertEquals(0, notifications.markVisibleReadForUser(alsoOwnOperational.getId(), coordinator.getId(),
                LocalDateTime.now()));

        SystemNotification tenantNotice = notification(TargetRole.TENANT, tenant.getId(),
                NotificationAuthorizationClass.RECIPIENT, null, "tenant notice");
        SystemNotification lessorNotice = notification(TargetRole.LANDLORD, lessor.getId(),
                NotificationAuthorizationClass.RECIPIENT, null, "lessor notice");
        assertEquals(List.of(tenantNotice), notifications.findCurrentlyVisibleForUser(tenant.getId()));
        assertEquals(1, notifications.countCurrentlyVisibleUnreadForUser(tenant.getId()));
        assertEquals(1, notifications.markVisibleReadForUser(tenantNotice.getId(), tenant.getId(), LocalDateTime.now()));
        assertEquals(0, notifications.markVisibleReadForUser(tenantNotice.getId(), lessor.getId(), LocalDateTime.now()));
        assertEquals(List.of(lessorNotice), notifications.findCurrentlyVisibleForUser(lessor.getId()));
        assertEquals(1, notifications.countCurrentlyVisibleUnreadForUser(lessor.getId()));
        assertEquals(1, notifications.markVisibleReadForUser(lessorNotice.getId(), lessor.getId(), LocalDateTime.now()));

        SystemNotification defaultAllCustomerNotice = notification(TargetRole.ALL, tenant.getId(),
                NotificationAuthorizationClass.RECIPIENT, null, "default ALL direct customer");
        assertTrue(notifications.findCurrentlyVisibleForUser(tenant.getId()).contains(defaultAllCustomerNotice));
        assertEquals(1, notifications.countCurrentlyVisibleUnreadForUser(tenant.getId()));
        assertEquals(1, notifications.markVisibleReadForUser(defaultAllCustomerNotice.getId(), tenant.getId(), LocalDateTime.now()));
        assertFalse(notifications.findCurrentlyVisibleForUser(lessor.getId()).contains(defaultAllCustomerNotice));
        assertEquals(0, notifications.markVisibleReadForUser(defaultAllCustomerNotice.getId(), lessor.getId(), LocalDateTime.now()));
    }

    @Test
    void explicitTenantNoticeRemainsRecipientScopedForTenantWithEmployeeProfile() {
        User dualCapabilityTenant = staff("dual-capability-tenant", Role.ROLE_TENANT);
        employees.saveAndFlush(new EmployeeProfile(dualCapabilityTenant, "OPERATIONS", null, null));
        User otherTenant = staff("dual-capability-other-tenant", Role.ROLE_TENANT);
        NotificationService service = new NotificationService(notifications, null, users, employees, lessors);

        SystemNotification notice = service.createAdminNotification(TargetRole.TENANT,
                dualCapabilityTenant.getId().toString(), "Tenant account notice", "Customer content",
                null, "SYSTEM", "info");

        assertEquals(Role.ROLE_TENANT, users.findById(dualCapabilityTenant.getId()).orElseThrow().getRole());
        assertEquals(NotificationAuthorizationClass.RECIPIENT, notice.getAuthorizationClass());
        assertTrue(notifications.findCurrentlyVisibleForUser(dualCapabilityTenant.getId()).contains(notice));
        assertEquals(1, notifications.countCurrentlyVisibleUnreadForUser(dualCapabilityTenant.getId()));
        assertEquals(1, notifications.markVisibleReadForUser(
                notice.getId(), dualCapabilityTenant.getId(), LocalDateTime.now()));
        assertFalse(notifications.findCurrentlyVisibleForUser(otherTenant.getId()).contains(notice));
        assertEquals(0, notifications.markVisibleReadForUser(notice.getId(), otherTenant.getId(), LocalDateTime.now()));
    }

    @Test
    void deactivatedAssignedGroundExecutiveLosesOperationalNotificationAccessAndDelivery() {
        User groundExecutive = staff("deactivated-ge", Role.ROLE_GROUND_BOY);
        EmployeeProfile profile = new EmployeeProfile(groundExecutive, "GROUND_BOY", null, null);
        profile.setStaffActive(true);
        employees.saveAndFlush(profile);
        SupportedCity north = city("deactivated-ge");
        OperatingTeam team = team(north, "deactivated-ge");
        VisitSession assigned = scopedSession("deactivated-ge", north, team, groundExecutive);
        assigned.setRepresentative(groundExecutive);
        assigned.setAssignedAt(Instant.now());
        assigned = sessions.saveAndFlush(assigned);
        Long sessionId = assigned.getId();
        SystemNotification operationsNotice = notification(TargetRole.GROUND_BOY, groundExecutive.getId(),
                NotificationAuthorizationClass.OPERATIONS_SESSION, sessionId, "assigned GE notice");
        var liveAuthorization = notificationAuthorization();

        assertTrue(liveAuthorization.lockAndCanDeliver(groundExecutive.getId(), sessionId, "GROUND_BOY"));
        assertEquals(List.of(operationsNotice), notifications.findCurrentlyVisibleForUser(groundExecutive.getId()));
        assertEquals(1, notifications.countCurrentlyVisibleUnreadForUser(groundExecutive.getId()));
        assertEquals(1, notifications.markVisibleReadForUser(operationsNotice.getId(), groundExecutive.getId(), LocalDateTime.now()));
        operationsNotice.setRead(false);
        operationsNotice.setReadAt(null);
        notifications.saveAndFlush(operationsNotice);

        EmployeeProfile persistedProfile = employees.findByUserId(groundExecutive.getId()).orElseThrow();
        persistedProfile.setStaffActive(false);
        employees.saveAndFlush(persistedProfile);
        assertEquals(groundExecutive.getId(), sessions.findById(sessionId).orElseThrow()
                .getRepresentative().getId(), "deactivation proof keeps the Session assignment intact");
        assertFalse(liveAuthorization.lockAndCanDeliver(groundExecutive.getId(), sessionId, "GROUND_BOY"));
        assertTrue(notifications.findCurrentlyVisibleForUser(groundExecutive.getId()).isEmpty());
        assertEquals(0, notifications.countCurrentlyVisibleUnreadForUser(groundExecutive.getId()));
        assertEquals(0, notifications.markVisibleReadForUser(operationsNotice.getId(), groundExecutive.getId(), LocalDateTime.now()));
        assertEquals(0, notifications.markAllCurrentlyVisibleReadForUser(groundExecutive.getId(), LocalDateTime.now()));
        assertFalse(notifications.findById(operationsNotice.getId()).orElseThrow().isRead());

        insertOutbox("deactivated-ge-outbox", groundExecutive.getId(), "GROUND_BOY",
                "OPERATIONS_SESSION", sessionId);
        NotificationService notificationService = org.mockito.Mockito.mock(NotificationService.class);
        VisitNotificationOutboxWorker worker = new VisitNotificationOutboxWorker(jdbc, notificationService,
                liveAuthorization, transactionManager);
        worker.deliverBatch();
        assertEquals("SUPPRESSED", outboxState("deactivated-ge-outbox"));
        org.mockito.Mockito.verifyNoInteractions(notificationService);
    }

    @Test
    void assignedGroundExecutiveLosesNotificationAuthorityWhenOwningTeamOrCityDeactivates() {
        User teamGroundExecutive = staff("team-deactivated-ge", Role.ROLE_GROUND_BOY);
        EmployeeProfile teamProfile = new EmployeeProfile(teamGroundExecutive, "GROUND_BOY", null, null);
        teamProfile.setStaffActive(true);
        employees.saveAndFlush(teamProfile);
        SupportedCity teamCity = city("team-deactivation");
        OperatingTeam team = team(teamCity, "team-deactivation");
        VisitSession teamSession = scopedSession("team-deactivation", teamCity, team, teamGroundExecutive);
        teamSession.setRepresentative(teamGroundExecutive);
        teamSession.setAssignedAt(Instant.now());
        teamSession = sessions.saveAndFlush(teamSession);
        Long teamSessionId = teamSession.getId();
        SystemNotification teamNotice = notification(TargetRole.GROUND_BOY, teamGroundExecutive.getId(),
                NotificationAuthorizationClass.OPERATIONS_SESSION, teamSessionId, "team deactivation notice");

        User cityGroundExecutive = staff("city-deactivated-ge", Role.ROLE_GROUND_BOY);
        EmployeeProfile cityProfile = new EmployeeProfile(cityGroundExecutive, "GROUND_BOY", null, null);
        cityProfile.setStaffActive(true);
        employees.saveAndFlush(cityProfile);
        SupportedCity city = city("city-deactivation");
        OperatingTeam cityTeam = team(city, "city-deactivation");
        VisitSession citySession = scopedSession("city-deactivation", city, cityTeam, cityGroundExecutive);
        citySession.setRepresentative(cityGroundExecutive);
        citySession.setAssignedAt(Instant.now());
        citySession = sessions.saveAndFlush(citySession);
        Long citySessionId = citySession.getId();
        SystemNotification cityNotice = notification(TargetRole.GROUND_BOY, cityGroundExecutive.getId(),
                NotificationAuthorizationClass.OPERATIONS_SESSION, citySessionId, "city deactivation notice");

        User genericEmployee = staff("unassigned-generic-employee", Role.ROLE_TENANT);
        EmployeeProfile genericProfile = new EmployeeProfile(genericEmployee, "OPERATIONS", null, null);
        genericProfile.setStaffActive(true);
        employees.saveAndFlush(genericProfile);
        SystemNotification genericNotice = notification(TargetRole.EMPLOYEE, genericEmployee.getId(),
                NotificationAuthorizationClass.OPERATIONS_SESSION, teamSessionId, "unassigned employee notice");

        var liveAuthorization = notificationAuthorization();
        assertTrue(liveAuthorization.lockAndCanDeliver(teamGroundExecutive.getId(), teamSessionId, "GROUND_BOY"));
        assertTrue(liveAuthorization.lockAndCanDeliver(cityGroundExecutive.getId(), citySessionId, "GROUND_BOY"));
        assertFalse(liveAuthorization.lockAndCanDeliver(teamGroundExecutive.getId(), teamSessionId, "EMPLOYEE"));
        assertFalse(liveAuthorization.lockAndCanDeliver(genericEmployee.getId(), teamSessionId, "EMPLOYEE"));
        assertFalse(liveAuthorization.lockAndCanDeliver(genericEmployee.getId(), teamSessionId, "GROUND_BOY"));
        assertTrue(notifications.findCurrentlyVisibleForUser(teamGroundExecutive.getId()).contains(teamNotice));
        assertTrue(notifications.findCurrentlyVisibleForUser(cityGroundExecutive.getId()).contains(cityNotice));
        assertFalse(notifications.findCurrentlyVisibleForUser(genericEmployee.getId()).contains(genericNotice));
        assertEquals(1, notifications.countCurrentlyVisibleUnreadForUser(teamGroundExecutive.getId()));
        assertEquals(1, notifications.countCurrentlyVisibleUnreadForUser(cityGroundExecutive.getId()));

        team.setActive(false);
        teams.saveAndFlush(team);
        assertEquals(teamGroundExecutive.getId(), sessions.findById(teamSessionId).orElseThrow()
                .getRepresentative().getId(), "Team deactivation must not clear the GE assignment");
        assertFalse(liveAuthorization.lockAndCanDeliver(teamGroundExecutive.getId(), teamSessionId, "GROUND_BOY"));
        assertFalse(notifications.findCurrentlyVisibleForUser(teamGroundExecutive.getId()).contains(teamNotice));
        assertEquals(0, notifications.countCurrentlyVisibleUnreadForUser(teamGroundExecutive.getId()));
        assertEquals(0, notifications.markVisibleReadForUser(teamNotice.getId(), teamGroundExecutive.getId(), LocalDateTime.now()));
        assertEquals(0, notifications.markAllCurrentlyVisibleReadForUser(teamGroundExecutive.getId(), LocalDateTime.now()));

        insertOutbox("team-deactivated-ge-outbox", teamGroundExecutive.getId(), "GROUND_BOY",
                "OPERATIONS_SESSION", teamSessionId);
        NotificationService notificationService = org.mockito.Mockito.mock(NotificationService.class);
        VisitNotificationOutboxWorker worker = new VisitNotificationOutboxWorker(jdbc, notificationService,
                liveAuthorization, transactionManager);
        worker.deliverBatch();
        assertEquals("SUPPRESSED", outboxState("team-deactivated-ge-outbox"));
        org.mockito.Mockito.verifyNoInteractions(notificationService);

        city.setActive(false);
        cities.saveAndFlush(city);
        assertEquals(cityGroundExecutive.getId(), sessions.findById(citySessionId).orElseThrow()
                .getRepresentative().getId(), "City deactivation must not clear the GE assignment");
        assertFalse(liveAuthorization.lockAndCanDeliver(cityGroundExecutive.getId(), citySessionId, "GROUND_BOY"));
        assertFalse(notifications.findCurrentlyVisibleForUser(cityGroundExecutive.getId()).contains(cityNotice));
        assertEquals(0, notifications.countCurrentlyVisibleUnreadForUser(cityGroundExecutive.getId()));
        assertEquals(0, notifications.markVisibleReadForUser(cityNotice.getId(), cityGroundExecutive.getId(), LocalDateTime.now()));
        assertEquals(0, notifications.markAllCurrentlyVisibleReadForUser(cityGroundExecutive.getId(), LocalDateTime.now()));
        assertFalse(notifications.findById(cityNotice.getId()).orElseThrow().isRead());

        insertOutbox("city-deactivated-ge-outbox", cityGroundExecutive.getId(), "GROUND_BOY",
                "OPERATIONS_SESSION", citySessionId);
        worker.deliverBatch();
        assertEquals("SUPPRESSED", outboxState("city-deactivated-ge-outbox"));
        org.mockito.Mockito.verifyNoInteractions(notificationService);
        assertFalse(notifications.findById(teamNotice.getId()).orElseThrow().isRead());
    }

    @Test
    void assignedGroundExecutiveWithCanonicalCityAndNoTeamRetainsNotificationAccess() {
        User groundExecutive = staff("city-intake-ge", Role.ROLE_GROUND_BOY);
        EmployeeProfile profile = new EmployeeProfile(groundExecutive, "GROUND_BOY", null, null);
        profile.setStaffActive(true);
        employees.saveAndFlush(profile);
        SupportedCity city = city("city-intake-ge");
        VisitSession session = scopedSession("city-intake-ge", city, null, groundExecutive);
        session.setRepresentative(groundExecutive);
        session.setAssignedAt(Instant.now());
        session = sessions.saveAndFlush(session);
        SystemNotification notice = notification(TargetRole.GROUND_BOY, groundExecutive.getId(),
                NotificationAuthorizationClass.OPERATIONS_SESSION, session.getId(), "city intake assignment");

        assertNull(sessions.findById(session.getId()).orElseThrow().getOperatingTeam());
        assertTrue(notificationAuthorization().lockAndCanDeliver(groundExecutive.getId(), session.getId(), "GROUND_BOY"));
        assertTrue(notifications.findCurrentlyVisibleForUser(groundExecutive.getId()).contains(notice));
        assertEquals(1, notifications.countCurrentlyVisibleUnreadForUser(groundExecutive.getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void outboxAuthorizationDenialIsTerminalButTransientDeliveryFailureRetries() {
        User recipient = staff("outbox-recipient", Role.ROLE_TENANT);
        User tenant = staff("outbox-tenant", Role.ROLE_TENANT);
        SupportedCity north = city("outbox-north");
        OperatingTeam team = team(north, "outbox-team");
        VisitSession session = scopedSession("outbox-session", north, team, recipient);
        insertOutbox("outbox-auth-denied", recipient.getId(), "EMPLOYEE", "OPERATIONS_SESSION", session.getId());
        insertOutbox("outbox-missing-session", recipient.getId(), "EMPLOYEE", "OPERATIONS_SESSION", null);
        insertOutbox("outbox-legacy", recipient.getId(), "EMPLOYEE", "STAFF_LEGACY_QUARANTINED", null);
        insertOutbox("outbox-transient", tenant.getId(), "TENANT", "RECIPIENT", null);

        NotificationService notificationService = org.mockito.Mockito.mock(NotificationService.class);
        OperationalNotificationAuthorizationService notificationAuthorization = notificationAuthorization();
        org.mockito.Mockito.when(notificationService.createNotificationWithEventKeyInCurrentTransaction(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("simulated transient persistence failure"));

        VisitNotificationOutboxWorker worker = new VisitNotificationOutboxWorker(jdbc, notificationService,
                notificationAuthorization, transactionManager);
        worker.deliverBatch();

        assertEquals("SUPPRESSED", outboxState("outbox-auth-denied"));
        assertEquals("SUPPRESSED", outboxState("outbox-missing-session"));
        assertEquals("SUPPRESSED", outboxState("outbox-legacy"));
        assertEquals("AUTHORIZATION_DENIED", outboxError("outbox-auth-denied"));
        assertEquals("FAILED", outboxState("outbox-transient"));
        assertEquals("DELIVERY_FAILED", outboxError("outbox-transient"));
        assertEquals(1, outboxAttempts("outbox-transient"));
    }

    private PropertyVisitRequest persist(PropertyVisitRequest request) {
        Listing savedListing = listings.saveAndFlush((Listing) request.getListing());
        request.setListing(savedListing);
        return requests.saveAndFlush(request);
    }

    private PropertyVisitRequest request(String suffix, VisitRequestStatus status, LocalDateTime createdAt,
                                         RentalDetails listing) {
        User tenant = new User();
        tenant.setEmail("queue-" + suffix.toLowerCase() + "@example.test");
        tenant.setRole(Role.ROLE_TENANT);
        tenant.setFullName("Queue " + suffix);
        tenant = users.saveAndFlush(tenant);
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(listing);
        request.setStatus(status);
        request.setVersion(null);
        request.setCreatedAt(createdAt);
        return request;
    }

    private RentalDetails listing(String suffix, Long localityId) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle(suffix);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Test address " + suffix);
        listing.setSector("Test sector");
        listing.setCity("Other city");
        listing.setCanonicalLocalityId(localityId);
        listing.setBhkCount("1BHK");
        listing.setMonthlyRent(BigDecimal.ONE);
        listing.setSecurityDeposit(BigDecimal.ZERO);
        return listing;
    }

    private User staff(String suffix, Role role) {
        User user = new User();
        user.setEmail(suffix + "-" + UUID.randomUUID() + "@example.test");
        user.setRole(role);
        user.setFullName(suffix);
        return users.saveAndFlush(user);
    }

    private void activateStaff(User user) {
        EmployeeProfile profile = new EmployeeProfile(user, "OPERATIONS", null, null);
        profile.setStaffActive(true);
        employees.saveAndFlush(profile);
    }

    private StaffAccessGrant grant(User user, StaffCapability capability, StaffScopeType scope,
                                   SupportedCity city, OperatingTeam team, Instant effectiveAt, Instant expiresAt) {
        return grants.saveAndFlush(new StaffAccessGrant(user, capability, scope, city, team,
                effectiveAt, expiresAt, null, Instant.now(), "TEST_PHASE1D", StaffGrantProvisioningSource.ADMIN_API));
    }

    private SupportedCity city(String suffix) {
        return cities.saveAndFlush(new SupportedCity("test-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8),
                "Test " + suffix, true));
    }

    private OperatingTeam team(SupportedCity city, String suffix) {
        return teams.saveAndFlush(new OperatingTeam(city,
                "test-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8), "Test " + suffix, true));
    }

    private VisitSession scopedSession(String suffix, SupportedCity city, OperatingTeam team, User coordinator) {
        User tenant = staff("session-tenant-" + suffix, Role.ROLE_TENANT);
        VisitSession session = new VisitSession();
        session.setTenant(tenant);
        session.setCity(city.getDisplayName());
        session.setSupportedCity(city);
        session.setOperatingTeam(team);
        session.setCoordinator(coordinator);
        session.setOperationalScopeReady(true);
        return sessions.saveAndFlush(session);
    }

    private PropertyVisitRequest scopedRequest(String suffix, VisitRequestStatus status, LocalDateTime createdAt,
            SupportedCity city, OperatingTeam team, User coordinator, VisitSession session, boolean ready) {
        User tenant = staff("request-tenant-" + suffix, Role.ROLE_TENANT);
        RentalDetails listing = listing(suffix, null);
        listing.setCity(city.getDisplayName());
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(listings.saveAndFlush(listing));
        request.setStatus(status);
        request.setCreatedAt(createdAt);
        request.setSupportedCity(city);
        request.setOperatingTeam(team);
        request.setCoordinator(coordinator);
        request.setOperationalScopeReady(ready);
        request.setSession(session);
        return requests.saveAndFlush(request);
    }

    private SystemNotification notification(TargetRole role, Long recipientId, NotificationAuthorizationClass type,
            Long sessionId, String title) {
        SystemNotification notification = new SystemNotification(role, recipientId.toString(), title,
                "test notification", null, "VISIT_SESSION", "info");
        notification.setAuthorizationClass(type);
        notification.setOperationalSessionId(sessionId);
        return notifications.saveAndFlush(notification);
    }

    private void insertOutbox(String eventKey, Long recipientId, String role, String classification, Long sessionId) {
        jdbc.update("""
                INSERT INTO visit_notification_outbox
                    (event_key, recipient_user_id, recipient_role, authorization_class, operational_session_id,
                     event_type, title, message, available_at)
                VALUES (?, ?, ?, ?, ?, 'TEST', ?, 'test message', CURRENT_TIMESTAMP - INTERVAL '1 second')
                """, eventKey, recipientId, role, classification, sessionId, eventKey);
    }

    private String outboxState(String eventKey) {
        return jdbc.queryForObject("SELECT state FROM visit_notification_outbox WHERE event_key = ?", String.class, eventKey);
    }

    private String outboxError(String eventKey) {
        return jdbc.queryForObject("SELECT last_error_code FROM visit_notification_outbox WHERE event_key = ?", String.class, eventKey);
    }

    private int outboxAttempts(String eventKey) {
        return jdbc.queryForObject("SELECT attempts FROM visit_notification_outbox WHERE event_key = ?", Integer.class, eventKey);
    }

    private OperationalNotificationAuthorizationService notificationAuthorization() {
        OperationalSecurityGuards guards = new OperationalSecurityGuards(cities, teams, employees, grants, jdbc);
        return new OperationalNotificationAuthorizationService(sessions, guards);
    }
}
