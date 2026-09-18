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
}
