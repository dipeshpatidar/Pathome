package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.GeAvailabilityResultCode;
import com.indore.pathome.spaces.dto.OperationsGeAvailabilityView;
import com.indore.pathome.spaces.dto.RecommendationRejectionReason;
import com.indore.pathome.spaces.dto.RecommendationRequest;
import com.indore.pathome.spaces.dto.RecommendationStatus;
import com.indore.pathome.spaces.dto.VisitSchedulingRecommendationView;
import com.indore.pathome.spaces.entity.GroundExecutiveSchedulingProfile;
import com.indore.pathome.spaces.entity.VisitPolicy;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.GroundExecutiveSchedulingProfileRepository;
import com.indore.pathome.spaces.repository.VisitPolicyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Restricted, plan-specific Ground Executive availability information.
 *
 * <p>Admission deliberately reuses the owned-DRAFT Session seam: the caller must currently own an
 * operationally ready DRAFT Session and hold an effective {@code OPS_COORDINATE@TEAM} grant on the
 * matching active canonical City/Team. Admin, supervisor, intake-only, tenant, lessor, GE and another
 * coordinator never pass through this path.
 *
 * <p>The population is the same active potential City scheduling population the scheduler evaluates.
 * Only a coarse allow-listed outcome is exposed for each entry; private shift, leave, reservation,
 * travel, commitment and raw rejection reasons are never serialized. The disclosure audit row is
 * written before the service returns, so an audit failure yields no roster response.
 */
@Service
public class OperationsGeAvailabilityService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_QUERY_LENGTH = 80;

    /**
     * Only bounded plan-level diagnostics may reach the client. Any diagnostic naming a Ground
     * Executive (the scheduler's {@code GE_*} codes) is deliberately absent.
     */
    private static final Set<String> SAFE_PLAN_EXPLANATIONS = Set.of(
            "SESSION_NOT_DRAFT",
            "SESSION_STATE_INCOMPLETE",
            "ITINERARY_EXCEEDS_SEARCH_BOUND",
            "ACTIVE_ITEM_PENDING_CONFIRMATION",
            "NO_CONFIRMED_ACTIVE_STOPS",
            "PROPERTY_NOT_ACTIVE",
            "COORDINATING_REQUEST_WITHOUT_ACTIVE_STOP",
            "STRUCTURED_TENANT_WINDOW_REQUIRED",
            "INVALID_TENANT_ZONE",
            "TENANT_WINDOWS_DO_NOT_INTERSECT",
            "CONFIRMED_PROPERTY_WINDOW_REQUIRED",
            "PROPERTY_CITY_MISMATCH",
            "ITINERARY_EXCEEDS_SESSION_DURATION_POLICY",
            "TENANT_WINDOW_EXPIRED",
            "NO_ACTIVE_CITY_COVERAGE",
            "RESERVATION_SEARCH_BOUND_EXCEEDED");

    private final OwnedOperationsAuthorizationService ownedAuthorization;
    private final VisitSchedulingRecommendationService recommendations;
    private final GroundExecutiveSchedulingProfileRepository profiles;
    private final VisitPolicyRepository visitPolicies;
    private final OperationalAuditService operationalAudit;

    @Value("${app.jwt.secret:}")
    private String discoveryCursorSecret;

    public OperationsGeAvailabilityService(OwnedOperationsAuthorizationService ownedAuthorization,
            VisitSchedulingRecommendationService recommendations,
            GroundExecutiveSchedulingProfileRepository profiles,
            VisitPolicyRepository visitPolicies,
            OperationalAuditService operationalAudit) {
        this.ownedAuthorization = ownedAuthorization;
        this.recommendations = recommendations;
        this.profiles = profiles;
        this.visitPolicies = visitPolicies;
        this.operationalAudit = operationalAudit;
    }

    @Transactional
    public OperationsGeAvailabilityView availability(Long actorId, Long sessionId,
            RecommendationRequest command, String q, String cursor, int size) {
        if (command == null || command.expectedSessionVersion() == null)
            throw new IllegalArgumentException("Expected Visit Session version is required");
        OwnedOperationsAuthorizationService.Actor actor = ownedAuthorization.admit(actorId);
        if (actor.admin()) throw new AccessDeniedException("Use the Admin scheduling route");
        VisitSession session = ownedAuthorization.lockTargetSession(sessionId);
        ownedAuthorization.requireOwnedDraftSession(actor, session);
        if (session.getStatus() != VisitSessionStatus.DRAFT)
            throw new VisitOperationsConflictException("Only draft sessions can expose availability");
        if (!Objects.equals(session.getVersion(), command.expectedSessionVersion()))
            throw new VisitOperationsConflictException("Visit Session changed; refresh availability");

        if (size < 1 || size > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Availability page size must be between 1 and 50");
        String query = normalizeAvailabilityQuery(q);
        if (discoveryCursorSecret == null || discoveryCursorSecret.isBlank())
            throw new IllegalStateException("Discovery cursor signing secret is unavailable");
        Instant now = Instant.now();
        long afterId = cursor == null ? 0 : OeDiscoveryCursor.decode(discoveryCursorSecret,
                cursor, actor.id(), session, query, size, now);

        PageRequest batchLimit = PageRequest.of(0, size + 1);
        List<GroundExecutiveSchedulingProfile> found = query.isEmpty()
                ? profiles.scanActiveStaffCoveringCity(session.getCity(), afterId, batchLimit)
                : profiles.scanActiveStaffCoveringCityMatching(session.getCity(), afterId,
                        VisitSchedulingRecommendationService.discoveryNamePattern(query),
                        VisitSchedulingRecommendationService.exactGeUserId(query), batchLimit);
        boolean moreCandidates = found.size() > size;
        List<GroundExecutiveSchedulingProfile> batch = found.subList(0, Math.min(size, found.size()));

        VisitSchedulingRecommendationService.AvailabilityAssessment assessment =
                recommendations.assessOwnedAvailability(session, batch, now);
        VisitSchedulingRecommendationView view = assessment.view();
        boolean planReady = planReady(view.status(), view.diagnostics());
        boolean bounded = view.candidateSearchTruncated();
        /*
         * NO_ELIGIBLE_GE describes this evaluated page's eligible subset, not the owned plan's
         * readiness. A page containing only coverage-mismatched candidates must not turn a complete
         * plan into a global "no eligible GE" statement; the per-entry codes carry that page result.
         */
        RecommendationStatus planStatus = publicPlanStatus(view.status(), planReady);

        List<OperationsGeAvailabilityView.GeAvailabilityEntry> entries = batch.stream()
                .map(profile -> {
                    Long geId = profile.getEmployeeProfile().getUser().getId();
                    return new OperationsGeAvailabilityView.GeAvailabilityEntry(geId,
                            profile.getEmployeeProfile().getUser().getFullName(),
                            classify(planReady, geId, assessment.feasibleGeIds(),
                                    assessment.rejectedByGe(), bounded));
                })
                .toList();

        List<String> explanationCodes = safePlanExplanations(view.diagnostics());
        Long visitPolicyVersion = visitPolicies.findById(VisitPolicy.SINGLETON_ID)
                .map(VisitPolicy::getVersion).orElse(0L);
        OperationsGeAvailabilityView.PlanTimingView timing = toTiming(assessment.planFacts());
        String fingerprint = fingerprint(session, assessment.planFacts(), view.policyVersion(),
                visitPolicyVersion);
        String nextCursor = moreCandidates ? OeDiscoveryCursor.encode(discoveryCursorSecret,
                actor.id(), session, query, size,
                batch.get(batch.size() - 1).getEmployeeProfile().getUser().getId(), now) : null;

        /*
         * The audit row is the authorization to disclose this page. It intentionally contains only
         * bounded metadata: actor, Session target, canonical City/Team, action and reason codes.
         * No returned name, search text, private reason or response payload is stored.
         */
        operationalAudit.recordUserEvent(actor.user(), "GE_AVAILABILITY_VIEWED", "VISIT_SESSION",
                session.getId(), "ROSTER_PAGE_DISCLOSED", session.getSupportedCity(),
                session.getOperatingTeam(), Map.of());

        return new OperationsGeAvailabilityView(session.getId(), session.getVersion(), now,
                view.policyVersion(), visitPolicyVersion, fingerprint, planStatus, planReady,
                explanationCodes, timing, bounded, entries, size, entries.size(), nextCursor);
    }

    static GeAvailabilityResultCode classify(boolean planReady, Long geId, Set<Long> feasibleGeIds,
            Map<Long, List<RecommendationRejectionReason>> rejectedByGe, boolean bounded) {
        if (!planReady) return GeAvailabilityResultCode.PLAN_NOT_READY;
        if (feasibleGeIds.contains(geId)) return GeAvailabilityResultCode.FEASIBLE_OPTION_FOUND;
        List<RecommendationRejectionReason> reasons = rejectedByGe.get(geId);
        if (reasons != null && reasons.contains(RecommendationRejectionReason.COVERAGE_MISMATCH))
            return GeAvailabilityResultCode.COVERAGE_NOT_SUITABLE;
        if (bounded) return GeAvailabilityResultCode.EVALUATION_INCOMPLETE;
        return GeAvailabilityResultCode.NO_FEASIBLE_OPTION_FOUND;
    }

    static boolean planReady(RecommendationStatus status, List<String> diagnostics) {
        if (status == null) return false;
        return switch (status) {
            case SESSION_NOT_SCHEDULABLE, INSUFFICIENT_TENANT_AVAILABILITY,
                    PROPERTY_AVAILABILITY_INCOMPLETE, CONFLICT, RECOMMENDATION_STALE -> false;
            case NO_FEASIBLE_TIME -> !diagnostics.contains("ITINERARY_EXCEEDS_SESSION_DURATION_POLICY")
                    && !diagnostics.contains("TENANT_WINDOW_EXPIRED");
            default -> true;
        };
    }

    /**
     * Projects the evaluator's internal status onto the restricted availability envelope.
     *
     * <p>The information view intentionally carries only plan-level facts. Two evaluator statuses
     * ({@code LOCATION_UNAVAILABLE_FALLBACK_USED} and {@code TRAVEL_ESTIMATE_UNCERTAIN}) are derived
     * from Ground Executive location/travel assessment; exposing them in the envelope would disclose
     * private scheduling metadata even when every per-entry code is already coarse. They therefore
     * collapse to the ordinary feasible outcome. {@code NO_ELIGIBLE_GE} on a ready complete plan is
     * the evaluated page's outcome, not a plan-level conclusion, so it keeps the same neutral
     * projection. A null status remains null: the client renders it as "not returned" rather than
     * echoing an internal code. No other status is rewritten.
     */
    static RecommendationStatus publicPlanStatus(RecommendationStatus status, boolean planReady) {
        if (status == null) return null;
        if (status == RecommendationStatus.LOCATION_UNAVAILABLE_FALLBACK_USED
                || status == RecommendationStatus.TRAVEL_ESTIMATE_UNCERTAIN) {
            return RecommendationStatus.RECOMMENDATION_AVAILABLE;
        }
        if (planReady && status == RecommendationStatus.NO_ELIGIBLE_GE) {
            return RecommendationStatus.RECOMMENDATION_AVAILABLE;
        }
        return status;
    }

    /** Filters every private or GE-specific diagnostic before it can reach the response DTO. */
    static List<String> safePlanExplanations(List<String> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return List.of();
        return diagnostics.stream()
                .filter(SAFE_PLAN_EXPLANATIONS::contains)
                .distinct()
                .toList();
    }

    static String normalizeAvailabilityQuery(String q) {
        if (q == null || q.isBlank()) return "";
        if (q.codePointCount(0, q.length()) > MAX_QUERY_LENGTH)
            throw new IllegalArgumentException("Search query must contain at most 80 printable characters");
        return VisitSchedulingRecommendationService.normalizeDiscoveryQuery(q);
    }

    private static OperationsGeAvailabilityView.PlanTimingView toTiming(
            VisitSchedulingRecommendationService.OwnedPlanFacts facts) {
        VisitSchedulingRecommendationService.OwnedPlanFacts value = facts == null
                ? new VisitSchedulingRecommendationService.OwnedPlanFacts(null, null, null, List.of(),
                        List.of(), 0, null, null, List.of(), false) : facts;
        return new OperationsGeAvailabilityView.PlanTimingView(
                value.configuredStopDwellMinutes(),
                value.maximumSessionDurationMinutes(),
                value.calculatedDurationMinutes(),
                "DWELL_PLUS_BETWEEN_STOP_TRAVEL",
                new OperationsGeAvailabilityView.TenantWindow(value.tenantStart(), value.tenantEnd(),
                        value.tenantZoneId(), value.preferredTimes()),
                value.stops().stream().map(stop -> new OperationsGeAvailabilityView.StopWindow(
                        stop.position(), stop.itemId(), stop.listingId(), stop.confirmationStatus(),
                        stop.availabilityStartAt(), stop.availabilityEndAt(), stop.availabilityZoneId(),
                        stop.availabilitySource(), stop.availabilityConfirmedAt(),
                        stop.offsetMinutes())).toList(),
                value.betweenStopTravel().stream().map(leg ->
                        new OperationsGeAvailabilityView.TravelAllowance(leg.fromPosition(),
                                leg.toPosition(), leg.minutes(), leg.source(), leg.confidence())).toList(),
                value.planningWindowBounded());
    }

    private static String fingerprint(VisitSession session,
            VisitSchedulingRecommendationService.OwnedPlanFacts facts, String schedulingPolicyVersion,
            Long visitPolicyVersion) {
        VisitSchedulingRecommendationService.OwnedPlanFacts value = facts == null
                ? new VisitSchedulingRecommendationService.OwnedPlanFacts(null, null, null, List.of(),
                        List.of(), 0, null, null, List.of(), false) : facts;
        StringBuilder canonical = new StringBuilder()
                .append("session=").append(session.getId())
                .append("|version=").append(session.getVersion())
                .append("|city=").append(session.getSupportedCity().getId())
                .append("|team=").append(session.getOperatingTeam().getId())
                .append("|schedulingPolicy=").append(schedulingPolicyVersion)
                .append("|visitPolicyVersion=").append(visitPolicyVersion)
                .append("|tenantStart=").append(value.tenantStart())
                .append("|tenantEnd=").append(value.tenantEnd())
                .append("|tenantZone=").append(value.tenantZoneId())
                .append("|preferences=").append(value.preferredTimes())
                .append("|dwell=").append(value.configuredStopDwellMinutes())
                .append("|maximumDuration=").append(value.maximumSessionDurationMinutes());
        for (VisitSchedulingRecommendationService.OwnedPlanStop stop : value.stops()) {
            canonical.append("|stop=").append(stop.position())
                    .append(',').append(stop.itemId())
                    .append(',').append(stop.listingId())
                    .append(',').append(stop.confirmationStatus())
                    .append(',').append(stop.availabilityStartAt())
                    .append(',').append(stop.availabilityEndAt())
                    .append(',').append(stop.availabilityZoneId())
                    .append(',').append(stop.availabilitySource());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
