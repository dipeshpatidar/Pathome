import test from 'node:test';
import assert from 'node:assert/strict';
import { canUseCanonicalTenantVisitCommand } from '../utils/tenantVisitCommand.ts';

test('ordinary Tenant Request uses the version-guarded command only with a live session and valid listing version', () => {
  assert.equal(canUseCanonicalTenantVisitCommand(true, 4), true);
  assert.equal(canUseCanonicalTenantVisitCommand(true, 0), true);
  assert.equal(canUseCanonicalTenantVisitCommand(false, 4), false);
  assert.equal(canUseCanonicalTenantVisitCommand(true, null), false);
  assert.equal(canUseCanonicalTenantVisitCommand(true, undefined), false);
  assert.equal(canUseCanonicalTenantVisitCommand(true, -1), false);
  assert.equal(canUseCanonicalTenantVisitCommand(true, 1.5), false);
  assert.equal(canUseCanonicalTenantVisitCommand(true, Number.MAX_SAFE_INTEGER + 1), false);
});
