package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.FailedUploadsController;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.indore.pathome.spaces.service.FailedUploadService;
import com.indore.pathome.spaces.service.MediaStagingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.indore.pathome.spaces.entity.MediaUploadFailure;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FailedUploadsController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class FailedUploadsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FailedUploadService failedUploadService;

    @MockBean
    private CloudinaryService cloudinaryService;

    @MockBean
    private PropertyMediaAssetRepository mediaAssetRepository;

    @MockBean
    private ListingRepository listingRepository;

    @MockBean
    @org.springframework.beans.factory.annotation.Qualifier("mediaStagingService")
    private MediaStagingService mediaStagingService;

    @MockBean
    private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;

    @MockBean
    private JwtUtils jwtUtils;

    @Test
    void unauthenticatedRequest_isBlockedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/failed-uploads"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "TENANT")
    void tenantRole_isForbiddenWith403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/failed-uploads"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRole_canListFailedUploads() throws Exception {
        when(failedUploadService.getUnresolved()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/failed-uploads"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUB_ADMIN")
    void subAdminRole_canListFailedUploads() throws Exception {
        when(failedUploadService.getUnresolved()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/failed-uploads"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRole_canAccessCount() throws Exception {
        when(failedUploadService.countUnresolved()).thenReturn(0L);

        mockMvc.perform(get("/api/v1/admin/failed-uploads/count"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUB_ADMIN")
    void subAdminRole_canAccessCount() throws Exception {
        when(failedUploadService.countUnresolved()).thenReturn(0L);

        mockMvc.perform(get("/api/v1/admin/failed-uploads/count"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUB_ADMIN")
    void subAdminRole_doesNotGainAccessToUnrelatedAdminRoutes() throws Exception {
        // Any request to /api/v1/admin/** outside of /api/v1/admin/failed-uploads/** must require ROLE_ADMIN
        mockMvc.perform(get("/api/v1/admin/system-settings"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRole_canDismissFailedUpload() throws Exception {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        when(failedUploadService.getById(1L)).thenReturn(Optional.of(failure));

        mockMvc.perform(post("/api/v1/admin/failed-uploads/1/dismiss"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRole_canRetryFailedUpload() throws Exception {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("RESOLVED"); // Already resolved returns 200 without doing work
        when(failedUploadService.getById(1L)).thenReturn(Optional.of(failure));

        mockMvc.perform(post("/api/v1/admin/failed-uploads/1/retry"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUB_ADMIN")
    void subAdminRole_canRetryFailedUpload() throws Exception {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("RESOLVED");
        when(failedUploadService.getById(1L)).thenReturn(Optional.of(failure));

        mockMvc.perform(post("/api/v1/admin/failed-uploads/1/retry"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "TENANT")
    void tenantRole_isForbiddenToRetry() throws Exception {
        mockMvc.perform(post("/api/v1/admin/failed-uploads/1/retry"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "TENANT")
    void tenantRole_isForbiddenToDismiss() throws Exception {
        mockMvc.perform(post("/api/v1/admin/failed-uploads/1/dismiss"))
                .andExpect(status().isForbidden());
    }
}
