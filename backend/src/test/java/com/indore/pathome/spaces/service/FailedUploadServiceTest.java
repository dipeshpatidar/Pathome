package com.indore.pathome.spaces.service;

import com.indore.pathome.spaces.entity.MediaUploadFailure;
import com.indore.pathome.spaces.exception.MediaUploadException;
import com.indore.pathome.spaces.repository.MediaUploadFailureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailedUploadServiceTest {

    private MediaUploadFailureRepository repository;
    private FailedUploadService service;

    @BeforeEach
    void setUp() {
        repository = mock(MediaUploadFailureRepository.class);
        service = new FailedUploadService(repository);
    }

    private FailedUploadService.UploadFailureContext makeContext(String uploadRequestId) {
        return new FailedUploadService.UploadFailureContext(
                42L, uploadRequestId, "IMAGE", "bedroom.webp", 1024L,
                "GENERAL", MediaUploadException.Stage.CLOUDINARY_UPLOAD,
                "Upload could not be completed.", "IOException: timeout"
        );
    }

    @Test
    void recordFailure_createsNewRowWhenNoExistingRecord() {
        FailedUploadService.UploadFailureContext ctx = makeContext("media-42-abc");
        when(repository.findByUploadRequestId("media-42-abc")).thenReturn(Optional.empty());

        MediaUploadFailure saved = new MediaUploadFailure();
        saved.setUploadRequestId("media-42-abc");
        when(repository.saveAndFlush(any())).thenReturn(saved);

        MediaUploadFailure result = service.recordFailure(ctx);
        verify(repository).saveAndFlush(any(MediaUploadFailure.class));
        assertNotNull(result);
    }

    @Test
    void recordFailure_incrementsRetryCountForExistingRecord() {
        FailedUploadService.UploadFailureContext ctx = makeContext("media-42-abc");

        MediaUploadFailure existing = new MediaUploadFailure();
        existing.setUploadRequestId("media-42-abc");
        existing.setRetryCount(1);
        // Simulate ID is set
        try {
            var field = MediaUploadFailure.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(existing, 7L);
        } catch (Exception ignored) {}

        when(repository.findByUploadRequestId("media-42-abc")).thenReturn(Optional.of(existing));
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenReturn(existing);

        service.recordFailure(ctx);
        verify(repository).saveAndFlush(argThat(f ->
                f != null && f.getRetryCount() == 2));
    }

    @Test
    void recordFailure_withNullUploadRequestId_createsRowWithoutIdempotencyCheck() {
        FailedUploadService.UploadFailureContext ctx = makeContext(null);
        MediaUploadFailure saved = new MediaUploadFailure();
        when(repository.saveAndFlush(any())).thenReturn(saved);

        service.recordFailure(ctx);
        verify(repository, never()).findByUploadRequestId(any());
        verify(repository).saveAndFlush(any());
    }

    @Test
    void markRetrying_returnsFalseWhenAlreadyRetrying() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("RETRYING");
        when(repository.findById(1L)).thenReturn(Optional.of(failure));

        boolean result = service.markRetrying(1L);
        assertFalse(result, "Should not allow retrying a record already in RETRYING state");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void markRetrying_returnsTrueAndTransitionsStatus() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        when(repository.findById(1L)).thenReturn(Optional.of(failure));
        when(repository.saveAndFlush(any())).thenReturn(failure);

        boolean result = service.markRetrying(1L);
        assertTrue(result);
        assertEquals("RETRYING", failure.getStatus());
    }

    @Test
    void recordResolution_setsResolvedStatusAndUrl() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("RETRYING");
        failure.setUploadRequestId("media-42-abc");
        when(repository.findById(5L)).thenReturn(Optional.of(failure));
        when(repository.saveAndFlush(any())).thenReturn(failure);

        service.recordResolution(5L, "https://cdn.example/img.webp");
        assertEquals("RESOLVED", failure.getStatus());
        assertEquals("https://cdn.example/img.webp", failure.getResolvedMediaUrl());
    }

    @Test
    void dismiss_setsStatusToDismissed() {
        MediaUploadFailure failure = new MediaUploadFailure();
        failure.setStatus("FAILED");
        when(repository.findById(3L)).thenReturn(Optional.of(failure));
        when(repository.saveAndFlush(any())).thenReturn(failure);

        service.dismiss(3L);
        assertEquals("DISMISSED", failure.getStatus());
    }

    @Test
    void getUnresolved_callsRepositoryWithUnresolvedStatuses() {
        when(repository.findByStatusInOrderByCreatedAtDesc(anyList(), any(Pageable.class)))
                .thenReturn(List.of());

        List<MediaUploadFailure> result = service.getUnresolved();
        verify(repository).findByStatusInOrderByCreatedAtDesc(
                eq(FailedUploadService.UNRESOLVED_STATUSES),
                any(PageRequest.class)
        );
        assertNotNull(result);
    }

    @Test
    void countUnresolved_delegatesToRepository() {
        when(repository.countByStatusIn(FailedUploadService.UNRESOLVED_STATUSES)).thenReturn(7L);
        assertEquals(7L, service.countUnresolved());
    }
}
