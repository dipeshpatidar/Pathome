package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadStatus;
import com.indore.pathome.spaces.dto.crm.LeadCaptureCommand;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    void budgetsMustFitNumericStorageWithoutRounding() {
        LeadContract.validateBudget(new BigDecimal("999999999999.99"), null);
        LeadContract.validateBudget(new BigDecimal("100.000"), new BigDecimal("1E+2"));
        assertThrows(IllegalArgumentException.class,
                () -> LeadContract.validateBudget(new BigDecimal("1.001"), null));
        assertThrows(IllegalArgumentException.class,
                () -> LeadContract.validateBudget(new BigDecimal("1000000000000"), null));
    }

    @Test
    void localityIdsMustBeExactPositiveSupportedIntegers() {
        assertEquals(List.of(1L, 2L), LeadContract.exactPositiveIds(
                List.of(1, "2", new BigInteger("2")), "preferredLocalityIds"));
        for (Object fractional : List.of(1.9d, 1.9f, new BigDecimal("1.9"), "1.9", "1e2")) {
            assertThrows(IllegalArgumentException.class,
                    () -> LeadContract.exactPositiveIds(Arrays.asList(fractional), "preferredLocalityIds"));
        }
        for (Object invalid : Arrays.asList(0, -1, null, new BigInteger("9223372036854775808"),
                "not-an-id", List.of(1))) {
            assertThrows(IllegalArgumentException.class,
                    () -> LeadContract.exactPositiveIds(Arrays.asList(invalid), "preferredLocalityIds"));
        }
        ArrayList<Integer> tooMany = new ArrayList<>();
        for (int i = 1; i <= 21; i++) tooMany.add(i);
        assertThrows(IllegalArgumentException.class,
                () -> LeadContract.exactPositiveIds(tooMany, "preferredLocalityIds"));
    }

    @Test
    void jacksonDoesNotCoerceFractionalLocalityIdsBeforeValidation() throws Exception {
        LeadCaptureCommand command = new ObjectMapper().readValue(
                "{\"preferredLocalityIds\":[1.9]}", LeadCaptureCommand.class);
        assertThrows(IllegalArgumentException.class, () -> LeadContract.exactPositiveIds(
                command.preferredLocalityIds(), "preferredLocalityIds"));
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
