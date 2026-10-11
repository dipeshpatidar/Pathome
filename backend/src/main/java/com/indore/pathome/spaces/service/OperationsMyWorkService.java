package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.OperationsMyWorkPage;
import com.indore.pathome.spaces.dto.OperationsStandaloneRequestDetailView;
import com.indore.pathome.spaces.repository.OperationsMyWorkRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 2A-1 coordinator-owned read foundation.
 *
 * Endpoint eligibility (no current usable OPS_COORDINATE Team at all) is 403; a specific target that
 * is not currently owned by the caller is 404 so existence never leaks.
 */
@Service
public class OperationsMyWorkService {
    private static final int MAX_PAGE_SIZE = 100;

    private final OperationsMyWorkRepository repository;

    public OperationsMyWorkService(OperationsMyWorkRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public OperationsMyWorkPage list(Long userId, int page, int size, boolean attentionOnly) {
        requireUser(userId);
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("Size must be between 1 and 100");
        long offset = Math.multiplyExact((long) page, (long) size);
        OperationsMyWorkRepository.Snapshot snapshot = repository.page(userId, offset, size, attentionOnly);
        requireEligible(snapshot.callerEligible());
        long totalPages = snapshot.totalCount() == 0 ? 0 : ((snapshot.totalCount() - 1) / size) + 1;
        return new OperationsMyWorkPage(snapshot.items(), snapshot.totalCount(), page, size, totalPages);
    }

    @Transactional(readOnly = true)
    public OperationsStandaloneRequestDetailView requestDetail(Long userId, Long requestId) {
        requireUser(userId);
        if (requestId == null || requestId <= 0) throw new IllegalArgumentException("Visit Request ID must be positive");
        OperationsMyWorkRepository.DetailSnapshot snapshot = repository.requestDetail(userId, requestId);
        requireEligible(snapshot.callerEligible());
        if (snapshot.detail() == null) throw new EntityNotFoundException("Visit Request not found");
        return snapshot.detail();
    }

    private static void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new AccessDeniedException("Authenticated User identity required");
    }

    private static void requireEligible(boolean eligible) {
        if (!eligible) throw new AccessDeniedException("Current OPS_COORDINATE Team authority is required");
    }
}
