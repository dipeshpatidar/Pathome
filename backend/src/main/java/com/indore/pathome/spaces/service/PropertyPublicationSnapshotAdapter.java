package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.backoffice.StaffPropertySnapshot;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only compatibility adapter for the two immutable publication snapshot representations.
 * It never changes stored history and rejects a resource unless it still belongs to this Listing.
 */
@Component
public class PropertyPublicationSnapshotAdapter {
    private final ObjectMapper mapper;
    private final PropertyMediaAssetRepository assets;

    public PropertyPublicationSnapshotAdapter(ObjectMapper mapper, PropertyMediaAssetRepository assets) {
        this.mapper = mapper;
        this.assets = assets;
    }

    public StaffPropertySnapshot read(Map<String, Object> snapshot, Long listingId) {
        if (snapshot == null || listingId == null) {
            throw new IllegalArgumentException("Historical publication content is unavailable");
        }
        Map<String, Object> compatible = new LinkedHashMap<>(snapshot);
        List<Map<String, Object>> media = normalizedMedia(snapshot.get("media"));
        compatible.put("media", media);
        StaffPropertySnapshot content;
        try {
            content = mapper.convertValue(compatible, StaffPropertySnapshot.class);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Historical publication content is invalid", ex);
        }
        requireCurrentListingMembership(content.media(), listingId);
        return content;
    }

    private static List<Map<String, Object>> normalizedMedia(Object value) {
        if (!(value instanceof List<?> source)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : source) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Historical media metadata is invalid");
            }
            Map<?, ?> item = raw;
            Long sourceId = positiveLong(item.get("sourceMediaAssetId"));
            if (sourceId == null) sourceId = positiveLong(item.get("resourceId"));
            if (sourceId == null || string(item.get("url")) == null
                    || string(item.get("mediaType")) == null) {
                throw new IllegalArgumentException("Historical media identity is unavailable");
            }
            Long stableSourceId = sourceId;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceMediaAssetId", stableSourceId);
            row.put("mediaId", nonblank(item.get("mediaId"))
                    .orElseGet(() -> "publication-" + stableSourceId));
            row.put("url", string(item.get("url")));
            row.put("mediaType", MediaType.valueOf(string(item.get("mediaType")).toUpperCase()));
            row.put("roomTag", item.get("roomTag") == null ? RoomTag.GENERAL.name()
                    : RoomTag.valueOf(string(item.get("roomTag")).toUpperCase()).name());
            row.put("caption", string(item.get("caption")));
            row.put("sortOrder", item.get("sortOrder") == null ? null
                    : Math.toIntExact(((Number) item.get("sortOrder")).longValue()));
            row.put("cover", Boolean.TRUE.equals(item.get("cover")) || Boolean.TRUE.equals(item.get("primaryCover")));
            row.put("uploadStatus", nonblank(item.get("uploadStatus")).orElse("UPLOADED"));
            result.add(row);
        }
        return result;
    }

    private void requireCurrentListingMembership(List<StaffPropertySnapshot.Media> media, Long listingId) {
        if (media.isEmpty()) return;
        Set<Long> sourceIds = new HashSet<>();
        for (StaffPropertySnapshot.Media item : media) {
            if (item.sourceMediaAssetId() == null || !sourceIds.add(item.sourceMediaAssetId())) {
                throw new IllegalArgumentException("Historical media identity is duplicated or unavailable");
            }
        }
        Map<Long, PropertyMediaAsset> found = assets.findAllById(sourceIds).stream()
                .collect(java.util.stream.Collectors.toMap(PropertyMediaAsset::getId, item -> item));
        for (Long sourceId : sourceIds) {
            PropertyMediaAsset asset = found.get(sourceId);
            if (asset == null || !listingId.equals(asset.getListingId())) {
                throw new IllegalArgumentException(
                        "Historical media no longer belongs to this property and cannot be reused");
            }
        }
    }

    private static Long positiveLong(Object value) {
        try {
            long parsed = value instanceof Number number ? number.longValue()
                    : Long.parseLong(String.valueOf(value));
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static java.util.Optional<String> nonblank(Object value) {
        String text = string(value);
        return text == null ? java.util.Optional.empty() : java.util.Optional.of(text);
    }

    private static String string(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
