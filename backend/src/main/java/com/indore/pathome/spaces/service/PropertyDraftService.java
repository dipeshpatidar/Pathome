package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indore.pathome.spaces.dto.draft.DraftDetailDTO;
import com.indore.pathome.spaces.dto.draft.DraftMediaDTO;
import com.indore.pathome.spaces.dto.draft.DraftSummaryDTO;
import com.indore.pathome.spaces.dto.draft.SaveDraftRequest;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.exception.MediaStagingException;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Service managing the lifecycle, persistence, media staging, and recovery
 * of admin property upload drafts (Single and Batch).
 */
@Service
public class PropertyDraftService {

    private static final Logger log = LoggerFactory.getLogger(PropertyDraftService.class);

    private static final Pattern SAFE_KEY_CHARS_PATTERN = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern DRAFT_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,64}$");
    private static final Pattern MEDIA_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{4,64}$");
    private static final Pattern KEY_PART_CLEAN_PATTERN = Pattern.compile("[^a-zA-Z0-9_-]");

    private static final long MAX_PAYLOAD_SIZE = 2 * 1024 * 1024; // 2 MB
    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024;  // 10 MB
    private static final long MAX_VIDEO_SIZE = 100 * 1024 * 1024; // 100 MB
    private static final int DEFAULT_INACTIVITY_RETENTION_DAYS = 15;

    private final PropertyUploadDraftRepository draftRepository;
    private final PropertyDraftMediaRepository draftMediaRepository;
    private final MediaStagingService mediaStagingService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Autowired(required = false)
    private ListingRepository listingRepository;

    public void setListingRepository(ListingRepository listingRepository) {
        this.listingRepository = listingRepository;
    }

    @Autowired(required = false)
    private PropertyMediaAssetRepository mediaAssetRepository;

    public void setMediaAssetRepository(PropertyMediaAssetRepository mediaAssetRepository) {
        this.mediaAssetRepository = mediaAssetRepository;
    }

    @Autowired(required = false)
    private NotificationService notificationService;

    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    // L1 in-memory cache: (adminId + ":" + draftId) -> draftId for fast O(1) checks
    private final ConcurrentHashMap<String, String> activeDraftCache = new ConcurrentHashMap<>();

    public PropertyDraftService(
            PropertyUploadDraftRepository draftRepository,
            PropertyDraftMediaRepository draftMediaRepository,
            @Qualifier("draftMediaStagingService") MediaStagingService mediaStagingService,
            ObjectMapper objectMapper
    ) {
        this(draftRepository, draftMediaRepository, mediaStagingService, objectMapper, null);
    }

    @Autowired
    public PropertyDraftService(
            PropertyUploadDraftRepository draftRepository,
            PropertyDraftMediaRepository draftMediaRepository,
            @Qualifier("draftMediaStagingService") MediaStagingService mediaStagingService,
            ObjectMapper objectMapper,
            @Autowired(required = false) PlatformTransactionManager transactionManager
    ) {
        this.draftRepository = Objects.requireNonNull(draftRepository, "draftRepository must not be null");
        this.draftMediaRepository = Objects.requireNonNull(draftMediaRepository, "draftMediaRepository must not be null");
        this.mediaStagingService = mediaStagingService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    /**
     * Lists active drafts for the authenticated admin, newest first.
     */
    @Transactional(readOnly = true)
    public List<DraftSummaryDTO> listDrafts(String adminId) {
        String cleanAdminId = sanitizeAdminId(adminId);
        List<PropertyUploadDraft> drafts = draftRepository
                .findAllByAdminIdAndStatusNotOrderByUpdatedAtDesc(cleanAdminId, "DISCARDED");

        List<DraftSummaryDTO> result = new ArrayList<>(drafts.size());
        for (PropertyUploadDraft draft : drafts) {
            // Only exclude drafts that have already reached terminal PUBLISHED status
            if ("PUBLISHED".equalsIgnoreCase(draft.getStatus())) {
                continue;
            }
            List<PropertyDraftMedia> mediaList = draftMediaRepository.findAllByDraftIdAndAdminId(draft.getDraftId(), cleanAdminId);
            result.add(new DraftSummaryDTO(
                    draft.getDraftId(),
                    draft.getDraftType(),
                    draft.getStatus(),
                    draft.getTitleSummary() != null ? draft.getTitleSummary() : "Untitled draft",
                    draft.getItemCount() != null ? draft.getItemCount() : 1,
                    draft.getVersion() != null ? draft.getVersion() : 1,
                    mediaList.size(),
                    draft.getCreatedAt(),
                    draft.getUpdatedAt(),
                    draft.getPublishedPropertyId()
            ));
        }
        return result;
    }

    /**
     * Retrieves full draft detail and its associated staged media records.
     * For BATCH drafts, auto-reconciles against backend listings to resolve interrupted publications.
     */
    @Transactional
    public DraftDetailDTO getDraft(String adminId, String draftId) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);

        PropertyUploadDraft draft = draftRepository.findByDraftIdAndAdminId(draftId, cleanAdminId)
                .orElseThrow(() -> new EntityNotFoundException("Draft not found or access denied: " + draftId));

        // Auto-reconcile backend truth for BATCH drafts (interrupted publication recovery)
        if ("BATCH".equalsIgnoreCase(draft.getDraftType()) && listingRepository != null) {
            reconcileInterruptedBatchDraftState(draft, cleanAdminId);
        }

        List<PropertyDraftMedia> mediaEntities = draftMediaRepository.findAllByDraftIdAndAdminId(draftId, cleanAdminId);
        List<DraftMediaDTO> mediaList = toMediaDtos(mediaEntities);

        // Fallback: If mediaEntities is empty for some staged cards, but listings exist with permanent assets,
        // reconstruct DraftMediaDTO from permanent property_media_assets
        if (mediaAssetRepository != null && listingRepository != null && "BATCH".equalsIgnoreCase(draft.getDraftType())) {
            reconstructMediaFromPermanentAssets(draft, mediaList);
        }

        return new DraftDetailDTO(
                draft.getDraftId(),
                draft.getDraftType(),
                draft.getStatus(),
                draft.getTitleSummary() != null ? draft.getTitleSummary() : "Untitled draft",
                draft.getItemCount() != null ? draft.getItemCount() : 1,
                draft.getVersion() != null ? draft.getVersion() : 1,
                draft.getPayload(),
                mediaList,
                draft.getCreatedAt(),
                draft.getUpdatedAt(),
                draft.getPublishedPropertyId()
        );
    }

    /**
     * Saves or updates a draft with optimistic concurrency protection.
     */
    @Transactional
    public DraftDetailDTO saveOrUpdateDraft(String adminId, SaveDraftRequest request) {
        String cleanAdminId = sanitizeAdminId(adminId);
        if (request == null || request.draftId() == null || request.draftId().isBlank()) {
            throw new IllegalArgumentException("Draft ID must be provided");
        }
        validateDraftId(request.draftId());

        if (request.payload() == null || request.payload().isBlank()) {
            throw new IllegalArgumentException("Draft payload cannot be empty");
        }
        if (request.payload().length() > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Draft payload exceeds maximum allowed size of 2 MB");
        }

        Optional<PropertyUploadDraft> existingOpt = draftRepository.findByDraftId(request.draftId());
        PropertyUploadDraft draft;

        if (existingOpt.isPresent()) {
            draft = existingOpt.get();
            // Ownership check: Admin A cannot overwrite Admin B's draft
            if (!draft.getAdminId().equals(cleanAdminId)) {
                throw new EntityNotFoundException("Draft not found or access denied: " + request.draftId());
            }

            if ("PUBLISHED".equalsIgnoreCase(draft.getStatus())) {
                throw new IllegalStateException("Draft is already published and cannot be modified.");
            }

            // Optimistic concurrency check
            if (request.version() != null && request.version() < draft.getVersion()) {
                throw new DraftConflictException(
                        draft.getDraftId(),
                        draft.getVersion(),
                        "A newer version of this draft was saved from another window or device. Please review the latest version before saving."
                );
            }

            draft.setVersion(draft.getVersion() + 1);
            draft.setPayload(request.payload());
            if (request.draftType() != null && !request.draftType().isBlank()) {
                draft.setDraftType(request.draftType());
            }
            if (request.status() != null && !request.status().isBlank()) {
                draft.setStatus(request.status());
            }
            if (request.titleSummary() != null) {
                draft.setTitleSummary(request.titleSummary().trim());
            }
            if (request.itemCount() != null && request.itemCount() > 0) {
                draft.setItemCount(request.itemCount());
            }
            draft.setUpdatedAt(LocalDateTime.now());
        } else {
            draft = new PropertyUploadDraft();
            draft.setDraftId(request.draftId());
            draft.setAdminId(cleanAdminId);
            draft.setDraftType(request.draftType() != null ? request.draftType() : "SINGLE");
            draft.setStatus(request.status() != null ? request.status() : "DRAFT");
            draft.setTitleSummary(request.titleSummary() != null ? request.titleSummary().trim() : "Untitled draft");
            draft.setItemCount(request.itemCount() != null && request.itemCount() > 0 ? request.itemCount() : 1);
            draft.setVersion(1);
            draft.setPayload(request.payload());
            draft.setCreatedAt(LocalDateTime.now());
            draft.setUpdatedAt(LocalDateTime.now());
        }

        PropertyUploadDraft saved = draftRepository.save(draft);
        activeDraftCache.put(cleanAdminId + ":" + saved.getDraftId(), saved.getDraftId());

        List<PropertyDraftMedia> mediaEntities = draftMediaRepository.findAllByDraftIdAndAdminId(saved.getDraftId(), cleanAdminId);
        List<DraftMediaDTO> mediaList = toMediaDtos(mediaEntities);

        return new DraftDetailDTO(
                saved.getDraftId(),
                saved.getDraftType(),
                saved.getStatus(),
                saved.getTitleSummary(),
                saved.getItemCount(),
                saved.getVersion(),
                saved.getPayload(),
                mediaList,
                saved.getCreatedAt(),
                saved.getUpdatedAt(),
                saved.getPublishedPropertyId()
        );
    }

    /**
     * Explicitly discards a draft, safely purging all associated staged media files
     * from object storage and deleting the database draft record.
     */
    @Transactional
    public void discardDraft(String adminId, String draftId) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);

        PropertyUploadDraft draft = draftRepository.findByDraftIdAndAdminId(draftId, cleanAdminId)
                .orElseThrow(() -> new EntityNotFoundException("Draft not found or access denied: " + draftId));

        // Published drafts serve as durable idempotency tombstones; do not delete
        if (draft.getPublishedPropertyId() != null || "PUBLISHED".equalsIgnoreCase(draft.getStatus())) {
            log.info("Discard ignored on published draft [{}]; preserving publication tombstone.", draftId);
            return;
        }

        // Purge associated draft media from object storage
        List<PropertyDraftMedia> mediaList = draftMediaRepository.findAllByDraftIdAndAdminId(draftId, cleanAdminId);
        for (PropertyDraftMedia media : mediaList) {
            cleanupStagedMedia(media.getStagingObjectKey());
        }
        draftMediaRepository.deleteAllByDraftIdAndAdminId(draftId, cleanAdminId);

        draftRepository.delete(draft);
        activeDraftCache.remove(cleanAdminId + ":" + draftId);
        log.info("Discarded draft [{}] and cleaned up {} staged media items for admin [{}]",
                draftId, mediaList.size(), cleanAdminId);
    }

    /**
     * Verifies that the draft exists and is owned by the specified admin.
     * If the draft has not yet been saved to the database (e.g. media selected before
     * the first debounced autosave), auto-establishes a blank initial draft record in
     * an isolated transaction with DataIntegrityViolationException conflict recovery.
     */
    public PropertyUploadDraft ensureDraftExists(String cleanAdminId, String draftId) {
        Optional<PropertyUploadDraft> existing = draftRepository.findByDraftId(draftId);
        if (existing.isPresent()) {
            PropertyUploadDraft draft = existing.get();
            if (!draft.getAdminId().equals(cleanAdminId)) {
                throw new EntityNotFoundException("Draft not found or access denied: " + draftId);
            }
            return draft;
        }

        try {
            if (transactionTemplate != null) {
                return transactionTemplate.execute(status -> createInitialDraftRecord(cleanAdminId, draftId));
            } else {
                return createInitialDraftRecord(cleanAdminId, draftId);
            }
        } catch (DataIntegrityViolationException e) {
            // Another concurrent media upload or autosave just inserted the initial record simultaneously
            return draftRepository.findByDraftIdAndAdminId(draftId, cleanAdminId)
                    .orElseThrow(() -> new EntityNotFoundException("Draft not found or access denied: " + draftId));
        }
    }

    private PropertyUploadDraft createInitialDraftRecord(String cleanAdminId, String draftId) {
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId(draftId);
        draft.setAdminId(cleanAdminId);
        draft.setDraftType("SINGLE");
        draft.setStatus("DRAFT");
        draft.setTitleSummary("Draft with uploaded media");
        draft.setPayload("{}");
        draft.setItemCount(1);
        draft.setVersion(1);
        draft.setCreatedAt(LocalDateTime.now());
        draft.setUpdatedAt(LocalDateTime.now());
        return draftRepository.save(draft);
    }

    /**
     * Stages an unpublished draft media file to private object storage under the
     * dedicated draft namespace `drafts/{adminId}/{draftId}/{mediaId}`.
     *
     * External B2 network I/O executes OUTSIDE any database transaction to prevent
     * holding connections and to prevent holding stale JPA entity snapshots across
     * long-running network operations.
     *
     * Once B2 upload succeeds, database persistence executes in a short isolated transaction
     * with bounded retries. If DB persistence permanently fails, compensating deletion
     * purges the staged B2 object to prevent untracked orphans.
     */
    public DraftMediaDTO stageDraftMedia(
            String adminId,
            String draftId,
            MultipartFile file,
            String cardId,
            String roomTag,
            Boolean isCover
    ) {
        return stageDraftMedia(adminId, draftId, file, cardId, roomTag, isCover, null);
    }

    public DraftMediaDTO stageDraftMedia(
            String adminId,
            String draftId,
            MultipartFile file,
            String cardId,
            String roomTag,
            Boolean isCover,
            String clientMediaId
    ) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No media file was uploaded");
        }

        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "image/jpeg";
        boolean isVideo = contentType.startsWith("video/");
        long size = file.getSize();

        if (isVideo && size > MAX_VIDEO_SIZE) {
            throw new IllegalArgumentException("Video walkthrough exceeds the maximum limit of 100 MB");
        }
        if (!isVideo && size > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("Property photo exceeds the maximum limit of 10 MB");
        }

        boolean coverFlag = Boolean.TRUE.equals(isCover);
        if (coverFlag && isVideo) {
            throw new IllegalArgumentException("A video walkthrough cannot be selected as the primary cover photo");
        }

        // Phase A: Validate ownership and ensure draft exists before starting external B2 upload
        boolean wasExistingDraft = draftRepository.findByDraftIdAndAdminId(draftId, cleanAdminId).isPresent();
        PropertyUploadDraft initialDraft = ensureDraftExists(cleanAdminId, draftId);

        // Phase B: Stream binary to B2 object storage OUTSIDE of database transaction
        String mediaId = (clientMediaId != null && !clientMediaId.isBlank())
                ? sanitizeMediaId(clientMediaId)
                : "dm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String safeFilename = sanitizeFilename(file.getOriginalFilename());
        String stagingKey = "drafts/" + sanitizeKeyPart(cleanAdminId) + "/" + draftId + "/" + mediaId + "_" + safeFilename;

        if (mediaStagingService != null) {
            try (InputStream in = file.getInputStream()) {
                mediaStagingService.stage(stagingKey, in, size, contentType);
            } catch (MediaStagingException mse) {
                throw mse;
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : "";
                if (errorMsg.contains("403") || errorMsg.contains("Access Denied") || errorMsg.contains("Forbidden")) {
                    log.error("Backblaze B2 permission denied for prefix 'drafts/'. If your Backblaze application key was restricted to 'staging/', please create a key with 'drafts/' access or configure 'pathome.draft-staging.s3.*'. Error: {}", errorMsg);
                } else {
                    log.error("Failed to stream draft media to staging: {}", errorMsg, e);
                }
                throw new IllegalStateException("Failed to store temporary draft media. " +
                        (errorMsg.contains("403") ? "Storage permission denied for draft media." : "Please try again."), e);
            }
        }

        // Phase C: Short isolated database transaction with bounded retry and compensating B2 cleanup
        PropertyDraftMedia saved;
        try {
            saved = persistMediaWithRetry(cleanAdminId, draftId, initialDraft, wasExistingDraft, mediaId, cardId, safeFilename, size, contentType, stagingKey, roomTag, coverFlag);
        } catch (Exception e) {
            log.error("Failed to persist draft media to database after retries. Purging newly staged B2 object [{}]", stagingKey, e);
            cleanupStagedMedia(stagingKey);
            throw e;
        }

        return new DraftMediaDTO(
                saved.getMediaId(),
                saved.getDraftId(),
                saved.getCardId(),
                saved.getOriginalFilename(),
                saved.getFileSizeBytes(),
                saved.getContentType(),
                saved.getRoomTag(),
                saved.getIsCover(),
                buildPreviewUrl(draftId, saved.getMediaId()),
                saved.getCreatedAt()
        );
    }

    private PropertyDraftMedia persistMediaWithRetry(
            String adminId,
            String draftId,
            PropertyUploadDraft initialDraft,
            boolean wasExistingDraft,
            String mediaId,
            String cardId,
            String safeFilename,
            long size,
            String contentType,
            String stagingKey,
            String roomTag,
            boolean coverFlag
    ) {
        int maxAttempts = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (transactionTemplate != null) {
                    return transactionTemplate.execute(status ->
                            doPersistMediaTransaction(adminId, draftId, initialDraft, wasExistingDraft, mediaId, cardId, safeFilename, size, contentType, stagingKey, roomTag, coverFlag)
                    );
                } else {
                    return doPersistMediaTransaction(adminId, draftId, initialDraft, wasExistingDraft, mediaId, cardId, safeFilename, size, contentType, stagingKey, roomTag, coverFlag);
                }
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException | CannotAcquireLockException e) {
                lastException = e;
                log.warn("Transient lock/concurrency conflict on draft [{}] media append attempt {}/{}: {}",
                        draftId, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(25L * attempt + (long) (Math.random() * 20));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Media persistence retry interrupted", ie);
                    }
                }
            }
        }

        throw new IllegalStateException("Failed to associate staged media with draft after " + maxAttempts + " attempts", lastException);
    }

    private PropertyDraftMedia doPersistMediaTransaction(
            String adminId,
            String draftId,
            PropertyUploadDraft initialDraft,
            boolean wasExistingDraft,
            String mediaId,
            String cardId,
            String safeFilename,
            long size,
            String contentType,
            String stagingKey,
            String roomTag,
            boolean coverFlag
    ) {
        // 1. Reload the latest fresh draft version from DB, falling back to initialized draft if newly created
        PropertyUploadDraft draft = draftRepository.findByDraftIdAndAdminId(draftId, adminId)
                .orElse(initialDraft);
        if (draft == null) {
            throw new EntityNotFoundException("Draft not found or access denied: " + draftId);
        }

        if ("PUBLISHED".equalsIgnoreCase(draft.getStatus())) {
            log.warn("Late draft media staging rejected for draft [{}]: draft is already PUBLISHED. Cleaning up staged B2 key [{}]", draftId, stagingKey);
            cleanupStagedMedia(stagingKey);
            throw new IllegalStateException("Draft is already published. New media cannot be attached.");
        }

        // 2. Clear previous cover photo if new media is designated as cover
        if (coverFlag) {
            if (cardId != null && !cardId.isBlank()) {
                draftMediaRepository.clearCoverFlagForCard(draftId, adminId, cardId);
            } else {
                draftMediaRepository.clearCoverFlagForDraft(draftId, adminId);
            }
        }

        // 3. Persist the media record (with idempotency guard)
        Optional<PropertyDraftMedia> existingMedia = draftMediaRepository.findByMediaIdAndAdminId(mediaId, adminId);
        PropertyDraftMedia draftMedia = existingMedia.orElseGet(PropertyDraftMedia::new);
        draftMedia.setMediaId(mediaId);
        draftMedia.setDraftId(draftId);
        draftMedia.setAdminId(adminId);
        draftMedia.setCardId(cardId);
        draftMedia.setOriginalFilename(safeFilename);
        draftMedia.setFileSizeBytes(size);
        draftMedia.setContentType(contentType);
        draftMedia.setStagingObjectKey(stagingKey);
        draftMedia.setRoomTag(roomTag != null ? roomTag : "LIVING_ROOM");
        draftMedia.setIsCover(coverFlag);

        PropertyDraftMedia saved = draftMediaRepository.save(draftMedia);

        // 4. Touch draft updatedAt timestamp directly without advancing parent entity @Version
        if (wasExistingDraft) {
            LocalDateTime now = LocalDateTime.now();
            draftRepository.touchUpdatedAt(draftId, adminId, now);
        }

        return saved;
    }

    /**
     * Retrieves an input stream for a staged draft media file for preview.
     */
    @Transactional(readOnly = true)
    public StagedMediaStream getDraftMediaStream(String adminId, String draftId, String mediaId) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);
        validateMediaId(mediaId);

        PropertyDraftMedia media = draftMediaRepository.findByMediaIdAndAdminId(mediaId, cleanAdminId)
                .orElseThrow(() -> new EntityNotFoundException("Draft media not found or access denied"));

        if (!media.getDraftId().equals(draftId)) {
            throw new EntityNotFoundException("Draft media does not belong to specified draft");
        }

        if (mediaStagingService == null) {
            throw new EntityNotFoundException("Staged draft media storage is not configured");
        }

        try {
            InputStream stream = mediaStagingService.retrieve(media.getStagingObjectKey());
            return new StagedMediaStream(stream, media.getContentType(), media.getFileSizeBytes(), media.getOriginalFilename());
        } catch (IllegalStateException e) {
            throw new EntityNotFoundException("Staged draft media file is missing or has expired");
        }
    }

    /**
     * Deletes a staged draft media file and removes its database record.
     */
    @Transactional
    public void deleteDraftMedia(String adminId, String draftId, String mediaId) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);
        validateMediaId(mediaId);

        PropertyDraftMedia media = draftMediaRepository.findByMediaIdAndAdminId(mediaId, cleanAdminId)
                .orElseThrow(() -> new EntityNotFoundException("Draft media not found or access denied"));

        if (!media.getDraftId().equals(draftId)) {
            throw new EntityNotFoundException("Draft media does not belong to specified draft");
        }

        cleanupStagedMedia(media.getStagingObjectKey());
        draftMediaRepository.delete(media);
        draftRepository.touchUpdatedAt(draftId, cleanAdminId, LocalDateTime.now());
        log.info("Deleted draft media [{}] from draft [{}] for admin [{}]", mediaId, draftId, cleanAdminId);
    }

    /**
     * Reassigns unassigned media items (cardId is null or empty) in a draft to a specific card ID.
     * Used when transitioning a SINGLE draft with staged media to a BATCH draft, binding existing
     * property A media to the actual first card ID created in the batch workspace.
     */
    @Transactional
    public int reassignUnassignedMediaToCard(String adminId, String draftId, String targetCardId) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);
        if (targetCardId == null || targetCardId.isBlank()) {
            throw new IllegalArgumentException("targetCardId cannot be null or empty");
        }

        int updated = draftMediaRepository.reassignUnassignedMediaToCard(draftId, cleanAdminId, targetCardId.trim());
        if (updated > 0) {
            draftRepository.touchUpdatedAt(draftId, cleanAdminId, LocalDateTime.now());
            log.info("Reassigned {} unassigned media items in draft [{}] to card [{}] for admin [{}]",
                    updated, draftId, targetCardId, cleanAdminId);
        }
        return updated;
    }

    /**
     * Reconciles a batch draft after a publish attempt.
     * Removes successfully published cards and their temporary media from the draft payload.
     * If all cards were published, converts the draft to a PUBLISHED tombstone.
     *
     * <p><b>Concurrency design:</b> The method is split into two phases to prevent
     * {@code StaleObjectStateException} caused by a concurrent autosave racing the commit:
     * <ol>
     *   <li><b>Phase A — short {@code @Transactional} with pessimistic write lock:</b>
     *       Loads the entity under a DB-level exclusive lock, mutates and saves it, and
     *       deletes the {@code property_draft_media} rows for published cards. No external
     *       I/O is performed. The transaction commits quickly.</li>
     *   <li><b>Phase B — post-commit B2 cleanup (no transaction):</b>
     *       Performs the object-storage DELETE calls after the DB transaction has already
     *       committed. B2 failures are non-fatal and logged.</li>
     * </ol>
     */
    public DraftDetailDTO reconcileBatchDraft(String adminId, String draftId, List<String> publishedCardIds) {
        return reconcileBatchDraft(adminId, draftId, publishedCardIds, Collections.emptyList());
    }

    public DraftDetailDTO reconcileBatchDraft(String adminId, String draftId, List<String> publishedCardIds, List<Map<String, Object>> newCompletedListings) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);

        if (publishedCardIds == null || publishedCardIds.isEmpty()) {
            return getDraft(adminId, draftId);
        }

        // Phase A: short DB-only transaction via TransactionTemplate (avoids Spring self-invocation
        // proxy bypass that would occur with a @Transactional protected method called via this.x()).
        // The pessimistic write lock inside prevents any concurrent autosave from bumping @Version
        // between the entity load and the save.
        final String safeAdminId = cleanAdminId;
        final List<String> safePublishedIds = publishedCardIds;
        final List<Map<String, Object>> safeCompleted = newCompletedListings;

        ReconcileResult result;
        if (transactionTemplate != null) {
            result = transactionTemplate.execute(status ->
                    reconcileBatchDraftInTx(safeAdminId, draftId, safePublishedIds, safeCompleted));
            if (result == null) {
                result = new ReconcileResult(Collections.emptyList(), false, 0, Collections.emptyList());
            }
        } else {
            result = reconcileBatchDraftInTx(cleanAdminId, draftId, publishedCardIds, newCompletedListings);
        }

        // Phase B: B2 deletes happen AFTER the transaction has committed. Failures are non-fatal.
        for (String key : result.b2KeysToDelete()) {
            cleanupStagedMedia(key);
        }

        // Phase C: Post-commit notification dispatch — only after terminal PUBLISHED tombstone has committed
        if (result.tombstoned()) {
            dispatchPublishedNotification(draftId, result.completedCount(), result.completedListingIds());
        }

        return result.tombstoned() ? null : getDraft(adminId, draftId);
    }

    /** Result carrier for the inner transactional phase of reconcileBatchDraft. */
    private record ReconcileResult(
            List<String> b2KeysToDelete,
            boolean tombstoned,
            int completedCount,
            List<Long> completedListingIds) {}

    /**
     * Inner transactional body of {@link #reconcileBatchDraft}.
     * Called programmatically via {@link TransactionTemplate} to guarantee a real JDBC transaction
     * is created regardless of the call stack (avoids Spring AOP self-invocation bypass).
     * Acquires a pessimistic write lock on the draft, performs all DB mutations, and returns a
     * {@link ReconcileResult} with the B2 keys to delete post-commit and a tombstone flag.
     */
    private ReconcileResult reconcileBatchDraftInTx(
            String cleanAdminId, String draftId,
            List<String> publishedCardIds, List<Map<String, Object>> newCompletedListings) {

        // Pessimistic write lock prevents any concurrent saveOrUpdateDraft from committing
        // a version increment between our load and our save.
        PropertyUploadDraft draft = draftRepository.findByDraftIdForUpdate(draftId)
                .or(() -> draftRepository.findByDraftIdAndAdminId(draftId, cleanAdminId))
                .filter(d -> d.getAdminId().equals(cleanAdminId))
                .orElseThrow(() -> new EntityNotFoundException("Draft not found or access denied: " + draftId));

        Set<String> publishedSet = new HashSet<>(publishedCardIds);

        try {
            JsonNode root = objectMapper.readTree(draft.getPayload());
            if (!root.has("stagedCards") || !root.get("stagedCards").isArray()) {
                return new ReconcileResult(Collections.emptyList(), false, 0, Collections.emptyList());
            }

            ArrayNode cardsArray = (ArrayNode) root.get("stagedCards");
            ArrayNode remainingCards = objectMapper.createArrayNode();

            // Maintain completed listings with idempotency (keyed by cardId)
            Map<String, ObjectNode> completedMap = new LinkedHashMap<>();
            if (root.has("completedListings") && root.get("completedListings").isArray()) {
                for (JsonNode node : root.get("completedListings")) {
                    String cid = node.has("cardId") ? node.get("cardId").asText() : "";
                    if (!cid.isBlank() && node.isObject()) {
                        completedMap.put(cid, (ObjectNode) node);
                    }
                }
            }

            // Merge explicitly passed newCompletedListings
            if (newCompletedListings != null && !newCompletedListings.isEmpty()) {
                for (Map<String, Object> comp : newCompletedListings) {
                    String cid = comp.get("cardId") != null ? String.valueOf(comp.get("cardId")) : "";
                    if (!cid.isBlank()) {
                        ObjectNode node = objectMapper.createObjectNode();
                        node.put("cardId", cid);
                        if (comp.get("listingId") instanceof Number n) {
                            node.put("listingId", n.longValue());
                        } else if (comp.get("listingId") != null) {
                            try {
                                node.put("listingId", Long.parseLong(String.valueOf(comp.get("listingId"))));
                            } catch (NumberFormatException ignored) {}
                        }
                        node.put("title", comp.get("title") != null ? String.valueOf(comp.get("title")) : "");
                        completedMap.put(cid, node);
                    }
                }
            }

            for (JsonNode cardNode : cardsArray) {
                String cardId = cardNode.has("id") ? cardNode.get("id").asText() : "";
                if (!publishedSet.contains(cardId)) {
                    remainingCards.add(cardNode);
                }
            }

            if (remainingCards.isEmpty()) {
                // All cards are published — collect B2 keys, delete DB rows, tombstone the draft.
                List<PropertyDraftMedia> allMedia = draftMediaRepository.findAllByDraftIdAndAdminId(draftId, cleanAdminId);
                List<String> keysToDelete = new ArrayList<>(allMedia.size());
                for (PropertyDraftMedia media : allMedia) {
                    keysToDelete.add(media.getStagingObjectKey());
                }

                // DB-only operations — no B2 calls inside this transaction
                draftMediaRepository.deleteAllByDraftIdAndAdminId(draftId, cleanAdminId);

                draft.setStatus("PUBLISHED");
                ObjectNode tombstonePayload = objectMapper.createObjectNode();
                if (!completedMap.isEmpty()) {
                    ArrayNode completedArray = objectMapper.createArrayNode();
                    completedMap.values().forEach(completedArray::add);
                    tombstonePayload.set("completedListings", completedArray);
                }
                draft.setPayload(tombstonePayload.toString());
                draft.setItemCount(0);
                draft.setUpdatedAt(LocalDateTime.now());
                draftRepository.save(draft);

                activeDraftCache.remove(cleanAdminId + ":" + draftId);

                int count = Math.max(1, completedMap.size());
                List<Long> listingIds = new ArrayList<>();
                for (ObjectNode n : completedMap.values()) {
                    if (n.has("listingId") && n.get("listingId").isNumber()) {
                        listingIds.add(n.get("listingId").asLong());
                    }
                }

                log.info("Converted completed BATCH draft [{}] into PUBLISHED tombstone (all properties published) and queued {} staged media items for B2 cleanup for admin [{}]",
                        draftId, keysToDelete.size(), cleanAdminId);

                return new ReconcileResult(keysToDelete, true, count, listingIds);
            }

            // Partial — update payload for remaining cards
            ((ObjectNode) root).set("stagedCards", remainingCards);
            if (!completedMap.isEmpty()) {
                ArrayNode completedArray = objectMapper.createArrayNode();
                completedMap.values().forEach(completedArray::add);
                ((ObjectNode) root).set("completedListings", completedArray);
            }

            int remainingCount = remainingCards.size();
            String titleSummary = remainingCount == 1
                    ? "Batch — 1 property remaining"
                    : "Batch — " + remainingCount + " properties remaining";

            draft.setPayload(objectMapper.writeValueAsString(root));
            draft.setItemCount(remainingCount);
            draft.setTitleSummary(titleSummary);
            draft.setVersion(draft.getVersion() + 1);
            draft.setUpdatedAt(LocalDateTime.now());
            draftRepository.save(draft);

            // Collect B2 keys for published cards' media; delete DB rows in this transaction.
            List<PropertyDraftMedia> allMedia = draftMediaRepository.findAllByDraftIdAndAdminId(draftId, cleanAdminId);
            List<String> keysToDelete = new ArrayList<>();
            for (PropertyDraftMedia media : allMedia) {
                if (media.getCardId() != null && publishedSet.contains(media.getCardId())) {
                    keysToDelete.add(media.getStagingObjectKey());
                    draftMediaRepository.delete(media);
                }
            }
            return new ReconcileResult(keysToDelete, false, 0, Collections.emptyList());

        } catch (JsonProcessingException e) {
            log.warn("Failed to parse batch draft payload JSON during reconciliation for draft [{}]: {}", draftId, e.getMessage());
            return new ReconcileResult(Collections.emptyList(), false, 0, Collections.emptyList());
        }
    }


    private void reconcileInterruptedBatchDraftState(PropertyUploadDraft draft, String cleanAdminId) {
        String payload = draft.getPayload();
        if (payload == null || payload.isBlank() || "{}".equals(payload.trim())) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            if (!root.has("stagedCards") || !root.get("stagedCards").isArray()) {
                return;
            }

            ArrayNode cardsArray = (ArrayNode) root.get("stagedCards");
            if (cardsArray.isEmpty()) {
                return;
            }

            List<String> newlyCompletedCardIds = new ArrayList<>();
            List<Map<String, Object>> newCompletedListings = new ArrayList<>();
            boolean payloadModified = false;

            for (JsonNode cardNode : cardsArray) {
                String cardId = cardNode.has("id") ? cardNode.get("id").asText() : "";
                if (cardId.isBlank()) continue;

                String compositeOriginDraftId = draft.getDraftId() + ":" + cardId;
                Optional<Listing> existing = listingRepository.findByOriginDraftId(compositeOriginDraftId);
                if (existing.isPresent()) {
                    Listing listing = existing.get();
                    Long listingId = listing.getId();
                    String title = listing.getTitle() != null ? listing.getTitle() : cardNode.path("title").asText("Property #" + listingId);

                    Optional<Set<String>> expectedUploadRequestIds = expectedUploadRequestIds(cardNode, listingId);
                    Set<String> permanentUploadRequestIds = new HashSet<>();
                    if (mediaAssetRepository != null) {
                        for (PropertyMediaAsset asset : mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(listingId)) {
                            String uploadRequestId = asset.getUploadRequestId();
                            if (uploadRequestId != null && !uploadRequestId.isBlank()) {
                                permanentUploadRequestIds.add(uploadRequestId);
                            }
                        }
                    }

                    // Completion is authoritative only when every expected durable media identity is present.
                    // An empty expected set is a legitimate zero-media card. An unidentifiable staged item is
                    // deliberately left resumable rather than falling back to an unsafe asset-count comparison.
                    boolean allExpectedMediaPersisted = expectedUploadRequestIds
                            .map(expectedIds -> expectedIds.stream().allMatch(permanentUploadRequestIds::contains))
                            .orElse(false);

                    if (allExpectedMediaPersisted) {
                        newlyCompletedCardIds.add(cardId);
                        newCompletedListings.add(Map.of("cardId", cardId, "listingId", listingId, "title", title));
                        log.info("Interrupted batch draft [{}] recovery: card [{}] is fully published on backend as listing #{}. Auto-reconciling.",
                                draft.getDraftId(), cardId, listingId);
                    } else {
                        // Card is partially published (listing exists, but some media missing)
                        // Mark publishedId on card if not already set to prevent duplicate listing creation
                        if (!cardNode.has("publishedId") || cardNode.get("publishedId").isNull()) {
                            ((ObjectNode) cardNode).put("publishedId", listingId);
                            payloadModified = true;
                        }
                    }
                }
            }

            if (!newlyCompletedCardIds.isEmpty()) {
                reconcileBatchDraft(cleanAdminId, draft.getDraftId(), newlyCompletedCardIds, newCompletedListings);
                // Refresh draft entity from database after reconciliation
                draftRepository.findByDraftIdAndAdminId(draft.getDraftId(), cleanAdminId).ifPresent(updated -> {
                    draft.setStatus(updated.getStatus());
                    draft.setPayload(updated.getPayload());
                    draft.setItemCount(updated.getItemCount());
                    draft.setTitleSummary(updated.getTitleSummary());
                    draft.setVersion(updated.getVersion());
                    draft.setUpdatedAt(updated.getUpdatedAt());
                });
            } else if (payloadModified) {
                draft.setPayload(objectMapper.writeValueAsString(root));
                draft.setUpdatedAt(LocalDateTime.now());
                draftRepository.save(draft);
            }
        } catch (Exception e) {
            log.warn("Non-fatal error inspecting batch draft [{}] for interrupted recovery: {}", draft.getDraftId(), e.getMessage());
        }
    }

    /**
     * Derives the canonical permanent-upload identities for one staged card using the same durable-ID
     * contract as the frontend: {@code media-<listingId>-<sanitized durableMediaId>}.
     *
     * <p>Only the durable-ID path is valid during backend recovery. If a staged media item lacks that
     * identity, the caller must retain the card for resume rather than guessing from filename, count, or time.</p>
     */
    private Optional<Set<String>> expectedUploadRequestIds(JsonNode cardNode, Long listingId) {
        JsonNode stagedMedia = cardNode.get("stagedMedia");
        if (stagedMedia == null || !stagedMedia.isArray()) {
            return Optional.of(Collections.emptySet());
        }

        Set<String> expectedIds = new HashSet<>();
        for (JsonNode mediaNode : stagedMedia) {
            String durableMediaId = mediaNode.path("id").asText("").trim();
            if (durableMediaId.isBlank()) {
                return Optional.empty();
            }
            expectedIds.add(canonicalUploadRequestId(listingId, durableMediaId));
        }
        return Optional.of(expectedIds);
    }

    private String canonicalUploadRequestId(Long listingId, String durableMediaId) {
        String sanitizedDurableMediaId = KEY_PART_CLEAN_PATTERN.matcher(durableMediaId.trim()).replaceAll("_");
        return "media-" + listingId + "-" + sanitizedDurableMediaId;
    }

    private void reconstructMediaFromPermanentAssets(PropertyUploadDraft draft, List<DraftMediaDTO> mediaList) {
        String payload = draft.getPayload();
        if (payload == null || payload.isBlank() || "{}".equals(payload.trim())) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            if (!root.has("stagedCards") || !root.get("stagedCards").isArray()) {
                return;
            }

            for (JsonNode cardNode : root.get("stagedCards")) {
                String cardId = cardNode.has("id") ? cardNode.get("id").asText() : "";
                if (cardId.isBlank()) continue;

                boolean hasExistingMedia = mediaList.stream().anyMatch(m -> cardId.equals(m.cardId()));
                if (hasExistingMedia) continue;

                String compositeOriginDraftId = draft.getDraftId() + ":" + cardId;
                Optional<Listing> existing = listingRepository.findByOriginDraftId(compositeOriginDraftId);
                if (existing.isPresent()) {
                    Long listingId = existing.get().getId();
                    List<PropertyMediaAsset> assets = mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(listingId);
                    for (PropertyMediaAsset asset : assets) {
                        String mediaId = asset.getUploadRequestId() != null && !asset.getUploadRequestId().isBlank()
                                ? asset.getUploadRequestId()
                                : "asset-" + asset.getId();
                        String contentType = asset.getMediaType() == MediaType.VIDEO_WALKTHROUGH ? "video/mp4" : "image/webp";
                        mediaList.add(new DraftMediaDTO(
                                mediaId,
                                draft.getDraftId(),
                                cardId,
                                asset.getCaption() != null ? asset.getCaption() : "photo",
                                null,
                                contentType,
                                asset.getRoomTag() != null ? asset.getRoomTag().name() : "LIVING_ROOM",
                                Boolean.TRUE.equals(asset.getIsPrimaryCover()),
                                asset.getMediaUrl(),
                                asset.getUploadedAt() != null ? asset.getUploadedAt() : LocalDateTime.now()
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Non-fatal error reconstructing media from permanent assets for draft [{}]: {}", draft.getDraftId(), e.getMessage());
        }
    }

    @Transactional
    public void onPropertyPublished(String adminId, String draftId) {
        onPropertyPublished(adminId, draftId, null);
    }

    /**
     * Invoked when a single property publish workflow has reached safe terminal completion.
     * Safely purges temporary staged media from B2 and database records,
     * retains publishedPropertyId, and transitions the draft to a lightweight PUBLISHED tombstone.
     */
    @Transactional
    public void onPropertyPublished(String adminId, String draftId, Long listingId) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);

        try {
            Optional<PropertyUploadDraft> draftOpt = draftRepository.findByDraftIdAndAdminId(draftId, cleanAdminId);
            if (draftOpt.isEmpty()) {
                log.debug("Draft [{}] not found for admin [{}] during publish finalization", draftId, cleanAdminId);
                return;
            }

            PropertyUploadDraft draft = draftOpt.get();

            // Idempotency: if draft is already PUBLISHED, return cleanly
            if ("PUBLISHED".equalsIgnoreCase(draft.getStatus())) {
                log.info("Draft [{}] is already PUBLISHED tombstone. Finalization is idempotent.", draftId);
                return;
            }

            // Relationship verification: ensure draft publishedPropertyId matches listingId if provided
            if (listingId != null) {
                if (draft.getPublishedPropertyId() != null && !draft.getPublishedPropertyId().equals(listingId)) {
                    throw new IllegalArgumentException("Draft [" + draftId + "] is linked to listing #"
                            + draft.getPublishedPropertyId() + ", cannot finalize for listing #" + listingId);
                }
                if (listingRepository != null) {
                    listingRepository.findById(listingId).ifPresent(listing -> {
                        if (listing.getOriginDraftId() != null && !listing.getOriginDraftId().equals(draftId)) {
                            throw new IllegalArgumentException("Listing #" + listingId + " does not originate from draft [" + draftId + "]");
                        }
                    });
                }
                draft.setPublishedPropertyId(listingId);
            }

            // Safeguard against premature batch draft tombstoning:
            // If this is a BATCH draft and its payload still contains unpublished cards, refuse to wipe payload.
            if ("BATCH".equalsIgnoreCase(draft.getDraftType())) {
                try {
                    String payload = draft.getPayload();
                    if (payload != null && !payload.isBlank() && !"{}".equals(payload.trim())) {
                        JsonNode root = objectMapper.readTree(payload);
                        if (root.has("stagedCards") && root.get("stagedCards").isArray()) {
                            ArrayNode cards = (ArrayNode) root.get("stagedCards");
                            int unpublishedCount = 0;
                            for (JsonNode c : cards) {
                                boolean hasPubId = c.has("publishedId") && !c.get("publishedId").isNull();
                                if (!hasPubId) {
                                    unpublishedCount++;
                                }
                            }
                            if (unpublishedCount > 0) {
                                log.warn("Refusing premature PUBLISHED tombstone for BATCH draft [{}] because {} unpublished cards remain in payload.",
                                        draftId, unpublishedCount);
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Non-fatal error inspecting batch payload during tombstone verification for draft [{}]: {}", draftId, e.getMessage());
                }
            }

            // 1. Purge all staged media files belonging to this draft from object storage
            List<PropertyDraftMedia> mediaList = draftMediaRepository.findAllByDraftIdAndAdminId(draftId, cleanAdminId);
            for (PropertyDraftMedia media : mediaList) {
                cleanupStagedMedia(media.getStagingObjectKey());
            }
            draftMediaRepository.deleteAllByDraftIdAndAdminId(draftId, cleanAdminId);

            // 2. Mark draft record as lightweight PUBLISHED tombstone, wiping raw payload but retaining minimal completion summary
            ObjectNode tombstonePayload = objectMapper.createObjectNode();
            try {
                String existingPayload = draft.getPayload();
                if (existingPayload != null && !existingPayload.isBlank() && !"{}".equals(existingPayload.trim())) {
                    JsonNode existingRoot = objectMapper.readTree(existingPayload);
                    if (existingRoot.has("completedListings") && existingRoot.get("completedListings").isArray()) {
                        tombstonePayload.set("completedListings", existingRoot.get("completedListings"));
                    }
                }
            } catch (Exception ignored) {}

            draft.setStatus("PUBLISHED");
            draft.setPayload(tombstonePayload.toString());
            draft.setItemCount(0);
            draft.setUpdatedAt(LocalDateTime.now());
            draftRepository.save(draft);

            activeDraftCache.remove(cleanAdminId + ":" + draftId);

            // Dispatch persistent published notification post-commit
            int count = 1;
            List<Long> listingIds = new ArrayList<>();
            if ("BATCH".equalsIgnoreCase(draft.getDraftType())) {
                if (tombstonePayload.has("completedListings") && tombstonePayload.get("completedListings").isArray()) {
                    ArrayNode arr = (ArrayNode) tombstonePayload.get("completedListings");
                    count = Math.max(1, arr.size());
                    for (JsonNode n : arr) {
                        if (n.has("listingId") && n.get("listingId").isNumber()) {
                            listingIds.add(n.get("listingId").asLong());
                        }
                    }
                }
            } else {
                if (draft.getPublishedPropertyId() != null) {
                    listingIds.add(draft.getPublishedPropertyId());
                } else if (listingId != null) {
                    listingIds.add(listingId);
                }
            }

            final int finalCount = count;
            final List<Long> finalListings = listingIds;
            final String finalDraftId = draftId;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatchPublishedNotification(finalDraftId, finalCount, finalListings);
                    }
                });
            } else {
                dispatchPublishedNotification(finalDraftId, finalCount, finalListings);
            }

            if ("BATCH".equalsIgnoreCase(draft.getDraftType())) {
                log.info("Converted completed BATCH draft [{}] into PUBLISHED tombstone (all properties published) and cleaned {} staged media items for admin [{}]",
                        draftId, mediaList.size(), cleanAdminId);
            } else {
                log.info("Converted draft [{}] into PUBLISHED tombstone linked to listing #{} and cleaned {} staged media items for admin [{}]",
                        draftId, draft.getPublishedPropertyId(), mediaList.size(), cleanAdminId);
            }
        } catch (EntityNotFoundException e) {
            log.debug("Draft [{}] was already cleaned up", draftId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Non-fatal error cleaning draft [{}] media after publication: {}", draftId, e.getMessage());
        }
    }

    private void dispatchPublishedNotification(String draftId, int count, List<Long> listingIds) {
        if (notificationService == null || draftId == null || draftId.isBlank()) {
            return;
        }

        String eventKey = "PROPERTY_PUBLISHED:" + draftId.trim();
        String title;
        String message;

        int effectiveCount = Math.max(1, count);
        if (effectiveCount == 1) {
            title = "Property published successfully";
            message = "1 property was published successfully.";
        } else {
            title = effectiveCount + " properties published successfully";
            message = effectiveCount + " properties were published successfully.";
        }

        StringBuilder detailsBuilder = new StringBuilder();
        detailsBuilder.append("Draft ID: ").append(draftId);
        if (listingIds != null && !listingIds.isEmpty()) {
            if (listingIds.size() == 1) {
                detailsBuilder.append(" • Listing #").append(listingIds.get(0));
            } else {
                detailsBuilder.append(" • Listings: #").append(
                        listingIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", #"))
                );
            }
        }
        detailsBuilder.append(" • ").append(effectiveCount).append(effectiveCount == 1 ? " property published" : " properties published");

        try {
            notificationService.createNotificationWithEventKey(
                    com.indore.pathome.spaces.entity.TargetRole.ADMIN,
                    null,
                    title,
                    message,
                    detailsBuilder.toString(),
                    "PROPERTY",
                    "success",
                    eventKey
            );
        } catch (Exception e) {
            log.warn("Non-fatal: failed to dispatch published notification for draft [{}]: {}", draftId, e.getMessage());
        }
    }

    private void cleanupStagedMedia(String stagingObjectKey) {
        if (stagingObjectKey == null || stagingObjectKey.isBlank() || mediaStagingService == null) {
            return;
        }
        try {
            mediaStagingService.delete(stagingObjectKey);
        } catch (Exception e) {
            log.warn("Failed to delete staged draft media [{}] (non-fatal): {}", stagingObjectKey, e.getMessage());
        }
    }

    private List<DraftMediaDTO> toMediaDtos(List<PropertyDraftMedia> mediaEntities) {
        List<DraftMediaDTO> list = new ArrayList<>(mediaEntities.size());
        for (PropertyDraftMedia m : mediaEntities) {
            list.add(new DraftMediaDTO(
                    m.getMediaId(),
                    m.getDraftId(),
                    m.getCardId(),
                    m.getOriginalFilename(),
                    m.getFileSizeBytes(),
                    m.getContentType(),
                    m.getRoomTag(),
                    m.getIsCover(),
                    buildPreviewUrl(m.getDraftId(), m.getMediaId()),
                    m.getCreatedAt()
            ));
        }
        return list;
    }

    private String buildPreviewUrl(String draftId, String mediaId) {
        return "/api/v1/admin/drafts/" + draftId + "/media/" + mediaId;
    }

    private String sanitizeAdminId(String adminId) {
        if (adminId == null || adminId.isBlank()) return "admin";
        String trimmed = adminId.trim().toLowerCase(Locale.ROOT);
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private String sanitizeKeyPart(String val) {
        if (val == null || val.isBlank()) return "admin";
        String clean = SAFE_KEY_CHARS_PATTERN.matcher(val.trim()).replaceAll("_");
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    /**
     * Daily scheduled maintenance job to purge abandoned/unfinished drafts past 15 days of inactivity.
     * Evaluates parent draft updatedAt. Runs in bounded batches of 50 drafts to avoid memory or DB lock pressure.
     * Deletes temporary staged media from object storage, deletes draft media DB records, and deletes the draft record.
     * Never deletes published properties, Failed Upload records, or staging/ objects.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
    @Transactional
    public int purgeInactiveDrafts() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(DEFAULT_INACTIVITY_RETENTION_DAYS);
        // Exclude drafts that are already PUBLISHED (kept as tombstones for idempotency) or DISCARDED
        List<PropertyUploadDraft> expiredDrafts = draftRepository.findAllByStatusNotInAndUpdatedAtBefore(
                List.of("PUBLISHED", "DISCARDED"),
                cutoff,
                PageRequest.of(0, 50)
        );

        if (expiredDrafts.isEmpty()) {
            return 0;
        }

        int purgedCount = 0;
        for (PropertyUploadDraft draft : expiredDrafts) {
            String draftId = draft.getDraftId();
            String adminId = draft.getAdminId();

            // Guard: double-check that this is not a published listing
            if (draft.getPublishedPropertyId() != null || "PUBLISHED".equalsIgnoreCase(draft.getStatus())) {
                continue;
            }

            // 1. Identify and delete temporary staged media from object storage
            List<PropertyDraftMedia> mediaList = draftMediaRepository.findAllByDraftIdAndAdminId(draftId, adminId);
            for (PropertyDraftMedia m : mediaList) {
                cleanupStagedMedia(m.getStagingObjectKey());
            }
            draftMediaRepository.deleteAllByDraftIdAndAdminId(draftId, adminId);

            // 2. Delete the inactive draft record
            draftRepository.delete(draft);
            activeDraftCache.remove(adminId + ":" + draftId);
            purgedCount++;
        }

        log.info("Purged {} inactive drafts (and their staged media) with no activity since [{}]", purgedCount, cutoff);
        return purgedCount;
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "media_file";
        String clean = SAFE_KEY_CHARS_PATTERN.matcher(filename.trim()).replaceAll("_");
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    private String sanitizeMediaId(String input) {
        if (input == null || input.isBlank()) return null;
        String clean = KEY_PART_CLEAN_PATTERN.matcher(input.trim()).replaceAll("_");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private void validateDraftId(String draftId) {
        if (draftId == null || !DRAFT_ID_PATTERN.matcher(draftId).matches()) {
            throw new IllegalArgumentException("Invalid draft ID format: " + draftId);
        }
    }

    private void validateMediaId(String mediaId) {
        if (mediaId == null || !MEDIA_ID_PATTERN.matcher(mediaId).matches()) {
            throw new IllegalArgumentException("Invalid media ID format: " + mediaId);
        }
    }

    public record StagedMediaStream(
            InputStream inputStream,
            String contentType,
            long contentLength,
            String originalFilename
    ) {}
}
