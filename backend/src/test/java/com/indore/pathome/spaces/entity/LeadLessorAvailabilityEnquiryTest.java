package com.indore.pathome.spaces.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LeadLessorAvailabilityEnquiryTest {
    @Test
    void withdrawalDisputeAndDeclineCannotBeReopenedByLaterReports() {
        for (LeadLessorAvailabilityOutcome closure : java.util.List.of(
                LeadLessorAvailabilityOutcome.CUSTOMER_WITHDREW,
                LeadLessorAvailabilityOutcome.CUSTOMER_DISPUTED,
                LeadLessorAvailabilityOutcome.PROPERTY_DECLINED)) {
            LeadLessorAvailabilityEnquiry enquiry = enquiry();

            assertTrue(enquiry.recordOutcome(closure, 10L, mock(User.class),
                    Instant.parse("2026-10-27T10:01:00Z")));
            assertTrue(enquiry.isDisclosureClosed());
            assertFalse(enquiry.recordOutcome(
                    LeadLessorAvailabilityOutcome.LESSOR_REPORTED_AVAILABLE,
                    11L, mock(User.class), Instant.parse("2026-10-27T10:02:00Z")));

            assertEquals(closure, enquiry.getOutcome());
            assertEquals(10L, enquiry.getOutcomeNoteActivityId());
        }
    }

    @Test
    void ordinaryReportedOutcomeCanStillBeUpdatedWhileOpen() {
        LeadLessorAvailabilityEnquiry enquiry = enquiry();

        assertTrue(enquiry.recordOutcome(LeadLessorAvailabilityOutcome.NO_ANSWER,
                10L, mock(User.class), Instant.parse("2026-10-27T10:01:00Z")));
        assertTrue(enquiry.recordOutcome(
                LeadLessorAvailabilityOutcome.CALLBACK_REQUESTED,
                11L, mock(User.class), Instant.parse("2026-10-27T10:02:00Z")));

        assertEquals(LeadLessorAvailabilityOutcome.CALLBACK_REQUESTED, enquiry.getOutcome());
        assertFalse(enquiry.isDisclosureClosed());
    }

    private static LeadLessorAvailabilityEnquiry enquiry() {
        return new LeadLessorAvailabilityEnquiry(UUID.randomUUID(),
                mock(LeadRoutingQueue.class), 20L, 30L, mock(LeadSourceEvent.class),
                mock(User.class), mock(SupportedCity.class), mock(OperatingTeam.class),
                2L, 3L, 4L, Instant.parse("2026-10-27T10:00:00Z"),
                Instant.parse("2026-10-28T10:00:00Z"));
    }
}
