package com.indore.pathome.spaces.exception;

/**
 * A governed Operations command could not obtain its database guards within the bounded command
 * waits, so the whole command was rolled back and may safely be retried.
 *
 * <p>Classification is delegated to the already-reviewed Package 3A SQLSTATE-led classifier so the
 * two commands cannot drift into different timeout policies: PostgreSQL {@code 55P03}
 * ({@code lock_not_available}, what {@code lock_timeout} raises) and {@code 57014}
 * ({@code query_canceled}) <em>with</em> timeout wording. Nothing else is treated as a bounded wait -
 * missing credentials stay 401, missing authority stays 403, lifecycle and version conflicts stay
 * 409, and a client or operator cancellation is never dressed up as a busy database.
 *
 * <p>The message is a fixed, sanitized sentence: no SQL text, stack trace, database identity or
 * credential is ever carried into the HTTP response.
 */
public class OperationalCommandUnavailableException extends RuntimeException {
    private static final String SAFE_MESSAGE =
            "The operation could not be completed because the database was busy. Please try again shortly.";

    public OperationalCommandUnavailableException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }

    /**
     * Returns a bounded-wait failure as this command's 503, and every other failure unchanged so
     * unrelated authorization, conflict and validation semantics are preserved.
     */
    public static RuntimeException translate(RuntimeException failure) {
        return OperatingTeamProvisioningUnavailableException.isBoundedWaitFailure(failure)
                ? new OperationalCommandUnavailableException(failure)
                : failure;
    }
}
