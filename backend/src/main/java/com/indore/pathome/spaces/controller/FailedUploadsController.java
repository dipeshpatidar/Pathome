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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Admin REST controller for viewing, retrying, and dismissing failed media upload records.
 *
 * <p>All endpoints require a valid admin JWT (enforced by the Spring Security filter chain).</p>
 */
@RestController
@RequestMapping("/api/v1/admin/failed-uploads")
@CrossOrigin(origins = "*", maxAge = 3600)
public class FailedUploadsController {

    private static final Logger log = LoggerFactory.getLogger(FailedUploadsController.class);

    private static final java.util.regex.Pattern UPLOAD_REQUEST_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{8,80}$");

    private final FailedUploadService failedUploadService;
    private final CloudinaryService cloudinaryService;
    private final PropertyMediaAssetRepository mediaAssetRepository;
    private final ListingRepository listingRepository;

    public FailedUploadsController(
            FailedUploadService failedUploadService,
            CloudinaryService cloudinaryService,
            PropertyMediaAssetRepository mediaAssetRepository,
            ListingRepository listingRepository) {
        this.failedUploadService = Objects.requireNonNull(failedUploadService);
        this.cloudinaryService = Objects.requireNonNull(cloudinaryService);
        this.mediaAssetRepository = Objects.requireNonNull(mediaAssetRepository);
        this.listingRepository = Objects.requireNonNull(listingRepository);
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
     * Re-attempts the upload for a failed record.
     *
     * <p>If a {@code file} multipart param is provided, it is uploaded to Cloudinary using
     * the original {@code uploadRequestId} (idempotent, {@code overwrite=true}).
     * The existing {@code PropertyMediaAsset} row is upserted and the failure marked RESOLVED.</p>
     *
     * <p>If no file is provided and the original upload already reached Cloudinary but failed
     * at the DB-persist stage, the controller returns a descriptive error asking for a file.</p>
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

        // Concurrent retry guard
        boolean locked = failedUploadService.markRetrying(id);
        if (!locked) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "A retry is already in progress for this upload"));
        }

        // If file is not supplied and listing is known, we need a file
        if ((file == null || file.isEmpty()) && !"DB_PERSIST".equals(failure.getFailureStage())) {
            failedUploadService.recordRetryFailure(id,
                    "A replacement file is required to retry this upload.",
                    "No file supplied and stage is not DB_PERSIST");
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Please select a replacement file to retry this upload"));
        }

        try {
            return executeRetry(failure, file);
        } catch (MediaUploadException mue) {
            failedUploadService.recordRetryFailure(id, mue.getSafeReason(), mue.getDiagnostic());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", mue.getSafeReason()));
        } catch (Exception e) {
            String diagnostic = MediaUploadException.diagnosticFrom(e);
            log.warn("Unexpected error during media retry [failureId={}]: {}", id, diagnostic);
            failedUploadService.recordRetryFailure(id,
                    "The retry could not be completed. Please try again.",
                    diagnostic);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "The retry could not be completed. Please try again."));
        }
    }

    // ---- Private helpers ----

    private ResponseEntity<?> executeRetry(MediaUploadFailure failure, MultipartFile file) {
        Long listingId = failure.getListingId();
        if (listingId == null) {
            failedUploadService.recordRetryFailure(failure.getId(),
                    "This failure has no associated property. Please publish the property first.",
                    "listingId is null");
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "This failure has no associated property. Please publish the property first."));
        }

        // Validate listing still exists
        if (listingRepository.findById(listingId).isEmpty()) {
            failedUploadService.recordRetryFailure(failure.getId(),
                    "The associated property could not be found.",
                    "listingId=" + listingId + " not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "The associated property could not be found."));
        }

        // Upload to Cloudinary with the original idempotency key (overwrite=true in CloudinaryService)
        MediaType mediaType = safeParseMediaType(failure.getMediaType());
        String cdnUrl;
        if (mediaType == MediaType.VIDEO_WALKTHROUGH) {
            cdnUrl = cloudinaryService.uploadVideo(file, failure.getUploadRequestId());
        } else {
            cdnUrl = cloudinaryService.uploadImage(file, failure.getUploadRequestId());
        }

        // Upsert PropertyMediaAsset (idempotency key prevents duplicate row)
        RoomTag roomTag = safeParseRoomTag(failure.getRoomTag());
        PropertyMediaAsset asset = mediaAssetRepository
                .findByListingIdAndUploadRequestId(listingId, failure.getUploadRequestId())
                .orElseGet(() -> {
                    PropertyMediaAsset newAsset = new PropertyMediaAsset();
                    newAsset.setListingId(listingId);
                    newAsset.setUploadRequestId(failure.getUploadRequestId());
                    return newAsset;
                });
        asset.setMediaUrl(cdnUrl);
        asset.setMediaType(mediaType);
        asset.setRoomTag(roomTag);
        asset.setVerificationStatus("ADMIN_RETRY");
        mediaAssetRepository.save(asset);

        // Append URL to gallery (dedup guard is inside updateListingGallery in PropertyController)
        listingRepository.findById(listingId).ifPresent(listing -> {
            String existing = listing.getMediaGalleryUrls();
            if (existing == null || !List.of(existing.split(",")).contains(cdnUrl)) {
                listing.setMediaGalleryUrls(
                        existing == null || existing.isBlank() ? cdnUrl : existing + "," + cdnUrl);
                listingRepository.save(listing);
            }
        });

        failedUploadService.recordResolution(failure.getId(), cdnUrl);

        return ResponseEntity.ok(Map.of(
                "message", "Media uploaded successfully",
                "mediaUrl", cdnUrl,
                "listingId", listingId
        ));
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
