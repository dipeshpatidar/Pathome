package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.OperationsTenantContactStatus;
import com.indore.pathome.spaces.dto.OperationsTenantContactView;
import com.indore.pathome.spaces.dto.OperationsSessionTenantContactView;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * R1-OE-1 secure tenant-contact read seam.
 *
 * <p>Two narrowly scoped read paths disclose a tenant's name and mobile to the <em>currently owning</em>
 * Operations Executive only: an unlinked claimed {@code RECEIVED} Request, and an operationally ready
 * {@code DRAFT}, {@code SCHEDULED}, {@code STARTED} or {@code INTERRUPTED} Session. Contact is resolved
 * only through that authorized root's tenant relationship.</p>
 *
 * <p>Authorization is re-established inside one writable transaction after the root and the shared
 * City/Team/staff/grant guard rows are locked, so ownership transfer, grant revocation, staff/City/Team
 * deactivation or Request linkage cannot race a live PII reveal. The required audit row is written in
 * the same transaction, so contact is released only if the audit row commits with it.</p>
 */
@Service
public class OperationsTenantContactService {

    /** Session-root statuses that permit a live tenant-contact reveal. */
    private static final EnumSet<VisitSessionStatus> REVEALABLE_SESSION_STATUSES =
            EnumSet.of(VisitSessionStatus.DRAFT, VisitSessionStatus.SCHEDULED,
                    VisitSessionStatus.STARTED, VisitSessionStatus.INTERRUPTED);

    private final PropertyVisitRequestRepository requests;
    private final VisitSessionRepository sessions;
    private final UserRepository users;
    private final StaffAccessService access;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;

    public OperationsTenantContactService(PropertyVisitRequestRepository requests, VisitSessionRepository sessions,
            UserRepository users, StaffAccessService access, OperationalSecurityGuards guards,
            OperationalAuditService audit) {
        this.requests = requests;
        this.sessions = sessions;
        this.users = users;
        this.access = access;
        this.guards = guards;
        this.audit = audit;
    }

    /**
     * Contact for an unlinked, currently-owned {@code RECEIVED} standalone Request.
     *
     * <p>A Request that has been linked to a Visit Session is no longer an operable Request root and
     * is reported as not found, so this route can never outlive its ownership root.</p>
     */
    @Transactional
    public OperationsTenantContactView requestContact(Long actorId, Long requestId) {
        requireCoordinatorEligibility(actorId);
        if (requestId == null || requestId <= 0) {
            throw new IllegalArgumentException("Visit Request ID must be positive");
        }
        PropertyVisitRequest request = requests.findLockedById(requestId)
                .orElseThrow(OperationsTenantContactService::notFound);
        if (request.getSession() != null) throw notFound();

        Scope scope = requireScope(request.getSupportedCity(), request.getOperatingTeam());
        lockScopeGuards(scope, actorId);
        requireActiveScope(scope);
        requireFreshCoordinator(actorId, scope.teamId());

        if (request.getStatusValue() != VisitRequestStatus.RECEIVED
                || !request.isOperationalScopeReady()
                || !isCoordinator(request.getCoordinator(), actorId)) {
            throw notFound();
        }

        OperationsTenantContactView view = OperationsTenantContactResolver.resolveRequest(
                request.getId(), request.getTenant());
        auditReveal(view.status(), actorId, "PROPERTY_VISIT_REQUEST", request.getId(),
                request.getSupportedCity(), request.getOperatingTeam());
        return view;
    }

    /**
     * Contact for a currently-owned, operationally ready, non-terminal Session.
     *
     * <p>Session-root ownership, status and scope are all re-checked under the Session-root lock. The
     * tenant is the Session's own tenant relationship; no linked Request, listing owner or lead field
     * is consulted.</p>
     */
    @Transactional
    public OperationsSessionTenantContactView sessionContact(Long actorId, Long sessionId) {
        requireCoordinatorEligibility(actorId);
        if (sessionId == null || sessionId <= 0) {
            throw new IllegalArgumentException("Visit Session ID must be positive");
        }
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(OperationsTenantContactService::notFound);

        Scope scope = requireScope(session.getSupportedCity(), session.getOperatingTeam());
        lockScopeGuards(scope, actorId);
        requireActiveScope(scope);
        requireFreshCoordinator(actorId, scope.teamId());

        if (!REVEALABLE_SESSION_STATUSES.contains(session.getStatus())
                || !session.isOperationalScopeReady()
                || !isCoordinator(session.getCoordinator(), actorId)) {
            throw notFound();
        }

        OperationsSessionTenantContactView view = OperationsTenantContactResolver.resolveSession(
                session.getId(), session.getTenant());
        auditReveal(view.status(), actorId, "VISIT_SESSION", session.getId(),
                session.getSupportedCity(), session.getOperatingTeam());
        return view;
    }

    /**
     * Coarse endpoint eligibility: 403 when the caller holds no current, usable
     * {@code OPS_COORDINATE@TEAM} authority. A specific hidden target stays 404.
     */
    private void requireCoordinatorEligibility(Long actorId) {
        if (actorId == null || actorId <= 0) {
            throw new AccessDeniedException("Authenticated User identity required");
        }
        boolean usable = access.effectiveGrants(actorId).stream().anyMatch(grant ->
                grant.getCapability() == StaffCapability.OPS_COORDINATE
                        && grant.getScopeType() == StaffScopeType.TEAM);
        if (!usable) {
            throw new AccessDeniedException("Current OPS_COORDINATE Team authority is required");
        }
    }

    private void requireFreshCoordinator(Long actorId, Long teamId) {
        if (!access.hasCapabilityAt(actorId, StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, teamId)) {
            throw notFound();
        }
    }

    private Scope requireScope(SupportedCity city, OperatingTeam team) {
        Long cityId = city == null ? null : city.getId();
        Long teamId = team == null ? null : team.getId();
        if (cityId == null || teamId == null) throw notFound();
        return new Scope(cityId, teamId);
    }

    /** Locks City, Team, EmployeeProfile and grant rows in the established operational lock order. */
    private void lockScopeGuards(Scope scope, Long actorId) {
        guards.acquire(List.of(scope.cityId()), List.of(scope.teamId()), List.of(actorId));
    }

    private void requireActiveScope(Scope scope) {
        if (!guards.cityIsActive(scope.cityId())
                || !guards.teamIsActive(scope.teamId())
                || !Objects.equals(guards.teamCityId(scope.teamId()), scope.cityId())) {
            throw notFound();
        }
    }

    /**
     * Synchronous, PII-safe audit for an actual disclosure. Unavailable contacts produce no audit
     * event because no PII is disclosed. No phone digits or tenant name enter the audit details.
     */
    private void auditReveal(OperationsTenantContactStatus status, Long actorId, String targetType, Long targetId,
            SupportedCity city, OperatingTeam team) {
        if (status != OperationsTenantContactStatus.CONTACT_AVAILABLE) return;
        audit.recordUserEvent(users.getReferenceById(actorId), "TENANT_CONTACT_REVEALED", targetType, targetId,
                "COORDINATION_CONTACT", city, team, Map.of());
    }

    private static boolean isCoordinator(User coordinator, Long actorId) {
        return coordinator != null && actorId.equals(coordinator.getId());
    }

    private static EntityNotFoundException notFound() {
        return new EntityNotFoundException("Operational work not found");
    }

    private record Scope(Long cityId, Long teamId) {}
}
