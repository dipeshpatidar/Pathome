import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { normalizeUserRole } from '../utils/authIdentity.ts';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const read = (relative) => readFile(path.join(root, relative), 'utf8');

test('Admin navigation identity is preserved and unknown roles cannot become Admin', () => {
  assert.equal(normalizeUserRole('ROLE_ADMIN'), 'ADMIN');
  assert.equal(normalizeUserRole('ADMIN'), 'ADMIN');
  assert.equal(normalizeUserRole('ROLE_SUPER_ADMIN'), 'SUPER_ADMIN');
  for (const malformed of [null, undefined, '', 'ROLE_', 'ROLE_FAKE_ADMIN']) {
    assert.notEqual(normalizeUserRole(malformed), 'ADMIN');
    assert.notEqual(normalizeUserRole(malformed), 'SUPER_ADMIN');
  }
});

test('Removal admission uses the protected queue and reuses its successful page', async () => {
  const home = await read('frontend-web/src/components/Home.tsx');
  const dashboard = await read('frontend-web/src/components/MasterAdminDashboard.tsx');
  const hook = await read('frontend-web/src/hooks/usePropertyRemovalAuthority.ts');
  const queue = await read('frontend-web/src/components/backoffice/PropertyRemovalQueue.tsx');
  assert.match(home, /usePropertyRemovalAuthority\(user\?\.id \?\? null, role\)/);
  assert.match(home, /canReviewPropertyRemoval=\{removalAuthority\.canReview\}/);
  assert.match(hook, /propertyRemovalService\.adminQueue\(/);
  assert.match(hook, /subscribeOperationsSession/);
  assert.match(hook, /isOperationsSessionActive\(session\)/);
  assert.match(hook, /sessionIdentity: session\.identity/);
  assert.match(dashboard, /initialAdmission=\{removalAuthority\?\.admissionQueue \?\? null\}/);
  assert.match(queue, /initialAdmission\.sessionIdentity === initialSession\.identity/);
});

test('Admin Visit destinations follow the persisted backend Admin role', async () => {
  const home = await read('frontend-web/src/components/Home.tsx');
  assert.match(home, /canReviewVisitRepairs=\{user\?\.role === 'ADMIN'\}/);
  assert.match(home, /canReviewVisitOutcomes=\{user\?\.role === 'ADMIN'\}/);
  assert.doesNotMatch(home, /canReviewVisitRepairs=\{user\?\.role === 'SUPER_ADMIN'\}/);
});
