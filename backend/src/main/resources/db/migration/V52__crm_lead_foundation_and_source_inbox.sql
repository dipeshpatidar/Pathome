-- Approved Batch D D-B/D6 additive CRM foundation.
-- Existing lead_routing_queue rows and identifiers are preserved. Unknown legacy facts remain unknown.

ALTER TABLE lead_routing_queue
    ALTER COLUMN tenant_name DROP NOT NULL,
    ALTER COLUMN phone_number DROP NOT NULL,
    ALTER COLUMN target_sector DROP NOT NULL;

ALTER TABLE lead_routing_queue
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN supported_city_id BIGINT,
    ADD COLUMN operating_team_id BIGINT,
    ADD COLUMN owner_user_id BIGINT,
    ADD COLUMN linked_user_id BIGINT,
    ADD COLUMN contact_phone_normalized VARCHAR(16),
    ADD COLUMN customer_said TEXT,
    ADD COLUMN intent VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED',
    ADD COLUMN property_type VARCHAR(32),
    ADD COLUMN bhk VARCHAR(32),
    ADD COLUMN budget_min NUMERIC(14, 2),
    ADD COLUMN budget_max NUMERIC(14, 2),
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    ADD COLUMN target_move_in_date DATE,
    ADD COLUMN furnishing VARCHAR(40),
    ADD COLUMN tenant_profile VARCHAR(64),
    ADD COLUMN parking_required BOOLEAN,
    ADD COLUMN pets_allowed BOOLEAN,
    ADD COLUMN lift_required BOOLEAN,
    ADD COLUMN floor_requirement VARCHAR(80),
    ADD COLUMN follow_up_at TIMESTAMPTZ,
    ADD COLUMN follow_up_zone_id VARCHAR(64),
    ADD COLUMN legacy_provenance VARCHAR(32);

UPDATE lead_routing_queue
SET legacy_provenance = 'LEGACY_UNVERIFIED'
WHERE legacy_provenance IS NULL;

ALTER TABLE lead_routing_queue
    ALTER COLUMN legacy_provenance SET DEFAULT 'VERIFIED_MANUAL',
    ALTER COLUMN legacy_provenance SET NOT NULL,
    ADD CONSTRAINT fk_lead_routing_queue_supported_city
        FOREIGN KEY (supported_city_id) REFERENCES supported_cities(id),
    ADD CONSTRAINT fk_lead_routing_queue_operating_team
        FOREIGN KEY (operating_team_id) REFERENCES operating_teams(id),
    ADD CONSTRAINT fk_lead_routing_queue_owner
        FOREIGN KEY (owner_user_id) REFERENCES users(id),
    ADD CONSTRAINT fk_lead_routing_queue_linked_user
        FOREIGN KEY (linked_user_id) REFERENCES users(id),
    ADD CONSTRAINT ck_lead_budget_min_nonnegative
        CHECK (budget_min IS NULL OR budget_min >= 0),
    ADD CONSTRAINT ck_lead_budget_max_nonnegative
        CHECK (budget_max IS NULL OR budget_max >= 0),
    ADD CONSTRAINT ck_lead_budget_order
        CHECK (budget_min IS NULL OR budget_max IS NULL OR budget_min <= budget_max),
    ADD CONSTRAINT ck_lead_follow_up_shape
        CHECK ((follow_up_at IS NULL AND follow_up_zone_id IS NULL)
            OR (follow_up_at IS NOT NULL AND follow_up_zone_id IS NOT NULL)),
    ADD CONSTRAINT ck_lead_intent
        CHECK (intent IN ('RENT', 'BUY', 'UNSPECIFIED')),
    ADD CONSTRAINT ck_lead_provenance
        CHECK (legacy_provenance IN ('VERIFIED_MANUAL', 'LEGACY_UNVERIFIED'));

CREATE INDEX idx_lead_city_status_created
    ON lead_routing_queue(supported_city_id, status, created_at DESC, id DESC);
CREATE INDEX idx_lead_team_owner_follow_up
    ON lead_routing_queue(operating_team_id, owner_user_id, status, follow_up_at, id);
CREATE INDEX idx_lead_normalized_contact
    ON lead_routing_queue(contact_phone_normalized)
    WHERE contact_phone_normalized IS NOT NULL;

CREATE TABLE lead_preferred_localities (
    id BIGSERIAL PRIMARY KEY,
    lead_id BIGINT NOT NULL REFERENCES lead_routing_queue(id) ON DELETE CASCADE,
    locality_id BIGINT NOT NULL REFERENCES localities(id),
    CONSTRAINT uk_lead_preferred_locality UNIQUE (lead_id, locality_id)
);

CREATE INDEX idx_lead_preferred_locality
    ON lead_preferred_localities(locality_id, lead_id);

CREATE TABLE lead_source_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    external_event_id VARCHAR(160) NOT NULL,
    event_kind VARCHAR(32) NOT NULL,
    source_channel VARCHAR(64),
    campaign_ref VARCHAR(200),
    referral_ref VARCHAR(200),
    payload_hash VARCHAR(64) NOT NULL,
    lead_id BIGINT REFERENCES lead_routing_queue(id),
    processing_state VARCHAR(24) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    lease_token UUID,
    error_code VARCHAR(64),
    received_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_lead_source_provider_event
        UNIQUE (provider, provider_account_id, external_event_id),
    CONSTRAINT ck_lead_source_state
        CHECK (processing_state IN ('PENDING', 'PROCESSING', 'PROCESSED', 'QUARANTINED')),
    CONSTRAINT ck_lead_source_attempts
        CHECK (attempts >= 0)
);

CREATE INDEX idx_lead_source_pending
    ON lead_source_events(processing_state, available_at, id);

CREATE TABLE lead_activities (
    id BIGSERIAL PRIMARY KEY,
    lead_id BIGINT NOT NULL REFERENCES lead_routing_queue(id) ON DELETE CASCADE,
    actor_user_id BIGINT REFERENCES users(id),
    activity_type VARCHAR(48) NOT NULL,
    source_event_id UUID REFERENCES lead_source_events(id),
    occurred_at TIMESTAMPTZ NOT NULL,
    note TEXT,
    CONSTRAINT ck_lead_activity_note_length
        CHECK (note IS NULL OR char_length(note) <= 4000)
);

CREATE INDEX idx_lead_activity_timeline
    ON lead_activities(lead_id, occurred_at DESC, id DESC);

CREATE TABLE lead_command_receipts (
    id UUID PRIMARY KEY,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    operation_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    result_reference VARCHAR(160),
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_lead_command_receipt
        UNIQUE (actor_user_id, operation_id, action)
);

CREATE INDEX idx_lead_command_completed
    ON lead_command_receipts(completed_at);
