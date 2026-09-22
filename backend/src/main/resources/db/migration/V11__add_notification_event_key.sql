-- V11: Add event_key column and unique index to system_notifications for idempotent event publishing
ALTER TABLE system_notifications ADD COLUMN IF NOT EXISTS event_key VARCHAR(120);

CREATE UNIQUE INDEX IF NOT EXISTS uk_notif_event_key ON system_notifications (event_key);
