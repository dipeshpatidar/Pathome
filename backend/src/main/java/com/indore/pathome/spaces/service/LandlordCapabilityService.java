package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class LandlordCapabilityService {
    private final UserRepository users;

    public LandlordCapabilityService(UserRepository users) {
        this.users = users;
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
        return new Capability(user.getLandlordActivatedAt() != null || user.getRole() == Role.ROLE_LANDLORD,
                user.getLandlordActivatedAt());
    }

    public record Capability(boolean enabled, LocalDateTime activatedAt) {}
}
