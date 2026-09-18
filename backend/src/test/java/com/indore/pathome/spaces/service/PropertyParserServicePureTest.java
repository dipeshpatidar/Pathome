package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import com.indore.pathome.spaces.dto.AvailabilityStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

        ParsedPropertyDTO relative = dateAwareParser.parse("2 BHK available after 3 months");
        assertEquals(AvailabilityStatus.AVAILABLE_FROM_DATE, relative.getAvailabilityStatus());
        assertEquals(LocalDate.of(2026, 12, 16), relative.getAvailableFrom());
    }

    @Test
    void separatesImmediateAvailabilityFromAConcretePossessionDate() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-16T06:00:00Z"), ZoneId.of("Asia/Kolkata"));
        PropertyParserService dateAwareParser = new PropertyParserService(null, fixedClock);

        ParsedPropertyDTO immediate = dateAwareParser.parse("2 BHK flat ready to move");
        assertEquals("Ready To Move", immediate.getPossessionDate());
        assertEquals(AvailabilityStatus.READY_NOW, immediate.getAvailabilityStatus());
        assertEquals(LocalDate.of(2026, 9, 16), immediate.getAvailableFrom());

        ParsedPropertyDTO dated = dateAwareParser.parse(
                "2 BHK flat ready to move on 15 Nov 2026");
        assertEquals("15 Nov 2026", dated.getPossessionDate());
        assertEquals(AvailabilityStatus.AVAILABLE_FROM_DATE, dated.getAvailabilityStatus());
        assertEquals(LocalDate.of(2026, 11, 15), dated.getAvailableFrom());

        ParsedPropertyDTO clarifiedDate = dateAwareParser.parse(
                "2 BHK flat ready to move, possession will be on 20 Nov 2026");
        assertEquals(AvailabilityStatus.AVAILABLE_FROM_DATE, clarifiedDate.getAvailabilityStatus());
        assertEquals(LocalDate.of(2026, 11, 20), clarifiedDate.getAvailableFrom());
    }

    @Test
    void resolvesDatesWithoutYearsToTheNextCalendarOccurrence() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-16T06:00:00Z"), ZoneId.of("Asia/Kolkata"));
        PropertyParserService dateAwareParser = new PropertyParserService(null, fixedClock);

        ParsedPropertyDTO upcoming = dateAwareParser.parse("possession date 20th of September");
        assertEquals(LocalDate.of(2026, 9, 20), upcoming.getAvailableFrom());
        assertEquals("20 Sep 2026", upcoming.getPossessionDate());

        ParsedPropertyDTO nextYear = dateAwareParser.parse("available from 15th September");
        assertEquals(LocalDate.of(2027, 9, 15), nextYear.getAvailableFrom());
        assertEquals("15 Sep 2027", nextYear.getPossessionDate());
    }

    @Test
    void flagsAnExplicitPastPossessionDateForReview() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-16T06:00:00Z"), ZoneId.of("Asia/Kolkata"));
        PropertyParserService dateAwareParser = new PropertyParserService(null, fixedClock);

        ParsedPropertyDTO dated = dateAwareParser.parse("possession on 15 Aug 2026");

        assertEquals(LocalDate.of(2026, 8, 15), dated.getAvailableFrom());
        assertTrue(dated.getConflicts().contains("Possession date is in the past: 15 Aug 2026"));
        assertTrue(dated.isRequiresReview());
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
