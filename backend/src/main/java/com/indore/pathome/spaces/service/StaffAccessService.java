package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.StaffCapabilitiesResponse;
import com.indore.pathome.spaces.dto.StaffCapabilitySummary;
import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StaffAccessService {
    private final UserRepository users;
    private final EmployeeProfileRepository employeeProfiles;
    private final StaffAccessGrantRepository grants;
    private final DatabaseClock databaseClock;

    public StaffAccessService(UserRepository users, EmployeeProfileRepository employeeProfiles,
                              StaffAccessGrantRepository grants, DatabaseClock databaseClock) {
        this.users = users;
        this.employeeProfiles = employeeProfiles;
        this.grants = grants;
        this.databaseClock = databaseClock;
    }

    @Transactional(readOnly = true)
    public StaffCapabilitiesResponse currentCapabilities(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new AccessDeniedException("Authenticated User identity required");
        }
        boolean active = employeeProfiles.existsByUserIdAndStaffActiveTrue(authenticatedUserId);
        List<StaffCapabilitySummary> capabilities = active
                ? grants.findEffectiveForUser(authenticatedUserId).stream().map(StaffAccessService::toSummary).toList()
                : List.of();
        return new StaffCapabilitiesResponse(active, capabilities, databaseClock.now());
    }

    @Transactional(readOnly = true)
    public List<StaffAccessGrant> effectiveGrants(Long userId) {
        if (userId == null || userId <= 0) return List.of();
        return grants.findEffectiveForUser(userId);
    }

    @Transactional(readOnly = true)
    public boolean hasCapabilityAt(Long userId, StaffCapability capability, StaffScopeType scopeType, Long scopeId) {
        if (userId == null || capability == null || scopeType == null
                || (scopeType != StaffScopeType.GLOBAL && scopeId == null)
                || !employeeProfiles.existsByUserIdAndStaffActiveTrue(userId)) return false;
        return grants.findEffectiveForUser(userId).stream().anyMatch(grant -> {
            if (grant.getCapability() != capability) return false;
            if (grant.getScopeType() == StaffScopeType.GLOBAL) return true;
            return switch (scopeType) {
                case GLOBAL -> false;
                case CITY -> grant.getScopeType() == StaffScopeType.CITY
                        && grant.getCity() != null && scopeId.equals(grant.getCity().getId());
                case TEAM -> grant.getScopeType() == StaffScopeType.TEAM
                        && grant.getTeam() != null && scopeId.equals(grant.getTeam().getId());
            };
        });
    }

    @Transactional(readOnly = true)
    public User requireGlobalAdmin(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new AccessDeniedException("Authenticated User identity required");
        }
        User user = users.findById(authenticatedUserId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated User is unavailable"));
        if (!employeeProfiles.existsByUserIdAndStaffActiveTrue(user.getId())
                || !grants.hasEffectiveGlobalAdmin(user.getId())) {
            throw new AccessDeniedException("Current global Staff Admin authority is required");
        }
        return user;
    }

    @Transactional(readOnly = true)
    public EmployeeProfile requireActiveStaff(Long userId) {
        return employeeProfiles.findByUserId(userId)
                .filter(EmployeeProfile::isStaffActive)
                .orElseThrow(() -> new AccessDeniedException("Active staff profile is required"));
    }

    private static StaffCapabilitySummary toSummary(StaffAccessGrant grant) {
        return switch (grant.getScopeType()) {
            case GLOBAL -> new StaffCapabilitySummary(grant.getCapability(), StaffScopeType.GLOBAL,
                    null, null, null, null);
            case CITY -> new StaffCapabilitySummary(grant.getCapability(), StaffScopeType.CITY,
                    grant.getCity().getId(), grant.getCity().getDisplayName(), null, null);
            case TEAM -> new StaffCapabilitySummary(grant.getCapability(), StaffScopeType.TEAM,
                    grant.getTeam().getCity().getId(), grant.getTeam().getCity().getDisplayName(),
                    grant.getTeam().getId(), grant.getTeam().getDisplayName());
        };
    }
}
