package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DiscardedDraftCleanupServiceTest {
    @Test
    void transientCloudinaryFailureKeepsMediaForSuccessfulRetry() {
        PropertyUploadDraftRepository drafts = mock(PropertyUploadDraftRepository.class);
        PropertyDraftMediaRepository media = mock(PropertyDraftMediaRepository.class);
        PropertyMediaAssetRepository liveMedia = mock(PropertyMediaAssetRepository.class);
        CloudinaryService cloudinary = mock(CloudinaryService.class);
        MediaStagingService staging = mock(MediaStagingService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setStatus("DISCARDED");
        draft.setLandlordUserId(5L);
        PropertyDraftMedia row = new PropertyDraftMedia();
        row.setId(12L);
        row.setMediaId("media-1");
        row.setDraftId("draft-1");
        row.setLandlordUserId(5L);
        row.setCloudinaryPublicId("pathome/qa/photo");
        row.setCloudinaryUrl("https://res.cloudinary.com/example/photo.webp");
        row.setContentType("image/jpeg");
        when(drafts.findByDraftIdForUpdate("draft-1")).thenReturn(Optional.of(draft));
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("draft-1", 5L))
                .thenReturn(List.of(row));
        when(media.existsByCloudinaryPublicIdAndIdNot("pathome/qa/photo", 12L)).thenReturn(false);
        when(media.existsByCloudinaryUrlAndIdNot(row.getCloudinaryUrl(), 12L)).thenReturn(false);
        when(liveMedia.existsByCloudinaryPublicId("pathome/qa/photo")).thenReturn(false);
        doThrow(new IllegalStateException("temporary provider failure"))
                .doNothing().when(cloudinary).deleteResource("pathome/qa/photo", false);

        DiscardedDraftCleanupService cleanup = new DiscardedDraftCleanupService(
                drafts, media, liveMedia, cloudinary, staging, transactionManager);

        cleanup.cleanup("draft-1");
        verify(media, never()).delete(row);

        cleanup.cleanup("draft-1");
        verify(media).delete(row);
    }
}
