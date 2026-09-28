package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.dto.lessor.LandlordDraftData;
import com.indore.pathome.spaces.dto.lessor.LandlordDraftResponse;
import com.indore.pathome.spaces.entity.PropertyDraftMedia;
import com.indore.pathome.spaces.entity.PropertyUploadDraft;
import com.indore.pathome.spaces.repository.PropertyDraftMediaRepository;
import com.indore.pathome.spaces.repository.PropertyUploadDraftRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GuestDraftService {
    private static final Logger log = LoggerFactory.getLogger(GuestDraftService.class);
    public record Created(LandlordDraftResponse draft, String credential) {}
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PropertyUploadDraftRepository drafts;
    private final PropertyDraftMediaRepository media;
    private final LandlordDraftService landlordDrafts;
    private final LandlordCapabilityService capabilities;
    private final MediaStagingService staging;
    private final int retentionDays;
    private final int maxActiveDrafts;
    private final int maxCreatesPerHour;
    private final Map<String, Window> creationWindows = new LinkedHashMap<>();
    private long cleanupCursor;
    private record Window(long startedAt, int count) {}

    public GuestDraftService(PropertyUploadDraftRepository drafts, PropertyDraftMediaRepository media,
                             LandlordDraftService landlordDrafts, LandlordCapabilityService capabilities,
                             @Qualifier("draftMediaStagingService") MediaStagingService staging,
                             @Value("${pathome.guest.retention-days:15}") int retentionDays,
                             @Value("${pathome.guest.max-active-drafts-per-session:1}") int maxActiveDrafts,
                             @Value("${pathome.guest.max-drafts-per-ip-hour:5}") int maxCreatesPerHour) {
        this.drafts = drafts; this.media = media; this.landlordDrafts = landlordDrafts;
        this.capabilities = capabilities; this.staging = staging;
        this.retentionDays = Math.max(1, Math.min(retentionDays, 30));
        if (maxActiveDrafts < 0 || maxActiveDrafts > 1)
            throw new IllegalArgumentException("This guest cookie model supports at most one active draft per session");
        this.maxActiveDrafts = maxActiveDrafts;
        this.maxCreatesPerHour = Math.max(1, Math.min(maxCreatesPerHour, 100));
    }

    public int cookieAgeSeconds() { return retentionDays * 86_400; }

    @Transactional
    public Created create(LandlordDraftData.Basics basics, String existingCredential, String remoteAddress) {
        PropertyUploadDraft existing = findCurrent(existingCredential);
        if (existing != null) return new Created(landlordDrafts.toResponse(existing, landlordDrafts.readData(existing)), null);
        if (maxActiveDrafts == 0) throw new IllegalArgumentException("Guest draft creation is temporarily unavailable");
        throttleCreation(remoteAddress);
        LandlordDraftData.Basics checked = landlordDrafts.validateBasics(basics);
        byte[] secret = new byte[32]; RANDOM.nextBytes(secret);
        String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        PropertyUploadDraft draft = new PropertyUploadDraft();
        draft.setDraftId("guest-" + UUID.randomUUID());
        draft.setGuestTokenHash(hash(credential));
        draft.setGuestExpiresAt(LocalDateTime.now().plusDays(retentionDays));
        draft.setDraftType("SINGLE"); draft.setStatus("DRAFT"); draft.setItemCount(1);
        draft.setTitleSummary(checked.propertyType().name().replace('_', ' ') + " draft");
        LandlordDraftData data = new LandlordDraftData(checked, null, null, null);
        draft.setPayload(landlordDrafts.writeData(data));
        return new Created(landlordDrafts.toResponse(drafts.saveAndFlush(draft), data), credential);
    }

    @Transactional(readOnly = true)
    public LandlordDraftResponse resume(String credential) {
        PropertyUploadDraft draft = findCurrent(credential);
        return draft == null ? null : landlordDrafts.toResponse(draft, landlordDrafts.readData(draft));
    }

    @Transactional(readOnly = true)
    public PropertyUploadDraft require(String draftId, String credential) {
        PropertyUploadDraft draft = drafts.findByDraftId(draftId)
                .orElseThrow(() -> new EntityNotFoundException("Draft unavailable"));
        if (!authorized(draft, credential)) throw new EntityNotFoundException("Draft unavailable");
        return draft;
    }

    @Transactional(readOnly = true)
    public LandlordDraftResponse get(String draftId, String credential) {
        PropertyUploadDraft draft = require(draftId, credential);
        return landlordDrafts.toResponse(draft, landlordDrafts.readData(draft));
    }

    @Transactional
    public LandlordDraftResponse updateBasics(String id, String proof, int version, LandlordDraftData.Basics data) {
        return landlordDrafts.updateGuestBasics(require(id, proof), version, data);
    }
    @Transactional
    public LandlordDraftResponse updatePricing(String id, String proof, int version, LandlordDraftData.Pricing data) {
        return landlordDrafts.updateGuestPricing(require(id, proof), version, data);
    }
    @Transactional
    public LandlordDraftResponse updateLocation(String id, String proof, int version, LandlordDraftData.Location data) {
        return landlordDrafts.updateGuestLocation(require(id, proof), version, data);
    }
    @Transactional
    public LandlordDraftResponse updateDetails(String id, String proof, int version, LandlordDraftData.Details data) {
        return landlordDrafts.updateGuestDetails(require(id, proof), version, data);
    }

    @Transactional
    public LandlordDraftResponse claim(String draftId, String credential, String email) {
        // The row lock serializes competing claims. A repeat by the rightful owner is safe.
        PropertyUploadDraft draft = drafts.findByDraftIdForUpdate(draftId)
                .orElseThrow(() -> new EntityNotFoundException("Draft unavailable"));
        if (draft.getLandlordUserId() != null) {
            Long owner;
            try { owner = capabilities.requireLandlordUserId(email); }
            catch (org.springframework.security.access.AccessDeniedException ex) {
                throw new EntityNotFoundException("Draft unavailable");
            }
            if (!owner.equals(draft.getLandlordUserId())) throw new EntityNotFoundException("Draft unavailable");
            return landlordDrafts.toResponse(draft, landlordDrafts.readData(draft));
        }
        if (!authorized(draft, credential)) throw new EntityNotFoundException("Draft unavailable");
        if (media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draftId).stream()
                .anyMatch(item -> "PENDING".equals(item.getUploadStatus()) || "DELETING".equals(item.getUploadStatus())))
            throw new com.indore.pathome.spaces.exception.DraftConflictException(draftId, draft.getVersion(),
                    "Wait for media uploads or removals to finish before signing in");
        capabilities.activate(email);
        Long owner = capabilities.requireLandlordUserId(email);
        // Capability activation clears the persistence context; reacquire the locked row.
        draft = drafts.findByDraftIdForUpdate(draftId)
                .orElseThrow(() -> new EntityNotFoundException("Draft unavailable"));
        if (!authorized(draft, credential)) throw new EntityNotFoundException("Draft unavailable");
        for (PropertyDraftMedia item : media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draftId)) {
            item.setGuestOwned(false); item.setLandlordUserId(owner);
            media.save(item);
        }
        draft.setGuestTokenHash(null); draft.setGuestExpiresAt(null);
        draft.setLandlordUserId(owner);
        drafts.saveAndFlush(draft);
        return landlordDrafts.toResponse(draft, landlordDrafts.readData(draft));
    }

    @Scheduled(cron = "${pathome.guest.cleanup-cron:0 45 3 * * *}", zone = "Asia/Kolkata")
    @Transactional
    public int purgeExpired() {
        List<PropertyUploadDraft> expired = drafts.findByGuestTokenHashIsNotNullAndGuestExpiresAtBeforeAndIdGreaterThanOrderByIdAsc(
                LocalDateTime.now(), cleanupCursor, PageRequest.of(0, 50));
        if (expired.isEmpty()) { cleanupCursor = 0; return 0; }
        int count = 0;
        for (PropertyUploadDraft candidate : expired) {
            cleanupCursor = candidate.getId();
            PropertyUploadDraft draft = drafts.findByDraftIdForUpdate(candidate.getDraftId()).orElse(null);
            if (draft == null || draft.getGuestTokenHash() == null || draft.getLandlordUserId() != null
                    || !draft.getGuestExpiresAt().isBefore(LocalDateTime.now())) continue;
            List<PropertyDraftMedia> rows = media.findByDraftIdAndGuestOwnedTrueOrderBySortOrderAscIdAsc(draft.getDraftId());
            boolean storageRemoved = true;
            for (PropertyDraftMedia row : rows) if (row.getStagingObjectKey() != null) {
                try {
                    staging.delete(row.getStagingObjectKey());
                    if (staging.existsStrict(row.getStagingObjectKey())) storageRemoved = false;
                } catch (RuntimeException ex) {
                    log.warn("Could not verify cleanup for guest draft {}: {}", draft.getDraftId(), ex.getMessage());
                    storageRemoved = false;
                }
            }
            if (!storageRemoved) continue;
            media.deleteAll(rows); drafts.delete(draft); count++;
        }
        return count;
    }

    private PropertyUploadDraft findCurrent(String credential) {
        if (credential == null || credential.length() != 43) return null;
        return drafts.findByGuestTokenHashAndStatus(hash(credential), "DRAFT")
                .filter(draft -> authorized(draft, credential)).orElse(null);
    }

    private boolean authorized(PropertyUploadDraft draft, String credential) {
        if (credential == null || credential.length() != 43 || draft.getGuestTokenHash() == null
                || draft.getAdminId() != null || draft.getLandlordUserId() != null
                || !"DRAFT".equals(draft.getStatus()) || draft.getGuestExpiresAt() == null
                || !draft.getGuestExpiresAt().isAfter(LocalDateTime.now())) return false;
        return MessageDigest.isEqual(draft.getGuestTokenHash().getBytes(StandardCharsets.US_ASCII),
                hash(credential).getBytes(StandardCharsets.US_ASCII));
    }

    private static String hash(String credential) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(credential.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private synchronized void throttleCreation(String remoteAddress) {
        long now = System.currentTimeMillis();
        creationWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() > 3_600_000);
        String key = remoteAddress == null ? "unknown" : remoteAddress;
        Window current = creationWindows.get(key);
        if (current != null && current.count() >= maxCreatesPerHour) throw new IllegalArgumentException("Too many draft attempts. Try again later.");
        if (creationWindows.size() >= 10_000 && current == null) throw new IllegalArgumentException("Draft creation is temporarily busy.");
        creationWindows.put(key, new Window(current == null ? now : current.startedAt(), current == null ? 1 : current.count() + 1));
    }
}
