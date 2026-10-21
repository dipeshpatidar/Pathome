# Milestone 4 — listing-review authority extension + genuine Lessor → Tenant → OE integration

This document is the frozen contract for the approved milestone. It runs on the same single disposable
PostgreSQL target as Package 1/3A/3B (`pathome_oe_test_integration`, see `oe-test.env`), through the same
guard (`lib/db-guard.sh`), and it adds no second environment.

## Approved authority extension

Effective `STAFF_ADMIN@GLOBAL` is admitted to exactly seven existing listing-review operations, and to
nothing else:

| # | Operation | Route |
| --- | --- | --- |
| 1 | listing review queue | `GET /api/v1/admin/lessor-review/listings` |
| 2 | listing review detail | `GET /api/v1/admin/lessor-review/listings/{listingId}` |
| 3 | revision review queue | `GET /api/v1/admin/lessor-review/revisions` |
| 4 | revision review detail | `GET /api/v1/admin/lessor-review/revisions/{draftId}` |
| 5 | start review | `POST /api/v1/admin/lessor-review/listings/{listingId}/start-review` |
| 6 | canonical-locality linking | `POST /api/v1/admin/lessor-review/listings/{listingId}/link-locality` |
| 7 | publication approval | `POST /api/v1/admin/lessor-review/listings/{listingId}/approve` |

Deliberately not extended, and still `ROLE_ADMIN` only at both the request matcher and the method security
rule: `POST .../request-changes`, `POST .../revisions/{draftId}/approve`,
`POST .../revisions/{draftId}/request-changes`, and every other `/api/v1/admin/**` path.

## Failure-mode matrix (required before implementation)

Legend: AUTH is `ListingReviewAuthority`; FILTER is the request matchers in `SecurityConfig`; SVC is
`LandlordReviewService`.

| # | Class | Failure mode | Expected behaviour | Verification |
| --- | --- | --- | --- | --- |
| 1 | Authorization | Anonymous caller on any of the seven routes | 401 from the security layer, no service call | HTTP test, controller-security test, journey |
| 2 | Authorization | Ordinary tenant | 403, nothing read, nothing mutated | HTTP + controller-security tests, journey |
| 3 | Authorization | `ROLE_SUB_ADMIN` role | 403 — no role substitutes for the authority | HTTP + controller-security tests |
| 4 | Authorization | Active staff profile, no grant | 403 | HTTP test, journey |
| 5 | Authorization | `CITY_TEAM_ADMIN@CITY(A)` caller | 403 on every City, including A | HTTP test |
| 6 | Authorization | `OPS_COORDINATE@TEAM` / `OPS_SUPERVISE@TEAM` caller | 403 — no narrower capability substitutes | HTTP test |
| 7 | Authorization | `STAFF_ADMIN@GLOBAL` on an inactive staff profile | 403 (live facts only) | HTTP test |
| 8 | Authorization | Revoked `STAFF_ADMIN@GLOBAL` grant | 403 immediately after the revocation, from live facts | HTTP test, journey, governance suite |
| 9 | Authorization | Not-yet-effective `STAFF_ADMIN@GLOBAL` grant | 403 | HTTP test |
| 10 | Authorization | Legacy `ROLE_ADMIN` with no staff profile or grant | admitted exactly as before, on the seven plus the three non-extended operations | HTTP + controller-security tests, journey |
| 11 | Authorization | Admitted staff administrator on any other `/api/v1/admin/**` path | 403 — no general administrative access | HTTP + controller-security tests, journey |
| 12 | Revocation race | Grant revoked between the entry decision and the listing row lock | 403 under the guard, nothing flushed | HTTP test with a real second connection holding the row lock (negative control proves the old ordering published the listing) |
| 13 | Concurrency | Two approvals for one listing | {200, 409}: one publication, one conflict, exactly one version advance | HTTP test with two real threads |
| 14 | Concurrency | Row lock held by another transaction | the command waits for the lock, then re-decides | HTTP test 12 |
| 15 | State | Start review on a `SUBMITTED` listing | 200 to `UNDER_REVIEW` | HTTP test, journey |
| 16 | State | Start review twice | 409, no mutation | HTTP test |
| 17 | State | Approve a `SUBMITTED` listing (skipping review) | 409, no mutation | HTTP test |
| 18 | State | Approve without a canonical locality | refused, and the listing stays `UNDER_REVIEW`/`PENDING` with no `published_at` | HTTP test, journey |
| 19 | Retry | Repeated approve after publication | 409, no second publication, no version change | HTTP test, journey |
| 20 | Canonical | Link a locality belonging to another City | 400, listing untouched (locality, sector and state unchanged) | HTTP test, journey |
| 21 | Canonical | Publication requires the listing City and the canonical locality City to agree | publication refused otherwise | HTTP test 18/20 |
| 22 | Audit | The review authority only ever exists through the audited staff-administration API | `STAFF_ACCESS_GRANT_CREATED` / `STAFF_ACCESS_GRANT_REVOKED` recorded once each for the review grant | HTTP test, journey |
| 23 | Audit | Revocation is effective immediately for the review routes | the next review call answers 403 | HTTP test 8 |
| 24 | Isolation | Review reads never expose another User's draft or listing | owner-scoped queries unchanged | source review plus `LandlordReviewServiceTest` |
| 25 | Rollback | A refused operation leaves no partial state | listing row, `published_at` and version all unchanged | HTTP tests 12 and 17-19 |
| 26 | Ordering | Mutations re-decide the authority after the row lock | `lock -> revalidate -> load -> mutate` | unit `InOrder` evidence |
| 27 | Ordering | Reads re-decide the authority before touching review data | no repository interaction on refusal | unit `verifyNoInteractions` evidence |

## Genuine journey (no SQL seeding)

`package4.sh journey` drives the whole path over real HTTP against the disposable database:

1. lessor registration through `POST /api/v1/auth/register`;
2. draft creation and section updates, with an unresolved locality;
3. canonical locality discovery through the lessor suggestion API;
4. a real photo upload to the configured object store;
5. submission, and the proof that an unpublished submission is not public;
6. review and publication by a staff administrator whose `STAFF_ADMIN@GLOBAL` grant was created through
   `POST /api/v1/staff/admin/users/{id}/grants`, including the refused publication before linking and the
   refused retry afterwards;
7. a tenant Visit Request against the genuinely published listing;
8. explicit Admin Team routing with an explicit City, Team, versions and reason;
9. real OE login, Claim, My Work and Release.

Nothing is written to `supported_cities`, `localities`, `listings`, `staff_access_grants`,
`operating_teams` or `operational_audit_events` by hand, and no migration is added.

The journey writes `journey-state.json` with e-mail addresses and identifiers only: no password and no
bearer token is written, printed or committed. Credentials come from the ephemeral run
(`$PATHOME_OE_TEST_RUN_DIR/secrets.env`), and `package4.sh journey-browser` supplies the shared
disposable password from there, so the browser pass never reads one from the state file.

## Commands

```bash
bash tools/oe-integration-env/package4.sh status
bash tools/oe-integration-env/package4.sh verify-unit
bash tools/oe-integration-env/package4.sh verify-postgres
bash tools/oe-integration-env/run.sh backend
bash tools/oe-integration-env/package4.sh journey
bash tools/oe-integration-env/package4.sh journey-browser
```

Every command prints one line per check and exits non-zero on failure. Nothing is reported as passing
unless it actually ran.

The Maven test run and the backend are configured through `lib/env-isolation.sh`: `.env.local` is never
sourced, inherited destinations are neutralised, and the disposable datasource is repinned to the
designation. The approved Phase 2A Operations acceptance suite (`phase2a.sh`, see `PHASE2A.md`) reuses
this same environment and the same guard.

## Boundaries

* No migration, entity or DTO-contract change; no frontend production change; no user role change; no
  general `/admin/**` access; no automatic routing, transfer or Workforce UI.
* No commit, push, merge, stash or reset, and no tracker edit.
