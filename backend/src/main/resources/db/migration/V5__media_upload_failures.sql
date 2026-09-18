-- V5: Durable failed-upload tracking for admin retry and visibility
CREATE TABLE IF NOT EXISTS media_upload_failures (
    id                  BIGSERIAL PRIMARY KEY,
    upload_request_id   VARCHAR(80),
    listing_id          BIGINT,
    media_type          VARCHAR(30)    NOT NULL DEFAULT 'IMAGE',
    original_filename   VARCHAR(255)   NOT NULL DEFAULT '',
    file_size_bytes     BIGINT,
    room_tag            VARCHAR(40),
    failure_stage       VARCHAR(60)    NOT NULL,
    failure_reason      VARCHAR(500)   NOT NULL,
    internal_diagnostic TEXT,
    retry_count         INTEGER        NOT NULL DEFAULT 0,
    status              VARCHAR(20)    NOT NULL DEFAULT 'FAILED',
    resolved_media_url  VARCHAR(1000),
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_muf_listing_id
    ON media_upload_failures (listing_id)
    WHERE listing_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_muf_status
    ON media_upload_failures (status);

CREATE UNIQUE INDEX IF NOT EXISTS idx_muf_upload_request_id
    ON media_upload_failures (upload_request_id)
    WHERE upload_request_id IS NOT NULL;
