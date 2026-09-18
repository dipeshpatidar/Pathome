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

    public MediaUploadException(Stage stage, String safeReason, String diagnostic, Throwable cause) {
        super(safeReason, cause);
        this.stage = stage;
        this.safeReason = safeReason;
        this.diagnostic = diagnostic;
    }

    public MediaUploadException(Stage stage, String safeReason, String diagnostic) {
        super(safeReason);
        this.stage = stage;
        this.safeReason = safeReason;
        this.diagnostic = diagnostic;
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

    /** Build a diagnostic string from any throwable without including the stack trace. */
    public static String diagnosticFrom(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
