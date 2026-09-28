package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.LandlordReviewController;
import com.indore.pathome.spaces.service.LandlordReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LandlordReviewController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class LandlordReviewSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private LandlordReviewService reviews;
    @MockBean private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockBean private JwtUtils jwtUtils;

    @Test
    void anonymousCannotReadReviewQueue() throws Exception {
        mockMvc.perform(get("/api/v1/admin/lessor-review/revisions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reviews);
    }

    @Test
    @WithMockUser(roles = "TENANT")
    void landlordCannotApproveOwnRevision() throws Exception {
        mockMvc.perform(post("/api/v1/admin/lessor-review/revisions/draft-1/approve"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reviews);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminMayOpenReviewQueue() throws Exception {
        mockMvc.perform(get("/api/v1/admin/lessor-review/revisions"))
                .andExpect(status().isOk());
        verify(reviews).revisions(0);
    }
}
