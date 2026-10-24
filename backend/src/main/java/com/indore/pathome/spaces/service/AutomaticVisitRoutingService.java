package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.AutomaticVisitRoutingControl;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.OperationalAuditEvent;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.TeamLocalityCoverage;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTask;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTaskState;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.repository.AutomaticVisitRoutingControlRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.TeamLocalityCoverageRepository;
import com.indore.pathome.spaces.repository.TeamLocalityCoverageRepository.ActiveCoverageRow;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One root-serialized automatic routing decision. The Request and task were committed together at
 * creation; this service never enrolls historical work and never changes customer Contact or Visit
 * scheduling state.
 */
@Service
public class AutomaticVisitRoutingService {
    public static final int DEFAULT_BATCH_SIZE = 20;

    private final VisitRequestRoutingTaskRepository tasks;
    private final PropertyVisitRequestRepository requests;
    private final VisitSessionRepository sessions;
    private final ListingRepository listings;
    private final LocalityRepository localities;
    private final SupportedCityRepository cities;
    private final OperatingTeamRepository teams;
    private final TeamLocalityCoverageRepository coverages;
    private final AutomaticVisitRoutingControlRepository controls;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final TransactionalCommandBounds commandBounds;
    private final DatabaseClock databaseClock;
    private final EntityManager entityManager;

    public AutomaticVisitRoutingService(VisitRequestRoutingTaskRepository tasks,
                                        PropertyVisitRequestRepository requests,
                                        VisitSessionRepository sessions,
                                        ListingRepository listings,
                                        LocalityRepository localities,
                                        SupportedCityRepository cities,
                                        OperatingTeamRepository teams,
                                        TeamLocalityCoverageRepository coverages,
                                        AutomaticVisitRoutingControlRepository controls,
                                        OperationalSecurityGuards guards,
                                        OperationalAuditService audit,
                                        TransactionalCommandBounds commandBounds,
                                        DatabaseClock databaseClock,
                                        EntityManager entityManager) {
        this.tasks = tasks;
        this.requests = requests;
        this.sessions = sessions;
        this.listings = listings;
        this.localities = localities;
        this.cities = cities;
        this.teams = teams;
        this.coverages = coverages;
        this.controls = controls;
        this.guards = guards;
        this.audit = audit;
        this.commandBounds = commandBounds;
        this.databaseClock = databaseClock;
        this.entityManager = entityManager;
    }

    @Transactional
    public List<Long> dueTaskIds(int limit) {
        return tasks.findDueTaskIds(Math.max(1, Math.min(DEFAULT_BATCH_SIZE, limit)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTask(Long taskId) {
        if (taskId == null || taskId <= 0) throw new IllegalArgumentException("Routing task ID must be positive");
        commandBounds.apply();
        VisitRequestRoutingTask discovered = tasks.findById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Routing task not found"));
        if (discovered.getState() != VisitRequestRoutingTaskState.PENDING
                && discovered.getState() != VisitRequestRoutingTaskState.RETRY) return;
        Long requestId = discovered.getRequest().getId();
        Long sessionId = requests.findSessionIdByRequestId(requestId).orElse(null);
        if (sessionId != null) {
            sessions.findLockedById(sessionId)
                    .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
            requests.findLockedById(requestId)
                    .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
            VisitRequestRoutingTask task = tasks.findLockedById(taskId)
                    .orElseThrow(() -> new EntityNotFoundException("Routing task not found"));
            if (task.getState() == VisitRequestRoutingTaskState.PENDING
                    || task.getState() == VisitRequestRoutingTaskState.RETRY) {
                task.skip("LINKED_TO_VISIT_SESSION", databaseClock.now());
                entityManager.flush();
            }
            return;
        }

        requests.findLockedById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
        // A manual writer may have committed while this transaction waited for the Request lock.
        // Clear only the stale persistence snapshot after the lock is held, then reload both rows in
        // the same transaction; PostgreSQL locks remain held across clear().
        entityManager.clear();
        PropertyVisitRequest request = requests.findLockedById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
        VisitRequestRoutingTask task = tasks.findLockedById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Routing task not found"));
        if (task.getState() != VisitRequestRoutingTaskState.PENDING
                && task.getState() != VisitRequestRoutingTaskState.RETRY) {
            return;
        }
        if (task.getProtocolVersion() != VisitRequestRoutingTask.SUPPORTED_PROTOCOL_VERSION) {
            task.retry("ROUTING_PROTOCOL_UNSUPPORTED", databaseClock.now());
            entityManager.flush();
            return;
        }
        AutomaticVisitRoutingControl control = controls.findShared()
                .orElseThrow(() -> new IllegalStateException("Automatic routing control is missing"));
        if (!control.isEnabled()) return;
        if (control.getProtocolVersion() != AutomaticVisitRoutingControl.SUPPORTED_PROTOCOL_VERSION) {
            task.retry("ROUTING_PROTOCOL_UNSUPPORTED", databaseClock.now());
            entityManager.flush();
            return;
        }
        if (request.getSession() != null) {
            task.skip("LINKED_TO_VISIT_SESSION", databaseClock.now());
            entityManager.flush();
            return;
        }
        if (request.getStatusValue() != VisitRequestStatus.RECEIVED
                || request.getOperatingTeam() != null || request.getCoordinator() != null) {
            task.skip("WORK_ALREADY_ROUTED", databaseClock.now());
            entityManager.flush();
            return;
        }
        if (!Objects.equals(request.getVersion(), task.getExpectedRequestVersion())) {
            task.needsReview("STALE_REQUEST_VERSION", databaseClock.now());
            entityManager.flush();
            return;
        }
        if (!request.isOperationalScopeReady()) {
            task.needsReview("CANONICAL_SCOPE_NOT_READY", databaseClock.now());
            entityManager.flush();
            return;
        }
        if (request.getSupportedCity() == null || request.getListing() == null
                || request.getListing().getCanonicalLocalityId() == null) {
            task.needsReview("CANONICAL_CITY_UNRESOLVED", databaseClock.now());
            entityManager.flush();
            return;
        }
        Long cityId = request.getSupportedCity().getId();
        Long listingId = request.getListing().getId();
        Long localityId = request.getListing().getCanonicalLocalityId();

        guards.acquire(List.of(cityId), List.of(), List.of());
        var city = cities.findLockedById(cityId)
                .orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
        if (!city.isActive()) {
            task.needsReview("INACTIVE_CITY", databaseClock.now());
            entityManager.flush();
            return;
        }
        var observedLocality = localities.findById(localityId).orElse(null);
        Long observedLocalityCityId = observedLocality == null || observedLocality.getSupportedCity() == null
                ? null : observedLocality.getSupportedCity().getId();
        if (observedLocalityCityId == null) {
            task.needsReview("CANONICAL_CITY_UNRESOLVED", databaseClock.now());
            entityManager.flush();
            return;
        }
        if (!cityId.equals(observedLocalityCityId)) {
            task.needsReview("CANONICAL_CITY_CONFLICT", databaseClock.now());
            entityManager.flush();
            return;
        }

        List<ActiveCoverageRow> initial = coverages.findActiveRoutingCandidates(cityId, localityId);
        if (initial.isEmpty()) {
            task.needsReview("NO_ACTIVE_TEAM_COVERAGE", databaseClock.now());
            entityManager.flush();
            return;
        }
        List<Long> teamIds = initial.stream().map(ActiveCoverageRow::getTeamId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (teamIds.size() != 1) {
            task.needsReview("OVERLAPPING_ACTIVE_COVERAGE", databaseClock.now());
            entityManager.flush();
            return;
        }
        Long teamId = teamIds.get(0);
        guards.acquire(List.of(cityId), List.of(teamId), List.of());
        OperatingTeam team = teams.findLockedById(teamId)
                .orElseThrow(() -> new EntityNotFoundException("Operating Team not found"));
        listings.lockForSchedulingById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Source Listing not found"));
        Long currentLocalityId = listings.findCanonicalLocalityIdById(listingId).orElse(null);
        if (currentLocalityId == null) {
            task.needsReview("CANONICAL_CITY_UNRESOLVED", databaseClock.now());
            entityManager.flush();
            return;
        }
        if (!currentLocalityId.equals(localityId)) {
            task.needsReview("STALE_CANONICAL_SOURCE", databaseClock.now());
            entityManager.flush();
            return;
        }
        var locality = localities.findLockedById(localityId)
                .orElseThrow(() -> new EntityNotFoundException("Canonical locality not found"));
        Long localityCityId = locality.getSupportedCity() == null
                ? null : locality.getSupportedCity().getId();
        if (localityCityId == null) {
            task.needsReview("CANONICAL_CITY_UNRESOLVED", databaseClock.now());
            entityManager.flush();
            return;
        }
        if (!cityId.equals(localityCityId)) {
            task.needsReview("CANONICAL_CITY_CONFLICT", databaseClock.now());
            entityManager.flush();
            return;
        }
        List<ActiveCoverageRow> current = coverages.findActiveRoutingCandidates(cityId, localityId);
        List<Long> currentTeamIds = current.stream().map(ActiveCoverageRow::getTeamId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (currentTeamIds.isEmpty()) {
            task.needsReview("NO_ACTIVE_TEAM_COVERAGE", databaseClock.now());
            entityManager.flush();
            return;
        }
        if (currentTeamIds.size() != 1 || !teamId.equals(currentTeamIds.get(0))) {
            task.needsReview("OVERLAPPING_ACTIVE_COVERAGE", databaseClock.now());
            entityManager.flush();
            return;
        }
        if (!team.isActive() || !cityId.equals(team.getCity().getId())) {
            task.needsReview("INACTIVE_TEAM", databaseClock.now());
            entityManager.flush();
            return;
        }
        ActiveCoverageRow selected = current.get(0);
        TeamLocalityCoverage coverage = coverages.findLockedById(selected.getId())
                .orElseThrow(() -> new EntityNotFoundException("Coverage not found"));
        if (!coverage.isActive() || !Objects.equals(coverage.getVersion(), selected.getCoverageVersion())) {
            task.needsReview("STALE_COVERAGE", databaseClock.now());
            entityManager.flush();
            return;
        }

        request.setOperatingTeam(team);
        request.setOperationalScopeReady(true);
        entityManager.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("routingTaskId", task.getId());
        details.put("routingTaskVersion", task.getVersion());
        details.put("expectedRequestVersion", task.getExpectedRequestVersion());
        details.put("resultingRequestVersion", request.getVersion());
        details.put("coverageId", coverage.getId());
        details.put("coverageVersion", coverage.getVersion());
        details.put("selectedLocalityId", localityId);
        details.put("cityId", cityId);
        details.put("teamId", teamId);
        details.put("operationalScopeReady", true);
        details.put("routingProtocolVersion", control.getProtocolVersion());
        details.put("automaticRoutingControlVersion", control.getVersion());
        OperationalAuditEvent event = audit.recordSystemEvent(
                "AUTOMATIC_TEAM_ROUTED", "PROPERTY_VISIT_REQUEST", request.getId(),
                "UNIQUE_CANONICAL_COVERAGE_V1", city, team, details);
        task.complete(coverage, team, request.getVersion(), event.getId(), databaseClock.now());
        entityManager.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTechnicalFailure(Long taskId, String reasonCode) {
        if (taskId == null || taskId <= 0) throw new IllegalArgumentException("Routing task ID must be positive");
        commandBounds.apply();
        VisitRequestRoutingTask task = tasks.findLockedById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Routing task not found"));
        if (task.getState() == VisitRequestRoutingTaskState.PENDING
                || task.getState() == VisitRequestRoutingTaskState.RETRY) {
            task.retry(reasonCode, databaseClock.now());
            entityManager.flush();
        }
    }
}
