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
    void devEnvironmentAllowsLocalAndLanOrigins() {
        var guard = new GuestRequestGuard(new MockEnvironment(), "");
        for (String origin : java.util.List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "http://dipeshs-macbook-air.local:5173",
                "http://phone.local:5173",
                "http://192.168.1.50:5173",
                "http://10.0.0.12:5173",
                "http://172.20.10.4:5173"
        )) {
            var request = new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts");
            request.addHeader("Origin", origin);
            assertDoesNotThrow(() -> guard.check(request), "Dev should allow origin: " + origin);
        }
    }

    @Test
    void devEnvironmentRejectsArbitraryInternetOrigins() {
        var guard = new GuestRequestGuard(new MockEnvironment(), "");
        for (String origin : java.util.List.of(
                "https://attacker.example",
                "https://evil.com",
                "http://evil.local.com",
                "http://sub.evil.com"
        )) {
            var request = new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts");
            request.addHeader("Origin", origin);
            assertThrows(AccessDeniedException.class, () -> guard.check(request),
                    "Dev should reject untrusted origin: " + origin);
        }
    }

    @Test
    void productionRejectsLanOriginsUnlessExplicitlyConfigured() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        var guard = new GuestRequestGuard(env, "https://pathome.com,https://app.pathome.com");

        var lanRequest = new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts");
        lanRequest.addHeader("Origin", "http://dipeshs-macbook-air.local:5173");
        assertThrows(AccessDeniedException.class, () -> guard.check(lanRequest),
                "Production must reject LAN origin not in configured list");

        var prodRequest = new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts");
        prodRequest.addHeader("Origin", "https://pathome.com");
        assertDoesNotThrow(() -> guard.check(prodRequest),
                "Production must allow configured origin");
    }

    @Test
    void productionRequiresExplicitOriginAndSecureCookie() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new GuestRequestGuard(env, ""));
        var guard = new GuestRequestGuard(env, "https://pathome.example");
        assertTrue(guard.secureCookie(new MockHttpServletRequest()));
    }
}
