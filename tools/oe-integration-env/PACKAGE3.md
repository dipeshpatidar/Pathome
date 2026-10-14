# Package 3A — governed Operating Team provisioning (OE integration environment)

This directory keeps one disposable PostgreSQL target (`pathome_oe_test_integration`, see
`oe-test.env`) and every server-touching step still runs through `lib/db-guard.sh` before any
statement. Package 3A adds the governed Team API and genuine OE admission evidence on top of the
Package 1 environment. It does not create a second environment, does not touch `pathome_db`, and
does not write `operating_teams`, `staff_access_grants` or audit history by hand.

## Failure-mode matrix (required before implementation)

Legend: **API** = `OperatingTeamProvisioningService` / `OperatingTeamProvisioningController`;
**CITY-OFF** = `OperationalOwnershipService.deactivateCity`.

| # | Class | Failure mode | Expected behaviour | Verification |
| --- | --- | --- | --- | --- |
| 1 | Authorization | No credential / invalid credential on any 3A route | 401 from the security layer, no service call | HTTP test: unauthenticated GET/POST |
| 2 | Authorization | Authenticated ordinary Tenant | 403, nothing persisted | HTTP test |
| 3 | Authorization | Active staff, no grant | 403 | HTTP test |
| 4 | Authorization | `CITY_TEAM_ADMIN@CITY` / `OPS_INTAKE@CITY` / `OPS_COORDINATE@TEAM` caller | 403 — no capability substitutes for `STAFF_ADMIN@GLOBAL` | HTTP test |
| 5 | Authorization | Legacy `ROLE_ADMIN` with no staff authority | 403 | HTTP test |
| 6 | Authorization | Inactive staff profile, or revoked / future / expired `STAFF_ADMIN@GLOBAL` grant | 403 (live database facts only) | HTTP test |
| 7 | Authorization | Grant revoked between the coarse check and the locked recheck | 403 under guards, nothing persisted | unit test (recheck ordering) |
| 8 | Authorization | Authority read from a stale managed entity | fresh JDBC `cityVersion` / `cityIsActive` reads under the guard | unit + HTTP tests |
| 9 | Concurrency | Two creators submit the same new `(city, code)` | exactly one 201 + one creation audit; the loser 409; one Team | HTTP test, two real threads |
| 10 | Concurrency | Governed creation interleaves with `deactivateCity` | City can never end inactive while an active Team exists | HTTP test, real row lock + second connection |
| 11 | Concurrency | City deactivation enumerates Teams before taking the City lock | enumeration refreshed *after* the City lock, before Team locks and drain validation | source + HTTP test 10 |
| 12 | Concurrency | Two `deactivateCity` calls | serialised by the City row lock; both see the drained state | lock order unchanged (City → Team → User) |
| 13 | Stale versions | `expectedCityVersion` omitted / negative / non-numeric | 400 | unit + HTTP tests |
| 14 | Stale versions | `expectedCityVersion` behind the persisted City version | 409, no Team, no audit | HTTP test |
| 15 | Stale versions | City version changes between the coarse read and the locked check | 409 detected under the lock | unit test |
| 16 | Retries | Exact repeated POST for an existing code | 409, no second Team, no second audit event | HTTP test |
| 17 | Retries | Retained *inactive* Team code | 409; the Team is never silently reactivated | HTTP test |
| 18 | Retries | Lost response after a successful creation | operator rereads the collection; no idempotency key is fabricated | contract; collection read verified |
| 19 | Audit rollback | Audit insert fails after the Team was flushed | whole transaction rolls back; no Team row remains; 500 sanitised | HTTP test with a temporary rejecting trigger |
| 20 | City state | Unknown City id / non-numeric id | 404 for a missing row, 400 for a malformed identifier | HTTP + unit tests |
| 21 | City state | City exists but is inactive at mutation time | 409, no Team, no audit | HTTP test |
| 22 | City state | City activated/deactivated between read and write | decided under the City lock | source + test 14/21 |
| 23 | Team state | Team created for a City that has never been active | impossible: active City required at mutation time | HTTP test 21 |
| 24 | Team state | Paginated Team list must expose inactive Teams | inactive Teams returned by code (retained codes visible) | HTTP test |
| 25 | Team state | Display-name collision | allowed (names stay non-unique); only `(city_id, code)` is unique | HTTP test |
| 26 | Input safety | Code upper case, illegal characters, empty, > 64 chars | 400, no silent upper/lower conversion | unit + HTTP tests |
| 27 | Input safety | Display name blank, only whitespace, > 160 chars, control characters | 400; surrounding whitespace trimmed | unit + HTTP tests |
| 28 | Input safety | Unbounded reason code | 400 unless `^[A-Z][A-Z0-9_]{0,63}$` | unit + HTTP tests |
| 29 | Input safety | Client-supplied `active`, creator, grant or ownership fields | ignored; the persisted Team is active and owned by the server | HTTP test |
| 30 | Database safety | Unbounded page size or negative page | 400; size bounded to 1..100, default 20 | unit + HTTP tests |
| 31 | Database safety | Unbounded scans in the request path | explicit `COUNT` + `LIMIT/OFFSET` per statement; no `findAll()` | source + `EXPLAIN`-free repository review |
| 32 | Database safety | Sensitive reads cached by a proxy/browser | `Cache-Control: no-store` on all three 3A routes | HTTP test |
| 33 | Database safety | Wrong database targeted by a test or script | `db-guard.sh` refuses anything but the marked disposable database | `run.sh status` + guard refusal tests |
| 34 | Database safety | Test schema leaking into unrelated data | UUID schema created and dropped inside the disposable database only | test harness |

## Commands

```bash
bash tools/oe-integration-env/run.sh status          # designation, guard verdict, backend state
bash tools/oe-integration-env/package3.sh verify-teams      # governed creation + GET contract
bash tools/oe-integration-env/package3.sh provision-oes     # register/activate/grant Team OEs
bash tools/oe-integration-env/package3.sh verify-http       # genuine admission + negatives
bash tools/oe-integration-env/package3.sh verify-postgres   # focused JUnit/HTTP-Postgres tests
```

Every command prints one line per check and exits non-zero on the first failure. Nothing is
reported as passing unless it actually ran.

## Boundaries

* Package 3A only: governed Team creation, its authorization, City-deactivation concurrency guards,
  transaction/audit guarantees, and genuine OE admission. No 3B routing, no listing-review change,
  no Workforce UI, no migration, no tracker edit.
* Fixtures are created only through `POST /auth/register`, `POST /api/v1/staff/admin/**` and the 3A
  Team API. `operating_teams`, `staff_access_grants` and `operational_audit_events` are never written
  by hand, and no trigger is bypassed.
* Existing scripts (`run.sh`, `lib/provision.sh`, `lib/verify.sh`) and their evidence are preserved.

---

# Package 3B — human-authorized initial intake routing

Package 3B adds the approved routing contract on top of the same disposable environment: the three
`/api/v1/operations/intake-routing/visit-requests…` routes, their source-scope authorization, the
existing reconciliation mutation reused inside the transaction that owns the Request lock, and bounded
command waits. It adds no migration, no entity change, no frontend surface and no tracker edit.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/operations/intake-routing/visit-requests?page&size` | eligible standalone `RECEIVED` Requests |
| GET | `/api/v1/operations/intake-routing/visit-requests/{requestId}/candidates?cityId&page&size` | Request snapshot + explicitly selected City + eligible Teams |
| POST | `/api/v1/operations/intake-routing/visit-requests/{requestId}/route` | explicit initial routing |

Command body (`InitialVisitRequestRoutingCommand`): `expectedRequestVersion`, `cityId`,
`expectedCityVersion`, `teamId`, `expectedTeamVersion`, `reasonCode`. No coordinator, session,
schedule or entitlement field exists, so routing cannot request any of those effects.

## Failure-mode matrix (required before implementation)

| # | Class | Failure mode | Expected behaviour |
| --- | --- | --- | --- |
| 1 | Authorization | Anonymous caller on any routing route | 401, no service call |
| 2 | Authorization | Ordinary Tenant, inactive staff, no-grant staff, legacy `ROLE_ADMIN` | 403 |
| 3 | Authorization | `OPS_INTAKE@CITY` or `OPS_SUPERVISE@TEAM` only | 403 — existing intake read retained, no routing power |
| 4 | Authorization | `STAFF_ADMIN@GLOBAL` | full discovery and routing, unresolved intake included |
| 5 | Authorization | `CITY_TEAM_ADMIN@CITY(A)` | City A canonical intake only |
| 6 | Source scope | City Admin on unresolved, City B or contradictory intake | 404 concealment, no mutation |
| 7 | Source scope | City Admin selecting a City they are not authorized for | 404 concealment; selecting a City never grants authority |
| 8 | Source scope | Retained `reconcileRequest` reaching unresolved intake | `AccessDenied` — global Staff Admin only |
| 9 | Canonical | Persisted locality contradicts the established Request scope | 409, even for global Admin |
| 10 | Canonical | City Admin on intake without a consistent canonical locality | 404 concealment |
| 11 | Selection | Missing/blank/negative `cityId`, `teamId` or versions | 400 |
| 12 | Selection | Zero Teams | empty candidate page; no destination invented |
| 13 | Selection | Exactly one Team | still requires explicit Team + version + reason |
| 14 | Selection | Many Teams | paginated; no default, ranking or automatic choice |
| 15 | Versions | Stale Request, City or Team version | 409, no mutation, no audit |
| 16 | Lifecycle | Linked, claimed, Team-routed, or non-`RECEIVED` Request | 409 |
| 17 | Retry | Unchanged replay after a successful routing | 409, one audit event only |
| 18 | Concurrency | Two routing commands for one Request | {204, 409}: one mutation, one audit |
| 19 | Atomicity | Audit insert fails after the mutation flushed | everything rolls back, 500 sanitized |
| 20 | Bounds | City/Request lock held by another transaction | 503 sanitized within the bounded wait, no partial state |
| 21 | Audit | Routing evidence recorded | `OWNERSHIP_RECONCILED` + `routingMode=INITIAL`, expected/resulting versions, source City, unresolved flag, selected City/Team, readiness, linked count |
| 22 | Side effects | Route, Claim, Release | no Visit Session, scheduling, availability, notification or entitlement change |
| 23 | Isolation | Same-Team vs another-Team OE | claimable metadata only for the owning Team; concealed from the other |
| 24 | Work | Normalised Claim/Release after routing | real 204s, version checks, coordinator set/cleared |

## Commands

```bash
bash tools/oe-integration-env/package3.sh verify-routing          # focused 3B unit + HTTP/PostgreSQL tests
bash tools/oe-integration-env/package3.sh verify-routing-browser  # genuine Chromium pass over the routing surface
```

## Approval dependency (not bypassed)

The genuine fresh-publication → tenant intake → routing → Claim acceptance needs a normally published
canonical listing, which needs the **narrow listing-review authority extension** — still
**unapproved**. No canonical published listing is fabricated anywhere (no fresh fixture in this
environment, no direct SQL write to `listings.canonical_locality_id`), so that acceptance remains
BLOCKED and is reported as such. The focused routing tests use isolated test schemas and the
established Package 1C fixture pattern, and the browser harness proves the approved routing surface
only.
