package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.entity.MediaUploadFailure;
import com.indore.pathome.spaces.repository.ListingRepository;
import com.indore.pathome.spaces.repository.PropertyMediaAssetRepository;
import com.indore.pathome.spaces.service.CloudinaryService;
import com.indore.pathome.spaces.service.FailedUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

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
    private FailedUploadsController controller;

    @BeforeEach
    void setUp() {
        failedUploadService = mock(FailedUploadService.class);
        cloudinaryService = mock(CloudinaryService.class);
        mediaAssetRepository = mock(PropertyMediaAssetRepository.class);
        listingRepository = mock(ListingRepository.class);
        controller = new FailedUploadsController(
                failedUploadService, cloudinaryService, mediaAssetRepository, listingRepository);
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
        when(failedUploadService.getById(1L)).thenReturn(Optional.of(failure));
        when(failedUploadService.markRetrying(1L)).thenReturn(false);

        ResponseEntity<?> response = controller.retry(1L,
                new MockMultipartFile("file", "img.webp", "image/webp", new byte[]{1, 2, 3}));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void retry_returnsBadRequestWhenNoFileAndNotDbPersistStage() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        failure.setFailureStage("CLOUDINARY_UPLOAD");
        when(failedUploadService.getById(1L)).thenReturn(Optional.of(failure));
        when(failedUploadService.markRetrying(1L)).thenReturn(true);

        ResponseEntity<?> response = controller.retry(1L, null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
