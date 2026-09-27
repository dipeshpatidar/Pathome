-- Additive V2. Historical rows remain manual/legacy until safely adopted by the learner.
ALTER TABLE search_alias_candidate ADD COLUMN lifecycle_state VARCHAR(30);
ALTER TABLE search_alias_candidate ADD COLUMN policy_version VARCHAR(30);
ALTER TABLE search_alias_candidate ADD COLUMN state_since TIMESTAMP;
ALTER TABLE search_alias_candidate ADD COLUMN evidence_since TIMESTAMP;
ALTER TABLE search_alias_candidate ADD COLUMN shadow_since TIMESTAMP;
ALTER TABLE search_alias_candidate ADD COLUMN health_since TIMESTAMP;
ALTER TABLE search_alias_candidate ADD COLUMN evaluated_at TIMESTAMP;
ALTER TABLE search_alias_candidate ADD COLUMN last_decision TEXT;
ALTER TABLE search_alias_candidate ADD CONSTRAINT ck_autonomous_lifecycle CHECK
    (lifecycle_state IS NULL OR lifecycle_state IN
     ('OBSERVED','EVIDENCE_BUILDING','SHADOW','ACTIVE','DEGRADED','AUTO_DISABLED','MANUALLY_DISABLED'));
ALTER TABLE search_alias ADD COLUMN policy_version VARCHAR(30);
ALTER TABLE search_alias ADD COLUMN disable_source VARCHAR(10);
-- A pre-existing disable is always a manual veto.
UPDATE search_alias SET disable_source = 'MANUAL' WHERE status = 'DISABLED';
ALTER TABLE search_query_event ADD COLUMN autonomous_checked_at TIMESTAMP;

CREATE TABLE search_alias_evidence (
    event_id BIGINT PRIMARY KEY REFERENCES search_query_event(id) ON DELETE CASCADE,
    alias_term VARCHAR(120) NOT NULL,
    entity_type VARCHAR(30) NOT NULL CHECK (entity_type = 'LOCALITY'),
    city_key VARCHAR(120) NOT NULL CHECK (city_key <> ''),
    target_value VARCHAR(120) NOT NULL,
    session_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    strength VARCHAR(10) NOT NULL CHECK (strength IN ('STRONG','MODERATE')),
    deterministic_target VARCHAR(120),
    policy_version VARCHAR(30) NOT NULL DEFAULT 'AUTONOMOUS_V2'
);
CREATE TABLE search_alias_audit (
    id BIGSERIAL PRIMARY KEY,
    candidate_id BIGINT REFERENCES search_alias_candidate(id) ON DELETE SET NULL,
    alias_term VARCHAR(120) NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    canonical_target VARCHAR(120) NOT NULL,
    city_key VARCHAR(120) NOT NULL,
    from_state VARCHAR(30),
    to_state VARCHAR(30) NOT NULL,
    event VARCHAR(40) NOT NULL,
    source VARCHAR(10) NOT NULL,
    policy_version VARCHAR(30) NOT NULL,
    reasons TEXT NOT NULL,
    facts TEXT NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT now()
);
-- Unchecked event scan is bounded and does not repeatedly visit processed history.
CREATE INDEX idx_sqe_autonomous_pending ON search_query_event(id) WHERE autonomous_checked_at IS NULL;
-- Windowed, city-scoped evidence selection, including competing targets and per-session caps.
CREATE INDEX idx_sae_scope_window ON search_alias_evidence(alias_term, entity_type, city_key, occurred_at, session_hash);
-- Fair bounded candidate scheduling (oldest evaluated first).
CREATE INDEX idx_sac_autonomous_evaluation ON search_alias_candidate(evaluated_at NULLS FIRST, id)
    WHERE policy_version = 'AUTONOMOUS_V2';
-- Safe for historical case variants; all new autonomous rows have normalized scope uniqueness.
CREATE UNIQUE INDEX uq_sa_autonomous_scope ON search_alias(lower(btrim(alias_term)), entity_type, lower(btrim(entity_city)))
    WHERE policy_version = 'AUTONOMOUS_V2';
CREATE INDEX idx_sa_normalized_scope ON search_alias(lower(btrim(alias_term)), entity_type, lower(btrim(coalesce(entity_city,''))));
CREATE INDEX idx_sac_normalized_scope ON search_alias_candidate(lower(btrim(candidate_term)), canonical_entity_type, lower(btrim(coalesce(canonical_city,''))));
CREATE INDEX idx_saa_candidate_time ON search_alias_audit(candidate_id, occurred_at);
