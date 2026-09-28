package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.NotificationResponseDto;
import com.indore.pathome.spaces.entity.SystemNotification;
import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/notifications")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class NotificationController {

    private final NotificationService notificationService;
    private final UserRepository users;

    public NotificationController(NotificationService notificationService, UserRepository users) {
        this.notificationService = notificationService;
        this.users = users;
    }

    /**
     * Retrieves notifications. For authenticated users, strictly returns their own
     * notifications. For unauthenticated callers, returns role-scoped announcements.
     */
    @GetMapping
    public ResponseEntity<List<NotificationResponseDto>> getNotifications(
            Authentication auth,
            @RequestParam(value = "role", required = false, defaultValue = "ALL") String roleStr
    ) {
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            Optional<User> userOpt = users.findByEmail(auth.getName());
            if (userOpt.isPresent()) {
                String recipientUserId = String.valueOf(userOpt.get().getId());
                List<SystemNotification> list = notificationService.getNotificationsForUser(recipientUserId);
                List<NotificationResponseDto> dtos = list.stream().map(NotificationResponseDto::from).toList();
                return ResponseEntity.ok(dtos);
            }
        }

        TargetRole role;
        try {
            role = TargetRole.valueOf(roleStr.toUpperCase());
        } catch (Exception e) {
            role = TargetRole.ALL;
        }

        List<SystemNotification> list = notificationService.getNotificationsForRole(role, null);
        List<NotificationResponseDto> dtos = list.stream().map(NotificationResponseDto::from).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Retrieves truthful unread count for the authenticated user.
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = users.findByEmail(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("User not found"));
        long count = notificationService.getUnreadCountForUser(String.valueOf(user.getId()));
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    /**
     * Marks a specific notification as read, enforcing that the notification belongs
     * to the requesting authenticated user.
     */
    @PutMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markAsRead(Authentication auth, @PathVariable("id") Long id) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = users.findByEmail(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("User not found"));
        boolean success = notificationService.markAsReadForUser(id, String.valueOf(user.getId()));
        return ResponseEntity.ok(Map.of("success", success, "id", id));
    }

    /**
     * Marks all notifications as read for the authenticated user.
     */
    @PutMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllAsRead(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = users.findByEmail(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("User not found"));
        int count = notificationService.markAllAsReadForUser(String.valueOf(user.getId()));
        return ResponseEntity.ok(Map.of("success", true, "count", count));
    }

    @PostMapping("/mark-all-read")
    public ResponseEntity<Map<String, Object>> postMarkAllAsRead(Authentication auth) {
        return markAllAsRead(auth);
    }

    /**
     * Admin/System Endpoint to Dispatch a Role-Scoped Notification
     */
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

        SystemNotification created = notificationService.createNotification(targetRole, recipientUserId, title, message, details, category, type);
        return ResponseEntity.ok(NotificationResponseDto.from(created));
    }
}
