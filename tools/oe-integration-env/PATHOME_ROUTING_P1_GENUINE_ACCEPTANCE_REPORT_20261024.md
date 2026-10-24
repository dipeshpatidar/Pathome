# PATHOME — ROUTING P1 GENUINE ACCEPTANCE REPORT

Date: 24 October 2026, IST  
Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
Branch/HEAD: `feature/phase2a-oe-ui-integration` / `a943775e07a153087d0b38c315db20e18b30aec5`

## A. Source and target preflight

- Git index empty; 280 pre-existing dirty-worktree entries preserved; no stage, commit, push, reset, stash or branch switch.
- Prior source archive SHA-256: `62ea0f59645df7d874fe6766f107753e450a52a893b64d71fbe9d345132ff44b`.
- Prior source manifest SHA-256: `0f776300ffb6543c6859d53156a1750d082563faeedb45c5d798872838c8e685`.
- Prior inventory: 1,586 files.
- V53 remains `15b856ad...d640d3`; V54 remains `50136181...de900`.
- Exact target designation had mode 600 and positive/negative guard checks passed before connection.
- Target database and both exact roles were absent before provisioning.

## B. PostgreSQL safety and migration

Approved target:

`127.0.0.1:5432/pathome_oe_test_routing_p1_20261024`

Run/schema:

- Run ID `routing-p1-20261024-01`
- Schema `routing_p1_20261024_01`
- Migrator `routing_p1_20261024_01_migrator`
- Runtime `routing_p1_20261024_01_app`

Positive attestation proved one disposable marker, exact run identity, migrator-owned schema, distinct credentials and runtime CREATE schema/public/database = false:false:false.

The unchanged chain passed clean V1→V54 and focused V53→V54 upgrade/history proof. Runtime DDL and foreign-schema creation were refused. No protected database or provider was accessed.

## C. Genuine C01–C07 matrix

| Scenario | Result | Evidence |
|---|---|---|
| C01 oversight SQL, mixed types, enrollment/readiness/canonical scope, paging and ordering | PASS | 7-test combined HTTP/PostgreSQL receipt |
| C02 grant lifecycle, concealment, direct read and historical Request | PASS | Included in combined receipt + migration test |
| C03 worker-first and manual-first serialization | PASS | Real Request locks and exact final states |
| C04 worker/coverage/disable contention | PASS | City PID 43552 and control PID 43556 with blocking PID arrays |
| C05 child interruption, rollback, fresh-JVM recovery and retry boundary | PASS | Child JVMs 43596, 43615, 43630, 43648; interrupted PostgreSQL PID 35026 |
| C06 automatic Tenant→Team→two HTTP claims→My Work | PASS | One winner, exact 204/409, scoped readback, SYSTEM/USER audit |
| C06 manual exception→OE claim→My Work | PASS | Real `OPS_ROUTING@CITY` route followed by authorized claim/readback |
| C07 grant APIs and both revocation orders | PASS | City-first/Team-first exact 200/403/200 assertions |
| C07 genuine connected A→B/A→B→A routing UI | FAIL | Account B's capability request returns 200, but real authenticated route remains indefinitely in “Checking your current City routing access…” |

Combined Surefire:

- `AutomaticRoutingAcceptanceHttpPostgresTest`: 7 tests, 0 failures/errors/skips.
- `RoutingOversightRepositoryPostgresTest`: 1 test PASS, including old-alias failure.
- `RoutingAcceptanceMigrationPostgresTest`: 2 tests PASS.
- `RoutingAcceptanceContractLiveTest`: 1 test PASS.

Raw evidence:

`/private/tmp/pathome-routing-p1-20261024-01/evidence/acceptance/`

## D. Three P1 correction verdicts

- Oversight privacy/authorization: **PASS** on required C01/C02 cases.
- Routing-only manual exception authority: **PASS** on required C03/C06 cases.
- Manual-first worker recovery: **PASS** with real blocking and no stale exception or task rewrite.

These are developer genuine results, not independent Sol acceptance.

## E. Complete business journey

The genuine automatic path passed real Tenant login and Request intake, durable task creation, one automatic Team, two competing HTTP OE claims with one winner and one visible stale loser, winner-only My Work, loser/foreign scope isolation, SYSTEM/user audit separation, and no Session/GE/OTP/entitlement/notification effect.

The manual path passed no-coverage exception routing through valid `OPS_ROUTING@CITY`, OE claim and My Work completion.

## F. Restart and retry

A test-owned Spring child was blocked after Team flush, killed, and rolled back. A fresh JVM recovered the persisted PENDING task and committed one Team/route/audit. A later child replay against terminal work was a no-op. A persisted RETRY was excluded before its short due boundary, included after it, then processed once. No duplicate routing or audit occurred.

## G. Security and UX

C01/C02 proved count and row predicates, direct-read concealment and live grant lifecycle. C06 proved cross-Team and cross-User isolation. No unauthorized success audit was observed.

C07 has a fail-closed privacy outcome: no stale private A content was observed after B login. The defect is availability/state settlement, not a demonstrated cross-account data leak.

### P1 — C07 account-switch route remains in `checking`

Observed:

- B is activated staff with both C07 grants revoked.
- Real B login succeeds and its capability endpoint returns HTTP 200.
- `/operations/routing` displays “Checking your current City routing access…” indefinitely instead of the explicit unavailable state.
- The same failure reproduces after a clean B login control, before introducing the held A response.
- The controlled runner did not reproduce this real authentication/storage event sequence.

Confirmed layer: frontend Home authentication adoption plus `useOperationsCapability` entry admission. Backend authorization is correct.

Smallest proposed correction, requiring owner approval because it changes frontend auth/UX behavior:

1. On the committed authentication replacement, adopt the new User and invalidate the prior Operations session in one ordered transition.
2. Ensure the newest current-session capacity check always yields a settled `granted`, `restricted` or `unavailable` state for the committed route entry; an older/retired check must never leave the current entry in `checking`.
3. Re-run real logout/login control for zero-grant and granted accounts, then delayed A→B and A→B→A. Assert no stale files, no indefinite checking, no busy-slot release by the old finalizer, and no navigation/scroll/focus disruption.

Likely scoped files: `frontend-web/src/components/Home.tsx`, `frontend-web/src/hooks/useOperationsCapability.ts`, and focused routing/session tests. No backend contract change is proposed.

## H. Cleanup

Final exact target cleanup:

- Database count 0.
- Exact role count 0.
- Port-8080 listener absent.
- Routing child JVM count 0.
- Protected database opened false.

Evidence:

`/private/tmp/pathome-routing-p1-20261024-01/evidence/final-cleanup-attestation.txt`

## I. Trackers and deadlines

Both canonical 23 October trackers were rechecked read-only with expected hashes:

- Cross-Role `cdc036af...2297c`
- Visit Session `011adba3...e3dc`

No workbook or Drive content changed. Proposed successor entry: C01–C06 genuine pass; C07 backend/grants pass but connected account-switch UI correction required; independent Sol pending.

Backoffice T1–T7 remains approved/pending; T5 retention and DEP-054 remain separate. Web V1 remains HIGH RISK / NO GO; native Tenant/GE remains AT RISK.

Shared routing/auth files must not be edited concurrently with Backoffice work. A fresh source checkpoint is required before that lane starts.

## J. Final result

**FAIL — CONFIRMED CORRECTION REQUIRED**

The three P1 backend defects are genuinely corrected, but C07 is a required routing acceptance scenario and fails in connected Chromium. Independent Sol review remains pending.

STOP. Do not start Backoffice, edit trackers, publish Git or deploy.
