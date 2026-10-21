package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Batch property-cover resolution for the Operations workspace.
 *
 * <p>A cover is public only for an ACTIVE listing. The resolver never returns draft media or an
 * invented image: it selects the primary property image, then the first genuine image, then a
 * supported legacy gallery entry. All media for the requested listings is read in one query.</p>
 */
@Service
public class OperationsPropertyCoverResolver {
    private static final Pattern CLOUDINARY_IMAGE_UPLOAD =
            Pattern.compile("^(https?://res\\.cloudinary\\.com/.+/image/upload/)(.+)$");
    private static final int COVER_WIDTH = 800;

    private final PropertyMediaAssetRepository mediaAssets;

    public OperationsPropertyCoverResolver(PropertyMediaAssetRepository mediaAssets) {
        this.mediaAssets = mediaAssets;
    }

    public Map<Long, PropertyCover> resolve(Collection<Listing> listings) {
        if (listings == null || listings.isEmpty()) return Map.of();
        List<Listing> unique = listings.stream()
                .filter(listing -> listing != null && listing.getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        Listing::getId, listing -> listing, (first, duplicate) -> first,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();
        if (unique.isEmpty()) return Map.of();

        Map<Long, List<PropertyMediaAsset>> byListing = new HashMap<>();
        List<Long> activeIds = unique.stream()
                .filter(listing -> listing.getStatus() == ListingStatus.ACTIVE)
                .map(Listing::getId)
                .toList();
        if (!activeIds.isEmpty()) {
            for (PropertyMediaAsset asset : mediaAssets.findByListingIdInOrderByUploadedAtDesc(activeIds)) {
                if (asset != null && asset.getListingId() != null) {
                    byListing.computeIfAbsent(asset.getListingId(), ignored -> new ArrayList<>()).add(asset);
                }
            }
        }

        Map<Long, PropertyCover> covers = new HashMap<>();
        for (Listing listing : unique) {
            ListingStatus status = listing.getStatus();
            if (status != ListingStatus.ACTIVE) {
                covers.put(listing.getId(), new PropertyCover(null, status));
                continue;
            }
            String cover = resolveActiveCover(listing, byListing.getOrDefault(listing.getId(), List.of()));
            covers.put(listing.getId(), new PropertyCover(cover, status));
        }
        return Map.copyOf(covers);
    }

    private static String resolveActiveCover(Listing listing, List<PropertyMediaAsset> rawAssets) {
        List<PropertyMediaAsset> images = rawAssets.stream()
                .filter(asset -> asset.getMediaType() == MediaType.IMAGE)
                .filter(OperationsPropertyCoverResolver::hasUsableUrl)
                .sorted(Comparator
                        .comparing(PropertyMediaAsset::getSortOrder,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PropertyMediaAsset::getUploadedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PropertyMediaAsset::getId,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        for (PropertyMediaAsset image : images) {
            if (Boolean.TRUE.equals(image.getIsPrimaryCover())) {
                return coverUrl(image.getMediaUrl());
            }
        }
        if (!images.isEmpty()) return coverUrl(images.get(0).getMediaUrl());

        // Supported legacy rows stored comma-separated gallery URLs rather than media assets.
        String gallery = listing.getMediaGalleryUrls();
        if (gallery == null || gallery.isBlank()) return null;
        for (String candidate : gallery.split(",")) {
            String url = candidate == null ? "" : candidate.trim();
            if (isLegacyImage(url)) return coverUrl(url);
        }
        return null;
    }

    private static boolean hasUsableUrl(PropertyMediaAsset asset) {
        return asset.getMediaUrl() != null && !asset.getMediaUrl().isBlank();
    }

    private static boolean isLegacyImage(String url) {
        if (url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("/video/upload/") || lower.endsWith(".mp4") || lower.endsWith(".mov")) {
            return false;
        }
        return lower.contains("/image/upload/")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".avif");
    }

    /**
     * Applies the same non-destructive Cloudinary max-width transformation used by discovery.
     * Non-Cloudinary and already-transformed URLs are returned unchanged.
     */
    static String coverUrl(String original) {
        if (original == null || original.isBlank()) return null;
        Matcher matcher = CLOUDINARY_IMAGE_UPLOAD.matcher(original.trim());
        if (!matcher.matches()) return original.trim();
        return matcher.group(1) + "w_" + COVER_WIDTH + ",c_limit,f_auto,q_auto/" + matcher.group(2);
    }

    public record PropertyCover(String coverImageUrl, ListingStatus listingStatus) {
        public static final PropertyCover NONE = new PropertyCover(null, null);
    }
}
