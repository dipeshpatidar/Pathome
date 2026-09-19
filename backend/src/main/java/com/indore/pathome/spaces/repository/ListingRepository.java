package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ListingRepository extends JpaRepository<Listing, Long> {
    List<Listing> findByStatus(ListingStatus status);
    List<Listing> findBySectorIgnoreCase(String sector);
    List<Listing> findBySectorIgnoreCaseAndStatus(String sector, ListingStatus status);
    List<Listing> findByListingTypeAndStatus(ListingType listingType, ListingStatus status);
    List<Listing> findByBhkCountAndStatus(String bhkCount, ListingStatus status);
    java.util.Optional<Listing> findByOriginDraftId(String originDraftId);
}
