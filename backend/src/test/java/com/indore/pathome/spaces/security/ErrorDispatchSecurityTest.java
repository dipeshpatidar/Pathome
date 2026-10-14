package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.controller.PropertyController;
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
import com.indore.pathome.spaces.service.TrustedOperationalIntakeService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security exception that keeps the container's error dispatch reachable must stay narrow.
 *
 * <p>The previous rule - {@code requestMatchers("/error").permitAll()} - granted every ordinary
 * request to the Boot error endpoint unrestricted access, because a path matcher cannot see how the
 * request arrived. {@code /error} is a real endpoint, so that is an exposure, not merely a
 * configuration detail.
 *
 * <p>These tests pin the three behaviours that matter: an ordinary anonymous {@code GET /error} is
 * refused, a genuine {@link DispatcherType#ERROR} dispatch still carries the status that was already
 * decided (so a permission denial is not rewritten as an authentication failure), and no other
 * protected resource is weakened - including when it is reached by an error dispatch.
 */
@WebMvcTest(controllers = PropertyController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ErrorDispatchSecurityTest {

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
    @MockBean private TrustedOperationalIntakeService trustedOperationalIntakeService;
    @MockBean @Qualifier("mediaStagingService") private MediaStagingService mediaStagingService;
    @MockBean private UserRepository userRepository;
    @MockBean private PropertyVisitRequestRepository propertyVisitRequestRepository;
    @MockBean private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockBean private JwtUtils jwtUtils;

    @Test
    void anonymousOrdinaryErrorRequestIsRefused() throws Exception {
        // An ordinary REQUEST dispatch to /error is not the container's error dispatch.
        mockMvc.perform(get("/error"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ordinaryErrorRequestIsRefusedForAnAuthenticatedCallerToo() throws Exception {
        mockMvc.perform(get("/error").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .user("plain-tenant").roles("TENANT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void genuineErrorDispatchRetainsTheDecidedStatus() throws Exception {
        // Exactly what the container does after AccessDeniedHandlerImpl.sendError(403): it dispatches
        // to the error page with DispatcherType.ERROR and the decided status as an attribute.
        mockMvc.perform(get("/error")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 403);
                            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI,
                                    "/api/v1/ground/visit-sessions/1");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    void errorDispatchDoesNotUnauthorizeOtherProtectedResources() throws Exception {
        // The exception is scoped to the error endpoint; an error dispatch to a protected resource is
        // still an anonymous request to that resource and is refused.
        mockMvc.perform(get("/api/v1/properties/7/tagged-media")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 403);
                            return request;
                        }))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingCredentialOnAProtectedEndpointIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/properties/7/tagged-media"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCredentialOnAProtectedEndpointIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/properties/7/tagged-media")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "TENANT")
    void authenticatedCallerWithoutTheResourcePermissionIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/properties/7/tagged-media"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void authenticatedCallerWithTheResourcePermissionIsAllowed() throws Exception {
        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(7L)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/properties/7/tagged-media"))
                .andExpect(status().isOk());
    }
}
