-- Approved D-B continuation: contact consent, public shortlists, truthful manual-sharing evidence,
-- and recoverable source processing. V51/V52 remain immutable.

ALTER TABLE lead_source_events
    ADD COLUMN fingerprint_version VARCHAR(24) NOT NULL DEFAULT 'LEGACY_ENVELOPE_V1',
    ADD COLUMN provider_form_id VARCHAR(100),
    ADD COLUMN extracted_payload_ciphertext TEXT,
    ADD COLUMN extracted_payload_iv VARCHAR(32),
    ADD COLUMN extraction_key_version VARCHAR(32),
    ADD COLUMN resolution_lead_id BIGINT REFERENCES lead_routing_queue(id);

UPDATE lead_source_events
SET fingerprint_version = 'MANUAL_CANONICAL_V1'
WHERE provider = 'MANUAL';

ALTER TABLE lead_source_events
    DROP CONSTRAINT ck_lead_source_state,
    ADD CONSTRAINT ck_lead_source_state
        CHECK (processing_state IN ('PENDING', 'PROCESSING', 'PROCESSED',
                                    'AWAITING_REVIEW', 'QUARANTINED'));

CREATE INDEX idx_lead_source_review
    ON lead_source_events(processing_state, received_at DESC, id);

CREATE TABLE lead_lookup_events (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    purpose VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_lead_lookup_purpose CHECK (purpose = 'DUPLICATE_CANDIDATE')
);

CREATE INDEX idx_lead_lookup_actor_time
    ON lead_lookup_events(actor_user_id, occurred_at DESC, id DESC);

CREATE TABLE lead_contact_preferences (
    id BIGSERIAL PRIMARY KEY,
    lead_id BIGINT NOT NULL REFERENCES lead_routing_queue(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    channel VARCHAR(24) NOT NULL,
    destination_choice VARCHAR(16) NOT NULL,
    country_calling_code VARCHAR(5),
    destination_ciphertext TEXT,
    destination_iv VARCHAR(32),
    destination_key_version VARCHAR(24),
    destination_fingerprint VARCHAR(64),
    consent_status VARCHAR(16) NOT NULL,
    consent_purpose VARCHAR(32) NOT NULL,
    consent_wording_version VARCHAR(64),
    evidence_method VARCHAR(24),
    recorded_at TIMESTAMPTZ NOT NULL,
    captured_by_user_id BIGINT NOT NULL REFERENCES users(id),
    CONSTRAINT uk_lead_contact_preference UNIQUE (lead_id, revision),
    CONSTRAINT ck_lead_contact_channel CHECK (channel IN ('WHATSAPP', 'OTHER')),
    CONSTRAINT ck_lead_destination_choice CHECK (destination_choice IN ('SAME', 'DIFFERENT', 'NONE')),
    CONSTRAINT ck_lead_consent_status CHECK (consent_status IN ('GRANTED', 'REVOKED', 'DISPUTED', 'UNKNOWN')),
    CONSTRAINT ck_lead_consent_purpose CHECK (consent_purpose = 'PROPERTY_SHARING'),
    CONSTRAINT ck_lead_evidence_method
        CHECK (evidence_method IS NULL OR evidence_method IN ('CUSTOMER_VERBAL', 'CUSTOMER_DIGITAL')),
    CONSTRAINT ck_lead_destination_cipher_shape
        CHECK ((destination_choice = 'NONE'
                AND destination_ciphertext IS NULL AND destination_iv IS NULL
                AND destination_fingerprint IS NULL)
            OR (destination_choice IN ('SAME', 'DIFFERENT')
                AND destination_ciphertext IS NOT NULL AND destination_iv IS NOT NULL
                AND destination_fingerprint IS NOT NULL))
);

CREATE INDEX idx_lead_contact_fingerprint
    ON lead_contact_preferences(destination_fingerprint)
    WHERE destination_fingerprint IS NOT NULL;

CREATE TABLE lead_shortlists (
    id BIGSERIAL PRIMARY KEY,
    lead_id BIGINT NOT NULL REFERENCES lead_routing_queue(id) ON DELETE CASCADE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_lead_shortlist_lead
    ON lead_shortlists(lead_id, created_at DESC, id DESC);

CREATE TABLE lead_shortlist_items (
    id BIGSERIAL PRIMARY KEY,
    shortlist_id BIGINT NOT NULL REFERENCES lead_shortlists(id) ON DELETE CASCADE,
    listing_id BIGINT NOT NULL REFERENCES listings(id),
    position INTEGER NOT NULL,
    CONSTRAINT uk_lead_shortlist_item UNIQUE (shortlist_id, listing_id),
    CONSTRAINT uk_lead_shortlist_position UNIQUE (shortlist_id, position),
    CONSTRAINT ck_lead_shortlist_position CHECK (position >= 0 AND position < 20)
);

CREATE TABLE lead_shares (
    id UUID PRIMARY KEY,
    lead_id BIGINT NOT NULL REFERENCES lead_routing_queue(id),
    shortlist_id BIGINT NOT NULL REFERENCES lead_shortlists(id),
    shortlist_version BIGINT NOT NULL,
    contact_preference_revision BIGINT NOT NULL,
    mode VARCHAR(32) NOT NULL,
    public_links_json TEXT NOT NULL,
    preview_text TEXT NOT NULL,
    masked_destination VARCHAR(40),
    recorded_at TIMESTAMPTZ NOT NULL,
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    CONSTRAINT ck_lead_share_mode CHECK (mode IN ('COPY_LINKS', 'MANUAL_BUSINESS_HANDOFF'))
);

CREATE INDEX idx_lead_share_lead
    ON lead_shares(lead_id, recorded_at DESC, id);

CREATE TABLE lead_share_events (
    id UUID PRIMARY KEY,
    share_id UUID NOT NULL REFERENCES lead_shares(id) ON DELETE CASCADE,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    event_type VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_lead_share_event_type CHECK (
        event_type IN ('CLIPBOARD_COPIED', 'HANDOFF_ATTEMPTED',
                       'OPERATOR_REPORTED_MANUAL_SEND', 'HANDOFF_CANCELLED')
    )
);

CREATE INDEX idx_lead_share_event_share
    ON lead_share_events(share_id, occurred_at DESC, id);
