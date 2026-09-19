package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.MediaUploadClaim;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.repository.MediaUploadClaimRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MediaUploadClaimService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadClaimService.class);
    private static final int BOUNDED_WAIT_SECONDS = 15;
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 10;

    private final MediaUploadClaimRepository claimRepository;
    private final PropertyMediaAssetRepository mediaAssetRepository;
    private final ThreadPoolTaskScheduler taskScheduler;

    public MediaUploadClaimService(
            MediaUploadClaimRepository claimRepository,
            PropertyMediaAssetRepository mediaAssetRepository,
            @Qualifier("mediaClaimTaskScheduler") ThreadPoolTaskScheduler taskScheduler) {
        this.claimRepository = Objects.requireNonNull(claimRepository, "claimRepository must not be null");
        this.mediaAssetRepository = Objects.requireNonNull(mediaAssetRepository, "mediaAssetRepository must not be null");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler must not be null");
    }

    /**
     * Represents the context and ownership credentials for an acquired claim.
     */
    public static class ClaimContext {
        private final Long listingId;
        private final String uploadRequestId;
        private final String ownerToken;
        private final boolean reclaimedFromExpired;
        private final boolean reclaimedFromFailed;
        private final AtomicBoolean ownershipLost = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> heartbeatFuture;

        public ClaimContext(Long listingId, String uploadRequestId, String ownerToken,
                            boolean reclaimedFromExpired, boolean reclaimedFromFailed) {
            this.listingId = listingId;
            this.uploadRequestId = uploadRequestId;
            this.ownerToken = ownerToken;
            this.reclaimedFromExpired = reclaimedFromExpired;
            this.reclaimedFromFailed = reclaimedFromFailed;
        }

        public Long getListingId() { return listingId; }
        public String getUploadRequestId() { return uploadRequestId; }
        public String getOwnerToken() { return ownerToken; }
        public boolean isReclaimedFromExpired() { return reclaimedFromExpired; }
        public boolean isReclaimedFromFailed() { return reclaimedFromFailed; }
        public boolean isOwnershipLost() { return ownershipLost.get(); }
        public void markOwnershipLost() { ownershipLost.set(true); }
        public ScheduledFuture<?> getHeartbeatFuture() { return heartbeatFuture; }
        public void setHeartbeatFuture(ScheduledFuture<?> heartbeatFuture) { this.heartbeatFuture = heartbeatFuture; }
    }

    /**
     * Outcome of attempting claim acquisition or bounded wait.
     */
    public static class ClaimResult {
        public enum Outcome {
            ACQUIRED,
            ALREADY_COMPLETE,
            CONFLICT_IN_PROGRESS
        }

        private final Outcome outcome;
        private final ClaimContext claimContext;
        private final PropertyMediaAsset existingAsset;

        private ClaimResult(Outcome outcome, ClaimContext claimContext, PropertyMediaAsset existingAsset) {
            this.outcome = outcome;
            this.claimContext = claimContext;
            this.existingAsset = existingAsset;
        }

        public static ClaimResult acquired(ClaimContext context) {
            return new ClaimResult(Outcome.ACQUIRED, context, null);
        }

        public static ClaimResult alreadyComplete(PropertyMediaAsset existingAsset) {
            return new ClaimResult(Outcome.ALREADY_COMPLETE, null, existingAsset);
        }

        public static ClaimResult conflict() {
            return new ClaimResult(Outcome.CONFLICT_IN_PROGRESS, null, null);
        }

        public boolean isAcquired() { return outcome == Outcome.ACQUIRED; }
        public boolean isAlreadyComplete() { return outcome == Outcome.ALREADY_COMPLETE; }
        public boolean isConflict() { return outcome == Outcome.CONFLICT_IN_PROGRESS; }
        public ClaimContext getClaimContext() { return claimContext; }
        public PropertyMediaAsset getExistingAsset() { return existingAsset; }
    }

    /**
     * Atomically acquires ownership of (listingId, uploadRequestId) or waits up to 15s.
     */
    public ClaimResult acquireOrWait(Long listingId, String uploadRequestId) {
        Objects.requireNonNull(listingId, "listingId must not be null");
        Objects.requireNonNull(uploadRequestId, "uploadRequestId must not be null");

        // 1. Permanent authority check first: if already persisted, return immediately
        Optional<PropertyMediaAsset> existing = mediaAssetRepository
                .findByListingIdAndUploadRequestId(listingId, uploadRequestId);
        if (existing.isPresent()) {
            return ClaimResult.alreadyComplete(existing.get());
        }

        String ownerToken = UUID.randomUUID().toString();

        // 2. Try atomic insert: ON CONFLICT DO NOTHING
        int inserted = claimRepository.insertClaimIfAbsent(listingId, uploadRequestId, ownerToken);
        if (inserted == 1) {
            log.info("Claim acquired fresh for listingId={} uploadRequestId={} ownerToken={}",
                    listingId, uploadRequestId, ownerToken);
            return ClaimResult.acquired(new ClaimContext(listingId, uploadRequestId, ownerToken, false, false));
        }

        // 3. Conflict occurred: inspect existing claim
        Optional<MediaUploadClaim> claimOpt = claimRepository
                .findByListingIdAndUploadRequestId(listingId, uploadRequestId);
        if (claimOpt.isPresent()) {
            MediaUploadClaim claim = claimOpt.get();
            if (MediaUploadClaim.STATUS_COMPLETED.equals(claim.getStatus())) {
                Optional<PropertyMediaAsset> asset = mediaAssetRepository
                        .findByListingIdAndUploadRequestId(listingId, uploadRequestId);
                if (asset.isPresent()) {
                    return ClaimResult.alreadyComplete(asset.get());
                }
            }

            boolean isExpired = claim.getExpiresAt() != null && claim.getExpiresAt().isBefore(Instant.now());
            boolean isFailed = MediaUploadClaim.STATUS_FAILED.equals(claim.getStatus());

            if (isFailed || isExpired) {
                int taken = claimRepository.atomicTakeover(listingId, uploadRequestId, ownerToken);
                if (taken == 1) {
                    log.info("Claim reclaimed via takeover for listingId={} uploadRequestId={} ownerToken={} (wasExpired={}, wasFailed={})",
                            listingId, uploadRequestId, ownerToken, isExpired, isFailed);
                    return ClaimResult.acquired(new ClaimContext(listingId, uploadRequestId, ownerToken, isExpired, isFailed));
                }
            }
        }

        // 4. Bounded server wait: poll for up to 15s approximately once per second
        log.info("Concurrent claim in progress for listingId={} uploadRequestId={}. Entering bounded wait...",
                listingId, uploadRequestId);

        long startMs = System.currentTimeMillis();
        long maxDurationMs = BOUNDED_WAIT_SECONDS * 1000L;

        while (System.currentTimeMillis() - startMs < maxDurationMs) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Bounded wait interrupted for listingId={} uploadRequestId={}", listingId, uploadRequestId);
                return ClaimResult.conflict();
            }

            // Check if authoritative media asset has appeared
            Optional<PropertyMediaAsset> asset = mediaAssetRepository
                    .findByListingIdAndUploadRequestId(listingId, uploadRequestId);
            if (asset.isPresent()) {
                log.info("Bounded wait resolved: asset found in DB for listingId={} uploadRequestId={}",
                        listingId, uploadRequestId);
                return ClaimResult.alreadyComplete(asset.get());
            }

            // Check claim state in case it completed, failed, or expired during wait
            Optional<MediaUploadClaim> currentClaim = claimRepository
                    .findByListingIdAndUploadRequestId(listingId, uploadRequestId);
            if (currentClaim.isPresent()) {
                MediaUploadClaim c = currentClaim.get();
                if (MediaUploadClaim.STATUS_COMPLETED.equals(c.getStatus())) {
                    asset = mediaAssetRepository.findByListingIdAndUploadRequestId(listingId, uploadRequestId);
                    if (asset.isPresent()) {
                        return ClaimResult.alreadyComplete(asset.get());
                    }
                }

                boolean expiredNow = c.getExpiresAt() != null && c.getExpiresAt().isBefore(Instant.now());
                boolean failedNow = MediaUploadClaim.STATUS_FAILED.equals(c.getStatus());

                if (failedNow || expiredNow) {
                    int taken = claimRepository.atomicTakeover(listingId, uploadRequestId, ownerToken);
                    if (taken == 1) {
                        log.info("Claim reclaimed during bounded wait for listingId={} uploadRequestId={}",
                                listingId, uploadRequestId);
                        return ClaimResult.acquired(new ClaimContext(listingId, uploadRequestId, ownerToken, expiredNow, failedNow));
                    }
                }
            }
        }

        log.warn("Bounded wait timed out after {}s for listingId={} uploadRequestId={}",
                BOUNDED_WAIT_SECONDS, listingId, uploadRequestId);
        return ClaimResult.conflict();
    }

    /**
     * Starts periodic lease renewal on the shared thread pool.
     * Retains only lightweight primitives; never retains MultipartFile or byte arrays.
     */
    public void startHeartbeat(ClaimContext context) {
        if (context == null) return;
        Long listingId = context.getListingId();
        String uploadRequestId = context.getUploadRequestId();
        String ownerToken = context.getOwnerToken();

        ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                if (context.isOwnershipLost()) return;
                int updated = claimRepository.renewLease(listingId, uploadRequestId, ownerToken);
                if (updated == 0) {
                    context.markOwnershipLost();
                    log.warn("Ownership lost or lease expired during renewal for listingId={} uploadRequestId={}",
                            listingId, uploadRequestId);
                } else {
                    log.debug("Heartbeat extended lease for listingId={} uploadRequestId={}", listingId, uploadRequestId);
                }
            } catch (Exception e) {
                log.warn("Heartbeat execution error for listingId={} uploadRequestId={}: {}",
                        listingId, uploadRequestId, e.getMessage());
            }
        }, Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS));

        context.setHeartbeatFuture(future);
    }

    /**
     * Stops the heartbeat task.
     */
    public void stopHeartbeat(ClaimContext context) {
        if (context != null && context.getHeartbeatFuture() != null) {
            context.getHeartbeatFuture().cancel(false);
            context.setHeartbeatFuture(null);
        }
    }

    /**
     * Transitions owned claim to COMPLETED.
     */
    public boolean markCompleted(ClaimContext context) {
        if (context == null) return false;
        stopHeartbeat(context);
        if (context.isOwnershipLost()) {
            log.warn("Cannot mark claim COMPLETED: ownership already lost for listingId={} uploadRequestId={}",
                    context.getListingId(), context.getUploadRequestId());
            return false;
        }
        int rows = claimRepository.markCompleted(
                context.getListingId(), context.getUploadRequestId(), context.getOwnerToken());
        return rows > 0;
    }

    /**
     * Transitions owned claim to FAILED.
     */
    public boolean markFailed(ClaimContext context, String errorMessage) {
        if (context == null) return false;
        stopHeartbeat(context);
        if (context.isOwnershipLost()) {
            log.warn("Cannot mark claim FAILED: ownership already lost for listingId={} uploadRequestId={}",
                    context.getListingId(), context.getUploadRequestId());
            return false;
        }
        String sanitized = sanitizeErrorMessage(errorMessage);
        int rows = claimRepository.markFailed(
                context.getListingId(), context.getUploadRequestId(), context.getOwnerToken(), sanitized);
        return rows > 0;
    }

    /**
     * Nightly scheduled cleanup of old claims (> 7 days).
     */
    @Scheduled(cron = "${pathome.media-claims.cleanup-cron:0 0 3 * * ?}")
    public void scheduledCleanup() {
        try {
            int deleted = claimRepository.cleanupOldClaims();
            if (deleted > 0) {
                log.info("Pruned {} old media upload claims (> 7 days)", deleted);
            }
        } catch (Exception e) {
            log.warn("Failed to prune old media upload claims: {}", e.getMessage());
        }
    }

    private String sanitizeErrorMessage(String msg) {
        if (msg == null || msg.isBlank()) return "Upload failed";
        String trimmed = msg.trim();
        // Remove potential credentials or URLs with secrets
        String sanitized = trimmed.replaceAll("(?i)(api[_-]?key|secret|password|token)[=:][^&\\s]+", "$1=***");
        return sanitized.length() > 500 ? sanitized.substring(0, 500) : sanitized;
    }
}
