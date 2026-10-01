package com.indore.pathome.spaces.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAuthenticationFilterTest {
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", "PathomeSpacesSuperSecretKeyForJWTAuthTokenGeneration2026!");
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", 3600000L);
        jwtUtils.validateConfiguration();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void verifiedTokenProvidesStableUserIdWithoutChangingAuthenticationName() throws Exception {
        String token = jwtUtils.generateToken(101L, "tenant@example.com", "ROLE_TENANT");
        assertTrue(jwtUtils.validateToken(token));
        assertEquals(101L, jwtUtils.getUserIdFromToken(token));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Authorization", "Bearer " + token);

        new JwtAuthenticationFilter(jwtUtils).doFilterInternal(
                request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {});

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("tenant@example.com", authentication.getName());
        assertEquals("ROLE_TENANT", authentication.getAuthorities().iterator().next().getAuthority());
        PathomeAuthenticationDetails details = assertInstanceOf(
                PathomeAuthenticationDetails.class, authentication.getDetails());
        assertEquals(101L, details.getUserId());
    }
}
