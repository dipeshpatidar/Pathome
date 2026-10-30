package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.PublicPropertyMediaResponse;
import com.indore.pathome.spaces.dto.PublicPropertyResponse;
import com.indore.pathome.spaces.entity.Listing;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only authority shared by the public JSON projection and initial HTML metadata.
 * Lead-specific publication, contact, ownership and City eligibility remain outside this service.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
public class PublicPropertyReadService {
    private static final int PUBLIC_COVER_CANDIDATE_LIMIT = 12;

    private final ListingRepository listings;
    private final LocalityRepository localities;
    private final PropertyMediaAssetRepository mediaAssets;
    private final CloudinaryService cloudinary;

    public PublicPropertyReadService(ListingRepository listings,
                                     LocalityRepository localities,
                                     PropertyMediaAssetRepository mediaAssets,
                                     CloudinaryService cloudinary) {
        this.listings = Objects.requireNonNull(listings, "ListingRepository must not be null");
        this.localities = Objects.requireNonNull(localities, "LocalityRepository must not be null");
        this.mediaAssets = Objects.requireNonNull(mediaAssets,
                "PropertyMediaAssetRepository must not be null");
        this.cloudinary = Objects.requireNonNull(cloudinary, "CloudinaryService must not be null");
    }

    public Optional<PublicPropertyResponse> findPublicProperty(long propertyId) {
        return loadPublicContext(propertyId).map(context ->
                toPublicPropertyResponse(context.listing(), context.locality()));
    }

    public Optional<PublicPropertySnapshot> findPublicSnapshot(long propertyId) {
        return loadPublicContext(propertyId)
                .map(context -> toSnapshot(context.listing(), context.locality(), context.city()));
    }

    private Optional<PublicContext> loadPublicContext(long propertyId) {
        if (propertyId <= 0) return Optional.empty();
        Listing listing = listings.findById(propertyId).orElse(null);
        if (listing == null || listing.getStatus() != ListingStatus.ACTIVE
                || listing.getWorkflowStatus() != ListingWorkflowStatus.PUBLISHED
                || listing.getCanonicalLocalityId() == null) {
            return Optional.empty();
        }
        Locality locality = localities.findById(listing.getCanonicalLocalityId()).orElse(null);
        if (locality == null) return Optional.empty();
        SupportedCity city = locality.getSupportedCity();
        if (city == null || !city.isActive()) return Optional.empty();
        return Optional.of(new PublicContext(listing, locality, city));
    }

    private PublicPropertySnapshot toSnapshot(Listing listing, Locality locality,
                                               SupportedCity city) {
        String title = PublicPropertyDisclosurePolicy.safeCanonicalTitle(listing, locality, city);
        String sector = PublicPropertyDisclosurePolicy.canonicalLocalityLabel(locality);
        String cityLabel = PublicPropertyDisclosurePolicy.canonicalCityLabel(city, locality);
        if (sector == null || cityLabel == null) return null;
        RentalDetails rental = listing instanceof RentalDetails details ? details : null;
        return new PublicPropertySnapshot(
                listing.getId(),
                title,
                cityLabel,
                sector,
                PublicPropertyDisclosurePolicy.safeBhk(listing.getBhkCount()),
                positiveFinite(listing.getTotalAreaSqFt()),
                rental == null ? null : positive(rental.getMonthlyRent()),
                findPublicCover(propertyId(listing))
        );
    }

    private String findPublicCover(Long propertyId) {
        if (propertyId == null) return null;
        List<PropertyMediaAsset> candidates = mediaAssets.findPublicCoverCandidates(
                propertyId, PageRequest.of(0, PUBLIC_COVER_CANDIDATE_LIMIT));
        if (candidates == null) return null;
        for (PropertyMediaAsset asset : candidates) {
            if (asset == null || asset.getRetiredAt() != null
                    || asset.getMediaType() != MediaType.IMAGE) {
                continue;
            }
            String safeUrl = cloudinary.trustedPublicMediaUrl(
                    asset.getMediaUrl(), asset.getCloudinaryPublicId(),
                    asset.getUploadRequestId());
            if (safeUrl != null) return safeUrl;
        }
        return null;
    }

    private PublicPropertyResponse toPublicPropertyResponse(Listing listing, Locality locality) {
        RentalDetails rental = listing instanceof RentalDetails details ? details : null;
        SupportedCity city = locality.getSupportedCity();
        return new PublicPropertyResponse(
                listing.getId(),
                PublicPropertyDisclosurePolicy.safeCanonicalTitle(listing, locality, city),
                PublicPropertyDisclosurePolicy.safeDescription(listing),
                listing.getListingType(),
                listing.getPropertyType(),
                PublicPropertyDisclosurePolicy.canonicalCityLabel(city, locality),
                PublicPropertyDisclosurePolicy.canonicalLocalityLabel(locality),
                PublicPropertyDisclosurePolicy.safeBhk(listing.getBhkCount()),
                null,
                null,
                PublicPropertyDisclosurePolicy.safeAmenities(listing.getAmenities()),
                listing.getTotalAreaSqFt(),
                listing.getBathroomCount(),
                listing.getFloorNumber(),
                listing.getTotalFloors(),
                rental != null ? rental.getMonthlyRent() : null,
                rental != null ? rental.getSecurityDeposit() : null,
                rental != null ? rental.getMaintenanceCharge() : null,
                rental != null ? rental.getBrokerageAmount() : null,
                rental != null ? rental.getBrokerageDays() : null,
                rental != null ? rental.getSecurityDepositMonths() : null,
                rental != null ? rental.getBachelorAllowed() : null,
                null,
                rental != null ? rental.getAvailableFrom() : null,
                listing.getVersion(),
                listing.getLastContentPublishedAt(),
                toPublicMedia(listing)
        );
    }

    private List<PublicPropertyMediaResponse> toPublicMedia(Listing listing) {
        List<PropertyMediaAsset> assets =
                mediaAssets.findByListingIdOrderByUploadedAtDesc(listing.getId());
        return assets == null ? List.of() : assets.stream()
                .filter(asset -> asset.getRetiredAt() == null)
                .filter(this::hasTrustedPublicMediaProvenance)
                .map(asset -> new PublicPropertyMediaResponse(
                        PublicPropertyDisclosurePolicy.safeMediaUrl(asset.getMediaUrl()),
                        asset.getMediaType(), asset.getRoomTag(),
                        Boolean.TRUE.equals(asset.getIsPrimaryCover()), null,
                        asset.getSortOrder()))
                .toList();
    }

    private boolean hasTrustedPublicMediaProvenance(PropertyMediaAsset asset) {
        return asset != null && cloudinary.isTrustedPublicMediaAsset(
                asset.getMediaUrl(), asset.getCloudinaryPublicId(),
                asset.getUploadRequestId());
    }

    private static Long propertyId(Listing listing) {
        return listing == null ? null : listing.getId();
    }

    private static Double positiveFinite(Double value) {
        return value != null && Double.isFinite(value) && value > 0 ? value : null;
    }

    private static BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private record PublicContext(Listing listing, Locality locality, SupportedCity city) {}

    public record PublicPropertySnapshot(
            Long id,
            String title,
            String city,
            String sector,
            String bhk,
            Double totalAreaSqFt,
            BigDecimal monthlyRent,
            String coverImageUrl
    ) {}
}
