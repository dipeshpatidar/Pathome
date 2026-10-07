package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = com.indore.pathome.spaces.controller.NotificationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class NotificationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;

    @MockBean
    private JwtUtils jwtUtils;

    @Test
    void anonymousPrivateFeedReadIsDenied() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousNotificationCreationIsDenied() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRole\":\"TENANT\",\"title\":\"untrusted\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "TENANT")
    void customerCannotCreateSystemNotifications() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRole\":\"ALL\",\"title\":\"untrusted\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreatePrivilegedAnnouncement() throws Exception {
        SystemNotification created = new SystemNotification(TargetRole.ADMIN, null,
                "Maintenance notice", "Scheduled maintenance", null, "SYSTEM", "info");
        created.setId(5L);
        when(notificationService.createAdminNotification(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(created);

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRole\":\"ADMIN\",\"title\":\"Maintenance notice\",\"message\":\"Scheduled maintenance\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedRecipientCanUseMarkAllReadAlias() throws Exception {
        UsernamePasswordAuthenticationToken recipient = new UsernamePasswordAuthenticationToken(
                "tenant", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
        recipient.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), 42L));
        when(notificationService.markAllAsReadForUser("42")).thenReturn(1);

        mockMvc.perform(post("/api/v1/notifications/mark-all-read")
                        .with(authentication(recipient)))
                .andExpect(status().isOk());
    }
}
