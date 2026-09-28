package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.LandlordCapabilityController;
import com.indore.pathome.spaces.service.LandlordCapabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LandlordCapabilityController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class LandlordCapabilitySecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private LandlordCapabilityService capabilities;
    @MockBean private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockBean private JwtUtils jwtUtils;

    @Test
    void unauthenticatedActivationIsDenied() throws Exception {
        mockMvc.perform(post("/api/v1/lessor/capability"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(capabilities);
    }

    @Test
    @WithMockUser(username = "tenant@example.com", roles = "TENANT")
    void activationUsesAuthenticatedIdentityAndIgnoresClientRole() throws Exception {
        when(capabilities.activate("tenant@example.com"))
                .thenReturn(new LandlordCapabilityService.Capability(17L, true, false, LocalDateTime.of(2026, 9, 28, 10, 0)));

        mockMvc.perform(post("/api/v1/lessor/capability")
                        .contentType("application/json")
                        .content("{\"role\":\"ROLE_ADMIN\",\"ownerUserId\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        verify(capabilities).activate("tenant@example.com");
    }

    @Test
    @WithMockUser(username = "tenant@example.com", roles = "TENANT")
    void capabilityCanBeReadWithoutMutation() throws Exception {
        when(capabilities.getCapability("tenant@example.com"))
                .thenReturn(new LandlordCapabilityService.Capability(17L, false, false, null));

        mockMvc.perform(get("/api/v1/lessor/capability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        verify(capabilities).getCapability("tenant@example.com");
        verifyNoMoreInteractions(capabilities);
    }
}
