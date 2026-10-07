package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.config.VisitExecutionProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Acquires free-visit holds as confirmed bookings enter the configured rolling horizon. */
@Component
public class VisitEntitlementReservationWorker {
    private final JdbcTemplate jdbc;
    private final VisitEntitlementStore entitlements;
    private final VisitExecutionProperties properties;

    public VisitEntitlementReservationWorker(JdbcTemplate jdbc, VisitEntitlementStore entitlements,
            VisitExecutionProperties properties) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${pathome.visit.entitlement-reservation-poll-delay-ms:60000}")
    @Transactional
    public void reserveDueBatch() {
        Instant now = Instant.now();
        Instant horizon = now.plusSeconds(properties.getReservationHorizonDays() * 86400L);
        List<Booking> due = jdbc.query("select s.id,s.tenant_id from visit_sessions s where s.status='SCHEDULED' and s.scheduled_at<=? and s.reserved_end_at>? and not exists(select 1 from visit_entitlement_ledger l where l.session_id=s.id and l.event_type='RESERVE') order by s.tenant_id,s.scheduled_at,s.id for update of s skip locked limit 50",
                (rs, row) -> new Booking(rs.getLong("id"), rs.getLong("tenant_id")), Timestamp.from(horizon), Timestamp.from(now));
        for (Booking booking : due) {
            if (!entitlements.reserve(booking.tenantId(), booking.sessionId(), horizon)) markRepairRequired(booking, now);
        }
    }

    private void markRepairRequired(Booking booking, Instant now) {
        UUID operationId = UUID.randomUUID();
        jdbc.update("update visit_sessions set status='REPAIR_REQUIRED',tenant_confirmation_state='PENDING',repair_state='REQUIRED',repair_operation_id=?,execution_state_changed_at=?,version=version+1,updated_at=? where id=? and status='SCHEDULED'",
                operationId, Timestamp.from(now), Timestamp.from(now), booking.sessionId());
        jdbc.update("insert into visit_execution_events(session_id,event_type,reason_code,idempotency_key) values (?,'REPAIR_REQUIRED','ENTITLEMENT_UNAVAILABLE','ENTITLEMENT_REPAIR:'||?) on conflict(idempotency_key) do nothing",
                booking.sessionId(), booking.sessionId());
        jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message,authorization_class,operational_session_id) values (?,?,'TENANT','VISIT_REPAIR_REQUIRED','Visit needs a new confirmation','Your visit is entering the free-visit confirmation window, but a credit could not be reserved. Operations is reviewing the booking and will contact you before the visit.','RECIPIENT',null) on conflict(event_key) do nothing",
                "ENTITLEMENT_REPAIR_TENANT:" + booking.sessionId(), booking.tenantId());
        List<Long> operationsUsers = jdbc.query("select u.id from users u left join employee_profiles ep on ep.user_id=u.id where u.role='ROLE_ADMIN' or upper(coalesce(ep.role_type,''))='WFH_ADMIN' order by u.id limit 100",
                (rs, row) -> rs.getLong(1));
        for (Long operationsUser : operationsUsers) {
            jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message,authorization_class,operational_session_id) values (?,?,'EMPLOYEE','VISIT_REPAIR_REQUIRED','Visit entitlement needs review','Visit Session '||?||' has no available credit hold inside the reservation horizon and needs a safe Operations outcome.','OPERATIONS_SESSION',?) on conflict(event_key) do nothing",
                    "ENTITLEMENT_REPAIR_OE:" + booking.sessionId() + ":" + operationsUser, operationsUser,
                    booking.sessionId(), booking.sessionId());
        }
    }

    private record Booking(Long sessionId, Long tenantId) {}
}
