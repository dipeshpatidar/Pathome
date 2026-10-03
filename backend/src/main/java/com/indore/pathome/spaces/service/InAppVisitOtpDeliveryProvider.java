package com.indore.pathome.spaces.service;

import org.springframework.stereotype.Component;

/** V1 exposes the code only in the authenticated tenant session; it does not claim an SMS was sent. */
@Component
public class InAppVisitOtpDeliveryProvider implements VisitOtpDeliveryProvider {
    @Override
    public DeliveryResult deliverToAuthenticatedTenant(Long tenantUserId, Long sessionId, String code) {
        return new DeliveryResult("IN_APP_AUTHENTICATED", code);
    }
}
