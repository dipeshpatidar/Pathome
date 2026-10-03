package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.config.VisitExecutionProperties;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/** Keeps booking confirmation and the corresponding credit hold in the same transaction. */
@Component
public class VisitEntitlementListener {
    private final VisitEntitlementStore entitlements;
    private final VisitExecutionProperties properties;

    public VisitEntitlementListener(VisitEntitlementStore entitlements, VisitExecutionProperties properties) {
        this.entitlements = entitlements;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onSessionEvent(VisitSessionNotificationEvent event) {
        if (event.type() == VisitSessionNotificationEvent.Type.SCHEDULED && event.scheduledAt() != null) {
            Instant horizon = Instant.now().plusSeconds(properties.getReservationHorizonDays() * 86400L);
            if (!event.scheduledAt().isAfter(horizon)
                    && !entitlements.reserve(event.tenantUserId(), event.sessionId(), horizon))
                throw new VisitOperationsConflictException("ENTITLEMENT_UNAVAILABLE: no visit credit is available to confirm this session");
        } else if (event.type() == VisitSessionNotificationEvent.Type.RESCHEDULED && event.scheduledAt() != null
                && !entitlements.hasReservation(event.sessionId())) {
            Instant horizon = Instant.now().plusSeconds(properties.getReservationHorizonDays() * 86400L);
            if (!event.scheduledAt().isAfter(horizon)
                    && !entitlements.reserve(event.tenantUserId(), event.sessionId(), horizon))
                throw new VisitOperationsConflictException("ENTITLEMENT_UNAVAILABLE: no visit credit is available to confirm this session");
        } else if (event.type() == VisitSessionNotificationEvent.Type.CANCELLED) {
            entitlements.release(event.tenantUserId(), event.sessionId(), null, "PRE_START_CANCELLATION");
        }
    }
}
