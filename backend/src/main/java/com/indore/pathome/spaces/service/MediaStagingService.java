package com.indore.pathome.spaces.service;

import java.io.InputStream;

/**
 * Pluggable abstraction for private temporary object storage of failed media
 * uploads.
 *
 * <p>
 * Isolates all business logic from specific cloud providers (AWS S3, Cloudflare
 * R2, MinIO).
 * Large binaries (images, videos up to 100 MB) are streamed directly to/from
 * staging storage
 * without being held in PostgreSQL or buffered unnecessarily in JVM heap
 * memory.
 * </p>
 */
public interface MediaStagingService {

    /**
     * Streams and stages a media binary in private temporary object storage.
     *
     * @param objectKey     unique, collision-safe key for the staged media object
     * @param inputStream   stream of the media binary
     * @param contentLength size of the stream in bytes
     * @param contentType   MIME type (e.g. image/jpeg, video/mp4)
     * @return the stored object key
     */
    String stage(String objectKey, InputStream inputStream, long contentLength, String contentType);

    /**
     * Opens an input stream to retrieve the staged media binary.
     *
     * @param objectKey the object key returned during staging
     * @return readable InputStream of the staged media
     */
    InputStream retrieve(String objectKey);

    /**
     * Checks if the object exists and has not yet expired or been purged.
     *
     * @param objectKey the object key
     * @return true if the staged object exists and is readable
     */
    boolean exists(String objectKey);

    /**
     * Proactively deletes the staged media object (e.g. on resolution or
     * dismissal).
     * Failure to delete must not undo a successful recovery.
     *
     * @param objectKey the object key
     */
    void delete(String objectKey);

    /**
     * Indicates whether a real remote S3-compatible staging store is configured and
     * active.
     */
    boolean isConfigured();
}
