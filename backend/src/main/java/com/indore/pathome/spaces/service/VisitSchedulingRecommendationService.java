package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.*;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.*;
import com.indore.pathome.spaces.service.field.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class VisitSchedulingRecommendationService {
    private static final List<VisitSessionStatus> ACTIVE_RESERVATION_STATUSES =
            List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED);

    private final VisitSessionRepository sessions;
    private final VisitSessionItemRepository items;
    private final PropertyVisitRequestRepository requests;
    private final GroundExecutiveSchedulingProfileRepository profiles;
    private final GroundExecutiveCoverageRepository coverage;
    private final GroundExecutiveShiftRepository shifts;
    private final GroundExecutiveUnavailabilityRepository unavailability;
    private final EmployeeProfileRepository employees;
    private final ListingRepository listings;
    private final LocalityRepository localities;
    private final VisitPolicyRepository visitPolicies;
    private final VisitOperationsAuthorizationService authorization;
    private final LocationSnapshotProvider locations;
    private final TravelTimeEstimator travelEstimator;
    private final SchedulingRecommendationPolicy policy;

    public VisitSchedulingRecommendationService(VisitSessionRepository sessions,
            VisitSessionItemRepository items, PropertyVisitRequestRepository requests,
            GroundExecutiveSchedulingProfileRepository profiles, GroundExecutiveCoverageRepository coverage,
            GroundExecutiveShiftRepository shifts, GroundExecutiveUnavailabilityRepository unavailability,
            EmployeeProfileRepository employees, ListingRepository listings, LocalityRepository localities,
            VisitPolicyRepository visitPolicies, VisitOperationsAuthorizationService authorization,
            LocationSnapshotProvider locations, TravelTimeEstimator travelEstimator,
            SchedulingRecommendationPolicy policy) {
        this.sessions = sessions;
        this.items = items;
        this.requests = requests;
        this.profiles = profiles;
        this.coverage = coverage;
        this.shifts = shifts;
        this.unavailability = unavailability;
        this.employees = employees;
        this.listings = listings;
        this.localities = localities;
        this.visitPolicies = visitPolicies;
        this.authorization = authorization;
        this.locations = locations;
        this.travelEstimator = travelEstimator;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public VisitSchedulingRecommendationView recommend(Long actorId, Long sessionId,
            RecommendationRequest command) {
        authorization.requireOperations(actorId);
        if (sessionId == null || sessionId <= 0) throw new IllegalArgumentException("Visit Session ID must be positive");
        if (command == null || command.expectedSessionVersion() == null)
            throw new IllegalArgumentException("Expected Visit Session version is required");
        VisitSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        requireVersion(session, command.expectedSessionVersion());
        return evaluate(session, Instant.now(), null, false).view();
    }

    /**
     * Reuses Package 2C-B's complete itinerary evaluator for bounded live repair.
     * The caller owns the session lock and supplies only the bounded candidate starts
     * it is willing to consider; this method never persists a recommendation.
     */
    @Transactional(readOnly = true)
    public List<LiveRepairCandidate> assessLiveRepair(VisitSession session, List<Instant> requestedStarts) {
        return assessLiveRepair(session, requestedStarts, false);
    }

    @Transactional
    public List<LiveRepairCandidate> assessLiveRepair(VisitSession session, List<Instant> requestedStarts,
            boolean lockInputs) {
        return assessLiveRepair(session, requestedStarts, lockInputs, null, Integer.MAX_VALUE);
    }

    /** Restricts live repair to the assigned GE plus the best bounded alternate GEs. */
    @Transactional
    public List<LiveRepairCandidate> assessLiveRepair(VisitSession session, List<Instant> requestedStarts,
            boolean lockInputs, Long assignedGeId, int maximumAlternateGes) {
        if (session == null || session.getId() == null || requestedStarts == null || requestedStarts.isEmpty())
            return List.of();
        if (session.getStatus() != VisitSessionStatus.SCHEDULED
                && session.getStatus() != VisitSessionStatus.REPAIR_REQUIRED)
            return List.of();
        Evaluation evaluation = evaluate(session, Instant.now(), requestedStarts, lockInputs, session.getId(), true);
        if (maximumAlternateGes < 0) throw new IllegalArgumentException("Alternate GE bound cannot be negative");
        Set<Long> allowedGes = new HashSet<>();
        if (assignedGeId != null) allowedGes.add(assignedGeId);
        evaluation.ranked().stream().map(CandidatePlan::geId).filter(id -> !Objects.equals(id, assignedGeId))
                .distinct().limit(maximumAlternateGes).forEach(allowedGes::add);
        return evaluation.feasible().stream().filter(plan -> allowedGes.contains(plan.geId()))
                .map(plan -> new LiveRepairCandidate(plan.geId(), plan.start(),
                plan.end(), plan.durationMinutes(), plan.zoneId(), plan.travelConfidence(), plan.locationAssessment()))
                .toList();
    }

    /** Rechecks the confirmed tenant and stop windows at the real OTP start time. */
    @Transactional
    public boolean actualExecutionWindowsValid(VisitSession session, Instant actualStart, Instant actualEnd) {
        if (session == null || actualStart == null || actualEnd == null || !actualStart.isBefore(actualEnd))
            return false;
        List<VisitSessionItem> active = items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(session.getId());
        if (active.isEmpty() || active.stream().anyMatch(item ->
                item.getConfirmationStatus() != VisitSessionItemConfirmationStatus.CONFIRMED
                        || item.getListing().getStatus() != ListingStatus.ACTIVE)) return false;
        List<PropertyVisitRequest> direct = requests.findLockedBySessionIdOrderByIdAsc(session.getId()).stream()
                .filter(request -> active.stream().anyMatch(item -> item.getSourceRequest() != null
                        && Objects.equals(item.getSourceRequest().getId(), request.getId()))).toList();
        if (direct.isEmpty() || direct.stream().anyMatch(request -> request.getAvailabilityStartAt() == null
                || request.getAvailabilityEndAt() == null || request.getAvailabilityStartAt().isAfter(actualStart)
                || request.getAvailabilityEndAt().isBefore(actualEnd))) return false;
        Map<Long, Locality> localityById = loadLocalities(active.stream().map(VisitSessionItem::getListing).toList());
        Instant stopStart = actualStart;
        Instant lastStopEnd = actualStart;
        for (int index = 0; index < active.size(); index++) {
            VisitSessionItem item = active.get(index);
            Listing listing = item.getListing();
            listings.lockForSchedulingById(listing.getId());
            Instant stopEnd = stopStart.plus(policy.getStopDwellMinutes(), ChronoUnit.MINUTES);
            lastStopEnd = stopEnd;
            if (item.getAvailabilityStartAt() == null || item.getAvailabilityEndAt() == null
                    || item.getAvailabilityStartAt().isAfter(stopStart)
                    || item.getAvailabilityEndAt().isBefore(stopEnd)) return false;
            if (index + 1 < active.size()) {
                Listing next = active.get(index + 1).getListing();
                TravelEstimate leg = estimate(point(listing, localityById.get(listing.getCanonicalLocalityId()), "PROPERTY"),
                        point(next, localityById.get(next.getCanonicalLocalityId()), "PROPERTY"), stopEnd);
                stopStart = stopEnd.plus(minutes(leg.duration()), ChronoUnit.MINUTES);
            }
        }
        return !lastStopEnd.isAfter(actualEnd);
    }

    @Transactional
    public ApprovalAssessment validateApproval(VisitSession lockedSession, Long expectedVersion,
            Long selectedGroundExecutiveUserId, Instant selectedAt, String zoneId) {
        if (lockedSession == null) throw new IllegalArgumentException("Visit Session is required");
        requireVersion(lockedSession, expectedVersion);
        if (lockedSession.getStatus() != VisitSessionStatus.DRAFT)
            throw new VisitOperationsConflictException("Only draft sessions can use recommendation approval");
        if (selectedGroundExecutiveUserId == null || selectedGroundExecutiveUserId <= 0 || selectedAt == null)
            throw new IllegalArgumentException("Ground Executive and visit time are required");
        validateZone(zoneId);

        EmployeeProfile employee = employees.findLockedByUserId(selectedGroundExecutiveUserId)
                .orElseThrow(() -> new VisitOperationsConflictException("Selected Ground Executive is no longer eligible"));
        if (!"GROUND_BOY".equalsIgnoreCase(employee.getRoleType()))
            throw new VisitOperationsConflictException("Selected Ground Executive is no longer eligible");
        GroundExecutiveSchedulingProfile profile = profiles.findLockedByUserId(selectedGroundExecutiveUserId)
                .orElseThrow(() -> new VisitOperationsConflictException("Ground Executive scheduling profile is unavailable"));
        if (!profile.isSchedulingActive())
            throw new VisitOperationsConflictException("Ground Executive scheduling is inactive");
        authorization.requireGroundExecutiveTarget(selectedGroundExecutiveUserId);

        Instant now = Instant.now();
        Evaluation evaluation = evaluate(lockedSession, now, selectedAt, true);
        CandidatePlan selected = evaluation.feasible().stream()
                .filter(plan -> plan.geId().equals(selectedGroundExecutiveUserId)
                        && plan.start().equals(selectedAt)
                        && plan.zoneId().equals(zoneId))
                .findFirst().orElseThrow(() -> new VisitOperationsConflictException(
                        "The recommendation is stale or the selected GE/time is no longer feasible; refresh recommendations"));
        CandidatePlan top = evaluation.ranked().stream().findFirst().orElseThrow(() ->
                new VisitOperationsConflictException("No feasible recommendation remains; refresh recommendations"));
        boolean override = !top.geId().equals(selected.geId()) || !top.start().equals(selected.start());
        return new ApprovalAssessment(selected.geId(), selected.start(), selected.durationMinutes(),
                top.geId(), top.start(), now, policy.getVersion(), override, selected.locationAssessment(),
                selected.travelConfidence(), evaluation.view().status());
    }

    private Evaluation evaluate(VisitSession session, Instant now, Instant additionallyRequestedStart,
                                boolean lockInputs) {
        return evaluate(session, now, additionallyRequestedStart == null ? List.of() : List.of(additionallyRequestedStart),
                lockInputs, null, false);
    }

    private Evaluation evaluate(VisitSession session, Instant now, Collection<Instant> additionallyRequestedStarts,
                                boolean lockInputs, Long excludedSessionId, boolean allowScheduledRepair) {
        if (session.getStatus() != VisitSessionStatus.DRAFT)
            if (!(allowScheduledRepair && (session.getStatus() == VisitSessionStatus.SCHEDULED
                    || session.getStatus() == VisitSessionStatus.REPAIR_REQUIRED)))
                return empty(session, now, RecommendationStatus.SESSION_NOT_SCHEDULABLE, false, "SESSION_NOT_DRAFT");
        if (session.getId() == null || session.getVersion() == null)
            return empty(session, now, RecommendationStatus.SESSION_NOT_SCHEDULABLE, false, "SESSION_STATE_INCOMPLETE");

        if (policy.getMaximumItineraryStops() < 1 || policy.getMaximumItineraryStops() == Integer.MAX_VALUE)
            throw new IllegalStateException("Maximum itinerary size must be a positive bounded value");
        if (items.countBySessionIdAndRemovedAtIsNull(session.getId()) > policy.getMaximumItineraryStops())
            return empty(session, now, RecommendationStatus.SESSION_NOT_SCHEDULABLE, true, "ITINERARY_EXCEEDS_SEARCH_BOUND");
        List<VisitSessionItem> sessionItems = items.findBySessionIdAndRemovedAtIsNullOrderByPositionAsc(session.getId());
        if (lockInputs) {
            sessionItems.stream().map(item -> item.getListing().getId()).distinct().sorted()
                    .forEach(id -> listings.lockForSchedulingById(id)
                            .orElseThrow(() -> new VisitOperationsConflictException("A property changed; refresh recommendations")));
        }
        if (sessionItems.stream().anyMatch(item -> item.getConfirmationStatus() == VisitSessionItemConfirmationStatus.PENDING))
            return empty(session, now, RecommendationStatus.SESSION_NOT_SCHEDULABLE, false, "ACTIVE_ITEM_PENDING_CONFIRMATION");
        List<VisitSessionItem> stops = sessionItems.stream()
                .filter(item -> item.getConfirmationStatus() == VisitSessionItemConfirmationStatus.CONFIRMED)
                .toList();
        if (stops.isEmpty()) return empty(session, now, RecommendationStatus.SESSION_NOT_SCHEDULABLE, false, "NO_CONFIRMED_ACTIVE_STOPS");
        if (stops.size() > policy.getMaximumItineraryStops())
            return empty(session, now, RecommendationStatus.SESSION_NOT_SCHEDULABLE, true, "ITINERARY_EXCEEDS_SEARCH_BOUND");
        if (stops.stream().anyMatch(item -> item.getListing().getStatus() != ListingStatus.ACTIVE))
            return empty(session, now, RecommendationStatus.SESSION_NOT_SCHEDULABLE, false, "PROPERTY_NOT_ACTIVE");

        List<PropertyVisitRequest> sessionRequests = lockInputs
                ? requests.findLockedBySessionIdOrderByIdAsc(session.getId())
                : requests.findBySessionIdOrderByCreatedAtAscIdAsc(session.getId());
        Set<Long> activeDirectIds = stops.stream().map(VisitSessionItem::getSourceRequest)
                .filter(Objects::nonNull).map(PropertyVisitRequest::getId).collect(Collectors.toSet());
        if (sessionRequests.stream().anyMatch(request -> request.getStatusValue() == VisitRequestStatus.COORDINATING
                && !activeDirectIds.contains(request.getId())))
            return empty(session, now, RecommendationStatus.SESSION_NOT_SCHEDULABLE, false, "COORDINATING_REQUEST_WITHOUT_ACTIVE_STOP");
        List<PropertyVisitRequest> directRequests = sessionRequests.stream()
                .filter(request -> activeDirectIds.contains(request.getId())).toList();
        if (directRequests.isEmpty() || directRequests.stream().anyMatch(request -> request.getAvailabilityStartAt() == null
                || request.getAvailabilityEndAt() == null || request.getAvailabilityZoneId() == null))
            return empty(session, now, RecommendationStatus.INSUFFICIENT_TENANT_AVAILABILITY,
                    false, "STRUCTURED_TENANT_WINDOW_REQUIRED");

        Instant tenantStart = directRequests.stream().map(PropertyVisitRequest::getAvailabilityStartAt)
                .max(Comparator.naturalOrder()).orElseThrow();
        Instant tenantEnd = directRequests.stream().map(PropertyVisitRequest::getAvailabilityEndAt)
                .min(Comparator.naturalOrder()).orElseThrow();
        List<Instant> preferences = directRequests.stream().map(PropertyVisitRequest::getPreferredAt)
                .filter(Objects::nonNull).toList();
        String zoneId = directRequests.get(0).getAvailabilityZoneId();
        try { ZoneId.of(zoneId); } catch (DateTimeException ex) {
            return empty(session, now, RecommendationStatus.INSUFFICIENT_TENANT_AVAILABILITY, false, "INVALID_TENANT_ZONE");
        }
        if (!tenantStart.isBefore(tenantEnd))
            return empty(session, now, RecommendationStatus.NO_FEASIBLE_TIME, false, "TENANT_WINDOWS_DO_NOT_INTERSECT");

        List<VisitSessionItem> confirmedStops = stops;
        if (confirmedStops.stream().anyMatch(item -> item.getAvailabilityStartAt() == null
                || item.getAvailabilityEndAt() == null || item.getAvailabilityZoneId() == null))
            return empty(session, now, RecommendationStatus.PROPERTY_AVAILABILITY_INCOMPLETE,
                    false, "CONFIRMED_PROPERTY_WINDOW_REQUIRED");

        Map<Long, Locality> localityById = loadLocalities(confirmedStops.stream()
                .map(VisitSessionItem::getListing).toList());
        List<Stop> plannedStops = new ArrayList<>();
        for (VisitSessionItem item : confirmedStops) {
            Listing listing = item.getListing();
            Locality locality = listing.getCanonicalLocalityId() == null ? null
                    : localityById.get(listing.getCanonicalLocalityId());
            String city = locality == null ? listing.getCity() : locality.getCity();
            if (city == null || !city.trim().equalsIgnoreCase(session.getCity().trim()))
                return empty(session, now, RecommendationStatus.SESSION_NOT_SCHEDULABLE, false, "PROPERTY_CITY_MISMATCH");
            plannedStops.add(new Stop(item, point(listing, locality, "PROPERTY")));
        }

        int dwell = policy.getStopDwellMinutes();
        if (dwell < 1 || policy.getCandidateStepMinutes() < 1 || policy.getCandidateLimit() < 1
                || policy.getMaximumCandidateStarts() < 1 || policy.getMaximumGroundExecutives() < 1)
            throw new IllegalStateException("Scheduling recommendation policy contains invalid bounds");
        List<Integer> offsets = new ArrayList<>(plannedStops.size());
        int duration = 0;
        List<TravelEstimate> internalLegs = new ArrayList<>();
        for (int i = 0; i < plannedStops.size(); i++) {
            offsets.add(duration);
            duration = Math.addExact(duration, dwell);
            if (i + 1 < plannedStops.size()) {
                TravelEstimate estimate = estimate(plannedStops.get(i).point(), plannedStops.get(i + 1).point(), now);
                internalLegs.add(estimate);
                duration = Math.addExact(duration, minutes(estimate.duration()));
            }
        }
        int maximumDuration = visitPolicies.findById(VisitPolicy.SINGLETON_ID)
                .map(VisitPolicy::getMaxVisitSessionDurationMinutes)
                .orElse(VisitPolicy.DEFAULT_MAX_SESSION_DURATION_MINUTES);
        if (duration <= 0 || duration > maximumDuration)
            return empty(session, now, RecommendationStatus.NO_FEASIBLE_TIME, false, "ITINERARY_EXCEEDS_SESSION_DURATION_POLICY");

        boolean planningTruncated = false;
        Instant boundedEnd = tenantEnd;
        long maxDays = policy.getMaximumPlanningWindowDays();
        if (maxDays < 1) throw new IllegalStateException("Maximum scheduling horizon must be positive");
        if (Duration.between(tenantStart, tenantEnd).compareTo(Duration.ofDays(maxDays)) > 0) {
            boundedEnd = tenantStart.plus(Duration.ofDays(maxDays));
            planningTruncated = true;
        }
        Instant candidateStartFloor = now.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES);
        Instant rangeStart = tenantStart.isAfter(candidateStartFloor) ? tenantStart : candidateStartFloor;
        if (!rangeStart.isBefore(boundedEnd))
            return empty(session, now, RecommendationStatus.NO_FEASIBLE_TIME, planningTruncated, "TENANT_WINDOW_EXPIRED");

        List<GroundExecutiveSchedulingProfile> activeProfiles = profiles.findActiveCoveringCity(
                session.getCity(), PageRequest.of(0, policy.getMaximumGroundExecutives() + 1));
        if (activeProfiles.isEmpty())
            return empty(session, now, RecommendationStatus.NO_ELIGIBLE_GE, planningTruncated, "NO_ACTIVE_CITY_COVERAGE");
        if (activeProfiles.size() > policy.getMaximumGroundExecutives()) {
            activeProfiles = activeProfiles.subList(0, policy.getMaximumGroundExecutives());
            planningTruncated = true;
        }
        List<Long> profileIds = activeProfiles.stream().map(GroundExecutiveSchedulingProfile::getEmployeeProfileId).toList();
        Map<Long, List<GroundExecutiveCoverage>> coverageByProfile = coverage.findBySchedulingProfileEmployeeProfileIdIn(profileIds)
                .stream().collect(Collectors.groupingBy(entry -> entry.getSchedulingProfile().getEmployeeProfileId()));

        Instant schedulingLookupStart = rangeStart.minus(Duration.ofMinutes(maximumDuration + policy.getUnknownTravelMinutes()
                + policy.getTravelBufferMinutes()));
        Instant schedulingLookupEnd = boundedEnd.plus(Duration.ofMinutes(maximumDuration + policy.getUnknownTravelMinutes()
                + policy.getTravelBufferMinutes())).plusSeconds(1);
        Map<Long, List<GroundExecutiveShift>> shiftsByProfile = shifts.findOverlappingForProfiles(profileIds, schedulingLookupStart, schedulingLookupEnd)
                .stream().collect(Collectors.groupingBy(shift -> shift.getSchedulingProfile().getEmployeeProfileId()));
        Map<Long, List<GroundExecutiveUnavailability>> unavailableByProfile = unavailability
                .findOverlappingForProfiles(profileIds, schedulingLookupStart, schedulingLookupEnd)
                .stream().collect(Collectors.groupingBy(value -> value.getSchedulingProfile().getEmployeeProfileId()));

        int maxReservations = policy.getMaximumReservationRows();
        if (maxReservations < 1 || maxReservations == Integer.MAX_VALUE)
            throw new IllegalStateException("Maximum reservation search size must be a positive bounded value");
        ReservationNeighborhood reservationNeighborhood = loadReservationNeighborhood(
                activeProfiles.stream().map(profile -> profile.getEmployeeProfile().getUser().getId()).toList(),
                schedulingLookupStart, schedulingLookupEnd, maxReservations, localityById, excludedSessionId);
        if (!reservationNeighborhood.complete())
            return empty(session, now, RecommendationStatus.NO_FEASIBLE_TIME, true, "RESERVATION_SEARCH_BOUND_EXCEEDED");

        CandidateStartSearch startSearch = candidateStarts(rangeStart, boundedEnd, preferences,
                plannedStops, offsets, dwell, duration,
                shiftsByProfile.values().stream().flatMap(Collection::stream).toList(),
                reservationNeighborhood.byGe().values().stream().flatMap(Collection::stream).toList(),
                additionallyRequestedStarts, policy.getMaximumCandidateStarts());
        planningTruncated |= startSearch.truncated();

        List<CandidatePlan> feasible = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        List<RecommendationRejection> rejected = new ArrayList<>();
        int eligibleGeCount = 0;
        for (GroundExecutiveSchedulingProfile profile : activeProfiles) {
            Long geId = profile.getEmployeeProfile().getUser().getId();
            if (!"GROUND_BOY".equalsIgnoreCase(profile.getEmployeeProfile().getRoleType())) continue;
            CoverageAssessment coverageAssessment = assessCoverage(coverageByProfile.getOrDefault(profile.getEmployeeProfileId(), List.of()),
                    session.getCity(), plannedStops);
            if (!coverageAssessment.eligible()) {
                diagnostics.add("GE_" + geId + "_COVERAGE_MISMATCH");
                rejected.add(new RecommendationRejection(geId, "INELIGIBLE",
                        List.of(RecommendationRejectionReason.COVERAGE_MISMATCH)));
                continue;
            }
            eligibleGeCount++;
            List<ReservedVisit> geReservations = reservationNeighborhood.byGe().getOrDefault(geId, List.of());
            LocationUse locationUse = locationFor(geId, plannedStops.get(0).point(), now);
            Map<LocalDate, Workload> workloadByDay = workloadByDay(
                    reservationNeighborhood.boundedByGe().getOrDefault(geId, List.of()), now, zoneId);
            EnumSet<RecommendationRejectionReason> geRejections =
                    EnumSet.noneOf(RecommendationRejectionReason.class);
            int feasibleBefore = feasible.size();
            for (Instant start : startSearch.starts()) {
                if (!start.isBefore(boundedEnd) || start.isBefore(rangeStart)) continue;
                CandidateAssessment assessment = assess(profile, session, geReservations,
                        shiftsByProfile.getOrDefault(profile.getEmployeeProfileId(), List.of()),
                        unavailableByProfile.getOrDefault(profile.getEmployeeProfileId(), List.of()),
                        plannedStops, offsets, internalLegs, duration, start, boundedEnd, zoneId,
                        preferences, locationUse, coverageAssessment.localityFit(), workloadByDay, now);
                if (assessment.plan() != null) feasible.add(assessment.plan());
                else geRejections.add(assessment.rejectionReason());
            }
            if (feasible.size() == feasibleBefore)
                rejected.add(new RecommendationRejection(geId, "INELIGIBLE", geRejections.isEmpty()
                        ? List.of(RecommendationRejectionReason.NO_FEASIBLE_TIME)
                        : geRejections.stream().sorted().toList()));
        }

        Comparator<CandidatePlan> order = candidateOrder();
        Map<Long, CandidatePlan> bestByGe = feasible.stream().collect(Collectors.toMap(CandidatePlan::geId,
                Function.identity(), (left, right) -> order.compare(left, right) <= 0 ? left : right));
        List<CandidatePlan> ranked = bestByGe.values().stream().sorted(order).toList();
        List<RecommendationCandidate> responseCandidates = new ArrayList<>();
        for (int i = 0; i < Math.min(ranked.size(), policy.getCandidateLimit()); i++)
            responseCandidates.add(toView(ranked.get(i), i + 1));

        RecommendationStatus status;
        if (ranked.isEmpty()) status = eligibleGeCount == 0
                ? RecommendationStatus.NO_ELIGIBLE_GE : RecommendationStatus.NO_FEASIBLE_TIME;
        else if (ranked.stream().allMatch(plan -> plan.locationAssessment().equals("UNAVAILABLE")))
            status = RecommendationStatus.LOCATION_UNAVAILABLE_FALLBACK_USED;
        else if (ranked.stream().allMatch(plan -> plan.travelConfidence().equals("UNKNOWN")))
            status = RecommendationStatus.TRAVEL_ESTIMATE_UNCERTAIN;
        else status = RecommendationStatus.RECOMMENDATION_AVAILABLE;
        return new Evaluation(new VisitSchedulingRecommendationView(session.getId(), session.getVersion(), now,
                policy.getVersion(), status, planningTruncated, diagnostics, rejected, responseCandidates),
                feasible, ranked, duration);
    }

    private CandidateAssessment assess(GroundExecutiveSchedulingProfile profile, VisitSession session,
            List<ReservedVisit> reservations, List<GroundExecutiveShift> geShifts,
            List<GroundExecutiveUnavailability> unavailable, List<Stop> stops, List<Integer> stopOffsets,
            List<TravelEstimate> internalLegs, int duration, Instant start, Instant tenantWindowEnd, String zoneId,
            List<Instant> preferredTimes, LocationUse location, boolean localCoverage,
            Map<LocalDate, Workload> workloadByDay, Instant now) {
        Instant end;
        try { end = start.plus(duration, ChronoUnit.MINUTES); }
        catch (DateTimeException | ArithmeticException ex) {
            return rejected(RecommendationRejectionReason.NO_FEASIBLE_TIME);
        }
        if (end.isAfter(tenantWindowEnd))
            return rejected(RecommendationRejectionReason.TENANT_WINDOW_MISMATCH);
        for (Stop stop : stops) {
            int index = stops.indexOf(stop);
            Instant arrival = start.plus(stopOffsets.get(index), ChronoUnit.MINUTES);
            Instant stopEnd = arrival.plus(policy.getStopDwellMinutes(), ChronoUnit.MINUTES);
            if (arrival.isBefore(stop.item().getAvailabilityStartAt()) || stopEnd.isAfter(stop.item().getAvailabilityEndAt()))
                return rejected(RecommendationRejectionReason.PROPERTY_WINDOW_MISMATCH);
        }

        ReservedVisit previous = null;
        ReservedVisit next = null;
        int neighborIndex = lowerBoundStart(reservations, start);
        if (neighborIndex > 0) previous = reservations.get(neighborIndex - 1);
        if (neighborIndex < reservations.size()) next = reservations.get(neighborIndex);
        if (previous != null && previous.end().isAfter(start))
            return rejected(RecommendationRejectionReason.RESERVATION_OVERLAP);
        if (next != null && next.start().isBefore(end))
            return rejected(RecommendationRejectionReason.RESERVATION_OVERLAP);

        Instant workStart = start;
        Instant workEnd = end;
        TravelEstimate firstLeg;
        if (previous != null) {
            TravelPoint from = previous.lastStop();
            firstLeg = estimate(from, stops.get(0).point(), previous.end());
            workStart = previous.end();
            if (previous.status() == VisitSessionStatus.STARTED && workStart.isBefore(now)) workStart = now;
            if (workStart.plus(firstLeg.duration()).isAfter(start))
                return rejected(RecommendationRejectionReason.FIRST_LEG_TRAVEL_INFEASIBLE);
        } else {
            boolean locationRelevant = !start.isAfter(now.plus(policy.getMaximumLocationFutureRelevanceMinutes(), ChronoUnit.MINUTES));
            if (locationRelevant && location.assessment().equals("USABLE")) {
                firstLeg = estimate(location.point(), stops.get(0).point(), now);
                workStart = now;
                if (now.plus(firstLeg.duration()).isAfter(start))
                    return rejected(RecommendationRejectionReason.FIRST_LEG_TRAVEL_INFEASIBLE);
            } else {
                firstLeg = estimate(new TravelPoint(null, null, null, null, "UNKNOWN_START"),
                        stops.get(0).point(), start);
                workStart = start.minus(firstLeg.duration());
                if (workStart.isBefore(start.minus(Duration.ofMinutes(policy.getUnknownTravelMinutes()
                        + policy.getTravelBufferMinutes()))))
                    return rejected(RecommendationRejectionReason.FIRST_LEG_TRAVEL_INFEASIBLE);
            }
        }

        TravelEstimate nextLeg = null;
        if (next != null) {
            nextLeg = estimate(stops.get(stops.size() - 1).point(), next.firstStop(), end);
            if (end.plus(nextLeg.duration()).isAfter(next.start()))
                return rejected(RecommendationRejectionReason.NEXT_LEG_TRAVEL_INFEASIBLE);
            workEnd = end.plus(nextLeg.duration());
        }
        Instant requiredWorkStart = workStart;
        Instant requiredWorkEnd = workEnd;
        boolean contained = geShifts.stream().anyMatch(shift -> !shift.getStartsAt().isAfter(requiredWorkStart)
                && !shift.getEndsAt().isBefore(requiredWorkEnd));
        if (!contained) return rejected(RecommendationRejectionReason.SHIFT_MISMATCH);
        if (unavailable.stream().anyMatch(interval -> interval.getStartsAt().isBefore(requiredWorkEnd)
                && interval.getEndsAt().isAfter(requiredWorkStart)))
            return rejected(RecommendationRejectionReason.UNAVAILABILITY_CONFLICT);

        TravelEstimate.Confidence confidence = minConfidence(firstLeg.confidence(),
                nextLeg == null ? TravelEstimate.Confidence.HIGH : nextLeg.confidence());
        for (TravelEstimate leg : internalLegs) confidence = minConfidence(confidence, leg.confidence());
        List<RecommendationReason> reasons = new ArrayList<>(List.of(
                RecommendationReason.SCHEDULING_ACTIVE,
                RecommendationReason.SHIFT_MATCH,
                RecommendationReason.NO_UNAVAILABILITY_CONFLICT,
                RecommendationReason.RESERVATION_FEASIBLE,
                RecommendationReason.PROPERTY_WINDOW_MATCH,
                RecommendationReason.TRAVEL_FEASIBLE));
        if (preferredTimes.stream().anyMatch(preferred -> preferred.equals(start)))
            reasons.add(RecommendationReason.TENANT_PREFERRED_TIME_MATCH);
        if (localCoverage) reasons.add(RecommendationReason.LOCALITY_COVERAGE_MATCH);
        else reasons.add(RecommendationReason.CITY_COVERAGE_MATCH);
        String candidateLocationAssessment = location.assessment();
        if (location.assessment().equals("USABLE")
                && start.isAfter(now.plus(policy.getMaximumLocationFutureRelevanceMinutes(), ChronoUnit.MINUTES)))
            candidateLocationAssessment = "DEGRADED";
        if (candidateLocationAssessment.equals("USABLE") && firstLeg.originSource().equals("LIVE_LOCATION"))
            reasons.add(RecommendationReason.LOCATION_USED_FOR_FIRST_LEG);
        else if (candidateLocationAssessment.equals("DEGRADED"))
            reasons.add(RecommendationReason.LOCATION_DEGRADED_FALLBACK_USED);
        else if (candidateLocationAssessment.equals("UNAVAILABLE"))
            reasons.add(RecommendationReason.LOCATION_UNAVAILABLE_FALLBACK_USED);
        if (confidence == TravelEstimate.Confidence.UNKNOWN || confidence == TravelEstimate.Confidence.LOW)
            reasons.add(RecommendationReason.TRAVEL_ESTIMATE_UNCERTAIN);

        LocalDate scheduledDay = start.atZone(ZoneId.of(zoneId)).toLocalDate();
        Workload workload = workloadByDay.getOrDefault(scheduledDay, new Workload(0, 0));
        int workloadCount = workload.count();
        int workloadMinutes = workload.minutes();
        if (workloadCount == 0) reasons.add(RecommendationReason.LOWER_FUTURE_WORKLOAD);
        long preferredGap = preferredTimes.isEmpty() ? Long.MAX_VALUE
                : preferredTimes.stream().mapToLong(preferred -> Math.abs(Duration.between(preferred, start).toMinutes())).min().orElse(Long.MAX_VALUE);
        long travelMinutes = firstLeg.duration().toMinutes();
        CandidatePlan plan = new CandidatePlan(profile.getEmployeeProfile().getUser().getId(), start, end, duration,
                zoneId, candidateLocationAssessment, location.reason(), confidence.name(), firstLeg.originSource(),
                workloadCount, workloadMinutes, preferredGap, travelMinutes, localCoverage, List.copyOf(reasons));
        return new CandidateAssessment(plan, null);
    }

    private CandidateAssessment rejected(RecommendationRejectionReason reason) {
        return new CandidateAssessment(null, reason);
    }

    private CandidateStartSearch candidateStarts(Instant start, Instant end, List<Instant> preferences,
            List<Stop> stops, List<Integer> offsets, int dwell, int duration,
            List<GroundExecutiveShift> allShifts, List<ReservedVisit> allReservations,
            Collection<Instant> requestedStarts, int maximum) {
        TreeSet<Instant> values = new TreeSet<>();
        values.add(start);
        Instant latestTenantStart = end.minus(duration, ChronoUnit.MINUTES);
        if (!latestTenantStart.isBefore(start) && latestTenantStart.isBefore(end)) values.add(latestTenantStart);
        for (Instant preferred : preferences) if (!preferred.isBefore(start) && preferred.isBefore(end)) values.add(preferred);
        if (requestedStarts != null) for (Instant requested : requestedStarts)
            if (requested != null && !requested.isBefore(start) && requested.isBefore(end)) values.add(requested);
        for (int i = 0; i < stops.size(); i++) {
            VisitSessionItem item = stops.get(i).item();
            Instant propertyStart = item.getAvailabilityStartAt().minus(offsets.get(i), ChronoUnit.MINUTES);
            Instant propertyLatestStart = item.getAvailabilityEndAt().minus(offsets.get(i) + dwell, ChronoUnit.MINUTES);
            if (!propertyStart.isBefore(start) && propertyStart.isBefore(end)) values.add(propertyStart);
            if (!propertyLatestStart.isBefore(start) && propertyLatestStart.isBefore(end)) values.add(propertyLatestStart);
        }
        for (GroundExecutiveShift shift : allShifts) {
            if (!shift.getStartsAt().isBefore(start) && shift.getStartsAt().isBefore(end)) values.add(shift.getStartsAt());
            Instant latest = shift.getEndsAt().minus(duration, ChronoUnit.MINUTES);
            if (!latest.isBefore(start) && latest.isBefore(end)) values.add(latest);
        }
        Duration travelBound = Duration.ofMinutes(policy.getUnknownTravelMinutes() + policy.getTravelBufferMinutes());
        for (ReservedVisit reservation : allReservations) {
            Instant after = reservation.end().plus(travelBound);
            Instant before = reservation.start().minus(duration, ChronoUnit.MINUTES).minus(travelBound);
            if (!after.isBefore(start) && after.isBefore(end)) values.add(after);
            if (!before.isBefore(start) && before.isBefore(end)) values.add(before);
        }
        Instant step = start;
        while (step.isBefore(end) && values.size() <= maximum) {
            values.add(step);
            step = step.plus(policy.getCandidateStepMinutes(), ChronoUnit.MINUTES);
        }
        List<Instant> sorted = new ArrayList<>(values);
        if (sorted.size() <= maximum) return new CandidateStartSearch(List.copyOf(sorted), false);
        LinkedHashSet<Instant> bounded = new LinkedHashSet<>();
        if (requestedStarts != null) for (Instant requested : requestedStarts) {
            if (bounded.size() >= maximum) break;
            if (requested != null && !requested.isBefore(start) && requested.isBefore(end)) bounded.add(requested);
        }
        if (bounded.size() < maximum) bounded.add(start);
        for (Instant preferred : preferences) {
            if (bounded.size() >= maximum) break;
            if (!preferred.isBefore(start) && preferred.isBefore(end)) bounded.add(preferred);
        }
        for (Instant value : sorted) {
            if (bounded.size() >= maximum) break;
            bounded.add(value);
        }
        return new CandidateStartSearch(bounded.stream().sorted().toList(), true);
    }

    private LocationUse locationFor(Long geId, TravelPoint property, Instant now) {
        LocationSnapshot snapshot;
        try {
            snapshot = locations.latestFor(new FieldResourceKey("INTERNAL_GE", geId.toString()), now).orElse(null);
        } catch (RuntimeException failure) {
            return LocationUse.unavailable("LOCATION_PROVIDER_FAILURE");
        }
        if (snapshot == null) return LocationUse.unavailable("NO_LOCATION_AVAILABLE");
        if (snapshot.assessment() != LocationAssessment.USABLE || snapshot.latitude() == null
                || snapshot.longitude() == null || snapshot.accuracyMeters() == null
                || !Double.isFinite(snapshot.accuracyMeters())
                || snapshot.capturedAt() == null || snapshot.receivedAt() == null
                || !validCoordinates(snapshot.latitude(), snapshot.longitude())
                || snapshot.receivedAt().isAfter(now.plusSeconds(30))
                || snapshot.capturedAt().isAfter(snapshot.receivedAt())
                || snapshot.capturedAt().isAfter(now.plusSeconds(30)))
            return LocationUse.degraded(snapshot.reason() == null ? "LOCATION_QUALITY_UNUSABLE" : snapshot.reason());
        if (Duration.between(snapshot.capturedAt(), now).getSeconds() > policy.getMaximumLocationAgeSeconds()
                || snapshot.accuracyMeters() < 0
                || snapshot.accuracyMeters() > policy.getMaximumLocationAccuracyMeters())
            return LocationUse.degraded("LOCATION_STALE_OR_LOW_ACCURACY");
        return new LocationUse("USABLE", "FRESH_ACCURATE_LOCATION", new TravelPoint(null, null,
                snapshot.latitude(), snapshot.longitude(), "LIVE_LOCATION"));
    }

    private TravelEstimate estimate(TravelPoint origin, TravelPoint destination, Instant at) {
        try {
            TravelEstimate value = travelEstimator.estimate(origin, destination, at);
            if (value == null || value.duration() == null || value.duration().isNegative())
                return uncertainEstimate();
            return value;
        } catch (RuntimeException failure) {
            return uncertainEstimate();
        }
    }

    private TravelEstimate uncertainEstimate() {
        return new TravelEstimate(Duration.ofMinutes(policy.getUnknownTravelMinutes()
                + policy.getTravelBufferMinutes()), TravelEstimate.Confidence.UNKNOWN,
                "CONSERVATIVE_UNKNOWN_FALLBACK");
    }

    private Map<Long, List<ReservedVisit>> mapReservations(List<VisitSession> reservationSessions,
            Map<Long, List<VisitSessionItem>> reservationItems, Map<Long, Locality> localityById) {
        Map<Long, List<ReservedVisit>> result = new HashMap<>();
        for (VisitSession reservation : reservationSessions) {
            List<VisitSessionItem> routeStops = reservationItems.getOrDefault(reservation.getId(), List.of());
            List<TravelPoint> points = routeStops.stream().sorted(Comparator.comparing(VisitSessionItem::getPosition))
                    .map(item -> point(item.getListing(), localityById.get(item.getListing().getCanonicalLocalityId()), "PREVIOUS_VISIT"))
                    .toList();
            TravelPoint first = points.isEmpty() ? unknownPoint() : points.get(0);
            TravelPoint last = points.isEmpty() ? unknownPoint() : points.get(points.size() - 1);
            ReservedVisit visit = new ReservedVisit(reservation.getId(), reservation.getRepresentative().getId(),
                    reservation.getScheduledAt(), reservation.getReservedEndAt(), reservation.getStatus(), first, last);
            result.computeIfAbsent(visit.geId(), ignored -> new ArrayList<>()).add(visit);
        }
        result.values().forEach(list -> list.sort(Comparator.comparing(ReservedVisit::start).thenComparing(ReservedVisit::sessionId)));
        return result;
    }

    private ReservationNeighborhood loadReservationNeighborhood(List<Long> representativeIds,
            Instant windowStart, Instant windowEnd, int maximumInWindowRows,
            Map<Long, Locality> localityById, Long excludedSessionId) {
        List<VisitSession> queriedInWindow = sessions.findActiveItinerariesForRepresentatives(
                representativeIds, ACTIVE_RESERVATION_STATUSES, windowStart, windowEnd,
                excludedSessionId, PageRequest.of(0, maximumInWindowRows + 1));
        List<VisitSession> inWindow = queriedInWindow;
        if (inWindow.size() > maximumInWindowRows) return ReservationNeighborhood.incomplete();

        List<VisitSession> previous = sessions.findNearestActiveReservationsBefore(
                representativeIds, ACTIVE_RESERVATION_STATUSES, windowStart, excludedSessionId);
        List<VisitSession> next = sessions.findNearestActiveReservationsAfter(
                representativeIds, ACTIVE_RESERVATION_STATUSES, windowEnd, excludedSessionId);
        Map<Long, VisitSession> sessionsById = new LinkedHashMap<>();
        inWindow.forEach(value -> sessionsById.putIfAbsent(value.getId(), value));
        previous.forEach(value -> sessionsById.putIfAbsent(value.getId(), value));
        next.forEach(value -> sessionsById.putIfAbsent(value.getId(), value));
        List<VisitSession> all = List.copyOf(sessionsById.values());
        if (all.isEmpty()) return new ReservationNeighborhood(Map.of(), Map.of(), true);

        Map<Long, List<VisitSessionItem>> allItems = items
                .findBySessionIdInAndRemovedAtIsNullOrderBySessionIdAscPositionAsc(
                        all.stream().map(VisitSession::getId).toList()).stream()
                .filter(item -> item.getConfirmationStatus() == VisitSessionItemConfirmationStatus.CONFIRMED)
                .collect(Collectors.groupingBy(item -> item.getSession().getId()));
        Set<Long> inWindowIds = inWindow.stream().map(VisitSession::getId).collect(Collectors.toSet());
        Map<Long, List<VisitSessionItem>> inWindowItems = allItems.entrySet().stream()
                .filter(entry -> inWindowIds.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new ReservationNeighborhood(mapReservations(all, allItems, localityById),
                mapReservations(inWindow, inWindowItems, localityById), true);
    }

    private int lowerBoundStart(List<ReservedVisit> reservations, Instant start) {
        int low = 0;
        int high = reservations.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (reservations.get(middle).start().isBefore(start)) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private Map<LocalDate, Workload> workloadByDay(List<ReservedVisit> reservations, Instant now, String zoneId) {
        Map<LocalDate, Workload> result = new HashMap<>();
        ZoneId zone = ZoneId.of(zoneId);
        for (ReservedVisit reservation : reservations) {
            if (!reservation.start().isAfter(now)) continue;
            LocalDate day = reservation.start().atZone(zone).toLocalDate();
            Workload current = result.getOrDefault(day, new Workload(0, 0));
            long totalMinutes = (long) current.minutes() + Duration.between(reservation.start(), reservation.end()).toMinutes();
            result.put(day, new Workload(current.count() + 1, (int) Math.min(Integer.MAX_VALUE, totalMinutes)));
        }
        return result;
    }

    private CoverageAssessment assessCoverage(List<GroundExecutiveCoverage> rows, String city, List<Stop> stops) {
        List<GroundExecutiveCoverage> cityRows = rows.stream()
                .filter(row -> row.getCity().trim().equalsIgnoreCase(city.trim())).toList();
        if (cityRows.stream().anyMatch(row -> row.getLocality() == null)) return new CoverageAssessment(true, false);
        Set<Long> coveredLocalities = cityRows.stream().map(GroundExecutiveCoverage::getLocality)
                .filter(Objects::nonNull).map(Locality::getId).collect(Collectors.toSet());
        boolean covered = stops.stream().allMatch(stop -> stop.point().localityId() != null
                && coveredLocalities.contains(stop.point().localityId()));
        return new CoverageAssessment(covered, covered);
    }

    private Map<Long, Locality> loadLocalities(List<Listing> properties) {
        Set<Long> ids = properties.stream().map(Listing::getCanonicalLocalityId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<Long, Locality> map = new HashMap<>();
        localities.findAllById(ids).forEach(value -> map.put(value.getId(), value));
        if (map.size() != ids.size()) throw new EntityNotFoundException("A canonical property locality is unavailable");
        return map;
    }

    private TravelPoint point(Listing listing, Locality locality, String source) {
        boolean resolved = listing.getLocationResolution() != LocationResolution.MANUAL_PENDING;
        Double latitude = resolved ? listing.getLatitude() : null;
        Double longitude = resolved ? listing.getLongitude() : null;
        if (!validCoordinates(latitude, longitude)) { latitude = null; longitude = null; }
        String city = locality == null ? listing.getCity() : locality.getCity();
        return new TravelPoint(city, locality == null ? listing.getCanonicalLocalityId() : locality.getId(),
                latitude, longitude, source);
    }

    private boolean validCoordinates(Double latitude, Double longitude) {
        return latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }

    private TravelPoint unknownPoint() { return new TravelPoint(null, null, null, null, "UNKNOWN"); }

    private int minutes(Duration duration) {
        long value = duration.toMinutes();
        if (!duration.minusMinutes(value).isZero()) value++;
        return Math.toIntExact(value);
    }

    private TravelEstimate.Confidence minConfidence(TravelEstimate.Confidence first, TravelEstimate.Confidence second) {
        return confidenceRank(first) >= confidenceRank(second) ? first : second;
    }

    private int confidenceRank(TravelEstimate.Confidence value) {
        return switch (value) { case HIGH -> 0; case MEDIUM -> 1; case LOW -> 2; case UNKNOWN -> 3; };
    }

    private Comparator<CandidatePlan> candidateOrder() {
        return Comparator.comparingLong(CandidatePlan::preferredGapMinutes)
                .thenComparingInt(plan -> confidenceRank(TravelEstimate.Confidence.valueOf(plan.travelConfidence())))
                .thenComparingLong(CandidatePlan::firstLegMinutes)
                .thenComparing(plan -> plan.locationAssessment().equals("USABLE") ? 0
                        : plan.locationAssessment().equals("DEGRADED") ? 1 : 2)
                .thenComparing(plan -> plan.localityFit() ? 0 : 1)
                .thenComparingInt(CandidatePlan::workloadCount)
                .thenComparingInt(CandidatePlan::workloadMinutes)
                .thenComparing(CandidatePlan::geId)
                .thenComparing(CandidatePlan::start);
    }

    private RecommendationCandidate toView(CandidatePlan plan, int rank) {
        return new RecommendationCandidate(plan.geId(), plan.start(), plan.end(), plan.durationMinutes(),
                plan.zoneId(), rank,
                "FEASIBLE", plan.locationAssessment(), plan.originSource(), plan.travelConfidence(),
                plan.workloadCount(), plan.workloadMinutes(), plan.reasons());
    }

    private Evaluation empty(VisitSession session, Instant now, RecommendationStatus status,
                             boolean truncated, String diagnostic) {
        return new Evaluation(new VisitSchedulingRecommendationView(session.getId(), session.getVersion(), now,
                policy.getVersion(), status, truncated, List.of(diagnostic), List.of(), List.of()),
                List.of(), List.of(), 0);
    }

    private void requireVersion(VisitSession session, Long expected) {
        if (expected == null || !Objects.equals(session.getVersion(), expected))
            throw new VisitOperationsConflictException("Visit Session changed; refresh recommendations");
    }

    private void validateZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank() || zoneId.length() > 64)
            throw new IllegalArgumentException("A valid IANA timezone is required");
        try { ZoneId.of(zoneId); }
        catch (DateTimeException ex) { throw new IllegalArgumentException("A valid IANA timezone is required"); }
    }

    private record Stop(VisitSessionItem item, TravelPoint point) {}
    private record CoverageAssessment(boolean eligible, boolean localityFit) {}
    private record ReservedVisit(Long sessionId, Long geId, Instant start, Instant end,
                                 VisitSessionStatus status, TravelPoint firstStop, TravelPoint lastStop) {}
    private record ReservationNeighborhood(Map<Long, List<ReservedVisit>> byGe,
            Map<Long, List<ReservedVisit>> boundedByGe, boolean complete) {
        static ReservationNeighborhood incomplete() {
            return new ReservationNeighborhood(Map.of(), Map.of(), false);
        }
    }
    private record CandidateStartSearch(List<Instant> starts, boolean truncated) {}
    private record CandidateAssessment(CandidatePlan plan, RecommendationRejectionReason rejectionReason) {}
    private record Workload(int count, int minutes) {}
    private record LocationUse(String assessment, String reason, TravelPoint point) {
        static LocationUse unavailable(String reason) { return new LocationUse("UNAVAILABLE", reason, null); }
        static LocationUse degraded(String reason) { return new LocationUse("DEGRADED", reason, null); }
    }
    private record CandidatePlan(Long geId, Instant start, Instant end, int durationMinutes, String zoneId,
            String locationAssessment, String locationReason, String travelConfidence, String originSource,
            int workloadCount, int workloadMinutes, long preferredGapMinutes, long firstLegMinutes,
            boolean localityFit, List<RecommendationReason> reasons) {}
    private record Evaluation(VisitSchedulingRecommendationView view, List<CandidatePlan> feasible,
                              List<CandidatePlan> ranked, int durationMinutes) {}

    public record ApprovalAssessment(Long selectedGeId, Instant selectedStart, int durationMinutes,
            Long topGeId, Instant topStart, Instant validatedAt, String policyVersion, boolean override,
            String locationAssessment, String travelConfidence, RecommendationStatus recommendationStatus) {}

    public record LiveRepairCandidate(Long geId, Instant start, Instant end, int durationMinutes,
            String zoneId, String travelConfidence, String locationAssessment) {}
}
