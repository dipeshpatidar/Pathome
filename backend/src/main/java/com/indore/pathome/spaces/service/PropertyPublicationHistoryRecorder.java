package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.PropertyPublicationOutbox;
import com.indore.pathome.spaces.entity.PropertyPublicationVersion;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyPublicationOutboxRepository;
import com.indore.pathome.spaces.repository.PropertyPublicationVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared immutable publication evidence for staff and legacy approved-publication writers. */
@Service
public class PropertyPublicationHistoryRecorder {
    private final PropertyPublicationVersionRepository publications;
    private final PropertyPublicationOutboxRepository outbox;
    private final PropertyMediaAssetRepository media;
    private final DatabaseClock clock;

    public PropertyPublicationHistoryRecorder(PropertyPublicationVersionRepository publications,
                                              PropertyPublicationOutboxRepository outbox,
                                              PropertyMediaAssetRepository media,
                                              DatabaseClock clock) {
        this.publications = publications;
        this.outbox = outbox;
        this.media = media;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Publication record(Listing listing, Map<String, Object> before, String revisionId,
                              String eventKind, Long actorUserId,
                              Map<String, Object> authorityEvidence) {
        Map<String, Object> after = snapshot(listing);
        Map<String, Object> changed = changed(before, after);
        long version = publications.currentVersion(listing.getId()) + 1;
        Instant at = clock.now();
        publications.saveAndFlush(new PropertyPublicationVersion(listing.getId(), version,
                revisionId, eventKind, actorUserId, authorityEvidence,
                before, after, changed, at));
        outbox.saveAndFlush(new PropertyPublicationOutbox(listing.getId(), version, eventKind,
                Map.of("listingId", listing.getId(), "publicationVersion", version,
                        "listingVersion", listing.getVersion(),
                        "changedFields", changed.keySet().stream().sorted().toList()), at));
        return new Publication(version, at);
    }

    public Map<String, Object> snapshot(Listing listing) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", listing.getTitle());
        values.put("titleProvenance", listing.getTitleProvenance());
        values.put("description", listing.getDescription());
        values.put("propertyType", listing.getPropertyType());
        values.put("bhkCount", listing.getBhkCount());
        values.put("address", listing.getAddress());
        values.put("landmark", listing.getLandmark());
        values.put("colony", listing.getColony());
        values.put("pincode", listing.getPincode());
        values.put("totalAreaSqFt", listing.getTotalAreaSqFt());
        values.put("floorNumber", listing.getFloorNumber());
        values.put("totalFloors", listing.getTotalFloors());
        values.put("furnishingStatus", listing.getFurnishingStatus());
        values.put("amenities", listing.getAmenities());
        values.put("bathroomCount", listing.getBathroomCount());
        values.put("vastuFacing", listing.getVastuFacing());
        if (listing instanceof RentalDetails rental) {
            values.put("monthlyRent", rental.getMonthlyRent());
            values.put("securityDeposit", rental.getSecurityDeposit());
            values.put("availableFrom", rental.getAvailableFrom());
            values.put("maintenanceCharge", rental.getMaintenanceCharge());
            values.put("brokerageAmount", rental.getBrokerageAmount());
            values.put("brokerageDays", rental.getBrokerageDays());
            values.put("securityDepositMonths", rental.getSecurityDepositMonths());
            values.put("bachelorAllowed", rental.getBachelorAllowed());
            values.put("preferredTenant", rental.getPreferredTenant());
        }
        List<Map<String, Object>> mediaRows = media.findByListingIdOrderByUploadedAtDesc(listing.getId())
                .stream().map(PropertyPublicationHistoryRecorder::mediaSnapshot).toList();
        values.put("media", mediaRows);
        return values;
    }

    private static Map<String, Object> mediaSnapshot(PropertyMediaAsset asset) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resourceId", asset.getId());
        row.put("url", asset.getMediaUrl());
        row.put("mediaType", asset.getMediaType());
        row.put("roomTag", asset.getRoomTag());
        row.put("caption", asset.getCaption());
        row.put("sortOrder", asset.getSortOrder());
        row.put("cover", Boolean.TRUE.equals(asset.getIsPrimaryCover()));
        return row;
    }

    private static Map<String, Object> changed(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            Object previous = before == null ? null : before.get(entry.getKey());
            if (before == null || !java.util.Objects.equals(previous, entry.getValue())) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", previous == null ? "" : previous);
                change.put("after", entry.getValue() == null ? "" : entry.getValue());
                result.put(entry.getKey(), change);
            }
        }
        return result;
    }

    public record Publication(long version, Instant publishedAt) {}
}

