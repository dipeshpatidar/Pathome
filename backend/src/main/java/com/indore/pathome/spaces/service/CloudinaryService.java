package com.indore.pathome.spaces.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.indore.pathome.spaces.exception.MediaUploadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class CloudinaryService {

    private static final Logger logger = LoggerFactory.getLogger(CloudinaryService.class);
    static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    static final long MAX_VIDEO_BYTES = 100L * 1024L * 1024L;
    private static final int VIDEO_CHUNK_BYTES = 6 * 1024 * 1024;

    private final Cloudinary cloudinary;

    public CloudinaryService(Cloudinary cloudinary) {
        this.cloudinary = Objects.requireNonNull(cloudinary, "Cloudinary must not be null");
    }

    /**
     * Authoritative metadata returned by Cloudinary upon successful upload.
     */
    public record CloudinaryUploadResult(
            String secureUrl,
            String publicId,
            String resourceType
    ) {}

    private static final int MAX_IMMEDIATE_ATTEMPTS = 2;
    private static final long TRANSIENT_BACKOFF_MS = 300L;
    private static final long RATE_LIMIT_BACKOFF_MS = 600L;

    /** Uploads photo file to Cloudinary under pathome/properties/images */
    public String uploadImage(MultipartFile file) {
        return uploadImage(file, null);
    }

    public String uploadImage(MultipartFile file, String uploadRequestId) {
        return uploadImageResult(file, uploadRequestId).secureUrl();
    }

    public CloudinaryUploadResult uploadImageResult(MultipartFile file, String uploadRequestId) {
        validateFile(file, MAX_IMAGE_BYTES, "Property image", "10 MB", "image/");
        Map<String, Object> options = new HashMap<>(ObjectUtils.asMap(
                "folder", "pathome/properties/images",
                "resource_type", "image",
                "format", "webp",
                "quality", "auto",
                "overwrite", true,
                "unique_filename", uploadRequestId == null
        ));
        if (uploadRequestId != null) options.put("public_id", uploadRequestId);
        return uploadWithImmediateRetry(file, options, false, "Property image");
    }

    /** Uploads video walkthrough MP4 file to Cloudinary under pathome/properties/videos */
    public String uploadVideo(MultipartFile file) {
        return uploadVideo(file, null);
    }

    public String uploadVideo(MultipartFile file, String uploadRequestId) {
        return uploadVideoResult(file, uploadRequestId).secureUrl();
    }

    public CloudinaryUploadResult uploadVideoResult(MultipartFile file, String uploadRequestId) {
        validateFile(file, MAX_VIDEO_BYTES, "Property video", "100 MB", "video/");
        Map<String, Object> options = new HashMap<>(ObjectUtils.asMap(
                "folder", "pathome/properties/videos",
                "resource_type", "video",
                "quality", "auto",
                "overwrite", true,
                "unique_filename", uploadRequestId == null
        ));
        if (uploadRequestId != null) options.put("public_id", uploadRequestId);
        return uploadWithImmediateRetry(file, options, true, "Property video");
    }

    /**
     * Uploads media stream directly from staging storage to Cloudinary without loading into JVM heap.
     */
    public CloudinaryUploadResult uploadStreamResult(
            InputStream inputStream,
            boolean isVideo,
            String uploadRequestId) {

        Objects.requireNonNull(inputStream, "inputStream must not be null");
        String folder = isVideo ? "pathome/properties/videos" : "pathome/properties/images";
        String resourceType = isVideo ? "video" : "image";
        String label = isVideo ? "Property video" : "Property image";

        Map<String, Object> options = new HashMap<>(ObjectUtils.asMap(
                "folder", folder,
                "resource_type", resourceType,
                "quality", "auto",
                "overwrite", true,
                "unique_filename", uploadRequestId == null
        ));
        if (!isVideo) {
            options.put("format", "webp");
        }
        if (uploadRequestId != null) options.put("public_id", uploadRequestId);

        try {
            Map<?, ?> uploadResult = isVideo
                    ? cloudinary.uploader().uploadLarge(inputStream, options, VIDEO_CHUNK_BYTES)
                    : cloudinary.uploader().upload(inputStream, options);

            return extractResult(uploadResult, label);
        } catch (Exception e) {
            CloudinaryErrorClassifier.ClassificationResult cr = CloudinaryErrorClassifier.classify(e);
            logger.warn("Staged stream upload failed [{}]: {}", cr.category(), cr.sanitizedDiagnostic());
            throw new MediaUploadException(
                    MediaUploadException.Stage.CLOUDINARY_UPLOAD,
                    cr.safeReason(),
                    cr.sanitizedDiagnostic(),
                    cr.category(),
                    cr.statusCode(),
                    e
            );
        }
    }

    private CloudinaryUploadResult uploadWithImmediateRetry(
            MultipartFile file,
            Map<String, Object> options,
            boolean chunked,
            String mediaLabel) {

        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_IMMEDIATE_ATTEMPTS) {
            attempt++;
            try {
                Map<?, ?> uploadResult;
                if (chunked) {
                    try (InputStream inputStream = file.getInputStream()) {
                        uploadResult = cloudinary.uploader().uploadLarge(inputStream, options, VIDEO_CHUNK_BYTES);
                    }
                } else {
                    uploadResult = cloudinary.uploader().upload(file.getBytes(), options);
                }
                return extractResult(uploadResult, mediaLabel);
            } catch (MediaUploadException mue) {
                throw mue;
            } catch (Exception exception) {
                lastException = exception;
                CloudinaryErrorClassifier.ClassificationResult cr = CloudinaryErrorClassifier.classify(exception);

                // Check Level 1 retry eligibility
                if (attempt < MAX_IMMEDIATE_ATTEMPTS && cr.shouldRetryImmediately()) {
                    long backoff = (cr.category() == com.indore.pathome.spaces.exception.CloudinaryFailureCategory.RATE_LIMITED)
                            ? RATE_LIMIT_BACKOFF_MS
                            : TRANSIENT_BACKOFF_MS;
                    logger.info("Immediate retry attempt {} for {} after {} ms [category={}]",
                            attempt, mediaLabel, backoff, cr.category());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                // If not retryable or max attempts exhausted, break and throw
                break;
            }
        }

        // Failure handling after immediate retry exhaustion or non-retryable error
        CloudinaryErrorClassifier.ClassificationResult cr = CloudinaryErrorClassifier.classify(lastException);
        logger.warn("{} upload failed [category={}]: {}", mediaLabel, cr.category(), cr.sanitizedDiagnostic());
        throw new MediaUploadException(
                MediaUploadException.Stage.CLOUDINARY_UPLOAD,
                cr.safeReason(),
                cr.sanitizedDiagnostic(),
                cr.category(),
                cr.statusCode(),
                lastException
        );
    }

    private CloudinaryUploadResult extractResult(Map<?, ?> uploadResult, String mediaLabel) {
        String secureUrl = Objects.toString(uploadResult.get("secure_url"), "");
        String publicId = Objects.toString(uploadResult.get("public_id"), "");
        String resourceType = Objects.toString(uploadResult.get("resource_type"), "");

        if (secureUrl.isBlank()) {
            throw new MediaUploadException(
                    MediaUploadException.Stage.CLOUDINARY_UPLOAD,
                    "The storage service did not return a valid URL. Please try again.",
                    "Cloudinary response missing secure_url",
                    com.indore.pathome.spaces.exception.CloudinaryFailureCategory.UNKNOWN,
                    null,
                    null
            );
        }
        logger.info("Successfully uploaded {} to Cloudinary (publicId={})", mediaLabel.toLowerCase(), publicId);
        return new CloudinaryUploadResult(secureUrl, publicId, resourceType);
    }

    private void validateFile(
            MultipartFile file,
            long maximumBytes,
            String mediaLabel,
            String limitLabel,
            String contentTypePrefix) {
        if (file == null || file.isEmpty()) {
            throw new MediaUploadException(
                    MediaUploadException.Stage.VALIDATION,
                    mediaLabel + " cannot be empty.",
                    "File is null or empty"
            );
        }
        if (file.getSize() > maximumBytes) {
            throw new MediaUploadException(
                    MediaUploadException.Stage.VALIDATION,
                    mediaLabel + " upload failed because the file exceeds the allowed size of " + limitLabel + ".",
                    "File size " + file.getSize() + " exceeds limit " + maximumBytes
            );
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith(contentTypePrefix)) {
            throw new MediaUploadException(
                    MediaUploadException.Stage.VALIDATION,
                    "This " + (contentTypePrefix.startsWith("image") ? "image" : "video")
                            + " format is not supported. Please choose a supported file type.",
                    "Unsupported content-type: " + contentType
            );
        }
    }

    /**
     * Reconciles deterministic Cloudinary asset for expired claim crash recovery.
     * Uses Admin API resource() lookup with exact public_id:
     * - Images: pathome/properties/images/{uploadRequestId}
     * - Videos: pathome/properties/videos/{uploadRequestId}
     *
     * Returns:
     * - Optional.of(result) if asset exists in Cloudinary
     * - Optional.empty() ONLY if Cloudinary definitively returns NotFound (HTTP 404)
     * - Throws MediaUploadException for ambiguous errors, rate limits, network timeouts, auth errors.
     *   Ambiguous errors MUST NOT be treated as "not found" to prevent duplicate uploads.
     */
    public java.util.Optional<CloudinaryUploadResult> findExistingResourceByUploadRequestId(
            String uploadRequestId,
            boolean isVideo) {

        if (uploadRequestId == null || uploadRequestId.isBlank()) {
            return java.util.Optional.empty();
        }

        String folder = isVideo ? "pathome/properties/videos" : "pathome/properties/images";
        String publicId = folder + "/" + uploadRequestId.trim();
        String resourceType = isVideo ? "video" : "image";
        Map<String, Object> options = ObjectUtils.asMap("resource_type", resourceType);

        try {
            Map<?, ?> response = cloudinary.api().resource(publicId, options);
            if (response != null) {
                String secureUrl = Objects.toString(response.get("secure_url"), "");
                String returnedPublicId = Objects.toString(response.get("public_id"), publicId);
                String returnedResourceType = Objects.toString(response.get("resource_type"), resourceType);
                if (!secureUrl.isBlank()) {
                    logger.info("Reconciled existing Cloudinary asset (publicId={}): {}", returnedPublicId, secureUrl);
                    return java.util.Optional.of(new CloudinaryUploadResult(secureUrl, returnedPublicId, returnedResourceType));
                }
            }
            return java.util.Optional.empty();
        } catch (com.cloudinary.api.exceptions.NotFound notFound) {
            logger.info("Cloudinary asset definitively not found for publicId={}", publicId);
            return java.util.Optional.empty();
        } catch (Exception e) {
            CloudinaryErrorClassifier.ClassificationResult cr = CloudinaryErrorClassifier.classify(e);
            logger.warn("Cloudinary reconciliation failed ambiguously for publicId={} [{}]: {}",
                    publicId, cr.category(), cr.sanitizedDiagnostic());
            throw new MediaUploadException(
                    MediaUploadException.Stage.CLOUDINARY_UPLOAD,
                    "Unable to verify existing media in cloud storage. Please try again.",
                    "Cloudinary reconciliation error: " + cr.sanitizedDiagnostic(),
                    cr.category(),
                    cr.statusCode(),
                    e
            );
        }
    }
}
