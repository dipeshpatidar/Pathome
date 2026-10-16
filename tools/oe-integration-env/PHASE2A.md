# Phase 2A — disposable integration harness

2026-10-16 B09 update: owner-approved transactional scheduling receipts and OE reconciliation are
documented in [B09_SCHEDULING.md](B09_SCHEDULING.md); execution results are recorded separately in
B09_RESULTS.md. V50 adds scheduling receipts. The sections below describe the original pre-B09
harness/safety baseline and historical code-only scope; they do not claim current live acceptance.

## Shared safety architecture

`lib/runtime.sh` parses designation, credentials and identity files as literal allowlisted data, never
shell programs. Maven/Java, Node, curl and psql use a minimal `env -i` allowlist. Maven rc files,
unreviewed project `.mvn` configuration, inherited JVM/Node options, alternate Spring configuration,
datasource/Flyway overrides, proxies and curl rc configuration cannot redirect a prepared child.
Package 4 unit paths use this boundary too. Local staging and JVM/home directories stay under the run.

Every application-database connection crosses `oe_guard_psql`: protected names/prefixes, non-loopback
hosts, invalid ports, connection-argument overrides and non-designated targets fail before psql.
psql uses `-X -w`, controlled libpq settings and statement/lock/connect timeouts. Maintenance access
to `postgres` is limited to recognized catalog/version/user queries, exact target creation and guarded
target deletion. Catalog database names are not application-database contents. No protected database
is queried, dumped, fingerprinted or used as a comparison baseline.

Startup/migration, fixtures and deletion require live disposable identity and sentinel proof. Startup
and fixtures also require the current run marker. Recreate is guarded teardown plus create; teardown
stops only a positively bound backend before deletion. Probe deletion uses the same destructive guard.
Flyway expectations use actual migration filenames sorted numerically (V2 through V49).

`lib/runtime.mjs` binds run/database/media designation to the launched Maven parent, listener and saved
process identities. Occupied ports, stale/reused PIDs, broken ancestry, foreign listeners, expired binding
and mismatched attestation are refused. After process proof, login must return a JWT signed with the
per-run secret for the generated bootstrap identity. A 401/403 alone never establishes binding.
Authenticated curl and every Node/browser API request recheck process binding; suites require current
attestation. Only a credential-free GET readiness probe is allowed before binding. HTTP origins are
exact `http://127.0.0.1:<designated-port>`.

## Private credentials and approved disposable media

`preflight` validates an external owner-only mode-600 literal media file named by
`PATHOME_OE_TEST_MEDIA_ENV_FILE` before any database setup. It must contain only the five keys below.
`recreate` then generates a run ID and credentials in `/private/tmp/pathome-oe-test/secrets.env` (or the
validated designation directory), mode 600, outside Git. The directory is private. Files remain literal
KEY=VALUE data: never source them or copy `.env.local` into them. Generated bootstrap/staff passwords
and JWT keys are not operator inputs. Prior run IDs and secrets are retained in unique private `previous-runs/` directories,
not reused as this run. Pre-existing `secrets.previous.env` is preserved.

Before preflight an operator must configure these keys in the separate approved media file.
The launcher copies them into newly generated run credentials after prerequisites pass:

```text
PATHOME_OE_TEST_MEDIA_MODE=disposable-account
PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD=true
OE_TEST_CLOUDINARY_CLOUD_NAME=<approved throwaway account cloud name>
OE_TEST_CLOUDINARY_API_KEY=<that account's key>
OE_TEST_CLOUDINARY_API_SECRET=<that account's secret>
```

The account must be explicitly approved for disposable uploads with no real assets or production
coupling. No credentials are supplied or verified by this correction task. Missing approval/credentials,
the shipped real account and production/live/staging-like names are refused. Binding pins the approved
media cloud. Never use the existing real account, commit credentials, place them in command arguments
or print the private file. A harmless real image is required at `sample_upload_media/interior_living.jpg`
or `PATHOME_OE_TEST_MEDIA_FILE`.

Inherited S3, mail/SMTP, SMS, webhook, payment and other destination overrides are not forwarded.
No new sender/provider is enabled. Media upload is the deliberate future external effect and stays
explicitly gated. Reports store safe IDs/versions and field names, not tokens/passwords/contact data.
The local frontend build pins relative API/OAuth destinations and disables Google login; inherited
frontend destination values cannot redirect the disposable browser build.

## Fixtures, lifecycle and evidence

The API manifest retains 82 required IDs: 19 fixtures and 63 coverage scenarios across eleven areas.
Each gets a distinct PASS / FAIL / NOT TESTED record. Failed, missing, duplicate or unsupported entries
cannot contribute to complete acceptance. Reports identify actor/authority, actual method/endpoint/status,
required response fields, safe before/after state and current-run provenance. Ownership commands
require authoritative owner/status/version transitions. Availability must match the exact submitted
window/state/source. Nested denial objects/arrays cannot hide contact fields. Reservation snapshots
include end time, duration, zone and assignment facts to distinguish idempotency from mutation.
Static route matching is
consistency evidence, not acceptance.

Supported APIs register users, activate/grant staff, create Teams, publish two different listings through
real media/review, intake requests and route them. Distinct tenants create independent primary,
second-cycle, Admin-scheduling and browser roots; deduplicated intake is not assumed fresh.
Writes refresh versions. Coordination transfers ownership; release/reclaim tests precede further owned
mutations. Derived stops use valid originating requests, never duplicate listings, and preserve direct
stops. Tenant/property windows and GE shifts have compatible offsets. Discovery requires the actual
feasible fixture GE. Idempotent scheduling and stale rescheduling follow the real contracts.

No supported API assigns the legacy Admin/GE roles required by existing shell/scheduling authorization.
The owner-authorized exception is `fixture-roles.sh` / `sql/fixture-roles.sql`: explicit
`PATHOME_OE_TEST_FIXTURE_ROLES=true`, disposable identity/sentinel/run/backend proof, two freshly
API-registered/activated synthetic users, transactional locks, only necessary roles/profile type, and
two append-only operational audit records. No grant SQL, trigger bypass or production/migration change.
Fresh login verifies JWT roles and capabilities. The exception is fixture setup, not API coverage.

## API versus genuine React acceptance

`phase2a/fullstack.mjs` is API integration, not browser acceptance. Native fetch observes real bound
Spring Boot responses; none is fabricated. Prepared assertions cover capability/team scope, queues,
claim/release, detail/contact privacy, ownership versions, coordination, itinerary, availability, GE,
scheduling, denial, idempotency, conflicts and API cross-credential isolation. Requests have 15-second
deadlines and a nine-minute run budget.

`phase2a/browser.mjs` has 12 separate B01–B12 scenarios, requiring all 82 API entries to pass in the
same run. Chromium operates compiled React: real login/capability routing, queues, claim/release,
contact reveal, coordination, itinerary add/reorder/remove, availability, feasible GE selection,
scheduling, tenant confirmation and real account change with an outstanding old response.
No authenticated storage seeding or fabricated shell 403 remains. The old workspace runner delegates here.

The proxy ordinarily streams real status/headers/body unchanged. Two explicit transport controls hold
one old-account response until after account change and drop delivery of a genuine successful selection
acknowledgement. B09 labels the actual upstream result as acknowledgement-dropped, not received browser
success. B12 requires uncertain-outcome UI and authoritative reconciliation without a second selection
POST. These disrupt transport, not invent HTTP responses. Tenant confirmation uses real UI after
separately labeled Admin API setup rescheduling. Queue observers are installed before workspace mount or the claim that triggers reconciliation.
Queue navigation is never treated as a network trigger. B11 uses real browser Reload to remount React,
holds an actual old-account read, and checks stale-data isolation before a separate new-account reload.
Browser scope is 1280×900 Chromium, not a device audit.

## Code-only verification

```bash
bash tools/oe-integration-env/phase2a.sh verify-static
git diff --check
git diff --cached --check
```

The consolidated check includes syntax, guard refusal, injection controls, JDBC/HTTP origin controls,
inert process/listener/attestation probes, real migration ordering, fixture/manifest consistency,
assertion negatives and evidence accounting. It uses synthetic temporary data only, no server/database,
container, media request or real browser acceptance.

## Prerequisites before database setup

`run.sh preflight` is nonconnecting. It resolves trusted local tools, verifies the exact approved
loopback coordinates and vacant backend port, rejects stale runtime artifacts, checks the private run
and credential paths, validates the explicitly approved disposable-media data and a local JPEG (at most
10 MiB), and verifies installed Playwright/Chromium without launching a browser.

With explicit `PATHOME_OE_TEST_ALLOW_LOCAL_BUILD=true`, it runs offline Maven `test-compile
spring-boot:help` with tests skipped and performs the existing local frontend build. No installation,
Spring start, database access or media call occurs. Missing cache/dependencies fail before setup.
A private receipt binds source/build/image/media fingerprints and target coordinates for 15 minutes.
Both create and recreate require a current receipt before any database boundary, including teardown.

The preflight and all live commands have process-group watchdogs. Browser launch is bounded to 30s,
HTTP waits to 15s, a browser scenario to 45s and browser shutdown to 10s. The nine-minute overall browser
budget begins before launch. Backend shutdown has a 30s command limit; guarded teardown has 60s.
A timed-out child group gets TERM, then KILL after 3s, with a bounded final journal update.

## Evidence and interruption handling

Every supervised command gets `evidence/commands/<unique-attempt>/command.json`, recording target,
run identity, start/end status and checkpoints. Backend logs are unique to the startup attempt.
Prior sanitized fixed-path reports are preserved in each command attempt before and after execution.
API/browser reports live in unique `evidence/attempts/` directories. Failed attempts retain FAIL and
NOT TESTED results. Only a fully successful finalized API attempt publishes the browser handoff pointer;
its fixtures belong to that same attempt. A retry never overwrites another attempt's report.

`run.sh recover` first proves disposable database identity/sentinel/run, then verifies recorded process
ownership. A private startup record permits recovery before final HTTP binding. Dead owned artifacts
are archived; live reused PIDs and foreign/orphan listeners are refused. Processes that do not stop
within the deadline retain their artifacts and diagnostic evidence. Recovery leaves the database intact.
`teardown` repeats proofs and recovery before dropping only the positively marked designated database.
An already-absent database with remaining runtime artifacts refuses rather than reporting cleanup success.

The verifier no longer creates intentionally unmarked probe databases. Their refusal is tested with
inert subprocess controls. For a known legacy probe, `run.sh recover-probe <exact-name>` records a
quarantine/refusal and performs no database operation: old name-only evidence does not establish
ownership. Unmarked partial databases, foreign listeners, missing run proof or legacy unowned probes
require explicit operator resolution. Never add a sentinel, remove a PID file or disable a guard to
force cleanup. This is safe refusal, not successful teardown.

## Proposed future sequence — NOT EXECUTED

Use only after the code-only gate, with owner-approved throwaway Cloudinary credentials and upload
permission. Store the five literal media keys in the external mode-600 file first. Supply any required
`PATHOME_OE_TEST_DB_PASSWORD` privately; never put passwords in logged arguments or print/source files.
No command below has been executed by the correction task.

If an earlier owned run was interrupted, first preserve the reported diagnostic directories and run
`bash tools/oe-integration-env/run.sh recover`. Stop if it refuses. Then, from the repository root:

```bash
set -e
export PATHOME_OE_TEST_MEDIA_ENV_FILE=/private/tmp/pathome-oe-approved-media.env
export PATHOME_OE_TEST_ALLOW_LOCAL_BUILD=true
bash tools/oe-integration-env/run.sh preflight
bash tools/oe-integration-env/run.sh recreate
bash tools/oe-integration-env/run.sh backend
bash tools/oe-integration-env/run.sh provision
bash tools/oe-integration-env/run.sh verify
bash tools/oe-integration-env/run.sh attest
PATHOME_OE_TEST_PHASE2A_LIVE=true PATHOME_OE_TEST_FIXTURE_ROLES=true bash tools/oe-integration-env/phase2a.sh acceptance
PATHOME_OE_TEST_PHASE2A_LIVE=true bash tools/oe-integration-env/phase2a.sh browser
bash tools/oe-integration-env/run.sh stop-backend
bash tools/oe-integration-env/run.sh teardown
```

A failed command stops the batch. Preserve the private attempt reports and backend log. To stop and
remove a proven disposable failed run, execute these separately, checking each result:

```bash
bash tools/oe-integration-env/run.sh recover
bash tools/oe-integration-env/run.sh teardown
```

If recovery or teardown refuses, stop and retain all artifacts for explicit operator resolution.
Do not retry an uncertain API mutation. A new full attempt requires fresh preflight/recreate after
safe recovery; all earlier attempt evidence remains available. An expired/changed preflight receipt
requires another preflight before setup.

All 82 API entries, B01–B12, actual database/migration/fixture transactions, genuine process binding,
preflight builds/cache/browser installation, media validity/uploads and live recovery remain NOT TESTED
until the separately authorized next phase.
