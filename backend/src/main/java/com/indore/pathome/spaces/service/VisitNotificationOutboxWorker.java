package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.NotificationAuthorizationClass;
import com.indore.pathome.spaces.entity.TargetRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Bounded SKIP LOCKED relay. Every claimed notification is finalized in its own transaction. */
@Component
public class VisitNotificationOutboxWorker {
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final OperationalNotificationAuthorizationService authorization;
    private final TransactionTemplate transactions;

    public VisitNotificationOutboxWorker(JdbcTemplate jdbc, NotificationService notifications,
            OperationalNotificationAuthorizationService authorization,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.authorization = authorization;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${pathome.visit.outbox.poll-delay-ms:5000}")
    public void deliverBatch() {
        List<OutboxItem> batch = transactions.execute(status -> claimBatch());
        if (batch == null || batch.isEmpty()) return;
        for (OutboxItem item : batch) {
            try {
                transactions.execute(status -> deliver(item));
            } catch (RuntimeException failure) {
                try {
                    transactions.executeWithoutResult(status -> retry(item));
                } catch (RuntimeException retryFailure) {
                    // The PROCESSING lease expires and a later bounded batch will reclaim it.
                }
            }
        }
    }

    private List<OutboxItem> claimBatch() {
        Instant now = Instant.now();
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM visit_notification_outbox
                    WHERE ((state IN ('QUEUED','FAILED') AND available_at <= ?)
                        OR (state = 'PROCESSING' AND claimed_at < ?))
                    ORDER BY available_at, id
                    FOR UPDATE SKIP LOCKED LIMIT 50
                )
                UPDATE visit_notification_outbox o
                   SET state='PROCESSING', attempts=attempts+1, claimed_at=?, last_error_code=NULL
                  FROM due WHERE o.id=due.id
                RETURNING o.id,o.event_key,o.recipient_user_id,o.recipient_role,
                          o.authorization_class,o.operational_session_id,o.title,o.message,o.attempts,o.claimed_at
                """, (rs, row) -> new OutboxItem(rs.getLong("id"), rs.getString("event_key"),
                        rs.getLong("recipient_user_id"), rs.getString("recipient_role"),
                        NotificationAuthorizationClass.valueOf(rs.getString("authorization_class")),
                        rs.getObject("operational_session_id", Long.class), rs.getString("title"),
                        rs.getString("message"), rs.getInt("attempts"),
                        rs.getTimestamp("claimed_at").toInstant()),
                Timestamp.from(now), Timestamp.from(now.minusSeconds(300)), Timestamp.from(now));
    }

    private Void deliver(OutboxItem item) {
        ClaimState claim = jdbc.query("SELECT state, claimed_at FROM visit_notification_outbox WHERE id=? FOR UPDATE",
                rs -> rs.next() ? new ClaimState(rs.getString("state"), rs.getTimestamp("claimed_at").toInstant()) : null,
                item.id());
        if (claim == null || !"PROCESSING".equals(claim.state())
                || !Objects.equals(claim.claimedAt(), item.claimedAt())) return null;

        if (item.authorizationClass() == NotificationAuthorizationClass.STAFF_LEGACY_QUARANTINED) {
            suppress(item.id());
            return null;
        }
        if (item.authorizationClass() == NotificationAuthorizationClass.OPERATIONS_SESSION
                && (item.operationalSessionId() == null
                    || !authorization.lockAndCanDeliver(item.recipientId(), item.operationalSessionId(), item.role()))) {
            suppress(item.id());
            return null;
        }

        TargetRole role;
        try {
            role = TargetRole.valueOf(item.role());
        } catch (IllegalArgumentException malformedRole) {
            suppress(item.id());
            return null;
        }
        notifications.createNotificationWithEventKeyInCurrentTransaction(role,
                String.valueOf(item.recipientId()), item.title(), item.message(), null,
                "VISIT_SESSION", "info", item.authorizationClass(), item.operationalSessionId(), item.eventKey());
        jdbc.update("UPDATE visit_notification_outbox SET state='SENT', delivered_at=?, last_error_code=NULL WHERE id=?",
                Timestamp.from(Instant.now()), item.id());
        return null;
    }

    private void suppress(Long id) {
        jdbc.update("UPDATE visit_notification_outbox SET state='SUPPRESSED', last_error_code='AUTHORIZATION_DENIED' WHERE id=?",
                id);
    }

    private void retry(OutboxItem item) {
        int delaySeconds = Math.min(600, 15 * Math.max(1, item.attempts()));
        jdbc.update("UPDATE visit_notification_outbox SET state='FAILED', available_at=?, last_error_code='DELIVERY_FAILED' "
                        + "WHERE id=? AND state='PROCESSING' AND claimed_at=?",
                Timestamp.from(Instant.now().plusSeconds(delaySeconds)), item.id(), Timestamp.from(item.claimedAt()));
    }

    private record ClaimState(String state, Instant claimedAt) {}
    private record OutboxItem(Long id, String eventKey, Long recipientId, String role,
                              NotificationAuthorizationClass authorizationClass, Long operationalSessionId,
                              String title, String message, int attempts, Instant claimedAt) {}
}
