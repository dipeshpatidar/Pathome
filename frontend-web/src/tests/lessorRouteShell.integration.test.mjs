import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { build } from 'esbuild';

const require = createRequire(import.meta.url);
const source = `
import React from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { renderToStaticMarkup } from 'react-dom/server';
import { NotificationProvider } from '../context/NotificationContext';
import { Navbar } from '../components/Navbar';
import { LessorWorkspace } from '../components/LessorWorkspace';
import { PathomeRouteShell } from '../components/PathomeRouteShell';
import { GuestDraftWorkspace } from '../components/LessorPortfolio';

function Route({ user, active, draftCount }) {
  const location = useLocation();
  const workspace = location.pathname.startsWith('/lessor')
    ? <LessorWorkspace user={user} savedCount={3} hasLessorCapability={active} draftCount={draftCount}
        draftState="ready" onRetryDrafts={() => {}} onRequestAuth={() => {}} />
    : <main>Landing page</main>;
  return <PathomeRouteShell pathname={location.pathname}
    normal={<><Navbar user={user} role={user?.role || 'GUEST'} hasLessorCapability={active}
      draftCount={draftCount} draftState="ready" onOpenDrafts={() => {}} onRetryDrafts={() => {}}
      onOpenAuthModal={() => {}} onLogout={() => {}} onOpenPostProperty={() => {}}
      postPropertyModalOpen={false} onClosePostProperty={() => {}} />{workspace}</>}
    focused={workspace} />;
}

export function renderRoute(path, user, active, draftCount) {
  const report = console.error;
  console.error = (...args) => {
    if (!String(args[0]).startsWith('Warning: useLayoutEffect does nothing on the server')) report(...args);
  };
  try {
    return renderToStaticMarkup(<NotificationProvider><MemoryRouter initialEntries={[path]}>
      <Route user={user} active={active} draftCount={draftCount} />
    </MemoryRouter></NotificationProvider>);
  } finally {
    console.error = report;
  }
}

export function renderGuestDraft(draft) {
  const report = console.error;
  console.error = (...args) => {
    if (!String(args[0]).startsWith('Warning: useLayoutEffect does nothing on the server')) report(...args);
  };
  try {
    return renderToStaticMarkup(<MemoryRouter><GuestDraftWorkspace draft={draft} loading={false} error=""
      onAdd={() => {}} onOpenDraft={() => {}} onRetry={() => {}} />
    </MemoryRouter>);
  } finally {
    console.error = report;
  }
}
`;

const output = await build({
  stdin: {
    contents: source,
    resolveDir: new URL('.', import.meta.url).pathname,
    sourcefile: 'lessor-route-harness.tsx',
    loader: 'tsx'
  },
  bundle: true,
  platform: 'node',
  format: 'cjs',
  write: false,
  external: ['react', 'react-dom', 'react-dom/server', 'react-router-dom', 'framer-motion', 'lucide-react'],
  define: { 'import.meta.env': '{"VITE_API_BASE_URL":"/api/v1","VITE_OAUTH_BASE_URL":""}' }
});
const bundled = { exports: {} };
new Function('require', 'module', 'exports', output.outputFiles[0].text)(require, bundled, bundled.exports);
const { renderRoute, renderGuestDraft } = bundled.exports;

const storage = () => {
  const map = new Map();
  return {
    getItem: key => map.get(key) ?? null,
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: key => map.delete(key)
  };
};
globalThis.localStorage = storage();
globalThis.sessionStorage = storage();

const tenant = { id: 11, role: 'TENANT', fullName: 'Tenant Tester', email: 'tenant@example.test' };
const lessor = { id: 12, role: 'TENANT', fullName: 'Lessor Tester', email: 'lessor@example.test' };

function assertShell(html, focused) {
  const globalCount = (html.match(/data-pathome-header="global"/g) || []).length;
  const onboardingCount = (html.match(/data-pathome-header="onboarding"/g) || []).length;
  assert.equal(globalCount, focused ? 0 : 1, 'global header count');
  assert.equal(onboardingCount, focused ? 1 : 0, 'onboarding header count');
  assert.equal(globalCount + onboardingCount, 1, 'total shell header count');
}

test('real route surfaces keep exactly one header through ten route cycles', () => {
  const cycles = [
    { path: '/', user: null, active: false, count: 0, focused: false },
    { path: '/lessor/new', user: null, active: false, count: 0, focused: true },
    { path: '/', user: null, active: false, count: 1, focused: false },
    { path: '/lessor?view=drafts', user: null, active: false, count: 1, focused: false },
    { path: '/lessor/drafts/guest-1', user: null, active: false, count: 1, focused: true },
    { path: '/lessor?view=drafts', user: null, active: false, count: 1, focused: false },
    { path: '/lessor', user: lessor, active: true, count: 1, focused: false },
    { path: '/lessor/new', user: lessor, active: true, count: 1, focused: true },
    { path: '/lessor', user: lessor, active: true, count: 1, focused: false },
    { path: '/lessor/drafts/draft-2', user: tenant, active: false, count: 1, focused: true },
    { path: '/lessor?view=drafts', user: tenant, active: false, count: 1, focused: false }
  ];
  for (let iteration = 0; iteration < 10; iteration += 1) {
    for (const state of cycles) {
      assertShell(renderRoute(state.path, state.user, state.active, state.count), state.focused);
    }
  }
});

test('tenant and guest draft routes render the real workspace without editor content or gateway', () => {
  for (const [user, active] of [[tenant, false], [null, false]]) {
    const html = renderRoute('/lessor?view=drafts', user, active, 1);
    assertShell(html, false);
    assert.equal(/Your drafts|Your property drafts/.test(html), true);
    assert.equal(/Continue your property listing|Add more details|Review your property/.test(html), false);
  }
  const tenantHtml = renderRoute('/lessor', tenant, false, 1);
  assert.equal(/List your property/.test(tenantHtml), true);
  assert.equal(/Your listings/.test(tenantHtml), false);
  assert.match(tenantHtml, /Saved homes, 3 properties/);
  assert.match(tenantHtml, /<span>List home<\/span>/);
  const guestHtml = renderRoute('/lessor', null, false, 1);
  assert.equal(/Post Your Property/.test(guestHtml), true);
});

test('guest draft card only exposes a resumable draft and concise summary', () => {
  const draft = {
    draftId: 'guest-1', status: 'DRAFT', version: 1, completionPercent: 57,
    createdAt: '2026-09-29T10:00:00Z', updatedAt: '2026-09-29T10:00:00Z',
    data: { basics: { propertyType: 'FLAT', rentalMode: 'LONG_TERM_RENTAL', bhkCount: '2BHK' },
      pricing: { monthlyRent: 18000, securityDeposit: 36000 },
      location: { city: 'Indore', localityInput: 'Vijay Nagar', canonicalLocalityId: null, address: '', landmark: '' },
      details: null }
  };
  const html = renderGuestDraft(draft);
  assert.equal(/Draft · 57% complete/.test(html), true);
  assert.equal(/2BHK Flat/.test(html), true);
  assert.equal(/Continue draft/.test(html), true);
  assert.equal(/Add more details|Review your property|Available from/.test(html), false);
  const submitted = renderGuestDraft({ ...draft, status: 'SUBMITTED' });
  assert.equal(/Continue draft|57% complete/.test(submitted), false);
});

test('account replacement does not reuse a prior portfolio or draft badge in rendered route', () => {
  const previous = renderRoute('/lessor', lessor, true, 3);
  assert.equal(/Your listings/.test(previous), true);
  assert.equal(/Drafts, 3 resumable drafts/.test(previous), true);
  const next = renderRoute('/lessor', tenant, false, 0);
  assertShell(next, false);
  assert.equal(/List your property/.test(next), true);
  assert.equal(/Your listings|Drafts, 3 resumable drafts/.test(next), false);
});

test('logout route renders one public header and no prior account controls', () => {
  const before = renderRoute('/lessor', lessor, true, 3);
  assert.match(before, /Your listings/);
  const after = renderRoute('/', null, null, 0);
  assertShell(after, false);
  assert.doesNotMatch(after, /Your listings|Log Out Session|Lessor Tester|Drafts, 3 resumable drafts/);
  assert.doesNotMatch(after, /aria-label="Notifications"/);
});
