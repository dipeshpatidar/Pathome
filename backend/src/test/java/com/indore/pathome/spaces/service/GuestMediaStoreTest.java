package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuestMediaStoreTest {
    @Test
    void anotherGuestCannotMutateMediaEvenWithItsId() {
        var guests = mock(GuestDraftService.class);
        var drafts = mock(PropertyUploadDraftRepository.class);
        var media = mock(PropertyDraftMediaRepository.class);
        var store = new GuestMediaStore(guests, drafts, media, 2, 120_000_000);
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("guest-one"); draft.setGuestTokenHash("hash");
        when(drafts.findByDraftIdForUpdate("guest-one")).thenReturn(Optional.of(draft));
        when(guests.require("guest-one", "guest-B-proof")).thenThrow(new EntityNotFoundException("Draft unavailable"));

        assertThrows(EntityNotFoundException.class,
                () -> store.cover("guest-one", "guest-B-proof", "known-media-id"));
        assertThrows(EntityNotFoundException.class,
                () -> store.claim("guest-one", "guest-B-proof", "new-media", "photo.jpg", "image/jpeg", 10));
        assertThrows(EntityNotFoundException.class,
                () -> store.tag("guest-one", "guest-B-proof", "known-media-id", RoomTag.BEDROOM));
        verifyNoInteractions(media);
    }

    @Test
    void enforcesFileCountAndTotalBytesOnTheServer() {
        var guests = mock(GuestDraftService.class);
        var drafts = mock(PropertyUploadDraftRepository.class);
        var media = mock(PropertyDraftMediaRepository.class);
        var store = new GuestMediaStore(guests, drafts, media, 2, 110_000_000);
        PropertyUploadDraft draft = new PropertyUploadDraft(); draft.setGuestTokenHash("hash");
        when(drafts.findByDraftIdForUpdate("guest-one")).thenReturn(Optional.of(draft));
        var first = new com.indore.pathome.spaces.entity.PropertyDraftMedia(); first.setFileSizeBytes(60_000_000L);
        var second = new com.indore.pathome.spaces.entity.PropertyDraftMedia(); second.setFileSizeBytes(40_000_000L);
        when(media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc("guest-one"))
                .thenReturn(List.of(first, second));
        assertThrows(IllegalArgumentException.class,
                () -> store.claim("guest-one", "proof", "new-media", "photo.jpg", "image/jpeg", 1));
        verify(media, never()).saveAndFlush(any());
    }

    @Test
    void completedParallelPhotoUsesDatabaseCoverCheckDespiteStaleMediaState() {
        var guests = mock(GuestDraftService.class);
        var drafts = mock(PropertyUploadDraftRepository.class);
        var media = mock(PropertyDraftMediaRepository.class);
        var store = new GuestMediaStore(guests, drafts, media, 2, 110_000_000);
        PropertyUploadDraft draft = new PropertyUploadDraft(); draft.setGuestTokenHash("hash");
        when(drafts.findByDraftIdForUpdate("guest-one")).thenReturn(Optional.of(draft));
        PropertyDraftMedia second = new PropertyDraftMedia();
        second.setMediaId("second"); second.setContentType("image/png"); second.setUploadStatus("PENDING"); second.setIsCover(false);
        when(media.findByMediaIdAndDraftIdAndGuestOwnedTrue("second", "guest-one"))
                .thenReturn(Optional.of(second));
        when(media.existsByDraftIdAndGuestOwnedTrueAndIsCoverTrueAndUploadStatusAndMediaIdNot(
                "guest-one", "STAGED", "second")).thenReturn(true);
        when(media.saveAndFlush(second)).thenReturn(second);

        store.complete("guest-one", "proof", "second");
        assertFalse(second.getIsCover());
        verify(media).existsByDraftIdAndGuestOwnedTrueAndIsCoverTrueAndUploadStatusAndMediaIdNot(
                "guest-one", "STAGED", "second");
    }
}
