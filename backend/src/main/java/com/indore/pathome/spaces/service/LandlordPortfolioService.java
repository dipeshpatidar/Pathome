package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordListingPage;
import com.indore.pathome.spaces.dto.lessor.LandlordListingSummary;
import com.indore.pathome.spaces.dto.lessor.LandlordListingDetail;
import com.indore.pathome.spaces.dto.lessor.LandlordMediaItem;
import com.indore.pathome.spaces.dto.lessor.LandlordPreview;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LandlordPortfolioService {
    private static final int PAGE_SIZE = 20;
    private final LandlordCapabilityService capabilities;
    private final ListingRepository listings;
    private final PropertyMediaAssetRepository assets;
    private final PropertyUploadDraftRepository drafts;

    public LandlordPortfolioService(LandlordCapabilityService capabilities, ListingRepository listings,
                                    PropertyMediaAssetRepository assets, PropertyUploadDraftRepository drafts) {
        this.capabilities = capabilities;
        this.listings = listings;
        this.assets = assets;
        this.drafts = drafts;
    }

    @Transactional(readOnly = true)
    public LandlordListingPage list(String email, int page) {
        Long ownerId = capabilities.requireLandlordUserId(email);
        if (page < 0) throw new IllegalArgumentException("Page must be zero or greater");
        var slice = listings.findLandlordRentals(ownerId, PageRequest.of(page, PAGE_SIZE));
        List<Long> ids = slice.getContent().stream().map(rental -> rental.getId()).toList();
        Map<Long, List<PropertyMediaAsset>> mediaByListing = ids.isEmpty() ? Map.of() :
                assets.findByListingIdInOrderByUploadedAtDesc(ids).stream()
                        .collect(Collectors.groupingBy(PropertyMediaAsset::getListingId));
        Map<Long, PropertyUploadDraft> revisions = ids.isEmpty() ? Map.of() :
                drafts.findByPublishedPropertyIdInAndLandlordUserIdAndStatusIn(ids, ownerId, List.of("DRAFT", "REVIEW", "CHANGES_REQUIRED"))
                        .stream().collect(Collectors.toMap(PropertyUploadDraft::getPublishedPropertyId,
                                draft -> draft, (first, ignored) -> first));
        var summaries = slice.getContent().stream().map(rental -> {
            List<PropertyMediaAsset> photos = mediaByListing.getOrDefault(rental.getId(), List.of()).stream()
                    .filter(asset -> asset.getMediaType() == MediaType.IMAGE && asset.getMediaUrl() != null)
                    .toList();
            String cover = photos.stream().filter(asset -> Boolean.TRUE.equals(asset.getIsPrimaryCover()))
                    .findFirst().or(() -> photos.stream().findFirst())
                    .map(PropertyMediaAsset::getMediaUrl).orElse(null);
            return new LandlordListingSummary(rental.getId(), rental.getTitle(), rental.getPropertyType(),
                    rental.getBhkCount(), rental.getCity(), rental.getSector(), rental.getMonthlyRent(),
                    rental.getWorkflowStatus(), rental.getUpdatedAt(), cover,
                    revisions.containsKey(rental.getId()) ? revisions.get(rental.getId()).getDraftId() : null,
                    revisions.containsKey(rental.getId()) ? revisions.get(rental.getId()).getStatus() : null);
        }).toList();
        return new LandlordListingPage(summaries, page, slice.hasNext());
    }

    @Transactional(readOnly = true)
    public LandlordListingDetail get(String email, Long listingId) {
        Long ownerId = capabilities.requireLandlordUserId(email);
        var listing = listings.findByIdAndOwnerUserId(listingId, ownerId)
                .filter(RentalDetails.class::isInstance)
                .map(RentalDetails.class::cast)
                .orElseThrow(() -> new EntityNotFoundException("Property not found"));
        var media = assets.findByListingIdOrderByUploadedAtDesc(listingId).stream()
                .map(asset -> new LandlordMediaItem(String.valueOf(asset.getId()), null,
                        asset.getMediaType() == MediaType.IMAGE ? "image/*" : "video/*",
                        asset.getMediaUrl(), "UPLOADED", Boolean.TRUE.equals(asset.getIsPrimaryCover()), 0))
                .toList();
        var preview = new LandlordPreview(listing.getTitle(), listing.getPropertyType(), listing.getBhkCount(),
                listing.getCity(), listing.getSector(), listing.getMonthlyRent(), listing.getSecurityDeposit(),
                listing.getAvailableFrom() == null ? null : listing.getAvailableFrom().toLocalDate(),
                listing.getFurnishingStatus(), listing.getTotalAreaSqFt(), listing.getDescription(), media, List.of());
        var revision = drafts.findFirstByPublishedPropertyIdAndLandlordUserIdAndStatusInOrderByIdDesc(
                listingId, ownerId, List.of("DRAFT", "REVIEW", "CHANGES_REQUIRED"));
        return new LandlordListingDetail(listingId, listing.getWorkflowStatus(), listing.getVersion(),
                revision.map(draft -> draft.getDraftId()).orElse(null),
                revision.map(draft -> draft.getStatus()).orElse(null),
                revision.map(draft -> draft.getReviewNote()).orElse(listing.getReviewNote()), preview);
    }
}
