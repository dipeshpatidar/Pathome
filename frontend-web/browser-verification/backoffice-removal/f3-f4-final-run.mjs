/**
 * Focused controlled Chromium proof for the final F3/F4 corrections.
 *
 * The production Vite bundle and production React components are used throughout. Every API
 * response is synthetic and in-memory. This proves component behavior only; it is not Spring HTTP,
 * PostgreSQL, authentication, provider, device, or release acceptance evidence.
 */
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  GateContextClosedError,
  GateLifecycle,
  GateTimeoutError,
  RequestContractError,
  assertHeaderContract,
  assertObjectContract,
  assertQueryContract,
  consumeExpectedConsoleError,
  createEvidenceRunId,
  hasDeclaredGate,
  writeReceiptAtomic
} from './runner-integrity.mjs';

const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const RUNNER_SELFTEST = process.env.PATHOME_RUNNER_SELFTEST?.trim() || '';
const RUNNER_FAULT = process.env.PATHOME_RUNNER_FAULT?.trim() || '';
const RUNNER_DISCOVERY_FILTER_FAULT =
  process.env.PATHOME_RUNNER_DISCOVERY_FILTER_FAULT?.trim() || '';
const RUN_ID = createEvidenceRunId();
const DEFAULT_EVIDENCE_ROOT = fileURLToPath(new URL(
  '../../../tools/oe-integration-env/evidence/backoffice-removal-c3-discovery-filter-20261025/',
  import.meta.url));
const EVIDENCE = process.env.PATHOME_RUNNER_EVIDENCE_ROOT?.trim()
  || join(DEFAULT_EVIDENCE_ROOT, RUNNER_SELFTEST ? 'selftest/runs' : 'runs', RUN_ID);
const HARNESS_ENTRY = fileURLToPath(new URL('./lessor-focus-harness.tsx', import.meta.url));
const MISSING_HARNESS_ENTRY = fileURLToPath(new URL('./missing-build-entry.tsx', import.meta.url));
const ORIGIN = 'http://pathome.local';
const GATE_TIMEOUT_MS = Number(process.env.PATHOME_RUNNER_GATE_TIMEOUT_MS || 45_000);
const SUITE_DEADLINE_MS = Number(process.env.PATHOME_RUNNER_SUITE_DEADLINE_MS || 9 * 60 * 1_000);
const MAX_LEDGER_ENTRIES = 50;
const OPERATION_ID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const DISCOVERY_QUERY_KEYS = new Set([
  'page', 'sector', 'city', 'q', 'bhk', 'propertyType', 'furnishing',
  'minRent', 'maxRent', 'rentalOnly'
]);
const FIXTURE_PROBE_PATH = '/runner-fixture-probe.html';
const BLOCKED_EXTERNAL_RESOURCE_ORIGINS = new Set([
  'https://fonts.googleapis.com', 'https://fonts.gstatic.com'
]);
const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon'
};

let checks = 0;
let failures = 0;
const fixtureLedgers = new Set();
const runnerRuntimeErrors = [];
const gateLifecycle = new GateLifecycle({ timeoutMs: GATE_TIMEOUT_MS });
const contextIds = new WeakMap();
const contextMetadata = new WeakMap();
const routeLedgers = new WeakMap();
let contextSequence = 0;
const check = (label, condition, detail = '') => {
  checks += 1;
  if (condition) console.log(`  PASS  ${label}`);
  else {
    failures += 1;
    console.log(`  FAIL  ${label}${detail ? ` (${detail})` : ''}`);
  }
};

const deferred = () => {
  let release;
  let reject;
  const promise = new Promise((resolve, fail) => {
    release = resolve;
    reject = fail;
  });
  return { promise, release, reject };
};

const createFixtureLedger = () => ({
  count: 0,
  entries: [],
  record(kind, detail = {}) {
    this.count += 1;
    if (this.entries.length < MAX_LEDGER_ENTRIES) {
      this.entries.push({ kind, detail, recordedAt: new Date().toISOString() });
    }
  }
});

const fixtureLedgerFor = state => {
  if (!state.fixtureLedger) state.fixtureLedger = createFixtureLedger();
  fixtureLedgers.add(state.fixtureLedger);
  return state.fixtureLedger;
};

const registerContext = (context, scenario) => {
  const contextId = `context-${++contextSequence}`;
  const metadata = { contextId, scenario };
  contextIds.set(context, contextId);
  contextMetadata.set(context, metadata);
  context.once('close', () => gateLifecycle.contextClosed(contextId));
  return metadata;
};

const awaitGate = async (gate, ledger, details) => {
  try {
    await gateLifecycle.wait(gate, details);
  } catch (error) {
    ledger.record(
      error instanceof RequestContractError ? error.kind
        : error instanceof GateTimeoutError ? 'gate-timeout'
          : error instanceof GateContextClosedError ? 'gate-context-closed' : 'gate-failure',
      {
        label: details.label,
        message: error instanceof Error ? error.message : String(error),
        ...(error instanceof RequestContractError ? error.detail : {})
      }
    );
    throw error;
  }
};

const requestContractFailure = (ledger, route, error, detail = {}) => {
  ledger.record(error.kind, {
    method: route.request().method(),
    url: route.request().url(),
    message: error.message,
    ...error.detail,
    ...detail
  });
};

const assertFixtureQuery = (ledger, route, contract, detail = {}) => {
  try {
    assertQueryContract(new URL(route.request().url()), contract);
  } catch (error) {
    if (error instanceof RequestContractError) {
      requestContractFailure(ledger, route, error, detail);
      return error;
    }
    throw error;
  }
  return null;
};

const registerExpectedHttpError = (state, route, status) => {
  state.expectedHttpErrors = state.expectedHttpErrors || [];
  state.expectedHttpErrors.push({
    method: route.request().method(),
    url: route.request().url(),
    status,
    consumed: false
  });
};

const registerExpectedResourceFailure = (state, url) => {
  state.expectedResourceFailures = state.expectedResourceFailures || [];
  state.expectedResourceFailures.push({ url: url.toString(), consumed: false });
};

const json = async (route, body, status = 200, state = null, expectedError = false) => {
  if (state && expectedError && status >= 400) {
    registerExpectedHttpError(state, route, status);
  }
  try {
    if (state?.failNextFulfill) {
      state.failNextFulfill = false;
      throw new Error('Deliberately injected route fulfillment failure.');
    }
    return await route.fulfill({
      status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' },
      body: JSON.stringify(body)
    });
  } catch (error) {
    routeLedgers.get(route)?.record('route-response-failure', {
      method: route.request().method(),
      url: route.request().url(),
      message: error instanceof Error ? error.message : String(error)
    });
    throw error;
  }
};

const routeContext = page => {
  const context = page.context();
  const contextId = contextIds.get(context);
  if (!contextId) throw new Error('Browser context was not registered with the gate lifecycle.');
  return contextMetadata.get(context);
};

const gateDetails = (page, type, label) => ({
  type,
  label,
  ...routeContext(page)
});

const terminalGateFailureCount = () => gateLifecycle.failures().length;

const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const until = async (predicate, label, timeout = 6_000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await wait(20);
  }
  throw new Error(`Timed out waiting for ${label}.`);
};

const API_METHODS = path => {
  if (path === '/api/v1/notifications') return ['GET'];
  if (path === '/api/v1/notifications/read-all'
      || /^\/api\/v1\/notifications\/\d+\/read$/.test(path)) return ['POST'];
  if (path === '/api/v1/staff/me/capabilities'
      || path === '/api/v1/lessor/capability'
      || path === '/api/v1/lessor/drafts'
      || path === '/api/v1/lessor/properties'
      || path === '/api/v1/lessor/properties/drafts'
      || path === '/api/v1/lessor/guest/drafts/resume'
      || path === '/api/v1/favorites'
      || path === '/api/v1/favorites/properties'
      || path === '/api/v1/properties') return ['GET'];
  if (/^\/api\/v1\/properties\/\d+$/.test(path)
      || /^\/api\/v1\/lessor\/properties\/\d+$/.test(path)) return ['GET'];
  if (/^\/api\/v1\/lessor\/properties\/\d+\/removal-requests$/.test(path)) {
    return ['GET', 'POST'];
  }
  if (/^\/api\/v1\/lessor\/properties\/\d+\/removal-requests\/\d+\/withdraw$/.test(path)) {
    return ['POST'];
  }
  return null;
};

const recordUnexpectedRequest = (ledger, route, kind, detail = {}) => {
  const request = route.request();
  ledger.record(kind, {
    method: request.method(),
    url: request.url(),
    ...detail
  });
};

const waitForTokenTransition = async (page, token, ledger, label) => {
  try {
    await page.waitForFunction(
      expected => localStorage.getItem('pathome_auth_token') === expected,
      token,
      { timeout: 1_500 }
    );
  } catch (error) {
    ledger.record('token-transition-timeout', {
      label,
      expectedToken: token,
      message: error instanceof Error ? error.message : String(error)
    });
    throw error;
  }
};

const buildFocusHarness = async (mutateFocusOwnership = false) => {
  const { build } = await import('../../node_modules/esbuild/lib/main.js');
  const result = await build({
    entryPoints: [(RUNNER_FAULT === 'build-failure' || RUNNER_SELFTEST === 'build-failure')
      ? MISSING_HARNESS_ENTRY
      : HARNESS_ENTRY],
    bundle: true,
    format: 'esm',
    write: false,
    outfile: 'focus-harness.js',
    define: { 'process.env.NODE_ENV': '"production"' },
    logLevel: 'warning',
    plugins: mutateFocusOwnership ? [{
      name: 'remove-pagination-focus-ticket',
      setup(build) {
        build.onLoad({ filter: /LessorListingView\.tsx$/ }, async args => {
          const source = await readFile(args.path, 'utf8');
          const changed = source
            .replace(
              'const invalidateRemovalPageFocus = (): void => {\n    removalPageFocusTicket.current = null;',
              'const invalidateRemovalPageFocus = (): void => {\n    // Negative control: retain the unowned ticket.'
            )
            .replace(
              `      if (ticket && ticket.listingId === listingId
          && ticket.listingEpoch === responseEpoch
          && ticket.pageRequestEpoch === pageRequestEpoch
          && ticket.page === page && ticket.page === result.page
          && ticket.action === focusAction
          && isOperationsSessionActive(ticket.session)) {`,
              `      if (ticket && isOperationsSessionActive(ticket.session)) {`
            )
            .replace(
              `          if (removalPageFocusTicket.current !== ticket
              || !owns()
              || !isLatestRequest(ticket.pageRequestEpoch, removalPageEpoch.current)
              || !isOperationsSessionActive(ticket.session)) return;`,
              `          if (removalPageFocusTicket.current !== ticket
              || !isOperationsSessionActive(ticket.session)) return;`
            );
          if (changed === source
              || !changed.includes('Negative control: retain the unowned ticket.')
              || changed.includes('ticket.listingId === listingId')
              || changed.includes('!isLatestRequest(ticket.pageRequestEpoch, removalPageEpoch.current)')) {
            throw new Error('The focus-ticket ownership negative control did not alter the guard.');
          }
          return { contents: changed, loader: 'tsx' };
        });
      }
    }] : []
  });
  return Buffer.from(result.outputFiles[0].text).toString('utf8');
};

const homeProperty = (id, title, listingVersion, monthlyRent, extra = {}) => ({
  id, title, listingType: 'RENT', propertyType: 'FLAT', city: 'Indore',
  sector: 'Vijay Nagar', bhk: '2BHK', monthlyRent, securityDeposit: monthlyRent * 2,
  totalAreaSqFt: 900, coverImageUrl: '/pathome-house.png', images: ['/pathome-house.png'],
  media: [], taggedMedia: [], verified: true, listingVersion, ...extra
});

const ownerPreview = (listingId, title, rent, reviewNote = null) => ({
  listingId, status: 'PUBLISHED', version: 12, openRevisionDraftId: null,
  openRevisionStatus: null, reviewNote,
  preview: {
    title, propertyType: 'FLAT', bhkCount: '2BHK', city: 'Indore', locality: 'Vijay Nagar',
    monthlyRent: rent, securityDeposit: rent * 2, availableFrom: '2026-11-01',
    furnishingStatus: 'SEMI_FURNISHED', totalAreaSqFt: 900, description: `${title} description`,
    amenities: 'Parking', media: [{
      mediaId: `${listingId}-cover`, filename: 'home.jpg', contentType: 'image/jpeg',
      url: '/pathome-house.png', status: 'UPLOADED', cover: true, sortOrder: 0,
      roomTag: 'LIVING_ROOM', caption: 'Living room'
    }], missingAttributes: []
  }
});

const removal = (listingId, requestStatus = 'PENDING', extra = {}) => ({
  requestId: listingId === 42 ? 77 : 88,
  listingId,
  requestStatus,
  requestVersion: requestStatus === 'PENDING' ? 0 : 1,
  currentListingStatus: 'PUBLISHED',
  currentListingVersion: 12,
  requestedAt: '2026-10-24T08:30:00Z',
  terminalAt: requestStatus === 'PENDING' ? null : '2026-10-24T09:00:00Z',
  ownerReason: 'Controlled owner reason',
  decisionReason: null,
  permittedActions: requestStatus === 'PENDING' ? ['WITHDRAW'] : ['REQUEST_AGAIN'],
  ...extra
});

const declaredDiscoveryValues = values => Object.fromEntries(
  Object.entries(values ?? {}).filter(([, value]) => value !== undefined && value !== null)
);

/**
 * Converts the fixture's declared discovery shape into the existing strict query contract.
 * Registered q alternatives own their phase-specific filters; otherwise the fixed filters
 * declared by state.discoveryQuery apply. Only state.discoveryOptionalQuery may omit a key.
 */
const discoveryQueryContract = (state, requestedQuery, expectedPage) => {
  const registeredQuery = requestedQuery
    ? state.discoveryByQuery?.[requestedQuery]
    : null;
  const withoutPage = values => Object.fromEntries(
    Object.entries(values).filter(([key]) => key !== 'page')
  );
  const fixedFilters = withoutPage(declaredDiscoveryValues(state.discoveryQuery));
  const phaseFilters = registeredQuery
    ? { ...withoutPage(declaredDiscoveryValues(registeredQuery.query)), q: requestedQuery }
    : fixedFilters;
  const requiredFilters = Object.keys(phaseFilters);
  const optionalDeclarations = declaredDiscoveryValues(state.discoveryOptionalQuery);
  const optionalFilters = Object.keys(optionalDeclarations)
    .filter(key => !requiredFilters.includes(key));

  return {
    required: ['page', ...requiredFilters],
    optional: optionalFilters,
    exact: { page: expectedPage, ...phaseFilters },
    allowedValues: Object.fromEntries(
      optionalFilters.map(key => [key, optionalDeclarations[key]])
    ),
    nonEmpty: ['q']
  };
};

const installRoutes = async (page, identity, state, options = {}) => {
  if (!contextIds.has(page.context())) {
    registerContext(page.context(), options.scenario || state.scenario || 'unclassified');
  }
  const ledger = fixtureLedgerFor(state);
  state.fixtureLedger = ledger;
  page.on('pageerror', error => {
    ledger.record('page-error', { message: error.message });
  });
  page.on('console', message => {
    if (message.type() !== 'error') return;
    if (consumeExpectedConsoleError(state, message)) return;
    ledger.record('unexpected-console-error', {
      text: message.text(),
      location: message.location()?.url ?? null
    });
  });
  await page.exposeFunction('__pathomeRecordUnhandledRejection', message => {
    ledger.record('unhandled-rejection', { message: String(message) });
  });
  await page.addInitScript(() => {
    window.addEventListener('unhandledrejection', event => {
      const reason = event.reason;
      void window.__pathomeRecordUnhandledRejection(
        reason instanceof Error ? reason.message : String(reason)
      );
    });
  });
  await page.addInitScript(({ user, saved }) => {
    localStorage.setItem('pathome_auth_token', user.token || 'controlled-token-a');
    localStorage.setItem('pathome_user', JSON.stringify(user.profile));
    localStorage.setItem('pathome_role', user.profile.role);
    if (saved) sessionStorage.setItem('pathome_discovery_context', JSON.stringify(saved));
  }, { user: identity, saved: state.savedDiscovery || null });

  await page.route('**/*', async route => {
    routeLedgers.set(route, ledger);
    try {
      const url = new URL(route.request().url());
      if (url.origin !== ORIGIN) {
        if (BLOCKED_EXTERNAL_RESOURCE_ORIGINS.has(url.origin)) {
          registerExpectedResourceFailure(state, url);
        } else {
          recordUnexpectedRequest(ledger, route, 'unexpected-origin', { origin: url.origin });
        }
        return await route.abort('blockedbyclient');
      }
      if (url.pathname === '/focus-harness.html') {
        return await route.fulfill({
          status: 200,
          contentType: 'text/html; charset=utf-8',
          body: '<!doctype html><html><body><div id="root"></div><script type="module" src="/focus-harness.js"></script></body></html>'
        });
      }
      if (url.pathname === '/focus-harness.js' && options.focusHarnessBundle) {
        return await route.fulfill({
          status: 200,
          contentType: 'text/javascript; charset=utf-8',
          body: options.focusHarnessBundle
        });
      }
      if (url.pathname === FIXTURE_PROBE_PATH) {
        return await route.fulfill({
          status: 200,
          contentType: 'text/html; charset=utf-8',
          body: '<!doctype html><html><body><main>Fixture query probe</main></body></html>'
        });
      }
      if (url.pathname.startsWith('/api/')) {
        const path = url.pathname;
        const method = route.request().method();
        const fixedQueryError = () => assertFixtureQuery(
          ledger, route, { optional: [] }, { routeContract: 'no-query' });
      if (path === '/api/v1/notifications' && method === 'GET') {
        const queryError = fixedQueryError();
        if (queryError) return await json(route, { message: 'Unregistered notification query.' }, 400);
        return await json(route, []);
      }
      if (path === '/api/v1/notifications/read-all' && method === 'POST') {
        const queryError = fixedQueryError();
        if (queryError) return await json(route, { message: 'Unregistered notification query.' }, 400);
        return await json(route, { updated: 0 });
      }
      if (/^\/api\/v1\/notifications\/\d+\/read$/.test(path) && method === 'POST') {
        const queryError = fixedQueryError();
        if (queryError) return await json(route, { message: 'Unregistered notification query.' }, 400);
        return await json(route, { updated: 1 });
      }
      if (path === '/api/v1/staff/me/capabilities' && method === 'GET') {
        const queryError = fixedQueryError();
        if (queryError) return await json(route, { message: 'Unregistered capability query.' }, 400);
        return await json(route, { staffActive: false, serverTime: null, capabilities: [] });
      }
      if (path === '/api/v1/lessor/capability' && method === 'GET') {
        const queryError = fixedQueryError();
        if (queryError) return await json(route, { message: 'Unregistered capability query.' }, 400);
        return await json(route, {
          userId: identity.profile.id, enabled: true, hasLessorProfile: true,
          activatedAt: '2026-08-01T00:00:00Z'
        });
      }
      if (path === '/api/v1/lessor/drafts' && method === 'GET') {
        const requestedPage = Number(url.searchParams.get('page'));
        const expectedPage = state.draftPage ?? 0;
        const queryError = assertFixtureQuery(ledger, route, {
          required: ['page'], exact: { page: String(expectedPage) }
        });
        if (queryError) return await json(route, { message: 'Unregistered draft query.' }, 400);
        if (!Number.isSafeInteger(requestedPage) || requestedPage !== expectedPage) {
          recordUnexpectedRequest(ledger, route, 'unexpected-page', {
            requestedPage, fixturePage: expectedPage
          });
          return await json(route, { message: 'Unregistered draft page.' }, 400);
        }
        return await json(route, { items: [], page: requestedPage, hasMore: false });
      }
      if (path === '/api/v1/lessor/properties/drafts' && method === 'GET') {
        const requestedPage = Number(url.searchParams.get('page'));
        const expectedPage = state.draftPage ?? 0;
        const queryError = assertFixtureQuery(ledger, route, {
          required: ['page'], exact: { page: String(expectedPage) }
        });
        if (queryError) return await json(route, { message: 'Unregistered draft query.' }, 400);
        if (!Number.isSafeInteger(requestedPage) || requestedPage !== expectedPage) {
          recordUnexpectedRequest(ledger, route, 'unexpected-page', { requestedPage });
          return await json(route, { message: 'Unregistered draft page.' }, 400);
        }
        return await json(route, {
          items: [], page: requestedPage, hasMore: false, totalCount: 0
        });
      }
      if (path === '/api/v1/lessor/guest/drafts/resume' && method === 'GET') {
        const queryError = fixedQueryError();
        if (queryError) return await json(route, { message: 'Unregistered guest-draft query.' }, 400);
        return await json(
          route, { message: 'No resumable guest draft.' }, 404, state, true
        );
      }
      if (path === '/api/v1/favorites/properties' && method === 'GET') {
        const requestedPage = Number(url.searchParams.get('page'));
        const pageSize = Number(url.searchParams.get('size'));
        const expectedPages = state.favoritePages ?? [0];
        const permittedSizes = state.favoritePageSizes ?? [6, 24];
        const queryError = assertFixtureQuery(ledger, route, {
          required: ['page', 'size'],
          allowedValues: {
            page: expectedPages.map(String),
            size: permittedSizes.map(String)
          }
        });
        if (queryError) return await json(route, { message: 'Unregistered saved-property query.' }, 400);
        if (!Number.isSafeInteger(requestedPage) || !expectedPages.includes(requestedPage)
            || !Number.isSafeInteger(pageSize) || !permittedSizes.includes(pageSize)) {
          recordUnexpectedRequest(ledger, route, 'unexpected-query', {
            requestedPage, pageSize
          });
          return await json(route, { message: 'Unregistered saved-property query.' }, 400);
        }
        return await json(route, {
          properties: [], page: requestedPage, hasMore: false
        });
      }
      if (path === '/api/v1/favorites' && method === 'GET') {
        const queryError = assertFixtureQuery(ledger, route, {
          required: ['propertyIds'], repeatable: ['propertyIds']
        });
        if (queryError) return await json(route, { message: 'Unregistered favorite query.' }, 400);
        const ids = url.searchParams.getAll('propertyIds').map(Number);
        if (ids.some(id => !Number.isSafeInteger(id) || id <= 0)) {
          recordUnexpectedRequest(ledger, route, 'unexpected-query', { propertyIds: ids });
          return await json(route, { message: 'Unregistered favorite identity.' }, 400);
        }
        return await json(route, { propertyIds: [] });
      }
      if (path === '/api/v1/properties' && method === 'GET') {
        const requestedPage = Number(url.searchParams.get('page'));
        const requestedQuery = url.searchParams.get('q') || '';
        const fixturePage = state.discoveryPages?.[requestedPage];
        const queryResponse = state.discoveryByQuery?.[url.searchParams.get('q') || ''];
        const registeredPages = state.discoveryPages
          ? Object.keys(state.discoveryPages).map(Number)
          : [0];
        const expectedPage = Number.isSafeInteger(requestedPage)
          ? String(requestedPage)
          : '';
        // C3_DISCOVERY_CONTRACT_START
        const queryError = assertFixtureQuery(ledger, route, {
          ...discoveryQueryContract(state, requestedQuery, expectedPage)
        });
        // C3_DISCOVERY_CONTRACT_END
        if (queryError) return await json(route, { message: 'Unregistered discovery query.' }, 400);
        if (!Number.isSafeInteger(requestedPage) || requestedPage < 0) {
          recordUnexpectedRequest(ledger, route, 'unexpected-page', { requestedPage });
          return await json(route, { message: 'Unregistered discovery page.' }, 400);
        }
        if (!registeredPages.includes(requestedPage)) {
          recordUnexpectedRequest(ledger, route, 'unexpected-page', {
            requestedPage, registeredPages
          });
          return await json(route, { message: 'Unregistered discovery page.' }, 400);
        }
        if (state.discoveryPages && !fixturePage) {
          recordUnexpectedRequest(ledger, route, 'unexpected-page', { requestedPage });
          return await json(route, { message: 'Unregistered discovery page.' }, 400);
        }
        if (state.discoveryByQuery && requestedQuery
            && !state.discoveryByQuery[requestedQuery]) {
          recordUnexpectedRequest(ledger, route, 'unexpected-query', {
            q: requestedQuery
          });
          return await json(route, { message: 'Unregistered discovery query.' }, 400);
        }
        if (requestedQuery && !state.discoveryByQuery
            && state.discoveryQuery?.q !== requestedQuery) {
          recordUnexpectedRequest(ledger, route, 'unexpected-query', { q: requestedQuery });
          return await json(route, { message: 'Unregistered discovery query.' }, 400);
        }
        state.discoveryQueries = state.discoveryQueries || {};
        state.discoveryQueries[requestedQuery] =
          (state.discoveryQueries[requestedQuery] || 0) + 1;
        state.discoveryCalls = state.discoveryCalls || {};
        state.discoveryCalls[requestedPage] = (state.discoveryCalls[requestedPage] || 0) + 1;
        state.discoveryRequests = state.discoveryRequests || [];
        state.discoveryRequests.push({
          page: requestedPage,
          q: requestedQuery,
          query: Object.fromEntries(url.searchParams)
        });
        if (fixturePage?.throwFixture) {
          throw new Error('Deliberately injected discovery route exception.');
        }
        if (fixturePage?.gateRejection) {
          await awaitGate(
            Promise.reject(new Error(fixturePage.gateRejection)),
            ledger,
            gateDetails(page, 'transport', `discovery-page-${requestedPage}`)
          );
        }
        if (hasDeclaredGate(fixturePage) && !state.bypassGate) {
          await awaitGate(
            fixturePage.gate, ledger, gateDetails(page, 'transport', `discovery-page-${requestedPage}`)
          );
        }
        state.discoveryResponses = state.discoveryResponses || {};
        state.discoveryResponses[requestedPage] = (state.discoveryResponses[requestedPage] || 0) + 1;
        return await json(route, {
          properties: queryResponse?.properties || fixturePage?.properties || state.discovery || [],
          page: requestedPage,
          hasMore: queryResponse
            ? Boolean(queryResponse.hasMore)
            : fixturePage ? Boolean(fixturePage.hasMore) : Boolean(state.hasMore)
        });
      }
      if (/^\/api\/v1\/properties\/\d+$/.test(path) && method === 'GET') {
        const id = Number(path.split('/').pop());
        const queryError = fixedQueryError();
        if (queryError) return await json(route, { message: 'Unregistered property-detail query.' }, 400);
        state.detailCalls[id] = (state.detailCalls[id] || 0) + 1;
        const call = state.detailCalls[id];
        const spec = state.details[id]?.[call - 1];
        if (!spec) {
          recordUnexpectedRequest(ledger, route, 'exhausted-fixture', {
            listingId: id, call
          });
          return await json(route, { message: 'No controlled detail response.' }, 404);
        }
        if (spec.property && spec.property.id !== id) {
          recordUnexpectedRequest(ledger, route, 'incorrect-listing-identity', {
            requestedListingId: id,
            fixtureListingId: spec.property.id
          });
          return await json(route, { message: 'Controlled detail identity mismatch.' }, 500);
        }
        state.activeDetailCalls = (state.activeDetailCalls || 0) + 1;
        state.peakDetailCalls = Math.max(state.peakDetailCalls || 0, state.activeDetailCalls);
        try {
          if (hasDeclaredGate(spec) && !state.bypassGate) {
            await awaitGate(
              spec.gate, ledger, gateDetails(page, 'transport', `detail-${id}-${call}`)
            );
          }
          if (spec.delay) await wait(spec.delay);
          if (spec.token) {
            await waitForTokenTransition(
              page, spec.token, ledger, `detail-${id}-${call}`
            );
          }
          state.detailResponses = state.detailResponses || {};
          state.detailResponses[id] = (state.detailResponses[id] || 0) + 1;
          if (spec.status) {
            return await json(route, { message: 'Controlled detail failure.' }, spec.status, state, true);
          }
          return await json(route, spec.property);
        } finally {
          state.activeDetailCalls -= 1;
        }
      }
      if (path === '/api/v1/lessor/properties' && method === 'GET') {
        const requestedPage = Number(url.searchParams.get('page'));
        const expectedPage = state.portfolioPage ?? 0;
        const queryError = assertFixtureQuery(ledger, route, {
          required: ['page'], exact: { page: String(expectedPage) }
        });
        if (queryError || requestedPage !== expectedPage) {
          if (!queryError) {
            recordUnexpectedRequest(ledger, route, 'unexpected-page', {
              requestedPage, fixturePage: expectedPage
            });
          }
          return await json(route, { message: 'Unregistered portfolio page.' }, 400);
        }
        return await json(route, {
          items: state.listings || [], page: requestedPage, hasMore: false
        });
      }
      if (/^\/api\/v1\/lessor\/properties\/\d+$/.test(path) && method === 'GET') {
        const id = Number(path.split('/').pop());
        const queryError = fixedQueryError();
        if (queryError) return await json(route, { message: 'Unregistered listing-detail query.' }, 400);
        state.detailCalls[id] = (state.detailCalls[id] || 0) + 1;
        const call = state.detailCalls[id];
        const spec = state.details[id]?.[call - 1];
        if (!spec) {
          recordUnexpectedRequest(ledger, route, 'exhausted-fixture', {
            listingId: id, call
          });
          return await json(route, { message: 'No controlled listing response.' }, 404);
        }
        if (spec.detail?.listingId !== undefined && spec.detail.listingId !== id) {
          recordUnexpectedRequest(ledger, route, 'incorrect-listing-identity', {
            requestedListingId: id,
            fixtureListingId: spec.detail.listingId
          });
          return await json(route, { message: 'Controlled listing identity mismatch.' }, 500);
        }
        if (hasDeclaredGate(spec) && !state.bypassGate) {
          await awaitGate(
            spec.gate, ledger, gateDetails(page, 'transport', `lessor-detail-${id}-${call}`)
          );
        }
        if (spec.delay) await wait(spec.delay);
        if (spec.token) {
          await waitForTokenTransition(
            page, spec.token, ledger, `lessor-detail-${id}-${call}`
          );
        }
        if (spec.status) {
          return await json(
            route, { message: 'Controlled listing failure.' }, spec.status, state, true
          );
        }
        return await json(route, spec.detail);
      }
      if (/^\/api\/v1\/lessor\/properties\/\d+\/removal-requests$/.test(path)
          && method === 'GET') {
        const id = Number(path.split('/')[5]);
        const requestedPage = Number(url.searchParams.get('page'));
        state.historyCalls[id] = (state.historyCalls[id] || 0) + 1;
        const call = state.historyCalls[id];
        const spec = state.histories[id]?.[call - 1];
        if (!spec) {
          recordUnexpectedRequest(ledger, route, 'exhausted-fixture', {
            listingId: id, historyCall: call
          });
          return await json(route, { message: 'No controlled history response.' }, 404);
        }
        const expectedPage = spec.page ?? 0;
        const queryError = assertFixtureQuery(ledger, route, {
          required: ['page'], exact: { page: String(expectedPage) }
        });
        if (queryError) return await json(route, { message: 'Controlled history query mismatch.' }, 400);
        if (!Number.isSafeInteger(requestedPage) || requestedPage !== expectedPage) {
          recordUnexpectedRequest(ledger, route, 'unexpected-page', {
            requestedPage,
            fixturePage: expectedPage
          });
          return await json(route, { message: 'Controlled history page mismatch.' }, 400);
        }
        if (hasDeclaredGate(spec) && !state.bypassGate) {
          await awaitGate(spec.gate, ledger, gateDetails(page, 'transport', `history-${id}-${call}`));
        }
        if (spec.delay) await wait(spec.delay);
        state.historyResponses = state.historyResponses || {};
        state.historyResponses[id] = (state.historyResponses[id] || 0) + 1;
        if (spec.status) {
          return await json(
            route, { message: 'Controlled history failure.' }, spec.status, state, true
          );
        }
        return await json(route, {
          items: spec.items, page: expectedPage, hasMore: Boolean(spec.hasMore)
        });
      }
      if (/^\/api\/v1\/lessor\/properties\/\d+\/removal-requests$/.test(path)
          && method === 'POST') {
        const id = Number(path.split('/')[5]);
        const registeredCreateListingIds = state.registeredCreateListingIds
          ?? (state.listings ?? []).map(listing => listing.listingId);
        if (!registeredCreateListingIds.includes(id)) {
          recordUnexpectedRequest(ledger, route, 'incorrect-listing-identity', {
            requestedListingId: id,
            fixtureListingIds: registeredCreateListingIds
          });
          return await json(route, { message: 'Unregistered create listing.' }, 404);
        }
        const queryError = fixedQueryError();
        if (queryError) return await json(route, { message: 'Unregistered create query.' }, 400);
        let body;
        try {
          body = route.request().postDataJSON();
        } catch (error) {
          ledger.record('unexpected-payload', {
            listingId: id, message: error instanceof Error ? error.message : String(error)
          });
          return await json(route, { message: 'Controlled create payload was not JSON.' }, 400);
        }
        const spec = state.createResponse || {
          status: 201, body: removal(id), reusable: true
        };
        try {
          assertObjectContract(body, {
            allowedKeys: ['operationId', 'reason'],
            requiredKeys: ['operationId', 'reason'],
            ...(spec.expectedOperationId ? {
              exact: { operationId: spec.expectedOperationId }
            } : {})
          });
          if (!OPERATION_ID_PATTERN.test(body.operationId)
              || typeof body.reason !== 'string'
              || !body.reason.trim()
              || (spec.expectedReason !== undefined && body.reason !== spec.expectedReason)) {
            throw new RequestContractError(
              'unexpected-payload',
              'Controlled create operation identity or reason did not match its fixture.',
              { listingId: id, body }
            );
          }
          const expectedVersion = spec.expectedListingVersion
            ?? state.details[id]?.[0]?.detail?.version;
          if (expectedVersion !== undefined) {
            assertHeaderContract(route.request().headers(), {
              'If-Match': expectedVersion
            });
          }
        } catch (error) {
          if (!(error instanceof RequestContractError)) throw error;
          requestContractFailure(ledger, route, error, { listingId: id, body });
          return await json(route, { message: 'Controlled create request did not match.' }, 400);
        }
        state.createCalls[id] = (state.createCalls[id] || 0) + 1;
        const maxUses = spec.maxUses ?? (spec.reusable === true ? Number.POSITIVE_INFINITY : 1);
        if (state.createCalls[id] > maxUses) {
          recordUnexpectedRequest(ledger, route, 'exhausted-fixture', {
            listingId: id, createCall: state.createCalls[id], maxUses
          });
          return await json(route, { message: 'Controlled create fixture was exhausted.' }, 409);
        }
        state.createBodies.push(body);
        state.createRequests = state.createRequests || [];
        state.createRequests.push({
          listingId: id,
          method,
          ifMatch: route.request().headers()['if-match'] ?? null,
          body
        });
        if (hasDeclaredGate(spec) && !state.bypassGate) {
          await awaitGate(spec.gate, ledger, gateDetails(page, 'transport', `create-${id}`));
        }
        if (spec.delay) await wait(spec.delay);
        if (spec.status && spec.status >= 400) {
          return await json(
            route, { message: spec.message || 'Controlled create failure.' },
            spec.status, state, true
          );
        }
        return await json(route, { request: spec.body || removal(id), created: true }, 201);
      }
      if (/^\/api\/v1\/lessor\/properties\/\d+\/removal-requests\/\d+\/withdraw$/.test(path)
          && method === 'POST') {
        const id = Number(path.split('/')[5]);
        const requestId = Number(path.split('/').at(-2));
        const queryError = fixedQueryError();
        if (queryError) return await json(route, { message: 'Unregistered withdrawal query.' }, 400);
        let body;
        try {
          body = route.request().postDataJSON();
        } catch (error) {
          ledger.record('unexpected-payload', {
            listingId: id, message: error instanceof Error ? error.message : String(error)
          });
          return await json(route, { message: 'Controlled withdraw payload was not JSON.' }, 400);
        }
        const spec = state.withdrawResponse || {
          status: 200, body: removal(id, 'WITHDRAWN', {
            requestId, permittedActions: ['REQUEST_AGAIN']
          }), reusable: true
        };
        const matchingRequest = Object.values(state.histories ?? {})
          .flatMap(pageHistory => pageHistory)
          .flatMap(pageHistory => pageHistory.items ?? [])
          .find(item => item.listingId === id && item.requestId === requestId);
        if (!matchingRequest) {
          recordUnexpectedRequest(ledger, route, 'incorrect-request-identity', {
            listingId: id,
            requestId
          });
          return await json(route, { message: 'Unregistered withdrawal request.' }, 404);
        }
        try {
          assertObjectContract(body, {
            allowedKeys: ['operationId'],
            requiredKeys: ['operationId'],
            ...(spec.expectedOperationId ? {
              exact: { operationId: spec.expectedOperationId }
            } : {})
          });
          if (!OPERATION_ID_PATTERN.test(body.operationId)) {
            throw new RequestContractError(
              'unexpected-payload',
              'Controlled withdrawal operation identity was invalid.',
              { listingId: id, body }
            );
          }
          const expectedVersion = spec.expectedRequestVersion
            ?? matchingRequest?.requestVersion;
          if (expectedVersion !== undefined) {
            assertHeaderContract(route.request().headers(), {
              'If-Match': expectedVersion
            });
          }
        } catch (error) {
          if (!(error instanceof RequestContractError)) throw error;
          requestContractFailure(ledger, route, error, { listingId: id, requestId, body });
          return await json(route, { message: 'Controlled withdrawal did not match.' }, 400);
        }
        state.withdrawCalls[id] = (state.withdrawCalls[id] || 0) + 1;
        const maxUses = spec.maxUses ?? (spec.reusable === true ? Number.POSITIVE_INFINITY : 1);
        if (state.withdrawCalls[id] > maxUses) {
          recordUnexpectedRequest(ledger, route, 'exhausted-fixture', {
            listingId: id, withdrawCall: state.withdrawCalls[id], maxUses
          });
          return await json(route, { message: 'Controlled withdrawal fixture was exhausted.' }, 409);
        }
        state.withdrawBodies.push(body);
        state.withdrawRequests = state.withdrawRequests || [];
        state.withdrawRequests.push({
          listingId: id,
          requestId,
          method,
          ifMatch: route.request().headers()['if-match'] ?? null,
          body
        });
        if (hasDeclaredGate(spec) && !state.bypassGate) {
          await awaitGate(spec.gate, ledger, gateDetails(page, 'transport', `withdraw-${id}`));
        }
        if (spec.delay) await wait(spec.delay);
        if (spec.status >= 400) {
          return await json(
            route, { message: spec.message || 'Controlled withdrawal failure.' },
            spec.status, state, true
          );
        }
        return await json(route, spec.body);
      }
      const expectedMethods = API_METHODS(path);
      if (expectedMethods) {
        recordUnexpectedRequest(ledger, route, 'unexpected-method', {
          expectedMethods
        });
      } else {
        recordUnexpectedRequest(ledger, route, 'unexpected-path');
      }
      return await json(route, { message: 'Controlled fixture has no route.' }, 404);
      }
      const file = join(DIST, url.pathname === '/' ? '/index.html' : url.pathname);
      try {
        return await route.fulfill({
          status: 200, contentType: CONTENT_TYPES[extname(file)] || 'application/octet-stream',
          body: await readFile(file)
        });
      } catch {
        return await route.fulfill({
          status: 200, contentType: 'text/html; charset=utf-8',
          body: await readFile(join(DIST, 'index.html'))
        });
      }
    } catch (error) {
      ledger.record('route-handler-exception', {
        message: error instanceof Error ? error.message : String(error)
      });
      try {
        return await json(route, {
          message: 'Controlled fixture handler failed.'
        }, 500);
      } catch (fulfillError) {
        ledger.record('route-response-failure', {
          message: fulfillError instanceof Error ? fulfillError.message : String(fulfillError)
        });
      }
    }
  });
};

const guestIdentity = {
  token: 'controlled-home-token',
  profile: {
    id: 1, email: 'guest@pathome.in', fullName: 'Controlled Guest', role: 'GUEST',
    freeVisitsUsed: 0, walletBalance: 0
  }
};
const ownerIdentity = {
  token: 'controlled-token-a',
  profile: {
    id: 5, email: 'owner@pathome.in', fullName: 'Controlled Owner', role: 'TENANT',
    freeVisitsUsed: 0, walletBalance: 0, hasLessorProfile: true
  }
};
const listings = [
  {
    listingId: 42, title: 'Owner Listing A', propertyType: 'FLAT', bhkCount: '2BHK',
    city: 'Indore', locality: 'Vijay Nagar', monthlyRent: 25_000, status: 'PUBLISHED',
    updatedAt: '2026-10-24T08:00:00Z', coverUrl: '/pathome-house.png',
    openRevisionDraftId: null, openRevisionStatus: null
  },
  {
    listingId: 43, title: 'Owner Listing B', propertyType: 'FLAT', bhkCount: '3BHK',
    city: 'Indore', locality: 'Palasia', monthlyRent: 35_000, status: 'PUBLISHED',
    updatedAt: '2026-10-24T08:00:00Z', coverUrl: '/pathome-house.png',
    openRevisionDraftId: null, openRevisionStatus: null
  }
];

const homeState = ({
  savedDiscovery = null, discovery = [], discoveryPages = null, discoveryByQuery = null,
  discoveryQuery = null, discoveryOptionalQuery = null, details = {}, scenario = 'home'
} = {}) => ({
  savedDiscovery, discovery, discoveryPages, discoveryByQuery,
  discoveryQuery: { city: 'Indore', ...(discoveryQuery ?? {}) },
  discoveryOptionalQuery,
  details, detailCalls: {}, hasMore: false, scenario
});
const ownerState = (details, histories = {}, options = {}) => {
  const registeredHistories = Object.fromEntries(Object.keys(details).map(listingId => [
    listingId,
    histories[listingId] ?? [{ items: [], page: 0, hasMore: false }]
  ]));
  return {
    listings,
    details,
    histories: registeredHistories,
    discoveryQuery: { city: 'Indore' },
    detailCalls: {},
    historyCalls: {},
    createCalls: {},
    withdrawCalls: {},
    createBodies: [],
    withdrawBodies: [],
    createResponse: options.createResponse,
    withdrawResponse: options.withdrawResponse
  };
};

const rentFor = async (page, id) =>
  page.locator(`#property-card-${id} .lp-card-rent`).innerText();
const emitHomeChange = (page, id) => page.evaluate(listingId => {
  window.dispatchEvent(new CustomEvent('pathome_property_current_changed', {
    detail: { listingId, reason: 'PUBLISHED', sourceId: 'f3-f4-final-controlled' }
  }));
}, id);
const visible = async (page, selector, timeout = 12_000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await page.locator(selector).first().isVisible().catch(() => false)) return true;
    await wait(80);
  }
  return false;
};

const installParsedBodyGate = async (page, pathFragment, gate) => {
  await page.exposeFunction('__pathomeWaitParsedBodyGate', details =>
    gateLifecycle.wait(gate.promise, details));
  const details = gateDetails(page, 'json', `parsed-body-${pathFragment}`);
  await page.addInitScript(({ fragment, gateDetails }) => {
    const originalJson = Response.prototype.json;
    Response.prototype.json = function (...args) {
      const response = this;
      if (response.url.includes(fragment)
          && !window.__pathomeParsedBodyGateEntered) {
        window.__pathomeParsedBodyGateEntered = true;
        return window.__pathomeWaitParsedBodyGate(gateDetails).then(() =>
          originalJson.apply(response, args));
      }
      return originalJson.apply(response, args);
    };
  }, { fragment: pathFragment, gateDetails: details });
};

const openOwnerPage = async (browser, state) => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(6_000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await installRoutes(page, ownerIdentity, state);
  await page.goto(`${ORIGIN}/lessor/listings/42`, { waitUntil: 'domcontentloaded' });
  await visible(page, 'text=Owner Listing A');
  return { context, page, errors };
};

const openFocusHarness = async (browser, state, focusHarnessBundle) => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(6_000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await installRoutes(page, ownerIdentity, state, { focusHarnessBundle });
  await page.goto(`${ORIGIN}/focus-harness.html`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Owner Listing A', exact: true }).waitFor();
  return { context, page, errors };
};

const returnToPortfolio = async page => {
  const back = page.getByRole('button', { name: /My Properties/ }).first();
  await back.waitFor({ state: 'visible' });
  await back.dispatchEvent('click');
  await page.waitForURL(url => url.pathname === '/lessor', { timeout: 8_000 });
  await visible(page, 'article.lessor-v0-listing-card');
};

const openListing = async (page, title, expectedHeading = title) => {
  const card = page.locator('article.lessor-v0-listing-card').filter({ hasText: title });
  await card.waitFor();
  await card.getByRole('button').click();
  await page.waitForURL(url => /^\/lessor\/listings\/\d+$/.test(url.pathname), { timeout: 8_000 });
  await page.getByRole('heading', { name: expectedHeading, exact: true }).waitFor();
};

const awaitLedgerKind = async (ledger, kind, timeout = 4_000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (ledger.entries.some(entry => entry.kind === kind)) return true;
    await wait(20);
  }
  return false;
};

const runDiscoveryQueryContractMatrix = async browser => {
  const results = [];
  const fixtureState = options => homeState({
    discoveryPages: { 0: { properties: [], hasMore: false } },
    ...options
  });

  const runCase = async ({
    mode, state, path, expectedStatus, expectedKind = null,
    expectedKey = null, expectedPage = 0
  }) => {
    state.scenario = mode;
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    page.setDefaultTimeout(5_000);
    let statusCode = null;
    let thrown = null;
    try {
      await installRoutes(page, guestIdentity, state);
      await page.goto(`${ORIGIN}${FIXTURE_PROBE_PATH}`, { waitUntil: 'domcontentloaded' });
      statusCode = await page.evaluate(async requestPath => {
        const response = await fetch(requestPath);
        await response.text();
        return response.status;
      }, path);
      await wait(30);
      const matchingEntries = state.fixtureLedger.entries.filter(entry =>
        entry.kind === expectedKind
        && (expectedKey === null || entry.detail?.key === expectedKey));
      const acceptedCalls = state.discoveryCalls?.[expectedPage] ?? 0;
      const detected = statusCode === expectedStatus
        && (expectedKind === null
          ? state.fixtureLedger.count === 0 && acceptedCalls === 1
          : matchingEntries.length > 0 && acceptedCalls === 0);
      results.push({
        mode,
        expectedKind,
        expectedKey,
        expectedStatus,
        statusCode,
        acceptedCalls,
        detected,
        matchingEntries
      });
    } catch (error) {
      thrown = error instanceof Error ? error.message : String(error);
      results.push({
        mode,
        expectedKind,
        expectedKey,
        expectedStatus,
        statusCode,
        acceptedCalls: state.discoveryCalls?.[expectedPage] ?? 0,
        detected: false,
        thrown
      });
    } finally {
      await context.close();
    }
  };

  await runCase({
    mode: 'Q1 page-only fixture accepts page-only request',
    state: fixtureState({ discoveryQuery: { city: undefined } }),
    path: '/api/v1/properties?page=0',
    expectedStatus: 200
  });
  await runCase({
    mode: 'Q1 page-only fixture rejects missing page',
    state: fixtureState({ discoveryQuery: { city: undefined } }),
    path: '/api/v1/properties',
    expectedStatus: 400, expectedKind: 'missing-query', expectedKey: 'page'
  });
  await runCase({
    mode: 'Q1 page-only fixture rejects wrong page',
    state: fixtureState({ discoveryQuery: { city: undefined } }),
    path: '/api/v1/properties?page=not-a-page',
    expectedStatus: 400, expectedKind: 'unexpected-query-value', expectedKey: 'page'
  });
  await runCase({
    mode: 'Q1 page-only fixture rejects duplicate page',
    state: fixtureState({ discoveryQuery: { city: undefined } }),
    path: '/api/v1/properties?page=0&page=0',
    expectedStatus: 400, expectedKind: 'duplicate-query', expectedKey: 'page'
  });
  await runCase({
    mode: 'Q1 page-only fixture rejects undeclared city',
    state: fixtureState({ discoveryQuery: { city: undefined } }),
    path: '/api/v1/properties?page=0&city=Indore',
    expectedStatus: 400, expectedKind: 'unexpected-query', expectedKey: 'city'
  });

  await runCase({
    mode: 'Q2 declared string filter accepts exact value',
    state: fixtureState({ discoveryQuery: { city: undefined, maxRent: '30000' } }),
    path: '/api/v1/properties?page=0&maxRent=30000',
    expectedStatus: 200
  });
  await runCase({
    mode: 'Q2 declared numeric filter accepts exact value',
    state: fixtureState({ discoveryQuery: { city: undefined, minRent: 20000 } }),
    path: '/api/v1/properties?page=0&minRent=20000',
    expectedStatus: 200
  });
  await runCase({
    mode: 'Q2 declared boolean filter accepts exact value',
    state: fixtureState({ discoveryQuery: { city: undefined, rentalOnly: true } }),
    path: '/api/v1/properties?page=0&rentalOnly=true',
    expectedStatus: 200
  });
  await runCase({
    mode: 'Q2 declared maxRent rejects omission',
    state: fixtureState({ discoveryQuery: { city: undefined, maxRent: '30000' } }),
    path: '/api/v1/properties?page=0',
    expectedStatus: 400, expectedKind: 'missing-query', expectedKey: 'maxRent'
  });
  await runCase({
    mode: 'Q2 declared maxRent rejects empty value',
    state: fixtureState({ discoveryQuery: { city: undefined, maxRent: '30000' } }),
    path: '/api/v1/properties?page=0&maxRent=',
    expectedStatus: 400, expectedKind: 'unexpected-query-value', expectedKey: 'maxRent'
  });
  await runCase({
    mode: 'Q2 declared maxRent rejects wrong value',
    state: fixtureState({ discoveryQuery: { city: undefined, maxRent: '30000' } }),
    path: '/api/v1/properties?page=0&maxRent=25000',
    expectedStatus: 400, expectedKind: 'unexpected-query-value', expectedKey: 'maxRent'
  });
  await runCase({
    mode: 'Q2 declared maxRent rejects duplicate value',
    state: fixtureState({ discoveryQuery: { city: undefined, maxRent: '30000' } }),
    path: '/api/v1/properties?page=0&maxRent=30000&maxRent=30000',
    expectedStatus: 400, expectedKind: 'duplicate-query', expectedKey: 'maxRent'
  });

  const multipleState = () => fixtureState({
    discoveryQuery: { city: 'Indore', maxRent: '30000' }
  });
  await runCase({
    mode: 'Q3 multiple declared filters accept exact values',
    state: multipleState(),
    path: '/api/v1/properties?page=0&city=Indore&maxRent=30000',
    expectedStatus: 200
  });
  await runCase({
    mode: 'Q3 multiple declared filters reject omitted city',
    state: multipleState(),
    path: '/api/v1/properties?page=0&maxRent=30000',
    expectedStatus: 400, expectedKind: 'missing-query', expectedKey: 'city'
  });
  await runCase({
    mode: 'Q3 multiple declared filters reject omitted maxRent',
    state: multipleState(),
    path: '/api/v1/properties?page=0&city=Indore',
    expectedStatus: 400, expectedKind: 'missing-query', expectedKey: 'maxRent'
  });
  await runCase({
    mode: 'Q3 multiple declared filters reject both omitted',
    state: multipleState(),
    path: '/api/v1/properties?page=0',
    expectedStatus: 400, expectedKind: 'missing-query', expectedKey: 'city'
  });
  await runCase({
    mode: 'Q3 multiple declared filters reject wrong city plus omitted maxRent',
    state: multipleState(),
    path: '/api/v1/properties?page=0&city=Ujjain',
    expectedStatus: 400, expectedKind: 'missing-query', expectedKey: 'maxRent'
  });
  await runCase({
    mode: 'Q3 multiple declared filters reject unsupported extra',
    state: multipleState(),
    path: '/api/v1/properties?page=0&city=Indore&maxRent=30000&sector=Vijay%20Nagar',
    expectedStatus: 400, expectedKind: 'unexpected-query', expectedKey: 'sector'
  });
  await runCase({
    mode: 'Q3 multiple declared filters reject duplicate city',
    state: multipleState(),
    path: '/api/v1/properties?page=0&city=Indore&city=Indore&maxRent=30000',
    expectedStatus: 400, expectedKind: 'duplicate-query', expectedKey: 'city'
  });
  await runCase({
    mode: 'Q3 multiple declared filters accept different parameter order',
    state: multipleState(),
    path: '/api/v1/properties?maxRent=30000&city=Indore&page=0',
    expectedStatus: 200
  });
  await runCase({
    mode: 'Q3 multiple declared filters accept equivalent percent encoding',
    state: multipleState(),
    path: '/api/v1/properties?page=0&maxRent=30000&city=%49ndore',
    expectedStatus: 200
  });

  const optionalState = () => fixtureState({
    discoveryQuery: { city: 'Indore' },
    discoveryOptionalQuery: { rentalOnly: ['true'] }
  });
  await runCase({
    mode: 'Q4 explicitly optional filter may be absent',
    state: optionalState(),
    path: '/api/v1/properties?page=0&city=Indore',
    expectedStatus: 200
  });
  await runCase({
    mode: 'Q4 explicitly optional filter accepts permitted value',
    state: optionalState(),
    path: '/api/v1/properties?page=0&city=Indore&rentalOnly=true',
    expectedStatus: 200
  });
  await runCase({
    mode: 'Q4 explicitly optional filter rejects unsupported value',
    state: optionalState(),
    path: '/api/v1/properties?page=0&city=Indore&rentalOnly=false',
    expectedStatus: 400, expectedKind: 'unexpected-query-value', expectedKey: 'rentalOnly'
  });
  await runCase({
    mode: 'Q4 explicitly optional filter rejects duplicate value',
    state: optionalState(),
    path: '/api/v1/properties?page=0&city=Indore&rentalOnly=true&rentalOnly=true',
    expectedStatus: 400, expectedKind: 'duplicate-query', expectedKey: 'rentalOnly'
  });

  const queryAlternativeState = () => fixtureState({
    discoveryByQuery: {
      alpha: { properties: [], hasMore: false }
    }
  });
  await runCase({
    mode: 'Q6 registered q alternative uses its own phase shape',
    state: queryAlternativeState(),
    path: '/api/v1/properties?page=0&q=alpha',
    expectedStatus: 200
  });
  await runCase({
    mode: 'Q6 unregistered q alternative is rejected',
    state: queryAlternativeState(),
    path: '/api/v1/properties?page=0&q=not-registered',
    expectedStatus: 400, expectedKind: 'unexpected-query', expectedKey: 'q'
  });
  await runCase({
    mode: 'Q6 no-q phase retains declared city filter',
    state: queryAlternativeState(),
    path: '/api/v1/properties?page=0&city=Indore',
    expectedStatus: 200
  });

  {
    const gate = deferred();
    const state = homeState({
      discoveryPages: {
        0: { properties: [], hasMore: false },
        1: { gate: gate.promise, properties: [], hasMore: false }
      },
      discoveryQuery: { city: 'Indore', maxRent: '30000' }
    });
    state.scenario = 'Q7 rejected discovery request has no fixture side effects';
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    let invalidStatus = null;
    let validStatus = null;
    try {
      await installRoutes(page, guestIdentity, state);
      await page.goto(`${ORIGIN}${FIXTURE_PROBE_PATH}`, { waitUntil: 'domcontentloaded' });
      const gateFailuresBefore = terminalGateFailureCount();
      invalidStatus = await page.evaluate(async path => {
        const response = await fetch(path);
        await response.text();
        return response.status;
      }, '/api/v1/properties?page=1&city=Indore');
      await wait(30);
      const rejectedBeforeGate = (state.discoveryCalls?.[1] ?? 0) === 0
        && (state.discoveryResponses?.[1] ?? 0) === 0
        && terminalGateFailureCount() === gateFailuresBefore
        && state.fixtureLedger.entries.some(entry =>
          entry.kind === 'missing-query' && entry.detail?.key === 'maxRent');
      gate.release();
      validStatus = await page.evaluate(async path => {
        const response = await fetch(path);
        await response.text();
        return response.status;
      }, '/api/v1/properties?page=1&city=Indore&maxRent=30000');
      await wait(30);
      results.push({
        mode: 'Q7 rejected discovery request has no fixture side effects',
        expectedKind: 'missing-query',
        detected: rejectedBeforeGate
          && invalidStatus === 400
          && validStatus === 200
          && state.discoveryCalls?.[1] === 1
          && state.discoveryResponses?.[1] === 1,
        invalidStatus,
        validStatus,
        discoveryCalls: state.discoveryCalls,
        discoveryResponses: state.discoveryResponses,
        gateFailuresBefore,
        gateFailuresAfter: terminalGateFailureCount()
      });
    } catch (error) {
      results.push({
        mode: 'Q7 rejected discovery request has no fixture side effects',
        expectedKind: 'missing-query',
        detected: false,
        invalidStatus,
        validStatus,
        thrown: error instanceof Error ? error.message : String(error)
      });
    } finally {
      await context.close();
    }
  }

  return results;
};

const runDiscoveryFilterOmissionProbe = async browser => {
  const state = homeState({
    discoveryPages: {
      0: {
        properties: [homeProperty(90, 'C1 filtered page zero', 2, 25_000)],
        hasMore: true
      },
      1: {
        properties: [homeProperty(91, 'C1 filtered page one', 2, 25_000)],
        hasMore: false
      }
    },
    discoveryQuery: { maxRent: '30000' }
  });
  state.scenario = 'C1 omitted page-one maxRent';
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(6_000);
  try {
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/?maxRent=30000`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-90');
    const pageZeroRequest = state.discoveryRequests?.find(request => request.page === 0) ?? null;
    await page.evaluate(() => {
      const originalFetch = window.fetch.bind(window);
      window.fetch = (input, init) => {
        const rawUrl = typeof input === 'string' ? input : input.url;
        const url = new URL(rawUrl, window.location.origin);
        if (url.pathname === '/api/v1/properties'
            && url.searchParams.get('page') === '1'
            && url.searchParams.has('maxRent')) {
          url.searchParams.delete('maxRent');
          return originalFetch(url.toString(), init);
        }
        return originalFetch(input, init);
      };
    });
    await page.getByRole('button', { name: 'Show more homes' }).click();
    await wait(300);
    receipt.discoveryFilterFault = {
      injectedPage: 1,
      removedParameter: 'maxRent',
      pageZeroRequest,
      acceptedRequests: state.discoveryRequests ?? [],
      rejectedEntries: state.fixtureLedger.entries.filter(entry =>
        entry.kind === 'missing-query')
    };
  } finally {
    await context.close();
  }
};

const runC3FailureInjections = async (browser, selectedMode) => {
  if (selectedMode === 'discovery-query-matrix') {
    return runDiscoveryQueryContractMatrix(browser);
  }
  const results = [];
  const run = async (mode, setup, inject, expectedKind, detect = null) => {
    if (selectedMode && selectedMode !== 'all' && selectedMode !== mode) return;
    const state = setup();
    state.scenario = mode;
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    page.setDefaultTimeout(5_000);
    const injections = [];
    try {
      await installRoutes(page, guestIdentity, state);
      await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
      await inject({ page, state });
      const ledgerDetected = expectedKind
        ? await awaitLedgerKind(state.fixtureLedger, expectedKind)
        : false;
      const detected = ledgerDetected || Boolean(await detect?.({ page, state }));
      injections.push({
        mode,
        expectedKind,
        detected,
        matchingEntries: state.fixtureLedger.entries
          .filter(entry => entry.kind === expectedKind)
      });
    } catch (error) {
      let detectorResult = false;
      if (detect) {
        try {
          detectorResult = await detect({ page, state });
        } catch {
          detectorResult = false;
        }
      }
      injections.push({
        mode,
        expectedKind,
        detected: (state.fixtureLedger?.entries.some(entry => entry.kind === expectedKind) ?? false)
          || Boolean(detectorResult),
        runnerError: error instanceof Error ? error.message : String(error)
      });
    } finally {
      await context.close();
    }
    results.push(...injections);
  };

  await run('unexpected-path', () => homeState(), async ({ page }) => {
    await page.evaluate(() => fetch('/api/v1/independent-unexpected-route'));
  }, 'unexpected-path');

  await run('unexpected-method', () => homeState(), async ({ page }) => {
    await page.evaluate(() => fetch('/api/v1/properties', { method: 'POST' }));
  }, 'unexpected-method');

  await run('unexpected-page', () => homeState({
    discoveryPages: { 0: { properties: [], hasMore: false } }
  }), async ({ page }) => {
    await page.evaluate(() => fetch('/api/v1/properties?page=99&city=Indore'));
  }, 'unexpected-page');

  await run('unexpected-query', () => homeState({
    discoveryByQuery: { alpha: { properties: [], hasMore: false } }
  }), async ({ page }) => {
    await page.evaluate(() => fetch(
      '/api/v1/properties?page=0&city=Indore&q=not-registered'
    ));
  }, 'unexpected-query');

  await run('wrong-detail-query', () => homeState({
    details: { 160: [{ property: homeProperty(160, 'Registered detail', 2, 25_000) }] }
  }), async ({ page }) => {
    await page.evaluate(() => fetch('/api/v1/properties/160?unregistered=1'));
  }, 'unexpected-query');

  await run('missing-query', () => homeState({
    discoveryPages: { 0: { properties: [], hasMore: false } }
  }), async ({ page }) => {
    await page.evaluate(() => fetch('/api/v1/properties?city=Indore'));
  }, 'missing-query');

  await run('duplicate-query', () => homeState({
    discoveryPages: { 0: { properties: [], hasMore: false } }
  }), async ({ page }) => {
    await page.evaluate(() => fetch(
      '/api/v1/properties?page=0&page=1&city=Indore'
    ));
  }, 'duplicate-query');

  await run('wrong-query-value', () => homeState({
    discoveryPages: { 0: { properties: [], hasMore: false } },
    discoveryQuery: { maxRent: '30000' }
  }), async ({ page }) => {
    await page.evaluate(() => fetch(
      '/api/v1/properties?page=0&city=Indore&maxRent=25000'
    ));
  }, 'unexpected-query-value');

  await run('incorrect-listing-identity', () => homeState({
    discovery: [homeProperty(90, 'Route identity probe', 2, 25_000)],
    details: { 90: [{ property: homeProperty(999, 'Wrong identity fixture', 3, 26_000) }] }
  }), async ({ page }) => {
    await visible(page, '#property-card-90');
    await emitHomeChange(page, 90);
  }, 'incorrect-listing-identity');

  await run('fixture-exhaustion', () => homeState({
    discovery: [homeProperty(90, 'Exhaustion probe', 2, 25_000)],
    details: { 90: [] }
  }), async ({ page }) => {
    await visible(page, '#property-card-90');
    await emitHomeChange(page, 90);
  }, 'exhausted-fixture');

  await run('unexpected-payload', () => ownerState({
    42: [{ detail: ownerPreview(42, 'Payload probe', 25_000) }]
  }), async ({ page }) => {
    await page.evaluate(() => fetch('/api/v1/lessor/properties/42/removal-requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}'
    }));
  }, 'unexpected-payload');

  await run('wrong-operation-id', () => ownerState({
    42: [{ detail: ownerPreview(42, 'Operation probe', 25_000) }]
  }), async ({ page }) => {
    await page.evaluate(() => fetch('/api/v1/lessor/properties/42/removal-requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'If-Match': '12' },
      body: JSON.stringify({ operationId: 'not-a-uuid', reason: 'Operation probe' })
    }));
  }, 'unexpected-payload');

  await run('incorrect-create-listing', () => ownerState({
    42: [{ detail: ownerPreview(42, 'Listing identity probe', 25_000) }]
  }), async ({ page }) => {
    await page.evaluate(() => fetch('/api/v1/lessor/properties/999/removal-requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'If-Match': '12' },
      body: JSON.stringify({
        operationId: '1bfbcac9-81cf-4f7b-954f-a4b896fe6ea3',
        reason: 'Listing identity probe'
      })
    }));
  }, 'incorrect-listing-identity');

  await run('incorrect-withdraw-request', () => ownerState({
    42: [{ detail: ownerPreview(42, 'Request identity probe', 25_000) }]
  }, {
    42: [{ items: [removal(42)], page: 0, hasMore: false }]
  }), async ({ page }) => {
    await page.evaluate(() => fetch(
      '/api/v1/lessor/properties/42/removal-requests/999/withdraw',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'If-Match': '0' },
        body: JSON.stringify({
          operationId: '1bfbcac9-81cf-4f7b-954f-a4b896fe6ea3'
        })
      }
    ));
  }, 'incorrect-request-identity');

  await run('exhausted-create-fixture', () => {
    const state = ownerState({
      42: [{ detail: ownerPreview(42, 'Exhausted create probe', 25_000) }]
    });
    state.createResponse = { status: 201, body: removal(42), maxUses: 0 };
    return state;
  }, async ({ page }) => {
    await page.evaluate(() => fetch('/api/v1/lessor/properties/42/removal-requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'If-Match': '12' },
      body: JSON.stringify({
        operationId: '1bfbcac9-81cf-4f7b-954f-a4b896fe6ea3',
        reason: 'Exhausted create probe'
      })
    }));
  }, 'exhausted-fixture');

  await run('unexpected-header', () => {
    const state = ownerState({
      42: [{ detail: ownerPreview(42, 'Header probe', 25_000) }]
    });
    state.createResponse = {
      status: 201,
      body: removal(42),
      expectedListingVersion: 99
    };
    return state;
  }, async ({ page }) => {
    await page.evaluate(() => fetch('/api/v1/lessor/properties/42/removal-requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'If-Match': '12' },
      body: JSON.stringify({
        operationId: '1bfbcac9-81cf-4f7b-954f-a4b896fe6ea3',
        reason: 'Header probe'
      })
    }));
  }, 'unexpected-header');

  await run('malformed-gate', () => homeState({
    discoveryPages: { 0: { gate: {}, properties: [], hasMore: false } }
  }), async () => {}, 'malformed-gate');

  await run('rejected-gate', () => homeState({
    discoveryPages: {
      0: {
        gateRejection: 'Injected rejected fixture gate.',
        properties: [],
        hasMore: false
      }
    }
  }), async () => {}, 'gate-failure');

  await run('gate-timeout', () => {
    const timedGate = deferred();
    if (selectedMode !== 'gate-timeout') {
      const poll = setInterval(() => {
        const pending = gateLifecycle.snapshot().some(gate =>
          gate.type === 'transport' && gate.status === 'pending'
          && gate.scenario === 'gate-timeout');
        if (!pending) return;
        clearInterval(poll);
        timedGate.reject(new GateTimeoutError(
          'Injected equivalent timeout for the consolidated matrix.'
        ));
      }, 10);
    }
    return homeState({
      discoveryPages: {
        0: { gate: timedGate.promise, properties: [], hasMore: false }
      }
    });
  }, async () => {}, 'gate-timeout');

  await run('route-handler-exception', () => homeState({
    discoveryPages: { 0: { throwFixture: true, properties: [], hasMore: false } }
  }), async () => {}, 'route-handler-exception');

  await run('unexpected-origin', () => homeState(), async ({ page }) => {
    await page.evaluate(() => fetch('https://example.invalid/pathome-runner-probe')
      .catch(() => null));
  }, 'unexpected-origin');

  await run('page-exception', () => homeState(), async ({ page }) => {
    await page.evaluate(() => {
      setTimeout(() => { throw new Error('Injected controlled page exception.'); }, 0);
    });
  }, 'page-error');

  await run('unhandled-rejection', () => homeState(), async ({ page }) => {
    await page.evaluate(() => {
      void Promise.reject(new Error('Injected controlled unhandled rejection.'));
    });
  }, 'unhandled-rejection');

  await run('unexpected-console-error', () => homeState(), async ({ page }) => {
    await page.evaluate(() => {
      setTimeout(() => console.error('Injected controlled console error.'), 0);
    });
  }, 'unexpected-console-error');

  await run('console-substring', () => homeState(), async ({ page }) => {
    await page.evaluate(() => {
      setTimeout(() => console.error(
        'Failed to load resource: independent unexpected application failure'
      ), 0);
    });
  }, 'unexpected-console-error');

  const missingTokenState = homeState({
    discovery: [homeProperty(90, 'Token transition probe', 2, 25_000)],
    details: {
      90: [{ token: 'never-written-token', property: homeProperty(90, 'Token probe v3', 3, 26_000) }]
    }
  });
  await run('missing-token-transition', () => missingTokenState, async ({ page }) => {
    await visible(page, '#property-card-90');
    await emitHomeChange(page, 90);
  }, 'token-transition-timeout');

  const unreleasedDiscoveryGate = deferred();
  await run('unreleased-json-gate', () => homeState({
    discovery: [homeProperty(160, 'Parsing timeout probe', 2, 25_000)],
    details: {
      160: [{ property: homeProperty(160, 'Held parse v9', 9, 19_999) }]
    }
  }), async ({ page, state }) => {
    const parseGate = deferred();
    await installParsedBodyGate(page, '/api/v1/properties/160', parseGate);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-160');
    await emitHomeChange(page, 160);
    await until(async () => await page.evaluate(
      () => window.__pathomeParsedBodyGateEntered === true
    ), 'the injected unreleased JSON gate');
  }, null, () => gateLifecycle.snapshot().some(gate =>
    gate.type === 'json' && gate.status === 'pending'
    && gate.scenario === 'unreleased-json-gate'));

  await run('unreleased-gate', () => homeState({
    discoveryPages: {
      0: {
        gate: unreleasedDiscoveryGate.promise,
        properties: [homeProperty(90, 'Unreleased gate probe', 2, 25_000)],
        hasMore: false
      }
    }
  }), async () => {
    await until(() => gateLifecycle.snapshot().some(gate =>
      gate.type === 'transport' && gate.status === 'pending'
      && gate.scenario === 'unreleased-gate'), 'the injected unreleased transport gate');
  }, null, () => gateLifecycle.snapshot().some(gate =>
    gate.type === 'transport' && gate.status === 'pending'
    && gate.scenario === 'unreleased-gate'));

  await run('runner-exception-while-pending', () => homeState({
    discoveryPages: {
      0: {
        gate: deferred().promise,
        properties: [],
        hasMore: false
      }
    }
  }), async () => {
    await until(() => gateLifecycle.snapshot().some(gate =>
      gate.type === 'transport' && gate.status === 'pending'
      && gate.scenario === 'runner-exception-while-pending'), 'the pending gate before runner exception');
    throw new Error('Injected runner exception while a gate was pending.');
  }, null, () => gateLifecycle.snapshot().some(gate =>
    gate.type === 'transport'
    && ['pending', 'context-closed'].includes(gate.status)
    && gate.scenario === 'runner-exception-while-pending'));

  await run('route-response-failure', () => {
    const state = homeState();
    state.failNextFulfill = true;
    return state;
  }, async () => {}, 'route-response-failure');

  if (!selectedMode || selectedMode === 'all' || selectedMode === 'runner-exception') {
    runnerRuntimeErrors.push({
      kind: 'runner-exception',
      message: 'Injected controlled runner exception.'
    });
    results.push({
      mode: 'runner-exception',
      expectedKind: 'runner-exception',
      detected: runnerRuntimeErrors.some(error => error.kind === 'runner-exception')
    });
  }
  return results;
};

const receiptPath = join(EVIDENCE, 'browser', 'receipt.json');
const receipt = {
  kind: 'controlled-production-component-browser',
  runId: RUN_ID,
  runnerMode: RUNNER_SELFTEST || RUNNER_FAULT || 'acceptance',
  status: 'INCOMPLETE',
  gateContract: 'Unified transport and JSON Promise lifecycle with unique waiters and retained close failures.',
  supersedes: 'Supersedes only the failed C3 runner-integrity claims; all historical receipts remain intact.',
  fixturePolicy: 'Strict route/query/body/header contracts; only exact registered error responses may suppress a resource console error.',
  checks: 0,
  failures: 0,
  scenarios: [],
  limitations: [
    'Synthetic in-memory APIs, not genuine Spring HTTP or PostgreSQL.',
    'No genuine authentication, grants, provider, database, device, or release acceptance.',
    'Unrelated activity and UX flows were intentionally not reopened.'
  ],
  phase: 'initialization'
};

const startedAt = new Date();
receipt.startedAt = startedAt.toISOString();
let runnerFailure = null;
let selftestResults = [];
let suiteDeadlineExceeded = false;
let browser = null;
let suiteWatchdog = null;
let focusHarnessBundle = null;
let focusHarnessNegativeBundle = null;
let setupPhase = 'receipt-initialization';

try {
  await writeReceiptAtomic(receiptPath, receipt);

  setupPhase = 'source-fingerprint-and-build';
  const playwrightModule = await import('/opt/homebrew/lib/node_modules/playwright/index.js');
  const { chromium } = playwrightModule.default ?? playwrightModule;
  focusHarnessBundle = await buildFocusHarness();
  focusHarnessNegativeBundle = await buildFocusHarness(true);
  setupPhase = 'browser-launch';
  browser = await chromium.launch({
    channel: 'chromium',
    ...((RUNNER_SELFTEST === 'browser-launch' || RUNNER_FAULT === 'browser-launch') ? {
      executablePath: join(DIST, 'missing-browser-executable')
    } : {})
  });
  setupPhase = 'scenario-execution';
  suiteWatchdog = setTimeout(() => {
    suiteDeadlineExceeded = true;
    runnerRuntimeErrors.push({
      kind: 'suite-deadline',
      message: `Controlled runner exceeded ${SUITE_DEADLINE_MS} ms.`
    });
    void browser?.close();
  }, SUITE_DEADLINE_MS);

try {
  if (RUNNER_SELFTEST === 'suite-deadline') {
    await wait(SUITE_DEADLINE_MS + 100);
  }
  if (RUNNER_SELFTEST === 'suite-exception') {
    throw new Error('Injected in-suite runner exception.');
  }
  if (RUNNER_SELFTEST) {
    selftestResults = await runC3FailureInjections(browser, RUNNER_SELFTEST);
    selftestResults.forEach(result => {
      check(`C3 failure injection detected: ${result.mode}`, result.detected === true,
        JSON.stringify(result));
    });
  } else if (RUNNER_DISCOVERY_FILTER_FAULT === 'omit-page-1') {
    await runDiscoveryFilterOmissionProbe(browser);
  } else {
  {
    const detail = homeProperty(90, 'Restored Listing v2', 2, 25_000);
    const state = homeState({
      savedDiscovery: {
        city: 'Indore', sector: '', searchKey: 'indore||||||||all', page: 0,
        properties: [detail], hasMore: false, scrollY: 0, originPropertyId: 90
      },
      details: { 90: [
        { delay: 120, property: homeProperty(90, 'Stale v1 response', 1, 11_111) },
        { delay: 80, property: homeProperty(90, 'Unversioned overwrite', null, 12_345) }
      ] }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    check('H1 restored Home card renders v2 before refresh', await visible(page, '#property-card-90'));
    await emitHomeChange(page, 90);
    await wait(280);
    check('H1 restore v2 then event v1 retains ₹25,000', await rentFor(page, 90) === '₹25,000/month');
    await emitHomeChange(page, 90);
    await wait(180);
    check('H3 unversioned event cannot replace restored v2', await rentFor(page, 90) === '₹25,000/month');
    check('F3 restore/event journey has no uncaught error', errors.length === 0, errors.join(' | '));
    receipt.scenarios.push('H1 restore->event; H3 restore->unversioned event');
    await context.close();
  }

  {
    const property = homeProperty(91, 'Discovery v2', 2, 25_000);
    const state = homeState({
      discovery: [property],
      details: { 91: [{ delay: 80, property: homeProperty(91, 'Unversioned focus', null, 12_345) }] }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    check('H3 discovery v2 renders', await visible(page, '#property-card-91'));
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await wait(220);
    check('H3 focus revalidation cannot replace versioned discovery fact',
      await rentFor(page, 91) === '₹25,000/month');
    receipt.scenarios.push('H3 discovery->unversioned focus');
    await context.close();
  }

  {
    const state = homeState({
      discoveryPages: {
        0: {
          properties: [homeProperty(99, 'First page v2', 2, 25_000)],
          hasMore: true
        },
        1: {
          properties: [
            homeProperty(100, 'Later page v2', 2, 30_000),
            homeProperty(99, 'Duplicate stale v1', 1, 11_111)
          ],
          hasMore: false
        }
      },
      details: {
        100: [{ delay: 80, property: homeProperty(100, 'Later stale v1', 1, 12_345) }]
      }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-99');
    await page.getByRole('button', { name: 'Show more homes' }).click();
    await visible(page, '#property-card-100');
    check('H2 later page keeps v2 and deduplicates an older repeated identity',
      await page.locator('#property-card-99').count() === 1
      && await rentFor(page, 99) === '₹25,000/month');
    await emitHomeChange(page, 100);
    await wait(180);
    check('H2 later-page v2 is not regressed by a held event v1',
      await rentFor(page, 100) === '₹30,000/month');
    receipt.scenarios.push('H2 later page append and held event v1');
    await context.close();
  }

  {
    const appendGate = deferred();
    let appendReleased = false;
    const state = homeState({
      discoveryPages: {
        0: {
          properties: [
            homeProperty(90, 'Within filter v2', 2, 25_000),
            homeProperty(91, 'Unrelated safe home', 2, 20_000)
          ],
          hasMore: true
        },
        1: {
          gate: appendGate.promise.then(() => { appendReleased = true; }),
          properties: [homeProperty(90, 'Held page v2', 2, 25_000)],
          hasMore: false
        }
      },
      discoveryQuery: { maxRent: '30000' },
      details: {
        90: [
          { property: homeProperty(90, 'Newer v3 outside filter', 3, 40_000) },
          { property: homeProperty(90, 'Genuinely newer matching v4', 4, 28_000) }
        ],
        91: [{ property: homeProperty(91, 'Unrelated safe home', 2, 20_000) }]
      }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/?maxRent=30000`, { waitUntil: 'domcontentloaded' });
    check('C1 page zero renders the in-filter v2 card and an unrelated card',
      await visible(page, '#property-card-90')
      && await page.locator('#property-card-91').count() === 1);

    await page.getByRole('button', { name: 'Show more homes' }).click();
    await until(() => (state.discoveryCalls?.[1] || 0) === 1,
      'the held page-1 request to enter the transport gate');
    check('C1 held page-1 request was dispatched exactly once',
      state.discoveryCalls[1] === 1, JSON.stringify(state.discoveryCalls));
    check('C1 held page response has not completed before the event',
      !appendReleased && (state.discoveryResponses?.[1] || 0) === 0);

    await emitHomeChange(page, 90);
    await until(async () => await page.locator('#property-card-90').count() === 0,
      'the newer v3 fact to remove Listing 90');
    check('C1 newer v3/₹40,000 removes Listing 90 under maxRent=30000',
      await page.locator('#property-card-90').count() === 0
      && await page.locator('#property-card-91').count() === 1,
      JSON.stringify({
        detailCalls: state.detailCalls,
        detailResponses: state.detailResponses,
        cardCount: await page.locator('#property-card-90').count(),
        ledger: state.fixtureLedger?.entries
      }));
    check('C1 page-1 response remains held after v3 became authoritative',
      !appendReleased && (state.discoveryResponses?.[1] || 0) === 0);

    appendGate.release();
    await until(() => (state.discoveryResponses?.[1] || 0) === 1,
      'the released stale page to complete');
    await wait(180);
    check('C1 released stale page v2 cannot reinsert Listing 90',
      await page.locator('#property-card-90').count() === 0);
    check('C1 rejected append preserves the unrelated valid card',
      await page.locator('#property-card-91').count() === 1
      && await rentFor(page, 91) === '₹20,000/month');

    await emitHomeChange(page, 90);
    await until(async () => await page.locator('#property-card-90').count() === 1,
      'the matching v4 fact to return Listing 90');
    check('C1 genuinely newer matching v4 can return at ₹28,000',
      await rentFor(page, 90) === '₹28,000/month');
    check('C1 append/filter journey has no page error', errors.length === 0, errors.join(' | '));
    receipt.scenarios.push('C1 held page v2 vs filtered-out event v3 and later matching v4');
    await context.close();
  }

  {
    const state = homeState({
      discovery: [
        homeProperty(90, 'Initial v2', 2, 25_000),
        homeProperty(91, 'Unrelated safe home', 2, 20_000)
      ],
      discoveryQuery: { maxRent: '30000' },
      details: {
        90: [
          { status: 404 },
          { property: homeProperty(90, 'Newest accepted v4', 4, 28_000) }
        ],
        91: [{ property: homeProperty(91, 'Unrelated safe home', 2, 20_000) }]
      }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/?maxRent=30000`, { waitUntil: 'domcontentloaded' });
    check('C1-R1 initial v2 and unrelated card render under maxRent=30000',
      await visible(page, '#property-card-90')
      && await page.locator('#property-card-91').count() === 1);
    check('C1-R1 initial discovery request keeps its exact page and filter identity',
      state.discoveryRequests?.[0]?.page === 0
      && state.discoveryRequests?.[0]?.query?.maxRent === '30000');

    await page.evaluate(() => {
      window.__schedulerMessages = [];
      const originalPostMessage = MessagePort.prototype.postMessage;
      window.__restoreHomeScheduler = () => {
        MessagePort.prototype.postMessage = originalPostMessage;
        window.__schedulerMessages.splice(0).forEach(([port, args]) => {
          originalPostMessage.apply(port, args);
        });
      };
      MessagePort.prototype.postMessage = function (...args) {
        window.__schedulerMessages.push([this, args]);
      };
    });

    await emitHomeChange(page, 90);
    await until(() => (state.detailResponses?.[90] || 0) === 1,
      'the batched current-property 404');
    await wait(80);
    await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await until(() => (state.detailResponses?.[90] || 0) === 2
      && (state.detailResponses?.[91] || 0) === 1,
    'the batched online revalidation');
    await wait(80);
    check('C1-R1 both availability facts complete before React commits queued work',
      await page.evaluate(() => window.__schedulerMessages.length > 0)
      && (state.detailCalls?.[90] || 0) === 2
      && (state.detailCalls?.[91] || 0) === 1);

    await page.evaluate(() => window.__restoreHomeScheduler());
    await wait(220);
    check('C1-R1 accepted v4 restores Listing 90 exactly once at ₹28,000',
      await page.locator('#property-card-90').count() === 1
      && await rentFor(page, 90) === '₹28,000/month');
    check('C1-R1 batched reconciliation preserves the unrelated card and inserts no extra row',
      await page.locator('#property-card-91').count() === 1
      && await page.locator('.lp-grid > li').count() === 2);
    receipt.scenarios.push('C1-R1 batched 404 -> online matching v4 mounted Home');
    await context.close();
  }

  {
    const eventGate = deferred();
    const state = homeState({
      discovery: [
        homeProperty(90, 'Initial v2', 2, 25_000),
        homeProperty(91, 'Unrelated safe home', 2, 20_000)
      ],
      discoveryQuery: { maxRent: '30000' },
      details: {
        90: [
          { gate: eventGate.promise, status: 404 },
          { property: homeProperty(90, 'Newer v4 before stale 404', 4, 28_000) }
        ],
        91: [{ property: homeProperty(91, 'Unrelated safe home', 2, 20_000) }]
      }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/?maxRent=30000`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-90');
    await page.evaluate(() => {
      window.__schedulerMessages = [];
      const originalPostMessage = MessagePort.prototype.postMessage;
      window.__restoreHomeScheduler = () => {
        MessagePort.prototype.postMessage = originalPostMessage;
        window.__schedulerMessages.splice(0).forEach(([port, args]) => {
          originalPostMessage.apply(port, args);
        });
      };
      MessagePort.prototype.postMessage = function (...args) {
        window.__schedulerMessages.push([this, args]);
      };
    });
    await emitHomeChange(page, 90);
    await until(() => (state.detailCalls?.[90] || 0) === 1,
      'the held event 404 request');
    await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await until(() => (state.detailResponses?.[90] || 0) === 1
      && (state.detailResponses?.[91] || 0) === 1,
    'the newer online v4 and unrelated refresh');
    eventGate.release();
    await wait(100);
    await page.evaluate(() => window.__restoreHomeScheduler());
    await wait(220);
    check('C1-R1 reverse completion order cannot let a stale 404 remove matching v4',
      await page.locator('#property-card-90').count() === 1
      && await rentFor(page, 90) === '₹28,000/month'
      && await page.locator('#property-card-91').count() === 1);
    receipt.scenarios.push('C1-R1 reverse order held 404 -> online v4 -> released stale 404');
    await context.close();
  }

  {
    const gate = deferred();
    const state = homeState({
      discoveryPages: {
        0: { properties: [homeProperty(90, 'Gate bypass page zero', 2, 25_000)], hasMore: true },
        1: {
          gate: gate.promise,
          properties: [homeProperty(90, 'Gate bypass page one', 2, 25_000)],
          hasMore: false
        }
      }
    });
    state.bypassGate = true;
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-90');
    await page.getByRole('button', { name: 'Show more homes' }).click();
    await until(() => (state.discoveryResponses?.[1] || 0) === 1,
      'the deliberately ungated response to complete');
    check('C3/E3 negative control: bypassing the transport gate fails the pre-release hold assertion',
      (state.discoveryCalls?.[1] || 0) === 1
      && (state.discoveryResponses?.[1] || 0) === 1);
    gate.release();
    receipt.scenarios.push('C3/E3 transport-gate bypass negative control');
    await context.close();
  }

  for (const order of ['focus-then-event', 'event-then-focus']) {
    const state = homeState({
      discovery: [homeProperty(92, 'Completion order v2', 2, 25_000)],
      details: { 92: order === 'focus-then-event'
        ? [
            { delay: 260, property: homeProperty(92, 'Focus v3 authoritative', 3, 33_333) },
            { delay: 30, property: homeProperty(92, 'Event v1 stale', 1, 11_111) }
          ]
        : [
            { delay: 260, property: homeProperty(92, 'Event v1 stale', 1, 11_111) },
            { delay: 30, property: homeProperty(92, 'Focus v3 authoritative', 3, 33_333) }
          ] }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-92');
    if (order === 'focus-then-event') {
      await page.evaluate(() => window.dispatchEvent(new Event('focus')));
      await wait(15);
      await emitHomeChange(page, 92);
    } else {
      await emitHomeChange(page, 92);
      await wait(15);
      await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    }
    await wait(360);
    check(`H4 ${order}: newer v3 remains after both completions`,
      await rentFor(page, 92) === '₹33,333/month');
    receipt.scenarios.push(`H4 ${order}`);
    await context.close();
  }

  {
    const eventGate = deferred();
    const state = homeState({
      discovery: [homeProperty(101, 'Timer completion v2', 2, 25_000)],
      details: { 101: [
        { gate: eventGate.promise, property: homeProperty(101, 'Late event v3', 3, 33_333) },
        { property: homeProperty(101, 'Timer v4 authoritative', 4, 44_444) }
      ] }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-101');
    await emitHomeChange(page, 101);
    const timerDeadline = Date.now() + 35_000;
    while ((state.detailCalls[101] || 0) < 2 && Date.now() < timerDeadline) await wait(100);
    check('H4 real 30-second polling path starts while an event response is held',
      (state.detailCalls[101] || 0) >= 2, JSON.stringify(state.detailCalls));
    await wait(120);
    check('H4 event→poll: newer timer v4 supersedes the held event v3',
      await rentFor(page, 101) === '₹44,444/month');
    eventGate.release();
    await wait(160);
    check('H4 event→poll: releasing older event v3 cannot regress v4',
      await rentFor(page, 101) === '₹44,444/month');
    receipt.scenarios.push('H4 actual interval poll -> held event completion');
    await context.close();
  }

  {
    const state = homeState({
      discovery: [
        homeProperty(93, 'Newer archive wins', 2, 25_000),
        homeProperty(94, 'Older archive loses', 2, 25_000),
        homeProperty(95, 'Future publish reappears', 2, 25_000)
      ],
      details: {
        93: [
          { delay: 260, property: homeProperty(93, 'Older valid v3', 3, 33_333) },
          { delay: 25, status: 404 }
        ],
        94: [
          { delay: 260, status: 404 },
          { delay: 25, property: homeProperty(94, 'Newer valid v4', 4, 44_444) }
        ],
        95: [
          { delay: 25, status: 404 },
          { delay: 80, property: homeProperty(95, 'Republished v5', 5, 55_555) }
        ]
      }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-93');
    await emitHomeChange(page, 93);
    await emitHomeChange(page, 93);
    await emitHomeChange(page, 94);
    await emitHomeChange(page, 94);
    await emitHomeChange(page, 95);
    await wait(120);
    await emitHomeChange(page, 95);
    await wait(360);
    check('H5 newer archive supersedes an older 200', await page.locator('#property-card-93').count() === 0);
    check('H5 stale 404 cannot erase a newer valid 200',
      await page.locator('#property-card-94').count() === 1
      && await rentFor(page, 94) === '₹44,444/month');
    check('H5 a later versioned publish can reappear after an archive',
      await page.locator('#property-card-95').count() === 1
      && await rentFor(page, 95) === '₹55,555/month');
    receipt.scenarios.push('H5 200/404 order, stale removal, valid reappearance');
    await context.close();
  }

  {
    const gate = deferred();
    const state = homeState({
      discovery: [homeProperty(96, 'Session fenced v2', 2, 25_000)],
      details: { 96: [
        { gate: gate.promise, property: homeProperty(96, 'Old-session overwrite', null, 12_345) }
      ] }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-96');
    await emitHomeChange(page, 96);
    await page.evaluate(() => {
      localStorage.setItem('pathome_auth_token', 'rotated-home-token');
      window.dispatchEvent(new Event('pathome_auth_changed'));
    });
    gate.release();
    await wait(200);
    check('H6 same-account token rotation fences a queued Home commit',
      await page.locator('#property-card-96').count() === 0
      || await rentFor(page, 96) !== '₹12,345/month');
    receipt.scenarios.push('H6 queued Home response after token rotation');
    await context.close();
  }

  {
    const parseGate = deferred();
    const state = homeState({
      discovery: [homeProperty(160, 'Parsing fence v2', 2, 25_000)],
      details: {
        160: [{
          property: homeProperty(160, 'Higher-version obsolete parse v9', 9, 19_999)
        }]
      }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await installRoutes(page, guestIdentity, state);
    await installParsedBodyGate(page, '/api/v1/properties/160', parseGate);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-160');
    await emitHomeChange(page, 160);
    await until(async () => await page.evaluate(() => window.__pathomeParsedBodyGateEntered === true),
      'the response body parser to enter its independent gate');
    check('C3/E2 response transport completed while JSON parsing remains held',
      (state.detailResponses?.[160] || 0) === 1
      && await rentFor(page, 160) === '₹25,000/month');

    await page.evaluate(() => {
      localStorage.setItem('pathome_auth_token', 'parsing-rotation-token');
      window.dispatchEvent(new Event('pathome_auth_changed'));
    });
    parseGate.release();
    await wait(220);
    check('C3/E2 higher-version response parsed after token rotation is fenced',
      await rentFor(page, 160).catch(() => null) !== '₹19,999/month');
    check('C3/E2 parsed-body boundary has no page error', errors.length === 0, errors.join(' | '));
    receipt.scenarios.push('C3/E2 independently held response.json boundary with higher-version stale payload');
    await context.close();
  }

  {
    const gate = deferred();
    const alpha = homeProperty(150, 'Filter alpha v2', 2, 25_000);
    const state = homeState({
      discoveryByQuery: {
        alpha: { properties: [alpha], hasMore: false },
        beta: { properties: [homeProperty(151, 'Filter B v2', 2, 35_000)], hasMore: false }
      },
      details: { 150: [
        { gate: gate.promise, property: homeProperty(150, 'Old filter A v1', 1, 11_111) }
      ] }
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/?q=alpha`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-150');
    await emitHomeChange(page, 150);
    await page.evaluate(() => {
      history.pushState({}, '', '/?q=beta');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await visible(page, '#property-card-151');
    await page.evaluate(() => history.back());
    await until(() => page.url().includes('q=alpha'), 'the router to return to filter A');
    await wait(1_200);
    const returnedToA = await page.locator('#property-card-150').isVisible().catch(() => false);
    check('H6 filter A discovery renders after the replacement reload', returnedToA,
      JSON.stringify({ url: page.url(), calls: state.discoveryQueries || {} }));
    gate.release();
    await until(() => (state.detailResponses?.[150] || 0) === 1,
      'the held filter-A event response to complete after release');
    await wait(180);
    check('H6 filter A→B→A fences queued work from the old filter',
      await page.locator('#property-card-150').count() === 1
      && await rentFor(page, 150) === '₹25,000/month'
      && await page.locator('#property-card-151').count() === 0);
    receipt.scenarios.push('H6 filter A→B→A queued event fence');
    await context.close();
  }

  {
    const properties = Array.from({ length: 23 }, (_, index) =>
      homeProperty(110 + index, `Bounded card ${index + 1}`, 2, 25_000 + index));
    const state = homeState({
      discovery: properties,
      details: Object.fromEntries(properties.map(property => [
        property.id, [{ delay: 120, property }]
      ]))
    });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-110');
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await wait(820);
    check('H7 mounted 23-card revalidation never exceeds six workers',
      (state.peakDetailCalls || 0) === 6, `peak=${state.peakDetailCalls || 0}`);
    check('H7 all 23 loaded identities are revalidated without list loss',
      await page.locator('.lp-grid #property-card-110, .lp-grid #property-card-132').count() === 2
      && await page.locator('.lp-grid > li').count() === 23);
    receipt.scenarios.push('H7 mounted 23-card six-worker bound');
    await context.close();
  }

  {
    const detailGate = deferred();
    const state = ownerState({
      42: [
        { detail: ownerPreview(42, 'Owner Listing A', 25_000) },
        { gate: detailGate.promise, detail: ownerPreview(42, 'Owner Listing A refreshed', 25_000) },
        { detail: ownerPreview(42, 'Owner Listing A reopened', 25_000) }
      ],
      43: [{ detail: ownerPreview(43, 'Owner Listing B', 35_000) }]
    });
    const { context, page, errors } = await openOwnerPage(browser, state);
    await page.getByRole('button', { name: /Request property removal/ }).click();
    await page.locator('#lessor-removal-reason').fill('Controlled nested create');
    await page.getByRole('button', { name: /Send removal request/ }).click();
    await until(() => (state.detailCalls[42] || 0) >= 2, 'A detail refresh to enter its gate');
    const createRequest = state.createRequests?.[0];
    check('C3.1 registered create keeps its exact Listing, method, version, UUID and reason',
      createRequest?.listingId === 42
      && createRequest?.method === 'POST'
      && createRequest?.ifMatch === '12'
      && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
        .test(createRequest?.body?.operationId ?? '')
      && createRequest?.body?.reason === 'Controlled nested create');
    await returnToPortfolio(page);
    await openListing(page, 'Owner Listing B');
    await visible(page, 'text=Owner Listing B');
    await until(() => (state.historyCalls[43] || 0) >= 1, 'B removal history to load');
    const historyBefore = state.historyCalls[42] || 0;
    detailGate.release();
    await wait(260);
    check('O2 create A→B: no obsolete A history dispatch',
      (state.historyCalls[42] || 0) === historyBefore, JSON.stringify(state.historyCalls));
    check('O2 create A→B: B remains ready and actionable',
      await page.getByText('Checking removal status…').count() === 0
      && await page.getByRole('button', { name: /Request property removal/ }).count() === 1);
    check('O2 create A→B: no A private state leaks into B',
      await page.getByText('Owner Listing A refreshed').count() === 0);
    check('O2 create A→B: no uncaught error', errors.length === 0, errors.join(' | '));
    receipt.scenarios.push('O2/O3 create success -> held detail -> A→B');
    await context.close();
  }

  {
    const detailGate = deferred();
    const state = ownerState({
      42: [
        { detail: ownerPreview(42, 'Owner Listing A', 25_000) },
        { gate: detailGate.promise, detail: ownerPreview(42, 'Owner Listing A reopened', 25_000) },
        { detail: ownerPreview(42, 'Owner Listing A again', 25_000) }
      ],
      43: [{ detail: ownerPreview(43, 'Owner Listing B', 35_000) }]
    }, {
      42: [
        { items: [], page: 0, hasMore: false },
        { items: [], page: 0, hasMore: false }
      ],
      43: [{ items: [], page: 0, hasMore: false }]
    });
    const { context, page } = await openOwnerPage(browser, state);
    await page.getByRole('button', { name: /Request property removal/ }).click();
    await page.locator('#lessor-removal-reason').fill('Controlled A-B-A');
    await page.getByRole('button', { name: /Send removal request/ }).click();
    await until(() => (state.detailCalls[42] || 0) >= 2, 'A detail refresh to enter its gate');
    await returnToPortfolio(page);
    await openListing(page, 'Owner Listing B');
    await returnToPortfolio(page);
    await openListing(page, 'Owner Listing A', 'Owner Listing A again');
    await until(() => (state.historyCalls[42] || 0) >= 2, 'replacement A history to load');
    const historyBeforeRelease = state.historyCalls[42] || 0;
    detailGate.release();
    await wait(260);
    check('O2 create A→B→A: old finalizer cannot change replacement A',
      (state.historyCalls[42] || 0) === historyBeforeRelease
      && await page.getByRole('heading', { name: 'Owner Listing A again', exact: true }).count() === 1);
    receipt.scenarios.push('O2 create success -> A→B→A');
    await context.close();
  }

  {
    const detailGate = deferred();
    const pending = removal(42);
    const state = ownerState({
      42: [
        { detail: ownerPreview(42, 'Owner Listing A', 25_000) },
        { gate: detailGate.promise, detail: ownerPreview(42, 'Owner Listing A refreshed', 25_000) }
      ],
      43: [{ detail: ownerPreview(43, 'Owner Listing B', 35_000) }]
    }, { 42: [{ items: [pending] }], 43: [{ items: [] }] });
    const { context, page } = await openOwnerPage(browser, state);
    await page.getByRole('button', { name: /Withdraw request/ }).click();
    await until(() => (state.detailCalls[42] || 0) >= 2, 'A withdrawal detail refresh to enter its gate');
    const withdrawRequest = state.withdrawRequests?.[0];
    check('C3.1 registered withdraw keeps its exact Listing, request, method, version and UUID',
      withdrawRequest?.listingId === 42
      && withdrawRequest?.requestId === 77
      && withdrawRequest?.method === 'POST'
      && withdrawRequest?.ifMatch === '0'
      && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
        .test(withdrawRequest?.body?.operationId ?? ''));
    await returnToPortfolio(page);
    await openListing(page, 'Owner Listing B');
    await until(() => (state.historyCalls[43] || 0) >= 1, 'B removal history to load');
    const historyBefore = state.historyCalls[42] || 0;
    detailGate.release();
    await wait(240);
    check('O2 withdraw A→B: no obsolete history dispatch',
      (state.historyCalls[42] || 0) === historyBefore);
    check('O2 withdraw A→B: B is not stuck or altered',
      await page.getByText('Checking removal status…').count() === 0
      && await page.getByRole('heading', { name: 'Owner Listing B', exact: true }).count() === 1);
    receipt.scenarios.push('O2 withdraw success -> held detail -> A→B');
    await context.close();
  }

  {
    const historyGate = deferred();
    let historyReleased = false;
    const state = ownerState({
      42: [
        { detail: ownerPreview(42, 'Owner Listing A', 25_000) },
        { detail: ownerPreview(42, 'Owner Listing A', 25_000) }
      ],
      43: [{ detail: ownerPreview(43, 'Owner Listing B', 35_000) }]
    }, {
      42: [
        { items: [], page: 0, hasMore: true },
        {
          gate: historyGate.promise.then(() => { historyReleased = true; }),
          items: [], page: 1, hasMore: true
        },
        { items: [], page: 0, hasMore: true }
      ],
      43: [{ items: [], page: 0, hasMore: true }]
    });
    const { context, page, errors } = await openFocusHarness(
      browser, state, focusHarnessBundle);
    await until(() => (state.historyCalls[42] || 0) === 1, 'A page zero history');
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await until(() => (state.historyCalls[42] || 0) === 2,
      'held A page-one history request');
    check('C2 A next-page transport gate is genuinely held',
      historyReleased === false && (state.historyResponses?.[42] || 0) === 1);

    await page.evaluate(() => window.selectFocusListing(43));
    await page.getByRole('heading', { name: 'Owner Listing B', exact: true }).waitFor();
    await until(() => (state.historyCalls[43] || 0) === 1, 'B page-zero history');
    await wait(120);
    const bFocusBeforeRelease = await page.evaluate(() => ({
      tag: document.activeElement?.tagName,
      text: document.activeElement?.textContent
    }));
    check('C2 reused instance does not transfer A focus intent to B',
      !(bFocusBeforeRelease.tag === 'P'
        && String(bFocusBeforeRelease.text).trim() === 'Page 1'),
      JSON.stringify(bFocusBeforeRelease));

    historyGate.release();
    await until(() => historyReleased === true, 'released A history response');
    await until(() => (state.historyResponses?.[42] || 0) >= 2,
      'released A history completion');
    await wait(120);
    check('C2 late A completion cannot focus or alter replacement B',
      await page.getByRole('heading', { name: 'Owner Listing B', exact: true }).count() === 1
      && !(await page.evaluate(() => document.activeElement?.textContent?.trim() === 'Page 1')));

    await page.evaluate(() => window.selectFocusListing(42));
    await page.getByRole('heading', { name: 'Owner Listing A', exact: true }).waitFor();
    await until(() => (state.historyCalls[42] || 0) === 3, 'replacement A page-zero history');
    await wait(120);
    check('C2 A→B→A requires a new generation-owned focus intent',
      !(await page.evaluate(() => document.activeElement?.textContent?.trim() === 'Page 1')));
    check('C2 focus ownership journey has no page error', errors.length === 0, errors.join(' | '));
    receipt.scenarios.push('C2 A next held -> B -> A replacement focus ownership');
    await context.close();
  }

  {
    const state = ownerState({
      42: [{ detail: ownerPreview(42, 'Owner Listing A', 25_000) }]
    }, {
      42: [
        { items: [], page: 0, hasMore: true },
        { items: [], page: 1, hasMore: false }
      ]
    });
    const { context, page, errors } = await openFocusHarness(
      browser, state, focusHarnessBundle);
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await until(() => (state.historyCalls[42] || 0) === 2, 'current page-two history');
    await until(async () => await page.locator('nav[aria-label="Removal history pages"] p')
      .evaluate(node => document.activeElement === node), 'current page focus');
    check('C2 successful current pagination focuses and announces Page 2',
      await page.locator('nav[aria-label="Removal history pages"] p').getAttribute('aria-live') === 'polite'
      && await page.evaluate(() => document.activeElement?.textContent?.trim() === 'Page 2'));
    check('C2 successful pagination has no page error', errors.length === 0, errors.join(' | '));
    receipt.scenarios.push('C2 current successful pagination focus and announcement');
    await context.close();
  }

  {
    const retainedIntentGate = deferred();
    const state = ownerState({
      42: [{ detail: ownerPreview(42, 'Owner Listing A', 25_000) }],
      43: [{ detail: ownerPreview(43, 'Owner Listing B', 35_000) }]
    }, {
      42: [
        { items: [], page: 0, hasMore: true },
        { gate: retainedIntentGate.promise, items: [], page: 1, hasMore: true }
      ],
      43: [{ items: [], page: 0, hasMore: true }]
    });
    const { context, page } = await openFocusHarness(
      browser, state, focusHarnessNegativeBundle);
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await until(() => (state.historyCalls[42] || 0) === 2,
      'the held A page-one history in the negative control');
    await page.evaluate(() => window.selectFocusListing(43));
    await page.getByRole('heading', { name: 'Owner Listing B', exact: true }).waitFor();
    await until(() => (state.historyCalls[43] || 0) === 1,
      'B page-zero history in the negative control');
    await wait(140);
    check('C2 negative control: retaining unowned A intent transfers B focus',
      await page.evaluate(() => document.activeElement?.tagName === 'P'
        && document.activeElement?.textContent?.trim() === 'Page 1'));
    retainedIntentGate.release();
    await until(() => (state.historyResponses[42] || 0) >= 2,
      'the late A history in the negative control');
    receipt.scenarios.push('C2 isolated retained-ticket ownership negative control');
    await context.close();
  }

  for (const status of [403, 404, 410, 429]) {
    const state = ownerState({
      42: [{ detail: ownerPreview(42, 'Owner Listing A', 25_000, 'PRIVATE_REVIEWER_SENTINEL') }]
    }, {
      42: [{ items: [removal(42, 'REJECTED', {
        decisionReason: 'PRIVATE_HISTORY_SENTINEL', permittedActions: ['REQUEST_AGAIN']
      })] }]
    }, {
      createResponse: { status, message: `Controlled ${status}` }
    });
    const { context, page } = await openOwnerPage(browser, state);
    await visible(page, 'text=PRIVATE_REVIEWER_SENTINEL');
    await page.getByRole('button', { name: /Request property removal again/ }).click();
    await page.locator('#lessor-removal-reason').fill(`Controlled ${status} removal reason`);
    await page.getByRole('button', { name: /Send removal request/ }).click();
    await wait(160);
    if (status === 429) {
      check('O5 current create 429: retryable failure retains authorized current state',
        await page.getByText('PRIVATE_REVIEWER_SENTINEL').count() === 1
        && await page.getByText('PRIVATE_HISTORY_SENTINEL').count() === 1);
    } else {
      check(`O5 current create ${status}: reviewer note withdrawn`,
        await page.getByText('PRIVATE_REVIEWER_SENTINEL').count() === 0);
      check(`O5 current create ${status}: history and request controls withdrawn`,
        await page.getByText('PRIVATE_HISTORY_SENTINEL').count() === 0
        && await page.locator('#lessor-removal-title').count() === 0);
    }
    check(`O5 current create ${status}: safe retry/error remains`,
      await page.locator('[role="alert"]').count() >= 1);
    receipt.scenarios.push(`O5 current create ${status}`);
    await context.close();
  }

  {
    const state = ownerState({
      42: [{ detail: ownerPreview(42, 'Owner Listing A', 25_000, 'PRIVATE_REVIEWER_SENTINEL') }]
    }, { 42: [{ status: 403 }] });
    const { context, page } = await openOwnerPage(browser, state);
    await wait(180);
    check('O5 current history 403 withdraws the visible reviewer note',
      await page.getByText('PRIVATE_REVIEWER_SENTINEL').count() === 0);
    receipt.scenarios.push('O5 current history 403');
    await context.close();
  }

  {
    const state = ownerState({
      42: [
        { detail: ownerPreview(42, 'Owner Listing A', 25_000, 'PRIVATE_REVIEWER_SENTINEL') },
        { status: 403 }
      ]
    }, {
      42: [{ items: [removal(42, 'REJECTED', {
        decisionReason: 'PRIVATE_HISTORY_SENTINEL', permittedActions: ['REQUEST_AGAIN']
      })] }]
    }, {
      createResponse: { status: 500, message: 'Controlled uncertain before detail denial' }
    });
    const { context, page } = await openOwnerPage(browser, state);
    await visible(page, 'text=PRIVATE_REVIEWER_SENTINEL');
    await page.getByRole('button', { name: /Request property removal again/ }).click();
    await page.locator('#lessor-removal-reason').fill('Controlled readback removal reason');
    await page.getByRole('button', { name: /Send removal request/ }).click();
    await visible(page, 'button:has-text("Check current status")');
    await page.getByRole('button', { name: 'Check current status' }).click();
    await wait(160);
    check('O5 current detail 403 withdraws reviewer, history and action state',
      await page.getByText('PRIVATE_REVIEWER_SENTINEL').count() === 0
      && await page.getByText('PRIVATE_HISTORY_SENTINEL').count() === 0
      && await page.locator('#lessor-removal-title').count() === 0);
    receipt.scenarios.push('O5 current detail 403 during exact readback');
    await context.close();
  }

  {
    const state = ownerState({
      42: [{ detail: ownerPreview(42, 'Owner Listing A', 25_000, 'PRIVATE_REVIEWER_SENTINEL') }]
    }, {
      42: [{ items: [removal(42, 'PENDING', {
        ownerReason: 'PRIVATE_HISTORY_SENTINEL'
      })] }]
    }, { withdrawResponse: { status: 403, message: 'Controlled withdraw denial' } });
    const { context, page } = await openOwnerPage(browser, state);
    await page.getByRole('button', { name: /Withdraw request/ }).click();
    await wait(160);
    check('O5 current withdraw 403 withdraws protected reviewer and history state',
      await page.getByText('PRIVATE_REVIEWER_SENTINEL').count() === 0
      && await page.getByText('PRIVATE_HISTORY_SENTINEL').count() === 0);
    receipt.scenarios.push('O5 current withdraw 403');
    await context.close();
  }

  {
    const actionGate = deferred();
    const state = ownerState({
      42: [{ detail: ownerPreview(42, 'Owner Listing A', 25_000) }],
      43: [{ detail: ownerPreview(43, 'Owner Listing B', 35_000) }]
    }, {}, {
      createResponse: { gate: actionGate.promise, status: 403, message: 'Controlled stale denial' }
    });
    const stateForB = { ...state };
    const { context, page } = await openOwnerPage(browser, stateForB);
    await page.getByRole('button', { name: /Request property removal/ }).click();
    await page.locator('#lessor-removal-reason').fill('Controlled stale denial reason');
    await page.getByRole('button', { name: /Send removal request/ }).click();
    await until(() => (state.createCalls[42] || 0) === 1, 'stale A create to enter its gate');
    await returnToPortfolio(page);
    await openListing(page, 'Owner Listing B');
    await visible(page, 'text=Owner Listing B');
    await until(() => (state.historyCalls[43] || 0) >= 1, 'B removal history to load');
    actionGate.release();
    await wait(220);
    check('O6 stale A 403 cannot erase B',
      await page.getByRole('heading', { name: 'Owner Listing B', exact: true }).count() === 1
      && await page.locator('#lessor-removal-title').count() === 1);
    receipt.scenarios.push('O6 stale A 403 after B');
    await context.close();
  }

  {
    const uncertainty = ownerState({
      42: [
        { detail: ownerPreview(42, 'Owner Listing A', 25_000, 'PRIVATE_REVIEWER_SENTINEL') },
        { detail: ownerPreview(42, 'Owner Listing A revalidated', 25_000, 'PRIVATE_REVIEWER_SENTINEL') }
      ]
    }, {
      42: [
        { items: [removal(42, 'REJECTED', { decisionReason: 'PRIVATE_HISTORY_SENTINEL' })] },
        { items: [removal(42, 'REJECTED', { decisionReason: 'PRIVATE_HISTORY_SENTINEL' })] }
      ]
    }, {
      createResponse: { status: 500, message: 'Controlled uncertain outcome' }
    });
    const { context, page } = await openOwnerPage(browser, uncertainty);
    await visible(page, 'text=PRIVATE_REVIEWER_SENTINEL');
    await page.getByRole('button', { name: /Request property removal again/ }).click();
    await page.locator('#lessor-removal-reason').fill('Controlled uncertain outcome reason');
    const send = page.getByRole('button', { name: /Send removal request/ });
    await send.evaluate(button => { button.click(); button.click(); });
    await wait(180);
    check('O7/O8 uncertain create sends exactly one mutation',
      (uncertainty.createCalls[42] || 0) === 1, JSON.stringify(uncertainty.createCalls));
    check('O7 uncertain failure retains safe current data',
      await page.getByText('PRIVATE_REVIEWER_SENTINEL').count() === 1
      && await page.getByText('PRIVATE_HISTORY_SENTINEL').count() === 1);
    check('O7 uncertain failure presents readback without silent replay',
      await page.getByRole('button', { name: 'Check current status' }).count() === 1
      && await page.getByRole('button', { name: /Send removal request/ }).count() === 0);
    await page.getByRole('button', { name: 'Check current status' }).click();
    await wait(180);
    check('O7 exact readback resolves to the current authorized state',
      (uncertainty.createCalls[42] || 0) === 1
      && await page.getByRole('button', { name: 'Check current status' }).count() === 0
      && await page.getByRole('heading', { name: 'Owner Listing A revalidated', exact: true }).count() === 1);
    receipt.scenarios.push('O7/O8 500 uncertainty, double click, manual readback');
    await context.close();
  }

  {
    const state = ownerState({
      42: [
        { detail: ownerPreview(42, 'Owner Listing A', 25_000) },
        { detail: ownerPreview(42, 'Owner Listing A authoritative', 25_000) }
      ]
    }, { 42: [{ items: [] }, { items: [] }] }, {
      createResponse: { status: 409, message: 'Controlled stale version conflict' }
    });
    const { context, page } = await openOwnerPage(browser, state);
    await page.getByRole('button', { name: /Request property removal/ }).click();
    await page.locator('#lessor-removal-reason').fill('Controlled stale version reason');
    await page.getByRole('button', { name: /Send removal request/ }).click();
    await wait(180);
    check('O8 stale 409 reads current authoritative detail without replay',
      (state.createCalls[42] || 0) === 1
      && await page.getByRole('heading', { name: 'Owner Listing A authoritative', exact: true }).count() === 1
      && await page.getByText('Controlled stale version conflict').count() === 1);
    receipt.scenarios.push('O8 stale 409 exact readback');
    await context.close();
  }

  {
    const actionGate = deferred();
    const state = ownerState({
      42: [{ detail: ownerPreview(42, 'Owner Listing A', 25_000) }],
      43: [{ detail: ownerPreview(43, 'Owner Listing B', 35_000) }]
    }, {}, {
      createResponse: { gate: actionGate.promise, status: 401, message: 'Old token expired' }
    });
    const { context, page } = await openOwnerPage(browser, state);
    await page.getByRole('button', { name: /Request property removal/ }).click();
    await page.locator('#lessor-removal-reason').fill('Controlled old token reason');
    await page.evaluate(() => {
      const buttons = [...document.querySelectorAll('button')];
      const send = buttons.find(button => button.textContent?.includes('Send removal request'));
      const back = buttons.find(button => button.textContent?.includes('My Properties'));
      if (!send || !back) throw new Error('Controlled action controls were unavailable.');
      send.click();
      queueMicrotask(() => {
        localStorage.setItem('pathome_auth_token', 'controlled-token-b');
        back.click();
      });
    });
    await until(() => (state.createCalls[42] || 0) === 1, 'old-token create to enter its gate');
    await visible(page, 'text=Submitted and managed properties');
    await openListing(page, 'Owner Listing B');
    await visible(page, 'text=Owner Listing B');
    await until(() => (state.historyCalls[43] || 0) >= 1, 'B removal history to load');
    actionGate.release();
    await wait(230);
    check('O4/O6 old-token 401 cannot expire the replacement session',
      await page.evaluate(() => localStorage.getItem('pathome_auth_token')) === 'controlled-token-b'
      && await page.getByRole('heading', { name: 'Owner Listing B', exact: true }).count() === 1);
    receipt.scenarios.push('O4/O6 old-token 401 after silent token replacement');
    await context.close();
  }

  {
    const property = homeProperty(97, 'Stable scroll card', 2, 25_000);
    const state = homeState({
      discovery: [property, homeProperty(98, 'Second card', 2, 26_000)],
      details: { 97: [{ delay: 50, property: homeProperty(97, 'Stable scroll card v3', 3, 27_000) }] }
    });
    const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const page = await context.newPage();
    await installRoutes(page, guestIdentity, state);
    await page.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
    await visible(page, '#property-card-97');
    await page.evaluate(() => window.scrollTo(0, 140));
    await wait(220);
    const before = await page.evaluate(() => window.scrollY);
    await emitHomeChange(page, 97);
    await wait(180);
    const after = await page.evaluate(() => window.scrollY);
    const retainedCards = await page.locator('#property-card-97, #property-card-98').count();
    check('H8 accepted background refresh does not blank the list',
      retainedCards === 2, `retained=${retainedCards}`);
    check('H8 accepted background refresh does not reset scroll',
      Math.abs(after - before) <= 1, `before=${before}, after=${after}`);
    const dimensions = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth, innerWidth: window.innerWidth
    }));
    check('H8 focused mobile refresh has no horizontal overflow',
      dimensions.scrollWidth <= dimensions.innerWidth, JSON.stringify(dimensions));
    await page.screenshot({ path: join(EVIDENCE, 'browser', 'home-refresh-390.png'), fullPage: true });
    receipt.scenarios.push('H8 mobile list, scroll and overflow continuity');
    await context.close();
  }
  }
} catch (error) {
  runnerFailure = {
    message: error instanceof Error ? error.message : String(error),
    stack: error instanceof Error ? error.stack : null
  };
  runnerRuntimeErrors.push({ kind: 'runner-exception', message: runnerFailure.message });
  console.error(`RUNNER FAILURE: ${runnerFailure.message}`);
} finally {
  clearTimeout(suiteWatchdog);
  try {
    if (browser) await browser.close();
    if (RUNNER_SELFTEST === 'cleanup-failure') {
      throw new Error('Injected browser cleanup failure.');
    }
  } catch (error) {
    runnerRuntimeErrors.push({
      kind: 'browser-close-failure',
      message: error instanceof Error ? error.message : String(error)
    });
  }
}
} catch (error) {
  if (!runnerFailure) {
    runnerFailure = {
      message: error instanceof Error ? error.message : String(error),
      stack: error instanceof Error ? error.stack : null
    };
  }
  runnerRuntimeErrors.push({
    kind: 'startup-failure',
    message: error instanceof Error ? error.message : String(error),
    phase: setupPhase
  });
  const message = error instanceof Error ? error.message : String(error);
  if (setupPhase === 'receipt-initialization') {
    console.error(
      `RUNNER EVIDENCE INITIALIZATION FAILURE: receipt could not be persisted: ${message}`
    );
  } else {
    console.error(`RUNNER STARTUP FAILURE during ${setupPhase}: ${message}`);
  }
}

const fixtureErrors = [...fixtureLedgers].flatMap((ledger, ledgerIndex) =>
  ledger.entries.map(entry => ({ ledgerIndex, ...entry })));
const fixtureErrorCount = [...fixtureLedgers].reduce((total, ledger) => total + ledger.count, 0);
const gateFailures = gateLifecycle.failures();
const pendingGateCount = gateFailures.length;
check('C3 strict fixture/browser ledger has no unexpected or exhausted entries',
  fixtureErrorCount === 0,
  JSON.stringify({ fixtureErrorCount, fixtureErrors }));
check('C3 every controlled gate was released and no fixture remains pending',
  pendingGateCount === 0, JSON.stringify(gateFailures));
check('C3 runner exception and suite deadline paths did not activate',
  runnerFailure === null && !suiteDeadlineExceeded,
  JSON.stringify({ runnerFailure, suiteDeadlineExceeded }));
check('C3 cleanup and runtime error arrays are empty',
  runnerRuntimeErrors.length === 0,
  JSON.stringify(runnerRuntimeErrors));
check('C3 every selected failure injection was detected',
  !RUNNER_SELFTEST || selftestResults.every(result => result.detected === true),
  JSON.stringify(selftestResults));

receipt.checks = checks;
receipt.failures = failures;
receipt.executedAt = new Date().toISOString();
receipt.startedAt = startedAt.toISOString();
receipt.durationMs = Date.now() - startedAt.getTime();
receipt.expectedFailureInjection = Boolean(RUNNER_SELFTEST);
receipt.phase = 'finalizing';
receipt.selftest = RUNNER_SELFTEST
  ? { requested: RUNNER_SELFTEST, results: selftestResults }
  : null;
receipt.fixtureIntegrity = {
  ledgerCount: fixtureLedgers.size,
  errorCount: fixtureErrorCount,
  entries: fixtureErrors,
  pendingGateCount
};
receipt.gateLifecycle = gateLifecycle.snapshot();
receipt.runtimeErrors = runnerRuntimeErrors;
receipt.runtimeErrorCount = runnerRuntimeErrors.length;
receipt.runnerFailure = runnerFailure;
receipt.environment = {
  node: process.version,
  platform: process.platform,
  architecture: process.arch,
  browser: 'Chromium',
  origin: ORIGIN,
  syntheticApisOnly: true,
  externalOriginsBlocked: true
};
try {
  const sourceEntries = [
    ['Home.tsx', new URL('../../src/components/Home.tsx', import.meta.url)],
    ['propertyRefreshState.ts', new URL('../../src/utils/propertyRefreshState.ts', import.meta.url)],
    ['propertyRefreshState.test.mjs', new URL('../../src/tests/propertyRefreshState.test.mjs', import.meta.url)],
    ['propertyRemovalService.ts', new URL('../../src/services/propertyRemovalService.ts', import.meta.url)],
    ['f3-f4-final-run.mjs', new URL(import.meta.url)],
    ['runner-integrity.mjs', new URL('./runner-integrity.mjs', import.meta.url)]
  ];
  if (RUNNER_SELFTEST === 'source-hash-failure') {
    sourceEntries.push(['missing-source', new URL('./missing-source.ts', import.meta.url)]);
  }
  receipt.sourceHashes = Object.fromEntries(await Promise.all(sourceEntries.map(
    async ([label, url]) => [
      label,
      createHash('sha256').update(await readFile(fileURLToPath(url))).digest('hex')
    ]
  )));
} catch (error) {
  runnerFailure = runnerFailure || {
    message: error instanceof Error ? error.message : String(error),
    stack: error instanceof Error ? error.stack : null
  };
  runnerRuntimeErrors.push({
    kind: 'source-hash-failure',
    message: error instanceof Error ? error.message : String(error)
  });
  receipt.sourceHashes = null;
}
check('C3 source fingerprint and build inputs were readable',
  receipt.sourceHashes !== null,
  JSON.stringify(runnerRuntimeErrors.filter(error => error.kind === 'source-hash-failure')));
receipt.checks = checks;
receipt.failures = failures;
receipt.historicalEvidencePreserved = {
  priorCorrectionReceipt:
    'tools/oe-integration-env/evidence/backoffice-removal-f3-f4-c1-c3-correction-20261025/browser/receipt.json',
  priorSevenOneCheckReceipt:
    'tools/oe-integration-env/evidence/backoffice-removal-f3-f4-f5-20261025/browser/receipt.json',
  note: 'This run writes only to a new evidence directory and does not overwrite retained receipts.'
};
receipt.runtimeErrors = runnerRuntimeErrors;
receipt.runtimeErrorCount = runnerRuntimeErrors.length;
receipt.runnerFailure = runnerFailure;
receipt.status = failures === 0
  && fixtureErrorCount === 0
  && pendingGateCount === 0
  && runnerRuntimeErrors.length === 0
  && runnerFailure === null
  && !suiteDeadlineExceeded ? 'PASS' : 'FAIL';
receipt.phase = receipt.status === 'PASS' ? 'complete' : 'failed';
try {
  const finalReceiptPath = RUNNER_SELFTEST === 'receipt-write-failure'
    ? join(receiptPath, 'blocked')
    : receiptPath;
  await writeReceiptAtomic(finalReceiptPath, receipt);
} catch (error) {
  console.error(`RUNNER RECEIPT WRITE FAILURE: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}
console.log(`\nControlled production-component checks: ${checks - failures}/${checks} passed`);
if (receipt.status !== 'PASS') process.exitCode = 1;
