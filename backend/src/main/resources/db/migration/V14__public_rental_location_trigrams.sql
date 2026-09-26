-- pg_trgm is a trusted PostgreSQL extension (verified for the development role).
-- Keep V13 B-tree indexes for exact and prefix lookups; these support bounded typo fallback.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_listings_active_rent_city_trgm
    ON listings USING gin ((lower(btrim(city))) gin_trgm_ops)
    WHERE status = 'ACTIVE' AND listing_type = 'RENT';

CREATE INDEX IF NOT EXISTS idx_listings_active_rent_sector_trgm
    ON listings USING gin ((lower(btrim(sector))) gin_trgm_ops)
    WHERE status = 'ACTIVE' AND listing_type = 'RENT';
