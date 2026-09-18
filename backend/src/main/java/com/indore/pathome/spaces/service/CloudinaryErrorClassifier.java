package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.exception.CloudinaryFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized, production-grade classifier for Cloudinary upload exceptions and errors.
 *
 * <p>Enforces strict classification priority:
 * <ol>
 *   <li>Typed Java / transport / SDK exceptions</li>
 *   <li>HTTP / provider status codes</li>
 *   <li>Structured provider error messages</li>
 *   <li>Conservative known-pattern message inspection</li>
 *   <li>UNKNOWN fallback (strictly non-transient)</li>
 * </ol>
 *
 * <p>All regular expressions are pre-compiled {@code private static final Pattern} constants.
 * All diagnostics are scrubbed of credentials and secrets before being returned.</p>
 */
public final class CloudinaryErrorClassifier {

    private static final Logger log = LoggerFactory.getLogger(CloudinaryErrorClassifier.class);

    // Pre-compiled regex patterns for status code extraction
    private static final Pattern STATUS_CODE_PATTERN =
            Pattern.compile("(?:status(?:[ _]code)?|http(?:_status)?)[:\\s=-]+(\\d{3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_HTTP_CODE_PATTERN =
            Pattern.compile("\"http_code\"\\s*:\\s*(\\d{3})");

    // Pre-compiled regex patterns for credential sanitization
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("(api[_-]?key)=([^&\\s,;\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern API_SECRET_PATTERN =
            Pattern.compile("(api[_-]?secret)=([^&\\s,;\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGNATURE_PATTERN =
            Pattern.compile("(signature)=([^&\\s,;\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(Bearer\\s+)[A-Za-z0-9._~+/-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOUDINARY_URL_SECRET_PATTERN =
            Pattern.compile("cloudinary://[^:]+:([^@]+)@", Pattern.CASE_INSENSITIVE);

    // Pre-compiled patterns for conservative message classification
    private static final Pattern RATE_LIMIT_MSG_PATTERN =
            Pattern.compile("\\b(rate limit|too many requests|rate_limited)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_MSG_PATTERN =
            Pattern.compile("\\b(invalid api[ _]?key|authorization required|unauthorized|not allowed|invalid signature)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTA_MSG_PATTERN =
            Pattern.compile("\\b(quota exceeded|storage limit|credit limit|monthly limit|account disabled)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_REJECTED_MSG_PATTERN =
            Pattern.compile("\\b(invalid image file|corrupted image|unsupported format|bad file format|file is truncated|unsupported video format)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INVALID_REQUEST_MSG_PATTERN =
            Pattern.compile("\\b(invalid parameter|invalid upload preset|transformation error|bad request)\\b", Pattern.CASE_INSENSITIVE);

    // Safe user-facing operational messages
    public static final String MSG_TRANSIENT =
            "Media storage was temporarily unavailable. The original file has been preserved and can be retried.";
    public static final String MSG_RATE_LIMITED =
            "Media storage is temporarily busy. The original file has been preserved and can be retried shortly.";
    public static final String MSG_AUTH_CONFIG =
            "Media storage is temporarily unavailable due to a system configuration issue. The original file has been preserved. Please retry after the issue is resolved.";
    public static final String MSG_QUOTA =
            "Media storage is currently unavailable due to a service limitation. The original file has been preserved for retry.";
    public static final String MSG_MEDIA_REJECTED =
            "This media could not be accepted. Please replace the file.";
    public static final String MSG_INVALID_REQUEST =
            "Media upload could not be completed due to invalid request parameters. The original file has been preserved and can be retried.";
    public static final String MSG_UNKNOWN =
            "Media upload could not be completed. The original file has been preserved where possible and can be retried.";

    private CloudinaryErrorClassifier() {}

    /**
     * Immutable classification result.
     */
    public record ClassificationResult(
            CloudinaryFailureCategory category,
            Integer statusCode,
            String safeReason,
            String sanitizedDiagnostic
    ) {
        /** True only for genuine temporary network/provider disruptions. */
        public boolean shouldRetryImmediately() {
            return category == CloudinaryFailureCategory.TRANSIENT
                    || category == CloudinaryFailureCategory.RATE_LIMITED;
        }

        /** True if the original binary may succeed on later retry and should be preserved. */
        public boolean isStagingEligible() {
            // All categories except MEDIA_REJECTED are staging-eligible.
            // When media is deterministically rejected, the binary cannot succeed on re-upload.
            return category != CloudinaryFailureCategory.MEDIA_REJECTED;
        }
    }

    /**
     * Classifies a throwable from a Cloudinary operation.
     *
     * @param throwable the caught exception
     * @return structured classification result
     */
    public static ClassificationResult classify(Throwable throwable) {
        if (throwable == null) {
            return new ClassificationResult(
                    CloudinaryFailureCategory.UNKNOWN,
                    null,
                    MSG_UNKNOWN,
                    "Unknown null exception"
            );
        }

        String rawDiagnostic = extractRawDiagnostic(throwable);
        String sanitizedDiagnostic = sanitize(rawDiagnostic);

        // Signal 1: Typed Java / Transport / Provider SDK Exceptions
        Throwable rootCause = findRootCause(throwable);
        if (isTypedTransientException(throwable) || isTypedTransientException(rootCause)) {
            return new ClassificationResult(
                    CloudinaryFailureCategory.TRANSIENT,
                    null,
                    MSG_TRANSIENT,
                    sanitizedDiagnostic
            );
        }

        // Check typed Cloudinary API exceptions via class name / reflection to avoid hard runtime coupling
        String className = throwable.getClass().getName();
        if (className.contains("RateLimited")) {
            return new ClassificationResult(CloudinaryFailureCategory.RATE_LIMITED, 429, MSG_RATE_LIMITED, sanitizedDiagnostic);
        }
        if (className.contains("NotAllowed")) {
            return new ClassificationResult(CloudinaryFailureCategory.AUTHENTICATION_OR_CONFIGURATION, 403, MSG_AUTH_CONFIG, sanitizedDiagnostic);
        }

        // Signal 2: HTTP / Provider Status Code
        Integer statusCode = extractStatusCode(rawDiagnostic);
        if (statusCode != null) {
            ClassificationResult fromStatus = classifyByStatusCode(statusCode, rawDiagnostic, sanitizedDiagnostic);
            if (fromStatus != null) {
                return fromStatus;
            }
        }

        // Signal 3: Conservative Message Inspection
        ClassificationResult fromMessage = classifyByMessage(rawDiagnostic, statusCode, sanitizedDiagnostic);
        if (fromMessage != null) {
            return fromMessage;
        }

        // Signal 4: UNKNOWN Fallback (Strictly non-transient)
        return new ClassificationResult(
                CloudinaryFailureCategory.UNKNOWN,
                statusCode,
                MSG_UNKNOWN,
                sanitizedDiagnostic
        );
    }

    private static boolean isTypedTransientException(Throwable t) {
        if (t == null) return false;
        // Explicitly exclude local filesystem errors
        if (t instanceof java.io.FileNotFoundException
                || t instanceof java.nio.file.FileSystemException) {
            return false;
        }
        // Genuine network / socket / transport exceptions
        if (t instanceof SocketTimeoutException
                || t instanceof ConnectException
                || t instanceof UnknownHostException
                || t instanceof NoRouteToHostException
                || t instanceof SocketException
                || t instanceof java.net.PortUnreachableException
                || t instanceof javax.net.ssl.SSLException) {
            return true;
        }
        String className = t.getClass().getName();
        return className.contains("ConnectTimeoutException")
                || className.contains("NoHttpResponseException")
                || className.contains("ConnectionClosedException")
                || className.contains("HttpHostConnectException");
    }

    private static Integer extractStatusCode(String message) {
        if (message == null || message.isBlank()) return null;

        Matcher jsonMatcher = JSON_HTTP_CODE_PATTERN.matcher(message);
        if (jsonMatcher.find()) {
            try {
                return Integer.parseInt(jsonMatcher.group(1));
            } catch (NumberFormatException ignored) {}
        }

        Matcher codeMatcher = STATUS_CODE_PATTERN.matcher(message);
        if (codeMatcher.find()) {
            try {
                return Integer.parseInt(codeMatcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static ClassificationResult classifyByStatusCode(int code, String rawMsg, String sanitizedDiagnostic) {
        if (code >= 500 && code < 600) {
            return new ClassificationResult(CloudinaryFailureCategory.TRANSIENT, code, MSG_TRANSIENT, sanitizedDiagnostic);
        }
        if (code == 429 || code == 420) {
            return new ClassificationResult(CloudinaryFailureCategory.RATE_LIMITED, code, MSG_RATE_LIMITED, sanitizedDiagnostic);
        }
        if (code == 401 || code == 403) {
            return new ClassificationResult(CloudinaryFailureCategory.AUTHENTICATION_OR_CONFIGURATION, code, MSG_AUTH_CONFIG, sanitizedDiagnostic);
        }
        if (code == 400 || code == 422) {
            // Distinguish quota vs media rejection vs generic invalid request
            if (QUOTA_MSG_PATTERN.matcher(rawMsg).find()) {
                return new ClassificationResult(CloudinaryFailureCategory.ACCOUNT_OR_QUOTA, code, MSG_QUOTA, sanitizedDiagnostic);
            }
            if (MEDIA_REJECTED_MSG_PATTERN.matcher(rawMsg).find()) {
                return new ClassificationResult(CloudinaryFailureCategory.MEDIA_REJECTED, code, MSG_MEDIA_REJECTED, sanitizedDiagnostic);
            }
            if (INVALID_REQUEST_MSG_PATTERN.matcher(rawMsg).find()) {
                return new ClassificationResult(CloudinaryFailureCategory.INVALID_REQUEST, code, MSG_INVALID_REQUEST, sanitizedDiagnostic);
            }
            // 400 default is INVALID_REQUEST
            return new ClassificationResult(CloudinaryFailureCategory.INVALID_REQUEST, code, MSG_INVALID_REQUEST, sanitizedDiagnostic);
        }
        return null;
    }

    private static ClassificationResult classifyByMessage(String rawMsg, Integer code, String sanitizedDiagnostic) {
        if (rawMsg == null || rawMsg.isBlank()) return null;

        if (RATE_LIMIT_MSG_PATTERN.matcher(rawMsg).find()) {
            return new ClassificationResult(CloudinaryFailureCategory.RATE_LIMITED, code != null ? code : 429, MSG_RATE_LIMITED, sanitizedDiagnostic);
        }
        if (AUTH_MSG_PATTERN.matcher(rawMsg).find()) {
            return new ClassificationResult(CloudinaryFailureCategory.AUTHENTICATION_OR_CONFIGURATION, code != null ? code : 401, MSG_AUTH_CONFIG, sanitizedDiagnostic);
        }
        if (QUOTA_MSG_PATTERN.matcher(rawMsg).find()) {
            return new ClassificationResult(CloudinaryFailureCategory.ACCOUNT_OR_QUOTA, code, MSG_QUOTA, sanitizedDiagnostic);
        }
        if (MEDIA_REJECTED_MSG_PATTERN.matcher(rawMsg).find()) {
            return new ClassificationResult(CloudinaryFailureCategory.MEDIA_REJECTED, code, MSG_MEDIA_REJECTED, sanitizedDiagnostic);
        }
        if (INVALID_REQUEST_MSG_PATTERN.matcher(rawMsg).find()) {
            return new ClassificationResult(CloudinaryFailureCategory.INVALID_REQUEST, code, MSG_INVALID_REQUEST, sanitizedDiagnostic);
        }
        return null;
    }

    /**
     * Sanitizes raw error strings to remove API keys, API secrets, signatures, Bearer tokens, and Cloudinary URLs.
     */
    public static String sanitize(String input) {
        if (input == null || input.isBlank()) return "none";
        String s = API_KEY_PATTERN.matcher(input).replaceAll("$1=***");
        s = API_SECRET_PATTERN.matcher(s).replaceAll("$1=***");
        s = SIGNATURE_PATTERN.matcher(s).replaceAll("$1=***");
        s = BEARER_PATTERN.matcher(s).replaceAll("$1***");
        s = CLOUDINARY_URL_SECRET_PATTERN.matcher(s).replaceAll("cloudinary://***:***@");
        return s;
    }

    private static String extractRawDiagnostic(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null && !msg.isBlank() ? ": " + msg : "");
    }

    private static Throwable findRootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
