package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeadContractTest {

    @Test
    void indianLocalNumbersAreNormalizedWithoutGuessingForeignNumbers() {
        assertEquals("+919876543210", LeadContract.normalizePhone("98765 43210"));
        assertEquals("+442071838750", LeadContract.normalizePhone("+44 20 7183 8750"));
        assertThrows(IllegalArgumentException.class, () -> LeadContract.normalizePhone("020 7183 8750"));
        assertThrows(IllegalArgumentException.class, () -> LeadContract.normalizePhone("98765 43210 ext 2"));
        assertThrows(IllegalArgumentException.class, () -> LeadContract.normalizePhone("+0123456789"));
    }

    @Test
    void budgetsMustBeFinitePositiveAndOrdered() {
        LeadContract.validateBudget(BigDecimal.valueOf(12000), BigDecimal.valueOf(18000));
        assertThrows(IllegalArgumentException.class,
                () -> LeadContract.validateBudget(BigDecimal.valueOf(18000), BigDecimal.valueOf(12000)));
        assertThrows(IllegalArgumentException.class,
                () -> LeadContract.validateBudget(BigDecimal.valueOf(-1), null));
    }

    @Test
    void followUpInstantAndZoneMustBeProvidedTogether() {
        ZoneId zone = LeadContract.validateFollowUp(Instant.parse("2026-10-22T04:30:00Z"), "Asia/Kolkata");
        assertEquals("Asia/Kolkata", zone.getId());
        assertThrows(IllegalArgumentException.class,
                () -> LeadContract.validateFollowUp(Instant.now(), null));
        assertThrows(IllegalArgumentException.class,
                () -> LeadContract.validateFollowUp(null, "Asia/Kolkata"));
        assertThrows(IllegalArgumentException.class,
                () -> LeadContract.validateFollowUp(Instant.now(), "Not/AZone"));
    }

    @Test
    void lifecycleAllowsOnlyTheApprovedTransitions() {
        LeadContract.requireTransition(LeadStatus.NEW, LeadStatus.QUALIFYING);
        LeadContract.requireTransition(LeadStatus.QUALIFYING, LeadStatus.QUALIFIED);
        LeadContract.requireTransition(LeadStatus.QUALIFYING, LeadStatus.CLOSED);
        LeadContract.requireTransition(LeadStatus.CLOSED, LeadStatus.QUALIFYING);
        assertThrows(VisitOperationsConflictException.class,
                () -> LeadContract.requireTransition(LeadStatus.NEW, LeadStatus.QUALIFIED));
        assertThrows(VisitOperationsConflictException.class,
                () -> LeadContract.requireTransition(LeadStatus.STALE, LeadStatus.QUALIFIED));
    }
}
