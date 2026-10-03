package com.indore.pathome.spaces.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Settles only provisional no-shows whose dispute window elapsed; clock passage alone never starts this state. */
@Component
public class VisitNoShowSettlementWorker {
    private final JdbcTemplate jdbc;
    private final VisitEntitlementStore entitlements;
    public VisitNoShowSettlementWorker(JdbcTemplate jdbc, VisitEntitlementStore entitlements) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
    }

    @Scheduled(fixedDelayString = "${pathome.visit.no-show-settlement-delay-ms:60000}")
    @Transactional
    public void settleBatch() {
        Instant now = Instant.now();
        List<NoShow> due = jdbc.query("select id,tenant_id from visit_sessions where status='PROVISIONAL_NO_SHOW' and no_show_dispute_until<=? order by no_show_dispute_until,id for update skip locked limit 50",
                (rs, row) -> new NoShow(rs.getLong(1), rs.getLong(2)), Timestamp.from(now));
        for (NoShow row : due) {
            // The conditional state transition is the final eligibility check under
            // the session lock. Any ledger failure rolls this update back with it.
            int finalized = jdbc.update("update visit_sessions set status='NO_SHOW',execution_state_changed_at=?,version=version+1,updated_at=? where id=? and status='PROVISIONAL_NO_SHOW' and no_show_dispute_until<=?",
                    Timestamp.from(now), Timestamp.from(now), row.sessionId(), Timestamp.from(now));
            if (finalized != 1) continue;
            entitlements.forfeitNoShow(row.tenantId(), row.sessionId());
            jdbc.update("insert into visit_execution_events(session_id,event_type,reason_code,idempotency_key) values (?,'NO_SHOW_FINALIZED','DISPUTE_WINDOW_ELAPSED','NO_SHOW_FINAL:'||?) on conflict(idempotency_key) do nothing",
                    row.sessionId(), row.sessionId());
            jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message) values (?,?,'TENANT','NO_SHOW_FINALIZED','Visit attendance review closed','The visit attendance review window has closed.') on conflict(event_key) do nothing",
                    "NO_SHOW_FINAL:" + row.sessionId(), row.tenantId());
        }
    }

    private record NoShow(Long sessionId, Long tenantId) {}
}
