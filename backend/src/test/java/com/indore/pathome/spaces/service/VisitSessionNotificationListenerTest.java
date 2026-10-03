package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.TargetRole;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisitSessionNotificationListenerTest {
    @Test
    void notificationTransitionsAreRecipientScopedAndUseVersionedStableKeys() {
        NotificationService notifications = mock(NotificationService.class);
        VisitSessionNotificationListener listener = new VisitSessionNotificationListener(notifications);
        Instant appointment = Instant.parse("2099-10-02T11:00:00Z");

        listener.onVisitSessionEvent(event(VisitSessionNotificationEvent.Type.SCHEDULED, 2L, appointment));
        listener.onVisitSessionEvent(event(VisitSessionNotificationEvent.Type.RESCHEDULED, 3L, appointment));
        listener.onVisitSessionEvent(event(VisitSessionNotificationEvent.Type.CANCELLED, 4L, null));
        listener.onVisitSessionEvent(event(VisitSessionNotificationEvent.Type.ASSIGNED, 5L, appointment));
        listener.onVisitSessionEvent(new VisitSessionNotificationEvent(VisitSessionNotificationEvent.Type.REASSIGNED,
                500L, 10L, 41L, 40L, 6L, appointment, "Asia/Kolkata"));

        verify(notifications).createNotificationWithEventKey(eq(TargetRole.TENANT), eq("10"),
                eq("Visit session scheduled"), contains("2099-10-02T16:30:00+05:30"), isNull(),
                eq("VISIT_SESSION"), eq("info"), eq("VISIT_SESSION_SCHEDULED:500:v2:10"));
        verify(notifications).createNotificationWithEventKey(eq(TargetRole.GROUND_BOY), eq("40"),
                eq("Visit Session assigned"), contains("Visit Session 500"), isNull(),
                eq("VISIT_SESSION"), eq("info"), eq("VISIT_SESSION_ASSIGNED:500:v2:40"));
        verify(notifications).createNotificationWithEventKey(eq(TargetRole.TENANT), eq("10"),
                eq("Visit session rescheduled"), contains("2099-10-02T16:30:00+05:30"), isNull(),
                eq("VISIT_SESSION"), eq("info"), eq("VISIT_SESSION_RESCHEDULED:500:v3:10"));
        verify(notifications).createNotificationWithEventKey(eq(TargetRole.GROUND_BOY), eq("40"),
                eq("Visit Session time changed"), contains("2099-10-02T16:30:00+05:30"), isNull(),
                eq("VISIT_SESSION"), eq("info"), eq("VISIT_SESSION_RESCHEDULED:500:v3:40"));
        verify(notifications).createNotificationWithEventKey(eq(TargetRole.TENANT), eq("10"),
                eq("Visit session cancelled"), eq("Operations cancelled your Visit Session."), isNull(),
                eq("VISIT_SESSION"), eq("info"), eq("VISIT_SESSION_CANCELLED:500:v4:10"));
        verify(notifications).createNotificationWithEventKey(eq(TargetRole.GROUND_BOY), eq("40"),
                eq("Visit Session cancelled"), contains("cancelled by Operations"), isNull(),
                eq("VISIT_SESSION"), eq("info"), eq("VISIT_SESSION_CANCELLED:500:v4:40"));
        verify(notifications).createNotificationWithEventKey(eq(TargetRole.GROUND_BOY), eq("40"),
                eq("Visit Session assigned"), contains("Visit Session 500"), isNull(),
                eq("VISIT_SESSION"), eq("info"), eq("VISIT_SESSION_ASSIGNED:500:v5:40"));
        verify(notifications).createNotificationWithEventKey(eq(TargetRole.GROUND_BOY), eq("41"),
                eq("Visit Session assigned"), contains("Visit Session 500"), isNull(),
                eq("VISIT_SESSION"), eq("info"), eq("VISIT_SESSION_ASSIGNED:500:v6:41"));
        verify(notifications).createNotificationWithEventKey(eq(TargetRole.TENANT), eq("10"),
                eq("Ground Executive updated"), contains("different Ground Executive"), isNull(),
                eq("VISIT_SESSION"), eq("info"), eq("VISIT_SESSION_REASSIGNED:500:v6:10"));
        verify(notifications).createNotificationWithEventKey(eq(TargetRole.GROUND_BOY), eq("40"),
                eq("Visit Session reassigned"), contains("no longer assigned"), isNull(),
                eq("VISIT_SESSION"), eq("info"), eq("VISIT_SESSION_REASSIGNED_FROM:500:v6:40"));
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void notificationsAreRegisteredOnlyForAfterCommit() throws Exception {
        Method method = VisitSessionNotificationListener.class.getMethod("onVisitSessionEvent",
                VisitSessionNotificationEvent.class);
        TransactionalEventListener listener = method.getAnnotation(TransactionalEventListener.class);
        assertNotNull(listener);
        assertEquals(TransactionPhase.AFTER_COMMIT, listener.phase());
    }

    private static VisitSessionNotificationEvent event(VisitSessionNotificationEvent.Type type,
                                                        Long version, Instant scheduledAt) {
        return new VisitSessionNotificationEvent(type, 500L, 10L, 40L, null, version, scheduledAt, "Asia/Kolkata");
    }
}
