package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.exception.DraftConflictException;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

@Service
public class GuestMediaStore {
    public record Claim(PropertyDraftMedia media, boolean alreadyStaged) {}
    private final GuestDraftService guests;
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;
    private final int maxFiles;
    private final long maxBytes;

    public GuestMediaStore(GuestDraftService guests, PropertyUploadDraftRepository drafts,
                           PropertyDraftMediaRepository media,
                           @Value("${pathome.guest.max-media-files:12}") int maxFiles,
                           @Value("${pathome.guest.max-media-bytes:262144000}") long maxBytes) {
        this.guests = guests; this.drafts = drafts; this.media = media;
        this.maxFiles = Math.max(1, Math.min(maxFiles, 30));
        this.maxBytes = Math.max(10_485_760L, Math.min(maxBytes, 524_288_000L));
    }

    @Transactional(readOnly = true)
    public List<PropertyDraftMedia> rows(String draftId, String proof) {
        guests.require(draftId, proof);
        return media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draftId);
    }

    @Transactional
    public Claim claim(String draftId, String proof, String mediaId, String filename,
                       String contentType, long size) {
        lock(draftId, proof);
        PropertyDraftMedia existing = media.findByMediaIdAndDraftIdAndGuestOwnedTrue(mediaId, draftId).orElse(null);
        if (existing != null) {
            if (existing.getFileSizeBytes() != size || !existing.getContentType().equals(contentType))
                throw new IllegalArgumentException("Retry must use the original media file");
            if ("STAGED".equals(existing.getUploadStatus())) return new Claim(existing, true);
            if ("PENDING".equals(existing.getUploadStatus()) &&
                    existing.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(2)))
                throw new DraftConflictException(draftId, 0, "This file is already uploading");
            if ("DELETING".equals(existing.getUploadStatus()))
                throw new DraftConflictException(draftId, 0, "Media is being removed");
            existing.setUploadStatus("PENDING"); existing.setUpdatedAt(LocalDateTime.now());
            return new Claim(media.saveAndFlush(existing), false);
        }
        List<PropertyDraftMedia> rows = media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draftId);
        if (rows.size() >= maxFiles || rows.stream().mapToLong(PropertyDraftMedia::getFileSizeBytes).sum() + size > maxBytes)
            throw new IllegalArgumentException("Guest media limit reached. Remove a file before adding another.");
        PropertyDraftMedia item = new PropertyDraftMedia();
        item.setMediaId(mediaId); item.setDraftId(draftId); item.setGuestOwned(true);
        item.setOriginalFilename(filename); item.setContentType(contentType); item.setFileSizeBytes(size);
        item.setUploadStatus("PENDING"); item.setUpdatedAt(LocalDateTime.now());
        item.setSortOrder(rows.stream().map(PropertyDraftMedia::getSortOrder).filter(v -> v != null)
                .max(Comparator.naturalOrder()).orElse(-1) + 1);
        item.setStagingObjectKey("drafts/guest/" + draftId + "/" + mediaId);
        return new Claim(media.saveAndFlush(item), false);
    }

    @Transactional
    public PropertyDraftMedia complete(String draftId, String proof, String mediaId) {
        lock(draftId, proof);
        PropertyDraftMedia row = requireMedia(draftId, mediaId);
        if ("STAGED".equals(row.getUploadStatus())) return row;
        if (!"PENDING".equals(row.getUploadStatus()) && !"FAILED".equals(row.getUploadStatus()))
            throw new DraftConflictException(draftId, 0, "Media cannot be completed");
        row.setUploadStatus("STAGED"); row.setUpdatedAt(LocalDateTime.now());
        // A scalar query reads current database state even when OpenEntityManagerInView
        // holds a stale PENDING media entity from this request's earlier upload claim.
        row.setIsCover(row.getContentType().startsWith("image/") && !media
                .existsByDraftIdAndGuestOwnedTrueAndIsCoverTrueAndUploadStatusAndMediaIdNot(
                        draftId, "STAGED", mediaId));
        return media.saveAndFlush(row);
    }

    @Transactional
    public void failed(String draftId, String proof, String mediaId) {
        lock(draftId, proof);
        PropertyDraftMedia row = requireMedia(draftId, mediaId);
        if ("PENDING".equals(row.getUploadStatus())) {
            row.setUploadStatus("FAILED"); row.setUpdatedAt(LocalDateTime.now()); media.save(row);
        }
    }

    @Transactional
    public void markMissing(String draftId, String proof, String mediaId) {
        lock(draftId, proof);
        PropertyDraftMedia row = requireMedia(draftId, mediaId);
        if ("STAGED".equals(row.getUploadStatus())) {
            row.setUploadStatus("FAILED"); row.setIsCover(false);
            row.setUpdatedAt(LocalDateTime.now()); media.saveAndFlush(row);
        }
    }

    @Transactional
    public List<PropertyDraftMedia> cover(String draftId, String proof, String mediaId) {
        lock(draftId, proof);
        List<PropertyDraftMedia> rows = media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draftId);
        PropertyDraftMedia selected = rows.stream().filter(row -> row.getMediaId().equals(mediaId))
                .findFirst().orElseThrow(() -> new EntityNotFoundException("Media unavailable"));
        if (!"STAGED".equals(selected.getUploadStatus()) || !selected.getContentType().startsWith("image/"))
            throw new IllegalArgumentException("Choose a staged photo for the cover");
        rows.forEach(row -> row.setIsCover(false));
        media.saveAllAndFlush(rows);
        selected.setIsCover(true);
        media.saveAndFlush(selected);
        return rows;
    }

    @Transactional
    public List<PropertyDraftMedia> reorder(String draftId, String proof, List<String> ids) {
        lock(draftId, proof);
        List<PropertyDraftMedia> rows = media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draftId);
        List<String> staged = rows.stream().filter(row -> "STAGED".equals(row.getUploadStatus()))
                .map(PropertyDraftMedia::getMediaId).toList();
        if (ids == null || ids.size() != staged.size() || new HashSet<>(ids).size() != ids.size()
                || !new HashSet<>(ids).equals(new HashSet<>(staged)))
            throw new IllegalArgumentException("Order must include every staged file exactly once");
        rows.forEach(row -> { int index = ids.indexOf(row.getMediaId()); if (index >= 0) row.setSortOrder(index); });
        return media.saveAll(rows).stream().sorted(Comparator.comparing(PropertyDraftMedia::getSortOrder)).toList();
    }

    @Transactional
    public PropertyDraftMedia markDeleting(String draftId, String proof, String mediaId) {
        lock(draftId, proof);
        PropertyDraftMedia row = requireMedia(draftId, mediaId);
        if ("PENDING".equals(row.getUploadStatus())) throw new DraftConflictException(draftId, 0, "Wait for upload to finish");
        row.setUploadStatus("DELETING"); return media.saveAndFlush(row);
    }

    @Transactional
    public void finishDeleting(String draftId, String proof, String mediaId) {
        lock(draftId, proof);
        PropertyDraftMedia row = requireMedia(draftId, mediaId);
        if (!"DELETING".equals(row.getUploadStatus())) throw new DraftConflictException(draftId, 0, "Media is not being removed");
        boolean cover = Boolean.TRUE.equals(row.getIsCover());
        media.delete(row); media.flush();
        if (cover) media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draftId).stream()
                .filter(other -> "STAGED".equals(other.getUploadStatus()) && other.getContentType().startsWith("image/"))
                .findFirst().ifPresent(next -> { next.setIsCover(true); media.save(next); });
    }

    private void lock(String draftId, String proof) {
        var draft = drafts.findByDraftIdForUpdate(draftId)
                .orElseThrow(() -> new EntityNotFoundException("Draft unavailable"));
        guests.require(draftId, proof);
        if (draft.getGuestTokenHash() == null) throw new EntityNotFoundException("Draft unavailable");
    }

    private PropertyDraftMedia requireMedia(String draftId, String mediaId) {
        return media.findByMediaIdAndDraftIdAndGuestOwnedTrue(mediaId, draftId)
                .orElseThrow(() -> new EntityNotFoundException("Media unavailable"));
    }
}
