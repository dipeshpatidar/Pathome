package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.config.VisitExecutionProperties;
import com.indore.pathome.spaces.dto.*;
import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.VisitSessionRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.repository.EmployeeProfileRepository;
import com.indore.pathome.spaces.repository.GroundExecutiveSchedulingProfileRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class VisitExecutionService {
    private static final Logger log = LoggerFactory.getLogger(VisitExecutionService.class);
    private static final int MAX_ETA_MINUTES = 24 * 60;
    private static final int MAX_EXECUTION_REPAIR_RETRIES = 2;
    private final VisitSessionRepository sessions;
    private final UserRepository users;
    private final EmployeeProfileRepository employees;
    private final GroundExecutiveSchedulingProfileRepository schedulingProfiles;
    private final VisitOperationsAuthorizationService authorization;
    private final VisitEntitlementStore entitlements;
    private final VisitExecutionProperties properties;
    private final VisitOtpDeliveryProvider delivery;
    private final VisitOtpCrypto otpCrypto;
    private final JdbcTemplate jdbc;
    private final VisitSchedulingRecommendationService recommendations;
    private final VisitSessionOutcomeService outcomes;
    private final TransactionTemplate repairTransaction;

    public VisitExecutionService(VisitSessionRepository sessions, UserRepository users, EmployeeProfileRepository employees,
            GroundExecutiveSchedulingProfileRepository schedulingProfiles,
            VisitOperationsAuthorizationService authorization, VisitEntitlementStore entitlements,
            VisitExecutionProperties properties, VisitOtpDeliveryProvider delivery, VisitOtpCrypto otpCrypto, JdbcTemplate jdbc,
            VisitSchedulingRecommendationService recommendations, VisitSessionOutcomeService outcomes,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.users = users;
        this.employees = employees;
        this.schedulingProfiles = schedulingProfiles;
        this.authorization = authorization;
        this.entitlements = entitlements;
        this.properties = properties;
        this.delivery = delivery;
        this.otpCrypto = otpCrypto;
        this.jdbc = jdbc;
        this.recommendations = recommendations;
        this.outcomes = outcomes;
        this.repairTransaction = new TransactionTemplate(transactionManager);
        this.repairTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public TenantVisitExecutionPage listTenantSessions(Long tenantId, int page) {
        requireTenant(tenantId);
        if (page < 0 || page > 10000) throw new IllegalArgumentException("Page must be within 0..10000");
        var result = sessions.findByTenantIdOrderByCreatedAtDescIdDesc(tenantId, PageRequest.of(page, 20));
        return new TenantVisitExecutionPage(result.getContent().stream().map(this::executionView).toList(),
                result.getNumber(), result.getSize(), result.getTotalPages());
    }

    @Transactional
    public VisitExecutionView markArrived(Long groundExecutiveId, Long sessionId) {
        authorization.requireGroundExecutive(groundExecutiveId);
        VisitSession session = lockAssignedSession(groundExecutiveId, sessionId);
        requirePreStart(session);
        Instant now = Instant.now();
        if (session.getArrivedAt() == null) {
            session.setArrivedAt(now);
            session.setExecutionStateChangedAt(now);
            audit(sessionId, groundExecutiveId, "ARRIVED", null, "ARRIVED:" + sessionId);
        }
        return executionView(session);
    }

    @Transactional(readOnly = true)
    public VisitExecutionView getAssignedExecution(Long groundExecutiveId, Long sessionId) {
        authorization.requireGroundExecutive(groundExecutiveId);
        VisitSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        if (session.getRepresentative() == null || !groundExecutiveId.equals(session.getRepresentative().getId())
                || !List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED,
                    VisitSessionStatus.PROVISIONAL_NO_SHOW, VisitSessionStatus.REPAIR_REQUIRED).contains(session.getStatus()))
            throw new EntityNotFoundException("Visit Session not found");
        return executionView(session);
    }

    @Transactional
    public GroundVisitTenantContactView getTenantContact(Long groundExecutiveId, Long sessionId) {
        authorization.requireGroundExecutive(groundExecutiveId);
        // Serialize contact disclosure with reassignment so a former GE cannot read
        // the tenant's contact after an assignment change commits concurrently.
        VisitSession session = lockAssignedSession(groundExecutiveId, sessionId);
        Instant now = Instant.now();
        boolean inContactWindow = session.getStatus() == VisitSessionStatus.STARTED
                || session.getArrivedAt() != null
                || (session.getScheduledAt() != null && !session.getScheduledAt().isAfter(now.plus(Duration.ofMinutes(30)))
                    && session.getScheduledAt().isAfter(now.minus(Duration.ofHours(2))));
        if (!inContactWindow || !List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED,
                VisitSessionStatus.PROVISIONAL_NO_SHOW).contains(session.getStatus()))
            throw new AccessDeniedException("Tenant contact is available only near an assigned visit");
        User tenant = session.getTenant();
        audit(sessionId, groundExecutiveId, "TENANT_CONTACT_VIEWED", null,
                "CONTACT_VIEW:" + sessionId + ":" + groundExecutiveId + ":" + now.toEpochMilli());
        return new GroundVisitTenantContactView(sessionId, tenant.getFullName(), tenant.getPhoneNumber());
    }

    @Transactional
    public GroundVisitStartCodeStatus getStartCodeStatus(Long groundExecutiveId, Long sessionId) {
        authorization.requireGroundExecutive(groundExecutiveId);
        VisitSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        if (session.getRepresentative() == null || !groundExecutiveId.equals(session.getRepresentative().getId())
                || !List.of(VisitSessionStatus.SCHEDULED, VisitSessionStatus.STARTED).contains(session.getStatus()))
            throw new EntityNotFoundException("Visit Session not found");
        Challenge current = challenge(sessionId, false);
        if (current == null || !groundExecutiveId.equals(current.groundExecutiveId())
                || !challengeStillAvailable(current.expiresAt(), Instant.now())
                || current.consumedAt() != null || current.invalidatedAt() != null)
            return new GroundVisitStartCodeStatus(sessionId, false, 0, null, null);
        return new GroundVisitStartCodeStatus(sessionId, true, current.generation(), current.expiresAt(), current.lockedUntil());
    }

    public VisitExecutionView reportContact(Long groundExecutiveId, Long sessionId, GroundVisitContactCommand command) {
        return executeRepairWithRetry(() -> reportContactAttempt(groundExecutiveId, sessionId, command));
    }

    private VisitExecutionView reportContactAttempt(Long groundExecutiveId, Long sessionId, GroundVisitContactCommand command) {
        authorization.requireGroundExecutive(groundExecutiveId);
        if (command == null || command.outcome() == null || command.operationId() == null)
            throw new IllegalArgumentException("Contact outcome and operation ID are required");
        VisitSession session = lockAssignedSession(groundExecutiveId, sessionId);
        String contactKey = "CONTACT:" + sessionId + ":" + command.operationId();
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from visit_execution_events where idempotency_key=?)", Boolean.class, contactKey)))
            return executionView(session);
        requirePreStart(session);
        String outcome = command.outcome().trim().toUpperCase();
        if (!List.of("NO_ANSWER", "BUSY", "CONNECTED", "TENANT_CONFIRMED", "TENANT_ACCEPTED_RESCHEDULE",
                "TENANT_DECLINED_RESCHEDULE").contains(outcome))
            throw new IllegalArgumentException("Unsupported contact outcome");
        Instant now = Instant.now();
        if (command.tenantEtaAt() != null && (command.tenantEtaAt().isBefore(now)
                || command.tenantEtaAt().isAfter(now.plus(Duration.ofMinutes(MAX_ETA_MINUTES)))))
            throw new IllegalArgumentException("Tenant ETA must be within the next 24 hours");
        Integer recent = jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='CONTACT_ATTEMPT' and occurred_at > ?",
                Integer.class, sessionId, java.sql.Timestamp.from(now.minus(Duration.ofMinutes(properties.getContactAttemptSpacingMinutes()))));
        if (recent != null && recent > 0) throw new VisitOperationsConflictException("Wait before recording another contact attempt");
        if (outcome.equals("TENANT_CONFIRMED") && command.tenantEtaAt() == null)
            throw new IllegalArgumentException("A tenant ETA/time is required for verbal confirmation");
        String reason = boundedReason(command.reasonCode());
        String metadata = "{\"outcome\":\"" + outcome + "\",\"tenantEtaAt\":"
                + (command.tenantEtaAt() == null ? "null" : "\"" + command.tenantEtaAt() + "\"") + "}";
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,metadata,idempotency_key) values (?,?,'CONTACT_ATTEMPT',?,?::jsonb,?)",
                sessionId, groundExecutiveId, reason, metadata, contactKey);
        if (session.getStatus() == VisitSessionStatus.PROVISIONAL_NO_SHOW
                && List.of("CONNECTED", "BUSY", "TENANT_DECLINED_RESCHEDULE").contains(outcome)) {
            // A live response supersedes the unanswered evidence, but it does not
            // establish that the visit happened or that a new time is feasible.
            session.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
            session.setTenantConfirmationState("PENDING");
            session.setRepairState("REQUIRED");
            session.setRepairOperationId(command.operationId());
            session.setNoShowDisputeUntil(null);
            session.setExecutionStateChangedAt(now);
            invalidateUnconsumedChallenge(sessionId, now);
            audit(sessionId, groundExecutiveId, "NO_SHOW_SUPERSEDED_BY_CONTACT", outcome,
                    "NO_SHOW_CONTACT_RECOVERED:" + sessionId + ":" + command.operationId());
            enqueueTenant(session.getTenant().getId(), "NO_SHOW_CONTACT_RECOVERED:" + sessionId + ":" + command.operationId(),
                    "Visit needs a new confirmation", "Your response was recorded and the provisional no-show review stopped. The visit has not started; Operations will confirm the next safe step.",
                    "VISIT_REPAIR_REQUIRED");
            enqueueOperations(sessionId, groundExecutiveId, command.operationId(),
                    "The tenant responded during provisional no-show review; confirm the next safe visit state.");
            return executionView(session, "NO_SHOW_SUPERSEDED");
        }
        if (outcome.equals("TENANT_ACCEPTED_RESCHEDULE")) {
            if (command.tenantEtaAt() == null)
                throw new IllegalArgumentException("Tenant-confirmed ETA is required to record reschedule consent");
            return applyConsentedReschedule(session, groundExecutiveId, command.tenantEtaAt(),
                    command.operationId(), reason, now, outcome);
        }
        if (outcome.equals("TENANT_CONFIRMED")) {
            if (command.tenantEtaAt() == null)
                throw new IllegalArgumentException("A tenant ETA/time is required for verbal confirmation");
            return applyConsentedReschedule(session, groundExecutiveId, command.tenantEtaAt(),
                    command.operationId(), reason, now, outcome);
        }
        if (outcome.equals("TENANT_DECLINED_RESCHEDULE")
                && "PENDING".equals(session.getTenantConfirmationState())) {
            session.setTenantConfirmationState("REJECTED");
            session.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
            session.setRepairState("REQUIRED");
            session.setRepairOperationId(command.operationId());
            session.setExecutionStateChangedAt(now);
            invalidateUnconsumedChallenge(sessionId, now);
            enqueueTenant(session.getTenant().getId(), "GE_DECLINED_RESCHEDULE:" + sessionId + ":" + command.operationId(),
                    "Visit time needs review", "Your Ground Executive recorded that you declined the proposed time. Operations will arrange another safe option.", "VISIT_REPAIR_REQUIRED");
            enqueueOperations(sessionId, groundExecutiveId, command.operationId(),
                    "The tenant declined a pending visit-time proposal; arrange another safe option.");
            return executionView(session, "RESCHEDULE_REQUIRES_OPERATIONS");
        }
        if (command.tenantEtaAt() != null) session.setTenantEtaAt(command.tenantEtaAt());
        if (outcome.equals("TENANT_CONFIRMED")) {
            session.setTenantConfirmationState("CONFIRMED");
            session.setTenantConfirmedAt(now);
            session.setTenantConfirmedBy(users.getReferenceById(groundExecutiveId));
            session.setExecutionStateChangedAt(now);
            enqueueTenant(session.getTenant().getId(), "VERBAL_TENANT_CONFIRM:" + sessionId + ":" + now.toEpochMilli(),
                    "Visit time confirmed", "Your Ground Executive recorded your verbal confirmation for "
                            + formatSessionTime(command.tenantEtaAt(), session) + ". Contact Pathome support if this does not match what you agreed.", "TENANT_TIME_CONFIRMED");
        }
        return executionView(session);
    }

    private VisitExecutionView applyConsentedReschedule(VisitSession session, Long actorId, Instant agreedAt,
            UUID operationId, String reason, Instant now, String consentOutcome) {
        Long currentGeId = session.getRepresentative().getId();
        List<VisitSchedulingRecommendationService.LiveRepairCandidate> evaluated = liveRepairCandidates(
                session, List.of(agreedAt), false, currentGeId);
        var chosen = evaluated.stream().filter(candidate -> candidate.start().equals(agreedAt))
                .sorted(java.util.Comparator.comparing((VisitSchedulingRecommendationService.LiveRepairCandidate c) ->
                        c.geId().equals(currentGeId) ? 0 : 1).thenComparing(
                        VisitSchedulingRecommendationService.LiveRepairCandidate::geId))
                .findFirst().orElse(null);
        List<Long> profileLocks = chosen == null || chosen.geId().equals(currentGeId)
                ? List.of(currentGeId)
                : List.of(currentGeId, chosen.geId());
        if (chosen != null && lockSchedulingProfiles(profileLocks)) {
            var fresh = liveRepairCandidates(session, List.of(agreedAt), true, currentGeId).stream()
                    .filter(candidate -> candidate.geId().equals(chosen.geId()) && candidate.start().equals(agreedAt))
                    .findFirst().orElse(null);
            var employee = employees.findLockedByUserId(chosen.geId())
                    .filter(profile -> "GROUND_BOY".equalsIgnoreCase(profile.getRoleType())).orElse(null);
            if (fresh != null && employee != null) {
                authorization.requireGroundExecutiveTarget(chosen.geId());
                List<Downstream> downstream = moveOverlappingDownstreamToRepair(session.getId(), chosen.geId(),
                        fresh.start(), fresh.end(), operationId);
                session.setRepresentative(employee.getUser());
                session.setAssignedAt(chosen.geId().equals(currentGeId) ? session.getAssignedAt() : now);
                session.setScheduledAt(fresh.start());
                session.setReservedEndAt(fresh.end());
                session.setDurationSnapshotMinutes(fresh.durationMinutes());
                session.setZoneId(fresh.zoneId());
                session.setStatus(VisitSessionStatus.SCHEDULED);
                session.setTenantEtaAt(agreedAt);
                session.setTenantConfirmationState("CONFIRMED");
                session.setTenantConfirmedAt(now);
                session.setTenantConfirmedBy(users.getReferenceById(actorId));
                session.setRepairState("NONE");
                session.setRepairOperationId(null);
                session.setExecutionStateChangedAt(now);
                sessions.saveAndFlush(session);
                invalidateUnconsumedChallenge(session.getId(), now);
                audit(session.getId(), actorId, consentOutcome, reason,
                        "ASSISTED_RESCHEDULE:" + operationId);
                enqueueTenant(session.getTenant().getId(), "ASSISTED_RESCHEDULE_CONFIRMED:" + operationId,
                        "Visit time confirmed", "Your visit is confirmed for " + formatSessionTime(agreedAt, session) + ".",
                        "VISIT_RESCHEDULED");
                if (!chosen.geId().equals(currentGeId)) {
                    enqueueGroundExecutiveRecipient(session.getId(), currentGeId, "ASSISTED_RESCHEDULE_FORMER_GE:" + operationId,
                            "Visit reassigned", "This visit has been reassigned. Its tenant contact and execution access are no longer available to you.", "GE_REASSIGNED");
                    enqueueGroundExecutiveRecipient(session.getId(), chosen.geId(), "ASSISTED_RESCHEDULE_NEW_GE:" + operationId,
                            "Visit assigned", "A visit has been assigned to you at " + formatSessionTime(agreedAt, session) + ". Refresh your assigned visits for details.", "GE_REASSIGNED");
                }
                repairDownstream(downstream, operationId, session.getId(), chosen.geId(), fresh.end());
                return executionView(session, "RESCHEDULE_CONFIRMED");
            }
        }

        UUID repairOperationId = operationId;
        session.setTenantEtaAt(agreedAt);
        session.setTenantConfirmationState("PENDING");
        session.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
        session.setRepairState("REQUIRED");
        session.setRepairOperationId(repairOperationId);
        session.setExecutionStateChangedAt(now);
        audit(session.getId(), actorId, consentOutcome, reason,
                "ASSISTED_RESCHEDULE:" + operationId);
        enqueueTenant(session.getTenant().getId(), "RESCHEDULE_REPAIR_REQUIRED:" + operationId,
                "Operations is checking your requested time", "Your Ground Executive recorded your agreement. We could not confirm that time against the visit and property availability yet, so Operations will contact you with a safe option.",
                "VISIT_REPAIR_REQUIRED");
        enqueueOperations(session.getId(), actorId, repairOperationId,
                "Tenant consented to a reschedule, but the requested time did not pass current visit and property feasibility checks.");
        return executionView(session, "RESCHEDULE_REQUIRES_OPERATIONS");
    }

    @Transactional
    public VisitStartCodeView issueStartCode(Long tenantId, Long sessionId) {
        requireTenant(tenantId);
        requireOtpKey();
        VisitSession session = sessions.findLockedByIdAndTenantId(sessionId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        requirePreStart(session);
        if (session.getRepresentative() == null) throw new VisitOperationsConflictException("A Ground Executive must be assigned first");
        if (!"NONE".equals(session.getRepairState()) || "PENDING".equals(session.getTenantConfirmationState())
                || "REJECTED".equals(session.getTenantConfirmationState()) || session.getStatus() == VisitSessionStatus.REPAIR_REQUIRED)
            throw new VisitOperationsConflictException("Visit time requires Operations repair before a start code can be issued");
        Instant now = Instant.now();
        if (session.getArrivedAt() == null && (session.getScheduledAt() == null
                || session.getScheduledAt().isAfter(now.plus(Duration.ofMinutes(30)))))
            throw new VisitOperationsConflictException("A start code is available near the scheduled visit or after the Ground Executive reports arrival");
        // A planned booking outside the horizon can acquire its one hold lazily when it enters the horizon.
        if (!entitlements.reserve(tenantId, sessionId, now.plus(Duration.ofDays(properties.getReservationHorizonDays()))))
            throw new VisitOperationsConflictException("ENTITLEMENT_UNAVAILABLE: contact Operations before requesting a start code");
        Challenge current = challenge(sessionId, true);
        if (current != null && current.consumedAt() == null && current.invalidatedAt() == null
                && now.isBefore(current.nextIssueAllowedAt()))
            throw new VisitOperationsConflictException("A new code can be requested after the cooldown");
        int generation = current == null ? 1 : current.generation() + 1;
        String code = otpCrypto.newCode();
        Instant expiresAt = now.plus(Duration.ofMinutes(properties.getOtp().getValidityMinutes()));
        Instant nextIssueAt = now.plus(Duration.ofSeconds(properties.getOtp().getRegenerationCooldownSeconds()));
        byte[] digest = otpCrypto.digest(sessionId, tenantId, session.getRepresentative().getId(), generation,
                code, properties.getOtp().getKeyId());
        if (current == null) {
            jdbc.update("insert into visit_start_challenges(session_id,tenant_id,ground_executive_user_id,generation,key_id,digest,issued_at,expires_at,next_issue_allowed_at) values (?,?,?,?,?,?,?,?,?)",
                    sessionId, tenantId, session.getRepresentative().getId(), generation, properties.getOtp().getKeyId(), digest,
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(expiresAt), java.sql.Timestamp.from(nextIssueAt));
        } else {
            jdbc.update("update visit_start_challenges set tenant_id=?,ground_executive_user_id=?,generation=?,key_id=?,digest=?,issued_at=?,expires_at=?,next_issue_allowed_at=?,consumed_at=null,invalidated_at=null,failed_attempts=0,locked_until=null,version=version+1 where id=?",
                    tenantId, session.getRepresentative().getId(), generation, properties.getOtp().getKeyId(), digest,
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(expiresAt), java.sql.Timestamp.from(nextIssueAt), current.id());
        }
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,idempotency_key) values (?,?,'CODE_ISSUED',?,'OTP_ISSUED:'||?||':'||?) on conflict(idempotency_key) do nothing",
                sessionId, tenantId, "GENERATION_" + generation, sessionId, generation);
        VisitOtpDeliveryProvider.DeliveryResult delivered = delivery.deliverToAuthenticatedTenant(tenantId, sessionId, code);
        return new VisitStartCodeView(sessionId, generation, delivered.displayCode(), expiresAt, nextIssueAt, delivered.channel());
    }

    public VisitExecutionView start(Long groundExecutiveId, Long sessionId, VisitOtpStartCommand command) {
        return executeRepairWithRetry(() -> startAttempt(groundExecutiveId, sessionId, command));
    }

    private VisitExecutionView startAttempt(Long groundExecutiveId, Long sessionId, VisitOtpStartCommand command) {
        authorization.requireGroundExecutive(groundExecutiveId);
        if (command == null || command.operationId() == null || command.generation() == null
                || command.generation() < 1 || command.code() == null || !command.code().matches("^[0-9]{6}$"))
            throw new IllegalArgumentException("A valid generation, six-digit code, and operation ID are required");
        requireOtpKey();
        VisitSession session = lockAssignedSession(groundExecutiveId, sessionId);
        if (session.getStatus() == VisitSessionStatus.STARTED) {
            if (command.operationId().equals(session.getStartOperationId())) return executionView(session);
            throw new VisitOperationsConflictException("This Visit Session has already started");
        }
        requirePreStart(session);
        if (!"NONE".equals(session.getRepairState()) || "PENDING".equals(session.getTenantConfirmationState())
                || "REJECTED".equals(session.getTenantConfirmationState()))
            throw new VisitOperationsConflictException("Visit time requires tenant confirmation or repair before start");
        Challenge challenge = challenge(sessionId, false);
        if (challenge == null || challenge.invalidatedAt() != null || challenge.consumedAt() != null)
            throw new VisitOperationsConflictException("A current start code is required");
        if (!challenge.groundExecutiveId().equals(groundExecutiveId))
            throw new AccessDeniedException("This Ground Executive is not assigned to the Visit Session");
        if (command.generation() != challenge.generation())
            throw new VisitOperationsConflictException("CODE_REFRESHED: request the latest start code");
        Instant now = Instant.now();
        if (challenge.failedAttempts() >= properties.getOtp().getMaximumAttempts())
            throw new VisitOperationsConflictException("TOO_MANY_ATTEMPTS: request a new start code after the cooldown");
        if (challenge.lockedUntil() != null && challenge.lockedUntil().isAfter(now))
            throw new VisitOperationsConflictException("TOO_MANY_ATTEMPTS: wait before requesting a new code");
        if (!challengeStillAvailable(challenge.expiresAt(), now)) throw new VisitOperationsConflictException("CODE_EXPIRED: request a new code");
        if (!otpCrypto.matches(challenge.digest(), sessionId, session.getTenant().getId(), groundExecutiveId,
                challenge.generation(), command.code(), challenge.keyId())) {
            int attempts = challenge.failedAttempts() + 1;
            Instant lockedUntil = attempts >= properties.getOtp().getMaximumAttempts()
                    ? now.plusSeconds(properties.getOtp().getRegenerationCooldownSeconds()) : null;
            jdbc.update("update visit_start_challenges set failed_attempts=?,locked_until=?,version=version+1 where id=?",
                    attempts, lockedUntil == null ? null : java.sql.Timestamp.from(lockedUntil), challenge.id());
            audit(sessionId, groundExecutiveId, "OTP_REJECTED", "INVALID_CODE", "OTP_FAILURE:" + sessionId + ":" + UUID.randomUUID());
            return executionView(session, attempts >= properties.getOtp().getMaximumAttempts()
                    ? "TOO_MANY_ATTEMPTS" : "INVALID_CODE"); // Commit attempt count; no exception rolls it back.
        }

        int duration = session.getDurationSnapshotMinutes();
        if (duration <= 0) throw new VisitOperationsConflictException("Session duration is unavailable");
        Instant expectedEnd = now.plus(Duration.ofMinutes(duration));
        if (!recommendations.actualExecutionWindowsValid(session, now, expectedEnd)) {
            session.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
            session.setTenantConfirmationState("PENDING");
            session.setRepairState("REQUIRED");
            session.setRepairOperationId(command.operationId());
            session.setExecutionStateChangedAt(now);
            invalidateUnconsumedChallenge(sessionId, now);
            audit(sessionId, groundExecutiveId, "REPAIR_REQUIRED", "EXECUTION_WINDOW_UNAVAILABLE",
                    "ACTUAL_WINDOW_REPAIR:" + sessionId + ":" + command.operationId());
            notifyRepairRequired(session, command.operationId(),
                    "Actual start is outside confirmed tenant or property availability.");
            return executionView(session, "REPAIR_REQUIRED");
        }
        var employee = employees.findLockedByUserId(groundExecutiveId)
                .filter(profile -> "GROUND_BOY".equalsIgnoreCase(profile.getRoleType()))
                .orElseThrow(() -> new AccessDeniedException("Current Ground Executive capability is unavailable"));
        authorization.requireGroundExecutiveTarget(employee.getUser().getId());
        Integer activeVisits = jdbc.queryForObject("select count(*) from visit_sessions where representative_user_id=? and status='STARTED' and id<>?",
                Integer.class, groundExecutiveId, sessionId);
        if (activeVisits != null && activeVisits > 0) {
            VisitExecutionView alternate = trySameTimeAlternate(session, groundExecutiveId, command.operationId(), now);
            if (alternate != null) return alternate;
            session.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
            session.setTenantConfirmationState("PENDING");
            session.setRepairState("REQUIRED");
            session.setRepairOperationId(command.operationId());
            session.setExecutionStateChangedAt(now);
            jdbc.update("update visit_start_challenges set invalidated_at=?,version=version+1 where id=? and invalidated_at is null",
                    java.sql.Timestamp.from(now), challenge.id());
            audit(sessionId, groundExecutiveId, "REPAIR_REQUIRED", "GE_ALREADY_HAS_ACTIVE_VISIT", "ACTIVE_GE_REPAIR:" + command.operationId());
            enqueueTenant(session.getTenant().getId(), "ACTIVE_GE_RECOVERY:" + command.operationId(),
                    "Visit needs immediate recovery", "Your Ground Executive is still completing another active visit. Operations is arranging a safe recovery; this visit has not started or used a credit.", "VISIT_REPAIR_REQUIRED");
            enqueueOperations(sessionId, groundExecutiveId, command.operationId(),
                    "Visit Session " + sessionId + " arrived while its assigned Ground Executive had another active visit. Arrange immediate recovery.");
            return executionView(session, "REPAIR_REQUIRED");
        }
        // V36's exclusion range begins at scheduled_at; move every conflicting scheduled reservation first.
        List<Downstream> downstream = moveOverlappingDownstreamToRepair(
                sessionId, groundExecutiveId, session.getScheduledAt(), expectedEnd, command.operationId());
        if (hasUnplannedOverlap(sessionId, groundExecutiveId, session.getScheduledAt(), expectedEnd))
            throw new StaleRepairPlanException("A new GE reservation entered the execution interval");
        entitlements.consume(session.getTenant().getId(), sessionId, groundExecutiveId);
        session.setStartedAt(now);
        session.setExecutionDurationSnapshotMinutes(duration);
        session.setExpectedEndAt(expectedEnd);
        session.setReservedEndAt(expectedEnd);
        session.setStatus(VisitSessionStatus.STARTED);
        session.setEntitlementConsumedAt(now);
        session.setStartOperationId(command.operationId());
        session.setExecutionStateChangedAt(now);
        jdbc.update("update visit_start_challenges set consumed_at=?,version=version+1 where id=? and consumed_at is null",
                java.sql.Timestamp.from(now), challenge.id());
        audit(sessionId, groundExecutiveId, "STARTED", "OTP_VERIFIED", "START:" + command.operationId());
        sessions.saveAndFlush(session);
        outcomes.captureSuccessfulStart(session, groundExecutiveId, command.operationId());
        repairDownstream(downstream, command.operationId(), sessionId, groundExecutiveId, expectedEnd);
        enqueueTenant(session.getTenant().getId(), "VISIT_STARTED:" + sessionId + ":" + command.operationId(),
                "Visit started", "Your visit has started.", "VISIT_STARTED");
        return executionView(session);
    }

    private VisitExecutionView trySameTimeAlternate(VisitSession session, Long formerGeId, UUID operationId, Instant now) {
        if (session.getScheduledAt() == null || !session.getScheduledAt().isAfter(now)) return null;
        List<VisitSchedulingRecommendationService.LiveRepairCandidate> feasible = liveRepairCandidates(
                session, List.of(session.getScheduledAt()), false, formerGeId);
        VisitSchedulingRecommendationService.LiveRepairCandidate alternate = feasible.stream()
                .filter(candidate -> !candidate.geId().equals(formerGeId)
                        && candidate.start().equals(session.getScheduledAt()))
                .sorted(java.util.Comparator.comparing(VisitSchedulingRecommendationService.LiveRepairCandidate::geId))
                .findFirst().orElse(null);
        if (alternate == null) return null;
        var employee = employees.findLockedByUserId(alternate.geId())
                .filter(profile -> "GROUND_BOY".equalsIgnoreCase(profile.getRoleType()))
                .orElse(null);
        if (employee == null || !lockSchedulingProfiles(List.of(formerGeId, alternate.geId()))) return null;
        authorization.requireGroundExecutiveTarget(alternate.geId());
        if (liveRepairCandidates(session, List.of(alternate.start()), true, formerGeId).stream()
                .noneMatch(candidate -> candidate.geId().equals(alternate.geId())
                        && candidate.start().equals(alternate.start()))) return null;

        session.setRepresentative(employee.getUser());
        session.setAssignedAt(now);
        session.setScheduledAt(alternate.start());
        session.setReservedEndAt(alternate.end());
        session.setDurationSnapshotMinutes(alternate.durationMinutes());
        session.setZoneId(alternate.zoneId());
        session.setTenantConfirmationState("CONFIRMED");
        session.setRepairState("NONE");
        session.setRepairOperationId(null);
        session.setExecutionStateChangedAt(now);
        sessions.saveAndFlush(session);
        invalidateUnconsumedChallenge(session.getId(), now);
        audit(session.getId(), formerGeId, "GE_REASSIGNED", "ACTIVE_VISIT_RECOVERY", "ACTIVE_GE_ALT:" + operationId);
        enqueueGroundExecutiveRecipient(session.getId(), formerGeId, "GE_REMOVED:" + session.getId() + ":" + operationId,
                "Visit reassigned", "This visit has been reassigned. Its tenant contact and execution access are no longer available to you.", "GE_REASSIGNED");
        enqueueGroundExecutiveRecipient(session.getId(), alternate.geId(), "GE_ASSIGNED:" + session.getId() + ":" + operationId,
                "Visit assigned", "A visit has been assigned to you at " + formatSessionTime(alternate.start(), session) + ". Refresh your assigned visits for details.", "GE_REASSIGNED");
        enqueueTenant(session.getTenant().getId(), "VISIT_GE_REASSIGNED:" + session.getId() + ":" + operationId,
                "Ground Executive updated", "Your Ground Executive changed. Your confirmed visit time is unchanged. Request the latest start code near the visit.", "GE_REASSIGNED");
        return executionView(session, "ALTERNATE_GE_ASSIGNED");
    }

    private void repairDownstream(List<Downstream> affected, UUID operationId, Long currentSessionId,
            Long groundExecutiveId, Instant currentExpectedEnd) {
        Instant now = Instant.now();
        Instant repairCutoff = now.plus(Duration.ofHours(properties.getRepairHorizonHours()));
        List<Downstream> work = new java.util.ArrayList<>(affected);
        List<Downstream> lockOrder = work.stream().collect(java.util.stream.Collectors.toMap(
                        Downstream::id, item -> item, (left, right) -> left))
                .values().stream().sorted(java.util.Comparator.comparing(Downstream::id)).toList();
        java.util.Map<Long, VisitSession> lockedSessions = new java.util.LinkedHashMap<>();
        for (Downstream item : lockOrder)
            sessions.findLockedById(item.id()).ifPresent(session -> lockedSessions.put(item.id(), session));

        for (Downstream item : work.stream().collect(java.util.stream.Collectors.toMap(
                        Downstream::id, item -> item, (left, right) -> left))
                .values().stream().sorted(java.util.Comparator.comparing(Downstream::scheduledAt)
                        .thenComparing(Downstream::id)).toList()) {
            VisitSession downstream = lockedSessions.get(item.id());
            if (downstream == null) continue;
            boolean tenantAcceptanceRequired = "PENDING".equals(downstream.getTenantConfirmationState());
            if (downstream.getStatus() == VisitSessionStatus.SCHEDULED) {
                Long assignedGeId = downstream.getRepresentative() == null ? null : downstream.getRepresentative().getId();
                boolean unchangedFeasible = liveRepairCandidates(downstream, List.of(item.scheduledAt()), false, assignedGeId)
                        .stream().anyMatch(candidate -> candidate.geId().equals(assignedGeId)
                                && candidate.start().equals(item.scheduledAt()));
                if (unchangedFeasible) continue;
                downstream.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
                downstream.setRepairState("REQUIRED");
                downstream.setRepairOperationId(operationId);
                downstream.setExecutionStateChangedAt(now);
                sessions.saveAndFlush(downstream);
                audit(downstream.getId(), groundExecutiveId, "REPAIR_REQUIRED", "TRAVEL_BUFFER_NO_LONGER_FEASIBLE",
                        "REPAIR:" + operationId + ":" + downstream.getId());
            }
            if (downstream == null || downstream.getStatus() != VisitSessionStatus.REPAIR_REQUIRED
                    || !operationId.equals(downstream.getRepairOperationId())) continue;

            java.util.TreeSet<Instant> requestedSet = new java.util.TreeSet<>();
            Instant base = item.scheduledAt().isAfter(now) ? item.scheduledAt() : now.plusSeconds(60);
            requestedSet.add(base.truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
            for (int minute = 1; minute <= properties.getAutoShiftThresholdMinutes(); minute++)
                requestedSet.add(item.scheduledAt().plus(Duration.ofMinutes(minute)));
            for (int minute = 1; minute <= properties.getRepairHorizonHours() * 60; minute++)
                requestedSet.add(base.plus(Duration.ofMinutes(minute)));
            List<Instant> requested = requestedSet.stream().filter(start -> !start.isAfter(repairCutoff)).toList();
            List<VisitSchedulingRecommendationService.LiveRepairCandidate> feasible;
            try {
                feasible = liveRepairCandidates(downstream, requested, false,
                        downstream.getRepresentative() == null ? null : downstream.getRepresentative().getId()).stream()
                        .filter(candidate -> !candidate.start().isAfter(repairCutoff)).toList();
            } catch (VisitOperationsConflictException stale) {
                continue;
            }
            Long originalGeId = downstream.getRepresentative() == null ? null : downstream.getRepresentative().getId();
            Instant originalStart = item.scheduledAt();
            VisitSchedulingRecommendationService.LiveRepairCandidate chosen = chooseRepair(feasible, originalGeId, originalStart);
            if (chosen == null) {
                notifyRepairRequired(downstream, operationId, "No safe itinerary change was available inside the repair horizon.");
                continue;
            }
            boolean material = isMaterialShift(Duration.between(originalStart, chosen.start()),
                    properties.getAutoShiftThresholdMinutes());
            boolean reassigned = !chosen.geId().equals(originalGeId);
            User replacement = downstream.getRepresentative();
            List<Long> profileLocks = reassigned ? List.of(originalGeId, chosen.geId()) : List.of(chosen.geId());
            if (!lockSchedulingProfiles(profileLocks)) {
                notifyRepairRequired(downstream, operationId, "Ground Executive scheduling eligibility changed during repair.");
                continue;
            }
            if (reassigned) {
                var profile = employees.findLockedByUserId(chosen.geId())
                        .filter(value -> "GROUND_BOY".equalsIgnoreCase(value.getRoleType())).orElse(null);
                var schedulingProfile = schedulingProfiles.findLockedByUserId(chosen.geId())
                        .filter(GroundExecutiveSchedulingProfile::isSchedulingActive).orElse(null);
                if (profile == null || schedulingProfile == null
                        || liveRepairCandidates(downstream, List.of(chosen.start()), true, originalGeId).stream()
                            .noneMatch(value -> value.geId().equals(chosen.geId()) && value.start().equals(chosen.start()))) {
                    notifyRepairRequired(downstream, operationId, "The alternate Ground Executive is no longer available.");
                    continue;
                }
                replacement = profile.getUser();
            } else if (liveRepairCandidates(downstream, List.of(chosen.start()), true, originalGeId).stream()
                    .noneMatch(value -> value.geId().equals(chosen.geId()) && value.start().equals(chosen.start()))) {
                notifyRepairRequired(downstream, operationId, "The visit feasibility changed during repair.");
                continue;
            }

            downstream.setRepresentative(replacement);
            downstream.setAssignedAt(reassigned ? now : downstream.getAssignedAt());
            downstream.setScheduledAt(chosen.start());
            downstream.setReservedEndAt(chosen.end());
            downstream.setDurationSnapshotMinutes(chosen.durationMinutes());
            downstream.setZoneId(chosen.zoneId());
            downstream.setStatus(VisitSessionStatus.SCHEDULED);
            downstream.setRepairOperationId(operationId);
            downstream.setExecutionStateChangedAt(now);
            if (material) {
                downstream.setRepairState("PROPOSED");
                downstream.setTenantConfirmationState("PENDING");
                downstream.setTenantConfirmedAt(null);
                downstream.setTenantConfirmedBy(null);
            } else {
                downstream.setRepairState("NONE");
                downstream.setTenantConfirmationState(tenantAcceptanceRequired ? "PENDING" : "CONFIRMED");
                if (tenantAcceptanceRequired) {
                    downstream.setTenantConfirmedAt(null);
                    downstream.setTenantConfirmedBy(null);
                }
            }
            sessions.saveAndFlush(downstream);
            invalidateUnconsumedChallenge(downstream.getId(), now);
            if (material || tenantAcceptanceRequired) {
                audit(downstream.getId(), null, "VISIT_TIME_PROPOSED",
                        material ? "LIVE_REPAIR_MATERIAL_CHANGE" : "LIVE_REPAIR_PENDING_UPDATE",
                        "LIVE_REPAIR_PROPOSED:" + operationId + ":" + downstream.getId());
                enqueueTenant(downstream.getTenant().getId(), "LIVE_REPAIR_PROPOSED:" + operationId + ":" + downstream.getId(),
                        "A revised visit time needs your confirmation", "The current proposed visit is "
                                + formatSessionTime(chosen.start(), downstream)
                                + ". It is not confirmed; review and accept this current option in the app.", "VISIT_TIME_PROPOSED");
            } else {
                String eventKey = (reassigned ? "LIVE_REPAIR_REASSIGNED:" : "LIVE_REPAIR_SHIFTED:")
                        + operationId + ":" + downstream.getId();
                audit(downstream.getId(), null, reassigned ? "GE_REASSIGNED" : "VISIT_TIME_UPDATED",
                        "LIVE_REPAIR_AUTOMATIC", eventKey);
                enqueueTenant(downstream.getTenant().getId(), eventKey,
                        reassigned ? "Ground Executive updated" : "Visit time updated",
                        reassigned ? "Your Ground Executive changed. Your confirmed visit time is unchanged."
                                : "Your visit is now expected at " + formatSessionTime(chosen.start(), downstream) + ".",
                        reassigned ? "GE_REASSIGNED" : "VISIT_TIME_UPDATED");
            }
            if (reassigned) {
                enqueueGroundExecutiveRecipient(downstream.getId(), originalGeId, "LIVE_REPAIR_FORMER_GE:" + operationId + ":" + downstream.getId(),
                        "Visit reassigned", "This visit has been reassigned. Its tenant contact and execution access are no longer available to you.", "GE_REASSIGNED");
                enqueueGroundExecutiveRecipient(downstream.getId(), chosen.geId(), "LIVE_REPAIR_NEW_GE:" + operationId + ":" + downstream.getId(),
                        "Visit assigned", "A visit has been assigned to you at " + formatSessionTime(chosen.start(), downstream) + ". Refresh your assigned visits for details.", "GE_REASSIGNED");
            }
            if (material) enqueueOperations(downstream.getId(), null, operationId,
                    "A material visit-time proposal is awaiting tenant confirmation.");
        }
    }

    private VisitSchedulingRecommendationService.LiveRepairCandidate chooseRepair(
            List<VisitSchedulingRecommendationService.LiveRepairCandidate> candidates, Long currentGe, Instant currentStart) {
        if (currentGe == null || currentStart == null) return null;
        var comparator = java.util.Comparator.comparing(VisitSchedulingRecommendationService.LiveRepairCandidate::start)
                .thenComparing(VisitSchedulingRecommendationService.LiveRepairCandidate::geId);
        var sameTimeAlternate = candidates.stream().filter(c -> c.start().equals(currentStart) && !c.geId().equals(currentGe))
                .min(comparator).orElse(null);
        if (sameTimeAlternate != null) return sameTimeAlternate;
        var smallShift = candidates.stream().filter(c -> c.geId().equals(currentGe)
                        && !c.start().isBefore(currentStart)
                        && !isMaterialShift(Duration.between(currentStart, c.start()), properties.getAutoShiftThresholdMinutes()))
                .min(comparator).orElse(null);
        if (smallShift != null) return smallShift;
        var smallAlternate = candidates.stream().filter(c -> !c.geId().equals(currentGe)
                        && !c.start().isBefore(currentStart)
                        && !isMaterialShift(Duration.between(currentStart, c.start()), properties.getAutoShiftThresholdMinutes()))
                .min(comparator).orElse(null);
        if (smallAlternate != null) return smallAlternate;
        return candidates.stream().filter(c -> c.start().isAfter(currentStart)
                        && isMaterialShift(Duration.between(currentStart, c.start()), properties.getAutoShiftThresholdMinutes()))
                .min(comparator).orElse(null);
    }

    static boolean isMaterialShift(Duration shift, int thresholdMinutes) {
        return shift.compareTo(Duration.ofMinutes(thresholdMinutes)) > 0;
    }

    static boolean challengeStillAvailable(Instant expiresAt, Instant now) {
        return expiresAt != null && expiresAt.isAfter(now);
    }

    private void notifyRepairRequired(VisitSession session, UUID operationId, String context) {
        notifyRepairRequired(session.getId(), session.getTenant().getId(), operationId, context);
    }

    private void notifyRepairRequired(Long sessionId, Long tenantId, UUID operationId, String context) {
        enqueueTenant(tenantId, "VISIT_REPAIR_REQUIRED:" + sessionId + ":" + operationId,
                "Visit time needs review", "Your visit time needs Operations review. We will update you when a safe time is confirmed.", "VISIT_REPAIR_REQUIRED");
        enqueueOperations(sessionId, null, operationId, "Visit Session " + sessionId
                + " needs urgent repair. " + context);
    }

    private List<VisitSchedulingRecommendationService.LiveRepairCandidate> liveRepairCandidates(
            VisitSession session, List<Instant> starts, boolean lockInputs, Long assignedGeId) {
        return recommendations.assessLiveRepair(session, starts, lockInputs, assignedGeId,
                properties.getAlternateGeCandidates());
    }

    private void invalidateUnconsumedChallenge(Long sessionId, Instant now) {
        jdbc.update("update visit_start_challenges set invalidated_at=?,version=version+1 where session_id=? and invalidated_at is null and consumed_at is null",
                java.sql.Timestamp.from(now), sessionId);
    }

    private boolean lockSchedulingProfiles(List<Long> userIds) {
        for (Long userId : userIds.stream().distinct().sorted().toList()) {
            GroundExecutiveSchedulingProfile profile = schedulingProfiles.findLockedByUserId(userId).orElse(null);
            if (profile == null || !profile.isSchedulingActive()) return false;
        }
        return true;
    }

    @Transactional
    public VisitExecutionView finish(Long groundExecutiveId, Long sessionId) {
        authorization.requireGroundExecutive(groundExecutiveId);
        VisitSession session = lockAssignedSession(groundExecutiveId, sessionId);
        if (session.getStatus() == VisitSessionStatus.COMPLETED) return executionView(session);
        if (session.getStatus() != VisitSessionStatus.STARTED) throw new VisitOperationsConflictException("Only an active visit can be finished");
        Instant now = Instant.now();
        session.setStatus(VisitSessionStatus.COMPLETED);
        session.setFinishedAt(now);
        session.setCompletedAt(now);
        session.setExecutionStateChangedAt(now);
        audit(sessionId, groundExecutiveId, "VISIT_FINISHED", null, "FINISH:" + sessionId);
        return executionView(session);
    }

    public VisitExecutionView needMoreTime(Long groundExecutiveId, Long sessionId, GroundVisitMoreTimeCommand command) {
        return executeRepairWithRetry(() -> needMoreTimeAttempt(groundExecutiveId, sessionId, command));
    }

    private VisitExecutionView needMoreTimeAttempt(Long groundExecutiveId, Long sessionId, GroundVisitMoreTimeCommand command) {
        authorization.requireGroundExecutive(groundExecutiveId);
        if (command == null || command.operationId() == null)
            throw new IllegalArgumentException("A stable operation ID is required");
        Integer additionalMinutes = command.additionalMinutes();
        if (additionalMinutes == null || additionalMinutes < 5 || additionalMinutes > 120)
            throw new IllegalArgumentException("Additional time must be between 5 and 120 minutes");
        VisitSession session = lockAssignedSession(groundExecutiveId, sessionId);
        String operationKey = "MORE_TIME:" + sessionId + ":" + command.operationId();
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from visit_execution_events where idempotency_key=?)", Boolean.class, operationKey)))
            return executionView(session);
        if (session.getStatus() != VisitSessionStatus.STARTED) throw new VisitOperationsConflictException("Only an active visit can request more time");
        Instant now = Instant.now();
        Instant newExpectedEnd = session.getExpectedEndAt().isAfter(now)
                ? session.getExpectedEndAt().plus(Duration.ofMinutes(additionalMinutes))
                : now.plus(Duration.ofMinutes(additionalMinutes));
        List<Downstream> downstream = moveOverlappingDownstreamToRepair(
                sessionId, groundExecutiveId, session.getScheduledAt(), newExpectedEnd, command.operationId());
        session.setExpectedEndAt(newExpectedEnd);
        session.setReservedEndAt(newExpectedEnd);
        session.setNeedsMoreTimeAt(now);
        session.setExecutionStateChangedAt(now);
        audit(sessionId, groundExecutiveId, "NEED_MORE_TIME", "PLUS_" + additionalMinutes, operationKey);
        sessions.saveAndFlush(session);
        repairDownstream(downstream, command.operationId(), sessionId, groundExecutiveId, newExpectedEnd);
        return executionView(session);
    }

    @Transactional
    public VisitExecutionView confirmTenant(Long tenantId, Long sessionId, TenantVisitConfirmationCommand command) {
        requireTenant(tenantId);
        if (command == null || command.action() == null || command.operationId() == null
                || command.expectedSessionVersion() == null)
            throw new IllegalArgumentException("Confirmation action, operation ID, and session version are required");
        VisitSession session = sessions.findLockedByIdAndTenantId(sessionId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        String action = command.action().trim().toUpperCase();
        if (!List.of("CONFIRM", "ACCEPT_RESCHEDULE", "REJECT_RESCHEDULE", "DISPUTE_NO_SHOW").contains(action))
            throw new IllegalArgumentException("Unsupported tenant confirmation action");
        String idempotencyKey = "TENANT_CONFIRM:" + command.operationId();
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from visit_execution_events where idempotency_key=?)", Boolean.class, idempotencyKey)))
            return executionView(session);
        if (!session.getVersion().equals(command.expectedSessionVersion()))
            throw new VisitOperationsConflictException("Visit Session changed; refresh before responding");
        Instant now = Instant.now();
        if (command.confirmedEtaAt() != null && (command.confirmedEtaAt().isBefore(now)
                || command.confirmedEtaAt().isAfter(now.plus(Duration.ofMinutes(MAX_ETA_MINUTES)))))
            throw new IllegalArgumentException("Confirmed ETA must be within the next 24 hours");
        boolean proposalRejectedAsStale = false;
        if (action.equals("DISPUTE_NO_SHOW")) {
            if (session.getStatus() != VisitSessionStatus.PROVISIONAL_NO_SHOW || session.getNoShowDisputeUntil() == null
                    || now.isAfter(session.getNoShowDisputeUntil()))
                throw new VisitOperationsConflictException("The no-show dispute window is closed");
            session.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
            session.setRepairState("REQUIRED");
            UUID repairOperationId = UUID.randomUUID();
            session.setRepairOperationId(repairOperationId);
            enqueueOperations(sessionId, tenantId, repairOperationId,
                    "Visit Session " + sessionId + " has a tenant-disputed provisional no-show and needs immediate review.");
            enqueueTenant(tenantId, "NO_SHOW_DISPUTED:" + sessionId + ":" + command.operationId(),
                    "Attendance review opened", "Your attendance dispute is recorded. Operations will review the visit.", "NO_SHOW_DISPUTED");
        } else {
            if (action.equals("CONFIRM") && session.getStatus() != VisitSessionStatus.SCHEDULED)
                throw new VisitOperationsConflictException("Only a scheduled visit can be confirmed");
            if (action.equals("CONFIRM") && "PENDING".equals(session.getTenantConfirmationState()))
                throw new VisitOperationsConflictException("Accept this proposed visit time with ACCEPT_RESCHEDULE");
            if ((action.equals("ACCEPT_RESCHEDULE") || action.equals("REJECT_RESCHEDULE"))
                    && (session.getStatus() != VisitSessionStatus.SCHEDULED
                        || !"PENDING".equals(session.getTenantConfirmationState()) || session.getScheduledAt() == null))
                throw new VisitOperationsConflictException("There is no proposed visit time waiting for your response");
            if (action.equals("ACCEPT_RESCHEDULE") && !proposedScheduleStillFeasible(session, now)) {
                session.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
                session.setRepairState("REQUIRED");
                session.setTenantConfirmationState("PENDING");
                session.setRepairOperationId(command.operationId());
                invalidateUnconsumedChallenge(sessionId, now);
                enqueueOperations(sessionId, tenantId, command.operationId(),
                        "A tenant accepted a proposed visit time that is no longer feasible; prepare a current safe option.");
                enqueueTenant(tenantId, "RESCHEDULE_REVALIDATION_FAILED:" + sessionId + ":" + command.operationId(),
                        "Visit time needs another review", "The proposed time is no longer available. Your visit credit remains held while Operations checks another safe option.",
                        "VISIT_REPAIR_REQUIRED");
                proposalRejectedAsStale = true;
            } else {
                session.setTenantConfirmationState(action.equals("REJECT_RESCHEDULE") ? "REJECTED" : "CONFIRMED");
                session.setTenantConfirmedAt(now);
                session.setTenantConfirmedBy(session.getTenant());
                if (action.equals("ACCEPT_RESCHEDULE")) {
                    session.setRepairState("NONE");
                    session.setRepairOperationId(null);
                }
                if (command.confirmedEtaAt() != null) session.setTenantEtaAt(command.confirmedEtaAt());
                if (action.equals("REJECT_RESCHEDULE")) {
                    session.setStatus(VisitSessionStatus.REPAIR_REQUIRED);
                    session.setRepairState("REQUIRED");
                    UUID repairOperationId = UUID.randomUUID();
                    session.setRepairOperationId(repairOperationId);
                    enqueueOperations(sessionId, tenantId, repairOperationId,
                            "Visit Session " + sessionId + " has a tenant-rejected proposed time and needs a new option.");
                    enqueueTenant(tenantId, "RESCHEDULE_REJECTED:" + sessionId + ":" + command.operationId(),
                            "Another visit time will be reviewed", "You declined the proposed time. Operations will check another safe option.", "RESCHEDULE_REJECTED");
                }
            }
        }
        session.setExecutionStateChangedAt(now);
        audit(sessionId, tenantId, "TENANT_" + action, null,
                idempotencyKey);
        if (!action.equals("DISPUTE_NO_SHOW") && !action.equals("REJECT_RESCHEDULE") && !proposalRejectedAsStale)
            enqueueTenant(tenantId, "TENANT_CONFIRMATION:" + sessionId + ":" + command.operationId(),
                    action.equals("ACCEPT_RESCHEDULE") ? "Visit time confirmed" : "Visit update received",
                    action.equals("ACCEPT_RESCHEDULE") ? "Your new visit time is confirmed for "
                            + formatSessionTime(session.getScheduledAt(), session) + "." : "Your visit update has been recorded.",
                    action.equals("ACCEPT_RESCHEDULE") ? "VISIT_RESCHEDULED" : "TENANT_CONFIRMATION");
        return proposalRejectedAsStale ? executionView(session, "REPAIR_REQUIRED") : executionView(session);
    }

    private boolean proposedScheduleStillFeasible(VisitSession session, Instant now) {
        Long geId = session.getRepresentative() == null ? null : session.getRepresentative().getId();
        if (geId == null || session.getScheduledAt() == null || !session.getScheduledAt().isAfter(now)
                || session.getReservedEndAt() == null || session.getDurationSnapshotMinutes() == null
                || session.getDurationSnapshotMinutes() <= 0 || !lockSchedulingProfiles(List.of(geId))) return false;
        var employee = employees.findLockedByUserId(geId)
                .filter(profile -> "GROUND_BOY".equalsIgnoreCase(profile.getRoleType())).orElse(null);
        if (employee == null) return false;
        try {
            authorization.requireGroundExecutiveTarget(geId);
        } catch (AccessDeniedException unavailable) {
            return false;
        }
        return recommendations.assessLiveRepair(session, List.of(session.getScheduledAt()), true, geId, 0).stream()
                .anyMatch(candidate -> candidate.geId().equals(geId)
                        && candidate.start().equals(session.getScheduledAt())
                        && candidate.end().equals(session.getReservedEndAt())
                        && candidate.durationMinutes() == session.getDurationSnapshotMinutes()
                        && candidate.zoneId().equals(session.getZoneId()));
    }

    @Transactional
    public VisitExecutionView markProvisionalNoShow(Long groundExecutiveId, Long sessionId) {
        authorization.requireGroundExecutive(groundExecutiveId);
        VisitSession session = lockAssignedSession(groundExecutiveId, sessionId);
        requirePreStart(session);
        if ("PENDING".equals(session.getTenantConfirmationState())
                || "REJECTED".equals(session.getTenantConfirmationState())
                || !"NONE".equals(session.getRepairState()))
            throw new VisitOperationsConflictException("An unconfirmed visit time cannot be marked no-show");
        if (session.getStatus() == VisitSessionStatus.PROVISIONAL_NO_SHOW) return executionView(session);
        Instant now = Instant.now();
        if (session.getArrivedAt() == null || session.getScheduledAt() == null
                || now.isBefore(session.getScheduledAt().plus(Duration.ofMinutes(properties.getTenantNoShowGraceMinutes()))))
            throw new VisitOperationsConflictException("Arrival and the tenant grace period are required first");
        java.sql.Timestamp latestSuccessfulContact = jdbc.queryForObject(
                "select max(occurred_at) from visit_execution_events where session_id=? and event_type='CONTACT_ATTEMPT' and metadata->>'outcome' in ('CONNECTED','TENANT_CONFIRMED','TENANT_ACCEPTED_RESCHEDULE')",
                java.sql.Timestamp.class, sessionId);
        Instant evidenceAfter = session.getScheduledAt();
        if (latestSuccessfulContact != null && latestSuccessfulContact.toInstant().isAfter(evidenceAfter))
            evidenceAfter = latestSuccessfulContact.toInstant();
        if (session.getTenantConfirmedAt() != null && session.getTenantConfirmedAt().isAfter(evidenceAfter))
            evidenceAfter = session.getTenantConfirmedAt();
        Integer attempts = jdbc.queryForObject("select count(*) from visit_execution_events where session_id=? and event_type='CONTACT_ATTEMPT' and metadata->>'outcome'='NO_ANSWER' and occurred_at>?",
                Integer.class, sessionId, java.sql.Timestamp.from(evidenceAfter));
        if (attempts == null || attempts < properties.getMinimumContactAttempts())
            throw new VisitOperationsConflictException("Required unanswered tenant contact attempts are not recorded");
        Boolean spacingMet = jdbc.queryForObject("select coalesce(max(occurred_at)-min(occurred_at) >= (? * interval '1 minute'),false) from visit_execution_events where session_id=? and event_type='CONTACT_ATTEMPT' and metadata->>'outcome'='NO_ANSWER' and occurred_at>?",
                Boolean.class, properties.getContactAttemptSpacingMinutes(), sessionId, java.sql.Timestamp.from(evidenceAfter));
        if (!Boolean.TRUE.equals(spacingMet)) throw new VisitOperationsConflictException("Contact attempts must be spaced apart");
        if (!entitlements.reserveForNoShow(session.getTenant().getId(), sessionId,
                now.plus(Duration.ofDays(properties.getReservationHorizonDays()))))
            throw new VisitOperationsConflictException("ENTITLEMENT_UNAVAILABLE: no visit credit can be held for the attendance review");
        session.setStatus(VisitSessionStatus.PROVISIONAL_NO_SHOW);
        session.setProvisionalNoShowAt(now);
        session.setNoShowDisputeUntil(now.plus(Duration.ofHours(properties.getTenantDisputeWindowHours())));
        session.setExecutionStateChangedAt(now);
        audit(sessionId, groundExecutiveId, "PROVISIONAL_NO_SHOW", "CONTACT_EVIDENCE_RECORDED", "PROVISIONAL_NO_SHOW:" + sessionId);
        enqueueTenant(session.getTenant().getId(), "PROVISIONAL_NO_SHOW:" + sessionId,
                "Visit attendance needs review", "Your visit was marked provisional after the Ground Executive could not reach you. You can dispute this in the app.", "PROVISIONAL_NO_SHOW");
        return executionView(session);
    }

    private List<Downstream> moveOverlappingDownstreamToRepair(Long currentSessionId, Long groundExecutiveId,
            Instant start, Instant expectedEnd, UUID operationId) {
        List<Downstream> conflicts = jdbc.query("select id,tenant_id,version,scheduled_at,reserved_end_at from visit_sessions where representative_user_id=? and id<>? and status='SCHEDULED' and scheduled_at < ? and reserved_end_at > ? order by id",
                (rs, row) -> new Downstream(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()), groundExecutiveId,
                currentSessionId, java.sql.Timestamp.from(expectedEnd), java.sql.Timestamp.from(start));
        Instant changedAt = Instant.now();
        List<Downstream> scheduledOrder = conflicts.stream()
                .sorted(java.util.Comparator.comparing(Downstream::scheduledAt).thenComparing(Downstream::id)).toList();
        int remaining = Math.max(0, properties.getMaximumDownstreamSessions() - scheduledOrder.size());
        Instant repairCutoff = changedAt.plus(Duration.ofHours(properties.getRepairHorizonHours()));
        List<Downstream> following = remaining == 0 ? List.of() : jdbc.query(
                "select id,tenant_id,version,scheduled_at,reserved_end_at from visit_sessions where representative_user_id=? and id<>? and status='SCHEDULED' and scheduled_at>=? and scheduled_at<=? order by scheduled_at,id limit ?",
                (rs, row) -> new Downstream(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()), groundExecutiveId,
                currentSessionId, java.sql.Timestamp.from(expectedEnd), java.sql.Timestamp.from(repairCutoff), remaining);
        List<Downstream> lockOrder = java.util.stream.Stream.concat(conflicts.stream(), following.stream())
                .collect(java.util.stream.Collectors.toMap(Downstream::id, item -> item, (left, right) -> left))
                .values().stream().sorted(java.util.Comparator.comparing(Downstream::id)).toList();
        for (Downstream row : lockOrder)
            jdbc.query("select id from visit_sessions where id=? for update", (rs, index) -> rs.getLong(1), row.id());

        // The initial ordered reads are the repair plan. Validate them again only
        // after every affected row is locked, before any state transition is applied.
        for (Downstream planned : lockOrder) {
            List<Downstream> fresh = jdbc.query("select id,tenant_id,version,scheduled_at,reserved_end_at "
                            + "from visit_sessions where id=? and representative_user_id=? and status='SCHEDULED' for update",
                    (rs, row) -> new Downstream(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                            rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()), planned.id(), groundExecutiveId);
            if (fresh.isEmpty() || !sameRepairSnapshot(planned, fresh.get(0)))
                throw new StaleRepairPlanException("A downstream Operations edit changed repair plan for session "
                        + planned.id() + " from version " + planned.version() + " to "
                        + fresh.stream().findFirst().map(Downstream::version).orElse(null));
        }

        java.util.Set<Long> boundedRepairIds = java.util.stream.Stream.concat(scheduledOrder.stream(),
                        following.stream().sorted(java.util.Comparator.comparing(Downstream::scheduledAt)
                                .thenComparing(Downstream::id)))
                .limit(properties.getMaximumDownstreamSessions()).map(Downstream::id)
                .collect(java.util.stream.Collectors.toSet());
        List<Downstream> transitioned = new java.util.ArrayList<>();
        for (Downstream downstream : conflicts) {
            Downstream latest = markConflictingReservationForRepair(downstream.id(), groundExecutiveId,
                    currentSessionId, start, expectedEnd, operationId, changedAt);
            if (latest == null) continue;
            transitioned.add(latest);
            if (!boundedRepairIds.contains(latest.id()))
                notifyRepairRequired(latest.id(), latest.tenantId(), operationId,
                        "The automatic repair bound was reached; Operations must review this conflicting visit.");
        }
        List<Downstream> boundedFollowing = new java.util.ArrayList<>();
        for (Downstream row : following) {
            if (!boundedRepairIds.contains(row.id())) continue;
            List<Downstream> latestRows = jdbc.query("select id,tenant_id,version,scheduled_at,reserved_end_at from visit_sessions where id=? and representative_user_id=? and status='SCHEDULED' for update",
                    (rs, index) -> new Downstream(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                            rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()), row.id(), groundExecutiveId);
            if (!latestRows.isEmpty()) boundedFollowing.add(latestRows.get(0));
        }
        return java.util.stream.Stream.concat(transitioned.stream()
                        .filter(item -> boundedRepairIds.contains(item.id())), boundedFollowing.stream())
                .sorted(java.util.Comparator.comparing(Downstream::scheduledAt).thenComparing(Downstream::id)).toList();
    }

    private Downstream markConflictingReservationForRepair(Long downstreamId, Long groundExecutiveId,
            Long currentSessionId, Instant start, Instant expectedEnd, UUID operationId, Instant changedAt) {
        for (int attempt = 0; attempt <= properties.getRepairRetries(); attempt++) {
            List<Downstream> latestRows = jdbc.query("select id,tenant_id,version,scheduled_at,reserved_end_at from visit_sessions where id=? and representative_user_id=? and id<>? and status='SCHEDULED' for update",
                    (rs, row) -> new Downstream(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                            rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()), downstreamId,
                    groundExecutiveId, currentSessionId);
            if (latestRows.isEmpty()) return null;
            Downstream latest = latestRows.get(0);
            if (!latest.scheduledAt().isBefore(expectedEnd) || !latest.reservedEndAt().isAfter(start)) return null;
            int changed = jdbc.update("update visit_sessions set status='REPAIR_REQUIRED',repair_state='REQUIRED',repair_operation_id=?,execution_state_changed_at=?,version=version+1,updated_at=? where id=? and status='SCHEDULED' and version=?",
                    operationId, java.sql.Timestamp.from(changedAt), java.sql.Timestamp.from(changedAt), latest.id(), latest.version());
            if (changed == 1) {
                audit(latest.id(), groundExecutiveId, "REPAIR_REQUIRED", "OVERLAPPING_EXECUTION",
                        "REPAIR:" + operationId + ":" + latest.id());
                return new Downstream(latest.id(), latest.tenantId(), latest.version() + 1,
                        latest.scheduledAt(), latest.reservedEndAt());
            }
        }
        throw new VisitOperationsConflictException("A downstream visit remained stale after bounded repair retries; retry the start");
    }

    private boolean sameRepairSnapshot(Downstream planned, Downstream fresh) {
        return planned.version().equals(fresh.version())
                && planned.scheduledAt().equals(fresh.scheduledAt())
                && planned.reservedEndAt().equals(fresh.reservedEndAt());
    }

    private boolean hasUnplannedOverlap(Long sessionId, Long geId, Instant start, Instant end) {
        Boolean overlapping = jdbc.queryForObject("select exists(select 1 from visit_sessions where representative_user_id=? and id<>? and status='SCHEDULED' and scheduled_at<? and reserved_end_at>?)",
                Boolean.class, geId, sessionId, java.sql.Timestamp.from(end), java.sql.Timestamp.from(start));
        return Boolean.TRUE.equals(overlapping);
    }

    private <T> T executeRepairWithRetry(Supplier<T> operation) {
        int attempts = Math.min(properties.getRepairRetries(), MAX_EXECUTION_REPAIR_RETRIES) + 1;
        boolean lockContention = false;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                return repairTransaction.execute(status -> operation.get());
            } catch (StaleRepairPlanException stale) {
                log.warn("Rolling back stale visit repair attempt {} of {}: {}", attempt + 1, attempts, stale.getMessage());
            } catch (DataIntegrityViolationException conflict) {
                if (!isReservationOverlap(conflict)) throw conflict;
                log.warn("Rolling back concurrent GE reservation overlap attempt {} of {}", attempt + 1, attempts);
            } catch (RuntimeException failure) {
                if (!isRetryableLockFailure(failure)) throw failure;
                lockContention = true;
                log.warn("Rolling back transient visit lock failure on attempt {} of {}", attempt + 1, attempts);
            }
        }
        if (lockContention)
            throw new VisitOperationsConflictException(
                    "The visit could not be safely updated while another execution was in progress. Retry the operation.");
        throw new VisitOperationsConflictException("Downstream visit state kept changing during repair; Operations state was preserved. Retry the operation.");
    }

    private boolean isRetryableLockFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PessimisticLockingFailureException) return true;
            if (cause instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if ("40P01".equals(state) || "55P03".equals(state)) return true;
            }
        }
        return false;
    }

    private boolean isReservationOverlap(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "23P01".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private VisitSession lockAssignedSession(Long actorId, Long sessionId) {
        if (sessionId == null || sessionId <= 0) throw new IllegalArgumentException("Visit Session ID must be positive");
        VisitSession session = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("Visit Session not found"));
        if (session.getRepresentative() == null || !actorId.equals(session.getRepresentative().getId()))
            throw new AccessDeniedException("This Ground Executive is not assigned to the Visit Session");
        return session;
    }

    private Challenge challenge(Long sessionId, boolean createIfMissing) {
        List<Challenge> result = jdbc.query("select id,generation,key_id,digest,expires_at,next_issue_allowed_at,consumed_at,invalidated_at,failed_attempts,locked_until,ground_executive_user_id from visit_start_challenges where session_id=? for update",
                (rs, row) -> new Challenge(rs.getLong("id"), rs.getInt("generation"), rs.getString("key_id"), rs.getBytes("digest"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("next_issue_allowed_at").toInstant(),
                        rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                        rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant(),
                        rs.getInt("failed_attempts"), rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant(),
                        rs.getLong("ground_executive_user_id")), sessionId);
        return result.isEmpty() ? null : result.get(0);
    }

    private void requirePreStart(VisitSession session) {
        if (session.getStatus() != VisitSessionStatus.SCHEDULED && session.getStatus() != VisitSessionStatus.PROVISIONAL_NO_SHOW)
            throw new VisitOperationsConflictException("Visit Session is not ready to start");
        if (session.getStartedAt() != null || session.getCompletedAt() != null)
            throw new VisitOperationsConflictException("Visit Session is no longer in pre-start state");
    }

    private void requireTenant(Long tenantId) {
        User user = users.findById(tenantId).orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        if (user.getRole() != Role.ROLE_TENANT) throw new AccessDeniedException("Tenant capability required");
    }

    private VisitExecutionView executionView(VisitSession session) {
        Instant now = Instant.now();
        return executionView(session, "OK");
    }

    private VisitExecutionView executionView(VisitSession session, String resultCode) {
        Instant now = Instant.now();
        return new VisitExecutionView(session.getId(), session.getStatus(), session.getVersion(), session.getScheduledAt(), session.getZoneId(),
                session.getArrivedAt(), session.getStartedAt(), session.getExpectedEndAt(), session.getFinishedAt(),
                session.getTenantEtaAt(), session.getTenantConfirmationState(), session.getRepairState(),
                session.getStatus() == VisitSessionStatus.STARTED && session.getExpectedEndAt() != null && now.isAfter(session.getExpectedEndAt()), resultCode);
    }

    private void audit(Long sessionId, Long actorId, String type, String reason, String key) {
        jdbc.update("insert into visit_execution_events(session_id,actor_user_id,event_type,reason_code,idempotency_key) values (?,?,?,?,?) on conflict(idempotency_key) do nothing",
                sessionId, actorId, type, reason, key);
    }

    private void enqueueTenant(Long recipient, String key, String title, String message, String type) {
        jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message,authorization_class,operational_session_id) values (?,?,'TENANT',?,?,?,'RECIPIENT',null) on conflict(event_key) do nothing",
                key, recipient, type, title, message);
    }

    private void enqueueOperationsRecipient(Long sessionId, Long recipient, String key,
            String title, String message, String type) {
        enqueueOperationalSessionNotification(sessionId, recipient, TargetRole.EMPLOYEE, key, title, message, type);
    }

    private void enqueueGroundExecutiveRecipient(Long sessionId, Long recipient, String key,
            String title, String message, String type) {
        enqueueOperationalSessionNotification(sessionId, recipient, TargetRole.GROUND_BOY, key, title, message, type);
    }

    private void enqueueOperationalSessionNotification(Long sessionId, Long recipient, TargetRole recipientRole,
            String key, String title, String message, String type) {
        jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message,authorization_class,operational_session_id) values (?,?,?,?,?,?,'OPERATIONS_SESSION',?) on conflict(event_key) do nothing",
                key, recipient, recipientRole.name(), type, title, message, sessionId);
    }

    private void enqueueOperations(Long sessionId, Long actorId, UUID operationId, String message) {
        List<Long> operationsUsers = jdbc.query("select u.id from users u left join employee_profiles ep on ep.user_id=u.id where u.role='ROLE_ADMIN' or upper(coalesce(ep.role_type,''))='WFH_ADMIN' order by u.id limit 100",
                (rs, row) -> rs.getLong(1));
        for (Long operationsUser : operationsUsers) {
            enqueueOperationsRecipient(sessionId, operationsUser, "ASSISTED_RESCHEDULE:" + sessionId + ":" + operationId + ":" + operationsUser,
                    "Tenant-assisted reschedule", message, "VISIT_RESCHEDULE_REVIEW");
        }
        audit(sessionId, actorId, "OPERATIONS_REPAIR_QUEUED", "REVIEW_REQUIRED",
                "OPERATIONS_REPAIR_QUEUE:" + sessionId + ":" + operationId);
    }

    private String boundedReason(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim().toUpperCase();
        if (!List.of("TENANT_LATE", "PROPERTY_ACCESS_DELAY", "GE_DELAY", "OTHER_APPROVED").contains(clean))
            throw new IllegalArgumentException("Unsupported reason code");
        return clean;
    }

    private String formatSessionTime(Instant value, VisitSession session) {
        if (value == null) return "the current visit time";
        java.time.ZoneId zone = java.time.ZoneId.of(session.getZoneId());
        return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone).format(value) + " (" + zone + ")";
    }

    private void requireOtpKey() {
        if (properties.getOtp().secretForKey(properties.getOtp().getKeyId()).getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("Visit start-code service is not configured");
    }

    private record Challenge(Long id, int generation, String keyId, byte[] digest, Instant expiresAt,
            Instant nextIssueAllowedAt, Instant consumedAt, Instant invalidatedAt, int failedAttempts,
            Instant lockedUntil, Long groundExecutiveId) {}
    private record Downstream(Long id, Long tenantId, Long version, Instant scheduledAt, Instant reservedEndAt) {}

    private static final class StaleRepairPlanException extends RuntimeException {
        private StaleRepairPlanException(String message) { super(message); }
    }
}
