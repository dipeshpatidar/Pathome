package com.indore.pathome.spaces.repository;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingType;
import com.indore.pathome.spaces.entity.PropertyType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.math.BigDecimal;

@Repository
public interface ListingRepository extends JpaRepository<Listing, Long> {
    interface LocalitySuggestionRow {
        String getCity();
        String getLocality();
        long getResultCount();
        Double getSimilarity();
    }

    interface CitySuggestionRow {
        String getCity();
        long getResultCount();
    }

    List<Listing> findByStatus(ListingStatus status);
    List<Listing> findByStatusAndCityIgnoreCase(ListingStatus status, String city);
    List<Listing> findBySectorIgnoreCase(String sector);
    List<Listing> findBySectorIgnoreCaseAndStatus(String sector, ListingStatus status);
    List<Listing> findByStatusAndSectorIgnoreCase(ListingStatus status, String sector);
    List<Listing> findByStatusAndCityIgnoreCaseAndSectorIgnoreCase(ListingStatus status, String city, String sector);
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

    // ── Paginated public-discovery variants (id DESC = latest-published-first) ─────────────────
    // id is the authoritative publication-order proxy: the listings table has no separate
    // publishedAt column. Listings are inserted exactly once at publication time and are
    // never recreated, so id DESC reliably surfaces the most recently published property first.

    Slice<Listing> findByStatusOrderByIdDesc(ListingStatus status, Pageable pageable);

    Slice<Listing> findByStatusAndCityIgnoreCaseOrderByIdDesc(
            ListingStatus status, String city, Pageable pageable);

    Slice<Listing> findByStatusAndSectorIgnoreCaseOrderByIdDesc(
            ListingStatus status, String sector, Pageable pageable);

    Slice<Listing> findByStatusAndCityIgnoreCaseAndSectorIgnoreCaseOrderByIdDesc(
            ListingStatus status, String city, String sector, Pageable pageable);

    /** All search branches retain the same ACTIVE visibility rule as public discovery. */
    @Query("select l from RentalDetails l where l.status = :status and l.listingType = :listingType " +
            "and (:cityKey = '' or lower(trim(l.city)) = :cityKey) " +
            "and (:sectorKey = '' or lower(trim(l.sector)) = :sectorKey) " +
            "and (:prefix = '' or lower(trim(l.sector)) like concat(:prefix, '%')) " +
            "and (:bhkKey = '' or upper(replace(l.bhkCount, ' ', '')) = :bhkKey) " +
            "and (:propertyType is null or l.propertyType = :propertyType) " +
            "and (:furnishingKey = '' or " +
            "(:furnishingKey = 'FURNISHED' and upper(replace(replace(l.furnishingStatus, '-', ' '), '_', ' ')) in ('FULLY FURNISHED', 'SEMI FURNISHED')) or " +
            "upper(replace(replace(l.furnishingStatus, '-', ' '), '_', ' ')) = replace(:furnishingKey, '_', ' ')) " +
            "and (:minRent is null or l.monthlyRent >= :minRent) " +
            "and (:maxRent is null or l.monthlyRent <= :maxRent) " +
            "order by l.id desc")
    Slice<Listing> searchPublicRentals(
            @Param("status") ListingStatus status,
            @Param("listingType") ListingType listingType,
            @Param("cityKey") String cityKey,
            @Param("sectorKey") String sectorKey,
            @Param("prefix") String prefix,
            @Param("bhkKey") String bhkKey,
            @Param("propertyType") PropertyType propertyType,
            @Param("furnishingKey") String furnishingKey,
            @Param("minRent") BigDecimal minRent,
            @Param("maxRent") BigDecimal maxRent,
            Pageable pageable);

    @Query(value = "select min(l.city) as city, min(l.sector) as locality, count(*) as \"resultCount\" " +
            "from listings l join rental_details r on r.id = l.id " +
            "where l.status = 'ACTIVE' and l.listing_type = 'RENT' " +
            "and (:cityKey = '' or lower(btrim(l.city)) = :cityKey) " +
            "and (:bhkKey = '' or upper(replace(l.bhk_count, ' ', '')) = :bhkKey) " +
            "and (:propertyTypeKey = '' or l.property_type = :propertyTypeKey) " +
            "and (:furnishingKey = '' or " +
            "(:furnishingKey = 'FURNISHED' and upper(replace(replace(l.furnishing_status, '-', ' '), '_', ' ')) in ('FULLY FURNISHED', 'SEMI FURNISHED')) or " +
            "upper(replace(replace(l.furnishing_status, '-', ' '), '_', ' ')) = replace(:furnishingKey, '_', ' ')) " +
            "and (:minRent is null or r.monthly_rent >= :minRent) " +
            "and (:maxRent is null or r.monthly_rent <= :maxRent) " +
            "and lower(btrim(l.sector)) like concat(:prefix, '%') " +
            "group by lower(btrim(l.city)), lower(btrim(l.sector)) " +
            "order by case when lower(btrim(l.sector)) = :prefix then 0 else 1 end, count(*) desc, min(l.sector)",
            nativeQuery = true)
    List<LocalitySuggestionRow> findPublicRentalLocalitySuggestions(
            @Param("cityKey") String cityKey,
            @Param("bhkKey") String bhkKey,
            @Param("propertyTypeKey") String propertyTypeKey,
            @Param("furnishingKey") String furnishingKey,
            @Param("minRent") BigDecimal minRent,
            @Param("maxRent") BigDecimal maxRent,
            @Param("prefix") String prefix,
            Pageable pageable);

    @Query(value = "select min(l.city) as city, min(l.sector) as locality, count(*) as \"resultCount\", " +
            "max(similarity(lower(btrim(l.sector)), :candidate)) as similarity " +
            "from listings l join rental_details r on r.id = l.id " +
            "where l.status = 'ACTIVE' and l.listing_type = 'RENT' " +
            "and (:cityKey = '' or lower(btrim(l.city)) = :cityKey) " +
            "and (:bhkKey = '' or upper(replace(l.bhk_count, ' ', '')) = :bhkKey) " +
            "and (:propertyTypeKey = '' or l.property_type = :propertyTypeKey) " +
            "and (:furnishingKey = '' or " +
            "(:furnishingKey = 'FURNISHED' and upper(replace(replace(l.furnishing_status, '-', ' '), '_', ' ')) in ('FULLY FURNISHED', 'SEMI FURNISHED')) or " +
            "upper(replace(replace(l.furnishing_status, '-', ' '), '_', ' ')) = replace(:furnishingKey, '_', ' ')) " +
            "and (:minRent is null or r.monthly_rent >= :minRent) " +
            "and (:maxRent is null or r.monthly_rent <= :maxRent) " +
            "and lower(btrim(l.sector)) % :candidate " +
            "and similarity(lower(btrim(l.sector)), :candidate) >= :minSimilarity " +
            "group by lower(btrim(l.city)), lower(btrim(l.sector)) " +
            "order by max(similarity(lower(btrim(l.sector)), :candidate)) desc, count(*) desc, min(l.sector)",
            nativeQuery = true)
    List<LocalitySuggestionRow> findPublicRentalFuzzyLocalities(
            @Param("cityKey") String cityKey,
            @Param("bhkKey") String bhkKey,
            @Param("propertyTypeKey") String propertyTypeKey,
            @Param("furnishingKey") String furnishingKey,
            @Param("minRent") BigDecimal minRent,
            @Param("maxRent") BigDecimal maxRent,
            @Param("candidate") String candidate,
            @Param("minSimilarity") double minSimilarity,
            Pageable pageable);

    @Query(value = "select min(city) as city, count(*) as \"resultCount\" " +
            "from listings where status = 'ACTIVE' and listing_type = 'RENT' " +
            "and lower(btrim(city)) like concat(:prefix, '%') " +
            "group by lower(btrim(city)) " +
            "order by case when lower(btrim(city)) = :prefix then 0 else 1 end, count(*) desc, min(city)",
            nativeQuery = true)
    List<CitySuggestionRow> findPublicRentalCitySuggestions(
            @Param("prefix") String prefix,
            Pageable pageable);

    @Query(value = "select min(city) as city, count(*) as \"resultCount\" " +
            "from listings where status = 'ACTIVE' and listing_type = 'RENT' " +
            "and lower(btrim(city)) % :candidate " +
            "and similarity(lower(btrim(city)), :candidate) >= :minSimilarity " +
            "group by lower(btrim(city)) " +
            "order by max(similarity(lower(btrim(city)), :candidate)) desc, count(*) desc, min(city)",
            nativeQuery = true)
    List<CitySuggestionRow> findPublicRentalFuzzyCities(
            @Param("candidate") String candidate,
            @Param("minSimilarity") double minSimilarity,
            Pageable pageable);
}
