CREATE TABLE visit_scheduling_decisions (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES visit_sessions(id),
    session_version_before BIGINT NOT NULL,
    recommendation_generated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    policy_version VARCHAR(40) NOT NULL,
    recommended_ground_executive_user_id BIGINT REFERENCES users(id),
    recommended_scheduled_at TIMESTAMP WITH TIME ZONE,
    selected_ground_executive_user_id BIGINT NOT NULL REFERENCES users(id),
    selected_scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes > 0),
    approved_by_user_id BIGINT NOT NULL REFERENCES users(id),
    approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    was_override BOOLEAN NOT NULL,
    override_reason VARCHAR(500),
    location_assessment VARCHAR(24) NOT NULL,
    travel_confidence VARCHAR(24) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_visit_scheduling_decision_override_reason
        CHECK ((was_override = FALSE AND override_reason IS NULL)
            OR (was_override = TRUE AND override_reason IS NOT NULL AND length(btrim(override_reason)) > 0)),
    CONSTRAINT chk_visit_scheduling_decision_recommendation
        CHECK ((recommended_ground_executive_user_id IS NULL) = (recommended_scheduled_at IS NULL))
);

CREATE INDEX idx_visit_scheduling_decision_session_created
    ON visit_scheduling_decisions (session_id, created_at DESC, id DESC);
