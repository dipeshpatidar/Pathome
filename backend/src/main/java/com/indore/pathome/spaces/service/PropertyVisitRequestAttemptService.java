package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.CreatePropertyVisitRequest;
import com.indore.pathome.spaces.entity.LeadAssistanceCommandReceipt;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One clean transaction for a new Tenant Request, trusted intake enrollment and typed receipt. */
@Service
public class PropertyVisitRequestAttemptService {
    public static final String COMMAND_ACTION = "TENANT_VISIT_REQUEST";
    public static final String ACKNOWLEDGE_ACTION = "TENANT_VISIT_REQUEST_ACKNOWLEDGE";

    private final UserRepository users;
    private final ListingRepository listings;
    private final PropertyVisitRequestRepository requests;
    private final VisitSessionRepository sessions;
    private final TrustedOperationalIntakeService trustedIntake;
    private final LeadAssistanceCommandReceiptRepository receipts;
    private final OperationalAuditService audit;
    private final DatabaseClock databaseClock;
    private final EntityManager entityManager;

    public PropertyVisitRequestAttemptService(UserRepository users, ListingRepository listings,
                                              PropertyVisitRequestRepository requests,
                                              VisitSessionRepository sessions,
                                              TrustedOperationalIntakeService trustedIntake,
                                              LeadAssistanceCommandReceiptRepository receipts,
                                              OperationalAuditService audit,
                                              DatabaseClock databaseClock,
                                              EntityManager entityManager) {
        this.users = users;
        this.listings = listings;
        this.requests = requests;
        this.sessions = sessions;
        this.trustedIntake = trustedIntake;
        this.receipts = receipts;
        this.audit = audit;
        this.databaseClock = databaseClock;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreateResult create(User tenant, Long listingId, CreatePropertyVisitRequest command,
                               UUID operationId, String payloadHash,
                               PropertyVisitRequestApplicationService.ValidatedRequest validated,
                               Long expectedListingVersion) {
        Long tenantId = tenant == null ? null : tenant.getId();
        if (tenantId == null) {
            throw new AccessDeniedException("Tenant account is unavailable");
        }
        if (operationId != null) lockOperation(tenantId, operationId);
        tenant = users.findLockedById(tenantId)
                .filter(user -> user.getRole() == Role.ROLE_TENANT)
                .orElseThrow(() -> new AccessDeniedException("Tenant account is unavailable"));
        listings.lockForSchedulingById(listingId)
                .orElseThrow(() -> new VisitOperationsConflictException(
                        "This property is no longer accepting visit requests"));
        if (operationId != null) {
            LeadAssistanceCommandReceipt existing = receipts
                    .findByActor_IdAndOperationIdAndAction(
                            tenantId, operationId, COMMAND_ACTION)
                    .orElse(null);
            if (existing != null) {
                if (!Objects.equals(existing.getPayloadHash(), payloadHash)) {
                    throw new VisitOperationsConflictException(
                            "This idempotency key was already used with a different Visit Request.");
                }
                PropertyVisitRequest request = requests.findById(
                                LeadService.parseStoredPositiveLongReference(
                                        existing.getResultReference()))
                        .filter(value -> value.getTenant() != null
                                && tenantId.equals(value.getTenant().getId())
                                && value.getListing() != null
                                && listingId.equals(value.getListing().getId()))
                        .orElseThrow(PropertyVisitRequestAttemptService::hidden);
                return new CreateResult(request, false);
            }
        }
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new VisitOperationsConflictException(
                        "This property is no longer accepting visit requests"));
        entityManager.refresh(listing, LockModeType.PESSIMISTIC_WRITE);
        if (listing.getStatus() != ListingStatus.ACTIVE
                || listing.getWorkflowStatus() != ListingWorkflowStatus.PUBLISHED) {
            throw new VisitOperationsConflictException(
                    "This property is no longer accepting visit requests");
        }
        if (expectedListingVersion != null
                && !expectedListingVersion.equals(listing.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The property changed while you were reviewing it. Refresh and try again.");
        }
        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setTenant(tenant);
        request.setListing(listing);
        request.setBudgetMin(command.budgetMin());
        request.setBudgetMax(command.budgetMax());
        request.setPreferredAreas(validated.preferredAreas());
        request.setMoveInTiming(validated.moveInTiming());
        request.setPreferredVisitTiming(validated.preferredVisitTiming());
        validated.availability().ifPresent(window -> {
            request.setAvailabilityStartAt(window.startsAt());
            request.setAvailabilityEndAt(window.endsAt());
            request.setAvailabilityZoneId(window.zoneId());
            request.setPreferredAt(window.preferredAt());
        });
        request.setTenantNote(validated.note());
        PropertyVisitRequest saved = trustedIntake.admitNewTenantIntakeAndSave(request);
        Instant now = databaseClock.now();
        audit.recordUserEvent(tenant, "TENANT_VISIT_REQUEST_CREATED",
                "PROPERTY_VISIT_REQUEST", saved.getId(), "EXPLICIT_CUSTOMER_INTENT",
                saved.getSupportedCity(), saved.getOperatingTeam(), Map.of("listingId", listingId));
        if (operationId != null) {
            receipts.saveAndFlush(new LeadAssistanceCommandReceipt(tenant, operationId,
                    COMMAND_ACTION, payloadHash, saved.getId().toString(), now));
        }
        return new CreateResult(saved, true);
    }

    /**
     * Durable acknowledgement is a distinct typed outcome from Request creation. It follows the
     * canonical Session-root lock order and never mutates or resurrects the referenced Request.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PropertyVisitRequest acknowledge(User tenant, Long requestId, Long leadListingId,
                                            UUID operationId, String payloadHash,
                                            Long expectedRequestVersion) {
        if (tenant == null || tenant.getId() == null || requestId == null
                || requestId <= 0 || leadListingId == null || leadListingId <= 0) {
            throw new AccessDeniedException("Tenant account is unavailable");
        }
        Long tenantId = tenant.getId();
        lockOperation(tenantId, operationId);
        tenant = users.findLockedById(tenantId)
                .filter(user -> user.getRole() == Role.ROLE_TENANT)
                .orElseThrow(() -> new AccessDeniedException("Tenant account is unavailable"));
        PropertyVisitRequestRepository.RequestTopologyRow topology =
                requests.findTopologyById(requestId)
                        .filter(row -> tenantId.equals(row.getTenantId()))
                        .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                                "Visit Request not found"));

        VisitSession session = null;
        PropertyVisitRequest request;
        if (topology.getSessionId() == null) {
            request = requests.findLockedByIdAndTenantId(requestId, tenantId)
                    .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                            "Visit Request not found"));
            if (request.getSession() != null) {
                throw topologyChanged();
            }
        } else {
            session = sessions.findLockedById(topology.getSessionId())
                    .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                            "Visit Request not found"));
            request = requests.findLockedByIdAndSessionId(requestId, session.getId())
                    .filter(value -> tenantId.equals(value.getTenant().getId()))
                    .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                            "Visit Request not found"));
        }
        if (!Objects.equals(request.getListing().getId(), leadListingId)) {
            throw new jakarta.persistence.EntityNotFoundException("Visit Request not found");
        }
        if (expectedRequestVersion == null
                || !Objects.equals(request.getVersion(), expectedRequestVersion)) {
            throw new VisitOperationsConflictException(
                    "Your existing Visit Request changed. Refresh its current status before acknowledging it.");
        }
        requireAcknowledgementTopology(request, session);
        LeadAssistanceCommandReceipt existing = receipts
                .findByActor_IdAndOperationIdAndAction(tenantId, operationId, ACKNOWLEDGE_ACTION)
                .orElse(null);
        if (existing != null) {
            Long storedRequestId = LeadService.parseStoredPositiveLongReference(
                    existing.getResultReference());
            if (!requestId.equals(storedRequestId)) throw hidden();
            if (!Objects.equals(existing.getPayloadHash(), payloadHash)) {
                throw new VisitOperationsConflictException(
                        "This idempotency key was already used with a different Visit Request acknowledgement.");
            }
            return request;
        }
        Instant now = databaseClock.now();
        receipts.saveAndFlush(new LeadAssistanceCommandReceipt(tenant, operationId,
                ACKNOWLEDGE_ACTION, payloadHash, request.getId().toString(), now));
        return request;
    }

    private static void requireAcknowledgementTopology(PropertyVisitRequest request,
                                                       VisitSession session) {
        VisitRequestStatus status = request.getStatusValue();
        if (status != VisitRequestStatus.RECEIVED
                && status != VisitRequestStatus.COORDINATING
                && status != VisitRequestStatus.SCHEDULED) {
            throw new VisitOperationsConflictException(
                    "This property has prior terminal or unsupported Visit Request history and cannot be acknowledged as current.");
        }
        if (session == null) {
            if (status == VisitRequestStatus.SCHEDULED) {
                throw topologyChanged();
            }
            return;
        }
        if (session.getTenant() == null
                || !Objects.equals(session.getTenant().getId(), request.getTenant().getId())
                || !Objects.equals(session.getId(), request.getSession().getId())) {
            throw topologyChanged();
        }
        if (status == VisitRequestStatus.SCHEDULED) {
            if (session.getStatus() != VisitSessionStatus.SCHEDULED
                    && session.getStatus() != VisitSessionStatus.STARTED) {
                throw topologyChanged();
            }
            return;
        }
        if (status == VisitRequestStatus.COORDINATING
                && session.getStatus() != VisitSessionStatus.DRAFT) {
            throw topologyChanged();
        }
    }

    private static VisitOperationsConflictException topologyChanged() {
        return new VisitOperationsConflictException(
                "Your Visit Request is linked to unsupported or changed Visit history. Refresh before continuing.");
    }

    private static jakarta.persistence.EntityNotFoundException hidden() {
        return new jakarta.persistence.EntityNotFoundException("Visit Request not found");
    }

    private void lockOperation(Long tenantId, UUID operationId) {
        entityManager.createNativeQuery(
                        "select pg_advisory_xact_lock(hashtextextended(?::text, 0))")
                .setParameter(1, tenantId + ":TENANT_VISIT_REQUEST:" + operationId)
                .getSingleResult();
    }

    public record CreateResult(PropertyVisitRequest request, boolean created) {}
}
