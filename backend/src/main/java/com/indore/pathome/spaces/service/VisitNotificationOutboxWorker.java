package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.TargetRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Bounded PostgreSQL SKIP LOCKED relay; event-key dedupe makes delivery retry safe. */
@Component
public class VisitNotificationOutboxWorker {
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;

    public VisitNotificationOutboxWorker(JdbcTemplate jdbc, NotificationService notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${pathome.visit.outbox.poll-delay-ms:5000}")
    @Transactional
    public void deliverBatch() {
        Instant now = Instant.now();
        List<OutboxItem> batch = jdbc.query("with due as (select id from visit_notification_outbox where ((state in ('QUEUED','FAILED') and available_at<=?) or (state='PROCESSING' and claimed_at<?)) order by available_at,id for update skip locked limit 50) update visit_notification_outbox o set state='PROCESSING',attempts=attempts+1,claimed_at=?,last_error_code=null from due where o.id=due.id returning o.id,o.event_key,o.recipient_user_id,o.recipient_role,o.title,o.message,o.attempts",
                (rs, row) -> new OutboxItem(rs.getLong("id"), rs.getString("event_key"), rs.getLong("recipient_user_id"),
                        rs.getString("recipient_role"), rs.getString("title"), rs.getString("message"), rs.getInt("attempts")),
                Timestamp.from(now), Timestamp.from(now.minusSeconds(300)), Timestamp.from(now));
        for (OutboxItem item : batch) {
            try {
                TargetRole role = TargetRole.valueOf(item.role());
                boolean persisted = notifications.createNotificationWithEventKey(role, String.valueOf(item.recipientId()),
                        item.title(), item.message(), null, "VISIT_SESSION", "info", item.eventKey()).isPresent();
                if (persisted) {
                    jdbc.update("update visit_notification_outbox set state='SENT',delivered_at=?,last_error_code=null where id=?",
                            Timestamp.from(Instant.now()), item.id());
                } else {
                    retry(item);
                }
            } catch (RuntimeException ex) {
                retry(item);
            }
        }
    }

    private void retry(OutboxItem item) {
        int delaySeconds = Math.min(600, 15 * Math.max(1, item.attempts()));
        jdbc.update("update visit_notification_outbox set state='FAILED',available_at=?,last_error_code='DELIVERY_FAILED' where id=?",
                Timestamp.from(Instant.now().plusSeconds(delaySeconds)), item.id());
    }

    private record OutboxItem(Long id, String eventKey, Long recipientId, String role,
                              String title, String message, int attempts) {}
}
