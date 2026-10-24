# PATHOME — P1 v0-to-Production Fidelity Parity Ledger

**Created before implementation:** 22 October 2026, IST  
**v0 repository:** `dipeshpatidar/pathome-UI`  
**v0 branch:** `v0/oe-panel-ux-finalization`  
**Approved reference checkpoint:** `2783641f8ef9a192852f004014344cb8759b4f69`  
**Production branch:** `feature/phase2a-oe-ui-integration`  
**Production baseline HEAD:** `a943775e07a153087d0b38c315db20e18b30aec5`

The isolated read-only v0 checkout used the final branch tip. Its latest Lead-specific commit is
`2783641` (“enhance lead state restoration and conflict resolution in share flow”). No owner-owned
local v0 checkout or separate newer approved capture set was present.

Approved composition evidence:

- `components/ops/ops-workspace.tsx`
- `components/ops/ops.css`
- `components/ops/ops-polish.css`
- `components/ops/ops-workflows.css`
- `components/ops/leads/leads.css`
- `components/ops/leads/leads-module.tsx`
- all referenced Lead surface components
- captured `/ops` home, Leads queue, capture and detail at 1440px

## Governance preservation

- Existing Pathome global header remains the sole account, notification and top-level identity owner.
  The v0 review strip, persona switcher and synthetic preview header are not production features.
- Existing authenticated capability data determines visible navigation.
- Existing backend URLs, DTOs, authorization, idempotency, consent, attachment and history logic are
  unchanged.
- Persistent v0 navigation uses `/operations` routes; it does not restore synthetic in-memory-only
  navigation.

## Surface ledger

| Surface | Approved v0 reference | Current divergence | Smallest correction | Preserved business behavior | Objective acceptance |
| --- | --- | --- | --- | --- | --- |
| Operations shell | `OpsWorkspace` `oe-frame` → persistent `oe-side` + `oe-body`; one `oe-main` content region | `OperationsDestination` swaps the whole Operations workspace for `OperationsLeadsWorkspace` on every Lead route | Mount one shared Operations workspace shell and render Lead content inside its content region | Existing Operations Overview/queue/detail routing, session authorization and deep links | Sidebar remains mounted while navigating Overview ⇄ Leads ⇄ detail/capture/share; one shared shell only |
| Desktop navigation | v0 sidebar with Operations home, attention, my work, claimable, Leads and conditional exceptions | Lead routes lose the sidebar and show a second local button nav | Capability-filtered persistent sidebar; active state from the actual route | Existing server capability gates and queue counts | Only authorized destinations appear; Leads/detail/share keep Leads active |
| Mobile navigation | v0 auto-sized workspace tabbar with short labels and safe-area padding | Lead routes replace Operations nav with a separate local header; current tabbar uses long labels | One auto-sized tabbar using short labels and authorized destinations only | Existing browser routes and actions | No cramped second navbar at 320–430px; touch and active states remain clear |
| S3 queue | v0 `LeadQueue`: page header, New enquiry action, compact filter row, scoped count, flat row list with status/follow-up/metadata | Visual vocabulary mostly exists, but queue is rendered in a standalone CRM shell with a second title/nav block | Render queue in shared `oe-main`; suppress duplicate workspace-level heading; align row/filters to v0 spacing and information hierarchy | Real paging, filters, scoped counts and duplicate-safe row actions | Queue hierarchy and persistent shell match v0; filter/page state remains real and refresh-safe |
| S4 capture | v0 grouped “Who and where from” + optional requirements card, sticky action bar, truthful unknown states | Standalone shell and local navigation; intake destination/security states add necessary production fields | Keep production fields but render page hierarchy/cards/actions inside shared shell without local nav | Duplicate lookup, draft fencing, explicit attach/keep-distinct, unknown phone, idempotent capture | Capture page uses v0 header/back/card/form rhythm and preserves every G10 safeguard |
| S5 detail | v0 back, identity/status header, action cluster, next-step card, requirements, calls/notes, activity and right rail | Standalone shell plus duplicated local navigation | Shared shell; preserve production detail composition while applying v0 spacing, typography and right-rail hierarchy | Ownership, reveal, versioning, activity, lifecycle and source evidence | Detail reads as one v0 Lead workspace and all owner/read-only states remain truthful |
| S6 discovery/shortlist | v0 two-column discovery with fixed ordered-shortlist rail on desktop and bottom action treatment on mobile | Existing production layout is adapted but lacks the shared shell context | Remove extra outer padding/nav; retain v0 discovery/shortlist hierarchy and action placement | Shared public Smart Search, generation fencing, ordered shortlist and public truth | Search/shortlist reads as part of same Lead workspace at all approved widths |
| S7 recipient/consent | v0 destination choices and consent within the Lead flow | Existing content is functionally richer but separated by local shell | Render inside shared shell with v0 fieldset/card semantics and same action order | SAME/DIFFERENT/NONE, withdrawal/dispute, revision and destination binding | Consent forms remain fully usable/accessible without visual shell break |
| S8 handoff/history | v0 handoff and immutable history tabs within Lead flow | Existing page has separate workspace header | Shared shell; align tabs, history cards and preview treatment to v0 | Secure manual WhatsApp attempt, no automatic send/delivery claim, event recovery | Handoff/history remain in same Operations workspace with persistent sidebar/tabbar |
| S10 exceptions | v0 conditional navigation and dedicated restricted view | Current exceptions live in another standalone Lead header | Render restricted view in shared shell; expose destination only for independent diagnostic authority | Separate diagnostics versus ordinary Lead access | Non-admin has no exceptions tab; Admin surface uses shared shell and redacted diagnostics |
| Empty/loading/error | v0 uses OE skeleton/state-zone language | Lead-local states sometimes replace the whole shell | Keep shared shell mounted while Lead content changes state | No stale/private data on denial/session replacement | Shell remains stable; protected content fails closed |
| Modals | v0 bottom sheet/mobile and centered desktop dialog | Existing modal largely matches | Preserve production focus trap and use v0 scrim/modal geometry | No clipboard/launch without explicit action | Sidebar cannot be interacted through an open modal; keyboard focus remains trapped |

## Confirmed root cause

`OperationsDestination.tsx` performs a top-level conditional replacement:

```text
/operations/leads*  → OperationsLeadsWorkspace (independent shell)
other /operations* → OperationsWorkspace (Operations shell)
```

`OperationsLeadsWorkspace.tsx` then supplies its own `ld-workspace-nav`, skip link and page section.
The sidebar and mobile tabbar therefore unmount at the route boundary. This is route composition,
not a CSS-only defect.

## Required tests

- Static/source tests proving one shared shell and no standalone Lead route branch.
- Shared-shell navigation active-state and capability filtering.
- Mobile tab count/label logic.
- Existing queue/capture/detail/share tests updated only for composition.
- Browser journey at 320, 375, 390, 430, 768, 1024 and 1440 with sidebar/tabbar visible across
  Overview, queue, capture, detail, share/history and authorized exceptions.
- Keyboard focus, modal trap, browser Back/Forward, refresh, deep link and account switch.
