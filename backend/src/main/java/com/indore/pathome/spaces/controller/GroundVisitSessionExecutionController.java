package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.*;
import com.indore.pathome.spaces.service.VisitExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ground/visit-sessions")
public class GroundVisitSessionExecutionController {
    private final VisitExecutionService execution;
    public GroundVisitSessionExecutionController(VisitExecutionService execution) { this.execution = execution; }

    @GetMapping("/{sessionId}/execution")
    public ResponseEntity<VisitExecutionView> getExecution(Authentication authentication, @PathVariable Long sessionId) {
        return ResponseEntity.ok(execution.getAssignedExecution(
                VisitOperationsController.authenticatedUserId(authentication), sessionId));
    }

    @GetMapping("/{sessionId}/tenant-contact")
    public ResponseEntity<GroundVisitTenantContactView> tenantContact(Authentication authentication, @PathVariable Long sessionId) {
        return ResponseEntity.ok(execution.getTenantContact(
                VisitOperationsController.authenticatedUserId(authentication), sessionId));
    }

    @GetMapping("/{sessionId}/start-code-status")
    public ResponseEntity<GroundVisitStartCodeStatus> startCodeStatus(Authentication authentication, @PathVariable Long sessionId) {
        return ResponseEntity.ok(execution.getStartCodeStatus(
                VisitOperationsController.authenticatedUserId(authentication), sessionId));
    }

    @PostMapping("/{sessionId}/arrived")
    public ResponseEntity<VisitExecutionView> arrived(Authentication authentication, @PathVariable Long sessionId) {
        return ResponseEntity.ok(execution.markArrived(
                VisitOperationsController.authenticatedUserId(authentication), sessionId));
    }

    @PostMapping("/{sessionId}/contact-attempts")
    public ResponseEntity<VisitExecutionView> contact(Authentication authentication, @PathVariable Long sessionId,
            @RequestBody GroundVisitContactCommand command) {
        return ResponseEntity.ok(execution.reportContact(
                VisitOperationsController.authenticatedUserId(authentication), sessionId, command));
    }

    @PostMapping("/{sessionId}/provisional-no-show")
    public ResponseEntity<VisitExecutionView> provisionalNoShow(Authentication authentication, @PathVariable Long sessionId) {
        return ResponseEntity.ok(execution.markProvisionalNoShow(
                VisitOperationsController.authenticatedUserId(authentication), sessionId));
    }

    @PostMapping("/{sessionId}/start")
    public ResponseEntity<VisitExecutionView> start(Authentication authentication, @PathVariable Long sessionId,
            @RequestBody VisitOtpStartCommand command) {
        return ResponseEntity.ok(execution.start(
                VisitOperationsController.authenticatedUserId(authentication), sessionId, command));
    }

    @PostMapping("/{sessionId}/need-more-time")
    public ResponseEntity<VisitExecutionView> needMoreTime(Authentication authentication, @PathVariable Long sessionId,
            @RequestBody GroundVisitMoreTimeCommand command) {
        return ResponseEntity.ok(execution.needMoreTime(
                VisitOperationsController.authenticatedUserId(authentication), sessionId, command));
    }

    @PostMapping("/{sessionId}/finish")
    public ResponseEntity<VisitExecutionView> finish(Authentication authentication, @PathVariable Long sessionId) {
        return ResponseEntity.ok(execution.finish(
                VisitOperationsController.authenticatedUserId(authentication), sessionId));
    }
}
