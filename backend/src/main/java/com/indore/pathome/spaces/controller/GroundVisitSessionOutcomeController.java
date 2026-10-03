package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.CompleteVisitSessionWithOutcomesCommand;
import com.indore.pathome.spaces.dto.GroundPendingVisitOutcomeView;
import com.indore.pathome.spaces.dto.GroundVisitSessionOutcomeView;
import com.indore.pathome.spaces.dto.RecordVisitSessionItemOutcomeCommand;
import com.indore.pathome.spaces.service.VisitSessionOutcomeCompletionService;
import com.indore.pathome.spaces.service.VisitSessionOutcomeService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ground/visit-sessions")
public class GroundVisitSessionOutcomeController {
    private final VisitSessionOutcomeService outcomes;
    private final VisitSessionOutcomeCompletionService completion;

    public GroundVisitSessionOutcomeController(VisitSessionOutcomeService outcomes,
            VisitSessionOutcomeCompletionService completion) {
        this.outcomes = outcomes;
        this.completion = completion;
    }

    @GetMapping("/{sessionId}/outcome-report")
    public ResponseEntity<GroundVisitSessionOutcomeView> getReport(Authentication authentication,
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(outcomes.getGroundOutcomeReport(
                VisitOperationsController.authenticatedUserId(authentication), sessionId));
    }

    @PutMapping("/{sessionId}/items/{itemId}/outcome")
    public ResponseEntity<GroundVisitSessionOutcomeView> recordOutcome(Authentication authentication,
            @PathVariable Long sessionId, @PathVariable Long itemId,
            @RequestBody RecordVisitSessionItemOutcomeCommand command) {
        return ResponseEntity.ok(outcomes.recordItemOutcome(
                VisitOperationsController.authenticatedUserId(authentication), sessionId, itemId, command));
    }

    @PostMapping("/{sessionId}/complete-with-outcomes")
    public ResponseEntity<GroundVisitSessionOutcomeView> complete(Authentication authentication,
            @PathVariable Long sessionId, @RequestBody CompleteVisitSessionWithOutcomesCommand command) {
        return ResponseEntity.ok(completion.complete(
                VisitOperationsController.authenticatedUserId(authentication), sessionId, command));
    }

    @GetMapping("/pending-outcomes")
    public ResponseEntity<Page<GroundPendingVisitOutcomeView>> pendingOutcomes(Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(outcomes.listGroundPendingOutcomes(
                VisitOperationsController.authenticatedUserId(authentication), page, size));
    }
}
