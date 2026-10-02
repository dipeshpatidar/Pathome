ALTER TABLE property_visit_requests
    ADD COLUMN availability_start_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN availability_end_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN availability_zone_id VARCHAR(64),
    ADD COLUMN preferred_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT chk_visit_request_availability_window
        CHECK (
            (availability_start_at IS NULL AND availability_end_at IS NULL
                AND availability_zone_id IS NULL AND preferred_at IS NULL)
            OR (availability_start_at IS NOT NULL AND availability_end_at IS NOT NULL
                AND availability_zone_id IS NOT NULL
                AND availability_start_at < availability_end_at
                AND (preferred_at IS NULL OR preferred_at BETWEEN availability_start_at AND availability_end_at))
        );

CREATE INDEX idx_visit_request_scheduling_availability
    ON property_visit_requests (status, availability_start_at, availability_end_at)
    WHERE availability_start_at IS NOT NULL;

ALTER TABLE visit_session_items
    ADD COLUMN availability_start_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN availability_end_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN availability_zone_id VARCHAR(64),
    ADD COLUMN availability_source VARCHAR(24),
    ADD CONSTRAINT chk_visit_item_property_availability
        CHECK (
            (availability_start_at IS NULL AND availability_end_at IS NULL
                AND availability_zone_id IS NULL AND availability_source IS NULL)
            OR (availability_start_at IS NOT NULL AND availability_end_at IS NOT NULL
                AND availability_zone_id IS NOT NULL AND availability_source IN ('PHONE', 'WHATSAPP', 'DIRECT', 'OTHER')
                AND availability_start_at < availability_end_at
                AND confirmation_status = 'CONFIRMED'
                AND availability_confirmed_at IS NOT NULL AND confirmed_by_user_id IS NOT NULL)
        );

CREATE INDEX idx_visit_item_property_availability
    ON visit_session_items (availability_start_at, availability_end_at)
    WHERE availability_start_at IS NOT NULL AND removed_at IS NULL;

ALTER TABLE localities
    ADD CONSTRAINT uk_locality_city_id UNIQUE (city, id);

CREATE TABLE ground_executive_scheduling_profiles (
    employee_profile_id BIGINT PRIMARY KEY REFERENCES employee_profiles(id),
    scheduling_active BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_user_id BIGINT REFERENCES users(id)
);

CREATE INDEX idx_ge_scheduling_active_profile
    ON ground_executive_scheduling_profiles (employee_profile_id)
    WHERE scheduling_active = TRUE;

CREATE TABLE ground_executive_shifts (
    id BIGSERIAL PRIMARY KEY,
    employee_profile_id BIGINT NOT NULL REFERENCES ground_executive_scheduling_profiles(employee_profile_id),
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    zone_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    updated_by_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ge_shift_interval CHECK (starts_at < ends_at),
    CONSTRAINT ex_ge_shift_overlap EXCLUDE USING gist (
        employee_profile_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    )
);

CREATE INDEX idx_ge_shift_profile_time
    ON ground_executive_shifts (employee_profile_id, starts_at, ends_at);

CREATE TABLE ground_executive_unavailability (
    id BIGSERIAL PRIMARY KEY,
    employee_profile_id BIGINT NOT NULL REFERENCES ground_executive_scheduling_profiles(employee_profile_id),
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    zone_id VARCHAR(64) NOT NULL,
    interval_type VARCHAR(24) NOT NULL CHECK (interval_type IN ('BREAK', 'UNAVAILABLE')),
    version BIGINT NOT NULL DEFAULT 0,
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    updated_by_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ge_unavailability_interval CHECK (starts_at < ends_at),
    CONSTRAINT ex_ge_unavailability_overlap EXCLUDE USING gist (
        employee_profile_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    )
);

CREATE INDEX idx_ge_unavailability_profile_time
    ON ground_executive_unavailability (employee_profile_id, starts_at, ends_at);

CREATE TABLE ground_executive_coverage (
    id BIGSERIAL PRIMARY KEY,
    employee_profile_id BIGINT NOT NULL REFERENCES ground_executive_scheduling_profiles(employee_profile_id),
    city VARCHAR(160) NOT NULL CHECK (length(btrim(city)) > 0),
    locality_id BIGINT,
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ge_coverage_canonical_locality
        FOREIGN KEY (city, locality_id) REFERENCES localities(city, id)
);

CREATE UNIQUE INDEX uk_ge_coverage_citywide
    ON ground_executive_coverage (employee_profile_id, city)
    WHERE locality_id IS NULL;

CREATE UNIQUE INDEX uk_ge_coverage_locality
    ON ground_executive_coverage (employee_profile_id, locality_id)
    WHERE locality_id IS NOT NULL;

CREATE INDEX idx_ge_coverage_city
    ON ground_executive_coverage (city, employee_profile_id);

CREATE INDEX idx_ge_coverage_locality
    ON ground_executive_coverage (locality_id, employee_profile_id)
    WHERE locality_id IS NOT NULL;
