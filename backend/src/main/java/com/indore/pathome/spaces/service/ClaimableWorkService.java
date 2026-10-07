package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ClaimableWorkItem;
import com.indore.pathome.spaces.dto.ClaimableWorkPage;
import com.indore.pathome.spaces.dto.ClaimableWorkTargetType;
import com.indore.pathome.spaces.repository.ClaimableWorkRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimableWorkService {
    private static final int MAX_PAGE_SIZE = 100;

    private final ClaimableWorkRepository repository;

    public ClaimableWorkService(ClaimableWorkRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ClaimableWorkPage list(Long userId, int page, int size) {
        requireUser(userId);
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("Size must be between 1 and 100");
        long offset = Math.multiplyExact((long) page, (long) size);
        ClaimableWorkRepository.Snapshot snapshot = repository.page(userId, offset, size);
        requireEligible(snapshot.callerEligible());
        long totalPages = snapshot.totalCount() == 0 ? 0 : ((snapshot.totalCount() - 1) / size) + 1;
        return new ClaimableWorkPage(snapshot.items(), snapshot.totalCount(), page, size, totalPages);
    }

    @Transactional(readOnly = true)
    public ClaimableWorkItem refresh(Long userId, ClaimableWorkTargetType targetType, Long targetId) {
        requireUser(userId);
        if (targetType == null) throw new IllegalArgumentException("Target type is required");
        if (targetId == null || targetId <= 0) throw new IllegalArgumentException("Target ID must be positive");
        ClaimableWorkRepository.MetadataSnapshot snapshot = repository.metadata(userId, targetType, targetId);
        requireEligible(snapshot.callerEligible());
        if (snapshot.item() == null) throw new EntityNotFoundException("Claimable work not found");
        return snapshot.item();
    }

    private static void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new AccessDeniedException("Authenticated User identity required");
    }

    private static void requireEligible(boolean eligible) {
        if (!eligible) throw new AccessDeniedException("Current OPS_COORDINATE Team authority is required");
    }
}
