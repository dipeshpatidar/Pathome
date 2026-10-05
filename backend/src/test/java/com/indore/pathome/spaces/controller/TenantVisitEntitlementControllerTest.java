package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.TenantVisitEntitlementView;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.service.TenantVisitEntitlementService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantVisitEntitlementControllerTest {
    @Test
    void endpointReadsOnlyAuthenticatedIdentity() {
        TenantVisitEntitlementService service = mock(TenantVisitEntitlementService.class);
        TenantVisitEntitlementController controller = new TenantVisitEntitlementController(service);
        UsernamePasswordAuthenticationToken tenant = new UsernamePasswordAuthenticationToken(
                "tenant@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
        tenant.setDetails(new PathomeAuthenticationDetails(mock(HttpServletRequest.class), 41L));
        TenantVisitEntitlementView own = new TenantVisitEntitlementView(5, 5, 0, 5, false);
        when(service.getMine(41L)).thenReturn(own);
        assertEquals(own, controller.getMine(tenant));
        verify(service).getMine(41L);
        verify(service, never()).getMine(42L);
        assertThrows(AccessDeniedException.class, () -> controller.getMine(null));
    }
}
