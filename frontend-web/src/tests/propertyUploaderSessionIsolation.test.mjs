import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  captureUploaderSession,
  isUploaderSessionActive,
  isUploaderSessionChangedError
} from '../utils/uploaderSession.ts';

const profile = (id, email) => ({ id, email, role: 'ADMIN', fullName: `Admin ${id}` });

function storageFor(id, token, email) {
  const values = new Map([
    ['pathome_auth_token', token],
    ['pathome_role', 'ADMIN'],
    ['pathome_user', JSON.stringify(profile(id, email))]
  ]);
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
    get length() { return values.size; },
    key: index => [...values.keys()][index] ?? null,
    values
  };
}

function installSession(id, token, email) {
  globalThis.localStorage = storageFor(id, token, email);
}

test('a captured uploader operation becomes inactive after another account signs in', () => {
  installSession(11, 'token-a', 'a@pathome.test');
  const captured = captureUploaderSession();
  assert.ok(captured);
  assert.equal(isUploaderSessionActive(captured), true);

  installSession(22, 'token-b', 'b@pathome.test');
  assert.equal(isUploaderSessionActive(captured), false);
  delete globalThis.localStorage;
});

test('uploader services pin the initiating credential and expose stale-session errors', () => {
  const property = readFileSync(new URL('../services/propertyService.ts', import.meta.url), 'utf8');
  const drafts = readFileSync(new URL('../services/draftService.ts', import.meta.url), 'utf8');
  for (const source of [property, drafts]) {
    assert.match(source, /resolveUploaderSession\(capturedSession\)/);
    assert.match(source, /assertUploaderSessionActive\(session\)/);
    assert.match(source, /Bearer \$\{session\.token\}/);
    assert.match(source, /createApiRequestError\([\s\S]{0,160}session\.token/);
  }
  assert.equal(typeof isUploaderSessionChangedError, 'function');
});

test('uploader cleanup cancels writes and 401/403 are never classified as offline recovery', () => {
  const hook = readFileSync(new URL('../hooks/usePropertyDraft.ts', import.meta.url), 'utf8');
  const bar = readFileSync(new URL('../components/DraftManagementBar.tsx', import.meta.url), 'utf8');
  const single = readFileSync(new URL('../components/MasterAdminDashboard.tsx', import.meta.url), 'utf8');
  const batch = readFileSync(new URL('../components/BatchPropertyIngestionStudio.tsx', import.meta.url), 'utf8');
  const cleanup = hook.match(/return \(\) => \{[\s\S]{0,900}?\n    \};/g)?.find(block =>
    block.includes("window.removeEventListener('beforeunload'"));
  assert.ok(cleanup, 'the uploader cleanup block must remain present');
  assert.equal(cleanup.includes('flushPendingSave()'), false);
  assert.match(cleanup, /pendingPayloadRef\.current = null;/);
  assert.match(hook, /matchesUploaderStoredCredential/);
  assert.match(hook, /withdrawDeniedProtectedDraft\(/);
  assert.match(hook, /localDraftStorage\.removeIf\(email, draftId, \(\) =>/);
  assert.match(hook, /localDraftStorage\.save\(operation\.emailAtDispatch, record, \(\) =>[\s\S]{0,100}operation\.isActive\(\)/);
  assert.match(hook, /activeOperationEpochRef/);
  assert.match(hook, /operationAbortControllerRef/);
  assert.match(hook, /operationId: symbol/);
  assert.match(hook, /extends UploaderOperationLifetime/);
  assert.match(hook, /captureProtectedOperation/);
  assert.match(hook, /operation\.isActive\(\)/);
  assert.match(hook, /operationAbortControllerRef\.current\?\.abort\('permission-denied'\)/);
  assert.match(hook, /const discardDraftRequest = useCallback/);
  assert.match(hook, /method: 'DELETE'[\s\S]{0,160}signal/);
  assert.match(hook, /assertUploaderSessionActive\(session\)/);
  assert.match(hook, /notifySessionExpired\(session\.token\)/);
  assert.match(hook, /withdrawDeniedProtectedDraft\([\s\S]{0,180}'You no longer have permission to discard saved drafts\.'/);
  assert.match(hook, /localDraftStorage\.remove\(email, draftId, \(\) => operation\.isActive\(\)\)/);
  assert.match(hook, /notAttempted/);
  assert.match(single, /singleDraft\.captureProtectedOperation/);
  assert.match(single, /isProtectedOperationActive/);
  assert.match(single, /restoreMediaFiles\(media, \(loaded, total\) =>[\s\S]{0,300}session, operation\)/);
  assert.match(single, /mediaRestorationOwnerRef/);
  assert.match(batch, /batchDraft\.captureProtectedOperation/);
  assert.match(batch, /isProtectedOperationActive/);
  assert.match(batch, /restoreMediaFiles\(serverMedia, undefined, session, operation\)/);

  // A token-bound 401 updates the shared generation before the request rejects. The uploader keeps a
  // receipt for that exact captured generation so current expiry remains recognizable without treating
  // every inactive operation, including cross-tab replacement, as session expiry.
  assert.match(hook, /currentSessionExpirySessionRef/);
  assert.match(hook, /sameOperationsCredential\(currentSessionExpirySessionRef\.current, session\)/);
  assert.match(hook, /if \(error\?\.status === 401\)[\s\S]{0,420}cancelled/);

  // Expiry is completed by the token-bound notification and Home reset. A late result must not finalize
  // replacement UI merely because its outcome is session-expired.
  assert.doesNotMatch(hook, /outcome === 'session-expired'\s*\?\s*true/);

  // Both single and bulk completion handlers check the initiating action and the exact active session
  // generation before any result-driven mutation or finalizer.
  assert.match(bar, /const ownsCurrentDiscardAction = useCallback/);
  assert.match(bar, /action\?\.id === actionId[\s\S]{0,120}isUploaderSessionActive\(action\.session\)/);
  assert.match(bar, /if \(!ownsCurrentDiscardAction\(actionId\)\) return;/);
  assert.match(bar, /if \(!ownsCurrentBulkDiscardAction\(actionId\)\) return;/);
  assert.match(bar, /isUploaderSessionActive/);
});
