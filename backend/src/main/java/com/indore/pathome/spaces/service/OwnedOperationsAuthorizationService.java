package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 2A-3A authorization seam for the six Visit Operations actions that the currently owning
 * Operations Executive may run: coordinate an owned Request, mark an owned Request unavailable, add
 * a suitable property, remove or reorder items, and record property availability.
 *
 * <p>Pathome Admin authority is untouched: {@code requireOperations} keeps its exact previous
 * meaning and Admin callers never pass through this seam. Admission here is deliberately narrow. A
 * caller must either be a Pathome Admin, or an active staff member holding a current, effective,
 * unrevoked, unexpired {@code OPS_COORDINATE@TEAM} grant on an active Team inside an active City.
 * Every other caller receives 403 before any target row is read, so hidden work is never disclosed.
 *
 * <p>Definitive authorization is re-established inside the caller's writable mutation transaction
 * after the authoritative root row and the canonical City &rarr; Team &rarr; EmployeeProfile &rarr;
 * grant guard rows are locked, so a concurrent ownership transfer, grant revocation, staff/City/Team
 * deactivation or Request/Session linkage change can never race an enabled mutation. Roots locked
 * here are always locked before their guards, and the guard pass is sorted, so no lock-order
 * inversion is introduced. A root that is not currently owned by the caller, or whose canonical
 * scope or capability is no longer usable, fails closed as not found with one generic message, so a
 * hidden root is indistinguishable from a missing one.
 */
@Service
public class OwnedOperationsAuthorizationService {
    /** Generic fail-closed target response: a hidden or stale root must not be distinguishable. */
    static final String HIDDEN_TARGET = "Operational work not found";

    private final UserRepository users;
    private final VisitSessionRepository sessions;
    private final PropertyVisitRequestRepository requests;
    private final StaffAccessService access;
    private final OperationalSecurityGuards guards;

    public OwnedOperationsAuthorizationService(UserRepository users, VisitSessionRepository sessions,
            PropertyVisitRequestRepository requests, StaffAccessService access, OperationalSecurityGuards guards) {
        this.users = users;
        this.sessions = sessions;
        this.requests = requests;
        this.access = access;
        this.guards = guards;
    }

    /**
     * Coarse admission evaluated before any target row is touched: 403 unless the caller is a Pathome
     * Admin or holds a usable {@code OPS_COORDINATE@TEAM} grant. No target detail is disclosed.
     */
    public Actor admit(Long actorId) {
        if (actorId == null || actorId <= 0) throw new AccessDeniedException("Authenticated user identity required");
        User user = users.findById(actorId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated user is unavailable"));
        if (user.getRole() == Role.ROLE_ADMIN) return new Actor(user, true);
        boolean usable = access.effectiveGrants(user.getId()).stream().anyMatch(grant ->
                grant.getCapability() == StaffCapability.OPS_COORDINATE
                        && grant.getScopeType() == StaffScopeType.TEAM);
        if (!usable) throw new AccessDeniedException("Current OPS_COORDINATE Team authority is required");
        return new Actor(user, false);
    }

    /**
     * Session root lock for owned-action routes. A missing root is reported exactly like a hidden
     * one so the route never becomes an existence oracle for another Team's or City's work.
     */
    public VisitSession lockTargetSession(Long sessionId) {
        if (sessionId == null || sessionId <= 0) throw new IllegalArgumentException("Visit Session ID must be positive");
        return sessions.findLockedById(sessionId).orElseThrow(OwnedOperationsAuthorizationService::hidden);
    }

    /** Request root lock for owned-action routes, with the same uniform missing/hidden response. */
    public PropertyVisitRequest lockTargetRequest(Long requestId) {
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("Visit Request ID must be positive");
        return requests.findLockedById(requestId).orElseThrow(OwnedOperationsAuthorizationService::hidden);
    }

    /**
     * Coordinate authority: the caller must currently own the standalone Request root and, when an
     * attach target was named, the DRAFT Session root as well. Both scopes are locked in a single
     * sorted guard pass, so the Session root is still locked before its Request and no cross-scope
     * order is created.
     */
    public void requireCoordinateAuthority(Actor actor, PropertyVisitRequest request, VisitSession attachTarget) {
        requireStandalone(request);
        Scope requestScope = scopeOf(request.getSupportedCity(), request.getOperatingTeam());
        Scope targetScope = attachTarget == null ? null
                : scopeOf(attachTarget.getSupportedCity(), attachTarget.getOperatingTeam());
        lockScopes(actor, requestScope, targetScope);
        requireOwnedRequestRoot(actor, request, requestScope);
        if (targetScope != null) {
            requireOwnedSessionRoot(actor, attachTarget, targetScope);
            requireDraft(attachTarget);
        }
    }

    /** Unlinked owned Request root: the Request must currently be the caller's ready Team work. */
    public void requireOwnedStandaloneRequest(Actor actor, PropertyVisitRequest request) {
        requireStandalone(request);
        Scope scope = scopeOf(request.getSupportedCity(), request.getOperatingTeam());
        lockScopes(actor, scope);
        requireOwnedRequestRoot(actor, request, scope);
    }

    /**
     * Owned DRAFT Session root for itinerary mutations. An owned Session that has moved past DRAFT
     * is a lifecycle conflict, never an Operations Executive mutation.
     */
    public void requireOwnedDraftSession(Actor actor, VisitSession session) {
        Scope scope = scopeOf(session.getSupportedCity(), session.getOperatingTeam());
        lockScopes(actor, scope);
        requireOwnedSessionRoot(actor, session, scope);
        requireDraft(session);
    }

    private void requireOwnedRequestRoot(Actor actor, PropertyVisitRequest request, Scope scope) {
        if (scope == null || !usableScope(actor, scope)
                || !request.isOperationalScopeReady()
                || request.getCoordinator() == null
                || !actor.id().equals(request.getCoordinator().getId())) throw hidden();
    }

    private void requireOwnedSessionRoot(Actor actor, VisitSession session, Scope scope) {
        if (scope == null || !usableScope(actor, scope)
                || !session.isOperationalScopeReady()
                || session.getCoordinator() == null
                || !actor.id().equals(session.getCoordinator().getId())) throw hidden();
    }

    /** Fresh canonical City, Team, EmployeeProfile and grant state, then the capability itself. */
    private boolean usableScope(Actor actor, Scope scope) {
        return guards.cityIsActive(scope.cityId())
                && guards.teamIsActive(scope.teamId())
                && Objects.equals(guards.teamCityId(scope.teamId()), scope.cityId())
                && access.hasCapabilityAt(actor.id(), StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM, scope.teamId());
    }

    /** Locks the union of the given canonical scopes in the established City then Team then grant order. */
    private void lockScopes(Actor actor, Scope... scopes) {
        Set<Long> cityIds = new LinkedHashSet<>();
        Set<Long> teamIds = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            if (scope == null) continue;
            cityIds.add(scope.cityId());
            teamIds.add(scope.teamId());
        }
        guards.acquire(cityIds, teamIds, List.of(actor.id()));
    }

    private static void requireStandalone(PropertyVisitRequest request) {
        // A linked Request is no longer an operable standalone Request root for an Operations Executive.
        if (request.getSession() != null) throw hidden();
    }

    private static void requireDraft(VisitSession session) {
        if (session.getStatus() != VisitSessionStatus.DRAFT)
            throw new VisitOperationsConflictException("Only draft sessions can be changed");
    }

    private static Scope scopeOf(SupportedCity city, OperatingTeam team) {
        return city == null || team == null ? null : new Scope(city.getId(), team.getId());
    }

    private static EntityNotFoundException hidden() {
        return new EntityNotFoundException(HIDDEN_TARGET);
    }

    /** The admitted caller: the authenticated User plus whether Pathome Admin authority applies. */
    public record Actor(User user, boolean admin) {
        public Long id() { return user.getId(); }
    }

    private record Scope(Long cityId, Long teamId) {}
}
