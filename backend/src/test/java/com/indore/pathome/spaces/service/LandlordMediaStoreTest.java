package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordMediaStoreTest {
    private LandlordCapabilityService capabilities;
    private PropertyUploadDraftRepository drafts;
    private PropertyDraftMediaRepository media;
    private LandlordMediaStore store;

    @BeforeEach
    void setUp() {
        capabilities = mock(LandlordCapabilityService.class);
        drafts = mock(PropertyUploadDraftRepository.class);
        media = mock(PropertyDraftMediaRepository.class);
        store = new LandlordMediaStore(capabilities, drafts, media);
        when(capabilities.requireLandlordUserId("owner@example.com")).thenReturn(5L);
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setStatus("DRAFT");
        when(drafts.findLandlordDraftForUpdate("d1", 5L)).thenReturn(Optional.of(draft));
        when(drafts.findByDraftIdAndLandlordUserId("d1", 5L)).thenReturn(Optional.of(draft));
        when(media.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void firstCompletedImageBecomesCoverButVideoCannot() {
        PropertyDraftMedia video = item("video", "video/mp4", "PENDING", false, 0);
        PropertyDraftMedia image = item("image", "image/jpeg", "PENDING", false, 1);
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("video", "d1", 5L)).thenReturn(Optional.of(video));
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("image", "d1", 5L)).thenReturn(Optional.of(image));
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L))
                .thenReturn(new ArrayList<>(List.of(video, image)));
        var videoResult = new CloudinaryService.CloudinaryUploadResult("https://example.com/video", "v", "video");
        var imageResult = new CloudinaryService.CloudinaryUploadResult("https://example.com/image", "i", "image");
        assertFalse(store.complete("owner@example.com", "d1", "video", videoResult).cover());
        assertTrue(store.complete("owner@example.com", "d1", "image", imageResult).cover());
        assertThrows(IllegalArgumentException.class, () -> store.makeCover("owner@example.com", "d1", "video"));
        assertTrue(store.makeCover("owner@example.com", "d1", "image").stream()
                .filter(row -> row.mediaId().equals("image")).findFirst().orElseThrow().cover());
    }

    @Test
    void deletingCoverPromotesFirstRemainingImageAndDoesNotTouchOtherOwner() {
        PropertyDraftMedia first = item("first", "image/jpeg", "DELETING", true, 0);
        PropertyDraftMedia second = item("second", "image/jpeg", "UPLOADED", false, 1);
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("first", "d1", 5L)).thenReturn(Optional.of(first));
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L)).thenReturn(List.of(second));
        store.finishDeleting("owner@example.com", "d1", "first");
        assertTrue(second.getIsCover());
        verify(media).delete(first);
        assertThrows(EntityNotFoundException.class, () -> store.recoverable("owner@example.com", "d1", "other"));
        verify(media, never()).findByMediaId("other");
    }

    @Test
    void rejectsIncompleteOrDuplicateReorder() {
        PropertyDraftMedia a = item("a", "image/jpeg", "UPLOADED", true, 0);
        PropertyDraftMedia b = item("b", "image/jpeg", "UPLOADED", false, 1);
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L)).thenReturn(List.of(a, b));
        assertThrows(IllegalArgumentException.class, () -> store.reorder("owner@example.com", "d1", List.of("a", "a")));
        assertThrows(IllegalArgumentException.class, () -> store.reorder("owner@example.com", "d1", List.of("a")));
        var reordered = store.reorder("owner@example.com", "d1", List.of("b", "a"));
        assertEquals("b", reordered.get(0).mediaId());
        assertTrue(a.getIsCover());
    }

    @Test
    void claimedStagedImagesKeepOneCoverThroughPromotionAndEditing() {
        PropertyDraftMedia first = item("first", "image/png", "STAGED", true, 0);
        PropertyDraftMedia second = item("second", "image/png", "STAGED", false, 1);
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("first", "d1", 5L)).thenReturn(Optional.of(first));
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("second", "d1", 5L)).thenReturn(Optional.of(second));
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L))
                .thenReturn(new ArrayList<>(List.of(first, second)));

        assertEquals("second", store.reorder("owner@example.com", "d1", List.of("second", "first")).get(0).mediaId());
        store.makeCover("owner@example.com", "d1", "second");
        assertFalse(first.getIsCover());
        assertTrue(second.getIsCover());

        var result = new CloudinaryService.CloudinaryUploadResult("https://example.com/image", "i", "image");
        assertFalse(store.complete("owner@example.com", "d1", "first", result).cover());
        assertTrue(store.complete("owner@example.com", "d1", "second", result).cover());
    }

    private PropertyDraftMedia item(String id, String contentType, String status, boolean cover, int order) {
        PropertyDraftMedia item = new PropertyDraftMedia();
        item.setMediaId(id);
        item.setDraftId("d1");
        item.setLandlordUserId(5L);
        item.setContentType(contentType);
        item.setUploadStatus(status);
        item.setIsCover(cover);
        item.setSortOrder(order);
        return item;
    }
}
