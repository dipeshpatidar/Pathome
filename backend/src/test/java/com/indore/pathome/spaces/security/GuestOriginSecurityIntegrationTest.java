package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.GuestDraftController;
import com.indore.pathome.spaces.service.GuestDraftService;
import com.indore.pathome.spaces.service.GuestMediaService;
import com.indore.pathome.spaces.service.LandlordSubmissionService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the real {@link GuestRequestGuard} + {@link SecurityConfig} CORS chain.
 *
 * <p>Unlike {@link GuestDraftSecurityTest} (which mocks the guard), this test imports the
 * <em>real</em> guard bean so that origin acceptance and rejection go through the actual
 * {@link DevOriginPolicy} logic. The service layer is mocked at the HTTP level; real
 * guest-proof ownership validation is covered separately in
 * {@link com.indore.pathome.spaces.service.GuestDraftServiceTest}.
 *
 * <p>Test groups:
 * <ul>
 *   <li>{@link OriginPolicy} — numeric LAN/loopback origins accepted; hostname-shaped lookalikes
 *       and out-of-range addresses rejected. Also includes CORS response-header assertions.</li>
 *   <li>{@link GuestOwnershipEnforcement} — HTTP ownership scenarios: new create, valid-proof
 *       access, missing-proof, invalid-proof, cross-guest isolation (different draftId),
 *       mutating-without-Origin, and claim-requires-auth. Service is stubbed per-proof-value
 *       to reflect real routing (valid credential → success, wrong/absent credential → 404).</li>
 * </ul>
 *
 * <p>Real proof-hashing, expiry, and discard logic are covered in
 * {@link com.indore.pathome.spaces.service.GuestDraftServiceTest#otherGuestAndRandomIdCannotReadAndExpiredProofIsDenied()}
 * and companion tests — those exercise the real {@link GuestDraftService} without a mocked service layer.
 */
@WebMvcTest(controllers = GuestDraftController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GuestRequestGuard.class})
class GuestOriginSecurityIntegrationTest {

    @Autowired MockMvc mvc;

    // ── Service mocks (GuestRequestGuard is the REAL bean — not listed here) ──────────────────
    @MockBean GuestDraftService drafts;
    @MockBean GuestMediaService media;
    @MockBean LandlordSubmissionService submissions;
    @MockBean OAuth2AuthenticationSuccessHandler oauth;
    @MockBean JwtUtils jwt;

    private static final String VALID_PROOF   = "VALIDCREDENTIAL12345678901234567890123";  // 38 chars - distinct stub
    private static final String INVALID_PROOF = "INVALIDPROOF1234567890123456789012345";
    private static final String DRAFT_A       = "guest-draft-aaa";
    private static final String DRAFT_B       = "guest-draft-bbb";
    private static final String CREATE_BODY   =
            "{\"propertyType\":\"FLAT\",\"rentalMode\":\"LONG_TERM_RENTAL\",\"bhkCount\":null}";

    private com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse stubResponse(String draftId) {
        return new com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse(
                draftId, "DRAFT", 1, 10,
                new com.indore.pathome.spaces.dto.lessor.LandlordDraftData(
                        new com.indore.pathome.spaces.dto.lessor.LandlordDraftData.Basics(
                                com.indore.pathome.spaces.entity.PropertyType.FLAT,
                                com.indore.pathome.spaces.entity.RentalMode.LONG_TERM_RENTAL,
                                null),
                        null, null, null),
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), null, null);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // A. ORIGIN POLICY (CORS + Guard)
    // ═══════════════════════════════════════════════════════════════════════════════════════════
    @Nested
    class OriginPolicy {

        // ── A.1 Accepted dev origins ────────────────────────────────────────────────────────────

        @ParameterizedTest(name = "guard+CORS accept [{0}]")
        @ValueSource(strings = {
                "http://localhost:5173",             // 1. localhost
                "http://127.0.0.1:5173",             // standard loopback
                "http://127.0.0.2:5173",             // 2. 127.x loopback range (127.0.0.2)
                "http://127.255.255.254:5173",        // end of loopback range
                "http://10.0.0.55:5173",             // 3. 10.x private
                "http://172.16.0.5:5173",            // 4. 172.16 (start of /12)
                "http://172.20.10.4:5173",           // 4. 172.20 mid-range
                "http://172.31.255.1:5173",          // 4. 172.31 (end of /12)
                "http://192.168.1.50:5173",          // 5. 192.168.x
                "http://dipeshs-macbook-air.local:5173" // 6. .local mDNS
        })
        void acceptedDevOrigins(String origin) throws Exception {
            when(drafts.create(any(), any(), any()))
                    .thenReturn(new GuestDraftService.Created(stubResponse("guest-stub"), "cred"));
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", origin)
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isOk());
        }

        // ── A.2 Rejected — must produce 403 ────────────────────────────────────────────────────

        @ParameterizedTest(name = "guard+CORS reject [{0}]")
        @ValueSource(strings = {
                "http://10.foo.attacker.com",        // 7. hostname-shaped 10.x lookalike
                "http://172.16.foo.attacker.com",    // 8. hostname-shaped 172.16 lookalike
                "http://localhost.attacker.com",     // 9. localhost lookalike
                "http://pathome.local.attacker.com", // 10. .local lookalike
                "http://172.15.0.1:5173",            // 11. 172.15 — outside /12
                "http://172.32.0.1:5173",            // 12. 172.32 — outside /12
                "https://attacker.example",          // public internet origin
                "http://evil-localhost.com",
                "http://sub.evil.com"
        })
        void rejectedDevOrigins(String origin) throws Exception {
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", origin)
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(drafts);
        }

        // ── A.3 CORS response headers — prove CORS agrees with guard ────────────────────────────

        /**
         * A legitimate numeric private-IP origin must be reflected back exactly in
         * {@code Access-Control-Allow-Origin} — confirming the CORS filter used
         * {@link DevOriginPolicy} rather than a wildcard pattern.
         */
        @Test
        void legitimateNumericIpOriginReflectedInCorsHeader() throws Exception {
            String allowedOrigin = "http://192.168.1.50:5173";
            when(drafts.create(any(), any(), any()))
                    .thenReturn(new GuestDraftService.Created(stubResponse("guest-stub"), "cred"));

            mvc.perform(options("/api/v1/lessor/guest/drafts")
                            .header("Origin", allowedOrigin)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", is(allowedOrigin)));
        }

        /**
         * A malicious hostname shaped like a private-IP origin must NOT be allowed by CORS.
         * Specifically proves that {@code 10.foo.attacker.com} does not match the 10.0.0.0/8 rule.
         */
        @Test
        void maliciousHostnameLookalikeMustNotBeAllowedByCors() throws Exception {
            mvc.perform(options("/api/v1/lessor/guest/drafts")
                            .header("Origin", "http://10.foo.attacker.com")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }

        /**
         * 127.0.0.2 is in the loopback /8 range — guard and CORS must both accept it.
         * This verifies the P2-2 alignment: CORS no longer limits loopback to only 127.0.0.1.
         */
        @Test
        void loopback127_0_0_2AcceptedByBothCorsAndGuard() throws Exception {
            String loopbackOrigin = "http://127.0.0.2:5173";
            when(drafts.create(any(), any(), any()))
                    .thenReturn(new GuestDraftService.Created(stubResponse("guest-stub"), "cred"));
            mvc.perform(options("/api/v1/lessor/guest/drafts")
                            .header("Origin", loopbackOrigin)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", is(loopbackOrigin)));
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", loopbackOrigin)
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isOk());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // B. GUEST OWNERSHIP ENFORCEMENT (HTTP layer + real guard, conditional service stubs)
    // ═══════════════════════════════════════════════════════════════════════════════════════════
    @Nested
    class GuestOwnershipEnforcement {

        /**
         * B-1 (test #13): Anonymous create succeeds — no prior proof cookie needed.
         */
        @Test
        void anonymousNewDraftCreationSucceeds() throws Exception {
            when(drafts.create(any(), any(), any()))
                    .thenReturn(new GuestDraftService.Created(stubResponse(DRAFT_A), "new-cred"));
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", "http://localhost:5173")
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isOk());
        }

        /**
         * B-2 (test #14): Valid proof cookie allows access to the owned draft.
         * Service stub returns the draft only when the correct proof value is supplied.
         */
        @Test
        void validProofAllowsAccessToOwnedDraft() throws Exception {
            // Only the correct proof gets the draft; anything else → EntityNotFound
            when(drafts.get(eq(DRAFT_A), eq(VALID_PROOF))).thenReturn(stubResponse(DRAFT_A));
            when(drafts.get(eq(DRAFT_A), eq(INVALID_PROOF))).thenThrow(new EntityNotFoundException("Draft unavailable"));
            when(drafts.get(eq(DRAFT_A), eq(null))).thenThrow(new EntityNotFoundException("Draft unavailable"));

            mvc.perform(get("/api/v1/lessor/guest/drafts/" + DRAFT_A)
                            .header("Origin", "http://localhost:5173")
                            .cookie(new Cookie("pathome_guest_draft", VALID_PROOF)))
                    .andExpect(status().isOk());
        }

        /**
         * B-3 (test #15): Missing proof cookie on an existing draft → 404.
         * Stub returns 404 when proof is null (simulating service's real behaviour).
         */
        @Test
        void missingProofOnExistingDraftIsRejected() throws Exception {
            when(drafts.get(eq(DRAFT_A), eq(null))).thenThrow(new EntityNotFoundException("Draft unavailable"));
            mvc.perform(get("/api/v1/lessor/guest/drafts/" + DRAFT_A)
                            .header("Origin", "http://localhost:5173"))
                    .andExpect(status().isNotFound());
        }

        /**
         * B-4 (test #16): Invalid/wrong proof cookie on an existing draft → 404.
         * Stub rejects INVALID_PROOF while VALID_PROOF would be accepted — exercises
         * proof-specificity at the HTTP boundary.
         */
        @Test
        void invalidProofOnExistingDraftIsRejected() throws Exception {
            when(drafts.get(eq(DRAFT_A), eq(VALID_PROOF))).thenReturn(stubResponse(DRAFT_A));
            when(drafts.get(eq(DRAFT_A), eq(INVALID_PROOF))).thenThrow(new EntityNotFoundException("Draft unavailable"));

            mvc.perform(get("/api/v1/lessor/guest/drafts/" + DRAFT_A)
                            .header("Origin", "http://localhost:5173")
                            .cookie(new Cookie("pathome_guest_draft", INVALID_PROOF)))
                    .andExpect(status().isNotFound());
        }

        /**
         * B-5 (test #17): Cross-guest isolation — guest A's proof cannot access guest B's draft.
         * Stub: DRAFT_A accepts VALID_PROOF; DRAFT_B rejects VALID_PROOF (different owner).
         */
        @Test
        void guestAProofCannotAccessGuestBDraft() throws Exception {
            when(drafts.get(eq(DRAFT_A), eq(VALID_PROOF))).thenReturn(stubResponse(DRAFT_A));
            when(drafts.get(eq(DRAFT_B), eq(VALID_PROOF))).thenThrow(new EntityNotFoundException("Draft unavailable"));

            // Owning draft succeeds
            mvc.perform(get("/api/v1/lessor/guest/drafts/" + DRAFT_A)
                            .header("Origin", "http://localhost:5173")
                            .cookie(new Cookie("pathome_guest_draft", VALID_PROOF)))
                    .andExpect(status().isOk());

            // Another guest's draft with the same proof is rejected
            mvc.perform(get("/api/v1/lessor/guest/drafts/" + DRAFT_B)
                            .header("Origin", "http://localhost:5173")
                            .cookie(new Cookie("pathome_guest_draft", VALID_PROOF)))
                    .andExpect(status().isNotFound());
        }

        /**
         * B-6 (test #18): Claim idempotency — the claim endpoint requires authentication;
         * anonymous attempt returns 401 (Spring Security rejects before service is reached).
         */
        @Test
        void claimRequiresAuthentication() throws Exception {
            mvc.perform(post("/api/v1/lessor/guest/drafts/" + DRAFT_A + "/claim")
                            .header("Origin", "http://localhost:5173"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(drafts);
        }

        /**
         * B-7: Mutating POST without an Origin header is rejected (403) before reaching service.
         */
        @Test
        void mutatingRequestWithoutOriginIsRejected() throws Exception {
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(drafts);
        }
    }
}
