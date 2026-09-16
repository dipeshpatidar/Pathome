package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.ParserLearningExampleDTO;
import com.indore.pathome.spaces.dto.ParserLearningStatsDTO;
import com.indore.pathome.spaces.dto.ParserTrainingRecordDTO;
import com.indore.pathome.spaces.entity.ParserDatasetPartition;
import com.indore.pathome.spaces.service.ParserLearningService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/parser-learning")
@PreAuthorize("hasRole('ADMIN')")
public class ParserLearningController {

    private static final int MAX_PAGE_SIZE = 200;
    private final ParserLearningService parserLearningService;

    public ParserLearningController(ParserLearningService parserLearningService) {
        this.parserLearningService = Objects.requireNonNull(parserLearningService);
    }

    @GetMapping("/stats")
    public ResponseEntity<ParserLearningStatsDTO> stats() {
        return ResponseEntity.ok(parserLearningService.stats());
    }

    @GetMapping("/examples/pending")
    public ResponseEntity<Page<ParserLearningExampleDTO>> pendingExamples(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(parserLearningService.pendingExamples(pageRequest(page, size)));
    }

    @GetMapping("/examples/{id}")
    public ResponseEntity<ParserLearningExampleDTO> example(@PathVariable String id) {
        return ResponseEntity.ok(parserLearningService.getExample(id));
    }

    @PostMapping("/examples/{id}/approve")
    public ResponseEntity<ParserLearningExampleDTO> approve(@PathVariable String id) {
        return ResponseEntity.ok(parserLearningService.approveForTraining(id));
    }

    @PostMapping("/examples/{id}/reject")
    public ResponseEntity<ParserLearningExampleDTO> reject(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> request) {
        String reason = request != null ? request.get("reason") : null;
        return ResponseEntity.ok(parserLearningService.rejectExample(id, reason));
    }

    @GetMapping("/dataset")
    public ResponseEntity<Page<ParserTrainingRecordDTO>> dataset(
            @RequestParam(defaultValue = "TRAIN") ParserDatasetPartition partition,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(parserLearningService.approvedDataset(
                partition, pageRequest(page, size)));
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        if (size < 1) throw new IllegalArgumentException("Page size must be greater than zero");
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
    }
}
