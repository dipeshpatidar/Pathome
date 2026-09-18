package com.indore.pathome.spaces.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
     * Uploads photo file to Cloudinary under pathome/properties/images
     */
    public String uploadImage(MultipartFile file) {
        return uploadImage(file, null);
    }

    public String uploadImage(MultipartFile file, String uploadRequestId) {
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
        return upload(file, options, false, "Property image");
    }

    /**
     * Uploads video walkthrough MP4 file to Cloudinary under pathome/properties/videos
     */
    public String uploadVideo(MultipartFile file) {
        return uploadVideo(file, null);
    }

    public String uploadVideo(MultipartFile file, String uploadRequestId) {
        validateFile(file, MAX_VIDEO_BYTES, "Property video", "100 MB", "video/");
        Map<String, Object> options = new HashMap<>(ObjectUtils.asMap(
                "folder", "pathome/properties/videos",
                "resource_type", "video",
                "quality", "auto",
                "overwrite", true,
                "unique_filename", uploadRequestId == null
        ));
        if (uploadRequestId != null) options.put("public_id", uploadRequestId);
        return upload(file, options, true, "Property video");
    }

    private String upload(
            MultipartFile file,
            Map<String, Object> options,
            boolean chunked,
            String mediaLabel) {
        try (InputStream inputStream = file.getInputStream()) {
            Map<?, ?> uploadResult = chunked
                    ? cloudinary.uploader().uploadLarge(inputStream, options, VIDEO_CHUNK_BYTES)
                    : cloudinary.uploader().upload(inputStream, options);
            String secureUrl = Objects.toString(uploadResult.get("secure_url"), "");
            if (secureUrl.isBlank()) {
                throw new IOException("Cloudinary response did not include a secure URL");
            }
            logger.info("Successfully uploaded {} to Cloudinary", mediaLabel.toLowerCase());
            return secureUrl;
        } catch (Exception exception) {
            logger.warn("{} upload failed: {}", mediaLabel, exception.getMessage());
            throw new IllegalStateException(mediaLabel + " upload could not be completed", exception);
        }
    }

    private void validateFile(
            MultipartFile file,
            long maximumBytes,
            String mediaLabel,
            String limitLabel,
            String contentTypePrefix) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(mediaLabel + " cannot be empty");
        }
        if (file.getSize() > maximumBytes) {
            throw new IllegalArgumentException(mediaLabel + " must be " + limitLabel + " or smaller");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith(contentTypePrefix)) {
            throw new IllegalArgumentException(mediaLabel + " has an unsupported file type");
        }
    }

}
