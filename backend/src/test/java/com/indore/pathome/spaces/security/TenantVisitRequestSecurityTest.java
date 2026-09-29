package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.TenantVisitRequestController;
import com.indore.pathome.spaces.dto.TenantVisitRequestPage;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.service.TenantVisitRequestHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TenantVisitRequestController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class TenantVisitRequestSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private UserRepository users;
    @MockBean private TenantVisitRequestHistoryService history;
    @MockBean private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockBean private JwtUtils jwtUtils;

    @Test
    void anonymousHistoryIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/tenant/visit-requests"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(users, history);
    }

    @Test
    @WithMockUser(username = "other@example.com", roles = "ADMIN")
    void nonTenantCannotReadVisitHistory() throws Exception {
        mockMvc.perform(get("/api/v1/tenant/visit-requests"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users, history);
    }

    @Test
    @WithMockUser(username = "tenant-a@example.com", roles = "TENANT")
    void suppliedTenantIdentityCannotChangeOwnership() throws Exception {
        User tenantA = new User();
        tenantA.setId(101L);
        when(users.findByEmail("tenant-a@example.com")).thenReturn(Optional.of(tenantA));
        when(history.listForUser(101L, 0)).thenReturn(new TenantVisitRequestPage(101L, List.of(), 0, 0, false));

        mockMvc.perform(get("/api/v1/tenant/visit-requests")
                        .param("tenantUserId", "202")
                        .param("userId", "202")
                        .param("email", "tenant-b@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(101))
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.requests").isEmpty());

        verify(users).findByEmail("tenant-a@example.com");
        verify(history).listForUser(101L, 0);
        verify(history, never()).listForUser(eq(202L), anyInt());
    }
}
