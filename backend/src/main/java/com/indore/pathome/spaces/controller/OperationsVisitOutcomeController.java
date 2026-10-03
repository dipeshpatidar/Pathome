package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.CorrectVisitOutcomeCommand;
import com.indore.pathome.spaces.dto.OperationsVisitOutcomeDetailView;
import com.indore.pathome.spaces.dto.VisitSessionOutcomeExceptionView;
import com.indore.pathome.spaces.service.VisitSessionOutcomeService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations/visit-outcomes")
public class OperationsVisitOutcomeController {
    private final VisitSessionOutcomeService outcomes;

    public OperationsVisitOutcomeController(VisitSessionOutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    @GetMapping("/exceptions")
    public ResponseEntity<Page<VisitSessionOutcomeExceptionView>> exceptions(Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(outcomes.listOperationsOutcomeExceptions(
                VisitOperationsController.authenticatedUserId(authentication), page, size));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<OperationsVisitOutcomeDetailView> detail(Authentication authentication,
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(outcomes.getOperationsOutcomeDetail(
                VisitOperationsController.authenticatedUserId(authentication), sessionId));
    }

    @PostMapping("/{sessionId}/corrections")
    public ResponseEntity<OperationsVisitOutcomeDetailView> correct(Authentication authentication,
            @PathVariable Long sessionId, @RequestBody CorrectVisitOutcomeCommand command) {
        return ResponseEntity.ok(outcomes.correctOutcome(
                VisitOperationsController.authenticatedUserId(authentication), sessionId, command));
    }
}
