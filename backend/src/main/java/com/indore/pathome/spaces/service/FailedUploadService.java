package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.MediaUploadFailure;
import com.indore.pathome.spaces.exception.MediaUploadException;
import com.indore.pathome.spaces.repository.MediaUploadFailureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of persistent failed-upload records.
 *
 * <p>All write operations run in {@code REQUIRES_NEW} transactions so that
 * failure records are committed independently of the caller's (possibly
 * rolling-back) transaction.</p>
 *
 * <p>An in-process {@code ConcurrentHashMap} cache maps
 * {@code uploadRequestId → failureId} to provide O(1) duplicate detection
 * before any DB hit.</p>
 */
@Service
public class FailedUploadService {

    private static final Logger log = LoggerFactory.getLogger(FailedUploadService.class);

    static final List<String> UNRESOLVED_STATUSES = List.of("FAILED", "RETRYING");
    private static final int ADMIN_PAGE_SIZE = 200;

    /** Context object passed by callers to avoid long parameter lists. */
    public record UploadFailureContext(
            Long listingId,
            String uploadRequestId,
            String mediaType,
            String originalFilename,
            Long fileSizeBytes,
            String roomTag,
            MediaUploadException.Stage stage,
            String safeReason,
            String diagnostic
    ) {}

    private final MediaUploadFailureRepository repository;

    /** uploadRequestId → failure primary key (L1 cache, cleared on resolution). */
    private final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<>();

    public FailedUploadService(MediaUploadFailureRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    /**
     * Persists a failure record. If a record for the same {@code uploadRequestId} already
     * exists, the existing record's retry count is incremented and status reset to FAILED.
     *
     * <p>Runs in its own transaction so that callers whose transactions are rolling back
     * still get the failure record committed.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MediaUploadFailure recordFailure(UploadFailureContext ctx) {
        try {
            if (ctx.uploadRequestId() != null) {
                Long cachedId = cache.get(ctx.uploadRequestId());
                if (cachedId != null) {
                    return incrementRetryAndReset(cachedId, ctx.safeReason(), ctx.diagnostic());
                }
                Optional<MediaUploadFailure> existing = repository.findByUploadRequestId(ctx.uploadRequestId());
                if (existing.isPresent()) {
                    cache.put(ctx.uploadRequestId(), existing.get().getId());
                    return incrementRetryAndReset(existing.get().getId(), ctx.safeReason(), ctx.diagnostic());
                }
            }

            MediaUploadFailure failure = new MediaUploadFailure();
            failure.setUploadRequestId(ctx.uploadRequestId());
            failure.setListingId(ctx.listingId());
            failure.setMediaType(ctx.mediaType() != null ? ctx.mediaType() : "IMAGE");
            failure.setOriginalFilename(ctx.originalFilename() != null ? ctx.originalFilename() : "");
            failure.setFileSizeBytes(ctx.fileSizeBytes());
            failure.setRoomTag(ctx.roomTag());
            failure.setFailureStage(ctx.stage() != null ? ctx.stage().name() : "CLOUDINARY_UPLOAD");
            failure.setFailureReason(ctx.safeReason());
            failure.setInternalDiagnostic(ctx.diagnostic());
            failure.setStatus("FAILED");
            MediaUploadFailure saved = repository.saveAndFlush(failure);

            if (ctx.uploadRequestId() != null) {
                cache.put(ctx.uploadRequestId(), saved.getId());
            }
            return saved;
        } catch (Exception e) {
            log.warn("Failed to persist upload failure record (non-critical): {}", e.getMessage());
            return new MediaUploadFailure(); // sentinel — caller must not depend on the returned id
        }
    }

    /**
     * Sets status = RETRYING before a retry attempt.
     * Concurrent callers that also try to retry see RETRYING and should abort.
     *
     * @return true if the transition succeeded (caller should proceed with upload),
     *         false if the record is already RETRYING or RESOLVED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetrying(Long failureId) {
        Optional<MediaUploadFailure> opt = repository.findById(failureId);
        if (opt.isEmpty()) return false;
        MediaUploadFailure failure = opt.get();
        if ("RETRYING".equals(failure.getStatus()) || "RESOLVED".equals(failure.getStatus())) {
            return false;
        }
        failure.setStatus("RETRYING");
        repository.saveAndFlush(failure);
        return true;
    }

    /**
     * Records a successful resolution after a retry or a re-upload.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResolution(Long failureId, String resolvedUrl) {
        repository.findById(failureId).ifPresent(failure -> {
            failure.setStatus("RESOLVED");
            failure.setResolvedMediaUrl(resolvedUrl);
            repository.saveAndFlush(failure);
            if (failure.getUploadRequestId() != null) {
                cache.remove(failure.getUploadRequestId());
            }
        });
    }

    /**
     * Looks up the failure id for a given {@code uploadRequestId}, using the L1 cache first.
     */
    public Optional<Long> findFailureIdByUploadRequestId(String uploadRequestId) {
        if (uploadRequestId == null) return Optional.empty();
        Long cached = cache.get(uploadRequestId);
        if (cached != null) return Optional.of(cached);
        return repository.findByUploadRequestId(uploadRequestId).map(MediaUploadFailure::getId);
    }

    /**
     * Admin dismiss — marks record as DISMISSED so it no longer appears in the default view.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dismiss(Long failureId) {
        repository.findById(failureId).ifPresent(failure -> {
            failure.setStatus("DISMISSED");
            repository.saveAndFlush(failure);
            if (failure.getUploadRequestId() != null) {
                cache.remove(failure.getUploadRequestId());
            }
        });
    }

    /**
     * Reverts a RETRYING record back to FAILED (used when a retry attempt itself fails).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRetryFailure(Long failureId, String newReason, String newDiagnostic) {
        repository.findById(failureId).ifPresent(failure -> {
            failure.setStatus("FAILED");
            failure.setRetryCount(failure.getRetryCount() + 1);
            failure.setFailureReason(newReason);
            failure.setInternalDiagnostic(newDiagnostic);
            repository.saveAndFlush(failure);
        });
    }

    /** Returns unresolved failures for admin display — bounded, never findAll(). */
    @Transactional(readOnly = true)
    public List<MediaUploadFailure> getUnresolved() {
        return repository.findByStatusInOrderByCreatedAtDesc(
                UNRESOLVED_STATUSES,
                PageRequest.of(0, ADMIN_PAGE_SIZE)
        );
    }

    /** Returns count of unresolved failures for the nav-item badge. */
    @Transactional(readOnly = true)
    public long countUnresolved() {
        return repository.countByStatusIn(UNRESOLVED_STATUSES);
    }

    /** Returns all failures for a given listing. */
    @Transactional(readOnly = true)
    public List<MediaUploadFailure> getByListingId(Long listingId) {
        return repository.findByListingIdOrderByCreatedAtDesc(listingId);
    }

    /** Returns a single failure record. */
    @Transactional(readOnly = true)
    public Optional<MediaUploadFailure> getById(Long failureId) {
        return repository.findById(failureId);
    }

    // ---- Private helpers ----

    private MediaUploadFailure incrementRetryAndReset(Long id, String newReason, String newDiagnostic) {
        MediaUploadFailure failure = repository.findById(id).orElseThrow();
        failure.setStatus("FAILED");
        failure.setRetryCount(failure.getRetryCount() + 1);
        failure.setFailureReason(newReason);
        failure.setInternalDiagnostic(newDiagnostic);
        return repository.saveAndFlush(failure);
    }
}
