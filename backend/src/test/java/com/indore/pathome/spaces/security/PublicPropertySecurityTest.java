package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.PropertyController;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.service.BatchPropertyPublishingService;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.indore.pathome.spaces.service.FailedUploadService;
import com.indore.pathome.spaces.service.MediaStagingService;
import com.indore.pathome.spaces.service.ParserLearningCaptureService;
import com.indore.pathome.spaces.service.ParserLearningService;
import com.indore.pathome.spaces.service.PropertyParserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PropertyController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PublicPropertySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private ListingRepository listingRepository;
    @MockBean private PropertyMediaAssetRepository mediaAssetRepository;
    @MockBean private CloudinaryService cloudinaryService;
    @MockBean private FailedUploadService failedUploadService;
    @MockBean private PropertyParserService propertyParserService;
    @MockBean private ParserLearningService parserLearningService;
    @MockBean private ParserLearningCaptureService parserLearningCaptureService;
    @MockBean private BatchPropertyPublishingService batchPropertyPublishingService;
    @MockBean @Qualifier("mediaStagingService") private MediaStagingService mediaStagingService;
    @MockBean private UserRepository userRepository;
    @MockBean private PropertyVisitRequestRepository propertyVisitRequestRepository;
    @MockBean private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockBean private JwtUtils jwtUtils;

    @Test
    void unauthenticatedTaggedMediaIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/properties/7/tagged-media"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "TENANT")
    void tenantTaggedMediaIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/properties/7/tagged-media"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminTaggedMediaIsAllowed() throws Exception {
        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(7L)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/properties/7/tagged-media"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUB_ADMIN")
    void subAdminTaggedMediaIsAllowed() throws Exception {
        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(7L)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/properties/7/tagged-media"))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedVisitRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/properties/7/visit-requests")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void nonTenantVisitRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/properties/7/visit-requests")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "TENANT")
    void malformedTenantVisitRequestIsBadRequest() throws Exception {
        RentalDetails listing = new RentalDetails();
        listing.setStatus(ListingStatus.ACTIVE);
        when(listingRepository.findById(7L)).thenReturn(Optional.of(listing));
        mockMvc.perform(post("/api/v1/properties/7/visit-requests")
                        .contentType("application/json").content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "tenant@example.com", roles = "TENANT")
    void tenantWhoseServerAccountIsNotTenantReceivesForbidden() throws Exception {
        RentalDetails listing = new RentalDetails();
        listing.setStatus(ListingStatus.ACTIVE);
        User serverAccount = new User();
        serverAccount.setRole(Role.ROLE_ADMIN);
        when(listingRepository.findById(7L)).thenReturn(Optional.of(listing));
        when(userRepository.findByEmail("tenant@example.com")).thenReturn(Optional.of(serverAccount));

        mockMvc.perform(post("/api/v1/properties/7/visit-requests")
                        .contentType("application/json")
                        .content("{\"preferredVisitTiming\":\"Saturday afternoon\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedSearchSuggestionsIsAllowed() throws Exception {
        when(listingRepository.findPublicRentalLocalitySuggestions(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        mockMvc.perform(get("/api/v1/properties/search-suggestions").param("q", "vijay"))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedSearchFeedbackIsAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/properties/search-feedback")
                        .contentType("application/json")
                        .content("{\"eventType\":\"SUGGESTION_SELECTED\",\"candidateTerm\":\"vijay\"}"))
                .andExpect(status().isOk());
    }
}
