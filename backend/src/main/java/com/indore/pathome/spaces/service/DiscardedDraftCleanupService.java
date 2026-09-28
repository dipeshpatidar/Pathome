package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Durable discard tombstones retain media metadata until remote removal is confirmed. */
@Service
public class DiscardedDraftCleanupService {
    private static final Logger log = LoggerFactory.getLogger(DiscardedDraftCleanupService.class);
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;
    private final PropertyMediaAssetRepository liveMedia;
    private final CloudinaryService cloudinary;
    private final MediaStagingService staging;
    private final TransactionTemplate cleanupTransaction;
    private long cleanupCursor;

    public DiscardedDraftCleanupService(PropertyUploadDraftRepository drafts,
                                        PropertyDraftMediaRepository media,
                                        PropertyMediaAssetRepository liveMedia,
                                        CloudinaryService cloudinary,
                                        @Qualifier("draftMediaStagingService") MediaStagingService staging,
                                        PlatformTransactionManager transactionManager) {
        this.drafts = drafts;
        this.media = media;
        this.liveMedia = liveMedia;
        this.cloudinary = cloudinary;
        this.staging = staging;
        this.cleanupTransaction = new TransactionTemplate(transactionManager);
        this.cleanupTransaction.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
    }

    public void afterCommit(String draftId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Discard cleanup requires a transaction");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { cleanup(draftId); }
                catch (RuntimeException ex) { log.warn("Discard cleanup for {} will be retried: {}", draftId, ex.getMessage()); }
            }
        });
    }

    /** Each call uses a new transaction because afterCommit runs before the original transaction is unbound. */
    public void cleanup(String draftId) {
        cleanupTransaction.executeWithoutResult(ignored -> cleanupCommitted(draftId));
    }

    private void cleanupCommitted(String draftId) {
        PropertyUploadDraft draft = drafts.findByDraftIdForUpdate(draftId).orElse(null);
        if (draft == null || !"DISCARDED".equals(draft.getStatus())) return;
        List<PropertyDraftMedia> rows = draft.getLandlordUserId() == null
                ? media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draftId)
                : media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, draft.getLandlordUserId());
        for (PropertyDraftMedia row : rows) {
            try {
                if (removeOrPreserveShared(row)) media.delete(row);
            } catch (RuntimeException ex) {
                log.warn("Discard media cleanup for draft {} media {} will be retried: {}",
                        draftId, row.getMediaId(), ex.getMessage());
            }
        }
    }

    private boolean removeOrPreserveShared(PropertyDraftMedia row) {
        String stagingKey = row.getStagingObjectKey();
        if (stagingKey != null && row.getCloudinaryUrl() == null) {
            if (media.existsByStagingObjectKeyAndIdNot(stagingKey, row.getId())) return false;
            staging.delete(stagingKey);
            if (staging.existsStrict(stagingKey)) return false;
            return true;
        }
        if (Boolean.TRUE.equals(row.getReusedFromListing())) return true;
        String publicId = row.getCloudinaryPublicId();
        boolean video = row.getContentType() != null && row.getContentType().startsWith("video/");
        if (publicId == null || publicId.isBlank()) {
            if (row.getLandlordUserId() == null) return false;
            String requestId = "lessor-" + row.getLandlordUserId() + "-" + row.getMediaId().replace("-", "");
            publicId = cloudinary.findExistingResourceByUploadRequestId(requestId, video)
                    .map(CloudinaryService.CloudinaryUploadResult::publicId).orElse(null);
            // An in-flight upload can finish later. Keep its claim for the next reconciliation.
            if (publicId == null || publicId.isBlank()) return false;
        }
        String url = row.getCloudinaryUrl();
        if (media.existsByCloudinaryPublicIdAndIdNot(publicId, row.getId())
                || url != null && media.existsByCloudinaryUrlAndIdNot(url, row.getId())) return false;
        if (liveMedia.existsByCloudinaryPublicId(publicId)
                || url != null && liveMedia.existsByMediaUrl(url)) return true;
        cloudinary.deleteResource(publicId, video);
        return true;
    }

    @Scheduled(cron = "${pathome.guest.cleanup-cron:0 45 3 * * *}", zone = "Asia/Kolkata")
    public void retryDiscarded() {
        List<PropertyUploadDraft> batch = drafts.findDiscardedLessorAfterId(
                cleanupCursor, PageRequest.of(0, 50));
        if (batch.isEmpty()) { cleanupCursor = 0; return; }
        for (PropertyUploadDraft draft : batch) {
            cleanupCursor = draft.getId();
            try { cleanup(draft.getDraftId()); }
            catch (RuntimeException ex) { log.warn("Discard cleanup for {} will be retried: {}", draft.getDraftId(), ex.getMessage()); }
        }
    }
}
