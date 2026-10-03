package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.VisitEntitlementRestoreCommand;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VisitEntitlementOperationsService {
    private final VisitOperationsAuthorizationService authorization;
    private final VisitSessionRepository sessions;
    private final VisitEntitlementStore entitlements;
    private final JdbcTemplate jdbc;

    public VisitEntitlementOperationsService(VisitOperationsAuthorizationService authorization,
            VisitSessionRepository sessions, VisitEntitlementStore entitlements, JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.sessions = sessions;
        this.entitlements = entitlements;
        this.jdbc = jdbc;
    }

    @Transactional
    public void restoreInterruptedSession(Long actorId, Long sessionId, VisitEntitlementRestoreCommand command) {
        authorization.requireOperations(actorId);
        if (command == null || command.operationId() == null || command.expectedSessionVersion() == null)
            throw new IllegalArgumentException("Version and operation ID are required");
        String reason = command.reasonCode() == null ? "" : command.reasonCode().trim().toUpperCase();
        if (!List.of("GE_FAILURE", "OWNER_ACCESS_FAILURE", "PATHOME_SYSTEM_FAILURE", "APPROVED_OPERATIONAL_EXCEPTION").contains(reason))
            throw new IllegalArgumentException("Unsupported operational restoration reason");
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        if (!session.getVersion().equals(command.expectedSessionVersion()))
            throw new VisitOperationsConflictException("Visit Session changed; refresh and retry");
        boolean interruptedStartedVisit = session.getStatus() == VisitSessionStatus.INTERRUPTED
                && session.getEntitlementConsumedAt() != null
                && Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from visit_execution_events where session_id=? and event_type='VISIT_INTERRUPTED')",
                    Boolean.class, sessionId));
        boolean finalizedNoShow = session.getStatus() == VisitSessionStatus.NO_SHOW
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from visit_entitlement_ledger where session_id=? and event_type='FORFEIT_NO_SHOW')",
                        Boolean.class, sessionId));
        if (!interruptedStartedVisit && !finalizedNoShow)
            throw new VisitOperationsConflictException("Only an interrupted started visit or finalized no-show forfeiture can be restored");
        String key = "OE_RESTORE:" + command.operationId();
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from visit_entitlement_ledger where session_id=? and idempotency_key=?)", Boolean.class, sessionId, key)))
            return;
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from visit_entitlement_ledger where session_id=? and event_type in ('RESTORE','OE_OPERATIONAL_RESTORE'))", Boolean.class, sessionId)))
            throw new VisitOperationsConflictException("This Visit Session already has an entitlement restoration");
        entitlements.operationalRestore(session.getTenant().getId(), sessionId, actorId, reason, key);
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,idempotency_key) values (?,?,'ENTITLEMENT_RESTORED',?,?) on conflict(idempotency_key) do nothing",
                sessionId, actorId, reason, key);
    }

    @Transactional
    public void documentInterruption(Long actorId, Long sessionId, VisitEntitlementRestoreCommand command) {
        authorization.requireOperations(actorId);
        if (command == null || command.operationId() == null || command.expectedSessionVersion() == null)
            throw new IllegalArgumentException("Version and operation ID are required");
        String reason = command.reasonCode() == null ? "" : command.reasonCode().trim().toUpperCase();
        if (!List.of("GE_FAILURE", "OWNER_ACCESS_FAILURE", "PATHOME_SYSTEM_FAILURE", "APPROVED_OPERATIONAL_EXCEPTION").contains(reason))
            throw new IllegalArgumentException("Unsupported interruption reason");
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        String key = "VISIT_INTERRUPTED:" + sessionId + ":" + command.operationId();
        if (session.getStatus() == VisitSessionStatus.INTERRUPTED
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from visit_execution_events where idempotency_key=?)",
                        Boolean.class, key))) return;
        if (!session.getVersion().equals(command.expectedSessionVersion()))
            throw new VisitOperationsConflictException("Visit Session changed; refresh and retry");
        if (session.getStatus() != VisitSessionStatus.STARTED || session.getEntitlementConsumedAt() == null)
            throw new VisitOperationsConflictException("Only an active consumed visit can be documented as interrupted");
        session.setStatus(VisitSessionStatus.INTERRUPTED);
        session.setExecutionStateChangedAt(java.time.Instant.now());
        sessions.saveAndFlush(session);
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,idempotency_key) values (?,?,'VISIT_INTERRUPTED',?,?)",
                sessionId, actorId, reason, key);
        jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message) values (?,?,'TENANT','VISIT_INTERRUPTED','Visit needs Operations recovery','Operations recorded that your visit was interrupted. Your visit is not marked complete; Pathome will review the next step.') on conflict(event_key) do nothing",
                key, session.getTenant().getId());
    }
}
