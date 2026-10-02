ALTER TABLE visit_session_items
    ADD COLUMN removed_by_user_id BIGINT REFERENCES users(id);
