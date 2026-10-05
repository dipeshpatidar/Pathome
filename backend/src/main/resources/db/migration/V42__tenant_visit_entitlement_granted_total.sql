-- Historical V39 balances do not prove the original grant. Keep legacy totals
-- unresolved; newly granted accounts record their own total atomically.
ALTER TABLE tenant_visit_entitlement_accounts
    ADD COLUMN total_granted_credits INTEGER
    CONSTRAINT chk_tenant_visit_entitlement_total_nonnegative
    CHECK (total_granted_credits >= 0);
