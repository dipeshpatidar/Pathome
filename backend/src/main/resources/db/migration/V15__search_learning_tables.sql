-- V15: Search Learning System
-- Governed feedback-learning loop: telemetry → candidates → controlled promotion → active aliases.
-- Learning never mutates canonical business data (listings, localities, property types).

-- Lightweight privacy-safe telemetry: structured signals only.
-- Raw free-text is NOT stored by default; only normalised/structured extracted fields.
CREATE TABLE IF NOT EXISTS search_query_event (
    id                    BIGSERIAL PRIMARY KEY,
    occurred_at           TIMESTAMP NOT NULL DEFAULT now(),
    event_type            VARCHAR(30) NOT NULL DEFAULT 'SUGGESTION_SHOWN', -- SUGGESTION_SHOWN | SUGGESTION_SELECTED | SEARCH_EXECUTED
    -- Parser output (structured)
    city_input            VARCHAR(120),          -- City sent by client (may be empty)
    location_candidate    VARCHAR(120),          -- Normalised location text extracted
    resolved_locality     VARCHAR(120),          -- Locality matched (if any)
    resolved_city         VARCHAR(120),          -- City matched (if any)
    resolution_method     VARCHAR(30),           -- EXACT | PREFIX | ALIAS | FUZZY | UNRESOLVED | STRUCTURED
    fuzzy_confidence      NUMERIC(4,3),          -- 0.000–1.000; null when not a fuzzy resolution
    bhk_key               VARCHAR(10),           -- e.g. 4BHK, 2RK; null if absent
    property_type_key     VARCHAR(40),           -- e.g. FLAT, HOUSE; null if absent
    furnishing_key        VARCHAR(20),           -- e.g. SEMI_FURNISHED; null if absent
    suggestion_count      SMALLINT,              -- How many suggestions were returned
    selected_type         VARCHAR(20),           -- CITY | LOCALITY | SEARCH_QUERY; null if no selection
    selected_rank         SMALLINT,              -- 0-based rank of selected suggestion; null if none
    search_executed       BOOLEAN,               -- Did user submit search?
    result_count          INTEGER,               -- Result set size after search; null if not submitted
    zero_result           BOOLEAN,               -- True when search ran and result_count = 0
    session_hash          VARCHAR(64)            -- Opaque hashed session key; never a raw session ID
);

-- Indexes supporting candidate aggregation queries and session dedup
CREATE INDEX IF NOT EXISTS idx_sqe_occurred_at ON search_query_event (occurred_at);
CREATE INDEX IF NOT EXISTS idx_sqe_location_candidate ON search_query_event (location_candidate) WHERE location_candidate IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sqe_resolved_locality ON search_query_event (resolved_locality, resolved_city) WHERE resolved_locality IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sqe_zero_result ON search_query_event (location_candidate) WHERE zero_result = true;
CREATE INDEX IF NOT EXISTS idx_sqe_session_candidate ON search_query_event (session_hash, location_candidate, occurred_at);
CREATE INDEX IF NOT EXISTS idx_sqe_event_type ON search_query_event (event_type);

-- Aggregated evidence for potential aliases/corrections.
-- One row per (candidate_term, canonical_entity_type, canonical_entity_value, canonical_city) quadruple.
-- Updated by the aggregation service; never directly mutated by autocomplete requests.
CREATE TABLE IF NOT EXISTS search_alias_candidate (
    id                         BIGSERIAL PRIMARY KEY,
    candidate_term             VARCHAR(120) NOT NULL,   -- Normalised user-typed variant
    canonical_entity_type      VARCHAR(30)  NOT NULL,   -- LOCALITY | CITY | PROPERTY_TYPE | FURNISHING
    canonical_entity_value     VARCHAR(120) NOT NULL,   -- Canonical name (city or locality name etc.)
    canonical_city             VARCHAR(120),            -- City scope when entity is LOCALITY
    evidence_count             INTEGER      NOT NULL DEFAULT 0,
    unique_session_count       INTEGER      NOT NULL DEFAULT 0,
    successful_selection_count INTEGER      NOT NULL DEFAULT 0,
    rejection_count            INTEGER      NOT NULL DEFAULT 0,
    selection_rate             NUMERIC(4,3),            -- successful_selection_count / (evidence_count) when > 0
    average_confidence         NUMERIC(4,3),
    status                     VARCHAR(30)  NOT NULL DEFAULT 'CANDIDATE',
    -- status: CANDIDATE | ELIGIBLE_FOR_REVIEW | APPROVED | REJECTED | AUTO_PROMOTED | DISABLED
    promoted_at                TIMESTAMP,
    promoted_by                VARCHAR(120),            -- 'AUTO' or admin identifier (no secrets)
    promotion_evidence         TEXT,                    -- JSON summary of thresholds passed
    first_seen_at              TIMESTAMP    NOT NULL DEFAULT now(),
    last_seen_at               TIMESTAMP    NOT NULL DEFAULT now(),
    created_at                 TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_alias_candidate UNIQUE (candidate_term, canonical_entity_type, canonical_entity_value, canonical_city)
);

CREATE INDEX IF NOT EXISTS idx_sac_candidate_term ON search_alias_candidate (candidate_term);
CREATE INDEX IF NOT EXISTS idx_sac_status ON search_alias_candidate (status);
CREATE INDEX IF NOT EXISTS idx_sac_entity ON search_alias_candidate (canonical_entity_type, canonical_entity_value);

-- Active/approved aliases used by the search resolver.
-- Promoted from search_alias_candidate when thresholds are met and approved.
-- Each alias has a full audit trail so rollback is always possible.
CREATE TABLE IF NOT EXISTS search_alias (
    id                    BIGSERIAL PRIMARY KEY,
    alias_term            VARCHAR(120) NOT NULL,   -- The alias to resolve (normalised)
    entity_type           VARCHAR(30)  NOT NULL,   -- LOCALITY | CITY | PROPERTY_TYPE | FURNISHING
    entity_value          VARCHAR(120) NOT NULL,   -- Canonical value to resolve to
    entity_city           VARCHAR(120),            -- City scope when entity_type = LOCALITY
    confidence            NUMERIC(4,3) NOT NULL DEFAULT 1.000,
    source_candidate_id   BIGINT REFERENCES search_alias_candidate(id) ON DELETE SET NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    -- status: ACTIVE | DISABLED | SUPERSEDED
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    disabled_at           TIMESTAMP,
    disabled_reason       VARCHAR(255),
    CONSTRAINT uq_search_alias UNIQUE (alias_term, entity_type, entity_city)
);

CREATE INDEX IF NOT EXISTS idx_sa_alias_term ON search_alias (alias_term) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_sa_alias_term_city ON search_alias (alias_term, entity_city) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_sa_entity ON search_alias (entity_type, entity_value) WHERE status = 'ACTIVE';
