package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.entity.SearchAlias;
import com.indore.pathome.spaces.entity.SearchAliasCandidate;
import com.indore.pathome.spaces.repository.SearchAliasCandidateRepository;
import com.indore.pathome.spaces.repository.SearchAliasRepository;
import com.indore.pathome.spaces.service.SearchLearningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Controlled administrative operations for search learning governance.
 * Secured under /api/v1/admin/** and requires ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/admin/search-learning")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSearchLearningController {

    private final SearchLearningService searchLearningService;
    private final SearchAliasCandidateRepository candidateRepository;
    private final SearchAliasRepository aliasRepository;

    @Autowired
    public AdminSearchLearningController(
            SearchLearningService searchLearningService,
            SearchAliasCandidateRepository candidateRepository,
            SearchAliasRepository aliasRepository) {
        this.searchLearningService = searchLearningService;
        this.candidateRepository = candidateRepository;
        this.aliasRepository = aliasRepository;
    }

    @GetMapping("/candidates")
    public ResponseEntity<List<SearchAliasCandidate>> getCandidates(
            @RequestParam(required = false) String status) {
        if (status != null && !status.isBlank()) {
            return ResponseEntity.ok(candidateRepository.findByStatus(status.trim().toUpperCase()));
        }
        return ResponseEntity.ok(candidateRepository.findAll());
    }

    @PostMapping("/candidates/{id}/approve")
    public ResponseEntity<SearchAlias> approveCandidate(
            @PathVariable Long id,
            Principal principal) {
        String adminUser = principal != null ? principal.getName() : "ADMIN";
        SearchAlias alias = searchLearningService.approveCandidate(id, adminUser);
        return ResponseEntity.ok(alias);
    }

    @PostMapping("/candidates/{id}/reject")
    public ResponseEntity<SearchAliasCandidate> rejectCandidate(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Principal principal) {
        String adminUser = principal != null ? principal.getName() : "ADMIN";
        String reason = body != null ? body.get("reason") : "Administrative rejection";
        SearchAliasCandidate candidate = searchLearningService.rejectCandidate(id, adminUser, reason);
        return ResponseEntity.ok(candidate);
    }

    @PostMapping("/aliases/{id}/disable")
    public ResponseEntity<SearchAlias> disableAlias(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : "Administrative rollback";
        SearchAlias alias = searchLearningService.disableAlias(id, reason);
        return ResponseEntity.ok(alias);
    }

    @GetMapping("/metrics")
    public ResponseEntity<SearchLearningService.SearchLearningMetrics> getMetrics() {
        return ResponseEntity.ok(searchLearningService.getMetrics());
    }

    @PostMapping("/aggregate")
    public ResponseEntity<Map<String, String>> triggerAggregation() {
        searchLearningService.aggregateCandidates();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Candidate aggregation executed"));
    }

    @PostMapping("/refresh-cache")
    public ResponseEntity<Map<String, String>> triggerCacheRefresh() {
        searchLearningService.refreshAliasCache();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Alias cache refreshed"));
    }
}
