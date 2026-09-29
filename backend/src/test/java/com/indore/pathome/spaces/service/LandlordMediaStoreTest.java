package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.exception.DraftConflictException;
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
        when(capabilities.requireOnboardingUserId("owner@example.com")).thenReturn(5L);
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setStatus("DRAFT");
        when(drafts.findLandlordDraftForUpdate("d1", 5L)).thenReturn(Optional.of(draft));
        when(drafts.findByDraftIdAndLandlordUserId("d1", 5L)).thenReturn(Optional.of(draft));
        when(media.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(media.findLessorCoverFlag(eq("d1"), eq(5L), anyString())).thenAnswer(invocation ->
                media.findByMediaIdAndDraftIdAndLandlordUserId(invocation.getArgument(2), "d1", 5L)
                        .map(PropertyDraftMedia::getIsCover));
        when(media.existsByDraftIdAndLandlordUserIdAndIsCoverTrueAndUploadStatusInAndMediaIdNotAndContentTypeStartingWith(
                eq("d1"), eq(5L), anyList(), anyString(), eq("image/"))).thenAnswer(invocation ->
                media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L).stream()
                        .anyMatch(row -> !row.getMediaId().equals(invocation.getArgument(3))
                                && Boolean.TRUE.equals(row.getIsCover())
                                && List.of("STAGED", "UPLOADED", "DELETING").contains(row.getUploadStatus())));
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
    void lateUploadCompletionCannotRecreateMediaAfterDraftWasDiscarded() {
        PropertyUploadDraft discarded = new PropertyUploadDraft();
        discarded.setStatus("DISCARDED");
        when(drafts.findLandlordDraftForUpdate("d1", 5L)).thenReturn(Optional.of(discarded));

        var result = new CloudinaryService.CloudinaryUploadResult("https://example.com/image", "i", "image");
        assertThrows(DraftConflictException.class,
                () -> store.complete("owner@example.com", "d1", "late-upload", result));

        verify(media, never()).findByMediaIdAndDraftIdAndLandlordUserId(anyString(), anyString(), anyLong());
        verify(media, never()).saveAndFlush(any());
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
    void deletingOldCoverDoesNotOverrideAReplacementChosenDuringRemoteRemoval() {
        PropertyDraftMedia deleting = item("first", "image/jpeg", "DELETING", true, 0);
        PropertyDraftMedia replacement = item("replacement", "image/jpeg", "UPLOADED", true, 1);
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("first", "d1", 5L)).thenReturn(Optional.of(deleting));
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L))
                .thenReturn(List.of(replacement));

        store.finishDeleting("owner@example.com", "d1", "first");

        assertTrue(replacement.getIsCover());
        verify(media, never()).save(any(PropertyDraftMedia.class));
    }

    @Test
    void uploadCompletingWhileCoverRemovalRunsDoesNotCreateASecondCover() {
        PropertyDraftMedia deletingCover = item("first", "image/jpeg", "DELETING", true, 0);
        PropertyDraftMedia arriving = item("second", "image/jpeg", "PENDING", false, 1);
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("second", "d1", 5L)).thenReturn(Optional.of(arriving));
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L))
                .thenReturn(List.of(deletingCover, arriving));
        var result = new CloudinaryService.CloudinaryUploadResult("https://example.com/image", "i", "image");

        assertFalse(store.complete("owner@example.com", "d1", "second", result).cover());
        assertTrue(deletingCover.getIsCover());
        assertFalse(arriving.getIsCover());

        when(media.findByMediaIdAndDraftIdAndLandlordUserId("first", "d1", 5L)).thenReturn(Optional.of(deletingCover));
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L)).thenReturn(List.of(arriving));
        store.finishDeleting("owner@example.com", "d1", "first");
        assertTrue(arriving.getIsCover());
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

    @Test
    void lateCompletionRepairsDuplicateCoverFlagsUnderDraftLock() {
        PropertyDraftMedia first = item("first", "image/png", "STAGED", true, 0);
        PropertyDraftMedia second = item("second", "image/png", "UPLOADED", true, 1);
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("first", "d1", 5L)).thenReturn(Optional.of(first));
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L))
                .thenReturn(List.of(first, second));
        when(media.clearOtherLessorCovers("d1", 5L, "first")).thenAnswer(invocation -> {
            second.setIsCover(false);
            return 1;
        });
        var result = new CloudinaryService.CloudinaryUploadResult("https://example.com/image", "i", "image");
        assertTrue(store.complete("owner@example.com", "d1", "first", result).cover());
        assertFalse(second.getIsCover());
        verify(media).clearOtherLessorCovers("d1", 5L, "first");
    }

    @Test
    void stalePendingEntityDoesNotBecomeSecondCoverWhenDatabaseAlreadyHasOne() {
        PropertyDraftMedia first = item("first", "image/png", "PENDING", false, 0);
        PropertyDraftMedia second = item("second", "image/png", "PENDING", false, 1);
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("first", "d1", 5L)).thenReturn(Optional.of(first));
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("second", "d1", 5L)).thenReturn(Optional.of(second));
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("d1", 5L))
                .thenReturn(List.of(first, second));
        var result = new CloudinaryService.CloudinaryUploadResult("https://example.com/image", "i", "image");
        assertTrue(store.complete("owner@example.com", "d1", "first", result).cover());
        // This entity snapshot is stale even though the scalar database query sees the first cover.
        first.setIsCover(false);
        doReturn(true).when(media).existsByDraftIdAndLandlordUserIdAndIsCoverTrueAndUploadStatusInAndMediaIdNotAndContentTypeStartingWith(
                "d1", 5L, List.of("UPLOADED", "STAGED", "DELETING"), "second", "image/");
        assertFalse(store.complete("owner@example.com", "d1", "second", result).cover());
        verify(media, times(1)).clearOtherLessorCovers(anyString(), eq(5L), anyString());
    }

    @Test
    void photoTagUsesExistingRoomTagAndRejectsOtherOwners() {
        PropertyDraftMedia photo = item("photo", "image/png", "UPLOADED", true, 0);
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("photo", "d1", 5L)).thenReturn(Optional.of(photo));
        assertEquals(RoomTag.KITCHEN, store.tag("owner@example.com", "d1", "photo", RoomTag.KITCHEN).roomTag());
        assertEquals("KITCHEN", photo.getRoomTag());
        assertThrows(EntityNotFoundException.class,
                () -> store.tag("owner@example.com", "d1", "another-owner", RoomTag.BEDROOM));
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
