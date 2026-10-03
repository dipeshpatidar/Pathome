package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ExpectedVisitSessionVersion;
import com.indore.pathome.spaces.dto.VisitRepairQueueItem;
import com.indore.pathome.spaces.dto.VisitRepairQueuePage;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.VisitRequestStatus;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class VisitRepairOperationsService {
    private final VisitOperationsAuthorizationService authorization;
    private final VisitSessionRepository sessions;
    private final PropertyVisitRequestRepository requests;
    private final JdbcTemplate jdbc;

    public VisitRepairOperationsService(VisitOperationsAuthorizationService authorization,
            VisitSessionRepository sessions, PropertyVisitRequestRepository requests, JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.sessions = sessions;
        this.requests = requests;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public VisitRepairQueuePage list(Long actorId, int page, int size) {
        authorization.requireOperations(actorId);
        if (page < 0 || page > 10000 || size < 1 || size > 100)
            throw new IllegalArgumentException("Page must be nonnegative and size must be within 1..100");
        List<VisitRepairQueueItem> items = jdbc.query("select id,version,city,scheduled_at,zone_id,representative_user_id,tenant_confirmation_state,repair_operation_id from visit_sessions where status='REPAIR_REQUIRED' order by updated_at,id limit ? offset ?",
                (rs, row) -> new VisitRepairQueueItem(rs.getLong("id"), rs.getLong("version"), rs.getString("city"),
                        rs.getTimestamp("scheduled_at") == null ? null : rs.getTimestamp("scheduled_at").toInstant(),
                        rs.getString("zone_id"),
                        rs.getObject("representative_user_id", Long.class), rs.getString("tenant_confirmation_state"),
                        rs.getObject("repair_operation_id", java.util.UUID.class)), size, page * size);
        Long total = jdbc.queryForObject("select count(*) from visit_sessions where status='REPAIR_REQUIRED'", Long.class);
        long count = total == null ? 0 : total;
        return new VisitRepairQueuePage(items, count, page, size, (int) Math.ceil((double) count / size));
    }

    @Transactional
    public VisitRepairQueueItem reopen(Long actorId, Long sessionId, ExpectedVisitSessionVersion command) {
        authorization.requireOperations(actorId);
        if (command == null || command.expectedSessionVersion() == null)
            throw new IllegalArgumentException("Expected Visit Session version is required");
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        String reopenKey = "REPAIR_REOPEN:" + sessionId + ":" + command.expectedSessionVersion();
        if (session.getStatus() == VisitSessionStatus.DRAFT
                && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from visit_execution_events where idempotency_key=?)", Boolean.class, reopenKey)))
            return new VisitRepairQueueItem(session.getId(), session.getVersion(), session.getCity(),
                    session.getScheduledAt(), session.getZoneId(), session.getRepresentative() == null ? null : session.getRepresentative().getId(),
                    session.getTenantConfirmationState(), session.getRepairOperationId());
        if (!session.getVersion().equals(command.expectedSessionVersion()))
            throw new VisitOperationsConflictException("Visit Session changed; refresh and retry");
        if (session.getStatus() != VisitSessionStatus.REPAIR_REQUIRED)
            throw new VisitOperationsConflictException("Only a session awaiting repair can be reopened");
        if (session.getStartedAt() != null) {
            boolean legacyReview = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from visit_execution_events where session_id=? and event_type='LEGACY_START_REVIEW')",
                    Boolean.class, sessionId));
            if (!legacyReview || session.getEntitlementConsumedAt() != null)
                throw new VisitOperationsConflictException("A historically started visit requires manual Operations review before a replacement booking");
            // The migration event preserves the historical timestamps before Operations reopens this booking.
            session.setStartedAt(null);
            session.setExpectedEndAt(null);
            session.setExecutionDurationSnapshotMinutes(null);
        }
        for (PropertyVisitRequest request : requests.findLockedBySessionIdOrderByIdAsc(sessionId)) {
            if (request.getStatusValue() == VisitRequestStatus.SCHEDULED)
                request.setStatus(VisitRequestStatus.COORDINATING);
        }
        session.setStatus(VisitSessionStatus.DRAFT);
        session.setScheduledAt(null);
        session.setReservedEndAt(null);
        session.setZoneId(null);
        session.setRepresentative(null);
        session.setAssignedAt(null);
        session.setTenantConfirmationState("PENDING");
        session.setExecutionStateChangedAt(Instant.now());
        session.setRepairState("NONE");
        sessions.saveAndFlush(session);
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,idempotency_key) values (?,?,'REPAIR_REOPENED','OPERATIONS_REVIEW',?) on conflict(idempotency_key) do nothing",
                sessionId, actorId, reopenKey);
        return new VisitRepairQueueItem(session.getId(), session.getVersion(), session.getCity(), null, null,
                null, session.getTenantConfirmationState(), session.getRepairOperationId());
    }
}
