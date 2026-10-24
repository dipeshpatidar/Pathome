package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.LeadRoutingQueue;
import com.indore.pathome.spaces.entity.LeadIntent;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.ListingType;
import com.indore.pathome.spaces.entity.Locality;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.exception.VisitOperationsConflictException;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.LocalityRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class LeadPublicPropertyService {
    private final ListingRepository listings;
    private final LocalityRepository localities;

    public LeadPublicPropertyService(ListingRepository listings, LocalityRepository localities) {
        this.listings = listings;
        this.localities = localities;
    }

    public List<EligibleProperty> requireCurrentPublicProperties(LeadRoutingQueue lead,
                                                                 List<Long> listingIds) {
        if (lead.getSupportedCity() == null) {
            throw new VisitOperationsConflictException(
                    "A canonical Lead City is required before selecting public properties");
        }
        if (lead.getIntent() == LeadIntent.BUY) {
            throw new VisitOperationsConflictException(
                    "BUY enquiries cannot use the rental property shortlist or manual share flow");
        }
        if (listingIds == null || listingIds.isEmpty()) return List.of();
        if (listingIds.size() > 20) {
            throw new IllegalArgumentException("At most 20 public properties may be shortlisted");
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>(listingIds);
        if (distinct.size() != listingIds.size()) {
            throw new IllegalArgumentException("A public property may appear only once in a shortlist");
        }
        Map<Long, EligibleProperty> eligibleById = currentPublicPropertiesById(
                lead, distinct, true);
        List<EligibleProperty> result = new ArrayList<>(distinct.size());
        for (Long listingId : distinct) {
            EligibleProperty property = eligibleById.get(listingId);
            if (property == null) {
                throw new VisitOperationsConflictException(
                        "Public property " + listingId + " is no longer available; refresh the shortlist");
            }
            result.add(property);
        }
        return List.copyOf(result);
    }

    /**
     * Final manual-share authorization uses the same eligibility rules while holding the selected
     * listing rows, so an unpublish or fact edit cannot commit between the check and event write.
     */
    public List<EligibleProperty> requireCurrentPublicPropertiesForUpdate(
            LeadRoutingQueue lead, List<Long> listingIds) {
        if (listingIds != null && !listingIds.isEmpty()) {
            listings.lockAllByIdIn(listingIds.stream().distinct().sorted().toList());
        }
        return requireCurrentPublicProperties(lead, listingIds);
    }

    /**
     * Batch read for share-history projections. One listing query and one locality query serve every
     * unique property across the page; per-share membership remains strict and ordered.
     */
    public Map<Long, EligibleProperty> currentPublicPropertiesById(
            LeadRoutingQueue lead, Collection<Long> requestedIds, boolean announceMissing) {
        if (lead.getSupportedCity() == null || lead.getIntent() == LeadIntent.BUY
                || requestedIds == null || requestedIds.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>(requestedIds);
        if (ids.size() > 400) {
            throw new IllegalArgumentException("Too many public properties were requested");
        }
        List<Listing> available = listings.findAllByIdInAndStatus(ids, ListingStatus.ACTIVE);
        Map<Long, Listing> byId = new HashMap<>();
        available.forEach(listing -> byId.put(listing.getId(), listing));
        List<Long> localityIds = available.stream()
                .map(Listing::getCanonicalLocalityId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Locality> localityById = localityIds.isEmpty() ? Map.of()
                : localities.findAllById(localityIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Locality::getId, value -> value));
        Map<Long, EligibleProperty> result = new HashMap<>();
        for (Long listingId : ids) {
            Listing listing = byId.get(listingId);
            if (listing == null || listing.getListingType() != ListingType.RENT
                    || !(listing instanceof RentalDetails rental)
                    || listing.getCanonicalLocalityId() == null) {
                continue;
            }
            Locality locality = localityById.get(listing.getCanonicalLocalityId());
            if (locality == null || locality.getSupportedCity() == null
                    || !lead.getSupportedCity().getId().equals(locality.getSupportedCity().getId())) {
                continue;
            }
            result.put(listingId, new EligibleProperty(listing.getId(), listing.getTitle(),
                    listing.getCity() + " / " + listing.getSector(), listing.getBhkCount(),
                    rental.getMonthlyRent(), listing.getVersion()));
        }
        if (announceMissing && result.size() != ids.size()) {
            Long missing = ids.stream().filter(id -> !result.containsKey(id)).findFirst().orElse(null);
            throw new VisitOperationsConflictException(
                    "Public property " + missing + " is no longer available; refresh the shortlist");
        }
        return Map.copyOf(result);
    }

    public record EligibleProperty(
            Long listingId,
            String title,
            String location,
            String bhk,
            BigDecimal monthlyRent,
            Long listingVersion) {
    }
}
