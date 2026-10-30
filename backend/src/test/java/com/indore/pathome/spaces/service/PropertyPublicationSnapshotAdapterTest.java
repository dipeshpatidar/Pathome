package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.entity.MediaType;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PropertyPublicationSnapshotAdapterTest {
    private final PropertyMediaAssetRepository assets = mock(PropertyMediaAssetRepository.class);
    private final PropertyPublicationSnapshotAdapter adapter =
            new PropertyPublicationSnapshotAdapter(new ObjectMapper().findAndRegisterModules(), assets);

    @Test
    void legacyResourceIdAndStaffSourceIdentityNormalizeToTheSameHistoricalMedia() {
        PropertyMediaAsset asset = new PropertyMediaAsset();
        asset.setId(83L);
        asset.setListingId(42L);
        when(assets.findAllById(org.mockito.ArgumentMatchers.anyIterable())).thenReturn(List.of(asset));

        Map<String, Object> legacy = baseSnapshot(List.of(Map.of(
                "resourceId", 83L,
                "url", "https://cdn.example/room.jpg",
                "mediaType", "IMAGE",
                "roomTag", "MASTER_BEDROOM",
                "caption", "Bedroom",
                "sortOrder", 2,
                "cover", true
        )));
        Map<String, Object> staff = baseSnapshot(List.of(Map.of(
                "sourceMediaAssetId", 83,
                "mediaId", "draft-temporary-id",
                "url", "https://cdn.example/room.jpg",
                "mediaType", "IMAGE",
                "roomTag", "MASTER_BEDROOM",
                "caption", "Bedroom",
                "sortOrder", 2,
                "cover", true,
                "uploadStatus", "UPLOADED"
        )));

        var legacyMedia = adapter.read(legacy, 42L).media();
        var staffMedia = adapter.read(staff, 42L).media();

        assertEquals(1, legacyMedia.size());
        assertEquals(83L, legacyMedia.get(0).sourceMediaAssetId());
        assertEquals(staffMedia.get(0).sourceMediaAssetId(), legacyMedia.get(0).sourceMediaAssetId());
        assertEquals(MediaType.IMAGE, legacyMedia.get(0).mediaType());
        assertEquals(RoomTag.MASTER_BEDROOM, legacyMedia.get(0).roomTag());
        assertEquals("Bedroom", legacyMedia.get(0).caption());
        assertEquals(2, legacyMedia.get(0).sortOrder());
        assertEquals(true, legacyMedia.get(0).cover());
    }

    @Test
    void foreignOrMissingHistoricalResourcesAreNeverSubstituted() {
        PropertyMediaAsset foreign = new PropertyMediaAsset();
        foreign.setId(83L);
        foreign.setListingId(99L);
        when(assets.findAllById(org.mockito.ArgumentMatchers.anyIterable())).thenReturn(List.of(foreign));

        Map<String, Object> snapshot = baseSnapshot(List.of(Map.of(
                "resourceId", 83L,
                "url", "https://cdn.example/foreign.jpg",
                "mediaType", "IMAGE",
                "roomTag", "GENERAL",
                "sortOrder", 0,
                "cover", true
        )));

        assertThrows(IllegalArgumentException.class, () -> adapter.read(snapshot, 42L));
    }

    @Test
    void correctiveRevisionUsesTheAdapterInsteadOfDroppingHistoricalMedia() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/indore/pathome/spaces/service/BackofficePropertyService.java"));
        assertTrue(service.contains("publicationSnapshots.read(source.getAfterSnapshot(), listingId)"));
        assertFalse(service.contains("content.pincode(), List.of())"));
    }

    private static Map<String, Object> baseSnapshot(List<Map<String, Object>> media) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", "Historical home");
        values.put("titleProvenance", "EXPLICIT");
        values.put("description", "Preserved");
        values.put("propertyType", "FLAT");
        values.put("bhkCount", "2BHK");
        values.put("monthlyRent", 25000);
        values.put("securityDeposit", 50000);
        values.put("availableFrom", "2026-11-01");
        values.put("address", "Private address");
        values.put("media", media);
        return values;
    }
}
