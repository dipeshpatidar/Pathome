package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.UserFavoriteRepository;
import com.indore.pathome.spaces.dto.PublicDiscoveryPage;
import com.indore.pathome.spaces.dto.PublicDiscoveryResponse;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.entity.RoomTag;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;

@Service
public class UserFavoriteService {
    public static final int MAX_BATCH_SIZE = 100;
    public static final int DEFAULT_SAVED_PAGE_SIZE = 6;
    public static final int MAX_SAVED_PAGE_SIZE = 24;
    private static final ZoneId INDIA_ZONE = ZoneId.of("Asia/Kolkata");

    private final UserFavoriteRepository favorites;
    private final ListingRepository listings;
    private final PropertyMediaAssetRepository mediaAssets;

    public UserFavoriteService(UserFavoriteRepository favorites, ListingRepository listings,
            PropertyMediaAssetRepository mediaAssets) {
        this.favorites = favorites;
        this.listings = listings;
        this.mediaAssets = mediaAssets;
    }

    @Transactional(readOnly = true)
    public List<Long> listSavedActiveProperties(Long userId, Collection<Long> propertyIds) {
        requireUserId(userId);
        if (propertyIds == null || propertyIds.isEmpty()) return List.of();
        if (propertyIds.size() > MAX_BATCH_SIZE || propertyIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Provide between 1 and 100 valid property IDs");
        }
        return favorites.findSavedActiveListingIds(userId, propertyIds.stream().distinct().toList());
    }

    @Transactional(readOnly = true)
    public PublicDiscoveryPage listSavedActiveListings(Long userId, int page, int pageSize) {
        requireUserId(userId);
        if (page < 0 || pageSize < 1 || pageSize > MAX_SAVED_PAGE_SIZE
                || (long) page * pageSize > Integer.MAX_VALUE - pageSize - 1L) {
            throw new IllegalArgumentException("Saved property page is invalid");
        }

        List<Long> fetchedIds = favorites.findSavedActiveListingPage(
                userId, pageSize + 1, Math.multiplyExact(page, pageSize));
        boolean hasMore = fetchedIds.size() > pageSize;
        List<Long> ids = fetchedIds.stream().limit(pageSize).toList();
        if (ids.isEmpty()) return new PublicDiscoveryPage(List.of(), page, pageSize, false);

        Map<Long, Listing> listingsById = listings.findAllById(ids).stream()
                .filter(listing -> listing.getId() != null)
                .collect(Collectors.toMap(Listing::getId, Function.identity()));
        Map<Long, List<PropertyMediaAsset>> mediaByListingId = mediaAssets
                .findByListingIdInOrderByUploadedAtDesc(ids).stream()
                .filter(asset -> asset.getListingId() != null)
                .collect(Collectors.groupingBy(PropertyMediaAsset::getListingId));

        List<PublicDiscoveryResponse> properties = ids.stream()
                .map(listingsById::get)
                .filter(listing -> listing != null && listing.getStatus() == com.indore.pathome.spaces.entity.ListingStatus.ACTIVE)
                .map(listing -> toDiscoverySummary(listing, mediaByListingId.getOrDefault(listing.getId(), List.of()), hasMore))
                .toList();
        return new PublicDiscoveryPage(properties, page, pageSize, hasMore);
    }

    private static PublicDiscoveryResponse toDiscoverySummary(
            Listing listing, List<PropertyMediaAsset> assets, boolean hasMore) {
        RentalDetails rental = listing instanceof RentalDetails rentalDetails ? rentalDetails : null;
        List<PropertyMediaAsset> usableAssets = assets.stream()
                .filter(asset -> asset.getMediaUrl() != null && !asset.getMediaUrl().isBlank())
                .toList();
        List<PropertyMediaAsset> images = usableAssets.stream()
                .filter(asset -> asset.getMediaType() != MediaType.VIDEO_WALKTHROUGH)
                .toList();
        PropertyMediaAsset cover = images.stream()
                .filter(asset -> Boolean.TRUE.equals(asset.getIsPrimaryCover()))
                .findFirst().orElse(images.isEmpty() ? null : images.get(0));
        String coverUrl = cover == null ? null : cover.getMediaUrl();
        RoomTag coverTag = cover == null ? null : cover.getRoomTag();
        int mediaCount = usableAssets.size();
        boolean hasVideo = usableAssets.stream().anyMatch(asset -> asset.getMediaType() == MediaType.VIDEO_WALKTHROUGH);

        if (usableAssets.isEmpty() && listing.getMediaGalleryUrls() != null && !listing.getMediaGalleryUrls().isBlank()) {
            List<String> legacyUrls = Arrays.stream(listing.getMediaGalleryUrls().split(","))
                    .map(String::trim).filter(url -> !url.isBlank()).toList();
            coverUrl = legacyUrls.stream()
                    .filter(url -> !url.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|webm|mov)(?:[?#].*)?$"))
                    .findFirst().orElse(null);
            mediaCount = legacyUrls.size();
            hasVideo = legacyUrls.stream()
                    .anyMatch(url -> url.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|webm|mov)(?:[?#].*)?$"));
        }

        LocalDateTime updatedAt = listing.getUpdatedAt();
        Instant updatedInstant = updatedAt == null ? null : updatedAt.atZone(INDIA_ZONE).toInstant();
        return new PublicDiscoveryResponse(
                listing.getId(), listing.getTitle(), listing.getListingType(), listing.getPropertyType(),
                listing.getCity(), listing.getSector(), listing.getBhkCount(), listing.getFurnishingStatus(),
                listing.getVastuFacing(), listing.getTotalAreaSqFt(), rental == null ? null : rental.getMonthlyRent(),
                rental == null ? null : rental.getSecurityDeposit(), rental == null ? null : rental.getMaintenanceCharge(),
                rental == null ? null : rental.getBachelorAllowed(), rental == null ? null : rental.getPreferredTenant(),
                rental == null ? null : rental.getAvailableFrom(), coverUrl, coverTag, mediaCount, hasVideo, hasMore,
                updatedInstant);
    }

    @Transactional
    public void save(Long userId, Long propertyId) {
        requireUserId(userId);
        requirePropertyId(propertyId);
        if (listings.findByIdAndStatus(propertyId, com.indore.pathome.spaces.entity.ListingStatus.ACTIVE).isEmpty()) {
            throw new EntityNotFoundException("Property not found");
        }
        favorites.saveIfAbsent(userId, propertyId);
    }

    @Transactional
    public void remove(Long userId, Long propertyId) {
        requireUserId(userId);
        requirePropertyId(propertyId);
        favorites.remove(userId, propertyId);
    }

    private static void requireUserId(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("Authenticated user required");
    }

    private static void requirePropertyId(Long propertyId) {
        if (propertyId == null || propertyId <= 0) throw new IllegalArgumentException("Valid property ID required");
    }
}
