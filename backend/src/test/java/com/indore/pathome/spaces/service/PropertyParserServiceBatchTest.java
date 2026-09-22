package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.AvailabilityStatus;
import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.repository.LocalityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PropertyParserServiceBatchTest {

    @Mock
    private LocalityRepository localityRepository;

    private PropertyParserService parserService;

    @BeforeEach
    void setUp() {
        when(localityRepository.findAll()).thenReturn(Collections.emptyList());
        parserService = new PropertyParserService(localityRepository);
        parserService.initCache();
    }

    @Test
    @DisplayName("Should split and parse numbered prompts (1), 2., 3))")
    void testNumberedPromptsSplitting() {
        String multiPrompt = """
                1) 2 BHK Flat in Vijay Nagar, Rent: 18000, Owner: +91 98260 11111
                2) 3 BHK House in Palasia, Rent 35000, Phone: +91 98260 22222
                3) 1 RK Flat in Bhawarkua, Rent 8000, Call: +91 98260 33333
                """;

        List<ParsedPropertyDTO> results = parserService.parseBatch(multiPrompt);

        assertNotNull(results);
        assertEquals(3, results.size());

        // First listing
        assertEquals("2 BHK", results.get(0).getBhk());
        assertEquals("Vijay Nagar", results.get(0).getSector());
        assertEquals(18000.0, results.get(0).getRentAmount());
        assertEquals("+91 98260 11111", results.get(0).getOwnerPhone());

        // Second listing
        assertEquals("3 BHK", results.get(1).getBhk());
        assertEquals("Palasia", results.get(1).getSector());
        assertEquals(35000.0, results.get(1).getRentAmount());
        assertEquals("+91 98260 22222", results.get(1).getOwnerPhone());

        // Third listing
        assertEquals("1 RK Studio", results.get(2).getBhk());
        assertEquals("Bhawarkua", results.get(2).getSector());
        assertEquals(8000.0, results.get(2).getRentAmount());
        assertEquals("+91 98260 33333", results.get(2).getOwnerPhone());
    }

    @Test
    @DisplayName("Should split voice transcription prompts using 'next property' cue")
    void testVoiceTranscriptionSplitting() {
        String voicePrompt = "2 BHK in Vijay Nagar rent 18 hazar phone +91 98260 12345 next property 3 BHK in Palasia rent 35 hazar owner +91 98260 54321";

        List<ParsedPropertyDTO> results = parserService.parseBatch(voicePrompt);

        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals("2 BHK", results.get(0).getBhk());
        assertEquals(18000.0, results.get(0).getRentAmount());
        assertEquals("3 BHK", results.get(1).getBhk());
        assertEquals(35000.0, results.get(1).getRentAmount());
    }

    @Test
    @DisplayName("Should split spoken ordinal property cues and retain only valid values for each listing")
    void testSpokenSecondPropertyCueAndSpeechNumberCorrections() {
        String voicePrompt = "add two properties one is one BHK near by Mahalaxmi Mandir at Mahalaxmi Nagar "
                + "having rent of 8000 and the deposit of 16000 with brokerage of 4000 and the bathrooms are to "
                + "and the owner number is +91 123456789 and the second property is nearby Bombay Hospital it is a "
                + "2BHK flat having three bathrooms with the rent of 17000 and the brokerage of 8000 with deposit of 30 "
                + "and the owner number is +913 456 9810";

        List<ParsedPropertyDTO> results = parserService.parseBatch(voicePrompt);

        assertEquals(2, results.size());

        ParsedPropertyDTO first = results.get(0);
        assertEquals("1 BHK", first.getBhk());
        assertEquals("Mahalaxmi Nagar", first.getSector());
        assertEquals(8000.0, first.getRentAmount());
        assertEquals("16000 Security Deposit", first.getDepositVal());
        assertEquals("₹4,000", first.getBrokerageVal());
        assertEquals("2 Baths", first.getBathrooms());
        assertEquals("Not Specified", first.getOwnerPhone(), "A nine-digit number must never be accepted as a phone");

        ParsedPropertyDTO second = results.get(1);
        assertEquals("2 BHK", second.getBhk());
        assertEquals("Flat", second.getType());
        assertEquals(17000.0, second.getRentAmount());
        assertEquals("₹8,000", second.getBrokerageVal());
        assertEquals("3 Baths", second.getBathrooms());
        assertNull(second.getDepositVal(), "A bare '30' must not be guessed as a monetary deposit");
        assertEquals("Not Specified", second.getOwnerPhone(), "Malformed country-code spacing must not become an owner phone");
    }

    @Test
    @DisplayName("Should parse the reported noisy dictation without inventing values or leaking a late correction")
    void testReportedNoisyDictationAndInvalidLateCorrection() {
        String voicePrompt = "list the property with 2BHK having rent of 18000 and brokerage of around the 8000 "
                + "and deposits of 32000 with two bathrooms and it is near by Bombay Hospital Vijayanagar "
                + "and in front of radiation blue and add\n\nNext property\n\n"
                + "that is 3 BHK flat with 32000 rent 15000 and 32000 15000 brokerage and and 60000 deposit "
                + "having two bathrooms in front of Bombay hospital nearby Bombay chemist and owner number is "
                + "+91 1234567890 and in the first property keep the owner number as 0987 6542";

        List<ParsedPropertyDTO> results = parserService.parseBatch(voicePrompt);

        assertEquals(2, results.size());

        ParsedPropertyDTO first = results.get(0);
        assertEquals("2 BHK", first.getBhk());
        assertEquals(18000.0, first.getRentAmount());
        assertEquals("₹8,000", first.getBrokerageVal());
        assertEquals("32000 Security Deposit", first.getDepositVal());
        assertEquals("2 Baths", first.getBathrooms());
        assertEquals("Vijay Nagar", first.getSector());
        assertEquals("Indore", first.getCity());
        assertEquals("Bombay Hospital, Radiation Blue", first.getLandmark());
        assertEquals("Not Specified", first.getType(), "A property type must not be invented from BHK alone");
        assertEquals("Not Specified", first.getOwnerPhone(), "An eight-digit late correction must remain invalid");
        assertFalse(first.getDescription().toLowerCase().contains("null"));
        assertTrue(first.getConflicts().stream().anyMatch(conflict -> conflict.contains("valid 10-digit")));

        ParsedPropertyDTO second = results.get(1);
        assertEquals("3 BHK", second.getBhk());
        assertEquals("Flat", second.getType());
        assertEquals(32000.0, second.getRentAmount());
        assertEquals("₹15,000", second.getBrokerageVal());
        assertEquals("60000 Security Deposit", second.getDepositVal());
        assertEquals("2 Baths", second.getBathrooms());
        assertEquals("Bombay Hospital, Bombay Chemist", second.getLandmark());
        assertEquals("Not Specified", second.getOwnerPhone(), "A number beginning with 1 is not a valid Indian mobile number");
        assertTrue(second.getConflicts().stream().anyMatch(conflict -> conflict.startsWith("Monthly Rent")));
        assertFalse(second.getRawPrompt().contains("first property keep"),
                "A correction targeting property one must not remain in property two's source text");
    }

    @Test
    @DisplayName("Should apply valid late dictation only to the explicitly named earlier property")
    void testValidLateCorrectionTargetsEarlierProperty() {
        String voicePrompt = "2 BHK flat in Vijay Nagar rent 18000 deposit 36000 next property "
                + "3 BHK flat in Palasia rent 32000 deposit 64000 owner phone 9123456789 "
                + "and in the first property keep the owner number as 9876543210 and make it fully furnished";

        List<ParsedPropertyDTO> results = parserService.parseBatch(voicePrompt);

        assertEquals(2, results.size());
        ParsedPropertyDTO first = results.get(0);
        ParsedPropertyDTO second = results.get(1);

        assertEquals("+91 98765 43210", first.getOwnerPhone());
        assertEquals("Fully Furnished", first.getFurnishingStatus());
        assertEquals(1, first.getAppliedAmendments().size());
        assertTrue(first.getRawPrompt().contains("Later correction:"));

        assertEquals("+91 91234 56789", second.getOwnerPhone());
        assertNull(second.getFurnishingStatus());
        assertTrue(second.getAppliedAmendments().isEmpty());
        assertFalse(second.getRawPrompt().contains("first property"));
    }

    @Test
    @DisplayName("Should understand action-first corrections for an earlier property")
    void testActionFirstLateCorrectionTargetsEarlierProperty() {
        String voicePrompt = "2 BHK flat in Vijay Nagar rent 18000 deposit 36000 next property "
                + "3 BHK flat in Palasia rent 32000 deposit 64000 add owner phone 9876543210 to the first property";

        List<ParsedPropertyDTO> results = parserService.parseBatch(voicePrompt);

        assertEquals(2, results.size());
        assertEquals("+91 98765 43210", results.get(0).getOwnerPhone());
        assertEquals("Not Specified", results.get(1).getOwnerPhone());
        assertEquals(1, results.get(0).getAppliedAmendments().size());
    }

    @Test
    @DisplayName("Should correctly normalize Hinglish rent terms (hazar and lakh)")
    void testHinglishRentNormalization() {
        String prompt = "3 BHK in Saket Nagar rent 25 hazar deposit 2 mahina owner +91 98260 99999";

        ParsedPropertyDTO dto = parserService.parseAndSave(prompt);

        assertNotNull(dto);
        assertEquals(25000.0, dto.getRentAmount());
        assertEquals("3 BHK", dto.getBhk());
        assertTrue(dto.getDepositVal().contains("2 Months Deposit"));
    }

    @Test
    @DisplayName("Should extract in-prompt photo URLs into mediaUrls field")
    void testInPromptUrlExtraction() {
        String prompt = "2 BHK in Vijay Nagar rent 20000 owner +91 98260 00000 photos https://res.cloudinary.com/pathome/properties/images/flat1.webp";

        List<ParsedPropertyDTO> batch = parserService.parseBatch(prompt);

        assertNotNull(batch);
        assertEquals(1, batch.size());
        assertEquals(1, batch.get(0).getMediaUrls().size());
        assertEquals("https://res.cloudinary.com/pathome/properties/images/flat1.webp", batch.get(0).getMediaUrls().get(0));
    }

    @Test
    @DisplayName("Should keep attributes isolated between consecutive property descriptions")
    void testConsecutivePromptsDoNotShareAttributes() {
        String firstPrompt = "Premium 2 BHK flat of 525 sqft in Nanda Nagar, Indore. Monthly rent 30000, "
                + "brokerage 15000, 1+1 security deposit. Owner Ramesh Sharma +91 98260 12345. "
                + "East facing, fully furnished, ready to move, status live.";
        String secondPrompt = "2 BHK flat on rent in Mahalaxmi Nagar, Indore. Rent 30000, deposit 60000, "
                + "owner Dipesh Patidar 6263421859, west facing, fully furnished, 3 bathrooms.";

        List<ParsedPropertyDTO> results = parserService.parseBatch(firstPrompt + "\n\nNext property\n\n" + secondPrompt);

        assertEquals(2, results.size());
        assertEquals(firstPrompt, results.get(0).getRawPrompt());
        assertEquals("Not Specified", results.get(0).getBathrooms());
        assertEquals("525 sqft", results.get(0).getAreaSqFt());
        assertEquals("Ramesh Sharma", results.get(0).getOwnerName());

        assertEquals(secondPrompt, results.get(1).getRawPrompt());
        assertEquals("3 Baths", results.get(1).getBathrooms());
        assertEquals("Dipesh Patidar", results.get(1).getOwnerName());
    }

    @Test
    @DisplayName("Should preserve field ownership across twenty consecutive property descriptions")
    void testTwentyPromptBatchKeepsEveryListingSeparate() {
        StringBuilder prompt = new StringBuilder();
        for (int index = 1; index <= 20; index++) {
            if (index > 1) {
                prompt.append("\n\nNext property\n\n");
            }
            String phone = String.format("9%09d", index);
            prompt.append("2 BHK flat in Vijay Nagar, Indore. Rent ")
                    .append(10000 + index)
                    .append(", deposit ").append(20000 + index)
                    .append(", owner Owner ").append(index)
                    .append(" ").append(phone)
                    .append(", ").append((index % 4) + 1).append(" bathrooms.");
        }

        List<ParsedPropertyDTO> results = parserService.parseBatch(prompt.toString());

        assertEquals(20, results.size());
        for (int index = 1; index <= 20; index++) {
            ParsedPropertyDTO listing = results.get(index - 1);
            String phone = String.format("9%09d", index);
            assertEquals(10000.0 + index, listing.getRentAmount());
            assertEquals((index % 4) + 1 + " Baths", listing.getBathrooms());
            assertEquals("+91 " + phone.substring(0, 5) + " " + phone.substring(5), listing.getOwnerPhone());
            assertTrue(listing.getRawPrompt().contains("Rent " + (10000 + index)));
        }
    }

    @Test
    @DisplayName("Should parse exact 4-property incident prompt into exactly 4 properties without splitting on internal blank lines")
    void testExactFourPropertyIncidentFormatWithInternalBlankLines() {
        String incidentPrompt = """
                2 BHK Flat for rent in Bhawarkuan, Indore near DAVV.

                Monthly rent is ₹21,000 and security deposit is ₹42,000.
                Built-up area is 1100 sqft.
                The flat is semi furnished and east facing.
                It has 2 bedrooms, 2 bathrooms and 1 balcony.
                Located on the 3rd floor of a 7-floor building.
                Covered car parking is available.
                Property is available immediately.
                Preferred for family or working professionals.
                Amenities include lift, security, CCTV and power backup.

                Next property

                3 BHK Flat for rent in Rau, Indore near IIM Indore.

                Monthly rent is ₹28,000 and security deposit is ₹56,000.
                Built-up area is 1550 sqft.
                The flat is fully furnished and north facing.
                It has 3 bedrooms, 3 bathrooms and 2 balconies.
                Located on the 5th floor of a 10-floor building.
                Covered car parking is available.
                Property is available immediately.
                Preferred for family.
                Amenities include lift, security, CCTV, power backup, gym and clubhouse.

                Next property

                2 BHK Flat for rent near Mari Mata Square, Indore.

                Monthly rent is ₹19,000 and security deposit is ₹38,000.
                Built-up area is 1000 sqft.
                The flat is unfurnished and west facing.
                It has 2 bedrooms, 2 bathrooms and 1 balcony.
                Located on the 2nd floor of a 6-floor building.
                Car parking is available.
                Property is available immediately.
                Preferred for family.
                Amenities include lift, security and CCTV.

                Next property

                3 BHK Flat for rent in Nipania, Indore near Phoenix Citadel.

                Monthly rent is ₹36,000 and security deposit is ₹72,000.
                Built-up area is 1700 sqft.
                The flat is semi furnished and south facing.
                It has 3 bedrooms, 3 bathrooms and 3 balconies.
                Located on the 8th floor of a 14-floor building.
                Covered car parking is available.
                Property is available immediately.
                Preferred for family.
                Amenities include lift, power backup, security, CCTV, gym, swimming pool, clubhouse and children's play area.
                """;

        List<ParsedPropertyDTO> results = parserService.parseBatch(incidentPrompt);

        assertNotNull(results);
        assertEquals(4, results.size(), "4 source properties must produce exactly 4 ParsedPropertyDTOs, not 8");

        // Property 1: Bhawarkuan
        ParsedPropertyDTO bhawarkuan = results.get(0);
        assertEquals("2 BHK", bhawarkuan.getBhk());
        assertEquals("Flat", bhawarkuan.getType());
        assertEquals("Bhawarkua", bhawarkuan.getSector());
        assertEquals("Indore", bhawarkuan.getCity());
        assertEquals("Davv", bhawarkuan.getLandmark());
        assertEquals(21000.0, bhawarkuan.getRentAmount());
        assertNotNull(bhawarkuan.getDepositVal());
        assertTrue(bhawarkuan.getDepositVal().contains("42,000"));
        assertEquals("1100 sqft", bhawarkuan.getAreaSqFt());
        assertEquals("2 Baths", bhawarkuan.getBathrooms());
        assertEquals("Semi Furnished", bhawarkuan.getFurnishingStatus());
        assertEquals("East Facing", bhawarkuan.getVastuFacing());
        assertEquals(AvailabilityStatus.READY_NOW, bhawarkuan.getAvailabilityStatus());
        assertEquals("Ready To Move", bhawarkuan.getPossessionDate());
        assertTrue(bhawarkuan.getAmenities().contains("High-Speed Lift"));
        assertTrue(bhawarkuan.getAmenities().contains("Gated Security"));
        assertTrue(bhawarkuan.getAmenities().contains("CCTV"));
        assertTrue(bhawarkuan.getAmenities().contains("Power Backup"));
        assertTrue(bhawarkuan.getAmenities().contains("Covered Parking"));
        assertTrue(bhawarkuan.getAmenities().contains("Balcony & City View"));
        assertEquals(3, bhawarkuan.getFloor());
        assertEquals(7, bhawarkuan.getTotalFloors());
        assertEquals(List.of("FAMILY", "WORKING_PROFESSIONALS"), bhawarkuan.getPreferredTenants());

        // Property 2: Rau
        ParsedPropertyDTO rau = results.get(1);
        assertEquals("3 BHK", rau.getBhk());
        assertEquals("Flat", rau.getType());
        assertEquals("Rau", rau.getSector());
        assertEquals("Indore", rau.getCity());
        assertEquals("Iim Indore", rau.getLandmark());
        assertEquals(28000.0, rau.getRentAmount());
        assertNotNull(rau.getDepositVal());
        assertTrue(rau.getDepositVal().contains("56,000"));
        assertEquals("1550 sqft", rau.getAreaSqFt());
        assertEquals("3 Baths", rau.getBathrooms());
        assertEquals("Fully Furnished", rau.getFurnishingStatus());
        assertEquals("North Facing", rau.getVastuFacing());
        assertEquals(AvailabilityStatus.READY_NOW, rau.getAvailabilityStatus());
        assertTrue(rau.getAmenities().contains("High-Speed Lift"));
        assertTrue(rau.getAmenities().contains("Gated Security"));
        assertTrue(rau.getAmenities().contains("CCTV"));
        assertTrue(rau.getAmenities().contains("Power Backup"));
        assertTrue(rau.getAmenities().contains("Fitness Center & Gym"));
        assertTrue(rau.getAmenities().contains("Clubhouse"));
        assertEquals(5, rau.getFloor());
        assertEquals(10, rau.getTotalFloors());
        assertEquals(List.of("FAMILY"), rau.getPreferredTenants());

        // Property 3: Mari Mata Square
        ParsedPropertyDTO mariMata = results.get(2);
        assertEquals("2 BHK", mariMata.getBhk());
        assertEquals("Flat", mariMata.getType());
        assertEquals("Mari Mata Square", mariMata.getSector());
        assertEquals("Indore", mariMata.getCity());
        assertEquals(19000.0, mariMata.getRentAmount());
        assertNotNull(mariMata.getDepositVal());
        assertTrue(mariMata.getDepositVal().contains("38,000"));
        assertEquals("1000 sqft", mariMata.getAreaSqFt());
        assertEquals("2 Baths", mariMata.getBathrooms());
        assertEquals("Unfurnished", mariMata.getFurnishingStatus());
        assertEquals("West Facing", mariMata.getVastuFacing());
        assertEquals(AvailabilityStatus.READY_NOW, mariMata.getAvailabilityStatus());
        assertTrue(mariMata.getAmenities().contains("High-Speed Lift"));
        assertTrue(mariMata.getAmenities().contains("Gated Security"));
        assertTrue(mariMata.getAmenities().contains("CCTV"));
        assertEquals(2, mariMata.getFloor());
        assertEquals(6, mariMata.getTotalFloors());
        assertEquals(List.of("FAMILY"), mariMata.getPreferredTenants());

        // Property 4: Nipania
        ParsedPropertyDTO nipania = results.get(3);
        assertEquals("3 BHK", nipania.getBhk());
        assertEquals("Flat", nipania.getType());
        assertEquals("Nipania", nipania.getSector());
        assertEquals("Indore", nipania.getCity());
        assertEquals("Phoenix Citadel", nipania.getLandmark());
        assertEquals(36000.0, nipania.getRentAmount());
        assertNotNull(nipania.getDepositVal());
        assertTrue(nipania.getDepositVal().contains("72,000"));
        assertEquals("1700 sqft", nipania.getAreaSqFt());
        assertEquals("3 Baths", nipania.getBathrooms());
        assertEquals("Semi Furnished", nipania.getFurnishingStatus());
        assertEquals("South Facing", nipania.getVastuFacing());
        assertEquals(AvailabilityStatus.READY_NOW, nipania.getAvailabilityStatus());
        assertTrue(nipania.getAmenities().contains("High-Speed Lift"));
        assertTrue(nipania.getAmenities().contains("Gated Security"));
        assertTrue(nipania.getAmenities().contains("CCTV"));
        assertTrue(nipania.getAmenities().contains("Power Backup"));
        assertTrue(nipania.getAmenities().contains("Fitness Center & Gym"));
        assertTrue(nipania.getAmenities().contains("Swimming Pool"));
        assertTrue(nipania.getAmenities().contains("Clubhouse"));
        assertEquals(8, nipania.getFloor());
        assertEquals(14, nipania.getTotalFloors());
        assertEquals(List.of("FAMILY"), nipania.getPreferredTenants());
    }

    @Test
    @DisplayName("Should preserve internal multiple paragraph breaks within a single property when explicit delimiters exist")
    void testMultipleParagraphBreaksWithinOnePropertyWithExplicitDelimiters() {
        String prompt = """
                2 BHK Flat in Vijay Nagar, Indore.

                Rent is 25000 and deposit is 50000.

                Carpet area is 1200 sqft with 2 bathrooms.

                Fully furnished and north facing.

                Next property

                3 BHK Flat in Palasia, Indore. Rent is 45000.
                """;

        List<ParsedPropertyDTO> results = parserService.parseBatch(prompt);

        assertEquals(2, results.size());
        ParsedPropertyDTO first = results.get(0);
        assertEquals("2 BHK", first.getBhk());
        assertEquals("Vijay Nagar", first.getSector());
        assertEquals(25000.0, first.getRentAmount());
        assertTrue(first.getDepositVal().contains("50000"));
        assertEquals("1200 sqft", first.getAreaSqFt());
        assertEquals("2 Baths", first.getBathrooms());
        assertEquals("Fully Furnished", first.getFurnishingStatus());
        assertEquals("North Facing", first.getVastuFacing());

        ParsedPropertyDTO second = results.get(1);
        assertEquals("3 BHK", second.getBhk());
        assertEquals("Palasia", second.getSector());
        assertEquals(45000.0, second.getRentAmount());
    }

    @Test
    @DisplayName("Should fallback to double-newline segmentation when no explicit delimiters exist")
    void testNoExplicitDelimiterFallbackDoubleNewline() {
        String prompt = """
                2 BHK Flat in Vijay Nagar, Indore. Rent is 20000.

                3 BHK Flat in Palasia, Indore. Rent is 40000.
                """;

        List<ParsedPropertyDTO> results = parserService.parseBatch(prompt);

        assertEquals(2, results.size());
        assertEquals("2 BHK", results.get(0).getBhk());
        assertEquals(20000.0, results.get(0).getRentAmount());
        assertEquals("3 BHK", results.get(1).getBhk());
        assertEquals(40000.0, results.get(1).getRentAmount());
    }

    @Test
    @DisplayName("Should recognize all availability variations including available immediately")
    void testAvailabilityVariationsImmediateAndDates() {
        ParsedPropertyDTO dto1 = parserService.parse("2 BHK in Vijay Nagar rent 20000. Property is available immediately.");
        assertEquals(AvailabilityStatus.READY_NOW, dto1.getAvailabilityStatus());
        assertEquals("Ready To Move", dto1.getPossessionDate());

        ParsedPropertyDTO dto2 = parserService.parse("2 BHK in Vijay Nagar rent 20000. Available immediately.");
        assertEquals(AvailabilityStatus.READY_NOW, dto2.getAvailabilityStatus());
        assertEquals("Ready To Move", dto2.getPossessionDate());

        ParsedPropertyDTO dto3 = parserService.parse("2 BHK in Vijay Nagar rent 20000. Immediately available.");
        assertEquals(AvailabilityStatus.READY_NOW, dto3.getAvailabilityStatus());
        assertEquals("Ready To Move", dto3.getPossessionDate());

        ParsedPropertyDTO dto4 = parserService.parse("2 BHK in Vijay Nagar rent 20000. Ready to move.");
        assertEquals(AvailabilityStatus.READY_NOW, dto4.getAvailabilityStatus());
        assertEquals("Ready To Move", dto4.getPossessionDate());

        ParsedPropertyDTO dto5 = parserService.parse("2 BHK in Vijay Nagar rent 20000. Immediate possession.");
        assertEquals(AvailabilityStatus.READY_NOW, dto5.getAvailabilityStatus());
        assertEquals("Ready To Move", dto5.getPossessionDate());

        ParsedPropertyDTO dto6 = parserService.parse("2 BHK in Vijay Nagar rent 20000. Available now.");
        assertEquals(AvailabilityStatus.READY_NOW, dto6.getAvailabilityStatus());
        assertEquals("Ready To Move", dto6.getPossessionDate());
    }

    @Test
    @DisplayName("Should recognize extended amenities while avoiding false security from security deposit")
    void testExtendedAmenitiesRecognitionAndSecurityDepositIsolation() {
        String promptWithAmenities = "2 BHK in Vijay Nagar rent 20000. Amenities include lift, security, CCTV, power backup, gym, swimming pool and clubhouse.";
        ParsedPropertyDTO dto1 = parserService.parse(promptWithAmenities);

        assertTrue(dto1.getAmenities().contains("High-Speed Lift"));
        assertTrue(dto1.getAmenities().contains("Gated Security"));
        assertTrue(dto1.getAmenities().contains("CCTV"));
        assertTrue(dto1.getAmenities().contains("Power Backup"));
        assertTrue(dto1.getAmenities().contains("Fitness Center & Gym"));
        assertTrue(dto1.getAmenities().contains("Swimming Pool"));
        assertTrue(dto1.getAmenities().contains("Clubhouse"));

        String promptWithDepositOnly = "2 BHK in Vijay Nagar rent 20000 and security deposit is 40000.";
        ParsedPropertyDTO dto2 = parserService.parse(promptWithDepositOnly);
        assertFalse(dto2.getAmenities().contains("Gated Security"),
                "A financial security deposit must not trigger the Gated Security amenity");
    }

    @Test
    @DisplayName("Should accurately parse the original 4-property prompt into exactly 4 properties with floor and preferred tenants")
    void testOriginalFourPropertyPromptWithFloorAndPreferredTenants() {
        String prompt = """
                2 BHK Flat for rent in Bhawarkuan, Indore near DAVV.

                Monthly rent is ₹21,000 and security deposit is ₹42,000.
                Built-up area is 1100 sqft.
                The flat is semi furnished and east facing.
                It has 2 bedrooms, 2 bathrooms and 1 balcony.
                Located on the 3rd floor of a 7-floor building.
                Covered car parking is available.
                Property is available immediately.
                Amenities include lift, power backup, CCTV, and 24x7 security.
                Preferred for family or working professionals.
                Owner: Rajesh Sharma, +91 98260 12345.

                Next property

                1 BHK Furnished Apartment in Rau, Indore near Silicon City.

                Rent is ₹13,500 and deposit is ₹27,000.
                Super built-up area 650 sqft.
                Fully furnished flat with sofa, bed, wardrobe, fridge, and RO.
                North facing, 1 bathroom, 1 balcony.
                Located on 5th floor of 10-floor building.
                Available from 1st October 2026.
                Gated society with security, lift, and open parking.
                Suitable for bachelors or students.
                Call owner: Amit Verma, 9893012345.

                Next property

                3 BHK Independent House in Mari Mata Square, Indore.

                Rent ₹32,000, security deposit ₹64,000.
                Plot area 1500 sqft, built-up 2200 sqft.
                Unfurnished, west facing, 3 bathrooms, 2 balconies.
                Ground floor + 1st floor independent duplex.
                Dedicated covered parking for car and 2 bikes.
                Possession available now.
                Families preferred.
                Owner: Sunita Jain, +91 94250 98765.

                Next property

                Luxury 3 BHK Penthouse in Nipania, Indore near Apollo DB City.

                Expected rent is ₹55,000, deposit is ₹1,10,000.
                Carpet area 2100 sqft with private terrace garden.
                Premium fully furnished with modular kitchen and ACs.
                4 bathrooms, 2 covered parkings.
                Located on 8th floor of 14-floor building.
                East facing, Vastu compliant.
                Amenities: swimming pool, gym, clubhouse, 24/7 security, power backup.
                Available from 15th October 2026.
                Contact owner: Dr. Alok Singhal, 9755012345.
                """;

        List<ParsedPropertyDTO> results = parserService.parseBatch(prompt);

        assertNotNull(results);
        assertEquals(4, results.size(), "4-property prompt with explicit delimiters must segment into exactly 4 properties");

        // Property #1: Bhawarkuan
        ParsedPropertyDTO prop1 = results.get(0);
        assertEquals("2 BHK", prop1.getBhk());
        assertEquals("Bhawarkua", prop1.getSector());
        assertEquals(21000.0, prop1.getRentAmount());
        assertEquals("42,000 Security Deposit", prop1.getDepositVal());
        assertEquals("1100 sqft", prop1.getAreaSqFt());
        assertEquals("Semi Furnished", prop1.getFurnishingStatus());
        assertEquals("East Facing", prop1.getVastuFacing());
        assertEquals("2 Baths", prop1.getBathrooms());
        assertEquals(AvailabilityStatus.READY_NOW, prop1.getAvailabilityStatus());
        assertEquals("Ready To Move", prop1.getPossessionDate());
        assertEquals("+91 98260 12345", prop1.getOwnerPhone());
        assertEquals("Rajesh Sharma", prop1.getOwnerName());
        assertTrue(prop1.getAmenities().contains("High-Speed Lift"));
        assertTrue(prop1.getAmenities().contains("Power Backup"));
        assertTrue(prop1.getAmenities().contains("CCTV"));
        assertTrue(prop1.getAmenities().contains("Gated Security"));
        assertEquals(3, prop1.getFloor(), "Bhawarkuan property floor must be 3");
        assertEquals(7, prop1.getTotalFloors(), "Bhawarkuan property total floors must be 7");
        assertEquals(List.of("FAMILY", "WORKING_PROFESSIONALS"), prop1.getPreferredTenants());

        // Property #2: Rau
        ParsedPropertyDTO prop2 = results.get(1);
        assertEquals(5, prop2.getFloor());
        assertEquals(10, prop2.getTotalFloors());
        assertEquals(List.of("BACHELORS", "STUDENTS"), prop2.getPreferredTenants());

        // Property #3: Mari Mata Square
        ParsedPropertyDTO prop3 = results.get(2);
        assertEquals(List.of("FAMILY"), prop3.getPreferredTenants());

        // Property #4: Nipania
        ParsedPropertyDTO prop4 = results.get(3);
        assertEquals(8, prop4.getFloor());
        assertEquals(14, prop4.getTotalFloors());
    }
}
