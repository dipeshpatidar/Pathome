-- V29: Add lessor workflow notification support to system_notifications
ALTER TABLE system_notifications ADD COLUMN IF NOT EXISTS listing_id BIGINT;
ALTER TABLE system_notifications ADD COLUMN IF NOT EXISTS revision_id VARCHAR(120);
ALTER TABLE system_notifications ADD COLUMN IF NOT EXISTS action_type VARCHAR(64);
ALTER TABLE system_notifications ADD COLUMN IF NOT EXISTS action_target VARCHAR(255);
ALTER TABLE system_notifications ADD COLUMN IF NOT EXISTS read_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_notif_recipient_read ON system_notifications (recipient_user_id, is_read);
CREATE INDEX IF NOT EXISTS idx_notif_listing ON system_notifications (listing_id);
