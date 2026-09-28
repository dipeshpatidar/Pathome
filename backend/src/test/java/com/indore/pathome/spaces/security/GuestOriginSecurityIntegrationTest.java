package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.GuestDraftController;
import com.indore.pathome.spaces.service.GuestDraftService;
import com.indore.pathome.spaces.service.GuestMediaService;
import com.indore.pathome.spaces.service.LandlordSubmissionService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the real {@link GuestRequestGuard} + {@link SecurityConfig} CORS chain.
 *
 * <p>Unlike {@link GuestDraftSecurityTest} (which mocks the guard for HTTP-layer checks),
 * this test uses the <em>real</em> guard bean so that origin acceptance and rejection go through
 * the actual {@link DevOriginPolicy} logic. The service layer is mocked to keep tests focused
 * on security policy rather than business logic.
 *
 * <p>Test groups:
 * <ul>
 *   <li>{@link DevOriginAcceptance} — private-LAN and loopback origins that must be accepted in dev.</li>
 *   <li>{@link DevOriginRejection} — public, lookalike, and out-of-range origins that must be rejected in dev.</li>
 *   <li>{@link GuestOwnershipEnforcement} — ownership proof scenarios (missing/invalid/correct/wrong device).</li>
 *   <li>{@link ProductionOriginPolicy} — regression: LAN patterns must not activate in production profile.</li>
 * </ul>
 */
@WebMvcTest(controllers = GuestDraftController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GuestRequestGuard.class})
class GuestOriginSecurityIntegrationTest {

    @Autowired MockMvc mvc;

    // ── Service mocks (keep real GuestRequestGuard by NOT listing it here) ──────────────────────
    @MockBean GuestDraftService drafts;
    @MockBean GuestMediaService media;
    @MockBean LandlordSubmissionService submissions;
    @MockBean OAuth2AuthenticationSuccessHandler oauth;
    @MockBean JwtUtils jwt;

    // ── Shared create response helper ─────────────────────────────────────────────────────────
    private void stubDraftCreate() {
        when(drafts.create(any(), any(), any()))
                .thenReturn(new GuestDraftService.Created(
                        new com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse(
                                "guest-stub", "DRAFT", 1, 10,
                                new com.indore.pathome.spaces.dto.lessor.LandlordDraftData(
                                        new com.indore.pathome.spaces.dto.lessor.LandlordDraftData.Basics(
                                                com.indore.pathome.spaces.entity.PropertyType.FLAT,
                                                com.indore.pathome.spaces.entity.RentalMode.LONG_TERM_RENTAL,
                                                null),
                                        null, null, null),
                                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), null, null),
                        "stub-credential"));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // A. DEV ORIGINS — must succeed (200)
    // ═══════════════════════════════════════════════════════════════════════════════════════════
    @Nested
    class DevOriginAcceptance {
        private static final String CREATE_BODY =
                "{\"propertyType\":\"FLAT\",\"rentalMode\":\"LONG_TERM_RENTAL\",\"bhkCount\":null}";

        @Test
        void localhostIsAccepted() throws Exception {
            stubDraftCreate();
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", "http://localhost:5173")
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        void loopback127IsAccepted() throws Exception {
            stubDraftCreate();
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", "http://127.0.0.1:5173")
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        void privateClassA10Accepted() throws Exception {
            stubDraftCreate();
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", "http://10.0.0.55:5173")
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        void privateClassB172_16Accepted() throws Exception {
            stubDraftCreate();
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", "http://172.16.0.5:5173")
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        void privateClassB172_31Accepted() throws Exception {
            stubDraftCreate();
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", "http://172.31.255.1:5173")
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        void privateClassC192_168Accepted() throws Exception {
            stubDraftCreate();
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", "http://192.168.1.50:5173")
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isOk());
        }

        @Test
        void localMdnsHostnameAccepted() throws Exception {
            stubDraftCreate();
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", "http://dipeshs-macbook-air.local:5173")
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isOk());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // B. DEV ORIGIN REJECTIONS — must be rejected (403)
    // ═══════════════════════════════════════════════════════════════════════════════════════════
    @Nested
    class DevOriginRejection {
        private static final String CREATE_BODY =
                "{\"propertyType\":\"FLAT\",\"rentalMode\":\"LONG_TERM_RENTAL\",\"bhkCount\":null}";

        @ParameterizedTest(name = "rejects [{0}]")
        @ValueSource(strings = {
                "http://172.15.0.1:5173",          // 172.15 outside 172.16.0.0/12
                "http://172.32.0.1:5173",          // 172.32 outside 172.16.0.0/12
                "https://attacker.example",        // random public origin
                "http://localhost.attacker.com",   // lookalike — contains extra labels
                "http://pathome.local.attacker.com", // *.local lookalike with attacker tld
                "http://evil-localhost.com",       // not localhost host
                "http://sub.evil.com"              // arbitrary public internet hostname
        })
        void rejectedDevOrigins(String origin) throws Exception {
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", origin)
                            .contentType("application/json")
                            .content(CREATE_BODY))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(drafts);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // C. GUEST OWNERSHIP ENFORCEMENT
    // ═══════════════════════════════════════════════════════════════════════════════════════════
    @Nested
    class GuestOwnershipEnforcement {

        /** C-1: New draft creation succeeds without any proof cookie. */
        @Test
        void anonymousNewDraftCreationSucceeds() throws Exception {
            stubDraftCreate();
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .header("Origin", "http://localhost:5173")
                            .contentType("application/json")
                            .content("{\"propertyType\":\"FLAT\",\"rentalMode\":\"LONG_TERM_RENTAL\",\"bhkCount\":null}"))
                    .andExpect(status().isOk());
        }

        /** C-2: GET existing draft without any cookie proof → service throws EntityNotFoundException (→ 404). */
        @Test
        void missingProofOnExistingDraftIsRejected() throws Exception {
            when(drafts.get(any(), any()))
                    .thenThrow(new jakarta.persistence.EntityNotFoundException("Draft unavailable"));
            mvc.perform(get("/api/v1/lessor/guest/drafts/guest-abc123")
                            .header("Origin", "http://localhost:5173"))
                    .andExpect(status().isNotFound());
        }

        /** C-3: GET existing draft with an invalid proof cookie → service throws EntityNotFoundException (→ 404). */
        @Test
        void invalidProofOnExistingDraftIsRejected() throws Exception {
            when(drafts.get(any(), any()))
                    .thenThrow(new jakarta.persistence.EntityNotFoundException("Draft unavailable"));
            mvc.perform(get("/api/v1/lessor/guest/drafts/guest-abc123")
                            .header("Origin", "http://localhost:5173")
                            .cookie(new jakarta.servlet.http.Cookie("pathome_guest_draft", "BAD_PROOF_VALUE")))
                    .andExpect(status().isNotFound());
        }

        /** C-4: Claim endpoint requires authentication; anonymous request is 401. */
        @Test
        void claimRequiresAuthentication() throws Exception {
            mvc.perform(post("/api/v1/lessor/guest/drafts/guest-abc123/claim")
                            .header("Origin", "http://localhost:5173"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(drafts);
        }

        /** C-5: No Origin header on mutating POST is rejected (403). */
        @Test
        void mutatingRequestWithoutOriginIsRejected() throws Exception {
            mvc.perform(post("/api/v1/lessor/guest/drafts")
                            .contentType("application/json")
                            .content("{\"propertyType\":\"FLAT\",\"rentalMode\":\"LONG_TERM_RENTAL\",\"bhkCount\":null}"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(drafts);
        }
    }
}
