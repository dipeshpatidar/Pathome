package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.TenantVisitAvailabilityCommand;
import com.indore.pathome.spaces.dto.TenantVisitAvailabilityView;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.service.TenantVisitAvailabilityService;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantVisitAvailabilityControllerIdentityTest {
    private final UserRepository users = mock(UserRepository.class);
    private final TenantVisitAvailabilityService service = mock(TenantVisitAvailabilityService.class);
    private final TenantVisitAvailabilityController controller = new TenantVisitAvailabilityController(users, service);

    @Test
    void usesVerifiedStableUserIdAndChecksDatabaseTenantCapability() {
        User tenant = new User();
        tenant.setId(42L);
        tenant.setRole(Role.ROLE_TENANT);
        when(users.findById(42L)).thenReturn(Optional.of(tenant));
        TenantVisitAvailabilityCommand command = new TenantVisitAvailabilityCommand(2L, null, null, null, null);
        TenantVisitAvailabilityView view = new TenantVisitAvailabilityView(51L, 3L, null, null, null, null);
        when(service.update(42L, 51L, command)).thenReturn(view);

        assertSame(view, controller.update(authentication(42L, "other@example.test"), 51L, command));
        verify(users).findById(42L);
        verify(service).update(42L, 51L, command);
    }

    @Test
    void rejectsNonTenantAndDoesNotAcceptCallerSuppliedOwner() {
        User subAdmin = new User();
        subAdmin.setId(7L);
        subAdmin.setRole(Role.ROLE_SUB_ADMIN);
        when(users.findById(7L)).thenReturn(Optional.of(subAdmin));

        assertThrows(AccessDeniedException.class, () -> controller.update(
                authentication(7L, "admin@example.test"), 51L,
                new TenantVisitAvailabilityCommand(0L, null, null, null, null)));
        verifyNoInteractions(service);
    }

    @Test
    void unavailableDatabaseIdentityIsRejected() {
        when(users.findById(99L)).thenReturn(Optional.empty());
        assertThrows(AccessDeniedException.class, () -> controller.update(
                authentication(99L, "tenant@example.test"), 51L,
                new TenantVisitAvailabilityCommand(0L, null, null, null, null)));
        verifyNoInteractions(service);
    }

    private UsernamePasswordAuthenticationToken authentication(Long userId, String name) {
        var auth = new UsernamePasswordAuthenticationToken(name, null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
        auth.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), userId));
        return auth;
    }
}
