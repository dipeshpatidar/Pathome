package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.CreateOperatingTeamCommand;
import com.indore.pathome.spaces.dto.OperatingTeamProvisioningCityPage;
import com.indore.pathome.spaces.dto.OperatingTeamProvisioningCityView;
import com.indore.pathome.spaces.dto.OperatingTeamProvisioningPage;
import com.indore.pathome.spaces.dto.OperatingTeamProvisioningView;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.OperatingTeamProvisioningUnavailableException;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Package 3A governed Operating Team provisioning.
 *
 * <p>The only creator of an active Team is a holder of current, effective {@code STAFF_ADMIN@GLOBAL}
 * backed by an active staff profile; the only creation that is persisted is an active Team under an
 * active canonical City. Authority, City state and the retained {@code (city_id, code)} key are all
 * re-read under the established guard order (governance lock → City row → actor profile/grant rows)
 * so a revoked grant, a stale City version or a concurrent creation is decided from authoritative
 * database facts rather than from anything the caller sent.
 *
 * <p>The command transaction is bounded before any guard is taken (see
 * {@link TransactionalCommandBounds}): a competing governance advisory lock, City row lock or
 * actor/grant row lock can delay the command, but never indefinitely. A bounded-wait failure rolls the
 * whole command back and is reported as a sanitized {@code 503}; every other failure keeps its own
 * (401/403/409/400/500) meaning.
 */
@Service
public class OperatingTeamProvisioningService {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private static final Pattern CODE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");
    private static final int MAX_CODE_LENGTH = 64;
    private static final int MAX_DISPLAY_NAME_LENGTH = 160;

    private final SupportedCityRepository cities;
    private final OperatingTeamRepository teams;
    private final StaffAccessService access;
    private final StaffGovernanceLock governanceLock;
    private final OperationalSecurityGuards guards;
    private final OperationalAuditService audit;
    private final EntityManager entityManager;
    private final TransactionalCommandBounds commandBounds;

    public OperatingTeamProvisioningService(SupportedCityRepository cities, OperatingTeamRepository teams,
                                            StaffAccessService access, StaffGovernanceLock governanceLock,
                                            OperationalSecurityGuards guards, OperationalAuditService audit,
                                            EntityManager entityManager, TransactionalCommandBounds commandBounds) {
        this.cities = cities;
        this.teams = teams;
        this.access = access;
        this.governanceLock = governanceLock;
        this.guards = guards;
        this.audit = audit;
        this.entityManager = entityManager;
        this.commandBounds = commandBounds;
    }

    @Transactional(readOnly = true)
    public OperatingTeamProvisioningCityPage listActiveCities(Long actorId, int page, int size) {
        access.requireGlobalAdmin(actorId);
        Pageable pageable = PageRequest.of(requirePage(page), requirePageSize(size));
        Page<SupportedCity> result = cities.findActiveCityPage(pageable);
        return new OperatingTeamProvisioningCityPage(result.getContent().stream()
                .map(OperatingTeamProvisioningService::toCityView).toList(),
                result.getTotalElements(), page, size, result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public OperatingTeamProvisioningPage listCityTeams(Long actorId, Long cityId, int page, int size) {
        access.requireGlobalAdmin(actorId);
        SupportedCity city = requireCity(cityId);
        Pageable pageable = PageRequest.of(requirePage(page), requirePageSize(size));
        Page<OperatingTeam> result = teams.findTeamPageByCityId(cityId, pageable);
        return new OperatingTeamProvisioningPage(toCityView(city), result.getContent().stream()
                .map(OperatingTeamProvisioningService::toView).toList(),
                result.getTotalElements(), page, size, result.getTotalPages());
    }

    @Transactional
    public OperatingTeamProvisioningView createTeam(Long actorId, Long cityId, CreateOperatingTeamCommand command) {
        if (cityId == null || cityId <= 0) throw new IllegalArgumentException("City ID must be a positive identifier");
        if (command == null) throw new IllegalArgumentException("Team creation details are required");
        String code = requireCode(command.code());
        String displayName = requireDisplayName(command.displayName());
        String reasonCode = requireReason(command.reasonCode());
        Long expectedCityVersion = requireExpectedCityVersion(command.expectedCityVersion());

        // Bound this mutation transaction before any blocking guard is taken, on the connection that
        // acquires the governance advisory lock, the City row lock and the actor profile/grant locks.
        commandBounds.apply();
        try {
            return persistTeam(actorId, cityId, code, displayName, reasonCode, expectedCityVersion);
        } catch (RuntimeException failure) {
            // Bounded-wait failures become a sanitized 503; everything else is rethrown untouched.
            throw OperatingTeamProvisioningUnavailableException.translate(failure);
        }
    }

    private OperatingTeamProvisioningView persistTeam(Long actorId, Long cityId, String code, String displayName,
                                                      String reasonCode, Long expectedCityVersion) {
        access.requireGlobalAdmin(actorId);
        governanceLock.acquire();
        access.requireGlobalAdmin(actorId);
        guards.acquire(List.of(cityId), List.of(), List.of(actorId));
        SupportedCity city = cities.findLockedById(cityId)
                .orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
        entityManager.refresh(city, LockModeType.PESSIMISTIC_WRITE);
        User actor = access.requireGlobalAdmin(actorId);
        if (!Objects.equals(guards.cityVersion(cityId), expectedCityVersion))
            throw new VisitOperationsConflictException("City version is stale; refresh and retry");
        if (!guards.cityIsActive(cityId))
            throw new VisitOperationsConflictException("Team creation requires an active canonical City");
        if (teams.findByCityIdAndCode(cityId, code).isPresent())
            throw new VisitOperationsConflictException(
                    "An operating Team with this code already exists in the City");

        OperatingTeam team = teams.saveAndFlush(new OperatingTeam(city, code, displayName, true));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cityId", cityId);
        details.put("teamId", team.getId());
        details.put("expectedCityVersion", expectedCityVersion);
        details.put("teamVersion", team.getVersion());
        audit.recordUserEvent(actor, "OPERATING_TEAM_CREATED", "OPERATING_TEAM", team.getId(), reasonCode,
                city, team, details);
        entityManager.flush();
        return toView(team);
    }

    private SupportedCity requireCity(Long cityId) {
        if (cityId == null || cityId <= 0) throw new IllegalArgumentException("City ID must be a positive identifier");
        return cities.findById(cityId).orElseThrow(() -> new EntityNotFoundException("Supported City not found"));
    }

    private static String requireCode(String raw) {
        String code = raw == null ? null : raw.trim();
        if (code == null || code.isEmpty() || code.length() > MAX_CODE_LENGTH || !CODE.matcher(code).matches())
            throw new IllegalArgumentException(
                    "Team code must be a lowercase canonical slug of 1 to " + MAX_CODE_LENGTH + " characters");
        return code;
    }

    private static String requireDisplayName(String raw) {
        String displayName = raw == null ? null : raw.trim();
        if (displayName == null || displayName.isEmpty() || displayName.length() > MAX_DISPLAY_NAME_LENGTH
                || CONTROL_CHARACTERS.matcher(displayName).find())
            throw new IllegalArgumentException(
                    "Team display name must be 1 to " + MAX_DISPLAY_NAME_LENGTH + " visible characters");
        return displayName;
    }

    private static String requireReason(String raw) {
        if (raw == null || !REASON.matcher(raw).matches())
            throw new IllegalArgumentException("A bounded uppercase reason code is required");
        return raw;
    }

    private static Long requireExpectedCityVersion(Long expectedCityVersion) {
        if (expectedCityVersion == null || expectedCityVersion < 0)
            throw new IllegalArgumentException("A nonnegative expected City version is required");
        return expectedCityVersion;
    }

    private static int requirePage(int page) {
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        return page;
    }

    private static int requirePageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        return size;
    }

    private static OperatingTeamProvisioningView toView(OperatingTeam team) {
        return new OperatingTeamProvisioningView(team.getId(), team.getCity().getId(), team.getCode(),
                team.getDisplayName(), team.isActive(), team.getVersion(), team.getCreatedAt(), team.getUpdatedAt());
    }

    private static OperatingTeamProvisioningCityView toCityView(SupportedCity city) {
        return new OperatingTeamProvisioningCityView(city.getId(), city.getCode(), city.getDisplayName(),
                city.isActive(), city.getVersion());
    }
}
