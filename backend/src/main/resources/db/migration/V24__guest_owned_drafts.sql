-- Guest proof is a one-way hash. Existing admin and landlord rows remain valid.
ALTER TABLE property_upload_drafts DROP CONSTRAINT ck_draft_one_owner;
ALTER TABLE property_upload_drafts ADD COLUMN guest_token_hash VARCHAR(64);
ALTER TABLE property_upload_drafts ADD COLUMN guest_expires_at TIMESTAMP;
ALTER TABLE property_upload_drafts ADD CONSTRAINT ck_draft_one_owner CHECK (
    (CASE WHEN admin_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN landlord_user_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN guest_token_hash IS NOT NULL THEN 1 ELSE 0 END) = 1
    AND (guest_token_hash IS NULL OR guest_expires_at IS NOT NULL)
);
CREATE UNIQUE INDEX idx_pud_guest_token_hash ON property_upload_drafts (guest_token_hash)
    WHERE guest_token_hash IS NOT NULL;
CREATE INDEX idx_pud_guest_expiry ON property_upload_drafts (guest_expires_at)
    WHERE guest_token_hash IS NOT NULL;

ALTER TABLE property_draft_media DROP CONSTRAINT ck_media_one_owner;
ALTER TABLE property_draft_media ADD COLUMN guest_owned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE property_draft_media ADD CONSTRAINT ck_media_one_owner CHECK (
    (CASE WHEN admin_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN landlord_user_id IS NOT NULL THEN 1 ELSE 0 END) +
    (CASE WHEN guest_owned THEN 1 ELSE 0 END) = 1
);
CREATE INDEX idx_pdm_guest_draft_order ON property_draft_media (draft_id, sort_order, id)
    WHERE guest_owned;
CREATE UNIQUE INDEX idx_pdm_guest_single_cover ON property_draft_media (draft_id)
    WHERE guest_owned AND is_cover;
ALTER TABLE property_draft_media DROP CONSTRAINT ck_landlord_media_storage;
ALTER TABLE property_draft_media ADD CONSTRAINT ck_landlord_media_storage CHECK (
    landlord_user_id IS NULL OR
    (upload_status IN ('PENDING', 'FAILED', 'STAGED', 'UPLOADED', 'DELETING') AND
     (upload_status <> 'UPLOADED' OR cloudinary_url IS NOT NULL))
);
