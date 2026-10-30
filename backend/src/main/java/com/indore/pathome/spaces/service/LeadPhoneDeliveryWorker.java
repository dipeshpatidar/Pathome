package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Bounded worker. The default adapter is disabled, so ordinary deployments remain unavailable until
 * a separately approved provider is configured.
 */
@Component
public class LeadPhoneDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(LeadPhoneDeliveryWorker.class);
    private static final int MAX_CANDIDATE_INSPECTIONS = 100;
    private static final int MAX_DISPATCHES = 10;
    private final LeadPhoneDeliveryClaimService claims;
    private final LeadPhoneDeliveryCompletionService completions;
    private final LeadPhoneDeliveryProvider provider;

    public LeadPhoneDeliveryWorker(LeadPhoneDeliveryClaimService claims,
                                   LeadPhoneDeliveryCompletionService completions,
                                   LeadPhoneDeliveryProvider provider) {
        this.claims = claims;
        this.completions = completions;
        this.provider = provider;
    }

    @Scheduled(fixedDelayString = "${pathome.crm.phone-delivery-poll-delay-ms:5000}")
    public void dispatchDue() {
        if (!provider.enabled()) return;
        int dispatched = 0;
        for (int inspected = 0;
             inspected < MAX_CANDIDATE_INSPECTIONS && dispatched < MAX_DISPATCHES;
             inspected++) {
            DispatchTicket ticket = claims.claimNext();
            if (ticket == null) {
                if (!claims.hasDueWork()) return;
                continue;
            }
            dispatched++;
            LeadPhoneDeliveryProvider.DeliveryResult result;
            try {
                result = provider.deliver(new LeadPhoneDeliveryProvider.DeliveryRequest(
                        ticket.challengeId(), ticket.attemptId(), ticket.destination(),
                        ticket.code(), ticket.confirmationUrl()));
            } catch (RuntimeException exception) {
                try {
                    completions.complete(ticket.outboxId(), LeadPhoneDeliveryState.UNKNOWN,
                            null, "PROVIDER_OUTCOME_UNKNOWN", ticket.attemptId());
                } catch (RuntimeException completionFailure) {
                    log.warn("Enquiry delivery completion failed after an ambiguous provider outcome");
                }
                continue;
            }
            if (result == null) {
                try {
                    completions.complete(ticket.outboxId(), LeadPhoneDeliveryState.UNKNOWN,
                            null, "PROVIDER_OUTCOME_MALFORMED", ticket.attemptId());
                } catch (RuntimeException exception) {
                    log.warn("Enquiry delivery completion failed after a malformed provider result");
                }
                continue;
            }
            try {
                completions.complete(ticket.outboxId(), result.state(),
                        result.providerMessageId(),
                        result.state() == LeadPhoneDeliveryState.SUPPRESSED
                                ? "DELIVERY_SUPPRESSED" : null, ticket.attemptId());
            } catch (RuntimeException exception) {
                // The leased attempt remains ambiguous until bounded expiry. Do not resend or halt
                // processing of later eligible work.
                log.warn("Enquiry delivery completion failed; the leased attempt remains fenced");
            }
        }
    }

    public record DispatchTicket(UUID outboxId, UUID challengeId, Long leadId,
                                 String destination, String code, String confirmationUrl,
                                 UUID attemptId) {}
}
