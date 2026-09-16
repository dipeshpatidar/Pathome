package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyParserServicePureTest {

    private final PropertyParserService parser = new PropertyParserService(null);

    @Test
    void parsesTheAdminPromptWithoutPersistentSideEffects() {
        String prompt = "2bhk flat on rent near bombay chemist infront of Infiniti hotel "
                + "mahalaxmi nagar rent is 30000 & the security deposit is 1+1 60000 "
                + "& the brokerage Fee is 15000 & possession date 20th of september west "
                + "facing Flat fully furnished flat Dipesh patidar owner 6263421859 & 3 bathroom";

        ParsedPropertyDTO dto = parser.parse(prompt);

        assertEquals("2 BHK", dto.getBhk());
        assertEquals("Flat", dto.getType());
        assertEquals("Mahalaxmi Nagar", dto.getSector());
        assertEquals("Indore", dto.getCity());
        assertEquals(30000.0, dto.getRentAmount());
        assertEquals("₹15,000", dto.getBrokerageVal());
        assertEquals("1+1 60000 Security Deposit", dto.getDepositVal());
        assertEquals("Dipesh Patidar", dto.getOwnerName());
        assertEquals("+91 62634 21859", dto.getOwnerPhone());
        assertEquals("West Facing", dto.getVastuFacing());
        assertEquals("Fully Furnished", dto.getFurnishingStatus());
        assertEquals("3 Baths", dto.getBathrooms());
        assertTrue(dto.isRequiresReview());
        assertTrue(dto.getMissingFields().contains("Pincode"));
        assertTrue(dto.getConflicts().isEmpty());
        assertTrue(!dto.isSavedToDatabase());
    }

    @Test
    void exposesConflictingRentsForAdministratorReview() {
        ParsedPropertyDTO dto = parser.parse(
                "2 BHK flat in Vijay Nagar rent 25000 and later rent 30000 owner +91 98260 12345");

        assertTrue(dto.isRequiresReview());
        assertTrue(dto.getConflicts().stream().anyMatch(value -> value.startsWith("Monthly Rent")));
    }

    @Test
    void resolvesRelativeAvailabilityToConcreteIndianCalendarDates() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-16T06:00:00Z"), ZoneId.of("Asia/Kolkata"));
        PropertyParserService dateAwareParser = new PropertyParserService(null, fixedClock);

        assertEquals("16 Nov 2026", dateAwareParser
                .parse("2 BHK in Vijay Nagar, available after 2 months").getPossessionDate());
        assertEquals("30 Sep 2026", dateAwareParser
                .parse("1 BHK, possession in 2 weeks").getPossessionDate());
        assertEquals("16 Nov 2026", dateAwareParser
                .parse("1 BHK, available 2 mahine baad").getPossessionDate());
        assertEquals("9 Nov 2026", dateAwareParser
                .parse("3 BHK in Palasia, available post Diwali").getPossessionDate());
        assertEquals("18 Oct 2028", dateAwareParser
                .parse("Flat available after Diwali 2028").getPossessionDate());
    }

    @Test
    void doesNotTreatDepositPaymentIntervalsAsAvailabilityDates() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-16T06:00:00Z"), ZoneId.of("Asia/Kolkata"));
        PropertyParserService dateAwareParser = new PropertyParserService(null, fixedClock);

        ParsedPropertyDTO dto = dateAwareParser
                .parse("2 BHK in Vijay Nagar, security deposit payable after 2 months");

        assertNull(dto.getPossessionDate());
    }

    @Test
    void parsesTheAdminBatchPromptWithTyposAndNaturalPhrasing() {
        String prompt = """
                2bhk flat on rent near bombay chemist infront of Infiniti hotel mahalaxmi nagar rent is 30000 & the security deposit is 1+1 60000 & the brokerage Fee is 15000 & possession date 20th of september west facing Flat fully furnished flat Dipesh patidar owner 6263421859 & 3 bathroom

                Next property

                1bhk flat on rent near opal homes chikatsak nagar mahalaxmi nagar 17000 rent 36000 securuity deposit 8500 brookerage & possession date is 25th of sep owner name dipesh patidar 8458888248 east dacing semi furnished brokerage is 7000&#x20;
                """;

        List<ParsedPropertyDTO> results = parser.parseBatch(prompt);

        assertEquals(2, results.size());

        ParsedPropertyDTO first = results.get(0);
        assertEquals("2 BHK", first.getBhk());
        assertEquals("Flat", first.getType());
        assertEquals("Mahalaxmi Nagar", first.getSector());
        assertEquals("Indore", first.getCity());
        assertEquals(30000.0, first.getRentAmount());
        assertEquals("1+1 60000 Security Deposit", first.getDepositVal());
        assertEquals("₹15,000", first.getBrokerageVal());
        assertEquals("3 Baths", first.getBathrooms());
        assertEquals("+91 62634 21859", first.getOwnerPhone());
        assertEquals("West Facing", first.getVastuFacing());
        assertEquals("Fully Furnished", first.getFurnishingStatus());

        ParsedPropertyDTO second = results.get(1);
        assertEquals("1 BHK", second.getBhk());
        assertEquals("Flat", second.getType());
        assertEquals("Indore", second.getCity());
        assertEquals(17000.0, second.getRentAmount());
        assertEquals("36000 Security Deposit", second.getDepositVal());
        assertEquals("+91 84588 88248", second.getOwnerPhone());
        assertEquals("East Facing", second.getVastuFacing());
        assertEquals("Semi Furnished", second.getFurnishingStatus());
        assertTrue(second.getConflicts().stream().anyMatch(conflict -> conflict.contains("Brokerage")));
    }
}
