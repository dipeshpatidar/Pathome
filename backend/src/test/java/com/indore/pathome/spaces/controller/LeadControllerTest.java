package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.crm.LeadCommandReceiptView;
import com.indore.pathome.spaces.dto.crm.LeadPage;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.service.LeadService;
import com.indore.pathome.spaces.service.LeadContactPreferenceService;
import com.indore.pathome.spaces.service.LeadFollowUpFilter;
import com.indore.pathome.spaces.service.LeadShareService;
import com.indore.pathome.spaces.service.LeadShortlistService;
import com.indore.pathome.spaces.service.LeadSourceProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LeadControllerTest {
    private final LeadService leads = mock(LeadService.class);
    private final LeadController controller = new LeadController(leads,
            mock(LeadContactPreferenceService.class), mock(LeadShortlistService.class),
            mock(LeadShareService.class), mock(LeadSourceProcessingService.class));

    @Test
    void listUsesVerifiedIdentityAndAlwaysSetsNoStore() {
        LeadPage page = new LeadPage(List.of(), 0, 0, 20, 0);
        when(leads.list(42L, null, null, null, false, null, null, null, null, 0, 20))
                .thenReturn(page);

        var response = controller.list(authentication(42L), null, null, null, null, null,
                null, null, null, "0", "20");

        assertEquals(page, response.getBody());
        assertTrue(String.valueOf(response.getHeaders().getCacheControl()).contains("no-store"));
    }

    @Test
    void receiptReadFailsBeforeServiceWhenIdentityIsNotVerified() {
        var anonymous = new UsernamePasswordAuthenticationToken(
                "staff@example.test", null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> controller.receipt(anonymous, UUID.randomUUID().toString(), null));
        verifyNoInteractions(leads);
    }

    @Test
    void malformedFiltersAreRejectedBeforeAnyServiceCall() {
        var authentication = authentication(42L);
        assertThrows(IllegalArgumentException.class,
                () -> controller.list(authentication, "0", null, null, null, null,
                        null, null, null, "0", "20"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.list(authentication, null, null, null, null, "yesterday",
                        null, null, null, "0", "20"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.list(authentication, null, null, "UNKNOWN", null, null,
                        null, null, null, "0", "20"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.list(authentication, null, null, null, null, null,
                        null, "YESTERDAY", "Asia/Kolkata", "0", "20"));
        verifyNoInteractions(leads);
    }

    @Test
    void approvedSearchAndFollowUpParametersReachTheService() {
        LeadPage page = new LeadPage(List.of(), 0, 0, 20, 0);
        when(leads.list(42L, 3L, 7L, null, false, null,
                "Green Park", LeadFollowUpFilter.TODAY, "Asia/Kolkata", 0, 20))
                .thenReturn(page);

        var response = controller.list(authentication(42L), "3", "7", null, null, null,
                "Green Park", "today", "Asia/Kolkata", "0", "20");

        assertEquals(page, response.getBody());
        verify(leads).list(42L, 3L, 7L, null, false, null,
                "Green Park", LeadFollowUpFilter.TODAY, "Asia/Kolkata", 0, 20);
    }

    @Test
    void unexpectedServiceFailureIsNeverConvertedIntoAnEmptySuccessfulPage() {
        when(leads.list(42L, null, null, null, false, null, null, null, null, 0, 20))
                .thenThrow(new IllegalStateException("injected queue failure"));

        assertThrows(IllegalStateException.class,
                () -> controller.list(authentication(42L), null, null, null, null, null,
                        null, null, null, "0", "20"));
    }

    @Test
    void receiptUsesTheAuthenticatedActorAndVerifiedUuid() {
        UUID operationId = UUID.randomUUID();
        LeadCommandReceiptView view = new LeadCommandReceiptView(
                "RECORDED", "LEAD_CAPTURE", "8", Instant.now());
        when(leads.receipt(42L, operationId, null)).thenReturn(view);

        var response = controller.receipt(authentication(42L), operationId.toString(), null);

        assertEquals(view, response.getBody());
        verify(leads).receipt(42L, operationId, null);
    }

    private static UsernamePasswordAuthenticationToken authentication(Long userId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "staff@example.test", null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
        authentication.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), userId));
        return authentication;
    }
}
