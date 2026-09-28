package com.indore.pathome.spaces.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;

class GuestRequestGuardTest {

    @Test
    void productionAllowsConfiguredOriginAndRejectsMutation() {
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

    // ── DEV: accepted LAN origins ─────────────────────────────────────────────────────────────
    @ParameterizedTest(name = "dev accepts [{0}]")
    @ValueSource(strings = {
            "http://localhost:5173",
            "https://localhost:5173",
            "http://127.0.0.1:5173",
            "http://127.0.0.2:8080",
            "http://dipeshs-macbook-air.local:5173",
            "http://phone.local:5173",
            "http://192.168.1.50:5173",
            "http://192.168.0.1:8080",
            "http://10.0.0.12:5173",
            "http://10.255.255.1:5173",
            "http://172.16.0.1:5173",       // first address in 172.16.0.0/12
            "http://172.20.10.4:5173",
            "http://172.31.255.255:5173"    // last address in 172.16.0.0/12
    })
    void devAcceptsLanAndLoopbackOrigins(String origin) {
        var guard = new GuestRequestGuard(new MockEnvironment(), "");
        var request = new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts");
        request.addHeader("Origin", origin);
        assertDoesNotThrow(() -> guard.check(request), "Dev should allow: " + origin);
    }

    // ── DEV: rejected origins ─────────────────────────────────────────────────────────────────
    @ParameterizedTest(name = "dev rejects [{0}]")
    @ValueSource(strings = {
            "https://attacker.example",
            "https://evil.com",
            "http://evil.local.com",              // not a .local hostname — has tld after .local
            "http://localhost.attacker.com",       // not localhost — contains additional labels
            "http://pathome.local.attacker.com",   // attacker.com tld, not .local
            "http://evil-localhost.com",
            "http://sub.evil.com",
            "http://172.15.0.1:5173",             // 172.15 outside 172.16.0.0/12
            "http://172.32.0.1:5173",             // 172.32 outside 172.16.0.0/12
            "http://172.0.0.1:5173"               // 172.0 not private (below /12 boundary)
    })
    void devRejectsUntrustedOrigins(String origin) {
        var guard = new GuestRequestGuard(new MockEnvironment(), "");
        var request = new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts");
        request.addHeader("Origin", origin);
        assertThrows(AccessDeniedException.class, () -> guard.check(request),
                "Dev should reject: " + origin);
    }

    // ── PRODUCTION: LAN not allowed ───────────────────────────────────────────────────────────
    @Test
    void productionRejectsLanOriginsUnlessExplicitlyConfigured() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        var guard = new GuestRequestGuard(env, "https://pathome.com,https://app.pathome.com");

        for (String lanOrigin : java.util.List.of(
                "http://dipeshs-macbook-air.local:5173",
                "http://192.168.1.50:5173",
                "http://10.0.0.1:5173",
                "http://172.20.10.4:5173",
                "http://127.0.0.1:5173",
                "http://localhost:5173"
        )) {
            var req = new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts");
            req.addHeader("Origin", lanOrigin);
            assertThrows(AccessDeniedException.class, () -> guard.check(req),
                    "Production must reject LAN origin: " + lanOrigin);
        }

        // Configured production origin must work
        var prodRequest = new MockHttpServletRequest("POST", "/api/v1/lessor/guest/drafts");
        prodRequest.addHeader("Origin", "https://pathome.com");
        assertDoesNotThrow(() -> guard.check(prodRequest), "Production must allow configured origin");
    }

    @Test
    void productionRequiresExplicitOriginConfiguration() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new GuestRequestGuard(env, ""));

        var guard = new GuestRequestGuard(env, "https://pathome.example");
        assertTrue(guard.secureCookie(new MockHttpServletRequest()));
    }

    // ── DevOriginPolicy unit tests ────────────────────────────────────────────────────────────
    @Test
    void devPolicyPrivateRangesBoundaries() {
        // 172.16–31 are private; 172.15 and 172.32 are not
        assertTrue(DevOriginPolicy.isLegitimateDevOrigin("http://172.16.0.1:5173"));
        assertTrue(DevOriginPolicy.isLegitimateDevOrigin("http://172.31.255.255:5173"));
        assertFalse(DevOriginPolicy.isLegitimateDevOrigin("http://172.15.0.1:5173"));
        assertFalse(DevOriginPolicy.isLegitimateDevOrigin("http://172.32.0.1:5173"));
        assertFalse(DevOriginPolicy.isLegitimateDevOrigin("http://172.0.0.1:5173"));
    }

    @Test
    void devPolicyRejectsMaliciousLookalikes() {
        assertFalse(DevOriginPolicy.isLegitimateDevOrigin("http://localhost.attacker.com"));
        assertFalse(DevOriginPolicy.isLegitimateDevOrigin("http://pathome.local.attacker.com"));
        assertFalse(DevOriginPolicy.isLegitimateDevOrigin("http://evil-localhost.com"));
        // Ensure .local matching is exact suffix, not substring
        assertFalse(DevOriginPolicy.isLegitimateDevOrigin("http://evil.local.evil.com"));
    }

    @Test
    void devPolicyNullAndBlankReturnFalse() {
        assertFalse(DevOriginPolicy.isLegitimateDevOrigin(null));
        assertFalse(DevOriginPolicy.isLegitimateDevOrigin(""));
        assertFalse(DevOriginPolicy.isLegitimateDevOrigin("   "));
    }
}
