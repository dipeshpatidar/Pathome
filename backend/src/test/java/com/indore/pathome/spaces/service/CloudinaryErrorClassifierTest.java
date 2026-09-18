package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.exception.CloudinaryFailureCategory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class CloudinaryErrorClassifierTest {

    @Test
    void socketTimeout_classifiedAsTransient() {
        SocketTimeoutException ex = new SocketTimeoutException("Read timed out");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertEquals(CloudinaryFailureCategory.TRANSIENT, result.category());
        assertTrue(result.shouldRetryImmediately());
        assertTrue(result.isStagingEligible());
        assertEquals(CloudinaryErrorClassifier.MSG_TRANSIENT, result.safeReason());
    }

    @Test
    void connectException_classifiedAsTransient() {
        ConnectException ex = new ConnectException("Connection refused");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertEquals(CloudinaryFailureCategory.TRANSIENT, result.category());
        assertTrue(result.shouldRetryImmediately());
        assertTrue(result.isStagingEligible());
    }

    @Test
    void fileNotFoundException_isNotTransient() {
        java.io.FileNotFoundException ex = new java.io.FileNotFoundException("/path/to/missing/media.jpg (No such file or directory)");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertNotEquals(CloudinaryFailureCategory.TRANSIENT, result.category());
        assertEquals(CloudinaryFailureCategory.UNKNOWN, result.category());
        assertFalse(result.shouldRetryImmediately(), "Local file not found must not trigger immediate network retry");
    }

    @Test
    void localNonNetworkIOException_isNotTransient() {
        IOException ex = new IOException("Disk read failure or broken pipe on local fd");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertNotEquals(CloudinaryFailureCategory.TRANSIENT, result.category());
        assertEquals(CloudinaryFailureCategory.UNKNOWN, result.category());
        assertFalse(result.shouldRetryImmediately(), "Local IO errors without network cause must not retry immediately");
    }

    @Test
    void ioExceptionWithNetworkRootCause_remainsTransient() {
        IOException ex = new IOException("Wrapper transport error", new java.net.SocketException("Connection reset"));
        var result = CloudinaryErrorClassifier.classify(ex);

        assertEquals(CloudinaryFailureCategory.TRANSIENT, result.category());
        assertTrue(result.shouldRetryImmediately());
    }

    @Test
    void provider5xx_classifiedAsTransient() {
        RuntimeException ex = new RuntimeException("Server returned unexpected status code - 503 - Service Unavailable");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertEquals(CloudinaryFailureCategory.TRANSIENT, result.category());
        assertEquals(503, result.statusCode());
        assertTrue(result.shouldRetryImmediately());
        assertTrue(result.isStagingEligible());
    }

    @Test
    void provider429_classifiedAsRateLimited() {
        RuntimeException ex = new RuntimeException("Server returned unexpected status code - 429 - {\"error\":{\"message\":\"Rate limit exceeded\",\"http_code\":429}}");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertEquals(CloudinaryFailureCategory.RATE_LIMITED, result.category());
        assertEquals(429, result.statusCode());
        assertTrue(result.shouldRetryImmediately());
        assertTrue(result.isStagingEligible());
        assertEquals(CloudinaryErrorClassifier.MSG_RATE_LIMITED, result.safeReason());
    }

    @Test
    void authOrConfigError_classifiedAsAuthConfig() {
        RuntimeException ex = new RuntimeException("Server returned unexpected status code - 401 - Invalid API key");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertEquals(CloudinaryFailureCategory.AUTHENTICATION_OR_CONFIGURATION, result.category());
        assertEquals(401, result.statusCode());
        assertFalse(result.shouldRetryImmediately(), "Auth errors must not retry immediately");
        assertTrue(result.isStagingEligible());
        assertEquals(CloudinaryErrorClassifier.MSG_AUTH_CONFIG, result.safeReason());
    }

    @Test
    void invalidRequest_classifiedAsInvalidRequest() {
        RuntimeException ex = new RuntimeException("Server returned unexpected status code - 400 - Invalid upload preset");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertEquals(CloudinaryFailureCategory.INVALID_REQUEST, result.category());
        assertEquals(400, result.statusCode());
        assertFalse(result.shouldRetryImmediately());
        assertTrue(result.isStagingEligible());
        assertEquals(CloudinaryErrorClassifier.MSG_INVALID_REQUEST, result.safeReason());
    }

    @Test
    void mediaRejected_classifiedAsMediaRejected() {
        RuntimeException ex = new RuntimeException("Server returned unexpected status code - 400 - Invalid image file or corrupted image");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertEquals(CloudinaryFailureCategory.MEDIA_REJECTED, result.category());
        assertEquals(400, result.statusCode());
        assertFalse(result.shouldRetryImmediately());
        assertFalse(result.isStagingEligible(), "Corrupted media must NOT be staged for automatic retry");
        assertEquals(CloudinaryErrorClassifier.MSG_MEDIA_REJECTED, result.safeReason());
    }

    @Test
    void quotaExceeded_classifiedAsAccountOrQuota() {
        RuntimeException ex = new RuntimeException("Server returned unexpected status code - 400 - Quota exceeded for monthly credit limit");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertEquals(CloudinaryFailureCategory.ACCOUNT_OR_QUOTA, result.category());
        assertFalse(result.shouldRetryImmediately());
        assertTrue(result.isStagingEligible());
        assertEquals(CloudinaryErrorClassifier.MSG_QUOTA, result.safeReason());
    }

    @Test
    void unknownError_classifiedAsUnknown_andDoesNotRetryImmediately() {
        RuntimeException ex = new RuntimeException("Something completely unexpected happened in provider");
        var result = CloudinaryErrorClassifier.classify(ex);

        assertEquals(CloudinaryFailureCategory.UNKNOWN, result.category());
        assertFalse(result.shouldRetryImmediately(), "UNKNOWN must strictly NOT trigger immediate retry");
        assertTrue(result.isStagingEligible(), "Original valid media should be staged for durable retry");
        assertEquals(CloudinaryErrorClassifier.MSG_UNKNOWN, result.safeReason());
    }

    @Test
    void sanitize_scrubsSensitiveCredentials() {
        String sensitive = "Failed with api_key=1234567890&api_secret=abcdef12345&signature=987654321 and Bearer eyJhbGciOiJIUzI1NiJ9";
        String sanitized = CloudinaryErrorClassifier.sanitize(sensitive);

        assertFalse(sanitized.contains("1234567890"));
        assertFalse(sanitized.contains("abcdef12345"));
        assertFalse(sanitized.contains("987654321"));
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertTrue(sanitized.contains("api_key=***"));
        assertTrue(sanitized.contains("api_secret=***"));
        assertTrue(sanitized.contains("signature=***"));
    }
}
