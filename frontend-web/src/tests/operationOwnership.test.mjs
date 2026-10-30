import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import {
  ExclusiveOperationGate,
  isCurrentProtectedDenial,
  isProtectedDenialStatus,
  isUncertainCommandFailure,
  operationOwnerIsCurrent
} from '../utils/operationOwnership.ts';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const read = relative => readFile(path.join(root, relative), 'utf8');

const importOwnershipMutant = async transform => {
  const source = await read('frontend-web/src/utils/operationOwnership.ts');
  const changed = transform(source);
  assert.notEqual(changed, source, 'the negative control must alter the ownership guard');
  const directory = await mkdtemp(path.join(tmpdir(), 'pathome-ownership-mutant-'));
  const file = path.join(directory, `mutant-${Date.now()}-${Math.random()}.ts`);
  await writeFile(file, changed, 'utf8');
  try {
    return await import(`${pathToFileURL(file).href}?v=${Date.now()}`);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
};

test('same-tick dispatch creates exactly one client operation intent', () => {
  const gate = new ExclusiveOperationGate();
  const first = gate.begin('account-a|token-a', '/backoffice/42', '42:draft-a', 1);
  const duplicate = gate.begin('account-a|token-a', '/backoffice/42', '42:draft-a', 1);
  assert.ok(first);
  assert.equal(duplicate, null);
});

test('an invalidated old finalizer cannot clear a newer operation', () => {
  const gate = new ExclusiveOperationGate();
  const old = gate.begin('account-a', 'A', 'listing-a', 1);
  gate.invalidate();
  const newer = gate.begin('account-b', 'B', 'listing-b', 2);
  assert.ok(old);
  assert.ok(newer);
  assert.equal(gate.owns(old), false);
  assert.equal(gate.finish(old), false);
  assert.equal(gate.owns(newer), true);
  assert.equal(gate.finish(newer), true);
});

test('owner comparison includes session, route, entity, and generation identity', () => {
  const captured = {
    operationId: 1, sessionIdentity: 'a', routeIdentity: 'A',
    entityIdentity: 'listing-a:draft-a', generation: 2
  };
  assert.equal(operationOwnerIsCurrent(captured, captured), true);
  for (const changed of [
    { ...captured, sessionIdentity: 'b' },
    { ...captured, routeIdentity: 'B' },
    { ...captured, entityIdentity: 'listing-b:draft-b' },
    { ...captured, generation: 3 }
  ]) {
    assert.equal(operationOwnerIsCurrent(captured, changed), false);
  }
});

test('protected denial and uncertain command statuses are distinguishable', () => {
  for (const status of [401, 403, 404, 410]) assert.equal(isProtectedDenialStatus(status), true);
  for (const status of [400, 409]) assert.equal(isProtectedDenialStatus(status), false);
  for (const status of [0, 408, 425, 429, 500, 503]) {
    assert.equal(isUncertainCommandFailure(status), true);
  }
  assert.equal(isUncertainCommandFailure(409), false);
});

test('only the current owner can withdraw protected state after a denial', () => {
  for (const status of [401, 403, 404, 410]) {
    assert.equal(isCurrentProtectedDenial(status, true), true);
    assert.equal(isCurrentProtectedDenial(status, false), false);
  }
  for (const status of [408, 425, 429, 500, 0]) {
    assert.equal(isCurrentProtectedDenial(status, true), false);
  }
});

test('the stale-denial ownership negative control exposes replacement data', async () => {
  const mutant = await importOwnershipMutant(source => source.replace(
    'currentOwner && isProtectedDenialStatus(status)',
    'isProtectedDenialStatus(status)'
  ));
  assert.equal(mutant.isCurrentProtectedDenial(403, false), true,
    'the intentionally unowned result must not be accepted by production');
});

test('editor guards parent callbacks and every finalizer by captured ownership', async () => {
  const editor = await read('frontend-web/src/components/backoffice/BackofficePropertyEditor.tsx');
  assert.match(editor, /operationGate\.current\.begin/);
  assert.match(editor, /if \(!ownsOperation\(owner\)\) return;[\s\S]{0,80}onRevision\(saved\)/);
  assert.match(editor, /if \(operationGate\.current\.finish\(owner\)\) setBusy\(null\)/);
  assert.match(editor, /commandOutcome\([\s\S]{0,100}submittedIntent\.id/);
  assert.match(editor, /publication outcome could not be confirmed/i);
  assert.match(editor, /applyRecoveredMedia\(current, mediaId, recovered\)/);
});

test('property and history context changes reset loading and reject old callbacks', async () => {
  const workspace = await read('frontend-web/src/components/backoffice/BackofficePropertiesWorkspace.tsx');
  assert.match(workspace, /editorGeneration === viewEpoch\.current/);
  assert.match(workspace, /setActivityLoadingMore\(false\)/);
  assert.match(workspace, /isProtectedDenialStatus\(status\)/);
  assert.match(workspace, /busyActionRef\.current = true/);
  assert.match(workspace, /await loadDetail\(\);[\s\S]{0,100}operationEpoch !== viewEpoch\.current[\s\S]{0,60}setTab\('history'\)/);
});

test('removal and owner pagination clear protected rows only on denial', async () => {
  const queue = await read('frontend-web/src/components/backoffice/PropertyRemovalQueue.tsx');
  const owner = await read('frontend-web/src/components/LessorListingView.tsx');
  assert.match(queue, /isProtectedDenialStatus\(statusOf\(cause\)\)[\s\S]{0,160}setItems\(\[\]\)/);
  assert.match(queue, /void load\(status, page, false\)/);
  assert.match(owner, /withdrawProtectedListingState/);
  assert.match(owner, /setRemovalHistory\(\[\]\)/);
  assert.match(owner, /isCurrentProtectedDenial\(statusOf\(cause\), owns\(\)\)/);
  assert.match(owner, /removealOperationIsOwned|removalOperationIsOwned/);
  assert.match(owner, /begin\([\s\S]{0,220}removal-create/);
  assert.match(owner, /refreshRemovalAfterCommand\(owner, session, operationEpoch\)/);
  assert.match(owner, /isOperationsSessionActive\(session\)/);
  assert.match(owner, /removalHistoryState !== 'loading' && removalHistory\.length > 0/);
  assert.match(owner, /removalBusyRef\.current/);
  assert.match(owner, /setRemovalBusy\(false\)/);
  assert.match(queue, /queueGeneration\.current \+= 1/);
  assert.match(queue, /invalidateDecisionOwner\(\)/);
  assert.match(queue, /expectedGeneration = queueGeneration\.current/);
});
