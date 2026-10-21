package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.TenantVisitAvailabilityCommand;
import com.indore.pathome.spaces.dto.TenantVisitAvailabilityView;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * One bounded, transactional availability attempt.
 *
 * <p>Topology is discovered without a lock. Linked work then locks the Session before the Request;
 * unlinked work locks only the Request. Every fact that decides eligibility is re-read after those
 * locks, and a linkage change aborts the attempt so the orchestrator can retry in canonical order.</p>
 */
@Service
public class TenantVisitAvailabilityTransaction {
    private final PropertyVisitRequestRepository requests;
    private final VisitSessionRepository sessions;
    private final UserRepository users;

    public TenantVisitAvailabilityTransaction(PropertyVisitRequestRepository requests,
                                              VisitSessionRepository sessions,
                                              UserRepository users) {
        this.requests = requests;
        this.sessions = sessions;
        this.users = users;
    }

    @Transactional
    public TenantVisitAvailabilityView attempt(Long authenticatedTenantId, Long requestId,
                                               TenantVisitAvailabilityCommand command) {
        PropertyVisitRequestRepository.RequestTopologyRow topology = requests.findTopologyById(requestId)
                .orElseThrow(TenantVisitAvailabilityTransaction::notFound);
        if (!authenticatedTenantId.equals(topology.getTenantId())) throw notFound();

        PropertyVisitRequest request;
        VisitSession session = null;
        if (topology.getSessionId() == null) {
            request = requests.findLockedById(requestId).orElseThrow(TenantVisitAvailabilityTransaction::notFound);
            if (!authenticatedTenantId.equals(request.getTenant().getId())) throw notFound();
            requireTenantCapability(authenticatedTenantId);
            if (request.getSession() != null) throw new TopologyChangedException();
        } else {
            // Canonical order: the Session root is locked before its linked Request.
            session = sessions.findLockedById(topology.getSessionId())
                    .orElseThrow(TenantVisitAvailabilityTransaction::notFound);
            request = requests.findLockedById(requestId).orElseThrow(TenantVisitAvailabilityTransaction::notFound);
            if (!authenticatedTenantId.equals(request.getTenant().getId())
                    || session.getTenant() == null
                    || !authenticatedTenantId.equals(session.getTenant().getId())) throw notFound();
            requireTenantCapability(authenticatedTenantId);
            if (request.getSession() == null
                    || !Objects.equals(request.getSession().getId(), session.getId())) {
                throw new TopologyChangedException();
            }
        }

        if (!Objects.equals(request.getVersion(), command.expectedRequestVersion()))
            throw new VisitOperationsConflictException("Visit Request changed; reload and retry");

        requirePreBookingState(request, session);

        var window = SchedulingWindowValidator.optional(command.availabilityStartAt(), command.availabilityEndAt(),
                command.availabilityZoneId(), command.preferredAt(), Instant.now(), true);
        if (window.isPresent()) {
            var value = window.get();
            request.setAvailabilityStartAt(value.startsAt());
            request.setAvailabilityEndAt(value.endsAt());
            request.setAvailabilityZoneId(value.zoneId());
            request.setPreferredAt(value.preferredAt());
        } else {
            request.setAvailabilityStartAt(null);
            request.setAvailabilityEndAt(null);
            request.setAvailabilityZoneId(null);
            request.setPreferredAt(null);
        }
        requests.flush();
        return TenantVisitAvailabilityService.view(request);
    }

    private static void requirePreBookingState(PropertyVisitRequest request, VisitSession session) {
        if (session == null) {
            if (request.getStatusValue() != VisitRequestStatus.RECEIVED) {
                throw new VisitOperationsConflictException(
                        "Availability can only be updated before coordination or after it begins in a draft session");
            }
            return;
        }
        if (session.getStatus() != VisitSessionStatus.DRAFT) {
            throw new VisitOperationsConflictException(
                    "Availability can only be updated before the visit is scheduled");
        }
        if (request.getStatusValue() != VisitRequestStatus.COORDINATING) {
            throw new VisitOperationsConflictException(
                    "Availability can only be updated while this request is being coordinated");
        }
    }

    /**
     * Fresh scalar capability check after the canonical locks. The managed Request tenant may be a
     * stale persistence-context snapshot; the persisted users.role is the authority here.
     */
    private void requireTenantCapability(Long authenticatedTenantId) {
        String role = users.findRoleValueById(authenticatedTenantId).orElse(null);
        if (!Role.ROLE_TENANT.name().equals(role) && !Role.ROLE_LANDLORD.name().equals(role))
            throw new AccessDeniedException("Tenant capability required");
    }

    private static EntityNotFoundException notFound() {
        return new EntityNotFoundException("Visit Request not found");
    }

    /** Signal a rollback because the lock-time topology no longer matches the discovery snapshot. */
    static final class TopologyChangedException extends RuntimeException {
        TopologyChangedException() {
            super("Visit Request topology changed during the availability update");
        }
    }
}
