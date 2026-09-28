package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.NotificationResponseDto;
import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class NotificationControllerAuthTest {

    private NotificationService notificationService;
    private UserRepository userRepository;
    private NotificationController controller;

    private static final String LESSOR_A_EMAIL = "lessor.a@pathome.in";
    private static final Long LESSOR_A_ID = 42L;

    private static final String LESSOR_B_EMAIL = "lessor.b@pathome.in";
    private static final Long LESSOR_B_ID = 99L;

    private Authentication authA;
    private Authentication authB;

    @BeforeEach
    public void setUp() {
        notificationService = mock(NotificationService.class);
        userRepository = mock(UserRepository.class);
        controller = new NotificationController(notificationService, userRepository);

        User userA = new User();
        userA.setId(LESSOR_A_ID);
        userA.setEmail(LESSOR_A_EMAIL);
        when(userRepository.findByEmail(LESSOR_A_EMAIL)).thenReturn(Optional.of(userA));

        User userB = new User();
        userB.setId(LESSOR_B_ID);
        userB.setEmail(LESSOR_B_EMAIL);
        when(userRepository.findByEmail(LESSOR_B_EMAIL)).thenReturn(Optional.of(userB));

        authA = new UsernamePasswordAuthenticationToken(LESSOR_A_EMAIL, "pass", List.of());
        authB = new UsernamePasswordAuthenticationToken(LESSOR_B_EMAIL, "pass", List.of());
    }

    @Test
    @DisplayName("1. Authenticated user receives only their own notifications")
    public void testAuthenticatedUserReceivesOnlyOwnNotifications() {
        SystemNotification notifA = new SystemNotification();
        notifA.setId(1L);
        notifA.setRecipientUserId(String.valueOf(LESSOR_A_ID));
        notifA.setTitle("Updates needed");

        when(notificationService.getNotificationsForUser(String.valueOf(LESSOR_A_ID)))
                .thenReturn(List.of(notifA));

        ResponseEntity<List<NotificationResponseDto>> response = controller.getNotifications(authA, "LANDLORD");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals(String.valueOf(LESSOR_A_ID), response.getBody().get(0).recipientUserId());
        assertEquals("Updates needed", response.getBody().get(0).title());

        verify(notificationService).getNotificationsForUser(String.valueOf(LESSOR_A_ID));
        verify(notificationService, never()).getNotificationsForUser(String.valueOf(LESSOR_B_ID));
    }

    @Test
    @DisplayName("2. Unread count is scoped strictly to authenticated user")
    public void testUnreadCountScopedStrictlyToUser() {
        when(notificationService.getUnreadCountForUser(String.valueOf(LESSOR_A_ID))).thenReturn(3L);

        ResponseEntity<Map<String, Object>> response = controller.getUnreadCount(authA);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(3L, response.getBody().get("unreadCount"));

        verify(notificationService).getUnreadCountForUser(String.valueOf(LESSOR_A_ID));
    }

    @Test
    @DisplayName("3. Unauthenticated request to unread count returns 401 Unauthorized")
    public void testUnauthenticatedUnreadCountReturns401() {
        ResponseEntity<Map<String, Object>> response = controller.getUnreadCount(null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @DisplayName("4. Marking another user's notification as read throws AccessDeniedException")
    public void testMarkingOtherUserNotificationThrowsAccessDenied() {
        // notification 10 belongs to User A, but User B tries to mark it as read
        when(notificationService.markAsReadForUser(10L, String.valueOf(LESSOR_B_ID)))
                .thenThrow(new AccessDeniedException("Cannot access another user's notification"));

        assertThrows(AccessDeniedException.class, () -> {
            controller.markAsRead(authB, 10L);
        });
    }

    @Test
    @DisplayName("5. Marking one's own notification as read succeeds")
    public void testMarkingOwnNotificationSucceeds() {
        when(notificationService.markAsReadForUser(10L, String.valueOf(LESSOR_A_ID))).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.markAsRead(authA, 10L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().get("success"));
        assertEquals(10L, response.getBody().get("id"));
    }

    @Test
    @DisplayName("6. Mark all as read affects only the authenticated user")
    public void testMarkAllAsReadAffectsOnlyCurrentUser() {
        when(notificationService.markAllAsReadForUser(String.valueOf(LESSOR_A_ID))).thenReturn(5);

        ResponseEntity<Map<String, Object>> response = controller.markAllAsRead(authA);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(5, response.getBody().get("count"));

        verify(notificationService).markAllAsReadForUser(String.valueOf(LESSOR_A_ID));
        verify(notificationService, never()).markAllAsReadForUser(String.valueOf(LESSOR_B_ID));
    }
}
