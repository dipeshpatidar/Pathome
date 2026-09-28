package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.LandlordDraftController;
import com.indore.pathome.spaces.service.LandlordDraftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LandlordDraftController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class LandlordDraftSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private LandlordDraftService drafts;
    @MockBean private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockBean private JwtUtils jwtUtils;

    @Test
    void unauthenticatedDraftReadIsDenied() throws Exception {
        mockMvc.perform(get("/api/v1/lessor/properties/drafts/a-draft"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(drafts);
    }

    @Test
    @WithMockUser(username = "owner@example.com", roles = "TENANT")
    void draftReadUsesAuthenticatedIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/lessor/properties/drafts/a-draft"))
                .andExpect(status().isOk());
        verify(drafts).get("owner@example.com", "a-draft");
    }

    @Test
    @WithMockUser(username = "owner@example.com", roles = "TENANT")
    void sectionUpdateRequiresVersionHeader() throws Exception {
        mockMvc.perform(patch("/api/v1/lessor/properties/drafts/a-draft/sections/pricing")
                        .contentType("application/json")
                        .content("{\"monthlyRent\":22000,\"securityDeposit\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(drafts);
    }
}
