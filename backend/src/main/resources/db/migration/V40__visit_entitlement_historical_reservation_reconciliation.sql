-- Release only auditable, unused holds whose scheduled execution interval ended
-- before V40 and has no execution/arrival history. Ambiguous history stays held.
CREATE TEMP TABLE v40_stale_visit_reservations ON COMMIT DROP AS
SELECT l.account_id,
       l.user_id,
       l.session_id,
       s.started_at,
       s.arrived_at,
       s.entitlement_consumed_at,
       s.started_at IS NULL
       AND s.arrived_at IS NULL
       AND s.entitlement_consumed_at IS NULL
       AND NOT EXISTS (
           SELECT 1 FROM visit_execution_events e WHERE e.session_id = s.id
       ) AS safe_to_release
FROM visit_entitlement_ledger l
JOIN visit_sessions s ON s.id = l.session_id
WHERE l.event_type = 'RESERVE'
  AND l.reason_code IN ('LEGACY_CONFIRMED_BOOKING', 'SCHEDULE_CONFIRMED')
  AND s.status = 'SCHEDULED'
  AND s.reserved_end_at <= CURRENT_TIMESTAMP
  AND NOT EXISTS (SELECT 1 FROM visit_entitlement_ledger x
                  WHERE x.session_id = s.id AND x.event_type IN ('CONSUME', 'FORFEIT_NO_SHOW', 'RELEASE'));

-- Keep unclear stale cases visible in the authoritative account reconciliation flag.
UPDATE tenant_visit_entitlement_accounts a
SET reconciliation_required = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM v40_stale_visit_reservations s
    WHERE s.account_id = a.id AND NOT s.safe_to_release
);

CREATE TEMP TABLE v40_releasable_visit_reservations ON COMMIT DROP AS
SELECT s.account_id, s.user_id, s.session_id
FROM v40_stale_visit_reservations s
JOIN tenant_visit_entitlement_accounts a ON a.id = s.account_id
WHERE s.safe_to_release
  -- An account with any ambiguous stale hold is reconciled as a whole. This
  -- avoids attributing aggregate reserved balance to a different ledger row.
  AND NOT EXISTS (SELECT 1 FROM v40_stale_visit_reservations ambiguous
                  WHERE ambiguous.account_id = s.account_id AND NOT ambiguous.safe_to_release)
  AND s.started_at IS NULL
  AND s.arrived_at IS NULL
  AND s.entitlement_consumed_at IS NULL
  AND (SELECT count(*) FROM v40_stale_visit_reservations same_account
       WHERE same_account.account_id = s.account_id AND same_account.safe_to_release
         AND same_account.started_at IS NULL AND same_account.arrived_at IS NULL
         AND same_account.entitlement_consumed_at IS NULL) <= a.reserved_credits;

-- If account totals cannot safely absorb every eligible release, preserve the holds
-- and require explicit reconciliation instead of manufacturing balance capacity.
UPDATE tenant_visit_entitlement_accounts a
SET reconciliation_required = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE (SELECT count(*) FROM v40_stale_visit_reservations s
       WHERE s.account_id = a.id AND s.safe_to_release
         AND s.started_at IS NULL AND s.arrived_at IS NULL
         AND s.entitlement_consumed_at IS NULL) > a.reserved_credits;

CREATE TEMP TABLE v40_released_visit_reservations ON COMMIT DROP AS
WITH inserted AS (
    INSERT INTO visit_entitlement_ledger(account_id, user_id, session_id, event_type,
            available_delta, reserved_delta, reason_code, idempotency_key)
    SELECT account_id, user_id, session_id, 'RELEASE', 1, -1,
           'V40_STALE_HISTORICAL_SCHEDULED', 'RELEASE:' || session_id
    FROM v40_releasable_visit_reservations
    ON CONFLICT (idempotency_key) DO NOTHING
    RETURNING account_id, user_id, session_id
)
SELECT account_id, user_id, session_id FROM inserted;

UPDATE tenant_visit_entitlement_accounts a
SET available_credits = a.available_credits + released.release_count,
    reserved_credits = a.reserved_credits - released.release_count,
    version = a.version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM (
    SELECT account_id, count(*)::integer AS release_count
    FROM v40_released_visit_reservations
    GROUP BY account_id
) released
WHERE a.id = released.account_id
  AND a.reserved_credits >= released.release_count;

UPDATE users u
SET free_visits_remaining = a.available_credits
FROM tenant_visit_entitlement_accounts a
WHERE a.user_id = u.id
  AND EXISTS (SELECT 1 FROM v40_released_visit_reservations r WHERE r.account_id = a.id);

-- A conflicting pre-existing release key or inconsistent reserved total is not
-- silently repaired: retain the remaining case for Operations reconciliation.
UPDATE tenant_visit_entitlement_accounts a
SET reconciliation_required = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM v40_releasable_visit_reservations r
    WHERE r.account_id = a.id
      AND NOT EXISTS (SELECT 1 FROM v40_released_visit_reservations done
                      WHERE done.session_id = r.session_id)
);
