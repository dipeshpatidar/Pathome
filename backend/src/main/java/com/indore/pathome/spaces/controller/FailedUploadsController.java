package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.entity.MediaUploadFailure;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.exception.MediaUploadException;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.indore.pathome.spaces.service.FailedUploadService;
import com.indore.pathome.spaces.service.MediaStagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Admin REST controller for viewing, retrying, and dismissing failed media upload records.
 *
 * <p>All endpoints require a valid admin JWT (enforced by the Spring Security filter chain and method security).</p>
 */
@RestController
@RequestMapping("/api/v1/admin/failed-uploads")
@PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
@CrossOrigin(origins = "*", maxAge = 3600)
public class FailedUploadsController {

    private static final Logger log = LoggerFactory.getLogger(FailedUploadsController.class);

    private static final java.util.regex.Pattern UPLOAD_REQUEST_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{8,80}$");

    private final FailedUploadService failedUploadService;
    private final CloudinaryService cloudinaryService;
    private final PropertyMediaAssetRepository mediaAssetRepository;
    private final ListingRepository listingRepository;
    private final MediaStagingService mediaStagingService;

    @org.springframework.beans.factory.annotation.Autowired
    public FailedUploadsController(
            FailedUploadService failedUploadService,
            CloudinaryService cloudinaryService,
            PropertyMediaAssetRepository mediaAssetRepository,
            ListingRepository listingRepository,
            @org.springframework.beans.factory.annotation.Qualifier("mediaStagingService") MediaStagingService mediaStagingService) {
        this.failedUploadService = Objects.requireNonNull(failedUploadService);
        this.cloudinaryService = Objects.requireNonNull(cloudinaryService);
        this.mediaAssetRepository = Objects.requireNonNull(mediaAssetRepository);
        this.listingRepository = Objects.requireNonNull(listingRepository);
        this.mediaStagingService = mediaStagingService;
    }

    public FailedUploadsController(
            FailedUploadService failedUploadService,
            CloudinaryService cloudinaryService,
            PropertyMediaAssetRepository mediaAssetRepository,
            ListingRepository listingRepository) {
        this(failedUploadService, cloudinaryService, mediaAssetRepository, listingRepository, failedUploadService.getMediaStagingService());
    }

    /**
     * GET /api/v1/admin/failed-uploads
     * Returns all unresolved failures (FAILED + RETRYING), newest first.
     */
    @GetMapping
    public ResponseEntity<List<MediaUploadFailure>> listUnresolved() {
        return ResponseEntity.ok(failedUploadService.getUnresolved());
    }

    /**
     * GET /api/v1/admin/failed-uploads/count
     * Returns the count of unresolved failures for badge display.
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> unresolvedCount() {
        return ResponseEntity.ok(Map.of("count", failedUploadService.countUnresolved()));
    }

    /**
     * GET /api/v1/admin/failed-uploads/{id}
     * Returns a single failure record.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return failedUploadService.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("Failed upload record not found"));
    }

    /**
     * POST /api/v1/admin/failed-uploads/{id}/dismiss
     * Marks a failure record as DISMISSED so it no longer appears in the default view.
     */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Map<String, String>> dismiss(@PathVariable Long id) {
        Optional<MediaUploadFailure> opt = failedUploadService.getById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Failed upload record not found"));
        }
        failedUploadService.dismiss(id);
        return ResponseEntity.ok(Map.of("message", "Upload issue dismissed successfully"));
    }

    /**
     * POST /api/v1/admin/failed-uploads/{id}/retry
     * Re-attempts recovery for a failed upload.
     *
     * <p>If {@code file} is null or empty, this is a ONE-CLICK AUTOMATIC RETRY (no file picker):
     * <ul>
     *   <li>For DB_PERSIST: reconciles database without re-uploading to Cloudinary.</li>
     *   <li>For STAGED_MEDIA_RETRY: streams original binary from private temporary object storage.</li>
     *   <li>If staged media is missing/expired: returns 400 Bad Request indicating Replace File is required.</li>
     * </ul>
     *
     * <p>If {@code file} is provided, this is a MANUAL REPLACEMENT:
     * uploads the replacement file to Cloudinary with {@code overwrite=true} using original {@code uploadRequestId}.</p>
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(
            @PathVariable Long id,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        Optional<MediaUploadFailure> opt = failedUploadService.getById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Failed upload record not found"));
        }
        MediaUploadFailure failure = opt.get();

        if ("RESOLVED".equals(failure.getStatus()) || "DISMISSED".equals(failure.getStatus())) {
            return ResponseEntity.ok(Map.of("message", "This upload has already been resolved"));
        }

        // Automatic retry (no file passed) vs Replace File (file passed)
        if (file == null || file.isEmpty()) {
            return handleAutomaticRetry(id, failure);
        } else {
            return handleReplaceFile(id, failure, file);
        }
    }

    private ResponseEntity<?> handleAutomaticRetry(Long id, MediaUploadFailure failure) {
        String strategy = failure.getRecoveryStrategy();

        if ("DATABASE_RECONCILIATION".equals(strategy)) {
            // Atomic state lock
            boolean locked = failedUploadService.markRetrying(id);
            if (!locked) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "A retry is already in progress for this upload"));
            }
            try {
                return reconcileDatabase(failure);
            } catch (Exception e) {
                String diagnostic = MediaUploadException.diagnosticFrom(e);
                log.warn("Database reconciliation failed [failureId={}]: {}", id, diagnostic);
                failedUploadService.recordRetryFailure(id,
                        "Could not reconcile property database record. Please try again.",
                        diagnostic);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("message", "Could not reconcile property database record. Please try again."));
            }
        }

        if ("STAGED_MEDIA_RETRY".equals(strategy)) {
            String stagingKey = failure.getStagingObjectKey();
            if (mediaStagingService == null || !mediaStagingService.exists(stagingKey)) {
                // Staged object expired or missing — do not increment retry count, return clear explanation
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "The original staged media file has expired or is unavailable. Please use 'Replace File' to upload a new file."));
            }

            // Atomic state lock
            boolean locked = failedUploadService.markRetrying(id);
            if (!locked) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "A retry is already in progress for this upload"));
            }

            try {
                return retryFromStagedMedia(failure);
            } catch (MediaUploadException mue) {
                failedUploadService.recordRetryFailure(id, mue.getSafeReason(), mue.getDiagnostic());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("message", mue.getSafeReason()));
            } catch (Exception e) {
                String diagnostic = MediaUploadException.diagnosticFrom(e);
                log.warn("Staged media retry failed [failureId={}]: {}", id, diagnostic);
                failedUploadService.recordRetryFailure(id,
                        "The retry could not be completed. Please try again.",
                        diagnostic);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("message", "The retry could not be completed. Please try again."));
            }
        }

        // Recovery strategy is REPLACE_FILE_REQUIRED
        return ResponseEntity.badRequest()
                .body(Map.of("message", "This upload cannot be retried automatically. Please use 'Replace File' to provide a replacement file."));
    }

    private ResponseEntity<?> handleReplaceFile(Long id, MediaUploadFailure failure, MultipartFile file) {
        // Atomic state lock
        boolean locked = failedUploadService.markRetrying(id);
        if (!locked) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "A retry is already in progress for this upload"));
        }

        try {
            return executeRetryWithFile(failure, file);
        } catch (MediaUploadException mue) {
            failedUploadService.recordRetryFailure(id, mue.getSafeReason(), mue.getDiagnostic());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", mue.getSafeReason()));
        } catch (Exception e) {
            String diagnostic = MediaUploadException.diagnosticFrom(e);
            log.warn("Replace file upload failed [failureId={}]: {}", id, diagnostic);
            failedUploadService.recordRetryFailure(id,
                    "The replacement upload could not be completed. Please try again.",
                    diagnostic);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "The replacement upload could not be completed. Please try again."));
        }
    }

    private ResponseEntity<?> reconcileDatabase(MediaUploadFailure failure) {
        Long listingId = failure.getListingId();
        if (listingId == null || listingRepository.findById(listingId).isEmpty()) {
            failedUploadService.recordRetryFailure(failure.getId(),
                    "The associated property could not be found.",
                    "listingId=" + listingId + " not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "The associated property could not be found."));
        }

        String cdnUrl = failure.getStorageUrl();
        MediaType mediaType = safeParseMediaType(failure.getMediaType());
        RoomTag roomTag = safeParseRoomTag(failure.getRoomTag());

        // Upsert PropertyMediaAsset without calling Cloudinary again
        upsertMediaAssetAndGallery(listingId, failure.getUploadRequestId(), cdnUrl, mediaType, roomTag, "ADMIN_RECONCILED");

        failedUploadService.recordResolution(failure.getId(), cdnUrl);

        return ResponseEntity.ok(Map.of(
                "message", "Media reconciled and saved to property successfully",
                "mediaUrl", cdnUrl,
                "listingId", listingId
        ));
    }

    private ResponseEntity<?> retryFromStagedMedia(MediaUploadFailure failure) throws Exception {
        Long listingId = failure.getListingId();
        if (listingId == null || listingRepository.findById(listingId).isEmpty()) {
            failedUploadService.recordRetryFailure(failure.getId(),
                    "The associated property could not be found.",
                    "listingId=" + listingId + " not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "The associated property could not be found."));
        }

        boolean isVideo = "VIDEO_WALKTHROUGH".equalsIgnoreCase(failure.getMediaType());

        CloudinaryService.CloudinaryUploadResult uploadResult;
        try (java.io.InputStream stream = mediaStagingService.retrieve(failure.getStagingObjectKey())) {
            uploadResult = cloudinaryService.uploadStreamResult(stream, isVideo, failure.getUploadRequestId());
        }

        String cdnUrl = uploadResult.secureUrl();
        MediaType mediaType = safeParseMediaType(failure.getMediaType());
        RoomTag roomTag = safeParseRoomTag(failure.getRoomTag());

        try {
            upsertMediaAssetAndGallery(listingId, failure.getUploadRequestId(), cdnUrl, mediaType, roomTag, "ADMIN_RETRY");
            failedUploadService.recordResolution(failure.getId(), cdnUrl);

            return ResponseEntity.ok(Map.of(
                    "message", "Media automatically recovered and uploaded successfully",
                    "mediaUrl", cdnUrl,
                    "listingId", listingId
            ));
        } catch (Exception dbEx) {
            String dbDiagnostic = MediaUploadException.diagnosticFrom(dbEx);
            log.error("Cloudinary succeeded during staged retry but DB persistence failed [failureId={}]: {}", failure.getId(), dbDiagnostic);
            failedUploadService.transitionToDbPersistFailure(
                    failure.getId(),
                    cdnUrl,
                    uploadResult.publicId(),
                    "Media uploaded successfully to storage but failed to save to property records.",
                    dbDiagnostic
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Media uploaded to storage but could not be saved to property records. Please retry to reconcile."));
        }
    }

    private ResponseEntity<?> executeRetryWithFile(MediaUploadFailure failure, MultipartFile file) {
        Long listingId = failure.getListingId();
        if (listingId == null || listingRepository.findById(listingId).isEmpty()) {
            failedUploadService.recordRetryFailure(failure.getId(),
                    "The associated property could not be found.",
                    "listingId=" + listingId + " not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "The associated property could not be found."));
        }

        MediaType mediaType = safeParseMediaType(failure.getMediaType());
        String cdnUrl;
        if (mediaType == MediaType.VIDEO_WALKTHROUGH) {
            cdnUrl = cloudinaryService.uploadVideo(file, failure.getUploadRequestId());
        } else {
            cdnUrl = cloudinaryService.uploadImage(file, failure.getUploadRequestId());
        }

        RoomTag roomTag = safeParseRoomTag(failure.getRoomTag());
        try {
            upsertMediaAssetAndGallery(listingId, failure.getUploadRequestId(), cdnUrl, mediaType, roomTag, "ADMIN_REPLACE");
            failedUploadService.recordResolution(failure.getId(), cdnUrl);

            return ResponseEntity.ok(Map.of(
                    "message", "Replacement media uploaded successfully",
                    "mediaUrl", cdnUrl,
                    "listingId", listingId
            ));
        } catch (Exception dbEx) {
            String dbDiagnostic = MediaUploadException.diagnosticFrom(dbEx);
            log.error("Cloudinary succeeded during replace upload but DB persistence failed [failureId={}]: {}", failure.getId(), dbDiagnostic);
            failedUploadService.transitionToDbPersistFailure(
                    failure.getId(),
                    cdnUrl,
                    failure.getUploadRequestId(),
                    "Replacement media uploaded successfully to storage but failed to save to property records.",
                    dbDiagnostic
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Media uploaded to storage but could not be saved to property records. Please retry to reconcile."));
        }
    }

    private void upsertMediaAssetAndGallery(
            Long listingId,
            String uploadRequestId,
            String cdnUrl,
            MediaType mediaType,
            RoomTag roomTag,
            String verificationStatus) {

        PropertyMediaAsset asset = mediaAssetRepository
                .findByListingIdAndUploadRequestId(listingId, uploadRequestId)
                .orElseGet(() -> {
                    PropertyMediaAsset newAsset = new PropertyMediaAsset();
                    newAsset.setListingId(listingId);
                    newAsset.setUploadRequestId(uploadRequestId);
                    return newAsset;
                });
        asset.setMediaUrl(cdnUrl);
        asset.setMediaType(mediaType);
        asset.setRoomTag(roomTag);
        asset.setVerificationStatus(verificationStatus);
        mediaAssetRepository.save(asset);

        listingRepository.findById(listingId).ifPresent(listing -> {
            String existing = listing.getMediaGalleryUrls();
            if (existing == null || !List.of(existing.split(",")).contains(cdnUrl)) {
                listing.setMediaGalleryUrls(
                        existing == null || existing.isBlank() ? cdnUrl : existing + "," + cdnUrl);
                listingRepository.save(listing);
            }
        });
    }

    private MediaType safeParseMediaType(String value) {
        try {
            return MediaType.valueOf(value);
        } catch (Exception e) {
            return MediaType.IMAGE;
        }
    }

    private RoomTag safeParseRoomTag(String value) {
        try {
            return RoomTag.valueOf(value);
        } catch (Exception e) {
            return RoomTag.GENERAL;
        }
    }
}
