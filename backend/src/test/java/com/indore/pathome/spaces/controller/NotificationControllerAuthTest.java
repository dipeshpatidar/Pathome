package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.NotificationResponseDto;
import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.security.PathomeAuthenticationDetails;
import com.indore.pathome.spaces.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationControllerAuthTest {

    private static final Long USER_A_ID = 42L;
    private static final Long USER_B_ID = 99L;

    private NotificationService notificationService;
    private NotificationController controller;
    private Authentication authA;
    private Authentication authBWithSpoofedName;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        controller = new NotificationController(notificationService);
        authA = authentication("lessor.a@pathome.in", USER_A_ID);
        // The signed principal name is deliberately misleading; only the verified JWT user ID is authoritative.
        authBWithSpoofedName = authentication("lessor.a@pathome.in", USER_B_ID);
    }

    @Test
    void authenticatedUserReceivesOwnNotificationAndRecipientInternalsAreOmitted() {
        SystemNotification notification = new SystemNotification(TargetRole.LANDLORD, "42",
                "Listing update", "Your listing was reviewed", "Review the requested changes",
                "PROPERTY", "info");
        notification.setId(7L);
        notification.setActionTarget("/lessor/listings/71");
        when(notificationService.getNotificationsForUser("42")).thenReturn(List.of(notification));

        ResponseEntity<List<NotificationResponseDto>> response = controller.getNotifications(authA);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        NotificationResponseDto item = response.getBody().get(0);
        assertEquals("Listing update", item.title());
        assertEquals("Review the requested changes", item.details());
        assertEquals("/lessor/listings/71", item.actionTarget());
        List<String> exposedFields = java.util.Arrays.stream(NotificationResponseDto.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertFalse(exposedFields.contains("recipientUserId"));
        assertFalse(exposedFields.contains("targetRole"));
        assertFalse(exposedFields.contains("eventKey"));
        verify(notificationService).getNotificationsForUser("42");
        verifyNoMoreInteractions(notificationService);
    }

    @Test
    void anotherUserCannotSelectRecipientWithNameOrRoleInput() {
        when(notificationService.getNotificationsForUser("99")).thenReturn(List.of());

        ResponseEntity<List<NotificationResponseDto>> response = controller.getNotifications(authBWithSpoofedName);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isEmpty());
        verify(notificationService).getNotificationsForUser("99");
        verify(notificationService, never()).getNotificationsForUser("42");
    }

    @Test
    void unreadCountAndReadAllUseVerifiedPrincipalId() {
        when(notificationService.getUnreadCountForUser("42")).thenReturn(2L);
        when(notificationService.markAllAsReadForUser("42")).thenReturn(2);

        ResponseEntity<Map<String, Object>> unread = controller.getUnreadCount(authA);
        ResponseEntity<Map<String, Object>> readAll = controller.markAllAsRead(authA);

        assertEquals(2L, unread.getBody().get("unreadCount"));
        assertEquals(2, readAll.getBody().get("count"));
        verify(notificationService).getUnreadCountForUser("42");
        verify(notificationService).markAllAsReadForUser("42");
    }

    @Test
    void recipientNavigationRemainsAvailableWithoutExposingRoleOrEventMetadata() {
        SystemNotification tenantVisit = new SystemNotification(TargetRole.TENANT, "42",
                "Visit session updated", "Your scheduled visit changed", null,
                "VISIT_SESSION", "info");
        SystemNotification lessorWorkflow = new SystemNotification(TargetRole.LANDLORD, "42",
                "Changes requested", "Review the listing changes", null, "PROPERTY", "info");
        lessorWorkflow.setListingId(71L);
        lessorWorkflow.setEventKey("CHANGES_REQUIRED:listing:71");

        assertEquals("/tenant#visit-history", NotificationResponseDto.from(tenantVisit).actionTarget());
        assertEquals("/lessor/listings/71", NotificationResponseDto.from(lessorWorkflow).actionTarget());
    }

    @Test
    void authenticatedPrincipalWithoutVerifiedUserIdIsRejected() {
        Authentication authWithoutIdentity = new UsernamePasswordAuthenticationToken("user", null, List.of());
        assertThrows(AccessDeniedException.class, () -> controller.getNotifications(authWithoutIdentity));
        verifyNoInteractions(notificationService);
    }

    private static Authentication authentication(String principalName, Long userId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principalName, null, List.of());
        HttpServletRequest request = new MockHttpServletRequest();
        authentication.setDetails(new PathomeAuthenticationDetails(request, userId));
        return authentication;
    }
}
