package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.*;
import com.indore.pathome.spaces.service.VisitExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenant/visit-sessions")
public class TenantVisitSessionExecutionController {
    private final VisitExecutionService execution;
    public TenantVisitSessionExecutionController(VisitExecutionService execution) { this.execution = execution; }

    @GetMapping
    public ResponseEntity<TenantVisitExecutionPage> list(Authentication authentication,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(execution.listTenantSessions(
                VisitOperationsController.authenticatedUserId(authentication), page));
    }

    @PostMapping("/{sessionId}/start-code")
    public ResponseEntity<VisitStartCodeView> startCode(Authentication authentication, @PathVariable Long sessionId) {
        return ResponseEntity.ok(execution.issueStartCode(
                VisitOperationsController.authenticatedUserId(authentication), sessionId));
    }

    @PostMapping("/{sessionId}/confirmation")
    public ResponseEntity<VisitExecutionView> confirm(Authentication authentication, @PathVariable Long sessionId,
            @RequestBody TenantVisitConfirmationCommand command) {
        return ResponseEntity.ok(execution.confirmTenant(
                VisitOperationsController.authenticatedUserId(authentication), sessionId, command));
    }
}
