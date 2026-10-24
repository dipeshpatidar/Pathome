package com.indore.pathome.spaces.exception;

/** Sensitive CRM data cannot be handled because production protection is not configured. */
public class LeadDataProtectionUnavailableException extends RuntimeException {
    public LeadDataProtectionUnavailableException(String message) {
        super(message);
    }

    public LeadDataProtectionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
