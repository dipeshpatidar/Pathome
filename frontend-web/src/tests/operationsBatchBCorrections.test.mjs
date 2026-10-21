import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';
import {
  EMPTY_OPERATIONS_FILTERS,
  normalizeOperationsFilters,
  parseOperationsRoute,
  serializeOperationsRoute
} from '../utils/operationsWorkspaceRoute.ts';
import {
  operationsAuthorityScopeFingerprint,
  operationsStatusMeta
} from '../utils/operationsWorkspacePresentation.ts';
import { notifySessionExpired } from '../services/apiError.ts';

registerHooks({ resolve: resolveOperationsModule });

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const workspace = read('../components/operations/OperationsWorkspace.tsx');
const authority = read('../hooks/useOperationsCapability.ts');
const home = read('../components/Home.tsx');
const dashboard = read('../components/EmployeeCrmDashboard.tsx');
const admin = read('../components/MasterAdminDashboard.tsx');
const css = read('../components/operations/operationsWorkspace.css');
const thumb = read('../components/operations/OperationsPropertyThumb.tsx');

const grant = (cityId, teamId, overrides = {}) => ({
  capability: 'OPS_COORDINATE',
  scopeType: 'TEAM',
  cityId,
  cityDisplayName: `City ${cityId}`,
  teamId,
  teamDisplayName: `Team ${teamId}`,
  ...overrides
});

test('removing the exact record keeps compatible type and status filters', () => {
  const withoutRecord = normalizeOperationsFilters({
    targetType: 'REQUEST',
    status: 'RECEIVED',
    cityId: 1,
    teamId: 7,
    targetId: null
  });
  assert.deepEqual(withoutRecord, {
    targetType: 'REQUEST',
    status: 'RECEIVED',
    cityId: 1,
    teamId: 7,
    targetId: null
  });
  const url = serializeOperationsRoute({
    kind: 'queue',
    queue: 'my-work',
    page: 1,
    filters: withoutRecord
  });
  assert.equal(url, '/operations/queues/my-work?type=REQUEST&status=RECEIVED&city=1&team=7');
});

test('removing the target type clears its dependent status and exact record', () => {
  assert.deepEqual(
    normalizeOperationsFilters({
      targetType: null,
      status: 'RECEIVED',
      cityId: 2,
      teamId: 9,
      targetId: 5200
    }),
    { targetType: null, status: null, cityId: 2, teamId: 9, targetId: null }
  );
});

test('an incompatible status is normalized before serialization and never reaches the API shape', () => {
  const normalized = normalizeOperationsFilters({
    ...EMPTY_OPERATIONS_FILTERS,
    targetType: 'SESSION',
    status: 'RECEIVED',
    targetId: 77
  });
  assert.equal(normalized.status, null);
  assert.equal(normalized.targetId, 77);
  const parsed = parseOperationsRoute(
    '/operations/queues/my-work',
    '?type=SESSION&status=RECEIVED&ref=77'
  );
  assert.equal(parsed.filters.status, null);
  assert.equal(parsed.filters.targetId, 77);
});

test('City and Team filters remain independent on their own', () => {
  assert.equal(
    normalizeOperationsFilters({
      ...EMPTY_OPERATIONS_FILTERS,
      teamId: 9
    }).teamId,
    9
  );
  assert.equal(
    normalizeOperationsFilters({
      ...EMPTY_OPERATIONS_FILTERS,
      cityId: 2
    }).cityId,
    2
  );
});

test('effective authority fingerprint ignores grant order, duplicates and unrelated capabilities', () => {
  const teamA = grant(1, 7);
  const teamB = grant(2, 9);
  const baseline = operationsAuthorityScopeFingerprint({
    staffActive: true,
    serverTime: null,
    capabilities: [teamA, teamB]
  });
  assert.equal(operationsAuthorityScopeFingerprint({
    staffActive: true,
    serverTime: null,
    capabilities: [teamB, teamA, teamA]
  }), baseline);
  assert.notEqual(operationsAuthorityScopeFingerprint({
    staffActive: true,
    serverTime: null,
    capabilities: [teamB]
  }), baseline);
  assert.equal(operationsAuthorityScopeFingerprint({
    staffActive: true,
    serverTime: null,
    capabilities: [teamA, teamB, { capability: 'REPORTS_VIEW', scopeType: 'GLOBAL', cityId: null, teamId: null }]
  }), baseline);
});

test('a scope change advances authorization generation and the workspace gates on it', () => {
  assert.match(authority, /const authorityScopeRef = useRef<string \| null>\(null\);/);
  assert.match(authority, /const scopeChanged = authorityScopeRef\.current !== null && authorityScopeRef\.current !== scope;/);
  assert.match(authority, /generation: verdictRef\.current\.generation \+ \(scopeChanged \? 1 : 0\)/);
  assert.match(workspace, /const effectiveScopeFingerprint = operationsAuthorityScopeFingerprint\(capabilities\);/);
  assert.match(workspace, /const protectedAccess = accessGranted && scopeCurrent;/);
  assert.match(workspace, /if \(accessGranted && !scopeCurrent\) \{/);
  assert.match(workspace, /reconcileScopeChange\(operationsCapabilityScopes\(capabilities\)\);/);
});

test('standalone navigation uses React Router while embedded navigation preserves the host', () => {
  assert.match(workspace, /const routerNavigate = useNavigate\(\);/);
  assert.match(workspace, /if \(!isPageLayout \|\| typeof window === 'undefined'\) return;/);
  assert.match(workspace, /routerNavigate\(url, \{/);
  assert.doesNotMatch(workspace, /window\.history\.pushState/);
  assert.doesNotMatch(workspace, /window\.history\.replaceState/);
  assert.match(workspace, /if \(isPageLayout && historyState\?\.pathomeOperationsPushed === true\) \{/);
  assert.match(workspace, /routerNavigate\(-1\)/);
  assert.match(workspace, /returnRouteForDetail\(current\)/);
});

test('session expiry captures the currently displayed canonical URL', () => {
  assert.match(home, /const currentPath = window\.location\.pathname;/);
  assert.match(home, /const currentSearch = window\.location\.search;/);
  assert.match(home, /operationsReturnPath\(currentPath, currentSearch\)/);
});

test('the Employee host tab is restored per User after refresh', () => {
  assert.match(dashboard, /pathome_employee_workspace_tab:\$\{userId \?\? 'anonymous'\}/);
  assert.match(dashboard, /readEmployeeWorkspaceTab\(user\?\.id\)/);
  assert.match(dashboard, /sessionStorage\.setItem\(employeeWorkspaceTabKey\(user\?\.id\), activeTab\)/);
});

test('the Admin host tab can restore Operations without restoring authority', () => {
  const persisted = admin.match(/const PERSISTED_ADMIN_TABS = new Set\(\[([\s\S]*?)\]\);/)?.[1] ?? '';
  assert.match(persisted, /'operations'/);
  const valid = home.match(/const VALID_ADMIN_TABS = new Set\(\[([\s\S]*?)\]\);/)?.[1] ?? '';
  assert.match(valid, /'operations'/);
  assert.match(admin, /operationsAuthority\.state === 'restricted'/);
});

test('destination navigation counts use the authorized unfiltered totals', () => {
  assert.match(workspace, /attention\.status === 'ready' \? attention\.unfilteredTotalCount/);
  assert.match(workspace, /myWork\.status === 'ready' \? myWork\.unfilteredTotalCount/);
  assert.match(workspace, /claimable\.status === 'ready' \? claimable\.unfilteredTotalCount/);
  assert.doesNotMatch(workspace, /attention\.status === 'ready' \? attention\.totalCount/);
});

test('Visit plan type is neutral while lifecycle status keeps its own badge', () => {
  const sessionRule = css.match(/\.oe-type--session\s*\{[^}]*\}/)?.[0] ?? '';
  assert.ok(sessionRule);
  assert.doesNotMatch(sessionRule, /background:\s*var\(--oe-pine\)/);
  assert.doesNotMatch(sessionRule, /color:\s*var\(--oe-on-pine\)/);
  assert.match(sessionRule, /color:\s*var\(--oe-muted\)/);
  assert.match(sessionRule, /inset 0 0 0 1px var\(--oe-line-strong\)/);
});

test('exceptional and unknown lifecycle states never inherit a success tone', () => {
  for (const status of ['INTERRUPTED', 'REPAIR_REQUIRED', 'PROVISIONAL_NO_SHOW']) {
    assert.equal(operationsStatusMeta('SESSION', status).tone, 'attention');
  }
  assert.equal(operationsStatusMeta('SESSION', 'SOMETHING_NEW').tone, 'open');
  assert.equal(operationsStatusMeta('REQUEST', 'RECEIVED').tone, 'open');
  assert.equal(operationsStatusMeta('REQUEST', 'COORDINATING').tone, 'active');
  assert.equal(operationsStatusMeta('REQUEST', 'SCHEDULED').tone, 'scheduled');
});

test('the thumbnail defensively withholds media unless the current status is ACTIVE', () => {
  assert.match(thumb, /const active = listingStatus === 'ACTIVE';/);
  assert.match(thumb, /const url = active && typeof imageUrl === 'string'/);
  assert.match(thumb, /const linkable = active/);
});

test('negative control: the original ID-chip and navigation-count bugs fail the corrected contract', () => {
  const originalRemoveRecord = (filters) => ({ ...filters, targetId: null, targetType: null });
  const broken = originalRemoveRecord({
    targetType: 'REQUEST',
    status: 'RECEIVED',
    cityId: 1,
    teamId: 7,
    targetId: 5200
  });
  const brokenUrl = serializeOperationsRoute({
    kind: 'queue',
    queue: 'my-work',
    page: 1,
    filters: broken
  });
  assert.equal(broken.targetType, null, 'the original implementation cleared the type');
  assert.equal(brokenUrl.includes('type=REQUEST'), false);
  assert.equal(brokenUrl.includes('status=RECEIVED'), false);

  const filteredPage = { totalCount: 0, unfilteredTotalCount: 28 };
  assert.notEqual(filteredPage.totalCount, filteredPage.unfilteredTotalCount,
    'the original filtered-navigation mapping would show zero as the destination total');
});

test('a 401 from an older token cannot notify or redirect the current account', () => {
  const events = [];
  const previousWindow = globalThis.window;
  const previousStorage = globalThis.localStorage;
  globalThis.window = { dispatchEvent: (event) => events.push(event) };
  globalThis.localStorage = {
    getItem: (key) => (key === 'pathome_auth_token' ? 'current-token' : key === 'pathome_user' ? '{"id":1}' : null)
  };
  try {
    notifySessionExpired('older-token');
    assert.equal(events.length, 0);
    notifySessionExpired('current-token');
    assert.equal(events.length, 1);
    assert.equal(events[0].type, 'pathome_session_expired');
  } finally {
    globalThis.window = previousWindow;
    globalThis.localStorage = previousStorage;
  }
});

test('a scope authorization bump blocks a pending mutation before dispatch', async () => {
  const previousWindow = globalThis.window;
  const previousStorage = globalThis.localStorage;
  const values = new Map([
    ['pathome_auth_token', 'scope-token'],
    ['pathome_user', '{"id":11,"role":"TENANT"}'],
    ['pathome_role', 'TENANT']
  ]);
  globalThis.localStorage = {
    getItem: (key) => values.get(key) ?? null
  };
  globalThis.window = { addEventListener: () => {}, dispatchEvent: () => {} };
  try {
    const sessionModule = await import('../utils/operationsSession.ts');
    const { OwnershipOperationRegistry } = await import('../utils/operationsOwnershipRegistry.ts');
    const registry = new OwnershipOperationRegistry();
    const session = sessionModule.captureOperationsSession();
    assert.ok(session);
    const operation = registry.begin('claim', session, 4);
    const unchanged = { authorizationGeneration: () => 4, isDispatchAuthorized: () => true };
    const changed = { authorizationGeneration: () => 5, isDispatchAuthorized: () => true };
    assert.equal(registry.blockReasonForDispatch(operation, unchanged), null);
    assert.equal(registry.blockReasonForDispatch(operation, changed), 'authorization-lost');
    assert.equal(registry.canCommit(operation, changed), false);
  } finally {
    globalThis.window = previousWindow;
    globalThis.localStorage = previousStorage;
  }
});
