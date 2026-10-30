package com.indore.pathome.spaces.exception;

import org.springframework.http.HttpStatus;

/** Typed, non-PII failure from enquiry-phone proof. Callers must never add the code to this message. */
public class LeadPhoneVerificationRejectedException extends RuntimeException {
    private final HttpStatus status;
    private final long retryAfterSeconds;

    public LeadPhoneVerificationRejectedException(HttpStatus status, String safeMessage) {
        this(status, safeMessage, 0);
    }

    public LeadPhoneVerificationRejectedException(HttpStatus status, String safeMessage,
                                                  long retryAfterSeconds) {
        super(safeMessage);
        this.status = status;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
