# PATHOME — P1 WhatsApp Final-Activation Security Correction

**Date:** 22 October 2026 IST

**Scope:** Owner-approved, release-blocking correction only

**Verdict:** IMPLEMENTED / AGENT-VERIFIED / GENUINE-INTEGRATION-VERIFIED

**Independent acceptance:** NOT CLAIMED. A separate focused Sol reacceptance is required.

## 1. Original Defect and Baseline Discriminator

Sol's reported sequence was reproduced against the original cached-link interaction:

1. Prepared and verified an authorized manual WhatsApp share.
2. Kept the confirmation dialog open.
3. Withdrew consent.
4. Activated the cached `waUrl` anchor.

The same controlled Chromium discriminator was run before and after correction. In the baseline
production-code build, the withdrawal case failed exactly as expected:

`events=0 navigations=1`

The browser navigated to the stale WhatsApp destination without a persisted handoff event. The final
corrected build passed the same assertion with `events=0 navigations=0`.

Evidence:

- `evidence/p1-whatsapp-final-activation-20261022/baseline/controlled-results.json`
- `evidence/p1-whatsapp-final-activation-20261022/corrected/controlled-results.json`

## 2. Confirmed Root Cause

The final dialog action was an unconditional anchor carrying the previously generated `waUrl`.
`verifyLaunch()` refreshed history and revealed the destination, but final activation did not obtain a
fresh server decision. The request was immediately followed by cached-anchor default navigation.

The pre-existing share-event endpoint also verified the actor/ownership relationship but did not
recheck the prepared share's current consent revision, destination, shortlist version, Lead lifecycle,
or public-property facts before writing a new handoff event.

## 3. Implemented Architecture

The correction uses the existing API family and preserves the approved contracts:

- `POST /shares` remains the idempotent replay/readiness contract used immediately before clipboard work.
- `POST /shares/{shareId}/events` is now the authoritative final gate for new `HANDOFF_ATTEMPTED` and
  `CLIPBOARD_COPIED` events.
- No endpoint, DTO, migration, permission, provider integration, or schema contract was added.
- No new dependency was added.

The final event transaction locks the Lead, selected shortlist, and selected listing rows before
validating and writing. It checks:

- authenticated actor and active effective grant;
- current editable Lead ownership and active lifecycle;
- requested share belongs to the requested Lead;
- current consent revision and `GRANTED` status;
- current destination binding and masked destination;
- current shortlist identity/version and ordered listing membership;
- current ACTIVE/public eligibility, canonical facts, links, and exact prepared preview.

Already-recorded event IDs remain read-only replays. That preserves lost-acknowledgement recovery
without authorizing or performing the external action a second time.

## 4. Frontend Final-Activation Behavior

The final dialog uses a genuine button, not a navigable cached anchor. On activation it:

1. Establishes a synchronous single-flight guard and activation generation.
2. Reserves an empty `about:blank` tab during the browser gesture.
3. Removes `window.opener` immediately and aborts if isolation fails.
4. Calls the existing event endpoint for final server authorization.
5. Navigates the reserved tab to WhatsApp only after the server records the new authorized attempt.
6. Closes the reserved blank context on denial, failure, cancellation, supersession, or unmount.

The event is committed before browser launch. The UI says only that the browser was asked to open
WhatsApp and that the authorized attempt was recorded; it never claims opening, sending, delivery, or
read receipt.

The frontend fences results by dialog generation, activation generation, captured account credential,
dialog close/reopen, share identity, Lead identity, shortlist version, consent revision, unmount, and
later activation.

## 5. Copy and Lost-Acknowledgement Handling

Clipboard work re-runs the exact existing idempotent share preparation request immediately before the
clipboard API call. This revalidates consent, ownership, shortlist, destination, and public truth
without creating a second share.

After the clipboard succeeds, `CLIPBOARD_COPIED` is recorded through the guarded event endpoint. If
the acknowledgement is lost, history reconciliation may confirm the existing event, but the clipboard
is not written again automatically.

## 6. Browser Popup and Failure Behavior

- Popup blocked: no event, no external launch, explicit retry guidance.
- 401/403/404/409/503 or network failure: no external launch, no persisted attempt for a denied new
  request, and truthful recovery copy.
- Dialog cancel or route unmount while authorization is held: late response cannot launch.
- Account A→B→A while authorization is held: prior generation stays invalid.
- Double click or repeated activation: one attempt and one external tab.
- The reserved blank tab is closed after a denied or fenced result.

## 7. Failure-Class Matrix

| Scenario | Result | Verification |
|---|---|---|
| Verify → withdraw consent → activate | PASS; no external navigation, no persisted attempt | Controlled Chromium + genuine Spring/PostgreSQL |
| Verify → dispute consent → activate | PASS; denied, no event | Backend unit + genuine PostgreSQL |
| Verify → destination revision change → activate | PASS; cached destination denied | Genuine PostgreSQL |
| Verify → staff grant revoked → activate | PASS; authorization denied, no event | Genuine PostgreSQL |
| Verify → Lead reassigned to another OE → activate | PASS; previous OE blocked | Genuine PostgreSQL |
| Verify → Lead closed → activate | PASS; lifecycle conflict, no event | Genuine PostgreSQL |
| Verify → shortlist version changes → activate | PASS; no stale handoff | Genuine PostgreSQL |
| Verify → listing unpublished → activate | PASS; no stale handoff | Genuine PostgreSQL |
| Verify → public listing facts change while row is locked | PASS; final request waits, then rejects changed facts | Genuine PostgreSQL lock-wait test |
| Verification held → dialog cancel | PASS; held result cannot launch | Controlled Chromium |
| Verification held → route unmount | PASS; held result cannot launch | Controlled Chromium |
| Verification held → account A→B→A | PASS; old generation never restored | Controlled Chromium |
| Double click / repeated activation | PASS; one event and one controlled external tab | Controlled + genuine Chromium |
| 401 / 403 / 404 / 409 / 503 | PASS; fail closed with no launch | Controlled Chromium |
| Network failure | PASS; fail closed with no launch | Controlled Chromium |
| Valid unchanged consent/scope | PASS; exactly one authorized attempt and one controlled tab | Controlled + genuine Chromium |
| Lost event acknowledgement | PASS; persisted history recovery, no automatic second action | Controlled Chromium + existing backend receipt/history tests |
| Popup blocked | PASS; no event, no false success, retry guidance | Controlled Chromium |
| Clipboard fallback after stale authority | PASS; zero clipboard writes and zero event mutation | Controlled Chromium |
| Copy-mode `NONE` alternative | PASS; no WhatsApp action | Controlled Chromium + genuine Chromium |
| Lead navigation, search, shortlist, consent, history, and responsive regression | PASS | Controlled + genuine Chromium |

## 8. Negative Control

An isolated controlled-route injection bypassed the final server readiness guard. The identical
consent-withdrawal discriminator failed as required:

`events=1 navigations=1`

The bypass exists only as `PATHOME_P1_BYPASS_FINAL_GUARD=true` handling inside the controlled test
script. It is absent from production source and the production bundle. After the negative-control run,
the normalized suite was rerun without the bypass and passed.

Evidence:

- `evidence/p1-whatsapp-final-activation-20261022/negative-control/controlled-results.json`
- `evidence/p1-whatsapp-final-activation-20261022/corrected/controlled-results.json`

## 9. Genuine Database and Spring Evidence

Positive target attestation:

- Database: `pathome_oe_test_g10_20261022`
- User: `pathome`
- Endpoint: `127.0.0.1:5432`
- Sentinel: exactly one `DISPOSABLE`
- Guard version: `1.0.0`
- Run identity: `17cdb6d3-ef16-4b67-9f53-73725c859a41`
- Flyway: successfully validated 53 migrations; applied to V53 in disposable schemas
- Temporary `p4_crm_http_test_*` schemas remaining after cleanup: `0`

Migration checksums re-attested from the retained disposable database:

| Version | Checksum | Success |
|---|---:|---|
| V51 | 1632712085 | true |
| V52 | -2000833596 | true |
| V53 | 850334017 | true |

Migration file SHA-256:

```text
0d38b0db50874236b2bc254cd15cebe7d12a0de0df41419cf7455e2ab93c2c85  V51__operations_work_filters_indexes.sql
722a4886f2fd125f243920da5e633bdec0726e27936187fe2112e38c3955d2ee  V52__crm_lead_foundation_and_source_inbox.sql
15b856ad6fe8d5f2c999617f255b546b30a457d7ac2a9270bcca7db739d640d3  V53__crm_manual_sharing_and_source_recovery.sql
```

The focused final test covers valid authorization and read-only replay, revoked/disputed consent,
destination revision changes, reassignment, closed lifecycle, shortlist changes, unpublished property,
listing fact change under lock wait, and grant revocation.

## 10. Verification Executed

All commands were run against the final source unless explicitly identified as the historical baseline
or negative control.

| Check | Result |
|---|---|
| `mvn -q -f backend/pom.xml -Dtest=LeadShareServiceTest test` | 12/12 PASS |
| `mvn -q -f backend/pom.xml -DskipTests test-compile` | PASS |
| `LeadHttpPostgresTest#finalHandoffEventRevalidatesConsentDestinationLifecycleScopeAndPublicTruth` | PASS |
| `LeadHttpPostgresTest#genuineBrowserLeadJourneyUsesSpringPostgresAndProductionBuild` | PASS |
| `npm run build` | PASS; existing large-chunk warning only |
| `node --test frontend-web/src/tests/operationsLeadsIntegration.test.mjs` | 12/12 PASS |
| Controlled Chromium correction suite | 48/48 PASS; zero browser/page exceptions |
| Genuine React → Spring → PostgreSQL browser journey | 53/53 PASS; zero browser/page exceptions; zero unexpected API 5xx |
| `git diff --check` / `git diff --cached --check` | PASS; empty index preserved |

Responsive checks passed at 320, 375, 390, 430, 768, 1024, and 1440 px with no page overflow and
44 px visible-control evidence in both controlled and genuine suites.

## 11. Residual Limitation

There is a small unavoidable interval between the committed final server authorization and browser
navigation. Once the external WhatsApp application has received the destination, Pathome cannot
revoke or recall that tab. Likewise, a clipboard operation cannot be recalled after the browser has
written it. The implementation minimizes and fences those intervals but does not claim impossible
post-launch guarantees.

Physical iPhone/Safari popup behavior and complete VoiceOver/WCAG audit were not executed. Real
WhatsApp/Meta provider messaging, delivery, and read evidence were not tested.

## 12. Current Local Application Status

The retained owner-review processes from G10 remain bound to loopback:

- Frontend `127.0.0.1:5173`: HTTP 200
- Backend `127.0.0.1:8080`: protected route returns expected 401 without authentication

The retained backend process predates this correction and was not restarted, deployed, or used as
evidence for the corrected backend. Final backend evidence came from disposable random-port Spring
instances and isolated PostgreSQL schemas.

## 13. Changed Files

Files modified by this P1 correction:

- `backend/src/main/java/com/indore/pathome/spaces/repository/ListingRepository.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadPublicPropertyService.java`
- `backend/src/main/java/com/indore/pathome/spaces/service/LeadShareService.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadShareServiceTest.java`
- `backend/src/test/java/com/indore/pathome/spaces/service/LeadHttpPostgresTest.java`
- `frontend-web/src/components/operations/OperationsLeadsWorkspace.tsx`
- `frontend-web/src/tests/operationsLeadsIntegration.test.mjs`
- `frontend-web/browser-verification/operations-leads/controlled-corrections.mjs`
- `frontend-web/browser-verification/operations-leads/run.mjs`
- `chat_gpt_changes`
- this report and the focused evidence directory

No migration file was changed.

## 14. Git, Provider, Migration, and Protected-Data Confirmation

- Git index remained empty.
- No stage, commit, push, merge, PR, rebase, reset, stash, clean, or branch switch occurred.
- `pathome_db` was not accessed.
- Historical `pathome_oe_test_integration` was not modified.
- No real WhatsApp, Meta, provider, customer, or tracker action occurred.
- Canonical tracker workbooks are absent locally; their milestone synchronization remains pending.
- No substitute tracker was opened or edited.

## 15. Tracker and Release Assessment

The exact canonical tracker SHA-256 values supplied in the request could not be verified because the
workbooks are not present locally. Milestone synchronization remains pending.

- Full Web V1, 31 October 2026: **HIGH RISK**
- Native Tenant and GE, 15 November 2026: **AT RISK**

This correction does not constitute full Web V1 acceptance, owner visual acceptance, or release
readiness.

## 16. Recommendation

Recommend one focused independent **GPT-6.1 Sol High** reacceptance covering:

- the original verify → withdraw → activate discriminator;
- valid unchanged handoff exactly once;
- revoked/disputed consent, reassignment, lifecycle, shortlist, and public-fact denial;
- transient failures and popup behavior;
- lost acknowledgement recovery without automatic relaunch.

STOP before Git publication, tracker edits, deployment, or new feature work.
