package com.indore.pathome.spaces.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.net.URI;
import java.util.Objects;

/**
 * Production implementation of {@link MediaStagingService} targeting S3-compatible
 * object storage (AWS S3, Cloudflare R2, MinIO, Wasabi).
 *
 * <p>Uses streaming operations without holding large media files in JVM heap memory.</p>
 */
public class S3MediaStagingService implements MediaStagingService {

    private static final Logger log = LoggerFactory.getLogger(S3MediaStagingService.class);

    private final S3Client s3Client;
    private final String bucketName;

    public S3MediaStagingService(
            String endpoint,
            String region,
            String bucketName,
            String accessKey,
            String secretKey,
            boolean pathStyleAccess) {

        this.bucketName = Objects.requireNonNull(bucketName, "bucketName must not be null");

        S3ClientBuilder builder = S3Client.builder();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region.trim()));
        } else {
            builder.region(Region.US_EAST_1);
        }

        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cleanKey(accessKey), cleanKey(secretKey))
            ));
        }

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.trim()));
        }

        S3Configuration.Builder serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyleAccess)
                .chunkedEncodingEnabled(false)
                .checksumValidationEnabled(false);
        builder.serviceConfiguration(serviceConfig.build());

        this.s3Client = builder.build();
        log.info("Initialized S3MediaStagingService with bucket: {}", bucketName);
    }

    /** Constructor for direct injection (e.g. testing with mocked S3Client). */
    public S3MediaStagingService(S3Client s3Client, String bucketName) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName must not be null");
    }

    @Override
    public String stage(String objectKey, InputStream inputStream, long contentLength, String contentType) {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        String key = normalizeKey(objectKey);

        try {
            PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key);

            if (contentType != null && !contentType.isBlank()) {
                requestBuilder.contentType(contentType);
            }

            s3Client.putObject(requestBuilder.build(), RequestBody.fromInputStream(inputStream, contentLength));
            log.info("Successfully staged media object: {} in bucket: {}", key, bucketName);
            return key;
        } catch (Exception e) {
            log.error("Failed to stage object [{}] in S3: {}", key, e.getMessage());
            throw new RuntimeException("Staging storage error: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream retrieve(String objectKey) {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        String key = normalizeKey(objectKey);
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            return s3Client.getObject(getRequest);
        } catch (NoSuchKeyException e) {
            log.warn("Staged object not found or expired in S3: {}", key);
            throw new IllegalStateException("Staged media object has expired or is missing: " + key, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                log.warn("Staged object not found or expired in S3 (404): {}", key);
                throw new IllegalStateException("Staged media object has expired or is missing: " + key, e);
            }
            log.error("Failed to retrieve staged object [{}] from S3: {}", key, e.getMessage());
            throw new RuntimeException("Error retrieving staged media: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to retrieve staged object [{}] from S3: {}", key, e.getMessage());
            throw new RuntimeException("Error retrieving staged media: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return false;
        String key = normalizeKey(objectKey);
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            log.warn("S3 error checking existence of object [{}]: {}", key, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Unexpected error checking existence of object [{}]: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        String key = normalizeKey(objectKey);
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.info("Deleted staged object from S3: {}", key);
        } catch (Exception e) {
            log.warn("Failed to delete staged object [{}] from S3 (non-fatal): {}", key, e.getMessage());
        }
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    private String normalizeKey(String objectKey) {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        String trimmed = objectKey.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static String cleanKey(String key) {
        if (key == null) return "";
        String trimmed = key.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            if (trimmed.length() >= 2) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }
}
