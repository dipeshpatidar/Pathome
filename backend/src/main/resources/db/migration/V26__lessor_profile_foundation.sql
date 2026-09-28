-- First-class supply-side lessor identity.
-- Decouples lessor contact and property ownership from login User accounts.

CREATE TABLE lessor_profiles (
    id BIGSERIAL PRIMARY KEY,
    linked_user_id BIGINT REFERENCES users(id),
    display_name VARCHAR(150) NOT NULL,
    mobile_number VARCHAR(30) NOT NULL,
    email VARCHAR(255),
    source_type VARCHAR(50) NOT NULL DEFAULT 'SELF_SERVICE',
    source_reference VARCHAR(255),
    created_by_user_id BIGINT REFERENCES users(id),
    claimed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_lessor_profile_source_type CHECK (
        source_type IN ('SELF_SERVICE', 'FIELD_TEAM', 'ADMIN', 'CRM', 'PARTNER', 'IMPORT')
    )
);

CREATE UNIQUE INDEX idx_lessor_profiles_linked_user
    ON lessor_profiles (linked_user_id)
    WHERE linked_user_id IS NOT NULL;

CREATE INDEX idx_lessor_profiles_mobile
    ON lessor_profiles (mobile_number);

CREATE INDEX idx_lessor_profiles_source
    ON lessor_profiles (source_type);

-- Additive relationship: associate listings with canonical lessor profile
ALTER TABLE listings ADD COLUMN lessor_profile_id BIGINT REFERENCES lessor_profiles(id);

CREATE INDEX idx_listings_lessor_profile_id
    ON listings (lessor_profile_id)
    WHERE lessor_profile_id IS NOT NULL;

-- Additive relationship: associate upload drafts with canonical lessor profile
ALTER TABLE property_upload_drafts ADD COLUMN lessor_profile_id BIGINT REFERENCES lessor_profiles(id);

CREATE INDEX idx_pud_lessor_profile_id
    ON property_upload_drafts (lessor_profile_id)
    WHERE lessor_profile_id IS NOT NULL;

-- Backfill: one distinct existing owner user -> one LessorProfile
INSERT INTO lessor_profiles (linked_user_id, display_name, mobile_number, email, source_type, created_at, updated_at)
SELECT u.id,
       COALESCE(NULLIF(TRIM(u.full_name), ''), 'Lessor ' || u.id),
       COALESCE(NULLIF(TRIM(u.phone_number), ''), '+91 9999999999'),
       u.email,
       'SELF_SERVICE',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM users u
WHERE (u.role = 'ROLE_LANDLORD'
       OR u.landlord_activated_at IS NOT NULL
       OR u.id IN (SELECT DISTINCT owner_user_id FROM listings WHERE owner_user_id IS NOT NULL))
ON CONFLICT (linked_user_id) DO NOTHING;

-- Backfill listings to reference the canonical profile of their owner user
UPDATE listings l
SET lessor_profile_id = lp.id
FROM lessor_profiles lp
WHERE l.owner_user_id = lp.linked_user_id
  AND l.lessor_profile_id IS NULL;

-- Backfill drafts to reference the canonical profile of their landlord user
UPDATE property_upload_drafts pud
SET lessor_profile_id = lp.id
FROM lessor_profiles lp
WHERE pud.landlord_user_id = lp.linked_user_id
  AND pud.lessor_profile_id IS NULL;
