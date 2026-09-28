-- Prepare permanent account identity for a future verified mobile login.
-- Legacy users.phone_number and lessor_profiles.mobile_number remain contact data.
ALTER TABLE users ADD COLUMN mobile_number_normalized VARCHAR(16);
ALTER TABLE users ADD COLUMN mobile_verified_at TIMESTAMP WITH TIME ZONE;

-- Existing email/password signup still requires email in AuthController.
-- PostgreSQL's existing unique email constraint continues to allow only one
-- non-null copy of each email while permitting future mobile-only accounts.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

CREATE UNIQUE INDEX uk_users_mobile_number_normalized
    ON users (mobile_number_normalized)
    WHERE mobile_number_normalized IS NOT NULL;

ALTER TABLE users ADD CONSTRAINT ck_users_mobile_number_e164
    CHECK (mobile_number_normalized IS NULL
        OR mobile_number_normalized ~ '^\+[1-9][0-9]{1,14}$');

-- A canonical login number must be verified before it is assigned.
-- Pending challenges belong outside users and are not represented here.
ALTER TABLE users ADD CONSTRAINT ck_users_mobile_verified_pair
    CHECK ((mobile_number_normalized IS NULL) = (mobile_verified_at IS NULL));
