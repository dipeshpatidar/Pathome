package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.StaffGrantCommand;
import com.indore.pathome.spaces.dto.StaffGrantView;
import com.indore.pathome.spaces.dto.StaffStateCommand;
import com.indore.pathome.spaces.dto.StaffUserAccessState;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.OperatingTeam;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.StaffAccessConflictException;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.OperatingTeamRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.SupportedCityRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class StaffAdministrationService {
    private static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final UserRepository users;
    private final EmployeeProfileRepository employeeProfiles;
    private final StaffAccessGrantRepository grants;
    private final SupportedCityRepository cities;
    private final OperatingTeamRepository teams;
    private final StaffAccessService access;
    private final StaffGovernanceLock governanceLock;
    private final OperationalSecurityGuards securityGuards;
    private final OperationalAuditService audit;
    private final DatabaseClock databaseClock;
    private final EntityManager entityManager;

    public StaffAdministrationService(UserRepository users,
                                      EmployeeProfileRepository employeeProfiles,
                                      StaffAccessGrantRepository grants,
                                      SupportedCityRepository cities,
                                      OperatingTeamRepository teams,
                                      StaffAccessService access,
                                      StaffGovernanceLock governanceLock,
                                      OperationalSecurityGuards securityGuards,
                                      OperationalAuditService audit,
                                      DatabaseClock databaseClock,
                                      EntityManager entityManager) {
        this.users = users;
        this.employeeProfiles = employeeProfiles;
        this.grants = grants;
        this.cities = cities;
        this.teams = teams;
        this.access = access;
        this.governanceLock = governanceLock;
        this.securityGuards = securityGuards;
        this.audit = audit;
        this.databaseClock = databaseClock;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public StaffUserAccessState inspectUser(Long actorUserId, Long targetUserId) {
        access.requireGlobalAdmin(actorUserId);
        User target = loadUser(targetUserId);
        EmployeeProfile profile = employeeProfiles.findByUserId(target.getId()).orElse(null);
        List<StaffGrantView> grantViews = grants.findAllByUserIdOrderByCreatedAtDesc(target.getId()).stream()
                .map(StaffAdministrationService::toGrantView).toList();
        return toUserState(target, profile, grantViews);
    }

    @Transactional
    public StaffUserAccessState activateStaff(Long actorUserId, Long targetUserId, StaffStateCommand command) {
        if (command == null) throw new IllegalArgumentException("Staff activation details are required");
        validateReason(command.reasonCode());
        governanceLock.acquire();
        User actor = access.requireGlobalAdmin(actorUserId);
        acquireUserScopeGuards(actor.getId(), targetUserId);
        User target = lockUser(targetUserId);
        EmployeeProfile profile = employeeProfiles.findLockedByUserId(target.getId()).orElse(null);
        if (profile == null) {
            if (command.expectedVersion() != null) throw staleProfile();
            profile = employeeProfiles.saveAndFlush(new EmployeeProfile(target, null, null, null));
        } else {
            requireExpectedVersion(profile, command.expectedVersion());
        }
        if (!profile.isStaffActive()) {
            Instant now = databaseClock.now();
            profile.setStaffActive(true);
            profile.setStaffActivatedAt(now);
            entityManager.flush();
            audit.recordUserEvent(actor, "STAFF_ACTIVATED", "USER", target.getId(), command.reasonCode(),
                    null, null, Map.of("staffActive", true));
        }
        return toUserState(target, profile, grants.findAllByUserIdOrderByCreatedAtDesc(target.getId()).stream()
                .map(StaffAdministrationService::toGrantView).toList());
    }

    @Transactional
    public StaffUserAccessState deactivateStaff(Long actorUserId, Long targetUserId, StaffStateCommand command) {
        if (command == null) throw new IllegalArgumentException("Staff deactivation details are required");
        validateReason(command.reasonCode());
        governanceLock.acquire();
        User actor = access.requireGlobalAdmin(actorUserId);
        List<StaffAccessGrant> observedGrants = acquireUserScopeGuards(actor.getId(), targetUserId);
        User target = lockUser(targetUserId);
        actor = access.requireGlobalAdmin(actorUserId);
        EmployeeProfile profile = employeeProfiles.findLockedByUserId(target.getId())
                .orElseThrow(() -> new EntityNotFoundException("Staff profile not found"));
        requireExpectedVersion(profile, command.expectedVersion());

        boolean hadEffectiveAdmin = profile.isStaffActive() && grants.hasEffectiveGlobalAdmin(target.getId());
        if (hadEffectiveAdmin && grants.countEffectiveGlobalAdminsExcluding(target.getId()) == 0) {
            throw new StaffAccessConflictException("The final effective global Admin cannot be deactivated");
        }

        Instant now = databaseClock.now();
        boolean stateChanged = profile.isStaffActive();
        if (stateChanged) {
            profile.setStaffActive(false);
            profile.setStaffDeactivatedAt(now);
            entityManager.flush();
            audit.recordUserEvent(actor, "STAFF_DEACTIVATED", "USER", target.getId(), command.reasonCode(),
                    null, null, Map.of("staffActive", false));
        }

        List<StaffAccessGrant> activeFacts = grants.findUnrevokedLockedByUserId(target.getId());
        for (StaffAccessGrant grant : activeFacts) {
            grant.revoke(actor, now, command.reasonCode());
            audit.recordUserEvent(actor, "STAFF_ACCESS_GRANT_REVOKED", "STAFF_ACCESS_GRANT", grant.getId(),
                    command.reasonCode(), grant.getCity(), grant.getTeam(), grantDetails(grant));
        }
        if (!stateChanged && !activeFacts.isEmpty()) {
            profile.setStaffDeactivatedAt(now);
            entityManager.flush();
        }
        entityManager.flush();
        return toUserState(target, profile, grants.findAllByUserIdOrderByCreatedAtDesc(target.getId()).stream()
                .map(StaffAdministrationService::toGrantView).toList());
    }

    @Transactional
    public StaffGrantView createGrant(Long actorUserId, Long targetUserId, StaffGrantCommand command) {
        if (command == null) throw new IllegalArgumentException("Grant details are required");
        validateReason(command.reasonCode());
        if (command.capability() == null || command.scopeType() == null) {
            throw new IllegalArgumentException("Capability and scope type are required");
        }

        governanceLock.acquire();
        User actor = access.requireGlobalAdmin(actorUserId);
        if (targetUserId == null || targetUserId <= 0)
            throw new IllegalArgumentException("User ID must be a positive stable ID");
        validateScopeShape(command);
        SupportedCity city = null;
        OperatingTeam team = null;
        if (command.scopeType() == StaffScopeType.CITY) {
            city = cities.findById(command.cityId())
                    .orElseThrow(() -> new IllegalArgumentException("City scope not found"));
        } else if (command.scopeType() == StaffScopeType.TEAM) {
            team = teams.findById(command.teamId())
                    .orElseThrow(() -> new IllegalArgumentException("Team scope not found"));
        }

        Long guardedTeamId = team == null ? null : team.getId();
        SupportedCity teamCity = team == null ? null : team.getCity();
        Long guardedCityId = city != null ? city.getId() : teamCity == null ? null : teamCity.getId();
        if (city != null) entityManager.detach(city);
        if (teamCity != null) entityManager.detach(teamCity);
        if (team != null) entityManager.detach(team);
        securityGuards.acquire(guardedCityId == null ? List.of() : List.of(guardedCityId),
                guardedTeamId == null ? List.of() : List.of(guardedTeamId), List.of(actor.getId(), targetUserId));
        SupportedCity guardedCity = guardedCityId == null ? null : cities.findLockedById(guardedCityId)
                .orElseThrow(() -> new IllegalArgumentException("Active City or Team scope not found"));
        if (city != null) city = guardedCity;
        if (guardedTeamId != null) {
            team = teams.findLockedById(guardedTeamId)
                    .orElseThrow(() -> new IllegalArgumentException("Active City or Team scope not found"));
        }
        actor = access.requireGlobalAdmin(actorUserId);
        User target = lockUser(targetUserId);
        EmployeeProfile profile = employeeProfiles.findLockedByUserId(target.getId())
                .filter(EmployeeProfile::isStaffActive)
                .orElseThrow(() -> new IllegalArgumentException("Target must have an active staff profile"));
        if ((city != null && !securityGuards.cityIsActive(city.getId()))
                || (team != null && (!securityGuards.teamIsActive(team.getId())
                    || !securityGuards.cityIsActive(team.getCity().getId()))))
            throw new IllegalArgumentException("Active City or Team scope not found");

        Instant now = databaseClock.now();
        Instant effectiveAt = command.effectiveAt() == null ? now : command.effectiveAt();
        if (command.expiresAt() != null && !command.expiresAt().isAfter(effectiveAt)) {
            throw new IllegalArgumentException("Grant expiry must be later than its effective time");
        }
        if (command.capability() == StaffCapability.STAFF_ADMIN) {
            if (command.expiresAt() != null || effectiveAt.isAfter(now)) {
                throw new IllegalArgumentException("Global Admin grants are immediate and cannot expire");
            }
            if (actor.getId().equals(target.getId())) {
                throw new StaffAccessConflictException("An Admin must provision another User as a global Admin");
            }
        }

        StaffAccessGrant candidate = new StaffAccessGrant(target, command.capability(), command.scopeType(),
                city, team, effectiveAt, command.expiresAt(), actor, now, command.reasonCode(),
                StaffGrantProvisioningSource.ADMIN_API);
        if (grants.existsOverlappingUnrevoked(target.getId(), command.capability().name(),
                command.scopeType().name(), city == null ? null : city.getId(), team == null ? null : team.getId(),
                effectiveAt, command.expiresAt())) {
            throw new StaffAccessConflictException("An overlapping grant already exists for this User and scope");
        }

        StaffAccessGrant persisted = grants.saveAndFlush(candidate);
        audit.recordUserEvent(actor, "STAFF_ACCESS_GRANT_CREATED", "STAFF_ACCESS_GRANT", persisted.getId(),
                command.reasonCode(), city, team, grantDetails(persisted));
        return toGrantView(persisted);
    }

    @Transactional
    public StaffGrantView revokeGrant(Long actorUserId, Long grantId, String reasonCode) {
        validateReason(reasonCode);
        governanceLock.acquire();
        User actor = access.requireGlobalAdmin(actorUserId);
        StaffAccessGrant observed = grants.findById(grantId)
                .orElseThrow(() -> new EntityNotFoundException("Staff grant not found"));
        Long targetUserId = observed.getUser().getId();
        SupportedCity observedCity = observed.getCity();
        OperatingTeam observedTeam = observed.getTeam();
        SupportedCity observedTeamCity = observedTeam == null ? null : observedTeam.getCity();
        Long cityId = observedCity != null ? observedCity.getId()
                : observedTeamCity == null ? null : observedTeamCity.getId();
        Long teamId = observedTeam == null ? null : observedTeam.getId();
        if (observedCity != null) entityManager.detach(observedCity);
        if (observedTeamCity != null) entityManager.detach(observedTeamCity);
        if (observedTeam != null) entityManager.detach(observedTeam);
        entityManager.detach(observed);
        securityGuards.acquire(cityId == null ? List.of() : List.of(cityId),
                teamId == null ? List.of() : List.of(teamId), List.of(actor.getId(), targetUserId));
        actor = access.requireGlobalAdmin(actorUserId);
        User target = lockUser(targetUserId);
        employeeProfiles.findLockedByUserId(target.getId());
        StaffAccessGrant grant = grants.findLockedById(grantId)
                .orElseThrow(() -> new EntityNotFoundException("Staff grant not found"));
        if (grant.getRevokedAt() != null) return toGrantView(grant);

        Instant now = databaseClock.now();
        if (grant.getCapability() == StaffCapability.STAFF_ADMIN
                && grant.getScopeType() == StaffScopeType.GLOBAL
                && grant.getEffectiveAt() != null && !grant.getEffectiveAt().isAfter(now)
                && (grant.getExpiresAt() == null || now.isBefore(grant.getExpiresAt()))
                && employeeProfiles.existsByUserIdAndStaffActiveTrue(grant.getUser().getId())
                && grants.countEffectiveGlobalAdminsExcluding(grant.getUser().getId()) == 0) {
            throw new StaffAccessConflictException("The final effective global Admin grant cannot be revoked");
        }

        grant.revoke(actor, now, reasonCode);
        audit.recordUserEvent(actor, "STAFF_ACCESS_GRANT_REVOKED", "STAFF_ACCESS_GRANT", grant.getId(),
                reasonCode, grant.getCity(), grant.getTeam(), grantDetails(grant));
        entityManager.flush();
        return toGrantView(grant);
    }

    private void detachGrantScope(StaffAccessGrant grant) {
        SupportedCity city = grant.getCity();
        OperatingTeam team = grant.getTeam();
        SupportedCity teamCity = team == null ? null : team.getCity();
        if (city != null) entityManager.detach(city);
        if (teamCity != null) entityManager.detach(teamCity);
        if (team != null) entityManager.detach(team);
        entityManager.detach(grant);
    }

    /**
     * Serialize authority changes with operational mutations before taking a User row lock.
     * Ownership writes hold City/Team and employee/grant guards before their coordinator FK
     * check takes a compatible lock on users, so staff writers must enter through those same
     * guards before acquiring a conflicting User FOR UPDATE lock.
     */
    private List<StaffAccessGrant> acquireUserScopeGuards(Long actorUserId, Long targetUserId) {
        if (targetUserId == null || targetUserId <= 0)
            throw new IllegalArgumentException("User ID must be a positive stable ID");
        List<StaffAccessGrant> observedGrants = grants.findAllByUserIdOrderByCreatedAtDesc(targetUserId);
        List<Long> cityIds = observedGrants.stream()
                .flatMap(grant -> java.util.stream.Stream.of(grant.getCity(),
                        grant.getTeam() == null ? null : grant.getTeam().getCity()))
                .filter(java.util.Objects::nonNull).map(SupportedCity::getId).distinct().toList();
        List<Long> teamIds = observedGrants.stream().map(StaffAccessGrant::getTeam).filter(java.util.Objects::nonNull)
                .map(OperatingTeam::getId).distinct().toList();
        observedGrants.forEach(this::detachGrantScope);
        securityGuards.acquire(cityIds, teamIds, List.of(actorUserId, targetUserId));
        return observedGrants;
    }

    private User loadUser(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("User ID must be a positive stable ID");
        return users.findById(userId).orElseThrow(() -> new EntityNotFoundException("User not found"));
    }

    private User lockUser(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("User ID must be a positive stable ID");
        return users.findLockedById(userId).orElseThrow(() -> new EntityNotFoundException("User not found"));
    }

    private static void validateScopeShape(StaffGrantCommand command) {
        boolean shapeValid = switch (command.scopeType()) {
            case GLOBAL -> command.cityId() == null && command.teamId() == null;
            case CITY -> command.cityId() != null && command.teamId() == null;
            case TEAM -> command.cityId() == null && command.teamId() != null;
        };
        boolean capabilityValid = switch (command.capability()) {
            case STAFF_ADMIN -> command.scopeType() == StaffScopeType.GLOBAL;
            case CITY_TEAM_ADMIN, OPS_INTAKE -> command.scopeType() == StaffScopeType.CITY;
            case OPS_COORDINATE, OPS_SUPERVISE -> command.scopeType() == StaffScopeType.TEAM;
        };
        if (!shapeValid || !capabilityValid) {
            throw new IllegalArgumentException("Capability and scope type are incompatible");
        }
    }

    private static void requireExpectedVersion(EmployeeProfile profile, Long expectedVersion) {
        if (expectedVersion == null || !expectedVersion.equals(profile.getVersion())) throw staleProfile();
    }

    private static StaffAccessConflictException staleProfile() {
        return new StaffAccessConflictException("Staff profile version is stale; reload the current state");
    }

    private static void validateReason(String reasonCode) {
        if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("A bounded uppercase reason code is required");
        }
    }

    private static Map<String, Object> grantDetails(StaffAccessGrant grant) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("capability", grant.getCapability().name());
        details.put("scopeType", grant.getScopeType().name());
        if (grant.getCity() != null) details.put("cityId", grant.getCity().getId());
        if (grant.getTeam() != null) details.put("teamId", grant.getTeam().getId());
        details.put("provisioningSource", grant.getProvisioningSource().name());
        return Map.copyOf(details);
    }

    private static StaffGrantView toGrantView(StaffAccessGrant grant) {
        Long cityId = null;
        String cityName = null;
        Long teamId = null;
        String teamName = null;
        if (grant.getScopeType() == StaffScopeType.CITY) {
            cityId = grant.getCity().getId();
            cityName = grant.getCity().getDisplayName();
        } else if (grant.getScopeType() == StaffScopeType.TEAM) {
            teamId = grant.getTeam().getId();
            teamName = grant.getTeam().getDisplayName();
            cityId = grant.getTeam().getCity().getId();
            cityName = grant.getTeam().getCity().getDisplayName();
        }
        return new StaffGrantView(grant.getId(), grant.getCapability(), grant.getScopeType(), cityId, cityName,
                teamId, teamName, grant.getEffectiveAt(), grant.getExpiresAt(), grant.getRevokedAt(),
                grant.getCreatedAt(), grant.getProvisioningSource());
    }

    private static StaffUserAccessState toUserState(User user, EmployeeProfile profile, List<StaffGrantView> grants) {
        return new StaffUserAccessState(user.getId(), profile != null,
                profile != null && profile.isStaffActive(), profile == null ? null : profile.getVersion(),
                profile == null ? null : profile.getStaffActivatedAt(),
                profile == null ? null : profile.getStaffDeactivatedAt(), grants);
    }
}
