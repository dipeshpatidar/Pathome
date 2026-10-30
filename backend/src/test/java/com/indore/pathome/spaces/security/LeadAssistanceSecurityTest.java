package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.LeadPhoneVerificationController;
import com.indore.pathome.spaces.controller.LeadVisitHandoffController;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmationView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.ProposalPropertyView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.VisitHandoffView;
import com.indore.pathome.spaces.entity.LeadPhoneChallengeState;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import com.indore.pathome.spaces.service.LeadPhoneVerificationService;
import com.indore.pathome.spaces.service.LeadVisitProposalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {LeadPhoneVerificationController.class,
        LeadVisitHandoffController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class LeadAssistanceSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean private LeadPhoneVerificationService phoneVerification;
    @MockBean private LeadVisitProposalService proposals;
    @MockBean private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockBean private JwtUtils jwtUtils;

    @Test
    void anonymousPhoneExchangeIsPostOnlyAndNoStore() throws Exception {
        when(phoneVerification.exchange(any())).thenReturn(new PhoneConfirmationView(
                LeadPhoneChallengeState.PENDING, LeadPhoneDeliveryState.ACCEPTED,
                "*******3210", 5, Instant.parse("2026-10-27T10:05:00Z"),
                "Enter the six-digit enquiry code from the SMS."));

        mockMvc.perform(post("/api/v1/lead-phone-verifications/exchange")
                        .contentType("application/json")
                        .content("{\"token\":\"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void protectedPhoneManagementStillRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/operations/leads/9/phone-verifications")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void VisitHandoffCookieCarriesOnlyAnOpaqueNonAuthContext() throws Exception {
        UUID proposalId = UUID.randomUUID();
        VisitHandoffView view = new VisitHandoffView(UUID.randomUUID(), proposalId,
                Instant.parse("2026-10-27T10:30:00Z"),
                new ProposalPropertyView(31L, "2 BHK Flat in Vijay Nagar, Indore",
                        "Indore / Vijay Nagar", "2 BHK", BigDecimal.valueOf(25000), 4L));
        when(proposals.exchange(any())).thenReturn(
                new LeadVisitProposalService.HandoffExchange("opaque-context", view));

        mockMvc.perform(post("/api/v1/lead-visit-handoffs/exchange")
                        .contentType("application/json")
                        .content("{\"token\":\"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }
}
