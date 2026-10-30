package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingWorkflowStatus;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.SupportedCity;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicPropertyReadServiceTest {
    private ListingRepository listings;
    private LocalityRepository localities;
    private PropertyMediaAssetRepository mediaAssets;
    private CloudinaryService cloudinary;
    private PublicPropertyReadService service;

    @BeforeEach
    void setUp() {
        listings = mock(ListingRepository.class);
        localities = mock(LocalityRepository.class);
        mediaAssets = mock(PropertyMediaAssetRepository.class);
        cloudinary = mock(CloudinaryService.class);
        service = new PublicPropertyReadService(listings, localities, mediaAssets, cloudinary);
    }

    @Test
    void publishedPropertyUsesCanonicalSafeFactsAndCurrentEligibleMedia() {
        RentalDetails listing = publishedListing(31L);
        Locality locality = locality(70L, "Vijay Nagar", "Indore");
        PropertyMediaAsset image = image(31L, "safe", true);
        PropertyMediaAsset unsafe = image(31L, "private", false);
        unsafe.setMediaUrl("https://res.cloudinary.com/demo/private/owner.webp");
        PropertyMediaAsset retired = image(31L, "retired", false);
        retired.setRetiredAt(java.time.LocalDateTime.now());

        when(listings.findById(31L)).thenReturn(Optional.of(listing));
        when(localities.findById(70L)).thenReturn(Optional.of(locality));
        when(mediaAssets.findByListingIdOrderByUploadedAtDesc(31L))
                .thenReturn(List.of(image, unsafe, retired));
        when(cloudinary.isTrustedPublicMediaAsset(any(), any(), any()))
                .thenAnswer(invocation ->
                        !((String) invocation.getArgument(0)).contains("/private/"));

        var result = service.findPublicProperty(31L).orElseThrow();

        assertEquals("2 BHK Flat in Vijay Nagar, Indore", result.title());
        assertEquals("Indore", result.city());
        assertEquals("Vijay Nagar", result.sector());
        assertNull(result.description());
        assertEquals(1, result.media().size());
        assertEquals(image.getMediaUrl(), result.media().get(0).mediaUrl());
        assertFalse(result.toString().contains("owner"));
        assertFalse(result.toString().contains("private"));
    }

    @Test
    void onlyActivePublishedListingsWithCanonicalLocalityCanEnterEitherProjection() {
        RentalDetails listing = publishedListing(41L);
        listing.setWorkflowStatus(ListingWorkflowStatus.SUBMITTED);
        when(listings.findById(41L)).thenReturn(Optional.of(listing));

        assertTrue(service.findPublicProperty(41L).isEmpty());
        assertTrue(service.findPublicSnapshot(41L).isEmpty());
        verify(localities, never()).findById(any());

        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        listing.setCanonicalLocalityId(null);
        assertTrue(service.findPublicProperty(41L).isEmpty());
        assertTrue(service.findPublicSnapshot(41L).isEmpty());
    }

    @Test
    void eachNonPublicLifecycleStateFailsClosedForHtmlAndJson() {
        for (ListingStatus status : new ListingStatus[]{
                ListingStatus.PENDING, ListingStatus.CLOSED}) {
            RentalDetails listing = publishedListing(91L);
            listing.setStatus(status);
            when(listings.findById(91L)).thenReturn(Optional.of(listing));
            assertTrue(service.findPublicProperty(91L).isEmpty(), status.name());
            assertTrue(service.findPublicSnapshot(91L).isEmpty(), status.name());
        }
        for (ListingWorkflowStatus workflow : new ListingWorkflowStatus[]{
                ListingWorkflowStatus.DRAFT, ListingWorkflowStatus.SUBMITTED,
                ListingWorkflowStatus.UNDER_REVIEW, ListingWorkflowStatus.CHANGES_REQUIRED,
                ListingWorkflowStatus.PAUSED, ListingWorkflowStatus.ARCHIVED}) {
            RentalDetails listing = publishedListing(92L);
            listing.setWorkflowStatus(workflow);
            when(listings.findById(92L)).thenReturn(Optional.of(listing));
            assertTrue(service.findPublicProperty(92L).isEmpty(), workflow.name());
            assertTrue(service.findPublicSnapshot(92L).isEmpty(), workflow.name());
        }
    }

    @Test
    void missingLocalityOrMissingOrInactiveCanonicalCityCannotProduceEitherProjection() {
        RentalDetails missingLocality = publishedListing(93L);
        when(listings.findById(93L)).thenReturn(Optional.of(missingLocality));
        when(localities.findById(70L)).thenReturn(Optional.empty());
        assertTrue(service.findPublicProperty(93L).isEmpty());
        assertTrue(service.findPublicSnapshot(93L).isEmpty());

        RentalDetails missingCity = publishedListing(94L);
        Locality locality = new Locality("Indore", "Vijay Nagar", null, 1, null);
        locality.setId(70L);
        locality.setSupportedCity(null);
        when(listings.findById(94L)).thenReturn(Optional.of(missingCity));
        when(localities.findById(70L)).thenReturn(Optional.of(locality));
        assertTrue(service.findPublicProperty(94L).isEmpty());
        assertTrue(service.findPublicSnapshot(94L).isEmpty());

        RentalDetails inactiveCity = publishedListing(95L);
        inactiveCity.setCanonicalLocalityId(75L);
        Locality inactiveCityLocality = locality(75L, "Sudama Nagar", "Indore");
        inactiveCityLocality.getSupportedCity().setActive(false);
        when(listings.findById(95L)).thenReturn(Optional.of(inactiveCity));
        when(localities.findById(75L)).thenReturn(Optional.of(inactiveCityLocality));
        assertTrue(service.findPublicProperty(95L).isEmpty());
        assertTrue(service.findPublicSnapshot(95L).isEmpty());

        RentalDetails activeCity = publishedListing(96L);
        activeCity.setCanonicalLocalityId(76L);
        Locality activeCityLocality = locality(76L, "Palasia", "Indore");
        when(listings.findById(96L)).thenReturn(Optional.of(activeCity));
        when(localities.findById(76L)).thenReturn(Optional.of(activeCityLocality));
        assertTrue(service.findPublicProperty(96L).isPresent());
        assertTrue(service.findPublicSnapshot(96L).isPresent());
    }

    @Test
    void htmlSnapshotReadsOnlyBoundedPublicCoverCandidates() {
        RentalDetails listing = publishedListing(51L);
        Locality locality = locality(71L, "Arera Colony", "Bhopal");
        PropertyMediaAsset primary = image(51L, "primary", true);
        when(listings.findById(51L)).thenReturn(Optional.of(listing));
        when(localities.findById(71L)).thenReturn(Optional.of(locality));
        when(mediaAssets.findPublicCoverCandidates(eq(51L), any(Pageable.class)))
                .thenReturn(List.of(primary));
        when(cloudinary.trustedPublicMediaUrl(primary.getMediaUrl(),
                primary.getCloudinaryPublicId(), primary.getUploadRequestId()))
                .thenReturn(primary.getMediaUrl());

        var snapshot = service.findPublicSnapshot(51L).orElseThrow();

        assertEquals(primary.getMediaUrl(), snapshot.coverImageUrl());
        assertEquals(new BigDecimal("21000.01"), snapshot.monthlyRent());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(mediaAssets).findPublicCoverCandidates(eq(51L), page.capture());
        assertEquals(0, page.getValue().getPageNumber());
        assertEquals(12, page.getValue().getPageSize());
        verify(mediaAssets, never()).findByListingIdOrderByUploadedAtDesc(any());
    }

    @Test
    void unavailableCoverAndOptionalFactsAreOmittedWithoutPlaceholders() {
        RentalDetails listing = publishedListing(61L);
        listing.setBhkCount(null);
        listing.setTotalAreaSqFt(null);
        listing.setMonthlyRent(null);
        Locality locality = locality(72L, "Nipania", "Indore");
        PropertyMediaAsset unsafe = image(61L, "foreign", true);
        unsafe.setMediaUrl("https://res.cloudinary.com/foreign/image/upload/private.webp");
        when(listings.findById(61L)).thenReturn(Optional.of(listing));
        when(localities.findById(72L)).thenReturn(Optional.of(locality));
        when(mediaAssets.findPublicCoverCandidates(eq(61L), any(Pageable.class)))
                .thenReturn(List.of(unsafe));
        when(cloudinary.trustedPublicMediaUrl(unsafe.getMediaUrl(),
                unsafe.getCloudinaryPublicId(), unsafe.getUploadRequestId())).thenReturn(null);

        var snapshot = service.findPublicSnapshot(61L).orElseThrow();

        assertNull(snapshot.coverImageUrl());
        assertNull(snapshot.monthlyRent());
        assertNull(snapshot.bhk());
        assertNull(snapshot.totalAreaSqFt());
    }

    @Test
    void eachReadReloadsCurrentAuthorityWithoutAnApplicationCache() {
        RentalDetails first = publishedListing(81L);
        RentalDetails second = publishedListing(81L);
        second.setMonthlyRent(new BigDecimal("22500.25"));
        Locality locality = locality(73L, "Palasia", "Indore");
        PropertyMediaAsset cover = image(81L, "current-cover", true);
        when(listings.findById(81L)).thenReturn(Optional.of(first), Optional.of(second));
        when(localities.findById(73L)).thenReturn(Optional.of(locality));
        when(mediaAssets.findPublicCoverCandidates(eq(81L), any(Pageable.class)))
                .thenReturn(List.of(cover), List.of());
        when(cloudinary.trustedPublicMediaUrl(cover.getMediaUrl(),
                cover.getCloudinaryPublicId(), cover.getUploadRequestId()))
                .thenReturn(cover.getMediaUrl());

        var firstRead = service.findPublicSnapshot(81L).orElseThrow();
        var secondRead = service.findPublicSnapshot(81L).orElseThrow();
        assertEquals(new BigDecimal("21000.01"), firstRead.monthlyRent());
        assertEquals(cover.getMediaUrl(), firstRead.coverImageUrl());
        assertEquals(new BigDecimal("22500.25"), secondRead.monthlyRent());
        assertNull(secondRead.coverImageUrl());
        verify(listings, org.mockito.Mockito.times(2)).findById(81L);
    }

    private RentalDetails publishedListing(long id) {
        RentalDetails listing = new RentalDetails();
        listing.setId(id);
        listing.setStatus(ListingStatus.ACTIVE);
        listing.setWorkflowStatus(ListingWorkflowStatus.PUBLISHED);
        listing.setCanonicalLocalityId(id == 51L ? 71L : id == 61L ? 72L : id == 81L ? 73L : 70L);
        listing.setBhkCount("2 BHK");
        listing.setPropertyType(com.indore.pathome.spaces.entity.PropertyType.FLAT);
        listing.setTotalAreaSqFt(850.0);
        listing.setMonthlyRent(new BigDecimal("21000.01"));
        listing.setAmenities("Balcony, Lift");
        return listing;
    }

    private static Locality locality(long id, String sector, String city) {
        SupportedCity supportedCity = new SupportedCity(
                city.toLowerCase(), city, true);
        Locality locality = new Locality(city, sector, null, 1, null);
        locality.setId(id);
        locality.setSupportedCity(supportedCity);
        return locality;
    }

    private static PropertyMediaAsset image(long listingId, String publicId, boolean primary) {
        PropertyMediaAsset image = new PropertyMediaAsset();
        image.setListingId(listingId);
        image.setMediaUrl("https://res.cloudinary.com/pathome/image/upload/"
                + publicId + ".webp");
        image.setCloudinaryPublicId(publicId);
        image.setMediaType(MediaType.IMAGE);
        image.setIsPrimaryCover(primary);
        image.setSortOrder(0);
        return image;
    }
}
