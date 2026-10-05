import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { build } from 'esbuild';

const require = createRequire(import.meta.url);
const result = await build({
  entryPoints: ['src/services/tenantVisitEntitlementService.ts'],
  bundle: true, platform: 'node', format: 'cjs', write: false,
  define: { 'import.meta.env': '{"VITE_API_BASE_URL":"/api/v1","VITE_OAUTH_BASE_URL":""}' }
});
const bundled = { exports: {} };
new Function('require', 'module', 'exports', result.outputFiles[0].text)(require, bundled, bundled.exports);
const { tenantVisitEntitlementService } = bundled.exports;
const account = readFileSync(new URL('../components/TenantDashboard.tsx', import.meta.url), 'utf8');

test('service reads only the signed-in tenant endpoint and keeps server balances separate', async () => {
  const priorFetch = globalThis.fetch;
  const priorStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', { configurable: true,
    value: { getItem: key => key === 'pathome_auth_token' ? 'tenant-token' : null } });
  const cases = [
    { totalGrantedSessions: 5, remainingSessions: 5, reservedSessions: 0, availableSessions: 5, totalReconciliationRequired: false },
    { totalGrantedSessions: 5, remainingSessions: 5, reservedSessions: 1, availableSessions: 4, totalReconciliationRequired: false },
    { totalGrantedSessions: 5, remainingSessions: 4, reservedSessions: 0, availableSessions: 4, totalReconciliationRequired: false },
    { totalGrantedSessions: 5, remainingSessions: 0, reservedSessions: 0, availableSessions: 0, totalReconciliationRequired: false },
    { totalGrantedSessions: null, remainingSessions: 4, reservedSessions: 1, availableSessions: 3, totalReconciliationRequired: true }
  ];
  let index = 0;
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/v1/tenant/visit-entitlement');
    assert.equal(options.headers.Authorization, 'Bearer tenant-token');
    return new Response(JSON.stringify(cases[index++]), { status: 200 });
  };
  try {
    for (const expected of cases) assert.deepEqual(await tenantVisitEntitlementService.getMine(), expected);
    assert.equal(index, cases.length);
  } finally {
    globalThis.fetch = priorFetch;
    if (priorStorage) Object.defineProperty(globalThis, 'localStorage', priorStorage);
    else delete globalThis.localStorage;
  }
});

test('unavailable and inconsistent responses cannot become a displayed number', async () => {
  const priorFetch = globalThis.fetch;
  const priorStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', { configurable: true,
    value: { getItem: key => key === 'pathome_auth_token' ? 'tenant-token' : null } });
  try {
    globalThis.fetch = async () => new Response('{}', { status: 503 });
    await assert.rejects(tenantVisitEntitlementService.getMine());
    globalThis.fetch = async () => new Response(JSON.stringify({
      totalGrantedSessions: 5, remainingSessions: 4, reservedSessions: 1,
      availableSessions: 4, totalReconciliationRequired: false
    }), { status: 200 });
    await assert.rejects(tenantVisitEntitlementService.getMine());
  } finally {
    globalThis.fetch = priorFetch;
    if (priorStorage) Object.defineProperty(globalThis, 'localStorage', priorStorage);
    else delete globalThis.localStorage;
  }
});

test('Account uses identity-gated server values in the frozen profile shell', () => {
  assert.match(account, /className="tenant-v0-profile-layout"/);
  assert.match(account, /className="tenant-v0-profile-fields"/);
  assert.match(account, /FREE VISIT SESSIONS/);
  assert.match(account, /visibleEntitlement\.value\?\.remainingSessions\} \/ \{visibleEntitlement\.value\?\.totalGrantedSessions\} remaining/);
  assert.match(account, /reservedSessions \?\? 0\) > 0/);
  assert.match(account, /Visit Session balance temporarily unavailable/);
  assert.match(account, /Your Visit Session total is being verified/);
  assert.match(account, /isCurrentTenantVisitSession\(identity\)/);
  assert.match(account, /entitlement\.identityKey === session\.key/);
  assert.doesNotMatch(account, /5 \/ 5 remaining|tenant-draft-continuation-title|getActiveTenantVisitCount/);
});
