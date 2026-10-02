package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.GroundVisitSessionPage;
import com.indore.pathome.spaces.dto.GroundVisitSessionView;
import com.indore.pathome.spaces.service.VisitOperationsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ground/visit-sessions")
public class GroundVisitSessionController {
    private final VisitOperationsService operations;

    public GroundVisitSessionController(VisitOperationsService operations) {
        this.operations = operations;
    }

    @GetMapping
    public ResponseEntity<GroundVisitSessionPage> listAssigned(Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(operations.listAssignedSessions(
                VisitOperationsController.authenticatedUserId(authentication), page, size));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<GroundVisitSessionView> getAssigned(Authentication authentication,
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(operations.getAssignedSession(
                VisitOperationsController.authenticatedUserId(authentication), sessionId));
    }
}
