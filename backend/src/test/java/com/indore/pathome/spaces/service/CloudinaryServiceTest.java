package com.indore.pathome.spaces.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.uploadImage(oversizedImage, "media-20-image"));

        assertTrue(exception.getMessage().contains("10 MB"));
        verify(cloudinary, times(0)).uploader();
    }

    @Test
    void rejectsVideoAboveCloudinaryLimitBeforeNetworkCall() {
        MultipartFile oversizedVideo = mock(MultipartFile.class);
        when(oversizedVideo.isEmpty()).thenReturn(false);
        when(oversizedVideo.getSize()).thenReturn(CloudinaryService.MAX_VIDEO_BYTES + 1L);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.uploadVideo(oversizedVideo, "media-20-video"));

        assertTrue(exception.getMessage().contains("100 MB"));
        verify(cloudinary, times(0)).uploader();
    }

    @Test
    void rejectsMismatchedMediaType() {
        MockMultipartFile textFile = new MockMultipartFile(
                "file", "notes.txt", "text/plain", new byte[]{1});

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.uploadImage(textFile, "media-20-notes"));

        assertTrue(exception.getMessage().contains("unsupported file type"));
    }

    @Test
    void uploadsImageWithStablePublicIdentifier() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "file", "living-room.webp", "image/webp", new byte[]{1, 2, 3});
        when(uploader.upload(any(InputStream.class), anyMap()))
                .thenReturn(Map.of("secure_url", "https://cdn.example/property.webp"));

        String url = service.uploadImage(image, "media-20-image");

        assertEquals("https://cdn.example/property.webp", url);
        verify(uploader).upload(any(InputStream.class), anyMap());
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
    void reportsInterruptedCloudinaryUpload() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "file", "bedroom.webp", "image/webp", new byte[]{1});
        when(uploader.upload(any(InputStream.class), anyMap()))
                .thenThrow(new IOException("connection interrupted"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.uploadImage(image, "media-20-bedroom"));

        assertTrue(exception.getMessage().contains("could not be completed"));
        verify(uploader).upload(any(InputStream.class), anyMap());
    }
}
