package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.ExpectedVisitSessionVersion;
import com.indore.pathome.spaces.dto.VisitRepairQueueItem;
import com.indore.pathome.spaces.dto.VisitRepairQueuePage;
import com.indore.pathome.spaces.dto.VisitEntitlementRestoreCommand;
import com.indore.pathome.spaces.service.VisitRepairOperationsService;
import com.indore.pathome.spaces.service.VisitEntitlementOperationsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations/visit-repairs")
public class VisitRepairOperationsController {
    private final VisitRepairOperationsService repairs;
    private final VisitEntitlementOperationsService entitlements;
    public VisitRepairOperationsController(VisitRepairOperationsService repairs, VisitEntitlementOperationsService entitlements) {
        this.repairs = repairs;
        this.entitlements = entitlements;
    }

    @GetMapping
    public ResponseEntity<VisitRepairQueuePage> list(Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(repairs.list(VisitOperationsController.authenticatedUserId(authentication), page, size));
    }

    @PostMapping("/{sessionId}/reopen")
    public ResponseEntity<VisitRepairQueueItem> reopen(Authentication authentication, @PathVariable Long sessionId,
            @RequestBody ExpectedVisitSessionVersion command) {
        return ResponseEntity.ok(repairs.reopen(VisitOperationsController.authenticatedUserId(authentication), sessionId, command));
    }

    @PostMapping("/{sessionId}/entitlement-restore")
    public ResponseEntity<Void> restore(Authentication authentication, @PathVariable Long sessionId,
            @RequestBody VisitEntitlementRestoreCommand command) {
        entitlements.restoreInterruptedSession(VisitOperationsController.authenticatedUserId(authentication), sessionId, command);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sessionId}/interruption")
    public ResponseEntity<Void> interruption(Authentication authentication, @PathVariable Long sessionId,
            @RequestBody VisitEntitlementRestoreCommand command) {
        entitlements.documentInterruption(VisitOperationsController.authenticatedUserId(authentication), sessionId, command);
        return ResponseEntity.noContent().build();
    }
}
