-- U04 Activity History keyset pagination.
-- Authored only. Flyway execution remains separately gated.

CREATE INDEX idx_publication_listing_time_id
    ON property_publication_versions (listing_id, published_at DESC, id DESC);
