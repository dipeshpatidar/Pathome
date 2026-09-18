package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.entity.MediaUploadFailure;
import com.indore.pathome.spaces.entity.RentalDetails;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.indore.pathome.spaces.service.FailedUploadService;
import com.indore.pathome.spaces.service.MediaStagingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FailedUploadsControllerTest {

    private FailedUploadService failedUploadService;
    private CloudinaryService cloudinaryService;
    private PropertyMediaAssetRepository mediaAssetRepository;
    private ListingRepository listingRepository;
    private MediaStagingService mediaStagingService;
    private FailedUploadsController controller;

    @BeforeEach
    void setUp() {
        failedUploadService = mock(FailedUploadService.class);
        cloudinaryService = mock(CloudinaryService.class);
        mediaAssetRepository = mock(PropertyMediaAssetRepository.class);
        listingRepository = mock(ListingRepository.class);
        mediaStagingService = mock(MediaStagingService.class);
        controller = new FailedUploadsController(
                failedUploadService, cloudinaryService, mediaAssetRepository, listingRepository, mediaStagingService);
    }

    private void setId(MediaUploadFailure failure, Long id) {
        try {
            var idField = MediaUploadFailure.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(failure, id);
        } catch (Exception ignored) {}
    }

    @Test
    void listUnresolved_returnsOkWithFailures() {
        MediaUploadFailure failure = new MediaUploadFailure();
        when(failedUploadService.getUnresolved()).thenReturn(List.of(failure));

        ResponseEntity<List<MediaUploadFailure>> response = controller.listUnresolved();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void unresolvedCount_returnsCountInMap() {
        when(failedUploadService.countUnresolved()).thenReturn(3L);

        ResponseEntity<Map<String, Long>> response = controller.unresolvedCount();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(3L, response.getBody().get("count"));
    }

    @Test
    void dismiss_returns404WhenRecordNotFound() {
        when(failedUploadService.getById(99L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> response = controller.dismiss(99L);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void dismiss_callsDismissAndReturnsOk() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        when(failedUploadService.getById(1L)).thenReturn(Optional.of(failure));

        ResponseEntity<Map<String, String>> response = controller.dismiss(1L);
        verify(failedUploadService).dismiss(1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void retry_returns404WhenRecordNotFound() {
        when(failedUploadService.getById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.retry(99L, null);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void retry_returnsOkWhenAlreadyResolved() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("RESOLVED");
        when(failedUploadService.getById(1L)).thenReturn(Optional.of(failure));

        ResponseEntity<?> response = controller.retry(1L, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("already been resolved"));
    }

    @Test
    void retry_returns409WhenAlreadyRetrying() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        failure.setFailureStage("DB_PERSIST");
        failure.setStorageUrl("https://res.cloudinary.com/demo/image/upload/sample.webp");
        setId(failure, 1L);

        when(failedUploadService.getById(1L)).thenReturn(Optional.of(failure));
        when(failedUploadService.markRetrying(1L)).thenReturn(false);

        ResponseEntity<?> response = controller.retry(1L, null);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void retry_automatic_dbPersistReconciliation_succeedsWithoutCloudinaryCall() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        failure.setListingId(10L);
        failure.setUploadRequestId("media-db-reconcile-1");
        failure.setMediaType("IMAGE");
        failure.setRoomTag("BEDROOM");
        failure.setFailureStage("DB_PERSIST");
        failure.setStorageUrl("https://res.cloudinary.com/demo/image/upload/sample.webp");
        failure.setStoragePublicId("media-db-reconcile-1");
        setId(failure, 1L);

        when(failedUploadService.getById(1L)).thenReturn(Optional.of(failure));
        when(failedUploadService.markRetrying(1L)).thenReturn(true);

        RentalDetails listing = new RentalDetails();
        listing.setMediaGalleryUrls("");
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(mediaAssetRepository.findByListingIdAndUploadRequestId(10L, "media-db-reconcile-1"))
                .thenReturn(Optional.empty());

        // Automatic retry (file is null)
        ResponseEntity<?> response = controller.retry(1L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Verify NO upload call to Cloudinary was made
        verify(cloudinaryService, never()).uploadImage(any(MockMultipartFile.class), anyString());
        verify(cloudinaryService, never()).uploadStreamResult(any(), anyBoolean(), anyString());
        // Verify PropertyMediaAsset was saved and resolution recorded
        verify(mediaAssetRepository).save(argThat(a -> "ADMIN_RECONCILED".equals(a.getVerificationStatus())));
        verify(failedUploadService).recordResolution(1L, "https://res.cloudinary.com/demo/image/upload/sample.webp");
    }

    @Test
    void retry_automatic_stagedMediaRetry_succeedsWithoutFilePicker() throws Exception {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        failure.setListingId(20L);
        failure.setUploadRequestId("media-staged-retry-2");
        failure.setMediaType("IMAGE");
        failure.setRoomTag("LIVING_ROOM");
        failure.setFailureStage("CLOUDINARY_UPLOAD");
        failure.setStagingObjectKey("staging/req-1/img.webp");
        failure.setStagingExpiresAt(LocalDateTime.now().plusDays(5));
        setId(failure, 2L);

        when(failedUploadService.getById(2L)).thenReturn(Optional.of(failure));
        when(failedUploadService.markRetrying(2L)).thenReturn(true);
        when(mediaStagingService.exists("staging/req-1/img.webp")).thenReturn(true);
        when(mediaStagingService.retrieve("staging/req-1/img.webp"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        RentalDetails listing = new RentalDetails();
        when(listingRepository.findById(20L)).thenReturn(Optional.of(listing));

        when(cloudinaryService.uploadStreamResult(any(InputStream.class), eq(false), eq("media-staged-retry-2")))
                .thenReturn(new CloudinaryService.CloudinaryUploadResult("https://cdn.example/staged.webp", "media-staged-retry-2", "image"));

        // Automatic retry (file is null)
        ResponseEntity<?> response = controller.retry(2L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(cloudinaryService).uploadStreamResult(any(InputStream.class), eq(false), eq("media-staged-retry-2"));
        verify(failedUploadService).recordResolution(2L, "https://cdn.example/staged.webp");
    }

    @Test
    void retry_automatic_stagedMediaMissingOrExpired_returnsBadRequest() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        failure.setListingId(20L);
        failure.setFailureStage("CLOUDINARY_UPLOAD");
        failure.setStagingObjectKey("staging/req-1/missing.webp");
        failure.setStagingExpiresAt(LocalDateTime.now().plusDays(5));
        setId(failure, 3L);

        when(failedUploadService.getById(3L)).thenReturn(Optional.of(failure));
        // Object storage says object is gone!
        when(mediaStagingService.exists("staging/req-1/missing.webp")).thenReturn(false);

        // Automatic retry (file is null)
        ResponseEntity<?> response = controller.retry(3L, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("expired or is unavailable"));
        // Never lock or increment retry count
        verify(failedUploadService, never()).markRetrying(3L);
    }

    @Test
    void retry_automatic_nonRecoverableFailure_returnsBadRequest() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        failure.setFailureStage("VALIDATION"); // Validation failures cannot be retried automatically
        setId(failure, 4L);

        when(failedUploadService.getById(4L)).thenReturn(Optional.of(failure));

        ResponseEntity<?> response = controller.retry(4L, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Replace File"));
        verify(failedUploadService, never()).markRetrying(any());
    }

    @Test
    void retry_manualReplaceFile_succeedsWithNewFile() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        failure.setListingId(10L);
        failure.setUploadRequestId("media-10-replace");
        failure.setMediaType("IMAGE");
        failure.setRoomTag("LIVING_ROOM");
        setId(failure, 5L);

        when(failedUploadService.getById(5L)).thenReturn(Optional.of(failure));
        when(failedUploadService.markRetrying(5L)).thenReturn(true);

        RentalDetails listing = new RentalDetails();
        listing.setMediaGalleryUrls("https://cdn.example/old.webp");
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        MockMultipartFile file = new MockMultipartFile("file", "replacement.webp", "image/webp", new byte[]{1, 2, 3});
        when(cloudinaryService.uploadImage(eq(file), eq("media-10-replace")))
                .thenReturn("https://cdn.example/replacement.webp");
        when(mediaAssetRepository.findByListingIdAndUploadRequestId(10L, "media-10-replace"))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.retry(5L, file);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(mediaAssetRepository).save(argThat(a -> "ADMIN_REPLACE".equals(a.getVerificationStatus())));
        verify(failedUploadService).recordResolution(5L, "https://cdn.example/replacement.webp");
    }

    @Test
    void retry_automatic_stagedVideoRetry_succeedsWithoutFilePicker() throws Exception {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        failure.setListingId(25L);
        failure.setUploadRequestId("media-staged-video-retry");
        failure.setMediaType("VIDEO_WALKTHROUGH");
        failure.setRoomTag("GENERAL");
        failure.setFailureStage("CLOUDINARY_UPLOAD");
        failure.setStagingObjectKey("staging/req-vid/walkthrough.mp4");
        failure.setStagingExpiresAt(LocalDateTime.now().plusDays(7));
        setId(failure, 6L);

        when(failedUploadService.getById(6L)).thenReturn(Optional.of(failure));
        when(failedUploadService.markRetrying(6L)).thenReturn(true);
        when(mediaStagingService.exists("staging/req-vid/walkthrough.mp4")).thenReturn(true);
        when(mediaStagingService.retrieve("staging/req-vid/walkthrough.mp4"))
                .thenReturn(new ByteArrayInputStream(new byte[]{10, 20, 30}));

        RentalDetails listing = new RentalDetails();
        when(listingRepository.findById(25L)).thenReturn(Optional.of(listing));

        when(cloudinaryService.uploadStreamResult(any(InputStream.class), eq(true), eq("media-staged-video-retry")))
                .thenReturn(new CloudinaryService.CloudinaryUploadResult("https://cdn.example/walkthrough.mp4", "media-staged-video-retry", "video"));

        ResponseEntity<?> response = controller.retry(6L, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(cloudinaryService).uploadStreamResult(any(InputStream.class), eq(true), eq("media-staged-video-retry"));
        verify(failedUploadService).recordResolution(6L, "https://cdn.example/walkthrough.mp4");
    }

    @Test
    void retry_automatic_dbPersistReconciliation_isIdempotent() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        failure.setListingId(10L);
        failure.setUploadRequestId("media-db-reconcile-idempotent");
        failure.setMediaType("IMAGE");
        failure.setRoomTag("KITCHEN");
        failure.setFailureStage("DB_PERSIST");
        failure.setStorageUrl("https://res.cloudinary.com/demo/image/upload/kitchen.webp");
        setId(failure, 7L);

        when(failedUploadService.getById(7L)).thenReturn(Optional.of(failure));
        when(failedUploadService.markRetrying(7L)).thenReturn(true);

        RentalDetails listing = new RentalDetails();
        // Gallery already has the URL
        listing.setMediaGalleryUrls("https://res.cloudinary.com/demo/image/upload/kitchen.webp");
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        com.indore.pathome.spaces.entity.PropertyMediaAsset existingAsset = new com.indore.pathome.spaces.entity.PropertyMediaAsset();
        existingAsset.setListingId(10L);
        existingAsset.setUploadRequestId("media-db-reconcile-idempotent");
        when(mediaAssetRepository.findByListingIdAndUploadRequestId(10L, "media-db-reconcile-idempotent"))
                .thenReturn(Optional.of(existingAsset));

        // Attempt 1
        ResponseEntity<?> response1 = controller.retry(7L, null);
        assertEquals(HttpStatus.OK, response1.getStatusCode());

        // Attempt 2 (idempotent repeat)
        ResponseEntity<?> response2 = controller.retry(7L, null);
        assertEquals(HttpStatus.OK, response2.getStatusCode());

        // Ensure gallery is NOT duplicated with commas
        assertEquals("https://res.cloudinary.com/demo/image/upload/kitchen.webp", listing.getMediaGalleryUrls());
    }

    @Test
    void stagedRetry_cloudinarySucceedsButDbFails_transitionsToDbPersist_nextRetryReconcilesWithoutCloudinary() throws Exception {
        MediaUploadFailure failure = new MediaUploadFailure();
        setId(failure, 99L);
        failure.setStatus("FAILED");
        failure.setFailureStage("CLOUDINARY_UPLOAD");
        failure.setListingId(10L);
        failure.setUploadRequestId("req-retry-db-fail");
        failure.setMediaType("IMAGE");
        failure.setStagingObjectKey("staging/req-retry-db-fail/img.webp");
        failure.setStagingExpiresAt(LocalDateTime.now().plusHours(1));

        RentalDetails listing = new RentalDetails();
        listing.setId(10L);

        when(failedUploadService.getById(99L)).thenReturn(Optional.of(failure));
        when(failedUploadService.markRetrying(99L)).thenReturn(true);
        when(mediaStagingService.exists("staging/req-retry-db-fail/img.webp")).thenReturn(true);
        when(mediaStagingService.retrieve("staging/req-retry-db-fail/img.webp"))
                .thenReturn(new ByteArrayInputStream("test".getBytes()));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        String authoritativeUrl = "https://res.cloudinary.com/demo/image/upload/authoritative.webp";
        String authoritativePublicId = "authoritative_pub_id";
        when(cloudinaryService.uploadStreamResult(any(InputStream.class), eq(false), eq("req-retry-db-fail")))
                .thenReturn(new CloudinaryService.CloudinaryUploadResult(authoritativeUrl, authoritativePublicId, "image"));

        // Simulate DB failure on save during first retry
        when(mediaAssetRepository.save(any())).thenThrow(new RuntimeException("PostgreSQL connection failure"));

        // FIRST RETRY: Cloudinary succeeds, DB fails
        ResponseEntity<?> response1 = controller.retry(99L, null);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response1.getStatusCode());

        // Verify failure was transitioned to DB_PERSIST with authoritative Cloudinary metadata
        verify(failedUploadService).transitionToDbPersistFailure(
                eq(99L),
                eq(authoritativeUrl),
                eq(authoritativePublicId),
                anyString(),
                contains("PostgreSQL connection failure")
        );
        // Staged object was NOT cleaned up prematurely
        verify(failedUploadService, never()).recordResolution(eq(99L), anyString());

        // Now update the failure object to simulate its persisted state after transition
        failure.setStatus("FAILED");
        failure.setFailureStage("DB_PERSIST");
        failure.setStorageUrl(authoritativeUrl);
        failure.setStoragePublicId(authoritativePublicId);

        // Reset mocks for second retry
        reset(cloudinaryService, mediaAssetRepository);
        // DB is back up now
        when(mediaAssetRepository.findByListingIdAndUploadRequestId(10L, "req-retry-db-fail"))
                .thenReturn(Optional.empty());

        // SECOND RETRY: DATABASE_RECONCILIATION
        assertEquals("DATABASE_RECONCILIATION", failure.getRecoveryStrategy());
        ResponseEntity<?> response2 = controller.retry(99L, null);
        assertEquals(HttpStatus.OK, response2.getStatusCode());

        // Verify Cloudinary was NOT called again!
        verifyNoInteractions(cloudinaryService);
        // Verify media asset was saved
        verify(mediaAssetRepository).save(any());
        // Verify resolution was recorded with authoritative URL and cleanup happened
        verify(failedUploadService).recordResolution(99L, authoritativeUrl);
    }

    @Test
    void controller_isAnnotatedWithPreAuthorizeAdminAndSubAdmin() {
        org.springframework.security.access.prepost.PreAuthorize annotation =
                FailedUploadsController.class.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertNotNull(annotation, "FailedUploadsController must be protected by @PreAuthorize");
        assertEquals("hasAnyRole('ADMIN', 'SUB_ADMIN')", annotation.value());
    }
}
