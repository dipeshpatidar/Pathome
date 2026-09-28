package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordMediaPromotionServiceTest {
    private LandlordCapabilityService capabilities;
    private PropertyUploadDraftRepository drafts;
    private PropertyDraftMediaRepository media;
    private LandlordMediaStore store;
    private MediaStagingService staging;
    private CloudinaryService cloudinary;
    private LandlordMediaPromotionService service;
    private PropertyUploadDraft draft;
    private PropertyDraftMedia photo;

    @BeforeEach
    void setUp() {
        capabilities = mock(LandlordCapabilityService.class);
        drafts = mock(PropertyUploadDraftRepository.class);
        media = mock(PropertyDraftMediaRepository.class);
        store = mock(LandlordMediaStore.class);
        staging = mock(MediaStagingService.class);
        cloudinary = mock(CloudinaryService.class);
        service = new LandlordMediaPromotionService(capabilities, drafts, media, store, staging, cloudinary);
        draft = new PropertyUploadDraft(); draft.setDraftId("guest-1"); draft.setStatus("DRAFT"); draft.setLandlordUserId(7L);
        photo = new PropertyDraftMedia(); photo.setDraftId("guest-1"); photo.setMediaId("photo-1");
        photo.setLandlordUserId(7L); photo.setContentType("image/png"); photo.setFileSizeBytes(32L);
        photo.setUploadStatus("STAGED"); photo.setStagingObjectKey("drafts/guest/guest-1/photo-1");
        when(capabilities.requireLandlordUserId("owner@example.com")).thenReturn(7L);
        when(drafts.findByDraftIdAndLandlordUserId("guest-1", 7L)).thenReturn(Optional.of(draft));
        when(media.findByMediaIdAndDraftIdAndLandlordUserId("photo-1", "guest-1", 7L))
                .thenReturn(Optional.of(photo));
    }

    @Test
    void stagedPhotoIsReadableOnlyThroughItsClaimedOwner() throws Exception {
        when(staging.retrieve(photo.getStagingObjectKey()))
                .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        var content = service.stagedContent("owner@example.com", "guest-1", "photo-1");
        assertArrayEquals(new byte[] {1, 2, 3}, content.stream().readAllBytes());
        assertEquals("image/png", content.type());
        when(capabilities.requireLandlordUserId("other@example.com")).thenReturn(8L);
        assertThrows(EntityNotFoundException.class,
                () -> service.stagedContent("other@example.com", "guest-1", "photo-1"));
        verify(staging, times(1)).retrieve(photo.getStagingObjectKey());
    }

    @Test
    void failedPromotionKeepsPrivateStagingReferenceForRetry() {
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("guest-1", 7L))
                .thenReturn(List.of(photo));
        when(cloudinary.findExistingResourceByUploadRequestId(anyString(), eq(false)))
                .thenThrow(new IllegalStateException("Provider unavailable"));
        assertThrows(IllegalStateException.class, () -> service.promote("owner@example.com", "guest-1"));
        verify(store, never()).complete(anyString(), anyString(), anyString(), any());
        verify(media, never()).clearPromotedStagingKey(anyString(), anyLong());
        assertEquals("STAGED", photo.getUploadStatus());
        assertNotNull(photo.getStagingObjectKey());
    }
}
