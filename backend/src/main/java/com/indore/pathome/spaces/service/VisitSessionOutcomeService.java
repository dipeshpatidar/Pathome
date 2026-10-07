package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.GroundVisitSessionItemOutcomeView;
import com.indore.pathome.spaces.dto.GroundVisitSessionOutcomeView;
import com.indore.pathome.spaces.dto.CompleteVisitSessionWithOutcomesCommand;
import com.indore.pathome.spaces.dto.GroundPendingVisitOutcomeView;
import com.indore.pathome.spaces.dto.CorrectVisitOutcomeCommand;
import com.indore.pathome.spaces.dto.OperationsVisitOutcomeAuditView;
import com.indore.pathome.spaces.dto.OperationsVisitOutcomeDetailView;
import com.indore.pathome.spaces.dto.OperationsVisitOutcomeItemView;
import com.indore.pathome.spaces.dto.RecordVisitSessionItemOutcomeCommand;
import com.indore.pathome.spaces.dto.TenantVisitOutcomeHistoryPage;
import com.indore.pathome.spaces.dto.TenantVisitSessionItemOutcomeView;
import com.indore.pathome.spaces.dto.TenantVisitSessionOutcomeView;
import com.indore.pathome.spaces.dto.VisitSessionOutcomeExceptionView;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.entity.VisitSession;
import com.indore.pathome.spaces.entity.VisitSessionItem;
import com.indore.pathome.spaces.entity.VisitSessionItemConfirmationStatus;
import com.indore.pathome.spaces.entity.VisitSessionItemOutcome;
import com.indore.pathome.spaces.entity.VisitSessionItemOutcomeState;
import com.indore.pathome.spaces.entity.VisitSessionItemSkipReason;
import com.indore.pathome.spaces.entity.VisitSessionOutcomeReport;
import com.indore.pathome.spaces.entity.VisitSessionOutcomeReportState;
import com.indore.pathome.spaces.entity.VisitSessionOutcomeScopeSource;
import com.indore.pathome.spaces.entity.VisitSessionStatus;
import com.indore.pathome.spaces.config.VisitExecutionProperties;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.VisitSessionItemOutcomeRepository;
import com.indore.pathome.spaces.repository.VisitSessionItemRepository;
import com.indore.pathome.spaces.repository.VisitSessionOutcomeReportRepository;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class VisitSessionOutcomeService {
    private static final int PRIVATE_NOTE_LIMIT = 500;

    private final VisitSessionRepository sessions;
    private final VisitSessionItemRepository sessionItems;
    private final VisitSessionOutcomeReportRepository reports;
    private final VisitSessionItemOutcomeRepository outcomes;
    private final VisitOperationsAuthorizationService authorization;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final VisitExecutionProperties properties;

    public VisitSessionOutcomeService(VisitSessionRepository sessions,
            VisitSessionItemRepository sessionItems,
            VisitSessionOutcomeReportRepository reports,
            VisitSessionItemOutcomeRepository outcomes,
            VisitOperationsAuthorizationService authorization,
            UserRepository users,
            JdbcTemplate jdbc,
            EntityManager entityManager,
            VisitExecutionProperties properties) {
        this.sessions = sessions;
        this.sessionItems = sessionItems;
        this.reports = reports;
        this.outcomes = outcomes;
        this.authorization = authorization;
        this.users = users;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.properties = properties;
    }

    /** Must join the Package 3 START transaction so START, entitlement, report and scope commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public VisitSessionOutcomeReport captureSuccessfulStart(VisitSession session, Long groundExecutiveId,
            UUID operationId) {
        authorization.requireGroundExecutive(groundExecutiveId);
        if (session == null || session.getId() == null || session.getStatus() != VisitSessionStatus.STARTED
                || session.getRepresentative() == null
                || !groundExecutiveId.equals(session.getRepresentative().getId()) || operationId == null)
            throw new VisitOperationsConflictException("Outcome scope can only be captured for the current GE at START");

        List<VisitSessionItem> scope = sessionItems.findBySessionIdAndRemovedAtIsNullAndConfirmationStatusOrderByPositionAsc(
                session.getId(), VisitSessionItemConfirmationStatus.CONFIRMED);
        if (scope.isEmpty())
            throw new VisitOperationsConflictException("A started visit must have at least one confirmed active property");
        if (scope.stream().anyMatch(item -> item.getListing() == null || item.getListing().getId() == null
                || item.getListing().getTitle() == null || item.getListing().getAddress() == null
                || item.getListing().getCity() == null || item.getListing().getSector() == null))
            throw new VisitOperationsConflictException("A confirmed itinerary property is missing its display details");
        if (reports.existsById(session.getId()))
            throw new VisitOperationsConflictException("Outcome scope already exists for this Visit Session");

        Instant capturedAt = Instant.now();
        VisitSessionOutcomeReport report = reports.saveAndFlush(new VisitSessionOutcomeReport(session,
                VisitSessionOutcomeReportState.OPEN, VisitSessionOutcomeScopeSource.OTP_START, capturedAt));
        List<VisitSessionItemOutcome> rows = scope.stream()
                .map(item -> new VisitSessionItemOutcome(report, item)).toList();
        outcomes.saveAllAndFlush(rows);
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,metadata,idempotency_key) "
                        + "values (?,?, 'OUTCOME_SCOPE_CAPTURED','OTP_START', "
                        + "jsonb_build_object('scopeSource','OTP_START','itemCount',?,'operationId',?), ?) "
                        + "on conflict (idempotency_key) do nothing",
                session.getId(), groundExecutiveId, rows.size(), operationId.toString(),
                "OUTCOME_SCOPE_CAPTURED:" + session.getId() + ":" + operationId);
        entityManager.flush();
        return report;
    }

    @Transactional
    public GroundVisitSessionOutcomeView getGroundOutcomeReport(Long groundExecutiveId, Long sessionId) {
        authorization.requireGroundExecutive(groundExecutiveId);
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        requireCurrentGroundExecutive(session, groundExecutiveId);
        VisitSessionOutcomeReport report = reports.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Outcome report not found"));
        return groundView(session, report, outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId));
    }

    @Transactional
    public GroundVisitSessionOutcomeView recordItemOutcome(Long groundExecutiveId, Long sessionId,
            Long itemId, RecordVisitSessionItemOutcomeCommand command) {
        authorization.requireGroundExecutive(groundExecutiveId);
        validateRecordCommand(sessionId, itemId, command);
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        requireCurrentGroundExecutive(session, groundExecutiveId);
        if (session.getStatus() != VisitSessionStatus.STARTED && session.getStatus() != VisitSessionStatus.COMPLETED)
            throw new VisitOperationsConflictException("Outcomes can only be recorded for a started or finished visit");

        VisitSessionOutcomeReport report = reports.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Outcome report not found"));
        String eventKey = itemOperationKey(sessionId, itemId, command.operationId());
        String requestHash = requestHash(sessionId, itemId, command);
        IdempotentEvent prior = findIdempotentEvent(eventKey);
        if (prior != null) {
            if (!Objects.equals(prior.actorId(), groundExecutiveId) || !Objects.equals(prior.requestHash(), requestHash))
                throw new VisitOperationsConflictException("This outcome operation ID was already used with different data");
            return groundView(session, report, outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId));
        }
        requireOpen(report);
        requireExpectedVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
        requireExpectedVersion(report.getVersion(), command.expectedReportVersion(), "Outcome report");

        VisitSessionItemOutcome outcome = outcomes.findLockedByItemIdAndSessionId(itemId, sessionId)
                .orElseThrow(() -> new VisitOperationsConflictException("Property is not part of the captured visit scope"));
        requireExpectedVersion(outcome.getVersion(), command.expectedItemVersion(), "Property outcome");
        String note = normalizePrivateNote(command.privateNote());
        outcome.record(command.outcome(), command.skipReason(), note,
                users.getReferenceById(groundExecutiveId), Instant.now());
        report.touch();
        outcomes.flush();
        reports.flush();
        insertItemOutcomeEvent(sessionId, groundExecutiveId, itemId, command, requestHash, eventKey);
        entityManager.flush();
        return groundView(session, report, outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId));
    }

    @Transactional(readOnly = true)
    public Page<GroundPendingVisitOutcomeView> listGroundPendingOutcomes(Long groundExecutiveId, int page, int size) {
        authorization.requireGroundExecutive(groundExecutiveId);
        if (page < 0 || page > 10000 || size < 1 || size > 20)
            throw new IllegalArgumentException("Pending outcome page is outside the supported range");
        return reports.findGroundPendingOutcomes(groundExecutiveId, PageRequest.of(page, size));
    }

    /** Locks session, report, then scoped items and validates the complete combined action before Package 3 finish. */
    @Transactional
    public GroundVisitSessionOutcomeView prepareCombinedCompletion(Long groundExecutiveId, Long sessionId,
            CompleteVisitSessionWithOutcomesCommand command) {
        authorization.requireGroundExecutive(groundExecutiveId);
        validateCombinedCommand(sessionId, command);
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        requireCurrentGroundExecutive(session, groundExecutiveId);
        VisitSessionOutcomeReport report = reports.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Outcome report not found"));
        String eventKey = "OUTCOME_REPORT_FINALIZED:" + sessionId + ":" + command.operationId();
        String requestHash = combinedRequestHash(sessionId, command);
        IdempotentEvent prior = findIdempotentEvent(eventKey);
        if (prior != null) {
            if (!Objects.equals(prior.actorId(), groundExecutiveId) || !Objects.equals(prior.requestHash(), requestHash))
                throw new VisitOperationsConflictException("This completion operation ID was already used with different data");
            return groundView(session, report, outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId));
        }
        requireOpen(report);
        if (session.getStatus() != VisitSessionStatus.STARTED && session.getStatus() != VisitSessionStatus.COMPLETED)
            throw new VisitOperationsConflictException("Only a started or finished visit can submit outcomes");
        requireExpectedVersion(session.getVersion(), command.expectedSessionVersion(), "Visit Session");
        requireExpectedVersion(report.getVersion(), command.expectedReportVersion(), "Outcome report");
        List<VisitSessionItemOutcome> rows = outcomes.findLockedBySessionIdOrderByItemId(sessionId);
        requireCompleteOutcomeSet(rows);
        return groundView(session, report, rows);
    }

    /** Called only after the outer completion transaction has validated and, when needed, finished the visit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public GroundVisitSessionOutcomeView finalizeCombinedCompletion(Long groundExecutiveId, Long sessionId,
            CompleteVisitSessionWithOutcomesCommand command) {
        authorization.requireGroundExecutive(groundExecutiveId);
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        requireCurrentGroundExecutive(session, groundExecutiveId);
        VisitSessionOutcomeReport report = reports.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Outcome report not found"));
        String eventKey = "OUTCOME_REPORT_FINALIZED:" + sessionId + ":" + command.operationId();
        String requestHash = combinedRequestHash(sessionId, command);
        IdempotentEvent prior = findIdempotentEvent(eventKey);
        if (prior != null) {
            if (!Objects.equals(prior.actorId(), groundExecutiveId) || !Objects.equals(prior.requestHash(), requestHash))
                throw new VisitOperationsConflictException("This completion operation ID was already used with different data");
            return groundView(session, report, outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId));
        }
        requireOpen(report);
        if (session.getStatus() != VisitSessionStatus.COMPLETED)
            throw new VisitOperationsConflictException("The physical visit must be finished before outcomes are finalized");
        requireExpectedVersion(report.getVersion(), command.expectedReportVersion(), "Outcome report");
        List<VisitSessionItemOutcome> rows = outcomes.findLockedBySessionIdOrderByItemId(sessionId);
        requireCompleteOutcomeSet(rows);
        report.markFinalized(users.getReferenceById(groundExecutiveId), Instant.now());
        report.touch();
        reports.flush();
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,metadata,idempotency_key) "
                        + "values (?,?, 'OUTCOME_REPORT_FINALIZED',NULL, "
                        + "jsonb_build_object('itemCount',?,'operationId',?,'requestHash',?,'completionMode','COMBINED'), ?) "
                        + "on conflict (idempotency_key) do nothing",
                sessionId, groundExecutiveId, rows.size(), command.operationId().toString(), requestHash, eventKey);
        enqueueOutcomeReady(session);
        entityManager.flush();
        return groundView(session, report, rows);
    }

    @Transactional
    public GroundVisitSessionOutcomeView finalizeReport(Long groundExecutiveId, Long sessionId,
            Long expectedSessionVersion, Long expectedReportVersion, UUID operationId) {
        authorization.requireGroundExecutive(groundExecutiveId);
        if (sessionId == null || operationId == null)
            throw new IllegalArgumentException("Session and finalization operation are required");
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        requireCurrentGroundExecutive(session, groundExecutiveId);
        VisitSessionOutcomeReport report = reports.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Outcome report not found"));
        String eventKey = "OUTCOME_REPORT_FINALIZED:" + sessionId + ":" + operationId;
        String requestHash = hash(sessionId + "|" + expectedSessionVersion + "|" + expectedReportVersion);
        IdempotentEvent prior = findIdempotentEvent(eventKey);
        if (prior != null) {
            if (!Objects.equals(prior.actorId(), groundExecutiveId) || !Objects.equals(prior.requestHash(), requestHash))
                throw new VisitOperationsConflictException("This finalization operation ID was already used with different data");
            return groundView(session, report, outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId));
        }
        requireOpen(report);
        if (session.getStatus() != VisitSessionStatus.COMPLETED)
            throw new VisitOperationsConflictException("The physical visit must be finished before outcomes are finalized");
        requireExpectedVersion(session.getVersion(), expectedSessionVersion, "Visit Session");
        requireExpectedVersion(report.getVersion(), expectedReportVersion, "Outcome report");
        List<VisitSessionItemOutcome> rows = outcomes.findLockedBySessionIdOrderByItemId(sessionId);
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.getOutcomeState() == VisitSessionItemOutcomeState.UNRECORDED))
            throw new VisitOperationsConflictException("Every captured property must have an outcome before finalization");

        report.markFinalized(users.getReferenceById(groundExecutiveId), Instant.now());
        report.touch();
        reports.flush();
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,metadata,idempotency_key) "
                        + "values (?,?, 'OUTCOME_REPORT_FINALIZED',NULL, "
                        + "jsonb_build_object('itemCount',?,'operationId',?,'requestHash',?), ?) "
                        + "on conflict (idempotency_key) do nothing",
                sessionId, groundExecutiveId, rows.size(), operationId.toString(), requestHash, eventKey);
        enqueueOutcomeReady(session);
        entityManager.flush();
        return groundView(session, report, rows);
    }

    @Transactional(readOnly = true)
    public TenantVisitSessionOutcomeView getTenantOutcomeReport(Long tenantId, Long sessionId) {
        requireTenant(tenantId);
        VisitSession session = sessions.findByIdAndTenantId(sessionId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        VisitSessionOutcomeReport report = reports.findById(sessionId).orElse(null);
        List<VisitSessionItemOutcome> rows = report == null ? List.of()
                : outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId);
        Map<Long, CorrectionTime> corrections = latestCorrectionTimes(List.of(sessionId));
        return tenantView(session, report, rows, corrections);
    }

    @Transactional(readOnly = true)
    public TenantVisitOutcomeHistoryPage listTenantOutcomeHistory(Long tenantId, int page, int size) {
        requireTenant(tenantId);
        if (page < 0 || page > 10000 || size < 1 || size > 50)
            throw new IllegalArgumentException("History page and size are outside the supported range");
        var result = sessions.findByTenantIdOrderByCreatedAtDescIdDesc(tenantId,
                org.springframework.data.domain.PageRequest.of(page, size));
        List<VisitSession> sessionPage = result.getContent();
        if (sessionPage.isEmpty()) return new TenantVisitOutcomeHistoryPage(List.of(), result.getNumber(),
                result.getSize(), result.getTotalPages(), result.getTotalElements());
        List<Long> ids = sessionPage.stream().map(VisitSession::getId).toList();
        Map<Long, VisitSessionOutcomeReport> reportBySession = reports.findAllBySessionIdIn(ids).stream()
                .collect(Collectors.toMap(VisitSessionOutcomeReport::getSessionId, Function.identity()));
        Map<Long, List<VisitSessionItemOutcome>> outcomesBySession = reportBySession.isEmpty() ? Map.of()
                : outcomes.findBySessionIdInOrderBySessionIdAscPositionSnapshotAscItemIdAsc(ids).stream()
                    .collect(Collectors.groupingBy(VisitSessionItemOutcome::getSessionId));
        Map<Long, CorrectionTime> corrections = latestCorrectionTimes(ids);
        List<TenantVisitSessionOutcomeView> views = sessionPage.stream().map(session -> tenantView(session,
                reportBySession.get(session.getId()), outcomesBySession.getOrDefault(session.getId(), List.of()),
                corrections)).toList();
        return new TenantVisitOutcomeHistoryPage(views, result.getNumber(), result.getSize(),
                result.getTotalPages(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Page<VisitSessionOutcomeExceptionView> listOperationsOutcomeExceptions(Long actorId, int page, int size) {
        authorization.requireOperations(actorId);
        if (page < 0 || page > 10000 || size < 1 || size > 50)
            throw new IllegalArgumentException("Valid outcome exception page and size are required");
        Instant cutoff = Instant.now().minus(properties.getIncompleteOutcomeGraceMinutes(), ChronoUnit.MINUTES);
        return reports.findOperationsExceptions(cutoff, PageRequest.of(page, size)).map(item -> {
            Instant overdueSince = item.reportState() == VisitSessionOutcomeReportState.OPEN
                    && item.finishedAt() != null
                    ? item.finishedAt().plus(properties.getIncompleteOutcomeGraceMinutes(), ChronoUnit.MINUTES)
                    : null;
            return new VisitSessionOutcomeExceptionView(item.sessionId(), item.reportState(), item.sessionState(),
                    item.city(), item.finishedAt(), item.scopeCapturedAt(), item.lastUpdatedAt(), overdueSince,
                    item.itemCount(), item.unrecordedCount(), item.visitedCount());
        });
    }

    @Transactional(readOnly = true)
    public OperationsVisitOutcomeDetailView getOperationsOutcomeDetail(Long actorId, Long sessionId) {
        authorization.requireOperations(actorId);
        VisitSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        VisitSessionOutcomeReport report = reports.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Outcome report not found"));
        List<VisitSessionItemOutcome> rows = outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId);
        return operationsView(session, report, rows, correctionHistory(sessionId));
    }

    @Transactional
    public OperationsVisitOutcomeDetailView correctOutcome(Long actorId, Long sessionId,
            CorrectVisitOutcomeCommand command) {
        User actor = authorization.requireOperations(actorId);
        validateCorrectionCommand(sessionId, command);
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        if (session.getStatus() != VisitSessionStatus.COMPLETED)
            throw new VisitOperationsConflictException("Only physically finished visits can be corrected");
        VisitSessionOutcomeReport report = reports.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Outcome report not found"));
        if (report.getState() != VisitSessionOutcomeReportState.OPEN
                && report.getState() != VisitSessionOutcomeReportState.FINALIZED)
            throw new VisitOperationsConflictException("This report is not available for Operations correction");
        if (report.getState() == VisitSessionOutcomeReportState.OPEN
                && session.getFinishedAt() != null
                && session.getFinishedAt().plus(properties.getIncompleteOutcomeGraceMinutes(), ChronoUnit.MINUTES)
                        .isAfter(Instant.now()))
            throw new VisitOperationsConflictException("This incomplete report is still within the GE completion window");
        String eventKey = correctionEventKey(sessionId, command.itemId(), command.operationId());
        String requestHash = correctionRequestHash(sessionId, command);
        IdempotentEvent prior = findIdempotentEvent(eventKey);
        if (prior != null) {
            if (!Objects.equals(prior.actorId(), actorId) || !Objects.equals(prior.requestHash(), requestHash))
                throw new VisitOperationsConflictException("This correction operation ID was already used with different data");
            return operationsView(session, report, outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId),
                    correctionHistory(sessionId));
        }
        requireExpectedVersion(report.getVersion(), command.expectedReportVersion(), "Outcome report");
        VisitSessionItemOutcome row = outcomes.findLockedByItemIdAndSessionId(command.itemId(), sessionId)
                .orElseThrow(() -> new VisitOperationsConflictException("Property is not part of the captured visit scope"));
        requireExpectedVersion(row.getVersion(), command.expectedItemVersion(), "Property outcome");
        String note = normalizePrivateNote(command.privateNote());
        if (command.outcome() == row.getOutcomeState() && command.skipReason() == row.getSkipReason()
                && Objects.equals(note, normalizePrivateNote(row.getPrivateNote())))
            return operationsView(session, report, outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId),
                    correctionHistory(sessionId));

        VisitSessionItemOutcomeState oldOutcome = row.getOutcomeState();
        VisitSessionItemSkipReason oldReason = row.getSkipReason();
        String oldPrivateNote = row.getPrivateNote();
        Instant correctedAt = Instant.now();
        boolean tenantVisibleChanged = oldOutcome != command.outcome() || oldReason != command.skipReason();
        if (tenantVisibleChanged) {
            row.record(command.outcome(), command.skipReason(), note, users.getReferenceById(actorId), correctedAt);
        } else {
            row.updatePrivateNote(note);
        }
        report.touch();
        List<VisitSessionItemOutcome> allRows = outcomes.findLockedBySessionIdOrderByItemId(sessionId);
        boolean finalizedNow = report.getState() == VisitSessionOutcomeReportState.OPEN
                && !allRows.isEmpty() && allRows.stream().noneMatch(item ->
                        item.getOutcomeState() == VisitSessionItemOutcomeState.UNRECORDED);
        if (finalizedNow) report.markFinalized(users.getReferenceById(actorId), Instant.now());
        reports.flush();
        outcomes.flush();
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,metadata,idempotency_key) "
                        + "values (?,?, 'OUTCOME_ITEM_CORRECTED','OPERATIONS_CORRECTION', "
                        + "jsonb_build_object('itemId',?,'previousOutcome',?,'previousSkipReason',CAST(? AS text),"
                        + "'previousPrivateNote',CAST(? AS text),'correctedOutcome',?,'correctedSkipReason',CAST(? AS text),"
                        + "'correctedPrivateNote',CAST(? AS text),'correctionReason',?,'operationId',?,'requestHash',?,'correctedAt',?),?)",
                sessionId, actorId, command.itemId(), oldOutcome.name(), oldReason == null ? null : oldReason.name(),
                oldPrivateNote, command.outcome().name(), command.skipReason() == null ? null : command.skipReason().name(),
                note, command.correctionReason().strip(), command.operationId().toString(), requestHash,
                correctedAt.toString(), eventKey);
        if (finalizedNow) {
            String finalizeKey = "OUTCOME_REPORT_FINALIZED:" + sessionId + ":OPS:" + command.operationId();
            jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,metadata,idempotency_key) "
                            + "values (?,?, 'OUTCOME_REPORT_FINALIZED','OPERATIONS_CORRECTION', "
                            + "jsonb_build_object('itemCount',?,'operationId',?,'completionMode','OPERATIONS_CORRECTION'), ?) "
                            + "on conflict (idempotency_key) do nothing",
                    sessionId, actorId, allRows.size(), command.operationId().toString(), finalizeKey);
            enqueueOutcomeReady(session);
        } else if (report.getState() == VisitSessionOutcomeReportState.FINALIZED
                && tenantVisibleChanged) {
            jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message,authorization_class,operational_session_id) "
                            + "values (?,?,'TENANT','VISIT_OUTCOME_UPDATED','Visit details were updated',"
                            + "'Pathome Operations updated details for your visit. Open visit history to review the latest information.','RECIPIENT',null) "
                            + "on conflict(event_key) do nothing",
                    "VISIT_OUTCOME_UPDATED:" + sessionId + ":" + command.itemId() + ":" + command.operationId(),
                    session.getTenant().getId());
        }
        entityManager.flush();
        return operationsView(session, report, outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId),
                correctionHistory(sessionId));
    }

    private GroundVisitSessionOutcomeView groundView(VisitSession session, VisitSessionOutcomeReport report,
            List<VisitSessionItemOutcome> rows) {
        List<GroundVisitSessionItemOutcomeView> itemViews = rows.stream().map(row ->
                new GroundVisitSessionItemOutcomeView(row.getItemId(), row.getListingIdSnapshot(),
                        row.getPositionSnapshot(), row.getTitleSnapshot(), row.getAddressSnapshot(),
                        row.getCitySnapshot(), row.getSectorSnapshot(), row.getOutcomeState(),
                        row.getSkipReason(), row.getPrivateNote(), row.getRecordedAt(), row.getVersion())).toList();
        return new GroundVisitSessionOutcomeView(session.getId(), session.getStatus().name(), session.getVersion(),
                report.getState(), report.getVersion(), report.getScopeCapturedAt(), summary(report, rows), itemViews);
    }

    private TenantVisitSessionOutcomeView tenantView(VisitSession session, VisitSessionOutcomeReport report,
            List<VisitSessionItemOutcome> rows, Map<Long, CorrectionTime> corrections) {
        String outcomeSummary = report == null ? null : summary(report, rows);
        boolean finalized = report != null && report.getState() == VisitSessionOutcomeReportState.FINALIZED
                && List.of("ALL_VIEWED", "PARTLY_VIEWED", "NONE_VIEWED").contains(outcomeSummary);
        boolean legacy = report != null && report.getState() == VisitSessionOutcomeReportState.LEGACY_UNRECORDED;
        List<TenantVisitSessionItemOutcomeView> properties = report != null
                && report.getState() != VisitSessionOutcomeReportState.LEGACY_UNRECORDED
                ? rows.stream().map(row -> tenantItemView(row, finalized, corrections.get(row.getItemId()))).toList()
                : List.of();
        long viewed = finalized ? rows.stream().filter(row ->
                row.getOutcomeState() == VisitSessionItemOutcomeState.VISITED).count() : 0;
        Instant lastUpdatedAt = report == null ? session.getExecutionStateChangedAt()
                : tenantVisibleLastUpdatedAt(report, rows, corrections);
        return new TenantVisitSessionOutcomeView(session.getId(), tenantLifecycle(session, report, rows),
                finalized || legacy ? outcomeSummary : null, report != null,
                session.getScheduledAt(), session.getStartedAt(), session.getFinishedAt(), session.getCity(),
                session.getAreaName(), report == null || report.getState() == VisitSessionOutcomeReportState.LEGACY_UNRECORDED
                        ? null : (long) rows.size(), finalized ? viewed : null,
                lastUpdatedAt, properties);
    }

    private Instant tenantVisibleLastUpdatedAt(VisitSessionOutcomeReport report,
            List<VisitSessionItemOutcome> rows, Map<Long, CorrectionTime> corrections) {
        if (report == null) return null;
        if (report.getState() == VisitSessionOutcomeReportState.OPEN) {
            return report.getScopeCapturedAt() == null ? report.getUpdatedAt() : report.getScopeCapturedAt();
        }
        if (report.getState() != VisitSessionOutcomeReportState.FINALIZED || report.getFinalizedAt() == null) {
            return report.getUpdatedAt();
        }
        return rows.stream().map(row -> corrections.get(row.getItemId()))
                .filter(Objects::nonNull).map(CorrectionTime::correctedAt)
                .filter(Objects::nonNull).reduce(report.getFinalizedAt(), (latest, at) -> at.isAfter(latest) ? at : latest);
    }

    private TenantVisitSessionItemOutcomeView tenantItemView(VisitSessionItemOutcome row, boolean finalized,
            CorrectionTime correction) {
        boolean operationsUpdated = finalized && correction != null && row.getRecordedAt() != null
                && !correction.correctedAt().isBefore(row.getRecordedAt());
        String outcome = !finalized ? "PENDING"
                : row.getOutcomeState() == VisitSessionItemOutcomeState.VISITED ? "VIEWED" : "NOT_VIEWED";
        return new TenantVisitSessionItemOutcomeView(row.getPositionSnapshot(), row.getTitleSnapshot(),
                row.getAddressSnapshot(), row.getCitySnapshot(), row.getSectorSnapshot(), outcome,
                finalized && row.getOutcomeState() == VisitSessionItemOutcomeState.SKIPPED
                        ? tenantSafeReason(row.getSkipReason(), operationsUpdated) : null,
                operationsUpdated ? "OPERATIONS_UPDATED" : finalized ? "GE_REPORTED" : null,
                operationsUpdated ? correction.correctedAt() : null);
    }

    private String tenantSafeReason(VisitSessionItemSkipReason reason, boolean operationsUpdated) {
        String detail = switch (reason) {
            case PROPERTY_UNAVAILABLE -> "The property was reported unavailable.";
            case TENANT_DECLINED -> "The GE reported that the tenant chose not to view this property.";
            case TENANT_LEFT_EARLY -> "The GE reported that the visit ended before this property was viewed.";
            case PROPERTY_MISMATCH -> "The property details were reported not to match.";
            case ACCESS_DENIED, OTHER -> "This property was not viewed.";
        };
        return operationsUpdated ? "Pathome Operations updated the result: " + detail : "GE reported: " + detail;
    }

    private Map<Long, CorrectionTime> latestCorrectionTimes(Collection<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(sessionIds.size(), "?"));
        String sql = "select distinct on ((metadata->>'itemId')::bigint) (metadata->>'itemId')::bigint as item_id, "
                + "coalesce((metadata->>'correctedAt')::timestamptz,occurred_at) corrected_at "
                + "from visit_execution_events where session_id in (" + placeholders + ") "
                + "and event_type='OUTCOME_ITEM_CORRECTED' "
                + "and ((metadata->>'previousOutcome') is distinct from (metadata->>'correctedOutcome') "
                + "or (metadata->>'previousSkipReason') is distinct from (metadata->>'correctedSkipReason')) "
                + "order by (metadata->>'itemId')::bigint, occurred_at desc, id desc";
        return jdbc.query(sql, (rs, row) -> Map.entry(rs.getLong("item_id"),
                new CorrectionTime(rs.getTimestamp("corrected_at").toInstant())), sessionIds.toArray())
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private OperationsVisitOutcomeDetailView operationsView(VisitSession session, VisitSessionOutcomeReport report,
            List<VisitSessionItemOutcome> rows, List<OperationsVisitOutcomeAuditView> history) {
        long pending = rows.stream().filter(row -> row.getOutcomeState() == VisitSessionItemOutcomeState.UNRECORDED).count();
        long viewed = rows.stream().filter(row -> row.getOutcomeState() == VisitSessionItemOutcomeState.VISITED).count();
        List<OperationsVisitOutcomeItemView> itemViews = rows.stream().map(row -> new OperationsVisitOutcomeItemView(
                row.getItemId(), row.getPositionSnapshot(), row.getTitleSnapshot(), row.getAddressSnapshot(),
                row.getCitySnapshot(), row.getSectorSnapshot(), row.getOutcomeState().name(),
                row.getSkipReason() == null ? null : row.getSkipReason().name(), row.getPrivateNote(),
                row.getRecordedAt(), row.getRecordedBy() == null ? null : row.getRecordedBy().getId(), row.getVersion())).toList();
        return new OperationsVisitOutcomeDetailView(session.getId(), session.getStatus().name(), report.getState().name(),
                report.getVersion(), session.getRepresentative() == null ? null : session.getRepresentative().getId(),
                session.getCity(), session.getAreaName(), session.getScheduledAt(), session.getStartedAt(),
                session.getFinishedAt(), report.getUpdatedAt(), summary(report, rows), rows.size(), pending, viewed,
                itemViews, history);
    }

    private List<OperationsVisitOutcomeAuditView> correctionHistory(Long sessionId) {
        return jdbc.query("select actor_user_id,occurred_at,metadata->>'itemId' item_id,"
                        + "metadata->>'previousOutcome' previous_outcome,metadata->>'previousSkipReason' previous_reason,"
                        + "metadata->>'correctedOutcome' corrected_outcome,metadata->>'correctedSkipReason' corrected_reason,"
                        + "metadata->>'correctionReason' correction_reason from visit_execution_events "
                        + "where session_id=? and event_type='OUTCOME_ITEM_CORRECTED' order by occurred_at desc,id desc limit 100",
                (rs, row) -> new OperationsVisitOutcomeAuditView(
                        rs.getObject("item_id") == null ? null : Long.valueOf(rs.getString("item_id")),
                        rs.getString("previous_outcome"), rs.getString("previous_reason"),
                        rs.getString("corrected_outcome"), rs.getString("corrected_reason"),
                        rs.getString("correction_reason"),
                        rs.getObject("actor_user_id") == null ? null : ((Number) rs.getObject("actor_user_id")).longValue(),
                        rs.getTimestamp("occurred_at").toInstant()), sessionId);
    }

    private void validateCorrectionCommand(Long sessionId, CorrectVisitOutcomeCommand command) {
        if (sessionId == null || sessionId <= 0 || command == null || command.itemId() == null || command.itemId() <= 0
                || command.operationId() == null || command.expectedReportVersion() == null
                || command.expectedReportVersion() < 0 || command.expectedItemVersion() == null
                || command.expectedItemVersion() < 0)
            throw new IllegalArgumentException("Session, item, versions, and correction operation are required");
        if (command.outcome() != VisitSessionItemOutcomeState.VISITED
                && command.outcome() != VisitSessionItemOutcomeState.SKIPPED)
            throw new IllegalArgumentException("A corrected outcome must be Viewed or Not viewed");
        if (command.outcome() == VisitSessionItemOutcomeState.SKIPPED && command.skipReason() == null)
            throw new IllegalArgumentException("A not-viewed correction requires a reason");
        if (command.outcome() == VisitSessionItemOutcomeState.VISITED && command.skipReason() != null)
            throw new IllegalArgumentException("A viewed correction cannot have a not-viewed reason");
        String reason = command.correctionReason() == null ? "" : command.correctionReason().strip();
        if (reason.length() < 8 || reason.length() > 300)
            throw new IllegalArgumentException("Correction reason must contain 8 to 300 characters");
        String note = normalizePrivateNote(command.privateNote());
        if (note != null && note.length() > PRIVATE_NOTE_LIMIT)
            throw new IllegalArgumentException("Private outcome note is too long");
        if (command.skipReason() == VisitSessionItemSkipReason.OTHER && note == null)
            throw new IllegalArgumentException("OTHER skip reasons require an internal note");
    }

    private String correctionEventKey(Long sessionId, Long itemId, UUID operationId) {
        return "OUTCOME_CORRECTION:" + sessionId + ":" + itemId + ":" + operationId;
    }

    private String correctionRequestHash(Long sessionId, CorrectVisitOutcomeCommand command) {
        return hash(sessionId + "|" + command.itemId() + "|" + command.outcome() + "|" + command.skipReason()
                + "|" + command.privateNote() + "|" + command.correctionReason() + "|"
                + command.expectedReportVersion() + "|" + command.expectedItemVersion());
    }

    private void enqueueOutcomeReady(VisitSession session) {
        jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message,authorization_class,operational_session_id) "
                        + "values (?,?,'TENANT','VISIT_OUTCOME_READY','Visit details are ready',"
                        + "'Your visit details are ready to review in visit history.','RECIPIENT',null) on conflict(event_key) do nothing",
                "VISIT_OUTCOME_READY:" + session.getId(), session.getTenant().getId());
    }

    private record CorrectionTime(Instant correctedAt) {}

    private String summary(VisitSessionOutcomeReport report, List<VisitSessionItemOutcome> rows) {
        if (report.getState() == VisitSessionOutcomeReportState.LEGACY_UNRECORDED) return "RESULTS_NOT_RECORDED";
        if (report.getState() != VisitSessionOutcomeReportState.FINALIZED) return null;
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.getOutcomeState() == VisitSessionItemOutcomeState.UNRECORDED))
            return "OUTCOME_REVIEW_REQUIRED";
        long visited = rows.stream().filter(row -> row.getOutcomeState() == VisitSessionItemOutcomeState.VISITED).count();
        if (visited == rows.size()) return "ALL_VIEWED";
        if (visited == 0) return "NONE_VIEWED";
        return "PARTLY_VIEWED";
    }

    private String tenantLifecycle(VisitSession session, VisitSessionOutcomeReport report,
            List<VisitSessionItemOutcome> rows) {
        if (session.getStatus() == VisitSessionStatus.COMPLETED) {
            if (report == null || report.getState() == VisitSessionOutcomeReportState.OPEN) return "DETAILS_PENDING";
            if (report.getState() == VisitSessionOutcomeReportState.LEGACY_UNRECORDED) return "RESULTS_NOT_RECORDED";
            String summary = summary(report, rows);
            return switch (summary) {
                case "ALL_VIEWED" -> "COMPLETED";
                case "PARTLY_VIEWED" -> "PARTIALLY_COMPLETED";
                case "NONE_VIEWED" -> "NO_PROPERTIES_VIEWED";
                default -> "OUTCOME_REVIEW_REQUIRED";
            };
        }
        if (session.getRepairState() != null && !"NONE".equals(session.getRepairState())) return "RECOVERY_REQUIRED";
        return switch (session.getStatus()) {
            case DRAFT -> "BEING_ARRANGED";
            case SCHEDULED -> "PENDING_CONFIRMATION".equals(session.getTenantConfirmationState())
                    || "PENDING".equals(session.getTenantConfirmationState()) ? "ACTION_REQUIRED" : "UPCOMING";
            case STARTED -> "IN_PROGRESS";
            case PROVISIONAL_NO_SHOW -> "ARRIVAL_REVIEW";
            case NO_SHOW -> "NO_SHOW";
            case CANCELLED -> "CANCELLED";
            case EXPIRED -> "EXPIRED";
            case INTERRUPTED -> "INTERRUPTED";
            case REPAIR_REQUIRED -> "RECOVERY_REQUIRED";
            case COMPLETED -> "DETAILS_PENDING";
        };
    }

    private void validateRecordCommand(Long sessionId, Long itemId, RecordVisitSessionItemOutcomeCommand command) {
        if (sessionId == null || sessionId <= 0 || itemId == null || itemId <= 0 || command == null
                || command.operationId() == null || command.expectedSessionVersion() == null
                || command.expectedReportVersion() == null || command.expectedItemVersion() == null
                || command.expectedSessionVersion() < 0 || command.expectedReportVersion() < 0
                || command.expectedItemVersion() < 0)
            throw new IllegalArgumentException("Session, property, expected versions, and operation ID are required");
        if (command.outcome() != VisitSessionItemOutcomeState.VISITED
                && command.outcome() != VisitSessionItemOutcomeState.SKIPPED)
            throw new IllegalArgumentException("A recorded property outcome must be VISITED or SKIPPED");
        if (command.outcome() == VisitSessionItemOutcomeState.SKIPPED && command.skipReason() == null)
            throw new IllegalArgumentException("A skipped property requires a reason");
        if (command.outcome() == VisitSessionItemOutcomeState.VISITED && command.skipReason() != null)
            throw new IllegalArgumentException("A visited property cannot have a skip reason");
        String note = normalizePrivateNote(command.privateNote());
        if (note != null && note.length() > PRIVATE_NOTE_LIMIT)
            throw new IllegalArgumentException("Private outcome note is too long");
        if (command.skipReason() == VisitSessionItemSkipReason.OTHER && note == null)
            throw new IllegalArgumentException("OTHER skip reasons require a private note");
    }

    private String normalizePrivateNote(String value) {
        if (value == null) return null;
        String note = value.strip();
        return note.isEmpty() ? null : note;
    }

    private void insertItemOutcomeEvent(Long sessionId, Long actorId, Long itemId,
            RecordVisitSessionItemOutcomeCommand command, String requestHash, String eventKey) {
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,metadata,idempotency_key) "
                        + "values (?,?, 'ITEM_OUTCOME_RECORDED',?, "
                        + "jsonb_build_object('itemId',?,'outcome',?,'skipReason',CAST(? AS text),'operationId',?,'requestHash',?), ?) "
                        + "on conflict (idempotency_key) do nothing",
                sessionId, actorId, command.skipReason() == null ? command.outcome().name()
                        : command.skipReason().name(), itemId, command.outcome().name(),
                command.skipReason() == null ? null : command.skipReason().name(),
                command.operationId().toString(), requestHash, eventKey);
    }

    private IdempotentEvent findIdempotentEvent(String eventKey) {
        List<IdempotentEvent> matches = jdbc.query("select actor_user_id, metadata->>'requestHash' request_hash "
                        + "from visit_execution_events where idempotency_key=?",
                (rs, row) -> new IdempotentEvent((Long) rs.getObject("actor_user_id"), rs.getString("request_hash")), eventKey);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private String itemOperationKey(Long sessionId, Long itemId, UUID operationId) {
        return "ITEM_OUTCOME:" + sessionId + ":" + itemId + ":" + operationId;
    }

    private String requestHash(Long sessionId, Long itemId, RecordVisitSessionItemOutcomeCommand command) {
        return hash(sessionId + "|" + itemId + "|" + command.outcome() + "|" + command.skipReason()
                + "|" + command.privateNote() + "|" + command.expectedSessionVersion()
                + "|" + command.expectedReportVersion() + "|" + command.expectedItemVersion());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void requireCurrentGroundExecutive(VisitSession session, Long groundExecutiveId) {
        if (session.getRepresentative() == null || !groundExecutiveId.equals(session.getRepresentative().getId()))
            throw new EntityNotFoundException("Visit Session not found");
    }

    private void requireOpen(VisitSessionOutcomeReport report) {
        if (report.getState() != VisitSessionOutcomeReportState.OPEN)
            throw new VisitOperationsConflictException("Outcome report is not open for GE changes");
    }

    private void requireExpectedVersion(Long actual, Long expected, String resource) {
        if (actual == null || !actual.equals(expected))
            throw new VisitOperationsConflictException(resource + " changed; refresh before retrying");
    }

    private void validateCombinedCommand(Long sessionId, CompleteVisitSessionWithOutcomesCommand command) {
        if (sessionId == null || sessionId <= 0 || command == null || command.operationId() == null
                || command.expectedSessionVersion() == null || command.expectedSessionVersion() < 0
                || command.expectedReportVersion() == null || command.expectedReportVersion() < 0)
            throw new IllegalArgumentException("Session, expected versions, and completion operation are required");
    }

    private String combinedRequestHash(Long sessionId, CompleteVisitSessionWithOutcomesCommand command) {
        return hash(sessionId + "|combined|" + command.expectedSessionVersion() + "|" + command.expectedReportVersion());
    }

    private void requireCompleteOutcomeSet(List<VisitSessionItemOutcome> rows) {
        if (rows.isEmpty() || rows.stream().anyMatch(row -> row.getOutcomeState() == VisitSessionItemOutcomeState.UNRECORDED))
            throw new VisitOperationsConflictException("Every captured property must have an outcome before finalization");
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) throw new AccessDeniedException("Authenticated tenant identity required");
        User user = users.findById(tenantId).orElseThrow(() -> new AccessDeniedException("Authenticated tenant is unavailable"));
        if (user.getRole() != Role.ROLE_TENANT) throw new AccessDeniedException("Tenant capability required");
    }

    private record IdempotentEvent(Long actorId, String requestHash) {}
}
