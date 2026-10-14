\set ON_ERROR_STOP on

-- Positive disposable marker.
--
-- This table is written immediately after the disposable database is created and before
-- any migration runs. It is the proof used by lib/db-guard.sh before any destructive
-- operation: a database that does not carry exactly this marker for its own name is
-- never dropped, reset or truncated.

CREATE TABLE IF NOT EXISTS pathome_oe_test_env (
    env_marker    text        PRIMARY KEY,
    db_name       text        NOT NULL,
    guard_version text        NOT NULL,
    run_id        text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_pathome_oe_test_env_marker CHECK (env_marker = 'DISPOSABLE')
);

INSERT INTO pathome_oe_test_env (env_marker, db_name, guard_version, run_id)
VALUES ('DISPOSABLE', :'oe_database', :'oe_guard_version', :'oe_run_id')
ON CONFLICT (env_marker) DO UPDATE
    SET db_name       = EXCLUDED.db_name,
        guard_version = EXCLUDED.guard_version,
        run_id        = EXCLUDED.run_id,
        created_at    = CURRENT_TIMESTAMP;

SELECT env_marker, db_name, guard_version, run_id
FROM pathome_oe_test_env;
