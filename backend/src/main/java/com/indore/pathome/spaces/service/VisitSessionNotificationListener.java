package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.TargetRole;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class VisitSessionNotificationListener {
    private final NotificationService notifications;

    public VisitSessionNotificationListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVisitSessionEvent(VisitSessionNotificationEvent event) {
        switch (event.type()) {
            case SCHEDULED -> {
                notifyTenant(event, "SCHEDULED", "Visit session scheduled",
                        "Your Visit Session has been scheduled for " + formatSchedule(event) + ".");
                notifyGroundAssigned(event, "ASSIGNED");
            }
            case RESCHEDULED -> {
                notifyTenant(event, "RESCHEDULED", "Visit session rescheduled",
                        "Your Visit Session has been rescheduled to " + formatSchedule(event) + ".");
                notifyGround(event, "RESCHEDULED", "Visit Session time changed",
                        "Visit Session " + event.sessionId() + " is now scheduled for " + formatSchedule(event) + ".");
            }
            case CANCELLED -> {
                notifyTenant(event, "CANCELLED", "Visit session cancelled",
                        "Operations cancelled your Visit Session.");
                notifyGround(event, "CANCELLED", "Visit Session cancelled",
                        "Visit Session " + event.sessionId() + " has been cancelled by Operations.");
            }
            case ASSIGNED -> notifyGroundAssigned(event, "ASSIGNED");
            case REASSIGNED -> {
                notifyGroundAssigned(event, "ASSIGNED");
                notifyGround(event, "REASSIGNED_FROM", "Visit Session reassigned",
                        "Visit Session " + event.sessionId() + " is no longer assigned to you.",
                        event.formerGroundExecutiveUserId());
            }
            case ITINERARY_CHANGED -> notifyGround(event, "ITINERARY_CHANGED", "Visit Session itinerary changed",
                    "The itinerary for Visit Session " + event.sessionId()
                            + " changed. Review the latest stops in the app before traveling.");
        }
    }

    private void notifyTenant(VisitSessionNotificationEvent event, String keyType, String title, String message) {
        notifications.createNotificationWithEventKey(TargetRole.TENANT, String.valueOf(event.tenantUserId()),
                title, message, null, "VISIT_SESSION", "info", eventKey(event, keyType, event.tenantUserId()));
    }

    private void notifyGroundAssigned(VisitSessionNotificationEvent event, String keyType) {
        notifyGround(event, keyType, "Visit Session assigned",
                "Visit Session " + event.sessionId() + " has been assigned to you.",
                event.groundExecutiveUserId());
    }

    private void notifyGround(VisitSessionNotificationEvent event, String keyType, String title, String message) {
        notifyGround(event, keyType, title, message, event.groundExecutiveUserId());
    }

    private void notifyGround(VisitSessionNotificationEvent event, String keyType, String title,
                              String message, Long groundExecutiveUserId) {
        if (groundExecutiveUserId == null) return;
        notifications.createNotificationWithEventKey(TargetRole.GROUND_BOY,
                String.valueOf(groundExecutiveUserId), title, message, null, "VISIT_SESSION", "info",
                eventKey(event, keyType, groundExecutiveUserId));
    }

    private String eventKey(VisitSessionNotificationEvent event, String type, Long recipientId) {
        return "VISIT_SESSION_" + type + ":" + event.sessionId() + ":v" + event.version() + ":" + recipientId;
    }

    private String formatSchedule(VisitSessionNotificationEvent event) {
        ZoneId zone = ZoneId.of(event.zoneId());
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone).format(event.scheduledAt())
                + " (" + event.zoneId() + ")";
    }
}
