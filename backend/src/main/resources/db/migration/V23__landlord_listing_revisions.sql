-- A revision stays separate from the approved listing until an administrator approves it.
ALTER TABLE property_upload_drafts ADD COLUMN revision_base_version BIGINT;
ALTER TABLE property_upload_drafts ADD COLUMN review_note VARCHAR(1000);
ALTER TABLE property_draft_media ADD COLUMN reused_from_listing BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE listings ADD COLUMN review_note VARCHAR(1000);
ALTER TABLE property_media_assets ADD COLUMN sort_order INTEGER;

CREATE UNIQUE INDEX idx_landlord_open_revision
    ON property_upload_drafts (published_property_id)
    WHERE landlord_user_id IS NOT NULL AND published_property_id IS NOT NULL
      AND status IN ('DRAFT', 'REVIEW');
