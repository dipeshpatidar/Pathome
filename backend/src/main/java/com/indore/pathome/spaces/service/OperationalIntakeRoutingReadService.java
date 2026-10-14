package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.IntakeRoutingCandidatesView;
import com.indore.pathome.spaces.dto.IntakeRoutingRequestPage;
import com.indore.pathome.spaces.dto.IntakeRoutingRequestView;
import com.indore.pathome.spaces.dto.IntakeRoutingTeamPage;
import com.indore.pathome.spaces.dto.IntakeRoutingTeamView;
import com.indore.pathome.spaces.dto.OperatingTeamProvisioningCityView;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.repository.OperationalIntakeRoutingRepository;
import com.indore.pathome.spaces.repository.OperationalIntakeRoutingRepository.RoutingRow;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Package 3B candidate reads. Read-only, bounded, and scoped from live database-backed authority.
 *
 * <p>A City Team Admin sees only eligible intake that is already canonically resolved to an authorized
 * City; unresolved intake, contradictory intake whose canonical locality belongs to a different City, and
 * intake belonging to any other City are all concealed with the single answer a missing Request produces.
 * The same canonical-source rule governs the collection read, the candidate-detail read and the routing
 * command, so no path can be used to acquire authority the others refuse. Global Staff Admin sees eligible
 * intake across every City and keeps its diagnostic read of unresolved and contradictory sources.
 */
@Service
public class OperationalIntakeRoutingReadService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final OperationalIntakeRoutingRepository routing;
    private final SupportedCityRepository cities;
    private final OperatingTeamRepository teams;
    private final StaffAccessService access;

    public OperationalIntakeRoutingReadService(OperationalIntakeRoutingRepository routing,
                                                SupportedCityRepository cities, OperatingTeamRepository teams,
                                                StaffAccessService access) {
        this.routing = routing;
        this.cities = cities;
        this.teams = teams;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public IntakeRoutingRequestPage listEligibleRequests(Long actorId, int page, int size) {
        IntakeRoutingAuthority.Scope scope = IntakeRoutingAuthority.resolve(access, actorId);
        Pageable pageable = PageRequest.of(requirePage(page), requirePageSize(size));
        Page<RoutingRow> result = scope.global()
                ? routing.findEligibleRequestPage(pageable)
                : routing.findEligibleCityScopedRequestPage(scope.cityIds(), pageable);
        return new IntakeRoutingRequestPage(result.getContent().stream()
                .map(OperationalIntakeRoutingReadService::toView).toList(),
                result.getTotalElements(), page, size, result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public IntakeRoutingCandidatesView readCandidates(Long actorId, Long requestId, Long cityId, int page, int size) {
        IntakeRoutingAuthority.Scope scope = IntakeRoutingAuthority.resolve(access, actorId);
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("Visit Request ID must be positive");
        if (cityId == null || cityId <= 0) throw new IllegalArgumentException("City ID must be positive");
        RoutingRow row = routing.findRoutingRow(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Request not found"));
        // Source scope is decided from the single persisted snapshot before any City or Team detail is
        // loaded and before any response fragment is built: a non-global caller owns only a source that is
        // canonically resolved to exactly one City it is authorized for. Unresolved, contradictory and
        // foreign sources are concealed with the same answer a missing Request produces, so neither the
        // Request, nor its Listing, its location, nor any Team metadata is ever disclosed.
        if (!scope.coversCanonicalSource(row.getCityId(), row.getLocalityCityId())) {
            throw new EntityNotFoundException("Visit Request not found");
        }
        if (!scope.global() && !scope.coversCity(cityId)) {
            throw new EntityNotFoundException("Supported City not found");
        }
        SupportedCity city = cities.findByIdAndActiveTrue(cityId)
                .orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
        Pageable pageable = PageRequest.of(requirePage(page), requirePageSize(size));
        Page<OperatingTeam> result = teams.findActiveTeamPageByCityId(cityId, pageable);
        return new IntakeRoutingCandidatesView(toView(row),
                new OperatingTeamProvisioningCityView(city.getId(), city.getCode(), city.getDisplayName(),
                        city.isActive(), city.getVersion()),
                new IntakeRoutingTeamPage(result.getContent().stream()
                        .map(OperationalIntakeRoutingReadService::toView).toList(),
                        result.getTotalElements(), page, size, result.getTotalPages()));
    }

    static IntakeRoutingRequestView toView(RoutingRow row) {
        Long sourceCityId = row.getCityId();
        Long localityCityId = row.getLocalityCityId();
        IntakeRoutingRequestView.LocationState locationState;
        if (sourceCityId == null || localityCityId == null) {
            locationState = IntakeRoutingRequestView.LocationState.UNRESOLVED;
        } else if (sourceCityId.equals(localityCityId)) {
            locationState = IntakeRoutingRequestView.LocationState.CANONICAL;
        } else {
            locationState = IntakeRoutingRequestView.LocationState.CONFLICTING;
        }
        boolean standalone = row.getSessionId() == null;
        boolean coordinatorAssigned = row.getCoordinatorUserId() != null;
        boolean teamReady = Boolean.TRUE.equals(row.getScopeReady()) && sourceCityId != null
                && row.getTeamId() != null && Boolean.TRUE.equals(row.getCityActive())
                && Boolean.TRUE.equals(row.getTeamActive()) && sourceCityId.equals(row.getTeamCityId());
        boolean eligible = standalone && "RECEIVED".equals(row.getStatus())
                && !coordinatorAssigned && row.getTeamId() == null;
        return new IntakeRoutingRequestView(row.getId(), row.getVersion(), row.getStatus(), row.getCreatedAt(),
                row.getListingId(), row.getListingTitle(), sourceCityId, row.getTeamId(),
                row.getCoordinatorUserId(), row.getCanonicalLocalityId(), localityCityId, locationState,
                teamReady, standalone, coordinatorAssigned, eligible);
    }

    private static IntakeRoutingTeamView toView(OperatingTeam team) {
        return new IntakeRoutingTeamView(team.getId(), team.getCity().getId(), team.getCode(),
                team.getDisplayName(), team.isActive(), team.getVersion());
    }

    private static int requirePage(int page) {
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        return page;
    }

    private static int requirePageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        return size;
    }
}
