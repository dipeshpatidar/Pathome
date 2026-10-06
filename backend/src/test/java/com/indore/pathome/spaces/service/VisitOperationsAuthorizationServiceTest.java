package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.EmployeeProfile;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisitOperationsAuthorizationServiceTest {
    private UserRepository users;
    private EmployeeProfileRepository employees;
    private VisitOperationsAuthorizationService authorization;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        employees = mock(EmployeeProfileRepository.class);
        authorization = new VisitOperationsAuthorizationService(users, employees);
    }

    @Test
    void onlyAdminCanUseSensitiveOperationsUntilScopedStaffAuthorizationExists() {
        User admin = user(1L, Role.ROLE_ADMIN);
        User tenant = user(2L, Role.ROLE_TENANT);
        User subAdmin = user(3L, Role.ROLE_SUB_ADMIN);
        User operations = user(4L, Role.ROLE_TENANT);
        User lessor = user(5L, Role.ROLE_LANDLORD);
        User groundExecutive = user(6L, Role.ROLE_GROUND_BOY);
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(users.findById(2L)).thenReturn(Optional.of(tenant));
        when(users.findById(3L)).thenReturn(Optional.of(subAdmin));
        when(users.findById(4L)).thenReturn(Optional.of(operations));
        when(users.findById(5L)).thenReturn(Optional.of(lessor));
        when(users.findById(6L)).thenReturn(Optional.of(groundExecutive));
        when(employees.findByUserId(4L)).thenReturn(Optional.of(profile("WFH_ADMIN")));

        assertSame(admin, authorization.requireOperations(1L));
        assertThrows(AccessDeniedException.class, () -> authorization.requireOperations(2L));
        assertThrows(AccessDeniedException.class, () -> authorization.requireOperations(3L));
        assertThrows(AccessDeniedException.class, () -> authorization.requireOperations(4L));
        assertThrows(AccessDeniedException.class, () -> authorization.requireOperations(5L));
        assertThrows(AccessDeniedException.class, () -> authorization.requireOperations(6L));
        verify(employees, never()).findByUserId(4L);
    }

    @Test
    void groundExecutiveCapabilityMustMatchBothCurrentRoleAndEmployeeProfile() {
        User ground = user(10L, Role.ROLE_GROUND_BOY);
        User tenant = user(11L, Role.ROLE_TENANT);
        when(users.findById(10L)).thenReturn(Optional.of(ground));
        when(users.findById(11L)).thenReturn(Optional.of(tenant));
        when(employees.findByUserId(10L)).thenReturn(Optional.of(profile("GROUND_BOY")));

        assertSame(ground, authorization.requireGroundExecutive(10L));
        assertThrows(AccessDeniedException.class, () -> authorization.requireOperations(10L));
        assertThrows(AccessDeniedException.class, () -> authorization.requireGroundExecutive(11L));
        assertThrows(IllegalArgumentException.class, () -> authorization.requireGroundExecutiveTarget(11L));
    }

    private static EmployeeProfile profile(String type) {
        EmployeeProfile profile = new EmployeeProfile();
        profile.setRoleType(type);
        return profile;
    }

    private static User user(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }
}
