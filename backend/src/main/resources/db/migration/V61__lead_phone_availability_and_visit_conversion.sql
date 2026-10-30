-- Owner-approved A1-A7 source contract. Prepared only; this file is not authorized to be applied.
-- PostgreSQL remains authoritative for enquiry proof, availability attempts and Visit conversion.

ALTER TABLE lead_routing_queue
    ADD COLUMN contact_phone_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_lead_contact_phone_revision_nonnegative
        CHECK (contact_phone_revision >= 0);

CREATE TABLE lead_phone_challenges (
    id UUID PRIMARY KEY,
    lead_id BIGINT NOT NULL REFERENCES lead_routing_queue(id) ON DELETE CASCADE,
    source_event_id UUID NOT NULL REFERENCES lead_source_events(id),
    purpose VARCHAR(32) NOT NULL,
    number_fingerprint VARCHAR(64) NOT NULL,
    masked_destination VARCHAR(32) NOT NULL,
    contact_phone_revision BIGINT NOT NULL,
    generation INTEGER NOT NULL,
    context_digest VARCHAR(64) NOT NULL,
    verification_digest VARCHAR(64) NOT NULL,
    confirmation_token_hash VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    delivery_state VARCHAR(16) NOT NULL,
    delivery_provider VARCHAR(24) NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    entry_method VARCHAR(16),
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_lead_phone_challenge_generation
        UNIQUE (lead_id, source_event_id, purpose, contact_phone_revision, generation),
    CONSTRAINT uk_lead_phone_confirmation_token
        UNIQUE (confirmation_token_hash),
    CONSTRAINT ck_lead_phone_purpose
        CHECK (purpose = 'ENQUIRY_PHONE_POSSESSION'),
    CONSTRAINT ck_lead_phone_challenge_state
        CHECK (state IN ('PENDING', 'VERIFIED', 'EXPIRED', 'EXHAUSTED', 'CANCELLED')),
    CONSTRAINT ck_lead_phone_delivery_state
        CHECK (delivery_state IN ('QUEUED', 'ACCEPTED', 'DELIVERED', 'FAILED', 'UNKNOWN', 'SUPPRESSED')),
    CONSTRAINT ck_lead_phone_failed_attempts
        CHECK (failed_attempts BETWEEN 0 AND 5),
    CONSTRAINT ck_lead_phone_generation
        CHECK (generation > 0),
    CONSTRAINT ck_lead_phone_revision
        CHECK (contact_phone_revision >= 0),
    CONSTRAINT ck_lead_phone_entry_method
        CHECK (entry_method IS NULL OR entry_method IN ('CUSTOMER_FORM', 'OE_ENTERED')),
    CONSTRAINT ck_lead_phone_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_lead_phone_verified_shape
        CHECK ((state = 'VERIFIED' AND verified_at IS NOT NULL AND entry_method IS NOT NULL)
            OR state <> 'VERIFIED')
);

CREATE UNIQUE INDEX uk_lead_phone_one_pending_generation
    ON lead_phone_challenges(
        lead_id, source_event_id, purpose, contact_phone_revision)
    WHERE state = 'PENDING';
CREATE INDEX idx_lead_phone_actor_window
    ON lead_phone_challenges(created_by_user_id, created_at DESC, id);
CREATE INDEX idx_lead_phone_destination_window
    ON lead_phone_challenges(number_fingerprint, created_at DESC, id);
CREATE INDEX idx_lead_phone_lead_current
    ON lead_phone_challenges(lead_id, purpose, created_at DESC, id);

CREATE TABLE lead_phone_delivery_outbox (
    id UUID PRIMARY KEY,
    challenge_id UUID NOT NULL REFERENCES lead_phone_challenges(id) ON DELETE CASCADE,
    destination_fingerprint VARCHAR(64) NOT NULL,
    payload_ciphertext TEXT NOT NULL,
    payload_iv VARCHAR(32) NOT NULL,
    payload_key_version VARCHAR(24) NOT NULL,
    provider_name VARCHAR(24) NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    lease_token UUID,
    provider_message_id VARCHAR(160),
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_lead_phone_outbox_challenge UNIQUE (challenge_id),
    CONSTRAINT ck_lead_phone_outbox_state
        CHECK (state IN ('QUEUED', 'DISPATCHING', 'ACCEPTED', 'FAILED', 'UNKNOWN', 'SUPPRESSED')),
    CONSTRAINT ck_lead_phone_outbox_attempts
        CHECK (attempts >= 0 AND attempts <= 5)
);

CREATE INDEX idx_lead_phone_outbox_due
    ON lead_phone_delivery_outbox(state, available_at, created_at, id);

CREATE TABLE lead_lessor_availability_enquiries (
    id UUID PRIMARY KEY,
    lead_id BIGINT NOT NULL REFERENCES lead_routing_queue(id),
    shortlist_id BIGINT NOT NULL REFERENCES lead_shortlists(id),
    listing_id BIGINT NOT NULL REFERENCES listings(id),
    source_event_id UUID NOT NULL REFERENCES lead_source_events(id),
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    city_id BIGINT NOT NULL REFERENCES supported_cities(id),
    team_id BIGINT NOT NULL REFERENCES operating_teams(id),
    lead_version BIGINT NOT NULL,
    shortlist_version BIGINT NOT NULL,
    listing_version BIGINT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    outcome_note_activity_id BIGINT REFERENCES lead_activities(id),
    reporting_user_id BIGINT REFERENCES users(id),
    reported_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_lead_availability_purpose
        CHECK (purpose = 'VIEWING_AVAILABILITY'),
    CONSTRAINT ck_lead_availability_outcome
        CHECK (outcome IN ('PENDING', 'NO_ANSWER', 'LESSOR_REPORTED_AVAILABLE',
                           'LESSOR_REPORTED_UNAVAILABLE', 'CALLBACK_REQUESTED',
                           'CONTACT_UNUSABLE', 'CUSTOMER_WITHDREW',
                           'CUSTOMER_DISPUTED', 'PROPERTY_DECLINED')),
    CONSTRAINT ck_lead_availability_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_lead_availability_versions
        CHECK (lead_version >= 0 AND shortlist_version >= 0 AND listing_version >= 0)
);

CREATE INDEX idx_lead_availability_lead
    ON lead_lessor_availability_enquiries(lead_id, created_at DESC, id);
CREATE INDEX idx_lead_availability_expiry
    ON lead_lessor_availability_enquiries(expires_at, id);
CREATE INDEX idx_lead_availability_actor_window
    ON lead_lessor_availability_enquiries(created_by_user_id, created_at DESC, id);
CREATE INDEX idx_lead_availability_property_window
    ON lead_lessor_availability_enquiries(created_by_user_id, listing_id, created_at DESC, id);

CREATE INDEX idx_operational_audit_actor_action_time
    ON operational_audit_events(actor_user_id, action_code, occurred_at DESC);

CREATE TABLE lead_visit_proposals (
    id UUID PRIMARY KEY,
    lead_id BIGINT NOT NULL REFERENCES lead_routing_queue(id),
    shortlist_id BIGINT NOT NULL REFERENCES lead_shortlists(id),
    listing_id BIGINT NOT NULL REFERENCES listings(id),
    contact_preference_revision BIGINT NOT NULL,
    lead_version BIGINT NOT NULL,
    shortlist_version BIGINT NOT NULL,
    listing_version BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    association_status VARCHAR(16) NOT NULL,
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    superseded_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_lead_visit_proposal_token UNIQUE (token_hash),
    CONSTRAINT ck_lead_visit_proposal_state
        CHECK (state IN ('OPEN', 'EXPIRED', 'REVOKED', 'SUPERSEDED')),
    CONSTRAINT ck_lead_visit_association_status
        CHECK (association_status IN ('PENDING', 'CONFIRMED', 'DISPUTED',
                                      'DECLINED', 'WITHDRAWN', 'UNAVAILABLE')),
    CONSTRAINT ck_lead_visit_proposal_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_lead_visit_proposal_lead
    ON lead_visit_proposals(lead_id, created_at DESC, id);
CREATE INDEX idx_lead_visit_proposal_expiry
    ON lead_visit_proposals(state, expires_at, id);

CREATE TABLE lead_visit_continuations (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES lead_visit_proposals(id) ON DELETE CASCADE,
    secret_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_seen_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_lead_visit_continuation_secret UNIQUE (secret_hash),
    CONSTRAINT ck_lead_visit_continuation_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_lead_visit_continuation_expiry
    ON lead_visit_continuations(expires_at, id)
    WHERE revoked_at IS NULL;

CREATE TABLE lead_visit_customer_responses (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES lead_visit_proposals(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    response_state VARCHAR(20) NOT NULL,
    request_id BIGINT REFERENCES property_visit_requests(id),
    recorded_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_lead_visit_customer_response UNIQUE (proposal_id, user_id),
    CONSTRAINT ck_lead_visit_customer_response_state
        CHECK (response_state IN ('DECLINED', 'REQUEST_RECORDED'))
);

CREATE TABLE lead_visit_association_claims (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES lead_visit_proposals(id),
    customer_response_id UUID NOT NULL REFERENCES lead_visit_customer_responses(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    request_id BIGINT NOT NULL REFERENCES property_visit_requests(id),
    consent_wording_version VARCHAR(64) NOT NULL,
    consent_recorded_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    decided_by_user_id BIGINT REFERENCES users(id),
    decision_reason_code VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_lead_visit_association_claim UNIQUE (proposal_id, user_id),
    CONSTRAINT ck_lead_visit_association_claim_state
        CHECK (state IN ('PENDING', 'CONFIRMED', 'DISPUTED', 'DECLINED', 'WITHDRAWN', 'UNAVAILABLE'))
);

CREATE INDEX idx_lead_visit_claim_lead
    ON lead_visit_association_claims(state, created_at, id);

CREATE TABLE lead_visit_links (
    id UUID PRIMARY KEY,
    lead_id BIGINT NOT NULL REFERENCES lead_routing_queue(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    proposal_id UUID NOT NULL REFERENCES lead_visit_proposals(id),
    request_id BIGINT NOT NULL REFERENCES property_visit_requests(id),
    confirmed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_lead_visit_link_lead UNIQUE (lead_id),
    CONSTRAINT uk_lead_visit_link_proposal UNIQUE (proposal_id),
    CONSTRAINT uk_lead_visit_link_request UNIQUE (request_id)
);

CREATE TABLE lead_assistance_command_receipts (
    id UUID PRIMARY KEY,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    operation_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    result_reference VARCHAR(160),
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_lead_assistance_receipt
        UNIQUE (actor_user_id, operation_id, action)
);

CREATE INDEX idx_lead_assistance_receipt_completed
    ON lead_assistance_command_receipts(completed_at);
