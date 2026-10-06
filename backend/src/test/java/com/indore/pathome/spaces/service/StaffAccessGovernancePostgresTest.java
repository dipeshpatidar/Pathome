package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.StaffCapabilitiesResponse;
import com.indore.pathome.spaces.dto.StaffCapabilitySummary;
import com.indore.pathome.spaces.dto.StaffGrantCommand;
import com.indore.pathome.spaces.dto.StaffGrantView;
import com.indore.pathome.spaces.dto.StaffStateCommand;
import com.indore.pathome.spaces.dto.StaffUserAccessState;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import com.indore.pathome.spaces.exception.StaffAccessConflictException;
import com.indore.pathome.spaces.exception.StaffAccessConflictException;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.OperationalAuditEventRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionItemRepository;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.controller.StaffCapabilitiesController;
import com.indore.pathome.spaces.service.VisitOperationsAuthorizationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
@Import({StaffAccessService.class, StaffAdministrationService.class, StaffAdminBootstrapService.class,
        OperationalAuditService.class, DatabaseClock.class, StaffGovernanceLock.class,
        OperationalSecurityGuards.class, VisitOperationsAuthorizationService.class,
        OperationalOwnershipService.class, TrustedOperationalIntakeService.class, VisitOperationsService.class,
        VisitRepairOperationsService.class,
        StaffAccessGovernancePostgresTest.TestBeans.class})
class StaffAccessGovernancePostgresTest {
    private static final String SCHEMA = "pathome_staff_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final String DB_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String DB_USER = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String DB_PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");

    @SpyBean private UserRepository users;
    @Autowired private EmployeeProfileRepository employeeProfiles;
    @SpyBean private StaffAccessGrantRepository grants;
    @SpyBean private OperationalSecurityGuards securityGuards;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private LocalityRepository localities;
    @Autowired private ListingRepository listings;
    @SpyBean private PropertyVisitRequestRepository requests;
    @Autowired private OperationalAuditEventRepository audits;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private StaffAccessService access;
    @Autowired private StaffAdministrationService administration;
    @Autowired private StaffAdminBootstrapService bootstrap;
    @Autowired private VisitOperationsAuthorizationService visitOperationsAuthorization;
    @Autowired private VisitSessionRepository visitSessions;
    @Autowired private VisitSessionItemRepository visitSessionItems;
    @Autowired private OperationalOwnershipService ownership;
    @Autowired private TrustedOperationalIntakeService operationalIntake;
    @Autowired private VisitOperationsService visitOperations;
    @Autowired private VisitRepairOperationsService visitRepairs;
    @org.springframework.boot.test.mock.mockito.MockBean
    private VisitSchedulingRecommendationService schedulingRecommendations;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        createSchema();
        registry.add("spring.datasource.url", () -> DB_URL + (DB_URL.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public");
        registry.add("spring.datasource.username", () -> DB_USER);
        registry.add("spring.datasource.password", () -> DB_PASSWORD);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void bootstrapGrantLifecycleAndGovernanceRemainDatabaseAuthoritativeUnderConcurrency() throws Exception {
        User firstConfiguredOwner = saveUser("phase1b-owner-a@example.test", Role.ROLE_TENANT);
        User alternateConfiguredOwner = saveUser("phase1b-owner-b@example.test", Role.ROLE_TENANT);

        String overlappingBootstrapEmail = "phase1b-bootstrap-overlap-" + UUID.randomUUID() + "@example.test";
        doAnswer(invocation -> {
            User persistedUser = invocation.getArgument(0);
            entityManager.persist(persistedUser);
            entityManager.flush();
            grants.saveAndFlush(new StaffAccessGrant(persistedUser, StaffCapability.STAFF_ADMIN,
                    StaffScopeType.GLOBAL, null, null, Instant.EPOCH, null, null, Instant.EPOCH,
                    "BOOTSTRAP_OVERLAP_TEST", StaffGrantProvisioningSource.INITIAL_BOOTSTRAP));
            return persistedUser;
        }).when(users).saveAndFlush(any(User.class));
        assertThrows(StaffAccessConflictException.class,
                () -> bootstrap.provisionFirstAdmin(overlappingBootstrapEmail,
                        "temporary-test-password", "DEPLOYMENT_OVERLAP_TEST"),
                "a post-guard overlapping Admin grant must reject bootstrap before a duplicate insert");
        ArgumentCaptor<Long> lockedUserId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> overlapUserId = ArgumentCaptor.forClass(Long.class);
        InOrder grantProtocol = inOrder(users, grants);
        grantProtocol.verify(users).findLockedById(lockedUserId.capture());
        grantProtocol.verify(grants).existsOverlappingUnrevoked(overlapUserId.capture(),
                eq(StaffCapability.STAFF_ADMIN.name()), eq(StaffScopeType.GLOBAL.name()),
                isNull(), isNull(), any(Instant.class), isNull());
        assertEquals(lockedUserId.getValue(), overlapUserId.getValue(),
                "the persisted bootstrap User is locked before its exact grant scope is checked");
        assertFalse(users.findByEmail(overlappingBootstrapEmail).isPresent(),
                "overlap rejection rolls back a newly-created bootstrap User");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM staff_access_grants "
                + "WHERE provisioning_source='INITIAL_BOOTSTRAP'", Integer.class));
        reset(users, grants);

        CountDownLatch bootstrapReady = new CountDownLatch(2);
        CountDownLatch bootstrapStart = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstBootstrap = pool.submit(() -> {
                bootstrapReady.countDown();
                bootstrapStart.await();
                return bootstrap.provisionFirstAdmin(firstConfiguredOwner.getEmail(), null, "DEPLOYMENT_CHANGE_1");
            });
            Future<Boolean> secondBootstrap = pool.submit(() -> {
                bootstrapReady.countDown();
                bootstrapStart.await();
                return bootstrap.provisionFirstAdmin(alternateConfiguredOwner.getEmail(), null, "DEPLOYMENT_CHANGE_2");
            });
            assertTrue(bootstrapReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            bootstrapStart.countDown();
            boolean firstCreated = firstBootstrap.get(20, java.util.concurrent.TimeUnit.SECONDS);
            boolean secondCreated = secondBootstrap.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(firstCreated, secondCreated, "one first-provisioning request wins the shared governance lock");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM staff_access_grants "
                + "WHERE capability='STAFF_ADMIN' AND scope_type='GLOBAL'", Integer.class));
        Long ownerGrantId = jdbc.queryForObject("SELECT id FROM staff_access_grants "
                + "WHERE capability='STAFF_ADMIN' AND scope_type='GLOBAL'", Long.class);
        Long ownerId = jdbc.queryForObject("SELECT user_id FROM staff_access_grants WHERE id=?", Long.class, ownerGrantId);
        Long unusedConfiguredUserId = ownerId.equals(firstConfiguredOwner.getId())
                ? alternateConfiguredOwner.getId() : firstConfiguredOwner.getId();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM staff_access_grants WHERE id=? "
                + "AND granted_by_user_id IS NULL AND expires_at IS NULL "
                + "AND provisioning_source='INITIAL_BOOTSTRAP'", Integer.class, ownerGrantId),
                "deployment bootstrap has no fictitious User grantor and no expiry");
        assertEquals(StaffGrantProvisioningSource.INITIAL_BOOTSTRAP,
                jdbc.queryForObject("SELECT provisioning_source FROM staff_access_grants WHERE id=?",
                        (rows, row) -> StaffGrantProvisioningSource.valueOf(rows.getString(1)), ownerGrantId));
        assertTrue(employeeProfiles.findByUserId(ownerId).orElseThrow().isStaffActive());
        assertFalse(employeeProfiles.findByUserId(unusedConfiguredUserId).isPresent(),
                "changing configured email after bootstrap cannot create or take over another account");
        assertTrue(users.findById(ownerId).orElseThrow().getRole() == Role.ROLE_TENANT,
                "Phase 1 provisioning does not promote a legacy JWT role");
        assertFalse(bootstrap.provisionFirstAdmin(users.findById(unusedConfiguredUserId).orElseThrow().getEmail(),
                null, "DEPLOYMENT_CHANGE_3"));
        assertFalse(employeeProfiles.findByUserId(unusedConfiguredUserId).isPresent());
        assertEquals(2, countAuditAction("INITIAL_ADMIN_BOOTSTRAPPED" ) + countAuditAction("STAFF_ACTIVATED"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE actor_kind='DEPLOYMENT_OPERATOR' AND actor_user_id IS NULL "
                + "AND operator_reference IN ('DEPLOYMENT_CHANGE_1','DEPLOYMENT_CHANGE_2') "
                + "AND action_code='INITIAL_ADMIN_BOOTSTRAPPED'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM staff_access_grants "
                + "WHERE capability='STAFF_ADMIN' AND scope_type='GLOBAL' "
                + "AND provisioning_source='INITIAL_BOOTSTRAP' AND granted_by_user_id IS NULL AND expires_at IS NULL",
                Integer.class));

        Long otherAdminId = ownerId.equals(firstConfiguredOwner.getId())
                ? alternateConfiguredOwner.getId() : firstConfiguredOwner.getId();
        StaffUserAccessState newlyActivatedAdmin = administration.activateStaff(ownerId, otherAdminId,
                new StaffStateCommand(null, "ADMIN_ONBOARDING"));
        assertTrue(newlyActivatedAdmin.staffActive());
        StaffGrantView secondAdminGrant = administration.createGrant(ownerId, otherAdminId,
                grant(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null, null, null, "ADMIN_DELEGATION"));
        assertNull(secondAdminGrant.expiresAt());
        assertNull(users.findById(otherAdminId).orElseThrow().getPasswordHash(),
                "bootstrap and Admin grant paths never reset an existing account credential");

        User target = saveUser("phase1b-scope-target@example.test", Role.ROLE_TENANT);
        administration.activateStaff(ownerId, target.getId(), new StaffStateCommand(null, "STAFF_ONBOARDING"));
        SupportedCity indore = cities.findByCode("indore").orElseThrow();
        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(indore, "phase1b-team", "Phase 1B Team", true));

        CountDownLatch grantReady = new CountDownLatch(2);
        CountDownLatch grantStart = new CountDownLatch(1);
        long auditCountBeforeGrantRace = audits.count();
        ExecutorService grantPool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = grantPool.submit(() -> attemptConcurrentGrant(
                    grantReady, grantStart, ownerId, target.getId(), indore.getId()));
            Future<Boolean> two = grantPool.submit(() -> attemptConcurrentGrant(
                    grantReady, grantStart, ownerId, target.getId(), indore.getId()));
            assertTrue(grantReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            grantStart.countDown();
            assertNotEquals(one.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    two.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "overlapping grants serialize by subject User and only one commits");
        } finally {
            grantPool.shutdownNow();
        }
        assertEquals(auditCountBeforeGrantRace + 1, audits.count(),
                "failed overlap must not leave a grant or successful audit fact");

        Instant now = Instant.now();
        StaffGrantView expired = administration.createGrant(ownerId, target.getId(), grant(
                StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, indore.getId(), null,
                now.minusSeconds(7200), now.minusSeconds(3600), "EXPIRED_ACCESS"));
        StaffGrantView future = administration.createGrant(ownerId, target.getId(), grant(
                StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, team.getId(),
                now.plusSeconds(3600), null, "FUTURE_SUPERVISION"));
        StaffGrantView revoked = administration.createGrant(ownerId, target.getId(), grant(
                StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, indore.getId(), null,
                null, null, "TEMPORARY_ADMIN"));
        administration.revokeGrant(ownerId, revoked.id(), "ACCESS_REMOVED");
        StaffGrantView renewed = administration.createGrant(ownerId, target.getId(), grant(
                StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, indore.getId(), null,
                null, null, "RENEWED_ACCESS"));
        assertNotEquals(revoked.id(), renewed.id(), "renewal creates a new immutable grant fact");
        assertNotNull(grants.findById(revoked.id()).orElseThrow().getRevokedAt());
        administration.revokeGrant(ownerId, renewed.id(), "RENEWAL_TEST_CLEANUP");
        assertNotNull(expired.id());
        assertNotNull(future.id());

        assertThrows(IllegalArgumentException.class, () -> administration.createGrant(ownerId, target.getId(),
                grant(StaffCapability.OPS_COORDINATE, StaffScopeType.CITY, indore.getId(), null,
                        null, null, "INVALID_CAPABILITY_SCOPE")));
        assertThrows(IllegalArgumentException.class, () -> administration.createGrant(ownerId, target.getId(),
                grant(StaffCapability.OPS_INTAKE, StaffScopeType.CITY, Long.MAX_VALUE, null,
                        null, null, "UNKNOWN_CITY")));
        SupportedCity inactiveCity = cities.saveAndFlush(new SupportedCity("phase1b-inactive", "Inactive Test City", false));
        assertThrows(IllegalArgumentException.class, () -> administration.createGrant(ownerId, target.getId(),
                grant(StaffCapability.OPS_INTAKE, StaffScopeType.CITY, inactiveCity.getId(), null,
                        null, null, "INACTIVE_CITY")));
        assertThrows(IllegalArgumentException.class, () -> administration.createGrant(ownerId, target.getId(),
                grant(StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null, null,
                        Instant.now().plusSeconds(3600), null, "FUTURE_ADMIN")));
        assertThrows(IllegalArgumentException.class, () -> administration.createGrant(ownerId, target.getId(),
                grant(StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, team.getId(),
                        Instant.now(), Instant.now().minusSeconds(3600), "INVALID_INTERVAL")));

        User notActivated = saveUser("phase1b-not-activated@example.test", Role.ROLE_TENANT);
        assertThrows(IllegalArgumentException.class, () -> administration.createGrant(ownerId, notActivated.getId(),
                grant(StaffCapability.OPS_INTAKE, StaffScopeType.CITY, indore.getId(), null,
                        null, null, "NO_IMPLICIT_ACTIVATION")));
        assertFalse(employeeProfiles.findByUserId(notActivated.getId()).isPresent(),
                "grant creation does not create or activate a target staff profile");

        Long cityGrantId = jdbc.queryForObject("SELECT id FROM staff_access_grants WHERE user_id=? "
                + "AND capability='OPS_INTAKE' AND revoked_at IS NULL", Long.class, target.getId());
        administration.createGrant(ownerId, target.getId(), grant(
                StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team.getId(), null, null,
                "TEAM_COORDINATION"));

        User secondCoordinator = saveUser("phase1c-second-coordinator@example.test", Role.ROLE_TENANT);
        administration.activateStaff(ownerId, secondCoordinator.getId(), new StaffStateCommand(null, "STAFF_ONBOARDING"));
        administration.createGrant(ownerId, secondCoordinator.getId(), grant(
                StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team.getId(), null, null,
                "TEAM_COORDINATION"));
        VisitSession claimRace = new VisitSession();
        claimRace.setTenant(target);
        claimRace.setCity(indore.getDisplayName());
        claimRace.setSupportedCity(indore);
        claimRace.setOperatingTeam(team);
        claimRace.setOperationalScopeReady(true);
        claimRace = visitSessions.saveAndFlush(claimRace);
        Long claimRaceSessionId = claimRace.getId();
        CountDownLatch claimReady = new CountDownLatch(2);
        CountDownLatch claimStart = new CountDownLatch(1);
        ExecutorService claimPool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> claimOne = claimPool.submit(() -> attemptClaim(
                    claimReady, claimStart, target.getId(), claimRaceSessionId));
            Future<Boolean> claimTwo = claimPool.submit(() -> attemptClaim(
                    claimReady, claimStart, secondCoordinator.getId(), claimRaceSessionId));
            assertTrue(claimReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            claimStart.countDown();
            assertNotEquals(claimOne.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    claimTwo.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "the Session root lock permits exactly one concurrent Team claim");
        } finally {
            claimPool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE action_code='OWNERSHIP_CLAIMED' AND target_type='VISIT_SESSION' AND target_id=?",
                Integer.class, claimRaceSessionId));
        Long claimWinnerId = visitSessions.findById(claimRaceSessionId).orElseThrow().getCoordinator().getId();
        Long claimLoserId = claimWinnerId.equals(target.getId()) ? secondCoordinator.getId() : target.getId();
        assertThrows(VisitOperationsConflictException.class, () -> ownership.claimSession(claimLoserId,
                claimRaceSessionId, 0L, Map.of(), "VISIBLE_LOSER_RETRY"),
                "a loser with valid visibility in the current Team receives a conflict");
        assertEquals(claimWinnerId, visitSessions.findById(claimRaceSessionId).orElseThrow().getCoordinator().getId());

        StaffCapabilitiesResponse effective = access.currentCapabilities(target.getId());
        assertTrue(effective.staffActive());
        assertNotNull(effective.serverTime());
        assertEquals(2, effective.capabilities().size(), effective.capabilities().toString());
        assertTrue(effective.capabilities().stream().anyMatch(value -> value.capability() == StaffCapability.OPS_INTAKE
                && value.cityId().equals(indore.getId()) && value.cityDisplayName().equals(indore.getDisplayName())));
        assertTrue(effective.capabilities().stream().anyMatch(value -> value.capability() == StaffCapability.OPS_COORDINATE
                && value.teamId().equals(team.getId()) && value.cityId().equals(indore.getId())));
        assertFalse(effective.capabilities().stream().anyMatch(value -> value.capability() == StaffCapability.CITY_TEAM_ADMIN
                || value.capability() == StaffCapability.OPS_SUPERVISE));
        assertFalse(effective.toString().contains("EXPIRED_ACCESS"));
        assertFalse(effective.toString().contains("TEMPORARY_ADMIN"));

        StaffCapabilitiesController currentController = new StaffCapabilitiesController(access);
        HttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        var authenticatedTarget = new UsernamePasswordAuthenticationToken("jwt-principal", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        authenticatedTarget.setDetails(new PathomeAuthenticationDetails(request, target.getId()));
        StaffCapabilitiesResponse endpointResponse = currentController.currentCapabilities(authenticatedTarget);
        assertTrue(endpointResponse.staffActive());
        assertEquals(effective.capabilities(), endpointResponse.capabilities(),
                "the current-user endpoint obtains identity from verified JWT details, never a target ID parameter");
        assertFalse(java.util.Arrays.stream(StaffCapabilitiesResponse.class.getRecordComponents())
                .anyMatch(component -> component.getName().toLowerCase(java.util.Locale.ROOT).contains("reason")));

        User revocationRaceCoordinator = saveUser("phase1c-revocation-race@example.test", Role.ROLE_TENANT);
        administration.activateStaff(ownerId, revocationRaceCoordinator.getId(),
                new StaffStateCommand(null, "STAFF_ONBOARDING"));
        StaffGrantView revocationRaceGrant = administration.createGrant(ownerId, revocationRaceCoordinator.getId(),
                grant(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team.getId(), null, null,
                        "TEAM_COORDINATION"));
        VisitSession revocationRaceSession = new VisitSession();
        revocationRaceSession.setTenant(target);
        revocationRaceSession.setCity(indore.getDisplayName());
        revocationRaceSession.setSupportedCity(indore);
        revocationRaceSession.setOperatingTeam(team);
        revocationRaceSession.setOperationalScopeReady(true);
        revocationRaceSession = visitSessions.saveAndFlush(revocationRaceSession);
        Long revocationRaceSessionId = revocationRaceSession.getId();
        CountDownLatch revocationReady = new CountDownLatch(2);
        CountDownLatch revocationStart = new CountDownLatch(1);
        ExecutorService revocationPool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> mutation = revocationPool.submit(() -> attemptClaim(
                    revocationReady, revocationStart, revocationRaceCoordinator.getId(), revocationRaceSessionId));
            Future<Boolean> revoke = revocationPool.submit(() -> attemptRevokeGrant(
                    revocationReady, revocationStart, ownerId, revocationRaceGrant.id()));
            assertTrue(revocationReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            revocationStart.countDown();
            boolean mutationAdmittedBeforeRevocation = mutation.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(revoke.get(20, java.util.concurrent.TimeUnit.SECONDS), "the grant revocation must commit");
            assertEquals(mutationAdmittedBeforeRevocation,
                    visitSessions.findById(revocationRaceSessionId).orElseThrow().getCoordinator() != null,
                    "a mutation admitted before revocation may finish; one admitted after revocation fails");
        } finally {
            revocationPool.shutdownNow();
        }

        User expiringCoordinator = saveUser("phase1c-expiring-coordinator@example.test", Role.ROLE_TENANT);
        administration.activateStaff(ownerId, expiringCoordinator.getId(), new StaffStateCommand(null, "STAFF_ONBOARDING"));
        Instant expiresAt = Instant.now().plusSeconds(6);
        administration.createGrant(ownerId, expiringCoordinator.getId(), grant(StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, team.getId(), null, expiresAt, "EXPIRY_WHILE_WAITING"));
        VisitSession expiryWaitSession = new VisitSession();
        expiryWaitSession.setTenant(target);
        expiryWaitSession.setCity(indore.getDisplayName());
        expiryWaitSession.setSupportedCity(indore);
        expiryWaitSession.setOperatingTeam(team);
        expiryWaitSession.setOperationalScopeReady(true);
        expiryWaitSession = visitSessions.saveAndFlush(expiryWaitSession);
        Long expiryWaitSessionId = expiryWaitSession.getId();
        Long expiringCoordinatorId = expiringCoordinator.getId();
        assertTrue(access.hasCapabilityAt(expiringCoordinatorId, StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, team.getId()), "the short-lived grant is effective before the race starts");
        String testSchemaUrl = DB_URL + (DB_URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
        try (Connection guardBlocker = DriverManager.getConnection(testSchemaUrl, DB_USER, DB_PASSWORD)) {
            guardBlocker.setAutoCommit(false);
            boolean blockerCommitted = false;
            try {
                try (Statement statement = guardBlocker.createStatement()) {
                    statement.execute("SELECT id FROM operating_teams WHERE id=" + team.getId() + " FOR UPDATE");
                }
                CountDownLatch expiryClaimReady = new CountDownLatch(1);
                ExecutorService expiryClaimPool = Executors.newSingleThreadExecutor();
                try {
                    Future<Boolean> claim = expiryClaimPool.submit(() -> {
                        expiryClaimReady.countDown();
                        try {
                            ownership.claimSession(expiringCoordinatorId, expiryWaitSessionId, 0L, Map.of(),
                                    "GRANT_EXPIRES_WHILE_WAITING");
                            return true;
                        } catch (AccessDeniedException | VisitOperationsConflictException
                                 | EntityNotFoundException expected) {
                            return false;
                        }
                    });
                    assertTrue(expiryClaimReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    boolean waitingAtTeamGuard = false;
                    Instant lockWaitDeadline = Instant.now().plusSeconds(4);
                    while (Instant.now().isBefore(lockWaitDeadline) && Instant.now().isBefore(expiresAt)) {
                        waitingAtTeamGuard = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS ("
                                + "SELECT 1 FROM pg_stat_activity WHERE pid <> pg_backend_pid() "
                                + "AND wait_event_type='Lock' AND query ILIKE '%operating_teams%')", Boolean.class));
                        if (waitingAtTeamGuard) break;
                        Thread.sleep(50);
                    }
                    assertTrue(waitingAtTeamGuard,
                            "the mutation must pass initial authority and reach the blocked Team row guard before expiry");
                    while (Instant.now().isBefore(expiresAt.plusMillis(100))) Thread.sleep(50);
                    guardBlocker.commit();
                    blockerCommitted = true;
                    assertFalse(claim.get(20, java.util.concurrent.TimeUnit.SECONDS),
                            "fresh post-guard database time must reject a grant that expired while waiting");
                } finally {
                    expiryClaimPool.shutdownNow();
                }
            } finally {
                if (!blockerCommitted) guardBlocker.rollback();
            }
        }
        assertNull(visitSessions.findById(expiryWaitSessionId).orElseThrow().getCoordinator());

        User deactivationRaceCoordinator = saveUser("phase1c-deactivation-race@example.test", Role.ROLE_TENANT);
        StaffUserAccessState activeDeactivationProfile = administration.activateStaff(ownerId,
                deactivationRaceCoordinator.getId(), new StaffStateCommand(null, "STAFF_ONBOARDING"));
        StaffGrantView deactivationRaceGrant = administration.createGrant(ownerId, deactivationRaceCoordinator.getId(),
                grant(StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team.getId(), null, null,
                        "DEACTIVATION_RACE_COORDINATION"));
        VisitSession deactivationRaceSession = new VisitSession();
        deactivationRaceSession.setTenant(target);
        deactivationRaceSession.setCity(indore.getDisplayName());
        deactivationRaceSession.setSupportedCity(indore);
        deactivationRaceSession.setOperatingTeam(team);
        deactivationRaceSession.setOperationalScopeReady(true);
        deactivationRaceSession = visitSessions.saveAndFlush(deactivationRaceSession);
        Long deactivationRaceSessionId = deactivationRaceSession.getId();
        Long deactivationRaceSessionVersion = deactivationRaceSession.getVersion();
        CountDownLatch ownershipGuardHeld = new CountDownLatch(1);
        CountDownLatch releaseOwnershipGuard = new CountDownLatch(1);
        CountDownLatch staffReachedGuard = new CountDownLatch(1);
        String ownershipThreadName = "phase1c-owner-lock-order";
        String staffThreadName = "phase1c-staff-lock-order";
        doAnswer(invocation -> {
            String threadName = Thread.currentThread().getName();
            if (ownershipThreadName.equals(threadName)) {
                Object result = invocation.callRealMethod();
                ownershipGuardHeld.countDown();
                if (!releaseOwnershipGuard.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Timed out waiting to release the ownership guard");
                return result;
            }
            if (staffThreadName.equals(threadName)) staffReachedGuard.countDown();
            return invocation.callRealMethod();
        }).when(securityGuards).acquire(anyCollection(), anyCollection(), anyCollection());
        ExecutorService deactivationRacePool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> claim = deactivationRacePool.submit(() -> {
                Thread.currentThread().setName(ownershipThreadName);
                ownership.claimSession(deactivationRaceCoordinator.getId(), deactivationRaceSessionId,
                        deactivationRaceSessionVersion, Map.of(), "PROFILE_DEACTIVATION_RACE");
                return true;
            });
            assertTrue(ownershipGuardHeld.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "ownership must hold the Team and profile guards before writing the coordinator FK");
            Future<Boolean> deactivate = deactivationRacePool.submit(() -> {
                Thread.currentThread().setName(staffThreadName);
                administration.deactivateStaff(ownerId, deactivationRaceCoordinator.getId(),
                        new StaffStateCommand(activeDeactivationProfile.version(), "PROFILE_DEACTIVATION_RACE"));
                return true;
            });
            assertTrue(staffReachedGuard.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "staff deactivation must wait at the same scope guard before taking the target User row lock");
            boolean targetUserRowWasAvailable;
            try (var connection = java.sql.DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 var statement = connection.createStatement()) {
                connection.setSchema(SCHEMA);
                connection.setAutoCommit(false);
                try (var row = statement.executeQuery("SELECT id FROM users WHERE id="
                        + deactivationRaceCoordinator.getId() + " FOR UPDATE NOWAIT")) {
                    targetUserRowWasAvailable = row.next();
                }
                connection.commit();
            } catch (java.sql.SQLException lockUnavailable) {
                targetUserRowWasAvailable = false;
            } finally {
                releaseOwnershipGuard.countDown();
            }
            assertTrue(claim.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(deactivate.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(targetUserRowWasAvailable,
                    "the staff writer must not hold the target User row while waiting for the ownership scope guard");
            assertEquals(deactivationRaceCoordinator.getId(), visitSessions.findById(deactivationRaceSessionId)
                    .orElseThrow().getCoordinator().getId(),
                    "ownership admitted before deactivation commits as the legal serialization");
        } finally {
            releaseOwnershipGuard.countDown();
            deactivationRacePool.shutdownNow();
            reset(securityGuards);
        }
        assertFalse(employeeProfiles.findByUserId(deactivationRaceCoordinator.getId()).orElseThrow().isStaffActive());
        assertNotNull(grants.findById(deactivationRaceGrant.id()).orElseThrow().getRevokedAt());

        User transferCoordinator = saveUser("phase1c-transfer-coordinator@example.test", Role.ROLE_TENANT);
        administration.activateStaff(ownerId, transferCoordinator.getId(),
                new StaffStateCommand(null, "STAFF_ONBOARDING"));
        administration.createGrant(ownerId, transferCoordinator.getId(), grant(
                StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, team.getId(), null, null,
                "SOURCE_COORDINATION"));
        OperatingTeam destinationTeam = teams.saveAndFlush(new OperatingTeam(indore,
                "phase1c-destination-" + UUID.randomUUID().toString().substring(0, 8), "Phase 1C Destination", true));
        administration.createGrant(ownerId, transferCoordinator.getId(), grant(
                StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, null, destinationTeam.getId(), null, null,
                "DESTINATION_COORDINATION"));
        administration.createGrant(ownerId, ownerId, grant(
                StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, team.getId(), null, null,
                "SOURCE_SUPERVISION"));
        administration.createGrant(ownerId, ownerId, grant(
                StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, destinationTeam.getId(), null, null,
                "DESTINATION_SUPERVISION"));

        VisitSession assignmentRace = new VisitSession();
        assignmentRace.setTenant(target);
        assignmentRace.setCity(indore.getDisplayName());
        assignmentRace.setSupportedCity(indore);
        assignmentRace.setOperatingTeam(team);
        assignmentRace.setOperationalScopeReady(true);
        assignmentRace = visitSessions.saveAndFlush(assignmentRace);
        Long assignmentRaceId = assignmentRace.getId();
        CountDownLatch assignmentReady = new CountDownLatch(3);
        CountDownLatch assignmentStart = new CountDownLatch(1);
        ExecutorService assignmentPool = Executors.newFixedThreadPool(3);
        try {
            Future<Boolean> claimant = assignmentPool.submit(() -> attemptClaim(
                    assignmentReady, assignmentStart, target.getId(), assignmentRaceId));
            Future<Boolean> supervisorAssignment = assignmentPool.submit(() -> attemptAssignCoordinator(
                    assignmentReady, assignmentStart, ownerId, assignmentRaceId, secondCoordinator.getId()));
            Future<Boolean> scopeDeactivation = assignmentPool.submit(() -> attemptTeamDeactivation(assignmentReady,
                    assignmentStart, ownerId, team.getId(), cities.findById(indore.getId()).orElseThrow().getVersion(),
                    teams.findById(team.getId()).orElseThrow().getVersion()));
            assertTrue(assignmentReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assignmentStart.countDown();
            assertNotEquals(claimant.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    supervisorAssignment.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "claim and supervisor assignment serialize to one committed coordinator");
            assertFalse(scopeDeactivation.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "Team deactivation cannot pass its drain while claim or assignment work remains");
        } finally {
            assignmentPool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE target_type='VISIT_SESSION' AND target_id=? "
                + "AND action_code IN ('OWNERSHIP_CLAIMED','OWNERSHIP_ASSIGNED')",
                Integer.class, assignmentRaceId));

        User repairOperationsAdmin = saveUser("phase1c-repair-operations-admin@example.test", Role.ROLE_ADMIN);
        VisitSession reopeningRace = new VisitSession();
        reopeningRace.setTenant(target);
        reopeningRace.setCity(indore.getDisplayName());
        reopeningRace.setSupportedCity(indore);
        reopeningRace.setOperatingTeam(team);
        reopeningRace.setOperationalScopeReady(true);
        reopeningRace.setStatus(com.indore.pathome.spaces.entity.VisitSessionStatus.REPAIR_REQUIRED);
        reopeningRace = visitSessions.saveAndFlush(reopeningRace);
        Long reopeningRaceId = reopeningRace.getId();
        Long reopeningRaceVersion = reopeningRace.getVersion();
        CountDownLatch reopenDrainReady = new CountDownLatch(2);
        CountDownLatch reopenDrainStart = new CountDownLatch(1);
        ExecutorService reopenDrainPool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> reopen = reopenDrainPool.submit(() -> attemptReopen(reopenDrainReady,
                    reopenDrainStart, repairOperationsAdmin.getId(), reopeningRaceId, reopeningRaceVersion));
            Future<Boolean> deactivate = reopenDrainPool.submit(() -> attemptTeamDeactivation(reopenDrainReady,
                    reopenDrainStart, ownerId, team.getId(), cities.findById(indore.getId()).orElseThrow().getVersion(),
                    teams.findById(team.getId()).orElseThrow().getVersion()));
            assertTrue(reopenDrainReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            reopenDrainStart.countDown();
            assertTrue(reopen.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(deactivate.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "repair work stays drain-blocking before and after reopening");
        } finally {
            reopenDrainPool.shutdownNow();
        }
        assertEquals(com.indore.pathome.spaces.entity.VisitSessionStatus.DRAFT,
                visitSessions.findById(reopeningRaceId).orElseThrow().getStatus());
        assertTrue(teams.findById(team.getId()).orElseThrow().isActive());

        Locality trustedLocality = new Locality(indore.getDisplayName(), "intake-" + UUID.randomUUID(), null, null, null);
        trustedLocality.setSupportedCity(indore);
        trustedLocality = localities.saveAndFlush(trustedLocality);
        User intakeTenant = saveUser("phase1c-intake-tenant@example.test", Role.ROLE_TENANT);
        PropertyVisitRequest trustedIntake = operationalIntake.admitAndSave(
                request(intakeTenant, listing("trusted-intake", trustedLocality)));
        assertEquals(indore.getId(), trustedIntake.getSupportedCity().getId());
        assertTrue(trustedIntake.isOperationalScopeReady());
        assertNull(trustedIntake.getOperatingTeam());
        assertNull(trustedIntake.getCoordinator());

        PropertyVisitRequest firstLinkedByOperations = operationalIntake.admitAndSave(
                request(intakeTenant, listing("link-first", trustedLocality)));
        User legacyRouteAdmin = saveUser("phase1c-legacy-route-admin@example.test", Role.ROLE_ADMIN);
        var createdByLink = visitOperations.coordinateRequest(legacyRouteAdmin.getId(), firstLinkedByOperations.getId(),
                new com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand(
                        firstLinkedByOperations.getVersion(), null, null));
        VisitSession linkedByOperations = visitSessions.findById(createdByLink.sessionId()).orElseThrow();
        assertEquals(indore.getId(), linkedByOperations.getSupportedCity().getId());
        assertTrue(linkedByOperations.isOperationalScopeReady());
        assertEquals(indore.getId(), requests.findById(firstLinkedByOperations.getId()).orElseThrow()
                .getSupportedCity().getId());

        PropertyVisitRequest secondLinkedByOperations = operationalIntake.admitAndSave(
                request(intakeTenant, listing("link-second", trustedLocality)));
        var attachedToExisting = visitOperations.coordinateRequest(legacyRouteAdmin.getId(), secondLinkedByOperations.getId(),
                new com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand(
                        secondLinkedByOperations.getVersion(), linkedByOperations.getId(), linkedByOperations.getVersion()));
        assertEquals(linkedByOperations.getId(), attachedToExisting.sessionId());
        assertEquals(linkedByOperations.getId(), requests.findById(secondLinkedByOperations.getId()).orElseThrow()
                .getSession().getId());

        User differentTenant = saveUser("phase1c-different-tenant@example.test", Role.ROLE_TENANT);
        PropertyVisitRequest tenantMismatchLink = operationalIntake.admitAndSave(
                request(differentTenant, listing("tenant-mismatch-link", trustedLocality)));
        Long tenantMismatchRequestId = tenantMismatchLink.getId();
        assertThrows(AccessDeniedException.class, () -> visitOperations.coordinateRequest(
                legacyRouteAdmin.getId(), tenantMismatchRequestId,
                new com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand(tenantMismatchLink.getVersion(),
                        linkedByOperations.getId(), visitSessions.findById(linkedByOperations.getId()).orElseThrow().getVersion())));
        assertNull(requests.findById(tenantMismatchRequestId).orElseThrow().getSession());

        SupportedCity bhopal = cities.findByCode("bhopal").orElseThrow();
        // Preserve the legacy display text while proving canonical City IDs govern the link contract.
        Locality bhopalLocality = new Locality(indore.getDisplayName(), "link-city-" + UUID.randomUUID(), null, null, null);
        bhopalLocality.setSupportedCity(bhopal);
        bhopalLocality = localities.saveAndFlush(bhopalLocality);
        PropertyVisitRequest cityMismatchLink = operationalIntake.admitAndSave(
                request(intakeTenant, listing("city-mismatch-link", bhopalLocality)));
        Long cityMismatchRequestId = cityMismatchLink.getId();
        Long cityMismatchRequestVersion = cityMismatchLink.getVersion();
        assertThrows(VisitOperationsConflictException.class, () -> visitOperations.coordinateRequest(
                legacyRouteAdmin.getId(), cityMismatchRequestId,
                new com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand(cityMismatchRequestVersion,
                        linkedByOperations.getId(), visitSessions.findById(linkedByOperations.getId()).orElseThrow().getVersion())));
        assertNull(requests.findById(cityMismatchRequestId).orElseThrow().getSession());

        PropertyVisitRequest mismatchedOwnership = operationalIntake.admitAndSave(
                request(intakeTenant, listing("link-mismatch", trustedLocality)));
        mismatchedOwnership.setOperatingTeam(team);
        mismatchedOwnership = requests.saveAndFlush(mismatchedOwnership);
        Long mismatchedRequestId = mismatchedOwnership.getId();
        Long mismatchedRequestVersion = mismatchedOwnership.getVersion();
        Long existingLinkSessionId = linkedByOperations.getId();
        Long existingLinkSessionVersion = visitSessions.findById(existingLinkSessionId).orElseThrow().getVersion();
        assertThrows(VisitOperationsConflictException.class, () -> visitOperations.coordinateRequest(legacyRouteAdmin.getId(),
                mismatchedRequestId, new com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand(
                        mismatchedRequestVersion, existingLinkSessionId, existingLinkSessionVersion)));
        assertNull(requests.findById(mismatchedRequestId).orElseThrow().getSession());

        PropertyVisitRequest rootDiscoveryRace = operationalIntake.admitAndSave(
                request(intakeTenant, listing("root-discovery-race", trustedLocality)));
        rootDiscoveryRace.setOperatingTeam(team);
        rootDiscoveryRace = requests.saveAndFlush(rootDiscoveryRace);
        Long rootDiscoveryRequestId = rootDiscoveryRace.getId();
        Long rootDiscoveryRequestVersion = rootDiscoveryRace.getVersion();
        CountDownLatch rootDiscoveryPaused = new CountDownLatch(1);
        CountDownLatch resumeRootLock = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger rootDiscoveries = new java.util.concurrent.atomic.AtomicInteger();
        CopyOnWriteArrayList<Long> rootAttemptTransactionIds = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Long> ambientTransactionId = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            Long discoveredSessionId = jdbc.query("SELECT session_id FROM property_visit_requests WHERE id=?",
                    rows -> rows.next() ? rows.getObject(1, Long.class) : null, rootDiscoveryRequestId);
            int discovery = rootDiscoveries.incrementAndGet();
            if (discovery >= 2) {
                rootAttemptTransactionIds.add(jdbc.queryForObject("SELECT txid_current()", Long.class));
            }
            if (discovery == 2) {
                rootDiscoveryPaused.countDown();
                if (!resumeRootLock.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Timed out waiting for concurrent link test to complete");
            }
            return java.util.Optional.ofNullable(discoveredSessionId);
        }).when(requests).findSessionIdByRequestId(rootDiscoveryRequestId);
        CountDownLatch rootClaimReady = new CountDownLatch(1);
        ExecutorService rootClaimPool = Executors.newSingleThreadExecutor();
        Future<Boolean> rootClaim;
        try {
            rootClaim = rootClaimPool.submit(() -> {
                rootClaimReady.countDown();
                try {
                    return new TransactionTemplate(transactionManager).execute(status -> {
                        ambientTransactionId.set(jdbc.queryForObject("SELECT txid_current()", Long.class));
                        try {
                            ownership.claimRequest(transferCoordinator.getId(), rootDiscoveryRequestId,
                                    rootDiscoveryRequestVersion, null, null, "ROOT_DISCOVERY_RACE");
                            return true;
                        } catch (VisitOperationsConflictException expected) {
                            assertFalse(status.isRollbackOnly(),
                                    "a failed independent root attempt must not mark its ambient caller rollback-only");
                            return false;
                        }
                    });
                } catch (RuntimeException failure) {
                    throw failure;
                }
            });
            assertTrue(rootClaimReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(rootDiscoveryPaused.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "claim must pause after nonlocking root discovery and before taking the Request lock");
            var linkedDuringDiscovery = visitOperations.coordinateRequest(legacyRouteAdmin.getId(), rootDiscoveryRequestId,
                    new com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand(rootDiscoveryRequestVersion, null, null));
            resumeRootLock.countDown();
            assertFalse(rootClaim.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "a Request linked after discovery must restart under its Session root and reject stale Request-only versions");
            org.mockito.Mockito.verify(requests, org.mockito.Mockito.times(3))
                    .findSessionIdByRequestId(rootDiscoveryRequestId);
            assertEquals(2, rootAttemptTransactionIds.size(),
                    "the apparent-unlinked attempt and linked-root retry each execute root discovery");
            assertNotEquals(rootAttemptTransactionIds.get(0), rootAttemptTransactionIds.get(1),
                    "the retry must use a fresh PostgreSQL transaction");
            assertNotEquals(ambientTransactionId.get(), rootAttemptTransactionIds.get(0),
                    "the first root attempt must be independent of the caller transaction");
            assertNotEquals(ambientTransactionId.get(), rootAttemptTransactionIds.get(1),
                    "the second root attempt must be independent of the caller transaction");
            assertNull(visitSessions.findById(linkedDuringDiscovery.sessionId()).orElseThrow().getCoordinator());
        } finally {
            resumeRootLock.countDown();
            rootClaimPool.shutdownNow();
        }

        PropertyVisitRequest unresolvedIntake = operationalIntake.admitAndSave(
                request(intakeTenant, listing("unresolved-intake", null)));
        assertNull(unresolvedIntake.getSupportedCity());
        assertFalse(unresolvedIntake.isOperationalScopeReady());

        SupportedCity inactiveIntakeCity = cities.saveAndFlush(new SupportedCity(
                "inactive-intake-" + UUID.randomUUID().toString().substring(0, 8), "Inactive Intake City", false));
        Locality inactiveLocality = new Locality(inactiveIntakeCity.getDisplayName(),
                "intake-" + UUID.randomUUID(), null, null, null);
        inactiveLocality.setSupportedCity(inactiveIntakeCity);
        inactiveLocality = localities.saveAndFlush(inactiveLocality);
        PropertyVisitRequest inactiveIntake = operationalIntake.admitAndSave(
                request(intakeTenant, listing("inactive-intake", inactiveLocality)));
        assertEquals(inactiveIntakeCity.getId(), inactiveIntake.getSupportedCity().getId());
        assertFalse(inactiveIntake.isOperationalScopeReady());
        assertNull(inactiveIntake.getOperatingTeam());
        assertNull(inactiveIntake.getCoordinator());

        User groundExecutive = saveUser("phase1c-separate-ge@example.test", Role.ROLE_GROUND_BOY);
        employeeProfiles.saveAndFlush(new EmployeeProfile(groundExecutive, "GROUND_BOY", null, null));
        User reassignedGroundExecutive = saveUser("phase1c-reassigned-ge@example.test", Role.ROLE_GROUND_BOY);
        employeeProfiles.saveAndFlush(new EmployeeProfile(reassignedGroundExecutive, "GROUND_BOY", null, null));
        User visitOperationsAdmin = saveUser("phase1c-visit-operations-admin@example.test", Role.ROLE_ADMIN);

        OwnershipFixture transferScheduleFixture = createSchedulableOwnershipFixture(intakeTenant, trustedLocality,
                indore, team, transferCoordinator, visitOperationsAdmin);
        Long transferScheduleSessionId = transferScheduleFixture.session().getId();
        Long transferScheduleVersion = transferScheduleFixture.session().getVersion();
        Long transferScheduleRequestVersion = transferScheduleFixture.request().getVersion();
        CountDownLatch transferScheduleReady = new CountDownLatch(2);
        CountDownLatch transferScheduleStart = new CountDownLatch(1);
        ExecutorService transferSchedulePool = Executors.newFixedThreadPool(2);
        boolean transferWonScheduleRace;
        try {
            Future<Boolean> transfer = transferSchedulePool.submit(() -> attemptTransfer(transferScheduleReady,
                    transferScheduleStart, ownerId, transferScheduleSessionId, transferScheduleVersion,
                    transferScheduleFixture.request().getId(), transferScheduleRequestVersion,
                    destinationTeam.getId(), transferCoordinator.getId()));
            Future<Boolean> schedule = transferSchedulePool.submit(() -> attemptSchedule(transferScheduleReady,
                    transferScheduleStart, visitOperationsAdmin.getId(), transferScheduleSessionId,
                    transferScheduleVersion, transferScheduleFixture.groundExecutive().getId()));
            assertTrue(transferScheduleReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            transferScheduleStart.countDown();
            transferWonScheduleRace = transfer.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(transferWonScheduleRace, schedule.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "transfer and scheduling serialize on the Session root and only one expected-version write commits");
        } finally {
            transferSchedulePool.shutdownNow();
        }
        VisitSession transferScheduleResult = visitSessions.findById(transferScheduleSessionId).orElseThrow();
        PropertyVisitRequest transferScheduleRequest = requests.findById(transferScheduleFixture.request().getId()).orElseThrow();
        assertEquals(transferWonScheduleRace ? destinationTeam.getId() : team.getId(),
                transferScheduleResult.getOperatingTeam().getId());
        assertEquals(transferScheduleResult.getOperatingTeam().getId(), transferScheduleRequest.getOperatingTeam().getId());
        assertEquals(transferScheduleFixture.groundExecutive().getId(), transferScheduleResult.getRepresentative() == null ? null
                : transferScheduleResult.getRepresentative().getId());

        OwnershipFixture transferAssignmentFixture = createSchedulableOwnershipFixture(intakeTenant, trustedLocality,
                indore, team, transferCoordinator, visitOperationsAdmin);
        Long transferAssignmentSessionId = transferAssignmentFixture.session().getId();
        visitOperations.schedule(visitOperationsAdmin.getId(), transferAssignmentSessionId,
                scheduleCommand(transferAssignmentFixture.session().getVersion(),
                        transferAssignmentFixture.groundExecutive().getId()));
        VisitSession scheduledForAssignment = visitSessions.findById(transferAssignmentSessionId).orElseThrow();
        PropertyVisitRequest requestForAssignment = requests.findById(transferAssignmentFixture.request().getId()).orElseThrow();
        Long transferAssignmentVersion = scheduledForAssignment.getVersion();
        Long transferAssignmentRequestVersion = requestForAssignment.getVersion();
        CountDownLatch transferAssignmentReady = new CountDownLatch(2);
        CountDownLatch transferAssignmentStart = new CountDownLatch(1);
        ExecutorService transferAssignmentPool = Executors.newFixedThreadPool(2);
        boolean transferWonAssignmentRace;
        try {
            Future<Boolean> transfer = transferAssignmentPool.submit(() -> attemptTransfer(transferAssignmentReady,
                    transferAssignmentStart, ownerId, transferAssignmentSessionId, transferAssignmentVersion,
                    requestForAssignment.getId(), transferAssignmentRequestVersion,
                    destinationTeam.getId(), transferCoordinator.getId()));
            Future<Boolean> reassign = transferAssignmentPool.submit(() -> attemptGroundExecutiveAssignment(
                    transferAssignmentReady, transferAssignmentStart, visitOperationsAdmin.getId(),
                    transferAssignmentSessionId, transferAssignmentVersion, reassignedGroundExecutive.getId()));
            assertTrue(transferAssignmentReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            transferAssignmentStart.countDown();
            transferWonAssignmentRace = transfer.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(transferWonAssignmentRace, reassign.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "transfer and GE reassignment serialize on the Session root");
        } finally {
            transferAssignmentPool.shutdownNow();
        }
        VisitSession transferAssignmentResult = visitSessions.findById(transferAssignmentSessionId).orElseThrow();
        PropertyVisitRequest transferAssignmentRequest = requests.findById(transferAssignmentFixture.request().getId()).orElseThrow();
        assertEquals(transferWonAssignmentRace ? destinationTeam.getId() : team.getId(),
                transferAssignmentResult.getOperatingTeam().getId());
        assertEquals(transferAssignmentResult.getOperatingTeam().getId(), transferAssignmentRequest.getOperatingTeam().getId());
        assertEquals(transferWonAssignmentRace ? transferAssignmentFixture.groundExecutive().getId()
                        : reassignedGroundExecutive.getId(),
                transferAssignmentResult.getRepresentative().getId(), "OE transfer preserves GE execution assignment");

        OwnershipFixture releaseScheduleFixture = createSchedulableOwnershipFixture(intakeTenant, trustedLocality,
                indore, destinationTeam, transferCoordinator, visitOperationsAdmin);
        Long releaseScheduleSessionId = releaseScheduleFixture.session().getId();
        Long releaseScheduleVersion = releaseScheduleFixture.session().getVersion();
        CountDownLatch releaseScheduleReady = new CountDownLatch(2);
        CountDownLatch releaseScheduleStart = new CountDownLatch(1);
        ExecutorService releaseSchedulePool = Executors.newFixedThreadPool(2);
        boolean releaseWonScheduleRace;
        try {
            Future<Boolean> release = releaseSchedulePool.submit(() -> attemptRelease(releaseScheduleReady,
                    releaseScheduleStart, transferCoordinator.getId(), releaseScheduleSessionId,
                    releaseScheduleVersion, releaseScheduleFixture.request().getId(),
                    releaseScheduleFixture.request().getVersion()));
            Future<Boolean> schedule = releaseSchedulePool.submit(() -> attemptSchedule(releaseScheduleReady,
                    releaseScheduleStart, visitOperationsAdmin.getId(), releaseScheduleSessionId,
                    releaseScheduleVersion, releaseScheduleFixture.groundExecutive().getId()));
            assertTrue(releaseScheduleReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            releaseScheduleStart.countDown();
            releaseWonScheduleRace = release.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(releaseWonScheduleRace, schedule.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "release and scheduling serialize on the Session root");
        } finally {
            releaseSchedulePool.shutdownNow();
        }
        VisitSession releaseScheduleResult = visitSessions.findById(releaseScheduleSessionId).orElseThrow();
        PropertyVisitRequest releaseScheduleRequest = requests.findById(releaseScheduleFixture.request().getId()).orElseThrow();
        assertEquals(releaseWonScheduleRace ? null : transferCoordinator.getId(),
                releaseScheduleResult.getCoordinator() == null ? null : releaseScheduleResult.getCoordinator().getId());
        assertEquals(releaseScheduleResult.getCoordinator() == null ? null : releaseScheduleResult.getCoordinator().getId(),
                releaseScheduleRequest.getCoordinator() == null ? null : releaseScheduleRequest.getCoordinator().getId());
        assertEquals(releaseWonScheduleRace ? com.indore.pathome.spaces.entity.VisitSessionStatus.DRAFT
                : com.indore.pathome.spaces.entity.VisitSessionStatus.SCHEDULED, releaseScheduleResult.getStatus());

        OwnershipFixture releaseAssignmentFixture = createSchedulableOwnershipFixture(intakeTenant, trustedLocality,
                indore, destinationTeam, transferCoordinator, visitOperationsAdmin);
        User releaseRaceGroundExecutive = saveUser("phase1c-release-race-ge@example.test", Role.ROLE_GROUND_BOY);
        employeeProfiles.saveAndFlush(new EmployeeProfile(releaseRaceGroundExecutive, "GROUND_BOY", null, null));
        Long releaseAssignmentSessionId = releaseAssignmentFixture.session().getId();
        visitOperations.schedule(visitOperationsAdmin.getId(), releaseAssignmentSessionId,
                scheduleCommand(releaseAssignmentFixture.session().getVersion(),
                        releaseAssignmentFixture.groundExecutive().getId()));
        VisitSession scheduledForReleaseAssignment = visitSessions.findById(releaseAssignmentSessionId).orElseThrow();
        PropertyVisitRequest requestForReleaseAssignment = requests.findById(releaseAssignmentFixture.request().getId()).orElseThrow();
        CountDownLatch releaseAssignmentReady = new CountDownLatch(2);
        CountDownLatch releaseAssignmentStart = new CountDownLatch(1);
        ExecutorService releaseAssignmentPool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> release = releaseAssignmentPool.submit(() -> attemptRelease(releaseAssignmentReady,
                    releaseAssignmentStart, transferCoordinator.getId(), releaseAssignmentSessionId,
                    scheduledForReleaseAssignment.getVersion(), requestForReleaseAssignment.getId(),
                    requestForReleaseAssignment.getVersion()));
            Future<Boolean> reassign = releaseAssignmentPool.submit(() -> attemptGroundExecutiveAssignment(
                    releaseAssignmentReady, releaseAssignmentStart, visitOperationsAdmin.getId(),
                    releaseAssignmentSessionId, scheduledForReleaseAssignment.getVersion(), releaseRaceGroundExecutive.getId()));
            assertTrue(releaseAssignmentReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            releaseAssignmentStart.countDown();
            assertFalse(release.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "release cannot abandon scheduled execution whether it races before or after GE reassignment");
            assertTrue(reassign.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "the valid GE reassignment remains independently applicable");
        } finally {
            releaseAssignmentPool.shutdownNow();
        }
        VisitSession releaseAssignmentResult = visitSessions.findById(releaseAssignmentSessionId).orElseThrow();
        assertEquals(com.indore.pathome.spaces.entity.VisitSessionStatus.SCHEDULED, releaseAssignmentResult.getStatus());
        assertEquals(transferCoordinator.getId(), releaseAssignmentResult.getCoordinator().getId());
        assertEquals(releaseRaceGroundExecutive.getId(), releaseAssignmentResult.getRepresentative().getId());

        OwnershipFixture linkTransferFixture = createSchedulableOwnershipFixture(intakeTenant, trustedLocality,
                indore, team, transferCoordinator, visitOperationsAdmin);
        PropertyVisitRequest requestForConcurrentLink = operationalIntake.admitAndSave(
                request(intakeTenant, listing("link-transfer-race", trustedLocality)));
        requestForConcurrentLink.setOperatingTeam(team);
        requestForConcurrentLink.setCoordinator(transferCoordinator);
        requestForConcurrentLink = requests.saveAndFlush(requestForConcurrentLink);
        Long linkTransferSessionId = linkTransferFixture.session().getId();
        Long linkTransferSessionVersion = linkTransferFixture.session().getVersion();
        Long linkTransferRequestVersion = linkTransferFixture.request().getVersion();
        Long concurrentLinkRequestId = requestForConcurrentLink.getId();
        Long concurrentLinkExpectedVersion = requestForConcurrentLink.getVersion();
        CountDownLatch linkTransferReady = new CountDownLatch(2);
        CountDownLatch linkTransferStart = new CountDownLatch(1);
        ExecutorService linkTransferPool = Executors.newFixedThreadPool(2);
        boolean transferWonLinkRace;
        try {
            Future<Boolean> transfer = linkTransferPool.submit(() -> attemptTransfer(linkTransferReady,
                    linkTransferStart, ownerId, linkTransferSessionId, linkTransferSessionVersion,
                    linkTransferFixture.request().getId(), linkTransferRequestVersion,
                    destinationTeam.getId(), transferCoordinator.getId()));
            Future<Boolean> link = linkTransferPool.submit(() -> attemptLink(linkTransferReady, linkTransferStart,
                    visitOperationsAdmin.getId(), concurrentLinkRequestId, concurrentLinkExpectedVersion,
                    linkTransferSessionId, linkTransferSessionVersion));
            assertTrue(linkTransferReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            linkTransferStart.countDown();
            transferWonLinkRace = transfer.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(transferWonLinkRace, link.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "link and Team transfer serialize at the Session root without committing mirror drift");
        } finally {
            linkTransferPool.shutdownNow();
        }
        VisitSession linkTransferResult = visitSessions.findById(linkTransferSessionId).orElseThrow();
        assertEquals(transferWonLinkRace ? destinationTeam.getId() : team.getId(),
                linkTransferResult.getOperatingTeam().getId());
        assertEquals(transferWonLinkRace ? 1 : 2, jdbc.queryForObject(
                "SELECT count(*) FROM property_visit_requests WHERE session_id=?", Integer.class, linkTransferSessionId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM property_visit_requests r "
                + "JOIN visit_sessions s ON s.id=r.session_id WHERE r.session_id=? AND "
                + "(r.supported_city_id IS DISTINCT FROM s.supported_city_id "
                + "OR r.operating_team_id IS DISTINCT FROM s.operating_team_id "
                + "OR r.coordinator_user_id IS DISTINCT FROM s.coordinator_user_id "
                + "OR r.operational_scope_ready IS DISTINCT FROM s.operational_scope_ready)",
                Integer.class, linkTransferSessionId));

        OperatingTeam deactivationRaceTeam = teams.saveAndFlush(new OperatingTeam(indore,
                "phase1c-drain-race-" + UUID.randomUUID().toString().substring(0, 8), "Transfer Drain Race", true));
        administration.createGrant(ownerId, ownerId, grant(StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM,
                null, deactivationRaceTeam.getId(), null, null, "DRAIN_RACE_SUPERVISION"));
        administration.createGrant(ownerId, transferCoordinator.getId(), grant(StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, deactivationRaceTeam.getId(), null, null, "DRAIN_RACE_COORDINATION"));
        OwnershipFixture teamDrainTransferFixture = createSchedulableOwnershipFixture(intakeTenant, trustedLocality,
                indore, deactivationRaceTeam, transferCoordinator, visitOperationsAdmin);
        Long drainTransferSessionId = teamDrainTransferFixture.session().getId();
        Long drainTransferSessionVersion = teamDrainTransferFixture.session().getVersion();
        Long drainTransferRequestId = teamDrainTransferFixture.request().getId();
        Long drainTransferRequestVersion = teamDrainTransferFixture.request().getVersion();
        Long deactivationRaceTeamId = deactivationRaceTeam.getId();
        Long deactivationRaceTeamVersion = deactivationRaceTeam.getVersion();
        Long deactivationRaceCityVersion = indore.getVersion();
        CountDownLatch drainTransferReady = new CountDownLatch(2);
        CountDownLatch drainTransferStart = new CountDownLatch(1);
        ExecutorService drainTransferPool = Executors.newFixedThreadPool(2);
        boolean teamDeactivationCommitted;
        try {
            Future<Boolean> transfer = drainTransferPool.submit(() -> attemptTransfer(drainTransferReady,
                    drainTransferStart, ownerId, drainTransferSessionId, drainTransferSessionVersion,
                    drainTransferRequestId, drainTransferRequestVersion,
                    destinationTeam.getId(), transferCoordinator.getId()));
            Future<Boolean> deactivate = drainTransferPool.submit(() -> attemptTeamDeactivation(drainTransferReady,
                    drainTransferStart, ownerId, deactivationRaceTeamId, deactivationRaceCityVersion,
                    deactivationRaceTeamVersion));
            assertTrue(drainTransferReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            drainTransferStart.countDown();
            assertTrue(transfer.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "the ownership transfer remains valid whether deactivation first finds workload or follows the drain");
            teamDeactivationCommitted = deactivate.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            drainTransferPool.shutdownNow();
        }
        assertEquals(teamDeactivationCommitted, !teams.findById(deactivationRaceTeamId).orElseThrow().isActive());
        assertEquals(destinationTeam.getId(), visitSessions.findById(drainTransferSessionId).orElseThrow()
                .getOperatingTeam().getId());
        assertEquals(destinationTeam.getId(), requests.findById(drainTransferRequestId).orElseThrow()
                .getOperatingTeam().getId());

        OperatingTeam destinationDeactivationTeam = teams.saveAndFlush(new OperatingTeam(indore,
                "phase1c-transfer-target-" + UUID.randomUUID().toString().substring(0, 8), "Transfer Target Drain", true));
        administration.createGrant(ownerId, ownerId, grant(StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM,
                null, destinationDeactivationTeam.getId(), null, null, "TARGET_DRAIN_SUPERVISION"));
        administration.createGrant(ownerId, transferCoordinator.getId(), grant(StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, null, destinationDeactivationTeam.getId(), null, null, "TARGET_DRAIN_COORDINATION"));
        OwnershipFixture targetDrainFixture = createSchedulableOwnershipFixture(intakeTenant, trustedLocality,
                indore, team, transferCoordinator, visitOperationsAdmin);
        Long targetDrainSessionId = targetDrainFixture.session().getId();
        Long targetDrainSessionVersion = targetDrainFixture.session().getVersion();
        Long targetDrainRequestId = targetDrainFixture.request().getId();
        Long targetDrainRequestVersion = targetDrainFixture.request().getVersion();
        Long destinationDeactivationTeamId = destinationDeactivationTeam.getId();
        CountDownLatch targetDrainReady = new CountDownLatch(2);
        CountDownLatch targetDrainStart = new CountDownLatch(1);
        ExecutorService targetDrainPool = Executors.newFixedThreadPool(2);
        boolean targetTransferWon;
        boolean destinationDeactivationCommitted;
        try {
            Future<Boolean> transfer = targetDrainPool.submit(() -> attemptTransfer(targetDrainReady,
                    targetDrainStart, ownerId, targetDrainSessionId, targetDrainSessionVersion,
                    targetDrainRequestId, targetDrainRequestVersion,
                    destinationDeactivationTeamId, transferCoordinator.getId()));
            Future<Boolean> deactivate = targetDrainPool.submit(() -> attemptTeamDeactivation(targetDrainReady,
                    targetDrainStart, ownerId, destinationDeactivationTeamId,
                    cities.findById(indore.getId()).orElseThrow().getVersion(),
                    teams.findById(destinationDeactivationTeamId).orElseThrow().getVersion()));
            assertTrue(targetDrainReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            targetDrainStart.countDown();
            targetTransferWon = transfer.get(20, java.util.concurrent.TimeUnit.SECONDS);
            destinationDeactivationCommitted = deactivate.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            targetDrainPool.shutdownNow();
        }
        assertNotEquals(targetTransferWon, destinationDeactivationCommitted,
                "an inactive destination rejects transfer; a committed transfer makes Team drain block deactivation");
        assertEquals(targetTransferWon, teams.findById(destinationDeactivationTeamId).orElseThrow().isActive());
        assertEquals(targetTransferWon ? destinationDeactivationTeamId : team.getId(),
                visitSessions.findById(targetDrainSessionId).orElseThrow().getOperatingTeam().getId());

        OperatingTeam teamGrantRace = teams.saveAndFlush(new OperatingTeam(indore,
                "phase1c-team-grant-race-" + UUID.randomUUID().toString().substring(0, 8), "Team Grant Race", true));
        User teamGrantRaceTarget = saveUser("phase1c-team-grant-race-target@example.test", Role.ROLE_TENANT);
        administration.activateStaff(ownerId, teamGrantRaceTarget.getId(), new StaffStateCommand(null, "STAFF_ONBOARDING"));
        Long teamGrantRaceId = teamGrantRace.getId();
        Long teamGrantRaceVersion = teamGrantRace.getVersion();
        Long teamGrantRaceCityVersion = cities.findById(indore.getId()).orElseThrow().getVersion();
        CountDownLatch teamGrantRaceReady = new CountDownLatch(2);
        CountDownLatch teamGrantRaceStart = new CountDownLatch(1);
        ExecutorService teamGrantRacePool = Executors.newFixedThreadPool(2);
        boolean teamGrantIssued;
        try {
            Future<Boolean> grantIssue = teamGrantRacePool.submit(() -> attemptCreateTeamCoordinatorGrant(
                    teamGrantRaceReady, teamGrantRaceStart, ownerId, teamGrantRaceTarget.getId(), teamGrantRaceId));
            Future<Boolean> deactivate = teamGrantRacePool.submit(() -> attemptTeamDeactivation(teamGrantRaceReady,
                    teamGrantRaceStart, ownerId, teamGrantRaceId, teamGrantRaceCityVersion, teamGrantRaceVersion));
            assertTrue(teamGrantRaceReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            teamGrantRaceStart.countDown();
            teamGrantIssued = grantIssue.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(deactivate.get(20, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            teamGrantRacePool.shutdownNow();
        }
        assertFalse(teams.findById(teamGrantRaceId).orElseThrow().isActive());
        assertEquals(teamGrantIssued ? 1 : 0, jdbc.queryForObject("SELECT count(*) FROM staff_access_grants "
                + "WHERE user_id=? AND team_id=? AND capability='OPS_COORDINATE' AND revoked_at IS NULL",
                Integer.class, teamGrantRaceTarget.getId(), teamGrantRaceId));
        assertFalse(access.hasCapabilityAt(teamGrantRaceTarget.getId(), StaffCapability.OPS_COORDINATE,
                StaffScopeType.TEAM, teamGrantRaceId));

        SupportedCity cityGrantRace = cities.saveAndFlush(new SupportedCity(
                "phase1c-city-grant-race-" + UUID.randomUUID().toString().substring(0, 8), "City Grant Race", true));
        User cityGrantRaceTarget = saveUser("phase1c-city-grant-race-target@example.test", Role.ROLE_TENANT);
        administration.activateStaff(ownerId, cityGrantRaceTarget.getId(), new StaffStateCommand(null, "STAFF_ONBOARDING"));
        Long cityGrantRaceId = cityGrantRace.getId();
        Long cityGrantRaceVersion = cityGrantRace.getVersion();
        Locality cityGrantRaceLocality = new Locality(cityGrantRace.getDisplayName(),
                "city-intake-" + UUID.randomUUID(), null, null, null);
        cityGrantRaceLocality.setSupportedCity(cityGrantRace);
        cityGrantRaceLocality = localities.saveAndFlush(cityGrantRaceLocality);
        PropertyVisitRequest cityGrantRaceRequest = request(intakeTenant,
                listing("city-deactivation-intake", cityGrantRaceLocality));
        CountDownLatch cityGrantRaceReady = new CountDownLatch(3);
        CountDownLatch cityGrantRaceStart = new CountDownLatch(1);
        ExecutorService cityGrantRacePool = Executors.newFixedThreadPool(3);
        boolean cityGrantIssued;
        try {
            Future<Boolean> grantIssue = cityGrantRacePool.submit(() -> attemptCreateCityIntakeGrant(
                    cityGrantRaceReady, cityGrantRaceStart, ownerId, cityGrantRaceTarget.getId(), cityGrantRaceId));
            Future<Boolean> deactivate = cityGrantRacePool.submit(() -> attemptCityDeactivation(cityGrantRaceReady,
                    cityGrantRaceStart, ownerId, cityGrantRaceId, cityGrantRaceVersion));
            Future<Boolean> intake = cityGrantRacePool.submit(() -> attemptTrustedIntake(cityGrantRaceReady,
                    cityGrantRaceStart, cityGrantRaceRequest));
            assertTrue(cityGrantRaceReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            cityGrantRaceStart.countDown();
            cityGrantIssued = grantIssue.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(deactivate.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "a drained City deactivates after any grant issuance admitted before its guard");
            assertTrue(intake.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "customer intake remains available while City deactivation serializes operational readiness");
        } finally {
            cityGrantRacePool.shutdownNow();
        }
        assertFalse(cities.findById(cityGrantRaceId).orElseThrow().isActive());
        assertEquals(cityGrantIssued ? 1 : 0, jdbc.queryForObject("SELECT count(*) FROM staff_access_grants "
                + "WHERE user_id=? AND city_id=? AND capability='OPS_INTAKE' AND revoked_at IS NULL",
                Integer.class, cityGrantRaceTarget.getId(), cityGrantRaceId));
        assertFalse(access.hasCapabilityAt(cityGrantRaceTarget.getId(), StaffCapability.OPS_INTAKE,
                StaffScopeType.CITY, cityGrantRaceId), "a grant cannot authorize work after City deactivation wins");
        PropertyVisitRequest persistedCityIntake = requests.findById(cityGrantRaceRequest.getId()).orElseThrow();
        assertEquals(cityGrantRaceId, persistedCityIntake.getSupportedCity().getId());
        assertNull(persistedCityIntake.getOperatingTeam());

        VisitSession linked = new VisitSession();
        linked.setTenant(intakeTenant);
        linked.setCity(indore.getDisplayName());
        linked.setCanonicalLocality(trustedLocality);
        linked.setRepresentative(groundExecutive);
        linked.setAssignedAt(Instant.now());
        linked = visitSessions.saveAndFlush(linked);
        PropertyVisitRequest linkedOne = requests.saveAndFlush(request(intakeTenant,
                listing("linked-one", trustedLocality), linked, VisitRequestStatus.COORDINATING));
        PropertyVisitRequest linkedTwo = requests.saveAndFlush(request(intakeTenant,
                listing("linked-two", trustedLocality), linked, VisitRequestStatus.COORDINATING));
        assertNull(linkedOne.getSupportedCity());
        assertFalse(linkedOne.isOperationalScopeReady());
        Long initialSessionVersion = linked.getVersion();
        Map<Long, Long> initialRequestVersions = Map.of(linkedOne.getId(), linkedOne.getVersion(),
                linkedTwo.getId(), linkedTwo.getVersion());
        ownership.reconcileSession(ownerId, linked.getId(), initialSessionVersion, initialRequestVersions,
                indore.getId(), null, null, true, indore.getVersion(), null, "INITIAL_SCOPE_RECONCILIATION");
        linked = visitSessions.findById(linked.getId()).orElseThrow();
        linkedOne = requests.findById(linkedOne.getId()).orElseThrow();
        linkedTwo = requests.findById(linkedTwo.getId()).orElseThrow();
        assertTrue(linked.isOperationalScopeReady());
        assertTrue(linkedOne.isOperationalScopeReady());
        assertTrue(linkedTwo.isOperationalScopeReady());
        assertEquals(indore.getId(), linkedOne.getSupportedCity().getId());
        assertEquals(indore.getId(), linkedTwo.getSupportedCity().getId());
        assertTrue(linked.getVersion() > initialSessionVersion);
        assertTrue(linkedOne.getVersion() > initialRequestVersions.get(linkedOne.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE action_code='OWNERSHIP_RECONCILED' AND target_id=?", Integer.class, linked.getId()));

        Map<Long, Long> scopeRequestVersions = requestVersions(linkedOne, linkedTwo);
        long beforeTeamReconciliationVersion = linked.getVersion();
        ownership.reconcileSession(ownerId, linked.getId(), linked.getVersion(), scopeRequestVersions,
                indore.getId(), team.getId(), transferCoordinator.getId(), true,
                indore.getVersion(), team.getVersion(), "TEAM_SCOPE_RECONCILIATION");
        linked = visitSessions.findById(linked.getId()).orElseThrow();
        linkedOne = requests.findById(linkedOne.getId()).orElseThrow();
        linkedTwo = requests.findById(linkedTwo.getId()).orElseThrow();
        assertTrue(linked.getVersion() > beforeTeamReconciliationVersion);
        for (Map.Entry<Long, Long> prior : scopeRequestVersions.entrySet())
            assertTrue(requests.findById(prior.getKey()).orElseThrow().getVersion() > prior.getValue());
        assertEquals(2, countActionForTarget("OWNERSHIP_RECONCILED", linked.getId()));
        assertEquals(groundExecutive.getId(), linked.getRepresentative().getId(), "OE reconciliation preserves GE assignment");

        User outsider = saveUser("phase1c-outside-scope@example.test", Role.ROLE_TENANT);
        Long linkedSessionId = linked.getId();
        Long linkedSessionVersion = linked.getVersion();
        Map<Long, Long> linkedRequestVersions = requestVersions(linkedOne, linkedTwo);
        Long outsiderId = outsider.getId();
        assertThrows(EntityNotFoundException.class, () -> ownership.claimSession(outsiderId, linkedSessionId,
                linkedSessionVersion, linkedRequestVersions, "OUTSIDE_SCOPE"));

        long beforeAssignmentVersion = linked.getVersion();
        Map<Long, Long> beforeAssignmentRequestVersions = requestVersions(linkedOne, linkedTwo);
        ownership.assignCoordinator(ownerId, linked.getId(), beforeAssignmentVersion,
                beforeAssignmentRequestVersions, target.getId(), "SUPERVISOR_REASSIGNMENT");
        linked = visitSessions.findById(linked.getId()).orElseThrow();
        linkedOne = requests.findById(linkedOne.getId()).orElseThrow();
        linkedTwo = requests.findById(linkedTwo.getId()).orElseThrow();
        assertTrue(linked.getVersion() > beforeAssignmentVersion);
        for (Map.Entry<Long, Long> prior : beforeAssignmentRequestVersions.entrySet())
            assertTrue(requests.findById(prior.getKey()).orElseThrow().getVersion() > prior.getValue());
        assertEquals(1, countActionForTarget("OWNERSHIP_ASSIGNED", linked.getId()));
        long assignedSessionVersion = linked.getVersion();
        Map<Long, Long> assignedRequestVersions = requestVersions(linkedOne, linkedTwo);
        int assignmentAuditsBeforeNoop = countActionForTarget("OWNERSHIP_ASSIGNED", linked.getId());
        ownership.assignCoordinator(ownerId, linked.getId(), assignedSessionVersion,
                assignedRequestVersions, target.getId(), "SUPERVISOR_ASSIGNMENT_NOOP");
        linked = visitSessions.findById(linked.getId()).orElseThrow();
        linkedOne = requests.findById(linkedOne.getId()).orElseThrow();
        linkedTwo = requests.findById(linkedTwo.getId()).orElseThrow();
        assertEquals(assignedSessionVersion, linked.getVersion(), "same-coordinator assignment is a no-op");
        assertEquals(assignedRequestVersions, requestVersions(linkedOne, linkedTwo));
        assertEquals(assignmentAuditsBeforeNoop, countActionForTarget("OWNERSHIP_ASSIGNED", linked.getId()),
                "a no-op assignment must not emit a duplicate success audit");
        long beforeTransferVersion = linked.getVersion();
        Map<Long, Long> beforeTransferRequestVersions = requestVersions(linkedOne, linkedTwo);
        ownership.transferTeam(ownerId, linked.getId(), beforeTransferVersion,
                beforeTransferRequestVersions, destinationTeam.getId(), transferCoordinator.getId(),
                "CROSS_TEAM_TRANSFER");
        linked = visitSessions.findById(linked.getId()).orElseThrow();
        linkedOne = requests.findById(linkedOne.getId()).orElseThrow();
        linkedTwo = requests.findById(linkedTwo.getId()).orElseThrow();
        assertTrue(linked.getVersion() > beforeTransferVersion);
        for (Map.Entry<Long, Long> prior : beforeTransferRequestVersions.entrySet())
            assertTrue(requests.findById(prior.getKey()).orElseThrow().getVersion() > prior.getValue());
        for (PropertyVisitRequest mirrored : List.of(linkedOne, linkedTwo)) {
            assertEquals(indore.getId(), mirrored.getSupportedCity().getId());
            assertEquals(destinationTeam.getId(), mirrored.getOperatingTeam().getId());
            assertEquals(transferCoordinator.getId(), mirrored.getCoordinator().getId());
            assertTrue(mirrored.isOperationalScopeReady());
        }
        assertEquals(groundExecutive.getId(), linked.getRepresentative().getId(), "OE transfer preserves GE assignment");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE action_code='OWNERSHIP_TRANSFERRED' AND target_id=?", Integer.class, linked.getId()));
        long transferredSessionVersion = linked.getVersion();
        Map<Long, Long> transferredRequestVersions = requestVersions(linkedOne, linkedTwo);
        int transferAuditsBeforeNoop = countActionForTarget("OWNERSHIP_TRANSFERRED", linked.getId());
        ownership.transferTeam(ownerId, linked.getId(), transferredSessionVersion, transferredRequestVersions,
                destinationTeam.getId(), transferCoordinator.getId(), "SAME_TEAM_TRANSFER_NOOP");
        linked = visitSessions.findById(linked.getId()).orElseThrow();
        linkedOne = requests.findById(linkedOne.getId()).orElseThrow();
        linkedTwo = requests.findById(linkedTwo.getId()).orElseThrow();
        assertEquals(transferredSessionVersion, linked.getVersion(), "same-Team/same-coordinator transfer is a no-op");
        assertEquals(transferredRequestVersions, requestVersions(linkedOne, linkedTwo));
        assertEquals(transferAuditsBeforeNoop, countActionForTarget("OWNERSHIP_TRANSFERRED", linked.getId()),
                "a no-op transfer must not emit a duplicate success audit");

        int reconciliationAuditsBeforeNoop = countActionForTarget("OWNERSHIP_RECONCILED", linked.getId());
        ownership.reconcileSession(ownerId, linked.getId(), linked.getVersion(),
                requestVersions(linkedOne, linkedTwo), indore.getId(), destinationTeam.getId(),
                transferCoordinator.getId(), true, indore.getVersion(), destinationTeam.getVersion(),
                "IDENTICAL_RECONCILIATION_NOOP");
        linked = visitSessions.findById(linked.getId()).orElseThrow();
        linkedOne = requests.findById(linkedOne.getId()).orElseThrow();
        linkedTwo = requests.findById(linkedTwo.getId()).orElseThrow();
        assertEquals(transferredSessionVersion, linked.getVersion(), "identical reconciliation is a no-op");
        assertEquals(transferredRequestVersions, requestVersions(linkedOne, linkedTwo));
        assertEquals(reconciliationAuditsBeforeNoop, countActionForTarget("OWNERSHIP_RECONCILED", linked.getId()),
                "a no-op reconciliation must not emit a duplicate success audit");
        Long outsideSourceTeamActorId = target.getId();
        Long transferredSessionIdForVisibility = linked.getId();
        Long transferredSessionVersionForVisibility = linked.getVersion();
        Map<Long, Long> transferredRequestVersionsForVisibility = requestVersions(linkedOne, linkedTwo);
        assertThrows(EntityNotFoundException.class, () -> ownership.claimSession(outsideSourceTeamActorId,
                transferredSessionIdForVisibility, transferredSessionVersionForVisibility,
                transferredRequestVersionsForVisibility,
                "HIDDEN_LOSER_RETRY"), "a loser outside the transferred Team scope receives a hidden result");
        assertThrows(VisitOperationsConflictException.class, () -> ownership.transferTeam(ownerId,
                linkedSessionId, linkedSessionVersion, linkedRequestVersions, destinationTeam.getId(),
                transferCoordinator.getId(), "STALE_TRANSFER"));

        VisitSession releaseCandidate = new VisitSession();
        releaseCandidate.setTenant(intakeTenant);
        releaseCandidate.setCity(indore.getDisplayName());
        releaseCandidate.setSupportedCity(indore);
        releaseCandidate.setOperatingTeam(destinationTeam);
        releaseCandidate.setCoordinator(transferCoordinator);
        releaseCandidate.setOperationalScopeReady(true);
        releaseCandidate = visitSessions.saveAndFlush(releaseCandidate);
        ownership.releaseCoordinator(transferCoordinator.getId(), releaseCandidate.getId(),
                releaseCandidate.getVersion(), Map.of(), "DRAFT_RELEASE");
        assertNull(visitSessions.findById(releaseCandidate.getId()).orElseThrow().getCoordinator());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE action_code='OWNERSHIP_RELEASED' AND target_id=?", Integer.class, releaseCandidate.getId()));

        VisitSession scheduledReleaseCandidate = new VisitSession();
        scheduledReleaseCandidate.setTenant(intakeTenant);
        scheduledReleaseCandidate.setCity(indore.getDisplayName());
        scheduledReleaseCandidate.setSupportedCity(indore);
        scheduledReleaseCandidate.setOperatingTeam(destinationTeam);
        scheduledReleaseCandidate.setCoordinator(transferCoordinator);
        scheduledReleaseCandidate.setOperationalScopeReady(true);
        scheduledReleaseCandidate.setStatus(com.indore.pathome.spaces.entity.VisitSessionStatus.SCHEDULED);
        Instant scheduledAt = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        scheduledReleaseCandidate.setScheduledAt(scheduledAt);
        scheduledReleaseCandidate.setZoneId("Asia/Kolkata");
        scheduledReleaseCandidate.setRepresentative(transferCoordinator);
        scheduledReleaseCandidate.setAssignedAt(Instant.now());
        scheduledReleaseCandidate.setDurationSnapshotMinutes(60);
        scheduledReleaseCandidate.setReservedEndAt(scheduledAt.plusSeconds(3600));
        scheduledReleaseCandidate = visitSessions.saveAndFlush(scheduledReleaseCandidate);
        Long scheduledReleaseId = scheduledReleaseCandidate.getId();
        Long scheduledReleaseVersion = scheduledReleaseCandidate.getVersion();
        assertThrows(VisitOperationsConflictException.class, () -> ownership.releaseCoordinator(
                transferCoordinator.getId(), scheduledReleaseId, scheduledReleaseVersion, Map.of(), "SCHEDULED_RELEASE"));

        SupportedCity drainCity = cities.saveAndFlush(new SupportedCity(
                "drain-test-" + UUID.randomUUID().toString().substring(0, 8), "Drain Test City", true));
        OperatingTeam drainTeam = teams.saveAndFlush(new OperatingTeam(drainCity, "drain-team", "Drain Team", true));
        Locality drainLocality = new Locality(drainCity.getDisplayName(), "drain-" + UUID.randomUUID(), null, null, null);
        drainLocality.setSupportedCity(drainCity);
        drainLocality = localities.saveAndFlush(drainLocality);
        VisitSession drainSession = new VisitSession();
        drainSession.setTenant(intakeTenant);
        drainSession.setCity(drainCity.getDisplayName());
        drainSession.setCanonicalLocality(drainLocality);
        drainSession.setSupportedCity(drainCity);
        drainSession.setOperatingTeam(drainTeam);
        drainSession.setOperationalScopeReady(true);
        drainSession = visitSessions.saveAndFlush(drainSession);
        PropertyVisitRequest drainRequestOne = request(intakeTenant,
                listing("drain-one", drainLocality), drainSession, VisitRequestStatus.COORDINATING);
        drainRequestOne.setSupportedCity(drainCity);
        drainRequestOne.setOperatingTeam(drainTeam);
        drainRequestOne.setOperationalScopeReady(true);
        drainRequestOne = requests.saveAndFlush(drainRequestOne);
        PropertyVisitRequest drainRequestTwo = request(intakeTenant,
                listing("drain-two", drainLocality), drainSession, VisitRequestStatus.COORDINATING);
        drainRequestTwo.setSupportedCity(drainCity);
        drainRequestTwo.setOperatingTeam(drainTeam);
        drainRequestTwo.setOperationalScopeReady(true);
        drainRequestTwo = requests.saveAndFlush(drainRequestTwo);
        Long drainSessionId = drainSession.getId();
        Long drainCityId = drainCity.getId();
        Long drainTeamId = drainTeam.getId();
        Long drainCityVersion = drainCity.getVersion();
        Long drainTeamVersion = drainTeam.getVersion();
        assertThrows(VisitOperationsConflictException.class, () -> ownership.deactivateTeam(
                ownerId, drainTeamId, drainCityVersion, drainTeamVersion + 1, "STALE_TEAM_DRAIN"));
        assertThrows(VisitOperationsConflictException.class, () -> ownership.deactivateCity(
                ownerId, drainCityId, drainCityVersion + 1, "STALE_CITY_DRAIN"));
        assertThrows(VisitOperationsConflictException.class, () -> ownership.deactivateTeam(
                ownerId, drainTeamId, drainCityVersion, drainTeamVersion, "BLOCKED_TEAM_DRAIN"));
        assertThrows(VisitOperationsConflictException.class, () -> ownership.deactivateCity(
                ownerId, drainCityId, drainCityVersion, "ACTIVE_TEAM_BLOCKS_CITY"));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            VisitSession current = visitSessions.findLockedById(drainSessionId).orElseThrow();
            current.setStatus(com.indore.pathome.spaces.entity.VisitSessionStatus.COMPLETED);
            visitSessions.saveAndFlush(current);
        });
        assertEquals(VisitRequestStatus.COORDINATING, requests.findById(drainRequestOne.getId()).orElseThrow().getStatusValue());
        assertEquals(VisitRequestStatus.COORDINATING, requests.findById(drainRequestTwo.getId()).orElseThrow().getStatusValue());
        ownership.deactivateTeam(ownerId, drainTeamId, drainCityVersion, drainTeamVersion, "DRAINED_TEAM");

        PropertyVisitRequest cityOnlyIntake = operationalIntake.admitAndSave(
                request(intakeTenant, listing("city-only-intake", drainLocality)));
        assertTrue(cityOnlyIntake.isOperationalScopeReady());
        ownership.deactivateCity(ownerId, drainCityId, drainCityVersion, "DRAINED_CITY");
        assertFalse(cities.findById(drainCityId).orElseThrow().isActive());
        assertTrue(requests.findById(cityOnlyIntake.getId()).isPresent(),
                "City deactivation preserves unassigned customer intake for Admin-only handling");
        PropertyVisitRequest afterDeactivationIntake = operationalIntake.admitAndSave(
                request(intakeTenant, listing("inactive-city-intake", drainLocality)));
        assertEquals(drainCityId, afterDeactivationIntake.getSupportedCity().getId());
        assertFalse(afterDeactivationIntake.isOperationalScopeReady());
        assertNull(afterDeactivationIntake.getOperatingTeam());

        assertThrows(AccessDeniedException.class, () -> visitOperationsAuthorization.requireOperations(target.getId()),
                "OPS_COORDINATE grant must not bypass Phase 0 Admin-only Visit Operations containment");
        User supervisor = saveUser("phase1b-supervisor@example.test", Role.ROLE_TENANT);
        administration.activateStaff(ownerId, supervisor.getId(), new StaffStateCommand(null, "STAFF_ONBOARDING"));
        administration.createGrant(ownerId, supervisor.getId(), grant(
                StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, null, team.getId(), null, null,
                "TEAM_SUPERVISION"));
        assertThrows(AccessDeniedException.class,
                () -> visitOperationsAuthorization.requireOperations(supervisor.getId()),
                "OPS_SUPERVISE grant must not bypass Phase 0 Admin-only Visit Operations containment");

        User legacyRoleAdmin = saveUser("phase1b-legacy-admin@example.test", Role.ROLE_ADMIN);
        administration.activateStaff(ownerId, legacyRoleAdmin.getId(), new StaffStateCommand(null, "STAFF_ONBOARDING"));
        assertThrows(AccessDeniedException.class, () -> administration.createGrant(legacyRoleAdmin.getId(),
                legacyRoleAdmin.getId(), grant(StaffCapability.OPS_INTAKE, StaffScopeType.CITY,
                        indore.getId(), null, null, null, "SELF_ELEVATION")),
                "persisted ROLE_ADMIN without a current global grant cannot use the 1B Admin API");

        User rollbackTarget = saveUser("phase1b-rollback-target@example.test", Role.ROLE_TENANT);
        administration.activateStaff(ownerId, rollbackTarget.getId(), new StaffStateCommand(null, "STAFF_ONBOARDING"));
        long beforeRollbackAudits = audits.count();
        TransactionTemplate rollback = new TransactionTemplate(transactionManager);
        rollback.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        rollback.execute(status -> {
            administration.createGrant(ownerId, rollbackTarget.getId(), grant(
                    StaffCapability.OPS_INTAKE, StaffScopeType.CITY, indore.getId(), null, null, null,
                    "ROLLBACK_PROBE"));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(beforeRollbackAudits, audits.count());
        assertTrue(grants.findAllByUserIdOrderByCreatedAtDesc(rollbackTarget.getId()).isEmpty());

        CountDownLatch revokeReady = new CountDownLatch(2);
        CountDownLatch revokeStart = new CountDownLatch(1);
        ExecutorService revokePool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> removeOther = revokePool.submit(() -> attemptConcurrentRevoke(
                    revokeReady, revokeStart, ownerId, secondAdminGrant.id(), "RACE_REVOKE_OTHER"));
            Future<Boolean> removeOwner = revokePool.submit(() -> attemptConcurrentRevoke(
                    revokeReady, revokeStart, otherAdminId, ownerGrantId, "RACE_REVOKE_OTHER"));
            assertTrue(revokeReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            revokeStart.countDown();
            assertNotEquals(removeOther.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    removeOwner.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    "concurrent cross-revocation leaves one valid global Admin");
        } finally {
            revokePool.shutdownNow();
        }
        assertEquals(1, grants.countEffectiveGlobalAdmins());

        Long finalAdminId = jdbc.queryForObject("SELECT g.user_id FROM staff_access_grants g "
                + "JOIN employee_profiles p ON p.user_id=g.user_id AND p.staff_active=TRUE "
                + "WHERE g.capability='STAFF_ADMIN' AND g.scope_type='GLOBAL' AND g.revoked_at IS NULL "
                + "AND g.effective_at<=clock_timestamp()", Long.class);
        Long finalAdminGrantId = jdbc.queryForObject("SELECT id FROM staff_access_grants WHERE user_id=? "
                + "AND capability='STAFF_ADMIN' AND scope_type='GLOBAL' AND revoked_at IS NULL",
                Long.class, finalAdminId);
        assertThrows(StaffAccessConflictException.class,
                () -> administration.revokeGrant(finalAdminId, finalAdminGrantId, "FINAL_ADMIN_REMOVAL"));
        EmployeeProfile finalProfile = employeeProfiles.findByUserId(finalAdminId).orElseThrow();
        assertThrows(StaffAccessConflictException.class,
                () -> administration.deactivateStaff(finalAdminId, finalAdminId,
                        new StaffStateCommand(finalProfile.getVersion(), "FINAL_ADMIN_DEACTIVATION")));
        assertEquals(1, grants.countEffectiveGlobalAdmins());

        StaffUserAccessState targetBeforeDeactivate = administration.inspectUser(finalAdminId, target.getId());
        StaffUserAccessState deactivated = administration.deactivateStaff(finalAdminId, target.getId(),
                new StaffStateCommand(targetBeforeDeactivate.version(), "STAFF_DEACTIVATION"));
        assertFalse(deactivated.staffActive());
        assertEquals(targetBeforeDeactivate.version() + 1, deactivated.version());
        assertTrue(deactivated.grants().stream().filter(value -> value.id().equals(cityGrantId))
                .allMatch(value -> value.revokedAt() != null));
        assertTrue(deactivated.grants().stream().filter(value -> value.id().equals(future.id()))
                .allMatch(value -> value.revokedAt() != null), "deactivation revokes future-effective grant facts too");
        assertTrue(users.findById(target.getId()).isPresent(), "deactivation preserves the authenticated User");
        assertFalse(access.currentCapabilities(target.getId()).staffActive());
        assertTrue(access.currentCapabilities(target.getId()).capabilities().isEmpty());

        StaffUserAccessState reactivated = administration.activateStaff(finalAdminId, target.getId(),
                new StaffStateCommand(deactivated.version(), "STAFF_REACTIVATION"));
        assertTrue(reactivated.staffActive());
        assertEquals(deactivated.version() + 1, reactivated.version());
        assertTrue(access.currentCapabilities(target.getId()).capabilities().isEmpty(),
                "reactivation does not restore revoked grants");
        assertThrows(StaffAccessConflictException.class, () -> administration.activateStaff(finalAdminId,
                target.getId(), new StaffStateCommand(deactivated.version(), "STALE_ACTIVATION")));
        assertThrows(StaffAccessConflictException.class, () -> administration.deactivateStaff(finalAdminId,
                target.getId(), new StaffStateCommand(deactivated.version(), "STALE_DEACTIVATION")));

        User legacyGroundExecutive = saveUser("phase1b-legacy-ground@example.test", Role.ROLE_GROUND_BOY);
        EmployeeProfile legacyGroundProfile = employeeProfiles.saveAndFlush(
                new EmployeeProfile(legacyGroundExecutive, "GROUND_BOY", "Legacy Sector", null));
        assertEquals(legacyGroundExecutive.getId(),
                visitOperationsAuthorization.requireGroundExecutive(legacyGroundExecutive.getId()).getId(),
                "legacy GE eligibility continues to come from the persisted User role and profile role type");
        StaffUserAccessState legacyActivated = administration.activateStaff(finalAdminId,
                legacyGroundExecutive.getId(), new StaffStateCommand(legacyGroundProfile.getVersion(), "STAFF_ONBOARDING"));
        StaffUserAccessState legacyDeactivated = administration.deactivateStaff(finalAdminId,
                legacyGroundExecutive.getId(), new StaffStateCommand(legacyActivated.version(), "STAFF_DEACTIVATION"));
        StaffUserAccessState legacyReactivated = administration.activateStaff(finalAdminId,
                legacyGroundExecutive.getId(), new StaffStateCommand(legacyDeactivated.version(), "STAFF_REACTIVATION"));
        User retainedLegacyUser = users.findById(legacyGroundExecutive.getId()).orElseThrow();
        EmployeeProfile retainedLegacyProfile = employeeProfiles.findByUserId(legacyGroundExecutive.getId()).orElseThrow();
        assertEquals(Role.ROLE_GROUND_BOY, retainedLegacyUser.getRole());
        assertEquals("GROUND_BOY", retainedLegacyProfile.getRoleType());
        assertTrue(legacyReactivated.staffActive());
        assertTrue(access.currentCapabilities(legacyGroundExecutive.getId()).capabilities().isEmpty(),
                "legacy profile reactivation must not create a Phase 1 capability grant");
        assertEquals(legacyGroundExecutive.getId(),
                visitOperationsAuthorization.requireGroundExecutive(legacyGroundExecutive.getId()).getId(),
                "legacy GE authorization remains derived from its unchanged persisted role/profile");

        assertTrue(jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE actor_kind='USER' AND actor_user_id IS NOT NULL "
                + "AND action_code IN ('STAFF_ACTIVATED','STAFF_DEACTIVATED','STAFF_ACCESS_GRANT_CREATED',"
                + "'STAFF_ACCESS_GRANT_REVOKED')", Integer.class) > 0);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE details::text ILIKE '%example.test%' OR details::text ILIKE '%password%' "
                + "OR details::text ILIKE '%otp%' OR details::text ILIKE '%salary%'", Integer.class));

        jdbc.update("UPDATE staff_access_grants SET revoked_at=clock_timestamp(), "
                + "revoke_reason_code='HISTORICAL_TEST_REVOCATION' WHERE capability='STAFF_ADMIN' "
                + "AND scope_type='GLOBAL' AND revoked_at IS NULL");
        assertEquals(0, grants.countEffectiveGlobalAdmins());
        EmployeeProfile changedConfiguredUserProfile = employeeProfiles.findByUserId(unusedConfiguredUserId).orElseThrow();
        assertFalse(bootstrap.provisionFirstAdmin(users.findById(unusedConfiguredUserId).orElseThrow().getEmail(),
                null, "DEPLOYMENT_CHANGE_AFTER_REVOCATION"),
                "revoked historical global Admin facts still prevent bootstrap for a changed configured owner");
        EmployeeProfile profileAfterRejectedBootstrap = employeeProfiles.findByUserId(unusedConfiguredUserId).orElseThrow();
        assertEquals(changedConfiguredUserProfile.getId(), profileAfterRejectedBootstrap.getId());
        assertEquals(changedConfiguredUserProfile.getVersion(), profileAfterRejectedBootstrap.getVersion());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM staff_access_grants "
                + "WHERE capability='STAFF_ADMIN' AND scope_type='GLOBAL'", Integer.class));
    }

    private boolean attemptConcurrentGrant(CountDownLatch ready, CountDownLatch start, Long actorId,
                                          Long subjectId, Long cityId) throws Exception {
        ready.countDown();
        start.await();
        try {
            administration.createGrant(actorId, subjectId, grant(StaffCapability.OPS_INTAKE,
                    StaffScopeType.CITY, cityId, null, null, null, "CONCURRENT_INTAKE"));
            return true;
        } catch (StaffAccessConflictException expected) {
            return false;
        }
    }

    private boolean attemptClaim(CountDownLatch ready, CountDownLatch start, Long actorId,
                                 Long sessionId) throws Exception {
        ready.countDown();
        start.await();
        try {
            ownership.claimSession(actorId, sessionId, 0L, Map.of(), "CLAIM_RACE_TEST");
            return true;
        } catch (VisitOperationsConflictException | AccessDeniedException | EntityNotFoundException expected) {
            return false;
        }
    }

    private boolean attemptTransfer(CountDownLatch ready, CountDownLatch start, Long actorId,
            Long sessionId, Long expectedSessionVersion, Long requestId, Long expectedRequestVersion,
            Long destinationTeamId, Long coordinatorId) throws Exception {
        ready.countDown();
        start.await();
        try {
            ownership.transferTeam(actorId, sessionId, expectedSessionVersion, Map.of(requestId, expectedRequestVersion),
                    destinationTeamId, coordinatorId, "TRANSFER_VISIT_RACE");
            return true;
        } catch (VisitOperationsConflictException | AccessDeniedException | EntityNotFoundException expected) {
            return false;
        }
    }

    private boolean attemptSchedule(CountDownLatch ready, CountDownLatch start, Long actorId,
            Long sessionId, Long expectedSessionVersion, Long groundExecutiveId) throws Exception {
        ready.countDown();
        start.await();
        try {
            visitOperations.schedule(actorId, sessionId, scheduleCommand(expectedSessionVersion, groundExecutiveId));
            return true;
        } catch (VisitOperationsConflictException | AccessDeniedException | EntityNotFoundException expected) {
            return false;
        }
    }

    private boolean attemptLink(CountDownLatch ready, CountDownLatch start, Long actorId,
            Long requestId, Long expectedRequestVersion, Long sessionId, Long expectedSessionVersion) throws Exception {
        ready.countDown();
        start.await();
        try {
            visitOperations.coordinateRequest(actorId, requestId,
                    new com.indore.pathome.spaces.dto.CoordinateVisitRequestCommand(
                            expectedRequestVersion, sessionId, expectedSessionVersion));
            return true;
        } catch (VisitOperationsConflictException | AccessDeniedException | EntityNotFoundException expected) {
            return false;
        }
    }

    private boolean attemptGroundExecutiveAssignment(CountDownLatch ready, CountDownLatch start, Long actorId,
            Long sessionId, Long expectedSessionVersion, Long groundExecutiveId) throws Exception {
        ready.countDown();
        start.await();
        try {
            visitOperations.assignGroundExecutive(actorId, sessionId,
                    new com.indore.pathome.spaces.dto.AssignGroundExecutiveCommand(expectedSessionVersion, groundExecutiveId));
            return true;
        } catch (VisitOperationsConflictException | AccessDeniedException | EntityNotFoundException expected) {
            return false;
        }
    }

    private boolean attemptRelease(CountDownLatch ready, CountDownLatch start, Long actorId,
            Long sessionId, Long expectedSessionVersion, Long requestId, Long expectedRequestVersion) throws Exception {
        ready.countDown();
        start.await();
        try {
            ownership.releaseCoordinator(actorId, sessionId, expectedSessionVersion,
                    Map.of(requestId, expectedRequestVersion), "RELEASE_VISIT_RACE");
            return true;
        } catch (VisitOperationsConflictException | AccessDeniedException | EntityNotFoundException expected) {
            return false;
        }
    }

    private boolean attemptTeamDeactivation(CountDownLatch ready, CountDownLatch start, Long actorId,
            Long teamId, Long cityVersion, Long teamVersion) throws Exception {
        ready.countDown();
        start.await();
        try {
            ownership.deactivateTeam(actorId, teamId, cityVersion, teamVersion, "TEAM_DEACTIVATION_RACE");
            return true;
        } catch (VisitOperationsConflictException | AccessDeniedException expected) {
            return false;
        }
    }

    private boolean attemptCityDeactivation(CountDownLatch ready, CountDownLatch start, Long actorId,
            Long cityId, Long cityVersion) throws Exception {
        ready.countDown();
        start.await();
        try {
            ownership.deactivateCity(actorId, cityId, cityVersion, "CITY_DEACTIVATION_RACE");
            return true;
        } catch (VisitOperationsConflictException | AccessDeniedException expected) {
            return false;
        }
    }

    private boolean attemptCreateCityIntakeGrant(CountDownLatch ready, CountDownLatch start,
            Long actorId, Long targetId, Long cityId) throws Exception {
        ready.countDown();
        start.await();
        try {
            administration.createGrant(actorId, targetId, grant(StaffCapability.OPS_INTAKE,
                    StaffScopeType.CITY, cityId, null, null, null, "CITY_DEACTIVATION_RACE_INTAKE"));
            return true;
        } catch (IllegalArgumentException | StaffAccessConflictException expected) {
            return false;
        }
    }

    private boolean attemptCreateTeamCoordinatorGrant(CountDownLatch ready, CountDownLatch start,
            Long actorId, Long targetId, Long teamId) throws Exception {
        ready.countDown();
        start.await();
        try {
            administration.createGrant(actorId, targetId, grant(StaffCapability.OPS_COORDINATE,
                    StaffScopeType.TEAM, null, teamId, null, null, "TEAM_DEACTIVATION_RACE_COORDINATION"));
            return true;
        } catch (IllegalArgumentException | StaffAccessConflictException expected) {
            return false;
        }
    }

    private boolean attemptTrustedIntake(CountDownLatch ready, CountDownLatch start,
            PropertyVisitRequest request) throws Exception {
        ready.countDown();
        start.await();
        operationalIntake.admitAndSave(request);
        return true;
    }

    private boolean attemptReopen(CountDownLatch ready, CountDownLatch start, Long actorId,
            Long sessionId, Long expectedVersion) throws Exception {
        ready.countDown();
        start.await();
        visitRepairs.reopen(actorId, sessionId,
                new com.indore.pathome.spaces.dto.ExpectedVisitSessionVersion(expectedVersion));
        return true;
    }

    private OwnershipFixture createSchedulableOwnershipFixture(User tenant, Locality locality, SupportedCity city,
            OperatingTeam team, User coordinator, User confirmer) {
        User groundExecutive = saveUser("phase1c-fixture-ge-" + UUID.randomUUID() + "@example.test", Role.ROLE_GROUND_BOY);
        employeeProfiles.saveAndFlush(new EmployeeProfile(groundExecutive, "GROUND_BOY", null, null));
        Listing sourceListing = listing("ownership-race", locality);
        VisitSession session = new VisitSession();
        session.setTenant(tenant);
        session.setCity(city.getDisplayName());
        session.setCanonicalLocality(locality);
        session.setSupportedCity(city);
        session.setOperatingTeam(team);
        session.setCoordinator(coordinator);
        session.setOperationalScopeReady(true);
        session = visitSessions.saveAndFlush(session);

        PropertyVisitRequest sourceRequest = request(tenant, sourceListing, session, VisitRequestStatus.COORDINATING);
        sourceRequest.setSupportedCity(city);
        sourceRequest.setOperatingTeam(team);
        sourceRequest.setCoordinator(coordinator);
        sourceRequest.setOperationalScopeReady(true);
        sourceRequest = requests.saveAndFlush(sourceRequest);

        Instant confirmedAt = Instant.now();
        com.indore.pathome.spaces.entity.VisitSessionItem item = new com.indore.pathome.spaces.entity.VisitSessionItem();
        item.setSession(session);
        item.setListing(sourceListing);
        item.setPosition(1);
        item.setSourceRequest(sourceRequest);
        item.setOrigin(com.indore.pathome.spaces.entity.VisitSessionItemOrigin.TENANT_REQUESTED);
        item.setConfirmationStatus(com.indore.pathome.spaces.entity.VisitSessionItemConfirmationStatus.CONFIRMED);
        item.setAvailabilityConfirmedAt(confirmedAt);
        item.setConfirmedBy(confirmer);
        item.setAvailabilityStartAt(confirmedAt.plusSeconds(1800));
        item.setAvailabilityEndAt(confirmedAt.plusSeconds(28800));
        item.setAvailabilityZoneId("Asia/Kolkata");
        item.setAvailabilitySource(com.indore.pathome.spaces.entity.PropertyAvailabilitySource.DIRECT);
        visitSessionItems.saveAndFlush(item);
        return new OwnershipFixture(session, sourceRequest, groundExecutive);
    }

    private com.indore.pathome.spaces.dto.ScheduleVisitSessionCommand scheduleCommand(
            Long expectedSessionVersion, Long groundExecutiveId) {
        return new com.indore.pathome.spaces.dto.ScheduleVisitSessionCommand(expectedSessionVersion,
                Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MINUTES),
                "Asia/Kolkata", groundExecutiveId, 60);
    }

    private record OwnershipFixture(VisitSession session, PropertyVisitRequest request, User groundExecutive) {}

    private boolean attemptAssignCoordinator(CountDownLatch ready, CountDownLatch start, Long actorId,
            Long sessionId, Long coordinatorId) throws Exception {
        ready.countDown();
        start.await();
        try {
            ownership.assignCoordinator(actorId, sessionId, 0L, Map.of(), coordinatorId, "ASSIGNMENT_RACE_TEST");
            return true;
        } catch (VisitOperationsConflictException | AccessDeniedException | EntityNotFoundException expected) {
            return false;
        }
    }

    private boolean attemptRevokeGrant(CountDownLatch ready, CountDownLatch start, Long actorId,
                                       Long grantId) throws Exception {
        ready.countDown();
        start.await();
        try {
            administration.revokeGrant(actorId, grantId, "REVOKE_RACE_TEST");
            return true;
        } catch (AccessDeniedException | StaffAccessConflictException expected) {
            return false;
        }
    }

    private boolean attemptConcurrentRevoke(CountDownLatch ready, CountDownLatch start, Long actorId,
                                           Long grantId, String reason) throws Exception {
        ready.countDown();
        start.await();
        try {
            administration.revokeGrant(actorId, grantId, reason);
            return true;
        } catch (AccessDeniedException | StaffAccessConflictException expected) {
            return false;
        }
    }

    private PropertyVisitRequest request(User tenant, Listing listing) {
        return request(tenant, listing, null, VisitRequestStatus.RECEIVED);
    }

    private PropertyVisitRequest request(User tenant, Listing listing, VisitSession session,
            VisitRequestStatus status) {
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(listing);
        request.setSession(session);
        request.setStatus(status);
        request.setVersion(null);
        return request;
    }

    private Listing listing(String suffix, Locality locality) {
        RentalDetails listing = new RentalDetails();
        listing.setTitle("Phase 1C " + suffix + " " + UUID.randomUUID());
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setAddress("Phase 1C test address");
        listing.setSector(locality == null ? "Unresolved" : locality.getSectorName());
        listing.setCity(locality == null ? "Unresolved" : locality.getCity());
        listing.setCanonicalLocalityId(locality == null ? null : locality.getId());
        listing.setBhkCount("1BHK");
        listing.setMonthlyRent(java.math.BigDecimal.ONE);
        listing.setSecurityDeposit(java.math.BigDecimal.ZERO);
        return listings.saveAndFlush(listing);
    }

    private static Map<Long, Long> requestVersions(PropertyVisitRequest... requests) {
        Map<Long, Long> versions = new java.util.LinkedHashMap<>();
        for (PropertyVisitRequest request : requests) versions.put(request.getId(), request.getVersion());
        return Map.copyOf(versions);
    }

    private User saveUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setRole(role);
        user.setFreeVisitsRemaining(0);
        return users.saveAndFlush(user);
    }

    private static StaffGrantCommand grant(StaffCapability capability, StaffScopeType scopeType,
                                           Long cityId, Long teamId, Instant effectiveAt,
                                           Instant expiresAt, String reasonCode) {
        return new StaffGrantCommand(capability, scopeType, cityId, teamId, effectiveAt, expiresAt, reasonCode);
    }

    private int countAuditAction(String actionCode) {
        return jdbc.queryForObject("SELECT count(*) FROM operational_audit_events WHERE action_code=?",
                Integer.class, actionCode);
    }

    private int countActionForTarget(String actionCode, Long targetId) {
        return jdbc.queryForObject("SELECT count(*) FROM operational_audit_events "
                + "WHERE action_code=? AND target_id=?", Integer.class, actionCode, targetId);
    }

    private static void createSchema() {
        try (Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
            connection.setSchema(SCHEMA);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/baseline/pathome-v1-legacy-core.sql"));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create isolated PostgreSQL test schema", error);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        PasswordEncoder testPasswordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }
}
