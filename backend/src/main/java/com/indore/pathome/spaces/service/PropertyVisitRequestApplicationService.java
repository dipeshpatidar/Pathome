package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.CreatePropertyVisitRequest;
import com.indore.pathome.spaces.dto.PropertyVisitRequestAcknowledgement;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.CreateOwnVisitRequestCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssistanceCommandStatusView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.OwnVisitRequestView;
import com.indore.pathome.spaces.entity.LeadAssistanceCommandReceipt;
import com.indore.pathome.spaces.entity.LeadVisitAssociationStatus;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared Request application seam. Existing self-service and assisted exact-property commands both
 * use this policy; lead ownership, association validity and enquiry OTP never gate ordinary Request
 * creation.
 */
@Service
public class PropertyVisitRequestApplicationService {
    private static final int MAX_PREFERRED_AREAS_LENGTH = 500;
    private static final int MAX_MOVE_IN_TIMING_LENGTH = 160;
    private static final int MAX_PREFERRED_VISIT_TIMING_LENGTH = 240;
    private static final int MAX_VISIT_NOTE_LENGTH = 2000;
    private static final EnumSet<VisitRequestStatus> ELIGIBLE_EXISTING = EnumSet.of(
            VisitRequestStatus.RECEIVED, VisitRequestStatus.COORDINATING,
            VisitRequestStatus.SCHEDULED);

    private final UserRepository users;
    private final ListingRepository listings;
    private final PropertyVisitRequestRepository requests;
    private final PropertyVisitRequestAttemptService attempts;
    private final LeadAssistanceCommandReceiptRepository receipts;
    private final LeadDataProtection protection;
    private final ObjectMapper objectMapper;

    public PropertyVisitRequestApplicationService(
            UserRepository users,
            ListingRepository listings,
            PropertyVisitRequestRepository requests,
            PropertyVisitRequestAttemptService attempts,
            LeadAssistanceCommandReceiptRepository receipts,
            LeadDataProtection protection,
            ObjectMapper objectMapper) {
        this.users = users;
        this.listings = listings;
        this.requests = requests;
        this.attempts = attempts;
        this.receipts = receipts;
        this.protection = protection;
        this.objectMapper = objectMapper;
    }

    /**
     * Existing public Request endpoint parity: authenticated User.id remains authoritative and an
     * existing row is returned without mutation.
     */
    public LegacyRequestResult requestLegacy(Long tenantId, Long listingId,
                                             CreatePropertyVisitRequest command) {
        User tenant = requireTenant(tenantId);
        requireListing(listingId);
        ValidatedRequest validated = validate(command);
        Optional<PropertyVisitRequest> existing = requests.findByTenantIdAndListingId(tenantId, listingId);
        if (existing.isPresent()) {
            return new LegacyRequestResult(acknowledgement(existing.get(), false), false);
        }
        PropertyVisitRequest created;
        boolean wasCreated;
        try {
            var attempt = attempts.create(tenant, listingId, command, null, null, validated, null);
            created = attempt.request();
            wasCreated = attempt.created();
        } catch (DataIntegrityViolationException duplicate) {
            if (!isTenantListingUniqueFailure(duplicate)) throw duplicate;
            created = requests.findByTenantIdAndListingId(tenantId, listingId)
                    .orElseThrow(() -> duplicate);
            wasCreated = false;
        }
        return new LegacyRequestResult(acknowledgement(created, wasCreated), wasCreated);
    }

    /**
     * The assisted command creates or deliberately acknowledges only the caller's own Request.
     * Optional Lead association is handled after this method's Request transaction commits.
     */
    public OwnVisitRequestView createOwn(Long tenantId, CreateOwnVisitRequestCommand command) {
        validateOwn(command);
        User tenant = requireTenant(tenantId);
        UUID operationId = command.operationId();
        String payloadHash = payloadHash(command);
        var matchingReceipts = receipts.findAllByActor_IdAndOperationId(tenantId, operationId);
        if (matchingReceipts.size() > 1) {
            throw hidden();
        }
        LeadAssistanceCommandReceipt replay = matchingReceipts.stream().findFirst().orElse(null);
        if (replay != null) {
            if (!PropertyVisitRequestAttemptService.COMMAND_ACTION.equals(replay.getAction())
                    && !PropertyVisitRequestAttemptService.ACKNOWLEDGE_ACTION.equals(
                    replay.getAction())) {
                throw hidden();
            }
            PropertyVisitRequest stored = storedOwnRequest(tenantId, replay);
            if (!Objects.equals(stored.getListing().getId(), command.listingId())) {
                throw hidden();
            }
            if (!Objects.equals(replay.getPayloadHash(), payloadHash)) {
                throw new VisitOperationsConflictException(
                        "This idempotency key was already used with a different Visit Request.");
            }
            return ownView(stored, false, false, LeadVisitAssociationStatus.UNAVAILABLE,
                    "Visit Request was already recorded for this account.");
        }

        Optional<PropertyVisitRequest> existing = requests.findByTenantIdAndListingId(
                tenantId, command.listingId());
        if (existing.isPresent()) {
            PropertyVisitRequest request = existing.get();
            requireEligibleExisting(request);
            if (!command.acknowledgeExistingRequest()) {
                return ownView(request, false, true, LeadVisitAssociationStatus.UNAVAILABLE,
                        "You already have a Visit Request for this property. Review its current status before continuing.");
            }
            PropertyVisitRequest acknowledged = attempts.acknowledge(tenant, request.getId(),
                    command.listingId(), operationId, payloadHash,
                    command.expectedRequestVersion());
            return ownView(acknowledged, false, false, LeadVisitAssociationStatus.UNAVAILABLE,
                    "The existing Visit Request was deliberately acknowledged. No duplicate request or routing was created.");
        }

        requireListingVersion(command.listingId(), command.expectedListingVersion());
        ValidatedRequest validated = validate(command.request());
        PropertyVisitRequest created;
        try {
            var attempt = attempts.create(tenant, command.listingId(), command.request(),
                    operationId, payloadHash, validated, command.expectedListingVersion());
            created = attempt.request();
            if (!attempt.created()) {
                return ownView(created, false, false, LeadVisitAssociationStatus.UNAVAILABLE,
                        "Visit Request was already recorded for this account.");
            }
        } catch (DataIntegrityViolationException duplicate) {
            if (!isTenantListingUniqueFailure(duplicate)) throw duplicate;
            // The uniqueness loser is fully rolled back before this fresh read. No failed transaction
            // is reused and no duplicate Request or routing task is created.
            created = requests.findByTenantIdAndListingId(tenantId, command.listingId())
                    .orElseThrow(() -> duplicate);
            requireEligibleExisting(created);
            if (!command.acknowledgeExistingRequest()) {
                return ownView(created, false, true, LeadVisitAssociationStatus.UNAVAILABLE,
                        "A concurrent Visit Request now exists. Review its status before continuing.");
            }
            created = attempts.acknowledge(tenant, created.getId(), command.listingId(),
                    operationId, payloadHash, command.expectedRequestVersion());
            return ownView(created, false, false, LeadVisitAssociationStatus.UNAVAILABLE,
                    "The concurrent Visit Request was deliberately acknowledged. No duplicate request or routing was created.");
        }
        return ownView(created, true, false, LeadVisitAssociationStatus.UNAVAILABLE,
                "Visit Request recorded and entered normal City/Team intake. This is not a confirmed visit.");
    }

    @Transactional(readOnly = true)
    public PropertyVisitRequest requireOwnRequest(Long tenantId, Long requestId) {
        PropertyVisitRequest request = requests.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
        if (request.getTenant() == null || !tenantId.equals(request.getTenant().getId())) {
            throw new EntityNotFoundException("Visit Request not found");
        }
        return request;
    }

    @Transactional(readOnly = true)
    public AssistanceCommandStatusView commandStatus(Long tenantId, UUID operationId) {
        var matching = receipts.findAllByActor_IdAndOperationId(tenantId, operationId);
        if (matching.size() > 1) throw hidden();
        LeadAssistanceCommandReceipt receipt = matching.stream().findFirst().orElse(null);
        if (receipt == null) {
            return new AssistanceCommandStatusView("NOT_RECORDED",
                    PropertyVisitRequestAttemptService.COMMAND_ACTION, null, null);
        }
        if (!PropertyVisitRequestAttemptService.COMMAND_ACTION.equals(receipt.getAction())
                && !PropertyVisitRequestAttemptService.ACKNOWLEDGE_ACTION.equals(
                receipt.getAction())) throw hidden();
        PropertyVisitRequest request = storedOwnRequest(tenantId, receipt);
        return new AssistanceCommandStatusView("RECORDED",
                receipt.getAction(),
                request.getId().toString(), receipt.getCompletedAt());
    }

    private User requireTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new AccessDeniedException("Authenticated Tenant identity is required");
        }
        return users.findById(tenantId)
                .filter(user -> user.getRole() == Role.ROLE_TENANT)
                .orElseThrow(() -> new AccessDeniedException("Tenant account is unavailable"));
    }

    private void requireListing(Long listingId) {
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        if (listing.getId() == null) {
            throw new EntityNotFoundException("Property not found");
        }
        if (listing.getStatus() != ListingStatus.ACTIVE
                || listing.getWorkflowStatus() != ListingWorkflowStatus.PUBLISHED) {
            throw new EntityNotFoundException("Property not found");
        }
    }

    private void requireListingVersion(Long listingId, Long expectedVersion) {
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new VisitOperationsConflictException(
                        "This property is no longer accepting visit requests"));
        if (expectedVersion == null || !expectedVersion.equals(listing.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The property changed while you were reviewing it. Refresh and try again.");
        }
        if (listing.getStatus() != ListingStatus.ACTIVE
                || listing.getWorkflowStatus() != ListingWorkflowStatus.PUBLISHED) {
            throw new VisitOperationsConflictException(
                    "This property is no longer accepting visit requests");
        }
    }

    private PropertyVisitRequest storedOwnRequest(Long tenantId, LeadAssistanceCommandReceipt receipt) {
        Long requestId = LeadService.parseStoredPositiveLongReference(
                receipt.getResultReference());
        PropertyVisitRequest request = requests.findById(requestId)
                .orElseThrow(PropertyVisitRequestApplicationService::hidden);
        if (request.getTenant() == null || !tenantId.equals(request.getTenant().getId())) {
            throw hidden();
        }
        return request;
    }

    private static void requireEligibleExisting(PropertyVisitRequest request) {
        VisitRequestStatus status = request.getStatusValue();
        if (!ELIGIBLE_EXISTING.contains(status)) {
            throw new VisitOperationsConflictException(
                    "This property has prior terminal or unsupported Visit Request history and cannot be duplicated or resurrected.");
        }
    }

    private ValidatedRequest validate(CreatePropertyVisitRequest request) {
        if (request == null) throw new IllegalArgumentException("Visit Request details are required");
        if (request.budgetMin() != null && request.budgetMin().signum() < 0
                || request.budgetMax() != null && request.budgetMax().signum() < 0) {
            throw new IllegalArgumentException("Budget values cannot be negative");
        }
        if (request.budgetMin() != null && request.budgetMax() != null
                && request.budgetMin().compareTo(request.budgetMax()) > 0) {
            throw new IllegalArgumentException("Minimum budget cannot exceed maximum budget");
        }
        String preferredTiming = requiredText(request.preferredVisitTiming(),
                MAX_PREFERRED_VISIT_TIMING_LENGTH, "Preferred visit timing");
        String preferredAreas = optionalText(request.preferredAreas(),
                MAX_PREFERRED_AREAS_LENGTH, "Preferred areas");
        String moveInTiming = optionalText(request.moveInTiming(),
                MAX_MOVE_IN_TIMING_LENGTH, "Move-in timing");
        String note = optionalText(request.note(), MAX_VISIT_NOTE_LENGTH, "Additional note");
        var availability = SchedulingWindowValidator.optional(request.availabilityStartAt(),
                request.availabilityEndAt(), request.availabilityZoneId(), request.preferredAt(),
                Instant.now(), true);
        return new ValidatedRequest(preferredTiming, preferredAreas, moveInTiming, note, availability);
    }

    private void validateOwn(CreateOwnVisitRequestCommand command) {
        if (command == null || command.operationId() == null || command.listingId() == null
                || command.expectedListingVersion() == null || command.request() == null) {
            throw new IllegalArgumentException(
                    "Operation ID, exact property/version and Request details are required");
        }
        if (!command.explicitVisitIntent()) {
            throw new IllegalArgumentException("Explicit visit intent is required");
        }
        if (command.acknowledgeExistingRequest() && command.expectedRequestVersion() == null) {
            throw new IllegalArgumentException(
                    "Acknowledging an existing Visit Request requires its current version");
        }
        if (!command.acknowledgeExistingRequest() && command.expectedRequestVersion() != null) {
            throw new IllegalArgumentException(
                    "Expected Request version is only valid for explicit acknowledgement");
        }
    }

    private String payloadHash(Object command) {
        try {
            return protection.keyedDigest("TENANT_VISIT_REQUEST_PAYLOAD",
                    objectMapper.writeValueAsString(command));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Visit Request command could not be fingerprinted");
        }
    }

    private static String requiredText(String value, int max, String label) {
        String normalized = optionalText(value, max, label);
        if (normalized == null) throw new IllegalArgumentException(label + " is required");
        return normalized;
    }

    private static String optionalText(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }

    private static boolean isTenantListingUniqueFailure(DataIntegrityViolationException failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("uk_property_visit_request_tenant_listing")
                    || message.contains("tenant_id_listing_id"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static PropertyVisitRequestAcknowledgement acknowledgement(
            PropertyVisitRequest request, boolean created) {
        return new PropertyVisitRequestAcknowledgement(request.getId(),
                request.getListing().getId(), request.getStatus(),
                created ? "Visit request received. We'll check property availability and suitable nearby options before confirming your Visit Session."
                        : "Your existing Visit Request was retained. No duplicate request was created.",
                request.getCreatedAt());
    }

    private static OwnVisitRequestView ownView(PropertyVisitRequest request, boolean created,
                                               boolean requiresAcknowledgement,
                                               LeadVisitAssociationStatus associationStatus,
                                               String message) {
        return new OwnVisitRequestView(request.getId(), request.getListing().getId(),
                request.getVersion(), request.getStatus(), created, requiresAcknowledgement,
                associationStatus,
                message, request.getCreatedAt());
    }

    private static EntityNotFoundException hidden() {
        return new EntityNotFoundException("Visit Request not found");
    }

    public record ValidatedRequest(String preferredVisitTiming, String preferredAreas,
                                   String moveInTiming, String note,
                                   Optional<SchedulingWindowValidator.Window> availability) {}

    public record LegacyRequestResult(PropertyVisitRequestAcknowledgement acknowledgement,
                                      boolean created) {}
}
