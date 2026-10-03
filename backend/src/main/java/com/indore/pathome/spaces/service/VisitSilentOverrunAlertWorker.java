package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.config.VisitExecutionProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Alerts Operations about visits still active beyond their expected end; it never fabricates completion. */
@Component
public class VisitSilentOverrunAlertWorker {
    private final JdbcTemplate jdbc;
    private final VisitExecutionProperties properties;

    public VisitSilentOverrunAlertWorker(JdbcTemplate jdbc, VisitExecutionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${pathome.visit.overrun-alert-poll-delay-ms:60000}")
    @Transactional
    public void enqueueDueAlerts() {
        Instant now = Instant.now();
        Instant dueAt = now.minusSeconds(properties.getSilentOverrunAlertMinutes() * 60L);
        List<Overrun> due = jdbc.query("select s.id,s.representative_user_id from visit_sessions s where s.status='STARTED' and s.expected_end_at<=? and not exists(select 1 from visit_execution_events e where e.session_id=s.id and e.event_type='SILENT_OVERRUN_ALERT') order by s.expected_end_at,s.id for update of s skip locked limit 50",
                (rs, row) -> new Overrun(rs.getLong("id"), rs.getLong("representative_user_id")), Timestamp.from(dueAt));
        List<Long> operationsUsers = jdbc.query("select u.id from users u left join employee_profiles ep on ep.user_id=u.id where u.role='ROLE_ADMIN' or upper(coalesce(ep.role_type,''))='WFH_ADMIN' order by u.id limit 100",
                (rs, row) -> rs.getLong(1));
        for (Overrun row : due) {
            int inserted = jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,idempotency_key) values (?,?,'SILENT_OVERRUN_ALERT','EXPECTED_END_PASSED','SILENT_OVERRUN:'||?) on conflict(idempotency_key) do nothing",
                    row.sessionId(), row.groundExecutiveId(), row.sessionId());
            if (inserted == 0) continue;
            for (Long operationsUser : operationsUsers) {
                jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message) values (?,?,'EMPLOYEE','SILENT_OVERRUN_ALERT','Visit is still in progress','Visit Session '||?||' is still active beyond its expected end. Contact the Ground Executive and tenant; the system has not marked it complete.') on conflict(event_key) do nothing",
                        "SILENT_OVERRUN:" + row.sessionId() + ":" + operationsUser, operationsUser, row.sessionId());
            }
        }
    }

    private record Overrun(Long sessionId, Long groundExecutiveId) {}
}
