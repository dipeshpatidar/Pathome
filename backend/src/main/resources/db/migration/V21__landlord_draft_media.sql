-- Extend existing draft media metadata without changing the admin staging path.
ALTER TABLE property_draft_media ALTER COLUMN admin_id DROP NOT NULL;
ALTER TABLE property_draft_media ALTER COLUMN staging_object_key DROP NOT NULL;
ALTER TABLE property_draft_media ADD COLUMN landlord_user_id BIGINT REFERENCES users(id);
ALTER TABLE property_draft_media ADD COLUMN cloudinary_url VARCHAR(1000);
ALTER TABLE property_draft_media ADD COLUMN cloudinary_public_id VARCHAR(300);
ALTER TABLE property_draft_media ADD COLUMN upload_status VARCHAR(20);
ALTER TABLE property_draft_media ADD COLUMN sort_order INTEGER;
ALTER TABLE property_draft_media ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE property_draft_media ADD CONSTRAINT ck_media_one_owner
    CHECK ((admin_id IS NULL) <> (landlord_user_id IS NULL));
ALTER TABLE property_draft_media ADD CONSTRAINT ck_landlord_media_storage
    CHECK (landlord_user_id IS NULL OR
           (upload_status IN ('PENDING', 'FAILED', 'UPLOADED', 'DELETING') AND
            (upload_status <> 'UPLOADED' OR cloudinary_url IS NOT NULL)));
CREATE INDEX idx_pdm_landlord_draft_order
    ON property_draft_media (landlord_user_id, draft_id, sort_order, id)
    WHERE landlord_user_id IS NOT NULL;
