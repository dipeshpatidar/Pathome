package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadPhoneChallenge;
import com.indore.pathome.spaces.entity.LeadPhoneChallengeState;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryOutbox;
import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import com.indore.pathome.spaces.repository.LeadPhoneChallengeRepository;
import com.indore.pathome.spaces.repository.LeadPhoneDeliveryOutboxRepository;
import com.indore.pathome.spaces.repository.LeadRoutingQueueRepository;
import com.indore.pathome.spaces.repository.LeadSourceEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Persists the outcome of an attempt outside the external-provider call. */
@Service
public class LeadPhoneDeliveryCompletionService {
    private final LeadPhoneDeliveryOutboxRepository outbox;
    private final LeadPhoneChallengeRepository challenges;
    private final LeadRoutingQueueRepository leads;
    private final LeadSourceEventRepository sourceEvents;
    private final DatabaseClock databaseClock;
    private final EntityManager entityManager;

    public LeadPhoneDeliveryCompletionService(
            LeadPhoneDeliveryOutboxRepository outbox,
            LeadPhoneChallengeRepository challenges,
            LeadRoutingQueueRepository leads,
            LeadSourceEventRepository sourceEvents,
            DatabaseClock databaseClock,
            EntityManager entityManager) {
        this.outbox = outbox;
        this.challenges = challenges;
        this.leads = leads;
        this.sourceEvents = sourceEvents;
        this.databaseClock = databaseClock;
        this.entityManager = entityManager;
    }

    @Transactional
    public void complete(UUID outboxId, LeadPhoneDeliveryState resultState,
                         String providerMessageId, String errorCode, UUID attemptId) {
        LeadPhoneDeliveryOutbox seed = outbox.findById(outboxId).orElse(null);
        if (seed == null) return;
        UUID challengeId = seed.getChallenge().getId();
        LeadPhoneChallenge challengeSeed = challenges.findById(challengeId).orElse(null);
        if (challengeSeed == null) return;
        Long leadId = challengeSeed.getLead().getId();
        UUID sourceId = challengeSeed.getSourceEvent().getId();

        leads.findLockedById(leadId).orElse(null);
        sourceEvents.findLockedById(sourceId).orElse(null);
        LeadPhoneChallenge challenge = challenges.findLockedById(challengeId).orElse(null);
        LeadPhoneDeliveryOutbox current = outbox.findLockedById(outboxId).orElse(null);
        if (challenge == null || current == null) return;
        entityManager.refresh(challenge, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
        if (attemptId == null || !attemptId.equals(current.getLeaseToken())) return;

        Instant now = databaseClock.now();
        if (resultState == LeadPhoneDeliveryState.SUPPRESSED) {
            current.markSuppressed(errorCode == null ? "DELIVERY_SUPPRESSED" : errorCode, now);
        } else {
            current.dispatchAccepted(resultState, providerMessageId, now);
        }
        if (challenge.getState() != LeadPhoneChallengeState.VERIFIED) {
            challenge.updateDelivery(resultState);
            challenges.saveAndFlush(challenge);
        }
        outbox.saveAndFlush(current);
    }
}
