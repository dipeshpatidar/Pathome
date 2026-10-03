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
    void scheduledReservationEndMustMatchPlannedDuration() {
        VisitSession session = new VisitSession();
        session.setTenant(new User());
        session.setCity("Sample City");
        session.setStatus(VisitSessionStatus.SCHEDULED);
        session.setScheduledAt(Instant.parse("2026-10-02T10:00:00Z"));
        session.setZoneId("UTC");
        session.setRepresentative(new User());
        session.setAssignedAt(Instant.parse("2026-10-02T09:00:00Z"));
        session.setDurationSnapshotMinutes(30);
        session.setReservedEndAt(Instant.parse("2026-10-02T10:30:00Z"));

        assertDoesNotThrow(session::onCreate);
        session.setReservedEndAt(Instant.parse("2026-10-02T10:31:00Z"));
        assertThrows(IllegalStateException.class, session::onCreate);
    }

    @Test
    void startedVisitUsesActualExecutionEndWhilePreservingPlannedDurationSnapshot() {
        Instant scheduled = Instant.parse("2026-10-02T10:00:00Z");
        VisitSession session = new VisitSession();
        session.setTenant(new User());
        session.setCity("Sample City");
        session.setStatus(VisitSessionStatus.STARTED);
        session.setScheduledAt(scheduled);
        session.setZoneId("UTC");
        session.setRepresentative(new User());
        session.setAssignedAt(scheduled.minusSeconds(300));
        session.setDurationSnapshotMinutes(60);
        session.setReservedEndAt(scheduled.plusSeconds(90 * 60L));
        session.setStartedAt(scheduled.plusSeconds(30 * 60L));
        session.setExecutionDurationSnapshotMinutes(60);
        session.setExpectedEndAt(scheduled.plusSeconds(90 * 60L));

        assertDoesNotThrow(session::onCreate);
        session.setReservedEndAt(session.getExpectedEndAt().plusSeconds(60));
        assertThrows(IllegalStateException.class, session::onCreate);
    }

    @Test
    void sessionItemStartsUnconfirmedAndRequiresPositivePositionAndConfirmationFacts() {
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(new VisitSession());
        item.setListing(mock(Listing.class));
        item.setPosition(1);
        item.setSourceRequest(new PropertyVisitRequest());
        item.setOrigin(VisitSessionItemOrigin.TENANT_REQUESTED);
        assertEquals(VisitSessionItemConfirmationStatus.PENDING, item.getConfirmationStatus());
        assertDoesNotThrow(item::onCreate);

        item.setPosition(0);
        assertThrows(IllegalStateException.class, item::onCreate);
        item.setPosition(1);
        item.setConfirmationStatus(VisitSessionItemConfirmationStatus.CONFIRMED);
        assertThrows(IllegalStateException.class, item::onCreate);
    }

    @Test
    void itemAcceptsEachTruthfulProvenanceCombination() {
        VisitSessionItem direct = itemWithProvenance(
                new PropertyVisitRequest(), null, VisitSessionItemOrigin.TENANT_REQUESTED);
        VisitSessionItem oeAdded = itemWithProvenance(
                null, new PropertyVisitRequest(), VisitSessionItemOrigin.OE_ADDED);
        VisitSessionItem lessorSuggested = itemWithProvenance(
                null, new PropertyVisitRequest(), VisitSessionItemOrigin.LESSOR_SUGGESTED);

        assertDoesNotThrow(direct::onCreate);
        assertDoesNotThrow(oeAdded::onCreate);
        assertDoesNotThrow(lessorSuggested::onCreate);
    }

    @Test
    void newItemsRejectUnknownOrContradictoryProvenanceButLegacyRowsCanBeUpdated() {
        VisitSessionItem unknownNewItem = itemWithProvenance(null, null, null);
        VisitSessionItem missingDirectRequest = itemWithProvenance(null, null, VisitSessionItemOrigin.TENANT_REQUESTED);
        VisitSessionItem missingDerivedRequest = itemWithProvenance(null, null, VisitSessionItemOrigin.OE_ADDED);
        VisitSessionItem lessorWithoutDerivedRequest = itemWithProvenance(null, null, VisitSessionItemOrigin.LESSOR_SUGGESTED);
        VisitSessionItem bothRequests = itemWithProvenance(
                new PropertyVisitRequest(), new PropertyVisitRequest(), VisitSessionItemOrigin.OE_ADDED);
        VisitSessionItem derivedAsDirect = itemWithProvenance(
                null, new PropertyVisitRequest(), VisitSessionItemOrigin.TENANT_REQUESTED);
        VisitSessionItem directAsDerived = itemWithProvenance(
                new PropertyVisitRequest(), null, VisitSessionItemOrigin.OE_ADDED);

        assertThrows(IllegalStateException.class, unknownNewItem::onCreate);
        assertThrows(IllegalStateException.class, missingDirectRequest::onCreate);
        assertThrows(IllegalStateException.class, missingDerivedRequest::onCreate);
        assertThrows(IllegalStateException.class, lessorWithoutDerivedRequest::onCreate);
        assertThrows(IllegalStateException.class, bothRequests::onCreate);
        assertThrows(IllegalStateException.class, derivedAsDirect::onCreate);
        assertThrows(IllegalStateException.class, directAsDerived::onCreate);

        VisitSessionItem legacyItem = itemWithProvenance(null, null, null);
        assertDoesNotThrow(legacyItem::onUpdate);
    }

    @Test
    void itemExposesIndependentRequestProvenanceAndOrigin() {
        PropertyVisitRequest request = new PropertyVisitRequest();
        VisitSessionItem item = itemWithProvenance(null, request, VisitSessionItemOrigin.LESSOR_SUGGESTED);

        assertNull(item.getSourceRequest());
        assertSame(request, item.getDerivedFromRequest());
        assertEquals(VisitSessionItemOrigin.LESSOR_SUGGESTED, item.getOrigin());
    }

    private static VisitSessionItem itemWithProvenance(
            PropertyVisitRequest source, PropertyVisitRequest derived, VisitSessionItemOrigin origin) {
        VisitSessionItem item = new VisitSessionItem();
        item.setSession(new VisitSession());
        item.setListing(mock(Listing.class));
        item.setPosition(1);
        item.setSourceRequest(source);
        item.setDerivedFromRequest(derived);
        item.setOrigin(origin);
        return item;
    }
}
