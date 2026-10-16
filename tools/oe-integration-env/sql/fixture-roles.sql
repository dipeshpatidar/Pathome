\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '15s';
CREATE TEMP TABLE fixture_inputs ON COMMIT DROP AS
SELECT :'target_db'::text AS database, :'run_id'::text AS run_id,
       :'admin_id'::bigint AS admin_id, :'ge_id'::bigint AS ge_id, :'tag'::text AS tag;
DO $$
DECLARE f fixture_inputs%ROWTYPE; u record;
BEGIN
  SELECT * INTO STRICT f FROM fixture_inputs;
  IF f.database <> 'pathome_oe_test_phase2a_20261015' OR current_database() <> f.database
      OR f.admin_id = f.ge_id OR f.tag !~ '^[a-f0-9]{10}$' THEN
    RAISE EXCEPTION 'Disposable fixture target refused';
  END IF;
  PERFORM 1 FROM public.pathome_oe_test_env WHERE env_marker = 'DISPOSABLE'
      AND db_name = current_database() AND run_id = f.run_id FOR SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Disposable fixture marker/run refused'; END IF;
  PERFORM id FROM users WHERE id IN (f.admin_id, f.ge_id) ORDER BY id FOR UPDATE;
  PERFORM id FROM employee_profiles WHERE user_id IN (f.admin_id, f.ge_id) ORDER BY id FOR UPDATE;
  IF (SELECT count(*) FROM users WHERE role = 'ROLE_TENANT' AND
      ((id = f.admin_id AND email = 'codex.p2a.admin.' || f.tag || '@pathome.test') OR
       (id = f.ge_id AND email = 'codex.p2a.ge.' || f.tag || '@pathome.test'))) <> 2 THEN
    RAISE EXCEPTION 'Only fresh synthetic fixture identities can receive legacy roles';
  END IF;
  IF (SELECT count(*) FROM employee_profiles WHERE user_id IN (f.admin_id, f.ge_id)
      AND staff_active AND role_type IS NULL) <> 2 THEN
    RAISE EXCEPTION 'Fixture staff must first be activated through the supported API';
  END IF;
  UPDATE users SET role = CASE WHEN id = f.admin_id THEN 'ROLE_ADMIN' ELSE 'ROLE_GROUND_BOY' END
    WHERE id IN (f.admin_id, f.ge_id);
  UPDATE employee_profiles SET role_type = 'GROUND_BOY', version = version + 1 WHERE user_id = f.ge_id;
  FOR u IN SELECT id, role FROM users WHERE id IN (f.admin_id, f.ge_id) LOOP
    INSERT INTO operational_audit_events(actor_kind, operator_reference, action_code, target_type,
      target_id, reason_code, details)
    VALUES ('DEPLOYMENT_OPERATOR', 'DISPOSABLE_TEST_FIXTURE', 'DISPOSABLE_ACTOR_ROLE_CREATED',
      'USER', u.id, 'DISPOSABLE_TEST_ONLY', jsonb_build_object('runId', f.run_id, 'role', u.role));
  END LOOP;
END $$;
COMMIT;
