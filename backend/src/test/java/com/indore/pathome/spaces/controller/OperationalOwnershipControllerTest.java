package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.VisitRequestOwnershipCommand;
import com.indore.pathome.spaces.dto.VisitSessionOwnershipCommand;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.service.OperationalOwnershipService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OperationalOwnershipControllerTest {
    private final OperationalOwnershipService ownership = mock(OperationalOwnershipService.class);
    private final OperationalOwnershipController controller = new OperationalOwnershipController(ownership);

    @Test
    void requestClaimUsesVerifiedUserIdentityAndForwardsVersionedRootState() {
        var authentication = authentication(42L);
        var command = new VisitRequestOwnershipCommand(7L, null, null, null, null, "CLAIM_WORK");

        assertEquals(204, controller.claimRequest(authentication, 81L, command).getStatusCode().value());

        verify(ownership).claimRequest(42L, 81L, 7L, null, null, "CLAIM_WORK");
    }

    @Test
    void sessionTransferUsesVerifiedIdentityAndForwardsLinkedRequestVersions() {
        var authentication = authentication(43L);
        var requestVersions = Map.of(81L, 3L, 82L, 5L);
        var command = new VisitSessionOwnershipCommand(9L, requestVersions, 44L, 12L, "TRANSFER_TEAM");

        assertEquals(204, controller.transferSessionTeam(authentication, 90L, command).getStatusCode().value());

        verify(ownership).transferTeam(43L, 90L, 9L, requestVersions, 12L, 44L, "TRANSFER_TEAM");
    }

    @Test
    void missingVerifiedIdentityFailsBeforeOwnershipMutation() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "staff@example.test", null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));

        assertThrows(AccessDeniedException.class, () -> controller.claimSession(authentication, 90L,
                new VisitSessionOwnershipCommand(9L, Map.of(), null, null, "CLAIM_WORK")));
    }

    private static UsernamePasswordAuthenticationToken authentication(Long userId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "staff@example.test", null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
        authentication.setDetails(new PathomeAuthenticationDetails(new MockHttpServletRequest(), userId));
        return authentication;
    }
}
