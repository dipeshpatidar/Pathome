package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordMediaItem;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/** Promotes claimed private media through the existing landlord media rows. */
@Service
public class LandlordMediaPromotionService {
    public record Content(InputStream stream, String type, long length) {}
    private final LandlordCapabilityService capabilities;
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;
    private final LandlordMediaStore store;
    private final MediaStagingService staging;
    private final CloudinaryService cloudinary;

    public LandlordMediaPromotionService(LandlordCapabilityService capabilities, PropertyUploadDraftRepository drafts,
            PropertyDraftMediaRepository media, LandlordMediaStore store,
            @Qualifier("draftMediaStagingService") MediaStagingService staging, CloudinaryService cloudinary) {
        this.capabilities = capabilities; this.drafts = drafts; this.media = media;
        this.store = store; this.staging = staging; this.cloudinary = cloudinary;
    }

    public List<LandlordMediaItem> promote(String email, String draftId) {
        Long owner = capabilities.requireOnboardingUserId(email);
        var draft = drafts.findByDraftIdAndLandlordUserId(draftId, owner)
                .orElseThrow(() -> new EntityNotFoundException("Draft unavailable"));
        if (!"DRAFT".equals(draft.getStatus()))
            throw new com.indore.pathome.spaces.exception.DraftConflictException(draftId, draft.getVersion(),
                    "Draft can no longer be edited");
        for (PropertyDraftMedia row : media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, owner)) {
            if (row.getStagingObjectKey() == null) continue;
            if ("UPLOADED".equals(row.getUploadStatus())) { cleanStaging(row, owner); continue; }
            if (!"STAGED".equals(row.getUploadStatus())) continue;
            boolean video = row.getContentType().startsWith("video/");
            String requestId = "lessor-" + owner + "-" + row.getMediaId().replace("-", "");
            var result = cloudinary.findExistingResourceByUploadRequestId(requestId, video);
            if (result.isEmpty()) {
                try (InputStream stream = staging.retrieve(row.getStagingObjectKey())) {
                    result = java.util.Optional.of(cloudinary.uploadStreamResult(stream, video, requestId));
                } catch (java.io.IOException ex) { throw new IllegalStateException("Temporary media could not be read", ex); }
            }
            store.complete(email, draftId, row.getMediaId(), result.orElseThrow());
            cleanStaging(row, owner);
        }
        return store.list(email, draftId);
    }

    public Content stagedContent(String email, String draftId, String mediaId) {
        Long owner = capabilities.requireOnboardingUserId(email);
        drafts.findByDraftIdAndLandlordUserId(draftId, owner)
                .orElseThrow(() -> new EntityNotFoundException("Draft unavailable"));
        PropertyDraftMedia row = media.findByMediaIdAndDraftIdAndLandlordUserId(mediaId, draftId, owner)
                .orElseThrow(() -> new EntityNotFoundException("Media unavailable"));
        if (!"STAGED".equals(row.getUploadStatus()) || row.getStagingObjectKey() == null)
            throw new EntityNotFoundException("Media unavailable");
        return new Content(staging.retrieve(row.getStagingObjectKey()), row.getContentType(), row.getFileSizeBytes());
    }

    protected void cleanStaging(PropertyDraftMedia row, Long owner) {
        // A failed storage delete leaves the key for a later idempotent retry.
        staging.delete(row.getStagingObjectKey());
        if (!staging.existsStrict(row.getStagingObjectKey())) media.clearPromotedStagingKey(row.getMediaId(), owner);
    }
}
