# Operations destination — real-backend verification (Package 2)

Runs the **real built app** (`frontend-web/dist`) in Chromium against a **live Spring Boot backend on
PostgreSQL**. Static assets are served in-process at a synthetic origin; every `/api/**` call is
forwarded verbatim to `http://localhost:8080`. Authentication, the capability contract and every
status code are therefore decided by the real application and the real database. No response is
fabricated and nothing is stubbed in the page.

Unlike [`../operations-workspace-real-backend/`](../operations-workspace-real-backend/README.md), this
suite drives the application's **own login form** and the **real navigation**: `localStorage` is never
seeded, so the `ROLE_TENANT` → `TENANT` normalisation the app performs is genuinely exercised.

## Running it

```bash
bash tools/oe-integration-env/run.sh backend      # disposable database, port 8080
bash tools/oe-integration-env/run.sh provision    # disposable identities and grants
npm --prefix frontend-web run build
node frontend-web/browser-verification/operations-destination-real-backend/run.mjs
```

Disposable credentials are read from `$PATHOME_OE_TEST_SECRETS`
(default `/private/tmp/pathome-oe-test/secrets.env`) and are never printed; credential continuity is
reported as a short non-reversible digest. The suite exits non-zero on any failure and takes well
under a minute.

## Coverage

| Approved requirement | Scenario |
| --- | --- |
| Normal authenticated navigation | `1` — the real form establishes a session with the server-issued User id (no placeholder), and a `ROLE_TENANT` account lands on the tenant dashboard |
| Capability gate, not a role label | `2`, `7` — `CITY_TEAM_ADMIN@CITY`, `OPS_INTAKE@CITY`, active staff without a grant, `STAFF_ADMIN@GLOBAL` and a plain tenant are all offered no entry; the gate consults the live capability endpoint |
| Direct URL and refresh fail closed | `3`, `4` — the destination refuses explicitly, keeps its URL, and issues zero protected requests |
| 401 vs 403 contract | `5` — permission denials answer **403** with the standard body and do not end the session |
| Genuine authentication expiry | `6` — an invalid credential still ends the session and re-offers sign-in, so session expiry was not globally suppressed |
| Tenant/lessor/guest journeys preserved | `8` — tenant dashboard, public property route and public landing page still render |
| Logout re-protects the destination | `9` — the session is cleared and `/operations` returns to the public entry with the sign-in prompt |
| Responsive integrity | `10` — no horizontal overflow at 320/390/768/1440 |

## Blocked — PACKAGE 3 DEPENDENCY

The **positive admitted path cannot be produced against this backend**. An effective
`OPS_COORDINATE@TEAM` grant requires an `operating_teams` row (`OPS_COORDINATE`/`OPS_SUPERVISE` must be
`TEAM`-scoped), and the disposable database contains none. No provisioning mechanism creates Team
records, and creating one here would be Operating Team management — explicitly outside Package 2.
Authority was **not** bypassed to obtain a green result.

The positive path is exercised in the controlled Chromium suite
([`../operations-destination/`](../operations-destination/README.md)), which is clearly labelled as a
simulated backend.

## Not covered here

* Production, staging, Cloudinary/S3 media and rate-limited external services.
* Physical mobile devices and real touch input.
* The positive admitted path (BLOCKED, above).
