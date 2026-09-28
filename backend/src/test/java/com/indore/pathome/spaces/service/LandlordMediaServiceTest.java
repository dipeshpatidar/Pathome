package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LandlordMediaServiceTest {
    private final LandlordMediaStore store = mock(LandlordMediaStore.class);
    private final CloudinaryService cloudinary = mock(CloudinaryService.class);
    private final LandlordMediaService service = new LandlordMediaService(store, cloudinary);
    private static final String MEDIA_ID = "a17f2ae2-d23e-4dae-b2f1-1724ee83de85";

    @Test
    void retryReconcilesCloudinarySuccessWithoutReuploadingFile() {
        PropertyDraftMedia item = new PropertyDraftMedia();
        item.setMediaId(MEDIA_ID);
        item.setLandlordUserId(5L);
        item.setContentType("image/jpeg");
        item.setUploadStatus("FAILED");
        when(store.claim(anyString(), eq("d1"), eq(MEDIA_ID), anyString(), eq("image/jpeg"), anyLong()))
                .thenReturn(new LandlordMediaStore.Claim(item, true));
        var cloudResult = new CloudinaryService.CloudinaryUploadResult("https://example.com/a.jpg", "p", "image");
        when(cloudinary.findExistingResourceByUploadRequestId(anyString(), eq(false)))
                .thenReturn(Optional.of(cloudResult));
        when(store.complete("owner@example.com", "d1", MEDIA_ID, cloudResult))
                .thenReturn(new com.indore.pathome.spaces.dto.lessor.LandlordMediaItem(MEDIA_ID, "a.jpg", "image/jpeg",
                        cloudResult.secureUrl(), "UPLOADED", true, 0));
        var file = new MockMultipartFile("file", "a.jpg", "image/jpeg", jpegBytes());

        var saved = service.upload("owner@example.com", "d1", MEDIA_ID, file);
        assertEquals("UPLOADED", saved.status());
        verify(cloudinary, never()).uploadImageResult(any(), anyString());
    }

    @Test
    void rejectsMismatchedHeadersBeforeClaimOrCloudUpload() {
        var file = new MockMultipartFile("file", "fake.jpg", "image/jpeg", "not a jpeg header".getBytes());
        assertThrows(IllegalArgumentException.class,
                () -> service.upload("owner@example.com", "d1", MEDIA_ID, file));
        verifyNoInteractions(store, cloudinary);
    }

    @Test
    void removingFailedRecordAlsoChecksForCloudinarySuccessBeforeFinalizing() {
        PropertyDraftMedia item = new PropertyDraftMedia();
        item.setMediaId(MEDIA_ID);
        item.setLandlordUserId(5L);
        item.setContentType("image/jpeg");
        item.setUploadStatus("DELETING");
        when(store.markDeleting("owner@example.com", "d1", MEDIA_ID)).thenReturn(item);
        when(cloudinary.findExistingResourceByUploadRequestId(anyString(), eq(false)))
                .thenReturn(Optional.of(new CloudinaryService.CloudinaryUploadResult("https://example.com/a.jpg", "pathome/properties/images/a", "image")));

        service.delete("owner@example.com", "d1", MEDIA_ID);
        verify(cloudinary).deleteResource("pathome/properties/images/a", false);
        verify(store).finishDeleting("owner@example.com", "d1", MEDIA_ID);
    }

    @Test
    void removingInheritedRevisionPhotoDoesNotDeleteLiveCloudinaryResource() {
        PropertyDraftMedia item = new PropertyDraftMedia();
        item.setMediaId(MEDIA_ID);
        item.setReusedFromListing(true);
        item.setCloudinaryPublicId("live-photo");
        when(store.markDeleting("owner@example.com", "d1", MEDIA_ID)).thenReturn(item);
        service.delete("owner@example.com", "d1", MEDIA_ID);
        verifyNoInteractions(cloudinary);
        verify(store).finishDeleting("owner@example.com", "d1", MEDIA_ID);
    }

    @Test
    void stagedMediaKeepsItsRecordWhenStorageCannotConfirmDeletion() {
        MediaStagingService staging = mock(MediaStagingService.class);
        LandlordMediaService withStaging = new LandlordMediaService(store, cloudinary, staging);
        PropertyDraftMedia item = new PropertyDraftMedia();
        item.setMediaId(MEDIA_ID);
        item.setStagingObjectKey("drafts/guest/example/media");
        when(store.markDeleting("owner@example.com", "d1", MEDIA_ID)).thenReturn(item);
        when(staging.existsStrict(item.getStagingObjectKey())).thenThrow(new IllegalStateException("Storage unavailable"));

        assertThrows(IllegalStateException.class, () -> withStaging.delete("owner@example.com", "d1", MEDIA_ID));
        verify(staging).delete(item.getStagingObjectKey());
        verify(store, never()).finishDeleting(anyString(), anyString(), anyString());
        verifyNoInteractions(cloudinary);
    }

    private byte[] jpegBytes() {
        return new byte[] { (byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13 };
    }
}
