package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import com.indore.pathome.spaces.dto.AvailabilityStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @Test
    void parsesFloorAndTotalFloorsVariationsAccurately() {
        // 1. "Located on the 3rd floor of a 7-floor building."
        ParsedPropertyDTO dto1 = parser.parse("2 BHK Flat in Bhawarkuan. Located on the 3rd floor of a 7-floor building.");
        assertEquals(3, dto1.getFloor());
        assertEquals(7, dto1.getTotalFloors());

        // 2. "3rd floor"
        ParsedPropertyDTO dto2 = parser.parse("3rd floor 2 BHK flat in Vijay Nagar rent 20000");
        assertEquals(3, dto2.getFloor());
        assertNull(dto2.getTotalFloors());

        // 3. "5th floor out of 12 floors"
        ParsedPropertyDTO dto3 = parser.parse("3 BHK flat in Nipania 5th floor out of 12 floors rent 35000");
        assertEquals(5, dto3.getFloor());
        assertEquals(12, dto3.getTotalFloors());

        // 4. "Floor 4 of 10"
        ParsedPropertyDTO dto4 = parser.parse("Floor 4 of 10 2 BHK flat in Rau rent 15000");
        assertEquals(4, dto4.getFloor());
        assertEquals(10, dto4.getTotalFloors());

        // 5. "Ground floor"
        ParsedPropertyDTO dto5 = parser.parse("Ground floor 1 BHK in Saket Nagar rent 12000");
        assertEquals(0, dto5.getFloor());
        assertNull(dto5.getTotalFloors());

        // 6. "1st floor of 8"
        ParsedPropertyDTO dto6 = parser.parse("1st floor of 8, 2 BHK in Mahalaxmi Nagar");
        assertEquals(1, dto6.getFloor());
        assertEquals(8, dto6.getTotalFloors());

        // 7. "on the 2nd floor of a 6-floor building"
        ParsedPropertyDTO dto7 = parser.parse("Located on the 2nd floor of a 6-floor building in Palasia");
        assertEquals(2, dto7.getFloor());
        assertEquals(6, dto7.getTotalFloors());
    }

    @Test
    void parsesPreferredTenantVariationsAndEnforcesAnyExclusivity() {
        // 1. "Preferred for family or working professionals."
        ParsedPropertyDTO dto1 = parser.parse("2 BHK in Bhawarkuan. Preferred for family or working professionals.");
        assertEquals(List.of("FAMILY", "WORKING_PROFESSIONALS"), dto1.getPreferredTenants());

        // 2. "Preferred for family."
        ParsedPropertyDTO dto2 = parser.parse("3 BHK in Vijay Nagar. Preferred for family.");
        assertEquals(List.of("FAMILY"), dto2.getPreferredTenants());

        // 3. "Preferred for bachelors."
        ParsedPropertyDTO dto3 = parser.parse("1 RK in Geeta Bhawan. Preferred for bachelors.");
        assertEquals(List.of("BACHELORS"), dto3.getPreferredTenants());

        // 4. "Preferred for students."
        ParsedPropertyDTO dto4 = parser.parse("1 BHK near DAVV. Preferred for students.");
        assertEquals(List.of("STUDENTS"), dto4.getPreferredTenants());

        // 5. "Students allowed."
        ParsedPropertyDTO dto5 = parser.parse("2 BHK in Bhawarkua. Students allowed.");
        assertEquals(List.of("STUDENTS"), dto5.getPreferredTenants());

        // 6. "No preference."
        ParsedPropertyDTO dto6 = parser.parse("2 BHK in Rau. No preference.");
        assertEquals(List.of("ANY"), dto6.getPreferredTenants());

        // 7. Normalization: If ANY is present with specific tenant types, ANY is excluded
        ParsedPropertyDTO dto7 = parser.parse("2 BHK flat open for all, preferred for family.");
        assertEquals(List.of("FAMILY"), dto7.getPreferredTenants());
    }

    @ParameterizedTest(name = "Floor phrase: {0} -> floor: {1}")
    @CsvSource(textBlock = """
        Ground floor, 0
        ground floor, 0
        GF, 0
        G/F, 0
        1st floor, 1
        first floor, 1
        1 floor flat, 1
        2nd floor, 2
        second floor, 2
        2 floor flat, 2
        3rd floor, 3
        third floor, 3
        3 floor flat, 3
        4th floor, 4
        5th floor, 5
        6th floor, 6
        10th floor, 10
        11th floor, 11
        12th floor, 12
        Located on the 3rd floor, 3
        Located at 3rd floor, 3
        Flat is on 3rd floor, 3
        Flat on 3rd floor, 3
        On the third floor, 3
        At third floor, 3
        3rd floor flat, 3
        3rd-floor flat, 3
        Property is on floor 3, 3
        Property at floor 3, 3
        Floor 3, 3
        Floor no 3, 3
        Floor number 3, 3
        Floor: 3, 3
        Third floor, 3
    """)
    void testUnitFloorPhrasingVariations(String phrase, int expectedFloor) {
        ParsedPropertyDTO dto = parser.parse("2 BHK Flat in Vijay Nagar. " + phrase + ".");
        assertEquals(expectedFloor, dto.getFloor());
    }

    @ParameterizedTest(name = "Combined floor/total: {0} -> floor: {1}, total: {2}")
    @CsvSource(delimiter = '|', textBlock = """
        3rd floor of a 7-floor building | 3 | 7
        3rd floor of 7 floors | 3 | 7
        3rd floor out of 7 | 3 | 7
        3rd floor out of 7 floors | 3 | 7
        3rd floor in a 7 floor building | 3 | 7
        3rd floor in a 7-storey building | 3 | 7
        3rd floor in a 7-story building | 3 | 7
        Located on 3rd floor, building has 7 floors | 3 | 7
        Located on 3rd floor. Total floors 7 | 3 | 7
        Floor 3 of 7 | 3 | 7
        Floor 3/7 | 3 | 7
        3rd floor / 7 floors | 3 | 7
        Floor: 3, Total Floors: 7 | 3 | 7
        Floor number 3, total floor 7 | 3 | 7
    """)
    void testCombinedFloorAndTotalFloors(String phrase, int expectedFloor, int expectedTotal) {
        ParsedPropertyDTO dto = parser.parse("2 BHK Flat in Bhawarkuan. " + phrase + ".");
        assertEquals(expectedFloor, dto.getFloor());
        assertEquals(expectedTotal, dto.getTotalFloors());
    }

    @ParameterizedTest(name = "Building height: {0} -> totalFloors: {1}")
    @CsvSource(textBlock = """
        Total floors 7, 7
        Total floor 7, 7
        Total floors: 7, 7
        7 total floors, 7
        Building has 7 floors, 7
        Building is 7 floors, 7
        7 floor building, 7
        7-floor building, 7
        7 storey building, 7
        7-storey building, 7
        7 story building, 7
        7-story building, 7
        Seven floor building, 7
        Seven floors, 7
        Seven storey building, 7
    """)
    void testBuildingHeightOnlyDoesNotPopulateFloor(String phrase, int expectedTotal) {
        ParsedPropertyDTO dto = parser.parse("2 BHK Flat in Vijay Nagar. " + phrase + ".");
        assertEquals(expectedTotal, dto.getTotalFloors());
        assertNull(dto.getFloor(), "Building height must NOT accidentally populate unit floor");
    }

    @ParameterizedTest(name = "G+N: {0} -> totalFloors: {1}")
    @CsvSource(textBlock = """
        G+1, 2
        G + 1, 2
        G+2, 3
        G + 2, 3
        G+3, 4
        Ground+1, 2
        Ground + 1, 2
        Ground+2, 3
        Ground + 2, 3
        Ground plus 2, 3
        Ground plus two, 3
        ground and 2 floors, 3
        ground and two floors, 3
    """)
    void testIndianGPlusNNotation(String phrase, int expectedTotal) {
        ParsedPropertyDTO dto = parser.parse("Independent house " + phrase + " in Saket.");
        assertEquals(expectedTotal, dto.getTotalFloors());
        assertNull(dto.getFloor(), "G+N notation must describe structure height, NOT unit floor");
    }

    @Test
    void testHouseAndVillaStructureSemanticsWithUnitFloor() {
        ParsedPropertyDTO dto1 = parser.parse("3 BHK independent house, Ground + 2 in Saket.");
        assertNull(dto1.getFloor());
        assertEquals(3, dto1.getTotalFloors());

        ParsedPropertyDTO dto2 = parser.parse("2 BHK on first floor in G+2 house in Saket.");
        assertEquals(1, dto2.getFloor());
        assertEquals(3, dto2.getTotalFloors());

        ParsedPropertyDTO dto3 = parser.parse("First-floor portion available in G+2 house in Saket.");
        assertEquals(1, dto3.getFloor());
        assertEquals(3, dto3.getTotalFloors());
    }

    @Test
    void testTopFloorExtraction() {
        ParsedPropertyDTO dto1 = parser.parse("top floor of a 5-floor building");
        assertEquals(5, dto1.getFloor());
        assertEquals(5, dto1.getTotalFloors());

        ParsedPropertyDTO dto2 = parser.parse("5th and top floor in Apollo DB City");
        assertEquals(5, dto2.getFloor());

        ParsedPropertyDTO dto3 = parser.parse("Flat is on top floor");
        assertNull(dto3.getFloor(), "top floor without known total floor count must NOT invent a number");
    }

    @Test
    void testLowerGroundAndBasementReturnsNull() {
        assertNull(parser.parse("Flat on basement floor").getFloor());
        assertNull(parser.parse("Unit on lower ground floor").getFloor());
        assertNull(parser.parse("Office on LG floor").getFloor());
        assertNull(parser.parse("Flat on upper ground floor").getFloor());
        assertNull(parser.parse("Flat on UG floor").getFloor());
    }

    @Test
    void testFloorFalsePositiveProtection() {
        String prompt = "3 BHK Flat in Scheme 140, Tower 7, Block 3, Flat 302, House No 7, Sector 3. "
                + "Area 1200 sqft, 2 bedrooms, 3 bathrooms, 2 balconies, parking for 2 cars. "
                + "7 days notice, available from 7 October. Rent ₹25,000, deposit ₹50,000.";
        ParsedPropertyDTO dto = parser.parse(prompt);
        assertNull(dto.getFloor(), "Unrelated numbers must not populate floor");
        assertNull(dto.getTotalFloors(), "Unrelated numbers must not populate totalFloors");
    }

    @Test
    void testPreferredTenantPhrasesAndCategories() {
        // Family variants
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. Family preferred.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. Preferred family.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. Only family.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. Family only.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. For family.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. For families.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. Suitable for family.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. Ideal for family.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. Looking for family.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. Tenant preference: family.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("2 BHK. Tenant preference - family.").getPreferredTenants());

        // Working Professionals variants
        assertEquals(List.of("WORKING_PROFESSIONALS"), parser.parse("1 BHK. Preferred for working professionals.").getPreferredTenants());
        assertEquals(List.of("WORKING_PROFESSIONALS"), parser.parse("1 BHK. Ideal for working professionals.").getPreferredTenants());
        assertEquals(List.of("WORKING_PROFESSIONALS"), parser.parse("1 BHK. Suitable for working professionals.").getPreferredTenants());
        assertEquals(List.of("WORKING_PROFESSIONALS"), parser.parse("1 BHK. Salaried professionals preferred.").getPreferredTenants());
        assertEquals(List.of("WORKING_PROFESSIONALS"), parser.parse("1 BHK. Corporate employees allowed.").getPreferredTenants());
        assertEquals(List.of("WORKING_PROFESSIONALS"), parser.parse("1 BHK. Working people preferred.").getPreferredTenants());

        // Bachelors variants
        assertEquals(List.of("BACHELORS"), parser.parse("1 RK. Available for bachelors.").getPreferredTenants());
        assertEquals(List.of("BACHELORS"), parser.parse("1 RK. Bachelors allowed.").getPreferredTenants());
        assertEquals(List.of("BACHELORS"), parser.parse("1 RK. Male bachelors preferred.").getPreferredTenants());
        assertEquals(List.of("BACHELORS"), parser.parse("1 RK. Female bachelors only.").getPreferredTenants());

        // Students variants
        assertEquals(List.of("STUDENTS"), parser.parse("1 BHK. Students allowed.").getPreferredTenants());
        assertEquals(List.of("STUDENTS"), parser.parse("1 BHK. College students preferred.").getPreferredTenants());
        assertEquals(List.of("STUDENTS"), parser.parse("1 BHK. Available for students.").getPreferredTenants());

        // ANY variants
        assertEquals(List.of("ANY"), parser.parse("2 BHK. No preference.").getPreferredTenants());
        assertEquals(List.of("ANY"), parser.parse("2 BHK. Any tenant.").getPreferredTenants());
        assertEquals(List.of("ANY"), parser.parse("2 BHK. Open to all.").getPreferredTenants());
        assertEquals(List.of("ANY"), parser.parse("2 BHK. All welcome.").getPreferredTenants());
        assertEquals(List.of("ANY"), parser.parse("2 BHK. Everyone welcome.").getPreferredTenants());
        assertEquals(List.of("ANY"), parser.parse("2 BHK. No restriction.").getPreferredTenants());
    }

    @Test
    void testMultipleTenantTypesWithConnectors() {
        assertEquals(List.of("FAMILY", "WORKING_PROFESSIONALS"), parser.parse("2 BHK. Family or working professionals.").getPreferredTenants());
        assertEquals(List.of("FAMILY", "BACHELORS"), parser.parse("2 BHK. Family / bachelors.").getPreferredTenants());
        assertEquals(List.of("FAMILY", "STUDENTS"), parser.parse("2 BHK. Family & students.").getPreferredTenants());
        assertEquals(List.of("FAMILY", "BACHELORS", "WORKING_PROFESSIONALS"), parser.parse("2 BHK. Families, bachelors and working professionals.").getPreferredTenants());
        assertEquals(List.of("FAMILY", "WORKING_PROFESSIONALS", "STUDENTS"), parser.parse("2 BHK. Preferred for family, working professionals or students.").getPreferredTenants());
    }

    @Test
    void testTenantNegationDoesNotProducePositiveCategory() {
        assertEquals(List.of("FAMILY"), parser.parse("Family only, no bachelors.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("Family only, bachelors not allowed.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("Family only, no students.").getPreferredTenants());
        assertEquals(List.of("FAMILY"), parser.parse("Family only, students not allowed.").getPreferredTenants());
        assertTrue(parser.parse("No bachelors allowed in this building.").getPreferredTenants().isEmpty());
        assertTrue(parser.parse("Bachelors not allowed.").getPreferredTenants().isEmpty());
        assertTrue(parser.parse("No students permitted.").getPreferredTenants().isEmpty());
        assertTrue(parser.parse("Students not allowed.").getPreferredTenants().isEmpty());
    }

    @Test
    void testTenantContextFalsePositiveProtection() {
        assertTrue(parser.parse("2 BHK near students hostel and market.").getPreferredTenants().isEmpty());
        assertTrue(parser.parse("3 BHK in working professionals area near IT Park.").getPreferredTenants().isEmpty());
        assertTrue(parser.parse("2 BHK with family restaurant nearby.").getPreferredTenants().isEmpty());
        assertTrue(parser.parse("Flat near family park and hospital.").getPreferredTenants().isEmpty());
    }
}
