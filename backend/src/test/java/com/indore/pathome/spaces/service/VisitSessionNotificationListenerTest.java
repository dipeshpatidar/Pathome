package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VisitSessionNotificationListenerTest {
    @Test
    void sessionTransitionsAreQueuedWithStableKeysAndAuthorizationContext() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<Object[]> queued = new ArrayList<>();
        doAnswer(invocation -> {
            queued.add(java.util.Arrays.copyOfRange(invocation.getArguments(), 1,
                    invocation.getArguments().length));
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
        VisitSessionNotificationListener listener = new VisitSessionNotificationListener(jdbc);
        Instant appointment = Instant.parse("2099-10-02T11:00:00Z");

        listener.enqueueVisitSessionEvent(event(VisitSessionNotificationEvent.Type.SCHEDULED, 2L, appointment));
        listener.enqueueVisitSessionEvent(event(VisitSessionNotificationEvent.Type.RESCHEDULED, 3L, appointment));
        listener.enqueueVisitSessionEvent(event(VisitSessionNotificationEvent.Type.CANCELLED, 4L, null));
        listener.enqueueVisitSessionEvent(event(VisitSessionNotificationEvent.Type.ASSIGNED, 5L, appointment));
        listener.enqueueVisitSessionEvent(new VisitSessionNotificationEvent(VisitSessionNotificationEvent.Type.REASSIGNED,
                500L, 10L, 41L, 40L, 6L, appointment, "Asia/Kolkata"));
        listener.enqueueVisitSessionEvent(event(VisitSessionNotificationEvent.Type.ITINERARY_CHANGED, 7L, null));

        assertEquals(11, queued.size());
        assertQueued(queued, "VISIT_SESSION_SCHEDULED:500:v2:10", 10L, "TENANT", "RECIPIENT", null);
        assertQueued(queued, "VISIT_SESSION_ASSIGNED:500:v2:40", 40L, "GROUND_BOY", "OPERATIONS_SESSION", 500L);
        assertQueued(queued, "VISIT_SESSION_RESCHEDULED:500:v3:10", 10L, "TENANT", "RECIPIENT", null);
        assertQueued(queued, "VISIT_SESSION_RESCHEDULED:500:v3:40", 40L, "GROUND_BOY", "OPERATIONS_SESSION", 500L);
        assertQueued(queued, "VISIT_SESSION_CANCELLED:500:v4:10", 10L, "TENANT", "RECIPIENT", null);
        assertQueued(queued, "VISIT_SESSION_CANCELLED:500:v4:40", 40L, "GROUND_BOY", "OPERATIONS_SESSION", 500L);
        assertQueued(queued, "VISIT_SESSION_ASSIGNED:500:v5:40", 40L, "GROUND_BOY", "OPERATIONS_SESSION", 500L);
        assertQueued(queued, "VISIT_SESSION_REASSIGNED:500:v6:10", 10L, "TENANT", "RECIPIENT", null);
        assertQueued(queued, "VISIT_SESSION_ASSIGNED:500:v6:41", 41L, "GROUND_BOY", "OPERATIONS_SESSION", 500L);
        assertQueued(queued, "VISIT_SESSION_REASSIGNED_FROM:500:v6:40", 40L, "GROUND_BOY", "OPERATIONS_SESSION", 500L);
        assertQueued(queued, "VISIT_SESSION_ITINERARY_CHANGED:500:v7:40", 40L, "GROUND_BOY", "OPERATIONS_SESSION", 500L);
        assertTrue(queuedRow(queued, "VISIT_SESSION_SCHEDULED:500:v2:10")[5].toString()
                .contains("2099-10-02T16:30:00+05:30 (Asia/Kolkata)"));
        verify(jdbc, times(11)).update(anyString(), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void enqueueListenerRunsBeforeCommitAndHasNoSecondDeliveryListener() throws Exception {
        Method method = VisitSessionNotificationListener.class.getMethod("enqueueVisitSessionEvent",
                VisitSessionNotificationEvent.class);
        TransactionalEventListener listener = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(listener);
        assertEquals(TransactionPhase.BEFORE_COMMIT, listener.phase());
        assertEquals(1, java.util.Arrays.stream(VisitSessionNotificationListener.class.getMethods())
                .filter(candidate -> candidate.isAnnotationPresent(TransactionalEventListener.class)).count());
    }

    private static void assertQueued(List<Object[]> rows, String eventKey, Long recipient,
            String role, String authorizationClass, Long sessionId) {
        Object[] row = queuedRow(rows, eventKey);
        assertEquals(recipient, row[1]);
        assertEquals(role, row[2]);
        String eventType = eventKey.substring("VISIT_SESSION_".length(), eventKey.indexOf(':'));
        assertEquals(eventType, row[3]);
        assertEquals(authorizationClass, row[6]);
        assertEquals(sessionId, row[7]);
    }

    private static Object[] queuedRow(List<Object[]> rows, String eventKey) {
        return rows.stream().filter(row -> Objects.equals(eventKey, row[0])).findFirst()
                .orElseThrow(() -> new AssertionError("Outbox event was not queued: " + eventKey));
    }

    private static VisitSessionNotificationEvent event(VisitSessionNotificationEvent.Type type,
            Long version, Instant scheduledAt) {
        return new VisitSessionNotificationEvent(type, 500L, 10L, 40L, null, version, scheduledAt, "Asia/Kolkata");
    }
}
