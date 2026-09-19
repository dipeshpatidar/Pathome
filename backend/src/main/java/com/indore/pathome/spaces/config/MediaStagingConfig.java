package com.indore.pathome.spaces.config;

import com.indore.pathome.spaces.service.FileSystemMediaStagingService;
import com.indore.pathome.spaces.service.MediaStagingService;
import com.indore.pathome.spaces.service.S3MediaStagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * Spring configuration for the {@link MediaStagingService} bean.
 *
 * <p>If external S3/R2 storage is configured via application properties or environment variables,
 * the production {@link S3MediaStagingService} is constructed. Otherwise, the local
 * {@link FileSystemMediaStagingService} is activated as a transparent fallback.</p>
 */
@Configuration
public class MediaStagingConfig {

    private static final Logger log = LoggerFactory.getLogger(MediaStagingConfig.class);

    @Bean(name = "mediaStagingService")
    @Primary
    @ConditionalOnMissingBean(name = "mediaStagingService")
    public MediaStagingService mediaStagingService(
            Environment environment,
            @Value("${pathome.staging.s3.endpoint:}") String endpoint,
            @Value("${pathome.staging.s3.region:us-east-005}") String region,
            @Value("${pathome.staging.s3.bucket:}") String bucket,
            @Value("${pathome.staging.s3.access-key:}") String accessKey,
            @Value("${pathome.staging.s3.secret-key:}") String secretKey,
            @Value("${pathome.staging.s3.path-style-access:${pathome.staging.s3.path-style:true}}") boolean pathStyleAccess,
            @Value("${pathome.staging.local-path:}") String localPath) {

        boolean isProduction = isProductionEnvironment(environment);

        if (bucket != null && !bucket.isBlank()) {
            log.info("Configuring S3MediaStagingService with bucket: {}", bucket);
            return new S3MediaStagingService(endpoint, region, bucket, accessKey, secretKey, pathStyleAccess);
        }

        if (isProduction) {
            throw new IllegalStateException(
                    "Production environment requires durable S3-compatible object storage configuration " +
                    "for failed media staging ('pathome.staging.s3.bucket' is missing or empty). " +
                    "Ephemeral local filesystem staging cannot be used in production multi-node/container environments. " +
                    "Please configure 'pathome.staging.s3.bucket', 'pathome.staging.s3.region', and appropriate credentials."
            );
        }

        log.info("Temporary object storage: S3 bucket not configured. Using local filesystem staging fallback.");
        return new FileSystemMediaStagingService(localPath);
    }

    /**
     * Dedicated staging service for draft media.
     * Supports Option A (dedicated credentials restricted to 'drafts/') or Option B (falling back to staging credentials).
     */
    @Bean(name = "draftMediaStagingService")
    public MediaStagingService draftMediaStagingService(
            Environment environment,
            @Value("${pathome.draft-staging.s3.endpoint:${pathome.staging.s3.endpoint:}}") String endpoint,
            @Value("${pathome.draft-staging.s3.region:${pathome.staging.s3.region:us-east-005}}") String region,
            @Value("${pathome.draft-staging.s3.bucket:${pathome.staging.s3.bucket:}}") String bucket,
            @Value("${pathome.draft-staging.s3.access-key:${pathome.staging.s3.access-key:}}") String accessKey,
            @Value("${pathome.draft-staging.s3.secret-key:${pathome.staging.s3.secret-key:}}") String secretKey,
            @Value("${pathome.staging.s3.path-style-access:${pathome.staging.s3.path-style:true}}") boolean pathStyleAccess,
            @Value("${pathome.staging.local-path:}") String localPath) {

        if (bucket != null && !bucket.isBlank()) {
            log.info("Configuring draftMediaStagingService (S3) with bucket: {}", bucket);
            return new S3MediaStagingService(endpoint, region, bucket, accessKey, secretKey, pathStyleAccess);
        }

        log.info("Draft media object storage: S3 bucket not configured. Using local filesystem staging fallback.");
        return new FileSystemMediaStagingService(localPath);
    }

    public static boolean isProductionEnvironment(Environment env) {
        if (env == null) return false;
        String[] activeProfiles = env.getActiveProfiles();
        for (String profile : activeProfiles) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        String requireDurable = env.getProperty("pathome.staging.require-durable");
        return "true".equalsIgnoreCase(requireDurable);
    }
}
