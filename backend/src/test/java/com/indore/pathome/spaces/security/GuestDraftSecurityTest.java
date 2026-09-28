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

    @Test
    void anonymousUserCanCreateGuestDraftFromLanOrigin() throws Exception {
        org.mockito.Mockito.when(drafts.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new GuestDraftService.Created(
                        new com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse("guest-123", "DRAFT", 1, 10,
                                new com.indore.pathome.spaces.dto.lessor.LandlordDraftData(
                                        new com.indore.pathome.spaces.dto.lessor.LandlordDraftData.Basics(
                                                com.indore.pathome.spaces.entity.PropertyType.FLAT, com.indore.pathome.spaces.entity.RentalMode.LONG_TERM_RENTAL, null),
                                        null, null, null),
                                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), null, null),
                        "fake-credential"));

        mvc.perform(post("/api/v1/lessor/guest/drafts")
                        .header("Origin", "http://dipeshs-macbook-air.local:5173")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"propertyType\":\"FLAT\",\"rentalMode\":\"LONG_TERM_RENTAL\",\"bhkCount\":null}"))
                .andExpect(status().isOk());
    }
}
