# Pathome disposable OE integration environment

The authoritative current execution contract is [PHASE2A.md](PHASE2A.md).
Only a positively identified disposable database is usable. No protected application database is
read, dumped, fingerprinted, migrated or deleted. Maintenance `postgres` catalog operations are
narrowly allowlisted; they are not access to other application-database contents.

## Entry points

- `run.sh`: prerequisite preflight, guarded create/recreate, backend startup/binding, provision, verify,
  attest, bounded stop/teardown and explicit interrupted-run recovery.
- `package3.sh`, `package4.sh`: package runners using the same subprocess/data boundary.
- `phase2a.sh verify-static`: consolidated nonconnecting syntax and negative controls.
- `phase2a.sh acceptance`: explicit live opt-in; 19 fixtures and 63 API coverage scenarios.
- `phase2a.sh browser`: 12 separate genuine React scenarios, after API success.

## Shared boundaries

`lib/runtime.sh` handles literal allowlisted configuration and minimal subprocess environments.
`lib/env-isolation.sh` pins Spring configuration, datasource/JWT/media and local staging.
`lib/db-guard.sh` checks every psql target and destructive proof. `lib/runtime.mjs` binds the launched
process/listener, per-run JWT and current target attestation. `lib/verify.sh` compares disposable facts
only with repository schema/migration artifacts.

HTTP arguments are allowlisted around exactly one validated loopback destination. PostgreSQL
reconnect/execution/include meta-commands and unreviewed SQL files are refused before a child starts.
`lib/readiness.mjs` binds prerequisite facts before destructive setup. `lib/lifecycle.mjs` provides
bounded command supervision and unique private attempt journals. Retries preserve prior evidence.

`oe-test.env` is nonsecret data, never executable shell configuration. Runtime secrets, run IDs,
process binding and private evidence stay outside Git under `/private/tmp/pathome-oe-test` by default.
`.env.local` is never imported. Generated credentials cannot fall back to inherited values.

Fixtures use supported APIs except the explicitly authorized, audited disposable-only Admin/GE role
exception in `fixture-roles.sh` / `sql/fixture-roles.sql`. No production authorization/migration changes.
Staff grants remain API-only. The old workspace browser runner delegates to genuine React acceptance
without seeded login or fabricated shell responses.

## Code-only command

```bash
bash tools/oe-integration-env/phase2a.sh verify-static
```

This starts no database/server/container/browser and makes no media request. Static success is not live
acceptance. Future commands and the exact disposable-media prerequisite are in PHASE2A.md; never use
the existing real Cloudinary account.

Package 3/4 documents retain package-specific workflows. Where old setup descriptions differ, the
shared safety boundary and current PHASE2A.md prerequisites take precedence. Old unbound artifacts
fail closed rather than being reused. No commit, push or deployment is part of this batch.
