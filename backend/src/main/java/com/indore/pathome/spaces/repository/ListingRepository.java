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

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
        value = "UPDATE listings SET media_gallery_urls = CASE " +
                "  WHEN media_gallery_urls IS NULL OR TRIM(media_gallery_urls) = '' THEN :cdnUrl " +
                "  WHEN POSITION(',' || :cdnUrl || ',' IN ',' || media_gallery_urls || ',') > 0 THEN media_gallery_urls " +
                "  ELSE media_gallery_urls || ',' || :cdnUrl " +
                "END WHERE id = :id",
        nativeQuery = true
    )
    int appendMediaGalleryUrlAtomic(
        @org.springframework.data.repository.query.Param("id") Long id,
        @org.springframework.data.repository.query.Param("cdnUrl") String cdnUrl
    );
}
