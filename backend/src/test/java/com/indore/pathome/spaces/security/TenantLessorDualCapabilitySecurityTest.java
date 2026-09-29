package com.indore.pathome.spaces.security;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.entity.LessorProfile;
import com.indore.pathome.spaces.entity.LessorSourceType;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.LessorProfileRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.service.LandlordCapabilityService;
import com.indore.pathome.spaces.service.LessorProfileService;
import com.indore.pathome.spaces.service.TenantVisitRequestHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = com.indore.pathome.spaces.controller.TenantVisitRequestController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        TenantVisitRequestHistoryService.class, LessorProfileService.class})
class TenantLessorDualCapabilitySecurityTest {
    private static final Long TENANT_ID = 101L;
    private static final Long OTHER_TENANT_ID = 202L;

    @Autowired private MockMvc mockMvc;
    @Autowired private LessorProfileService lessorProfileService;

    @MockBean private UserRepository users;
    @MockBean private LessorProfileRepository lessorProfiles;
    @MockBean private PropertyVisitRequestRepository visitRequests;
    @MockBean private PropertyMediaAssetRepository media;
    @MockBean private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockBean private JwtUtils jwtUtils;
    @MockBean private PlatformTransactionManager transactionManager;

    private final Map<Long, LessorProfile> profilesByUser = new HashMap<>();

    @BeforeEach
    void setUp() {
        profilesByUser.clear();
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        when(lessorProfiles.findByLinkedUserId(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(profilesByUser.get(invocation.getArgument(0))));
        when(lessorProfiles.existsByLinkedUserId(anyLong()))
                .thenAnswer(invocation -> profilesByUser.containsKey(invocation.getArgument(0)));
        when(lessorProfiles.insertIfNotExists(anyLong(), anyString(), anyString(), anyString(), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            if (profilesByUser.containsKey(userId)) return 0;
            LessorProfile profile = new LessorProfile();
            profile.setId(501L);
            profile.setLinkedUserId(userId);
            profile.setDisplayName(invocation.getArgument(1));
            profile.setMobileNumber(invocation.getArgument(2));
            profile.setEmail(invocation.getArgument(3));
            profile.setSourceType(LessorSourceType.valueOf(invocation.getArgument(4)));
            profile.setCreatedAt(invocation.getArgument(5));
            profile.setUpdatedAt(invocation.getArgument(6));
            profilesByUser.put(userId, profile);
            return 1;
        });
    }

    @Test
    @WithMockUser(username = "tenant-a@example.com", roles = "TENANT")
    void creatingLessorProfilePreservesTenantRoleAndTenantVisitHistoryAccess() throws Exception {
        User tenantA = user(TENANT_ID, "tenant-a@example.com", Role.ROLE_TENANT);
        User tenantB = user(OTHER_TENANT_ID, "tenant-b@example.com", Role.ROLE_TENANT);
        when(users.findByEmail("tenant-a@example.com")).thenReturn(Optional.of(tenantA));

        PropertyVisitRequest tenantARequest = visitRequest(301L, tenantA, 701L, "Tenant A home");
        PropertyVisitRequest tenantBRequest = visitRequest(302L, tenantB, 702L, "Tenant B private home");
        List<PropertyVisitRequest> allRequests = List.of(tenantARequest, tenantBRequest);
        when(visitRequests.findByTenantId(eq(TENANT_ID), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            List<PropertyVisitRequest> owned = allRequests.stream()
                    .filter(request -> TENANT_ID.equals(request.getTenant().getId()))
                    .toList();
            return new PageImpl<>(owned, pageable, owned.size());
        });
        when(media.findByListingIdInOrderByUploadedAtDesc(List.of(701L))).thenReturn(List.of());

        lessorProfileService.getOrCreateProfileForUser(tenantA);
        tenantA.setLandlordActivatedAt(LocalDateTime.of(2026, 9, 29, 9, 0));
        var capability = new LandlordCapabilityService(users, lessorProfiles)
                .getCapability("tenant-a@example.com");

        org.junit.jupiter.api.Assertions.assertEquals(Role.ROLE_TENANT, tenantA.getRole());
        org.junit.jupiter.api.Assertions.assertTrue(capability.enabled());
        org.junit.jupiter.api.Assertions.assertTrue(capability.hasLessorProfile());

        mockMvc.perform(get("/api/v1/tenant/visit-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(TENANT_ID))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].requestId").value(301))
                .andExpect(jsonPath("$.requests[0].propertyTitle").value("Tenant A home"))
                .andExpect(jsonPath("$.requests[1]").doesNotExist());

        verify(visitRequests).findByTenantId(eq(TENANT_ID), any(Pageable.class));
        verify(visitRequests, never()).findByTenantId(eq(OTHER_TENANT_ID), any(Pageable.class));
    }

    private static User user(Long id, String email, Role role) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFullName("Pathome User " + id);
        user.setPhoneNumber("+919876543210");
        user.setRole(role);
        return user;
    }

    private static PropertyVisitRequest visitRequest(Long requestId, User tenant, Long listingId, String title) {
        Listing listing = mock(Listing.class);
        when(listing.getId()).thenReturn(listingId);
        when(listing.getTitle()).thenReturn(title);
        when(listing.getCity()).thenReturn("Indore");
        when(listing.getSector()).thenReturn("Vijay Nagar");
        when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);

        PropertyVisitRequest request = new PropertyVisitRequest();
        request.setId(requestId);
        request.setTenant(tenant);
        request.setListing(listing);
        request.setStatus("RECEIVED");
        request.setCreatedAt(LocalDateTime.of(2026, 9, 29, 10, 0));
        return request;
    }
}
