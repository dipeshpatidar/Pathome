package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class VisitOperationsAuthorizationService {
    private static final String GROUND_PROFILE = "GROUND_BOY";

    private final UserRepository users;
    private final EmployeeProfileRepository employees;
    private final StaffAccessGrantRepository grants;

    public VisitOperationsAuthorizationService(UserRepository users, EmployeeProfileRepository employees,
                                               StaffAccessGrantRepository grants) {
        this.users = users;
        this.employees = employees;
        this.grants = grants;
    }

    public void requireScopedOperationalRead(Long authenticatedUserId) {
        User user = loadAuthenticatedUser(authenticatedUserId);
        if (!employees.existsByUserIdAndStaffActiveTrue(user.getId())
                || grants.findEffectiveForUser(user.getId()).isEmpty()) {
            throw new AccessDeniedException("Current scoped staff authority is required for operational reads");
        }
    }

    public User requireOperations(Long authenticatedUserId) {
        User user = loadAuthenticatedUser(authenticatedUserId);
        if (user.getRole() == Role.ROLE_ADMIN) return user;
        throw new AccessDeniedException("Pathome Admin authority is required for operations access");
    }

    public User requireGroundExecutive(Long authenticatedUserId) {
        User user = loadAuthenticatedUser(authenticatedUserId);
        EmployeeProfile profile = employees.findByUserId(user.getId()).orElse(null);
        if (user.getRole() != Role.ROLE_GROUND_BOY || profile == null
                || !GROUND_PROFILE.equalsIgnoreCase(profile.getRoleType())) {
            throw new AccessDeniedException("Ground Executive capability required");
        }
        return user;
    }

    public User requireGroundExecutiveTarget(Long userId) {
        User target = users.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Ground Executive not found"));
        EmployeeProfile profile = employees.findByUserId(target.getId()).orElse(null);
        if (target.getRole() != Role.ROLE_GROUND_BOY || profile == null
                || !GROUND_PROFILE.equalsIgnoreCase(profile.getRoleType())) {
            throw new IllegalArgumentException("Target user is not an eligible Ground Executive");
        }
        return target;
    }

    private User loadAuthenticatedUser(Long userId) {
        if (userId == null || userId <= 0) throw new AccessDeniedException("Authenticated user identity required");
        return users.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated user is unavailable"));
    }
}
