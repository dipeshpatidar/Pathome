-- Receipts share the booking transaction. No TTL: late transport replays must remain safe.
CREATE TABLE visit_scheduling_commands (
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    command_id UUID NOT NULL,
    session_id BIGINT NOT NULL REFERENCES visit_sessions(id),
    operation VARCHAR(40) NOT NULL CHECK (operation = 'OE_SELECTION'),
    canonical_payload TEXT NOT NULL,
    response_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (actor_user_id, command_id)
);
CREATE INDEX idx_visit_scheduling_command_session ON visit_scheduling_commands(session_id);
