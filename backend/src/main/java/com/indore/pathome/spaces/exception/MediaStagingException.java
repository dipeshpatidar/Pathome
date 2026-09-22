package com.indore.pathome.spaces.exception;

/**
 * Thrown when a temporary media file cannot be staged to private object storage (B2/S3/FileSystem).
 *
 * <p>Separates transient network/storage interruptions (mapped to HTTP 503 Service Unavailable)
 * from permanent infrastructure or permission misconfigurations (mapped to HTTP 500 Internal Server Error).
 * Preserves a safe user-facing reason separate from internal diagnostic details.</p>
 */
public class MediaStagingException extends RuntimeException {

    private final boolean transientFailure;
    private final String safeReason;
    private final String stagingKey;

    public MediaStagingException(String safeReason, String diagnostic, String stagingKey, boolean transientFailure, Throwable cause) {
        super(diagnostic, cause);
        this.safeReason = safeReason;
        this.stagingKey = stagingKey;
        this.transientFailure = transientFailure;
    }

    public MediaStagingException(String safeReason, String diagnostic, String stagingKey, boolean transientFailure) {
        this(safeReason, diagnostic, stagingKey, transientFailure, null);
    }

    public boolean isTransient() {
        return transientFailure;
    }

    public String getSafeReason() {
        return safeReason;
    }

    public String getStagingKey() {
        return stagingKey;
    }
}
