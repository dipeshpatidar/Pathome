package com.indore.pathome.spaces.exception;

/**
 * Structured classification of Cloudinary storage upload failures.
 *
 * <p>Used to drive recovery decisions (immediate retry vs. staging vs. manual replacement)
 * rather than relying on unclassified raw exceptions or brittle message matching.</p>
 */
public enum CloudinaryFailureCategory {
    /** Network/transport timeouts, connection resets, DNS failures, Cloudinary 5xx. */
    TRANSIENT,

    /** HTTP 429 / concurrency rate limit. */
    RATE_LIMITED,

    /** Invalid API key/secret, unauthorized cloud name, disabled account (HTTP 401/403). */
    AUTHENTICATION_OR_CONFIGURATION,

    /** Malformed parameters, invalid upload presets, syntax error (HTTP 400). */
    INVALID_REQUEST,

    /** Corrupted media, unsupported internal codec, deterministic provider rejection. */
    MEDIA_REJECTED,

    /** Cloudinary account storage, transformation, or monthly credit ceiling reached. */
    ACCOUNT_OR_QUOTA,

    /** Unclassified or unexpected provider failure. UNKNOWN does NOT mean TRANSIENT. */
    UNKNOWN;

    /**
     * Indicates if an upload failure under this category is eligible for binary staging
     * and subsequent automatic retry. Returns false only for deterministic media rejection.
     */
    public boolean isStagingEligible() {
        return this != MEDIA_REJECTED;
    }
}

