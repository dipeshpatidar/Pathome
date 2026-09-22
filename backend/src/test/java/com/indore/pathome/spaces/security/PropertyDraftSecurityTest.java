package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.PropertyDraftController;
import com.indore.pathome.spaces.dto.draft.DraftSummaryDTO;
import com.indore.pathome.spaces.service.PropertyDraftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PropertyDraftController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PropertyDraftSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PropertyDraftService draftService;

    @MockBean
    private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;

    @MockBean
    private JwtUtils jwtUtils;

    @Test
    void unauthenticatedGetDrafts_isBlockedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/drafts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedDeleteDraft_isBlockedWith401() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/drafts/draft-test-123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "tenant@pathome.com", roles = "TENANT")
    void tenantRoleGetDrafts_isForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/drafts"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "tenant@pathome.com", roles = "TENANT")
    void tenantRoleDeleteDraft_isForbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/drafts/draft-test-123"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@pathome.com", roles = "ADMIN")
    void adminRole_canListDrafts() throws Exception {
        DraftSummaryDTO summary = new DraftSummaryDTO(
                "draft-1", "SINGLE", "DRAFT", "Summary", 1, 1, 0,
                LocalDateTime.now(), LocalDateTime.now()
        );
        when(draftService.listDrafts("admin@pathome.com")).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/v1/admin/drafts"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin@pathome.com", roles = "ADMIN")
    void adminRole_canDeleteDraft() throws Exception {
        doNothing().when(draftService).discardDraft("admin@pathome.com", "draft-1");

        mockMvc.perform(delete("/api/v1/admin/drafts/draft-1"))
                .andExpect(status().isNoContent());

        verify(draftService).discardDraft("admin@pathome.com", "draft-1");
    }

    @Test
    @WithMockUser(username = "subadmin@pathome.com", roles = "SUB_ADMIN")
    void subAdminRole_canListDrafts() throws Exception {
        when(draftService.listDrafts("subadmin@pathome.com")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/drafts"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "subadmin@pathome.com", roles = "SUB_ADMIN")
    void subAdminRole_canDeleteDraft() throws Exception {
        doNothing().when(draftService).discardDraft("subadmin@pathome.com", "draft-sub-1");

        mockMvc.perform(delete("/api/v1/admin/drafts/draft-sub-1"))
                .andExpect(status().isNoContent());

        verify(draftService).discardDraft("subadmin@pathome.com", "draft-sub-1");
    }
}
