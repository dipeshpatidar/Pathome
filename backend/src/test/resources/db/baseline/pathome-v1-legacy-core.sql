-- Supported deployment baseline: the pre-Flyway core schema recorded at version 1.
-- This fixture models the legacy tables/columns consumed or altered by V2–V41.
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    full_name VARCHAR(255),
    phone_number VARCHAR(40),
    google_sub VARCHAR(255),
    role VARCHAR(40) NOT NULL DEFAULT 'ROLE_TENANT',
    free_visits_remaining INTEGER NOT NULL DEFAULT 5,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE listings (
    id BIGSERIAL PRIMARY KEY,
    listing_category VARCHAR(31) NOT NULL DEFAULT 'RENTAL',
    title VARCHAR(255) NOT NULL,
    description TEXT,
    address TEXT NOT NULL,
    city VARCHAR(160) NOT NULL,
    sector VARCHAR(160) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    listing_type VARCHAR(32) NOT NULL DEFAULT 'RENT',
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    property_type VARCHAR(40) NOT NULL DEFAULT 'FLAT',
    bhk_count VARCHAR(16),
    furnishing_status VARCHAR(255),
    vastu_facing VARCHAR(255),
    amenities TEXT,
    total_area_sq_ft DOUBLE PRECISION,
    media_gallery_urls TEXT,
    owner_phone_number VARCHAR(40) NOT NULL DEFAULT 'legacy-contact',
    owner_name VARCHAR(255),
    bathroom_count INTEGER,
    colony VARCHAR(255),
    state VARCHAR(255),
    pincode VARCHAR(255),
    landmark VARCHAR(255),
    possession_date_text VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rental_details (
    id BIGINT PRIMARY KEY REFERENCES listings(id),
    monthly_rent NUMERIC(14,2) NOT NULL DEFAULT 0,
    security_deposit NUMERIC(14,2) NOT NULL DEFAULT 0,
    maintenance_charge NUMERIC(14,2),
    brokerage_amount NUMERIC(14,2),
    brokerage_days INTEGER,
    security_deposit_months INTEGER,
    bachelor_allowed BOOLEAN DEFAULT TRUE,
    available_from TIMESTAMP
);

CREATE TABLE sale_details (
    id BIGINT PRIMARY KEY REFERENCES listings(id),
    asking_price NUMERIC(14,2) NOT NULL DEFAULT 0,
    price_per_sq_ft NUMERIC(14,2),
    zoning_attributes VARCHAR(255),
    boundary_wall BOOLEAN,
    ownership_type VARCHAR(100)
);

CREATE TABLE property_media_assets (
    id BIGSERIAL PRIMARY KEY,
    listing_id BIGINT NOT NULL REFERENCES listings(id),
    media_type VARCHAR(30) NOT NULL DEFAULT 'IMAGE',
    room_tag VARCHAR(40),
    caption TEXT,
    is_primary_cover BOOLEAN NOT NULL DEFAULT FALSE,
    media_url VARCHAR(1000),
    cloudinary_public_id VARCHAR(300),
    verification_status VARCHAR(40),
    sector VARCHAR(160),
    city VARCHAR(160),
    price_tag VARCHAR(80),
    vastu_facing VARCHAR(80),
    uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT property_media_assets_room_tag_check CHECK (room_tag IS NULL OR room_tag IN (
        'GENERAL', 'LIVING_ROOM', 'MASTER_BEDROOM', 'BEDROOM', 'KITCHEN', 'BATHROOM',
        'BALCONY', 'EXTERIOR', 'AMENITIES', 'FLOOR_PLAN'))
);

CREATE TABLE system_notifications (
    id BIGSERIAL PRIMARY KEY,
    recipient_user_id VARCHAR(120),
    target_role VARCHAR(255) NOT NULL DEFAULT 'ALL',
    title VARCHAR(255) NOT NULL DEFAULT '',
    message TEXT,
    details TEXT,
    category VARCHAR(255) NOT NULL DEFAULT 'SYSTEM',
    type VARCHAR(255) NOT NULL DEFAULT 'info',
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE localities (
    id BIGSERIAL PRIMARY KEY,
    city VARCHAR(255) NOT NULL,
    sector_name VARCHAR(255) NOT NULL,
    aliases VARCHAR(255),
    demand_score INTEGER,
    avg_rent_amount DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_localities_city_sector UNIQUE (city, sector_name)
);

CREATE TABLE employee_profiles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id),
    role_type VARCHAR(32) NOT NULL,
    assigned_sector VARCHAR(160),
    base_salary NUMERIC(14,2) NOT NULL DEFAULT 0,
    closed_deals_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE lead_routing_queue (
    id BIGSERIAL PRIMARY KEY,
    tenant_name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(80) NOT NULL,
    target_sector VARCHAR(160) NOT NULL,
    budget NUMERIC(14,2),
    status VARCHAR(40) NOT NULL DEFAULT 'ASSIGNED',
    assigned_employee_id BIGINT REFERENCES employee_profiles(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX idx_lead_status_sector ON lead_routing_queue(status, target_sector);
CREATE INDEX idx_lead_employee ON lead_routing_queue(assigned_employee_id);

CREATE TABLE parser_training_examples (
    id BIGSERIAL PRIMARY KEY
);
