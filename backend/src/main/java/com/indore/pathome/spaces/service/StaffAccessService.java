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

    /**
     * Holds the live facts a global Staff Admin decision is made from - the actor's staff profile row and
     * every effective global {@code STAFF_ADMIN} grant row - for the rest of the calling transaction.
     *
     * <p>The rows are taken {@code FOR SHARE} in the established governance order (actor profile before
     * actor grants, exactly as {@code OperationalSecurityGuards} acquires its guards), and they must be
     * taken <em>before</em> any lock that comes later in that hierarchy, so a caller that respects the
     * order cannot invert it against the governance or operational-routing paths and deadlock.
     *
     * <p>{@code FOR SHARE} is what couples this decision's commit with a revocation's commit without
     * freezing the actor: a revocation or deactivation takes {@code FOR UPDATE} on these same rows and
     * therefore cannot commit while the decision holds them, whereas another review by the same actor -
     * which also only takes share locks - is never blocked.
     *
     * <p>The locks are released by PostgreSQL when this transaction commits or rolls back, so they
     * remain held across the guarded mutation they protect.
     *
     * <p>An actor with no staff profile row holds nothing on that row; the decision itself still refuses
     * them, because {@link #requireGlobalAdmin(Long)} re-reads the same facts.
     */
    @Transactional
    public void holdGlobalAdminEvidence(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new AccessDeniedException("Authenticated User identity required");
        }
        employeeProfiles.findSharedByUserId(authenticatedUserId);
        grants.findEffectiveGlobalAdminSharedByUserId(authenticatedUserId);
    }

    /**
     * The real persisted {@code User} that a verified stable identity attributes an audited action to.
     *
     * <p>This never invents an actor: the identity comes from the authenticated credential, and it is
     * resolved against {@code users} in the calling transaction, so an action whose actor cannot be
     * resolved is refused rather than recorded against a fabricated or borrowed identity.
     */
    @Transactional(readOnly = true)
    public User requireAttributableUser(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new AccessDeniedException("An attributable staff or administrator identity is required");
        }
        return users.findById(authenticatedUserId)
                .orElseThrow(() -> new AccessDeniedException("An attributable staff or administrator identity is required"));
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
