# Phase 2A — Final Correction Batch Report

The final blocker-closure addendum below supersedes the earlier proposed execution sequence and
evidence paths. Earlier sections are retained as historical claims and results.

2026-10-15 IST. Repository: /Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0
Branch: feature/phase2a-oe-ui-integration
Baseline and final HEAD: 23cbdfe8722516daaf474d58a5113e4da71d333a

## Outcome and next gate

The eight confirmed failure classes have been addressed in harness/test code as one shared safety,
fixture, lifecycle and evidence design. Code-only verification passes. This is not independent safety
approval or live integration acceptance. HOLD live execution pending the requested focused independent
Sol review and approved disposable-media configuration. No production frontend re-audit was performed.

The package implementation skill guided shared-invariant design, bounded verification, independent
review handoff and changelog accountability. The explicit task restriction took precedence over its
normal database/build/remote/checkpoint stages: no live testing, remote mutation or Git checkpoint.

## Eight-finding correction map

1. **Fail-closed subprocess configuration.** Literal allowlisted file parsing replaces shell sourcing.
   Minimal child environments, trusted tool resolution, isolated HOME/JVM directories, Maven rc/.mvn
   refusal, explicit Spring datasource/Flyway/JWT/profile/local-staging pins and controlled curl/libpq
   settings apply across all launchers, including Package 4 unit paths.
   Evidence: lib/runtime.sh:8,49,62,97; lib/env-isolation.sh:245; package4.sh:78.
   Negative controls cover all 19 identified inherited injection channels, unknown webhook inheritance,
   executable/duplicate/unknown/symlinked data, missing-key inheritance and external curl destinations.

2. **Every database connection guarded.** The lowest psql boundary resolves designation and rejects
   protected names/prefixes, wrong targets, malformed ports and separate or attached connection override
   arguments before a subprocess. It uses psql -X -w and controlled PG settings. Maintenance permits
   exactly one recognized statement, not additional commands/files. Guard failure terminates deletion.
   Startup/migrations/fixtures require identity/sentinel/run proof; recreate and probe cleanup use
   mandatory destructive checks. Diagnostics/status are guarded too.
   Evidence: lib/db-guard.sh:205,230,291; run.sh:302,323; lib/verify.sh:117.
   Negative controls use inert subprocess stubs: protected names, overrides, multiple maintenance
   commands, maintenance files, failed drop proof, fake JDBC loopback hosts, ports and parameters.

3. **Actual disposable-backend binding.** Generated run ID/marker/secrets, launched Maven process,
   listener/ancestry/process identity, run-signed bootstrap JWT and target attestation form one binding.
   Occupied ports and stale PID files are refused; authenticated clients recheck process binding.
   A generic 401/403 is not proof. Origins are exact designated HTTP loopback origins.
   Evidence: lib/runtime.mjs:73,103; run.sh:323,454; lib/runtime.sh:114.
   Inert probes demonstrate refusal for reused PIDs, foreign listeners, broken ancestry, expired
   bindings and foreign/duplicate attestation values. JWT tests cover JJWT HS256/384/512 behavior.
   No actual backend process/listener was inspected or started by these probes.

4. **Numeric Flyway ordering.** Version numbers are extracted and sorted numerically, with the same
   repository-derived sequence used by verification rather than lexicographic filename ordering.
   Evidence: run.sh:145; lib/verify.sh:159.
   Nonconnecting regression reads the real repository filenames and proves V2 through V49 in order.

5. **Legitimate independent fixtures.** Four distinct tenant identities produce independent roots;
   a second real listing avoids duplicate itinerary listings. APIs create users/activation/grants/Teams,
   media/review/intake/routing. No supported role-assignment API exists for legacy Admin/GE roles.
   The explicitly authorized exception is a guarded transactional fixture for exactly two freshly
   API-registered/activated synthetic users; it assigns only the necessary roles/profile type and appends
   two audit events. No grants/triggers/production provisioning/migrations are altered.
   Subsequent login proves actual ROLE_ADMIN/ROLE_GROUND_BOY plus live capability facts. Admin-only
   scheduling-profile commands use the Admin credential.
   Evidence: fixture-roles.sh:1; sql/fixture-roles.sql:1; phase2a/fullstack.mjs:162.
   SQL and fixture transactions remain NOT TESTED; static guards/fixture boundaries are verified.

6. **One coherent scenario lifecycle.** C13 uses claimBaseVersion. Coordination ownership transfer,
   release/reclaim order, independent second/Admin/browser roots, refreshed versions, valid originating
   Request IDs, distinct derived listings and direct-stop preservation are explicit. Admin scheduling
   is separate from OE selection; identical retries assert idempotency, stale rescheduling changes the
   requested time. Tenant/property availability and GE shifts use intersecting +05:30 windows.
   Requests are bounded to 15 seconds with a nine-minute run budget.
   Evidence: phase2a/manifest.mjs:691; phase2a/fullstack.mjs:239; phase2a/evidence.mjs:15.
   Static lifecycle and offset-window controls pass; real backend state transitions remain NOT TESTED.

7. **Truthful acceptance evidence.** Every fixture/coverage ID has a distinct execution record with
   actor/authority, actual method/endpoint/status, required response field names, safe authoritative
   state and PASS/FAIL/NOT TESTED. Supporting API calls and the explicit SQL fixture exception are labeled.
   C06/C08/C26/C35/C61/C62 assertions are corrected; missing/hidden errors, contact privacy, nonmutation
   on denial, actual feasible GE identity, IDs/versions and idempotency are checked. C61 is explicitly API
   cross-credential isolation, not a browser switch. Missing/failed/duplicate records cannot yield success.
   Evidence: phase2a/evidence.mjs:25,50; phase2a/fullstack.mjs:39; phase2a/manifest.mjs:691.
   Negative controls reject null-success JSON, stale C13 data, contact leaks, changed denial state,
   empty GE candidates and false accounting. Route mappings alone are not acceptance.

8. **Genuine React acceptance.** Twelve separately counted B scenarios use real forms/screens and live
   API responses. No authenticated browser storage seeding, fabricated shell 403 or replacement cache
   headers remain in the real-backend workspace path. The legacy runner delegates to the new browser
   suite. Related legacy harnesses also require binding and preserve actual backend cache headers;
   their existing proxy transport is not counted as this new browser acceptance.
   Evidence: phase2a/browser.mjs:10; frontend-web/browser-verification/operations-workspace-real-backend/run.mjs:1.
   The normal proxy streams actual status/headers/body. Explicit transport controls hold one actual
   old-account response and drop a genuine successful selection acknowledgement. They fabricate no
   HTTP response. Uncertainty requires UI reconciliation without a second selection POST. Tenant
   confirmation uses genuine UI following a separately labeled Admin API setup reschedule.
   Hardcoded fixture registration credentials are removed. All real browser behavior is NOT TESTED.

All paths above are relative to tools/oe-integration-env unless prefixed frontend-web.

## Verification actually executed

Final consolidated command:
```bash
bash tools/oe-integration-env/phase2a.sh verify-static
git diff --check
git diff --cached --check
```

- Static Node suite: **32/32 PASS**, zero failed/cancelled/skipped/todo, approximately 3.8 seconds.
- Database guard self-tests: **13/13 PASS**.
- Package 3 guard self-tests: **10/10 PASS**.
- Environment isolation self-tests: **19/19 PASS**.
- Shell syntax and JavaScript syntax: PASS through the consolidated suite.
- Working and staged whitespace checks: PASS (no staged changes).
- Refusal tests only touch synthetic temporary files or inert stubs; no database/network/media call.
- One earlier consolidated pass also passed; final self-review tightened attached psql argument and
  maintenance multi-command refusal plus test-context JWT pinning, then the final pass above passed.
  Self-tests are also invoked within the static suite; these are not distinct integration passes.
- No Maven, npm build, PostgreSQL, Docker, Spring Boot, upload or live browser command was executed.

## Final counts and coverage distinction

**82 API entries = 19 fixture steps + 63 coverage scenarios**, eleven areas. All 82 retain their IDs.
F14 includes the explicit audited disposable role fixture, so not every setup operation is an HTTP API.

**12 separate React browser scenarios (B01–B12)**: login/capability routing; queues; claim/release;
lessor contact; coordination; itinerary add/reorder/remove; availability; feasible GE discovery/selection;
scheduling acknowledgement uncertainty; authoritative reconciliation without resend; tenant confirmation;
real cross-tab account change/stale response isolation. Browser viewport: 1280×900 Chromium only.

API evidence is phase2a-api.json; browser evidence is phase2a-browser.json under the private run evidence
directory. Neither report has been generated by a live run in this task. Static tests use temporary
synthetic report data only. Prepared counts must not be reported as live PASS counts.

## Remaining NOT TESTED and owner prerequisite

- All F01–F19, C01–C63 and B01–B12 against actual Spring Boot/PostgreSQL.
- Create/recreate/drop/probe cleanup, real sentinel/run/process binding, startup, actual Flyway application,
  role/audit SQL, provisioning, concurrency/ownership transitions, contact privacy and scheduling outcomes.
- Real external media account validity/upload, actual backend logging and end-to-end secret non-disclosure.
- Maven/JUnit, frontend build, responsive/device/accessibility/visual verification (production UI unchanged).
- Actual installed-runtime/cache/browser prerequisites and private account credentials.

No new architecture decision is requested. The narrow role fixture was expressly authorized by the task.
The owner/operator still must supply an approved **throwaway Cloudinary account**, explicitly permit its
uploads and place its credentials only in the private mode-600 run secrets file. The real shipped account
and production-like destinations are refused. No real credentials were read, reused or exposed.

The exact proposed sequence below is NOT EXECUTED and requires the independent safety gate first.

Required literal keys in the external, owner-only mode-600 secrets.env:
```text
PATHOME_OE_TEST_MEDIA_MODE=disposable-account
PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD=true
OE_TEST_CLOUDINARY_CLOUD_NAME=<approved disposable cloud>
OE_TEST_CLOUDINARY_API_KEY=<private key for that disposable cloud>
OE_TEST_CLOUDINARY_API_SECRET=<private secret for that disposable cloud>
```

First create the guarded run from the repository root:
```bash
bash tools/oe-integration-env/run.sh recreate
```

STOP and configure the media keys privately; never source/print secrets.env. If database authentication
needs PATHOME_OE_TEST_DB_PASSWORD, supply it privately, not in logged command arguments. Confirm a
harmless image, current frontend-web/dist, installed Chromium/Playwright and offline Maven cache.
Only after those prerequisites, run one fail-fast shell batch:
```bash
set -e
bash tools/oe-integration-env/run.sh backend
bash tools/oe-integration-env/run.sh provision
bash tools/oe-integration-env/run.sh verify
bash tools/oe-integration-env/run.sh attest
PATHOME_OE_TEST_PHASE2A_LIVE=true PATHOME_OE_TEST_FIXTURE_ROLES=true bash tools/oe-integration-env/phase2a.sh acceptance
PATHOME_OE_TEST_PHASE2A_LIVE=true bash tools/oe-integration-env/phase2a.sh browser
bash tools/oe-integration-env/run.sh stop-backend
bash tools/oe-integration-env/run.sh teardown
```

On failure stop, preserve private evidence and resolve the failing prerequisite; do not bypass guards,
count missing scenarios as PASS or automatically resend uncertain mutations. Cleanup also fails closed
for unbound/ambiguous processes. Stale artifacts are never automatically trusted or deleted.

## Exact files changed by this correction batch

Paths relative to /Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0:
```text
chat_gpt_changes
frontend-web/browser-verification/listing-publication-journey/run.mjs
frontend-web/browser-verification/operations-governed-intake/run.mjs
frontend-web/browser-verification/operations-governed-provisioning/run.mjs
frontend-web/browser-verification/operations-workspace-real-backend/README.md
frontend-web/browser-verification/operations-workspace-real-backend/run.mjs
tools/oe-integration-env/README.md
tools/oe-integration-env/PHASE2A.md
tools/oe-integration-env/journey.mjs
tools/oe-integration-env/lib/db-guard.sh
tools/oe-integration-env/lib/env-isolation.sh
tools/oe-integration-env/lib/package3-verify.sh
tools/oe-integration-env/lib/package3b-verify.sh
tools/oe-integration-env/lib/runtime.mjs
tools/oe-integration-env/lib/runtime.sh
tools/oe-integration-env/lib/verify.sh
tools/oe-integration-env/oe-test.env
tools/oe-integration-env/package3.sh
tools/oe-integration-env/package4.sh
tools/oe-integration-env/phase2a.sh
tools/oe-integration-env/fixture-roles.sh
tools/oe-integration-env/phase2a/browser.mjs
tools/oe-integration-env/phase2a/evidence.mjs
tools/oe-integration-env/phase2a/fullstack.mjs
tools/oe-integration-env/phase2a/manifest.mjs
tools/oe-integration-env/sql/fixture-roles.sql
tools/oe-integration-env/tests/oe-harness-static.test.mjs
tools/oe-integration-env/CORRECTION_REPORT.md
```

28 files including this report/log. Seven newly created files: fixture-roles.sh, lib/runtime.sh,
lib/runtime.mjs, phase2a/browser.mjs, phase2a/evidence.mjs, sql/fixture-roles.sql and CORRECTION_REPORT.md.
Other affected files may already have been modified/untracked when this batch began.
Pre-existing PACKAGE3.md, PACKAGE4.md, expected-schema.txt and phase2a/manifest-report.mjs were preserved
without correction-batch edits. Existing unrelated work was not stashed/reset/discarded.

## Exact final Git worktree status

```text
 M chat_gpt_changes
 M frontend-web/browser-verification/listing-publication-journey/run.mjs
 M frontend-web/browser-verification/operations-governed-intake/run.mjs
 M frontend-web/browser-verification/operations-governed-provisioning/run.mjs
 M frontend-web/browser-verification/operations-workspace-real-backend/README.md
 M frontend-web/browser-verification/operations-workspace-real-backend/run.mjs
 M tools/oe-integration-env/PACKAGE3.md
 M tools/oe-integration-env/PACKAGE4.md
 M tools/oe-integration-env/README.md
 M tools/oe-integration-env/journey.mjs
 M tools/oe-integration-env/lib/db-guard.sh
 M tools/oe-integration-env/lib/package3-verify.sh
 M tools/oe-integration-env/lib/package3b-verify.sh
 M tools/oe-integration-env/lib/verify.sh
 M tools/oe-integration-env/oe-test.env
 M tools/oe-integration-env/package3.sh
 M tools/oe-integration-env/package4.sh
 M tools/oe-integration-env/run.sh
?? tools/oe-integration-env/CORRECTION_REPORT.md
?? tools/oe-integration-env/PHASE2A.md
?? tools/oe-integration-env/expected-schema.txt
?? tools/oe-integration-env/fixture-roles.sh
?? tools/oe-integration-env/lib/env-isolation.sh
?? tools/oe-integration-env/lib/runtime.mjs
?? tools/oe-integration-env/lib/runtime.sh
?? tools/oe-integration-env/phase2a.sh
?? tools/oe-integration-env/phase2a/
?? tools/oe-integration-env/sql/fixture-roles.sql
?? tools/oe-integration-env/tests/
```

No source staging, commit, push, merge, deployment, new dependency or migration edit. HEAD is unchanged.
Production authentication/authorization and UI/UX code are untouched; their live behavior is not claimed
reverified. Security changes are harness-local; no production query/cache/performance path is changed.


## Final blocker-closure addendum — 2026-10-15 21:45 IST

Owner-authorized scope: close the independent GPT-6 HOLD findings in one targeted code-only batch.
Baseline and final HEAD: `23cbdfe8722516daaf474d58a5113e4da71d333a`.
Branch: `feature/phase2a-oe-ui-integration`. This addendum supersedes prior setup instructions.
The original bypasses were independently reproduced with inert subprocess boundaries in the preceding
verification turn; the focused regression batch below now proves refusal and legitimate controls.

### Root causes and complete corrections

1. **HTTP destination parsing.** The old scan recognized positional URL strings but missed `--url=`
   and scheme-less option values. `runtime.sh` now parses a small argument grammar: exactly one
   validated designated loopback URL, necessary methods/JSON/auth/output/deadline arguments only.
   URL overrides, extra URLs, config files, redirects and unexpected transfer controls refuse before
   curl starts. Values are consumed as data; a URL-looking string inside legitimate JSON is harmless.
   Curl's rc/proxy/protocol/deadline isolation is preserved.

2. **Actual PostgreSQL operation.** `psql -X` did not prohibit explicit reconnects. The lowest boundary
   now accepts one operation and reviewed flags/variables only, refuses backslash meta-commands in
   command text, and accepts `-f` only for the exact reviewed baseline, marker and role-fixture paths.
   Reviewed files permit only their existing inert `\set ON_ERROR_STOP on` directive; includes,
   reconnects and shell escapes are refused. Maintenance SQL, exact JDBC/target proof, `-X -w`,
   sentinel/run/destructive checks and the supported Flyway startup remain intact.

3. **Acceptance postconditions.** C12 previously trusted status; C41 trusted timestamp presence; C28
   searched only top-level contact keys. Claim/release now require authoritative owner/state/version
   transitions. My Work membership is recorded explicitly as an observed owner, rather than pretending
   the DTO contains a coordinator field. C41 compares returned and independently reread availability
   state/start/end/zone/source with the submitted values, accepting equivalent timestamp offsets and
   legitimate already-matching idempotency. Contacts are checked recursively through arrays/objects.
   Required mutations must advance authoritative state and agree with rereads. Idempotent reservation
   snapshots include end, duration, zone, assignment and tenant-confirmation facts.

4. **Genuine React triggers.** Queue reads are observed before opening Operations or before the claim
   that causes reconciliation. B02/B03 no longer assume queue navigation fetches. B11 uses actual browser
   Reload on the Operations route to remount React and start a fresh held old-account request, proves
   that request remained outstanding until the real switch began, and checks stale-data isolation before
   a separate new-account reload. Genuine forms, backend status/headers/body and explicitly labeled API
   setup/acknowledgement interruption remain unchanged. No login storage seeding or fabricated response.

5. **Prerequisites before setup.** New `readiness.mjs` validates private paths, exact approved coordinates,
   vacant port, media approval/literal credentials, installed Playwright/Chromium and a bounded JPEG.
   `preflight` resolves trusted tools, then performs offline Maven test compilation/plugin help and an
   explicitly approved local frontend build without starting Spring or running tests. The build pins
   relative API/OAuth destinations and disables Google login. A 15-minute receipt binds source/build,
   image/media and target facts. Both create/recreate refuse missing/stale/changed prerequisites before
   any database boundary. Credentials are prepared in an external mode-600 file before setup.

6. **Interruption, history and deadlines.** Commands have unique private journals with run/target,
   checkpoints and final status. Startup ownership is recorded before complete HTTP binding; recovery
   verifies database/sentinel/run and process identity, handles dead owned records, and archives original
   runtime bytes. Reused PIDs, foreign/orphan listeners and ambiguous proof refuse. Prior run credentials
   and sanitized fixed-path reports are preserved in unique archives. API/browser attempts have unique
   reports and interruption status; only the latest fully successful API attempt enables browser handoff.
   Failed retries cannot reuse an earlier success pointer. Atomic writes use unique temporary paths.
   The live unmarked guard probe was removed; inert tests exercise its refusal without creating an
   interruption orphan. Known legacy unowned probes are explicitly quarantined, never relabeled/deleted.
   Browser budget starts before launch; launch/HTTP/scenario/shutdown and backend shutdown/teardown
   are independently bounded. A command process-group watchdog prevents hangs, including startup and
   cleanup failures. Inherited supervision flags require matching private active PID/command proof.

### Verification actually executed — one consolidated nonconnecting batch

```bash
bash tools/oe-integration-env/phase2a.sh verify-static
git diff --check
git diff --cached --check
```

Executed in one clean-environment Python wrapper with a 120-second command timeout.
Whole batch: **5.04 seconds**. Node TAP: **53/53 PASS**, zero failures/cancelled/skipped/todo,
approximately 3.79 seconds. This includes 32 existing and 21 new focused regression tests.
DB guard self-tests: 13/13; Package 3 guard: 10/10; environment isolation: 19/19. Those self-tests also
run inside the Node suite and are not separate integration results. Shell and JS syntax passed.
Final self-review identified one additional absent-database edge case: teardown could return success
while runtime artifacts remained. It now refuses and preserves those artifacts. The focused
`node --test --test-reporter=tap --test-name-pattern='absent database with leftover runtime proof'
tools/oe-integration-env/tests/oe-blocker-closure.test.mjs` control passed (1/1, zero failures/skips),
covering all three leftover artifact types plus a clean absent-database legitimate case. This focused
check was repeated after tightening its inert logging assertion; no broader suite was repeated.
Thus the executed evidence is the 53-test consolidated batch plus this additional focused regression;
a 54-test complete-suite run is NOT claimed. Final `bash -n tools/oe-integration-env/run.sh` also passed.

Working and staged whitespace checks passed; the index is empty. Final documentation/log changes
receive a final whitespace check, not another test-suite run.

What the tests actually exercised:

- Original external curl option probes, config/transfer options, extra destinations and injected headers
  refuse before an inert child; legitimate anonymous readiness, bearer reads and JSON POSTs reach it.
- Reconnect/include/shell/connection-override/unreviewed-file/variable probes refuse before inert psql;
  all three reviewed SQL files, ordinary SQL and permitted maintenance catalog queries remain supported.
- Missing-sentinel refusal executes real guard logic against an inert zero-count result, with no database.
- C12 rejects unchanged/wrong-owner/wrong-root/wrong-version state and accepts the exact transition.
  C41 rejects incorrect persisted state and accepts exact offset-equivalent or valid idempotent state.
  C28 rejects nested object/array contact data and accepts generic error detail. Reservation changes
  cannot pass identical-retry assertions.
- Evidence retries preserve prior bytes and accurate counts, failed latest attempts cannot borrow prior
  success, recovery archives refuse overwrite, and command journals retain interruption facts.
- Partial/dead backend recovery plans accept owned synthetic identities and reject foreign/reused/orphan
  controls. No real backend process was killed. Only an inert Node timeout-control process was terminated.
- Stubbed create/recreate stop before any database boundary when preflight fails. Synthetic receipts
  reject missing/stale/changed prerequisites. Synthetic media files enforce approval/keys/permissions.
- Browser queue observer filtering/registration was exercised with an inert page API; trigger placement,
  genuine transport and timeout boundaries were checked in source. No browser was launched.
- A hanging inert Node child is terminated by the actual watchdog; a normal inert child completes.

### Previously passing gates preserved

Backend process/listener/JWT/attestation binding tests remain green. Real repository Flyway filenames
still order V2–V49 numerically. The Admin/GE fixture SQL and supported activation/grant flow are unchanged;
reviewed SQL remains accepted by the stricter boundary. All 82 IDs, actors, corrected contract mappings,
root separation, ownership ordering and timezone intersections remain covered. No production backend,
OE frontend behavior/layout, migration, dependency or business rule was modified.

### Exact files changed by this closure batch

Paths relative to repository root. Three files are new in this batch; pre-existing uncommitted work is
preserved. The report and changelog are updated, not replaced.

```text
chat_gpt_changes
tools/oe-integration-env/CORRECTION_REPORT.md
tools/oe-integration-env/PHASE2A.md
tools/oe-integration-env/README.md
tools/oe-integration-env/lib/db-guard.sh
tools/oe-integration-env/lib/lifecycle.mjs                 (new)
tools/oe-integration-env/lib/readiness.mjs                 (new)
tools/oe-integration-env/lib/runtime.mjs
tools/oe-integration-env/lib/runtime.sh
tools/oe-integration-env/lib/verify.sh
tools/oe-integration-env/phase2a.sh
tools/oe-integration-env/phase2a/browser.mjs
tools/oe-integration-env/phase2a/evidence.mjs
tools/oe-integration-env/phase2a/fullstack.mjs
tools/oe-integration-env/phase2a/manifest.mjs
tools/oe-integration-env/run.sh
tools/oe-integration-env/tests/oe-blocker-closure.test.mjs  (new)
tools/oe-integration-env/tests/oe-harness-static.test.mjs
```

### Disposable Cloudinary prerequisite and proposed next sequence — NOT EXECUTED

The owner must approve a throwaway account with no real assets or production coupling and explicitly
permit its uploads. Actual credentials/account validity have not been inspected. Privately prepare
`/private/tmp/pathome-oe-approved-media.env`, owned by the operator, mode 600, with only:

```text
PATHOME_OE_TEST_MEDIA_MODE=disposable-account
PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD=true
OE_TEST_CLOUDINARY_CLOUD_NAME=<approved disposable cloud>
OE_TEST_CLOUDINARY_API_KEY=<that disposable account key>
OE_TEST_CLOUDINARY_API_SECRET=<that disposable account secret>
```

Never source/print that file, copy production credentials or place secrets in logged arguments.
Supply any required database password privately. Required local tools/cache/dependencies, harmless JPEG,
installed Chromium/Playwright and local-build approval must satisfy preflight first. The shipped real
cloud, production-like names, missing opt-in and insecure/unknown/duplicate data fail closed.

For an interrupted owned prior run, preserve its diagnostics and run `run.sh recover` first; stop if it
refuses. Then the proposed sequence from the repository root is:

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

On failure: stop the batch, preserve `evidence/commands/`, `evidence/attempts/` and the startup log.
Execute `bash tools/oe-integration-env/run.sh recover`, check success, then execute
`bash tools/oe-integration-env/run.sh teardown`. Both remain guarded and bounded. Do not automatically
resend uncertain mutations. Do not delete proof files, manufacture sentinels or disable guards.
An unmarked partial database, unbound orphan listener or legacy unowned probe is quarantined for
explicit operator resolution; this is intentional safe refusal and never a claimed successful cleanup.
`run.sh recover-probe <exact-legacy-name>` records that refusal without connecting to PostgreSQL.
A fresh complete attempt uses preflight/recreate after safe recovery and preserves all prior evidence.

### Remaining NOT TESTED, operational limits and Git status

NOT TESTED: actual prerequisite tool/cache/Chromium/media-account validity, offline Maven/frontend builds,
PostgreSQL connections or create/drop/migrations, real startup/binding/shutdown/recovery, fixture SQL/audit,
all 82 API entries, B01–B12, uploads, physical-device/responsive/accessibility behavior and actual backend
log secrecy. These need the separate authorized live phase, not another full-project code audit.

No unresolved code-only blocker was observed by this targeted batch. Installed prerequisites and approved
media credentials remain operator prerequisites; no claim of live API/browser readiness or acceptance is
made. Unknown/unmarked/foreign artifacts require operator resolution if encountered; none were inspected
by contacting PostgreSQL or a running application in this task.

The original dirty worktree is retained. No staging, commit, push, merge, deployment, stash or reset.
HEAD and branch are unchanged; index remains empty. Only the 18 listed harness/report/log files changed
in this closure batch. Existing frozen frontend, fixture SQL, migrations and unrelated work are preserved.
