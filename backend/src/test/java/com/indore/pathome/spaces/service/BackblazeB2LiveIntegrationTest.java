package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live Backblaze B2 integration smoke test.
 * Tests PUT, HEAD/EXISTS, GET, CONTENT MATCH, DELETE, and POST-DELETE HEAD
 * strictly under the 'staging/integration-test/' prefix using harmless test content.
 */
class BackblazeB2LiveIntegrationTest {

    @Test
    void testLiveBackblazeB2RoundTrip() throws Exception {
        Map<String, String> config = loadConfig();

        String bucket = config.get("PATHOME_STAGING_S3_BUCKET");
        String region = config.getOrDefault("PATHOME_STAGING_S3_REGION", "us-east-005");
        String endpoint = config.get("PATHOME_STAGING_S3_ENDPOINT");
        String accessKey = config.get("PATHOME_STAGING_S3_ACCESS_KEY");
        String secretKey = config.get("PATHOME_STAGING_S3_SECRET_KEY");
        boolean pathStyle = Boolean.parseBoolean(config.getOrDefault("PATHOME_STAGING_S3_PATH_STYLE", "true"));

        boolean credentialsAvailable = bucket != null && !bucket.isBlank()
                && accessKey != null && !accessKey.isBlank() && !accessKey.contains("YOUR")
                && secretKey != null && !secretKey.isBlank() && !secretKey.contains("YOUR");

        assumeTrue(credentialsAvailable,
                "Live Backblaze B2 credentials not configured or are placeholders — skipping live smoke test in CI/unconfigured environment");


        S3MediaStagingService stagingService = new S3MediaStagingService(
                endpoint, region, bucket, accessKey, secretKey, pathStyle
        );

        String testKey = "staging/integration-test/smoke-" + UUID.randomUUID() + ".txt";
        byte[] expectedContent = ("Pathome B2 Smoke Test Verification Payload: " + System.currentTimeMillis())
                .getBytes(StandardCharsets.UTF_8);

        try {
            // 1. PUT
            String stagedKey = stagingService.stage(
                    testKey,
                    new ByteArrayInputStream(expectedContent),
                    expectedContent.length,
                    "text/plain"
            );
            assertEquals(testKey, stagedKey, "Staged key must match requested key without alteration");

            // 2. HEAD / EXISTS
            boolean existsBefore = stagingService.exists(testKey);
            assertTrue(existsBefore, "Object must exist in Backblaze B2 after PUT");

            // 3. GET & 4. CONTENT VERIFICATION
            try (InputStream is = stagingService.retrieve(testKey)) {
                assertNotNull(is, "Retrieved stream must not be null");
                byte[] actualContent = is.readAllBytes();
                assertArrayEquals(expectedContent, actualContent, "Retrieved content must match uploaded content exactly");
            }

        } finally {
            // 5. DELETE
            stagingService.delete(testKey);

            // 6. POST-DELETE HEAD / EXISTS
            boolean existsAfter = stagingService.exists(testKey);
            assertFalse(existsAfter, "Object must no longer exist in Backblaze B2 after DELETE");
        }
    }

    @Test
    void testLiveDraftB2RoundTrip() throws Exception {
        Map<String, String> config = loadConfig();

        String bucket = config.getOrDefault("PATHOME_DRAFT_STAGING_S3_BUCKET", config.get("PATHOME_STAGING_S3_BUCKET"));
        String region = config.getOrDefault("PATHOME_DRAFT_STAGING_S3_REGION", config.getOrDefault("PATHOME_STAGING_S3_REGION", "us-east-005"));
        String endpoint = config.getOrDefault("PATHOME_DRAFT_STAGING_S3_ENDPOINT", config.get("PATHOME_STAGING_S3_ENDPOINT"));
        String accessKey = config.getOrDefault("PATHOME_DRAFT_STAGING_S3_ACCESS_KEY", config.get("PATHOME_STAGING_S3_ACCESS_KEY"));
        String secretKey = config.getOrDefault("PATHOME_DRAFT_STAGING_S3_SECRET_KEY", config.get("PATHOME_STAGING_S3_SECRET_KEY"));
        boolean pathStyle = Boolean.parseBoolean(config.getOrDefault("PATHOME_DRAFT_STAGING_S3_PATH_STYLE",
                config.getOrDefault("PATHOME_STAGING_S3_PATH_STYLE", "true")));

        boolean credentialsAvailable = bucket != null && !bucket.isBlank()
                && accessKey != null && !accessKey.isBlank() && !accessKey.contains("YOUR")
                && secretKey != null && !secretKey.isBlank() && !secretKey.contains("YOUR");

        assumeTrue(credentialsAvailable,
                "Live Backblaze B2 draft credentials not configured — skipping live draft test");

        S3MediaStagingService draftStagingService = new S3MediaStagingService(
                endpoint, region, bucket, accessKey, secretKey, pathStyle
        );

        String testKey = "drafts/live-test/smoke-" + UUID.randomUUID() + ".txt";
        byte[] expectedContent = ("Pathome Draft B2 Smoke Verification: " + System.currentTimeMillis())
                .getBytes(StandardCharsets.UTF_8);

        try {
            // 1. PUT
            String stagedKey = draftStagingService.stage(
                    testKey,
                    new ByteArrayInputStream(expectedContent),
                    expectedContent.length,
                    "text/plain"
            );
            assertEquals(testKey, stagedKey, "Staged key under drafts/ must match requested key");

            // 2. HEAD / EXISTS
            boolean existsBefore = draftStagingService.exists(testKey);
            assertTrue(existsBefore, "Object under drafts/ must exist in Backblaze B2 after PUT");

            // 3. GET
            try (InputStream is = draftStagingService.retrieve(testKey)) {
                assertNotNull(is, "Retrieved stream must not be null");
                byte[] actualContent = is.readAllBytes();
                assertArrayEquals(expectedContent, actualContent, "Retrieved content must match uploaded content");
            }
        } finally {
            // 4. DELETE
            draftStagingService.delete(testKey);

            // 5. Verify deletion
            boolean existsAfter = draftStagingService.exists(testKey);
            assertFalse(existsAfter, "Object under drafts/ must no longer exist after DELETE");
        }
    }

    private static Map<String, String> loadConfig() {
        Map<String, String> env = new HashMap<>(System.getenv());

        // Check root .env.local
        Path[] searchPaths = new Path[]{
                Paths.get(".env.local"),
                Paths.get("../.env.local"),
                Paths.get("../../.env.local")
        };

        for (Path p : searchPaths) {
            if (Files.isRegularFile(p)) {
                try (BufferedReader reader = Files.newBufferedReader(p)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            String k = line.substring(0, eq).trim();
                            String v = line.substring(eq + 1).trim();
                            if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith("\"") && v.endsWith("\""))) {
                                if (v.length() >= 2) {
                                    v = v.substring(1, v.length() - 1).trim();
                                }
                            }
                            // Do not overwrite existing non-empty env vars
                            if (!env.containsKey(k) || env.get(k) == null || env.get(k).isBlank()) {
                                env.put(k, v);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                break;
            }
        }

        return env;
    }
}
