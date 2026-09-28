-- LessorProfile truthfulness hardening.
-- Addresses post-V26/V27 review findings without touching applied migrations.

-- FINDING 1: display_name must be nullable.
-- Profiles for incomplete users, FIELD_TEAM, CRM, IMPORT etc. legitimately have
-- no name yet. Do not fabricate a name; let display_name be NULL until a real
-- name is provided.
ALTER TABLE lessor_profiles
    ALTER COLUMN display_name DROP NOT NULL;

-- Name provenance safety:
-- Do NOT destructively rewrite display_name in the database.
-- Mutable users.full_name cannot reliably prove historic provenance:
-- user full_name may have changed after V26, and a genuine profile name
-- matching "Lessor <id>" must never be erased.
-- Instead, application validation treats known legacy generated-placeholder
-- semantics ("Lessor <digits>") as incomplete, preventing submission without
-- a genuine usable name while preserving database integrity.

-- FINDING 2: Restore genuine +91 9999999999 that V27 wrongly zeroed.
-- V27 unconditionally set mobile_number = NULL where it found '+91 9999999999'.
-- V26 originally trimmed source phones: NULLIF(TRIM(u.phone_number), '').
-- Restore only where trimmed source User phone proves this was real source data:
--   SELF_SERVICE profile whose mobile_number is currently NULL
--   AND the linked User's trimmed phone_number is exactly '+91 9999999999'
-- The old V26 fabricated sentinel was written ONLY when the User had no phone,
-- so if TRIM(User.phone_number) contains this value it was real source data.
UPDATE lessor_profiles lp
SET    mobile_number = TRIM(u.phone_number),
       updated_at    = CURRENT_TIMESTAMP
FROM   users u
WHERE  lp.linked_user_id  = u.id
  AND  lp.source_type     = 'SELF_SERVICE'
  AND  lp.mobile_number   IS NULL
  AND  TRIM(u.phone_number) = '+91 9999999999';
