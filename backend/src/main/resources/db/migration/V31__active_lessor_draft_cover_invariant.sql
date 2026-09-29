-- Historical submitted drafts can contain ambiguous duplicate cover flags. Preserve them,
-- while checking the one-cover editing invariant for authenticated drafts that are DRAFT.
-- A deferred constraint trigger allows an A -> B cover replacement within one transaction.
CREATE OR REPLACE FUNCTION pathome_assert_active_lessor_draft_cover_limit(target_draft_id VARCHAR)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    draft_status VARCHAR(30);
    cover_count BIGINT;
BEGIN
    SELECT status
      INTO draft_status
      FROM property_upload_drafts
     WHERE draft_id = target_draft_id
     FOR UPDATE;

    IF draft_status = 'DRAFT' THEN
        SELECT COUNT(*)
          INTO cover_count
          FROM property_draft_media
         WHERE draft_id = target_draft_id
           AND is_cover IS TRUE
           AND guest_owned IS FALSE
           AND landlord_user_id IS NOT NULL;

        IF cover_count > 1 THEN
            RAISE EXCEPTION 'An active authenticated draft may have at most one cover photo.'
                USING ERRCODE = '23505',
                      CONSTRAINT = 'uq_property_draft_media_one_cover_per_active_authenticated_draft';
        END IF;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION pathome_check_active_lessor_draft_cover_limit()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pathome_assert_active_lessor_draft_cover_limit(NEW.draft_id);
    IF TG_OP = 'UPDATE' AND OLD.draft_id IS DISTINCT FROM NEW.draft_id THEN
        PERFORM pathome_assert_active_lessor_draft_cover_limit(OLD.draft_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_pdm_active_lessor_draft_cover_limit
AFTER INSERT OR UPDATE ON property_draft_media
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION pathome_check_active_lessor_draft_cover_limit();

CREATE OR REPLACE FUNCTION pathome_check_active_lessor_draft_cover_limit_on_status()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'DRAFT' THEN
        PERFORM pathome_assert_active_lessor_draft_cover_limit(NEW.draft_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_pud_active_lessor_draft_cover_limit
AFTER UPDATE ON property_upload_drafts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION pathome_check_active_lessor_draft_cover_limit_on_status();
