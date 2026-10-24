# PATHOME G10 — OE PLAIN-LANGUAGE AND RESPONSIVE LEDGER

**Date:** 23 October 2026, IST  
**Status:** IMPLEMENTED; focused automated tests executed; browser and owner acceptance recorded separately

This ledger preserves the existing Operations v0 structure, routes, controls and permissions. It changes
primary wording only where the underlying business action remains the same.

| Route / component | Previous wording | Approved implementation | Business meaning preserved | Verification |
|---|---|---|---|---|
| Operations overview / navigation | Source exceptions | Import issues | Same Staff Admin review surface for enquiries that could not be imported | Static language audit |
| Operations overview | Browse feasible GEs | Matching Ground Executives | Same bounded eligible-GE discovery | Batch C regression + language audit |
| Operations overview | Reload the session | Refresh visit plan | Same read-only plan refresh | Existing scheduling tests + language audit |
| Visit plan detail | Execution / Execution state changed | Visit progress / Visit progress last updated | Same execution timestamps; no new state | Build + integration |
| Visit plan detail | Read-only history | Kept for history / view only | Same terminal, non-editable state | Build + integration |
| Visit availability | Property windows | Property visit times | Same per-property availability windows | Build + integration |
| Visit availability | Plan snapshot reference | Visit plan details | Same authorized supporting detail in disclosure | Build + integration |
| Visit availability | Browse feasible GEs | Matching Ground Executives | Same candidate discovery | Batch C regression |
| Lead capture | Intake destination | City and team | Same persisted `cityId` / `teamId` contract | Lead DTO/query tests |
| Lead detail | Source attribution | Enquiry source | Same source provenance; raw details remain protected | Lead integration |
| Lead detail | Reveal number to call | Show phone number | Same authorized reveal and auditing | Operations contact tests |
| Lead detail | PROPERTY_SHARING / GRANTED / REVOKED | Customer permission with plain status | Same consent purpose and server state | Lead integration |
| Lead detail | Read-only through your current authority | View only with your current access | Same server authorization and ownership | Lead capability tests |
| Property selection | Share public properties | Share property details | Same public-property shortlist | Lead integration |
| Property selection | New share | New message | Same property-message preparation | Preparation recovery tests |
| Recipient step | Destination | WhatsApp number | Same encrypted destination contract | Consent tests |
| Recipient step | Consent · PROPERTY_SHARING | Customer permission | Same revocable purpose-specific consent | Consent receipt tests |
| Recipient step | Record consent and continue | Save permission and continue | Same consent command and expected versions | Consent tests |
| Preparation step | Prepare the immutable share snapshot | Prepare the property message | Same immutable server-stored message, hidden from ordinary UI | Preparation recovery tests |
| Preparation step | Prepare share | Prepare property message | Same initial mutation | Preparation recovery tests |
| Preparation step | Check share status | Check if message is ready | Read-only receipt and exact history reconciliation only | GET-only test |
| Preparation step | The prepare reply was lost | We couldn’t confirm that the property message is ready | Truthful uncertain acknowledgement | Browser negative case |
| Preparation step | Nothing has been sent | No message has been sent to the customer | Same truthful non-delivery statement | Browser negative case |
| Preparation step | Blanket “service unavailable” state for every 503 | Proven no-write configuration rejection: “The customer property links are not available yet…” | A confirmed rejection ends the old pending check and permits a deliberate new preparation; every ambiguous 503 stays read-only and uncertain | Preparation recovery unit tests + controlled browser |
| Preparation step | Check share status | Check if message is ready | Read-only GET receipt plus exact authorized history lookup; no preparation POST | Preparation recovery tests |
| Handoff step | Handoff / Manual WhatsApp handoff | Send in WhatsApp | Same manual, operator-controlled action | Existing S8 tests |
| Handoff step | Copy prepared message | Copy message | Same exact prepared text | Browser flow |
| Handoff step | Share history | Message history | Same immutable historical record | Lead integration |
| Handoff step | Wrong number or consent — change it | Wrong number or permission — change it | Same return to recipient step | Build + browser |
| Handoff step | WhatsApp opened | Attempted to open WhatsApp | A recorded handoff attempt is not evidence that a browser tab opened, Send was pressed, or delivery occurred | Event presentation + plain-language tests |
| Handoff recovery | The saved history shows that WhatsApp was already opened… | We recorded your attempt to open WhatsApp, but couldn’t confirm that it opened. Check WhatsApp before trying again. | Preserves the attempt fact while making no navigation or delivery claim | Controlled acknowledgement-loss negative case + responsive browser run |
| Visit scheduling conflict | The server rejected this command and no completed receipt was recorded | The request was rejected and no saved result was recorded | Same definitive command rejection without implementation terminology | Scheduling tests + language audit |
| Request and visit contact guidance | backend / canonical contact | Pathome can confirm the saved details / saved lessor contact | Same server-authorized disclosure and audit boundary described in ordinary language | Contact tests + language audit |
| Import retry result | The current persisted state has been refreshed | The current details have been refreshed | Same authoritative refresh after a retry | Import recovery tests + language audit |
| Import issues | Diagnostic code / payload retained | Issue code / original enquiry kept | Authorized technical detail remains behind disclosure; no raw data added | Build + static audit |
| Shared notifications | 36px category tabs and compact error dismiss | 44px minimum category tabs and error dismiss | Same filters and dismiss action | CSS/source audit |

## Preserved distinctions

- Saved: a server-confirmed receipt or current record exists.
- Waiting: the saved request is not yet visible.
- Unavailable: the service or required public-link configuration is unavailable.
- Uncertain: the request may have been accepted, but the response was not confirmed.
- Prepared: a message exists on the server; the customer has not received it.
- Attempted to open WhatsApp: a handoff attempt was recorded; the screen cannot confirm that the
  browser opened a WhatsApp tab, that Send was pressed, or that delivery occurred.
- Operator reported sent: only the operator’s report is stored.

## Automated guard

`frontend-web/src/tests/operationsPlainLanguage.test.mjs` rejects the removed primary phrases and checks
the approved replacements. It is a regression guard, not a substitute for owner visual acceptance.

## Five-finding correction ledger — 23 October 2026

| Component | Previous text | Corrected text | Business truth | Responsive behavior | Test evidence |
|---|---|---|---|---|---|
| Preparation step, definitive rejection | Every 503 retained an endless pending check | The customer property links are not available yet, so this message was not saved… Try again… | Only the two existing trusted configuration rejections are proven no-write outcomes; ambiguous responses remain uncertain | Form stays available; action changes to **Prepare message**; 44px controls and no overflow at all eight widths | Preparation recovery tests; controlled Chromium |
| Preparation step, event label | WhatsApp opened | Attempted to open WhatsApp | A recorded attempt is not evidence of browser navigation, Send, or delivery | Natural wrapping in event history at all eight widths | Plain-language tests; controlled Chromium |
| Handoff recovery | The saved history shows that WhatsApp was already opened… | We recorded your attempt to open WhatsApp, but couldn’t confirm that it opened. Check WhatsApp before trying again. | The attempt is recorded; opening is unconfirmed | Warning notice wraps without clipping; destructive action now wraps inside 320px and retains a 44px target | Controlled acknowledgement-loss check; 320–1440px Chromium pass |
| Visit scheduling conflict | The server rejected this command and no completed receipt was recorded | The request was rejected and no saved result was recorded | Definitive rejection with no saved command result | Existing scheduling drawer layout preserved | Scheduling closure suite; language audit |
| Request and visit contact guidance | backend / canonical contact | Pathome can confirm the saved details / saved lessor contact | Same audited server-authorized contact disclosure | Existing contact cards and mobile stacking preserved | Contact suite; language audit |
| Import retry result | The current persisted state has been refreshed | The current details have been refreshed | The authoritative record was refreshed after import retry | Existing import-issues responsive layout preserved | Import recovery suite; language audit |
