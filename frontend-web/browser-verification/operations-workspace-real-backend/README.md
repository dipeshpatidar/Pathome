# OE workspace real-backend integration verification

Runs the **real built app** (`frontend-web/dist`) in Chromium against a **live Spring Boot backend on
PostgreSQL**. Static assets are served in-process at a synthetic origin (`http://oe.local`); every
`/api/**` request is forwarded verbatim to `http://localhost:8080`. Authorization, ownership locks,
optimistic versions and the 401/403/404/409/204 semantics are therefore decided by the real
application and the real database — nothing about the Operations contract is stubbed.

This suite is deliberately separate from `../operations-workspace/run.mjs`, the controlled-stub race
harness for Package 1A, which remains the fast deterministic regression suite and is not affected by
this one.

## Running it

```bash
# 1. backend (local development database, disposable first-Admin bootstrap only on the first run)
set -a; . ./.env.local; set +a
export APP_FIRST_ADMIN_PROVISIONING_ENABLED=true
export APP_BOOTSTRAP_ADMIN_EMAIL='codex.ops.admin@pathome.test'
export APP_BOOTSTRAP_ADMIN_PASSWORD='<disposable>'
export APP_BOOTSTRAP_OPERATOR_REFERENCE='CODEX_OPS_INTEGRATION_GATE'
mvn -f backend/pom.xml spring-boot:run

# 2. frontend build + suite
npm --prefix frontend-web run build
node frontend-web/browser-verification/operations-workspace-real-backend/run.mjs
```

The suite prints one line per check and exits non-zero on any failure. A full pass takes about two
minutes and signs in five disposable identities plus the bootstrap admin against the real backend.

## Required fixture

Two disposable Operating Teams in Indore (`supported_cities.code='indore'`), one Team Alpha claimable
Request, one Team Bravo claimable Request, and one Team Alpha DRAFT Visit Session, all owned by the
disposable identities below. Ownership authority is created **only** through
`POST /api/v1/staff/admin/users/{id}/activate` and `POST /api/v1/staff/admin/users/{id}/grants`
(`provisioning_source='ADMIN_API'`); `staff_access_grants` is never written by hand.

| Identity | Team / scope | Purpose |
| --- | --- | --- |
| `codex.ops.admin@pathome.test` | `STAFF_ADMIN@GLOBAL` (bootstrap) | provisions and revokes grants |
| `codex.oe.alpha@pathome.test` | `OPS_COORDINATE@TEAM` Alpha | authorised OE |
| `codex.oe.bravo@pathome.test` | `OPS_COORDINATE@TEAM` Bravo | OE from another Team |
| `codex.oe.nogrant@pathome.test` | active staff, no grant | staffed account without OE permission |
| `codex.subadmin@pathome.test` | `CITY_TEAM_ADMIN@CITY` + `OPS_COORDINATE@TEAM` Alpha | Sub-Admin / second authorised OE |
| `codex.subadmin.noops@pathome.test` | `CITY_TEAM_ADMIN@CITY` only | Sub-Admin without OPS_COORDINATE |

Claimable work requires a Team-routed, operational-scope-ready root. No application flow assigns
`operating_team_id`, and every ACTIVE listing in the local database has `canonical_locality_id` NULL,
so `TrustedOperationalIntakeService` cannot derive `supported_city_id`/`operational_scope_ready`
either. The fixture therefore creates its Request rows through the genuine tenant intake API and then
sets only the routing columns (`supported_city_id`, `operating_team_id`, `operational_scope_ready`)
on those disposable rows. The Visit Session is created end-to-end by the supported flows — claim,
`POST /operations/visit-requests/{id}/coordinate`, then release — so the V45 ownership-mirror trigger
is obeyed and never bypassed.

## Coverage

| Required verification | Scenario |
| --- | --- |
| Active authorised OE | `A1`–`A10`, `B1`–`B12` |
| OE from another Team | `E1`–`E4`, `C4`, `A9` |
| Account without OE permission | `F1`–`F3`, `G4` |
| Authorised Admin/Sub-Admin from existing grants | `G1`–`G3`, `G4` |
| Claimable Work and My Work | `A5`–`A8`, `B5`–`B7`, `B12` |
| Request/Session detail authorization | `E4`, `B1`, `C1`, `D4`, `H6` |
| Claim and Release against real records | `B2`–`B4`, `B9`–`B11`, `C2`–`C3` |
| Successful HTTP 204 | `B3`, `B10`, `C3`, `D6` |
| Stale-version HTTP 409 | `D5`–`D11` |
| Unauthorized 401/403 and inaccessible-resource 404 | `H8`, `F3`, `C4`, `E4`, `H6` |
| Ownership competition between authorized OEs | `D5`–`D11` |
| Permission revocation and protected-state clearing | `H2`–`H7` |
| Session/account switching | `H10`, `H11` |
| Post-mutation authoritative reconciliation | `B7`, `B12`, `D3` |
| No duplicated mutation or unauthorized access | `C2`, `B2`, `B9`, `D7`, `D10`, `D11` |
| Responsive layout and 44px touch targets | `I1`–`I5` |
| Entry-point blocker evidence | `J1`–`J4` |

## Two documented boundaries

**Seeded client role.** Sessions are established by writing the exact localStorage keys the app writes
(`pathome_auth_token`, `pathome_user`, `pathome_role`) with a bearer token from a real
`POST /api/v1/auth/login`. The login form's own role normalisation is **not** exercised, because
`normalizeRole` maps `ROLE_TENANT` to `TENANT`; `J1`–`J4` measure that as the entry-point blocker
below. Authority is never seeded — every run proves it from the live `/api/v1/staff/me/capabilities`
response.

**Console-shell gate.** `MasterAdminDashboard` and `EmployeeCrmDashboard` also read role-gated shell
endpoints (`/api/v1/admin/**`, `/api/v1/ground/**`) that require a JWT role claim
(`hasRole("ADMIN")` / `hasRole("GROUND_BOY")`). The backend answers 401 there for a provisioned staff
account and the app treats 401 as session expiry, tearing the console down. Those unrelated shell
reads are answered with **403** (the authority the account genuinely has) so the workspace can be
reached; no data is fabricated and every Operations, staff-capability and notification call still
goes to the live backend. Set `shellGate: false` when constructing a context to observe the
underlying 401 (scenario `J`).

## Entry-point blocker (measured)

The supported provisioning mechanisms (`POST /api/v1/auth/register`, `StaffAdminBootstrapService`,
`StaffAdminController`) can only create `ROLE_TENANT` users; no endpoint sets `users.role`. Because
the consoles are gated on the JWT role claim, and because the Employee console additionally calls
`/api/v1/ground/visit-sessions**` (which requires `ROLE_GROUND_BOY`), **no locally provisionable
staff identity can reach the Operations workspace through the real login form**. Scenarios `J1`–`J4`
record that with real responses. That gap is an integration finding, not a defect in the workspace
itself: the workspace and its authority contract verified green once the console shell was reachable.

## Not covered here

* Real production or staging data, Cloudinary/S3 media, and rate-limited external services.
* Physical mobile devices and real touch input.
* A genuine browser tab visibility transition (the suite uses a real `focus` event for revalidation).
* `Workforce & Teams Administration` — Team records were created as disposable fixture rows because
  no provisioning mechanism exists for them.
