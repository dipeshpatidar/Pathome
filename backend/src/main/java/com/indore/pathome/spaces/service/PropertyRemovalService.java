package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.removal.PropertyRemovalDtos;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Owner-requested, Admin-approved property removal. The request and lifecycle archive commit in one
 * transaction. No method deletes or detaches property, lessor, media, visit or audit history.
 */
@Service
public class PropertyRemovalService {
    private static final int PAGE_SIZE = 20;
    private static final Set<ListingWorkflowStatus> ELIGIBLE = Set.of(
            ListingWorkflowStatus.SUBMITTED,
            ListingWorkflowStatus.UNDER_REVIEW,
            ListingWorkflowStatus.CHANGES_REQUIRED,
            ListingWorkflowStatus.PUBLISHED,
            ListingWorkflowStatus.PAUSED);

    private final PropertyRemovalRequestRepository requests;
    private final ListingRepository listings;
    private final LocalityRepository localities;
    private final LessorProfileRepository lessorProfiles;
    private final UserRepository users;
    private final EmployeeProfileRepository employeeProfiles;
    private final StaffAccessGrantRepository grants;
    private final PropertyUploadDraftRepository drafts;
    private final VisitSessionRepository sessions;
    private final PropertyRevisionEventRepository revisionEvents;
    private final OperationalAuditService audit;
    private final NotificationService notifications;
    private final DatabaseClock clock;
    private final EntityManager entityManager;

    public PropertyRemovalService(PropertyRemovalRequestRepository requests, ListingRepository listings,
                                  LocalityRepository localities, LessorProfileRepository lessorProfiles,
                                  UserRepository users, EmployeeProfileRepository employeeProfiles,
                                  StaffAccessGrantRepository grants, PropertyUploadDraftRepository drafts,
                                  VisitSessionRepository sessions, PropertyRevisionEventRepository revisionEvents,
                                  OperationalAuditService audit, NotificationService notifications,
                                  DatabaseClock clock, EntityManager entityManager) {
        this.requests = requests;
        this.listings = listings;
        this.localities = localities;
        this.lessorProfiles = lessorProfiles;
        this.users = users;
        this.employeeProfiles = employeeProfiles;
        this.grants = grants;
        this.drafts = drafts;
        this.sessions = sessions;
        this.revisionEvents = revisionEvents;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Transactional
    public PropertyRemovalDtos.CreateResult create(Long userId, Long listingId, Long expectedListingVersion,
                                                   PropertyRemovalDtos.CreateRequest command) {
        if (command == null || command.operationId() == null)
            throw new IllegalArgumentException("Operation ID is required");
        if (expectedListingVersion == null) throw new IllegalArgumentException("Current property version is required");
        String reason = optionalReason(command.reason(), 1000);
        String payloadHash = hash("CREATE|" + listingId + "|" + expectedListingVersion + "|" + (reason == null ? "" : reason));
        Optional<PropertyRemovalRequest> prior = requests
                .findByListingIdAndRequesterUserIdAndCreationOperationId(
                        listingId, userId, command.operationId());
        if (prior.isPresent()) {
            requireOwner(userId, prior.get());
            Listing current = listings.findByIdAndOwnerUserId(listingId, userId)
                    .orElseThrow(() -> new EntityNotFoundException("Property not found"));
            requireLinkedLessor(current, userId);
            if (!payloadHash.equals(prior.get().getCreationPayloadHash())) {
                throw conflict("This operation ID is already committed with different removal-request details.");
            }
            return new PropertyRemovalDtos.CreateResult(view(prior.get(), current), false);
        }

        Listing listing = lockOwnedFresh(listingId, userId);
        requireLinkedLessor(listing, userId);
        if (listing.getVersion() == null || !expectedListingVersion.equals(listing.getVersion())) {
            throw conflict("The property changed. Reload before requesting removal.");
        }
        requireEligible(listing);
        Optional<PropertyRemovalRequest> racedReplay = requests
                .findByListingIdAndRequesterUserIdAndCreationOperationId(
                        listingId, userId, command.operationId());
        if (racedReplay.isPresent()) {
            if (!payloadHash.equals(racedReplay.get().getCreationPayloadHash())) {
                throw conflict("This operation ID is already committed with different removal-request details.");
            }
            return new PropertyRemovalDtos.CreateResult(view(racedReplay.get(), listing), false);
        }
        List<PropertyRemovalRequest> pending = requests.findPendingForUpdate(listingId);
        if (!pending.isEmpty()) {
            throw conflict("A property removal request is already pending.");
        }
        PropertyRemovalRequest saved = requests.saveAndFlush(new PropertyRemovalRequest(
                listingId, userId, command.operationId(), payloadHash, clock.now(),
                expectedListingVersion, reason));
        User actor = users.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated User is unavailable"));
        audit.recordUserEvent(actor, "PROPERTY_REMOVAL_REQUESTED", "PROPERTY_REMOVAL_REQUEST",
                saved.getId(), "OWNER_REMOVAL_REQUEST", null, null,
                Map.of("previousVersion", listing.getVersion(), "resultingVersion", listing.getVersion()));
        Map<String, Object> requestedDetails = new LinkedHashMap<>();
        requestedDetails.put("requestId", saved.getId());
        requestedDetails.put("requestedListingVersion", expectedListingVersion);
        if (reason != null) requestedDetails.put("reason", reason);
        revisionEvent(listingId, saved.getId(), "REMOVAL_REQUESTED", userId,
                requestedDetails);
        return new PropertyRemovalDtos.CreateResult(view(saved, listing), true);
    }

    @Transactional(readOnly = true)
    public PropertyRemovalDtos.OwnerHistory ownerHistory(Long userId, Long listingId, int page) {
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        Listing listing = listings.findByIdAndOwnerUserId(listingId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        requireLinkedLessor(listing, userId);
        var rows = requests.findOwnerHistory(listingId, userId, PageRequest.of(page, PAGE_SIZE));
        return new PropertyRemovalDtos.OwnerHistory(rows.getContent().stream()
                .map(row -> view(row, listing)).toList(), page, rows.hasNext());
    }

    @Transactional(readOnly = true)
    public PropertyRemovalDtos.RequestView ownerRequest(Long userId, Long listingId, Long requestId) {
        Listing listing = listings.findByIdAndOwnerUserId(listingId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        requireLinkedLessor(listing, userId);
        PropertyRemovalRequest request = requests.findByIdAndListingIdAndRequesterUserId(
                        requestId, listingId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Removal request not found"));
        return view(request, listing);
    }

    @Transactional
    public PropertyRemovalDtos.RequestView withdraw(Long userId, Long listingId, Long requestId,
                                                    Long expectedRequestVersion,
                                                    PropertyRemovalDtos.WithdrawRequest command) {
        if (expectedRequestVersion == null) throw new IllegalArgumentException("Current request version is required");
        if (command == null || command.operationId() == null)
            throw new IllegalArgumentException("Operation ID is required");
        String payloadHash = hash("WITHDRAW|" + listingId + "|" + requestId + "|" + expectedRequestVersion);
        Listing listing = lockOwnedFresh(listingId, userId);
        requireLinkedLessor(listing, userId);
        PropertyRemovalRequest request = requests.findLockedById(requestId)
                .filter(row -> listingId.equals(row.getListingId()) && userId.equals(row.getRequesterUserId()))
                .orElseThrow(() -> new EntityNotFoundException("Removal request not found"));
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (request.getRequestStatus() != PropertyRemovalRequestStatus.PENDING) {
            if (command.operationId().equals(request.getTerminalOperationId())
                    && payloadHash.equals(request.getTerminalPayloadHash())) {
                return view(request, listing);
            }
            throw conflict("This removal request has already been decided.");
        }
        if (!expectedRequestVersion.equals(request.getRequestVersion())) {
            throw conflict("The removal request changed. Reload before withdrawing it.");
        }
        request.withdraw(userId, command.operationId(), payloadHash, listing.getVersion(), clock.now());
        PropertyRemovalRequest saved = requests.saveAndFlush(request);
        User actor = users.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated User is unavailable"));
        audit.recordUserEvent(actor, "PROPERTY_REMOVAL_WITHDRAWN", "PROPERTY_REMOVAL_REQUEST",
                saved.getId(), "OWNER_WITHDRAWAL", null, null,
                Map.of("previousVersion", expectedRequestVersion,
                        "resultingVersion", saved.getRequestVersion(),
                        "previousCanonicalLocalityId", listing.getCanonicalLocalityId() == null
                                ? 0L : listing.getCanonicalLocalityId()));
        revisionEvent(listingId, saved.getId(), "REMOVAL_WITHDRAWN", userId,
                Map.of("requestId", saved.getId(), "requestVersion", saved.getRequestVersion()));
        return view(saved, listing);
    }

    @Transactional(readOnly = true)
    public PropertyRemovalDtos.AdminQueue adminQueue(Long userId, PropertyRemovalRequestStatus status,
                                                      Long cityId, int page, int size) {
        requireRemovalAdminRead(userId);
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        if (size < 1 || size > 50) throw new IllegalArgumentException("Page size must be between 1 and 50");
        List<Long> cityIds = cityId == null ? List.of() : List.of(cityId);
        var rows = requests.findAdminQueue(status, cityIds, cityId == null,
                PageRequest.of(page, size));
        List<PropertyRemovalRequest> pageRows = rows.getContent();
        List<Long> listingIds = pageRows.stream().map(PropertyRemovalRequest::getListingId)
                .distinct().toList();
        Map<Long, Listing> listingById = listings.findAllById(listingIds).stream()
                .collect(java.util.stream.Collectors.toMap(Listing::getId, listing -> listing));
        List<Long> localityIds = listingById.values().stream()
                .map(Listing::getCanonicalLocalityId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Locality> localityById = localityIds.isEmpty() ? Map.of()
                : localities.findAllById(localityIds).stream()
                    .collect(java.util.stream.Collectors.toMap(Locality::getId, row -> row));
        Map<Long, Long> appointmentCounts = listingIds.isEmpty() ? Map.of()
                : sessions.countAppointmentsForListings(listingIds,
                        List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED))
                    .stream().collect(java.util.stream.Collectors.toMap(
                            VisitSessionRepository.AppointmentCountRow::getListingId,
                            VisitSessionRepository.AppointmentCountRow::getSessionCount));
        List<PropertyRemovalDtos.AdminQueueItem> items = pageRows.stream().map(request -> {
            Listing listing = listingById.get(request.getListingId());
            String city = null;
            String locality = null;
            if (listing != null && listing.getCanonicalLocalityId() != null) {
                Locality row = localityById.get(listing.getCanonicalLocalityId());
                city = row == null || row.getSupportedCity() == null
                        ? null : row.getSupportedCity().getDisplayName();
                locality = row == null ? null : row.getSectorName();
            }
            long appointments = appointmentCounts.getOrDefault(request.getListingId(), 0L);
            return new PropertyRemovalDtos.AdminQueueItem(request.getId(), request.getListingId(),
                    listing == null ? "Property unavailable" : listing.getTitle(), city, locality,
                    request.getRequestStatus(), request.getRequestVersion(),
                    listing == null ? null : listing.getVersion(),
                    listing == null ? null : listing.getWorkflowStatus(), request.getRequestedAt(),
                    request.getOwnerReason(), appointments);
        }).toList();
        return new PropertyRemovalDtos.AdminQueue(items, page, rows.hasNext());
    }

    @Transactional(readOnly = true)
    public PropertyRemovalDtos.AdminDetail adminDetail(Long userId, Long requestId) {
        requireRemovalAdminRead(userId);
        PropertyRemovalRequest request = requests.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Removal request not found"));
        Listing listing = listings.findById(request.getListingId())
                .orElseThrow(() -> new EntityNotFoundException("Removal request not found"));
        String city = null;
        String locality = null;
        if (listing.getCanonicalLocalityId() != null) {
            Locality row = localities.findById(listing.getCanonicalLocalityId()).orElse(null);
            city = row == null || row.getSupportedCity() == null ? null : row.getSupportedCity().getDisplayName();
            locality = row == null ? null : row.getSectorName();
        }
        PropertyUploadDraft open = drafts.findOpenRevisions(listing.getId(),
                        List.of("DRAFT", "REVIEW", "CHANGES_REQUIRED"))
                .stream().findFirst().orElse(null);
        long scheduled = sessions.countAppointmentsForListing(listing.getId(),
                List.of(VisitSessionStatus.SCHEDULED));
        long started = sessions.countAppointmentsForListing(listing.getId(),
                List.of(VisitSessionStatus.STARTED));
        return new PropertyRemovalDtos.AdminDetail(view(request, listing), listing.getTitle(),
                city, locality, scheduled, started,
                open == null ? null : open.getStatus(), open == null ? null : open.getDraftId());
    }

    @Transactional
    public PropertyRemovalDtos.RequestView decide(Long userId, Long requestId,
                                                   Long expectedRequestVersion,
                                                   PropertyRemovalDtos.DecisionRequest command) {
        if (expectedRequestVersion == null) throw new IllegalArgumentException("Current request version is required");
        if (command == null || command.operationId() == null || command.decision() == null
                || command.expectedListingVersion() == null) {
            throw new IllegalArgumentException("Decision, operation ID and current property version are required");
        }
        String reason = requiredReason(command.reason(), 1000);
        requireRemovalAdminRead(userId);
        String payloadHash = hash("DECISION|" + requestId + "|" + expectedRequestVersion + "|"
                + command.decision().name() + "|" + command.expectedListingVersion() + "|" + reason);
        PropertyRemovalRequest observed = requests.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Removal request not found"));
        if (observed.getRequestStatus() != PropertyRemovalRequestStatus.PENDING) {
            if (command.operationId().equals(observed.getTerminalOperationId())
                    && payloadHash.equals(observed.getTerminalPayloadHash())) {
                return view(observed, listings.findById(observed.getListingId()).orElse(null));
            }
            throw conflict("This removal request has already been decided.");
        }
        Long listingId = observed.getListingId();
        listings.lockForSchedulingById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Removal request not found"));
        AdminEvidence evidence = requireRemovalAdminForDecision(userId);
        PropertyRemovalRequest request = requests.findLockedById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Removal request not found"));
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new EntityNotFoundException("Removal request not found"));
        entityManager.refresh(listing, LockModeType.PESSIMISTIC_WRITE);
        if (request.getRequestStatus() != PropertyRemovalRequestStatus.PENDING) {
            if (command.operationId().equals(request.getTerminalOperationId())
                    && payloadHash.equals(request.getTerminalPayloadHash())) {
                return view(request, listing);
            }
            throw conflict("This removal request was decided in another session.");
        }
        if (!expectedRequestVersion.equals(request.getRequestVersion())) {
            throw conflict("The removal request changed. Reload before deciding it.");
        }
        if (!command.expectedListingVersion().equals(listing.getVersion())) {
            throw conflict("The property changed after review. Reload before deciding removal.");
        }
        requireEligible(listing);
        Instant now = clock.now();
        long observedListingVersion = listing.getVersion();
        long resultingListingVersion = command.decision() == PropertyRemovalDtos.DecisionType.APPROVED
                ? Math.addExact(observedListingVersion, 1)
                : observedListingVersion;
        Map<String, Object> authority = Map.of(
                "capability", StaffCapability.STAFF_ADMIN.name(),
                "scopeType", "GLOBAL",
                "grantId", evidence.grantIds().get(0),
                "previousVersion", observedListingVersion,
                "resultingVersion", resultingListingVersion);
        PropertyRemovalRequest saved;
        if (command.decision() == PropertyRemovalDtos.DecisionType.APPROVED) {
            request.approve(userId, command.operationId(), payloadHash, reason,
                    observedListingVersion, resultingListingVersion, now, authority);
            listing.setWorkflowStatus(ListingWorkflowStatus.ARCHIVED);
            listing.setStatus(ListingStatus.CLOSED);
            Listing savedListing = listings.saveAndFlush(listing);
            Long savedVersion = savedListing.getVersion();
            if (savedVersion == null || savedVersion.longValue() != observedListingVersion + 1) {
                throw new IllegalStateException("Property lifecycle version did not advance exactly once");
            }
            if (savedVersion.longValue() != resultingListingVersion) {
                throw new IllegalStateException("Property lifecycle version differs from authority evidence");
            }
            saved = request;
        } else {
            request.reject(userId, command.operationId(), payloadHash, reason,
                    observedListingVersion, now, authority);
            saved = requests.saveAndFlush(request);
        }
        entityManager.detach(saved);
        User actor = users.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated User is unavailable"));
        audit.recordUserEvent(actor, "PROPERTY_REMOVAL_" + saved.getRequestStatus().name(),
                "PROPERTY_REMOVAL_REQUEST", saved.getId(), "ADMIN_REMOVAL_DECISION", null, null,
                Map.of("fromStatus", "PENDING", "toStatus", saved.getRequestStatus().name(),
                        "previousVersion", observedListingVersion,
                        "resultingVersion", resultingListingVersion,
                        "capability", StaffCapability.STAFF_ADMIN.name(),
                        "scopeType", "GLOBAL"));
        revisionEvent(listingId, saved.getId(), "REMOVAL_" + saved.getRequestStatus().name(), userId,
                Map.of("requestId", saved.getId(), "requestVersion", saved.getRequestVersion(),
                        "resultingListingVersion", resultingListingVersion,
                        "reason", reason, "reasonCode", "REMOVAL_" + saved.getRequestStatus().name()));
        notifyOwner(saved);
        return view(saved, listing);
    }

    @Transactional
    public boolean legacyArchiveDenied(Long userId, Long listingId) {
        listings.findByIdAndOwnerUserId(listingId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        throw conflict("Property removal now requires an Admin-approved removal request. "
                + "Open the property and choose Request property removal.");
    }

    /**
     * Fresh persisted authority for ordinary protected reads. Read-only transactions must not acquire
     * grant or User row locks; decisions retain the separate locked authority path below.
     */
    private void requireRemovalAdminRead(Long userId) {
        if (userId == null || userId <= 0 || !Role.ROLE_ADMIN.name().equals(
                users.findRoleValueById(userId).orElse(""))) {
            throw new AccessDeniedException("Current removal-decision authority is required");
        }
        employeeProfiles.findByUserId(userId)
                .filter(EmployeeProfile::isStaffActive)
                .orElseThrow(() -> new AccessDeniedException(
                        "Current removal-decision authority is required"));
        if (!grants.hasEffectiveGlobalAdmin(userId)) {
            throw new AccessDeniedException("Current removal-decision authority is required");
        }
        users.findById(userId)
                .orElseThrow(() -> new AccessDeniedException(
                        "Current removal-decision authority is required"));
    }

    private AdminEvidence requireRemovalAdminForDecision(Long userId) {
        if (userId == null || userId <= 0) throw new AccessDeniedException("Authenticated Admin is required");
        String role = users.findRoleValueById(userId).orElse("");
        if (!Role.ROLE_ADMIN.name().equals(role)) {
            throw new AccessDeniedException("Current removal-decision authority is required");
        }
        EmployeeProfile profile = employeeProfiles.findByUserId(userId)
                .filter(EmployeeProfile::isStaffActive)
                .orElseThrow(() -> new AccessDeniedException("Current removal-decision authority is required"));
        List<StaffAccessGrant> held = grants.findEffectiveGlobalAdminSharedByUserId(userId);
        if (held.isEmpty()) {
            throw new AccessDeniedException("Current removal-decision authority is required");
        }
        users.findSharedById(userId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated Admin is unavailable"));
        return new AdminEvidence(profile.getId(), held.stream().map(StaffAccessGrant::getId).toList());
    }

    private void notifyOwner(PropertyRemovalRequest request) {
        String recipient = String.valueOf(request.getRequesterUserId());
        String eventKey = "property-removal-" + request.getId() + "-" + request.getRequestStatus().name();
        notifications.createNotificationWithEventKeyInCurrentTransaction(
                TargetRole.LANDLORD, recipient,
                request.getRequestStatus() == PropertyRemovalRequestStatus.APPROVED
                        ? "Property removal approved" : "Property removal request rejected",
                "Your property removal request has a final decision. Open the property for details.",
                null, "PROPERTY", "info", NotificationAuthorizationClass.RECIPIENT,
                null, eventKey);
    }

    private PropertyRemovalDtos.RequestView view(PropertyRemovalRequest request, Listing listing) {
        List<String> actions = new ArrayList<>();
        if (request.getRequestStatus() == PropertyRemovalRequestStatus.PENDING
                && request.getRequesterUserId() != null) actions.add("WITHDRAW");
        return new PropertyRemovalDtos.RequestView(request.getId(), request.getListingId(),
                request.getRequestStatus(), request.getRequestVersion(),
                listing == null ? null : listing.getWorkflowStatus(),
                listing == null ? null : listing.getVersion(), request.getRequestedAt(),
                request.getDecidedAt(), request.getOwnerReason(), request.getDecisionReason(),
                List.copyOf(actions));
    }

    private void requireEligible(Listing listing) {
        if (listing == null || listing.getOwnerUserId() == null
                || listing.getWorkflowStatus() == null
                || !ELIGIBLE.contains(listing.getWorkflowStatus())
                || listing.getStatus() == ListingStatus.CLOSED) {
            throw conflict("This property cannot be requested for removal in its current state.");
        }
    }

    private void requireLinkedLessor(Listing listing, Long userId) {
        if (listing.getLessorProfileId() == null) {
            throw new EntityNotFoundException("Property not found");
        }
        LessorProfile profile = lessorProfiles.findById(listing.getLessorProfileId())
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        if (!userId.equals(profile.getLinkedUserId()) || !userId.equals(listing.getOwnerUserId())) {
            throw new EntityNotFoundException("Property not found");
        }
    }

    private void requireOwner(Long userId, PropertyRemovalRequest request) {
        if (!userId.equals(request.getRequesterUserId())) {
            throw new EntityNotFoundException("Removal request not found");
        }
    }

    private Listing lockOwnedFresh(Long listingId, Long userId) {
        listings.lockOwnedId(listingId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        Listing listing = listings.findByIdAndOwnerUserId(listingId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        entityManager.refresh(listing, LockModeType.PESSIMISTIC_WRITE);
        return listing;
    }

    private void revisionEvent(Long listingId, Long requestId, String eventType, Long actorId,
                               Map<String, Object> details) {
        revisionEvents.save(new PropertyRevisionEvent(listingId,
                requestId == null ? null : "removal:" + requestId, eventType, actorId,
                clock.now(), null, details));
    }

    private static String optionalReason(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > max) throw new IllegalArgumentException("Reason is too long");
        return trimmed;
    }

    private static String requiredReason(String value, int max) {
        String reason = optionalReason(value, max);
        if (reason == null) throw new IllegalArgumentException("A decision reason is required");
        return reason;
    }

    private static DraftConflictException conflict(String message) {
        return new DraftConflictException("property-removal", 0, message);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record AdminEvidence(Long profileId, List<Long> grantIds) {}
}

