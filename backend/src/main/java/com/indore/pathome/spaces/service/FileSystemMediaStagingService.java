package com.indore.pathome.spaces.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Local filesystem fallback implementation of {@link MediaStagingService}.
 *
 * <p>Used when external S3 storage is not configured (e.g. local development,
 * offline CI environments, unit/integration testing). Ensures all staging workflows
 * operate correctly without requiring external cloud accounts or fabricated credentials.</p>
 */
public class FileSystemMediaStagingService implements MediaStagingService {

    private static final Logger log = LoggerFactory.getLogger(FileSystemMediaStagingService.class);

    private final Path rootDir;

    public FileSystemMediaStagingService(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            this.rootDir = Paths.get(System.getProperty("java.io.tmpdir"), "pathome-media-staging");
        } else {
            this.rootDir = Paths.get(basePath);
        }
        try {
            Files.createDirectories(this.rootDir);
            log.info("Initialized FileSystemMediaStagingService at: {}", this.rootDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Could not create staging directory: {}", this.rootDir, e);
            throw new RuntimeException("Could not initialize local staging directory", e);
        }
    }

    public FileSystemMediaStagingService(Path rootDir) {
        this.rootDir = Objects.requireNonNull(rootDir);
        try {
            Files.createDirectories(this.rootDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize local staging directory", e);
        }
    }

    @Override
    public String stage(String objectKey, InputStream inputStream, long contentLength, String contentType) {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(inputStream, "inputStream must not be null");

        Path targetPath = resolveSafePath(objectKey);
        try {
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Staged media locally at: {}", targetPath.toAbsolutePath());
            return objectKey;
        } catch (IOException e) {
            log.error("Failed to stage object locally [{}]: {}", objectKey, e.getMessage());
            throw new RuntimeException("Local staging failure: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream retrieve(String objectKey) {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Path targetPath = resolveSafePath(objectKey);
        if (!Files.exists(targetPath) || !Files.isRegularFile(targetPath)) {
            throw new IllegalStateException("Staged media file missing or expired: " + objectKey);
        }
        try {
            return Files.newInputStream(targetPath);
        } catch (IOException e) {
            log.error("Failed to open staged file [{}]: {}", objectKey, e.getMessage());
            throw new RuntimeException("Error reading staged file: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return false;
        try {
            Path targetPath = resolveSafePath(objectKey);
            return Files.exists(targetPath) && Files.isRegularFile(targetPath);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            Path targetPath = resolveSafePath(objectKey);
            Files.deleteIfExists(targetPath);
            log.info("Deleted local staged file: {}", objectKey);
        } catch (Exception e) {
            log.warn("Failed to delete local staged file [{}] (non-fatal): {}", objectKey, e.getMessage());
        }
    }

    @Override
    public boolean isConfigured() {
        return false; // Indicates fallback local staging, not external S3
    }

    private Path resolveSafePath(String objectKey) {
        // Prevent path traversal
        String sanitizedKey = objectKey.replace("..", "").replaceAll("^/+", "");
        Path resolved = rootDir.resolve(sanitizedKey).normalize();
        if (!resolved.startsWith(rootDir)) {
            throw new SecurityException("Illegal staging object key outside root directory: " + objectKey);
        }
        return resolved;
    }
}
