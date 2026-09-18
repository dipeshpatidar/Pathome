-- V6: Smart / Stage-Aware Failed Media Recovery support
ALTER TABLE media_upload_failures
    ADD COLUMN IF NOT EXISTS storage_url                  VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS storage_public_id           VARCHAR(200),
    ADD COLUMN IF NOT EXISTS staging_object_key          VARCHAR(300),
    ADD COLUMN IF NOT EXISTS staging_expires_at          TIMESTAMP,
    ADD COLUMN IF NOT EXISTS cloudinary_failure_category VARCHAR(50),
    ADD COLUMN IF NOT EXISTS provider_status_code        INTEGER;

CREATE INDEX IF NOT EXISTS idx_muf_staging_key
    ON media_upload_failures (staging_object_key)
    WHERE staging_object_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_muf_staging_expires
    ON media_upload_failures (staging_expires_at)
    WHERE staging_expires_at IS NOT NULL;
