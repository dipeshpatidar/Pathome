ALTER TABLE property_visit_requests
    ADD CONSTRAINT uk_property_visit_request_id_session UNIQUE (id, session_id);

ALTER TABLE visit_session_items
    ADD COLUMN derived_from_request_id BIGINT,
    ADD COLUMN origin VARCHAR(32);

-- Package 1 source requests are exact-listing tenant requests, so their direct origin is known.
UPDATE visit_session_items
SET origin = 'TENANT_REQUESTED'
WHERE source_request_id IS NOT NULL;

ALTER TABLE visit_session_items
    ADD CONSTRAINT fk_visit_session_item_derived_request_session
        FOREIGN KEY (derived_from_request_id, session_id)
        REFERENCES property_visit_requests (id, session_id),
    ADD CONSTRAINT chk_visit_session_item_provenance
        CHECK (
            (source_request_id IS NULL AND derived_from_request_id IS NULL AND origin IS NULL)
            OR (source_request_id IS NOT NULL AND derived_from_request_id IS NULL
                AND origin IS NOT NULL AND origin = 'TENANT_REQUESTED')
            OR (source_request_id IS NULL AND derived_from_request_id IS NOT NULL
                AND origin IS NOT NULL AND origin IN ('OE_ADDED', 'LESSOR_SUGGESTED'))
        );

CREATE INDEX idx_visit_session_item_derived_request
    ON visit_session_items (derived_from_request_id, session_id)
    WHERE derived_from_request_id IS NOT NULL;
