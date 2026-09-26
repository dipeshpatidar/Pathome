package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.ParsedPropertyDTO;
import com.indore.pathome.spaces.dto.AvailabilityStatus;
import com.indore.pathome.spaces.dto.CreatePropertyVisitRequest;
import com.indore.pathome.spaces.dto.PublicDiscoveryPage;
import com.indore.pathome.spaces.dto.PublicDiscoveryResponse;
import com.indore.pathome.spaces.dto.PublicPropertyResponse;
import com.indore.pathome.spaces.dto.PublicSearchSuggestion;
import com.indore.pathome.spaces.dto.PublicSearchSuggestions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ParserInputSource;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.Role;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.entity.User;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import com.indore.pathome.spaces.repository.UserRepository;
import com.indore.pathome.spaces.service.FailedUploadService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.*;
import java.time.LocalDate;
import java.math.BigDecimal;
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
    private FailedUploadService failedUploadService;

    @Mock
    private PropertyParserService propertyParserService;

    @Mock
    private ParserLearningService parserLearningService;

    @Mock
    private ParserLearningCaptureService parserLearningCaptureService;

    @Mock
    private BatchPropertyPublishingService batchPropertyPublishingService;

    @Mock
    private com.indore.pathome.spaces.repository.PropertyUploadDraftRepository draftRepository;

    @Mock
    private com.indore.pathome.spaces.repository.LocalityRepository localityRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PropertyVisitRequestRepository propertyVisitRequestRepository;

    @InjectMocks
    private PropertyController propertyController;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        propertyController.setDraftRepository(draftRepository);
        propertyController.setLocalityRepository(localityRepository);
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
    public void publicDiscoveryReturnsSafeProperties() throws Exception {
        com.indore.pathome.spaces.entity.RentalDetails rental = new com.indore.pathome.spaces.entity.RentalDetails();
        rental.setId(7L);
        rental.setTitle("Test Property");
        rental.setDescription("2 BHK, 525 sqft, ₹30,000. Owner: Ramesh Sharma (+91 98765 43210)\nLessor: Asha Verma 99887 76655");
        rental.setCity("Indore");
        rental.setSector("Vijay Nagar");
        rental.setAddress("Private address must not be returned");
        rental.setOwnerName("Private owner");
        rental.setOwnerPhoneNumber("+91 98260 12345");
        rental.setLatitude(22.7);
        rental.setLongitude(75.8);
        rental.setBhkCount("2 BHK");
        rental.setMonthlyRent(BigDecimal.valueOf(25000));
        rental.setStatus(ListingStatus.ACTIVE);
        // Paginated discovery: page 0, size 6, no next page
        when(listingRepository.findByStatusOrderByIdDesc(eq(ListingStatus.ACTIVE), any()))
                .thenReturn(new SliceImpl<Listing>(List.of(rental), PageRequest.of(0, 6), false));
        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(7L)).thenReturn(List.of());

        ResponseEntity<PublicDiscoveryPage> response = propertyController.getAllActiveProperties(null, null, 0);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertFalse(response.getBody().properties().isEmpty());
        PublicDiscoveryResponse property = response.getBody().properties().get(0);
        assertEquals("Indore", property.city());
        assertEquals("Vijay Nagar", property.sector());
        assertEquals(25000, property.monthlyRent().intValueExact());
        // Discovery card must not expose ownerPhone, address, coordinates
        assertFalse(java.util.Arrays.stream(PublicDiscoveryResponse.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains("owner") || name.contains("address")
                        || name.contains("latitude") || name.contains("longitude")));
        String serialized = new ObjectMapper().writeValueAsString(property);
        assertFalse(serialized.contains("ownerPhoneNumber"));
        assertFalse(serialized.contains("Private address"));
        assertFalse(serialized.contains("Ramesh Sharma"));
        // hasMore false — no Show More
        assertFalse(response.getBody().hasMore());
    }

    @Test
    public void publicDiscoveryFirstPageContainsAtMostSixProperties() {
        List<RentalDetails> listings = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            RentalDetails r = new RentalDetails();
            r.setId((long) i);
            r.setTitle("Property " + i);
            r.setStatus(ListingStatus.ACTIVE);
            r.setBhkCount("2 BHK");
            r.setCity("Indore");
            r.setSector("Vijay Nagar");
            listings.add(r);
        }
        // Slice has next page = true (7th property would exist)
        when(listingRepository.findByStatusOrderByIdDesc(eq(ListingStatus.ACTIVE), any()))
                .thenReturn(new SliceImpl<Listing>((List<Listing>)(List<?>) listings, PageRequest.of(0, 6), true));
        listings.forEach(l -> when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(l.getId())).thenReturn(List.of()));

        ResponseEntity<PublicDiscoveryPage> response = propertyController.getAllActiveProperties(null, null, 0);
        PublicDiscoveryPage page = response.getBody();

        assertEquals(6, page.properties().size());
        assertEquals(6, page.pageSize());
        assertEquals(0, page.page());
        assertTrue(page.hasMore()); // 7th exists
    }

    @Test
    public void publicDiscoverySecondPageAppendsNextResults() {
        RentalDetails r7 = new RentalDetails();
        r7.setId(7L);
        r7.setTitle("Property 7");
        r7.setStatus(ListingStatus.ACTIVE);
        r7.setBhkCount("2 BHK");
        r7.setCity("Indore");
        r7.setSector("Vijay Nagar");
        when(listingRepository.findByStatusOrderByIdDesc(eq(ListingStatus.ACTIVE), eq(PageRequest.of(1, 6))))
                .thenReturn(new SliceImpl<Listing>(List.of(r7), PageRequest.of(1, 6), false));
        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(7L)).thenReturn(List.of());

        ResponseEntity<PublicDiscoveryPage> response = propertyController.getAllActiveProperties(null, null, 1);
        PublicDiscoveryPage page = response.getBody();

        assertEquals(1, page.properties().size());
        assertEquals(7L, page.properties().get(0).id());
        assertEquals(1, page.page());
        assertFalse(page.hasMore()); // last page
    }

    @Test
    public void publicDiscoveryBatchQueriesMediaAssetsWithoutNPlusOne() {
        RentalDetails r1 = new RentalDetails();
        r1.setId(101L);
        r1.setTitle("Prop 101");
        r1.setStatus(ListingStatus.ACTIVE);
        r1.setBhkCount("2 BHK");
        r1.setCity("Indore");
        r1.setSector("Vijay Nagar");

        RentalDetails r2 = new RentalDetails();
        r2.setId(102L);
        r2.setTitle("Prop 102");
        r2.setStatus(ListingStatus.ACTIVE);
        r2.setBhkCount("3 BHK");
        r2.setCity("Indore");
        r2.setSector("Palasia");

        PropertyMediaAsset a1 = new PropertyMediaAsset();
        a1.setListingId(101L);
        a1.setMediaUrl("https://res.cloudinary.com/demo/image/upload/v1/prop101.webp");
        a1.setMediaType(MediaType.IMAGE);
        a1.setIsPrimaryCover(true);

        PropertyMediaAsset a2 = new PropertyMediaAsset();
        a2.setListingId(102L);
        a2.setMediaUrl("https://res.cloudinary.com/demo/image/upload/v1/prop102.webp");
        a2.setMediaType(MediaType.IMAGE);
        a2.setIsPrimaryCover(true);

        when(listingRepository.findByStatusOrderByIdDesc(eq(ListingStatus.ACTIVE), any()))
                .thenReturn(new SliceImpl<Listing>(List.of(r1, r2), PageRequest.of(0, 6), false));
        when(mediaAssetRepository.findByListingIdInOrderByUploadedAtDesc(eq(List.of(101L, 102L))))
                .thenReturn(List.of(a1, a2));

        ResponseEntity<PublicDiscoveryPage> response = propertyController.getAllActiveProperties(null, null, 0);

        assertEquals(2, response.getBody().properties().size());
        assertNotNull(response.getBody().properties().get(0).coverImageUrl());
        assertNotNull(response.getBody().properties().get(1).coverImageUrl());
        verify(mediaAssetRepository).findByListingIdInOrderByUploadedAtDesc(eq(List.of(101L, 102L)));
        verify(mediaAssetRepository, never()).findByListingIdOrderByUploadedAtDesc(101L);
        verify(mediaAssetRepository, never()).findByListingIdOrderByUploadedAtDesc(102L);
    }

    @Test
    public void publicDiscoveryGenuineNoMediaListingReturnsNullCover() {
        RentalDetails listing = new RentalDetails();
        listing.setId(17L);
        listing.setTitle("No-media property");
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setBhkCount("2 BHK");
        listing.setCity("Indore");
        listing.setSector("Nanda Nagar");
        when(listingRepository.findByStatusOrderByIdDesc(eq(ListingStatus.ACTIVE), any()))
                .thenReturn(new SliceImpl<Listing>(List.of(listing), PageRequest.of(0, 6), false));
        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(17L)).thenReturn(List.of());

        PublicDiscoveryResponse item = propertyController.getAllActiveProperties(null, null, 0).getBody().properties().get(0);
        assertNull(item.coverImageUrl()); // correctly null — no fabrication
        assertNull(item.coverRoomTag());
        assertEquals(0, item.mediaCount());
    }

    @Test
    public void publicDiscoveryUsesOnlyTheSelectedCoversStoredTag() {
        RentalDetails listing = new RentalDetails();
        listing.setId(18L);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setCity("Indore");
        PropertyMediaAsset first = new PropertyMediaAsset();
        first.setMediaUrl("https://cdn.example/first.webp");
        first.setMediaType(MediaType.IMAGE);
        first.setRoomTag(RoomTag.KITCHEN);
        PropertyMediaAsset primary = new PropertyMediaAsset();
        primary.setMediaUrl("https://cdn.example/cover.webp");
        primary.setMediaType(MediaType.IMAGE);
        primary.setRoomTag(RoomTag.LIVING_ROOM);
        primary.setIsPrimaryCover(true);
        when(listingRepository.findByStatusOrderByIdDesc(eq(ListingStatus.ACTIVE), any()))
                .thenReturn(new SliceImpl<Listing>(List.of(listing), PageRequest.of(0, 6), false));
        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(18L)).thenReturn(List.of(first, primary));

        PublicDiscoveryResponse item = propertyController.getAllActiveProperties(null, null, 0).getBody().properties().get(0);

        assertEquals("https://cdn.example/cover.webp", item.coverImageUrl());
        assertEquals(RoomTag.LIVING_ROOM, item.coverRoomTag());
        assertEquals(2, item.mediaCount());
        verify(mediaAssetRepository, times(1)).findByListingIdOrderByUploadedAtDesc(18L);
    }

    @Test
    public void publicDiscoveryLegacyGalleryDoesNotInventCoverTag() {
        RentalDetails listing = new RentalDetails();
        listing.setId(19L);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setMediaGalleryUrls("https://cdn.example/legacy.webp");
        when(listingRepository.findByStatusOrderByIdDesc(eq(ListingStatus.ACTIVE), any()))
                .thenReturn(new SliceImpl<Listing>(List.of(listing), PageRequest.of(0, 6), false));
        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(19L)).thenReturn(List.of());

        PublicDiscoveryResponse item = propertyController.getAllActiveProperties(null, null, 0).getBody().properties().get(0);

        assertEquals("https://cdn.example/legacy.webp", item.coverImageUrl());
        assertNull(item.coverRoomTag());
    }

    @Test
    public void publicDiscoveryFiltersByCityAndSector() {
        when(listingRepository.findByStatusAndCityIgnoreCaseAndSectorIgnoreCaseOrderByIdDesc(
                eq(ListingStatus.ACTIVE), eq("Indore"), eq("Vijay Nagar"), any()))
                .thenReturn(new SliceImpl<Listing>(List.of(), PageRequest.of(0, 6), false));

        ResponseEntity<PublicDiscoveryPage> response = propertyController
                .getAllActiveProperties("Vijay Nagar", "Indore", 0);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().properties().isEmpty());
        verify(listingRepository).findByStatusAndCityIgnoreCaseAndSectorIgnoreCaseOrderByIdDesc(
                eq(ListingStatus.ACTIVE), eq("Indore"), eq("Vijay Nagar"), any());
    }

    @Test
    public void publicDiscoveryFiltersByCityOnlyAndSectorOnly() {
        when(listingRepository.findByStatusAndCityIgnoreCaseOrderByIdDesc(
                eq(ListingStatus.ACTIVE), eq("Indore"), any()))
                .thenReturn(new SliceImpl<Listing>(List.of(), PageRequest.of(0, 6), false));
        when(listingRepository.findByStatusAndSectorIgnoreCaseOrderByIdDesc(
                eq(ListingStatus.ACTIVE), eq("Nipania"), any()))
                .thenReturn(new SliceImpl<Listing>(List.of(), PageRequest.of(0, 6), false));

        propertyController.getAllActiveProperties(null, "Indore", 0);
        propertyController.getAllActiveProperties("Nipania", null, 0);

        verify(listingRepository).findByStatusAndCityIgnoreCaseOrderByIdDesc(
                eq(ListingStatus.ACTIVE), eq("Indore"), any());
        verify(listingRepository).findByStatusAndSectorIgnoreCaseOrderByIdDesc(
                eq(ListingStatus.ACTIVE), eq("Nipania"), any());
    }

    @Test
    public void publicPropertyDetailHidesInactiveAndMissingListings() {
        when(listingRepository.findById(99L)).thenReturn(Optional.empty());
        assertEquals(404, propertyController.getPropertyById(99L).getStatusCode().value());

        RentalDetails closed = new RentalDetails();
        closed.setStatus(ListingStatus.CLOSED);
        when(listingRepository.findById(100L)).thenReturn(Optional.of(closed));
        assertEquals(404, propertyController.getPropertyById(100L).getStatusCode().value());
    }

    @Test
    public void authenticatedTenantCanCreateAnUnscheduledVisitRequest() {
        RentalDetails listing = new RentalDetails();
        listing.setId(77L);
        listing.setStatus(ListingStatus.ACTIVE);
        User tenant = new User();
        tenant.setId(8L);
        tenant.setEmail("tenant@example.com");
        tenant.setRole(Role.ROLE_TENANT);
        when(listingRepository.findById(77L)).thenReturn(Optional.of(listing));
        when(userRepository.findByEmail("tenant@example.com")).thenReturn(Optional.of(tenant));
        when(propertyVisitRequestRepository.findByTenantIdAndListingId(8L, 77L)).thenReturn(Optional.empty());
        when(propertyVisitRequestRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            var saved = invocation.getArgument(0, com.indore.pathome.spaces.entity.PropertyVisitRequest.class);
            saved.setId(15L);
            return saved;
        });

        var response = propertyController.requestVisit(77L, new CreatePropertyVisitRequest(
                BigDecimal.valueOf(20000), BigDecimal.valueOf(28000), "Vijay Nagar", "Within a month",
                "Saturday afternoon", "Parking preferred"),
                new UsernamePasswordAuthenticationToken("tenant@example.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_TENANT"))));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("RECEIVED", response.getBody().status());
        assertTrue(response.getBody().message().contains("before confirming"));
        verify(propertyVisitRequestRepository).saveAndFlush(any());
    }

    @Test
    public void duplicateTenantVisitRequestReturnsExistingInterestRecord() {
        RentalDetails listing = new RentalDetails();
        listing.setId(77L);
        listing.setStatus(ListingStatus.ACTIVE);
        User tenant = new User();
        tenant.setId(8L);
        tenant.setRole(Role.ROLE_TENANT);
        PropertyVisitRequest existing = new PropertyVisitRequest();
        existing.setId(15L);
        existing.setListing(listing);
        existing.setTenant(tenant);
        when(listingRepository.findById(77L)).thenReturn(Optional.of(listing));
        when(userRepository.findByEmail("tenant@example.com")).thenReturn(Optional.of(tenant));
        when(propertyVisitRequestRepository.findByTenantIdAndListingId(8L, 77L)).thenReturn(Optional.of(existing));

        var response = propertyController.requestVisit(77L, new CreatePropertyVisitRequest(
                null, null, null, null, "Saturday afternoon", null),
                new UsernamePasswordAuthenticationToken("tenant@example.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_TENANT"))));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(15L, response.getBody().requestId());
        verify(propertyVisitRequestRepository, never()).saveAndFlush(any());
    }

    @Test
    public void publicListAndDetailExposeTheSameSafeMedia() throws Exception {
        RentalDetails listing = new RentalDetails();
        listing.setId(31L);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setBhkCount("2 BHK");
        listing.setCity("Indore");
        listing.setSector("Vijay Nagar");
        PropertyMediaAsset image = new PropertyMediaAsset();
        image.setMediaUrl("https://res.cloudinary.com/demo/image/upload/v12345/prop.webp");
        image.setMediaType(MediaType.IMAGE);
        image.setIsPrimaryCover(true);
        image.setRoomTag(RoomTag.BALCONY);
        when(listingRepository.findByStatusOrderByIdDesc(eq(ListingStatus.ACTIVE), any()))
                .thenReturn(new SliceImpl<Listing>(List.of(listing), PageRequest.of(0, 6), false));
        when(listingRepository.findById(31L)).thenReturn(Optional.of(listing));
        when(mediaAssetRepository.findByListingIdOrderByUploadedAtDesc(31L)).thenReturn(List.of(image));

        PublicDiscoveryResponse listItem = propertyController.getAllActiveProperties(null, null, 0).getBody().properties().get(0);
        PublicPropertyResponse detailResponse = propertyController.getPropertyById(31L).getBody();

        // Discovery returns cover URL (possibly width-transformed); detail returns full media collection
        assertNotNull(listItem.coverImageUrl());
        assertTrue(listItem.coverImageUrl().contains("cloudinary.com"));
        assertEquals(RoomTag.BALCONY, listItem.coverRoomTag());
        // Detail still returns the full safe media list
        assertEquals("https://res.cloudinary.com/demo/image/upload/v12345/prop.webp", detailResponse.media().get(0).mediaUrl());
        assertEquals(RoomTag.BALCONY, detailResponse.media().get(0).roomTag());
        assertFalse(java.util.Arrays.stream(detailResponse.media().get(0).getClass().getRecordComponents())
                .map(component -> component.getName().toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains("upload") || name.contains("publicid") || name.contains("staging")));
        String serializedDetail = new ObjectMapper().writeValueAsString(detailResponse);
        assertFalse(serializedDetail.contains("uploadRequestId"));
        assertFalse(serializedDetail.contains("cloudinaryPublicId"));
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
        verify(listingRepository).appendMediaGalleryUrlAtomic(20L, "https://cdn.example/photo.webp");
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

    @Test
    public void createFromParsedPrompt_idempotentOnPublishedDraft_preventsDuplicateCreation() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setAdminVerified(true);
        dto.setDraftId("draft-published-xyz");
        dto.setTitle("2 BHK Luxury Flat");
        dto.setBhk("2 BHK");
        dto.setType("Flat");
        dto.setCity("Indore");
        dto.setSector("Vijay Nagar");
        dto.setOwnerPhone("+91 98260 12345");
        dto.setRentAmount(25000.0);
        dto.setDepositVal("50000 Security Deposit");

        com.indore.pathome.spaces.entity.PropertyUploadDraft existingDraft =
                new com.indore.pathome.spaces.entity.PropertyUploadDraft();
        existingDraft.setDraftId("draft-published-xyz");
        existingDraft.setPublishedPropertyId(42L);
        existingDraft.setStatus("PUBLISHED");

        when(draftRepository.findByDraftId("draft-published-xyz")).thenReturn(Optional.of(existingDraft));
        when(draftRepository.findByDraftIdForUpdate("draft-published-xyz")).thenReturn(Optional.of(existingDraft));

        ResponseEntity<Map<String, Object>> response = propertyController.createFromParsedPrompt(dto);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("SUCCESS", response.getBody().get("status"));
        assertEquals(42L, response.getBody().get("propertyId"));

        // Crucial: listingRepository.save must NOT be called again (zero duplicate DB inserts)
        verify(listingRepository, never()).save(any());
    }

    @Test
    public void createFromParsedPrompt_durableDatabaseListingIdempotency_returnsAuthoritativeListing() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setAdminVerified(true);
        dto.setDraftId("draft-persisted-db-77");
        dto.setTitle("3 BHK Penthouse");
        dto.setBhk("3 BHK");
        dto.setType("Penthouse");
        dto.setCity("Indore");
        dto.setSector("AB Road");
        dto.setOwnerPhone("+91 98260 12345");
        dto.setRentAmount(60000.0);
        dto.setDepositVal("120000 Security Deposit");

        RentalDetails existingListing = new RentalDetails();
        existingListing.setId(77L);
        existingListing.setOriginDraftId("draft-persisted-db-77");

        when(listingRepository.findByOriginDraftId("draft-persisted-db-77")).thenReturn(Optional.of(existingListing));

        ResponseEntity<Map<String, Object>> response = propertyController.createFromParsedPrompt(dto);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("SUCCESS", response.getBody().get("status"));
        assertEquals(77L, response.getBody().get("propertyId"));

        verify(listingRepository, never()).save(any());
    }

    @Test
    public void createFromParsedPrompt_concurrentRaceUniqueConstraint_resolvesToWinningListing() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setAdminVerified(true);
        dto.setDraftId("draft-race-condition");
        dto.setTitle("2 BHK Apartment");
        dto.setBhk("2 BHK");
        dto.setType("Apartment");
        dto.setCity("Indore");
        dto.setSector("Bhawarkua");
        dto.setOwnerPhone("+91 98260 12345");
        dto.setRentAmount(22000.0);
        dto.setDepositVal("44000 Security Deposit");
        dto.setDescription("Furnished apartment");
        dto.setBathrooms("2 Bath");

        // Initially listing repository finds nothing before save
        when(listingRepository.findByOriginDraftId("draft-race-condition")).thenReturn(Optional.empty());

        com.indore.pathome.spaces.entity.PropertyUploadDraft draft =
                new com.indore.pathome.spaces.entity.PropertyUploadDraft();
        draft.setDraftId("draft-race-condition");
        draft.setStatus("DRAFT");
        draft.setPublishedPropertyId(null);
        when(draftRepository.findByDraftIdForUpdate("draft-race-condition")).thenReturn(Optional.of(draft));

        // Another concurrent thread successfully inserts first; this thread hits unique constraint violation
        RentalDetails winningListing = new RentalDetails();
        winningListing.setId(999L);
        winningListing.setOriginDraftId("draft-race-condition");

        when(listingRepository.save(any(Listing.class))).thenAnswer(inv -> {
            // Once duplicate key is hit, findByOriginDraftId returns the winner
            when(listingRepository.findByOriginDraftId("draft-race-condition")).thenReturn(Optional.of(winningListing));
            throw new org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint idx_listings_origin_draft_id");
        });

        ResponseEntity<Map<String, Object>> response = propertyController.createFromParsedPrompt(dto);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("SUCCESS", response.getBody().get("status"));
        assertEquals(999L, response.getBody().get("propertyId"));
    }

    @Test
    public void createFromParsedPrompt_adminOwnershipIDOR_deniesUnauthorizedPublishing() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setAdminVerified(true);
        dto.setDraftId("draft-admin-b-only");
        dto.setTitle("1 BHK Flat");
        dto.setBhk("1 BHK");
        dto.setType("Flat");
        dto.setCity("Indore");
        dto.setSector("Rau");
        dto.setOwnerPhone("+91 98260 12345");
        dto.setRentAmount(12000.0);
        dto.setDepositVal("24000 Security Deposit");

        com.indore.pathome.spaces.entity.PropertyUploadDraft draft =
                new com.indore.pathome.spaces.entity.PropertyUploadDraft();
        draft.setDraftId("draft-admin-b-only");
        draft.setAdminId("admin-b@pathome.in");
        draft.setStatus("DRAFT");

        when(draftRepository.findByDraftIdForUpdate("draft-admin-b-only")).thenReturn(Optional.of(draft));

        // Mock current authenticated user as admin-a@pathome.in
        org.springframework.security.core.Authentication auth = mock(org.springframework.security.core.Authentication.class);
        when(auth.getName()).thenReturn("admin-a@pathome.in");
        org.springframework.security.core.context.SecurityContext context = mock(org.springframework.security.core.context.SecurityContext.class);
        when(context.getAuthentication()).thenReturn(auth);
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);

        try {
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                    propertyController.createFromParsedPrompt(dto)
            );
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    public void createFromParsedPrompt_marksDraftAsPublishedOnSuccess() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setAdminVerified(true);
        dto.setDraftId("draft-new-123");
        dto.setTitle("1 BHK Studio");
        dto.setBhk("1 BHK");
        dto.setType("Studio");
        dto.setCity("Indore");
        dto.setSector("Palasia");
        dto.setOwnerPhone("+91 98260 12345");
        dto.setRentAmount(15000.0);
        dto.setDepositVal("30000 Security Deposit");
        dto.setDescription("Clean studio flat in Palasia");
        dto.setBathrooms("1 Bath");

        com.indore.pathome.spaces.entity.PropertyUploadDraft draft =
                new com.indore.pathome.spaces.entity.PropertyUploadDraft();
        draft.setDraftId("draft-new-123");
        draft.setStatus("DRAFT");
        draft.setPublishedPropertyId(null);

        when(draftRepository.findByDraftId("draft-new-123")).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdForUpdate("draft-new-123")).thenReturn(Optional.of(draft));
        when(listingRepository.save(any(Listing.class))).thenAnswer(inv -> {
            Listing l = inv.getArgument(0);
            l.setId(101L);
            return l;
        });

        ResponseEntity<Map<String, Object>> response = propertyController.createFromParsedPrompt(dto);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(101L, response.getBody().get("propertyId"));

        // Verify draft was updated with published property ID and status PUBLISHING (retaining payload)
        assertEquals(101L, draft.getPublishedPropertyId());
        assertEquals("PUBLISHING", draft.getStatus());
        verify(draftRepository).save(draft);
    }

    @Test
    public void createFromParsedPrompt_retainsPayloadAndDoesNotTriggerDraftMediaCleanup() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setAdminVerified(true);
        dto.setDraftId("draft-retain-456");
        dto.setTitle("2 BHK Apartment");
        dto.setBhk("2 BHK");
        dto.setType("Apartment");
        dto.setCity("Indore");
        dto.setSector("Vijay Nagar");
        dto.setOwnerPhone("+91 98260 54321");
        dto.setRentAmount(25000.0);
        dto.setDepositVal("50000 Deposit");
        dto.setBathrooms("2 Bath");

        com.indore.pathome.spaces.entity.PropertyUploadDraft draft =
                new com.indore.pathome.spaces.entity.PropertyUploadDraft();
        draft.setDraftId("draft-retain-456");
        draft.setStatus("DRAFT");
        draft.setPayload("{\"title\":\"2 BHK Apartment\",\"details\":\"preserved\"}");
        draft.setItemCount(1);
        draft.setPublishedPropertyId(null);

        when(draftRepository.findByDraftId("draft-retain-456")).thenReturn(Optional.of(draft));
        when(draftRepository.findByDraftIdForUpdate("draft-retain-456")).thenReturn(Optional.of(draft));
        when(listingRepository.save(any(Listing.class))).thenAnswer(inv -> {
            Listing l = inv.getArgument(0);
            l.setId(202L);
            return l;
        });

        ResponseEntity<Map<String, Object>> response = propertyController.createFromParsedPrompt(dto);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(202L, response.getBody().get("propertyId"));

        // Critical P0 Invariant: Payload must NOT be cleared to "{}" on listing creation!
        assertEquals("{\"title\":\"2 BHK Apartment\",\"details\":\"preserved\"}", draft.getPayload());
        assertEquals(1, draft.getItemCount());
        assertEquals(202L, draft.getPublishedPropertyId());
        assertEquals("PUBLISHING", draft.getStatus());
    }

    @Test
    public void createFromParsedPromptMapsFloorTotalFloorsAndPreferredTenants() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setAdminVerified(true);
        dto.setBhk("2 BHK");
        dto.setType("Flat");
        dto.setStatus("LIVE");
        dto.setCity("Indore");
        dto.setSector("Bhawarkua");
        dto.setOwnerPhone("+91 98260 12345");
        dto.setRentAmount(21000.0);
        dto.setDepositVal("₹42,000 Security Deposit");
        dto.setFloor(3);
        dto.setTotalFloors(7);
        dto.setPreferredTenants(List.of("FAMILY", "WORKING_PROFESSIONALS"));

        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> {
            Listing listing = invocation.getArgument(0);
            listing.setId(301L);
            return listing;
        });

        ResponseEntity<Map<String, Object>> response = propertyController.createFromParsedPrompt(dto);
        assertEquals(200, response.getStatusCode().value());

        var captor = org.mockito.ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository, atLeastOnce()).save(captor.capture());
        RentalDetails saved = (RentalDetails) captor.getValue();
        assertEquals(3, saved.getFloorNumber());
        assertEquals(7, saved.getTotalFloors());
        assertEquals("FAMILY,WORKING_PROFESSIONALS", saved.getPreferredTenant());
        assertEquals(Boolean.TRUE, saved.getBachelorAllowed(), "bachelorAllowed must NOT be modified or derived from preferredTenants");
    }

    @Test
    public void createFromParsedPromptPreservesFloorZero() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setAdminVerified(true);
        dto.setBhk("1 BHK");
        dto.setType("Flat");
        dto.setStatus("LIVE");
        dto.setCity("Indore");
        dto.setSector("Vijay Nagar");
        dto.setOwnerPhone("+91 98260 12345");
        dto.setRentAmount(15000.0);
        dto.setDepositVal("₹30,000 Security Deposit");
        dto.setFloor(0);
        dto.setTotalFloors(4);
        dto.setPreferredTenants(List.of("ANY"));

        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> {
            Listing listing = invocation.getArgument(0);
            listing.setId(302L);
            return listing;
        });

        ResponseEntity<Map<String, Object>> response = propertyController.createFromParsedPrompt(dto);
        assertEquals(200, response.getStatusCode().value());

        var captor = org.mockito.ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository, atLeastOnce()).save(captor.capture());
        RentalDetails saved = (RentalDetails) captor.getValue();
        assertEquals(0, saved.getFloorNumber(), "Ground Floor 0 must be preserved");
        assertEquals(4, saved.getTotalFloors());
        assertEquals("ANY", saved.getPreferredTenant());
        assertEquals(Boolean.TRUE, saved.getBachelorAllowed(), "bachelorAllowed must NOT be modified by preferredTenants");
    }

    @Test
    public void createFromParsedPromptBackwardCompatibilityWhenFloorAndTenantAbsent() {
        ParsedPropertyDTO dto = new ParsedPropertyDTO();
        dto.setAdminVerified(true);
        dto.setBhk("3 BHK");
        dto.setType("House");
        dto.setStatus("LIVE");
        dto.setCity("Indore");
        dto.setSector("Saket");
        dto.setOwnerPhone("+91 98260 12345");
        dto.setRentAmount(35000.0);
        dto.setDepositVal("₹70,000 Security Deposit");
        // floor, totalFloors, preferredTenants absent (null)

        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> {
            Listing listing = invocation.getArgument(0);
            listing.setId(303L);
            return listing;
        });

        ResponseEntity<Map<String, Object>> response = propertyController.createFromParsedPrompt(dto);
        assertEquals(200, response.getStatusCode().value());

        var captor = org.mockito.ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository, atLeastOnce()).save(captor.capture());
        RentalDetails saved = (RentalDetails) captor.getValue();
        assertNull(saved.getFloorNumber());
        assertNull(saved.getTotalFloors());
        assertNull(saved.getPreferredTenant());
    }

    @Test
    public void rentalSuggestionsUseActiveCityScopedBhkRowsAndDeduplicateVariants() {
        ListingRepository.LocalitySuggestionRow first = mock(ListingRepository.LocalitySuggestionRow.class);
        when(first.getCity()).thenReturn("Indore");
        when(first.getLocality()).thenReturn("Vijay Nagar");
        when(first.getResultCount()).thenReturn(3L);
        ListingRepository.LocalitySuggestionRow duplicate = mock(ListingRepository.LocalitySuggestionRow.class);
        when(duplicate.getCity()).thenReturn("INDORE");
        when(duplicate.getLocality()).thenReturn("vijay nagar");
        when(listingRepository.findPublicRentalLocalitySuggestions(eq("indore"), eq("4BHK"), eq(""), eq(""),
                isNull(), isNull(), eq("vijay"), any()))
                .thenReturn(List.of(first, duplicate));

        var response = propertyController.getSearchSuggestions("4bhk in vijay", "Indore", 8);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().suggestions().size());
        assertEquals("SEARCH_QUERY", response.getBody().suggestions().get(0).type());
        assertEquals("4 BHK in Vijay Nagar, Indore", response.getBody().suggestions().get(0).label());
        assertEquals(3L, response.getBody().suggestions().get(0).resultCount());
        assertEquals("Vijay Nagar", response.getBody().suggestions().get(0).locality());
        verify(listingRepository, never()).findPublicRentalCitySuggestions(anyString(), any());
    }

    @Test
    public void rentalSuggestionsHandleEmptyQueryCitySelectionAndBoundedLimit() throws Exception {
        assertTrue(propertyController.getSearchSuggestions(" ", "Indore", 8).getBody().suggestions().isEmpty());
        verifyNoInteractions(listingRepository);

        ListingRepository.CitySuggestionRow pune = mock(ListingRepository.CitySuggestionRow.class);
        when(pune.getCity()).thenReturn("Pune");
        when(pune.getResultCount()).thenReturn(2L);
        when(listingRepository.findPublicRentalCitySuggestions(eq("pune"), any())).thenReturn(List.of(pune));
        when(listingRepository.findPublicRentalLocalitySuggestions(eq("indore"), eq(""), eq(""), eq(""),
                isNull(), isNull(), eq("pune"), any()))
                .thenReturn(List.of());
        var result = propertyController.getSearchSuggestions("Pune", "Indore", 999).getBody();
        assertEquals(1, result.suggestions().size());
        assertEquals("CITY", result.suggestions().get(0).type());
        assertEquals("Pune", result.suggestions().get(0).city());
        assertEquals(2L, result.suggestions().get(0).resultCount());
        verify(listingRepository).findPublicRentalCitySuggestions(eq("pune"), eq(PageRequest.of(0, 10)));

        String json = new ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("ownerPhone"));
        assertFalse(json.contains("address"));
        assertFalse(json.contains("internal"));
    }

    @Test
    public void freeTextAndStructuredRentalSearchUseRealPublicFilters() {
        when(listingRepository.searchPublicRentals(any(), any(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 6), false));

        propertyController.getAllActiveProperties(null, "Indore", "4bhk in Vijay Nagar", null, true, 0);
        verify(listingRepository).searchPublicRentals(
                eq(ListingStatus.ACTIVE), eq(com.indore.pathome.spaces.entity.ListingType.RENT),
                eq("indore"), eq(""), eq("vijay nagar"), eq("4BHK"), isNull(), eq(""),
                isNull(), isNull(), eq(PageRequest.of(0, 6)));

        propertyController.getAllActiveProperties("Vijay Nagar", "Indore", null, "4BHK", true, 0);
        verify(listingRepository).searchPublicRentals(
                eq(ListingStatus.ACTIVE), eq(com.indore.pathome.spaces.entity.ListingType.RENT),
                eq("indore"), eq("vijay nagar"), eq(""), eq("4BHK"), isNull(), eq(""),
                isNull(), isNull(), eq(PageRequest.of(0, 6)));
    }

    @Test
    public void publicSuggestionHttpEndpointReturnsOnlySafeFields() throws Exception {
        ListingRepository.LocalitySuggestionRow row = mock(ListingRepository.LocalitySuggestionRow.class);
        when(row.getCity()).thenReturn("Indore");
        when(row.getLocality()).thenReturn("Vijay Nagar");
        when(row.getResultCount()).thenReturn(2L);
        when(listingRepository.findPublicRentalCitySuggestions(eq("vijay"), any())).thenReturn(List.of());
        when(listingRepository.findPublicRentalLocalitySuggestions(eq("indore"), eq(""), eq(""), eq(""),
                isNull(), isNull(), eq("vijay"), any()))
                .thenReturn(List.of(row));

        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(propertyController).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/properties/search-suggestions").param("q", "vijay").param("city", "Indore"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.suggestions[0].label")
                        .value("Vijay Nagar, Indore"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.suggestions[0].resultCount")
                        .value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.suggestions[0].ownerPhone")
                        .doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.suggestions[0].address")
                        .doesNotExist());
    }

    @Test
    public void propertyTypeSuggestionsRequireMatchingActiveRentalInventory() throws Exception {
        ListingRepository.LocalitySuggestionRow row = mock(ListingRepository.LocalitySuggestionRow.class);
        when(row.getCity()).thenReturn("Indore");
        when(row.getLocality()).thenReturn("Vijay Nagar");
        when(row.getResultCount()).thenReturn(2L);
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), eq("2BHK"), eq("FLAT"), eq(""), isNull(), isNull(),
                eq("vijay nagar"), any())).thenReturn(List.of(row));

        var matched = propertyController.getSearchSuggestions("2bhk flat in vijay nagar", "Indore", 8).getBody();
        assertEquals(1, matched.suggestions().size());
        assertEquals("2 BHK Flat in Vijay Nagar, Indore", matched.suggestions().get(0).label());
        assertEquals(com.indore.pathome.spaces.entity.PropertyType.FLAT, matched.suggestions().get(0).propertyType());
        assertEquals(2L, matched.suggestions().get(0).resultCount());
        String publicJson = new ObjectMapper().writeValueAsString(matched);
        assertTrue(publicJson.contains("\"propertyType\":\"FLAT\""));
        assertFalse(publicJson.contains("ownerPhone"));

        var absent = propertyController.getSearchSuggestions("2bhk flat in unknown", "Indore", 8).getBody();
        assertTrue(absent.suggestions().isEmpty());
        verify(listingRepository).findPublicRentalLocalitySuggestions(
                eq("indore"), eq("2BHK"), eq("FLAT"), eq(""), isNull(), isNull(), eq("unknown"), any());
    }

    @Test
    public void partialFlatQueryAndPropertyTypeDiscoveryUseStructuredFilters() throws Exception {
        ListingRepository.LocalitySuggestionRow row = mock(ListingRepository.LocalitySuggestionRow.class);
        when(row.getCity()).thenReturn("Indore");
        when(row.getLocality()).thenReturn("Vijay Nagar");
        when(row.getResultCount()).thenReturn(2L);
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), eq("2BHK"), eq("FLAT"), eq(""), isNull(), isNull(), eq(""), any()))
                .thenReturn(List.of(row));
        assertEquals("2 BHK Flat in Vijay Nagar, Indore",
                propertyController.getSearchSuggestions("2bhk fla", "Indore", 8).getBody().suggestions().get(0).label());

        when(listingRepository.searchPublicRentals(any(), any(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 6), false));
        propertyController.getAllActiveProperties(null, "Indore", "2bhk flat vijay", null, null, true, 0);
        verify(listingRepository).searchPublicRentals(
                eq(ListingStatus.ACTIVE), eq(com.indore.pathome.spaces.entity.ListingType.RENT),
                eq("indore"), eq(""), eq("vijay"), eq("2BHK"),
                eq(com.indore.pathome.spaces.entity.PropertyType.FLAT), eq(""),
                isNull(), isNull(), eq(PageRequest.of(0, 6)));

        propertyController.getAllActiveProperties("Vijay Nagar", "Indore", null, "2BHK", "FLAT", true, 0);
        verify(listingRepository).searchPublicRentals(
                eq(ListingStatus.ACTIVE), eq(com.indore.pathome.spaces.entity.ListingType.RENT),
                eq("indore"), eq("vijay nagar"), eq(""), eq("2BHK"),
                eq(com.indore.pathome.spaces.entity.PropertyType.FLAT), eq(""),
                isNull(), isNull(), eq(PageRequest.of(0, 6)));
    }

    @Test
    public void partialPriceQueriesDoNotCollapseSuggestions() {
        ListingRepository.LocalitySuggestionRow row = mock(ListingRepository.LocalitySuggestionRow.class);
        when(row.getCity()).thenReturn("Indore");
        when(row.getLocality()).thenReturn("Vijay Nagar");
        when(row.getResultCount()).thenReturn(5L);
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), eq("2BHK"), eq("FLAT"), eq(""), isNull(), isNull(), eq(""), any()))
                .thenReturn(List.of(row));

        // "2bhk flat under" should preserve bhk and flat and not treat "under" as location
        var result = propertyController.getSearchSuggestions("2bhk flat under", "Indore", 8).getBody();
        assertNotNull(result);
        assertEquals(1, result.suggestions().size());
        assertEquals("2 BHK Flat in Vijay Nagar, Indore", result.suggestions().get(0).label());
        assertEquals(5L, result.suggestions().get(0).resultCount());

        // "2bhk flat under 2" should also gracefully return the inventory suggestions
        var result2 = propertyController.getSearchSuggestions("2bhk flat under 2", "Indore", 8).getBody();
        assertNotNull(result2);
        assertEquals(1, result2.suggestions().size());
        assertEquals("2 BHK Flat in Vijay Nagar, Indore", result2.suggestions().get(0).label());
    }

    @Test
    public void oneRkQueryReturnsInventoryMatchWhenInventoryExists() {
        ListingRepository.LocalitySuggestionRow row = mock(ListingRepository.LocalitySuggestionRow.class);
        when(row.getCity()).thenReturn("Indore");
        when(row.getLocality()).thenReturn("Bombay Hospital");
        when(row.getResultCount()).thenReturn(1L);
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), eq("1RK"), eq(""), eq(""), isNull(), isNull(), eq(""), any()))
                .thenReturn(List.of(row));

        var response = propertyController.getSearchSuggestions("1rk", "Indore", 8).getBody();
        assertNotNull(response);
        assertEquals(1, response.suggestions().size());
        assertEquals("1 RK in Bombay Hospital, Indore", response.suggestions().get(0).label());
        assertEquals(1L, response.suggestions().get(0).resultCount());
        assertEquals("1RK", response.suggestions().get(0).bhk());
    }

    @Test
    public void zeroInventoryRecognizedIntentReturnsQueryIntentSuggestionWithoutFakeCount() {
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), eq("1RK"), eq(""), eq(""), isNull(), isNull(), eq(""), any()))
                .thenReturn(List.of());

        var response = propertyController.getSearchSuggestions("1rk", "Indore", 8).getBody();
        assertNotNull(response);
        assertEquals(1, response.suggestions().size());
        PublicSearchSuggestion suggestion = response.suggestions().get(0);
        assertEquals("QUERY_INTENT", suggestion.type());
        assertEquals("Search 1 RK homes in Indore", suggestion.label());
        assertEquals(0L, suggestion.resultCount());
        assertEquals("1RK", suggestion.bhk());
        assertEquals("Indore", suggestion.city());
        assertNull(suggestion.locality());
    }

    @Test
    public void explicitCityOverrideInQueryRoutesToCorrectCityInventoryOrIntent() {
        // 1. Selected Indore + "2bhk flat in pune" with zero Pune inventory
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("pune"), eq("2BHK"), eq("FLAT"), eq(""), isNull(), isNull(), eq(""), any()))
                .thenReturn(List.of());

        var puneResponse = propertyController.getSearchSuggestions("2bhk flat in pune", "Indore", 8).getBody();
        assertNotNull(puneResponse);
        assertEquals("Pune", puneResponse.effectiveCity());
        assertEquals("EXPLICIT_QUERY", puneResponse.citySource());
        assertEquals(1, puneResponse.suggestions().size());
        PublicSearchSuggestion puneIntent = puneResponse.suggestions().get(0);
        assertEquals("QUERY_INTENT", puneIntent.type());
        assertEquals("Search 2 BHK flats in Pune", puneIntent.label());
        assertEquals("Pune", puneIntent.city());
        assertEquals(0L, puneIntent.resultCount());

        // 2. Selected Pune + "2bhk flat in indore" with Indore inventory
        ListingRepository.LocalitySuggestionRow indoreRow = mock(ListingRepository.LocalitySuggestionRow.class);
        when(indoreRow.getCity()).thenReturn("Indore");
        when(indoreRow.getLocality()).thenReturn("Vijay Nagar");
        when(indoreRow.getResultCount()).thenReturn(4L);
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), eq("2BHK"), eq("FLAT"), eq(""), isNull(), isNull(), eq(""), any()))
                .thenReturn(List.of(indoreRow));

        var indoreResponse = propertyController.getSearchSuggestions("2bhk flat in indore", "Pune", 8).getBody();
        assertNotNull(indoreResponse);
        assertEquals("Indore", indoreResponse.effectiveCity());
        assertEquals("EXPLICIT_QUERY", indoreResponse.citySource());
        assertEquals(1, indoreResponse.suggestions().size());
        assertEquals("2 BHK Flat in Vijay Nagar, Indore", indoreResponse.suggestions().get(0).label());
        assertEquals(4L, indoreResponse.suggestions().get(0).resultCount());

        // 3. Selected Indore + authoritative locality "3bhk baner" -> Pune / LOCALITY_RESOLUTION
        ListingRepository.LocalitySuggestionRow banerRow = mock(ListingRepository.LocalitySuggestionRow.class);
        when(banerRow.getCity()).thenReturn("Pune");
        when(banerRow.getLocality()).thenReturn("Baner");
        when(banerRow.getResultCount()).thenReturn(2L);
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("pune"), eq("3BHK"), eq(""), eq(""), isNull(), isNull(), eq("baner"), any()))
                .thenReturn(List.of(banerRow));

        var banerResponse = propertyController.getSearchSuggestions("3bhk baner", "Indore", 8).getBody();
        assertNotNull(banerResponse);
        assertEquals("Pune", banerResponse.effectiveCity());
        assertEquals("LOCALITY_RESOLUTION", banerResponse.citySource());
        assertEquals(1, banerResponse.suggestions().size());
        assertEquals("3 BHK in Baner, Pune", banerResponse.suggestions().get(0).label());

        // 4. Unsupported explicit city "2bhk in mumbai" -> returns UNSUPPORTED_CITY without Indore fallback
        var mumbaiResponse = propertyController.getSearchSuggestions("2bhk in mumbai", "Indore", 8).getBody();
        assertNotNull(mumbaiResponse);
        assertEquals("Mumbai", mumbaiResponse.effectiveCity());
        assertEquals("EXPLICIT_QUERY", mumbaiResponse.citySource());
        assertEquals(1, mumbaiResponse.suggestions().size());
        assertEquals("UNSUPPORTED_CITY", mumbaiResponse.suggestions().get(0).type());
        assertEquals("Pathome is not yet available in Mumbai", mumbaiResponse.suggestions().get(0).label());
    }

    @Test
    public void discoverySearchPropagatesExplicitCityToPublicRentalRepository() {
        when(listingRepository.searchPublicRentals(any(), any(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 6), false));

        // Selected UI city is "Indore", but query has "2bhk flat in pune" -> searches Pune
        propertyController.getAllActiveProperties(null, "Indore", "2bhk flat in pune", null, true, 0);
        verify(listingRepository).searchPublicRentals(
                eq(ListingStatus.ACTIVE), eq(com.indore.pathome.spaces.entity.ListingType.RENT),
                eq("pune"), eq(""), eq(""), eq("2BHK"),
                eq(com.indore.pathome.spaces.entity.PropertyType.FLAT), eq(""),
                isNull(), isNull(), eq(PageRequest.of(0, 6)));
    }

    @Test
    public void selectedIndoreWith3BhkBanerAndZeroInventoryReturnsPuneQueryIntent() {
        // Zero Pune inventory for 3BHK in Baner
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("pune"), eq("3BHK"), eq(""), eq(""), isNull(), isNull(), eq("baner"), any()))
                .thenReturn(List.of());

        var response = propertyController.getSearchSuggestions("3bhk baner", "Indore", 8).getBody();
        assertNotNull(response);
        assertEquals("Pune", response.effectiveCity());
        assertEquals("LOCALITY_RESOLUTION", response.citySource());
        assertEquals(1, response.suggestions().size());

        PublicSearchSuggestion suggestion = response.suggestions().get(0);
        assertEquals("QUERY_INTENT", suggestion.type());
        assertEquals("Search 3 BHK homes in Baner, Pune", suggestion.label());
        assertEquals("Pune", suggestion.city());
        assertEquals("Baner", suggestion.locality());
        assertEquals(0L, suggestion.resultCount());

        // Verify no Indore inventory was queried
        verify(listingRepository, never()).findPublicRentalLocalitySuggestions(
                eq("indore"), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void selectedIndoreWithMpNagarCanonicalLocalityReturnsBhopalEntityMatchNotSearchAnyway() {
        // Zero Bhopal inventory for MP Nagar
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("bhopal"), eq(""), eq(""), eq(""), isNull(), isNull(), eq("mp nagar"), any()))
                .thenReturn(List.of());

        var response = propertyController.getSearchSuggestions("mp nagar", "Indore", 8).getBody();
        assertNotNull(response);
        assertEquals("Bhopal", response.effectiveCity());
        assertEquals("LOCALITY_RESOLUTION", response.citySource());
        assertEquals(1, response.suggestions().size());

        PublicSearchSuggestion suggestion = response.suggestions().get(0);
        assertEquals("ENTITY_MATCH", suggestion.type());
        assertEquals("MP Nagar, Bhopal", suggestion.label());
        assertEquals("Bhopal", suggestion.city());
        assertEquals("MP Nagar", suggestion.locality());
        assertEquals(0L, suggestion.resultCount());
    }

    @Test
    public void knownLocalityWithRealInventoryReturnsLocalityMatch() {
        ListingRepository.LocalitySuggestionRow row = mock(ListingRepository.LocalitySuggestionRow.class);
        when(row.getCity()).thenReturn("Indore");
        when(row.getLocality()).thenReturn("Vijay Nagar");
        when(row.getResultCount()).thenReturn(6L);
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), eq(""), eq(""), eq(""), isNull(), isNull(), eq("vijay nagar"), any()))
                .thenReturn(List.of(row));

        var response = propertyController.getSearchSuggestions("vijay nagar", "Indore", 8).getBody();
        assertNotNull(response);
        assertEquals("Indore", response.effectiveCity());
        assertEquals(1, response.suggestions().size());

        PublicSearchSuggestion suggestion = response.suggestions().get(0);
        assertEquals("LOCALITY", suggestion.type());
        assertEquals("Vijay Nagar, Indore", suggestion.label());
        assertEquals(6L, suggestion.resultCount());
    }

    @Test
    public void ambiguousLocalityAcrossMultipleCitiesReturnsCityQualifiedAlternativesWithSelectedUiCityFirst() {
        // "gandhi nagar" exists in both Indore and Bhopal
        com.indore.pathome.spaces.entity.Locality indoreLoc = mock(com.indore.pathome.spaces.entity.Locality.class);
        when(indoreLoc.getCity()).thenReturn("Indore");
        when(indoreLoc.getSectorName()).thenReturn("Gandhi Nagar");

        com.indore.pathome.spaces.entity.Locality bhopalLoc = mock(com.indore.pathome.spaces.entity.Locality.class);
        when(bhopalLoc.getCity()).thenReturn("Bhopal");
        when(bhopalLoc.getSectorName()).thenReturn("Gandhi Nagar");

        when(localityRepository.findAllBySectorNameIgnoreCase("gandhi nagar"))
                .thenReturn(List.of(bhopalLoc, indoreLoc));

        // Indore has 2 homes, Bhopal has 0 homes
        ListingRepository.LocalitySuggestionRow indoreRow = mock(ListingRepository.LocalitySuggestionRow.class);
        when(indoreRow.getCity()).thenReturn("Indore");
        when(indoreRow.getLocality()).thenReturn("Gandhi Nagar");
        when(indoreRow.getResultCount()).thenReturn(2L);
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("indore"), eq(""), eq(""), eq(""), isNull(), isNull(), eq("gandhi nagar"), any()))
                .thenReturn(List.of(indoreRow));
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("bhopal"), eq(""), eq(""), eq(""), isNull(), isNull(), eq("gandhi nagar"), any()))
                .thenReturn(List.of());

        var response = propertyController.getSearchSuggestions("gandhi nagar", "Indore", 8).getBody();
        assertNotNull(response);
        assertEquals("Indore", response.effectiveCity());
        assertEquals(2, response.suggestions().size());

        // First suggestion is Indore (selected UI city) with real inventory
        PublicSearchSuggestion s1 = response.suggestions().get(0);
        assertEquals("Indore", s1.city());
        assertEquals("Gandhi Nagar", s1.locality());
        assertEquals("LOCALITY", s1.type());
        assertEquals(2L, s1.resultCount());

        // Second suggestion is Bhopal (authoritative alternative) with ENTITY_MATCH (0 inventory)
        PublicSearchSuggestion s2 = response.suggestions().get(1);
        assertEquals("Bhopal", s2.city());
        assertEquals("Gandhi Nagar", s2.locality());
        assertEquals("ENTITY_MATCH", s2.type());
        assertEquals(0L, s2.resultCount());
    }

    @Test
    public void explicitCityWithAmbiguousLocalityResolvesExplicitCityDirectly() {
        // "gandhi nagar" exists in both Indore and Bhopal
        com.indore.pathome.spaces.entity.Locality indoreLoc = mock(com.indore.pathome.spaces.entity.Locality.class);
        when(indoreLoc.getCity()).thenReturn("Indore");
        when(indoreLoc.getSectorName()).thenReturn("Gandhi Nagar");

        com.indore.pathome.spaces.entity.Locality bhopalLoc = mock(com.indore.pathome.spaces.entity.Locality.class);
        when(bhopalLoc.getCity()).thenReturn("Bhopal");
        when(bhopalLoc.getSectorName()).thenReturn("Gandhi Nagar");

        when(localityRepository.findAllBySectorNameIgnoreCase("gandhi nagar"))
                .thenReturn(List.of(indoreLoc, bhopalLoc));

        // Query: "gandhi nagar in bhopal" with selectedCity="Indore"
        ListingRepository.LocalitySuggestionRow bhopalRow = mock(ListingRepository.LocalitySuggestionRow.class);
        when(bhopalRow.getCity()).thenReturn("Bhopal");
        when(bhopalRow.getLocality()).thenReturn("Gandhi Nagar");
        when(bhopalRow.getResultCount()).thenReturn(3L);
        when(listingRepository.findPublicRentalLocalitySuggestions(
                eq("bhopal"), eq(""), eq(""), eq(""), isNull(), isNull(), eq("gandhi nagar"), any()))
                .thenReturn(List.of(bhopalRow));

        var response = propertyController.getSearchSuggestions("gandhi nagar in bhopal", "Indore", 8).getBody();
        assertNotNull(response);
        assertEquals("Bhopal", response.effectiveCity());
        assertEquals("EXPLICIT_QUERY", response.citySource());
        assertEquals(1, response.suggestions().size());
        assertEquals("Bhopal", response.suggestions().get(0).city());
        assertEquals("Gandhi Nagar", response.suggestions().get(0).locality());
    }

    @Test
    public void unknownArbitraryTextReturnsSearchAnyway() {
        when(listingRepository.findPublicRentalLocalitySuggestions(
                anyString(), anyString(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(List.of());

        var response = propertyController.getSearchSuggestions("unmatched query term", "Indore", 8).getBody();
        assertNotNull(response);
        assertEquals("Indore", response.effectiveCity());
        assertEquals(1, response.suggestions().size());
        assertEquals("SEARCH_ANYWAY", response.suggestions().get(0).type());
        assertEquals("Search \"unmatched query term\"", response.suggestions().get(0).label());
    }
}
