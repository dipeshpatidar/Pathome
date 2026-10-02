package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.TenantVisitRequestPage;
import com.indore.pathome.spaces.dto.TenantVisitRequestResponse;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyVisitRequestRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TenantVisitRequestHistoryService {
    private static final int PAGE_SIZE = 8;
    private final PropertyVisitRequestRepository requests;
    private final PropertyMediaAssetRepository media;

    public TenantVisitRequestHistoryService(PropertyVisitRequestRepository requests, PropertyMediaAssetRepository media) {
        this.requests = requests;
        this.media = media;
    }

    @Transactional(readOnly = true)
    public TenantVisitRequestPage listForUser(Long userId, int page) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Authenticated user ID required");
        }
        if (page < 0) {
            throw new IllegalArgumentException("Page must be zero or greater");
        }

        var result = requests.findByTenantId(userId,
                PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        List<Long> listingIds = result.getContent().stream()
                .map(request -> request.getListing().getId()).distinct().toList();
        Map<Long, String> coverByListing = listingIds.isEmpty() ? Map.of() :
                media.findByListingIdInOrderByUploadedAtDesc(listingIds).stream()
                        .filter(asset -> asset.getMediaType() == MediaType.IMAGE
                                && asset.getMediaUrl() != null && !asset.getMediaUrl().isBlank())
                        .collect(Collectors.groupingBy(PropertyMediaAsset::getListingId,
                                Collectors.collectingAndThen(Collectors.toList(), assets -> assets.stream()
                                        .filter(asset -> Boolean.TRUE.equals(asset.getIsPrimaryCover()))
                                        .findFirst().orElse(assets.get(0)).getMediaUrl())));

        List<TenantVisitRequestResponse> items = result.getContent().stream().map(request -> {
            var listing = request.getListing();
            return new TenantVisitRequestResponse(request.getId(), listing.getId(), listing.getTitle(),
                    listing.getPropertyType() == null ? null : listing.getPropertyType().name(), listing.getBhkCount(),
                    listing.getCity(), listing.getSector(), listing.getStatus() == ListingStatus.ACTIVE,
                    coverByListing.get(listing.getId()),
                    request.getPreferredVisitTiming(), request.getStatus(), request.getCreatedAt(),
                    request.getAvailabilityStartAt(), request.getAvailabilityEndAt(),
                    request.getAvailabilityZoneId(), request.getPreferredAt());
        }).toList();
        return new TenantVisitRequestPage(userId, items, result.getTotalElements(), page, result.hasNext());
    }
}
