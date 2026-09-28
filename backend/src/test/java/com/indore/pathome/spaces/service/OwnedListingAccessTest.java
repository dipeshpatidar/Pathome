package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.repository.ListingRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedListingAccessTest {
    @Test
    void lookupUsesAuthenticatedOwnerAndNeverUnscopedId() {
        LandlordCapabilityService capabilities = mock(LandlordCapabilityService.class);
        ListingRepository listings = mock(ListingRepository.class);
        Listing owned = new RentalDetails();
        when(capabilities.requireLandlordUserId("owner@example.com")).thenReturn(12L);
        when(listings.findByIdAndOwnerUserId(91L, 12L)).thenReturn(Optional.of(owned));

        assertSame(owned, new OwnedListingAccess(capabilities, listings).requireOwned("owner@example.com", 91L));
        verify(listings).findByIdAndOwnerUserId(91L, 12L);
        verify(listings, never()).findById(anyLong());
    }

    @Test
    void anotherOwnersIdHasNoResult() {
        LandlordCapabilityService capabilities = mock(LandlordCapabilityService.class);
        ListingRepository listings = mock(ListingRepository.class);
        when(capabilities.requireLandlordUserId("owner@example.com")).thenReturn(12L);
        when(listings.findByIdAndOwnerUserId(91L, 12L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> new OwnedListingAccess(capabilities, listings).requireOwned("owner@example.com", 91L));
    }
}
