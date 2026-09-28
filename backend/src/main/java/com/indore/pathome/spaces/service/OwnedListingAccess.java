package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.repository.ListingRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OwnedListingAccess {
    private final LandlordCapabilityService capabilities;
    private final ListingRepository listings;

    public OwnedListingAccess(LandlordCapabilityService capabilities, ListingRepository listings) {
        this.capabilities = capabilities;
        this.listings = listings;
    }

    @Transactional(readOnly = true)
    public Listing requireOwned(String authenticatedEmail, Long listingId) {
        Long ownerId = capabilities.requireLandlordUserId(authenticatedEmail);
        return listings.findByIdAndOwnerUserId(listingId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
    }
}
