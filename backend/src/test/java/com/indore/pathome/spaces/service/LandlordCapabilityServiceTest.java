package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LandlordCapabilityServiceTest {
    private UserRepository users;
    private LessorProfileRepository profileRepo;
    private LandlordCapabilityService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profileRepo = mock(LessorProfileRepository.class);
        service = new LandlordCapabilityService(users, profileRepo);
    }

    @Test
    void openingOnboardingDoesNotActivateTenant() {
        User tenant = user(17L, Role.ROLE_TENANT);
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(tenant));

        var result = service.activate("owner@example.com");

        assertFalse(result.enabled());
        assertNull(result.activatedAt());
        assertEquals(Role.ROLE_TENANT, tenant.getRole());
        assertEquals(17L, service.requireOnboardingUserId("owner@example.com"));
        verify(users, never()).activateLandlordCapability(any(), any());
    }

    @Test
    void repeatActivationDoesNotRewriteAuditTime() {
        User tenant = user(17L, Role.ROLE_TENANT);
        tenant.setLandlordActivatedAt(LocalDateTime.of(2026, 9, 28, 10, 0));
        tenant.setLandlordActivatedByUserId(17L);
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(tenant));

        when(profileRepo.existsByLinkedUserId(17L)).thenReturn(true);
        service.activateAfterSubmission("owner@example.com");
        var result = service.getCapability("owner@example.com");

        assertTrue(result.enabled());
        assertEquals(tenant.getLandlordActivatedAt(), result.activatedAt());
        verify(users, never()).activateLandlordCapability(any(), any());
    }

    @Test
    void tenantWithoutActivationCannotUseLandlordOperations() {
        when(users.findByEmail("tenant@example.com")).thenReturn(Optional.of(user(21L, Role.ROLE_TENANT)));
        assertThrows(AccessDeniedException.class, () -> service.requireLandlordUserId("tenant@example.com"));
    }

    @Test
    void administrativeRoleCannotSelfActivate() {
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user(8L, Role.ROLE_ADMIN)));
        assertThrows(AccessDeniedException.class, () -> service.activate("admin@example.com"));
        verify(users, never()).activateLandlordCapability(any(), any());
    }

    @Test
    void tenantWithoutLessorProfileReturnsFalseForHasLessorProfile() {
        User tenant = user(22L, Role.ROLE_TENANT);
        when(users.findByEmail("tenant@example.com")).thenReturn(Optional.of(tenant));
        when(profileRepo.existsByLinkedUserId(22L)).thenReturn(false);

        var capability = service.getCapability("tenant@example.com");
        assertEquals(22L, capability.userId());
        assertFalse(capability.hasLessorProfile());
        assertFalse(capability.enabled());
    }

    @Test
    void linkedProfileWithoutActivationRemainsInactive() {
        User tenant = user(23L, Role.ROLE_TENANT);
        when(users.findByEmail("lessor@example.com")).thenReturn(Optional.of(tenant));
        when(profileRepo.existsByLinkedUserId(23L)).thenReturn(true);

        var capability = service.getCapability("lessor@example.com");
        assertEquals(23L, capability.userId());
        assertTrue(capability.hasLessorProfile());
        assertFalse(capability.enabled());
        assertThrows(AccessDeniedException.class, () -> service.requireLandlordUserId("lessor@example.com"));
        tenant.setLandlordActivatedAt(LocalDateTime.now());
        assertTrue(service.getCapability("lessor@example.com").enabled());
        assertEquals(23L, service.requireLandlordUserId("lessor@example.com"));
    }

    @Test
    void landlordRoleWithoutProfileDoesNotFabricateLessorCapability() {
        User landlord = user(24L, Role.ROLE_LANDLORD);
        when(users.findByEmail("landlord@example.com")).thenReturn(Optional.of(landlord));

        var capability = service.getCapability("landlord@example.com");
        assertEquals(24L, capability.userId());
        assertFalse(capability.hasLessorProfile());
        assertFalse(capability.enabled());
        assertThrows(AccessDeniedException.class, () -> service.requireLandlordUserId("landlord@example.com"));
    }

    @Test
    void timestampWithoutProfileDoesNotGrantMyProperties() {
        User tenant = user(25L, Role.ROLE_TENANT);
        tenant.setLandlordActivatedAt(LocalDateTime.now());
        when(users.findByEmail("tenant@example.com")).thenReturn(Optional.of(tenant));
        assertFalse(service.getCapability("tenant@example.com").enabled());
        assertThrows(AccessDeniedException.class, () -> service.requireLandlordUserId("tenant@example.com"));
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }
}
