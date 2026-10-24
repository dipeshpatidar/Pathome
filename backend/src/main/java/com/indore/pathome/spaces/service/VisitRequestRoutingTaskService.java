package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.RoutingTaskRecheckCommand;
import com.indore.pathome.spaces.dto.VisitRequestRoutingTaskPage;
import com.indore.pathome.spaces.dto.VisitRequestRoutingTaskView;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTask;
import com.indore.pathome.spaces.entity.VisitRequestRoutingTaskState;
import com.indore.pathome.spaces.exception.OperationalCommandUnavailableException;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository;
import com.indore.pathome.spaces.repository.VisitRequestRoutingTaskRepository.RoutingTaskRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class VisitRequestRoutingTaskService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final VisitRequestRoutingTaskRepository tasks;
    private final PropertyVisitRequestRepository requests;
    private final StaffAccessService access;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final ListingRepository listings;
    private final LocalityRepository localities;
    private final TransactionalCommandBounds commandBounds;
    private final DatabaseClock databaseClock;
    private final EntityManager entityManager;

    public VisitRequestRoutingTaskService(VisitRequestRoutingTaskRepository tasks,
                                          PropertyVisitRequestRepository requests,
                                          StaffAccessService access,
                                          OperationalSecurityGuards guards,
                                          OperationalAuditService audit,
                                          ListingRepository listings,
                                          LocalityRepository localities,
                                          TransactionalCommandBounds commandBounds,
                                          DatabaseClock databaseClock,
                                          EntityManager entityManager) {
        this.tasks = tasks;
        this.requests = requests;
        this.access = access;
        this.guards = guards;
        this.audit = audit;
        this.listings = listings;
        this.localities = localities;
        this.commandBounds = commandBounds;
        this.databaseClock = databaseClock;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public VisitRequestRoutingTaskPage list(Long actorId, int page, int size) {
        IntakeRoutingAuthority.Scope scope = IntakeRoutingAuthority.resolve(access, actorId);
        Pageable pageable = PageRequest.of(requirePage(page), requirePageSize(size));
        Page<RoutingTaskRow> rows = scope.global()
                ? tasks.findRoutingPage(pageable)
                : tasks.findRoutingPageByCityIds(scope.cityIds(), pageable);
        return new VisitRequestRoutingTaskPage(rows.getContent().stream()
                .map(VisitRequestRoutingTaskService::toView).toList(),
                rows.getTotalElements(), page, size, rows.getTotalPages());
    }

    @Transactional(readOnly = true)
    public VisitRequestRoutingTaskView read(Long actorId, Long taskId) {
        if (taskId == null || taskId <= 0) throw new IllegalArgumentException("Task ID must be positive");
        IntakeRoutingAuthority.Scope scope = resolveForRecordRead(actorId);
        RoutingTaskRow row = tasks.findRoutingTaskRow(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Routing task not found"));
        requireTaskVisibility(scope, row);
        requireCityRoutingAuthority(scope, row.getCityId());
        return toView(row);
    }

    /**
     * Record-level reads conceal both missing and unauthorized identities. Operation-level gates
     * such as list and recheck retain their explicit authorization responses.
     */
    private IntakeRoutingAuthority.Scope resolveForRecordRead(Long actorId) {
        try {
            return IntakeRoutingAuthority.resolve(access, actorId);
        } catch (AccessDeniedException denied) {
            throw new EntityNotFoundException("Routing task not found");
        }
    }

    @Transactional
    public VisitRequestRoutingTaskView recheck(Long actorId, Long taskId,
                                               RoutingTaskRecheckCommand command) {
        if (taskId == null || taskId <= 0) throw new IllegalArgumentException("Task ID must be positive");
        if (command == null || command.expectedTaskVersion() == null
                || command.expectedTaskVersion() < 0
                || command.reasonCode() == null || !REASON.matcher(command.reasonCode()).matches()) {
            throw new IllegalArgumentException("Expected task version and reason code are required");
        }
        try {
            return recheckBounded(actorId, taskId, command);
        } catch (RuntimeException failure) {
            throw OperationalCommandUnavailableException.translate(failure);
        }
    }

    private VisitRequestRoutingTaskView recheckBounded(Long actorId, Long taskId,
                                                       RoutingTaskRecheckCommand command) {
        commandBounds.apply();
        IntakeRoutingAuthority.Scope scope = IntakeRoutingAuthority.resolve(access, actorId);
        RoutingTaskRow observedRow = tasks.findRoutingTaskRow(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Routing task not found"));
        requireTaskVisibility(scope, observedRow);
        Long requestId = observedRow.getRequestId();
        if (requests.findSessionIdByRequestId(requestId).orElse(null) != null) {
            throw new VisitOperationsConflictException("Linked Request routing is managed by its Visit Session");
        }
        PropertyVisitRequest lockedRequest = requests.findLockedById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Routing task not found"));
        VisitRequestRoutingTask task = tasks.findLockedById(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Routing task not found"));
        Long cityId = lockedRequest.getSupportedCity() == null
                ? null : lockedRequest.getSupportedCity().getId();
        guards.acquire(cityId == null ? List.of() : List.of(cityId), List.of(), List.of(actorId));
        requireCityRoutingAuthority(scope, cityId);
        if (!Objects.equals(task.getVersion(), command.expectedTaskVersion())) {
            throw new VisitOperationsConflictException("Routing task version is stale; reload and retry");
        }
        if (task.getState() != VisitRequestRoutingTaskState.NEEDS_REVIEW) {
            throw new VisitOperationsConflictException("Only a routing exception can be rechecked");
        }
        Long listingId = observedRow.getListingId();
        if (listingId == null) throw new EntityNotFoundException("Routing task not found");
        listings.lockForSchedulingById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Routing task not found"));
        if (observedRow.getLocalityId() != null) {
            localities.findLockedById(observedRow.getLocalityId());
        }
        /*
         * Authority is deliberately re-read only after every canonical source and evidence row has
         * been locked. A grant can expire while this command waits for a Listing or locality lock;
         * the scope captured before that wait is no longer safe admission evidence.
         */
        IntakeRoutingAuthority.Scope finalScope = IntakeRoutingAuthority.resolve(access, actorId);
        RoutingTaskRow finalRow = tasks.findRoutingTaskRow(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Routing task not found"));
        requireTaskVisibility(finalScope, finalRow);
        requireFreshCityRoutingAuthority(finalScope, cityId);
        if (!Objects.equals(task.getVersion(), command.expectedTaskVersion())) {
            throw new VisitOperationsConflictException("Routing task version is stale; reload and retry");
        }
        if (task.getState() != VisitRequestRoutingTaskState.NEEDS_REVIEW) {
            throw new VisitOperationsConflictException("Only a routing exception can be rechecked");
        }
        task.requeue(databaseClock.now());
        User actor = access.requireAttributableUser(actorId);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("routingTaskId", task.getId());
        details.put("routingTaskVersion", task.getVersion());
        if (cityId != null) details.put("cityId", cityId);
        audit.recordUserEvent(actor, "ROUTING_RECHECK_REQUESTED",
                "VISIT_REQUEST_ROUTING_TASK", task.getId(), command.reasonCode(),
                task.getRequest().getSupportedCity(), null, details);
        entityManager.flush();
        return toView(tasks.findRoutingTaskRow(taskId)
                .orElseThrow(() -> new EntityNotFoundException("Routing task not found")));
    }

    private static void requireCityRoutingAuthority(IntakeRoutingAuthority.Scope scope, Long cityId) {
        if (scope.global()) {
            return;
        }
        if (cityId != null && (scope.hasBroadAuthority(cityId)
                || scope.hasRoutingAuthority(cityId))) {
            return;
        }
        throw new AccessDeniedException("Current City routing authority is required");
    }

    private void requireFreshCityRoutingAuthority(IntakeRoutingAuthority.Scope scope, Long cityId) {
        try {
            requireCityRoutingAuthority(scope, cityId);
        } catch (AccessDeniedException denied) {
            throw new EntityNotFoundException("Routing task not found");
        }
    }

    private static void requireTaskVisibility(IntakeRoutingAuthority.Scope scope, RoutingTaskRow row) {
        if (!scope.coversRoutingTaskSource(row.getCityId(), row.getLocalityCityId(),
                Boolean.TRUE.equals(row.getScopeReady()))) {
            throw new EntityNotFoundException("Routing task not found");
        }
    }

    private static VisitRequestRoutingTaskView toView(RoutingTaskRow row) {
        boolean canonical = row.getCityId() != null && row.getCityId().equals(row.getLocalityCityId());
        boolean eligible = Boolean.TRUE.equals(row.getScopeReady()) && canonical
                && "RECEIVED".equals(row.getRequestStatus())
                && row.getTeamId() == null && row.getCoordinatorUserId() == null;
        return new VisitRequestRoutingTaskView(row.getId(), row.getTaskVersion(), row.getTaskState(),
                row.getLastErrorCode(), row.getRequestId(), row.getRequestVersion(),
                row.getRequestStatus(), row.getCityId(), row.getCityDisplayName(),
                row.getLocalityId(), row.getLocalityDisplayName(), row.getListingId(),
                row.getListingTitle(), row.getTeamId(), row.getCoordinatorUserId(),
                row.getResultTeamId(), row.getResultTeamName(), row.getResultRequestVersion(),
                row.getEnrolledAt(), row.getUpdatedAt(), eligible);
    }

    private static int requirePage(int page) {
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        return page;
    }

    private static int requirePageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return size;
    }
}
