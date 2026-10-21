package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OperationsPropertyCoverResolverTest {
    private PropertyMediaAssetRepository media;
    private OperationsPropertyCoverResolver resolver;

    @BeforeEach
    void setUp() {
        media = mock(PropertyMediaAssetRepository.class);
        resolver = new OperationsPropertyCoverResolver(media);
    }

    @Test
    void activeListingPrefersThePrimaryImageAndUsesOneBatchQuery() {
        Listing listing = listing(1L, ListingStatus.ACTIVE);
        PropertyMediaAsset first = image(1L, "https://example.test/first.jpg", false, 0, 2L);
        PropertyMediaAsset primary = image(1L, "https://res.cloudinary.com/cloud/image/upload/v1/a.jpg", true, 5, 1L);
        when(media.findByListingIdInOrderByUploadedAtDesc(List.of(1L))).thenReturn(List.of(first, primary));

        Map<Long, OperationsPropertyCoverResolver.PropertyCover> covers = resolver.resolve(List.of(listing));

        assertEquals(
                "https://res.cloudinary.com/cloud/image/upload/w_800,c_limit,f_auto,q_auto/v1/a.jpg",
                covers.get(1L).coverImageUrl()
        );
        assertEquals(ListingStatus.ACTIVE, covers.get(1L).listingStatus());
        verify(media, times(1)).findByListingIdInOrderByUploadedAtDesc(List.of(1L));
    }

    @Test
    void firstGenuineImageIsUsedWhenNoPrimaryIsFlagged() {
        Listing listing = listing(2L, ListingStatus.ACTIVE);
        PropertyMediaAsset late = image(2L, "https://example.test/late.jpg", false, 9, 1L);
        PropertyMediaAsset early = image(2L, "https://example.test/early.jpg", false, 1, 2L);
        when(media.findByListingIdInOrderByUploadedAtDesc(List.of(2L))).thenReturn(List.of(late, early));

        assertEquals("https://example.test/early.jpg",
                resolver.resolve(List.of(listing)).get(2L).coverImageUrl());
    }

    @Test
    void supportedLegacyGalleryIsUsedOnlyWhenNoImageAssetExists() {
        Listing listing = listing(3L, ListingStatus.ACTIVE);
        listing.setMediaGalleryUrls("https://example.test/walkthrough.mp4, https://example.test/legacy.jpg");
        when(media.findByListingIdInOrderByUploadedAtDesc(List.of(3L))).thenReturn(List.of());

        assertEquals("https://example.test/legacy.jpg",
                resolver.resolve(List.of(listing)).get(3L).coverImageUrl());
    }

    @Test
    void unavailableListingReceivesNoCoverAndNoMediaRead() {
        Listing pending = listing(4L, ListingStatus.PENDING);
        Listing closed = listing(5L, ListingStatus.CLOSED);

        Map<Long, OperationsPropertyCoverResolver.PropertyCover> covers = resolver.resolve(List.of(pending, closed));

        assertNull(covers.get(4L).coverImageUrl());
        assertNull(covers.get(5L).coverImageUrl());
        assertEquals(ListingStatus.PENDING, covers.get(4L).listingStatus());
        verify(media, never()).findByListingIdInOrderByUploadedAtDesc(any());
    }

    @Test
    void missingOrMalformedImagesBecomeNullRatherThanAFabricatedCover() {
        Listing listing = listing(6L, ListingStatus.ACTIVE);
        when(media.findByListingIdInOrderByUploadedAtDesc(List.of(6L))).thenReturn(List.of());
        assertNull(resolver.resolve(List.of(listing)).get(6L).coverImageUrl());

        Listing videoOnly = listing(7L, ListingStatus.ACTIVE);
        videoOnly.setMediaGalleryUrls("https://example.test/walkthrough.mp4");
        when(media.findByListingIdInOrderByUploadedAtDesc(List.of(7L))).thenReturn(List.of());
        assertNull(resolver.resolve(List.of(videoOnly)).get(7L).coverImageUrl());
    }

    private static Listing listing(Long id, ListingStatus status) {
        RentalDetails listing = new RentalDetails();
        listing.setId(id);
        listing.setTitle("Property " + id);
        listing.setStatus(status);
        return listing;
    }

    private static PropertyMediaAsset image(
            Long listingId, String url, boolean primary, Integer sortOrder, Long id) {
        PropertyMediaAsset asset = new PropertyMediaAsset(listingId, url, MediaType.IMAGE,
                com.indore.pathome.spaces.entity.RoomTag.GENERAL, null);
        asset.setIsPrimaryCover(primary);
        asset.setSortOrder(sortOrder);
        asset.setId(id);
        asset.setUploadedAt(LocalDateTime.of(2026, 10, 1, 10, 0).plusSeconds(id));
        return asset;
    }
}
