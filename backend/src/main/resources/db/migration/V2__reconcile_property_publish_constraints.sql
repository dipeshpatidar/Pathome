-- Reconcile constraints created by older Hibernate entity versions.
-- Existing non-Flyway schemas are baselined at version 1, then receive this migration.

ALTER TABLE IF EXISTS listings
    ALTER COLUMN latitude DROP NOT NULL;

ALTER TABLE IF EXISTS listings
    ALTER COLUMN longitude DROP NOT NULL;

ALTER TABLE IF EXISTS property_media_assets
    DROP CONSTRAINT IF EXISTS property_media_assets_room_tag_check;

ALTER TABLE IF EXISTS property_media_assets
    ADD CONSTRAINT property_media_assets_room_tag_check
    CHECK (room_tag IN (
        'GENERAL',
        'LIVING_ROOM',
        'MASTER_BEDROOM',
        'BEDROOM',
        'KITCHEN',
        'BATHROOM',
        'BALCONY',
        'EXTERIOR',
        'AMENITIES',
        'FLOOR_PLAN'
    ));

ALTER TABLE IF EXISTS listings
    DROP CONSTRAINT IF EXISTS listings_property_type_check;

ALTER TABLE IF EXISTS listings
    ADD CONSTRAINT listings_property_type_check
    CHECK (property_type IN (
        'FLAT',
        'HOUSE',
        'PLOT',
        'LAND',
        'PENTHOUSE',
        'STUDIO',
        'SERVICED_APARTMENT'
    ));

DO $$
BEGIN
    IF to_regclass('parser_training_examples') IS NOT NULL THEN
        ALTER TABLE parser_training_examples
            ADD COLUMN IF NOT EXISTS created_by VARCHAR(254);
        UPDATE parser_training_examples
            SET created_by = 'legacy-import'
            WHERE created_by IS NULL;
        ALTER TABLE parser_training_examples
            ALTER COLUMN created_by SET NOT NULL;
    END IF;
END
$$;
