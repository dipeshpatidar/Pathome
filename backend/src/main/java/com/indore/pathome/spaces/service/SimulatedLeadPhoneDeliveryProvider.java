package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadPhoneDeliveryState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Zero-network adapter for isolated tests. It records only that a simulated transport accepted an
 * attempt; it never claims an SMS was delivered and is disabled by default in every environment.
 */
@Component
public class SimulatedLeadPhoneDeliveryProvider implements LeadPhoneDeliveryProvider {
    private final boolean enabled;

    public SimulatedLeadPhoneDeliveryProvider(
            @Value("${pathome.crm.phone-verification-simulator-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "SIMULATED";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public DeliveryResult deliver(DeliveryRequest request) {
        if (request == null) throw new IllegalArgumentException("Delivery request is required");
        if (!enabled) {
            return new DeliveryResult(LeadPhoneDeliveryState.SUPPRESSED, null);
        }
        // The fixed opaque acknowledgement is test evidence only. No network or clock is used here.
        return new DeliveryResult(LeadPhoneDeliveryState.ACCEPTED,
                "simulated:" + request.attemptId());
    }
}
