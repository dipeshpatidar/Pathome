ALTER TABLE property_visit_requests
    ADD COLUMN session_id BIGINT,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE property_visit_requests
    ADD CONSTRAINT chk_property_visit_request_status
        CHECK (status IN ('RECEIVED', 'COORDINATING', 'SCHEDULED', 'UNAVAILABLE', 'CANCELLED')),
    ADD CONSTRAINT uk_property_visit_request_id_session_listing UNIQUE (id, session_id, listing_id);

CREATE TABLE visit_sessions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES users(id),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SCHEDULED', 'STARTED', 'COMPLETED', 'EXPIRED', 'CANCELLED', 'NO_SHOW')),
    version BIGINT NOT NULL DEFAULT 0,
    city VARCHAR(160) NOT NULL CHECK (length(btrim(city)) > 0),
    area_name VARCHAR(160),
    canonical_locality_id BIGINT REFERENCES localities(id),
    scheduled_at TIMESTAMP WITH TIME ZONE,
    zone_id VARCHAR(64),
    representative_user_id BIGINT REFERENCES users(id),
    assigned_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_snapshot_minutes INTEGER CHECK (duration_snapshot_minutes IS NULL OR duration_snapshot_minutes >= 0),
    entitlement_consumed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_visit_session_schedule_zone
        CHECK ((scheduled_at IS NULL) = (zone_id IS NULL)),
    CONSTRAINT chk_visit_session_assignment
        CHECK ((representative_user_id IS NULL) = (assigned_at IS NULL)),
    CONSTRAINT chk_visit_session_expiry
        CHECK (started_at IS NULL OR expires_at IS NULL OR expires_at >= started_at),
    CONSTRAINT uk_visit_session_id_tenant UNIQUE (id, tenant_id)
);

ALTER TABLE property_visit_requests
    ADD CONSTRAINT fk_visit_request_session_tenant
        FOREIGN KEY (session_id, tenant_id) REFERENCES visit_sessions(id, tenant_id);

CREATE INDEX idx_property_visit_request_tenant_history
    ON property_visit_requests (tenant_id, created_at DESC, id DESC);
CREATE INDEX idx_visit_session_tenant_history
    ON visit_sessions (tenant_id, created_at DESC, id DESC);
CREATE INDEX idx_visit_session_status_schedule
    ON visit_sessions (status, scheduled_at);
CREATE INDEX idx_visit_session_rep_schedule
    ON visit_sessions (representative_user_id, status, scheduled_at);
DROP INDEX IF EXISTS idx_property_visit_request_tenant;

CREATE TABLE visit_session_items (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES visit_sessions(id),
    listing_id BIGINT NOT NULL REFERENCES listings(id),
    position INTEGER NOT NULL CHECK (position > 0),
    source_request_id BIGINT,
    confirmation_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (confirmation_status IN ('PENDING', 'CONFIRMED', 'UNAVAILABLE')),
    availability_confirmed_at TIMESTAMP WITH TIME ZONE,
    confirmed_by_user_id BIGINT REFERENCES users(id),
    lessor_confirmation_reference VARCHAR(255),
    removed_at TIMESTAMP WITH TIME ZONE,
    removal_reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_visit_session_item_listing UNIQUE (session_id, listing_id),
    CONSTRAINT uk_visit_session_item_position UNIQUE (session_id, position),
    CONSTRAINT chk_visit_session_item_confirmation
        CHECK ((availability_confirmed_at IS NULL) = (confirmed_by_user_id IS NULL)),
    CONSTRAINT chk_visit_session_item_confirmed_metadata
        CHECK (confirmation_status <> 'CONFIRMED' OR availability_confirmed_at IS NOT NULL),
    CONSTRAINT fk_visit_session_item_source_request_session
        FOREIGN KEY (source_request_id, session_id, listing_id)
        REFERENCES property_visit_requests(id, session_id, listing_id)
);

CREATE INDEX idx_visit_session_item_listing
    ON visit_session_items (listing_id, session_id);

CREATE TABLE visit_policy (
    id BIGINT PRIMARY KEY CHECK (id = 1),
    free_visit_sessions_default INTEGER NOT NULL CHECK (free_visit_sessions_default >= 0),
    max_visit_session_duration_minutes INTEGER NOT NULL CHECK (max_visit_session_duration_minutes > 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO visit_policy (id, free_visit_sessions_default, max_visit_session_duration_minutes)
VALUES (1, 5, 60);
