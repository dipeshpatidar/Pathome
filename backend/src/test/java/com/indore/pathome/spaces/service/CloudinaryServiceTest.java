package com.indore.pathome.spaces.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.indore.pathome.spaces.exception.MediaUploadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudinaryServiceTest {

    private Cloudinary cloudinary;
    private Uploader uploader;
    private CloudinaryService service;

    @BeforeEach
    void setUp() {
        cloudinary = mock(Cloudinary.class);
        uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        service = new CloudinaryService(cloudinary);
    }

    @Test
    void rejectsImageAboveCloudinaryLimitBeforeNetworkCall() {
        MultipartFile oversizedImage = mock(MultipartFile.class);
        when(oversizedImage.isEmpty()).thenReturn(false);
        when(oversizedImage.getSize()).thenReturn(CloudinaryService.MAX_IMAGE_BYTES + 1L);

        MediaUploadException exception = assertThrows(
                MediaUploadException.class,
                () -> service.uploadImage(oversizedImage, "media-20-image"));

        assertEquals(MediaUploadException.Stage.VALIDATION, exception.getStage());
        assertTrue(exception.getSafeReason().contains("10 MB"));
        verify(cloudinary, times(0)).uploader();
    }

    @Test
    void rejectsVideoAboveCloudinaryLimitBeforeNetworkCall() {
        MultipartFile oversizedVideo = mock(MultipartFile.class);
        when(oversizedVideo.isEmpty()).thenReturn(false);
        when(oversizedVideo.getSize()).thenReturn(CloudinaryService.MAX_VIDEO_BYTES + 1L);

        MediaUploadException exception = assertThrows(
                MediaUploadException.class,
                () -> service.uploadVideo(oversizedVideo, "media-20-video"));

        assertEquals(MediaUploadException.Stage.VALIDATION, exception.getStage());
        assertTrue(exception.getSafeReason().contains("100 MB"));
        verify(cloudinary, times(0)).uploader();
    }

    @Test
    void rejectsMismatchedMediaType() {
        MockMultipartFile textFile = new MockMultipartFile(
                "file", "notes.txt", "text/plain", new byte[]{1});

        MediaUploadException exception = assertThrows(
                MediaUploadException.class,
                () -> service.uploadImage(textFile, "media-20-notes"));

        assertEquals(MediaUploadException.Stage.VALIDATION, exception.getStage());
        assertTrue(exception.getSafeReason().contains("not supported"));
    }

    @Test
    void uploadsImageWithStablePublicIdentifier() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "file", "living-room.webp", "image/webp", new byte[]{1, 2, 3});
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenReturn(Map.of("secure_url", "https://cdn.example/property.webp"));

        String url = service.uploadImage(image, "media-20-image");

        assertEquals("https://cdn.example/property.webp", url);
        verify(uploader).upload(any(byte[].class), anyMap());
    }

    @Test
    void videoUsesChunkedUpload() throws Exception {
        MockMultipartFile video = new MockMultipartFile(
                "file", "walkthrough.mp4", "video/mp4", new byte[]{1, 2, 3});
        when(uploader.uploadLarge(any(InputStream.class), anyMap(), anyInt()))
                .thenReturn(Map.of("secure_url", "https://cdn.example/walkthrough.mp4"));

        String url = service.uploadVideo(video, "media-20-video");

        assertEquals("https://cdn.example/walkthrough.mp4", url);
        verify(uploader).uploadLarge(any(InputStream.class), anyMap(), anyInt());
    }

    @Test
    void reportsInterruptedCloudinaryUploadAsMediaUploadException() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "file", "bedroom.webp", "image/webp", new byte[]{1});
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenThrow(new java.net.SocketTimeoutException("Read timed out"));

        MediaUploadException exception = assertThrows(
                MediaUploadException.class,
                () -> service.uploadImage(image, "media-20-bedroom"));

        assertEquals(MediaUploadException.Stage.CLOUDINARY_UPLOAD, exception.getStage());
        assertTrue(exception.getSafeReason().contains("temporarily unavailable"));
        assertTrue(exception.getDiagnostic().contains("SocketTimeoutException"));
        // Verifies bounded Level 1 immediate retry occurred before giving up
        verify(uploader, atLeast(2)).upload(any(byte[].class), anyMap());
    }

    @Test
    void transientFailureSucceedsOnImmediateRetry() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "file", "bedroom.webp", "image/webp", new byte[]{1});

        Map<String, Object> successMap = Map.of(
                "secure_url", "https://cdn.example/retry-success.webp",
                "public_id", "media-20-retry",
                "resource_type", "image"
        );

        // First attempt fails with transient timeout, second succeeds
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenThrow(new java.net.SocketTimeoutException("Read timed out"))
                .thenReturn(successMap);

        String url = service.uploadImage(image, "media-20-retry");

        assertEquals("https://cdn.example/retry-success.webp", url);
        verify(uploader, times(2)).upload(any(byte[].class), anyMap());
    }

    @Test
    void diagnosticFromExtractsExceptionClassAndMessage() {
        IOException cause = new IOException("read timeout");
        String diagnostic = MediaUploadException.diagnosticFrom(cause);
        assertTrue(diagnostic.contains("IOException"));
        assertTrue(diagnostic.contains("read timeout"));
    }

    @Test
    void diagnosticFromHandlesNull() {
        assertEquals("unknown", MediaUploadException.diagnosticFrom(null));
    }

    @Test
    void imageDeterministicReconciliationSuccess() throws Exception {
        com.cloudinary.Api api = mock(com.cloudinary.Api.class);
        when(cloudinary.api()).thenReturn(api);

        com.cloudinary.api.ApiResponse apiResponse = mock(com.cloudinary.api.ApiResponse.class);
        when(apiResponse.get("secure_url")).thenReturn("https://res.cloudinary.com/demo/image/upload/sample.webp");
        when(apiResponse.get("public_id")).thenReturn("pathome/properties/images/req-123");
        when(apiResponse.get("resource_type")).thenReturn("image");

        when(api.resource(eq("pathome/properties/images/req-123"), anyMap())).thenReturn(apiResponse);

        java.util.Optional<CloudinaryService.CloudinaryUploadResult> result =
                service.findExistingResourceByUploadRequestId("req-123", false);

        assertTrue(result.isPresent());
        assertEquals("https://res.cloudinary.com/demo/image/upload/sample.webp", result.get().secureUrl());
        assertEquals("pathome/properties/images/req-123", result.get().publicId());
        assertEquals("image", result.get().resourceType());
    }

    @Test
    void videoDeterministicReconciliationSuccess() throws Exception {
        com.cloudinary.Api api = mock(com.cloudinary.Api.class);
        when(cloudinary.api()).thenReturn(api);

        com.cloudinary.api.ApiResponse apiResponse = mock(com.cloudinary.api.ApiResponse.class);
        when(apiResponse.get("secure_url")).thenReturn("https://res.cloudinary.com/demo/video/upload/tour.mp4");
        when(apiResponse.get("public_id")).thenReturn("pathome/properties/videos/req-vid-1");
        when(apiResponse.get("resource_type")).thenReturn("video");

        when(api.resource(eq("pathome/properties/videos/req-vid-1"), anyMap())).thenReturn(apiResponse);

        java.util.Optional<CloudinaryService.CloudinaryUploadResult> result =
                service.findExistingResourceByUploadRequestId("req-vid-1", true);

        assertTrue(result.isPresent());
        assertEquals("https://res.cloudinary.com/demo/video/upload/tour.mp4", result.get().secureUrl());
        assertEquals("pathome/properties/videos/req-vid-1", result.get().publicId());
        assertEquals("video", result.get().resourceType());
    }


    @Test
    void definitiveCloudinaryNotFoundReturnsEmpty() throws Exception {
        com.cloudinary.Api api = mock(com.cloudinary.Api.class);
        when(cloudinary.api()).thenReturn(api);

        when(api.resource(eq("pathome/properties/images/not-found-id"), anyMap()))
                .thenThrow(new com.cloudinary.api.exceptions.NotFound("Resource not found"));

        java.util.Optional<CloudinaryService.CloudinaryUploadResult> result =
                service.findExistingResourceByUploadRequestId("not-found-id", false);

        assertTrue(result.isEmpty(), "Definitive NotFound must return Optional.empty()");
    }

    @Test
    void ambiguousCloudinaryErrorThrowsMediaUploadExceptionAndDoesNotReturnEmpty() throws Exception {
        com.cloudinary.Api api = mock(com.cloudinary.Api.class);
        when(cloudinary.api()).thenReturn(api);

        when(api.resource(eq("pathome/properties/images/rate-limit-id"), anyMap()))
                .thenThrow(new com.cloudinary.api.exceptions.RateLimited("Rate limit exceeded"));

        MediaUploadException ex = assertThrows(MediaUploadException.class, () ->
                service.findExistingResourceByUploadRequestId("rate-limit-id", false));

        assertEquals(MediaUploadException.Stage.CLOUDINARY_UPLOAD, ex.getStage());
        assertTrue(ex.getDiagnostic().contains("RateLimited"));
    }
}
