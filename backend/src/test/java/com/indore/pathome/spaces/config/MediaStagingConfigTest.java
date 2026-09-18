package com.indore.pathome.spaces.config;

import com.indore.pathome.spaces.service.FileSystemMediaStagingService;
import com.indore.pathome.spaces.service.MediaStagingService;
import com.indore.pathome.spaces.service.S3MediaStagingService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class MediaStagingConfigTest {

    private final MediaStagingConfig config = new MediaStagingConfig();

    @Test
    void nonProductionWithoutBucket_fallsBackToFileSystemStaging() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        MediaStagingService service = config.mediaStagingService(
                env, "", "us-east-1", "", "", "", true, ""
        );

        assertNotNull(service);
        assertTrue(service instanceof FileSystemMediaStagingService);
    }

    @Test
    void productionWithoutBucket_throwsIllegalStateException() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                config.mediaStagingService(env, "", "us-east-1", "", "", "", true, "")
        );

        assertTrue(ex.getMessage().contains("Production environment requires durable S3-compatible object storage"));
    }

    @Test
    void explicitRequireDurableWithoutBucket_throwsIllegalStateException() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("pathome.staging.require-durable", "true");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                config.mediaStagingService(env, "", "us-east-1", "", "", "", true, "")
        );

        assertTrue(ex.getMessage().contains("Production environment requires durable S3-compatible object storage"));
    }

    @Test
    void productionWithBucket_configuresS3Service() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");

        MediaStagingService service = config.mediaStagingService(
                env, "http://localhost:9000", "us-east-1", "test-bucket", "test-key", "test-secret", true, ""
        );

        assertNotNull(service);
        assertTrue(service instanceof S3MediaStagingService);
    }
}
