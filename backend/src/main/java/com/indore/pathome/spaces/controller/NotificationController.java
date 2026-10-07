package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.NotificationResponseDto;
import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.security.PathomeAuthenticationIdentity;
import com.indore.pathome.spaces.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponseDto>> getNotifications(
            Authentication auth
    ) {
        String recipientUserId = String.valueOf(PathomeAuthenticationIdentity.requireUserId(auth));
        List<SystemNotification> list = notificationService.getNotificationsForUser(recipientUserId);
        List<NotificationResponseDto> dtos = list.stream().map(NotificationResponseDto::from).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Retrieves truthful unread count for the authenticated user.
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(Authentication auth) {
        String recipientUserId = String.valueOf(PathomeAuthenticationIdentity.requireUserId(auth));
        long count = notificationService.getUnreadCountForUser(recipientUserId);
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    /**
     * Marks a specific notification as read, enforcing that the notification belongs
     * to the requesting authenticated user.
     */
    @PutMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markAsRead(Authentication auth, @PathVariable("id") Long id) {
        String recipientUserId = String.valueOf(PathomeAuthenticationIdentity.requireUserId(auth));
        boolean success = notificationService.markAsReadForUser(id, recipientUserId);
        return ResponseEntity.ok(Map.of("success", success, "id", id));
    }

    /**
     * Marks all notifications as read for the authenticated user.
     */
    @PutMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllAsRead(Authentication auth) {
        String recipientUserId = String.valueOf(PathomeAuthenticationIdentity.requireUserId(auth));
        int count = notificationService.markAllAsReadForUser(recipientUserId);
        return ResponseEntity.ok(Map.of("success", true, "count", count));
    }

    @PostMapping("/mark-all-read")
    public ResponseEntity<Map<String, Object>> postMarkAllAsRead(Authentication auth) {
        return markAllAsRead(auth);
    }

    /**
     * Privileged endpoint for administrative notices. Domain workflow notifications
     * continue to be created by backend services.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<NotificationResponseDto> createNotification(@RequestBody Map<String, Object> payload) {
        String roleStr = (String) payload.getOrDefault("targetRole", "ALL");
        TargetRole targetRole;
        try {
            targetRole = TargetRole.valueOf(roleStr.toUpperCase());
        } catch (Exception e) {
            targetRole = TargetRole.ALL;
        }

        String recipientUserId = (String) payload.get("recipientUserId");
        String title = (String) payload.getOrDefault("title", "System Notification");
        String message = (String) payload.getOrDefault("message", "");
        String details = (String) payload.get("details");
        String category = (String) payload.getOrDefault("category", "SYSTEM");
        String type = (String) payload.getOrDefault("type", "info");

        SystemNotification created = notificationService.createAdminNotification(targetRole, recipientUserId, title,
                message, details, category, type);
        return ResponseEntity.ok(NotificationResponseDto.from(created));
    }
}
