package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.ParserModelRegistrationRequest;
import com.indore.pathome.spaces.dto.ParserModelVersionDTO;
import com.indore.pathome.spaces.entity.ParserModelStatus;
import com.indore.pathome.spaces.service.ParserModelRegistryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/parser-models")
@PreAuthorize("hasRole('ADMIN')")
public class ParserModelController {

    private static final int MAX_PAGE_SIZE = 100;
    private final ParserModelRegistryService registryService;

    public ParserModelController(ParserModelRegistryService registryService) {
        this.registryService = Objects.requireNonNull(registryService);
    }

    @PostMapping("/candidates")
    public ResponseEntity<ParserModelVersionDTO> registerCandidate(
            @RequestBody ParserModelRegistrationRequest request) {
        return ResponseEntity.ok(registryService.registerCandidate(request));
    }

    @PostMapping("/{version}/shadow")
    public ResponseEntity<ParserModelVersionDTO> promoteToShadow(@PathVariable String version) {
        return ResponseEntity.ok(registryService.promoteToShadow(version));
    }

    @GetMapping
    public ResponseEntity<Page<ParserModelVersionDTO>> versions(
            @RequestParam(defaultValue = "CANDIDATE") ParserModelStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        if (size < 1) throw new IllegalArgumentException("Page size must be greater than zero");
        return ResponseEntity.ok(registryService.versions(
                status, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE))));
    }
}
