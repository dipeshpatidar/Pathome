package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.UserFavoriteRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class UserFavoriteServiceTest {
    private UserFavoriteRepository favorites;
    private ListingRepository listings;
    private PropertyMediaAssetRepository mediaAssets;
    private UserFavoriteService service;

    @BeforeEach
    void setUp() {
        favorites = mock(UserFavoriteRepository.class);
        listings = mock(ListingRepository.class);
        mediaAssets = mock(PropertyMediaAssetRepository.class);
        service = new UserFavoriteService(favorites, listings, mediaAssets);
    }

    @Test
    void listUsesOnlyRequestedDistinctPropertyIds() {
        when(favorites.findSavedActiveListingIds(12L, List.of(3L, 4L))).thenReturn(List.of(4L));

        assertEquals(List.of(4L), service.listSavedActiveProperties(12L, List.of(3L, 4L, 3L)));
        verify(favorites).findSavedActiveListingIds(12L, List.of(3L, 4L));
    }

    @Test
    void emptyBatchDoesNotQueryFavorites() {
        assertEquals(List.of(), service.listSavedActiveProperties(12L, List.of()));
        verifyNoInteractions(favorites);
    }

    @Test
    void rejectsInvalidAndOversizedBatches() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listSavedActiveProperties(12L, List.of(1L, 0L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.listSavedActiveProperties(12L, java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList()));
        verifyNoInteractions(favorites);
    }

    @Test
    void saveUsesIdempotentRepositoryOperationForActiveListing() {
        when(listings.findByIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(Optional.of(mock(Listing.class)));

        service.save(12L, 7L);
        service.save(12L, 7L);

        verify(favorites, times(2)).saveIfAbsent(12L, 7L);
    }

    @Test
    void saveRejectsUnavailableListingAndNeverPersistsIt() {
        when(listings.findByIdAndStatus(7L, ListingStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.save(12L, 7L));
        verifyNoInteractions(favorites);
    }

    @Test
    void removeIsIdempotentlyScopedToAuthenticatedUser() {
        service.remove(12L, 7L);
        service.remove(12L, 7L);
        verify(favorites, times(2)).remove(12L, 7L);
    }

    @Test
    void savedSummaryPageLoadsCurrentListingsAndMediaInBatchesForAuthenticatedUser() {
        Listing second = activeListing(4L);
        Listing first = activeListing(9L);
        when(favorites.findSavedActiveListingPage(12L, 3, 0)).thenReturn(List.of(9L, 4L, 1L));
        when(listings.findAllById(List.of(9L, 4L))).thenReturn(List.of(second, first));
        when(mediaAssets.findByListingIdInOrderByUploadedAtDesc(List.of(9L, 4L))).thenReturn(List.of());

        var page = service.listSavedActiveListings(12L, 0, 2);

        assertEquals(2, page.properties().size());
        assertEquals(List.of(9L, 4L), page.properties().stream().map(item -> item.id()).toList());
        assertEquals(2, page.pageSize());
        assertEquals(true, page.hasMore());
        verify(favorites).findSavedActiveListingPage(12L, 3, 0);
        verify(listings).findAllById(List.of(9L, 4L));
        verify(mediaAssets).findByListingIdInOrderByUploadedAtDesc(List.of(9L, 4L));
    }

    @Test
    void savedSummaryPagesRejectInvalidBoundsBeforeQuerying() {
        assertThrows(IllegalArgumentException.class, () -> service.listSavedActiveListings(12L, -1, 6));
        assertThrows(IllegalArgumentException.class, () -> service.listSavedActiveListings(12L, 0, 25));
        assertThrows(IllegalArgumentException.class, () -> service.listSavedActiveListings(12L, Integer.MAX_VALUE, 24));
        verifyNoInteractions(favorites, listings, mediaAssets);
    }

    private static Listing activeListing(Long id) {
        Listing listing = mock(Listing.class);
        when(listing.getId()).thenReturn(id);
        when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
        return listing;
    }
}
