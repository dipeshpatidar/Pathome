import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';

registerHooks({ resolve: resolveOperationsModule });
const { freshSchedulingSessionIsEligible } = await import('../utils/operationsWorkspacePresentation.ts');
const workspace = readFileSync(new URL('../components/operations/OperationsWorkspace.tsx', import.meta.url), 'utf8');

const session = (overrides = {}) => ({
  sessionId: 500,
  status: 'DRAFT',
  coordinatorUserId: 41,
  ...overrides
});

test('only the same DRAFT visit owned by the captured coordinator may open scheduling', () => {
  assert.equal(freshSchedulingSessionIsEligible(session(), 500, 41), true);
  assert.equal(freshSchedulingSessionIsEligible(session({ status: 'SCHEDULED' }), 500, 41), false);
  assert.equal(freshSchedulingSessionIsEligible(session({ status: 'STARTED' }), 500, 41), false);
  assert.equal(freshSchedulingSessionIsEligible(session({ sessionId: 501 }), 500, 41), false);
  assert.equal(freshSchedulingSessionIsEligible(session({ coordinatorUserId: 99 }), 500, 41), false);
  assert.equal(freshSchedulingSessionIsEligible(null, 500, 41), false);
});

test('the drawer open captures context, verifies authority, reads fresh state, then opens', () => {
  const start = workspace.indexOf('const openSchedulingDrawerFresh');
  const end = workspace.indexOf('const scheduleOutcomeContextHolds', start);
  const body = workspace.slice(start, end);
  const capture = body.indexOf('const captured = captureOperationsSession()');
  const root = body.indexOf('const mountedRoot = openRootRef.current');
  const authority = body.indexOf('const authorityGeneration');
  const verify = body.indexOf('authorityRef.current.verifySession');
  const freshRead = body.indexOf('operationsWorkspaceService.sessionDetail');
  const eligibility = body.indexOf('freshSchedulingSessionIsEligible');
  const open = body.indexOf('openScheduleDrawer(sessionId)');
  assert.ok(capture > -1 && root > capture && authority > root);
  assert.ok(verify > authority && freshRead > verify && eligibility > freshRead && open > eligibility);
});

test('a second click is refused and late answers are gated by generation, abort and context', () => {
  const start = workspace.indexOf('const openSchedulingDrawerFresh');
  const end = workspace.indexOf('const scheduleOutcomeContextHolds', start);
  const body = workspace.slice(start, end);
  assert.match(body, /schedulingOpenRef\.current\.controller !== null/);
  assert.match(body, /scheduleDrawerRef\.current\.sessionId !== null/);
  assert.match(body, /new AbortController\(\)/);
  assert.match(body, /schedulingOpenRef\.current\.generation === generation/);
  assert.match(body, /isOperationsSessionActive\(captured\)/);
  assert.match(body, /authorityRef\.current\.authorizationGeneration\(\) === authorityGeneration/);
  assert.match(body, /openRootRef\.current\.mountGeneration === root\.mountGeneration/);
  assert.match(body, /if \(!stillCurrent\(\)\) return;/);
});

test('a failed fresh read offers a retry and never opens the drawer with the old snapshot', () => {
  const start = workspace.indexOf('const openSchedulingDrawerFresh');
  const end = workspace.indexOf('const scheduleOutcomeContextHolds', start);
  const body = workspace.slice(start, end);
  const catchIndex = body.indexOf('} catch (cause) {');
  const catchBody = body.slice(catchIndex);
  assert.match(catchBody, /setSchedulingOpenError/);
  assert.doesNotMatch(catchBody, /openScheduleDrawer\(/);
  const detail = readFileSync(new URL('../components/operations/OperationsWorkDetail.tsx', import.meta.url), 'utf8');
  assert.match(detail, /schedulingOpenError \? \(/);
  assert.match(detail, /Checking latest visit details…/);
  assert.match(detail, /Try again/);
});

test('the existing receipt path is untouched by the fresh pre-open read', () => {
  const start = workspace.indexOf('const openSchedulingDrawerFresh');
  const end = workspace.indexOf('const scheduleOutcomeContextHolds', start);
  const body = workspace.slice(start, end);
  assert.doesNotMatch(body, /commandId|preserveSchedulingIntent|forgetSchedulingIntent|reconcileScheduling/);
  assert.match(workspace, /readSchedulingIntent\(captured, sessionId\)/);
});

test('a fresh denial withdraws the exact mounted root synchronously and never opens a new drawer', () => {
  const start = workspace.indexOf('const openSchedulingDrawerFresh');
  const end = workspace.indexOf('const scheduleOutcomeContextHolds', start);
  const body = workspace.slice(start, end);
  const catchIndex = body.indexOf('} catch (cause) {');
  const catchBody = body.slice(catchIndex);
  const denial = catchBody.indexOf('status === 403 || status === 404');
  const withdraw = catchBody.indexOf('withdrawDeniedRootRef.current?.(root)');
  const error = catchBody.indexOf("setSchedulingOpenError('This visit is no longer available");
  assert.ok(denial > -1 && withdraw > denial && error > withdraw,
    'the denial closes the exact root before reporting the refusal');
  assert.doesNotMatch(catchBody, /openScheduleDrawer\(/);
  assert.doesNotMatch(catchBody, /refreshAll\(/,
    'protected detail and contact are removed without waiting for a queue refresh');
});

test('a changed coordinator or a non-DRAFT current state is published truthfully without booking', () => {
  const start = workspace.indexOf('const openSchedulingDrawerFresh');
  const end = workspace.indexOf('const scheduleOutcomeContextHolds', start);
  const body = workspace.slice(start, end);
  const coordinator = body.indexOf('fresh.coordinatorUserId !== captured.userId');
  const coordinatorWithdraw = body.indexOf('withdrawDeniedRootRef.current?.(root)', coordinator);
  const malformed = body.indexOf('fresh.sessionId !== sessionId');
  const ineligible = body.indexOf('freshSchedulingSessionIsEligible(fresh, sessionId, captured.userId)');
  const currentSnapshot = body.indexOf("setDetail({ status: 'ready', snapshot: { kind: 'session', session: fresh } })", ineligible);
  assert.ok(malformed > -1 && ineligible > malformed);
  assert.ok(coordinator > -1 && coordinatorWithdraw > coordinator,
    'a root now owned by another coordinator is withdrawn instead of installed');
  assert.ok(currentSnapshot > ineligible,
    'the same owned non-DRAFT Session is shown as the real current state');
  assert.equal(body.slice(ineligible, currentSnapshot).includes('openScheduleDrawer(sessionId)'), false,
    'neither refusal path can open NEW scheduling');
  assert.match(body, /fresh\.sessionId !== sessionId/);
  assert.match(body, /mountGeneration === root\.mountGeneration/);
});
