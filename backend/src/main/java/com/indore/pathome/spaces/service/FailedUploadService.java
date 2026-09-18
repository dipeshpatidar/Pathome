package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.MediaUploadFailure;
import com.indore.pathome.spaces.exception.MediaUploadException;
import com.indore.pathome.spaces.repository.MediaUploadFailureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
            String diagnostic,
            String storageUrl,
            String storagePublicId,
            String stagingObjectKey,
            java.time.LocalDateTime stagingExpiresAt,
            String cloudinaryFailureCategory,
            Integer providerStatusCode
    ) {
        /** Backward-compatible constructor for existing callers and tests. */
        public UploadFailureContext(
                Long listingId,
                String uploadRequestId,
                String mediaType,
                String originalFilename,
                Long fileSizeBytes,
                String roomTag,
                MediaUploadException.Stage stage,
                String safeReason,
                String diagnostic
        ) {
            this(listingId, uploadRequestId, mediaType, originalFilename, fileSizeBytes,
                    roomTag, stage, safeReason, diagnostic, null, null, null, null, null, null);
        }
    }

    private final MediaUploadFailureRepository repository;
    private final MediaStagingService mediaStagingService;

    private static final int MAX_CACHE_ENTRIES = 5000;

    /** uploadRequestId → failure primary key (L1 cache, cleared on resolution). */
    private final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<>();

    @Autowired
    public FailedUploadService(MediaUploadFailureRepository repository, MediaStagingService mediaStagingService) {
        this.repository = Objects.requireNonNull(repository);
        this.mediaStagingService = mediaStagingService;
    }

    public FailedUploadService(MediaUploadFailureRepository repository) {
        this(repository, null);
    }

    public MediaStagingService getMediaStagingService() {
        return mediaStagingService;
    }

    private void putInCache(String uploadRequestId, Long failureId) {
        if (uploadRequestId == null || failureId == null) return;
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(uploadRequestId, failureId);
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
                    return incrementRetryAndReset(cachedId, ctx);
                }
                Optional<MediaUploadFailure> existing = repository.findByUploadRequestId(ctx.uploadRequestId());
                if (existing.isPresent()) {
                    putInCache(ctx.uploadRequestId(), existing.get().getId());
                    return incrementRetryAndReset(existing.get().getId(), ctx);
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
            failure.setStorageUrl(ctx.storageUrl());
            failure.setStoragePublicId(ctx.storagePublicId());
            failure.setStagingObjectKey(ctx.stagingObjectKey());
            failure.setStagingExpiresAt(ctx.stagingExpiresAt());
            failure.setCloudinaryFailureCategory(ctx.cloudinaryFailureCategory());
            failure.setProviderStatusCode(ctx.providerStatusCode());
            failure.setStatus("FAILED");
            MediaUploadFailure saved = repository.saveAndFlush(failure);

            if (ctx.uploadRequestId() != null) {
                putInCache(ctx.uploadRequestId(), saved.getId());
            }
            return saved;
        } catch (Exception e) {
            log.warn("Failed to persist upload failure record (non-critical): {}", e.getMessage());
            return new MediaUploadFailure(); // sentinel — caller must not depend on the returned id
        }
    }

    /**
     * Sets status = RETRYING before a retry attempt using an atomic database update.
     * Concurrent callers that also try to retry see 0 rows updated and abort.
     *
     * @return true if the transition succeeded (caller should proceed with upload),
     *         false if the record is already RETRYING, RESOLVED, or DISMISSED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetrying(Long failureId) {
        if (failureId == null) return false;
        return repository.markRetryingIfFailed(failureId) > 0;
    }

    /**
     * Records a successful resolution after a retry or a re-upload.
     * Proactively cleans up any staged media binary.
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
            cleanupStagedMedia(failure.getStagingObjectKey());
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
     * Proactively cleans up any staged media binary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dismiss(Long failureId) {
        repository.findById(failureId).ifPresent(failure -> {
            if ("RESOLVED".equals(failure.getStatus())) return; // Do not overwrite terminal RESOLVED status
            failure.setStatus("DISMISSED");
            repository.saveAndFlush(failure);
            if (failure.getUploadRequestId() != null) {
                cache.remove(failure.getUploadRequestId());
            }
            cleanupStagedMedia(failure.getStagingObjectKey());
        });
    }

    /**
     * Reverts a RETRYING record back to FAILED (used when a retry attempt itself fails).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRetryFailure(Long failureId, String newReason, String newDiagnostic) {
        repository.findById(failureId).ifPresent(failure -> {
            if ("RESOLVED".equals(failure.getStatus())) return; // Do not overwrite terminal RESOLVED status
            failure.setStatus("FAILED");
            failure.setRetryCount(failure.getRetryCount() + 1);
            failure.setFailureReason(newReason);
            failure.setInternalDiagnostic(newDiagnostic);
            repository.saveAndFlush(failure);
        });
    }

    /**
     * Transitions a failure record to DB_PERSIST stage after a successful storage upload
     * where subsequent database persistence failed. Preserves authoritative storage URL
     * and public ID so the next retry can reconcile the database without re-uploading to Cloudinary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionToDbPersistFailure(
            Long failureId,
            String storageUrl,
            String storagePublicId,
            String newReason,
            String newDiagnostic) {
        repository.findById(failureId).ifPresent(failure -> {
            if ("RESOLVED".equals(failure.getStatus())) return; // Do not overwrite terminal RESOLVED status
            failure.setStatus("FAILED");
            failure.setFailureStage("DB_PERSIST");
            failure.setStorageUrl(storageUrl);
            failure.setStoragePublicId(storagePublicId);
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

    /** Proactively cleans up staged media object without failing if deletion fails. */
    public void cleanupStagedMedia(String stagingObjectKey) {
        if (stagingObjectKey == null || stagingObjectKey.isBlank() || mediaStagingService == null) {
            return;
        }
        try {
            mediaStagingService.delete(stagingObjectKey);
        } catch (Exception e) {
            log.warn("Failed to delete staged object [{}] (non-fatal): {}", stagingObjectKey, e.getMessage());
        }
    }

    // ---- Private helpers ----

    private MediaUploadFailure incrementRetryAndReset(Long id, UploadFailureContext ctx) {
        MediaUploadFailure failure = repository.findById(id).orElseThrow();
        failure.setStatus("FAILED");
        failure.setRetryCount(failure.getRetryCount() + 1);
        failure.setFailureReason(ctx.safeReason());
        failure.setInternalDiagnostic(ctx.diagnostic());
        if (ctx.storageUrl() != null) failure.setStorageUrl(ctx.storageUrl());
        if (ctx.storagePublicId() != null) failure.setStoragePublicId(ctx.storagePublicId());
        if (ctx.stagingObjectKey() != null) {
            failure.setStagingObjectKey(ctx.stagingObjectKey());
            failure.setStagingExpiresAt(ctx.stagingExpiresAt());
        }
        if (ctx.cloudinaryFailureCategory() != null) failure.setCloudinaryFailureCategory(ctx.cloudinaryFailureCategory());
        if (ctx.providerStatusCode() != null) failure.setProviderStatusCode(ctx.providerStatusCode());
        return repository.saveAndFlush(failure);
    }
}

