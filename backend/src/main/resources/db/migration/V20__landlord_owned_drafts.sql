-- Share the durable draft table while keeping admin and landlord ownership disjoint.
ALTER TABLE property_upload_drafts ALTER COLUMN admin_id DROP NOT NULL;
ALTER TABLE property_upload_drafts ADD COLUMN landlord_user_id BIGINT REFERENCES users(id);
ALTER TABLE property_upload_drafts ADD CONSTRAINT ck_draft_one_owner
    CHECK ((admin_id IS NULL) <> (landlord_user_id IS NULL));

CREATE INDEX idx_pud_landlord_status_updated
    ON property_upload_drafts (landlord_user_id, status, updated_at DESC, id DESC)
    WHERE landlord_user_id IS NOT NULL;
