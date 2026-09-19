-- V7: Admin Property Upload Drafts & Staged Draft Media
CREATE TABLE IF NOT EXISTS property_upload_drafts (
    id                  BIGSERIAL PRIMARY KEY,
    draft_id            VARCHAR(64)    NOT NULL UNIQUE,
    admin_id            VARCHAR(120)   NOT NULL,
    draft_type          VARCHAR(30)    NOT NULL DEFAULT 'SINGLE',
    status              VARCHAR(30)    NOT NULL DEFAULT 'DRAFT',
    title_summary       VARCHAR(255),
    item_count          INTEGER        NOT NULL DEFAULT 1,
    version             INTEGER        NOT NULL DEFAULT 1,
    payload             TEXT           NOT NULL,
    published_property_id BIGINT,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW()
);

ALTER TABLE property_upload_drafts ADD COLUMN IF NOT EXISTS published_property_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_pud_admin_updated
    ON property_upload_drafts (admin_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_pud_draft_id
    ON property_upload_drafts (draft_id);

CREATE INDEX IF NOT EXISTS idx_pud_status
    ON property_upload_drafts (status);

CREATE INDEX IF NOT EXISTS idx_pud_published_property_id
    ON property_upload_drafts (published_property_id);

-- Enforce hard database-level unique idempotency: one draft produces at most one published property
ALTER TABLE listings ADD COLUMN IF NOT EXISTS origin_draft_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS idx_listings_origin_draft_id
    ON listings (origin_draft_id)
    WHERE origin_draft_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS property_draft_media (
    id                  BIGSERIAL PRIMARY KEY,
    media_id            VARCHAR(64)    NOT NULL UNIQUE,
    draft_id            VARCHAR(64)    NOT NULL,
    admin_id            VARCHAR(120)   NOT NULL,
    card_id             VARCHAR(64),
    original_filename   VARCHAR(255)   NOT NULL DEFAULT '',
    file_size_bytes     BIGINT         NOT NULL DEFAULT 0,
    content_type        VARCHAR(100)   NOT NULL DEFAULT 'image/jpeg',
    staging_object_key  VARCHAR(300)   NOT NULL,
    room_tag            VARCHAR(40),
    is_cover            BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_draft_media_draft
        FOREIGN KEY (draft_id)
        REFERENCES property_upload_drafts (draft_id)
        ON DELETE CASCADE
);

-- Idempotent drop of obsolete media expires_at column if previously created in dev DB
ALTER TABLE property_draft_media DROP COLUMN IF EXISTS expires_at;

CREATE INDEX IF NOT EXISTS idx_pdm_draft_id
    ON property_draft_media (draft_id);

CREATE INDEX IF NOT EXISTS idx_pdm_admin_id
    ON property_draft_media (admin_id);

CREATE INDEX IF NOT EXISTS idx_pdm_staging_key
    ON property_draft_media (staging_object_key);
