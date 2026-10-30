package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmationConfirmCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmationExchangeCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneConfirmationView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneIssueCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneResendCommand;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.PhoneVerificationView;
import com.indore.pathome.spaces.dto.crm.LeadAssistanceDtos.AssistanceCommandStatusView;
import com.indore.pathome.spaces.entity.LeadActivity;
import com.indore.pathome.spaces.entity.LeadAssistanceCommandReceipt;
import com.indore.pathome.spaces.entity.LeadPhoneChallenge;
import com.indore.pathome.spaces.entity.LeadPhoneChallengeState;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryOutbox;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import com.indore.pathome.spaces.entity.LeadPhoneEntryMethod;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadSourceEvent;
import com.indore.pathome.spaces.entity.LeadSourceEventState;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.exception.LeadPhoneVerificationRejectedException;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.LeadActivityRepository;
import com.indore.pathome.spaces.repository.LeadAssistanceCommandReceiptRepository;
import com.indore.pathome.spaces.repository.LeadPhoneChallengeRepository;
import com.indore.pathome.spaces.repository.LeadPhoneDeliveryOutboxRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class LeadPhoneVerificationService {
    static final String PURPOSE = "ENQUIRY_PHONE_POSSESSION";
    private static final String ISSUE_ACTION = "LEAD_PHONE_ISSUE";
    private static final String RESEND_ACTION = "LEAD_PHONE_RESEND";
    private static final String CONFIRM_ACTION = "LEAD_PHONE_CONFIRM";

    private final LeadRoutingQueueRepository leads;
    private final LeadSourceEventRepository sourceEvents;
    private final LeadPhoneChallengeRepository challenges;
    private final LeadPhoneDeliveryOutboxRepository outbox;
    private final LeadActivityRepository activities;
    private final LeadAssistanceCommandReceiptRepository receipts;
    private final LeadAccessService leadAccess;
    private final StaffAccessService staffAccess;
    private final OperationalSecurityGuards guards;
    private final TransactionalCommandBounds commandBounds;
    private final DatabaseClock databaseClock;
    private final LeadDataProtection protection;
    private final LeadAssistanceSecretService secrets;
    private final LeadPhoneDeliveryProvider provider;
    private final OperationalAuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String configuredOrigin;

    public LeadPhoneVerificationService(
            LeadRoutingQueueRepository leads,
            LeadSourceEventRepository sourceEvents,
            LeadPhoneChallengeRepository challenges,
            LeadPhoneDeliveryOutboxRepository outbox,
            LeadActivityRepository activities,
            LeadAssistanceCommandReceiptRepository receipts,
            LeadAccessService leadAccess,
            StaffAccessService staffAccess,
            OperationalSecurityGuards guards,
            TransactionalCommandBounds commandBounds,
            DatabaseClock databaseClock,
            LeadDataProtection protection,
            LeadAssistanceSecretService secrets,
            LeadPhoneDeliveryProvider provider,
            OperationalAuditService audit,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${pathome.crm.public-origin:}") String configuredOrigin) {
        this.leads = leads;
        this.sourceEvents = sourceEvents;
        this.challenges = challenges;
        this.outbox = outbox;
        this.activities = activities;
        this.receipts = receipts;
        this.leadAccess = leadAccess;
        this.staffAccess = staffAccess;
        this.guards = guards;
        this.commandBounds = commandBounds;
        this.databaseClock = databaseClock;
        this.protection = protection;
        this.secrets = secrets;
        this.provider = provider;
        this.audit = audit;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.configuredOrigin = configuredOrigin;
    }

    @Transactional
    public PhoneVerificationView issue(Long actorId, Long leadId, String operationKey,
                                       PhoneIssueCommand command) {
        validateIssue(command);
        UUID operationId = operationId(operationKey);
        String payloadHash = payloadHash(leadId, command);
        lockOperation(actorId, ISSUE_ACTION, operationId);
        LeadPhoneChallenge replay = requireIssueReceipt(
                actorId, leadId, operationId, payloadHash);
        if (replay != null) return view(replay);

        commandBounds.apply();
        LeadRoutingQueue lead = lockLead(leadId);
        requireCurrentContactOwner(actorId, lead);
        LeadSourceEvent source = lockSource(command.sourceEvent().id());
        requireProcessedSource(lead, source);
        requireCurrentPhoneRevision(lead, command.expectedContactPhoneRevision());
        requireWorkingLead(lead);
        requireVersion(lead, command.expectedLeadVersion());
        acquireSecurityGuards(actorId, lead);
        requireCurrentContactOwner(actorId, lead);

        String numberFingerprint = numberFingerprint(lead);
        acquireWindowLocks(actorId, numberFingerprint);
        Instant now = databaseClock.now();
        enforceSendLimits(actorId, numberFingerprint, now);
        int generation = nextGeneration(lead.getId(), source.getId(), lead.getContactPhoneRevision());
        for (LeadPhoneChallenge previous : challenges.findGenerations(lead.getId(), source.getId(),
                PURPOSE, lead.getContactPhoneRevision())) {
            if (previous.getState() == LeadPhoneChallengeState.PENDING) {
                previous.cancel(now);
                challenges.save(previous);
                purgeDeliverySecret(previous.getId(), now);
            }
        }
        challenges.flush();

        DeliveryMaterial material = newDeliveryMaterial(
                lead, source, numberFingerprint, generation, now);
        LeadPhoneChallenge challenge = material.challenge();
        challenge = challenges.saveAndFlush(challenge);
        User actor = staffAccess.requireAttributableUser(actorId);
        if (provider.enabled()) {
            outbox.saveAndFlush(new LeadPhoneDeliveryOutbox(material.outboxId(), challenge,
                    numberFingerprint, material.ciphertext(), material.iv(), material.keyId(),
                    provider.name(), now));
        }
        activities.save(new LeadActivity(lead, actor, "LEAD_PHONE_CHALLENGE_ISSUED", source,
                now, null));
        audit.recordUserEvent(actor, "LEAD_PHONE_CHALLENGE_ISSUED", "LEAD", lead.getId(),
                "ENQUIRY_PHONE_PROOF", lead.getSupportedCity(), lead.getOperatingTeam(),
                Map.of("previousVersion", command.expectedLeadVersion(),
                        "resultingVersion", lead.getVersion() == null ? 0L : lead.getVersion()));
        receipts.saveAndFlush(new LeadAssistanceCommandReceipt(actor, operationId, ISSUE_ACTION,
                payloadHash, challenge.getId().toString(), now));
        return view(challenge);
    }

    @Transactional(readOnly = true)
    public PhoneVerificationView latest(Long actorId, Long leadId) {
        LeadRoutingQueue lead = leads.findById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
        requireCurrentContactOwner(actorId, lead);
        LeadPhoneChallenge latest = challenges.findFirstByLead_IdOrderByCreatedAtDescIdDesc(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Phone verification not found"));
        return view(latest);
    }

    @Transactional(readOnly = true)
    public PhoneVerificationView get(Long actorId, Long leadId, UUID challengeId) {
        LeadPhoneChallenge challenge = challenges.findById(challengeId)
                .orElseThrow(() -> new EntityNotFoundException("Phone verification not found"));
        if (challenge.getLead() == null || !leadId.equals(challenge.getLead().getId())) {
            throw new EntityNotFoundException("Phone verification not found");
        }
        requireCurrentContactOwner(actorId, challenge.getLead());
        return view(challenge);
    }

    @Transactional
    public PhoneVerificationView resend(Long actorId, Long leadId, UUID challengeId,
                                        String operationKey, PhoneResendCommand command) {
        validateResend(command);
        UUID operationId = operationId(operationKey);
        String payloadHash = payloadHash(Map.of("leadId", leadId, "challengeId", challengeId,
                "command", command));
        lockOperation(actorId, RESEND_ACTION, operationId);
        LeadPhoneChallenge replay = requireReceipt(actorId, leadId, operationId,
                RESEND_ACTION, payloadHash);
        if (replay != null) return view(replay);

        commandBounds.apply();
        LeadRoutingQueue lead = lockLead(leadId);
        requireCurrentContactOwner(actorId, lead);
        LeadPhoneChallenge seed = challenges.findById(challengeId)
                .orElseThrow(LeadPhoneVerificationService::hidden);
        if (!seed.getLead().getId().equals(leadId)) throw hidden();
        LeadSourceEvent source = lockSource(seed.getSourceEvent().getId());
        LeadPhoneChallenge previous = challenges.findLockedById(challengeId).orElseThrow(
                LeadPhoneVerificationService::hidden);
        requireCurrentPhoneRevision(lead, previous.getContactPhoneRevision());
        requireWorkingLead(lead);
        requireVersion(lead, command.expectedLeadVersion());
        requireChallengeVersion(previous, command.expectedChallengeVersion());
        acquireSecurityGuards(actorId, lead);
        requireCurrentContactOwner(actorId, lead);

        String fingerprint = numberFingerprint(lead);
        acquireWindowLocks(actorId, fingerprint);
        Instant now = databaseClock.now();
        if (previous.getState() != LeadPhoneChallengeState.PENDING) {
            throw new LeadPhoneVerificationRejectedException(HttpStatus.CONFLICT,
                    "This enquiry code can no longer be resent.");
        }
        if (!previous.getGeneration().equals(command.expectedGeneration())) {
            throw new VisitOperationsConflictException(
                    "The enquiry challenge changed while it was being reviewed.");
        }
        if (now.isBefore(previous.getCreatedAt().plus(
                LeadPhoneVerificationPolicy.RESEND_COOLDOWN))) {
            long retryAfter = Math.max(1, Duration.between(now,
                    previous.getCreatedAt().plus(
                            LeadPhoneVerificationPolicy.RESEND_COOLDOWN)).toSeconds());
            throw new LeadPhoneVerificationRejectedException(HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before requesting another enquiry code.", retryAfter);
        }

        enforceSendLimits(actorId, fingerprint, now);
        previous.cancel(now);
        challenges.saveAndFlush(previous);
        purgeDeliverySecret(previous.getId(), now);
        int generation = nextGeneration(lead.getId(), source.getId(), lead.getContactPhoneRevision());
        DeliveryMaterial material = newDeliveryMaterial(lead, source, fingerprint, generation, now);
        LeadPhoneChallenge replacement = challenges.saveAndFlush(material.challenge());
        if (provider.enabled()) {
            outbox.saveAndFlush(new LeadPhoneDeliveryOutbox(material.outboxId(), replacement,
                    fingerprint, material.ciphertext(), material.iv(), material.keyId(),
                    provider.name(), now));
        }
        User actor = staffAccess.requireAttributableUser(actorId);
        activities.save(new LeadActivity(lead, actor, "LEAD_PHONE_CHALLENGE_RESENT", source,
                now, null));
        audit.recordUserEvent(actor, "LEAD_PHONE_CHALLENGE_RESENT", "LEAD", lead.getId(),
                "ENQUIRY_PHONE_PROOF", lead.getSupportedCity(), lead.getOperatingTeam(), Map.of());
        receipts.saveAndFlush(new LeadAssistanceCommandReceipt(actor, operationId, RESEND_ACTION,
                payloadHash, replacement.getId().toString(), now));
        return view(replacement);
    }

    @Transactional(noRollbackFor = LeadPhoneVerificationRejectedException.class)
    public PhoneVerificationView confirmFromOperator(Long actorId, Long leadId, UUID challengeId,
                                                     String operationKey, PhoneConfirmCommand command) {
        validateConfirm(command);
        UUID operationId = operationId(operationKey);
        String payloadHash = payloadHash(Map.of("leadId", leadId, "challengeId", challengeId,
                "command", command));
        lockOperation(actorId, CONFIRM_ACTION, operationId);
        LeadPhoneChallenge replay = requireReceipt(actorId, leadId, operationId,
                CONFIRM_ACTION, payloadHash);
        if (replay != null) return view(replay);

        commandBounds.apply();
        LeadRoutingQueue lead = lockLead(leadId);
        requireCurrentContactOwner(actorId, lead);
        LeadPhoneChallenge seed = challenges.findById(challengeId)
                .orElseThrow(LeadPhoneVerificationService::hidden);
        if (!seed.getLead().getId().equals(leadId)) throw hidden();
        LeadSourceEvent source = lockSource(seed.getSourceEvent().getId());
        LeadPhoneChallenge challenge = challenges.findLockedById(challengeId)
                .orElseThrow(LeadPhoneVerificationService::hidden);
        requireProcessedSource(lead, source);
        requireCurrentPhoneRevision(lead, challenge.getContactPhoneRevision());
        requireWorkingLead(lead);
        requireVersion(lead, command.expectedLeadVersion());
        requireChallengeVersion(challenge, command.expectedChallengeVersion());
        acquireSecurityGuardsFor(List.of(actorId, challenge.getCreatedBy().getId()), lead);
        requireCurrentContactOwner(actorId, lead);
        User actor = staffAccess.requireAttributableUser(actorId);
        Instant now = databaseClock.now();
        requireCurrentIssuingAuthority(challenge, lead, source, now);
        verifyChallenge(challenge, lead, command.code(), LeadPhoneEntryMethod.OE_ENTERED,
                now, actor, source);
        receipts.saveAndFlush(new LeadAssistanceCommandReceipt(actor, operationId, CONFIRM_ACTION,
                payloadHash, challenge.getId().toString(), now));
        return view(challenge);
    }

    @Transactional(readOnly = true)
    public PhoneConfirmationView exchange(PhoneConfirmationExchangeCommand command) {
        if (command == null) throw new IllegalArgumentException("Confirmation token is required");
        LeadPhoneChallenge challenge = challengeForToken(command.token());
        Instant now = databaseClock.now();
        return confirmationView(challenge, now);
    }

    @Transactional(noRollbackFor = LeadPhoneVerificationRejectedException.class)
    public PhoneConfirmationView confirmCustomer(PhoneConfirmationConfirmCommand command) {
        if (command == null) throw new IllegalArgumentException("Confirmation token and code are required");
        LeadPhoneChallenge seed = challengeForToken(command.token());
        LeadRoutingQueue lead = lockLead(seed.getLead().getId());
        LeadSourceEvent source = lockSource(seed.getSourceEvent().getId());
        LeadPhoneChallenge challenge = challenges.findLockedById(seed.getId())
                .orElseThrow(LeadPhoneVerificationService::hidden);
        requireProcessedSource(lead, source);
        requireWorkingLead(lead);
        acquireSecurityGuardsFor(List.of(challenge.getCreatedBy().getId()), lead);
        Instant now = databaseClock.now();
        requireProcessedSource(lead, source);
        requireWorkingLead(lead);
        requireCurrentIssuingAuthority(challenge, lead, source, now);
        verifyChallenge(challenge, lead, command.code(), LeadPhoneEntryMethod.CUSTOMER_FORM,
                now, null, source);
        return confirmationView(challenge, now);
    }

    /**
     * Current proof is purpose-, Lead-, source-, number-revision-, number- and time-bound.
     */
    @Transactional(readOnly = true)
    public boolean hasCurrentProof(LeadRoutingQueue lead, UUID sourceEventId, Instant now) {
        if (lead == null || lead.getContactPhoneNormalized() == null || sourceEventId == null) {
            return false;
        }
        String fingerprint = numberFingerprint(lead);
        return !challenges.findCurrentProofs(lead.getId(), sourceEventId, PURPOSE,
                lead.getContactPhoneRevision(), fingerprint,
                now.minus(LeadPhoneVerificationPolicy.PROOF_LIFETIME)).isEmpty();
    }

    @Transactional(readOnly = true)
    public AssistanceCommandStatusView commandStatus(Long actorId, Long leadId,
                                                      UUID operationId, String action) {
        if (action == null || (!ISSUE_ACTION.equals(action)
                && !RESEND_ACTION.equals(action) && !CONFIRM_ACTION.equals(action))) {
            throw new IllegalArgumentException("A supported phone-verification action is required");
        }
        LeadAssistanceCommandReceipt receipt = receipts
                .findByActor_IdAndOperationIdAndAction(actorId, operationId, action)
                .orElse(null);
        if (receipt == null) {
            return new AssistanceCommandStatusView("NOT_RECORDED", action, null, null);
        }
        LeadPhoneChallenge challenge = storedChallenge(receipt);
        if (!leadId.equals(challenge.getLead().getId())) throw hidden();
        requireCurrentContactOwner(actorId, challenge.getLead());
        return new AssistanceCommandStatusView("RECORDED", action,
                receipt.getResultReference(), receipt.getCompletedAt());
    }

    private void verifyChallenge(LeadPhoneChallenge challenge, LeadRoutingQueue lead,
                                 String submittedCode, LeadPhoneEntryMethod method, Instant now,
                                 User operator, LeadSourceEvent source) {
        if (challenge.getState() == LeadPhoneChallengeState.VERIFIED) {
            purgeDeliverySecret(challenge.getId(), now);
            if (challenge.getEntryMethod() == method) return;
            throw new VisitOperationsConflictException(
                    "This enquiry code was already confirmed by another method.");
        }
        if (challenge.getState() == LeadPhoneChallengeState.EXHAUSTED) {
            purgeDeliverySecret(challenge.getId(), now);
            throw new LeadPhoneVerificationRejectedException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many incorrect attempts. Ask the customer to request a new code.");
        }
        if (challenge.getState() != LeadPhoneChallengeState.PENDING
                || now.isAfter(challenge.getExpiresAt())) {
            if (challenge.getState() == LeadPhoneChallengeState.PENDING) {
                challenge.expire(now);
                challenges.saveAndFlush(challenge);
                purgeDeliverySecret(challenge.getId(), now);
            }
            throw new LeadPhoneVerificationRejectedException(HttpStatus.GONE,
                    "This enquiry code has expired. Request a new code.");
        }
        if (!Objects.equals(challenge.getContactPhoneRevision(), lead.getContactPhoneRevision())
                || !Objects.equals(challenge.getNumberFingerprint(), numberFingerprint(lead))) {
            challenge.cancel(now);
            challenges.saveAndFlush(challenge);
            purgeDeliverySecret(challenge.getId(), now);
            throw new LeadPhoneVerificationRejectedException(HttpStatus.CONFLICT,
                    "The enquiry phone number changed. Request a new code.");
        }
        if (!secrets.codeMatches(challenge.getVerificationDigest(), challenge.getContextDigest(),
                submittedCode)) {
            challenge.recordFailedAttempt();
            challenges.saveAndFlush(challenge);
            if (challenge.getState() == LeadPhoneChallengeState.EXHAUSTED) {
                purgeDeliverySecret(challenge.getId(), now);
                throw new LeadPhoneVerificationRejectedException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many incorrect attempts. Request a new code.");
            }
            int remaining = LeadPhoneChallenge.MAX_FAILED_ATTEMPTS
                    - challenge.getFailedAttempts();
            throw new LeadPhoneVerificationRejectedException(HttpStatus.BAD_REQUEST,
                    "The enquiry code is incorrect. " + remaining + " attempts remain.");
        }
        challenge.verify(method, now);
        challenges.saveAndFlush(challenge);
        purgeDeliverySecret(challenge.getId(), now);
        activities.save(new LeadActivity(lead, operator,
                method == LeadPhoneEntryMethod.CUSTOMER_FORM
                        ? "LEAD_PHONE_VERIFIED_CUSTOMER_FORM"
                        : "LEAD_PHONE_VERIFIED_OE_ENTERED",
                source, now, null));
        if (operator == null) {
            audit.recordSystemEvent("LEAD_PHONE_VERIFIED", "LEAD", lead.getId(),
                    "ENQUIRY_PHONE_POSSESSION", lead.getSupportedCity(), lead.getOperatingTeam(),
                    Map.of());
        } else {
            audit.recordUserEvent(operator, "LEAD_PHONE_VERIFIED", "LEAD", lead.getId(),
                    "ENQUIRY_PHONE_POSSESSION", lead.getSupportedCity(),
                    lead.getOperatingTeam(), Map.of());
        }
    }

    private DeliveryMaterial newDeliveryMaterial(LeadRoutingQueue lead, LeadSourceEvent source,
                                                 String fingerprint, int generation, Instant now) {
        String code = secrets.sixDigitCode();
        String token = secrets.opaqueToken();
        String contextDigest = secrets.phoneContextDigest(lead.getId(), source.getId(), PURPOSE,
                fingerprint, lead.getContactPhoneRevision(), generation);
        String verificationDigest = secrets.phoneVerificationDigest(contextDigest, code);
        String tokenHash = secrets.phoneConfirmationTokenHash(token);
        LeadPhoneDeliveryState initialDelivery = provider.enabled()
                ? LeadPhoneDeliveryState.QUEUED : LeadPhoneDeliveryState.SUPPRESSED;
        LeadPhoneChallenge challenge = new LeadPhoneChallenge(UUID.randomUUID(), lead, source,
                PURPOSE, fingerprint, lead.getContactPhoneRevision(),
                LeadDataProtection.mask(lead.getContactPhoneNormalized()), generation,
                contextDigest, verificationDigest, tokenHash, initialDelivery, provider.name(),
                lead.getOwner(), now, now.plus(
                        LeadPhoneVerificationPolicy.CHALLENGE_LIFETIME));
        UUID outboxId = UUID.randomUUID();
        if (!provider.enabled()) {
            return new DeliveryMaterial(challenge, outboxId, null, null, null);
        }
        String confirmationUrl = publicOrigin() + "/verify-enquiry-phone#token=" + token;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "LEAD_PHONE_DELIVERY_V1");
        payload.put("code", code);
        payload.put("confirmationUrl", confirmationUrl);
        try {
            LeadDataProtection.ProtectedValue protectedValue =
                    protection.protect(objectMapper.writeValueAsString(payload));
            return new DeliveryMaterial(challenge, outboxId, protectedValue.ciphertext(),
                    protectedValue.iv(), protectedValue.keyId());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Enquiry delivery payload could not be serialized");
        }
    }

    private LeadPhoneChallenge requireIssueReceipt(Long actorId, Long leadId, UUID operationId,
                                                   String payloadHash) {
        LeadAssistanceCommandReceipt receipt = receipts
                .findByActor_IdAndOperationIdAndAction(actorId, operationId, ISSUE_ACTION)
                .orElse(null);
        if (receipt == null) return null;
        LeadPhoneChallenge challenge = storedChallenge(receipt);
        if (!Objects.equals(challenge.getLead().getId(), leadId)) throw hidden();
        requireCurrentContactOwner(actorId, challenge.getLead());
        if (!receipt.getPayloadHash().equals(payloadHash)) {
            throw new VisitOperationsConflictException(
                    "This idempotency key was already used with a different enquiry-code request.");
        }
        return challenge;
    }

    private LeadPhoneChallenge requireReceipt(Long actorId, Long leadId, UUID operationId,
                                              String action,
                                              String payloadHash) {
        LeadAssistanceCommandReceipt receipt = receipts
                .findByActor_IdAndOperationIdAndAction(actorId, operationId, action)
                .orElse(null);
        if (receipt == null) return null;
        LeadPhoneChallenge challenge = storedChallenge(receipt);
        if (!Objects.equals(challenge.getLead().getId(), leadId)) throw hidden();
        requireCurrentContactOwner(actorId, challenge.getLead());
        if (!receipt.getPayloadHash().equals(payloadHash)) {
            throw new VisitOperationsConflictException(
                    "This idempotency key was already used with a different command.");
        }
        return challenge;
    }

    private LeadPhoneChallenge storedChallenge(LeadAssistanceCommandReceipt receipt) {
        if (!ISSUE_ACTION.equals(receipt.getAction())
                && !RESEND_ACTION.equals(receipt.getAction())
                && !CONFIRM_ACTION.equals(receipt.getAction())) throw hidden();
        UUID challengeId = LeadService.parseStoredUuidReference(receipt.getResultReference());
        return challenges.findById(challengeId)
                .orElseThrow(LeadPhoneVerificationService::hidden);
    }

    private LeadPhoneChallenge challengeForToken(String token) {
        String tokenHash;
        try {
            tokenHash = secrets.phoneConfirmationTokenHash(token);
        } catch (IllegalArgumentException exception) {
            throw new LeadPhoneVerificationRejectedException(HttpStatus.NOT_FOUND,
                    "This enquiry confirmation link is not available.");
        }
        LeadPhoneChallenge challenge = challenges.findByConfirmationTokenHash(tokenHash)
                .orElseThrow(() -> new LeadPhoneVerificationRejectedException(HttpStatus.NOT_FOUND,
                        "This enquiry confirmation link is not available."));
        if (!MessageDigest.isEqual(tokenHash.getBytes(StandardCharsets.US_ASCII),
                challenge.getConfirmationTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new LeadPhoneVerificationRejectedException(HttpStatus.NOT_FOUND,
                    "This enquiry confirmation link is not available.");
        }
        return challenge;
    }

    private void requireProcessedSource(LeadRoutingQueue lead, LeadSourceEvent source) {
        if (source == null || source.getLead() == null || !lead.getId().equals(source.getLead().getId())
                || source.getProcessingState() != LeadSourceEventState.PROCESSED) {
            throw new VisitOperationsConflictException(
                    "A current processed enquiry source is required for phone verification.");
        }
    }

    private void requireCurrentPhoneRevision(LeadRoutingQueue lead, Long expectedRevision) {
        if (lead.getContactPhoneNormalized() == null
                || expectedRevision == null
                || !expectedRevision.equals(lead.getContactPhoneRevision())) {
            throw new VisitOperationsConflictException(
                    "The enquiry phone number changed. Refresh before requesting a code.");
        }
    }

    private void requireChallengeVersion(LeadPhoneChallenge challenge, Long expected) {
        if (expected == null || !expected.equals(challenge.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The enquiry code changed while it was being reviewed. Refresh and retry.");
        }
    }

    private void requireWorkingLead(LeadRoutingQueue lead) {
        LeadStatus status = lead.getStatus();
        if (status != LeadStatus.NEW && status != LeadStatus.QUALIFYING
                && status != LeadStatus.QUALIFIED) {
            throw new VisitOperationsConflictException(
                    "Phone verification is unavailable for this Lead state.");
        }
    }

    private void requireVersion(LeadRoutingQueue lead, Long expected) {
        if (expected == null || !expected.equals(lead.getVersion())) {
            throw new VisitOperationsConflictException(
                    "The Lead changed while this request was in progress. Refresh and retry.");
        }
    }

    private LeadRoutingQueue lockLead(Long leadId) {
        if (leadId == null || leadId <= 0) throw new IllegalArgumentException("Lead ID is required");
        return leads.findLockedById(leadId)
                .orElseThrow(() -> new EntityNotFoundException("Lead not found"));
    }

    private LeadSourceEvent lockSource(UUID sourceEventId) {
        if (sourceEventId == null) throw new IllegalArgumentException("Source event ID is required");
        return sourceEvents.findLockedById(sourceEventId)
                .orElseThrow(LeadPhoneVerificationService::hidden);
    }

    private void acquireSecurityGuards(Long actorId, LeadRoutingQueue lead) {
        acquireSecurityGuardsFor(List.of(actorId), lead);
    }

    private void acquireSecurityGuardsFor(List<Long> actorIds, LeadRoutingQueue lead) {
        Long cityId = lead.getSupportedCity() == null ? null : lead.getSupportedCity().getId();
        Long teamId = lead.getOperatingTeam() == null ? null : lead.getOperatingTeam().getId();
        guards.acquire(cityId == null ? List.of() : List.of(cityId),
                teamId == null ? List.of() : List.of(teamId),
                actorIds.stream().filter(Objects::nonNull).distinct().sorted().toList());
    }

    private void requireCurrentIssuingAuthority(LeadPhoneChallenge challenge,
                                                LeadRoutingQueue lead,
                                                LeadSourceEvent source,
                                                Instant now) {
        boolean current = challenge.getCreatedBy() != null
                && lead.getOwner() != null
                && challenge.getCreatedBy().getId().equals(lead.getOwner().getId())
                && lead.getSupportedCity() != null
                && lead.getOperatingTeam() != null
                && Objects.equals(challenge.getSourceEvent().getId(), source.getId())
                && source.getProcessingState() == LeadSourceEventState.PROCESSED
                && Objects.equals(challenge.getContactPhoneRevision(),
                        lead.getContactPhoneRevision())
                && guards.cityIsActive(lead.getSupportedCity().getId())
                && guards.teamIsActive(lead.getOperatingTeam().getId())
                && Objects.equals(guards.teamCityId(lead.getOperatingTeam().getId()),
                        lead.getSupportedCity().getId())
                && staffAccess.hasCapabilityAt(challenge.getCreatedBy().getId(),
                        StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM,
                        lead.getOperatingTeam().getId());
        if (current) return;
        challenge.cancel(now);
        challenge.updateDelivery(LeadPhoneDeliveryState.SUPPRESSED);
        challenges.saveAndFlush(challenge);
        purgeDeliverySecret(challenge.getId(), now);
        throw new LeadPhoneVerificationRejectedException(HttpStatus.CONFLICT,
                "This enquiry confirmation is no longer current. The saved Lead and follow-up remain available.");
    }

    private void purgeDeliverySecret(UUID challengeId, Instant now) {
        outbox.findByChallenge_Id(challengeId).ifPresent(value -> {
            value.purgePayload(now);
            outbox.save(value);
        });
    }

    private void requireCurrentContactOwner(Long actorId, LeadRoutingQueue lead) {
        LeadAccessContext context = leadAccess.context(actorId);
        leadAccess.requireContactOwner(context, lead, actorId);
        if (lead.getSupportedCity() == null || lead.getOperatingTeam() == null
                || !guards.cityIsActive(lead.getSupportedCity().getId())
                || !guards.teamIsActive(lead.getOperatingTeam().getId())
                || !Objects.equals(guards.teamCityId(lead.getOperatingTeam().getId()),
                        lead.getSupportedCity().getId())) {
            throw hidden();
        }
    }

    private String numberFingerprint(LeadRoutingQueue lead) {
        if (lead.getContactPhoneNormalized() == null) {
            throw new VisitOperationsConflictException(
                    "A validated enquiry phone is required before sending a code.");
        }
        return protection.keyedDigest("LEAD_PHONE_DESTINATION",
                lead.getContactPhoneNormalized());
    }

    private int nextGeneration(Long leadId, UUID sourceEventId, Long phoneRevision) {
        return challenges.findGenerations(leadId, sourceEventId, PURPOSE, phoneRevision).stream()
                .map(LeadPhoneChallenge::getGeneration)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
    }

    private void acquireWindowLocks(Long actorId, String fingerprint) {
        List<String> keys = List.of("lead-phone-actor:" + actorId,
                "lead-phone-destination:" + fingerprint).stream().sorted().toList();
        for (String key : keys) {
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                    statement -> statement.setString(1, key), resultSet -> null);
        }
    }

    private void enforceSendLimits(Long actorId, String fingerprint, Instant now) {
        long actorMinute = challenges.countByActorSince(actorId, now.minus(Duration.ofMinutes(1)));
        long actorHour = challenges.countByActorSince(actorId, now.minus(Duration.ofHours(1)));
        if (!LeadPhoneVerificationPolicy.actorSendAllowed(actorMinute, actorHour)) {
            throw new LeadPhoneVerificationRejectedException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many enquiry codes were requested. Continue the saved Lead follow-up.",
                    60);
        }
        long destinationHour = challenges.countByDestinationSince(
                fingerprint, now.minus(Duration.ofHours(1)));
        long destinationDay = challenges.countByDestinationSince(
                fingerprint, now.minus(Duration.ofHours(24)));
        if (!LeadPhoneVerificationPolicy.destinationSendAllowed(
                destinationHour, destinationDay)) {
            throw new LeadPhoneVerificationRejectedException(HttpStatus.TOO_MANY_REQUESTS,
                    "This number has reached its enquiry-code limit. Continue the saved Lead follow-up.",
                    3600);
        }
    }

    private void lockOperation(Long actorId, String action, UUID operationId) {
        String key = actorId + ":" + action + ":" + operationId;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, key), resultSet -> null);
    }

    private String payloadHash(Long leadId, Object command) {
        return payloadHash(Map.of("leadId", leadId, "command", command));
    }

    private String payloadHash(Object value) {
        try {
            return protection.keyedDigest("LEAD_ASSISTANCE_PAYLOAD",
                    objectMapper.writeValueAsString(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Assistance command could not be fingerprinted");
        }
    }

    private static UUID operationId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID");
        }
    }

    private static void validateIssue(PhoneIssueCommand command) {
        if (command == null || command.expectedLeadVersion() == null
                || command.sourceEvent() == null || command.sourceEvent().id() == null
                || command.expectedContactPhoneRevision() == null) {
            throw new IllegalArgumentException(
                    "Expected Lead/revision and an attached source event are required");
        }
        if (!command.noticeAgreed()) {
            throw new IllegalArgumentException(
                    "Customer notice and agreement are required before sending an enquiry code");
        }
    }

    private static void validateResend(PhoneResendCommand command) {
        if (command == null || command.expectedLeadVersion() == null
                || command.expectedChallengeVersion() == null
                || command.expectedGeneration() <= 0 || !command.noticeAgreed()) {
            throw new IllegalArgumentException(
                    "Current Lead/challenge versions, generation and notice agreement are required");
        }
    }

    private static void validateConfirm(PhoneConfirmCommand command) {
        if (command == null || command.expectedLeadVersion() == null
                || command.expectedChallengeVersion() == null) {
            throw new IllegalArgumentException("Current Lead/challenge versions and code are required");
        }
    }

    private PhoneVerificationView view(LeadPhoneChallenge challenge) {
        LeadRoutingQueue lead = challenge.getLead();
        Instant now = databaseClock.now();
        LeadPhoneChallengeState effectiveState = effectiveState(challenge, now);
        boolean currentProofUsable = effectiveState == LeadPhoneChallengeState.VERIFIED
                && challenge.getVerifiedAt() != null
                && now.isBefore(challenge.getVerifiedAt().plus(
                LeadPhoneVerificationPolicy.PROOF_LIFETIME))
                && Objects.equals(challenge.getContactPhoneRevision(),
                lead.getContactPhoneRevision())
                && lead.getContactPhoneNormalized() != null
                && challenge.getNumberFingerprint().equals(
                protection.keyedDigest("LEAD_PHONE_DESTINATION",
                        lead.getContactPhoneNormalized()))
                && challenge.getSourceEvent().getProcessingState()
                == LeadSourceEventState.PROCESSED;
        Instant proofUsableUntil = currentProofUsable
                ? challenge.getVerifiedAt().plus(LeadPhoneVerificationPolicy.PROOF_LIFETIME)
                : null;
        String message = switch (effectiveState) {
            case VERIFIED -> currentProofUsable
                    ? "Phone possession is current for this enquiry source and phone revision. This is not account verification."
                    : "This phone was confirmed historically, but it is no longer current proof for the present enquiry number or source.";
            case PENDING -> challenge.getDeliveryState() == LeadPhoneDeliveryState.SUPPRESSED
                    ? "SMS confirmation is unavailable. The Lead and follow-up remain saved."
                    : "The enquiry code is pending confirmation.";
            case EXPIRED -> "The enquiry code expired. Request a new code if the number is still current.";
            case EXHAUSTED -> "The enquiry code reached its attempt limit. Request a new code.";
            case CANCELLED -> "The enquiry code was cancelled because its context changed.";
        };
        return new PhoneVerificationView(challenge.getId(), lead.getId(), effectiveState,
                challenge.getDeliveryState(), challenge.getMaskedDestination(),
                challenge.getFailedAttempts(),
                LeadPhoneChallenge.MAX_FAILED_ATTEMPTS, challenge.getGeneration(),
                challenge.getVersion(), lead.getContactPhoneRevision(),
                challenge.getContactPhoneRevision(), currentProofUsable, proofUsableUntil,
                challenge.getCreatedAt(),
                challenge.getExpiresAt(), challenge.getVerifiedAt(),
                challenge.getEntryMethod() == null ? null : challenge.getEntryMethod().name(),
                message);
    }

    private PhoneConfirmationView confirmationView(LeadPhoneChallenge challenge, Instant now) {
        LeadPhoneChallengeState state = effectiveState(challenge, now);
        String message = switch (state) {
            case PENDING -> "Enter the six-digit enquiry code from the SMS.";
            case VERIFIED -> "This enquiry phone is already confirmed.";
            case EXPIRED -> "This enquiry code expired. Request a new code from Pathome.";
            case EXHAUSTED -> "Too many incorrect attempts. Request a new code from Pathome.";
            case CANCELLED -> "This enquiry code is no longer active.";
        };
        return new PhoneConfirmationView(state, challenge.getDeliveryState(),
                challenge.getMaskedDestination(),
                Math.max(0, LeadPhoneChallenge.MAX_FAILED_ATTEMPTS
                        - challenge.getFailedAttempts()), challenge.getExpiresAt(), message);
    }

    private static LeadPhoneChallengeState effectiveState(LeadPhoneChallenge challenge, Instant now) {
        if (challenge.getState() == LeadPhoneChallengeState.PENDING
                && now.isAfter(challenge.getExpiresAt())) {
            return LeadPhoneChallengeState.EXPIRED;
        }
        return challenge.getState();
    }

    private String publicOrigin() {
        if (configuredOrigin == null || configuredOrigin.isBlank()) {
            throw new IllegalStateException(
                    "A canonical public origin is not configured for enquiry confirmation");
        }
        try {
            URI uri = URI.create(configuredOrigin.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            return uri.getScheme().toLowerCase() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "The configured public origin is invalid for enquiry confirmation");
        }
    }

    private static EntityNotFoundException hidden() {
        return new EntityNotFoundException("Phone verification not found");
    }

    private record DeliveryMaterial(LeadPhoneChallenge challenge, UUID outboxId,
                                    String ciphertext, String iv, String keyId) {}
}
