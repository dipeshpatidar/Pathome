-- Owner-approved Gate I V60 correction.
-- Replace only the draft ownership CHECK with a total SQL predicate.

ALTER TABLE property_upload_drafts
    DROP CONSTRAINT ck_draft_one_owner;

ALTER TABLE property_upload_drafts
    ADD CONSTRAINT ck_draft_one_owner CHECK (
        (
            staff_maker_user_id IS NOT NULL
            AND lessor_profile_id IS NOT NULL
            AND revision_mode IS NOT DISTINCT FROM 'STAFF_DIRECT'
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
