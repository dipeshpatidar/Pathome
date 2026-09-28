-- Unresolved landlord submissions remain private until an administrator links a canonical locality.
ALTER TABLE listings ADD COLUMN location_resolution VARCHAR(24);
ALTER TABLE listings ADD COLUMN location_provider VARCHAR(40);
ALTER TABLE listings ADD COLUMN location_provider_place_id VARCHAR(160);
ALTER TABLE listings ADD CONSTRAINT ck_listing_location_resolution
    CHECK (location_resolution IS NULL OR location_resolution IN
        ('CANONICAL', 'EXTERNAL_RESOLVED', 'MANUAL_PENDING'));
UPDATE listings SET location_resolution = 'CANONICAL' WHERE canonical_locality_id IS NOT NULL;
