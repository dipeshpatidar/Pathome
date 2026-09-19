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
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
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
            // Never list drafts that have already been successfully published
            if ("PUBLISHED".equalsIgnoreCase(draft.getStatus()) || draft.getPublishedPropertyId() != null) {
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
                    draft.getUpdatedAt()
            ));
        }
        return result;
    }

    /**
     * Retrieves full draft detail and its associated staged media records.
     */
    @Transactional(readOnly = true)
    public DraftDetailDTO getDraft(String adminId, String draftId) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);

        PropertyUploadDraft draft = draftRepository.findByDraftIdAndAdminId(draftId, cleanAdminId)
                .orElseThrow(() -> new EntityNotFoundException("Draft not found or access denied: " + draftId));

        List<PropertyDraftMedia> mediaEntities = draftMediaRepository.findAllByDraftIdAndAdminId(draftId, cleanAdminId);
        List<DraftMediaDTO> mediaList = toMediaDtos(mediaEntities);

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
                draft.getUpdatedAt()
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
                saved.getUpdatedAt()
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
        String mediaId = "dm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String safeFilename = sanitizeFilename(file.getOriginalFilename());
        String stagingKey = "drafts/" + sanitizeKeyPart(cleanAdminId) + "/" + draftId + "/" + mediaId + "_" + safeFilename;

        if (mediaStagingService != null) {
            try (InputStream in = file.getInputStream()) {
                mediaStagingService.stage(stagingKey, in, size, contentType);
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

        // 4. Update draft updatedAt timestamp and save if it existed prior to this media staging
        if (wasExistingDraft) {
            draft.setUpdatedAt(LocalDateTime.now());
            draftRepository.save(draft);
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
     * Reconciles a batch draft after a publish attempt.
     * Removes successfully published cards and their temporary media from the draft payload.
     * If all cards were published, discards the entire draft.
     */
    @Transactional
    public DraftDetailDTO reconcileBatchDraft(String adminId, String draftId, List<String> publishedCardIds) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);

        PropertyUploadDraft draft = draftRepository.findByDraftIdAndAdminId(draftId, cleanAdminId)
                .orElseThrow(() -> new EntityNotFoundException("Draft not found or access denied: " + draftId));

        if (publishedCardIds == null || publishedCardIds.isEmpty()) {
            return getDraft(adminId, draftId);
        }

        Set<String> publishedSet = new HashSet<>(publishedCardIds);

        // Delete staged media associated with the published cards
        List<PropertyDraftMedia> allMedia = draftMediaRepository.findAllByDraftIdAndAdminId(draftId, cleanAdminId);
        for (PropertyDraftMedia media : allMedia) {
            if (media.getCardId() != null && publishedSet.contains(media.getCardId())) {
                cleanupStagedMedia(media.getStagingObjectKey());
                draftMediaRepository.delete(media);
            }
        }

        // Reconcile structured JSON payload
        try {
            JsonNode root = objectMapper.readTree(draft.getPayload());
            if (root.has("stagedCards") && root.get("stagedCards").isArray()) {
                ArrayNode cardsArray = (ArrayNode) root.get("stagedCards");
                ArrayNode remainingCards = objectMapper.createArrayNode();

                for (JsonNode cardNode : cardsArray) {
                    String cardId = cardNode.has("id") ? cardNode.get("id").asText() : "";
                    if (!publishedSet.contains(cardId)) {
                        remainingCards.add(cardNode);
                    }
                }

                if (remainingCards.isEmpty()) {
                    // All cards published! Complete cleanup and retain tombstone
                    onPropertyPublished(adminId, draftId);
                    return null;
                }

                ((ObjectNode) root).set("stagedCards", remainingCards);
                draft.setPayload(objectMapper.writeValueAsString(root));
                draft.setItemCount(remainingCards.size());
                draft.setVersion(draft.getVersion() + 1);
                draft.setUpdatedAt(LocalDateTime.now());
                draftRepository.save(draft);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse batch draft payload JSON during reconciliation for draft [{}]: {}", draftId, e.getMessage());
        }

        return getDraft(adminId, draftId);
    }

    /**
     * Invoked when a single property has successfully been published.
     * Safely purges temporary staged media from B2 and database records,
     * and transitions the draft to a lightweight PUBLISHED tombstone.
     */
    @Transactional
    public void onPropertyPublished(String adminId, String draftId) {
        String cleanAdminId = sanitizeAdminId(adminId);
        validateDraftId(draftId);

        try {
            // 1. Purge all staged media files belonging to this draft from object storage
            List<PropertyDraftMedia> mediaList = draftMediaRepository.findAllByDraftIdAndAdminId(draftId, cleanAdminId);
            for (PropertyDraftMedia media : mediaList) {
                cleanupStagedMedia(media.getStagingObjectKey());
            }
            draftMediaRepository.deleteAllByDraftIdAndAdminId(draftId, cleanAdminId);

            // 2. Mark draft record as lightweight PUBLISHED tombstone, wiping payload to free storage
            draftRepository.findByDraftIdAndAdminId(draftId, cleanAdminId).ifPresent(draft -> {
                draft.setStatus("PUBLISHED");
                draft.setPayload("{}");
                draft.setItemCount(0);
                draft.setUpdatedAt(LocalDateTime.now());
                draftRepository.save(draft);
            });

            activeDraftCache.remove(cleanAdminId + ":" + draftId);
            log.info("Converted draft [{}] into PUBLISHED tombstone and cleaned {} staged media items for admin [{}]",
                    draftId, mediaList.size(), cleanAdminId);
        } catch (EntityNotFoundException e) {
            log.debug("Draft [{}] was already cleaned up", draftId);
        } catch (Exception e) {
            log.warn("Non-fatal error cleaning draft [{}] media after publication: {}", draftId, e.getMessage());
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
