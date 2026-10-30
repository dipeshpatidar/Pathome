package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;

import java.util.UUID;

/**
 * Purpose-specific delivery boundary for enquiry-phone proof. Implementations must never be reused
 * for account login or Visit-start OTP.
 */
public interface LeadPhoneDeliveryProvider {
    String name();

    boolean enabled();

    DeliveryResult deliver(DeliveryRequest request);

    record DeliveryRequest(UUID challengeId, UUID attemptId, String normalizedDestination,
                           String code, String confirmationUrl) {}

    record DeliveryResult(LeadPhoneDeliveryState state, String providerMessageId) {
        public DeliveryResult {
            if (state == null) throw new IllegalArgumentException("Delivery result state is required");
        }
    }
}
