-- Landlord account identity is authoritative; a phone number is optional for this flow.
-- Existing admin creation still validates its own required owner phone input.
ALTER TABLE listings ALTER COLUMN owner_phone_number DROP NOT NULL;
