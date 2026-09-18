package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class MediaStagingServiceTest {

    @TempDir
    Path tempDir;

    private FileSystemMediaStagingService stagingService;

    @BeforeEach
    void setUp() {
        stagingService = new FileSystemMediaStagingService(tempDir);
    }

    @Test
    void stageAndRetrieve_roundTripSucceeds() throws Exception {
        String key = "staging/req-123/sample_image.webp";
        byte[] payload = "test-image-binary-bytes".getBytes(StandardCharsets.UTF_8);

        String stagedKey = stagingService.stage(key, new ByteArrayInputStream(payload), payload.length, "image/webp");
        assertEquals(key, stagedKey);

        assertTrue(stagingService.exists(key));

        try (InputStream stream = stagingService.retrieve(key)) {
            byte[] retrieved = stream.readAllBytes();
            assertArrayEquals(payload, retrieved);
        }
    }

    @Test
    void delete_removesStagedObject() {
        String key = "staging/req-456/sample_video.mp4";
        byte[] payload = "test-video-payload".getBytes(StandardCharsets.UTF_8);

        stagingService.stage(key, new ByteArrayInputStream(payload), payload.length, "video/mp4");
        assertTrue(stagingService.exists(key));

        stagingService.delete(key);
        assertFalse(stagingService.exists(key));
    }

    @Test
    void retrieve_missingObject_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> stagingService.retrieve("staging/missing/file.jpg"));
    }

    @Test
    void pathTraversalAttempt_isRejected() {
        String maliciousKey = "../../../etc/passwd";
        byte[] payload = "malicious".getBytes(StandardCharsets.UTF_8);

        // Path traversal sanitization strips .. preventing escape from rootDir
        String staged = stagingService.stage(maliciousKey, new ByteArrayInputStream(payload), payload.length, "text/plain");
        assertNotNull(staged);
        // Ensure it stayed inside tempDir
        assertTrue(stagingService.exists(staged));
    }

    @Test
    void s3StagingService_normalizesLeadingSlashes() {
        software.amazon.awssdk.services.s3.S3Client mockS3 = org.mockito.Mockito.mock(software.amazon.awssdk.services.s3.S3Client.class);
        S3MediaStagingService s3Service = new S3MediaStagingService(mockS3, "test-bucket");

        byte[] payload = "s3-test-bytes".getBytes(StandardCharsets.UTF_8);
        String returnedKey = s3Service.stage("/staging/req-123/sample.webp", new ByteArrayInputStream(payload), payload.length, "image/webp");

        assertEquals("staging/req-123/sample.webp", returnedKey);

        org.mockito.ArgumentCaptor<software.amazon.awssdk.services.s3.model.PutObjectRequest> putCaptor =
                org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.PutObjectRequest.class);
        Mockito.verify(mockS3).putObject(putCaptor.capture(), Mockito.any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertEquals("staging/req-123/sample.webp", putCaptor.getValue().key());
        assertFalse(putCaptor.getValue().key().startsWith("/"));

        s3Service.exists("/staging/req-123/sample.webp");
        org.mockito.ArgumentCaptor<software.amazon.awssdk.services.s3.model.HeadObjectRequest> headCaptor =
                org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class);
        org.mockito.Mockito.verify(mockS3).headObject(headCaptor.capture());
        assertEquals("staging/req-123/sample.webp", headCaptor.getValue().key());

        s3Service.delete("/staging/req-123/sample.webp");
        org.mockito.ArgumentCaptor<software.amazon.awssdk.services.s3.model.DeleteObjectRequest> delCaptor =
                org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class);
        org.mockito.Mockito.verify(mockS3).deleteObject(delCaptor.capture());
        assertEquals("staging/req-123/sample.webp", delCaptor.getValue().key());
    }
}

