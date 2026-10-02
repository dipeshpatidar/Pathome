CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE visit_sessions
    ADD COLUMN reserved_end_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE visit_sessions
    ADD CONSTRAINT chk_visit_session_scheduled_booking
        CHECK (status NOT IN ('SCHEDULED', 'STARTED') OR (
            scheduled_at IS NOT NULL
            AND zone_id IS NOT NULL
            AND representative_user_id IS NOT NULL
            AND assigned_at IS NOT NULL
            AND duration_snapshot_minutes > 0
            AND reserved_end_at IS NOT NULL
            AND reserved_end_at > scheduled_at
            AND reserved_end_at = scheduled_at + (duration_snapshot_minutes * INTERVAL '1 minute')
        ));

ALTER TABLE visit_sessions
    ADD CONSTRAINT ex_visit_session_ge_reservation_overlap
        EXCLUDE USING gist (
            representative_user_id WITH =,
            tstzrange(scheduled_at, reserved_end_at, '[)') WITH &&
        )
        WHERE (status IN ('SCHEDULED', 'STARTED'));

CREATE INDEX idx_visit_session_ge_reservation
    ON visit_sessions (representative_user_id, scheduled_at, reserved_end_at)
    WHERE status IN ('SCHEDULED', 'STARTED');
