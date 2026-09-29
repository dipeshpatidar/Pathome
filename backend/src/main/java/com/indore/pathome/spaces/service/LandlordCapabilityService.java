package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class LandlordCapabilityService {
    private final UserRepository users;
    private final LessorProfileRepository lessorProfiles;

    public LandlordCapabilityService(UserRepository users, LessorProfileRepository lessorProfiles) {
        this.users = users;
        this.lessorProfiles = lessorProfiles;
    }

    @Transactional(readOnly = true)
    public Capability getCapability(String authenticatedEmail) {
        User user = requireEligibleUser(authenticatedEmail);
        return toCapability(user);
    }

    /** Kept for older clients; opening onboarding never grants capability. */
    @Transactional(readOnly = true)
    public Capability activate(String authenticatedEmail) {
        return getCapability(authenticatedEmail);
    }

    @Transactional
    void activateAfterSubmission(String authenticatedEmail) {
        User user = requireEligibleUser(authenticatedEmail);
        if (!lessorProfiles.existsByLinkedUserId(user.getId())) {
            throw new IllegalStateException("Submitted listing requires a linked lessor profile");
        }
        if (user.getLandlordActivatedAt() == null) {
            users.activateLandlordCapability(user.getId(), LocalDateTime.now());
        }
    }

    @Transactional(readOnly = true)
    public Long requireOnboardingUserId(String authenticatedEmail) {
        return requireEligibleUser(authenticatedEmail).getId();
    }

    @Transactional(readOnly = true)
    public Long requireLandlordUserId(String authenticatedEmail) {
        User user = requireEligibleUser(authenticatedEmail);
        if (user.getLandlordActivatedAt() == null || !lessorProfiles.existsByLinkedUserId(user.getId())) {
            throw new AccessDeniedException("Landlord capability required");
        }
        return user.getId();
    }

    private User requireEligibleUser(String authenticatedEmail) {
        if (authenticatedEmail == null || authenticatedEmail.isBlank()) {
            throw new AccessDeniedException("Authenticated account required");
        }
        User user = users.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        if (user.getRole() != Role.ROLE_TENANT && user.getRole() != Role.ROLE_LANDLORD) {
            throw new AccessDeniedException("Account is not eligible for landlord self-service");
        }
        return user;
    }

    private Capability toCapability(User user) {
        boolean hasProfile = lessorProfiles.existsByLinkedUserId(user.getId());
        boolean enabled = hasProfile && user.getLandlordActivatedAt() != null;
        return new Capability(
                user.getId(),
                enabled,
                hasProfile,
                user.getLandlordActivatedAt()
        );
    }

    public record Capability(Long userId, boolean enabled, boolean hasLessorProfile, LocalDateTime activatedAt) {}
}
