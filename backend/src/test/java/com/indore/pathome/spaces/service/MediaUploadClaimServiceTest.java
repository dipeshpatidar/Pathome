package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.MediaUploadClaim;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.repository.MediaUploadClaimRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MediaUploadClaimServiceTest {

    private MediaUploadClaimRepository claimRepository;
    private PropertyMediaAssetRepository mediaAssetRepository;
    private ThreadPoolTaskScheduler taskScheduler;
    private MediaUploadClaimService claimService;

    @BeforeEach
    void setUp() {
        claimRepository = mock(MediaUploadClaimRepository.class);
        mediaAssetRepository = mock(PropertyMediaAssetRepository.class);
        taskScheduler = mock(ThreadPoolTaskScheduler.class);
        claimService = new MediaUploadClaimService(claimRepository, mediaAssetRepository, taskScheduler);
    }

    @Test
    void existingPropertyMediaAssetReturnsImmediatelyWithoutClaimOrCloudinary() {
        PropertyMediaAsset asset = new PropertyMediaAsset();
        asset.setId(101L);
        asset.setListingId(42L);
        asset.setUploadRequestId("media-42-dm-1");
        asset.setMediaUrl("https://cdn.example/photo.webp");

        when(mediaAssetRepository.findByListingIdAndUploadRequestId(42L, "media-42-dm-1"))
                .thenReturn(Optional.of(asset));

        MediaUploadClaimService.ClaimResult result = claimService.acquireOrWait(42L, "media-42-dm-1");

        assertTrue(result.isAlreadyComplete());
        assertEquals(asset, result.getExistingAsset());
        verify(claimRepository, never()).insertClaimIfAbsent(anyLong(), anyString(), anyString());
    }

    @Test
    void freshClaimNormalPathAcquiresSuccessfully() {
        when(mediaAssetRepository.findByListingIdAndUploadRequestId(42L, "media-42-dm-fresh"))
                .thenReturn(Optional.empty());
        when(claimRepository.insertClaimIfAbsent(eq(42L), eq("media-42-dm-fresh"), anyString()))
                .thenReturn(1);

        MediaUploadClaimService.ClaimResult result = claimService.acquireOrWait(42L, "media-42-dm-fresh");

        assertTrue(result.isAcquired());
        assertFalse(result.getClaimContext().isReclaimedFromExpired());
        assertFalse(result.getClaimContext().isReclaimedFromFailed());
        assertNotNull(result.getClaimContext().getOwnerToken());
        assertEquals("media-42-dm-fresh", result.getClaimContext().getUploadRequestId());
    }

    @Test
    void expiredClaimReclaimedByExactlyOneContender() {
        when(mediaAssetRepository.findByListingIdAndUploadRequestId(42L, "media-42-dm-exp"))
                .thenReturn(Optional.empty());
        // Insert fails (conflict)
        when(claimRepository.insertClaimIfAbsent(eq(42L), eq("media-42-dm-exp"), anyString()))
                .thenReturn(0);

        MediaUploadClaim expiredClaim = new MediaUploadClaim(42L, "media-42-dm-exp", "old-token",
                Instant.now().minusSeconds(10));
        when(claimRepository.findByListingIdAndUploadRequestId(42L, "media-42-dm-exp"))
                .thenReturn(Optional.of(expiredClaim));

        // First contender wins takeover
        when(claimRepository.atomicTakeover(eq(42L), eq("media-42-dm-exp"), anyString()))
                .thenReturn(1);

        MediaUploadClaimService.ClaimResult result = claimService.acquireOrWait(42L, "media-42-dm-exp");

        assertTrue(result.isAcquired());
        assertTrue(result.getClaimContext().isReclaimedFromExpired());
        assertFalse(result.getClaimContext().isReclaimedFromFailed());
    }

    @Test
    void failedClaimReclaimedByRetry() {
        when(mediaAssetRepository.findByListingIdAndUploadRequestId(42L, "media-42-dm-failed"))
                .thenReturn(Optional.empty());
        when(claimRepository.insertClaimIfAbsent(eq(42L), eq("media-42-dm-failed"), anyString()))
                .thenReturn(0);

        MediaUploadClaim failedClaim = new MediaUploadClaim(42L, "media-42-dm-failed", "old-token",
                Instant.now().plusSeconds(20));
        failedClaim.setStatus(MediaUploadClaim.STATUS_FAILED);
        when(claimRepository.findByListingIdAndUploadRequestId(42L, "media-42-dm-failed"))
                .thenReturn(Optional.of(failedClaim));

        when(claimRepository.atomicTakeover(eq(42L), eq("media-42-dm-failed"), anyString()))
                .thenReturn(1);

        MediaUploadClaimService.ClaimResult result = claimService.acquireOrWait(42L, "media-42-dm-failed");

        assertTrue(result.isAcquired());
        assertFalse(result.getClaimContext().isReclaimedFromExpired());
        assertTrue(result.getClaimContext().isReclaimedFromFailed());
    }

    @Test
    void heartbeatRenewsLeaseAndCancelsOnLostOwnership() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(taskScheduler).scheduleAtFixedRate(taskCaptor.capture(), any(Duration.class));

        MediaUploadClaimService.ClaimContext context = new MediaUploadClaimService.ClaimContext(
                42L, "media-42-heartbeat", "owner-tok-1", false, false);

        claimService.startHeartbeat(context);
        assertNotNull(context.getHeartbeatFuture());

        // Simulate heartbeat execution when owner matches
        when(claimRepository.renewLease(42L, "media-42-heartbeat", "owner-tok-1")).thenReturn(1);
        taskCaptor.getValue().run();
        assertFalse(context.isOwnershipLost());

        // Simulate lost ownership (0 rows updated)
        when(claimRepository.renewLease(42L, "media-42-heartbeat", "owner-tok-1")).thenReturn(0);
        taskCaptor.getValue().run();
        assertTrue(context.isOwnershipLost());
    }

    @Test
    void wrongOwnerTokenCannotCompleteOrMarkFailed() {
        MediaUploadClaimService.ClaimContext context = new MediaUploadClaimService.ClaimContext(
                42L, "media-42-wrong", "token-A", false, false);
        context.markOwnershipLost();

        // markCompleted must return false when ownership was lost
        boolean completed = claimService.markCompleted(context);
        assertFalse(completed);
        verify(claimRepository, never()).markCompleted(anyLong(), anyString(), anyString());

        // markFailed must return false when ownership was lost
        boolean failed = claimService.markFailed(context, "some error");
        assertFalse(failed);
        verify(claimRepository, never()).markFailed(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void heartbeatCancelledAfterSuccessAndFailure() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));

        MediaUploadClaimService.ClaimContext context1 = new MediaUploadClaimService.ClaimContext(
                42L, "media-42-success", "token-success", false, false);
        claimService.startHeartbeat(context1);
        when(claimRepository.markCompleted(42L, "media-42-success", "token-success")).thenReturn(1);
        claimService.markCompleted(context1);
        verify(future, times(1)).cancel(false);

        ScheduledFuture<?> future2 = mock(ScheduledFuture.class);
        doReturn(future2).when(taskScheduler).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));

        MediaUploadClaimService.ClaimContext context2 = new MediaUploadClaimService.ClaimContext(
                42L, "media-42-fail", "token-fail", false, false);
        claimService.startHeartbeat(context2);
        when(claimRepository.markFailed(eq(42L), eq("media-42-fail"), eq("token-fail"), anyString())).thenReturn(1);
        claimService.markFailed(context2, "Upload failed");
        verify(future2, times(1)).cancel(false);
    }

    @Test
    void scheduledCleanupPrunesStaleClaims() {
        when(claimRepository.cleanupOldClaims()).thenReturn(5);
        claimService.scheduledCleanup();
        verify(claimRepository, times(1)).cleanupOldClaims();
    }
}
