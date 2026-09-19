package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.repository.LocalityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class PropertyParserServiceTest {

    @Mock
    private LocalityRepository localityRepository;

    @InjectMocks
    private PropertyParserService propertyParserService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(localityRepository.findAll()).thenReturn(new ArrayList<>());
        when(localityRepository.findDistinctCities()).thenReturn(new ArrayList<>());
        propertyParserService.initCache();
    }

    @Test
    public void testNumericBhkParsing() {
        ParsedPropertyDTO dto = propertyParserService.parseAndSave("4bhk flat in Nanda Nagar for 18000");
        assertNotNull(dto);
        assertEquals("4 BHK", dto.getBhk());
        assertEquals("Flat", dto.getType());
        assertEquals("Nanda Nagar", dto.getSector());
        assertEquals(18000.0, dto.getRentAmount());
    }

    @Test
    public void testFractionalBhkParsing() {
        ParsedPropertyDTO dto = propertyParserService.parseAndSave("2.5 bhk flat in Vijay Nagar for 24000");
        assertNotNull(dto);
        assertEquals("2.5 BHK", dto.getBhk());
        assertEquals("Vijay Nagar", dto.getSector());
        assertEquals(24000.0, dto.getRentAmount());
    }

    @Test
    public void testWordBhkParsing() {
        ParsedPropertyDTO dto = propertyParserService.parseAndSave("three bhk house in Bhawarkua Indore for 28000");
        assertNotNull(dto);
        assertEquals("3 BHK", dto.getBhk());
        assertEquals("House", dto.getType());
        assertEquals("Indore", dto.getCity());
    }

    @Test
    public void testStudioAndDuplexParsing() {
        ParsedPropertyDTO studioDto = propertyParserService.parseAndSave("1 rk studio in Rau for 10k");
        assertEquals("1 RK Studio", studioDto.getBhk());
        assertEquals(10000.0, studioDto.getRentAmount());

        ParsedPropertyDTO duplexDto = propertyParserService.parseAndSave("duplex villa in Palasia for 50000");
        assertEquals("Duplex Villa", duplexDto.getBhk());
        assertEquals("House", duplexDto.getType());
    }

    @Test
    public void testVastuAndAmenitiesParsing() {
        ParsedPropertyDTO dto = propertyParserService.parseAndSave("2bhk in Vijay Nagar with balcony facing west for 20000");
        assertEquals("West Facing", dto.getVastuFacing());
        assertTrue(dto.getAmenities().contains("Balcony & City View"));
    }

    @Test
    public void testParsingDoesNotPersistLocalityUntilPublicationIsConfirmed() {
        when(localityRepository.findByCityIgnoreCaseAndSectorNameIgnoreCase(eq("Jaipur"), eq("Civil Lines")))
                .thenReturn(Optional.empty());

        Locality savedLocality = new Locality("Jaipur", "Civil Lines", "civil lines", 92, 22000.0);
        when(localityRepository.save(any(Locality.class))).thenReturn(savedLocality);

        ParsedPropertyDTO dto = propertyParserService.parse("2bhk in Civil Lines Jaipur for 22000");
        assertNotNull(dto);
        assertEquals("Jaipur", dto.getCity());
        assertEquals("Civil Lines", dto.getSector());
        assertFalse(dto.isSavedToDatabase());
        verify(localityRepository, never()).save(any(Locality.class));

        propertyParserService.confirmLocality(dto.getCity(), dto.getSector(), dto.getRentAmount());
        verify(localityRepository, times(1)).save(any(Locality.class));
    }

    @Test
    public void testConflictingAmountsRequireReviewWithoutInventingValues() {
        ParsedPropertyDTO dto = propertyParserService.parse(
                "2 BHK flat in Vijay Nagar rent 25000, later rent 30000, owner +91 98260 12345");

        assertTrue(dto.isRequiresReview());
        assertTrue(dto.getConflicts().stream().anyMatch(conflict -> conflict.startsWith("Monthly Rent")));
        assertTrue(dto.getMissingFields().contains("Security Deposit"));
    }

    @Test
    public void testAmbiguousCachedLocalityDoesNotInventACity() {
        when(localityRepository.findAll()).thenReturn(List.of(
                new Locality("Jaipur", "Civil Lines", "civil lines", 92, 22000.0),
                new Locality("Pune", "Civil Lines", "civil lines", 92, 30000.0)
        ));
        propertyParserService.initCache();

        ParsedPropertyDTO ambiguous = propertyParserService.parse(
                "2 BHK flat in Civil Lines rent 22000 owner +91 98260 12345");
        ParsedPropertyDTO explicit = propertyParserService.parse(
                "2 BHK flat in Civil Lines Jaipur rent 22000 owner +91 98260 12345");

        assertEquals("Not Specified", ambiguous.getCity());
        assertEquals("Jaipur", explicit.getCity());
        assertEquals("Civil Lines", explicit.getSector());
    }

    @Test
    public void testUserComplexPromptParsing() {
        String prompt = "Premium 2bhk flat 525 sqft 15000 brokerage 30000 rent 1+1 security deposit owner name Piyushi Saha 458888248";
        ParsedPropertyDTO dto = propertyParserService.parseAndSave(prompt);

        assertNotNull(dto);
        assertEquals("2 BHK", dto.getBhk());
        assertEquals("Flat", dto.getType());
        assertEquals(30000.0, dto.getRentAmount());
        assertEquals("₹30,000", dto.getRentVal());
        assertEquals("₹15,000", dto.getBrokerageVal());
        assertEquals("525 sqft", dto.getAreaSqFt());
        assertEquals("1+1 Security Deposit", dto.getDepositVal());
        assertEquals("Piyushi Saha", dto.getOwnerName());
        assertEquals("Not Specified", dto.getOwnerPhone());
        assertTrue(dto.getMissingFields().contains("Owner Contact Number"));
        assertEquals("Not Specified", dto.getVastuFacing());
    }

    @Test
    public void testFull18PlusAttributeExtractionAndMissingTelemetry() {
        String fullPrompt = "Premium 2bhk flat 525 sqft 15000 brokerage 30000 rent 1+1 security deposit owner name Piyushi Saha 9876543210 status live in Nanda Nagar Indore facing east fully furnished ready to move near Main Square 452010 3 bathrooms";
        ParsedPropertyDTO dto = propertyParserService.parseAndSave(fullPrompt);

        assertNotNull(dto);
        assertEquals("2 BHK", dto.getBhk());
        assertEquals("Flat", dto.getType());
        assertEquals(30000.0, dto.getRentAmount());
        assertEquals("₹15,000", dto.getBrokerageVal());
        assertEquals("525 sqft", dto.getAreaSqFt());
        assertEquals("1+1 Security Deposit", dto.getDepositVal());
        assertEquals("Piyushi Saha", dto.getOwnerName());
        assertEquals("+91 98765 43210", dto.getOwnerPhone());
        assertEquals("East Facing", dto.getVastuFacing());
        assertEquals("Fully Furnished", dto.getFurnishingStatus());
        assertEquals("Ready To Move", dto.getPossessionDate());
        assertEquals("LIVE", dto.getStatus());
        assertEquals("Nanda Nagar", dto.getSector());
        assertEquals("Indore", dto.getCity());
        assertEquals("452010", dto.getPincode());
        assertEquals("Main Square", dto.getLandmark());
        assertEquals("3 Baths", dto.getBathrooms());

        // Test partial prompt missing fields detection
        String partialPrompt = "2bhk flat in Nanda Nagar";
        ParsedPropertyDTO partialDto = propertyParserService.parseAndSave(partialPrompt);
        assertNotNull(partialDto);
        assertNotNull(partialDto.getMissingFields());
        assertTrue(partialDto.getMissingFields().contains("Bathrooms Count"));
        assertTrue(partialDto.getMissingFields().contains("Monthly Rent"));
    }

    @Test
    public void testUserExactScreenshotPromptParsing() {
        String userPrompt = "Luxury 3 BHK Penthouse of 1800 sqft in Vijay Nagar, Indore. Monthly rent ₹45,000, brokerage ₹22,500, security deposit ₹90,000. Owner John Doe +91 1234567890. North-East facing with terrace, balcony and pool. Fully furnished, ready to move, status live. near by mahalaxmi temple. 452010 and rent is 20000 with 1+1 diposite and brokerage is 10000";
        ParsedPropertyDTO dto = propertyParserService.parseAndSave(userPrompt);

        assertNotNull(dto);
        assertEquals("3 BHK", dto.getBhk());
        assertEquals("Penthouse", dto.getType());
        assertEquals(45000.0, dto.getRentAmount());
        assertEquals("₹22,500", dto.getBrokerageVal());
        assertEquals("1800 sqft", dto.getAreaSqFt());
        assertEquals("John Doe", dto.getOwnerName());
        assertEquals("Not Specified", dto.getOwnerPhone());
        assertTrue(dto.getMissingFields().contains("Owner Contact Number"));
        assertEquals("North-East Facing", dto.getVastuFacing());
        assertEquals("Fully Furnished", dto.getFurnishingStatus());
        assertEquals("Ready To Move", dto.getPossessionDate());
        assertEquals("LIVE", dto.getStatus());
        assertEquals("Vijay Nagar", dto.getSector());
        assertEquals("Indore", dto.getCity());
        assertEquals("452010", dto.getPincode());
        assertEquals("Mahalaxmi Temple", dto.getLandmark());
    }

    @Test
    public void testInterveningWordsBhkAndNoFakeDefaults() {
        String prompt = "sdfjsdfjerijejfdsf 2 kldsjflkdjsf bhk";
        ParsedPropertyDTO dto = propertyParserService.parseAndSave(prompt);

        assertNotNull(dto);
        assertEquals("2 BHK", dto.getBhk());
        assertEquals("Not Specified", dto.getType());
        assertEquals("Not Specified", dto.getBathrooms());
        assertNull(dto.getDepositVal(), "Missing depositVal must be null, not fake default");
        assertNull(dto.getFurnishingStatus(), "Missing furnishingStatus must be null, not fake default");
        assertNull(dto.getPossessionDate(), "Missing possessionDate must be null, not fake default");
        assertNull(dto.getBrokerageDays(), "Missing brokerageDays must be null, not fake default");
        assertEquals("Not Specified", dto.getOwnerName(), "Missing ownerName must be Not Specified, not Direct Owner");
    }

    @Test
    public void testNoFakeDefaultsWhenAttributesMissing() {
        String prompt = "2bhk flat in Vijay Nagar for 25000";
        ParsedPropertyDTO dto = propertyParserService.parseAndSave(prompt);

        assertNotNull(dto);
        assertEquals("2 BHK", dto.getBhk());
        assertEquals("Flat", dto.getType());
        assertEquals("Vijay Nagar", dto.getSector());
        assertEquals(25000.0, dto.getRentAmount());

        // Must NOT invent fake property data
        assertNull(dto.getDepositVal(), "Missing depositVal must be null, not fake default");
        assertNull(dto.getFurnishingStatus(), "Missing furnishingStatus must be null, not fake default");
        assertNull(dto.getPossessionDate(), "Missing possessionDate must be null, not fake default");
        assertNull(dto.getBrokerageDays(), "Missing brokerageDays must be null, not fake default");
        assertEquals("Not Specified", dto.getOwnerName(), "Missing ownerName must be Not Specified, not Direct Owner");

        // Telemetry must report missing attributes
        assertTrue(dto.getMissingFields().contains("Security Deposit"));
        assertTrue(dto.getMissingFields().contains("Furnishing Status"));
        assertTrue(dto.getMissingFields().contains("Possession Date / Readiness"));
    }

    @Test
    public void testUserNoisyPromptAndTypoAutoCorrection() {
        String userNoisyPrompt = "sfjsf dsfjl2 jsfldsjfas fij dfbhk 2 jskljfkldsjf sdfbhk bhk";
        ParsedPropertyDTO dto1 = propertyParserService.parseAndSave(userNoisyPrompt);
        assertNotNull(dto1);
        assertEquals("2 BHK", dto1.getBhk());

        String typoPrompt = "3.5 flt in saket nagr 25000 rnt owner jhon doe +91 9876543210 est facing semifurnishd";
        ParsedPropertyDTO dto2 = propertyParserService.parseAndSave(typoPrompt);
        assertNotNull(dto2);
        assertEquals("3.5 BHK", dto2.getBhk());
        assertEquals("Flat", dto2.getType());
        assertEquals("Saket Nagar", dto2.getSector());
        assertEquals(25000.0, dto2.getRentAmount());
        assertEquals("₹25,000", dto2.getRentVal());
        assertEquals("Jhon Doe", dto2.getOwnerName());
        assertEquals("+91 98765 43210", dto2.getOwnerPhone());
        assertEquals("East Facing", dto2.getVastuFacing());
        assertEquals("Semi Furnished", dto2.getFurnishingStatus());
    }

    @Test
    public void testRepetitiveLocalityAndFailSafeCityParsing() {
        String prompt1 = "3 BHK Penthouse in Vijay Nagar, Vijay";
        ParsedPropertyDTO dto1 = propertyParserService.parseAndSave(prompt1);
        assertNotNull(dto1);
        assertEquals("Vijay Nagar", dto1.getSector());
        assertEquals("Indore", dto1.getCity());

        String prompt2 = "2 BHK Flat in Nanda Nagar, Nanda";
        ParsedPropertyDTO dto2 = propertyParserService.parseAndSave(prompt2);
        assertNotNull(dto2);
        assertEquals("Nanda Nagar", dto2.getSector());
        assertEquals("Indore", dto2.getCity());

        String prompt3 = "1 BHK Studio near Saket Nagar for 12000";
        ParsedPropertyDTO dto3 = propertyParserService.parseAndSave(prompt3);
        assertNotNull(dto3);
        assertEquals("Saket Nagar", dto3.getSector());
        assertEquals("Indore", dto3.getCity());
    }

    @Test
    public void testPhoneExtractionVariations() {
        // Spaced 5+5 digit phone number with country code
        String promptSpaced = "2 BHK in Nanda Nagar. Monthly rent 30000. Owner Ramesh Sharma +91 98260 12345. East facing.";
        ParsedPropertyDTO dtoSpaced = propertyParserService.parseAndSave(promptSpaced);
        assertEquals("Ramesh Sharma", dtoSpaced.getOwnerName());
        assertEquals("+91 98260 12345", dtoSpaced.getOwnerPhone());

        // Dashed phone number
        String promptDashed = "2 BHK flat. Owner Suresh 98260-12345 rent 15000";
        ParsedPropertyDTO dtoDashed = propertyParserService.parseAndSave(promptDashed);
        assertEquals("Suresh", dtoDashed.getOwnerName());
        assertEquals("+91 98260 12345", dtoDashed.getOwnerPhone());

        // Contiguous 10-digit phone number
        String promptContiguous = "2 BHK flat rent 20000 owner Anita 9123456789";
        ParsedPropertyDTO dtoContiguous = propertyParserService.parseAndSave(promptContiguous);
        assertEquals("Anita", dtoContiguous.getOwnerName());
        assertEquals("+91 91234 56789", dtoContiguous.getOwnerPhone());
    }

    @Test
    public void testAreaFollowingBrokerageDaysIsNotUsedAsMonthlyRent() {
        ParsedPropertyDTO dto = propertyParserService.parse(
                "2 BHK in Nanda Nagar 15 days rent 1200 square feet rent is 25000 owner +91 98260 12345");

        assertEquals(25000.0, dto.getRentAmount());
        assertEquals("1200 sqft", dto.getAreaSqFt());
        assertEquals("15 Days", dto.getBrokerageDays());
        assertFalse(dto.getDescription().contains("Listing Status: null"));
    }

    @Test
    public void testScreenshotUserPromptsParsing() {
        // Exact Prompt 1 from screenshot
        String prompt1 = "2bhk flat on rent near bombay chemist infront of Infiniti hotel mahalaxmi nagar rent is 30000 & the security deposit is 1+1 60000 & the brokerage Fee is 15000 & possession date 20th of september west facing Flat fully furnished flat Dipesh patidar owner 6263421859 & 3 bathroom";
        ParsedPropertyDTO dto1 = propertyParserService.parseAndSave(prompt1);

        assertNotNull(dto1);
        assertEquals("2 BHK", dto1.getBhk());
        assertEquals("Flat", dto1.getType());
        assertEquals(30000.0, dto1.getRentAmount());
        assertEquals("₹15,000", dto1.getBrokerageVal());
        assertEquals("Dipesh Patidar", dto1.getOwnerName());
        assertEquals("+91 62634 21859", dto1.getOwnerPhone());
        assertEquals("West Facing", dto1.getVastuFacing());
        assertEquals("Fully Furnished", dto1.getFurnishingStatus());
        assertEquals("3 Baths", dto1.getBathrooms());
        assertEquals("Mahalaxmi Nagar", dto1.getSector());

        // Exact Prompt 2 from screenshot
        String prompt2 = "1bhk flat on rent near opal homes chikatsak nagar mahalaxmi nagar 17000 rent 36000 securuity deposit 8500 brokerage & possession date is 25th of sep owner name dipesh patidar 8458888248 east dacing semi furnished brokerage is 7000 and bathromm 2";
        ParsedPropertyDTO dto2 = propertyParserService.parseAndSave(prompt2);

        assertNotNull(dto2);
        assertEquals("1 BHK", dto2.getBhk());
        assertEquals("Flat", dto2.getType());
        assertEquals(17000.0, dto2.getRentAmount());
        assertEquals("₹7,000", dto2.getBrokerageVal());
        assertEquals("Dipesh Patidar", dto2.getOwnerName());
        assertEquals("+91 84588 88248", dto2.getOwnerPhone());
        assertEquals("East Facing", dto2.getVastuFacing());
        assertEquals("Semi Furnished", dto2.getFurnishingStatus());
        assertEquals("2 Baths", dto2.getBathrooms());
        assertEquals("Mahalaxmi Nagar", dto2.getSector());
    }

    @Test
    public void testOwnerNameAndContactExtractionRegression() {
        // 1. Minimum test 1: "Name is Piyushi Saha number is 9131670191"
        ParsedPropertyDTO dto1 = propertyParserService.parseAndSave("2 BHK flat in Bhawarkuan Name is Piyushi Saha number is 9131670191");
        assertNotNull(dto1);
        assertEquals("Piyushi Saha", dto1.getOwnerName());
        assertEquals("+91 91316 70191", dto1.getOwnerPhone());
        assertTrue(dto1.getOwnerPhone().replace(" ", "").contains("9131670191"));

        // 2. Minimum test 2: "Owner name is Rahul Sharma and contact is 9876543210"
        ParsedPropertyDTO dto2 = propertyParserService.parseAndSave("2 BHK flat in Vijay Nagar Owner name is Rahul Sharma and contact is 9876543210");
        assertNotNull(dto2);
        assertEquals("Rahul Sharma", dto2.getOwnerName());
        assertEquals("+91 98765 43210", dto2.getOwnerPhone());
        assertTrue(dto2.getOwnerPhone().replace(" ", "").contains("9876543210"));

        // 3. Minimum test 3: Description WITHOUT owner details does not invent an owner name
        ParsedPropertyDTO dto3 = propertyParserService.parseAndSave("2 BHK flat in Nanda Nagar Indore with rent 25000 9876543210");
        assertNotNull(dto3);
        assertEquals("Not Specified", dto3.getOwnerName(), "Description without owner name must not invent an owner name");
        assertEquals("+91 98765 43210", dto3.getOwnerPhone());

        ParsedPropertyDTO dto3b = propertyParserService.parseAndSave("2 BHK flat in Vijay Nagar Indore with rent 20000 deposit 40000 2 baths");
        assertNotNull(dto3b);
        assertEquals("Not Specified", dto3b.getOwnerName());
        assertEquals("Not Specified", dto3b.getOwnerPhone());

        // 4. Minimum test 4: Unrelated words following the phone/name boundary are not swallowed into ownerName
        ParsedPropertyDTO dto4 = propertyParserService.parseAndSave("2 BHK flat in Palasia Name is Piyushi Saha number is 9131670191 facing east ready to move");
        assertNotNull(dto4);
        assertEquals("Piyushi Saha", dto4.getOwnerName());
        assertEquals("+91 91316 70191", dto4.getOwnerPhone());
        assertEquals("East Facing", dto4.getVastuFacing());
        assertEquals("Ready To Move", dto4.getPossessionDate());

        // 5. Natural variations:
        // Variation A: "Owner Piyushi Saha, contact 9131670191"
        ParsedPropertyDTO dtoVarA = propertyParserService.parseAndSave("Flat in Rau Owner Piyushi Saha, contact 9131670191");
        assertNotNull(dtoVarA);
        assertEquals("Piyushi Saha", dtoVarA.getOwnerName());
        assertEquals("+91 91316 70191", dtoVarA.getOwnerPhone());

        // Variation B: "Contact person Piyushi Saha - 9131670191"
        ParsedPropertyDTO dtoVarB = propertyParserService.parseAndSave("Flat in Rau Contact person Piyushi Saha - 9131670191");
        assertNotNull(dtoVarB);
        assertEquals("Piyushi Saha", dtoVarB.getOwnerName());
        assertEquals("+91 91316 70191", dtoVarB.getOwnerPhone());

        // Variation C: "Piyushi Saha 9131670191"
        ParsedPropertyDTO dtoVarC = propertyParserService.parseAndSave("2 BHK flat in Rau rent 15000 Piyushi Saha 9131670191");
        assertNotNull(dtoVarC);
        assertEquals("Piyushi Saha", dtoVarC.getOwnerName());
        assertEquals("+91 91316 70191", dtoVarC.getOwnerPhone());
    }
}
