package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.PropertyVisitRequest;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantVisitRequestHistoryServiceTest {
    private final PropertyVisitRequestRepository requests = mock(PropertyVisitRequestRepository.class);
    private final PropertyMediaAssetRepository media = mock(PropertyMediaAssetRepository.class);
    private final TenantVisitRequestHistoryService history = new TenantVisitRequestHistoryService(requests, media);

    @Test
    void returnsOnlyAuthenticatedUsersRecordsAndExistingFacts() {
        Listing listing = mock(Listing.class);
        when(listing.getId()).thenReturn(77L);
        when(listing.getTitle()).thenReturn("Garden apartment");
        when(listing.getCity()).thenReturn("Indore");
        when(listing.getSector()).thenReturn("Vijay Nagar");
        when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
        PropertyVisitRequest own = new PropertyVisitRequest();
        own.setId(31L);
        own.setListing(listing);
        own.setPreferredVisitTiming("Saturday afternoon");
        own.setStatus("RECEIVED");
        own.setCreatedAt(LocalDateTime.of(2026, 9, 29, 10, 15));
        when(requests.findByTenantId(eq(101L), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(own), invocation.getArgument(1), 1));
        when(media.findByListingIdInOrderByUploadedAtDesc(List.of(77L))).thenReturn(List.of());

        var result = history.listForUser(101L, 0);

        assertEquals(1, result.totalCount());
        assertFalse(result.hasMore());
        assertEquals(31L, result.requests().get(0).requestId());
        assertEquals("Garden apartment", result.requests().get(0).propertyTitle());
        assertEquals("Saturday afternoon", result.requests().get(0).preferredVisitTiming());
        assertEquals("RECEIVED", result.requests().get(0).status());
        assertTrue(result.requests().get(0).propertyAvailable());
        verify(requests).findByTenantId(eq(101L), argThat(page -> page.getPageNumber() == 0
                && page.getPageSize() == 8 && page.getSort().isSorted()));
        verify(requests, never()).findByTenantId(eq(202L), any());
    }

    @Test
    void emptyHistoryDoesNotQueryMediaOrInventMetrics() {
        when(requests.findByTenantId(eq(101L), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        var result = history.listForUser(101L, 0);

        assertTrue(result.requests().isEmpty());
        assertEquals(0, result.totalCount());
        verifyNoInteractions(media);
    }

    @Test
    void mediaForMultipleRequestsIsFetchedOnceAndUsesThePrimaryCover() {
        Listing firstListing = mock(Listing.class);
        when(firstListing.getId()).thenReturn(77L);
        Listing secondListing = mock(Listing.class);
        when(secondListing.getId()).thenReturn(88L);
        PropertyVisitRequest first = new PropertyVisitRequest();
        first.setId(1L);
        first.setListing(firstListing);
        PropertyVisitRequest second = new PropertyVisitRequest();
        second.setId(2L);
        second.setListing(secondListing);
        when(requests.findByTenantId(eq(101L), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(first, second), invocation.getArgument(1), 2));
        PropertyMediaAsset ordinary = mock(PropertyMediaAsset.class);
        when(ordinary.getListingId()).thenReturn(77L);
        when(ordinary.getMediaType()).thenReturn(MediaType.IMAGE);
        when(ordinary.getMediaUrl()).thenReturn("ordinary.jpg");
        PropertyMediaAsset primary = mock(PropertyMediaAsset.class);
        when(primary.getListingId()).thenReturn(77L);
        when(primary.getMediaType()).thenReturn(MediaType.IMAGE);
        when(primary.getMediaUrl()).thenReturn("primary.jpg");
        when(primary.getIsPrimaryCover()).thenReturn(true);
        when(media.findByListingIdInOrderByUploadedAtDesc(List.of(77L, 88L)))
                .thenReturn(List.of(ordinary, primary));

        var result = history.listForUser(101L, 0);

        assertEquals("primary.jpg", result.requests().get(0).coverImageUrl());
        assertNull(result.requests().get(1).coverImageUrl());
        verify(media, times(1)).findByListingIdInOrderByUploadedAtDesc(List.of(77L, 88L));
    }

    @Test
    void rejectsMissingIdentityAndInvalidPage() {
        assertThrows(IllegalArgumentException.class, () -> history.listForUser(null, 0));
        assertThrows(IllegalArgumentException.class, () -> history.listForUser(101L, -1));
        verifyNoInteractions(requests, media);
    }
}
