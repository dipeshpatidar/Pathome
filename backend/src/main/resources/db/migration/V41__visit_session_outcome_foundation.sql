ALTER TABLE visit_session_items
    ADD CONSTRAINT uk_visit_session_item_id_session UNIQUE (id, session_id);

CREATE TABLE visit_session_outcome_reports (
    session_id BIGINT PRIMARY KEY REFERENCES visit_sessions(id),
    state VARCHAR(24) NOT NULL
        CHECK (state IN ('OPEN', 'FINALIZED', 'LEGACY_UNRECORDED')),
    scope_source VARCHAR(24) NOT NULL
        CHECK (scope_source IN ('OTP_START', 'MIGRATED_ACTIVE', 'LEGACY_COMPLETED')),
    scope_captured_at TIMESTAMP WITH TIME ZONE,
    finalized_at TIMESTAMP WITH TIME ZONE,
    finalized_by_user_id BIGINT REFERENCES users(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_visit_outcome_report_state_metadata CHECK (
        (state = 'LEGACY_UNRECORDED' AND scope_source = 'LEGACY_COMPLETED'
            AND scope_captured_at IS NULL AND finalized_at IS NULL AND finalized_by_user_id IS NULL)
        OR
        (state = 'OPEN' AND scope_source IN ('OTP_START', 'MIGRATED_ACTIVE')
            AND scope_captured_at IS NOT NULL AND finalized_at IS NULL AND finalized_by_user_id IS NULL)
        OR
        (state = 'FINALIZED' AND scope_source IN ('OTP_START', 'MIGRATED_ACTIVE')
            AND scope_captured_at IS NOT NULL AND finalized_at IS NOT NULL AND finalized_by_user_id IS NOT NULL)
    )
);

CREATE INDEX idx_visit_outcome_report_open
    ON visit_session_outcome_reports (updated_at, session_id)
    WHERE state = 'OPEN';

CREATE TABLE visit_session_item_outcomes (
    item_id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES visit_session_outcome_reports(session_id),
    position_snapshot INTEGER NOT NULL CHECK (position_snapshot > 0),
    listing_id_snapshot BIGINT NOT NULL,
    title_snapshot TEXT NOT NULL,
    address_snapshot TEXT NOT NULL,
    city_snapshot TEXT NOT NULL,
    sector_snapshot TEXT NOT NULL,
    outcome_state VARCHAR(16) NOT NULL
        CHECK (outcome_state IN ('UNRECORDED', 'VISITED', 'SKIPPED')),
    skip_reason VARCHAR(32)
        CHECK (skip_reason IS NULL OR skip_reason IN (
            'PROPERTY_UNAVAILABLE', 'ACCESS_DENIED', 'TENANT_DECLINED',
            'TENANT_LEFT_EARLY', 'PROPERTY_MISMATCH', 'OTHER')),
    private_note VARCHAR(500),
    recorded_by_user_id BIGINT REFERENCES users(id),
    recorded_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_visit_item_outcome_session_item
        FOREIGN KEY (item_id, session_id) REFERENCES visit_session_items(id, session_id),
    CONSTRAINT chk_visit_item_outcome_metadata CHECK (
        (outcome_state = 'UNRECORDED' AND skip_reason IS NULL AND private_note IS NULL
            AND recorded_by_user_id IS NULL AND recorded_at IS NULL)
        OR
        (outcome_state = 'VISITED' AND skip_reason IS NULL
            AND recorded_by_user_id IS NOT NULL AND recorded_at IS NOT NULL)
        OR
        (outcome_state = 'SKIPPED' AND skip_reason IS NOT NULL
            AND recorded_by_user_id IS NOT NULL AND recorded_at IS NOT NULL)
    ),
    CONSTRAINT chk_visit_item_outcome_other_note CHECK (
        skip_reason <> 'OTHER' OR (private_note IS NOT NULL AND length(btrim(private_note)) > 0)
    )
);

CREATE INDEX idx_visit_item_outcome_report_position
    ON visit_session_item_outcomes (session_id, position_snapshot, item_id);

-- Completed before this migration has no trustworthy property-level result.
INSERT INTO visit_session_outcome_reports
    (session_id, state, scope_source, scope_captured_at, version, created_at, updated_at)
SELECT id, 'LEGACY_UNRECORDED', 'LEGACY_COMPLETED', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM visit_sessions
WHERE status = 'COMPLETED'
ON CONFLICT (session_id) DO NOTHING;

-- An already-running visit cannot recover its true START-time title snapshot, but its itinerary is
-- immutable after START. Mark the rollout-time scope explicitly and leave every outcome unrecorded.
INSERT INTO visit_session_outcome_reports
    (session_id, state, scope_source, scope_captured_at, version, created_at, updated_at)
SELECT id, 'OPEN', 'MIGRATED_ACTIVE', CURRENT_TIMESTAMP, 0,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM visit_sessions
WHERE status = 'STARTED'
ON CONFLICT (session_id) DO NOTHING;

INSERT INTO visit_session_item_outcomes
    (item_id, session_id, position_snapshot, listing_id_snapshot, title_snapshot,
     address_snapshot, city_snapshot, sector_snapshot, outcome_state, version, created_at, updated_at)
SELECT i.id, i.session_id, i.position, i.listing_id, l.title, l.address, l.city, l.sector,
       'UNRECORDED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM visit_sessions s
JOIN visit_session_outcome_reports r ON r.session_id = s.id AND r.scope_source = 'MIGRATED_ACTIVE'
JOIN visit_session_items i ON i.session_id = s.id
JOIN listings l ON l.id = i.listing_id
WHERE s.status = 'STARTED'
  AND i.removed_at IS NULL
  AND i.confirmation_status = 'CONFIRMED'
ON CONFLICT (item_id) DO NOTHING;

INSERT INTO visit_execution_events(session_id, actor_user_id, event_type, reason_code, metadata, idempotency_key)
SELECT r.session_id, NULL, 'OUTCOME_SCOPE_CAPTURED', 'MIGRATED_ACTIVE_SCOPE',
       jsonb_build_object('scopeSource', 'MIGRATED_ACTIVE', 'itemCount',
           (SELECT count(*) FROM visit_session_item_outcomes o WHERE o.session_id = r.session_id)),
       'OUTCOME_SCOPE_MIGRATED:' || r.session_id
FROM visit_session_outcome_reports r
WHERE r.scope_source = 'MIGRATED_ACTIVE'
ON CONFLICT (idempotency_key) DO NOTHING;
