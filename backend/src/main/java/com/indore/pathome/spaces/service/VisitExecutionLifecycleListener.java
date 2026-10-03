package com.indore.pathome.spaces.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;

@Component
public class VisitExecutionLifecycleListener {
    private static final EnumSet<VisitSessionNotificationEvent.Type> INVALIDATING = EnumSet.of(
            VisitSessionNotificationEvent.Type.RESCHEDULED,
            VisitSessionNotificationEvent.Type.REASSIGNED,
            VisitSessionNotificationEvent.Type.CANCELLED);
    private final JdbcTemplate jdbc;

    public VisitExecutionLifecycleListener(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void invalidateObsoleteStartCode(VisitSessionNotificationEvent event) {
        if (!INVALIDATING.contains(event.type())) return;
        jdbc.update("update visit_start_challenges set invalidated_at=?,version=version+1 where session_id=? and consumed_at is null and invalidated_at is null",
                Timestamp.from(Instant.now()), event.sessionId());
    }
}
