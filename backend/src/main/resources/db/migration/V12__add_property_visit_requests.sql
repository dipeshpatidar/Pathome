CREATE TABLE IF NOT EXISTS property_visit_requests (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES users(id),
    listing_id BIGINT NOT NULL REFERENCES listings(id),
    budget_min NUMERIC(14, 2),
    budget_max NUMERIC(14, 2),
    preferred_areas VARCHAR(500),
    move_in_timing VARCHAR(160),
    preferred_visit_timing VARCHAR(240),
    tenant_note VARCHAR(2000),
    status VARCHAR(40) NOT NULL DEFAULT 'RECEIVED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_property_visit_request_tenant_listing UNIQUE (tenant_id, listing_id)
);

CREATE INDEX IF NOT EXISTS idx_property_visit_request_tenant ON property_visit_requests (tenant_id);
CREATE INDEX IF NOT EXISTS idx_property_visit_request_listing ON property_visit_requests (listing_id);
CREATE INDEX IF NOT EXISTS idx_property_visit_request_status_created ON property_visit_requests (status, created_at);

CREATE INDEX IF NOT EXISTS idx_listing_status_city_sector ON listings (status, city, sector);
