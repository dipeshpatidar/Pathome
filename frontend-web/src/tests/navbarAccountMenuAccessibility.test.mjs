import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { build } from 'esbuild';

const require = createRequire(import.meta.url);
const navbarSource = readFileSync(new URL('../components/Navbar.tsx', import.meta.url), 'utf8');

const source = `
import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { renderToStaticMarkup } from 'react-dom/server';
import { NotificationProvider } from '../context/NotificationContext';
import { Navbar } from '../components/Navbar';

const noop = () => {};

function Header({ path, user, role }) {
  return (
    <MemoryRouter initialEntries={[path]}>
      <Navbar
        user={user}
        role={role}
        onOpenAuthModal={noop}
        onLogout={noop}
        onOpenPostProperty={noop}
        postPropertyModalOpen={false}
        onClosePostProperty={noop}
        hasLessorCapability={false}
        draftCount={0}
        draftState="ready"
        onOpenDrafts={noop}
        onRetryDrafts={noop}
      />
    </MemoryRouter>
  );
}

export function renderHeader(path, user, role = user?.role || 'GUEST') {
  const report = console.error;
  console.error = (...args) => {
    if (!String(args[0]).startsWith('Warning: useLayoutEffect does nothing on the server')) report(...args);
  };
  try {
    return renderToStaticMarkup(<NotificationProvider><Header path={path} user={user} role={role} /></NotificationProvider>);
  } finally {
    console.error = report;
  }
}
`;

const output = await build({
  stdin: {
    contents: source,
    resolveDir: new URL('.', import.meta.url).pathname,
    sourcefile: 'navbar-account-accessibility-harness.tsx',
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
const { renderHeader } = bundled.exports;

const storage = () => {
  const values = new Map();
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key)
  };
};

globalThis.localStorage = storage();
globalThis.sessionStorage = storage();

const escapeRegExp = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const accountTrigger = (html, label) => {
  const match = html.match(new RegExp(`<button\\b(?=[^>]*\\baria-label="${escapeRegExp(label)}")[^>]*>`));
  assert.ok(match, `Expected an account trigger named "${label}"`);
  return match[0];
};

const user = (id, fullName) => ({
  id,
  role: 'TENANT',
  fullName,
  email: `controlled-${id}@example.test`
});

test('the profile trigger owns a meaningful name and disclosure state in every signed-in header branch', () => {
  assert.doesNotMatch(navbarSource, /aria-label=\{isConsumerHeader\s*\?/);
  assert.doesNotMatch(navbarSource, /aria-expanded=\{isConsumerHeader\s*\?/);
  assert.match(navbarSource, /aria-label=\{accountMenuLabel\}/);
  assert.match(navbarSource, /aria-expanded=\{profileDropdownOpen\}/);
  assert.match(navbarSource, /aria-controls=\{profileDropdownOpen \? profileAccountMenuId : undefined\}/);
  assert.match(navbarSource, /id=\{profileAccountMenuId\}/);
  assert.match(navbarSource, /profileAccountMenuId = .*useId\(\)/);
});

test('Operations, tenant and lessor headers expose the same descriptive account semantics', () => {
  const controlledUser = user(42, 'CONTROLLED User A');
  for (const path of ['/operations', '/tenant', '/lessor']) {
    const trigger = accountTrigger(renderHeader(path, controlledUser), 'Profile menu for CONTROLLED User A');
    assert.match(trigger, /\baria-expanded="false"/);
    assert.doesNotMatch(trigger, /\baria-controls=/);
  }
});

test('missing, empty and whitespace names use the role-neutral account fallback', () => {
  for (const fullName of [undefined, '', '   ']) {
    const html = renderHeader('/operations', user(42, fullName));
    const trigger = accountTrigger(html, 'Account menu');
    assert.match(trigger, /\baria-expanded="false"/);
    assert.doesNotMatch(trigger, /\baria-label="[A-Z]"/);
    assert.doesNotMatch(html, /aria-label="Account menu for undefined"/);
  }
});

test('long and Unicode names remain truthful in the accessible name', () => {
  const longName = 'Ananya Rajyavardhan-Sharma of the Pathome Accessibility Review Account';
  const unicodeName = 'आर्या शर्मा';
  assert.ok(
    accountTrigger(renderHeader('/operations', user(42, longName)), `Profile menu for ${longName}`)
  );
  assert.ok(
    accountTrigger(renderHeader('/operations', user(42, unicodeName)), `Profile menu for ${unicodeName}`)
  );
});

test('account replacement derives a fresh label and guest state has no authenticated menu', () => {
  const accountA = renderHeader('/operations', user(42, 'CONTROLLED User A'));
  const accountB = renderHeader('/operations', user(84, 'CONTROLLED User B'));
  assert.ok(accountTrigger(accountA, 'Profile menu for CONTROLLED User A'));
  assert.ok(accountTrigger(accountB, 'Profile menu for CONTROLLED User B'));
  assert.doesNotMatch(accountB, /Profile menu for CONTROLLED User A/);

  const guest = renderHeader('/operations', null, 'GUEST');
  assert.doesNotMatch(guest, /aria-label="Account menu"|aria-expanded="false" aria-controls="profile-account-menu/);
  assert.match(guest, /aria-label="Sign in or register"/);
});
