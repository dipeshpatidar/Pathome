package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.dto.AvailabilityStatus;
import com.indore.pathome.spaces.entity.Listing;
import com.indore.pathome.spaces.entity.ListingStatus;
import com.indore.pathome.spaces.entity.PropertyMediaAsset;
import com.indore.pathome.spaces.exception.MediaUploadException;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MediaUploadClaimConcurrencyTest {

    private ListingRepository listingRepository;
    private PropertyMediaAssetRepository mediaAssetRepository;
    private CloudinaryService cloudinaryService;
    private FailedUploadService failedUploadService;
    private MediaUploadClaimService claimService;
    private PropertyController controller;

    private Listing sampleListing;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        mediaAssetRepository = mock(PropertyMediaAssetRepository.class);
        cloudinaryService = mock(CloudinaryService.class);
        failedUploadService = mock(FailedUploadService.class);
        claimService = mock(MediaUploadClaimService.class);

        PropertyParserService parserService = mock(PropertyParserService.class);
        ParserLearningService learningService = mock(ParserLearningService.class);
        ParserLearningCaptureService captureService = mock(ParserLearningCaptureService.class);
        BatchPropertyPublishingService batchService = mock(BatchPropertyPublishingService.class);
        MediaStagingService stagingService = mock(MediaStagingService.class);

        controller = new PropertyController(
                listingRepository,
                mediaAssetRepository,
                cloudinaryService,
                failedUploadService,
                parserService,
                learningService,
                captureService,
                batchService,
                stagingService,
                mock(com.indore.pathome.spaces.repository.UserRepository.class),
                mock(com.indore.pathome.spaces.repository.PropertyVisitRequestRepository.class)
        );
        controller.setMediaUploadClaimService(claimService);

        sampleListing = new com.indore.pathome.spaces.entity.RentalDetails();
        sampleListing.setId(42L);
        sampleListing.setTitle("Super Corridor Luxury Villa");
        sampleListing.setStatus(ListingStatus.ACTIVE);

        when(listingRepository.findById(42L)).thenReturn(Optional.of(sampleListing));
        when(listingRepository.findById(43L)).thenReturn(Optional.of(sampleListing));
    }

    @Test
    void twoConcurrentIdenticalRequestsUploadToCloudinaryExactlyOnce() throws Exception {
        String uploadRequestId = "media-42-dm-concurrent-2";
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        PropertyMediaAsset persistedAsset = new PropertyMediaAsset();
        persistedAsset.setId(501L);
        persistedAsset.setListingId(42L);
        persistedAsset.setUploadRequestId(uploadRequestId);
        persistedAsset.setMediaUrl("https://cdn.example/villa.webp");

        AtomicInteger cloudinaryUploadCount = new AtomicInteger(0);

        // Request 1 acquires claim fresh
        MediaUploadClaimService.ClaimContext context1 = new MediaUploadClaimService.ClaimContext(
                42L, uploadRequestId, "tok-1", false, false);
        MediaUploadClaimService.ClaimResult result1 = MediaUploadClaimService.ClaimResult.acquired(context1);

        // Request 2 encounters bounded wait which resolves to already complete
        MediaUploadClaimService.ClaimResult result2 = MediaUploadClaimService.ClaimResult.alreadyComplete(persistedAsset);

        // First call to acquireOrWait returns acquired, second returns alreadyComplete
        when(claimService.acquireOrWait(42L, uploadRequestId))
                .thenReturn(result1)
                .thenReturn(result2);

        when(cloudinaryService.uploadImageResult(any(), eq(uploadRequestId)))
                .thenAnswer(inv -> {
                    cloudinaryUploadCount.incrementAndGet();
                    return new CloudinaryService.CloudinaryUploadResult("https://cdn.example/villa.webp", uploadRequestId, "image");
                });

        when(mediaAssetRepository.save(any(PropertyMediaAsset.class))).thenReturn(persistedAsset);

        // Execute Request 1
        ResponseEntity<?> response1 = controller.uploadTaggedMediaAsset(
                42L, file, "HALL", "IMAGE", "Living Hall", false, null, null, null, uploadRequestId);

        // Execute Request 2
        ResponseEntity<?> response2 = controller.uploadTaggedMediaAsset(
                42L, file, "HALL", "IMAGE", "Living Hall", false, null, null, null, uploadRequestId);

        // Assert Cloudinary called exactly once
        assertEquals(1, cloudinaryUploadCount.get(), "Cloudinary upload count must be exactly 1");

        // Both responses should return HTTP 200/201 with the asset
        assertTrue(response1.getStatusCode().is2xxSuccessful());
        assertTrue(response2.getStatusCode().is2xxSuccessful());
        PropertyMediaAsset resAsset1 = (PropertyMediaAsset) response1.getBody();
        PropertyMediaAsset resAsset2 = (PropertyMediaAsset) response2.getBody();
        assertEquals(persistedAsset.getMediaUrl(), resAsset1.getMediaUrl());
        assertEquals(persistedAsset.getMediaUrl(), resAsset2.getMediaUrl());

        // Gallery appended once
        verify(listingRepository, times(1)).appendMediaGalleryUrlAtomic(eq(42L), eq("https://cdn.example/villa.webp"));
    }

    @Test
    void threeConcurrentIdenticalRequestsUploadToCloudinaryExactlyOnce() throws Exception {
        String uploadRequestId = "media-42-dm-concurrent-3";
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        PropertyMediaAsset persistedAsset = new PropertyMediaAsset();
        persistedAsset.setId(502L);
        persistedAsset.setListingId(42L);
        persistedAsset.setUploadRequestId(uploadRequestId);
        persistedAsset.setMediaUrl("https://cdn.example/villa3.webp");

        AtomicInteger cloudinaryUploadCount = new AtomicInteger(0);

        MediaUploadClaimService.ClaimContext context1 = new MediaUploadClaimService.ClaimContext(
                42L, uploadRequestId, "tok-1", false, false);
        MediaUploadClaimService.ClaimResult result1 = MediaUploadClaimService.ClaimResult.acquired(context1);
        MediaUploadClaimService.ClaimResult result2 = MediaUploadClaimService.ClaimResult.alreadyComplete(persistedAsset);
        MediaUploadClaimService.ClaimResult result3 = MediaUploadClaimService.ClaimResult.alreadyComplete(persistedAsset);

        when(claimService.acquireOrWait(42L, uploadRequestId))
                .thenReturn(result1)
                .thenReturn(result2)
                .thenReturn(result3);

        when(cloudinaryService.uploadImageResult(any(), eq(uploadRequestId)))
                .thenAnswer(inv -> {
                    cloudinaryUploadCount.incrementAndGet();
                    return new CloudinaryService.CloudinaryUploadResult("https://cdn.example/villa3.webp", uploadRequestId, "image");
                });

        when(mediaAssetRepository.save(any(PropertyMediaAsset.class))).thenReturn(persistedAsset);

        ResponseEntity<?> resp1 = controller.uploadTaggedMediaAsset(42L, file, "HALL", "IMAGE", null, false, null, null, null, uploadRequestId);
        ResponseEntity<?> resp2 = controller.uploadTaggedMediaAsset(42L, file, "HALL", "IMAGE", null, false, null, null, null, uploadRequestId);
        ResponseEntity<?> resp3 = controller.uploadTaggedMediaAsset(42L, file, "HALL", "IMAGE", null, false, null, null, null, uploadRequestId);

        assertEquals(1, cloudinaryUploadCount.get(), "Cloudinary called exactly once across 3 concurrent identical requests");
        assertTrue(resp1.getStatusCode().is2xxSuccessful());
        assertTrue(resp2.getStatusCode().is2xxSuccessful());
        assertTrue(resp3.getStatusCode().is2xxSuccessful());

        verify(listingRepository, times(1)).appendMediaGalleryUrlAtomic(eq(42L), eq("https://cdn.example/villa3.webp"));
        verify(mediaAssetRepository, times(1)).save(any(PropertyMediaAsset.class));
    }

    @Test
    void differentUploadRequestIdsOnSameListingRemainIndependent() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});

        when(claimService.acquireOrWait(eq(42L), eq("media-42-dm-1")))
                .thenReturn(MediaUploadClaimService.ClaimResult.acquired(new MediaUploadClaimService.ClaimContext(42L, "media-42-dm-1", "t1", false, false)));
        when(claimService.acquireOrWait(eq(42L), eq("media-42-dm-2")))
                .thenReturn(MediaUploadClaimService.ClaimResult.acquired(new MediaUploadClaimService.ClaimContext(42L, "media-42-dm-2", "t2", false, false)));

        when(cloudinaryService.uploadImageResult(any(), eq("media-42-dm-1")))
                .thenReturn(new CloudinaryService.CloudinaryUploadResult("https://cdn.example/1.webp", "media-42-dm-1", "image"));
        when(cloudinaryService.uploadImageResult(any(), eq("media-42-dm-2")))
                .thenReturn(new CloudinaryService.CloudinaryUploadResult("https://cdn.example/2.webp", "media-42-dm-2", "image"));

        controller.uploadTaggedMediaAsset(42L, file, "HALL", "IMAGE", null, false, null, null, null, "media-42-dm-1");
        controller.uploadTaggedMediaAsset(42L, file, "KITCHEN", "IMAGE", null, false, null, null, null, "media-42-dm-2");

        verify(cloudinaryService, times(1)).uploadImageResult(any(), eq("media-42-dm-1"));
        verify(cloudinaryService, times(1)).uploadImageResult(any(), eq("media-42-dm-2"));
    }

    @Test
    void sameUploadRequestIdOnDifferentListingsRemainIndependent() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});

        when(claimService.acquireOrWait(eq(42L), eq("media-shared-id")))
                .thenReturn(MediaUploadClaimService.ClaimResult.acquired(new MediaUploadClaimService.ClaimContext(42L, "media-shared-id", "t42", false, false)));
        when(claimService.acquireOrWait(eq(43L), eq("media-shared-id")))
                .thenReturn(MediaUploadClaimService.ClaimResult.acquired(new MediaUploadClaimService.ClaimContext(43L, "media-shared-id", "t43", false, false)));

        when(cloudinaryService.uploadImageResult(any(), eq("media-shared-id")))
                .thenReturn(new CloudinaryService.CloudinaryUploadResult("https://cdn.example/shared.webp", "media-shared-id", "image"));

        controller.uploadTaggedMediaAsset(42L, file, "HALL", "IMAGE", null, false, null, null, null, "media-shared-id");
        controller.uploadTaggedMediaAsset(43L, file, "HALL", "IMAGE", null, false, null, null, null, "media-shared-id");

        verify(claimService, times(1)).acquireOrWait(42L, "media-shared-id");
        verify(claimService, times(1)).acquireOrWait(43L, "media-shared-id");
    }

    @Test
    void boundedWaitExpiryReturnsDeterministic409ConflictWithoutFailedUploadRecord() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});
        String uploadRequestId = "media-42-dm-timeout";

        when(claimService.acquireOrWait(42L, uploadRequestId))
                .thenReturn(MediaUploadClaimService.ClaimResult.conflict());

        ResponseEntity<?> response = controller.uploadTaggedMediaAsset(
                42L, file, "HALL", "IMAGE", null, false, null, null, null, uploadRequestId);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("UPLOAD_IN_PROGRESS", body.get("error"));
        assertEquals(409, body.get("status"));

        // Must NOT call Cloudinary
        verify(cloudinaryService, never()).uploadImageResult(any(), anyString());
        // Must NOT create a Failed Upload record for legitimate concurrent in-progress upload
        verify(failedUploadService, never()).recordFailure(any());
    }

    @Test
    void freshClaimNormalPathDoesNotCallCloudinaryAdminApiReconciliation() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});
        String uploadRequestId = "media-42-dm-normal";

        when(claimService.acquireOrWait(42L, uploadRequestId))
                .thenReturn(MediaUploadClaimService.ClaimResult.acquired(
                        new MediaUploadClaimService.ClaimContext(42L, uploadRequestId, "tok-fresh", false, false)));

        when(cloudinaryService.uploadImageResult(any(), eq(uploadRequestId)))
                .thenReturn(new CloudinaryService.CloudinaryUploadResult("https://cdn.example/fresh.webp", uploadRequestId, "image"));

        controller.uploadTaggedMediaAsset(42L, file, "HALL", "IMAGE", null, false, null, null, null, uploadRequestId);

        // Verification: Admin API reconciliation was NEVER called on normal path
        verify(cloudinaryService, never()).findExistingResourceByUploadRequestId(anyString(), anyBoolean());
        verify(cloudinaryService, times(1)).uploadImageResult(any(), eq(uploadRequestId));
    }

    @Test
    void expiredClaimRecoveryWhenCloudinaryAssetExistsReusesAssetWithoutReupload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});
        String uploadRequestId = "media-42-dm-expired-exists";

        // Reclaimed from expired lease (crash recovery candidate)
        when(claimService.acquireOrWait(42L, uploadRequestId))
                .thenReturn(MediaUploadClaimService.ClaimResult.acquired(
                        new MediaUploadClaimService.ClaimContext(42L, uploadRequestId, "tok-reclaimed", true, false)));

        // Cloudinary reconciliation returns existing asset
        when(cloudinaryService.findExistingResourceByUploadRequestId(uploadRequestId, false))
                .thenReturn(Optional.of(new CloudinaryService.CloudinaryUploadResult(
                        "https://cdn.example/reconciled.webp", "pathome/properties/images/" + uploadRequestId, "image")));

        ResponseEntity<?> response = controller.uploadTaggedMediaAsset(
                42L, file, "HALL", "IMAGE", null, false, null, null, null, uploadRequestId);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        // Must NOT perform file upload to Cloudinary
        verify(cloudinaryService, never()).uploadImageResult(any(), anyString());
        // Persisted to DB and gallery updated
        verify(mediaAssetRepository, times(1)).save(any(PropertyMediaAsset.class));
        verify(listingRepository, times(1)).appendMediaGalleryUrlAtomic(42L, "https://cdn.example/reconciled.webp");
    }

    @Test
    void expiredClaimRecoveryWhenCloudinaryNotFoundUploadsOnce() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});
        String uploadRequestId = "media-42-dm-expired-notfound";

        when(claimService.acquireOrWait(42L, uploadRequestId))
                .thenReturn(MediaUploadClaimService.ClaimResult.acquired(
                        new MediaUploadClaimService.ClaimContext(42L, uploadRequestId, "tok-reclaimed", true, false)));

        // Cloudinary definitively not found
        when(cloudinaryService.findExistingResourceByUploadRequestId(uploadRequestId, false))
                .thenReturn(Optional.empty());

        when(cloudinaryService.uploadImageResult(any(), eq(uploadRequestId)))
                .thenReturn(new CloudinaryService.CloudinaryUploadResult("https://cdn.example/uploaded.webp", uploadRequestId, "image"));

        ResponseEntity<?> response = controller.uploadTaggedMediaAsset(
                42L, file, "HALL", "IMAGE", null, false, null, null, null, uploadRequestId);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        // Uploaded once
        verify(cloudinaryService, times(1)).uploadImageResult(any(), eq(uploadRequestId));
    }

    @Test
    void expiredClaimReconciliationErrorDoesNotUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});
        String uploadRequestId = "media-42-dm-expired-error";

        when(claimService.acquireOrWait(42L, uploadRequestId))
                .thenReturn(MediaUploadClaimService.ClaimResult.acquired(
                        new MediaUploadClaimService.ClaimContext(42L, uploadRequestId, "tok-reclaimed", true, false)));

        // Reconciliation fails with rate limit or timeout
        when(cloudinaryService.findExistingResourceByUploadRequestId(uploadRequestId, false))
                .thenThrow(new MediaUploadException(
                        MediaUploadException.Stage.CLOUDINARY_UPLOAD,
                        "Storage service rate limited",
                        "RateLimited 429",
                        null,
                        429,
                        null
                ));

        assertThrows(MediaUploadException.class, () ->
                controller.uploadTaggedMediaAsset(42L, file, "HALL", "IMAGE", null, false, null, null, null, uploadRequestId));

        // Must NOT upload again on ambiguous error
        verify(cloudinaryService, never()).uploadImageResult(any(), anyString());
    }

    @Test
    void priorDbPersistFailureReusesStorageUrlWithoutCloudinaryCall() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[]{1});
        String uploadRequestId = "media-42-dm-dbpersist";

        when(claimService.acquireOrWait(42L, uploadRequestId))
                .thenReturn(MediaUploadClaimService.ClaimResult.acquired(
                        new MediaUploadClaimService.ClaimContext(42L, uploadRequestId, "tok-dbpersist", false, true)));

        com.indore.pathome.spaces.entity.MediaUploadFailure failure = new com.indore.pathome.spaces.entity.MediaUploadFailure();
        failure.setStorageUrl("https://cdn.example/prior-success.webp");
        failure.setStoragePublicId("pathome/properties/images/" + uploadRequestId);
        when(failedUploadService.findFailureByUploadRequestId(uploadRequestId)).thenReturn(Optional.of(failure));

        ResponseEntity<?> response = controller.uploadTaggedMediaAsset(
                42L, file, "HALL", "IMAGE", null, false, null, null, null, uploadRequestId);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        // Must NOT call Cloudinary uploader because storageUrl was already present
        verify(cloudinaryService, never()).uploadImageResult(any(), anyString());
        verify(listingRepository, times(1)).appendMediaGalleryUrlAtomic(42L, "https://cdn.example/prior-success.webp");
    }
}
