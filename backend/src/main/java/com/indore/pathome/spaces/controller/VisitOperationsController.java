package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.*;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.service.VisitOperationsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations")
public class VisitOperationsController {
    private final VisitOperationsService operations;

    public VisitOperationsController(VisitOperationsService operations) {
        this.operations = operations;
    }

    @GetMapping("/visit-requests")
    public ResponseEntity<OperationsVisitRequestPage> listRequests(Authentication authentication,
            @RequestParam(required = false) VisitRequestStatus status,
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(operations.listRequests(authenticatedUserId(authentication), status, city, page, size));
    }

    @PostMapping("/visit-requests/{requestId}/coordinate")
    public ResponseEntity<OperationsVisitSessionView> coordinate(Authentication authentication,
            @PathVariable Long requestId, @RequestBody CoordinateVisitRequestCommand command) {
        return ResponseEntity.ok(operations.coordinateRequest(authenticatedUserId(authentication), requestId, command));
    }

    @PostMapping("/visit-requests/{requestId}/unavailable")
    public ResponseEntity<OperationsVisitRequestItem> unavailable(Authentication authentication,
            @PathVariable Long requestId, @RequestBody ExpectedVisitRequestVersion command) {
        return ResponseEntity.ok(operations.markRequestUnavailable(authenticatedUserId(authentication), requestId, command));
    }

    @GetMapping("/visit-sessions/{sessionId}")
    public ResponseEntity<OperationsVisitSessionView> getSession(Authentication authentication,
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(operations.getOperationsSession(authenticatedUserId(authentication), sessionId));
    }

    @PostMapping("/visit-sessions/{sessionId}/items")
    public ResponseEntity<OperationsVisitSessionView> addItem(Authentication authentication,
            @PathVariable Long sessionId, @RequestBody AddVisitSessionItemCommand command) {
        return ResponseEntity.ok(operations.addNearbyProperty(authenticatedUserId(authentication), sessionId, command));
    }

    @DeleteMapping("/visit-sessions/{sessionId}/items/{itemId}")
    public ResponseEntity<OperationsVisitSessionView> removeItem(Authentication authentication,
            @PathVariable Long sessionId, @PathVariable Long itemId,
            @RequestBody RemoveVisitSessionItemCommand command) {
        return ResponseEntity.ok(operations.removeItem(authenticatedUserId(authentication), sessionId, itemId, command));
    }

    @PutMapping("/visit-sessions/{sessionId}/items/order")
    public ResponseEntity<OperationsVisitSessionView> reorderItems(Authentication authentication,
            @PathVariable Long sessionId, @RequestBody ReorderVisitSessionItemsCommand command) {
        return ResponseEntity.ok(operations.reorderItems(authenticatedUserId(authentication), sessionId, command));
    }

    @PutMapping("/visit-sessions/{sessionId}/items/{itemId}/availability")
    public ResponseEntity<OperationsVisitSessionView> setAvailability(Authentication authentication,
            @PathVariable Long sessionId, @PathVariable Long itemId,
            @RequestBody VisitSessionAvailabilityCommand command) {
        return ResponseEntity.ok(operations.setAvailability(authenticatedUserId(authentication), sessionId, itemId, command));
    }

    @PostMapping("/visit-sessions/{sessionId}/schedule")
    public ResponseEntity<OperationsVisitSessionView> schedule(Authentication authentication,
            @PathVariable Long sessionId, @RequestBody ScheduleVisitSessionCommand command) {
        return ResponseEntity.ok(operations.schedule(authenticatedUserId(authentication), sessionId, command));
    }

    @PutMapping("/visit-sessions/{sessionId}/schedule")
    public ResponseEntity<OperationsVisitSessionView> reschedule(Authentication authentication,
            @PathVariable Long sessionId, @RequestBody RescheduleVisitSessionCommand command) {
        return ResponseEntity.ok(operations.reschedule(authenticatedUserId(authentication), sessionId, command));
    }

    @PostMapping("/visit-sessions/{sessionId}/assignment")
    public ResponseEntity<OperationsVisitSessionView> assignGroundExecutive(Authentication authentication,
            @PathVariable Long sessionId, @RequestBody AssignGroundExecutiveCommand command) {
        return ResponseEntity.ok(operations.assignGroundExecutive(authenticatedUserId(authentication), sessionId, command));
    }

    @PostMapping("/visit-sessions/{sessionId}/cancel")
    public ResponseEntity<OperationsVisitSessionView> cancel(Authentication authentication,
            @PathVariable Long sessionId, @RequestBody ExpectedVisitSessionVersion command) {
        if (command == null) throw new IllegalArgumentException("Request body is required");
        return ResponseEntity.ok(operations.cancel(authenticatedUserId(authentication), sessionId,
                command.expectedSessionVersion()));
    }

    static Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw new AccessDeniedException("Authentication required");
        }
        if (!(authentication.getDetails() instanceof com.indore.pathome.spaces.security.PathomeAuthenticationDetails details)
                || details.getUserId() == null || details.getUserId() <= 0) {
            throw new AccessDeniedException("Authenticated user identity unavailable");
        }
        return details.getUserId();
    }
}
