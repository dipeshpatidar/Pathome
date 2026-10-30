import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');

test('OTP confirmation consumes only a fragment and immediately strips it', async () => {
  const source = await read('../components/EnquiryPhoneVerificationPage.tsx');
  assert.match(source, /window\.location\.hash/);
  assert.match(source, /history\.replaceState/);
  assert.doesNotMatch(source, /localStorage\.setItem/);
  assert.doesNotMatch(source, /sessionStorage\.setItem/);
  assert.doesNotMatch(source, /fetch\(/);
});

test('invitation page load never calls an auto-request endpoint', async () => {
  const source = await read('../components/VisitInvitationBanner.tsx');
  assert.match(source, /exchangeVisitToken/);
  assert.match(source, /currentVisitContext/);
  assert.doesNotMatch(source, /property-visit-commands|visit-requests/);
});

test('auth return stores only an exact property id and action, never customer PII', async () => {
  const source = await read('../components/Home.tsx');
  assert.match(source, /pathome_pending_visit_property_id/);
  assert.match(source, /pathome_pending_visit_action/);
  assert.match(source, /REQUEST_VISIT/);
  assert.doesNotMatch(source, /pathome_pending_visit_phone|pathome_pending_visit_name/);
});

test('revealed lessor contact is cleared on identity change and backgrounding', async () => {
  const source = await read('../components/operations/LeadLessorAvailabilityPanel.tsx');
  assert.match(source, /subscribeOperationsSession/);
  assert.match(source, /visibilitychange/);
  assert.match(source, /setContact\(null\)/);
  assert.match(source, /contextEpoch/);
  assert.match(source, /sensitiveController\.current\?\.abort\(\)/);
  assert.match(source, /status === 401 \|\| status === 403 \|\| status === 404/);
  assert.match(source, /current\(epoch, key, session\)/);
  assert.doesNotMatch(source, /localStorage\.setItem|sessionStorage\.setItem/);
});

test('phone proof reloads rejected state and labels only authoritative current proof', async () => {
  const source = await read('../components/operations/LeadPhoneVerificationPanel.tsx');
  assert.match(source, /status === 400 \|\| status === 409 \|\| status === 410 \|\| status === 429/);
  assert.match(source, /await load\(\)/);
  assert.match(source, /currentProofUsable/);
  assert.match(source, /boundContactPhoneRevision/);
  assert.match(source, /verification\.state === 'CANCELLED'/);
  assert.doesNotMatch(source, /Current for this source and phone revision for 24 hours/);
});
