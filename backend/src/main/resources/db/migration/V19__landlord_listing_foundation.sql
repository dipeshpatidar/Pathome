-- Additive listing foundation. Preserve ACTIVE/PENDING/CLOSED public behavior during rollout.
ALTER TABLE listings ADD COLUMN owner_user_id BIGINT REFERENCES users(id);
ALTER TABLE listings ADD COLUMN rental_mode VARCHAR(32);
ALTER TABLE listings ADD COLUMN workflow_status VARCHAR(32);
ALTER TABLE listings ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE listings ADD COLUMN canonical_locality_id BIGINT REFERENCES localities(id);
ALTER TABLE listings ADD COLUMN submitted_at TIMESTAMP;
ALTER TABLE listings ADD COLUMN published_at TIMESTAMP;

-- ACTIVE already means publicly discoverable. CLOSED and PENDING remain unmapped until reviewed.
UPDATE listings SET workflow_status = 'PUBLISHED'
WHERE status = 'ACTIVE';
UPDATE listings SET rental_mode = 'LONG_TERM_RENTAL'
WHERE listing_type = 'RENT';

ALTER TABLE listings ADD CONSTRAINT ck_listing_rental_mode
    CHECK (rental_mode IS NULL OR rental_mode IN ('LONG_TERM_RENTAL', 'SHORT_STAY'));
ALTER TABLE listings ADD CONSTRAINT ck_listing_workflow_status
    CHECK (workflow_status IS NULL OR workflow_status IN
        ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'CHANGES_REQUIRED', 'PUBLISHED', 'PAUSED', 'ARCHIVED'));

CREATE INDEX idx_listing_owner_workflow_updated
    ON listings (owner_user_id, workflow_status, updated_at DESC, id DESC)
    WHERE owner_user_id IS NOT NULL;
