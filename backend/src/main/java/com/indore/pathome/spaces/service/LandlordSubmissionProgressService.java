package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordSubmissionProgress;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observes the existing synchronous submission pipeline without becoming a source of submission truth.
 * Records are process-local, owner-scoped, bounded, and expire; draft/listing persistence remains authoritative.
 */
@Service
public class LandlordSubmissionProgressService {
    private static final int MAX_RECORDS = 1_000;
    private static final Duration ACTIVE_TTL = Duration.ofHours(2);
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(30);

    private final LandlordCapabilityService capabilities;
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;
    private final ConcurrentHashMap<ProgressKey, ProgressEntry> records = new ConcurrentHashMap<>();

    public LandlordSubmissionProgressService(LandlordCapabilityService capabilities,
            PropertyUploadDraftRepository drafts, PropertyDraftMediaRepository media) {
        this.capabilities = capabilities;
        this.drafts = drafts;
        this.media = media;
    }

    public LandlordSubmissionProgress start(String authenticatedEmail, String draftId) {
        Long ownerId = capabilities.requireOnboardingUserId(authenticatedEmail);
        PropertyUploadDraft draft = requireOwnedDraft(draftId, ownerId);
        List<PropertyDraftMedia> rows = media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, ownerId);
        ProgressKey key = new ProgressKey(ownerId, draftId);
        Instant now = Instant.now();
        synchronized (records) {
            pruneExpired(now);
            if (!"DRAFT".equals(draft.getStatus())) {
                if ("REVIEW".equals(draft.getStatus()) || "SUBMITTED".equals(draft.getStatus())) {
                    return completed(draftId, rows);
                }
                throw new EntityNotFoundException("Submission unavailable");
            }
            ProgressEntry existing = records.get(key);
            if (existing != null && !existing.failed() && !existing.completed()) return view(draftId, existing);
            ensureCapacity();
            Set<String> uploaded = uploadedIds(rows);
            ProgressEntry initial = new ProgressEntry(ProgressStatus.PREPARING, 0, rows.size(), uploaded, now);
            records.put(key, initial);
            return view(draftId, initial);
        }
    }

    public LandlordSubmissionProgress get(String authenticatedEmail, String draftId) {
        Long ownerId = capabilities.requireOnboardingUserId(authenticatedEmail);
        PropertyUploadDraft draft = requireOwnedDraft(draftId, ownerId);
        ProgressKey key = new ProgressKey(ownerId, draftId);
        Instant now = Instant.now();
        ProgressEntry entry = records.get(key);
        if (entry != null && expired(entry, now)) {
            records.remove(key, entry);
            entry = null;
        }
        if (entry != null) return view(draftId, entry);
        if ("REVIEW".equals(draft.getStatus()) || "SUBMITTED".equals(draft.getStatus())) {
            return completed(draftId,
                    media.findByDraftIdAndLandlordUserIdOrderBySortOrderAscIdAsc(draftId, ownerId));
        }
        throw new EntityNotFoundException("Submission progress unavailable");
    }

    /** Called at the start of the existing media promotion loop. */
    public void mediaStarted(Long ownerId, String draftId, List<PropertyDraftMedia> rows) {
        updateActive(new ProgressKey(ownerId, draftId), current -> {
            Set<String> processed = new HashSet<>(current.processedMediaIds());
            processed.addAll(uploadedIds(rows));
            int total = Math.max(Math.max(current.totalMedia(), rows.size()), processed.size());
            return current.with(ProgressStatus.PROCESSING_MEDIA, mediaPercent(processed.size(), total), total, processed);
        });
    }

    /** Called only after the existing media row has been committed as UPLOADED. */
    public void mediaProcessed(Long ownerId, String draftId, String mediaId) {
        updateActive(new ProgressKey(ownerId, draftId), current -> {
            Set<String> processed = new HashSet<>(current.processedMediaIds());
            processed.add(mediaId);
            return current.with(ProgressStatus.PROCESSING_MEDIA,
                    mediaPercent(processed.size(), current.totalMedia()), current.totalMedia(), processed);
        });
    }

    /** Called immediately before the existing authenticated submit operation starts. */
    public Long beginSaving(String authenticatedEmail, String draftId) {
        Long ownerId = capabilities.requireOnboardingUserId(authenticatedEmail);
        requireOwnedDraft(draftId, ownerId);
        updateActive(new ProgressKey(ownerId, draftId), current ->
                current.with(ProgressStatus.SAVING_PROPERTY, current.percent(), current.totalMedia(), current.processedMediaIds()));
        return ownerId;
    }

    /** Called only after the transactional submission service has returned and committed. */
    public void complete(Long ownerId, String draftId) {
        updateActive(new ProgressKey(ownerId, draftId), current ->
                current.with(ProgressStatus.COMPLETED, 100, current.totalMedia(), current.processedMediaIds()));
    }

    public void fail(String authenticatedEmail, String draftId) {
        Long ownerId = capabilities.requireOnboardingUserId(authenticatedEmail);
        fail(ownerId, draftId);
    }

    public void fail(Long ownerId, String draftId) {
        updateActive(new ProgressKey(ownerId, draftId), current ->
                current.with(ProgressStatus.FAILED, current.percent(), current.totalMedia(), current.processedMediaIds()));
    }

    private PropertyUploadDraft requireOwnedDraft(String draftId, Long ownerId) {
        return drafts.findByDraftIdAndLandlordUserId(draftId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Submission unavailable"));
    }

    private void updateActive(ProgressKey key, java.util.function.UnaryOperator<ProgressEntry> operation) {
        records.computeIfPresent(key, (ignored, current) -> {
            if (current.completed() || current.failed() || expired(current, Instant.now())) return current;
            return operation.apply(current).withUpdatedAt(Instant.now());
        });
    }

    private void pruneExpired(Instant now) {
        records.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    }

    private void ensureCapacity() {
        if (records.size() < MAX_RECORDS) return;
        records.entrySet().stream()
                .filter(entry -> entry.getValue().completed() || entry.getValue().failed())
                .min(java.util.Comparator.comparing(entry -> entry.getValue().updatedAt()))
                .ifPresent(entry -> records.remove(entry.getKey(), entry.getValue()));
        if (records.size() >= MAX_RECORDS) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Submission progress is temporarily unavailable");
        }
    }

    private boolean expired(ProgressEntry entry, Instant now) {
        Duration ttl = entry.completed() || entry.failed() ? TERMINAL_TTL : ACTIVE_TTL;
        return entry.updatedAt().plus(ttl).isBefore(now);
    }

    private static Set<String> uploadedIds(List<PropertyDraftMedia> rows) {
        return rows.stream().filter(row -> "UPLOADED".equals(row.getUploadStatus()))
                .map(PropertyDraftMedia::getMediaId).collect(java.util.stream.Collectors.toSet());
    }

    private static int mediaPercent(int processed, int total) {
        if (total <= 0) return 0;
        // Media events account for at most 90%; persistence stays at its actual completed-work value until commit.
        return (int) (90L * Math.min(processed, total) / total);
    }

    private static LandlordSubmissionProgress completed(String draftId, List<PropertyDraftMedia> rows) {
        return new LandlordSubmissionProgress(draftId, ProgressStatus.COMPLETED.name(), 100,
                uploadedIds(rows).size(), rows.size(), ProgressStatus.COMPLETED.name(), true, false);
    }

    private static LandlordSubmissionProgress view(String draftId, ProgressEntry entry) {
        boolean completed = entry.completed();
        boolean failed = entry.failed();
        return new LandlordSubmissionProgress(draftId, entry.status().name(), entry.percent(),
                entry.processedMediaIds().size(), entry.totalMedia(), entry.status().name(), completed, failed);
    }

    private enum ProgressStatus { PREPARING, PROCESSING_MEDIA, SAVING_PROPERTY, COMPLETED, FAILED }
    private record ProgressKey(Long ownerId, String draftId) {}
    private record ProgressEntry(ProgressStatus status, int percent, int totalMedia,
                                 Set<String> processedMediaIds, Instant updatedAt) {
        ProgressEntry {
            processedMediaIds = Set.copyOf(processedMediaIds);
        }
        boolean completed() { return status == ProgressStatus.COMPLETED; }
        boolean failed() { return status == ProgressStatus.FAILED; }
        ProgressEntry with(ProgressStatus nextStatus, int nextPercent, int nextTotal, Set<String> processed) {
            return new ProgressEntry(nextStatus, nextPercent, nextTotal, processed, updatedAt);
        }
        ProgressEntry withUpdatedAt(Instant now) {
            return new ProgressEntry(status, percent, totalMedia, processedMediaIds, now);
        }
    }
}
