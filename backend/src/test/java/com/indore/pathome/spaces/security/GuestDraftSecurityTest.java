package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.GuestDraftController;
import com.indore.pathome.spaces.service.GuestDraftService;
import com.indore.pathome.spaces.service.GuestMediaService;
import com.indore.pathome.spaces.service.LandlordSubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GuestDraftController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class GuestDraftSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean GuestDraftService drafts;
    @MockBean GuestMediaService media;
    @MockBean LandlordSubmissionService submissions;
    @MockBean GuestRequestGuard guard;
    @MockBean OAuth2AuthenticationSuccessHandler oauth;
    @MockBean JwtUtils jwt;

    @Test
    void guestCannotClaimWithoutAuthentication() throws Exception {
        mvc.perform(post("/api/v1/lessor/guest/drafts/guest-random/claim")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(drafts);
    }

    @Test
    void guestHasNoDirectSubmitEndpoint() throws Exception {
        mvc.perform(post("/api/v1/lessor/guest/drafts/guest-random/submit")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(submissions);
    }
}
