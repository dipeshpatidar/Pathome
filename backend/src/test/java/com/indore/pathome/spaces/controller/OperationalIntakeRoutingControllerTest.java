package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.InitialVisitRequestRoutingCommand;
import com.indore.pathome.spaces.dto.IntakeRoutingCandidatesView;
import com.indore.pathome.spaces.dto.IntakeRoutingRequestPage;
import com.indore.pathome.spaces.dto.IntakeRoutingTeamPage;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.service.OperationalIntakeRoutingReadService;
import com.indore.pathome.spaces.service.OperationalOwnershipService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OperationalIntakeRoutingControllerTest {
    private final OperationalIntakeRoutingReadService reads = mock(OperationalIntakeRoutingReadService.class);
    private final OperationalOwnershipService ownership = mock(OperationalOwnershipService.class);
    private final OperationalIntakeRoutingController controller =
            new OperationalIntakeRoutingController(reads, ownership);

    @Test
    void candidateReadUsesVerifiedIdentityAndBoundedPaging() {
        var authentication = authentication(42L);
        var page = new IntakeRoutingRequestPage(List.of(), 0L, 0, 20, 0L);
        when(reads.listEligibleRequests(42L, 1, 5)).thenReturn(page);

        ResponseEntity<IntakeRoutingRequestPage> response = controller.listCandidates(authentication, "1", "5");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(page, response.getBody());
        assertTrue(String.valueOf(response.getHeaders().getCacheControl()).contains("no-store"));
        verify(reads).listEligibleRequests(42L, 1, 5);
    }

    @Test
    void candidatesReadForwardsTheExplicitlySelectedCity() {
        var authentication = authentication(43L);
        var view = new IntakeRoutingCandidatesView(null, null,
                new IntakeRoutingTeamPage(List.of(), 0L, 0, 20, 0L));
        when(reads.readCandidates(43L, 81L, 7L, 0, 20)).thenReturn(view);

        ResponseEntity<IntakeRoutingCandidatesView> response =
                controller.readCandidates(authentication, "81", "7", "0", "20");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(view, response.getBody());
        assertTrue(String.valueOf(response.getHeaders().getCacheControl()).contains("no-store"));
        verify(reads).readCandidates(43L, 81L, 7L, 0, 20);
    }

    @Test
    void routeCommandForwardsEveryExplicitVersionAndReturnsNoContent() {
        var authentication = authentication(44L);
        var command = new InitialVisitRequestRoutingCommand(3L, 7L, 1L, 12L, 0L, 4L, "INITIAL_ROUTING");

        ResponseEntity<Void> response = controller.route(authentication, "81", command);

        assertEquals(204, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getHeaders().getCacheControl()).contains("no-store"));
        verify(ownership).routeInitialRequest(44L, 81L, 3L, 7L, 1L, 12L, 0L, 4L, "INITIAL_ROUTING");
    }

    @Test
    void malformedIdentifiersAndMissingBodyAreRejectedBeforeAnyMutation() {
        var authentication = authentication(45L);

        assertThrows(IllegalArgumentException.class, () -> controller.readCandidates(
                authentication, "not-a-number", "7", "0", "20"));
        assertThrows(IllegalArgumentException.class, () -> controller.readCandidates(
                authentication, "81", "not-a-number", "0", "20"));
        assertThrows(IllegalArgumentException.class, () -> controller.readCandidates(
                authentication, "81", null, "0", "20"),
                "the explicitly selected City is mandatory");
        assertThrows(IllegalArgumentException.class, () -> controller.readCandidates(
                authentication, "81", "  ", "0", "20"),
                "a blank City is never implicitly defaulted");
        assertThrows(IllegalArgumentException.class, () -> controller.route(authentication, "81", null));
        assertThrows(IllegalArgumentException.class, () -> controller.route(
                authentication, "not-a-number",
                new InitialVisitRequestRoutingCommand(3L, 7L, 1L, 12L, 0L, null, "INITIAL_ROUTING")));
        verifyNoInteractions(ownership);
    }

    @Test
    void missingVerifiedIdentityFailsBeforeAnyServiceCall() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "staff@example.test", null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));

        assertThrows(AccessDeniedException.class, () -> controller.listCandidates(authentication, "0", "20"));
        assertThrows(AccessDeniedException.class, () -> controller.readCandidates(authentication, "81", "7", "0", "20"));
        assertThrows(AccessDeniedException.class, () -> controller.route(authentication, "81",
                new InitialVisitRequestRoutingCommand(3L, 7L, 1L, 12L, 0L, null, "INITIAL_ROUTING")));
        verifyNoInteractions(reads, ownership);
    }

    private static UsernamePasswordAuthenticationToken authentication(Long userId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "staff@example.test", null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
        authentication.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), userId));
        return authentication;
    }
}
