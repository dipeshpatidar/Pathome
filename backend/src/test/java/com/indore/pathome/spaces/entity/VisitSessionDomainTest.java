package com.indore.pathome.spaces.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class VisitSessionDomainTest {
    @Test
    void policyHasExpectedDefaultsAndRejectsInvalidValues() {
        VisitPolicy policy = new VisitPolicy();
        assertEquals(5, policy.getFreeVisitSessionsDefault());
        assertEquals(60, policy.getMaxVisitSessionDurationMinutes());

        policy.setFreeVisitSessionsDefault(-1);
        assertThrows(IllegalStateException.class, policy::onCreate);
        policy.setFreeVisitSessionsDefault(0);
        policy.setMaxVisitSessionDurationMinutes(0);
        assertThrows(IllegalStateException.class, policy::onCreate);
    }

    @Test
    void sessionRequiresTenantCityAndConsistentScheduleAndAssignment() {
        VisitSession session = new VisitSession();
        session.setTenant(new User());
        session.setCity("Sample City");
        assertEquals(VisitSessionStatus.DRAFT, session.getStatus());
        assertEquals(0L, session.getVersion());
        assertDoesNotThrow(session::onCreate);

        session.setScheduledAt(Instant.parse("2026-10-02T10:00:00Z"));
        assertThrows(IllegalStateException.class, session::onCreate);
        session.setZoneId("UTC");
        assertDoesNotThrow(session::onCreate);

        session.setRepresentative(new User());
        assertThrows(IllegalStateException.class, session::onCreate);
        session.setAssignedAt(Instant.parse("2026-10-02T09:00:00Z"));
        assertDoesNotThrow(session::onCreate);

        session.setStartedAt(Instant.parse("2026-10-02T10:00:00Z"));
        session.setExpiresAt(Instant.parse("2026-10-02T09:59:59Z"));
        assertThrows(IllegalStateException.class, session::onCreate);
        session.setStartedAt(null);
        session.setExpiresAt(null);

        session.setDurationSnapshotMinutes(-1);
        assertThrows(IllegalStateException.class, session::onCreate);
    }

    @Test
    void sessionItemStartsUnconfirmedAndRequiresPositivePositionAndConfirmationFacts() {
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(new VisitSession());
        item.setListing(mock(Listing.class));
        item.setPosition(1);
        assertEquals(VisitSessionItemConfirmationStatus.PENDING, item.getConfirmationStatus());
        assertDoesNotThrow(item::onCreate);

        item.setPosition(0);
        assertThrows(IllegalStateException.class, item::onCreate);
        item.setPosition(1);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
        assertThrows(IllegalStateException.class, item::onCreate);
    }
}
