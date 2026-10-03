-- Package 3 visit execution foundation. V33-V38 remain immutable.
ALTER TABLE visit_sessions
    ADD COLUMN arrived_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN execution_duration_snapshot_minutes INTEGER,
    ADD COLUMN expected_end_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN tenant_eta_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN tenant_confirmation_state VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN tenant_confirmed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN tenant_confirmed_by_user_id BIGINT REFERENCES users(id),
    ADD COLUMN repair_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    ADD COLUMN repair_operation_id UUID,
    ADD COLUMN provisional_no_show_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN no_show_dispute_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN start_operation_id UUID,
    ADD COLUMN needs_more_time_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN execution_state_changed_at TIMESTAMP WITH TIME ZONE;

-- The V33 status check must be widened before classifying legacy STARTED rows.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
        WHERE conrelid='visit_sessions'::regclass AND contype='c'
          AND pg_get_constraintdef(oid) ILIKE '%status%'
          AND pg_get_constraintdef(oid) ILIKE '%DRAFT%'
    LOOP
        EXECUTE format('ALTER TABLE visit_sessions DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

-- Pre-Package-3 STARTED rows have no verifiable OTP or entitlement history. Preserve
-- their timestamps and reservation, but require human review rather than inventing
-- completion/consumption or treating two historical rows as simultaneous active work.
UPDATE visit_sessions
SET status = 'REPAIR_REQUIRED',
    repair_state = 'REQUIRED',
    tenant_confirmation_state = 'PENDING',
    execution_state_changed_at = CURRENT_TIMESTAMP,
    execution_duration_snapshot_minutes = CASE WHEN started_at IS NULL THEN NULL ELSE duration_snapshot_minutes END,
    expected_end_at = CASE WHEN started_at IS NULL THEN NULL
        ELSE GREATEST(reserved_end_at, started_at + (duration_snapshot_minutes * INTERVAL '1 minute')) END
WHERE status = 'STARTED';

ALTER TABLE visit_sessions
    ADD CONSTRAINT chk_visit_execution_duration CHECK (
        execution_duration_snapshot_minutes IS NULL OR execution_duration_snapshot_minutes > 0),
    ADD CONSTRAINT chk_visit_execution_expected_end CHECK (
        (started_at IS NULL AND expected_end_at IS NULL AND execution_duration_snapshot_minutes IS NULL)
        OR (started_at IS NOT NULL AND expected_end_at IS NOT NULL
            AND execution_duration_snapshot_minutes > 0 AND expected_end_at > started_at)),
    ADD CONSTRAINT chk_visit_tenant_confirmation CHECK (
        tenant_confirmation_state IN ('NOT_REQUIRED', 'PENDING', 'CONFIRMED', 'REJECTED')),
    ADD CONSTRAINT chk_visit_repair_state CHECK (
        repair_state IN ('NONE', 'PROPOSED', 'REQUIRED'));

-- Extend the frozen session state vocabulary and replace only the V36 duration equality check.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'visit_sessions'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%status%'
          AND pg_get_constraintdef(oid) ILIKE '%DRAFT%'
    LOOP
        EXECUTE format('ALTER TABLE visit_sessions DROP CONSTRAINT %I', c.conname);
    END LOOP;
    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'visit_sessions'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%duration_snapshot_minutes%'
          AND pg_get_constraintdef(oid) ILIKE '%reserved_end_at%'
    LOOP
        EXECUTE format('ALTER TABLE visit_sessions DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE visit_sessions
    ADD CONSTRAINT chk_visit_session_status CHECK (status IN (
        'DRAFT', 'SCHEDULED', 'STARTED', 'COMPLETED', 'EXPIRED', 'CANCELLED',
        'NO_SHOW', 'PROVISIONAL_NO_SHOW', 'REPAIR_REQUIRED', 'INTERRUPTED')),
    ADD CONSTRAINT chk_visit_session_staffed_booking CHECK (
        status NOT IN ('SCHEDULED', 'STARTED') OR (
            scheduled_at IS NOT NULL AND zone_id IS NOT NULL
            AND representative_user_id IS NOT NULL AND assigned_at IS NOT NULL
            AND duration_snapshot_minutes > 0 AND reserved_end_at IS NOT NULL
            AND reserved_end_at > scheduled_at)),
    ADD CONSTRAINT chk_visit_session_planned_reservation CHECK (
        status <> 'SCHEDULED' OR reserved_end_at = scheduled_at
            + (duration_snapshot_minutes * INTERVAL '1 minute')),
    ADD CONSTRAINT chk_visit_session_started_reservation CHECK (
        status <> 'STARTED' OR (started_at IS NOT NULL AND expected_end_at IS NOT NULL
            AND execution_duration_snapshot_minutes > 0 AND reserved_end_at = expected_end_at));

CREATE UNIQUE INDEX uk_visit_session_one_active_per_ge
    ON visit_sessions (representative_user_id)
    WHERE status = 'STARTED';
CREATE INDEX idx_visit_session_repair_queue
    ON visit_sessions (updated_at, id) WHERE repair_state IN ('PROPOSED', 'REQUIRED');
CREATE INDEX idx_visit_session_execution_overrun
    ON visit_sessions (expected_end_at, id) WHERE status = 'STARTED';

CREATE TABLE tenant_visit_entitlement_accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id),
    available_credits INTEGER NOT NULL CHECK (available_credits >= 0),
    reserved_credits INTEGER NOT NULL DEFAULT 0 CHECK (reserved_credits >= 0),
    reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_visit_entitlement_account_user ON tenant_visit_entitlement_accounts(user_id);

CREATE TABLE visit_entitlement_ledger (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES tenant_visit_entitlement_accounts(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    session_id BIGINT REFERENCES visit_sessions(id),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'INITIAL_GRANT', 'RESERVE', 'RELEASE', 'CONSUME', 'RESTORE',
        'FORFEIT_NO_SHOW', 'ADMIN_ADJUSTMENT', 'OE_OPERATIONAL_RESTORE')),
    available_delta INTEGER NOT NULL,
    reserved_delta INTEGER NOT NULL,
    actor_user_id BIGINT REFERENCES users(id),
    reason_code VARCHAR(64),
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_visit_entitlement_ledger_delta CHECK (
        available_delta <> 0 OR reserved_delta <> 0)
);
CREATE INDEX idx_visit_entitlement_ledger_session ON visit_entitlement_ledger(session_id, id);
CREATE INDEX idx_visit_entitlement_ledger_user ON visit_entitlement_ledger(user_id, occurred_at DESC);
CREATE UNIQUE INDEX uk_visit_entitlement_single_reserve_per_session
    ON visit_entitlement_ledger(session_id) WHERE event_type = 'RESERVE';
CREATE UNIQUE INDEX uk_visit_entitlement_single_consume_per_session
    ON visit_entitlement_ledger(session_id) WHERE event_type = 'CONSUME';
CREATE UNIQUE INDEX uk_visit_entitlement_single_restore_per_session
    ON visit_entitlement_ledger(session_id) WHERE event_type IN ('RESTORE', 'OE_OPERATIONAL_RESTORE');

CREATE TABLE visit_start_challenges (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL UNIQUE REFERENCES visit_sessions(id),
    tenant_id BIGINT NOT NULL REFERENCES users(id),
    ground_executive_user_id BIGINT NOT NULL REFERENCES users(id),
    generation INTEGER NOT NULL CHECK (generation > 0),
    key_id VARCHAR(64) NOT NULL,
    digest BYTEA NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_issue_allowed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    invalidated_at TIMESTAMP WITH TIME ZONE,
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_visit_start_challenge_window CHECK (expires_at > issued_at)
);
CREATE INDEX idx_visit_start_challenge_expiry ON visit_start_challenges(expires_at)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE TABLE visit_execution_events (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES visit_sessions(id),
    actor_user_id BIGINT REFERENCES users(id),
    event_type VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason_code VARCHAR(64),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE
);
CREATE INDEX idx_visit_execution_events_session ON visit_execution_events(session_id, occurred_at, id);

INSERT INTO visit_execution_events(session_id,event_type,reason_code,metadata,idempotency_key)
SELECT id, 'LEGACY_START_REVIEW', 'PRE_PACKAGE_3_START_UNVERIFIED',
       jsonb_build_object('legacyStartedAt', started_at, 'legacyScheduledAt', scheduled_at,
           'legacyReservedEndAt', reserved_end_at, 'legacyAssignedGroundExecutiveUserId', representative_user_id),
       'LEGACY_START_REVIEW:' || id
FROM visit_sessions WHERE status='REPAIR_REQUIRED' AND repair_state='REQUIRED'
ON CONFLICT(idempotency_key) DO NOTHING;

CREATE TABLE visit_notification_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_key VARCHAR(180) NOT NULL UNIQUE,
    recipient_user_id BIGINT NOT NULL REFERENCES users(id),
    recipient_role VARCHAR(24) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    title VARCHAR(180) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (state IN ('QUEUED', 'PROCESSING', 'SENT', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_visit_notification_outbox_due
    ON visit_notification_outbox(available_at, id) WHERE state IN ('QUEUED', 'FAILED');

-- Seed authoritative accounts from the legacy compatibility balance.
INSERT INTO tenant_visit_entitlement_accounts(user_id, available_credits, reconciliation_required)
SELECT id, GREATEST(COALESCE(free_visits_remaining, 0), 0),
       free_visits_remaining IS NULL OR free_visits_remaining < 0
FROM users
WHERE role = 'ROLE_TENANT'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO visit_entitlement_ledger(account_id, user_id, event_type, available_delta,
                                     reserved_delta, reason_code, idempotency_key)
SELECT a.id, a.user_id, 'INITIAL_GRANT', a.available_credits, 0,
       'LEGACY_BALANCE_RECONCILIATION', 'INITIAL_GRANT:' || a.user_id
FROM tenant_visit_entitlement_accounts a
WHERE a.available_credits > 0
ON CONFLICT (idempotency_key) DO NOTHING;

-- Grandfather existing near-term confirmed bookings when a credit is available.
WITH eligible AS (
    SELECT s.id AS session_id, s.tenant_id, a.id AS account_id,
           row_number() OVER (PARTITION BY s.tenant_id ORDER BY s.scheduled_at, s.id) AS booking_rank,
           a.available_credits
    FROM visit_sessions s
    JOIN tenant_visit_entitlement_accounts a ON a.user_id = s.tenant_id
    WHERE s.status = 'SCHEDULED'
      AND s.scheduled_at <= CURRENT_TIMESTAMP + INTERVAL '7 days'
), grandfathered AS (
    SELECT * FROM eligible WHERE booking_rank <= available_credits
)
INSERT INTO visit_entitlement_ledger(account_id, user_id, session_id, event_type,
                                     available_delta, reserved_delta, reason_code, idempotency_key)
SELECT account_id, tenant_id, session_id, 'RESERVE', -1, 1,
       'LEGACY_CONFIRMED_BOOKING', 'RESERVE:' || session_id
FROM grandfathered
ON CONFLICT (idempotency_key) DO NOTHING;

UPDATE tenant_visit_entitlement_accounts a
SET available_credits = a.available_credits - x.reserved_count,
    reserved_credits = x.reserved_count,
    updated_at = CURRENT_TIMESTAMP
FROM (
    SELECT account_id, count(*)::integer AS reserved_count
    FROM visit_entitlement_ledger WHERE event_type = 'RESERVE'
      AND reason_code = 'LEGACY_CONFIRMED_BOOKING'
    GROUP BY account_id
) x
WHERE a.id = x.account_id;

UPDATE tenant_visit_entitlement_accounts a
SET reconciliation_required = TRUE
WHERE (SELECT count(*) FROM visit_sessions s
       WHERE s.tenant_id = a.user_id AND s.status = 'SCHEDULED'
         AND s.scheduled_at <= CURRENT_TIMESTAMP + INTERVAL '7 days') >
      (SELECT count(*) FROM visit_entitlement_ledger l
       WHERE l.account_id = a.id AND l.event_type = 'RESERVE'
         AND l.reason_code = 'LEGACY_CONFIRMED_BOOKING');

UPDATE users u SET free_visits_remaining = a.available_credits
FROM tenant_visit_entitlement_accounts a WHERE a.user_id = u.id;
