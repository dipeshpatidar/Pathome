package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Package 1C mutation commands. No broad OE HTTP read or mutation route is exposed here. */
@Service
public class OperationalOwnershipService {
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final List<VisitRequestStatus> BLOCKING_REQUESTS = List.of(
            VisitRequestStatus.RECEIVED, VisitRequestStatus.COORDINATING, VisitRequestStatus.SCHEDULED);
    private static final List<VisitSessionStatus> BLOCKING_SESSIONS = List.of(
            VisitSessionStatus.DRAFT, VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED,
            VisitSessionStatus.PROVISIONAL_NO_SHOW, VisitSessionStatus.REPAIR_REQUIRED,
            VisitSessionStatus.INTERRUPTED);
    private static final String SESSION_BLOCKING_SQL = sqlEnumList(BLOCKING_SESSIONS);
    private static final String REQUEST_BLOCKING_SQL = sqlEnumList(BLOCKING_REQUESTS);

    private final PropertyVisitRequestRepository requests;
    private final VisitSessionRepository sessions;
    private final SupportedCityRepository cities;
    private final OperatingTeamRepository teams;
    private final UserRepository users;
    private final StaffAccessService access;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final TransactionTemplate requestRootTransactions;

    public OperationalOwnershipService(PropertyVisitRequestRepository requests, VisitSessionRepository sessions,
            SupportedCityRepository cities, OperatingTeamRepository teams, UserRepository users,
            StaffAccessService access, OperationalSecurityGuards guards, OperationalAuditService audit,
            JdbcTemplate jdbc, EntityManager entityManager, PlatformTransactionManager transactionManager) {
        this.requests = requests;
        this.sessions = sessions;
        this.cities = cities;
        this.teams = teams;
        this.users = users;
        this.access = access;
        this.guards = guards;
        this.audit = audit;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(transactionManager);
        this.requestRootTransactions = new TransactionTemplate(transactionManager);
        this.requestRootTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void claimRequest(Long actorId, Long requestId, Long expectedRequestVersion,
            Long expectedSessionVersion, Map<Long, Long> expectedRequestVersions, String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        Candidate candidate = requestCandidate(requestId);
        requireCandidateTeamAuthority(actorId, candidate, StaffCapability.OPS_COORDINATE);
        runRequestRoot(requestId, root -> {
            Long teamId = teamId(root);
            if (teamId == null) throw new EntityNotFoundException("Operational work not found");
            lockGuards(root, actorId, null);
            requireInitialVisibility(actorId, teamId);
            requireFresh(actorId, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId);
            requireClaimVersions(root, actorId, expectedSessionVersion, expectedRequestVersion, expectedRequestVersions);
            requireOperationalWork(root);
            if (teamId == null || !ready(root)) throw new VisitOperationsConflictException("Only ready Team work can be claimed");
            requireActiveScope(root);
            if (coordinatorId(root) != null) {
                hideOrConflict(actorId, teamId);
            }
            requireClaimFresh(actorId, teamId);
            setCoordinator(root, users.getReferenceById(actorId));
            flushAndAudit(root, actorId, "OWNERSHIP_CLAIMED", reasonCode, Map.of(
                    "coordinatorUserId", actorId, "operationalScopeReady", true));
            return null;
        });
    }

    public void claimSession(Long actorId, Long sessionId, Long expectedSessionVersion,
            Map<Long, Long> expectedRequestVersions, String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        Candidate candidate = sessionCandidate(sessionId);
        requireCandidateTeamAuthority(actorId, candidate, StaffCapability.OPS_COORDINATE);
        inTransaction(() -> {
            Root root = lockSessionRoot(sessionId);
            Long teamId = teamId(root);
            if (teamId == null) throw new EntityNotFoundException("Operational work not found");
            lockGuards(root, actorId, null);
            requireInitialVisibility(actorId, teamId);
            requireFresh(actorId, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId);
            requireClaimVersions(root, actorId, expectedSessionVersion, null, expectedRequestVersions);
            requireOperationalWork(root);
            if (teamId == null || !ready(root)) throw new VisitOperationsConflictException("Only ready Team work can be claimed");
            requireActiveScope(root);
            if (coordinatorId(root) != null) hideOrConflict(actorId, teamId);
            requireClaimFresh(actorId, teamId);
            setCoordinator(root, users.getReferenceById(actorId));
            flushAndAudit(root, actorId, "OWNERSHIP_CLAIMED", reasonCode, Map.of(
                    "coordinatorUserId", actorId, "operationalScopeReady", true));
            return null;
        });
    }

    public void assignRequestCoordinator(Long actorId, Long requestId, Long expectedRequestVersion,
            Long expectedSessionVersion, Map<Long, Long> expectedRequestVersions,
            Long targetCoordinatorId, String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        if (targetCoordinatorId == null || targetCoordinatorId <= 0) throw new IllegalArgumentException("Coordinator User ID is required");
        Candidate candidate = requestCandidate(requestId);
        requireCandidateTeamAuthority(actorId, candidate, StaffCapability.OPS_SUPERVISE);
        runRequestRoot(requestId, root -> {
            Long teamId = teamId(root);
            if (teamId == null) throw new EntityNotFoundException("Operational work not found");
            lockGuards(root, actorId, targetCoordinatorId);
            requireInitialVisibility(actorId, teamId);
            requireFresh(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, teamId);
            requireRootVersions(root, expectedSessionVersion, expectedRequestVersion, expectedRequestVersions);
            requireOperationalWork(root);
            if (teamId == null || !ready(root)) throw new VisitOperationsConflictException("Only ready Team work can be assigned");
            requireActiveScope(root);
            requireCoordinatorTarget(targetCoordinatorId, teamId);
            if (Objects.equals(coordinatorId(root), targetCoordinatorId)) return null;
            setCoordinator(root, users.getReferenceById(targetCoordinatorId));
            flushAndAudit(root, actorId, "OWNERSHIP_ASSIGNED", reasonCode, Map.of(
                    "coordinatorUserId", targetCoordinatorId, "operationalScopeReady", true));
            return null;
        });
    }

    public void assignCoordinator(Long actorId, Long sessionId, Long expectedSessionVersion,
            Map<Long, Long> expectedRequestVersions, Long targetCoordinatorId, String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        if (targetCoordinatorId == null || targetCoordinatorId <= 0) throw new IllegalArgumentException("Coordinator User ID is required");
        Candidate candidate = sessionCandidate(sessionId);
        requireCandidateTeamAuthority(actorId, candidate, StaffCapability.OPS_SUPERVISE);
        inTransaction(() -> {
            Root root = lockSessionRoot(sessionId);
            Long teamId = teamId(root);
            if (teamId == null) throw new EntityNotFoundException("Operational work not found");
            lockGuards(root, actorId, targetCoordinatorId);
            requireInitialVisibility(actorId, teamId);
            requireFresh(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, teamId);
            requireRootVersions(root, expectedSessionVersion, null, expectedRequestVersions);
            requireOperationalWork(root);
            if (teamId == null || !ready(root)) throw new VisitOperationsConflictException("Only ready Team work can be assigned");
            requireActiveScope(root);
            requireCoordinatorTarget(targetCoordinatorId, teamId);
            if (Objects.equals(coordinatorId(root), targetCoordinatorId)) return null;
            setCoordinator(root, users.getReferenceById(targetCoordinatorId));
            flushAndAudit(root, actorId, "OWNERSHIP_ASSIGNED", reasonCode, Map.of(
                    "coordinatorUserId", targetCoordinatorId, "operationalScopeReady", true));
            return null;
        });
    }

    public void transferTeam(Long actorId, Long sessionId, Long expectedSessionVersion,
            Map<Long, Long> expectedRequestVersions, Long destinationTeamId, Long targetCoordinatorId,
            String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        if (destinationTeamId == null || destinationTeamId <= 0) throw new IllegalArgumentException("Destination Team ID is required");
        Candidate candidate = sessionCandidate(sessionId);
        preflightTransfer(actorId, candidate, destinationTeamId);
        inTransaction(() -> {
            Root root = lockSessionRoot(sessionId);
            Long sourceCityId = cityId(root);
            Long sourceTeamId = teamId(root);
            if (sourceCityId == null || sourceTeamId == null)
                throw new EntityNotFoundException("Operational work not found");
            OperatingTeam destination = teams.findById(destinationTeamId)
                    .orElseThrow(() -> new EntityNotFoundException("Destination Team not found"));
            SupportedCity destinationCity = destination.getCity();
            Long destinationCityId = destinationCity.getId();
            entityManager.detach(destinationCity);
            entityManager.detach(destination);
            guards.acquire(nonNull(sourceCityId, destinationCityId), nonNull(sourceTeamId, destinationTeamId),
                    nonNull(actorId, targetCoordinatorId));
            destination = teams.findLockedById(destinationTeamId)
                    .orElseThrow(() -> new EntityNotFoundException("Destination Team not found"));
            requireInitialVisibility(actorId, sourceTeamId);
            requireInitialVisibility(actorId, destinationTeamId);
            requireFresh(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, sourceTeamId);
            requireFresh(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, destinationTeamId);
            requireRootVersions(root, expectedSessionVersion, null, expectedRequestVersions);
            requireOperationalWork(root);
            if (!ready(root)) throw new VisitOperationsConflictException("Only ready Team work can be transferred");
            if (!sourceCityId.equals(destinationCityId))
                throw new VisitOperationsConflictException("Operational transfer cannot change the canonical City");
            requireActiveScope(root);
            if (!guards.teamIsActive(destinationTeamId) || !guards.cityIsActive(destinationCityId))
                throw new VisitOperationsConflictException("Destination scope is inactive");
            if (targetCoordinatorId != null) requireCoordinatorTarget(targetCoordinatorId, destinationTeamId);
            if (Objects.equals(sourceTeamId, destinationTeamId)
                    && Objects.equals(coordinatorId(root), targetCoordinatorId)) return null;
            setTeam(root, destination);
            setCoordinator(root, targetCoordinatorId == null ? null : users.getReferenceById(targetCoordinatorId));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sourceCityId", sourceCityId);
            details.put("destinationCityId", destinationCityId);
            details.put("sourceTeamId", sourceTeamId);
            details.put("destinationTeamId", destinationTeamId);
            if (targetCoordinatorId != null) details.put("coordinatorUserId", targetCoordinatorId);
            flushAndAudit(root, actorId, "OWNERSHIP_TRANSFERRED", reasonCode, details);
            return null;
        });
    }

    public void transferRequestTeam(Long actorId, Long requestId, Long expectedRequestVersion,
            Long expectedSessionVersion, Map<Long, Long> expectedRequestVersions, Long destinationTeamId,
            Long targetCoordinatorId, String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        Candidate candidate = requestCandidate(requestId);
        preflightTransfer(actorId, candidate, destinationTeamId);
        runRequestRoot(requestId, root -> {
            transfer(root, actorId, expectedSessionVersion, expectedRequestVersion, expectedRequestVersions,
                    destinationTeamId, targetCoordinatorId, reasonCode);
            return null;
        });
    }

    private void transfer(Root root, Long actorId, Long expectedSessionVersion, Long expectedRequestVersion,
            Map<Long, Long> expectedRequestVersions, Long destinationTeamId,
            Long targetCoordinatorId, String reasonCode) {
        if (destinationTeamId == null || destinationTeamId <= 0) throw new IllegalArgumentException("Destination Team ID is required");
        Long sourceCityId = cityId(root);
        Long sourceTeamId = teamId(root);
        if (sourceCityId == null || sourceTeamId == null)
            throw new EntityNotFoundException("Operational work not found");
        OperatingTeam destination = teams.findById(destinationTeamId)
                .orElseThrow(() -> new EntityNotFoundException("Destination Team not found"));
        Long destinationCityId = destination.getCity().getId();
        guards.acquire(nonNull(sourceCityId, destinationCityId), nonNull(sourceTeamId, destinationTeamId),
                nonNull(actorId, targetCoordinatorId));
        requireInitialVisibility(actorId, sourceTeamId);
        requireInitialVisibility(actorId, destinationTeamId);
        requireFresh(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, sourceTeamId);
        requireFresh(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, destinationTeamId);
        requireRootVersions(root, expectedSessionVersion, expectedRequestVersion, expectedRequestVersions);
        requireOperationalWork(root);
        if (!ready(root)) throw new VisitOperationsConflictException("Only ready Team work can be transferred");
        if (!sourceCityId.equals(destinationCityId))
            throw new VisitOperationsConflictException("Operational transfer cannot change the canonical City");
        requireActiveScope(root);
        if (!guards.teamIsActive(destinationTeamId) || !guards.cityIsActive(destinationCityId))
            throw new VisitOperationsConflictException("Destination scope is inactive");
        if (targetCoordinatorId != null) requireCoordinatorTarget(targetCoordinatorId, destinationTeamId);
        if (Objects.equals(sourceTeamId, destinationTeamId)
                && Objects.equals(coordinatorId(root), targetCoordinatorId)) return;
        setTeam(root, destination);
        setCoordinator(root, targetCoordinatorId == null ? null : users.getReferenceById(targetCoordinatorId));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sourceCityId", sourceCityId);
        details.put("destinationCityId", destinationCityId);
        details.put("sourceTeamId", sourceTeamId);
        details.put("destinationTeamId", destinationTeamId);
        if (targetCoordinatorId != null) details.put("coordinatorUserId", targetCoordinatorId);
        flushAndAudit(root, actorId, "OWNERSHIP_TRANSFERRED", reasonCode, details);
    }

    public void releaseCoordinator(Long actorId, Long sessionId, Long expectedSessionVersion,
            Map<Long, Long> expectedRequestVersions, String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        Candidate candidate = sessionCandidate(sessionId);
        requireCandidateVisibility(actorId, candidate);
        inTransaction(() -> {
            Root root = lockSessionRoot(sessionId);
            Long teamId = teamId(root);
            if (teamId == null) throw new EntityNotFoundException("Operational work not found");
            lockGuards(root, actorId, null);
            requireInitialVisibility(actorId, teamId);
            boolean ownsPreTransition = Objects.equals(coordinatorId(root), actorId)
                    && access.hasCapabilityAt(actorId, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId);
            if (!ownsPreTransition && !access.hasCapabilityAt(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, teamId))
                throw new AccessDeniedException("Current Team coordination authority is required");
            requireRootVersions(root, expectedSessionVersion, null, expectedRequestVersions);
            requireOperationalWork(root);
            if (!ready(root)) throw new VisitOperationsConflictException("Only ready Team work can be released");
            if (root.session == null || root.session.getStatus() != VisitSessionStatus.DRAFT
                    || root.session.getStartedAt() != null || root.session.getEntitlementConsumedAt() != null
                    || root.session.getRepresentative() != null)
                throw new VisitOperationsConflictException("Scheduled, started, repair, and terminal work cannot be released");
            requireActiveScope(root);
            if (coordinatorId(root) == null) return null;
            setCoordinator(root, null);
            flushAndAudit(root, actorId, "OWNERSHIP_RELEASED", reasonCode, Map.of(
                    "operationalScopeReady", true, "linkedRequestCount", (long) root.requests.size()));
            return null;
        });
    }

    public void releaseRequestCoordinator(Long actorId, Long requestId, Long expectedRequestVersion,
            Long expectedSessionVersion, Map<Long, Long> expectedRequestVersions, String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        Candidate candidate = requestCandidate(requestId);
        requireCandidateVisibility(actorId, candidate);
        runRequestRoot(requestId, root -> {
            Long teamId = teamId(root);
            if (teamId == null) throw new EntityNotFoundException("Operational work not found");
            lockGuards(root, actorId, null);
            requireInitialVisibility(actorId, teamId);
            boolean ownsPreTransition = Objects.equals(coordinatorId(root), actorId)
                    && access.hasCapabilityAt(actorId, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId);
            if (!ownsPreTransition && !access.hasCapabilityAt(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, teamId))
                throw new AccessDeniedException("Current Team coordination authority is required");
            requireRootVersions(root, expectedSessionVersion, expectedRequestVersion, expectedRequestVersions);
            requireOperationalWork(root);
            if (!ready(root)) throw new VisitOperationsConflictException("Only ready Team work can be released");
            if (root.session != null && root.session.getStatus() != VisitSessionStatus.DRAFT)
                throw new VisitOperationsConflictException("Scheduled, started, repair, and terminal work cannot be released");
            if (root.session != null && (root.session.getStartedAt() != null
                    || root.session.getEntitlementConsumedAt() != null || root.session.getRepresentative() != null))
                throw new VisitOperationsConflictException("A previously scheduled or consumed Visit cannot be released");
            if (root.session == null && root.request.getStatusValue() != VisitRequestStatus.RECEIVED)
                throw new VisitOperationsConflictException("Only unlinked received Requests can be released");
            requireActiveScope(root);
            if (coordinatorId(root) == null) return null;
            setCoordinator(root, null);
            flushAndAudit(root, actorId, "OWNERSHIP_RELEASED", reasonCode, Map.of(
                    "operationalScopeReady", true, "linkedRequestCount", (long) root.requests.size()));
            return null;
        });
    }

    public void reconcileRequest(Long actorId, Long requestId, Long expectedRequestVersion,
            Long expectedSessionVersion, Map<Long, Long> expectedRequestVersions, Long cityId, Long teamId,
            Long coordinatorUserId, boolean scopeReady, Long expectedCityVersion, Long expectedTeamVersion,
            String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        Candidate candidate = requestCandidate(requestId);
        requireInitialAdminForReconciliation(actorId, candidate.cityId, cityId);
        runRequestRoot(requestId, root -> {
            reconcile(root, actorId, expectedSessionVersion, expectedRequestVersion, expectedRequestVersions,
                    cityId, teamId, coordinatorUserId, scopeReady, expectedCityVersion, expectedTeamVersion, reasonCode);
            return null;
        });
    }

    public void reconcileSession(Long actorId, Long sessionId, Long expectedSessionVersion,
            Map<Long, Long> expectedRequestVersions, Long cityId, Long teamId, Long coordinatorUserId,
            boolean scopeReady, Long expectedCityVersion, Long expectedTeamVersion, String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        Candidate candidate = sessionCandidate(sessionId);
        requireInitialAdminForReconciliation(actorId, candidate.cityId, cityId);
        inTransaction(() -> {
            Root root = lockSessionRoot(sessionId);
            reconcile(root, actorId, expectedSessionVersion, null, expectedRequestVersions,
                    cityId, teamId, coordinatorUserId, scopeReady, expectedCityVersion, expectedTeamVersion, reasonCode);
            return null;
        });
    }

    public void deactivateTeam(Long actorId, Long teamId, Long expectedCityVersion,
            Long expectedTeamVersion, String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        inTransaction(() -> {
            OperatingTeam observed = teams.findById(teamId)
                    .orElseThrow(() -> new EntityNotFoundException("Operating Team not found"));
            SupportedCity observedCity = observed.getCity();
            Long cityId = observedCity.getId();
            requireInitialCityAdmin(actorId, cityId);
            entityManager.detach(observedCity);
            entityManager.detach(observed);
            guards.acquire(List.of(cityId), List.of(teamId), List.of(actorId));
            requireFreshCityAdmin(actorId, cityId);
            if (!Objects.equals(guards.cityVersion(cityId), expectedCityVersion)
                    || !Objects.equals(guards.teamVersion(teamId), expectedTeamVersion))
                throw new VisitOperationsConflictException("City or Team version is stale; refresh and retry");
            SupportedCity city = cities.findLockedById(cityId).orElseThrow();
            OperatingTeam team = teams.findLockedById(teamId).orElseThrow();
            entityManager.refresh(city, LockModeType.PESSIMISTIC_WRITE);
            entityManager.refresh(team, LockModeType.PESSIMISTIC_WRITE);
            if (!team.isActive()) return null;
            long workload = teamDrainCount(teamId);
            if (workload > 0) throw new VisitOperationsConflictException("Team deactivation is blocked by nonterminal operational work");
            team.setActive(false);
            entityManager.flush();
            User actor = requireActor(actorId);
            audit.recordUserEvent(actor, "OPERATING_TEAM_DEACTIVATED", "OPERATING_TEAM", teamId,
                    reasonCode, city, team, Map.of("teamId", teamId));
            entityManager.flush();
            return null;
        });
    }

    public void deactivateCity(Long actorId, Long cityId, Long expectedCityVersion, String reasonCode) {
        validateActorAndReason(actorId, reasonCode);
        inTransaction(() -> {
            List<OperatingTeam> observedTeams = teams.findAllByCityIdOrderByIdAsc(cityId);
            requireInitialCityAdmin(actorId, cityId);
            List<Long> teamIds = observedTeams.stream().map(OperatingTeam::getId).toList();
            observedTeams.forEach(entityManager::detach);
            guards.acquire(List.of(cityId), teamIds, List.of(actorId));
            requireFreshCityAdmin(actorId, cityId);
            if (!Objects.equals(guards.cityVersion(cityId), expectedCityVersion))
                throw new VisitOperationsConflictException("City version is stale; refresh and retry");
            SupportedCity city = cities.findLockedById(cityId)
                    .orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
            entityManager.refresh(city, LockModeType.PESSIMISTIC_WRITE);
            List<OperatingTeam> lockedTeams = new ArrayList<>();
            for (Long id : teamIds) {
                OperatingTeam team = teams.findLockedById(id).orElseThrow();
                entityManager.refresh(team, LockModeType.PESSIMISTIC_WRITE);
                lockedTeams.add(team);
            }
            if (lockedTeams.stream().anyMatch(OperatingTeam::isActive))
                throw new VisitOperationsConflictException("Every Team in the City must be inactive before City deactivation");
            if (!city.isActive()) return null;
            long workload = cityDrainCount(cityId);
            if (workload > 0) throw new VisitOperationsConflictException("City deactivation is blocked by nonterminal operational work");
            city.setActive(false);
            entityManager.flush();
            User actor = requireActor(actorId);
            audit.recordUserEvent(actor, "SUPPORTED_CITY_DEACTIVATED", "SUPPORTED_CITY", cityId,
                    reasonCode, city, null, Map.of("cityId", cityId));
            entityManager.flush();
            return null;
        });
    }

    private void reconcile(Root root, Long actorId, Long expectedSessionVersion, Long expectedRequestVersion,
            Map<Long, Long> expectedRequestVersions, Long cityId, Long teamId, Long coordinatorId,
            boolean scopeReady, Long expectedCityVersion, Long expectedTeamVersion, String reasonCode) {
        if (scopeReady && cityId == null) throw new IllegalArgumentException("Ready scope requires a canonical City");
        if (coordinatorId != null && teamId == null) throw new IllegalArgumentException("Coordinator requires a Team");
        Long oldCityId = cityId(root);
        Long oldTeamId = teamId(root);
        SupportedCity city = cityId == null ? null : cities.findById(cityId)
                .orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
        OperatingTeam team = teamId == null ? null : teams.findById(teamId)
                .orElseThrow(() -> new EntityNotFoundException("Operating Team not found"));
        SupportedCity teamCity = team == null ? null : team.getCity();
        if (city != null) entityManager.detach(city);
        if (teamCity != null) entityManager.detach(teamCity);
        if (team != null) entityManager.detach(team);
        List<Long> cityGuards = nonNull(oldCityId, cityId);
        List<Long> teamGuards = nonNull(oldTeamId, teamId);
        List<Long> userGuards = nonNull(actorId, coordinatorId);
        guards.acquire(cityGuards, teamGuards, userGuards);
        city = cityId == null ? null : cities.findLockedById(cityId)
                .orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
        team = teamId == null ? null : teams.findLockedById(teamId)
                .orElseThrow(() -> new EntityNotFoundException("Operating Team not found"));
        requireFreshAdminForReconciliation(actorId, oldCityId, cityId);
        requireRootVersions(root, expectedSessionVersion, expectedRequestVersion, expectedRequestVersions);
        if (team != null && !Objects.equals(team.getCity().getId(), cityId))
            throw new VisitOperationsConflictException("Team must belong to the selected canonical City");
        if (root.session != null && root.session.getCanonicalLocality() != null
                && root.session.getCanonicalLocality().getSupportedCity() != null
                && !Objects.equals(root.session.getCanonicalLocality().getSupportedCity().getId(), cityId))
            throw new VisitOperationsConflictException("Reconciliation City conflicts with the Session locality");
        for (PropertyVisitRequest linkedRequest : root.requests) {
            Long localityId = linkedRequest.getListing().getCanonicalLocalityId();
            if (localityId == null) continue;
            Long localityCityId = jdbc.query("select supported_city_id from localities where id = ?",
                    rs -> rs.next() ? rs.getObject(1, Long.class) : null, localityId);
            if (localityCityId != null && !Objects.equals(localityCityId, cityId))
                throw new VisitOperationsConflictException("Reconciliation City conflicts with a linked canonical Listing locality");
        }
        if ((oldCityId != null && !Objects.equals(oldCityId, cityId))
                || (oldTeamId != null && !Objects.equals(oldTeamId, teamId))
                || (coordinatorId(root) != null && !Objects.equals(coordinatorId(root), coordinatorId))
                || (ready(root) && !scopeReady))
            throw new VisitOperationsConflictException("Reconciliation cannot erase or replace established ownership");
        if (cityId != null && !Objects.equals(guards.cityVersion(cityId), expectedCityVersion))
            throw new VisitOperationsConflictException("City version is stale; refresh and retry");
        if (teamId != null && !Objects.equals(guards.teamVersion(teamId), expectedTeamVersion))
            throw new VisitOperationsConflictException("Team version is stale; refresh and retry");
        if (scopeReady && (city == null || !guards.cityIsActive(cityId)))
            throw new VisitOperationsConflictException("Ready scope requires an active City");
        if (team != null && (!guards.teamIsActive(teamId) || !guards.cityIsActive(cityId)))
            throw new VisitOperationsConflictException("Ready Team assignment requires an active scope");
        if (coordinatorId != null) requireCoordinatorTarget(coordinatorId, teamId);
        if (Objects.equals(oldCityId, cityId) && Objects.equals(oldTeamId, teamId)
                && Objects.equals(coordinatorId(root), coordinatorId) && ready(root) == scopeReady) return;
        setCity(root, city);
        setTeam(root, team);
        setCoordinator(root, coordinatorId == null ? null : users.getReferenceById(coordinatorId));
        setReady(root, scopeReady);
        Map<String, Object> details = new LinkedHashMap<>();
        if (cityId != null) details.put("cityId", cityId);
        if (teamId != null) details.put("teamId", teamId);
        if (coordinatorId != null) details.put("coordinatorUserId", coordinatorId);
        details.put("operationalScopeReady", scopeReady);
        details.put("linkedRequestCount", (long) root.requests.size());
        flushAndAudit(root, actorId, "OWNERSHIP_RECONCILED", reasonCode, details);
    }

    private long teamDrainCount(Long teamId) {
        return jdbc.queryForObject("select "
                + "(select count(*) from visit_sessions where operating_team_id=? and status in (" + SESSION_BLOCKING_SQL + ")) + "
                + "(select count(*) from property_visit_requests where session_id is null and operating_team_id=? and status in (" + REQUEST_BLOCKING_SQL + "))",
                Long.class, teamId, teamId);
    }

    private long cityDrainCount(Long cityId) {
        return jdbc.queryForObject("select "
                + "(select count(*) from visit_sessions where supported_city_id=? and status in (" + SESSION_BLOCKING_SQL + ")) + "
                + "(select count(*) from property_visit_requests where session_id is null and supported_city_id=? "
                + "and operating_team_id is not null and status in (" + REQUEST_BLOCKING_SQL + "))",
                Long.class, cityId, cityId);
    }

    private void requireInitialCityAdmin(Long actorId, Long cityId) {
        if (!access.hasCapabilityAt(actorId, StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null)
                && !access.hasCapabilityAt(actorId, StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, cityId))
            throw new AccessDeniedException("Current City or global Admin authority is required");
    }

    private void requireFreshCityAdmin(Long actorId, Long cityId) {
        requireInitialCityAdmin(actorId, cityId);
    }

    private User requireActor(Long actorId) {
        return users.findById(actorId).orElseThrow(() -> new AccessDeniedException("Authenticated User is unavailable"));
    }

    private void requireInitialAdminForReconciliation(Long actorId, Long oldCityId, Long newCityId) {
        if (access.hasCapabilityAt(actorId, StaffCapability.STAFF_ADMIN, StaffScopeType.GLOBAL, null)) return;
        if (oldCityId != null && !access.hasCapabilityAt(actorId, StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, oldCityId))
            throw new AccessDeniedException("Current City or global Admin authority is required");
        if (newCityId != null && !access.hasCapabilityAt(actorId, StaffCapability.CITY_TEAM_ADMIN, StaffScopeType.CITY, newCityId))
            throw new AccessDeniedException("Current City or global Admin authority is required");
        if (oldCityId == null && newCityId == null)
            throw new AccessDeniedException("Global Admin authority is required for unresolved scope");
    }

    private void requireFreshAdminForReconciliation(Long actorId, Long oldCityId, Long newCityId) {
        requireInitialAdminForReconciliation(actorId, oldCityId, newCityId);
    }

    private void runRequestRoot(Long requestId, java.util.function.Function<Root, Void> action) {
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("Visit Request ID must be positive");
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                requestRootTransactions.execute(status -> {
                    Long discoveredSessionId = requests.findSessionIdByRequestId(requestId).orElse(null);
                    if (discoveredSessionId != null) {
                        VisitSession session = lockSession(discoveredSessionId);
                        List<PropertyVisitRequest> linked = requests.findLockedBySessionIdOrderByIdAsc(discoveredSessionId);
                        PropertyVisitRequest target = linked.stream().filter(row -> requestId.equals(row.getId())).findFirst()
                                .orElseThrow(RootChangedException::new);
                        action.apply(new Root(session, linked, target));
                        return null;
                    }
                    PropertyVisitRequest request = requests.findLockedById(requestId)
                            .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
                    if (request.getSession() != null) throw new RootChangedException();
                    action.apply(new Root(null, List.of(request), request));
                    return null;
                });
                return;
            } catch (RootChangedException changed) {
                if (attempt == 1) throw new VisitOperationsConflictException("Request linkage changed repeatedly; reload and retry");
            }
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private Root lockSessionRoot(Long sessionId) {
        VisitSession session = lockSession(sessionId);
        return new Root(session, requests.findLockedBySessionIdOrderByIdAsc(sessionId), null);
    }

    private VisitSession lockSession(Long sessionId) {
        if (sessionId == null || sessionId <= 0) throw new IllegalArgumentException("Visit Session ID must be positive");
        return sessions.findLockedById(sessionId).orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
    }

    private void requireRootVersions(Root root, Long expectedSessionVersion, Long expectedRequestVersion,
            Map<Long, Long> expectedRequestVersions) {
        if (root.session == null) {
            if (expectedSessionVersion != null || expectedRequestVersions != null)
                throw new IllegalArgumentException("Session versions are not valid for an unlinked Request");
            if (expectedRequestVersion == null || !expectedRequestVersion.equals(root.request.getVersion()))
                throw new VisitOperationsConflictException("Visit Request version is stale; refresh and retry");
            return;
        }
        if (expectedRequestVersion != null || expectedSessionVersion == null
                || !expectedSessionVersion.equals(root.session.getVersion()))
            throw new VisitOperationsConflictException("Visit Session version is stale; refresh and retry");
        if (expectedRequestVersions == null || expectedRequestVersions.size() != root.requests.size())
            throw new VisitOperationsConflictException("Every linked Request version is required; refresh and retry");
        Set<Long> actualIds = new HashSet<>();
        for (PropertyVisitRequest request : root.requests) {
            actualIds.add(request.getId());
            if (!Objects.equals(expectedRequestVersions.get(request.getId()), request.getVersion()))
                throw new VisitOperationsConflictException("A linked Request version is stale; refresh and retry");
        }
        if (!actualIds.equals(expectedRequestVersions.keySet()))
            throw new VisitOperationsConflictException("Linked Request version set is stale; refresh and retry");
    }

    private void requireClaimVersions(Root root, Long actorId, Long expectedSessionVersion,
            Long expectedRequestVersion, Map<Long, Long> expectedRequestVersions) {
        try {
            requireRootVersions(root, expectedSessionVersion, expectedRequestVersion, expectedRequestVersions);
        } catch (VisitOperationsConflictException stale) {
            Long teamId = teamId(root);
            if (teamId == null) throw new EntityNotFoundException("Operational work not found");
            lockGuards(root, actorId, null);
            if (!access.hasCapabilityAt(actorId, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId)
                    && !access.hasCapabilityAt(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, teamId))
                throw new EntityNotFoundException("Operational work not found");
            throw stale;
        }
    }

    private void lockGuards(Root root, Long actorId, Long targetCoordinatorId) {
        guards.acquire(nonNull(cityId(root)), nonNull(teamId(root)), nonNull(actorId, targetCoordinatorId));
    }

    private void requireInitial(Long actorId, StaffCapability capability, StaffScopeType scope, Long scopeId) {
        if (!access.hasCapabilityAt(actorId, capability, scope, scopeId))
            throw new AccessDeniedException("Current scoped operational authority is required");
    }

    private Candidate requestCandidate(Long requestId) {
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("Visit Request ID must be positive");
        Long sessionId = requests.findSessionIdByRequestId(requestId).orElse(null);
        if (sessionId != null) return sessionCandidate(sessionId);
        PropertyVisitRequest request = requests.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
        return new Candidate(cityId(request), teamId(request), request.isOperationalScopeReady());
    }

    private Candidate sessionCandidate(Long sessionId) {
        if (sessionId == null || sessionId <= 0) throw new IllegalArgumentException("Visit Session ID must be positive");
        VisitSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        return new Candidate(session.getSupportedCity() == null ? null : session.getSupportedCity().getId(),
                session.getOperatingTeam() == null ? null : session.getOperatingTeam().getId(),
                session.isOperationalScopeReady());
    }

    private void requireCandidateTeamAuthority(Long actorId, Candidate candidate, StaffCapability capability) {
        if (candidate.teamId == null) throw new EntityNotFoundException("Operational work not found");
        requireInitialVisibility(actorId, candidate.teamId);
        requireInitial(actorId, capability, StaffScopeType.TEAM, candidate.teamId);
        if (!candidate.ready)
            throw new VisitOperationsConflictException("Only ready Team work supports this ownership action");
    }

    private void requireCandidateVisibility(Long actorId, Candidate candidate) {
        if (candidate.teamId == null) throw new EntityNotFoundException("Operational work not found");
        requireInitialVisibility(actorId, candidate.teamId);
        if (!candidate.ready)
            throw new VisitOperationsConflictException("Only ready Team work supports this ownership action");
    }

    private void preflightTransfer(Long actorId, Candidate candidate, Long destinationTeamId) {
        if (candidate.teamId == null) throw new EntityNotFoundException("Operational work not found");
        if (destinationTeamId == null || destinationTeamId <= 0)
            throw new IllegalArgumentException("Destination Team ID is required");
        requireInitialVisibility(actorId, candidate.teamId);
        OperatingTeam destination = teams.findById(destinationTeamId)
                .orElseThrow(() -> new EntityNotFoundException("Destination Team not found"));
        requireInitialVisibility(actorId, destinationTeamId);
        requireInitial(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, candidate.teamId);
        requireInitial(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, destinationTeamId);
        if (!candidate.ready || candidate.cityId == null)
            throw new VisitOperationsConflictException("Only ready Team work can be transferred");
        if (!Objects.equals(candidate.cityId, destination.getCity().getId()))
            throw new VisitOperationsConflictException("Operational transfer cannot change the canonical City");
    }

    private void requireClaimFresh(Long actorId, Long teamId) {
        if (access.hasCapabilityAt(actorId, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId)) return;
        if (access.hasCapabilityAt(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, teamId))
            throw new VisitOperationsConflictException("Claim lost; supervisory visibility remains");
        throw new EntityNotFoundException("Operational work not found");
    }

    private void requireInitialVisibility(Long actorId, Long teamId) {
        if (!access.hasCapabilityAt(actorId, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId)
                && !access.hasCapabilityAt(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, teamId))
            throw new EntityNotFoundException("Operational work not found");
    }

    private void requireFresh(Long actorId, StaffCapability capability, StaffScopeType scope, Long scopeId) {
        if (!access.hasCapabilityAt(actorId, capability, scope, scopeId))
            throw new AccessDeniedException("Current scoped operational authority is required");
    }

    private void requireActiveScope(Root root) {
        Long cityId = cityId(root);
        Long teamId = teamId(root);
        if (cityId == null || !guards.cityIsActive(cityId)
                || teamId == null || !guards.teamIsActive(teamId))
            throw new VisitOperationsConflictException("Operational City or Team is inactive");
    }

    private void requireCoordinatorTarget(Long targetId, Long teamId) {
        if (!access.hasCapabilityAt(targetId, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId))
            throw new VisitOperationsConflictException("Target User lacks current coordinator authority for this Team");
    }

    private void requireOperationalWork(Root root) {
        if (root.session != null) {
            if (!BLOCKING_SESSIONS.contains(root.session.getStatus()))
                throw new VisitOperationsConflictException("Terminal Visit ownership is retained as history");
        } else if (!BLOCKING_REQUESTS.contains(root.request.getStatusValue())) {
            throw new VisitOperationsConflictException("Terminal Request ownership is retained as history");
        }
    }

    private void hideOrConflict(Long actorId, Long teamId) {
        if (!access.hasCapabilityAt(actorId, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId)
                && !access.hasCapabilityAt(actorId, StaffCapability.OPS_SUPERVISE, StaffScopeType.TEAM, teamId))
            throw new EntityNotFoundException("Operational work not found");
        throw new VisitOperationsConflictException("Operational work was claimed concurrently; refresh and retry");
    }

    private void flushAndAudit(Root root, Long actorId, String action, String reasonCode, Map<String, Object> details) {
        entityManager.flush();
        User actor = users.findById(actorId).orElseThrow(() -> new AccessDeniedException("Authenticated User is unavailable"));
        SupportedCity city = root.session != null ? root.session.getSupportedCity() : root.request.getSupportedCity();
        OperatingTeam team = root.session != null ? root.session.getOperatingTeam() : root.request.getOperatingTeam();
        String targetType = root.session != null ? "VISIT_SESSION" : "PROPERTY_VISIT_REQUEST";
        Long targetId = root.session != null ? root.session.getId() : root.request.getId();
        audit.recordUserEvent(actor, action, targetType, targetId, reasonCode, city, team, details);
        entityManager.flush();
    }

    private void setCity(Root root, SupportedCity city) {
        if (root.session != null) root.session.setSupportedCity(city);
        root.requests.forEach(request -> request.setSupportedCity(city));
    }

    private void setTeam(Root root, OperatingTeam team) {
        if (root.session != null) root.session.setOperatingTeam(team);
        root.requests.forEach(request -> request.setOperatingTeam(team));
    }

    private void setCoordinator(Root root, User coordinator) {
        if (root.session != null) root.session.setCoordinator(coordinator);
        root.requests.forEach(request -> request.setCoordinator(coordinator));
    }

    private void setReady(Root root, boolean ready) {
        if (root.session != null) root.session.setOperationalScopeReady(ready);
        root.requests.forEach(request -> request.setOperationalScopeReady(ready));
    }

    private static Long cityId(Root root) {
        SupportedCity city = root.session != null ? root.session.getSupportedCity() : root.request.getSupportedCity();
        return city == null ? null : city.getId();
    }

    private static Long cityId(PropertyVisitRequest request) {
        return request.getSupportedCity() == null ? null : request.getSupportedCity().getId();
    }

    private static Long teamId(Root root) {
        OperatingTeam team = root.session != null ? root.session.getOperatingTeam() : root.request.getOperatingTeam();
        return team == null ? null : team.getId();
    }

    private static Long teamId(PropertyVisitRequest request) {
        return request.getOperatingTeam() == null ? null : request.getOperatingTeam().getId();
    }

    private static Long coordinatorId(Root root) {
        User coordinator = root.session != null ? root.session.getCoordinator() : root.request.getCoordinator();
        return coordinator == null ? null : coordinator.getId();
    }

    private static boolean ready(Root root) {
        return root.session != null ? root.session.isOperationalScopeReady() : root.request.isOperationalScopeReady();
    }

    private static void validateActorAndReason(Long actorId, String reasonCode) {
        if (actorId == null || actorId <= 0) throw new AccessDeniedException("Authenticated User identity required");
        if (reasonCode == null || !REASON.matcher(reasonCode).matches())
            throw new IllegalArgumentException("A bounded uppercase reason code is required");
    }

    private static List<Long> nonNull(Long... ids) {
        List<Long> result = new ArrayList<>();
        for (Long id : ids) if (id != null) result.add(id);
        return result;
    }

    private static String sqlEnumList(List<? extends Enum<?>> statuses) {
        return statuses.stream().map(status -> "'" + status.name() + "'")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private record Root(VisitSession session, List<PropertyVisitRequest> requests, PropertyVisitRequest request) {}
    private record Candidate(Long cityId, Long teamId, boolean ready) {}

    private static final class RootChangedException extends RuntimeException {}
}
