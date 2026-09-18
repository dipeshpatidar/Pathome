ALTER TABLE IF EXISTS property_media_assets
    ADD COLUMN IF NOT EXISTS upload_request_id VARCHAR(80);

CREATE UNIQUE INDEX IF NOT EXISTS idx_media_listing_upload_request
    ON property_media_assets (listing_id, upload_request_id)
    WHERE upload_request_id IS NOT NULL;
