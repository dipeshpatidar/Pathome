import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { build } from 'esbuild';

const require = createRequire(import.meta.url);
const output = await build({
  entryPoints: ['src/services/propertyService.ts'],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  write: false,
  define: { 'import.meta.env': '{"VITE_API_BASE_URL":"/api/v1","VITE_OAUTH_BASE_URL":""}' }
});
const moduleObject = { exports: {} };
new Function('require', 'module', 'exports', output.outputFiles[0].text)(require, moduleObject, moduleObject.exports);
const { propertyService } = moduleObject.exports;

test('visit-request service preserves the backend created-versus-existing acknowledgement', async () => {
  const originalFetch = globalThis.fetch;
  const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const requests = [];
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: { getItem: key => key === 'pathome_auth_token' ? 'tenant-token' : null }
  });
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options });
    const created = requests.length === 1;
    return new Response(JSON.stringify({
      requestId: 91,
      propertyId: 42,
      status: created ? 'RECEIVED' : 'CANCELLED',
      message: created ? 'Received' : 'Existing request',
      receivedAt: '2026-10-04T12:00:00Z'
    }), { status: created ? 201 : 200, headers: { 'Content-Type': 'application/json' } });
  };
  try {
    const payload = { preferredVisitTiming: 'Tomorrow · Morning' };
    const first = await propertyService.createVisitRequest(42, payload);
    const duplicate = await propertyService.createVisitRequest(42, payload);
    assert.equal(first.created, true);
    assert.equal(first.status, 'RECEIVED');
    assert.equal(duplicate.created, false);
    assert.equal(duplicate.status, 'CANCELLED');
    assert.equal(requests.length, 2);
    assert.equal(requests[0].url, '/api/v1/properties/42/visit-requests');
    assert.equal(requests[0].options.headers.Authorization, 'Bearer tenant-token');
  } finally {
    globalThis.fetch = originalFetch;
    if (originalStorage === undefined) delete globalThis.localStorage;
    else Object.defineProperty(globalThis, 'localStorage', originalStorage);
  }
});
