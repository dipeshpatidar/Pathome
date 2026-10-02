package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VisitOperationsControllerIdentityTest {
    @Test
    void operationsIdentityComesFromVerifiedAuthenticationDetails() {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "tenant-b@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
        authentication.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), 42L));

        assertEquals(42L, VisitOperationsController.authenticatedUserId(authentication));
    }

    @Test
    void missingAuthenticatedIdentityFailsClosed() {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "tenant@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> VisitOperationsController.authenticatedUserId(authentication));
    }
}
