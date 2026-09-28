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

    public LandlordCapabilityService(UserRepository users) {
        this(users, null);
    }

    public LandlordCapabilityService(UserRepository users, LessorProfileRepository lessorProfiles) {
        this.users = users;
        this.lessorProfiles = lessorProfiles;
    }

    @Transactional(readOnly = true)
    public Capability getCapability(String authenticatedEmail) {
        User user = requireEligibleUser(authenticatedEmail);
        return toCapability(user);
    }

    @Transactional
    public Capability activate(String authenticatedEmail) {
        User user = requireEligibleUser(authenticatedEmail);
        if (user.getLandlordActivatedAt() == null) {
            users.activateLandlordCapability(user.getId(), LocalDateTime.now());
            user = users.findById(user.getId()).orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        }
        return toCapability(user);
    }

    @Transactional(readOnly = true)
    public Long requireLandlordUserId(String authenticatedEmail) {
        User user = requireEligibleUser(authenticatedEmail);
        if (user.getLandlordActivatedAt() == null && user.getRole() != Role.ROLE_LANDLORD) {
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
        boolean hasProfile = (lessorProfiles != null && lessorProfiles.findByLinkedUserId(user.getId()).isPresent())
                || user.getRole() == Role.ROLE_LANDLORD;
        boolean enabled = user.getLandlordActivatedAt() != null || user.getRole() == Role.ROLE_LANDLORD || hasProfile;
        return new Capability(
                user.getId(),
                enabled,
                hasProfile,
                user.getLandlordActivatedAt()
        );
    }

    public record Capability(Long userId, boolean enabled, boolean hasLessorProfile, LocalDateTime activatedAt) {
        public Capability(boolean enabled, LocalDateTime activatedAt) {
            this(null, enabled, enabled, activatedAt);
        }
    }
}
