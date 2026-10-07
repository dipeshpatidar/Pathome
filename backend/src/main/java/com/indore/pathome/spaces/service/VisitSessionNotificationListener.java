package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.TargetRole;
import com.indore.pathome.spaces.entity.NotificationAuthorizationClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class VisitSessionNotificationListener {
    private final JdbcTemplate jdbc;

    public VisitSessionNotificationListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void enqueueVisitSessionEvent(VisitSessionNotificationEvent event) {
        switch (event.type()) {
            case SCHEDULED -> {
                enqueueTenant(event, "SCHEDULED", "Visit session scheduled",
                        "Your Visit Session has been scheduled for " + formatSchedule(event) + ".");
                enqueueGroundAssigned(event, "ASSIGNED");
            }
            case RESCHEDULED -> {
                enqueueTenant(event, "RESCHEDULED", "Visit session rescheduled",
                        "Your visit time is proposed as " + formatSchedule(event) + ". Confirm or reject this change in Pathome.");
                enqueueGround(event, "RESCHEDULED", "Visit Session time changed",
                        "Visit Session " + event.sessionId() + " is now scheduled for " + formatSchedule(event) + ".", event.groundExecutiveUserId());
            }
            case CANCELLED -> {
                enqueueTenant(event, "CANCELLED", "Visit session cancelled", "Operations cancelled your Visit Session.");
                enqueueGround(event, "CANCELLED", "Visit Session cancelled",
                        "Visit Session " + event.sessionId() + " has been cancelled by Operations.", event.groundExecutiveUserId());
            }
            case ASSIGNED -> enqueueGroundAssigned(event, "ASSIGNED");
            case REASSIGNED -> {
                enqueueTenant(event, "REASSIGNED", "Ground Executive updated",
                        "A different Ground Executive is assigned to your visit. Review the latest visit details in Pathome.");
                enqueueGroundAssigned(event, "ASSIGNED");
                enqueueGround(event, "REASSIGNED_FROM", "Visit Session reassigned",
                        "Visit Session " + event.sessionId() + " is no longer assigned to you.", event.formerGroundExecutiveUserId());
            }
            case ITINERARY_CHANGED -> enqueueGround(event, "ITINERARY_CHANGED", "Visit Session itinerary changed",
                    "The itinerary for Visit Session " + event.sessionId()
                            + " changed. Review the latest stops in the app before traveling.", event.groundExecutiveUserId());
        }
    }

    private void enqueueTenant(VisitSessionNotificationEvent event, String keyType, String title, String message) {
        enqueue(event, keyType, TargetRole.TENANT, event.tenantUserId(), title, message,
                NotificationAuthorizationClass.RECIPIENT, null);
    }

    private void enqueueGroundAssigned(VisitSessionNotificationEvent event, String keyType) {
        enqueueGround(event, keyType, "Visit Session assigned",
                "Visit Session " + event.sessionId() + " has been assigned to you.", event.groundExecutiveUserId());
    }

    private void enqueueGround(VisitSessionNotificationEvent event, String keyType, String title,
                               String message, Long recipientId) {
        if (recipientId == null) return;
        NotificationAuthorizationClass authorizationClass = event.sessionId() == null
                ? NotificationAuthorizationClass.STAFF_LEGACY_QUARANTINED
                : NotificationAuthorizationClass.OPERATIONS_SESSION;
        enqueue(event, keyType, TargetRole.GROUND_BOY, recipientId, title, message,
                authorizationClass, event.sessionId());
    }

    private void enqueue(VisitSessionNotificationEvent event, String keyType, TargetRole role, Long recipientId,
                         String title, String message, NotificationAuthorizationClass authorizationClass,
                         Long operationalSessionId) {
        if (recipientId == null) return;
        String key = eventKey(event, keyType, recipientId);
        jdbc.update("insert into visit_notification_outbox(event_key,recipient_user_id,recipient_role,event_type,title,message,authorization_class,operational_session_id) "
                        + "values (?,?,?,?,?,?,?,?) on conflict(event_key) do nothing",
                key, recipientId, role.name(), keyType, title, message, authorizationClass.name(), operationalSessionId);
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
