package com.indore.pathome.spaces.exception;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.QueryTimeoutException;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The governed Team creation command could not obtain its database guards within the bounded command
 * waits, so the whole command was rolled back and may safely be retried.
 *
 * <p>Only bounded-wait failures are translated into this exception, and the decision is led by the
 * database's own SQLSTATE rather than by the class of the exception it happens to arrive in:
 * PostgreSQL {@code 55P03} ({@code lock_not_available}, what {@code lock_timeout} raises) and
 * {@code 57014} ({@code query_canceled}) <em>with</em> timeout wording, which is what
 * {@code statement_timeout} raises. Everything else keeps its own meaning - missing credentials stay
 * 401, missing authority stays 403, stale versions and taken {@code (city, code)} keys stay 409, a
 * duplicate key or an audit failure stays on the existing sanitized path, and a client or operator
 * cancellation is never dressed up as a busy database.
 *
 * <p>The message is a fixed, sanitized sentence: no SQL text, stack trace, database identity or
 * credential is ever carried into the HTTP response.
 */
public class OperatingTeamProvisioningUnavailableException extends RuntimeException {
    private static final String SAFE_MESSAGE =
            "The Team could not be created because the database was busy. Please try again shortly.";

    /** PostgreSQL {@code lock_not_available}: raised by {@code lock_timeout}, and by {@code NOWAIT}. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    /**
     * PostgreSQL {@code query_canceled}. {@code statement_timeout} reports it, but so do
     * {@code pg_cancel_backend} and a client that abandons its statement, so on its own it only says
     * "something was canceled" - it is not yet evidence of a bounded wait.
     */
    private static final String QUERY_CANCELED = "57014";
    /**
     * The wording PostgreSQL adds to a timeout cancellation: {@code canceling statement due to
     * statement timeout}. A user or operator cancellation instead reports {@code canceling statement
     * due to user request}, so requiring timeout wording is what separates a bounded statement wait
     * from a cancellation. Compiled once at class loading.
     */
    private static final Pattern TIMEOUT_WORDING = Pattern.compile("(?i)\\btimeout\\b");

    /**
     * Cause chains (and {@link SQLException#getNextException()} chains) come from third-party code, so
     * the walk is bounded and identity-guarded: a malformed or cyclic chain can never spin here.
     */
    private static final int MAX_WALK_DEPTH = 64;

    public OperatingTeamProvisioningUnavailableException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }

    /**
     * Returns the transient bounded-wait failure as this command's 503, and returns every other
     * failure unchanged so unrelated authorization, conflict and validation semantics are preserved.
     */
    public static RuntimeException translate(RuntimeException failure) {
        return isBoundedWaitFailure(failure) ? new OperatingTeamProvisioningUnavailableException(failure) : failure;
    }

    /**
     * Classifies the failure as a bounded command wait, walking wrapped Spring, Hibernate, JPA, JDBC
     * and driver causes safely.
     *
     * <p>SQLSTATE evidence is authoritative. When the database reported a state, only that state
     * decides the outcome: a wrapper class may never overrule it. This matters because Hibernate's
     * PostgreSQL dialect routes <em>every</em> {@code 57014} - including a cancellation - into
     * {@code org.hibernate.QueryTimeoutException} and from there into Spring's
     * {@code QueryTimeoutException}, so a class-name match would report a user cancellation as a busy
     * database.
     *
     * <p>Only when no SQLSTATE was reported at all may a wrapper class decide, and then only for
     * classes that unambiguously mean "a bounded wait expired": a JDBC {@link SQLTimeoutException}, a
     * JPA query or lock timeout, or Hibernate's lock timeout. Generic pessimistic-lock, persistence,
     * JDBC and cancellation exceptions are never treated as timeouts because of their class.
     */
    public static boolean isBoundedWaitFailure(Throwable failure) {
        SqlStateEvidence evidence = collectSqlStateEvidence(failure);
        if (evidence.boundedWait()) {
            return true;
        }
        return !evidence.anyStateReported() && hasUnambiguousTimeoutWrapper(failure);
    }

    /** What the database (or the driver) actually reported anywhere in the failure. */
    private record SqlStateEvidence(boolean boundedWait, boolean anyStateReported) {}

    private static SqlStateEvidence collectSqlStateEvidence(Throwable failure) {
        boolean boundedWait = false;
        boolean anyStateReported = false;
        Set<Throwable> visitedCauses = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_WALK_DEPTH && visitedCauses.add(cause);
             depth++, cause = cause.getCause()) {
            if (!(cause instanceof SQLException sqlFailure)) {
                continue;
            }
            Set<SQLException> visitedLinks = Collections.newSetFromMap(new IdentityHashMap<>());
            for (SQLException link = sqlFailure; link != null && visitedLinks.size() < MAX_WALK_DEPTH
                    && visitedLinks.add(link); link = link.getNextException()) {
                String sqlState = link.getSQLState();
                if (sqlState == null) {
                    continue;
                }
                anyStateReported = true;
                boundedWait = boundedWait || LOCK_NOT_AVAILABLE.equals(sqlState)
                        || (QUERY_CANCELED.equals(sqlState) && reportsTimeoutWording(link));
            }
        }
        return new SqlStateEvidence(boundedWait, anyStateReported);
    }

    private static boolean reportsTimeoutWording(SQLException failure) {
        String message = failure.getMessage();
        return message != null && TIMEOUT_WORDING.matcher(message).find();
    }

    /**
     * The wrapper-only fallback, reachable solely when the whole chain carries no SQLSTATE. Each class
     * here means a bounded wait by itself; deliberately absent are
     * {@code jakarta.persistence.PessimisticLockException}, {@code org.hibernate.PessimisticLockException},
     * {@code org.hibernate.exception.LockAcquisitionException},
     * {@code org.springframework.dao.PessimisticLockingFailureException},
     * {@code org.springframework.dao.CannotAcquireLockException} and
     * {@code org.springframework.dao.QueryTimeoutException}: all of them also carry deadlocks,
     * serialization failures or cancellations, which are not bounded waits.
     */
    private static boolean hasUnambiguousTimeoutWrapper(Throwable failure) {
        Set<Throwable> visitedCauses = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_WALK_DEPTH && visitedCauses.add(cause);
             depth++, cause = cause.getCause()) {
            if (cause instanceof SQLTimeoutException
                    || cause instanceof QueryTimeoutException
                    || cause instanceof LockTimeoutException
                    || cause instanceof org.hibernate.exception.LockTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
