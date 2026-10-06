package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.StaffAccessGrant;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffGrantProvisioningSource;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.StaffAccessConflictException;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.StaffAccessGrantRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class StaffAdminBootstrapService {
    private static final String BOOTSTRAP_REASON = "INITIAL_PROVISIONING";

    private final UserRepository users;
    private final EmployeeProfileRepository employeeProfiles;
    private final StaffAccessGrantRepository grants;
    private final PasswordEncoder passwordEncoder;
    private final StaffGovernanceLock governanceLock;
    private final OperationalAuditService audit;
    private final DatabaseClock databaseClock;
    private final EntityManager entityManager;

    public StaffAdminBootstrapService(UserRepository users,
                                     EmployeeProfileRepository employeeProfiles,
                                     StaffAccessGrantRepository grants,
                                     PasswordEncoder passwordEncoder,
                                     StaffGovernanceLock governanceLock,
                                     OperationalAuditService audit,
                                     DatabaseClock databaseClock,
                                     EntityManager entityManager) {
        this.users = users;
        this.employeeProfiles = employeeProfiles;
        this.grants = grants;
        this.passwordEncoder = passwordEncoder;
        this.governanceLock = governanceLock;
        this.audit = audit;
        this.databaseClock = databaseClock;
        this.entityManager = entityManager;
    }

    @Transactional
    public boolean provisionFirstAdmin(String configuredEmail, String configuredPassword,
                                       String operatorReference) {
        if (configuredEmail == null || configuredEmail.isBlank()) {
            throw new IllegalStateException("Explicit first Admin provisioning requires a configured target email");
        }
        if (operatorReference == null || !operatorReference.matches("[A-Z0-9][A-Z0-9:_-]{0,119}")) {
            throw new IllegalStateException("Explicit first Admin provisioning requires a bounded operator reference");
        }

        governanceLock.acquire();
        if (grants.hasHistoricalGlobalAdminGrant()) return false;

        String targetEmail = configuredEmail.trim();
        User resolvedTarget = users.findByEmail(targetEmail)
                .orElseGet(() -> createConfiguredUser(targetEmail, configuredPassword));
        User target = users.findLockedById(resolvedTarget.getId()).orElseThrow();

        EmployeeProfile profile = employeeProfiles.findLockedByUserId(target.getId()).orElse(null);
        if (profile == null) {
            profile = employeeProfiles.saveAndFlush(new EmployeeProfile(target, null, null, null));
        }

        Instant now = databaseClock.now();
        if (grants.existsOverlappingUnrevoked(target.getId(), StaffCapability.STAFF_ADMIN.name(),
                StaffScopeType.GLOBAL.name(), null, null, now, null)) {
            throw new StaffAccessConflictException("An overlapping grant already exists for this User and scope");
        }

        if (!profile.isStaffActive()) {
            profile.setStaffActive(true);
            profile.setStaffActivatedAt(now);
            entityManager.flush();
            audit.recordDeploymentOperatorEvent(operatorReference, "STAFF_ACTIVATED", "USER", target.getId(),
                    BOOTSTRAP_REASON, null, null, Map.of("staffActive", true));
        }

        StaffAccessGrant initialGrant = new StaffAccessGrant(target, StaffCapability.STAFF_ADMIN,
                StaffScopeType.GLOBAL, null, null, now, null, null, now, BOOTSTRAP_REASON,
                StaffGrantProvisioningSource.INITIAL_BOOTSTRAP);
        StaffAccessGrant savedGrant = grants.saveAndFlush(initialGrant);
        audit.recordDeploymentOperatorEvent(operatorReference, "INITIAL_ADMIN_BOOTSTRAPPED",
                "STAFF_ACCESS_GRANT", savedGrant.getId(), BOOTSTRAP_REASON, null, null,
                Map.of("capability", StaffCapability.STAFF_ADMIN.name(),
                        "scopeType", StaffScopeType.GLOBAL.name(),
                        "provisioningSource", StaffGrantProvisioningSource.INITIAL_BOOTSTRAP.name()));
        return true;
    }

    private User createConfiguredUser(String email, String configuredPassword) {
        if (configuredPassword == null || configuredPassword.isBlank()) {
            throw new IllegalStateException("A password is required only when first provisioning creates a new User");
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(configuredPassword));
        user.setRole(Role.ROLE_TENANT);
        user.setFreeVisitsRemaining(0);
        return users.saveAndFlush(user);
    }
}
