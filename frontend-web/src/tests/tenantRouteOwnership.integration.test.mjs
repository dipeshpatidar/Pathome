import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { build } from 'esbuild';

const require = createRequire(import.meta.url);
const source = `
import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { renderToStaticMarkup } from 'react-dom/server';
import { TenantDashboard } from '../components/TenantDashboard';

const user = { id: 41, role: 'TENANT', fullName: 'Tenant Tester', email: null };
const noop = () => {};
export function renderTenant(path) {
  return renderToStaticMarkup(<MemoryRouter initialEntries={[path]}>
    <TenantDashboard user={user} savedCount={null} properties={[]} discoveryState="READY" discoveryLoadedKey={null}
      discoveryCity="" discoveryQuery="" searchFilters={{}} hasMoreProperties={false}
      loadingMoreProperties={false} loadMorePropertiesError={null} onSearchHomes={noop}
      onRetryDiscovery={noop} onLoadMoreProperties={noop} onRequestVisit={noop}
      onViewProperty={noop} hasLessorCapability={false} onOpenLessor={noop} onLogout={noop} />
  </MemoryRouter>);
}
`;

const output = await build({
  stdin: {
    contents: source,
    resolveDir: new URL('.', import.meta.url).pathname,
    sourcefile: 'tenant-route-harness.tsx',
    loader: 'tsx'
  },
  bundle: true,
  platform: 'node',
  format: 'cjs',
  write: false,
  external: ['react', 'react-dom', 'react-dom/server', 'react-router-dom', 'framer-motion', 'lucide-react'],
  plugins: [{
    name: 'closed-location-dialog',
    setup(plugin) {
      plugin.onResolve({ filter: /DiscoveryLocationDialog$/ }, () => ({ path: 'closed-location-dialog', namespace: 'test' }));
      plugin.onLoad({ filter: /.*/, namespace: 'test' }, () => ({
        contents: 'export const DiscoveryLocationDialog = () => null;', loader: 'js'
      }));
    }
  }],
  define: { 'import.meta.env': '{"VITE_API_BASE_URL":"/api/v1","VITE_OAUTH_BASE_URL":""}' }
});
const bundled = { exports: {} };
new Function('require', 'module', 'exports', output.outputFiles[0].text)(require, bundled, bundled.exports);
const { renderTenant } = bundled.exports;

globalThis.localStorage = { getItem: () => null };
globalThis.sessionStorage = { getItem: () => null };

const render = path => {
  const original = console.error;
  console.error = (...args) => {
    if (!String(args[0]).startsWith('Warning: useLayoutEffect does nothing on the server')) original(...args);
  };
  try { return renderTenant(path); }
  finally { console.error = original; }
};

test('Explore, Saved, and My Visits own separate page content and search never leaks into visits', () => {
  const explore = render('/tenant#tenant-home-search');
  assert.match(explore, /Find a place/);
  assert.match(explore, /id="compact-search-input"/);
  assert.doesNotMatch(explore, /id="tenant-visits-page-title"/);

  const saved = render('/tenant#saved-homes-title');
  assert.match(saved, /id="saved-homes-title"/);
  assert.doesNotMatch(saved, /id="compact-search-input"|id="tenant-visits-page-title"/);

  for (const path of ['/tenant#visit-history', '/tenant#visit-session-17']) {
    const visits = render(path);
    assert.match(visits, /id="tenant-visits-page-title"/);
    assert.doesNotMatch(visits, /id="compact-search-input"|id="tenant-home-search"|id="discover-homes"|id="tenant-v0-filter-trigger"/);
  }

  const account = render('/tenant#account');
  assert.match(account, /id="tenant-account-title"/);
  assert.match(account, /Tenant Tester/);
  assert.doesNotMatch(account, /id="compact-search-input"|id="tenant-visits-page-title"|id="discover-homes"/);

  const notifications = render('/tenant#notifications');
  assert.match(notifications, /id="tenant-notifications-title"/);
  assert.match(notifications, /id="tenant-notifications-root"/);
  assert.doesNotMatch(notifications, /id="compact-search-input"|id="tenant-visits-page-title"|id="discover-homes"/);
});
