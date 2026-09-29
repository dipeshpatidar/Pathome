package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordMediaItem;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

@Service
public class LandlordMediaStore {
    public record Claim(PropertyDraftMedia media, boolean retry) {}

    private final LandlordCapabilityService capabilities;
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;

    public LandlordMediaStore(LandlordCapabilityService capabilities,
                              PropertyUploadDraftRepository drafts, PropertyDraftMediaRepository media) {
        this.capabilities = capabilities;
        this.drafts = drafts;
        this.media = media;
    }

    @Transactional(readOnly = true)
    public List<LandlordMediaItem> list(String email, String draftId) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        requireOwned(ownerId, draftId);
        return media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, ownerId)
                .stream().map(this::toItem).toList();
    }

    @Transactional
    public Claim claim(String email, String draftId, String mediaId, String filename,
                       String contentType, long size) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        lockEditable(ownerId, draftId);
        var current = media.findByMediaIdAndDraftIdAndLandlordUserId(mediaId, draftId, ownerId);
        if (current.isPresent()) {
            PropertyDraftMedia saved = current.get();
            if ("UPLOADED".equals(saved.getUploadStatus())) return new Claim(saved, false);
            if ("DELETING".equals(saved.getUploadStatus())) throw new DraftConflictException(draftId, 0, "Media is being removed");
            if ("PENDING".equals(saved.getUploadStatus()) && saved.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(2))) {
                throw new DraftConflictException(draftId, 0, "This file is already uploading. Please wait.");
            }
            if (saved.getFileSizeBytes() != size || !saved.getContentType().equals(contentType)) {
                throw new IllegalArgumentException("Retry must use the original media file");
            }
            saved.setUploadStatus("PENDING");
            saved.setUpdatedAt(LocalDateTime.now());
            return new Claim(media.saveAndFlush(saved), true);
        }
        int next = media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, ownerId).stream()
                .map(PropertyDraftMedia::getSortOrder).filter(order -> order != null)
                .max(Comparator.naturalOrder()).orElse(-1) + 1;
        PropertyDraftMedia item = new PropertyDraftMedia();
        item.setMediaId(mediaId);
        item.setDraftId(draftId);
        item.setLandlordUserId(ownerId);
        item.setOriginalFilename(filename);
        item.setContentType(contentType);
        item.setFileSizeBytes(size);
        item.setUploadStatus("PENDING");
        item.setSortOrder(next);
        item.setUpdatedAt(LocalDateTime.now());
        return new Claim(media.saveAndFlush(item), false);
    }

    @Transactional
    public LandlordMediaItem complete(String email, String draftId, String mediaId,
                                      CloudinaryService.CloudinaryUploadResult result) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        lockEditable(ownerId, draftId);
        PropertyDraftMedia item = ownedMedia(ownerId, draftId, mediaId);
        // The request may still have a PENDING entity from claim() in OpenEntityManagerInView.
        // Read the current cover flag as a scalar so another completed upload or cover choice wins.
        boolean currentCover = media.findLessorCoverFlag(draftId, ownerId, mediaId).orElse(false);
        item.setIsCover(currentCover);
        if ("UPLOADED".equals(item.getUploadStatus())) return toItem(item);
        if (!"PENDING".equals(item.getUploadStatus()) && !"FAILED".equals(item.getUploadStatus())
                && !"STAGED".equals(item.getUploadStatus())) {
            throw new DraftConflictException(draftId, 0, "Media cannot be completed");
        }
        item.setCloudinaryUrl(result.secureUrl());
        item.setCloudinaryPublicId(result.publicId());
        item.setUploadStatus("UPLOADED");
        item.setUpdatedAt(LocalDateTime.now());
        if (item.getContentType().startsWith("image/")) {
            if (!currentCover && !media
                    .existsByDraftIdAndLandlordUserIdAndIsCoverTrueAndUploadStatusInAndMediaIdNotAndContentTypeStartingWith(
                            draftId, ownerId, List.of("UPLOADED", "STAGED", "DELETING"), mediaId, "image/")) {
                item.setIsCover(true);
            }
        }
        LandlordMediaItem completed = toItem(media.saveAndFlush(item));
        if (completed.cover()) media.clearOtherLessorCovers(draftId, ownerId, mediaId);
        return completed;
    }

    @Transactional
    public void markFailed(String email, String draftId, String mediaId) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        lockEditable(ownerId, draftId);
        PropertyDraftMedia item = ownedMedia(ownerId, draftId, mediaId);
        if ("PENDING".equals(item.getUploadStatus())) {
            item.setUploadStatus("FAILED");
            item.setUpdatedAt(LocalDateTime.now());
            media.save(item);
        }
    }

    @Transactional(readOnly = true)
    public PropertyDraftMedia recoverable(String email, String draftId, String mediaId) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        requireOwned(ownerId, draftId);
        return ownedMedia(ownerId, draftId, mediaId);
    }

    @Transactional
    public List<LandlordMediaItem> makeCover(String email, String draftId, String mediaId) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        lockEditable(ownerId, draftId);
        List<PropertyDraftMedia> items = media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, ownerId);
        PropertyDraftMedia selected = items.stream().filter(item -> mediaId.equals(item.getMediaId()))
                .findFirst().orElseThrow(() -> new EntityNotFoundException("Media not found"));
        if (!("UPLOADED".equals(selected.getUploadStatus()) || "STAGED".equals(selected.getUploadStatus()))
                || !selected.getContentType().startsWith("image/")) {
            throw new IllegalArgumentException("Only a ready image can be the cover");
        }
        items.forEach(item -> item.setIsCover(item == selected));
        media.saveAll(items);
        return items.stream().map(this::toItem).toList();
    }

    @Transactional
    public LandlordMediaItem tag(String email, String draftId, String mediaId, RoomTag tag) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        lockEditable(ownerId, draftId);
        PropertyDraftMedia item = ownedMedia(ownerId, draftId, mediaId);
        if ("DELETING".equals(item.getUploadStatus()))
            throw new DraftConflictException(draftId, 0, "Media is being removed");
        item.setRoomTag(tag.name());
        return toItem(media.saveAndFlush(item));
    }

    @Transactional
    public List<LandlordMediaItem> reorder(String email, String draftId, List<String> ids) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        lockEditable(ownerId, draftId);
        List<PropertyDraftMedia> items = media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, ownerId);
        List<String> ready = items.stream().filter(item -> "UPLOADED".equals(item.getUploadStatus())
                        || "STAGED".equals(item.getUploadStatus()))
                .map(PropertyDraftMedia::getMediaId).toList();
        if (ids == null || ids.size() != ready.size() || new HashSet<>(ids).size() != ids.size()
                || !new HashSet<>(ids).equals(new HashSet<>(ready))) {
            throw new IllegalArgumentException("Order must include each ready item exactly once");
        }
        for (PropertyDraftMedia item : items) {
            int index = ids.indexOf(item.getMediaId());
            if (index >= 0) item.setSortOrder(index);
        }
        media.saveAll(items);
        return items.stream().sorted(Comparator.comparing(PropertyDraftMedia::getSortOrder))
                .map(this::toItem).toList();
    }

    @Transactional
    public PropertyDraftMedia markDeleting(String email, String draftId, String mediaId) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        lockEditable(ownerId, draftId);
        PropertyDraftMedia item = ownedMedia(ownerId, draftId, mediaId);
        if ("PENDING".equals(item.getUploadStatus())) throw new DraftConflictException(draftId, 0, "Wait for upload to finish");
        item.setUploadStatus("DELETING");
        item.setUpdatedAt(LocalDateTime.now());
        return media.saveAndFlush(item);
    }

    @Transactional
    public void finishDeleting(String email, String draftId, String mediaId) {
        Long ownerId = capabilities.requireOnboardingUserId(email);
        lockEditable(ownerId, draftId);
        PropertyDraftMedia item = ownedMedia(ownerId, draftId, mediaId);
        if (!"DELETING".equals(item.getUploadStatus())) throw new DraftConflictException(draftId, 0, "Media is not being removed");
        boolean wasCover = Boolean.TRUE.equals(item.getIsCover());
        media.delete(item);
        media.flush();
        if (wasCover) {
            List<PropertyDraftMedia> remaining = media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, ownerId);
            boolean anotherCoverExists = remaining.stream().anyMatch(other -> ("UPLOADED".equals(other.getUploadStatus())
                            || "STAGED".equals(other.getUploadStatus())) && Boolean.TRUE.equals(other.getIsCover())
                    && other.getContentType().startsWith("image/"));
            if (!anotherCoverExists) remaining.stream().filter(other -> ("UPLOADED".equals(other.getUploadStatus())
                            || "STAGED".equals(other.getUploadStatus())) &&
                    other.getContentType().startsWith("image/"))
                    .findFirst().ifPresent(next -> { next.setIsCover(true); media.save(next); });
        }
    }

    private PropertyUploadDraft lockEditable(Long ownerId, String draftId) {
        PropertyUploadDraft draft = drafts.findLandlordDraftForUpdate(draftId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Draft not found"));
        if (!"DRAFT".equals(draft.getStatus())) throw new DraftConflictException(draftId, draft.getVersion(), "Draft can no longer be edited");
        return draft;
    }

    private void requireOwned(Long ownerId, String draftId) {
        drafts.findByDraftIdAndLandlordUserId(draftId, ownerId)
                .filter(draft -> !"DISCARDED".equals(draft.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException("Draft not found"));
    }

    private PropertyDraftMedia ownedMedia(Long ownerId, String draftId, String mediaId) {
        return media.findByMediaIdAndDraftIdAndLandlordUserId(mediaId, draftId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Media not found"));
    }

    private LandlordMediaItem toItem(PropertyDraftMedia item) {
        String url = "STAGED".equals(item.getUploadStatus()) && item.getStagingObjectKey() != null
                ? "/api/v1/lessor/properties/drafts/" + item.getDraftId() + "/media/" + item.getMediaId() + "/content"
                : item.getCloudinaryUrl();
        return new LandlordMediaItem(item.getMediaId(), item.getOriginalFilename(), item.getContentType(),
                url, item.getUploadStatus(), Boolean.TRUE.equals(item.getIsCover()),
                item.getSortOrder() == null ? 0 : item.getSortOrder(), RoomTag.fromStored(item.getRoomTag()));
    }

    public List<PropertyDraftMedia> listRaw(Long ownerId, String draftId) {
        return media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, ownerId);
    }

}
