package com.indore.pathome.spaces.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;

class GuestRequestGuardTest {
    @Test
    void rejectsCrossOriginAndOriginlessMutations() {
        var guard = new GuestRequestGuard(new MockEnvironment(), "https://pathome.example");
        var allowed = new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts");
        allowed.addHeader("Origin", "https://pathome.example");
        assertDoesNotThrow(() -> guard.check(allowed));
        var attacker = new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts");
        attacker.addHeader("Origin", "https://attacker.example");
        assertThrows(AccessDeniedException.class, () -> guard.check(attacker));
        assertThrows(AccessDeniedException.class,
                () -> guard.check(new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts")));
    }

    @Test
    void productionRequiresExplicitOriginAndSecureCookie() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new GuestRequestGuard(env, ""));
        var guard = new GuestRequestGuard(env, "https://pathome.example");
        assertTrue(guard.secureCookie(new MockHttpServletRequest()));
    }
}
