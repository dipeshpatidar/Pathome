package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.MediaUploadFailure;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MediaUploadFailureRepository extends JpaRepository<MediaUploadFailure, Long> {

    /** Admin list — bounded by Pageable, never a full table scan. */
    List<MediaUploadFailure> findByStatusInOrderByCreatedAtDesc(List<String> statuses, Pageable pageable);

    /** All failures for a specific listing, newest first. */
    List<MediaUploadFailure> findByListingIdOrderByCreatedAtDesc(Long listingId);

    /** Idempotency check — O(1) via unique index. */
    Optional<MediaUploadFailure> findByUploadRequestId(String uploadRequestId);

    /** Count of unresolved (FAILED + RETRYING) failures for badge display. */
    long countByStatusIn(List<String> statuses);

    /** Atomic status transition FAILED -> RETRYING to prevent concurrent retry races. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE MediaUploadFailure m SET m.status = 'RETRYING', m.updatedAt = CURRENT_TIMESTAMP WHERE m.id = :id AND m.status = 'FAILED'")
    int markRetryingIfFailed(@org.springframework.data.repository.query.Param("id") Long id);
}
