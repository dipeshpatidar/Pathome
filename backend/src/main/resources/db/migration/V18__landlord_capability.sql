-- Additive, same-account landlord capability. Existing tenant/admin roles and tokens stay valid.
ALTER TABLE users ADD COLUMN landlord_activated_at TIMESTAMP;
ALTER TABLE users ADD COLUMN landlord_activated_by_user_id BIGINT;

ALTER TABLE users ADD CONSTRAINT ck_landlord_activation_audit
    CHECK ((landlord_activated_at IS NULL) = (landlord_activated_by_user_id IS NULL));

ALTER TABLE users ADD CONSTRAINT fk_landlord_activation_actor
    FOREIGN KEY (landlord_activated_by_user_id) REFERENCES users(id);
