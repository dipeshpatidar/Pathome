package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.OperationsLessorContactStatus;
import com.indore.pathome.spaces.dto.OperationsLessorContactView;
import com.indore.pathome.spaces.entity.LessorProfile;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionItem;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionItemRepository;
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
 * Phase 2A A1a secure lessor-contact read seam.
 *
 * <p>Two narrowly scoped read paths disclose a lessor's name and mobile to the <em>currently
 * owning</em> Operations Executive only: a standalone claimed {@code RECEIVED} Request, and an
 * active item of a currently-owned Visit Session. Contact is resolved exclusively through the
 * approved canonical relationship {@code Listing.lessorProfileId -> LessorProfile}; no legacy owner
 * field, User value, or synthetic number is ever used, and nothing is created or mutated.</p>
 *
 * <p>Authorization is re-established inside a single writable transaction after the authoritative
 * root and the shared City/Team/staff/grant guard rows are locked, so a concurrent ownership
 * transfer, grant revocation, staff deactivation or City/Team deactivation can never race a live
 * PII reveal. The required audit event is written in the same transaction, so the contact number is
 * only released after the transaction (including its audit row) has committed.</p>
 */
@Service
public class OperationsLessorContactService {

    /** Session-root statuses that permit a live contact reveal. Terminal/historical work never does. */
    private static final EnumSet<VisitSessionStatus> REVEALABLE_SESSION_STATUSES =
            EnumSet.of(VisitSessionStatus.DRAFT, VisitSessionStatus.SCHEDULED,
                    VisitSessionStatus.STARTED, VisitSessionStatus.INTERRUPTED);

    private final PropertyVisitRequestRepository requests;
    private final VisitSessionRepository sessions;
    private final VisitSessionItemRepository items;
    private final ListingRepository listings;
    private final LessorProfileRepository lessorProfiles;
    private final UserRepository users;
    private final StaffAccessService access;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;

    public OperationsLessorContactService(PropertyVisitRequestRepository requests, VisitSessionRepository sessions,
            VisitSessionItemRepository items, ListingRepository listings, LessorProfileRepository lessorProfiles,
            UserRepository users, StaffAccessService access, OperationalSecurityGuards guards,
            OperationalAuditService audit) {
        this.requests = requests;
        this.sessions = sessions;
        this.items = items;
        this.listings = listings;
        this.lessorProfiles = lessorProfiles;
        this.users = users;
        this.access = access;
        this.guards = guards;
        this.audit = audit;
    }

    /**
     * Contact for an unlinked, currently-owned {@code RECEIVED} standalone Request.
     *
     * <p>A Request that has been linked to a Visit Session is no longer an operable Request root and
     * is reported as not found, so the Request contact route can never outlive its ownership root.</p>
     */
    @Transactional
    public OperationsLessorContactView requestContact(Long actorId, Long requestId) {
        requireCoordinatorEligibility(actorId);
        if (requestId == null || requestId <= 0) {
            throw new IllegalArgumentException("Visit Request ID must be positive");
        }
        PropertyVisitRequest request = requests.findLockedById(requestId)
                .orElseThrow(OperationsLessorContactService::notFound);
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

        OperationsLessorContactView view = resolveContact(request.getListing().getId());
        auditReveal(view, actorId, "PROPERTY_VISIT_REQUEST", request.getId(),
                request.getSupportedCity(), request.getOperatingTeam());
        return view;
    }

    /**
     * Contact for one active, non-removed item of a currently-owned Visit Session.
     *
     * <p>Each property resolves its own lessor independently, so a Session spanning several lessors
     * never cross-associates contact. Session-root ownership, status, item membership and removal are
     * all re-checked under the Session-root lock.</p>
     */
    @Transactional
    public OperationsLessorContactView sessionItemContact(Long actorId, Long sessionId, Long itemId) {
        requireCoordinatorEligibility(actorId);
        if (sessionId == null || sessionId <= 0) {
            throw new IllegalArgumentException("Visit Session ID must be positive");
        }
        if (itemId == null || itemId <= 0) {
            throw new IllegalArgumentException("Visit Session Item ID must be positive");
        }
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(OperationsLessorContactService::notFound);

        Scope scope = requireScope(session.getSupportedCity(), session.getOperatingTeam());
        lockScopeGuards(scope, actorId);
        requireActiveScope(scope);
        requireFreshCoordinator(actorId, scope.teamId());

        if (!REVEALABLE_SESSION_STATUSES.contains(session.getStatus())
                || !session.isOperationalScopeReady()
                || !isCoordinator(session.getCoordinator(), actorId)) {
            throw notFound();
        }

        VisitSessionItem item = items.findBySessionIdAndId(sessionId, itemId).orElseThrow(OperationsLessorContactService::notFound);
        if (item.getRemovedAt() != null || item.getListing() == null) throw notFound();

        OperationsLessorContactView view = resolveContact(item.getListing().getId());
        auditReveal(view, actorId, "VISIT_SESSION_ITEM", item.getId(),
                session.getSupportedCity(), session.getOperatingTeam());
        return view;
    }

    /**
     * Coarse endpoint eligibility: 403 when the caller holds no current, usable
     * {@code OPS_COORDINATE@TEAM} authority. A specific target that is not owned by the caller stays
     * 404 so target existence never leaks.
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

    /**
     * Fresh, target-scoped authorization re-evaluated against database time after the guard rows are
     * locked. A revoked/expired grant, an inactive EmployeeProfile, or an inactive Team/City fails
     * closed as not found.
     */
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

    private OperationsLessorContactView resolveContact(Long listingId) {
        Listing listing = listingId == null ? null : listings.findById(listingId).orElse(null);
        if (listing == null) return OperationsLessorContactView.unavailable(listingId);
        Long profileId = listing.getLessorProfileId();
        LessorProfile profile = profileId == null ? null : lessorProfiles.findById(profileId).orElse(null);
        return OperationsLessorContactResolver.resolve(listing.getId(), listing.getOwnerUserId(), profile);
    }

    /**
     * Synchronous, PII-safe audit for an actual contact reveal. Unavailable contacts produce no audit
     * event because no PII is disclosed. No phone digits or lessor name ever enter the audit details.
     */
    private void auditReveal(OperationsLessorContactView view, Long actorId, String targetType, Long targetId,
            SupportedCity city, OperatingTeam team) {
        if (view.status() != OperationsLessorContactStatus.CONTACT_AVAILABLE) return;
        audit.recordUserEvent(users.getReferenceById(actorId), "LESSOR_CONTACT_REVEALED", targetType, targetId,
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
