-- B-tree expression indexes for the exact-city and locality-prefix predicates used by
-- public rental discovery. No pg_trgm extension or full-table wildcard search is added.
CREATE INDEX IF NOT EXISTS idx_listings_rental_city_locality_prefix
    ON listings (status, listing_type, (lower(btrim(city))), (lower(btrim(sector))) text_pattern_ops, id DESC);

CREATE INDEX IF NOT EXISTS idx_listings_rental_locality_prefix
    ON listings (status, listing_type, (lower(btrim(sector))) text_pattern_ops, id DESC);

CREATE INDEX IF NOT EXISTS idx_listings_rental_city_bhk
    ON listings (status, listing_type, (lower(btrim(city))), (upper(replace(bhk_count, ' ', ''))), id DESC);
