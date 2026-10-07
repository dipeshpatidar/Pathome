-- Make notification authorization explicit. Unknown historical staff messages stay quarantined;
-- Session links are never inferred from text, target role, or event keys.
ALTER TABLE system_notifications
    ADD COLUMN authorization_class VARCHAR(32) NOT NULL DEFAULT 'STAFF_LEGACY_QUARANTINED',
    ADD COLUMN operational_session_id BIGINT;

UPDATE system_notifications
   SET authorization_class = 'RECIPIENT'
 WHERE target_role IN ('TENANT', 'LANDLORD', 'ALL')
   AND EXISTS (
       SELECT 1
         FROM users u
        WHERE u.id::text = system_notifications.recipient_user_id
          AND u.role IN ('ROLE_TENANT', 'ROLE_LANDLORD')
          AND NOT EXISTS (SELECT 1 FROM employee_profiles ep WHERE ep.user_id = u.id)
   );

ALTER TABLE system_notifications
    ADD CONSTRAINT fk_system_notifications_operational_session
        FOREIGN KEY (operational_session_id) REFERENCES visit_sessions(id),
    ADD CONSTRAINT ck_system_notifications_authorization_context CHECK (
        authorization_class IN ('RECIPIENT', 'OPERATIONS_SESSION', 'STAFF_LEGACY_QUARANTINED')
        AND ((authorization_class = 'OPERATIONS_SESSION') = (operational_session_id IS NOT NULL))
    );

CREATE INDEX idx_system_notifications_session_visibility
    ON system_notifications (recipient_user_id, operational_session_id, created_at DESC)
    WHERE authorization_class = 'OPERATIONS_SESSION';
CREATE INDEX idx_system_notifications_recipient_feed
    ON system_notifications (recipient_user_id, created_at DESC)
    WHERE authorization_class = 'RECIPIENT';

ALTER TABLE visit_notification_outbox
    ADD COLUMN authorization_class VARCHAR(32) NOT NULL DEFAULT 'STAFF_LEGACY_QUARANTINED',
    ADD COLUMN operational_session_id BIGINT;

UPDATE visit_notification_outbox
   SET authorization_class = 'RECIPIENT'
 WHERE recipient_role = 'TENANT'
   AND EXISTS (
       SELECT 1
         FROM users u
        WHERE u.id = visit_notification_outbox.recipient_user_id
          AND u.role IN ('ROLE_TENANT', 'ROLE_LANDLORD')
          AND NOT EXISTS (SELECT 1 FROM employee_profiles ep WHERE ep.user_id = u.id)
   );

ALTER TABLE visit_notification_outbox
    ADD CONSTRAINT fk_visit_notification_outbox_operational_session
        FOREIGN KEY (operational_session_id) REFERENCES visit_sessions(id),
    ADD CONSTRAINT ck_visit_notification_outbox_authorization_context CHECK (
        authorization_class IN ('RECIPIENT', 'OPERATIONS_SESSION', 'STAFF_LEGACY_QUARANTINED')
        AND ((authorization_class = 'OPERATIONS_SESSION') = (operational_session_id IS NOT NULL))
    );

ALTER TABLE visit_notification_outbox
    DROP CONSTRAINT visit_notification_outbox_state_check,
    ADD CONSTRAINT ck_visit_notification_outbox_state CHECK (
        state IN ('QUEUED', 'PROCESSING', 'SENT', 'FAILED', 'SUPPRESSED')
    );
