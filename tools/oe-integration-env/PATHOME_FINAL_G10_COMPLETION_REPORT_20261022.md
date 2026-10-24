# PATHOME — FINAL G10 COMPLETION REPORT

**Executed:** 22 October 2026, India Standard Time  
**Repository:** `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
**Branch / committed HEAD:** `feature/phase2a-oe-ui-integration` /
`a943775e07a153087d0b38c315db20e18b30aec5`  
**Git index:** EMPTY  
**Git publication:** NONE

## Status

- **IMPLEMENTED:** PASS
- **AGENT-TESTED:** PASS
- **GENUINE-INTEGRATION-VERIFIED:** PASS for the executed G10 cohort
- **INDEPENDENTLY ACCEPTED:** NOT CLAIMED
- **OWNER VISUALLY ACCEPTED:** NOT CLAIMED
- **RELEASE-READY:** NOT CLAIMED

## 1. Executive Result

The approved Lead search and follow-up capabilities are implemented end to end, the fresh disposable
database is positively attested, focused real PostgreSQL/Spring witnesses pass, and the genuine
authenticated Chromium → React → Spring → PostgreSQL journey passes 51/51 checks.

The application is running for owner inspection:

- Frontend: `http://localhost:5173/`
- CRM: `http://localhost:5173/operations/leads`
- Backend: `http://127.0.0.1:8080`

Both listeners are loopback-only. The owner-review account email is
`codex.g10.review.oe@pathome.test`; its password is stored only in the mode-600 external file
`/private/tmp/pathome-oe-g10-20261022/review-login.txt`.

## 2. Repository and Worktree

- Repository root matches the approved workspace.
- Branch and committed HEAD match the owner baseline.
- Local HEAD and `origin/feature/phase2a-oe-ui-integration` both resolve to
  `a943775e07a153087d0b38c315db20e18b30aec5`.
- The inherited uncommitted worktree was preserved.
- Git index was verified empty.
- No stage, commit, push, merge, PR, rebase, reset, stash, clean or branch switch occurred.

## 3. Fresh Disposable Environment

**Database:** `pathome_oe_test_g10_20261022`  
**Endpoint:** `127.0.0.1:5432`  
**Run ID:** `17cdb6d3-ef16-4b67-9f53-73725c859a41`  
**Run directory:** `/private/tmp/pathome-oe-g10-20261022`  
**Sentinel:** exactly one `DISPOSABLE` row matching the database and run  
**PostgreSQL:** 14.14  
**Role:** `pathome`  

The database was confirmed absent before creation. It was then created through the supported
`preflight → create → backend → provision → verify → attest` lifecycle.

Historical `pathome_oe_test_integration` and its suspect schema were not opened, modified, repaired,
reused, copied or dropped. Protected `pathome_db` was not accessed.

## 4. Safety-Harness Adaptation

Changes are exact-target allowlists, not wildcard or general-purpose bypasses:

- Added `tools/oe-integration-env/oe-test-g10-20261022.env`.
- Extended `tools/oe-integration-env/lib/runtime.mjs` with an exact mapping:
  - `pathome_oe_test_phase2a_20261015` → its original run directory;
  - `pathome_oe_test_g10_20261022` → `/private/tmp/pathome-oe-g10-20261022`.
- `readiness.mjs` now uses that exact mapping instead of the old single Phase 2A coordinate.
- `DisposableIntegrationTarget` now recognizes the old and new exact G10 database names only.
- Live database identity is compared to the actual designated target.
- Protected names, loopback-only hosts, one-sentinel requirement, run identity, process/listener
  binding and attestation remain mandatory.
- Added the V52/V53 Lead tables to `expected-schema.txt`; no migration was changed.

Negative guard tests continue to reject:

- missing/disallowed opt-in;
- missing designation;
- protected `pathome_db` and protected-prefix names;
- `postgres`, template databases and unrelated local databases;
- remote hosts;
- malformed/staging/ambiguous names;
- an arbitrary third well-formed `pathome_oe_test_*` database.

## 5. Migration Provenance

Flyway executed the genuine repository chain:

- Applied migrations: **53**
- Highest version: **V53**
- Failed history rows: **0**
- V1 legacy baseline success row: present
- Public base tables: 57 total = 55 expected application tables + 2 infrastructure tables
- Expected public view count: 1
- Disposable marker leakage into application tables: 0
- Schema-source fingerprint:
  `7630800708c8048093d292835ea55ee9269aab220f42edf4b63f89eec4fa7c15`

The environment runs with Hibernate DDL set to `none`; migrations, not Hibernate, own the schema.

Frozen migration hashes:

```text
0d38b0db50874236b2bc254cd15cebe7d12a0de0df41419cf7455e2ab93c2c85  V51
722a4886f2fd125f243920da5e633bdec0726e27936187fe2112e38c3955d2ee  V52
15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3  V53
```

## 6. Synthetic Review State

Provisioning used supported registration, Staff Administration, Team creation, grants and Lead capture
APIs. No staff-grant or sentinel rows were manually written.

| Account | Purpose | Authority |
| --- | --- | --- |
| `codex.g10.review.oe@pathome.test` | Owner-review OE | `OPS_COORDINATE@TEAM` |
| `codex.g10.review.supervisor@pathome.test` | Same-Team read persona | `OPS_SUPERVISE@TEAM` |
| `codex.g10.review.admin@pathome.test` | Diagnostic-only Admin | `STAFF_ADMIN@GLOBAL` |
| Provisioned City Admin / Intake / No-grant users | Environment verification | As recorded by normal provisioning |

Retained review data:

- 8 synthetic Leads owned by the review OE;
- one Team: `North Indore Residential Coordination Team`;
- one genuinely published synthetic rental in Vijay Nagar, Indore;
- follow-up, missing-information and activity states suitable for visual inspection.

`/private/tmp/pathome-oe-g10-20261022/review-state.json` is mode 600 and contains no token or
password. The mode-600 review-login file is intentionally outside Git.

## 7. Approved Lead Search API

Route:

```text
GET /api/v1/operations/leads
```

New additive parameters:

- `q`
- `followUp=OVERDUE|TODAY|NONE`
- `followUpZone=<IANA-zone>`

Existing parameters remain:

- `cityId`
- `teamId`
- `status`
- `owner=me`
- legacy `followUpBefore`
- `page`
- `size`

`followUp` and legacy `followUpBefore` cannot be combined.

### Search normalization and safety

- Leading/trailing whitespace is trimmed and internal whitespace collapsed.
- Maximum accepted length is 80 characters; longer input returns 400.
- Customer-name matching is case-insensitive.
- Numeric Lead identity, `LD-####` and `Lead #id` forms are supported without a new persisted
  reference identity.
- Preferred-locality sector names and aliases are searched through the existing relationship.
- Exactly four digits also match the masked-contact suffix.
- One-to-three-digit phone fragments do not act as contact suffixes.
- SQL wildcard `%`, `_` and the escape character are escaped and treated literally.
- Full phone numbers are never returned as a search side effect.
- The existing visibility predicate is applied before filtering, count and pagination.
- The same count/select filters and stable `createdAt DESC, id DESC` ordering are used.

### Follow-up time

- The server captures one database instant for each filtered list/count evaluation.
- `OVERDUE`: `followUpAt < now`; equality to now is not overdue.
- `TODAY`: half-open local-day interval using the explicit validated IANA zone.
- `NONE`: `followUpAt IS NULL`.
- Invalid or missing required zones return 400.
- DST spring/fall boundaries, non-hour `America/St_Johns`, midnight rollover and fractional seconds
  are covered.

## 8. Frontend Search Experience

The shared Operations shell now exposes:

- approved v0-style Lead search input;
- Follow-up selector: Any, Overdue, Due today, No follow-up;
- existing City, Team, Status and Owned-by-me filters;
- server count and pagination;
- preserved active Leads navigation and one-row mobile tabbar.

Search changes reset pagination and retain the request-generation/AbortController fencing. Sensitive
`q` text is not written to the URL, browser history or localStorage. It remains in mounted-page state,
so detail → Back preserves it; a hard browser refresh intentionally retains only the safe URL context
(City/Team/status/follow-up/page), not the free-text query.

## 9. Defects Found and Corrected by Genuine Tests

1. **Manual-enquiry fingerprint overflow**
   - The persisted fingerprint column is `VARCHAR(24)`.
   - The prior attach/keep-distinct labels exceeded that width and produced 500 responses.
   - Corrected persisted labels:
     - `MANUAL_ATTACHED_V1`
     - `MANUAL_DISTINCT_V1`
   - The longer protected snapshot-format version remains inside encrypted evidence. No migration was
     required.

2. **React Strict Mode initial-load discard**
   - The queue and Lead subsurfaces did not re-arm mount guards after React’s development probe.
   - Successful API responses were therefore discarded and screens remained on Loading in Vite dev.
   - Queue initial loading and all affected `mounted.current` guards now re-arm on each effect setup.

3. **Smart Search schema-path defect**
   - Test schemas use `currentSchema`, so unqualified `similarity` / `%` could not be resolved even
     though `pg_trgm` is installed in `public`.
   - Existing native queries now schema-qualify `public.similarity` and `OPERATOR(public.%)`.
   - The new browser assertion rejects any unexpected API 5xx and now passes.

## 10. Verification Executed

### Backend focused unit/service/controller/guard

```bash
mvn -q -f backend/pom.xml \
  -Dtest='LeadControllerTest,LeadServiceTest,LeadShareServiceTest,DisposableIntegrationTargetGuardTest' test
```

Result: **39/39 PASS**:

- `LeadControllerTest`: 6
- `LeadServiceTest`: 9
- `LeadShareServiceTest`: 9
- `DisposableIntegrationTargetGuardTest`: 15

### Genuine PostgreSQL/Spring batch 1

Search, follow-up, authorization, manual attachment and concurrency:

```text
7/7 PASS
```

Methods:

- exact scoped search;
- timezone-aware follow-up selection;
- owner/Team/City/Admin/union authorization;
- ATTACH_ENQUIRY;
- KEEP_DISTINCT;
- atomic protected evidence;
- concurrent attachment serialization.

### Genuine PostgreSQL/Spring batch 2

Consent, public property, shortlist, share history, replay and rollback:

```text
8/8 PASS
```

Methods covered current owner/lifecycle authority, consent revision, public catalog truth,
ordered shortlist, manual handoff preparation, immutable/read-only history, changed-fact replay,
lost acknowledgement and transaction rollback.

The 20-share history batch witness verifies one event batch, one shortlist batch, one shortlist-item
batch and one public-property batch; it does not issue one query per share/property.

### Genuine connected Chromium

```text
51/51 PASS
0 browser/page exceptions
0 unexpected API 5xx responses
```

The real journey covers:

- authenticated owner queue;
- name/reference/last-four search;
- NONE/OVERDUE follow-up filtering;
- capture and detail;
- qualification gaps and Next action;
- real Smart Search held-page fencing;
- public property and ordered shortlist;
- consent, prepared share, manual WhatsApp intent and history;
- supervisor, peer denial and diagnostic-only Admin separation;
- duplicate Clear race;
- 320, 375, 390, 430, 768, 1024 and 1440px layouts;
- 44px controls and zero page overflow.

### Frontend

```text
npm --prefix frontend-web run build
```

PASS; existing chunk-size warning only.

Affected frontend cohort:

```text
59/59 PASS
```

Controlled transport suite:

```text
36/36 PASS
0 browser/page exceptions
```

Controlled evidence remains separate from genuine integration evidence.

### Owner review smoke

```text
7/7 PASS
```

Verified on the retained local application:

- real owner login token;
- 8 persisted queue rows;
- persistent sidebar;
- diagnostic navigation absent;
- real customer search;
- persisted Lead detail;
- Next action present;
- real published property visible in Smart Search;
- no API 5xx during the smoke path.

## 11. Local Application and Cleanup

Current listeners:

```text
Spring Boot  127.0.0.1:8080
Vite         127.0.0.1:5173
```

Cleanup facts:

- test-schema pattern `p4_crm_http_test_*`: **0 remaining schemas**
- public synthetic retained Leads: **8**
- active published synthetic properties: **1**
- Flyway successful rows: **53**
- DISPOSABLE marker rows: **1**
- current application database sessions: 10 Hikari pool connections
- old suspect database: preserved and untouched
- owner-review data: intentionally retained

Safe shutdown:

```bash
PATHOME_OE_TEST_ENV_FILE=tools/oe-integration-env/oe-test-g10-20261022.env \
PATHOME_OE_TEST_MEDIA_ENV_FILE=/private/tmp/pathome-oe-phase2a-20261015/media.env \
bash tools/oe-integration-env/run.sh stop-backend

kill "$(lsof -tiTCP:5173 -sTCP:LISTEN)"
```

Do not run teardown while the owner-review database must remain available. Teardown is destructive and
was not executed.

## 12. Evidence

- `tools/oe-integration-env/evidence/final-g10-completion-20261022/`
- `tools/oe-integration-env/evidence/p1-v0-fidelity-20261022/fidelity-comparison.png`
- `tools/oe-integration-env/evidence/g10-connected-20261022/owner-review-1440.png`
- `tools/oe-integration-env/evidence/final-g10-completion-20261022/evidence-manifest.sha256`

## 13. Trackers, Validation Limits and Deadlines

The two exact Smart Intake Approval workbooks remain absent from the local Mac workspace and Spotlight.
No differently hashed workbook was edited or treated as the approved canonical edition.

Not tested:

- physical iPhone/Safari gestures;
- VoiceOver and comprehensive WCAG audit;
- real Meta/WhatsApp delivery or provider retry activation;
- staging/deployment;
- production-scale query plans and representative large-dataset performance;
- exact canonical tracker row comparison.

No production index was added. The bounded current environment does not support a credible
production-volume index decision; the existing Lead indexes remain unchanged.

Web V1 on 31 October 2026 remains **HIGH RISK**. Native Tenant/GE on 15 November 2026 remains
**AT RISK**.

## 14. Scope and Invariants

- Stable `User` identity and additive tenant/lessor capability remain unchanged.
- OE ownership, supervisor read, City intake and staff deactivation/revocation remain server-enforced.
- Diagnostic-only Staff Admin cannot access ordinary named Lead rows.
- Attachment/keep-distinct do not merge Users or identities.
- Encrypted source evidence, idempotency, receipts, audits and versions remain intact.
- Public property discovery remains server-authoritative.
- Consent/destination revisions and current readiness are revalidated before actions.
- Manual handoff never claims provider delivery.
- Share history remains read-only and immutable.
- No Visit Session, entitlement, notification, media or provider contract was redesigned.
- No tracker or protected-data mutation occurred.
- No Git publication occurred.

**Final:** G10 is implemented, agent-tested and genuine-integration verified for the executed cohort.
Independent acceptance, owner visual acceptance and release readiness remain separate future gates.
