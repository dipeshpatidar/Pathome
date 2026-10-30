ALTER TABLE lead_phone_delivery_outbox
    DROP CONSTRAINT ck_lead_phone_outbox_state,
    ADD CONSTRAINT ck_lead_phone_outbox_state
        CHECK (state IN (
            'QUEUED',
            'DISPATCHING',
            'ACCEPTED',
            'DELIVERED',
            'FAILED',
            'UNKNOWN',
            'SUPPRESSED'
        ));
