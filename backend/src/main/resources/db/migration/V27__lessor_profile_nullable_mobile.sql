-- Cleanup: nullify any fabricated phone sentinel that may have been written
-- by an earlier version of V26 (before the ON CONFLICT fix and nullable change).
-- Safe to run on any environment: no-op if the sentinel is absent.
-- mobile_number is already nullable (enforced by V26 CREATE TABLE).

UPDATE lessor_profiles
SET mobile_number = NULL
WHERE mobile_number = '+91 9999999999';
