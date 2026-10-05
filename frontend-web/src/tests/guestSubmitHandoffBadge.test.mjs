import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const home = read('../components/Home.tsx');
const workspace = read('../components/LessorWorkspace.tsx');
const preview = read('../components/LessorPreviewStep.tsx');
const navbar = read('../components/Navbar.tsx');
const drafts = read('../services/lessorDraftService.ts');
const submission = read('../services/lessorSubmissionService.ts');
const backendSubmission = read('../../../backend/src/main/java/com/indore/pathome/spaces/service/LandlordSubmissionService.java');
const backendClaim = read('../../../backend/src/main/java/com/indore/pathome/spaces/service/GuestDraftService.java');

test('guest final submit records intent and auth success keeps the current property route', () => {
  assert.match(preview, /if \(guest\) \{ onGuestSubmit\?\.\(\); return; \}/);
  assert.match(home, /sessionStorage\.setItem\(submit \? 'pathome_guest_submit_draft' : 'pathome_guest_save_draft', draftId\)/);
  assert.match(home, /if \(userProfile\.role === 'TENANT' && location\.pathname\.startsWith\('\/lessor\/'\)[\s\S]*?pathome_guest_submit_draft[\s\S]*?return;/);
});

test('guest draft is claimed before any authenticated read and submit context blocks the editor', () => {
  const guestHandoff = workspace.match(/if \(draftId\?\.startsWith\('guest-'\) && !ownerDraft\) \{([\s\S]*?)\n    \}/)?.[1] ?? '';
  assert.match(guestHandoff, /pendingSubmit \|\| pendingSave\) void claimDraft\(draftId, pendingSubmit\)/);
  assert.match(guestHandoff, /else if \(live\) setError/);
  assert.doesNotMatch(guestHandoff, /lessorDraftService\.get\(/);
  assert.match(workspace, /const postAuthSubmitPending = Boolean\([\s\S]*?pathome_guest_submit_draft[\s\S]*?const editorVisible = !submissionComplete && !postAuthSubmitPending/);
  assert.match(drafts, /claimGuest\(draftId: string\)[\s\S]*?GUEST_BASE[\s\S]*?\/claim/);
});

test('submission feedback stays visible and retry resolves owned draft state before promoting', () => {
  assert.match(workspace, /const draft = await lessorDraftService\.get\(id\);[\s\S]*?lessorSubmissionProgressService\.start\(id\)[\s\S]*?lessorMediaService\.promote\(id\)[\s\S]*?lessorSubmissionService\.submit\(id\)/);
  assert.match(workspace, /transition !== 'idle' && transition !== 'promoting' && transition !== 'submitting' && !visibleSubmissionProgress/);
  assert.doesNotMatch(workspace, /Submitting your property…/);
  assert.match(workspace, /PathomeSubmissionProgress/);
  assert.match(backendClaim, /The row lock serializes competing claims\. A repeat by the rightful owner is safe\./);
  assert.match(backendSubmission, /if \(!"DRAFT"\.equals\(draft\.getStatus\(\)\)\)[\s\S]*?"REVIEW"\.equals\(draft\.getStatus\(\)\) \|\| "SUBMITTED"\.equals\(draft\.getStatus\(\)\)/);
  assert.match(backendSubmission, /listings\.findByOriginDraftId\(draftId\)[\s\S]*?\.filter\(listing -> ownerId\.equals\(listing\.getOwnerUserId\(\)\)\)/);
});

test('successful submission retains the existing confirmation and listings destination', () => {
  assert.match(workspace, /lessorSubmissionComplete: true,[\s\S]*?lessorSubmissionUserId: user\?\.id/);
  assert.match(workspace, /Property submitted/);
  assert.match(workspace, /Go to My Properties/);
});

test('notification badge keeps count, zero visibility, accessibility and bell action while using a restrained anchored style', () => {
  const bell = navbar.match(/<motion\.button[\s\S]*?aria-label=\{unreadCount > 0[\s\S]*?<\/motion\.button>/)?.[0] ?? '';
  assert.ok(bell, 'notification button remains rendered');
  assert.match(bell, /relative flex min-h-11 min-w-11/);
  assert.match(bell, /unreadCount > 0 \? `Open notifications, \$\{unreadCount\} unread`/);
  assert.match(bell, /unreadCount > 9 \? '9\+' : unreadCount/);
  assert.match(navbar, /\{unreadCount > 0 && \([\s\S]*?<span[\s\S]*?aria-hidden="true"/);
  assert.match(bell, /navigate\('\/tenant#notifications'\)/);
  assert.match(bell, /absolute -top-1 -right-1 inline-flex h-\[18px\] min-w-\[18px\]/);
  assert.match(bell, /bg-\[#355c49\].*text-white/);
  assert.doesNotMatch(bell, /border-2 border-slate-950|shadow-lg shadow-emerald/);
});
