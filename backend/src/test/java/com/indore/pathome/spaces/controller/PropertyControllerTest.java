package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import com.indore.pathome.spaces.dto.AvailabilityStatus;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ParserInputSource;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.indore.pathome.spaces.service.BatchPropertyPublishingService;
import com.indore.pathome.spaces.service.ParserLearningCaptureService;
import com.indore.pathome.spaces.service.ParserLearningService;
import com.indore.pathome.spaces.service.PropertyParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.*;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class PropertyControllerTest {

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private PropertyMediaAssetRepository mediaAssetRepository;

    @Mock
    private CloudinaryService cloudinaryService;

    @Mock
    private PropertyParserService propertyParserService;

    @Mock
    private ParserLearningService parserLearningService;

    @Mock
    private ParserLearningCaptureService parserLearningCaptureService;

    @Mock
    private BatchPropertyPublishingService batchPropertyPublishingService;

    @InjectMocks
    private PropertyController propertyController;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testParseNaturalLanguagePrompt_ReturnsParsedDTO() {
        ParsedPropertyDTO expectedDto = new ParsedPropertyDTO("3 BHK", "Flat", "Indore", "Vijay Nagar", "", "₹22,000", 22000.0, "East Facing", List.of(), "Title", "Label", true);
        when(propertyParserService.parse(anyString())).thenReturn(expectedDto);

        Map<String, String> request = Map.of("prompt", "3bhk in Vijay Nagar for 22000");
        ResponseEntity<ParsedPropertyDTO> response = propertyController.parseNaturalLanguagePrompt(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("3 BHK", response.getBody().getBhk());
        assertEquals("Vijay Nagar", response.getBody().getSector());
        verify(parserLearningService).quarantinePredictions(
                argThat(items -> items.size() == 1 && items.get(0) == expectedDto),
                eq(ParserInputSource.UNKNOWN));
    }

    @Test
    public void testGetAllActiveProperties_ReturnsListings() {
        com.indore.pathome.spaces.entity.RentalDetails rental = new com.indore.pathome.spaces.entity.RentalDetails();
        rental.setTitle("Test Property");
        when(listingRepository.findByStatus(ListingStatus.ACTIVE)).thenReturn(List.of(rental));

        ResponseEntity<List<Listing>> response = propertyController.getAllActiveProperties(null, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertFalse(response.getBody().isEmpty());
    }

    @Test
    public void repeatedMediaUploadRequestReturnsExistingAssetWithoutUploadingAgain() {
        RentalDetails listing = new RentalDetails();
        PropertyMediaAsset existingAsset = new PropertyMediaAsset();
        existingAsset.setListingId(20L);
        existingAsset.setUploadRequestId("media-20-a1b2c3d4");
        existingAsset.setMediaUrl("https://cdn.example/property.webp");

        when(listingRepository.findById(20L)).thenReturn(Optional.of(listing));
        when(mediaAssetRepository.findByListingIdAndUploadRequestId(20L, "media-20-a1b2c3d4"))
                .thenReturn(Optional.of(existingAsset));

        ResponseEntity<?> response = propertyController.uploadTaggedMediaAsset(
                20L,
                new MockMultipartFile("file", "property.webp", "image/webp", new byte[]{1}),
                "GENERAL",
                "IMAGE",
                "Property photo",
                true,
                "Nanda Nagar",
                "₹30,000",
                "East Facing",
                "media-20-a1b2c3d4");

        assertEquals(200, response.getStatusCode().value());
        assertSame(existingAsset, response.getBody());
        verifyNoInteractions(cloudinaryService);
        verify(mediaAssetRepository, never()).save(any(PropertyMediaAsset.class));
    }

    @Test
    public void uploadTaggedMediaReturnsNotFoundWhenListingMissing() {
        when(listingRepository.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = propertyController.uploadTaggedMediaAsset(
                999L,
                new MockMultipartFile("file", "photo.webp", "image/webp", new byte[]{1}),
                "GENERAL",
                "IMAGE",
                "Caption",
                false,
                "Sector A",
                "₹20,000",
                "North Facing",
                "media-999-validid");

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Property listing not found", response.getBody());
        verifyNoInteractions(cloudinaryService);
    }

    @Test
    public void uploadTaggedMediaRejectsInvalidUploadRequestId() {
        RentalDetails listing = new RentalDetails();
        when(listingRepository.findById(20L)).thenReturn(Optional.of(listing));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> propertyController.uploadTaggedMediaAsset(
                        20L,
                        new MockMultipartFile("file", "photo.webp", "image/webp", new byte[]{1}),
                        "GENERAL",
                        "IMAGE",
                        "Caption",
                        false,
                        "Sector A",
                        "₹20,000",
                        "North Facing",
                        "invalid/id!@#"));

        assertTrue(exception.getMessage().contains("Media upload identifier is invalid"));
        verifyNoInteractions(cloudinaryService);
    }

    @Test
    public void uploadTaggedMediaSuccessfullyUploadsAndPersistsNewAsset() {
        RentalDetails listing = new RentalDetails();
        listing.setId(20L);
        listing.setSector("Vijay Nagar");
        listing.setCity("Indore");

        when(listingRepository.findById(20L)).thenReturn(Optional.of(listing));
        when(mediaAssetRepository.findByListingIdAndUploadRequestId(20L, "media-20-12345678"))
                .thenReturn(Optional.empty());
        when(cloudinaryService.uploadImage(any(), eq("media-20-12345678")))
                .thenReturn("https://cdn.example/photo.webp");
        when(mediaAssetRepository.save(any(PropertyMediaAsset.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "photo.webp", "image/webp", new byte[]{1, 2, 3});
        ResponseEntity<?> response = propertyController.uploadTaggedMediaAsset(
                20L,
                file,
                "LIVING_ROOM",
                "IMAGE",
                "Spacious Living Room",
                true,
                "Vijay Nagar",
                "₹25,000",
                "East Facing",
                "media-20-12345678");

        assertEquals(201, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof PropertyMediaAsset);
        PropertyMediaAsset asset = (PropertyMediaAsset) response.getBody();
        assertEquals("https://cdn.example/photo.webp", asset.getMediaUrl());
        assertEquals("media-20-12345678", asset.getUploadRequestId());
        assertEquals(20L, asset.getListingId());
        assertTrue(asset.getIsPrimaryCover());
        verify(cloudinaryService).uploadImage(file, "media-20-12345678");
        verify(mediaAssetRepository).save(any(PropertyMediaAsset.class));
        verify(listingRepository).save(listing);
        assertEquals("https://cdn.example/photo.webp", listing.getMediaGalleryUrls());
    }

    @Test
    public void createFromParsedPromptRejectsUnreviewedData() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setBhk("2 BHK");
        dto.setType("Flat");
        dto.setCity("Indore");
        dto.setSector("Vijay Nagar");
        dto.setOwnerPhone("+91 98260 12345");
        dto.setRentAmount(30000.0);
        dto.setDepositVal("60000 Security Deposit");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> propertyController.createFromParsedPrompt(dto));

        assertTrue(exception.getMessage().contains("administrator must review"));
        verifyNoInteractions(listingRepository);
    }

    @Test
    public void createFromParsedPromptMapsConfirmedExtractionWithoutDefaults() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setAdminVerified(true);
        dto.setTitle("2 BHK Flat in Vijay Nagar");
        dto.setDescription("Original admin-reviewed description");
        dto.setBhk("2 BHK");
        dto.setType("Flat");
        dto.setStatus("LIVE");
        dto.setCity("Indore");
        dto.setSector("Vijay Nagar");
        dto.setAddress("Vijay Nagar, Indore");
        dto.setOwnerName("Dipesh Patidar");
        dto.setOwnerPhone("+91 98260 12345");
        dto.setBathrooms("3 Baths");
        dto.setRentAmount(30000.0);
        dto.setDepositVal("1+1 60000 Security Deposit");
        dto.setBrokerageVal("₹15,000");
        dto.setBrokerageDays("15 Days");
        dto.setPossessionDate("15 Nov 2026");
        dto.setAvailabilityStatus(AvailabilityStatus.AVAILABLE_FROM_DATE);
        dto.setAvailableFrom(LocalDate.of(2026, 11, 15));

        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> {
            Listing listing = invocation.getArgument(0);
            listing.setId(42L);
            return listing;
        });

        ResponseEntity<Map<String, Object>> response = propertyController.createFromParsedPrompt(dto);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(42L, response.getBody().get("propertyId"));
        var listingCaptor = org.mockito.ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(listingCaptor.capture());
        RentalDetails saved = (RentalDetails) listingCaptor.getValue();
        assertEquals("Dipesh Patidar", saved.getOwnerName());
        assertEquals(3, saved.getBathroomCount());
        assertEquals(60000, saved.getSecurityDeposit().intValueExact());
        assertEquals(2, saved.getSecurityDepositMonths());
        assertEquals(15000, saved.getBrokerageAmount().intValueExact());
        assertEquals(15, saved.getBrokerageDays());
        assertEquals("15 Nov 2026", saved.getPossessionDateText());
        assertEquals(LocalDate.of(2026, 11, 15), saved.getAvailableFrom().toLocalDate());
        verify(propertyParserService).confirmLocality("Indore", "Vijay Nagar", 30000.0);
        verify(parserLearningCaptureService).captureAfterSuccessfulPublish(dto, 42L);
    }

    @Test
    public void batchPublishReturnsOriginalRequestIndexForEverySuccessfulProperty() {
        AtomicLong ids = new AtomicLong(100L);
        when(batchPropertyPublishingService.publish(any(RentalDetails.class), anyList())).thenAnswer(invocation -> {
            Listing listing = invocation.getArgument(0);
            listing.setId(ids.incrementAndGet());
            return listing;
        });

        Map<String, Object> first = validBatchProperty("2 BHK", "Vijay Nagar");
        Map<String, Object> invalid = validBatchProperty("1 BHK", "Palasia");
        invalid.put("adminVerified", false);
        Map<String, Object> third = validBatchProperty("3 BHK", "Mahalaxmi Nagar");

        ResponseEntity<Map<String, Object>> response = propertyController.createBatchProperties(
                Map.of("listings", List.of(first, invalid, third)));

        assertEquals(2, response.getBody().get("successCount"));
        assertEquals(1, response.getBody().get("failedCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created =
                (List<Map<String, Object>>) response.getBody().get("createdListings");
        assertEquals(List.of(0, 2), created.stream().map(item -> item.get("requestIndex")).toList());
        assertEquals(List.of(101L, 102L), created.stream().map(item -> item.get("id")).toList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed =
                (List<Map<String, Object>>) response.getBody().get("failedListings");
        assertEquals(
                "Review this property's required details and try publishing it again.",
                failed.get(0).get("error"));
        assertFalse(failed.get(0).get("error").toString().contains("administrator must review"));
    }

    @Test
    public void batchPublishingMapsStructuredAvailabilityFromReviewedPayload() {
        when(batchPropertyPublishingService.publish(any(RentalDetails.class), anyList())).thenAnswer(invocation -> {
            RentalDetails listing = invocation.getArgument(0);
            listing.setId(201L);
            return listing;
        });

        Map<String, Object> property = validBatchProperty("2 BHK", "Vijay Nagar");
        property.put("possessionDate", "15 Nov 2026");
        property.put("availabilityStatus", "AVAILABLE_FROM_DATE");
        property.put("availableFrom", "2026-11-15");

        propertyController.createBatchProperties(Map.of("listings", List.of(property)));

        var listingCaptor = org.mockito.ArgumentCaptor.forClass(RentalDetails.class);
        verify(batchPropertyPublishingService).publish(listingCaptor.capture(), anyList());
        RentalDetails published = listingCaptor.getValue();
        assertEquals("15 Nov 2026", published.getPossessionDateText());
        assertEquals(LocalDate.of(2026, 11, 15), published.getAvailableFrom().toLocalDate());
    }

    @Test
    public void directPropertyCreationMapsStructuredAvailabilityDate() {
        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> property = new HashMap<>();
        property.put("ownerPhoneNumber", "+91 98260 12345");
        property.put("sector", "Vijay Nagar");
        property.put("monthlyRent", 30000);
        property.put("securityDeposit", 60000);
        property.put("bhkCount", "2 BHK");
        property.put("propertyType", "Flat");
        property.put("city", "Indore");
        property.put("possessionDate", "15 Nov 2026");
        property.put("availabilityStatus", "AVAILABLE_FROM_DATE");
        property.put("availableFrom", "2026-11-15");

        ResponseEntity<Listing> response = propertyController.createProperty(property);

        assertEquals(201, response.getStatusCode().value());
        var listingCaptor = org.mockito.ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(listingCaptor.capture());
        RentalDetails saved = (RentalDetails) listingCaptor.getValue();
        assertEquals("15 Nov 2026", saved.getPossessionDateText());
        assertEquals(LocalDate.of(2026, 11, 15), saved.getAvailableFrom().toLocalDate());
        verify(propertyParserService).confirmLocality("Indore", "Vijay Nagar", 30000.0);
    }

    private Map<String, Object> validBatchProperty(String bhk, String sector) {
        Map<String, Object> property = new HashMap<>();
        property.put("adminVerified", true);
        property.put("title", bhk + " Flat in " + sector);
        property.put("description", "Administrator-reviewed property description");
        property.put("bhk", bhk);
        property.put("type", "Flat");
        property.put("status", "LIVE");
        property.put("city", "Indore");
        property.put("sector", sector);
        property.put("ownerPhone", "+91 98260 12345");
        property.put("rentAmount", 18000.0);
        property.put("depositVal", "36000 Security Deposit");
        return property;
    }
}
