package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuestMediaServiceTest {
    private static final String MEDIA_ID = "00000000-0000-4000-8000-000000000001";

    @Test
    void missingStagedObjectCanBeReuploadedWithTheSameRequestId() throws Exception {
        GuestMediaStore store = mock(GuestMediaStore.class);
        LandlordMediaService validator = mock(LandlordMediaService.class);
        MediaStagingService staging = mock(MediaStagingService.class);
        MultipartFile file = mock(MultipartFile.class);
        PropertyDraftMedia row = row();
        when(file.getOriginalFilename()).thenReturn("photo.png");
        when(file.getSize()).thenReturn(20L);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[20]));
        when(validator.validateFile(file)).thenReturn("image/png");
        when(store.claim("guest-1", "proof", MEDIA_ID, "photo.png", "image/png", 20L))
                .thenReturn(new GuestMediaStore.Claim(row, true), new GuestMediaStore.Claim(row, false));
        when(staging.existsStrict(row.getStagingObjectKey())).thenReturn(false);
        when(store.complete("guest-1", "proof", MEDIA_ID)).thenReturn(row);

        var result = new GuestMediaService(store, validator, staging, 30)
                .upload("guest-1", "proof", MEDIA_ID, file, "127.0.0.1");
        assertEquals("STAGED", result.status());
        verify(store).markMissing("guest-1", "proof", MEDIA_ID);
        verify(staging).stage(eq(row.getStagingObjectKey()), any(), eq(20L), eq("image/png"));
        verify(store).complete("guest-1", "proof", MEDIA_ID);
    }

    @Test
    void unknownStorageStateDoesNotDiscardAStagedObject() {
        GuestMediaStore store = mock(GuestMediaStore.class);
        LandlordMediaService validator = mock(LandlordMediaService.class);
        MediaStagingService staging = mock(MediaStagingService.class);
        MultipartFile file = mock(MultipartFile.class);
        PropertyDraftMedia row = row();
        when(file.getOriginalFilename()).thenReturn("photo.png");
        when(file.getSize()).thenReturn(20L);
        when(validator.validateFile(file)).thenReturn("image/png");
        when(store.claim("guest-1", "proof", MEDIA_ID, "photo.png", "image/png", 20L))
                .thenReturn(new GuestMediaStore.Claim(row, true));
        when(staging.existsStrict(row.getStagingObjectKey()))
                .thenThrow(new IllegalStateException("Storage unavailable"));

        assertThrows(IllegalStateException.class, () -> new GuestMediaService(store, validator, staging, 30)
                .upload("guest-1", "proof", MEDIA_ID, file, "127.0.0.1"));
        verify(store, never()).markMissing(anyString(), anyString(), anyString());
    }

    private PropertyDraftMedia row() {
        PropertyDraftMedia row = new PropertyDraftMedia();
        row.setDraftId("guest-1"); row.setMediaId(MEDIA_ID); row.setContentType("image/png");
        row.setOriginalFilename("photo.png"); row.setUploadStatus("STAGED");
        row.setStagingObjectKey("drafts/guest/guest-1/" + MEDIA_ID);
        return row;
    }
}
