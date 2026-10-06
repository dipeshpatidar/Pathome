package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.StaffCapabilitiesResponse;
import com.indore.pathome.spaces.dto.StaffCapabilitySummary;
import com.indore.pathome.spaces.dto.StaffGrantCommand;
import com.indore.pathome.spaces.dto.StaffGrantView;
import com.indore.pathome.spaces.dto.StaffStateCommand;
import com.indore.pathome.spaces.dto.StaffUserAccessState;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.StaffAccessConflictException;
import com.indore.pathome.spaces.exception.StaffAccessConflictException;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.OperationalAuditEventRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.controller.StaffCapabilitiesController;
import com.indore.pathome.spaces.service.VisitOperationsAuthorizationService;
import jakarta.persistence.EntityManager;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.mockito.ArgumentMatchers.any;
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
        VisitOperationsAuthorizationService.class, StaffAccessGovernancePostgresTest.TestBeans.class})
class StaffAccessGovernancePostgresTest {
    private static final String SCHEMA = "pathome_staff_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final String DB_URL = System.getenv().getOrDefault(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/pathome_db");
    private static final String DB_USER = System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "pathome");
    private static final String DB_PASSWORD = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");

    @SpyBean private UserRepository users;
    @Autowired private EmployeeProfileRepository employeeProfiles;
    @SpyBean private StaffAccessGrantRepository grants;
    @Autowired private SupportedCityRepository cities;
    @Autowired private OperatingTeamRepository teams;
    @Autowired private OperationalAuditEventRepository audits;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private StaffAccessService access;
    @Autowired private StaffAdministrationService administration;
    @Autowired private StaffAdminBootstrapService bootstrap;
    @Autowired private VisitOperationsAuthorizationService visitOperationsAuthorization;
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
