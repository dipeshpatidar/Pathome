package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.*;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class VisitOperationsService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PropertyVisitRequestRepository requests;
    private final VisitSessionRepository sessions;
    private final UserRepository users;
    private final VisitPolicyRepository visitPolicies;
    private final VisitSessionItemRepository items;
    private final ListingRepository listings;
    private final LocalityRepository localities;
    private final VisitOperationsAuthorizationService authorization;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final VisitSchedulingRecommendationService recommendations;
    private final VisitSchedulingDecisionRepository decisions;
    private final OperationalSecurityGuards operationalGuards;
    private final OperationalAuditService operationalAudit;

    public VisitOperationsService(PropertyVisitRequestRepository requests,
                                  VisitSessionRepository sessions,
                                  UserRepository users,
                                  VisitPolicyRepository visitPolicies,
                                  VisitSessionItemRepository items,
                                  ListingRepository listings,
                                  LocalityRepository localities,
                                  VisitOperationsAuthorizationService authorization,
                                  ApplicationEventPublisher events,
                                  EntityManager entityManager,
                                  VisitSchedulingRecommendationService recommendations,
                                  VisitSchedulingDecisionRepository decisions,
                                  OperationalSecurityGuards operationalGuards,
                                  OperationalAuditService operationalAudit) {
        this.requests = requests;
        this.sessions = sessions;
        this.users = users;
        this.visitPolicies = visitPolicies;
        this.items = items;
        this.listings = listings;
        this.localities = localities;
        this.authorization = authorization;
        this.events = events;
        this.entityManager = entityManager;
        this.recommendations = recommendations;
        this.decisions = decisions;
        this.operationalGuards = operationalGuards;
        this.operationalAudit = operationalAudit;
    }

    @Transactional(readOnly = true)
    public VisitSchedulingRecommendationView recommend(Long actorId, Long sessionId, RecommendationRequest command) {
        return recommendations.recommend(actorId, sessionId, command);
    }

    @Transactional
    public VisitSchedulingApprovalView approveRecommendation(Long actorId, Long sessionId,
            ApproveVisitRecommendationCommand command) {
        User actor = authorization.requireOperations(actorId);
        requireCommand(command);
        if (command.expectedSessionVersion() == null)
            throw new IllegalArgumentException("Expected Visit Session version is required");
        if (command.groundExecutiveUserId() == null || command.groundExecutiveUserId() <= 0)
            throw new IllegalArgumentException("Ground Executive user ID must be positive");
        if (command.scheduledAt() == null) throw new IllegalArgumentException("Scheduled time is required");
        if (command.overrideReason() != null && command.overrideReason().length() > 500)
            throw new IllegalArgumentException("Override reason must be 500 characters or fewer");

        VisitSession session = lockSession(sessionId);
        requireVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
        boolean repairApproval = session.getStatus() == VisitSessionStatus.REPAIR_REQUIRED;
        if (session.getStatus() != VisitSessionStatus.DRAFT && !repairApproval)
            throw new VisitOperationsConflictException("Only draft or repair-required sessions can use recommendation approval");
        lockGroundExecutives(List.of(command.groundExecutiveUserId()));
        VisitSchedulingRecommendationService.ApprovalAssessment assessment = recommendations.validateApproval(
                session, command.expectedSessionVersion(), command.groundExecutiveUserId(),
                command.scheduledAt(), command.zoneId());
        String reason = command.overrideReason() == null ? null : command.overrideReason().trim();
        if (assessment.override() && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("An override reason is required when selecting a different candidate");
        if (!assessment.override() && reason != null && !reason.isBlank())
            throw new IllegalArgumentException("Override reason is only allowed when selecting a different candidate");

        if (repairApproval) {
            // This state change is transaction-local. Any scheduling or persistence
            // failure rolls the repair case back without dropping it from the queue.
            session.setStatus(VisitSessionStatus.DRAFT);
            session.setRepairState("NONE");
            session.setRepairOperationId(null);
            session.setTenantConfirmationState("PENDING");
            session.setTenantConfirmedAt(null);
            session.setTenantConfirmedBy(null);
            entityManager.flush();
        }
        ScheduleVisitSessionCommand booking = new ScheduleVisitSessionCommand(session.getVersion(),
                command.scheduledAt(), command.zoneId(), command.groundExecutiveUserId(), assessment.durationMinutes());
        OperationsVisitSessionView scheduled = schedule(actorId, sessionId, booking);

        VisitSchedulingDecision decision = new VisitSchedulingDecision();
        decision.setSession(session);
        decision.setSessionVersionBefore(command.expectedSessionVersion());
        decision.setRecommendationGeneratedAt(assessment.validatedAt());
        decision.setPolicyVersion(assessment.policyVersion());
        decision.setRecommendedGroundExecutive(users.getReferenceById(assessment.topGeId()));
        decision.setRecommendedScheduledAt(assessment.topStart());
        decision.setSelectedGroundExecutive(users.getReferenceById(assessment.selectedGeId()));
        decision.setSelectedScheduledAt(assessment.selectedStart());
        decision.setDurationMinutes(assessment.durationMinutes());
        decision.setApprovedBy(actor);
        decision.setApprovedAt(Instant.now());
        decision.setOverride(assessment.override());
        decision.setOverrideReason(assessment.override() ? reason : null);
        decision.setLocationAssessment(assessment.locationAssessment());
        decision.setTravelConfidence(assessment.travelConfidence());
        decisions.saveAndFlush(decision);
        return new VisitSchedulingApprovalView(scheduled, decision.getId(), assessment.override(),
                assessment.topGeId(), assessment.topStart());
    }

    @Transactional(readOnly = true)
    public OperationsVisitRequestPage listRequests(Long actorId, VisitRequestStatus status, String city,
                                                    int page, int size) {
        authorization.requireOperations(actorId);
        validatePage(page, size);
        String cityFilter = normalizeCityFilter(city);
        Page<PropertyVisitRequestRepository.OperationsQueueRow> result = requests.findOperationsQueue(
                status == null ? null : status.name(), cityFilter,
                PageRequest.of(page, size == 0 ? DEFAULT_PAGE_SIZE : size));
        List<OperationsVisitRequestItem> rows = result.getContent().stream().map(row ->
                new OperationsVisitRequestItem(row.getId(), row.getStatus(), row.getVersion(), row.getCreatedAt(),
                        row.getListingId(), row.getListingTitle(), row.getCity(), row.getSector())).toList();
        return new OperationsVisitRequestPage(rows, result.getTotalElements(), result.getNumber(),
                result.getSize(), result.getTotalPages());
    }

    @Transactional
    public OperationsVisitSessionView coordinateRequest(Long actorId, Long requestId,
                                                         CoordinateVisitRequestCommand command) {
        User actor = authorization.requireOperations(actorId);
        requireCommand(command);
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("Visit Request ID must be positive");
        if (command.sessionId() != null && command.sessionId() <= 0)
            throw new IllegalArgumentException("Visit Session ID must be positive");
        VisitSession requestedSession = command.sessionId() == null ? null : lockSession(command.sessionId());
        PropertyVisitRequest request = requests.findLockedById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
        acquireOperationalScopeGuards(request, actorId);
        actor = authorization.requireOperations(actorId);

        if (request.getSession() != null) {
            if (command.sessionId() != null && !Objects.equals(command.sessionId(), request.getSession().getId()))
                throw new VisitOperationsConflictException("Visit Request is already attached to another session");
            if (request.getStatusValue() == VisitRequestStatus.COORDINATING
                    || request.getStatusValue() == VisitRequestStatus.SCHEDULED) {
                VisitSession existing = sessions.findById(request.getSession().getId())
                        .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
                return operationsView(existing);
            }
            throw new VisitOperationsConflictException("Visit Request is already linked and cannot be coordinated");
        }

        requireVersion(request.getVersion(), command.expectedRequestVersion(), "Visit Request");
        if (request.getStatusValue() != VisitRequestStatus.RECEIVED)
            throw new VisitOperationsConflictException("Only received requests can begin coordination");
        if (requestedSession != null) requireSameTenant(request, requestedSession);

        Listing requestedListing = request.getListing();
        if (requestedListing.getStatus() != ListingStatus.ACTIVE)
            throw new VisitOperationsConflictException("The requested property is no longer active");
        requireOperationalScopeActive(request.getSupportedCity(), request.getOperatingTeam());
        LocationSnapshot location = resolveLocation(requestedListing);
        VisitSession session;
        boolean newSession = command.sessionId() == null;
        if (newSession) {
            if (command.expectedSessionVersion() != null)
                throw new IllegalArgumentException("Session version is only valid when attaching to an existing session");
            session = new VisitSession();
            session.setTenant(request.getTenant());
            session.setCity(location.city());
            session.setAreaName(requestedListing.getSector());
            session.setCanonicalLocality(location.locality());
            session.setSupportedCity(request.getSupportedCity());
            session.setOperatingTeam(request.getOperatingTeam());
            session.setCoordinator(request.getCoordinator());
            session.setOperationalScopeReady(request.isOperationalScopeReady());
            session = sessions.save(session);
        } else {
            if (command.expectedSessionVersion() == null)
                throw new IllegalArgumentException("Expected session version is required when attaching a request");
            session = requestedSession;
            requireVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
            requireDraft(session);
            requireSameTenant(request, session);
            requireSameCity(location.city(), session.getCity());
            requireOwnershipMirror(request, session);
        }

        request.setSession(session);
        request.setStatus(VisitRequestStatus.COORDINATING);
        // V33's source-request foreign key includes session_id, so persist the link first.
        entityManager.flush();
        ensureDirectItem(session, request, requestedListing);
        if (!newSession) touch(session);
        entityManager.flush();
        operationalAudit.recordUserEvent(actor, "REQUEST_SESSION_LINKED", "VISIT_SESSION", session.getId(),
                "GOVERNED_LINK", session.getSupportedCity(), session.getOperatingTeam(),
                Map.of("linkedRequestCount", 1L, "operationalScopeReady", session.isOperationalScopeReady()));
        entityManager.flush();
        return operationsView(session);
    }

    @Transactional
    public OperationsVisitRequestItem markRequestUnavailable(Long actorId, Long requestId,
                                                              ExpectedVisitRequestVersion command) {
        authorization.requireOperations(actorId);
        requireCommand(command);
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("Visit Request ID must be positive");
        PropertyVisitRequest request = requests.findLockedById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
        if (request.getStatusValue() == VisitRequestStatus.UNAVAILABLE) return requestItem(request);
        requireVersion(request.getVersion(), command.expectedRequestVersion(), "Visit Request");
        if (request.getStatusValue() != VisitRequestStatus.RECEIVED || request.getSession() != null)
            throw new VisitOperationsConflictException("Only unlinked received requests can be marked unavailable here");
        request.setStatus(VisitRequestStatus.UNAVAILABLE);
        entityManager.flush();
        return requestItem(request);
    }

    @Transactional(readOnly = true)
    public OperationsVisitSessionView getOperationsSession(Long actorId, Long sessionId) {
        authorization.requireOperations(actorId);
        if (sessionId == null || sessionId <= 0) throw new IllegalArgumentException("Visit Session ID must be positive");
        VisitSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        return operationsView(session);
    }

    @Transactional
    public OperationsVisitSessionView addNearbyProperty(Long actorId, Long sessionId,
                                                         AddVisitSessionItemCommand command) {
        authorization.requireOperations(actorId);
        requireCommand(command);
        if (command.listingId() == null || command.listingId() <= 0
                || command.originatingRequestId() == null || command.originatingRequestId() <= 0)
            throw new IllegalArgumentException("Listing and originating request IDs must be positive");
        VisitSession session = lockSession(sessionId);
        requireVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
        requireDraft(session);
        if (command.origin() != VisitSessionItemOrigin.OE_ADDED
                && command.origin() != VisitSessionItemOrigin.LESSOR_SUGGESTED)
            throw new IllegalArgumentException("Nearby properties must use an approved derived origin");

        PropertyVisitRequest originRequest = requests.findLockedById(command.originatingRequestId())
                .orElseThrow(() -> new EntityNotFoundException("Originating Visit Request not found"));
        requireRequestBelongsToSession(originRequest, session);
        if (originRequest.getStatusValue() != VisitRequestStatus.COORDINATING
                && originRequest.getStatusValue() != VisitRequestStatus.UNAVAILABLE)
            throw new VisitOperationsConflictException("Originating request is not available for itinerary assembly");

        Listing listing = listings.findByIdAndStatus(command.listingId(), ListingStatus.ACTIVE)
                .orElseThrow(() -> new EntityNotFoundException("Active listing not found"));
        LocationSnapshot location = resolveLocation(listing);
        requireSameCity(location.city(), session.getCity());
        if (items.existsBySessionIdAndListingId(sessionId, listing.getId()))
            throw new VisitOperationsConflictException("Listing is already present in this session history");

        VisitSessionItem item = new VisitSessionItem();
        item.setSession(session);
        item.setListing(listing);
        item.setPosition(nextPosition(sessionId));
        item.setDerivedFromRequest(originRequest);
        item.setOrigin(command.origin());
        saveItem(item);
        touch(session);
        entityManager.flush();
        return operationsView(session);
    }

    @Transactional
    public OperationsVisitSessionView removeItem(Long actorId, Long sessionId, Long itemId,
                                                  RemoveVisitSessionItemCommand command) {
        User actor = authorization.requireOperations(actorId);
        requireCommand(command);
        if (command.reason() != null && command.reason().length() > 500)
            throw new IllegalArgumentException("Removal reason must be 500 characters or fewer");
        VisitSession session = lockSession(sessionId);
        VisitSessionItem item = items.findBySessionIdAndId(sessionId, itemId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session Item not found"));
        if (item.getRemovedAt() != null) return operationsView(session);
        requireVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
        requireBeforeStart(session);
        if (item.getSourceRequest() != null
                && item.getConfirmationStatus() != VisitSessionItemConfirmationStatus.UNAVAILABLE)
            throw new VisitOperationsConflictException("A directly requested property must be recorded unavailable before removal");

        if (session.getStatus() == VisitSessionStatus.SCHEDULED
                && items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(sessionId).stream()
                    .filter(this::isConfirmedActiveStop)
                    .noneMatch(stop -> !Objects.equals(stop.getId(), item.getId())))
            throw new VisitOperationsConflictException("A scheduled session must retain at least one confirmed stop");
        item.setRemovedAt(Instant.now());
        item.setRemovedBy(actor);
        item.setRemovalReason(command.reason() == null || command.reason().isBlank()
                ? "Removed by Operations" : command.reason().trim());
        touch(session);
        entityManager.flush();
        if (session.getStatus() == VisitSessionStatus.SCHEDULED)
            publishSessionEvent(session, VisitSessionNotificationEvent.Type.ITINERARY_CHANGED, null);
        return operationsView(session);
    }

    @Transactional
    public OperationsVisitSessionView reorderItems(Long actorId, Long sessionId,
                                                    ReorderVisitSessionItemsCommand command) {
        authorization.requireOperations(actorId);
        requireCommand(command);
        VisitSession session = lockSession(sessionId);
        requireVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
        requireBeforeStart(session);
        List<VisitSessionItem> all = items.findBySessionIdOrderByPositionAsc(sessionId);
        List<VisitSessionItem> active = all.stream().filter(this::isActive).toList();
        List<Long> requestedIds = command.itemIds();
        if (requestedIds == null || requestedIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("Item IDs must be present and positive");
        if (requestedIds.size() != new HashSet<>(requestedIds).size())
            throw new IllegalArgumentException("Item order cannot contain duplicate IDs");
        Map<Long, VisitSessionItem> activeById = active.stream().collect(Collectors.toMap(VisitSessionItem::getId, Function.identity()));
        if (requestedIds.size() != active.size() || !activeById.keySet().equals(new HashSet<>(requestedIds)))
            throw new IllegalArgumentException("Item order must include every active item from this session exactly once");

        int max = items.findMaximumPosition(sessionId);
        long temporaryBase = (long) max + all.size() + 1L;
        if (temporaryBase + all.size() > Integer.MAX_VALUE)
            throw new VisitOperationsConflictException("Session itinerary position capacity is exhausted");
        for (int i = 0; i < all.size(); i++) all.get(i).setPosition((int) temporaryBase + i);
        items.saveAllAndFlush(all);

        for (int i = 0; i < requestedIds.size(); i++) activeById.get(requestedIds.get(i)).setPosition(i + 1);
        List<VisitSessionItem> inactive = all.stream().filter(item -> !isActive(item))
                .sorted(Comparator.comparing(VisitSessionItem::getRemovedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(VisitSessionItem::getId)).toList();
        for (int i = 0; i < inactive.size(); i++) inactive.get(i).setPosition(requestedIds.size() + i + 1);
        touch(session);
        entityManager.flush();
        if (session.getStatus() == VisitSessionStatus.SCHEDULED)
            publishSessionEvent(session, VisitSessionNotificationEvent.Type.ITINERARY_CHANGED, null);
        return operationsView(session);
    }

    @Transactional
    public OperationsVisitSessionView setAvailability(Long actorId, Long sessionId, Long itemId,
                                                       VisitSessionAvailabilityCommand command) {
        User actor = authorization.requireOperations(actorId);
        requireCommand(command);
        VisitSession session = lockSession(sessionId);
        VisitSessionItem item = items.findBySessionIdAndId(sessionId, itemId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session Item not found"));
        if (item.getRemovedAt() != null)
            throw new VisitOperationsConflictException("Removed itinerary items cannot be confirmed");
        if (command.status() != VisitSessionItemConfirmationStatus.CONFIRMED
                && command.status() != VisitSessionItemConfirmationStatus.UNAVAILABLE)
            throw new IllegalArgumentException("Availability must be CONFIRMED or UNAVAILABLE");
        if (command.status() == VisitSessionItemConfirmationStatus.UNAVAILABLE
                && command.hasAnyAvailabilityField())
            throw new IllegalArgumentException("Unavailable properties cannot carry an availability window");
        if (command.hasAnyAvailabilityField() && (command.status() != VisitSessionItemConfirmationStatus.CONFIRMED
                || command.availabilitySource() == null))
            throw new IllegalArgumentException("A confirmed property window requires a confirmation source");
        if (item.getConfirmationStatus() == command.status() && !command.hasAnyAvailabilityField())
            return operationsView(session);
        requireVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
        requireDraft(session);
        if (item.getConfirmationStatus() != VisitSessionItemConfirmationStatus.PENDING
                && item.getConfirmationStatus() != command.status())
            throw new VisitOperationsConflictException("Availability cannot be changed after it has been recorded");

        SchedulingWindowValidator.Window propertyWindow = command.hasAnyAvailabilityField()
                ? SchedulingWindowValidator.required(command.availabilityStartAt(), command.availabilityEndAt(),
                    command.availabilityZoneId(), Instant.now(), true)
                : null;

        item.setConfirmationStatus(command.status());
        item.setAvailabilityConfirmedAt(Instant.now());
        item.setConfirmedBy(actor);
        if (propertyWindow != null) {
            item.setAvailabilityStartAt(propertyWindow.startsAt());
            item.setAvailabilityEndAt(propertyWindow.endsAt());
            item.setAvailabilityZoneId(propertyWindow.zoneId());
            item.setAvailabilitySource(command.availabilitySource());
        }
        if (command.status() == VisitSessionItemConfirmationStatus.UNAVAILABLE
                && item.getSourceRequest() != null) {
            PropertyVisitRequest request = requests.findLockedById(item.getSourceRequest().getId())
                    .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
            if (request.getStatusValue() == VisitRequestStatus.COORDINATING)
                request.setStatus(VisitRequestStatus.UNAVAILABLE);
        }
        touch(session);
        entityManager.flush();
        return operationsView(session);
    }

    @Transactional
    public OperationsVisitSessionView schedule(Long actorId, Long sessionId,
                                                ScheduleVisitSessionCommand command) {
        authorization.requireOperations(actorId);
        requireScheduleCommand(command);
        VisitSession session = lockSession(sessionId);
        if (session.getStatus() == VisitSessionStatus.SCHEDULED
                && Objects.equals(session.getScheduledAt(), command.scheduledAt())
                && Objects.equals(session.getZoneId(), command.zoneId())
                && Objects.equals(session.getRepresentative() == null ? null : session.getRepresentative().getId(),
                    command.groundExecutiveUserId())
                && Objects.equals(session.getDurationSnapshotMinutes(), command.durationMinutes())) return operationsView(session);
        requireVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
        if (session.getStatus() != VisitSessionStatus.DRAFT)
            throw new VisitOperationsConflictException("Only draft sessions can be scheduled");
        validateDuration(command.durationMinutes());
        lockGroundExecutives(List.of(command.groundExecutiveUserId()));
        User ground = authorization.requireGroundExecutiveTarget(command.groundExecutiveUserId());
        Instant reservedEnd = reservationEnd(command.scheduledAt(), command.durationMinutes());
        rejectOverlappingReservation(ground.getId(), session.getId(), command.scheduledAt(), reservedEnd);
        applySchedule(session, command.scheduledAt(), command.zoneId());
        session.setDurationSnapshotMinutes(command.durationMinutes());
        session.setReservedEndAt(reservedEnd);
        session.setRepresentative(ground);
        session.setAssignedAt(Instant.now());
        List<VisitSessionItem> active = requireSchedulableItems(session.getId());
        validateSessionLocations(session, active);
        List<PropertyVisitRequest> sessionRequests = requests.findLockedBySessionIdOrderByIdAsc(sessionId);
        Set<Long> activeDirectRequestIds = active.stream().map(VisitSessionItem::getSourceRequest)
                .filter(Objects::nonNull).map(PropertyVisitRequest::getId).collect(Collectors.toSet());
        for (PropertyVisitRequest request : sessionRequests) {
            if (request.getStatusValue() == VisitRequestStatus.COORDINATING) {
                if (!activeDirectRequestIds.contains(request.getId()))
                    throw new VisitOperationsConflictException("A coordinating request has no active confirmed direct property");
                request.setStatus(VisitRequestStatus.SCHEDULED);
            }
        }
        session.setStatus(VisitSessionStatus.SCHEDULED);
        touch(session);
        flushBookingOrConflict();
        publishSessionEvent(session, "PENDING".equals(session.getTenantConfirmationState())
                ? VisitSessionNotificationEvent.Type.RESCHEDULED : VisitSessionNotificationEvent.Type.SCHEDULED, null);
        return operationsView(session);
    }

    @Transactional
    public OperationsVisitSessionView reschedule(Long actorId, Long sessionId,
                                                  RescheduleVisitSessionCommand command) {
        authorization.requireOperations(actorId);
        requireRescheduleCommand(command);
        VisitSession session = lockSession(sessionId);
        if (session.getStatus() == VisitSessionStatus.SCHEDULED
                && Objects.equals(session.getScheduledAt(), command.scheduledAt())
                && Objects.equals(session.getZoneId(), command.zoneId())) return operationsView(session);
        requireVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
        if (session.getStatus() != VisitSessionStatus.SCHEDULED || session.getStartedAt() != null)
            throw new VisitOperationsConflictException("Only not-yet-started scheduled sessions can be rescheduled");
        if (session.getRepresentative() == null || session.getDurationSnapshotMinutes() == null
                || session.getDurationSnapshotMinutes() <= 0)
            throw new VisitOperationsConflictException("Scheduled session has no valid GE reservation");
        lockGroundExecutives(List.of(session.getRepresentative().getId()));
        authorization.requireGroundExecutiveTarget(session.getRepresentative().getId());
        Instant reservedEnd = reservationEnd(command.scheduledAt(), session.getDurationSnapshotMinutes());
        rejectOverlappingReservation(session.getRepresentative().getId(), session.getId(), command.scheduledAt(), reservedEnd);
        applySchedule(session, command.scheduledAt(), command.zoneId());
        session.setReservedEndAt(reservedEnd);
        session.setTenantConfirmationState("PENDING");
        session.setTenantConfirmedAt(null);
        session.setTenantConfirmedBy(null);
        validateSessionLocations(session, activeItems(sessionId));
        touch(session);
        flushBookingOrConflict();
        publishSessionEvent(session, VisitSessionNotificationEvent.Type.RESCHEDULED, null);
        return operationsView(session);
    }

    @Transactional
    public OperationsVisitSessionView cancel(Long actorId, Long sessionId, Long expectedSessionVersion) {
        authorization.requireOperations(actorId);
        if (sessionId == null || sessionId <= 0) throw new IllegalArgumentException("Visit Session ID must be positive");
        VisitSession session = lockSession(sessionId);
        if (session.getStatus() == VisitSessionStatus.CANCELLED) return operationsView(session);
        requireVersion(session.getVersion(), expectedSessionVersion, "Visit Session");
        if (session.getStatus() != VisitSessionStatus.DRAFT && session.getStatus() != VisitSessionStatus.SCHEDULED)
            throw new VisitOperationsConflictException("Only pre-start sessions can be cancelled");
        if (session.getRepresentative() != null)
            lockGroundExecutives(List.of(session.getRepresentative().getId()));
        for (PropertyVisitRequest request : requests.findLockedBySessionIdOrderByIdAsc(sessionId)) {
            if (request.getStatusValue() == VisitRequestStatus.COORDINATING
                    || request.getStatusValue() == VisitRequestStatus.SCHEDULED)
                request.setStatus(VisitRequestStatus.CANCELLED);
        }
        session.setStatus(VisitSessionStatus.CANCELLED);
        touch(session);
        entityManager.flush();
        publishSessionEvent(session, VisitSessionNotificationEvent.Type.CANCELLED, null);
        return operationsView(session);
    }

    @Transactional
    public OperationsVisitSessionView assignGroundExecutive(Long actorId, Long sessionId,
                                                              AssignGroundExecutiveCommand command) {
        authorization.requireOperations(actorId);
        requireCommand(command);
        if (command.groundExecutiveUserId() == null || command.groundExecutiveUserId() <= 0)
            throw new IllegalArgumentException("Ground Executive user ID must be positive");
        VisitSession session = lockSession(sessionId);
        User target = users.findById(command.groundExecutiveUserId())
                .orElseThrow(() -> new EntityNotFoundException("Ground Executive not found"));
        if (session.getRepresentative() != null
                && Objects.equals(session.getRepresentative().getId(), target.getId())) return operationsView(session);
        requireVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
        if (session.getStatus() != VisitSessionStatus.SCHEDULED || session.getStartedAt() != null)
            throw new VisitOperationsConflictException("Only scheduled, not-yet-started sessions can be assigned");
        Long formerGroundId = session.getRepresentative() == null ? null : session.getRepresentative().getId();
        List<Long> lockIds = new ArrayList<>();
        lockIds.add(target.getId());
        if (formerGroundId != null) lockIds.add(formerGroundId);
        lockGroundExecutives(lockIds);
        target = authorization.requireGroundExecutiveTarget(command.groundExecutiveUserId());
        if (session.getScheduledAt() == null || session.getReservedEndAt() == null
                || session.getDurationSnapshotMinutes() == null || session.getDurationSnapshotMinutes() <= 0)
            throw new VisitOperationsConflictException("Scheduled session has no valid reservation");
        rejectOverlappingReservation(target.getId(), session.getId(), session.getScheduledAt(), session.getReservedEndAt());
        session.setRepresentative(target);
        session.setAssignedAt(Instant.now());
        touch(session);
        flushBookingOrConflict();
        publishSessionEvent(session, VisitSessionNotificationEvent.Type.REASSIGNED, formerGroundId);
        return operationsView(session);
    }

    @Transactional(readOnly = true)
    public GroundVisitSessionPage listAssignedSessions(Long groundUserId, int page, int size) {
        User ground = authorization.requireGroundExecutive(groundUserId);
        validatePage(page, size);
        int actualSize = size == 0 ? DEFAULT_PAGE_SIZE : size;
        Page<VisitSession> result = sessions.findByRepresentativeIdAndStatusInOrderByScheduledAtAscIdAsc(
                ground.getId(), List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED,
                        VisitSessionStatus.PROVISIONAL_NO_SHOW), PageRequest.of(page, actualSize));
        List<Long> ids = result.getContent().stream().map(VisitSession::getId).toList();
        Map<Long, List<VisitSessionItem>> itemsBySession = ids.isEmpty() ? Map.of()
                : items.findBySessionIdInAndRemovedAtIsNullOrderBySessionIdAscPositionAsc(ids).stream()
                        .filter(this::isConfirmedActiveStop)
                        .collect(Collectors.groupingBy(item -> item.getSession().getId()));
        List<GroundVisitSessionView> views = result.getContent().stream()
                .map(session -> groundView(session, itemsBySession.getOrDefault(session.getId(), List.of())))
                .toList();
        return new GroundVisitSessionPage(views, result.getTotalElements(), page, actualSize, result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public GroundVisitSessionView getAssignedSession(Long groundUserId, Long sessionId) {
        User ground = authorization.requireGroundExecutive(groundUserId);
        if (sessionId == null || sessionId <= 0) throw new IllegalArgumentException("Visit Session ID must be positive");
        VisitSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        if (!List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED,
                VisitSessionStatus.PROVISIONAL_NO_SHOW).contains(session.getStatus()) || session.getRepresentative() == null
                || !Objects.equals(session.getRepresentative().getId(), ground.getId()))
            throw new EntityNotFoundException("Visit Session not found");
        List<VisitSessionItem> active = items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(sessionId).stream()
                .filter(this::isConfirmedActiveStop)
                .toList();
        return groundView(session, active);
    }

    private void ensureDirectItem(VisitSession session, PropertyVisitRequest request, Listing listing) {
        Optional<VisitSessionItem> existing = items.findBySessionIdAndListingId(session.getId(), listing.getId());
        if (existing.isPresent()) {
            VisitSessionItem item = existing.get();
            if (Objects.equals(item.getSourceRequest() == null ? null : item.getSourceRequest().getId(), request.getId())
                    && item.getDerivedFromRequest() == null
                    && item.getOrigin() == VisitSessionItemOrigin.TENANT_REQUESTED
                    && item.getRemovedAt() == null) return;
            throw new VisitOperationsConflictException("Requested property already has a different session item");
        }
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(session);
        item.setListing(listing);
        item.setPosition(nextPosition(session.getId()));
        item.setSourceRequest(request);
        item.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        saveItem(item);
    }

    private void saveItem(VisitSessionItem item) {
        try {
            items.saveAndFlush(item);
        } catch (DataIntegrityViolationException ex) {
            throw new VisitOperationsConflictException("The itinerary changed concurrently; reload and retry");
        }
    }

    private int nextPosition(Long sessionId) {
        int max = items.findMaximumPosition(sessionId);
        if (max == Integer.MAX_VALUE)
            throw new VisitOperationsConflictException("Session itinerary position capacity is exhausted");
        return max + 1;
    }

    private VisitSession lockSession(Long sessionId) {
        if (sessionId == null || sessionId <= 0) throw new IllegalArgumentException("Visit Session ID must be positive");
        return sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
    }

    private void acquireOperationalScopeGuards(PropertyVisitRequest request, Long actorId) {
        SupportedCity city = request.getSupportedCity();
        OperatingTeam team = request.getOperatingTeam();
        SupportedCity teamCity = team == null ? null : team.getCity();
        Long cityId = city == null ? null : city.getId();
        Long teamId = team == null ? null : team.getId();
        if (city != null) entityManager.detach(city);
        if (teamCity != null) entityManager.detach(teamCity);
        if (team != null) entityManager.detach(team);
        operationalGuards.acquire(cityId == null ? List.of() : List.of(cityId),
                teamId == null ? List.of() : List.of(teamId), List.of(actorId));
        if (cityId != null) request.setSupportedCity(entityManager.find(SupportedCity.class, cityId));
        if (teamId != null) request.setOperatingTeam(entityManager.find(OperatingTeam.class, teamId));
    }

    private void requireOperationalScopeActive(SupportedCity city, OperatingTeam team) {
        if (city != null && !operationalGuards.cityIsActive(city.getId()))
            throw new VisitOperationsConflictException("Operational City is inactive");
        if (team != null && (!operationalGuards.teamIsActive(team.getId()) || city == null
                || !Objects.equals(operationalGuards.teamCityId(team.getId()), city.getId())))
            throw new VisitOperationsConflictException("Operational Team is inactive or outside its City");
    }

    private static void requireOwnershipMirror(PropertyVisitRequest request, VisitSession session) {
        if (!Objects.equals(id(request.getSupportedCity()), id(session.getSupportedCity()))
                || !Objects.equals(id(request.getOperatingTeam()), id(session.getOperatingTeam()))
                || !Objects.equals(id(request.getCoordinator()), id(session.getCoordinator()))
                || request.isOperationalScopeReady() != session.isOperationalScopeReady())
            throw new VisitOperationsConflictException("Request and Session operational ownership must match");
    }

    private static Long id(SupportedCity city) { return city == null ? null : city.getId(); }
    private static Long id(OperatingTeam team) { return team == null ? null : team.getId(); }
    private static Long id(User user) { return user == null ? null : user.getId(); }

    private void requireDraft(VisitSession session) {
        if (session.getStatus() != VisitSessionStatus.DRAFT)
            throw new VisitOperationsConflictException("Only draft sessions can be changed");
    }

    private void requireBeforeStart(VisitSession session) {
        if (session.getStatus() != VisitSessionStatus.DRAFT && session.getStatus() != VisitSessionStatus.SCHEDULED)
            throw new VisitOperationsConflictException("This session can no longer be changed");
        if (session.getStartedAt() != null)
            throw new VisitOperationsConflictException("Started sessions can no longer be changed");
    }

    private void requireVersion(Long actual, Long expected, String label) {
        if (expected == null || !Objects.equals(actual, expected))
            throw new VisitOperationsConflictException(label + " changed; reload and retry");
    }

    private void touch(VisitSession session) {
        Instant now = Instant.now();
        if (session.getUpdatedAt() != null && !now.isAfter(session.getUpdatedAt()))
            now = session.getUpdatedAt().plus(1, ChronoUnit.MICROS);
        session.setUpdatedAt(now);
    }

    private void requireSameTenant(PropertyVisitRequest request, VisitSession session) {
        if (!Objects.equals(request.getTenant().getId(), session.getTenant().getId()))
            throw new AccessDeniedException("Visit Request cannot be attached to this session");
    }

    private void requireRequestBelongsToSession(PropertyVisitRequest request, VisitSession session) {
        if (request.getSession() == null || !Objects.equals(request.getSession().getId(), session.getId()))
            throw new AccessDeniedException("Originating request does not belong to this session");
        requireSameTenant(request, session);
    }

    private void requireSameCity(String listingCity, String sessionCity) {
        if (!normalizeCity(listingCity).equals(normalizeCity(sessionCity)))
            throw new VisitOperationsConflictException("A Visit Session cannot contain properties from different cities");
    }

    private LocationSnapshot resolveLocation(Listing listing) {
        Map<Long, Locality> localityById = localitiesFor(List.of(listing));
        return resolveLocation(listing, localityById);
    }

    private LocationSnapshot resolveLocation(Listing listing, Map<Long, Locality> localityById) {
        Long localityId = listing.getCanonicalLocalityId();
        Locality locality = localityId == null ? null : Optional.ofNullable(localityById.get(localityId))
                .orElseThrow(() -> new EntityNotFoundException("Listing locality not found"));
        String city = locality == null ? listing.getCity() : locality.getCity();
        if (city == null || city.isBlank()) throw new IllegalArgumentException("Listing city is required for visit coordination");
        return new LocationSnapshot(city.trim(), locality);
    }

    private Map<Long, Locality> localitiesFor(Collection<Listing> listingsToResolve) {
        Set<Long> ids = listingsToResolve.stream().map(Listing::getCanonicalLocalityId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return StreamSupport.stream(localities.findAllById(ids).spliterator(), false)
                .collect(Collectors.toMap(Locality::getId, Function.identity()));
    }

    private String normalizeCity(String city) {
        if (city == null) return "";
        return city.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeCityFilter(String city) {
        if (city == null || city.isBlank()) return null;
        String normalized = city.trim();
        if (normalized.length() > 160) throw new IllegalArgumentException("City filter is too long");
        return normalized;
    }

    private void validatePage(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        if (size < 0 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("Page size must be between 0 and 100");
    }

    private void applySchedule(VisitSession session, Instant scheduledAt, String zoneId) {
        if (zoneId == null || zoneId.isBlank() || zoneId.length() > 64)
            throw new IllegalArgumentException("A valid IANA timezone is required");
        if (!ZoneId.getAvailableZoneIds().contains(zoneId))
            throw new IllegalArgumentException("A valid IANA timezone is required");
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("A valid IANA timezone is required");
        }
        if (scheduledAt == null || !scheduledAt.isAfter(Instant.now()))
            throw new IllegalArgumentException("Scheduled time must be in the future");
        session.setScheduledAt(scheduledAt);
        session.setZoneId(zone.getId());
    }

    private void validateDuration(Integer durationMinutes) {
        if (durationMinutes == null || durationMinutes <= 0)
            throw new IllegalArgumentException("Planned duration must be positive");
        int maximum = visitPolicies.findById(VisitPolicy.SINGLETON_ID)
                .map(VisitPolicy::getMaxVisitSessionDurationMinutes)
                .orElse(VisitPolicy.DEFAULT_MAX_SESSION_DURATION_MINUTES);
        if (durationMinutes > maximum)
            throw new IllegalArgumentException("Planned duration exceeds the configured session maximum");
    }

    private Instant reservationEnd(Instant start, Integer durationMinutes) {
        if (start == null || durationMinutes == null || durationMinutes <= 0)
            throw new IllegalArgumentException("A valid reservation start and positive duration are required");
        try {
            return start.plus(durationMinutes, ChronoUnit.MINUTES);
        } catch (DateTimeException | ArithmeticException ex) {
            throw new IllegalArgumentException("Planned reservation end is outside the supported time range", ex);
        }
    }

    private void rejectOverlappingReservation(Long groundId, Long sessionId, Instant start, Instant end) {
        if (sessions.existsOverlappingReservation(groundId,
                List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED), sessionId, start, end))
            throw new VisitOperationsConflictException("Ground Executive already has an overlapping Visit Session");
    }

    private void lockGroundExecutives(Collection<Long> groundIds) {
        groundIds.stream().filter(Objects::nonNull).distinct().sorted().forEach(groundId ->
                users.findLockedById(groundId)
                        .orElseThrow(() -> new EntityNotFoundException("Ground Executive not found")));
    }

    private void flushBookingOrConflict() {
        try {
            entityManager.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new VisitOperationsConflictException("The Ground Executive reservation changed concurrently; reload and retry");
        }
    }

    private void requireCommand(Object command) {
        if (command == null) throw new IllegalArgumentException("Request body is required");
    }

    private void requireScheduleCommand(ScheduleVisitSessionCommand command) {
        requireCommand(command);
        if (command.scheduledAt() == null) throw new IllegalArgumentException("Scheduled time is required");
        if (command.zoneId() == null || command.zoneId().isBlank() || command.zoneId().length() > 64)
            throw new IllegalArgumentException("A valid IANA timezone is required");
        if (command.groundExecutiveUserId() == null || command.groundExecutiveUserId() <= 0)
            throw new IllegalArgumentException("Ground Executive user ID must be positive");
        if (command.durationMinutes() == null || command.durationMinutes() <= 0)
            throw new IllegalArgumentException("Planned duration must be positive");
    }

    private void requireRescheduleCommand(RescheduleVisitSessionCommand command) {
        requireCommand(command);
        if (command.scheduledAt() == null) throw new IllegalArgumentException("Scheduled time is required");
        if (command.zoneId() == null || command.zoneId().isBlank() || command.zoneId().length() > 64)
            throw new IllegalArgumentException("A valid IANA timezone is required");
    }

    private List<VisitSessionItem> requireSchedulableItems(Long sessionId) {
        List<VisitSessionItem> allActive = items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(sessionId);
        List<VisitSessionItem> visibleStops = allActive.stream().filter(this::isConfirmedActiveStop).toList();
        if (visibleStops.isEmpty()) throw new VisitOperationsConflictException("At least one available property is required");
        if (allActive.stream().anyMatch(item -> item.getConfirmationStatus() != VisitSessionItemConfirmationStatus.UNAVAILABLE
                && !isConfirmedActiveStop(item)))
            throw new VisitOperationsConflictException("Every active property must be confirmed before scheduling");
        return visibleStops;
    }

    private List<VisitSessionItem> activeItems(Long sessionId) {
        return items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(sessionId).stream()
                .filter(item -> item.getConfirmationStatus() != VisitSessionItemConfirmationStatus.UNAVAILABLE)
                .toList();
    }

    private boolean isActive(VisitSessionItem item) {
        return item.getRemovedAt() == null
                && item.getConfirmationStatus() != VisitSessionItemConfirmationStatus.UNAVAILABLE;
    }

    private boolean isConfirmedActiveStop(VisitSessionItem item) {
        return item.getRemovedAt() == null
                && item.getConfirmationStatus() == VisitSessionItemConfirmationStatus.CONFIRMED;
    }

    private void validateSessionLocations(VisitSession session, List<VisitSessionItem> sessionItems) {
        Map<Long, Locality> localityById = localitiesFor(sessionItems.stream()
                .map(VisitSessionItem::getListing).toList());
        for (VisitSessionItem item : sessionItems) {
            LocationSnapshot location = resolveLocation(item.getListing(), localityById);
            requireSameCity(location.city(), session.getCity());
        }
    }

    private OperationsVisitSessionView operationsView(VisitSession session) {
        List<VisitSessionItem> sessionItems = items.findBySessionIdOrderByPositionAsc(session.getId());
        Map<Long, Locality> localityById = localitiesFor(sessionItems.stream()
                .map(VisitSessionItem::getListing).toList());
        List<VisitSessionItemView> itemViews = sessionItems.stream()
                .map(item -> itemView(item, resolveLocation(item.getListing(), localityById).city())).toList();
        return new OperationsVisitSessionView(session.getId(), session.getStatus(), session.getVersion(),
                session.getCity(), session.getAreaName(), session.getScheduledAt(), session.getReservedEndAt(),
                session.getDurationSnapshotMinutes(), session.getZoneId(),
                session.getRepresentative() == null ? null : session.getRepresentative().getId(),
                session.getAssignedAt(), itemViews);
    }

    private VisitSessionItemView itemView(VisitSessionItem item, String authoritativeCity) {
        Listing listing = item.getListing();
        return new VisitSessionItemView(item.getId(), listing.getId(), listing.getTitle(), listing.getAddress(),
                authoritativeCity, listing.getSector(), item.getPosition(),
                item.getSourceRequest() == null ? null : item.getSourceRequest().getId(),
                item.getDerivedFromRequest() == null ? null : item.getDerivedFromRequest().getId(),
                item.getOrigin(), item.getConfirmationStatus(), item.getAvailabilityConfirmedAt(),
                item.getRemovedAt(), item.getRemovedBy() == null ? null : item.getRemovedBy().getId(),
                item.getRemovalReason(), item.getAvailabilityStartAt(), item.getAvailabilityEndAt(),
                item.getAvailabilityZoneId(), item.getAvailabilitySource(),
                item.getConfirmedBy() == null ? null : item.getConfirmedBy().getId());
    }

    private GroundVisitSessionView groundView(VisitSession session, List<VisitSessionItem> sessionItems) {
        Map<Long, Locality> localityById = localitiesFor(sessionItems.stream()
                .map(VisitSessionItem::getListing).toList());
        List<GroundVisitSessionItemView> itemViews = sessionItems.stream().map(item -> {
            Listing listing = item.getListing();
            return new GroundVisitSessionItemView(item.getId(), listing.getId(), listing.getTitle(),
                    listing.getAddress(), resolveLocation(listing, localityById).city(), listing.getSector(), item.getPosition(),
                    item.getAvailabilityConfirmedAt());
        }).toList();
        Instant now = Instant.now();
        return new GroundVisitSessionView(session.getId(), session.getStatus(), session.getVersion(),
                session.getCity(), session.getScheduledAt(), session.getReservedEndAt(),
                session.getDurationSnapshotMinutes(), session.getZoneId(), session.getArrivedAt(), session.getStartedAt(),
                session.getExpectedEndAt(), session.getFinishedAt(), session.getTenantEtaAt(),
                session.getTenantConfirmationState(), session.getRepairState(),
                session.getStatus() == VisitSessionStatus.STARTED && session.getExpectedEndAt() != null
                        && now.isAfter(session.getExpectedEndAt()), itemViews);
    }

    private OperationsVisitRequestItem requestItem(PropertyVisitRequest request) {
        Listing listing = request.getListing();
        LocationSnapshot location = resolveLocation(listing);
        return new OperationsVisitRequestItem(request.getId(), request.getStatus(), request.getVersion(),
                request.getCreatedAt(), listing.getId(), listing.getTitle(), location.city(), listing.getSector());
    }

    private void publishSessionEvent(VisitSession session, VisitSessionNotificationEvent.Type type, Long formerGroundId) {
        events.publishEvent(new VisitSessionNotificationEvent(type, session.getId(), session.getTenant().getId(),
                session.getRepresentative() == null ? null : session.getRepresentative().getId(), formerGroundId,
                session.getVersion(), session.getScheduledAt(), session.getZoneId()));
    }

    private record LocationSnapshot(String city, Locality locality) {}
}
