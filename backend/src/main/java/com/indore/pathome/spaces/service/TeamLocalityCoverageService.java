package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.CoverageOptionView;
import com.indore.pathome.spaces.dto.CoverageOptionsView;
import com.indore.pathome.spaces.dto.TeamLocalityCoverageCommand;
import com.indore.pathome.spaces.dto.TeamLocalityCoveragePage;
import com.indore.pathome.spaces.dto.TeamLocalityCoverageStateCommand;
import com.indore.pathome.spaces.dto.TeamLocalityCoverageView;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.TeamLocalityCoverage;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.OperationalCommandUnavailableException;
import com.indore.pathome.spaces.exception.StaffAccessConflictException;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.TeamLocalityCoverageRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned management of verified canonical locality-to-Team coverage. */
@Service
public class TeamLocalityCoverageService {
    public static final int MAX_PAGE_SIZE = 100;
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final TeamLocalityCoverageRepository coverages;
    private final SupportedCityRepository cities;
    private final LocalityRepository localities;
    private final OperatingTeamRepository teams;
    private final UserRepository users;
    private final StaffAccessService access;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final TransactionalCommandBounds commandBounds;
    private final DatabaseClock databaseClock;
    private final EntityManager entityManager;

    public TeamLocalityCoverageService(TeamLocalityCoverageRepository coverages,
                                       SupportedCityRepository cities,
                                       LocalityRepository localities,
                                       OperatingTeamRepository teams,
                                       UserRepository users,
                                       StaffAccessService access,
                                       OperationalSecurityGuards guards,
                                       OperationalAuditService audit,
                                       TransactionalCommandBounds commandBounds,
                                       DatabaseClock databaseClock,
                                       EntityManager entityManager) {
        this.coverages = coverages;
        this.cities = cities;
        this.localities = localities;
        this.teams = teams;
        this.users = users;
        this.access = access;
        this.guards = guards;
        this.audit = audit;
        this.commandBounds = commandBounds;
        this.databaseClock = databaseClock;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public TeamLocalityCoveragePage list(Long actorId, Long selectedCityId, int page, int size) {
        IntakeRoutingAuthority.Scope scope = IntakeRoutingAuthority.resolve(access, actorId);
        if (selectedCityId != null && !scope.canManageCoverage(selectedCityId)) {
            throw new AccessDeniedException("Current City routing authority is required");
        }
        Pageable pageable = PageRequest.of(requirePage(page), requirePageSize(size));
        Page<TeamLocalityCoverage> rows;
        if (selectedCityId != null) {
            rows = coverages.findPageByCityIds(List.of(selectedCityId), pageable);
        } else if (scope.global()) {
            rows = coverages.findAllPage(pageable);
        } else {
            rows = coverages.findPageByCityIds(scope.cityIds(), pageable);
        }
        return new TeamLocalityCoveragePage(rows.getContent().stream()
                .map(TeamLocalityCoverageService::toView).toList(),
                rows.getTotalElements(), page, size, rows.getTotalPages());
    }

    @Transactional(readOnly = true)
    public List<TeamLocalityCoverageView> activeForLocality(Long actorId, Long cityId, Long localityId) {
        requirePositive(cityId, "City");
        requirePositive(localityId, "Locality");
        IntakeRoutingAuthority.Scope scope = IntakeRoutingAuthority.resolve(access, actorId);
        if (!scope.canManageCoverage(cityId)) {
            throw new AccessDeniedException("Current City routing authority is required");
        }
        Locality locality = localities.findById(localityId)
                .orElseThrow(() -> new EntityNotFoundException("Locality not found"));
        if (locality.getSupportedCity() == null
                || !cityId.equals(locality.getSupportedCity().getId())) {
            throw new EntityNotFoundException("Locality not found");
        }
        return coverages.findActiveByCityAndLocality(cityId, localityId).stream()
                .map(TeamLocalityCoverageService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public CoverageOptionsView options(Long actorId, Long cityId) {
        requirePositive(cityId, "City");
        IntakeRoutingAuthority.Scope scope = IntakeRoutingAuthority.resolve(access, actorId);
        if (!scope.canManageCoverage(cityId)) {
            throw new AccessDeniedException("Current City routing authority is required");
        }
        SupportedCity city = cities.findByIdAndActiveTrue(cityId)
                .orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
        List<CoverageOptionView> localityOptions = localities
                .findBySupportedCityIdOrderBySectorNameAscIdAsc(cityId).stream()
                .map(locality -> new CoverageOptionView(locality.getId(), locality.getSectorName(),
                        cityId, null))
                .toList();
        List<CoverageOptionView> teamOptions = teams.findAllByCityIdOrderByCodeAsc(cityId).stream()
                .filter(OperatingTeam::isActive)
                .map(team -> new CoverageOptionView(team.getId(), team.getDisplayName(),
                        cityId, team.getVersion()))
                .toList();
        return new CoverageOptionsView(
                new CoverageOptionView(city.getId(), city.getDisplayName(), city.getId(), city.getVersion()),
                localityOptions, teamOptions);
    }

    @Transactional(readOnly = true)
    public List<CoverageOptionView> cities(Long actorId) {
        IntakeRoutingAuthority.Scope scope = IntakeRoutingAuthority.resolve(access, actorId);
        List<SupportedCity> available = scope.global()
                ? cities.findAllByActiveTrueOrderByDisplayNameAsc()
                : cities.findAllById(scope.cityIds()).stream().filter(SupportedCity::isActive)
                    .sorted(java.util.Comparator.comparing(SupportedCity::getDisplayName)
                            .thenComparing(SupportedCity::getId))
                    .toList();
        return available.stream()
                .map(city -> new CoverageOptionView(city.getId(), city.getDisplayName(),
                        city.getId(), city.getVersion()))
                .toList();
    }

    @Transactional
    public TeamLocalityCoverageView create(Long actorId, TeamLocalityCoverageCommand command) {
        validateCommand(command);
        try {
            return createBounded(actorId, command);
        } catch (RuntimeException failure) {
            throw OperationalCommandUnavailableException.translate(failure);
        }
    }

    private TeamLocalityCoverageView createBounded(Long actorId, TeamLocalityCoverageCommand command) {
        commandBounds.apply();
        IntakeRoutingAuthority.Scope scope = IntakeRoutingAuthority.resolve(access, actorId);
        if (!scope.canManageCoverage(command.cityId())) {
            throw new AccessDeniedException("Current City routing authority is required");
        }
        SupportedCity observedCity = cities.findById(command.cityId())
                .orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
        Locality observedLocality = localities.findById(command.localityId())
                .orElseThrow(() -> new EntityNotFoundException("Locality not found"));
        OperatingTeam observedTeam = teams.findById(command.teamId())
                .orElseThrow(() -> new EntityNotFoundException("Operating Team not found"));
        validateSameCity(observedCity, observedLocality, observedTeam);
        Long observedTeamCityId = observedTeam.getCity().getId();
        entityManager.detach(observedCity);
        entityManager.detach(observedLocality);
        entityManager.detach(observedTeam.getCity());
        entityManager.detach(observedTeam);

        guards.acquire(List.of(command.cityId()), List.of(command.teamId()), List.of(actorId));
        SupportedCity city = cities.findLockedById(command.cityId())
                .orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
        OperatingTeam team = teams.findLockedById(command.teamId())
                .orElseThrow(() -> new EntityNotFoundException("Operating Team not found"));
        Locality locality = localities.findLockedById(command.localityId())
                .orElseThrow(() -> new EntityNotFoundException("Locality not found"));
        validateSameCity(city, locality, team);
        if (!city.isActive() || !team.isActive() || !Objects.equals(team.getCity().getId(), city.getId())) {
            throw new StaffAccessConflictException("Coverage requires an active City and same-City Team");
        }
        /*
         * All canonical evidence is now locked. Re-resolve the caller's live capabilities so an
         * expiring grant cannot be admitted from the pre-lock snapshot, while preserving the
         * already-lawful guard order above.
         */
        IntakeRoutingAuthority.Scope finalScope = IntakeRoutingAuthority.resolve(access, actorId);
        if (!finalScope.canManageCoverage(command.cityId())) {
            throw new AccessDeniedException("Current City routing authority is required");
        }
        User actor = users.getReferenceById(actorId);
        if (coverages.findByCanonicalLocalityIdAndOperatingTeamId(locality.getId(), team.getId())
                .isPresent()) {
            throw new StaffAccessConflictException(
                    "Coverage for this locality and Team already exists; use the explicit versioned restore action");
        }
        TeamLocalityCoverage coverage = coverages.saveAndFlush(new TeamLocalityCoverage(
                city, locality, team, actor, databaseClock.now(), command.reasonCode()));
        audit.recordUserEvent(actor,
                "TEAM_LOCALITY_COVERAGE_CREATED",
                "TEAM_LOCALITY_COVERAGE", coverage.getId(), command.reasonCode(), city, team,
                coverageDetails(coverage));
        return toView(coverage);
    }

    @Transactional
    public TeamLocalityCoverageView changeState(Long actorId, Long coverageId,
                                                TeamLocalityCoverageStateCommand command) {
        if (command == null || command.expectedVersion() == null || command.expectedVersion() < 0
                || command.reasonCode() == null || !REASON.matcher(command.reasonCode()).matches()) {
            throw new IllegalArgumentException("Expected coverage version and reason code are required");
        }
        try {
            return changeStateBounded(actorId, coverageId, command);
        } catch (RuntimeException failure) {
            throw OperationalCommandUnavailableException.translate(failure);
        }
    }

    private TeamLocalityCoverageView changeStateBounded(Long actorId, Long coverageId,
                                                         TeamLocalityCoverageStateCommand command) {
        commandBounds.apply();
        requirePositive(coverageId, "Coverage");
        TeamLocalityCoverage observed = coverages.findById(coverageId)
                .orElseThrow(() -> new EntityNotFoundException("Coverage not found"));
        Long cityId = observed.getSupportedCity().getId();
        Long teamId = observed.getOperatingTeam().getId();
        IntakeRoutingAuthority.Scope scope = IntakeRoutingAuthority.resolve(access, actorId);
        if (!scope.canManageCoverage(cityId)) {
            throw new EntityNotFoundException("Coverage not found");
        }
        entityManager.detach(observed.getSupportedCity());
        entityManager.detach(observed.getCanonicalLocality());
        entityManager.detach(observed.getOperatingTeam().getCity());
        entityManager.detach(observed.getOperatingTeam());
        entityManager.detach(observed);
        guards.acquire(List.of(cityId), List.of(teamId), List.of(actorId));
        TeamLocalityCoverage coverage = coverages.findLockedById(coverageId)
                .orElseThrow(() -> new EntityNotFoundException("Coverage not found"));
        IntakeRoutingAuthority.Scope finalScope = IntakeRoutingAuthority.resolve(access, actorId);
        if (!finalScope.canManageCoverage(cityId)) {
            throw new AccessDeniedException("Current City routing authority is required");
        }
        if (!Objects.equals(coverage.getVersion(), command.expectedVersion())) {
            throw new StaffAccessConflictException("Coverage version is stale; reload and retry");
        }
        if (coverage.isActive() == command.active()) return toView(coverage);
        User actor = users.getReferenceById(actorId);
        coverage.changeActive(command.active(), actor, databaseClock.now(), command.reasonCode());
        entityManager.flush();
        audit.recordUserEvent(actor,
                command.active() ? "TEAM_LOCALITY_COVERAGE_REACTIVATED" : "TEAM_LOCALITY_COVERAGE_DEACTIVATED",
                "TEAM_LOCALITY_COVERAGE", coverage.getId(), command.reasonCode(),
                coverage.getSupportedCity(), coverage.getOperatingTeam(), coverageDetails(coverage));
        return toView(coverage);
    }

    private static void validateCommand(TeamLocalityCoverageCommand command) {
        if (command == null) throw new IllegalArgumentException("Coverage details are required");
        requirePositive(command.cityId(), "City");
        requirePositive(command.localityId(), "Locality");
        requirePositive(command.teamId(), "Team");
        if (command.reasonCode() == null || !REASON.matcher(command.reasonCode()).matches()) {
            throw new IllegalArgumentException("A bounded uppercase reason code is required");
        }
    }

    private static void validateSameCity(SupportedCity city, Locality locality, OperatingTeam team) {
        Long cityId = city.getId();
        Long localityCityId = locality.getSupportedCity() == null
                ? null : locality.getSupportedCity().getId();
        Long teamCityId = team.getCity() == null ? null : team.getCity().getId();
        if (cityId == null || !cityId.equals(localityCityId) || !cityId.equals(teamCityId)) {
            throw new IllegalArgumentException("Coverage locality and Team must belong to the selected City");
        }
    }

    private static Map<String, Object> coverageDetails(TeamLocalityCoverage coverage) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cityId", coverage.getSupportedCity().getId());
        details.put("coverageId", coverage.getId());
        details.put("selectedLocalityId", coverage.getCanonicalLocality().getId());
        details.put("teamId", coverage.getOperatingTeam().getId());
        details.put("coverageActive", coverage.isActive());
        return details;
    }

    private static TeamLocalityCoverageView toView(TeamLocalityCoverage coverage) {
        return new TeamLocalityCoverageView(coverage.getId(),
                coverage.getSupportedCity().getId(), coverage.getSupportedCity().getDisplayName(),
                coverage.getCanonicalLocality().getId(), coverage.getCanonicalLocality().getSectorName(),
                coverage.getOperatingTeam().getId(), coverage.getOperatingTeam().getDisplayName(),
                coverage.isActive(), coverage.getVersion(), coverage.getCreatedAt(), coverage.getUpdatedAt(),
                coverage.getCreatedBy().getId(), coverage.getUpdatedBy().getId(),
                coverage.getChangeReasonCode());
    }

    private static int requirePage(int page) {
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        return page;
    }

    private static int requirePageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return size;
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) throw new IllegalArgumentException(field + " ID must be positive");
    }
}
