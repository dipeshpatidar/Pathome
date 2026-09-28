package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.*;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import jakarta.persistence.EntityNotFoundException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordPortfolioServiceTest {
    @Test
    void listsOnlyAuthenticatedOwnersPageWithOneBatchedCoverLookup() {
        LandlordCapabilityService capabilities = mock(LandlordCapabilityService.class);
        ListingRepository listings = mock(ListingRepository.class);
        PropertyMediaAssetRepository assets = mock(PropertyMediaAssetRepository.class);
        when(capabilities.requireLandlordUserId("owner@example.com")).thenReturn(5L);
        RentalDetails first = listing(11L, "First");
        RentalDetails second = listing(12L, "Second");
        when(listings.findLandlordRentals(eq(5L), any(PageRequest.class)))
                .thenReturn(new SliceImpl<>(List.of(first, second), PageRequest.of(0, 20), true));
        PropertyMediaAsset cover = new PropertyMediaAsset(11L, "https://example.com/cover.jpg",
                MediaType.IMAGE, RoomTag.GENERAL, "cover");
        cover.setIsPrimaryCover(true);
        when(assets.findByListingIdInOrderByUploadedAtDesc(List.of(11L, 12L))).thenReturn(List.of(cover));

        var result = new LandlordPortfolioService(capabilities, listings, assets).list("owner@example.com", 0);
        assertEquals(2, result.items().size());
        assertTrue(result.hasMore());
        assertEquals("https://example.com/cover.jpg", result.items().get(0).coverUrl());
        assertNull(result.items().get(1).coverUrl());
        assertEquals(ListingWorkflowStatus.SUBMITTED, result.items().get(0).status());
        verify(listings).findLandlordRentals(eq(5L), any(PageRequest.class));
        verify(assets, times(1)).findByListingIdInOrderByUploadedAtDesc(anyCollection());
        verify(listings, never()).findAll();
    }

    @Test
    void detailRequiresOwnershipAndOmitsPrivateAddressContactAndCoordinates() {
        LandlordCapabilityService capabilities = mock(LandlordCapabilityService.class);
        ListingRepository listings = mock(ListingRepository.class);
        PropertyMediaAssetRepository assets = mock(PropertyMediaAssetRepository.class);
        when(capabilities.requireLandlordUserId("owner@example.com")).thenReturn(5L);
        when(capabilities.requireLandlordUserId("other@example.com")).thenReturn(8L);
        RentalDetails owned = listing(11L, "First");
        owned.setAddress("Private street address");
        owned.setOwnerPhoneNumber("9000000000");
        owned.setLatitude(22.5);
        when(listings.findByIdAndOwnerUserId(11L, 5L)).thenReturn(java.util.Optional.of(owned));

        var service = new LandlordPortfolioService(capabilities, listings, assets);
        var detail = service.get("owner@example.com", 11L);
        assertEquals("First", detail.preview().title());
        assertFalse(detail.toString().contains("Private street address"));
        assertFalse(detail.toString().contains("9000000000"));
        assertFalse(detail.toString().contains("22.5"));
        assertThrows(EntityNotFoundException.class, () -> service.get("other@example.com", 11L));
        verify(listings).findByIdAndOwnerUserId(11L, 8L);
    }

    private RentalDetails listing(Long id, String title) {
        RentalDetails listing = new RentalDetails();
        listing.setId(id);
        listing.setTitle(title);
        listing.setOwnerUserId(5L);
        listing.setPropertyType(PropertyType.FLAT);
        listing.setBhkCount("2BHK");
        listing.setCity("Indore");
        listing.setSector("Vijay Nagar");
        listing.setMonthlyRent(new BigDecimal("20000"));
        listing.setWorkflowStatus(ListingWorkflowStatus.SUBMITTED);
        listing.setStatus(ListingStatus.PENDING);
        return listing;
    }
}
