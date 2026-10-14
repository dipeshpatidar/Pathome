# Operations destination — controlled browser verification (Package 2)

## SIMULATED BACKEND — not real-backend evidence

Runs the **real built app** (`frontend-web/dist`) in Chromium with every `/api/**` response produced
in-process by the controllable stub in `run.mjs`. There is no server port and no network, so **no
result from this file may be reported as a real-backend pass**. Real-backend boundary results come
from [`../operations-destination-real-backend/`](../operations-destination-real-backend/README.md).

This suite exists because the honest positive case cannot yet be produced end to end: the disposable
database has no Operating Team, so no account can hold an effective `OPS_COORDINATE@TEAM` grant.
That gap is recorded as **BLOCKED — PACKAGE 3 DEPENDENCY**, never worked around by weakening
authorization.

What *is* real: the production React tree, the real router and route guards, the real
`useOperationsCapability` state machine, the real workspace module, `localStorage`, focus events, and
the exact requests the app transmits — asserted from the stub's server-side log, including the
bearer credential on every call.

## Running it

```bash
npm --prefix frontend-web run build
node frontend-web/browser-verification/operations-destination/run.mjs
```

Requires Playwright with the full Chromium build. The script prints one line per check and exits
non-zero on any failure. A full pass takes well under a minute.

## Coverage

| Approved requirement | Scenario |
| --- | --- |
| An eligible Executive is offered Operations | `1` — the entry appears only after the grant is verified, never on a role label, and nothing navigates there automatically |
| The user deliberately chooses the destination | `2` — clicking opens `/operations`, the real workspace renders, and entry re-verifies with the server |
| Direct URL access, browser refresh, re-verification | `3` — a direct link is admitted on the verified capability; focus re-verifies; a refresh fetches no protected work before the verdict and shows the verifying state rather than stale work |
| No grant, inactive staff, and role labels as substitutes | `4` — every shape is refused explicitly, at the URL, with zero protected requests |
| Revoked capability | `5` — the workspace is withdrawn without a reload and the navigation entry disappears |
| Pending or failed verification | `3` (held verdict), `6` (5xx) — work stays hidden and Retry recovers from an authoritative re-read |
| Stale asynchronous responses across accounts | `7` — Account A's late grant cannot admit Account B, and no protected request carries A's credential after the switch |
| Responsive behaviour and touch targets | `8` — no horizontal overflow at 320/375/768/1024/1440; the compact header defers to the account menu; both entries meet 44px |
| Entry-specific verification (Package 2 correction) | `9` — with the destination's own entry verdict held, zero protected reads are transmitted and no queue is revealed; a valid verdict admits it and reads begin only then; a refusing verdict reads nothing; `10` — an embedded Employee *and* an embedded Admin workspace transitioning to `/operations` each re-verify as a new entry and are not admitted by the earlier verdict; `11` — with Account A's entry check held, an account switch issues B's own check, A's outdated completion admits nothing and no protected read carries A's credential; `12` — an outdated entry verdict released after a newer entry began reveals nothing while the current verdict still decides |
| Strict sign-in identity (Package 2 correction) | `13` — a server response of `{userId: true}` (and `[1]`, `''`, `0`, `-1`, `1.5`, `'1e3'`, `' 41 '`, unsafe integers, …) is refused by the real sign-in form: nothing is persisted, the visitor stays public and no protected read is transmitted; the same scenario proves a valid response still signs in |

## Not covered here

* Live Spring Boot/PostgreSQL authorization — that is the real-backend suite's job.
* The positive admitted path against a real backend (BLOCKED, above).
* Physical mobile devices and real touch input.

## Negative control

Scenarios 9-13 detect the defects they were written for: with the read-admission gate removed and the
loose `Number(...)` identity coercion reinstated in a local build, this suite reports 19 failures
(`zero protected reads start while the entry verdict is held (expected=0 actual=3)`, `no account is
persisted (expected=null actual="{\"id\":1,...}")`, …). The exact sources are restored afterwards and
all 90 checks pass.
