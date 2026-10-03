package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.GroundVisitSessionItemOutcomeView;
import com.indore.pathome.spaces.dto.GroundVisitSessionOutcomeView;
import com.indore.pathome.spaces.dto.RecordVisitSessionItemOutcomeCommand;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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

    public VisitSessionOutcomeService(VisitSessionRepository sessions,
            VisitSessionItemRepository sessionItems,
            VisitSessionOutcomeReportRepository reports,
            VisitSessionItemOutcomeRepository outcomes,
            VisitOperationsAuthorizationService authorization,
            UserRepository users,
            JdbcTemplate jdbc,
            EntityManager entityManager) {
        this.sessions = sessions;
        this.sessionItems = sessionItems;
        this.reports = reports;
        this.outcomes = outcomes;
        this.authorization = authorization;
        this.users = users;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
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
        entityManager.flush();
        return groundView(session, report, rows);
    }

    @Transactional(readOnly = true)
    public TenantVisitSessionOutcomeView getTenantOutcomeReport(Long tenantId, Long sessionId) {
        requireTenant(tenantId);
        VisitSession session = sessions.findByIdAndTenantId(sessionId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        VisitSessionOutcomeReport report = reports.findById(sessionId).orElse(null);
        List<VisitSessionItemOutcome> rows = report == null || report.getState() != VisitSessionOutcomeReportState.FINALIZED
                ? List.of() : outcomes.findBySessionIdOrderByPositionSnapshotAscItemIdAsc(sessionId);
        return tenantView(session, report, rows);
    }

    @Transactional(readOnly = true)
    public List<TenantVisitSessionOutcomeView> listTenantOutcomeHistory(Long tenantId, int page, int size) {
        requireTenant(tenantId);
        if (page < 0 || page > 10000 || size < 1 || size > 50)
            throw new IllegalArgumentException("History page and size are outside the supported range");
        var result = sessions.findByTenantIdOrderByCreatedAtDescIdDesc(tenantId,
                org.springframework.data.domain.PageRequest.of(page, size));
        List<VisitSession> sessionPage = result.getContent();
        if (sessionPage.isEmpty()) return List.of();
        List<Long> ids = sessionPage.stream().map(VisitSession::getId).toList();
        Map<Long, VisitSessionOutcomeReport> reportBySession = reports.findAllBySessionIdIn(ids).stream()
                .collect(Collectors.toMap(VisitSessionOutcomeReport::getSessionId, Function.identity()));
        Collection<Long> finalizedIds = reportBySession.values().stream()
                .filter(report -> report.getState() == VisitSessionOutcomeReportState.FINALIZED)
                .map(VisitSessionOutcomeReport::getSessionId).toList();
        Map<Long, List<VisitSessionItemOutcome>> outcomesBySession = finalizedIds.isEmpty() ? Map.of()
                : outcomes.findBySessionIdInOrderBySessionIdAscPositionSnapshotAscItemIdAsc(finalizedIds).stream()
                    .collect(Collectors.groupingBy(VisitSessionItemOutcome::getSessionId));
        return sessionPage.stream().map(session -> tenantView(session, reportBySession.get(session.getId()),
                outcomesBySession.getOrDefault(session.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public Page<VisitSessionOutcomeExceptionView> listOperationsOutcomeExceptions(Long actorId,
            Instant completedBefore, int page, int size) {
        authorization.requireOperations(actorId);
        if (completedBefore == null || page < 0 || page > 10000 || size < 1 || size > 50)
            throw new IllegalArgumentException("A completion cutoff and valid page are required");
        return reports.findOperationsExceptions(completedBefore,
                org.springframework.data.domain.PageRequest.of(page, size));
    }

    private GroundVisitSessionOutcomeView groundView(VisitSession session, VisitSessionOutcomeReport report,
            List<VisitSessionItemOutcome> rows) {
        List<GroundVisitSessionItemOutcomeView> itemViews = rows.stream().map(row ->
                new GroundVisitSessionItemOutcomeView(row.getItemId(), row.getListingIdSnapshot(),
                        row.getPositionSnapshot(), row.getTitleSnapshot(), row.getAddressSnapshot(),
                        row.getCitySnapshot(), row.getSectorSnapshot(), row.getOutcomeState(),
                        row.getSkipReason(), row.getPrivateNote(), row.getRecordedAt())).toList();
        return new GroundVisitSessionOutcomeView(session.getId(), session.getStatus().name(), session.getVersion(),
                report.getState(), report.getVersion(), report.getScopeCapturedAt(), summary(report, rows), itemViews);
    }

    private TenantVisitSessionOutcomeView tenantView(VisitSession session, VisitSessionOutcomeReport report,
            List<VisitSessionItemOutcome> rows) {
        String outcomeSummary = report == null ? null : summary(report, rows);
        boolean finalized = report != null && report.getState() == VisitSessionOutcomeReportState.FINALIZED
                && List.of("ALL_VIEWED", "PARTLY_VIEWED", "NONE_VIEWED").contains(outcomeSummary);
        List<TenantVisitSessionItemOutcomeView> properties = finalized ? rows.stream().map(row ->
                new TenantVisitSessionItemOutcomeView(row.getPositionSnapshot(), row.getTitleSnapshot(),
                        row.getAddressSnapshot(), row.getCitySnapshot(), row.getSectorSnapshot(),
                        row.getOutcomeState(), row.getSkipReason())).toList() : List.of();
        return new TenantVisitSessionOutcomeView(session.getId(), tenantLifecycle(session, report, rows),
                finalized ? outcomeSummary : null, session.getScheduledAt(), session.getStartedAt(),
                session.getFinishedAt(), properties);
    }

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
                || command.expectedReportVersion() == null || command.expectedSessionVersion() < 0
                || command.expectedReportVersion() < 0)
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
                + "|" + command.expectedReportVersion());
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
            throw new AccessDeniedException("This Ground Executive is no longer assigned to the Visit Session");
    }

    private void requireOpen(VisitSessionOutcomeReport report) {
        if (report.getState() != VisitSessionOutcomeReportState.OPEN)
            throw new VisitOperationsConflictException("Outcome report is not open for GE changes");
    }

    private void requireExpectedVersion(Long actual, Long expected, String resource) {
        if (actual == null || !actual.equals(expected))
            throw new VisitOperationsConflictException(resource + " changed; refresh before retrying");
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) throw new AccessDeniedException("Authenticated tenant identity required");
        User user = users.findById(tenantId).orElseThrow(() -> new AccessDeniedException("Authenticated tenant is unavailable"));
        if (user.getRole() != Role.ROLE_TENANT) throw new AccessDeniedException("Tenant capability required");
    }

    private record IdempotentEvent(Long actorId, String requestHash) {}
}
