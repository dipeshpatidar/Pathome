package com.indore.pathome.spaces.service;

public interface VisitOtpDeliveryProvider {
    DeliveryResult deliverToAuthenticatedTenant(Long tenantUserId, Long sessionId, String code);
    record DeliveryResult(String channel, String displayCode) {}
}
