package com.indore.pathome.spaces.exception;

/**
 * Thrown when a media file upload cannot be completed at any stage
 * (pre-upload validation, Cloudinary network call, or database persistence).
 *
 * <p>Carries three distinct fields so callers can separate what is safe to show
 * to end users (safeReason) from what is only suitable for internal diagnostics.</p>
 */
public class MediaUploadException extends RuntimeException {

    public enum Stage {
        /** File failed content-type or size validation before any network call. */
        VALIDATION,
        /** Cloudinary API call failed (network error, service unavailability, etc.). */
        CLOUDINARY_UPLOAD,
        /** Cloudinary upload succeeded but subsequent database write failed. */
        DB_PERSIST
    }

    private final Stage stage;
    private final String safeReason;
    private final String diagnostic;
    private final CloudinaryFailureCategory category;
    private final Integer providerStatusCode;
    private final String storageUrl;
    private final String storagePublicId;

    public MediaUploadException(Stage stage, String safeReason, String diagnostic, Throwable cause) {
        this(stage, safeReason, diagnostic, null, null, null, null, cause);
    }

    public MediaUploadException(Stage stage, String safeReason, String diagnostic) {
        this(stage, safeReason, diagnostic, null, null, null, null, null);
    }

    public MediaUploadException(
            Stage stage,
            String safeReason,
            String diagnostic,
            CloudinaryFailureCategory category,
            Integer providerStatusCode,
            Throwable cause) {
        this(stage, safeReason, diagnostic, category, providerStatusCode, null, null, cause);
    }

    public MediaUploadException(
            Stage stage,
            String safeReason,
            String diagnostic,
            String storageUrl,
            String storagePublicId,
            Throwable cause) {
        this(stage, safeReason, diagnostic, null, null, storageUrl, storagePublicId, cause);
    }

    public MediaUploadException(
            Stage stage,
            String safeReason,
            String diagnostic,
            CloudinaryFailureCategory category,
            Integer providerStatusCode,
            String storageUrl,
            String storagePublicId,
            Throwable cause) {
        super(safeReason, cause);
        this.stage = stage;
        this.safeReason = safeReason;
        this.diagnostic = diagnostic;
        this.category = category;
        this.providerStatusCode = providerStatusCode;
        this.storageUrl = storageUrl;
        this.storagePublicId = storagePublicId;
    }

    public Stage getStage() {
        return stage;
    }

    /** A clean, user-facing message that never exposes internal details. */
    public String getSafeReason() {
        return safeReason;
    }

    /** Exception class name + message for internal diagnostic logs. Never exposed in HTTP responses. */
    public String getDiagnostic() {
        return diagnostic;
    }

    public CloudinaryFailureCategory getCategory() {
        return category;
    }

    public Integer getProviderStatusCode() {
        return providerStatusCode;
    }

    public String getStorageUrl() {
        return storageUrl;
    }

    public String getStoragePublicId() {
        return storagePublicId;
    }

    /** Build a diagnostic string from any throwable without including the stack trace. */
    public static String diagnosticFrom(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
