package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.CreateOperatingTeamCommand;
import com.indore.pathome.spaces.dto.OperatingTeamProvisioningCityPage;
import com.indore.pathome.spaces.dto.OperatingTeamProvisioningView;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.OperatingTeamProvisioningUnavailableException;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.QueryTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Package 3A: command shape, authorization ordering, lock ordering, stale-version and duplicate-code
 * behaviour of governed Team creation, decided before any database work is allowed to happen.
 */
@ExtendWith(MockitoExtension.class)
class OperatingTeamProvisioningServiceTest {
    private static final long ACTOR_ID = 41L;
    private static final long CITY_ID = 7L;

    @Mock private SupportedCityRepository cities;
    @Mock private OperatingTeamRepository teams;
    @Mock private StaffAccessService access;
    @Mock private StaffGovernanceLock governanceLock;
    @Mock private OperationalSecurityGuards guards;
    @Mock private OperationalAuditService audit;
    @Mock private EntityManager entityManager;
    @Mock private TransactionalCommandBounds commandBounds;

    private OperatingTeamProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new OperatingTeamProvisioningService(cities, teams, access, governanceLock, guards, audit,
                entityManager, commandBounds);
    }

    @Test
    void malformedCommandsAreRejectedBeforeAnyAuthorityOrLockWork() {
        List<CreateOperatingTeamCommand> rejected = List.of(
                command("TEAM-A", "Team A", 0L, "TEAM_ONBOARDING"),
                command("team-a", " ", 0L, "TEAM_ONBOARDING"),
                command("team-a", "Team A", null, "TEAM_ONBOARDING"),
                command("team-a", "Team A", -1L, "TEAM_ONBOARDING"),
                command("team-a", "Team A", 0L, "team_onboarding"),
                command("team-a", "Team\u0007A", 0L, "TEAM_ONBOARDING"),
                command("team_a", "Team A", 0L, "TEAM_ONBOARDING"),
                command("-team-a", "Team A", 0L, "TEAM_ONBOARDING"),
                command("a".repeat(65), "Team A", 0L, "TEAM_ONBOARDING"),
                command("team-a", "a".repeat(161), 0L, "TEAM_ONBOARDING"));

        for (CreateOperatingTeamCommand command : rejected) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createTeam(ACTOR_ID, CITY_ID, command), command.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> service.createTeam(ACTOR_ID, CITY_ID, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.createTeam(ACTOR_ID, 0L, command("team-a", "Team A", 0L, "TEAM_ONBOARDING")));

        verifyNoInteractions(access, governanceLock, guards, teams, audit, entityManager);
        verifyNoInteractions(commandBounds);
    }

    @Test
    void boundingTheCommandTransactionPrecedesEveryBlockingGuard() {
        SupportedCity city = city(true);
        doReturn(new User()).when(access).requireGlobalAdmin(ACTOR_ID);
        when(cities.findLockedById(CITY_ID)).thenReturn(Optional.of(city));
        when(guards.cityVersion(CITY_ID)).thenReturn(4L);
        when(guards.cityIsActive(CITY_ID)).thenReturn(true);
        when(teams.findByCityIdAndCode(CITY_ID, "team-a")).thenReturn(Optional.empty());
        when(teams.saveAndFlush(any(OperatingTeam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createTeam(ACTOR_ID, CITY_ID, command("team-a", "Team A", 4L, "TEAM_ONBOARDING"));

        // The transaction-local lock/statement bounds are established on the command's connection before
        // the advisory governance lock and the City/actor row guards are acquired.
        InOrder order = inOrder(commandBounds, governanceLock, guards);
        order.verify(commandBounds).apply();
        order.verify(governanceLock).acquire();
        order.verify(guards).acquire(anyCollection(), anyCollection(), anyCollection());
    }

    @Test
    void boundedWaitFailuresBecomeTheSanitizedUnavailableCommandFailure() {
        when(access.requireGlobalAdmin(ACTOR_ID)).thenReturn(new User());
        doThrow(new CannotAcquireLockException("could not obtain lock",
                new SQLException("canceling statement due to lock timeout", "55P03")))
                .when(governanceLock).acquire();

        OperatingTeamProvisioningUnavailableException failure = assertThrows(
                OperatingTeamProvisioningUnavailableException.class,
                () -> service.createTeam(ACTOR_ID, CITY_ID, command("team-a", "Team A", 0L, "TEAM_ONBOARDING")));

        assertEquals("The Team could not be created because the database was busy. Please try again shortly.",
                failure.getMessage());
        verify(teams, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void everyOtherFailureKeepsItsOwnMeaning() {
        when(access.requireGlobalAdmin(ACTOR_ID))
                .thenThrow(new AccessDeniedException("Current global Staff Admin authority is required"));
        assertThrows(AccessDeniedException.class, () -> service.createTeam(ACTOR_ID, CITY_ID,
                command("team-a", "Team A", 0L, "TEAM_ONBOARDING")));

        doReturn(new User()).when(access).requireGlobalAdmin(ACTOR_ID);
        when(cities.findLockedById(CITY_ID)).thenReturn(Optional.of(city(true)));
        when(guards.cityVersion(CITY_ID)).thenReturn(4L);
        when(guards.cityIsActive(CITY_ID)).thenReturn(true);
        when(teams.findByCityIdAndCode(CITY_ID, "team-a")).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint",
                new SQLException("duplicate key value violates unique constraint", "23505")))
                .when(teams).saveAndFlush(any(OperatingTeam.class));
        assertThrows(DataIntegrityViolationException.class, () -> service.createTeam(ACTOR_ID, CITY_ID,
                command("team-a", "Team A", 4L, "TEAM_ONBOARDING")));

        when(teams.findByCityIdAndCode(CITY_ID, "team-a"))
                .thenReturn(Optional.of(new OperatingTeam(city(true), "team-a", "Retained", false)));
        assertThrows(VisitOperationsConflictException.class, () -> service.createTeam(ACTOR_ID, CITY_ID,
                command("team-a", "Team A", 4L, "TEAM_ONBOARDING")));
    }

    /**
     * Requirement 7: an audit failure is not a bounded wait. It keeps its own meaning so the existing
     * sanitized error handling - and the whole-transaction rollback - stay exactly as they were.
     */
    @Test
    void anAuditFailureIsNotDressedUpAsABoundedWait() {
        SupportedCity city = city(true);
        when(access.requireGlobalAdmin(ACTOR_ID)).thenReturn(new User());
        when(cities.findLockedById(CITY_ID)).thenReturn(Optional.of(city));
        when(guards.cityVersion(CITY_ID)).thenReturn(4L);
        when(guards.cityIsActive(CITY_ID)).thenReturn(true);
        when(teams.findByCityIdAndCode(CITY_ID, "team-a")).thenReturn(Optional.empty());
        when(teams.saveAndFlush(any(OperatingTeam.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("P3A_TEST_AUDIT_FAILURE"))
                .when(audit).recordUserEvent(any(), any(), any(), any(), any(), any(), any(), any());

        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.createTeam(ACTOR_ID,
                CITY_ID, command("team-a", "Team A", 4L, "TEAM_ONBOARDING")));

        assertFalse(failure instanceof OperatingTeamProvisioningUnavailableException,
                "an audit failure must not become a 503");
        assertEquals("P3A_TEST_AUDIT_FAILURE", failure.getMessage());
    }

    @Test
    void conflictsAreNeverTranslatedIntoATransientDatabaseFailure() {
        VisitOperationsConflictException conflict =
                new VisitOperationsConflictException("City version is stale; refresh and retry");
        assertSame(conflict, OperatingTeamProvisioningUnavailableException.translate(conflict));
        assertFalse(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(conflict));
    }

    /**
     * Requirement 1, a negative control that fails under the superseded classifier: a bare
     * pessimistic-lock failure carries no SQLSTATE, yet the previous class-name match answered "true"
     * and turned any lock error into a 503 "database was busy". Class alone is not evidence of a bounded
     * wait, so each of these must keep its own meaning and reach the existing sanitized handling.
     */
    @Test
    void barePessimisticLockFailuresWithoutSqlStateAreNotBoundedTimeouts() {
        List<RuntimeException> unprovenFailures = List.of(
                new PessimisticLockException("pessimistic lock failure"),
                new CannotAcquireLockException("could not obtain lock"),
                new org.springframework.dao.PessimisticLockingFailureException("could not execute statement"),
                new PessimisticLockException("could not execute statement",
                        new org.hibernate.PessimisticLockException("could not execute statement",
                                new SQLException("deadlock detected", "40P01"), "for update")));

        for (RuntimeException failure : unprovenFailures) {
            assertFalse(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(failure),
                    failure.getClass().getName() + " must not be classified as a bounded wait");
            assertSame(failure, OperatingTeamProvisioningUnavailableException.translate(failure),
                    "an unproven failure must be passed through untouched");
        }
    }

    /** Requirement 2: genuine PostgreSQL {@code 55P03} lock-timeout evidence, at every wrapper depth. */
    @Test
    void genuinePostgresLockTimeoutEvidenceIsClassified() {
        SQLException lockTimeout = new SQLException("canceling statement due to lock timeout", "55P03");
        List<RuntimeException> boundedWaits = List.of(
                new RuntimeException(lockTimeout),
                new PessimisticLockException("could not execute statement", lockTimeout),
                // The shape the governed command really produces: Spring translation -> JPA -> Hibernate
                // -> PostgreSQL driver, with the SQLSTATE preserved all the way down.
                new org.springframework.dao.PessimisticLockingFailureException("could not execute statement",
                        new PessimisticLockException("could not execute statement",
                                new org.hibernate.PessimisticLockException("could not execute statement",
                                        lockTimeout, "select id from supported_cities for update"))));

        for (RuntimeException failure : boundedWaits) {
            assertTrue(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(failure),
                    failure.getClass().getName() + " carries 55P03 evidence and is a bounded wait");
            assertTrue(OperatingTeamProvisioningUnavailableException.translate(failure)
                            instanceof OperatingTeamProvisioningUnavailableException,
                    "a bounded wait becomes the sanitized 503 translation");
        }
    }

    /** Requirement 3: genuine {@code 57014} statement-timeout evidence, and the driver-only shape. */
    @Test
    void genuineStatementTimeoutEvidenceIsClassified() {
        SQLException statementTimeout =
                new SQLException("canceling statement due to statement timeout", "57014");

        assertTrue(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(
                new RuntimeException(statementTimeout)));
        // Hibernate's PostgreSQL dialect routes every 57014 through QueryTimeoutException; the SQLSTATE
        // and its timeout wording are what make this a bounded statement wait.
        assertTrue(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(
                new org.hibernate.QueryTimeoutException("could not execute statement", statementTimeout,
                        "insert into operating_teams (code) values ('team-a')")));
        assertTrue(OperatingTeamProvisioningUnavailableException.translate(
                new RuntimeException(statementTimeout)) instanceof OperatingTeamProvisioningUnavailableException);

        // The wrapper-only fallback exists for the one shape where a bounded wait arrives with no SQLSTATE.
        assertTrue(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(
                new SQLTimeoutException("A statement timed out")));
        assertTrue(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(
                new QueryTimeoutException("query timed out")));
        assertTrue(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(
                new LockTimeoutException("lock timeout")));
    }

    /**
     * Requirement 4, a second negative control that fails under the superseded classifier: PostgreSQL
     * reports {@code 57014} for a {@code statement_timeout} <em>and</em> for {@code pg_cancel_backend} or
     * an abandoned statement, and Hibernate funnels all of them into {@code QueryTimeoutException}. The
     * class-name match therefore reported a client cancellation as a busy database; only timeout wording
     * may decide it. Serialization failures, deadlocks, duplicate keys and unrelated states stay on the
     * existing sanitized path too.
     */
    @Test
    void unrelatedSqlStatesAndUserCancellationsAreNotBoundedTimeouts() {
        SQLException userRequest = new SQLException("canceling statement due to user request", "57014");
        List<RuntimeException> notBoundedWaits = List.of(
                new org.springframework.dao.QueryTimeoutException("could not execute statement", userRequest),
                new org.hibernate.QueryTimeoutException("could not execute statement", userRequest, "select 1"),
                new CannotAcquireLockException("could not serialize access",
                        new SQLException("could not serialize access due to concurrent update", "40001")),
                new org.springframework.dao.PessimisticLockingFailureException("deadlock detected",
                        new SQLException("deadlock detected", "40P01")),
                new DataIntegrityViolationException("duplicate key",
                        new SQLException("duplicate key value violates unique constraint", "23505")),
                new jakarta.persistence.PersistenceException("could not execute statement",
                        new SQLException("unrecognized configuration parameter", "42704")));

        for (RuntimeException failure : notBoundedWaits) {
            assertFalse(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(failure),
                    failure.getClass().getName() + " is not a bounded command wait");
            assertSame(failure, OperatingTeamProvisioningUnavailableException.translate(failure));
        }
    }

    /** Requirement 5: nested cause chains, {@code nextException} chains and cyclic chains. */
    @Test
    void nestedExceptionChainsAreClassifiedSafely() {
        SQLException chained = new SQLException("could not execute statement", "XX000");
        chained.setNextException(new SQLException("canceling statement due to lock timeout", "55P03"));
        assertTrue(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(
                new RuntimeException(new jakarta.persistence.PersistenceException("wrapped", chained))),
                "a SQLSTATE found through getNextException is evidence too");

        Throwable deeplyNested = new SQLException("canceling statement due to statement timeout", "57014");
        for (int layer = 0; layer < 20; layer++) {
            deeplyNested = new RuntimeException("layer " + layer, deeplyNested);
        }
        assertTrue(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(deeplyNested));

        RuntimeException selfCausing = new RuntimeException("cyclic") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(selfCausing),
                "a cyclic chain must terminate and prove nothing");

        SQLException selfLinked = new SQLException("cyclic", "55P03");
        selfLinked.setNextException(selfLinked);
        assertTrue(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(selfLinked),
                "a cyclic nextException chain must terminate at its own SQLSTATE evidence");
    }

    /** Requirement 7: stale versions and denied authority translate to themselves, never to a 503. */
    @Test
    void staleVersionsAndDeniedAuthorityKeepTheirOwnMeaning() {
        AccessDeniedException denied =
                new AccessDeniedException("Current global Staff Admin authority is required");
        assertFalse(OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(denied));
        assertSame(denied, OperatingTeamProvisioningUnavailableException.translate(denied));
    }

    @Test
    void creationRequiresCurrentGlobalAdminAuthorityBeforeAnyLockOrWrite() {
        when(access.requireGlobalAdmin(ACTOR_ID))
                .thenThrow(new AccessDeniedException("Current global Staff Admin authority is required"));

        assertThrows(AccessDeniedException.class, () -> service.createTeam(ACTOR_ID, CITY_ID,
                command("team-a", "Team A", 0L, "TEAM_ONBOARDING")));

        verify(governanceLock, never()).acquire();
        verify(guards, never()).acquire(anyCollection(), anyCollection(), anyCollection());
        verify(teams, never()).saveAndFlush(any());
    }

    @Test
    void unknownCityIsNotFoundAndNoTeamIsWritten() {
        when(access.requireGlobalAdmin(ACTOR_ID)).thenReturn(new User());
        when(cities.findLockedById(CITY_ID)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.createTeam(ACTOR_ID, CITY_ID,
                command("team-a", "Team A", 0L, "TEAM_ONBOARDING")));

        verify(teams, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void staleCityVersionAndInactiveCityConflictWithoutWriting() {
        when(access.requireGlobalAdmin(ACTOR_ID)).thenReturn(new User());
        when(cities.findLockedById(CITY_ID)).thenReturn(Optional.of(city(true)));
        when(guards.cityVersion(CITY_ID)).thenReturn(4L);

        assertThrows(VisitOperationsConflictException.class, () -> service.createTeam(ACTOR_ID, CITY_ID,
                command("team-a", "Team A", 3L, "TEAM_ONBOARDING")));

        when(guards.cityVersion(CITY_ID)).thenReturn(4L);
        when(guards.cityIsActive(CITY_ID)).thenReturn(false);
        assertThrows(VisitOperationsConflictException.class, () -> service.createTeam(ACTOR_ID, CITY_ID,
                command("team-a", "Team A", 4L, "TEAM_ONBOARDING")));

        verify(teams, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void retainedCityCodeConflictsWithoutCreatingASecondTeam() {
        when(access.requireGlobalAdmin(ACTOR_ID)).thenReturn(new User());
        when(cities.findLockedById(CITY_ID)).thenReturn(Optional.of(city(true)));
        when(guards.cityVersion(CITY_ID)).thenReturn(4L);
        when(guards.cityIsActive(CITY_ID)).thenReturn(true);
        when(teams.findByCityIdAndCode(CITY_ID, "team-a"))
                .thenReturn(Optional.of(new OperatingTeam(city(true), "team-a", "Retained", false)));

        assertThrows(VisitOperationsConflictException.class, () -> service.createTeam(ACTOR_ID, CITY_ID,
                command(" team-a ", "Team A", 4L, "TEAM_ONBOARDING")));

        verify(teams, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void successfulCreationTrimsInputAndAuditsInsideTheGovernedLockOrder() {
        SupportedCity city = city(true);
        User actor = new User();
        when(access.requireGlobalAdmin(ACTOR_ID)).thenReturn(actor);
        when(cities.findLockedById(CITY_ID)).thenReturn(Optional.of(city));
        when(guards.cityVersion(CITY_ID)).thenReturn(4L);
        when(guards.cityIsActive(CITY_ID)).thenReturn(true);
        when(teams.findByCityIdAndCode(CITY_ID, "team-a")).thenReturn(Optional.empty());
        when(teams.saveAndFlush(any(OperatingTeam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OperatingTeamProvisioningView view = service.createTeam(ACTOR_ID, CITY_ID,
                command("  team-a ", "  Team A  ", 4L, "TEAM_ONBOARDING"));

        ArgumentCaptor<OperatingTeam> persisted = ArgumentCaptor.forClass(OperatingTeam.class);
        verify(teams).saveAndFlush(persisted.capture());
        assertEquals("team-a", persisted.getValue().getCode());
        assertEquals("Team A", persisted.getValue().getDisplayName());
        assertTrue(persisted.getValue().isActive());
        assertEquals("team-a", view.code());
        assertTrue(view.active());
        assertEquals(0L, view.version().longValue());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordUserEvent(eq(actor), eq("OPERATING_TEAM_CREATED"), eq("OPERATING_TEAM"),
                any(), eq("TEAM_ONBOARDING"), eq(city), any(OperatingTeam.class), details.capture());
        assertEquals(CITY_ID, details.getValue().get("cityId"));
        assertEquals(4L, details.getValue().get("expectedCityVersion"));

        InOrder order = inOrder(governanceLock, guards, audit);
        order.verify(governanceLock).acquire();
        order.verify(guards).acquire(anyCollection(), anyCollection(), anyCollection());
        order.verify(audit).recordUserEvent(any(), any(), any(), any(), any(), any(), any(), any());
        verify(entityManager).flush();
    }

    @Test
    void readRoutesRequireAuthorityAndBoundEveryPage() {
        doThrow(new AccessDeniedException("Current global Staff Admin authority is required"))
                .when(access).requireGlobalAdmin(ACTOR_ID);
        assertThrows(AccessDeniedException.class, () -> service.listActiveCities(ACTOR_ID, 0, 20));
        assertThrows(AccessDeniedException.class, () -> service.listCityTeams(ACTOR_ID, CITY_ID, 0, 20));

        doReturn(new User()).when(access).requireGlobalAdmin(ACTOR_ID);
        when(cities.findActiveCityPage(any())).thenReturn(new PageImpl<>(List.of(city(true)),
                PageRequest.of(0, 20), 1));
        OperatingTeamProvisioningCityPage page = service.listActiveCities(ACTOR_ID, 0, 20);
        assertEquals(1, page.items().size());
        assertEquals(1L, page.totalCount());
        assertEquals(1L, page.totalPages());

        assertThrows(IllegalArgumentException.class, () -> service.listActiveCities(ACTOR_ID, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> service.listActiveCities(ACTOR_ID, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.listActiveCities(ACTOR_ID, 0, 101));
        assertThrows(IllegalArgumentException.class, () -> service.listCityTeams(ACTOR_ID, 0L, 0, 20));
        when(cities.findById(CITY_ID)).thenReturn(Optional.empty());
        assertThrows(EntityNotFoundException.class, () -> service.listCityTeams(ACTOR_ID, CITY_ID, 0, 20));
        verify(teams, never()).findTeamPageByCityId(anyLong(), any());
    }

    private static CreateOperatingTeamCommand command(String code, String displayName, Long expectedCityVersion,
            String reasonCode) {
        return new CreateOperatingTeamCommand(code, displayName, expectedCityVersion, reasonCode);
    }

    private static SupportedCity city(boolean active) {
        return new SupportedCity("city-" + CITY_ID, "City", active);
    }
}
