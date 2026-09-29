package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordMediaItem;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.RoomTag;
import com.indore.pathome.spaces.exception.DraftConflictException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class GuestMediaService {
    public record Content(InputStream stream, String type, long length) {}
    private record Window(long startedAt, int count) {}
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cntrl}]");
    private final GuestMediaStore store;
    private final LandlordMediaService validator;
    private final MediaStagingService staging;
    private final int maxAttempts;
    private final Map<String, Window> attempts = new LinkedHashMap<>();

    public GuestMediaService(GuestMediaStore store, LandlordMediaService validator,
                             @Qualifier("draftMediaStagingService") MediaStagingService staging,
                             @Value("${pathome.guest.max-upload-attempts-per-ip-hour:30}") int maxAttempts) {
        this.store = store; this.validator = validator; this.staging = staging;
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 200));
    }

    public List<PropertyDraftMedia> rows(String id, String proof) {
        return store.rows(id, proof).stream().filter(row -> !"STAGED".equals(row.getUploadStatus()) ||
                row.getStagingObjectKey() != null && staging.existsStrict(row.getStagingObjectKey())).toList();
    }
    public List<LandlordMediaItem> list(String id, String proof) {
        return store.rows(id, proof).stream().map(row ->
                "STAGED".equals(row.getUploadStatus()) && !staging.existsStrict(row.getStagingObjectKey())
                    ? new LandlordMediaItem(row.getMediaId(), row.getOriginalFilename(), row.getContentType(),
                        null, "FAILED", false, row.getSortOrder() == null ? 0 : row.getSortOrder(),
                        RoomTag.fromStored(row.getRoomTag())) : item(row)).toList();
    }

    public LandlordMediaItem upload(String id, String proof, String mediaId, MultipartFile file, String remoteAddress) {
        throttle(remoteAddress);
        String cleanId = uuid(mediaId);
        String mime = validator.validateFile(file);
        String filename = filename(file.getOriginalFilename());
        GuestMediaStore.Claim claim = store.claim(id, proof, cleanId, filename, mime, file.getSize());
        if (claim.alreadyStaged()) {
            if (staging.existsStrict(claim.media().getStagingObjectKey())) return item(claim.media());
            store.markMissing(id, proof, cleanId);
            claim = store.claim(id, proof, cleanId, filename, mime, file.getSize());
        }
        try (InputStream input = file.getInputStream()) {
            staging.stage(claim.media().getStagingObjectKey(), input, file.getSize(), mime);
            return item(store.complete(id, proof, cleanId));
        } catch (IOException ex) {
            store.failed(id, proof, cleanId);
            throw new IllegalStateException("Temporary media could not be read", ex);
        } catch (RuntimeException ex) {
            try { store.failed(id, proof, cleanId); } catch (RuntimeException failure) { ex.addSuppressed(failure); }
            throw ex;
        }
    }

    public LandlordMediaItem recover(String id, String proof, String mediaId) {
        String cleanId = uuid(mediaId);
        PropertyDraftMedia row = store.rows(id, proof).stream().filter(item -> item.getMediaId().equals(cleanId))
                .findFirst().orElseThrow(() -> new EntityNotFoundException("Media unavailable"));
        if ("STAGED".equals(row.getUploadStatus())) {
            if (staging.existsStrict(row.getStagingObjectKey())) return item(row);
            store.markMissing(id, proof, cleanId);
            throw new DraftConflictException(id, 0, "Select the original file to retry this upload");
        }
        if ("DELETING".equals(row.getUploadStatus())) throw new DraftConflictException(id, 0, "Media is being removed");
        if ("PENDING".equals(row.getUploadStatus()) &&
                row.getUpdatedAt().isAfter(java.time.LocalDateTime.now().minusMinutes(2)))
            throw new DraftConflictException(id, 0, "This file is still uploading");
        if (row.getStagingObjectKey() != null && staging.existsStrict(row.getStagingObjectKey()))
            return item(store.complete(id, proof, cleanId));
        store.failed(id, proof, cleanId);
        throw new DraftConflictException(id, 0, "Select the original file to retry this upload");
    }

    public Content content(String id, String proof, String mediaId) {
        PropertyDraftMedia row = store.rows(id, proof).stream().filter(item -> item.getMediaId().equals(uuid(mediaId)))
                .findFirst().orElseThrow(() -> new EntityNotFoundException("Media unavailable"));
        if (!"STAGED".equals(row.getUploadStatus())) throw new EntityNotFoundException("Media unavailable");
        return new Content(staging.retrieve(row.getStagingObjectKey()), row.getContentType(), row.getFileSizeBytes());
    }

    public List<LandlordMediaItem> cover(String id, String proof, String mediaId) {
        return items(store.cover(id, proof, uuid(mediaId)));
    }
    public LandlordMediaItem tag(String id, String proof, String mediaId, RoomTag tag) {
        if (tag == null) throw new IllegalArgumentException("Photo category is required");
        return item(store.tag(id, proof, uuid(mediaId), tag));
    }
    public List<LandlordMediaItem> reorder(String id, String proof, List<String> ids) {
        return items(store.reorder(id, proof, ids == null ? null : ids.stream().map(this::uuid).toList()));
    }
    public void delete(String id, String proof, String mediaId) {
        PropertyDraftMedia row = store.markDeleting(id, proof, uuid(mediaId));
        if (row.getStagingObjectKey() != null) staging.delete(row.getStagingObjectKey());
        if (row.getStagingObjectKey() != null && staging.existsStrict(row.getStagingObjectKey()))
            throw new DraftConflictException(id, 0, "Media removal is incomplete. Retry.");
        store.finishDeleting(id, proof, row.getMediaId());
    }

    private List<LandlordMediaItem> items(List<PropertyDraftMedia> rows) { return rows.stream().map(this::item).toList(); }
    private LandlordMediaItem item(PropertyDraftMedia row) {
        String url = "STAGED".equals(row.getUploadStatus())
                ? "/api/v1/lessor/guest/drafts/" + row.getDraftId() + "/media/" + row.getMediaId() + "/content" : null;
        return new LandlordMediaItem(row.getMediaId(), row.getOriginalFilename(), row.getContentType(),
                url, row.getUploadStatus(), Boolean.TRUE.equals(row.getIsCover()),
                row.getSortOrder() == null ? 0 : row.getSortOrder(), RoomTag.fromStored(row.getRoomTag()));
    }
    private String uuid(String raw) {
        try { String clean = UUID.fromString(raw).toString();
            if (!clean.equalsIgnoreCase(raw)) throw new IllegalArgumentException(); return clean;
        } catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid media request ID"); }
    }
    private String filename(String raw) {
        String name = raw == null ? "property-media" : raw.replace('\\', '/');
        name = CONTROLS.matcher(name.substring(name.lastIndexOf('/') + 1)).replaceAll("").trim();
        return name.isEmpty() ? "property-media" : name.substring(0, Math.min(255, name.length()));
    }
    private synchronized void throttle(String remoteAddress) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(entry -> now - entry.getValue().startedAt() > 3_600_000);
        String key = remoteAddress == null ? "unknown" : remoteAddress;
        Window current = attempts.get(key);
        if (current != null && current.count() >= maxAttempts) throw new IllegalArgumentException("Too many upload attempts. Try later.");
        if (attempts.size() >= 10_000 && current == null) throw new IllegalArgumentException("Uploads are temporarily busy.");
        attempts.put(key, new Window(current == null ? now : current.startedAt(), current == null ? 1 : current.count() + 1));
    }
}
