package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DiscardedDraftCleanupServiceTest {
    private PropertyUploadDraftRepository drafts;
    private PropertyDraftMediaRepository media;
    private PropertyMediaAssetRepository liveMedia;
    private CloudinaryService cloudinary;
    private MediaStagingService staging;
    private DiscardedDraftCleanupService service;
    private PropertyUploadDraft draft;

    @BeforeEach
    void setUp() {
        drafts = mock(PropertyUploadDraftRepository.class);
        media = mock(PropertyDraftMediaRepository.class);
        liveMedia = mock(PropertyMediaAssetRepository.class);
        cloudinary = mock(CloudinaryService.class);
        staging = mock(MediaStagingService.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        service = new DiscardedDraftCleanupService(drafts, media, liveMedia, cloudinary, staging, transactions);
        draft = new PropertyUploadDraft();
        draft.setDraftId("draft-one");
        draft.setLandlordUserId(5L);
        draft.setStatus("DISCARDED");
        when(drafts.findByDraftIdForUpdate("draft-one")).thenReturn(Optional.of(draft));
    }

    @Test
    void rollbackDoesNotDeleteRemoteMediaAndCommitStartsCleanup() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.afterCommit("draft-one");
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(staging, cloudinary);
            verify(drafts, never()).findByDraftIdForUpdate(any());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.afterCommit("draft-one");
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(drafts).findByDraftIdForUpdate("draft-one");
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test
    void failedRemoteCleanupRetainsRowForScheduledRetry() {
        PropertyDraftMedia row = ownerMedia("cloud-one");
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("draft-one", 5L)).thenReturn(List.of(row));
        doThrow(new IllegalStateException("storage down")).when(cloudinary).deleteResource("cloud-one", false);

        service.cleanup("draft-one");

        verify(media, never()).delete(row);
        verify(drafts, never()).delete(draft);
    }

    @Test
    void successfulCleanupRemovesRowAfterConfirmedRemoteDeletion() {
        PropertyDraftMedia row = ownerMedia("cloud-one");
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("draft-one", 5L)).thenReturn(List.of(row));

        service.cleanup("draft-one");

        var order = inOrder(cloudinary, media);
        order.verify(cloudinary).deleteResource("cloud-one", false);
        order.verify(media).delete(row);
    }

    @Test
    void publishedOrOtherDraftReferencesAreNeverDeleted() {
        PropertyDraftMedia live = ownerMedia("published-cloud");
        PropertyDraftMedia shared = ownerMedia("shared-cloud");
        shared.setId(12L);
        when(media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc("draft-one", 5L))
                .thenReturn(List.of(live, shared));
        when(liveMedia.existsByCloudinaryPublicId("published-cloud")).thenReturn(true);
        when(media.existsByCloudinaryPublicIdAndIdNot("shared-cloud", 12L)).thenReturn(true);

        service.cleanup("draft-one");

        verify(cloudinary, never()).deleteResource(any(), anyBoolean());
        verify(media).delete(live);
        verify(media, never()).delete(shared);
    }

    @Test
    void guestStagingIsVerifiedBeforeItsMetadataIsRemoved() {
        draft.setLandlordUserId(null);
        PropertyDraftMedia row = new PropertyDraftMedia();
        row.setId(19L);
        row.setDraftId("draft-one");
        row.setGuestOwned(true);
        row.setStagingObjectKey("guest/photo");
        when(media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc("draft-one")).thenReturn(List.of(row));
        when(staging.existsStrict("guest/photo")).thenReturn(true, false);

        service.cleanup("draft-one");
        verify(media, never()).delete(row);
        service.cleanup("draft-one");
        verify(media).delete(row);
    }

    private PropertyDraftMedia ownerMedia(String publicId) {
        PropertyDraftMedia row = new PropertyDraftMedia();
        row.setId(11L);
        row.setDraftId("draft-one");
        row.setLandlordUserId(5L);
        row.setMediaId("media-one");
        row.setCloudinaryPublicId(publicId);
        row.setCloudinaryUrl("https://example.test/" + publicId);
        row.setContentType("image/jpeg");
        return row;
    }
}
