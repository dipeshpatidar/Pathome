package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.LeadPhoneChallenge;
import com.indore.pathome.spaces.entity.LeadPhoneChallengeState;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryOutbox;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadSourceEvent;
import com.indore.pathome.spaces.entity.LeadSourceEventState;
import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.entity.StaffCapability;
import com.indore.pathome.spaces.entity.StaffScopeType;
import com.indore.pathome.spaces.repository.LeadPhoneChallengeRepository;
import com.indore.pathome.spaces.repository.LeadPhoneDeliveryOutboxRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Claims one durable delivery attempt without performing network I/O in the transaction. */
@Service
public class LeadPhoneDeliveryClaimService {
    private static final Duration LEASE = Duration.ofSeconds(45);

    private final LeadPhoneDeliveryOutboxRepository outbox;
    private final LeadPhoneChallengeRepository challenges;
    private final LeadRoutingQueueRepository leads;
    private final LeadSourceEventRepository sourceEvents;
    private final DatabaseClock databaseClock;
    private final LeadDataProtection protection;
    private final LeadPhoneDeliveryProvider provider;
    private final StaffAccessService staffAccess;
    private final OperationalSecurityGuards guards;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public LeadPhoneDeliveryClaimService(
            LeadPhoneDeliveryOutboxRepository outbox,
            LeadPhoneChallengeRepository challenges,
            LeadRoutingQueueRepository leads,
            LeadSourceEventRepository sourceEvents,
            DatabaseClock databaseClock,
            LeadDataProtection protection,
            LeadPhoneDeliveryProvider provider,
            StaffAccessService staffAccess,
            OperationalSecurityGuards guards,
            ObjectMapper objectMapper,
            EntityManager entityManager) {
        this.outbox = outbox;
        this.challenges = challenges;
        this.leads = leads;
        this.sourceEvents = sourceEvents;
        this.databaseClock = databaseClock;
        this.protection = protection;
        this.provider = provider;
        this.staffAccess = staffAccess;
        this.guards = guards;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    @Transactional
    public LeadPhoneDeliveryWorker.DispatchTicket claimNext() {
        Instant discoveryNow = databaseClock.now();
        List<UUID> due = outbox.findDueIds(discoveryNow, PageRequest.of(0, 1));
        if (due.isEmpty()) return null;
        UUID outboxId = due.get(0);
        LeadPhoneDeliveryOutbox seed = outbox.findById(outboxId).orElse(null);
        if (seed == null) return null;
        UUID challengeId = seed.getChallenge().getId();
        LeadPhoneChallenge challengeSeed = challenges.findById(challengeId).orElse(null);
        if (challengeSeed == null) {
            outbox.deleteById(outboxId);
            return null;
        }
        Long leadId = challengeSeed.getLead().getId();
        UUID sourceId = challengeSeed.getSourceEvent().getId();

        LeadRoutingQueue lead = leads.findLockedById(leadId).orElse(null);
        LeadSourceEvent source = sourceEvents.findLockedById(sourceId).orElse(null);
        LeadPhoneChallenge challenge = challenges.findLockedById(challengeId).orElse(null);
        LeadPhoneDeliveryOutbox current = outbox.findLockedById(outboxId).orElse(null);
        if (current == null) return null;
        if (challenge == null) {
            outbox.deleteById(outboxId);
            return null;
        }
        // The seed entities may still be managed with pre-lock state. Pessimistic lookups can return
        // those same instances, so refresh after the domain locks and before any inference or write.
        entityManager.refresh(challenge, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
        if (isTerminal(current.getState())) return null;
        if (handleUncertainDispatch(challenge, current, databaseClock.now())) {
            return null;
        }
        if (lead == null || source == null) {
            suppress(challenge, current, databaseClock.now(), "DISPATCH_CONTEXT_MISSING");
            return null;
        }
        if (lead.getSupportedCity() == null || lead.getOperatingTeam() == null
                || lead.getOwner() == null
                || challenge.getCreatedBy() == null) {
            suppress(challenge, current, databaseClock.now(), "DISPATCH_CONTEXT_MISSING");
            return null;
        }
        guards.acquire(List.of(lead.getSupportedCity().getId()),
                List.of(lead.getOperatingTeam().getId()),
                List.of(challenge.getCreatedBy().getId()));
        // Security guards can wait on City/Team/profile/grant writers. Every admission fact and the
        // dispatch lease derive from the first database time taken after that final wait.
        entityManager.refresh(lead, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(source, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(challenge, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
        Instant admissionNow = databaseClock.now();
        if (isTerminal(current.getState())) return null;
        if (handleUncertainDispatch(challenge, current, admissionNow)) {
            return null;
        }
        if (!isDispatchContextCurrent(challenge, current, lead, source, admissionNow)) {
            suppress(challenge, current, admissionNow, "DISPATCH_CONTEXT_CHANGED");
            return null;
        }
        if (!guards.cityIsActive(lead.getSupportedCity().getId())
                || !guards.teamIsActive(lead.getOperatingTeam().getId())
                || !Objects.equals(guards.teamCityId(lead.getOperatingTeam().getId()),
                        lead.getSupportedCity().getId())
                || !lead.getOwner().getId().equals(challenge.getCreatedBy().getId())
                || !staffAccess.hasCapabilityAt(challenge.getCreatedBy().getId(),
                        StaffCapability.OPS_COORDINATE, StaffScopeType.TEAM,
                        lead.getOperatingTeam().getId())) {
            suppress(challenge, current, admissionNow, "DISPATCH_AUTHORITY_CHANGED");
            return null;
        }
        if (!provider.enabled()) {
            challenge.updateDelivery(LeadPhoneDeliveryState.SUPPRESSED);
            current.markSuppressed("PROVIDER_DISABLED", admissionNow);
            challenges.saveAndFlush(challenge);
            outbox.saveAndFlush(current);
            return null;
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(protection.reveal(current.getPayloadCiphertext(),
                    current.getPayloadIv(), current.getPayloadKeyVersion()));
        } catch (Exception exception) {
            challenge.updateDelivery(LeadPhoneDeliveryState.FAILED);
            current.markSuppressed("DELIVERY_PAYLOAD_INVALID", admissionNow);
            challenges.saveAndFlush(challenge);
            outbox.saveAndFlush(current);
            return null;
        }
        String code = payload.path("code").asText(null);
        String confirmationUrl = payload.path("confirmationUrl").asText(null);
        if (code == null || !code.matches("[0-9]{6}")
                || confirmationUrl == null || !confirmationUrl.startsWith("https://")) {
            challenge.updateDelivery(LeadPhoneDeliveryState.FAILED);
            current.markSuppressed("DELIVERY_PAYLOAD_INVALID", admissionNow);
            challenges.saveAndFlush(challenge);
            outbox.saveAndFlush(current);
            return null;
        }

        UUID attemptId = UUID.randomUUID();
        current.claim(attemptId, admissionNow.plus(LEASE), admissionNow);
        challenge.updateDelivery(LeadPhoneDeliveryState.QUEUED);
        challenges.saveAndFlush(challenge);
        outbox.saveAndFlush(current);
        return new LeadPhoneDeliveryWorker.DispatchTicket(outboxId, challengeId, leadId,
                lead.getContactPhoneNormalized(), code, confirmationUrl, attemptId);
    }

    private boolean isTerminal(LeadPhoneDeliveryState state) {
        return state == LeadPhoneDeliveryState.ACCEPTED
                || state == LeadPhoneDeliveryState.DELIVERED
                || state == LeadPhoneDeliveryState.FAILED
                || state == LeadPhoneDeliveryState.UNKNOWN
                || state == LeadPhoneDeliveryState.SUPPRESSED;
    }

    /**
     * Resolves only non-claimable dispatch states. Discovery is a stale advisory, so the freshly
     * locked row must win before queue-context checks can suppress it.
     */
    private boolean handleUncertainDispatch(LeadPhoneChallenge challenge,
                                            LeadPhoneDeliveryOutbox current,
                                            Instant now) {
        if (current.getState() != LeadPhoneDeliveryState.DISPATCHING
                && !(current.getState() == LeadPhoneDeliveryState.QUEUED
                && current.getLeaseUntil() != null)) {
            return false;
        }
        if (current.getState() == LeadPhoneDeliveryState.DISPATCHING
                && current.getLeaseUntil() != null
                && current.getLeaseUntil().isAfter(now)) {
            return true;
        }
        // An expired dispatch or legacy queued-with-lease row might already have reached the
        // provider. It remains uncertain and must never be sent again automatically.
        current.markUnknown("DISPATCH_OUTCOME_UNKNOWN", now);
        challenge.updateDelivery(LeadPhoneDeliveryState.UNKNOWN);
        challenges.saveAndFlush(challenge);
        outbox.saveAndFlush(current);
        return true;
    }

    private boolean isDispatchContextCurrent(LeadPhoneChallenge challenge,
                                             LeadPhoneDeliveryOutbox current,
                                             LeadRoutingQueue lead,
                                             LeadSourceEvent source,
                                             Instant now) {
        LeadStatus status = lead.getStatus();
        return current.getState() == LeadPhoneDeliveryState.QUEUED
                && challenge.getState() == LeadPhoneChallengeState.PENDING
                && !now.isAfter(challenge.getExpiresAt())
                && (status == LeadStatus.NEW || status == LeadStatus.QUALIFYING
                    || status == LeadStatus.QUALIFIED)
                && source.getLead() != null
                && lead.getId().equals(source.getLead().getId())
                && source.getProcessingState() == LeadSourceEventState.PROCESSED
                && Objects.equals(lead.getContactPhoneRevision(),
                        challenge.getContactPhoneRevision())
                && lead.getContactPhoneNormalized() != null
                && Objects.equals(challenge.getNumberFingerprint(),
                        protection.keyedDigest("LEAD_PHONE_DESTINATION",
                                lead.getContactPhoneNormalized()));
    }

    private void suppress(LeadPhoneChallenge challenge, LeadPhoneDeliveryOutbox current,
                          Instant now, String reason) {
        challenge.cancel(now);
        challenge.updateDelivery(LeadPhoneDeliveryState.SUPPRESSED);
        current.markSuppressed(reason, now);
        challenges.saveAndFlush(challenge);
        outbox.saveAndFlush(current);
    }

    @Transactional(readOnly = true)
    public boolean hasDueWork() {
        return !outbox.findDueIds(databaseClock.now(), PageRequest.of(0, 1)).isEmpty();
    }
}
