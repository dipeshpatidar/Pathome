package com.indore.pathome.spaces.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilsTest {

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", "PathomeSpacesSuperSecretKeyForJWTAuthTokenGeneration2026!");
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", 3600000L);
        jwtUtils.validateConfiguration();
    }

    @Test
    void testGenerateAndValidateToken() {
        String token = jwtUtils.generateToken(101L, "tenant@pathome.in", "ROLE_TENANT");

        assertNotNull(token);
        assertTrue(jwtUtils.validateToken(token));

        assertEquals("tenant@pathome.in", jwtUtils.getEmailFromToken(token));
        assertEquals(101L, jwtUtils.getUserIdFromToken(token));
        assertEquals("ROLE_TENANT", jwtUtils.getRoleFromToken(token));
    }

    @Test
    void testInvalidTokenValidation() {
        assertFalse(jwtUtils.validateToken("invalid.jwt.token"));
    }

    @Test
    void rejectsWeakSigningSecretAtStartup() {
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", "too-short");

        IllegalStateException error = assertThrows(
                IllegalStateException.class, jwtUtils::validateConfiguration);

        assertTrue(error.getMessage().contains("at least 32 bytes"));
    }
}
