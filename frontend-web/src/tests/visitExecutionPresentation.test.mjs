import test from 'node:test';
import assert from 'node:assert/strict';
import { boundedVisitPage, operationalVisitTime, visitStartFeedback } from '../utils/visitExecutionPresentation.ts';

test('21 repair cases allow page two and clamp after its last action', () => {
  assert.equal(boundedVisitPage(0, 2), 0);
  assert.equal(boundedVisitPage(1, 2), 1);
  assert.equal(boundedVisitPage(1, 1), 0);
  assert.equal(boundedVisitPage(0, 0), 0);
});

test('alternate assignment and repair never report a started visit', () => {
  assert.match(visitStartFeedback('STARTED', 'STARTED'), /Visit started/);
  assert.doesNotMatch(visitStartFeedback('SCHEDULED', 'ALTERNATE_GE_ASSIGNED'), /Visit started/);
  assert.match(visitStartFeedback('SCHEDULED', 'ALTERNATE_GE_ASSIGNED'), /You did not start it/);
  assert.doesNotMatch(visitStartFeedback('REPAIR_REQUIRED', 'REPAIR_REQUIRED'), /Visit started/);
  assert.doesNotMatch(visitStartFeedback('SCHEDULED', 'START_CODE_ISSUED'), /Visit started/);
  assert.match(visitStartFeedback('SCHEDULED', 'START_CODE_ISSUED'), /visit did not start/i);
});

test('missing appointment timestamps are not replaced with fabricated times', () => {
  assert.equal(operationalVisitTime(null, 'Asia/Kolkata'), 'Time unavailable');
  assert.equal(operationalVisitTime('not-a-timestamp', 'Asia/Kolkata'), 'Time unavailable');
});

test.todo('pass expiry, countdown, and nextRequestAt cooldown use server timestamps without implying START');
test.todo('ARRIVED presentation states a GE report and never implies GPS/live tracking');
test.todo('scheduled confirmation, locality, duration, and proposal wording remain neutral when fields are absent');
test.todo('reschedule accept/reject presentation does not invent a previous time or an unstructured reason');

test('appointment formatting follows visit zone when device zone differs', () => {
  const prior = process.env.TZ;
  process.env.TZ = 'America/Los_Angeles';
  try {
    const label = operationalVisitTime('2026-10-03T06:30:00Z', 'Asia/Kolkata');
    assert.match(label, /12:00/);
    assert.match(label, /Asia\/Kolkata/);
    assert.notEqual(label, operationalVisitTime('2026-10-03T06:30:00Z', 'America/Los_Angeles'));
  } finally {
    if (prior === undefined) delete process.env.TZ;
    else process.env.TZ = prior;
  }
});
