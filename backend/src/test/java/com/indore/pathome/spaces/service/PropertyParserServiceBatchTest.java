package com.indore.pathome.spaces.service;

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
}
