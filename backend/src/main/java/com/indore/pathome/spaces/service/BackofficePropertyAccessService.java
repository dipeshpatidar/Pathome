package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Action-specific admission for the three fixed CITY property duties. Existing role labels and
 * {@code STAFF_ADMIN@GLOBAL} are deliberately not consulted.
 */
@Service
public class BackofficePropertyAccessService {
    private final UserRepository users;
    private final EmployeeProfileRepository profiles;
    private final StaffAccessGrantRepository grants;

    public BackofficePropertyAccessService(UserRepository users, EmployeeProfileRepository profiles,
                                           StaffAccessGrantRepository grants) {
        this.users = users;
        this.profiles = profiles;
        this.grants = grants;
    }

    @Transactional(readOnly = true)
    public List<Long> inventoryCityIds(Long userId, StaffCapability capability) {
        requireUser(userId);
        if (!profiles.existsByUserIdAndStaffActiveTrue(userId)) return List.of();
        return grants.findEffectiveForUser(userId).stream()
                .filter(grant -> grant.getCapability() == capability)
                .filter(grant -> grant.getScopeType() == com.indore.pathome.spaces.entity.StaffScopeType.CITY)
                .map(grant -> grant.getCity() == null ? null : grant.getCity().getId())
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    /**
     * Holds the current City grant share lock and active profile decision before a following Listing
     * root lock. Callers revalidate after the Listing lock with {@link #revalidate}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public GrantEvidence requireForMutation(Long userId, StaffCapability capability, Long cityId) {
        requireUser(userId);
        if (cityId == null || cityId <= 0) {
            throw new AccessDeniedException("Current property City authority is required");
        }
        if (!profiles.existsByUserIdAndStaffActiveTrue(userId)) {
            throw new AccessDeniedException("Current property City authority is required");
        }
        List<StaffAccessGrant> held = grants.findEffectiveCityCapabilityShared(
                userId, capability.name(), cityId);
        if (held.isEmpty()) throw new AccessDeniedException("Current property City authority is required");
        return new GrantEvidence(userId, cityId, capability.name(), held.get(0).getId(),
                held.stream().map(StaffAccessGrant::getId).toList());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public GrantEvidence revalidate(Long userId, StaffCapability capability, Long cityId) {
        return requireForMutation(userId, capability, cityId);
    }

    @Transactional(readOnly = true)
    public void requireRead(Long userId, StaffCapability capability, Long cityId) {
        requireUser(userId);
        if (cityId == null || cityId <= 0
                || !profiles.existsByUserIdAndStaffActiveTrue(userId)
                || grants.findEffectiveForUser(userId).stream().noneMatch(grant ->
                    grant.getCapability() == capability
                            && grant.getScopeType() == com.indore.pathome.spaces.entity.StaffScopeType.CITY
                            && grant.getCity() != null && cityId.equals(grant.getCity().getId()))) {
            throw new AccessDeniedException("Current property City authority is required");
        }
    }

    public record GrantEvidence(Long userId, Long cityId, String capability, Long firstGrantId,
                                List<Long> heldGrantIds) {}

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0 || users.findById(userId).isEmpty()) {
            throw new AccessDeniedException("Authenticated User identity required");
        }
    }
}

