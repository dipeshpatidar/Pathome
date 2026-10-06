ALTER TABLE operating_teams
    ADD CONSTRAINT uk_operating_teams_id_city UNIQUE (id, city_id);

ALTER TABLE property_visit_requests
    ADD COLUMN supported_city_id BIGINT,
    ADD COLUMN operating_team_id BIGINT,
    ADD COLUMN coordinator_user_id BIGINT,
    ADD COLUMN operational_scope_ready BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE visit_sessions
    ADD COLUMN supported_city_id BIGINT,
    ADD COLUMN operating_team_id BIGINT,
    ADD COLUMN coordinator_user_id BIGINT,
    ADD COLUMN operational_scope_ready BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE property_visit_requests
    ADD CONSTRAINT fk_pvr_supported_city FOREIGN KEY (supported_city_id) REFERENCES supported_cities(id),
    ADD CONSTRAINT fk_pvr_operating_team_city FOREIGN KEY (operating_team_id, supported_city_id)
        REFERENCES operating_teams(id, city_id),
    ADD CONSTRAINT fk_pvr_coordinator_user FOREIGN KEY (coordinator_user_id) REFERENCES users(id),
    ADD CONSTRAINT ck_pvr_operational_scope_shape CHECK (
        (operational_scope_ready = FALSE OR supported_city_id IS NOT NULL)
        AND (operating_team_id IS NULL OR supported_city_id IS NOT NULL)
        AND (coordinator_user_id IS NULL OR operating_team_id IS NOT NULL)
    );

ALTER TABLE visit_sessions
    ADD CONSTRAINT fk_vs_supported_city FOREIGN KEY (supported_city_id) REFERENCES supported_cities(id),
    ADD CONSTRAINT fk_vs_operating_team_city FOREIGN KEY (operating_team_id, supported_city_id)
        REFERENCES operating_teams(id, city_id),
    ADD CONSTRAINT fk_vs_coordinator_user FOREIGN KEY (coordinator_user_id) REFERENCES users(id),
    ADD CONSTRAINT ck_vs_operational_scope_shape CHECK (
        (operational_scope_ready = FALSE OR supported_city_id IS NOT NULL)
        AND (operating_team_id IS NULL OR supported_city_id IS NOT NULL)
        AND (coordinator_user_id IS NULL OR operating_team_id IS NOT NULL)
    );

CREATE INDEX idx_pvr_city_intake_order
    ON property_visit_requests (supported_city_id, created_at DESC, id DESC)
    WHERE operational_scope_ready = TRUE AND operating_team_id IS NULL
      AND coordinator_user_id IS NULL AND session_id IS NULL;
CREATE INDEX idx_vs_team_unclaimed_order
    ON visit_sessions (operating_team_id, created_at, id)
    WHERE operational_scope_ready = TRUE AND coordinator_user_id IS NULL;
CREATE INDEX idx_vs_coordinator_work_order
    ON visit_sessions (coordinator_user_id, status, updated_at, id)
    WHERE coordinator_user_id IS NOT NULL;
CREATE INDEX idx_vs_team_workload_order
    ON visit_sessions (operating_team_id, status, updated_at, id)
    WHERE operating_team_id IS NOT NULL;

CREATE OR REPLACE FUNCTION enforce_operational_ownership_mirror()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_session_id BIGINT;
    mismatch BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'property_visit_requests' THEN
        affected_session_id := NEW.session_id;
        IF affected_session_id IS NULL THEN
            RETURN NULL;
        END IF;
        SELECT EXISTS (
            SELECT 1
            FROM property_visit_requests r
            JOIN visit_sessions s ON s.id = r.session_id
            WHERE r.session_id = affected_session_id
              AND (r.supported_city_id IS DISTINCT FROM s.supported_city_id
                   OR r.operating_team_id IS DISTINCT FROM s.operating_team_id
                   OR r.coordinator_user_id IS DISTINCT FROM s.coordinator_user_id
                   OR r.operational_scope_ready IS DISTINCT FROM s.operational_scope_ready)
        ) INTO mismatch;
    ELSE
        affected_session_id := NEW.id;
        SELECT EXISTS (
            SELECT 1
            FROM property_visit_requests r
            JOIN visit_sessions s ON s.id = r.session_id
            WHERE r.session_id = affected_session_id
              AND (r.supported_city_id IS DISTINCT FROM s.supported_city_id
                   OR r.operating_team_id IS DISTINCT FROM s.operating_team_id
                   OR r.coordinator_user_id IS DISTINCT FROM s.coordinator_user_id
                   OR r.operational_scope_ready IS DISTINCT FROM s.operational_scope_ready)
        ) INTO mismatch;
        IF NOT EXISTS (SELECT 1 FROM visit_sessions WHERE id = affected_session_id) THEN
            mismatch := TRUE;
        END IF;
    END IF;

    IF mismatch THEN
        RAISE EXCEPTION 'linked request/session operational ownership differs'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_operational_ownership_mirror';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_pvr_ownership_mirror
AFTER INSERT OR UPDATE ON property_visit_requests
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_operational_ownership_mirror();

CREATE CONSTRAINT TRIGGER ct_vs_ownership_mirror
AFTER INSERT OR UPDATE ON visit_sessions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_operational_ownership_mirror();
