import test from 'node:test';
import assert from 'node:assert/strict';
import {
  canSaveSkip,
  canSubmitOutcomeReport,
  outcomeSaveFailureAction,
  outcomeStateLabel,
  outcomeSkipReasons,
  physicalFinishAvailable,
  recordedOutcomeCount,
  skipReasonLabel
} from '../utils/visitOutcomePresentation.ts';

const report = (states, reportState = 'OPEN') => ({
  sessionId: 7, sessionState: 'STARTED', sessionVersion: 3, reportState, reportVersion: 4,
  scopeCapturedAt: null, summary: null,
  items: states.map((outcome, itemId) => ({ itemId, listingId: itemId, position: itemId + 1, title: 'Home',
    address: 'Street', city: 'City', sector: 'Sector', outcome, skipReason: null, privateNote: null,
    recordedAt: null, itemVersion: 0 }))
});

test('Viewed and Not viewed use human labels and the constrained field reason taxonomy', () => {
  assert.equal(outcomeStateLabel('VISITED'), 'Viewed');
  assert.equal(outcomeStateLabel('SKIPPED'), 'Not viewed');
  assert.deepEqual(outcomeSkipReasons.map(reason => reason.value), [
    'PROPERTY_UNAVAILABLE', 'ACCESS_DENIED', 'TENANT_DECLINED', 'TENANT_LEFT_EARLY', 'PROPERTY_MISMATCH', 'OTHER'
  ]);
  assert.equal(skipReasonLabel('TENANT_DECLINED'), 'Tenant chose not to view');
});

test('OTHER needs a short note; ordinary skip reasons do not require typing', () => {
  assert.equal(canSaveSkip('PROPERTY_UNAVAILABLE', ''), true);
  assert.equal(canSaveSkip('OTHER', '  '), false);
  assert.equal(canSaveSkip('OTHER', 'Gate was locked'), true);
});

test('progress and combined submission require every captured property while physical finish remains separate', () => {
  const partial = report(['VISITED', 'UNRECORDED']);
  assert.equal(recordedOutcomeCount(partial), 1);
  assert.equal(canSubmitOutcomeReport(partial), false);
  assert.equal(physicalFinishAvailable(partial.sessionState), true);
  assert.equal(canSubmitOutcomeReport(report(['SKIPPED', 'VISITED'])), true);
  assert.equal(canSubmitOutcomeReport(report(['VISITED'], 'FINALIZED')), false);
  assert.equal(physicalFinishAvailable('COMPLETED'), false);
});

test('stale conflicts refresh, network failures retain retry, and reassignment removes write access', () => {
  assert.equal(outcomeSaveFailureAction(409), 'REFRESH');
  assert.equal(outcomeSaveFailureAction(403), 'ACCESS_LOST');
  assert.equal(outcomeSaveFailureAction(undefined), 'RETRY');
});
