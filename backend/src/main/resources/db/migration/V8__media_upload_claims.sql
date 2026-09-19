-- V8: Distributed media upload claims for concurrent upload idempotency
CREATE TABLE IF NOT EXISTS media_upload_claims (
    id                  BIGSERIAL PRIMARY KEY,
    listing_id          BIGINT NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
    upload_request_id   VARCHAR(80) NOT NULL,
    owner_token         VARCHAR(64) NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    error_message       TEXT,
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_media_upload_claim UNIQUE (listing_id, upload_request_id)
);

CREATE INDEX IF NOT EXISTS idx_media_upload_claims_expiry
    ON media_upload_claims (status, expires_at);
