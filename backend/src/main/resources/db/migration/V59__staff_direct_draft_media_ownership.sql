-- Owner-approved Gate I V59.
-- Admit the existing STAFF_DIRECT revision shape without changing legacy ownership.

ALTER TABLE property_upload_drafts
    DROP CONSTRAINT ck_draft_one_owner;

ALTER TABLE property_upload_drafts
    ADD CONSTRAINT ck_draft_one_owner CHECK (
        (
            staff_maker_user_id IS NOT NULL
            AND lessor_profile_id IS NOT NULL
            AND revision_mode = 'STAFF_DIRECT'
            AND admin_id IS NULL
            AND landlord_user_id IS NULL
            AND guest_token_hash IS NULL
            AND guest_expires_at IS NULL
        )
        OR
        (
            staff_maker_user_id IS NULL
            AND revision_mode IS DISTINCT FROM 'STAFF_DIRECT'
            AND (
                (CASE WHEN admin_id IS NOT NULL THEN 1 ELSE 0 END) +
                (CASE WHEN landlord_user_id IS NOT NULL THEN 1 ELSE 0 END) +
                (CASE WHEN guest_token_hash IS NOT NULL THEN 1 ELSE 0 END)
            ) = 1
            AND (guest_token_hash IS NULL OR guest_expires_at IS NOT NULL)
        )
    );

ALTER TABLE property_draft_media
    ADD COLUMN staff_maker_user_id BIGINT;

ALTER TABLE property_draft_media
    ADD CONSTRAINT fk_pdm_staff_maker
    FOREIGN KEY (staff_maker_user_id) REFERENCES users(id) ON DELETE RESTRICT;

ALTER TABLE property_draft_media
    DROP CONSTRAINT ck_media_one_owner;

ALTER TABLE property_draft_media
    ADD CONSTRAINT ck_media_one_owner CHECK (
        (
            staff_maker_user_id IS NOT NULL
            AND admin_id IS NULL
            AND landlord_user_id IS NULL
            AND guest_owned = FALSE
        )
        OR
        (
            staff_maker_user_id IS NULL
            AND (
                (CASE WHEN admin_id IS NOT NULL THEN 1 ELSE 0 END) +
                (CASE WHEN landlord_user_id IS NOT NULL THEN 1 ELSE 0 END) +
                (CASE WHEN guest_owned THEN 1 ELSE 0 END)
            ) = 1
        )
    );

CREATE INDEX idx_pdm_staff_draft_order
    ON property_draft_media (staff_maker_user_id, draft_id, sort_order, id)
    WHERE staff_maker_user_id IS NOT NULL;
