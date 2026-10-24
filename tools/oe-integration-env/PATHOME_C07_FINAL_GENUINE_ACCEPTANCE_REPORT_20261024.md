# PATHOME — C07 FINAL GENUINE ACCEPTANCE

Date: 24 October 2026, IST  
Repository: `/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0`  
Branch / HEAD / remote HEAD: `feature/phase2a-oe-ui-integration` / `a943775e07a153087d0b38c315db20e18b30aec5` / `a943775e07a153087d0b38c315db20e18b30aec5`  
Staging index: empty  
Git publication: none

## Final verdict

**DEVELOPER GENUINE C07 PASS — AWAITING INDEPENDENT SOL ACCEPTANCE**

The focused connected campaign passed **147/147 checks** against real Chromium, Spring Boot and the exact approved disposable PostgreSQL target. This is developer acceptance evidence. It is not independent Sol acceptance, Web V1 release approval, physical-device approval or production approval.

## 1. Source and build identity

### Approved target

- Host / port: `127.0.0.1:5432`
- Database: `pathome_oe_test_c07_correction_20261024`
- Run ID: `routing-c07-correction-20261024-01`
- Schema: `routing_c07_correction_20261024_01`
- Migrator: `routing_c07_correction_20261024_01_migrator`
- Runtime: `routing_c07_correction_20261024_01_app`
- Private run directory: `/private/tmp/pathome-routing-c07-correction-20261024-01` (`0700`)

### Corrected production source

| File | SHA-256 |
|---|---|
| `OperationsRoutingWorkspace.tsx` | `809f2b9fd7f6409f279d35cf22192685453828099b6a48a1d6c2fc9737d254d8` |
| `useOperationsCapability.ts` | `1c11d7f1d843deb20184d33ea573737d2f16e7e6f786a930ce36066bc1e80c18` |
| `operationsWorkspacePresentation.ts` | `66481c2643cc4ba243bb0768f9dc799c80b27398b18e9cfb7f6a08c3284fce3e` |

The three working files differ from the historical archive source, as expected for the corrected generation. The historical archive was not rewritten or rolled back.

- Current preflight source tree hash: `721bcabeb6e1bdcccbdae65fe17cdd0858603dc5796b8fe8390a94a134a6aa25`
- Production frontend build tree hash: `fac7669075979223a8ba930a0248419afe00693c57be90acc6816d437163be92`
- Fresh nonignored source manifest: `/Users/dipeshpatidar/Documents/Pathome-Routing-C07-Correction-20261024-source-checkpoint/current-source.manifest.sha256`
- Fresh manifest SHA-256: `011041848c13951cc16b4ffc04a2fda1a26563b5429b9570ca88a113bbb83a22`
- Historical archive SHA-256: `98c9f6405ebf399b2ef514e61d45c009f8c1da075df804707f0d6ee7c839c1ed`

## 2. Architecture and edge-case coverage

The acceptance preserved the backend as the authorization source of truth. No production source, backend DTO/API, database schema/migration, grant semantics, navigation contract, approved copy or `Home.tsx` was changed.

The real campaign covered:

- Current authenticated User, token and grant identity on every capability receipt.
- Explicit restricted and unavailable terminal states.
- Structural validation of capability DTOs, including valid empty grants.
- City routing and Team coordination as independently checked authorities.
- Wrong-City denial, concealed protected data and absence of a phantom success audit.
- Real Admin API grant and revoke operations, followed by current-authority revalidation.
- Clean and delayed account transitions across distinct real synthetic identities.
- Session-owned capability timeout, abort and Retry.
- Current-account malformed and HTTP 5xx failure handling.
- Route-entry admission, protected-read suppression and session-generation isolation.
- No protected-account content flashing during a newer restricted account.
- Four-width responsive and keyboard behavior.

## 3. G01–G16 acceptance matrix

All results below are in the final evidence JSON:

`tools/oe-integration-env/evidence/c07-correction-20261024/routing-p1-c07-browser-2026-10-24T09-21-51-994Z.json`

| Case | Classification | Result | Business-state evidence |
|---|---|---|---|
| G01 zero-grant B | GENUINE CONNECTED | PASS | Real capability `200`, `staffActive=true`, `capabilities=[]`; explicit restricted; zero protected routing reads; no workspace |
| G02 valid routing actor A | GENUINE CONNECTED | PASS | Current City routing plus Team grants; admitted workspace; current City `Bhopal (2)` |
| G03 Team-only C | GENUINE CONNECTED | PASS | Real Team grant; routing `403`; restricted UI; claimable work `200` |
| G04 wrong-City D | GENUINE CONNECTED | PASS | City `Indore (1)` only; wrong-City coverage/options `403`; no forbidden team data; D actor success-audit count `0` |
| G05 revoke City | GENUINE CONNECTED | PASS | Genuine Admin API revoke; focus revalidation reached restricted; Team claimable work stayed `200` |
| G06 revoke Team | GENUINE CONNECTED | PASS | Genuine Admin API revoke; Team duty `403`; City routing stayed `200`; routing workspace stayed admitted |
| G07 A granted → B restricted | GENUINE CONNECTED | PASS | Real logout/login; B restricted; no A protected content entered B; route stayed `/operations/routing` |
| G08 A restricted → B granted | GENUINE CONNECTED | PASS | Restricted B to granted A reached A workspace without stale denial |
| G09 A → B → A | GENUINE CONNECTED | PASS | Newest A generation restored only A authority; route and scroll stable |
| G10 same-role A → D | GENUINE CONNECTED | PASS | Two real City-routing identities; D made fresh protected reads for City `1`; no A protected read transmitted after D settled |
| G11 late A success | CONNECTED WITH CONTROLLED FAULT | PASS | Real backend A response held at loopback proxy; logout retired transport; release attempt recorded as aborted; B stayed restricted |
| G12 late A error/finalizer | CONNECTED WITH CONTROLLED FAULT | PASS | Controlled late-error release attempt could not reach granted D after A transport retirement; no stale mutation or protected A read |
| G13 B settles before A | CONNECTED WITH CONTROLLED FAULT | PASS | D capability settled before release attempt; release event occurred afterward and did not change D |
| G14 malformed current response | CONNECTED WITH CONTROLLED FAULT | PASS | Real C response transformed by missing required structure; unavailable, no protected content, Retry restored genuine restricted state |
| G15 current timeout | CONNECTED WITH CONTROLLED FAULT | PASS | Current response suppressed; unavailable at `10080 ms`; owned transport aborted; one capability check; Retry reached D granted |
| G16 transport/HTTP failure | CONNECTED WITH CONTROLLED FAULT | PASS | Real `401` denial and wrong-City `403`; controlled current `500` became unavailable without internal detail; Retry restored B restricted |

G11–G13 are not labelled as unmodified genuine deliveries. The proxy held the genuine backend response, but logout correctly closed the retired browser transport. The release attempt is therefore recorded with `deliveredStatus: null` and `aborted: true`; no stale body was fabricated or delivered.

## 4. Database, authorization and audit truth

The unchanged Flyway chain completed with:

- successful history rows: `54`
- failed history rows: `0`
- latest migration: `54 | automatic visit routing | 1594209664`
- schema owner: migrator role
- runtime schema/public/database CREATE: `false:false:false`

Final grant readback:

- A (`userId 24`): active `OPS_ROUTING@CITY` City 2 and `OPS_COORDINATE@TEAM` Team 13.
- B (`userId 25`): both synthetic grants revoked; zero active City routing grants.
- C (`userId 26`): active Team coordination only; no City routing grant.
- D (`userId 27`): active City routing only for City 1.

Observed revoke reason codes include `C07_G05_LIVE_CITY_REVOKE`, `C07_G06_LIVE_TEAM_REVOKE`, `C07_B_CITY_REVOKED` and `C07_B_TEAM_REVOKED`.

Wrong-City actor D produced **0** operational audit events. Successful Admin grant/revoke/activation audit remained attributable to the real synthetic Owner.

Review note: successful protected routing reads returned `Cache-Control: no-store`; global authorization-denial `403` responses did not. No protected data was present in those denials. Because the accepted C07 correction intentionally did not change backend authorization or error handling, the header gap is recorded for independent contract review rather than silently repaired.

## 5. UX and responsive acceptance

Connected Chromium checks passed at **320, 390, 768 and 1280 px**:

- `scrollWidth === innerWidth` at every width in both restricted and unavailable states.
- Explicit restricted and unavailable headings remained visible.
- Retry height: `46 px`.
- Retry keyboard focus indicator visible.
- Retry preserved `/operations/routing` and the same navigation entry count; no document reload.
- No protected workspace flash was observed for restricted B.
- Same-account revalidation preserved route, scroll and the focused `Refresh` control.
- No full-page skeleton replacement occurred during current-authority revalidation.

Manual visual review confirmed the 320 px unavailable state and 1280 px restricted state are legible, unclipped and truthful.

## 6. Verification executed

| Check | Result |
|---|---|
| `bash tools/oe-integration-env/routing-acceptance.sh gate1` | PASS, 5/5 |
| Offline Maven test-compile and `spring-boot:help` preflight | PASS |
| `npm --prefix frontend-web run build` | PASS; existing large-chunk warning |
| Focused routing/session/browser contract Node tests | PASS, 15/15 |
| Genuine baseline browser journey | PASS, 42/42 |
| Final genuine/fault-injected campaign | PASS, 147/147 |
| `git diff --check` | PASS |
| PostgreSQL target attestation | PASS |
| Flyway startup/history | PASS, 54 successful / 0 failed |
| Guarded cleanup | PASS; DB 0, exact roles 0, port 8080 listener 0, owned service processes 0 |

The full historical C01–C06 campaign was intentionally not rerun. Preserved evidence:

- `tools/oe-integration-env/PATHOME_ROUTING_CLOSURE_01A169A2_EXECUTION_REPORT_20261024.md`
- `tools/oe-integration-env/PATHOME_ROUTING_P1_GENUINE_ACCEPTANCE_REPORT_20261024.md`
- `tools/oe-integration-env/PATHOME_ROUTING_P1_CHECKPOINT_REPORT_20261024.md`

## 7. Checks not run

- Independent GPT-6.1 Sol High source-and-evidence acceptance: **NOT TESTED**.
- Physical iPhone/Safari, VoiceOver, physical keyboard and other hardware: **NOT TESTED**.
- Firefox/WebKit, screen-reader campaign and complete WCAG 2.2 AA audit: **NOT TESTED**.
- Staging, production, deployment, backup/restore and production security validation: **NOT TESTED**.
- Full historical frontend suite (previously reported 94/94) and full C01–C06 genuine campaign: **NOT RERUN**, per owner instruction.
- Backoffice T1–T7, DEP-054 and CRM/WhatsApp implementation: **NOT TESTED / NOT IMPLEMENTED**.

## 8. Tracker assessment

Both canonical workbooks under `Approved Versions` were inspected read-only. No newer approved workbook was present in that directory.

| Tracker | SHA-256 |
|---|---|
| Cross-Role Canonical 2026-10-23 | `cdc036af9efa0ab03a46b83a3ac80c4dcc87e10c6c9c7c5e9fb2ec987902297c` |
| Visit Session Canonical 2026-10-23 | `011adba3ae3391ddba5d42eb7a9ae578d6a9cf6edc9b12b6f91a4bd7c9e2e3dc` |

Preserved tracker boundaries:

- All 20 original OE questions and four batches remain recorded.
- DEP-050 and DEP-051 remain Phase 1 frozen/completed dependencies.
- DEP-054 remains separately gated after OE-panel completion and Astra design; it was not implemented.
- Backoffice T1–T7 remains approved for bounded implementation but unimplemented.
- T5 retention/erasure remains separately gated.
- Web V1 target remains 31 October with **HIGH RISK / NO GO**.
- Native Tenant/GE target remains 15 November with **AT RISK**.

Tracker evidence was not edited.

## 9. Cleanup and scope confirmation

- Database absent after cleanup.
- Exact migrator role absent.
- Exact runtime role absent.
- Port 8080 listener absent.
- Owned service processes absent.
- Backend binding/PID artifacts absent.
- No wildcard database cleanup.
- No protected database was opened or changed.
- No external SMS, WhatsApp, notification or media provider operation occurred.
- No migration, entity, DTO, API, grant or backend authorization change occurred.
- No stage, commit, push, merge, rebase, reset, stash, branch switch or deployment occurred.
- Git branch and local/remote HEAD remain `a943775e07a153087d0b38c315db20e18b30aec5`; index empty.

Evidence bundle:

`tools/oe-integration-env/evidence/c07-correction-20261024/`

Evidence manifest SHA-256:

`e40eac345b3539a4b0fa1432d145e0a417e0e8e9994cc1f385df0de57ef8e7fc`

## 10. Remaining gates and delivery impact

The corrected C07 implementation is ready for focused independent source-and-evidence acceptance. This does not close the full routing programme, Web V1 or native delivery. Independent Sol acceptance, physical devices, full accessibility, staging/recovery, Admin/CRM/Backoffice completion and owner release GO remain open.

**STOP after this consolidated acceptance report.** Do not change production source, trackers, Git publication or deployment automatically.
