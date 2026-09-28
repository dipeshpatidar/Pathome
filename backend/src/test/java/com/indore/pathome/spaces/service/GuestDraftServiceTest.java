package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.entity.PropertyType;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.RentalMode;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GuestDraftServiceTest {
    private PropertyUploadDraftRepository drafts;
    private PropertyDraftMediaRepository media;
    private LandlordCapabilityService capabilities;
    private MediaStagingService staging;
    private GuestDraftService service;
    private ObjectMapper mapper;
    private final LandlordDraftData.Basics basics =
            new LandlordDraftData.Basics(PropertyType.FLAT, RentalMode.LONG_TERM_RENTAL, "2BHK");

    @BeforeEach
    void setUp() {
        drafts = mock(PropertyUploadDraftRepository.class);
        media = mock(PropertyDraftMediaRepository.class);
        capabilities = mock(LandlordCapabilityService.class);
        staging = mock(MediaStagingService.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        LandlordDraftService existing = new LandlordDraftService(drafts, capabilities, mapper,
                mock(LandlordLocationService.class), media);
        service = new GuestDraftService(drafts, media, existing, capabilities,
                staging, 15, 1, 5);
        when(drafts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(any())).thenReturn(List.of());
    }

    @Test
    void createsOpaqueProofButStoresOnlyHashAndDoesNotExposeSecretInResponse() throws Exception {
        var created = service.create(basics, null, "127.0.0.1");
        var draft = org.mockito.ArgumentCaptor.forClass(PropertyUploadDraft.class);
        verify(drafts).saveAndFlush(draft.capture());
        assertTrue(created.draft().draftId().startsWith("guest-"));
        assertEquals(43, created.credential().length());
        assertEquals(64, draft.getValue().getGuestTokenHash().length());
        assertFalse(draft.getValue().getGuestTokenHash().contains(created.credential()));
        assertFalse(mapper.writeValueAsString(created.draft()).contains(created.credential()));
        assertNull(draft.getValue().getLandlordUserId());
        assertEquals("DRAFT", draft.getValue().getStatus());
    }

    @Test
    void sameGuestSessionResumesItsSingleActiveDraftWithoutCreatingAnother() {
        var first = service.create(basics, null, "127.0.0.1");
        PropertyUploadDraft saved = draftFor(first);
        when(drafts.findByGuestTokenHashAndStatus(saved.getGuestTokenHash(), "DRAFT"))
                .thenReturn(Optional.of(saved));

        var repeated = service.create(basics, first.credential(), "127.0.0.1");
        assertEquals(first.draft().draftId(), repeated.draft().draftId());
        assertNull(repeated.credential());
        verify(drafts, times(1)).saveAndFlush(any());
    }

    @Test
    void otherGuestAndRandomIdCannotReadAndExpiredProofIsDenied() {
        var createdA = service.create(basics, null, "127.0.0.1");
        var createdB = service.create(basics, null, "127.0.0.2");
        PropertyUploadDraft draftA = draftFor(createdA);
        when(drafts.findByDraftId(draftA.getDraftId())).thenReturn(Optional.of(draftA));
        assertSame(draftA, service.require(draftA.getDraftId(), createdA.credential()));
        assertThrows(EntityNotFoundException.class, () -> service.require(draftA.getDraftId(), createdB.credential()));
        assertThrows(EntityNotFoundException.class, () -> service.require("guest-random", createdA.credential()));
        draftA.setGuestExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThrows(EntityNotFoundException.class, () -> service.require(draftA.getDraftId(), createdA.credential()));
    }

    @Test
    void claimTransfersOnceAndOldGuestProofCannotReadOrReclaimForAnotherOwner() {
        var created = service.create(basics, null, "127.0.0.1");
        PropertyUploadDraft draft = draftFor(created);
        when(drafts.findByDraftIdForUpdate(draft.getDraftId())).thenReturn(Optional.of(draft));
        when(drafts.findByDraftId(draft.getDraftId())).thenReturn(Optional.of(draft));
        when(capabilities.requireLandlordUserId("owner@example.com")).thenReturn(7L);
        when(capabilities.requireLandlordUserId("other@example.com")).thenReturn(8L);

        assertEquals(draft.getDraftId(), service.claim(draft.getDraftId(), created.credential(), "owner@example.com").draftId());
        assertEquals(7L, draft.getLandlordUserId());
        assertNull(draft.getGuestTokenHash());
        assertNull(draft.getGuestExpiresAt());
        assertThrows(EntityNotFoundException.class, () -> service.require(draft.getDraftId(), created.credential()));
        assertEquals(draft.getDraftId(), service.claim(draft.getDraftId(), null, "owner@example.com").draftId());
        assertThrows(EntityNotFoundException.class, () -> service.claim(draft.getDraftId(), created.credential(), "other@example.com"));
        verify(capabilities, times(1)).activate("owner@example.com");
    }

    @Test
    void invalidProofNeverActivatesLandlordCapability() {
        var created = service.create(basics, null, "127.0.0.1");
        PropertyUploadDraft draft = draftFor(created);
        when(drafts.findByDraftIdForUpdate(draft.getDraftId())).thenReturn(Optional.of(draft));
        assertThrows(EntityNotFoundException.class, () -> service.claim(draft.getDraftId(), "x".repeat(43), "other@example.com"));
        verifyNoInteractions(capabilities);
    }

    @Test
    void expiredCleanupRetainsMetadataWhenStorageDeletionCannotBeVerified() {
        var created = service.create(basics, null, "127.0.0.1");
        PropertyUploadDraft draft = draftFor(created);
        draft.setId(15L);
        draft.setGuestExpiresAt(LocalDateTime.now().minusDays(1));
        PropertyDraftMedia photo = new PropertyDraftMedia();
        photo.setDraftId(draft.getDraftId());
        photo.setGuestOwned(true);
        photo.setStagingObjectKey("drafts/guest/15/photo");
        when(drafts.findByGuestTokenHashIsNotNullAndGuestExpiresAtBeforeAndIdGreaterThanOrderByIdAsc(
                any(), eq(0L), any())).thenReturn(List.of(draft));
        when(drafts.findByDraftIdForUpdate(draft.getDraftId())).thenReturn(Optional.of(draft));
        when(media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draft.getDraftId()))
                .thenReturn(List.of(photo));
        when(staging.existsStrict(photo.getStagingObjectKey()))
                .thenThrow(new IllegalStateException("Storage unavailable"));

        assertEquals(0, service.purgeExpired());
        verify(staging).delete(photo.getStagingObjectKey());
        verify(media, never()).deleteAll(any());
        verify(drafts, never()).delete(any());
    }

    private PropertyUploadDraft draftFor(GuestDraftService.Created created) {
        var captor = org.mockito.ArgumentCaptor.forClass(PropertyUploadDraft.class);
        verify(drafts, atLeastOnce()).saveAndFlush(captor.capture());
        return captor.getAllValues().stream().filter(row -> row.getDraftId().equals(created.draft().draftId()))
                .findFirst().orElseThrow();
    }
}
