/**
 * Real backend API integration. Every assertion below is made against a real backend response.
 * Browser acceptance is browser.mjs. No response interception or authenticated browser storage here.
 */
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { SCENARIOS, PHASE2A_AREAS } from './manifest.mjs';
import { boundClient, writePrivate, verifyJwt } from '../lib/runtime.mjs';
import { Evidence, assertResponse, offsetWindow, snapshot, unchanged, isObject } from './evidence.mjs';

const tool = fileURLToPath(new URL('../', import.meta.url));
const ADMIN_ROOT_IDS = new Set(['C44', 'C45', 'C48', 'C52', 'C53', 'C54']);
const required = (condition, message) => { if (!condition) throw Error(message); };
const linked = view => Object.fromEntries((view.linkedRequestVersions ?? []).map(e => [e.requestId, e.version]));
const view = response => response.json?.session ?? response.json;
const noStore = response => String(response.headers['cache-control']).includes('no-store');

async function main() {
  const fixturesOnly = process.argv.includes('--b09-fixtures');
  required(process.env.PATHOME_OE_TEST_PHASE2A_LIVE === 'true', 'Explicit live opt-in required');
  required(process.env.PATHOME_OE_TEST_FIXTURE_ROLES === 'true', 'Disposable actor fixture opt-in required');
  const client = boundClient();
  const { secrets, binding, root } = client;
  const evidence = new Evidence(fixturesOnly ? SCENARIOS.filter(s => s.kind === 'fixture') : SCENARIOS,
    root + '/evidence/' + (fixturesOnly ? 'b09-fixtures.json' : 'phase2a-api.json'), fixturesOnly ? 'B09_FIXTURES' : 'API',
    { runId: binding.runId, database: binding.database, backend: client.origin });
  evidence.watchInterruptions();
  const api = client.request;
  const ctx = { ...offsetWindow(), tag: randomUUID().replaceAll('-', '').slice(0, 10),
    sessions: {}, missingRequestId: 9007199254740000, contactValues: [], fixtureCalls: [] };
  const password = secrets.OE_STAFF_PASSWORD;
  required(password && secrets.OE_BOOTSTRAP_ADMIN_PASSWORD, 'Disposable credentials absent');
  let activeId = null, succeeded = false;
  const checked = async (method, path, token, body, statuses = [200], headers) => {
    const response = await api(method, path, token, body, headers);
    ctx.fixtureCalls.push({ method, endpoint: path, httpStatus: response.status });
    required(statuses.includes(response.status), method + ' ' + path + ': unexpected HTTP ' + response.status);
    return response;
  };
  const authority = actor => ctx.sessions[actor]?.authority ?? (actor === 'anonymous' ? { anonymous: true } : null);
  const fixture = async (id, operation) => {
    activeId = id; const first = ctx.fixtureCalls.length;
    const response = await operation();
    const scenario = SCENARIOS.find(s => s.id === id);
    const calls = ctx.fixtureCalls.slice(first);
    required(calls.length > 0, 'Fixture produced no execution evidence');
    evidence.record(id, { result: 'PASS', actor: scenario.actor, authority: authority(scenario.actor),
      method: calls.at(-1).method, endpoint: calls.at(-1).endpoint, httpStatus: calls.at(-1).httpStatus,
      calls, requiredFields: response && isObject(response.json) ? Object.keys(response.json).filter(k => !/token|email|password|mobile|name/i.test(k)) : [],
      after: snapshot(response?.json) });
    activeId = null;
    return response;
  };
  const signIn = async (email, label) => {
    const response = await checked('POST', '/auth/login', null, { email,
      password: label === 'bootstrap-admin' ? secrets.OE_BOOTSTRAP_ADMIN_PASSWORD : password });
    const auth = response.json;
    required(typeof auth?.token === 'string', 'No live credential');
    const claims = verifyJwt(auth.token, secrets.OE_JWT_SECRET, email);
    const capabilities = await checked('GET', '/staff/me/capabilities', auth.token);
    required(Array.isArray(capabilities.json?.capabilities), 'Malformed live capability facts');
    return { email, token: auth.token, userId: auth.userId,
      authority: { role: claims.role, staffActive: capabilities.json.staffActive, capabilities: capabilities.json.capabilities } };
  };
  const register = async (kind, phone = false) => {
    const email = 'codex.p2a.' + kind + '.' + ctx.tag + '@pathome.test';
    const response = await checked('POST', '/auth/register', null, { email, password, fullName: 'P2A ' + kind,
      ...(phone ? { phoneNumber: '+9198765' + String(kind === 'lessor' ? 11001 : 22002) } : {}) });
    required(response.json?.role === 'ROLE_TENANT' && response.json.userId > 0, 'Registration did not create an ordinary User');
    return { email, userId: response.json.userId, token: response.json.token, response };
  };
  const activate = async person => {
    const state = await checked('GET', '/staff/admin/users/' + person.userId, ctx.admin.token);
    await checked('POST', '/staff/admin/users/' + person.userId + '/activate', ctx.admin.token,
      { expectedVersion: state.json.version ?? null, reasonCode: 'PHASE2A_FIXTURE_ACTIVATION' });
  };
  const grant = (person, capability, scopeType, id) => checked('POST', '/staff/admin/users/' + person.userId + '/grants', ctx.admin.token,
    { capability, scopeType, ...(scopeType === 'TEAM' ? { teamId: id } : scopeType === 'CITY' ? { cityId: id } : {}), reasonCode: 'PHASE2A_FIXTURE_GRANT' });
  const route = async requestId => {
    const candidates = await checked('GET', '/operations/intake-routing/visit-requests/' + requestId + '/candidates?cityId=' + ctx.city.id, ctx.admin.token);
    const team = candidates.json.teams.items.find(t => t.id === ctx.teamAlphaId);
    required(team && candidates.json.request.requestId === requestId, 'Routing candidate identity mismatch');
    return checked('POST', '/operations/intake-routing/visit-requests/' + requestId + '/route', ctx.admin.token,
      { expectedRequestVersion: candidates.json.request.requestVersion, cityId: ctx.city.id,
        expectedCityVersion: candidates.json.city.version, teamId: team.id, expectedTeamVersion: team.version,
        reasonCode: 'INITIAL_ROUTING' }, [204]);
  };
  const intake = async person => {
    const result = await checked('POST', '/properties/' + ctx.listingId + '/visit-requests', person.token,
      { preferredVisitTiming: 'Disposable acceptance window', availabilityStartAt: ctx.availabilityStart,
        availabilityEndAt: ctx.availabilityEnd, availabilityZoneId: ctx.availabilityZone }, [201]);
    required(result.json.requestId > 0, 'Intake returned no Request ID');
    ctx.lastIntakeResponse = result;
    return result.json.requestId;
  };
  const ownedRequest = async id => {
    const result = await checked('GET', '/operations/my-work?page=0&size=100', ctx.sessions['oe-alpha'].token);
    const item = result.json.items.find(i => i.targetType === 'REQUEST' && i.targetId === id);
    required(item, 'Owned Request missing'); return item;
  };
  const claimRequest = async id => {
    const current = await checked('GET', '/operations/claimable-work/REQUEST/' + id, ctx.sessions['oe-alpha'].token);
    await checked('POST', '/operations/visit-requests/' + id + '/claim', ctx.sessions['oe-alpha'].token,
      { expectedRequestVersion: current.json.expectedVersion, reasonCode: 'CLAIM_WORK' }, [204]);
    return ownedRequest(id);
  };
  const sessionRead = async id => checked('GET', '/operations/visit-sessions/' + id, ctx.sessions['oe-alpha'].token);
  const publishAnother = async lessor => {
    const draft = await checked('POST', '/lessor/properties/drafts', lessor.token, { propertyType: 'FLAT', rentalMode: 'LONG_TERM_RENTAL', bhkCount: '2BHK' });
    const id = draft.json.draftId;
    for (const [section, payload] of sections()) {
      const current = await checked('GET', '/lessor/properties/drafts/' + id, lessor.token);
      await checked('PATCH', '/lessor/properties/drafts/' + id + '/sections/' + section, lessor.token, payload, [200], { 'If-Match': String(current.json.version) });
    }
    const mediaId = randomUUID(), form = new FormData();
    form.append('file', new Blob([readFileSync(process.env.P4_MEDIA_FILE)], { type: 'image/jpeg' }), 'disposable-cover.jpg');
    const upload = await checked('POST', '/lessor/properties/drafts/' + id + '/media?mediaId=' + mediaId, lessor.token, form);
    required(upload.json.status === 'UPLOADED', 'Secondary real upload incomplete');
    await checked('PUT', '/lessor/properties/drafts/' + id + '/media/' + mediaId + '/cover', lessor.token);
    const submitted = await checked('POST', '/lessor/properties/drafts/' + id + '/submit', lessor.token);
    const listingId = submitted.json.listingId;
    await checked('POST', '/admin/lessor-review/listings/' + listingId + '/start-review', ctx.admin.token);
    await checked('POST', '/admin/lessor-review/listings/' + listingId + '/link-locality', ctx.admin.token, { canonicalLocalityId: ctx.locality.id });
    await checked('POST', '/admin/lessor-review/listings/' + listingId + '/approve', ctx.admin.token);
    return listingId;
  };
  const sections = () => [
    ['pricing', { monthlyRent: 21000, securityDeposit: 42000 }],
    ['location', { city: ctx.city.displayName, canonicalLocalityId: null, localityInput: ctx.locality.name,
      address: 'Disposable fixture private address', landmark: 'Fixture landmark', resolutionType: 'MANUAL_PENDING',
      provider: null, providerPlaceId: null, selectionToken: null }],
    ['details', { availableFrom: new Date().toISOString().slice(0, 10), furnishingStatus: 'SEMI_FURNISHED', totalAreaSqFt: 1180,
      floorNumber: 4, totalFloors: 8, amenities: 'Lift', description: 'Disposable Phase 2A fixture.' }]
  ];
  try {
    ctx.admin = await signIn(secrets.OE_BOOTSTRAP_ADMIN_EMAIL, 'bootstrap-admin');
    ctx.sessions['bootstrap-admin'] = ctx.admin;
    const cities = await checked('GET', '/staff/admin/operating-teams/cities?page=0&size=50', ctx.admin.token);
    ctx.city = cities.json.items.find(c => c.code === (process.env.P4_CITY_CODE ?? 'indore') && c.active);
    required(ctx.city, 'Canonical supported City unavailable');
    const team = async label => {
      const result = await checked('POST', '/staff/admin/cities/' + ctx.city.id + '/operating-teams', ctx.admin.token,
        { code: 'p2a-' + ctx.tag + '-' + label, displayName: 'P2A ' + label, expectedCityVersion: ctx.city.version,
          reasonCode: 'PHASE2A_FIXTURE_TEAM' }, [201]);
      required(result.json.id > 0, 'Team ID missing'); return result;
    };
    ctx.teamAlphaId = (await fixture('F10', () => team('alpha'))).json.id;
    ctx.teamBravoId = (await fixture('F11', () => team('bravo'))).json.id;
    ctx.people = {};
    await fixture('F12', async () => {
      let response;
      for (const name of ['oe-alpha', 'oe-bravo', 'ge', 'admin', 'no-grant', 'city-admin']) {
        const person = await register(name); ctx.people[name] = person; await activate(person); response = person.response;
      }
      return response;
    });
    await fixture('F13', async () => {
      for (const name of ['oe-alpha', 'oe-bravo']) await grant(ctx.people[name], 'OPS_COORDINATE', 'TEAM',
        name === 'oe-alpha' ? ctx.teamAlphaId : ctx.teamBravoId);
      await grant(ctx.people.ge, 'OPS_COORDINATE', 'TEAM', ctx.teamAlphaId);
      return checked('GET', '/staff/admin/users/' + ctx.people.ge.userId, ctx.admin.token);
    });
    await fixture('F14', async () => {
      await grant(ctx.people.admin, 'STAFF_ADMIN', 'GLOBAL');
      await grant(ctx.people['city-admin'], 'CITY_TEAM_ADMIN', 'CITY', ctx.city.id);
      execFileSync('/bin/bash', [tool + 'fixture-roles.sh', String(ctx.people.admin.userId), String(ctx.people.ge.userId), ctx.tag],
        { env: { PATH: '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin', HOME: process.env.HOME,
          PATHOME_OE_TEST_ENV_FILE: process.env.PATHOME_OE_TEST_ENV_FILE ?? tool + 'oe-test.env', PATHOME_OE_TEST_FIXTURE_ROLES: 'true',
          PATHOME_OE_TEST_DB_PASSWORD: process.env.PATHOME_OE_TEST_DB_PASSWORD ?? '',
          PATHOME_OE_TEST_BACKEND_PORT: String(new URL(client.origin).port) }, timeout: 30000, stdio: ['ignore', 'pipe', 'pipe'] });
      ctx.fixtureCalls.push({ method: 'TRANSACTION', endpoint: 'disposable-test-only/actor-roles', httpStatus: null,
        auditAction: 'DISPOSABLE_ACTOR_ROLE_CREATED' });
      for (const [actor, name] of Object.entries({ 'oe-alpha': 'oe-alpha', 'oe-bravo': 'oe-bravo', 'ge-alpha': 'ge',
        'legacy-admin': 'admin', 'staff-no-grant': 'no-grant', 'city-admin': 'city-admin' })) {
        ctx.sessions[actor] = await signIn(ctx.people[name].email, actor);
      }
      required(ctx.sessions['legacy-admin'].authority.role === 'ROLE_ADMIN' && ctx.sessions['ge-alpha'].authority.role === 'ROLE_GROUND_BOY',
        'Fixture role verification failed');
      ctx.geUserId = ctx.people.ge.userId;
      return checked('GET', '/staff/me/capabilities', ctx.sessions['legacy-admin'].token);
    });
    let lessor;
    await fixture('F01', async () => { lessor = await register('lessor', true); ctx.sessions.lessor = await signIn(lessor.email, 'lessor'); return lessor.response; });
    const localityRead = await checked('GET', '/lessor/locations/suggestions?city=' + encodeURIComponent(ctx.city.displayName) + '&q=Vijay', lessor.token);
    ctx.locality = localityRead.json.find(l => l.city === ctx.city.displayName);
    required(ctx.locality?.id > 0, 'Canonical locality unavailable');
    ctx.draftId = (await fixture('F02', () => checked('POST', '/lessor/properties/drafts', lessor.token,
      { propertyType: 'FLAT', rentalMode: 'LONG_TERM_RENTAL', bhkCount: '2BHK' }))).json.draftId;
    await fixture('F03', async () => {
      let result;
      for (const [section, payload] of sections()) {
        const current = await checked('GET', '/lessor/properties/drafts/' + ctx.draftId, lessor.token);
        result = await checked('PATCH', '/lessor/properties/drafts/' + ctx.draftId + '/sections/' + section, lessor.token,
          payload, [200], { 'If-Match': String(current.json.version) });
      }
      return result;
    });
    const mediaId = randomUUID();
    await fixture('F04', async () => {
      const form = new FormData(); form.append('file', new Blob([readFileSync(process.env.P4_MEDIA_FILE)], { type: 'image/jpeg' }), 'disposable-cover.jpg');
      const result = await checked('POST', '/lessor/properties/drafts/' + ctx.draftId + '/media?mediaId=' + mediaId, lessor.token, form);
      required(result.json.status === 'UPLOADED', 'Real media upload incomplete'); return result;
    });
    await fixture('F05', () => checked('PUT', '/lessor/properties/drafts/' + ctx.draftId + '/media/' + mediaId + '/cover', lessor.token));
    ctx.listingId = (await fixture('F06', () => checked('POST', '/lessor/properties/drafts/' + ctx.draftId + '/submit', lessor.token))).json.listingId;
    ctx.sessions.reviewer = ctx.admin;
    await fixture('F07', () => checked('POST', '/admin/lessor-review/listings/' + ctx.listingId + '/start-review', ctx.admin.token));
    await fixture('F08', () => checked('POST', '/admin/lessor-review/listings/' + ctx.listingId + '/link-locality',
      ctx.admin.token, { canonicalLocalityId: ctx.locality.id }));
    await fixture('F09', async () => {
      const result = await checked('POST', '/admin/lessor-review/listings/' + ctx.listingId + '/approve', ctx.admin.token);
      required(result.json.status === 'PUBLISHED', 'Listing not published'); return result;
    });
    ctx.nearbyListingId = await publishAnother(lessor);
    required(ctx.nearbyListingId !== ctx.listingId, 'Nearby listing must be distinct');
    let tenant, secondTenant, adminTenant, browserTenant;
    await fixture('F15', async () => {
      tenant = await register('tenant', true); secondTenant = await register('second-tenant');
      adminTenant = await register('admin-tenant'); browserTenant = await register('browser-tenant');
      ctx.sessions['plain-tenant'] = await signIn(tenant.email, 'plain-tenant');
      ctx.requestId = await intake(tenant); ctx.secondRequestId = await intake(secondTenant);
      ctx.adminRequestId = await intake(adminTenant); ctx.browserRequestId = await intake(browserTenant);
      required(new Set([ctx.requestId, ctx.secondRequestId, ctx.adminRequestId, ctx.browserRequestId]).size === 4, 'Independent intake roots collided');
      return ctx.lastIntakeResponse;
    });
    await fixture('F16', async () => {
      let result; for (const id of [ctx.requestId, ctx.secondRequestId, ctx.adminRequestId, ctx.browserRequestId]) result = await route(id);
      return result;
    });
    const adminToken = ctx.sessions['legacy-admin'].token;
    let scheduling;
    scheduling = (await fixture('F17', () => checked('PUT', '/operations/ground-executives/' + ctx.geUserId + '/scheduling/active',
      adminToken, { expectedVersion: 0, schedulingActive: true }))).json;
    scheduling = (await fixture('F18', () => checked('POST', '/operations/ground-executives/' + ctx.geUserId + '/scheduling/shifts',
      adminToken, { expectedVersion: scheduling.version, startsAt: ctx.windowStart, endsAt: ctx.windowEnd, zoneId: ctx.availabilityZone }))).json;
    await fixture('F19', () => checked('PUT', '/operations/ground-executives/' + ctx.geUserId + '/scheduling/coverage', adminToken,
      { expectedVersion: scheduling.version, coverage: [{ city: ctx.city.displayName, localityId: ctx.locality.id }] }));
    // Credential-free handoff for a separate genuine login/browser journey.
    evidence.provenance.fixturesFile = evidence.directory + '/browser-fixtures.json';
    writePrivate(evidence.provenance.fixturesFile, { runId: binding.runId, requestId: ctx.browserRequestId, secondRequestId: ctx.secondRequestId,
      listingId: ctx.listingId, nearbyListingId: ctx.nearbyListingId, geUserId: ctx.geUserId,
      localityName: ctx.locality.name,
      oeEmail: ctx.sessions['oe-alpha'].email, otherEmail: ctx.sessions['oe-bravo'].email, tenantEmail: browserTenant.email,
      adminEmail: ctx.sessions['legacy-admin'].email,
      ...offsetWindow() });

    if (fixturesOnly) {
      required(evidence.complete(), 'Incomplete B09 fixtures');
      succeeded = true;
      console.log('B09 fixture preparation: 19 setup entries passed; 63 API coverage scenarios not run.');
      return;
    }

    ctx.targetType = 'REQUEST'; ctx.targetId = ctx.requestId;
    const queue = await checked('GET', '/operations/claimable-work?page=0&size=100', ctx.sessions['oe-alpha'].token);
    ctx.claimBaseVersion = queue.json.items.find(i => i.targetType === 'REQUEST' && i.targetId === ctx.requestId)?.expectedVersion;
    required(Number.isInteger(ctx.claimBaseVersion), 'Claimable optimistic version missing');
    for (const scenario of SCENARIOS.filter(s => s.kind === 'coverage').sort((a,b) => a.stage - b.stage || a.id.localeCompare(b.id))) {
      activeId = scenario.id;
      const supportingStart = ctx.fixtureCalls.length;
      if (scenario.id === 'C44') {
        const owned = await claimRequest(ctx.adminRequestId);
        const coordinated = await checked('POST', '/operations/visit-requests/' + ctx.adminRequestId + '/coordinate',
          ctx.sessions['oe-alpha'].token, { expectedRequestVersion: owned.expectedVersion, sessionId: null, expectedSessionVersion: null });
        ctx.adminSessionId = coordinated.json.sessionId;
        const adminView = (await sessionRead(ctx.adminSessionId)).json;
        await checked('PUT', '/operations/visit-sessions/' + ctx.adminSessionId + '/items/' + adminView.items[0].itemId + '/availability',
          ctx.sessions['oe-alpha'].token, { expectedSessionVersion: adminView.version, status: 'CONFIRMED',
            availabilityStartAt: ctx.availabilityStart, availabilityEndAt: ctx.availabilityEnd,
            availabilityZoneId: ctx.availabilityZone, availabilitySource: 'PHONE' });
      }
      const adminRoot = ADMIN_ROOT_IDS.has(scenario.id);
      ctx.sessionId = adminRoot ? ctx.adminSessionId : ctx.primarySessionId;
      if (ctx.sessionId) {
        let current;
        if (scenario.id === 'C19') current = (await checked('GET', '/operations/claimable-work/SESSION/' + ctx.sessionId, ctx.sessions['oe-alpha'].token)).json;
        else current = (await sessionRead(ctx.sessionId)).json;
        ctx.sessionVersion = current.version ?? current.expectedVersion; ctx.sessionJson = current;
        ctx.linkedRequestVersions = linked(current);
        ctx.itemId = (current.items ?? []).find(i => !i.removedAt && i.sourceRequestId === ctx.requestId)?.itemId;
        ctx.derivedItemId = (current.items ?? []).find(i => i.listingId === ctx.nearbyListingId)?.itemId;
      }
      if (scenario.id === 'C35') ctx.secondOwned = await claimRequest(ctx.secondRequestId);
      let body;
      const requestVersion = ctx.claimBaseVersion + 1;
      const ownership = { expectedSessionVersion: ctx.sessionVersion, expectedRequestVersions: ctx.linkedRequestVersions, reasonCode: 'CLAIM_WORK' };
      const add = { expectedSessionVersion: ctx.sessionVersion, listingId: ctx.nearbyListingId, originatingRequestId: ctx.requestId, origin: 'OE_ADDED' };
      const availability = { expectedSessionVersion: ctx.sessionVersion, status: 'CONFIRMED', availabilityStartAt: ctx.availabilityStart,
        availabilityEndAt: ctx.availabilityEnd, availabilityZoneId: ctx.availabilityZone, availabilitySource: 'PHONE' };
      const select = candidate => ({ commandId: randomUUID(), expectedSessionVersion: ctx.sessionVersion, groundExecutiveUserId: candidate?.groundExecutiveUserId,
        scheduledAt: candidate?.scheduledAt, zoneId: candidate?.zoneId, overrideReason: null });
      switch (scenario.id) {
        case 'C11': body = { expectedRequestVersion: ctx.claimBaseVersion + 5, reasonCode: 'CLAIM_WORK' }; break;
        case 'C12': case 'C15': body = { expectedRequestVersion: ctx.claimBaseVersion, reasonCode: 'CLAIM_WORK' }; break;
        case 'C14': body = { expectedRequestVersion: requestVersion, reasonCode: 'CLAIM_WORK' }; break;
        case 'C16': body = { expectedRequestVersion: (await checked('GET', '/operations/claimable-work/REQUEST/' + ctx.secondRequestId, ctx.sessions['oe-alpha'].token)).json.expectedVersion, reasonCode: 'CLAIM_WORK' }; break;
        case 'C17': ctx.secondReleaseVersion = (await ownedRequest(ctx.secondRequestId)).expectedVersion;
          body = { expectedRequestVersion: ctx.secondReleaseVersion, reasonCode: 'RELEASE_WORK' }; break;
        case 'C18': body = { expectedRequestVersion: (await ownedRequest(ctx.secondRequestId)).expectedVersion + 5, reasonCode: 'RELEASE_WORK' }; break;
        case 'C19': body = ownership; break;
        case 'C20': body = { ...ownership, reasonCode: 'RELEASE_WORK' }; break;
        case 'C32': body = { expectedRequestVersion: (await ownedRequest(ctx.requestId)).expectedVersion, sessionId: null, expectedSessionVersion: null }; break;
        case 'C33': case 'C34': body = { expectedRequestVersion: requestVersion, sessionId: null, expectedSessionVersion: null }; break;
        case 'C35': body = { expectedRequestVersion: ctx.secondOwned.expectedVersion }; break;
        case 'C36': case 'C39': case 'C40': body = add; break;
        case 'C37': ctx.submittedOrder = ctx.sessionJson.items.filter(i => !i.removedAt).sort((a,b) => a.position-b.position).map(i => i.itemId).reverse();
          body = { expectedSessionVersion: ctx.sessionVersion, itemIds: ctx.submittedOrder }; break;
        case 'C38': ctx.itemId = ctx.derivedItemId; body = { expectedSessionVersion: ctx.sessionVersion, reason: 'Disposable derived stop removal' }; break;
        case 'C41': case 'C43': body = availability; break;
        case 'C42': body = { expectedSessionVersion: ctx.sessionVersion }; break;
        case 'C44': case 'C46': body = { expectedSessionVersion: ctx.sessionVersion }; break;
        case 'C45': body = select(ctx.adminCandidate); break;
        case 'C47': body = select(ctx.ownerCandidate); break;
        case 'C48': case 'C49': body = { expectedSessionVersion: ctx.sessionVersion, groundExecutiveUserId: ctx.geUserId }; break;
        case 'C52': body = { expectedSessionVersion: ctx.sessionVersion, scheduledAt: ctx.sessionJson.scheduledAt, zoneId: ctx.sessionJson.zoneId,
          groundExecutiveUserId: ctx.geUserId, durationMinutes: ctx.sessionJson.durationMinutes }; break;
        case 'C53': body = { expectedSessionVersion: ctx.sessionVersion, scheduledAt: ctx.rescheduleAt, zoneId: ctx.availabilityZone }; break;
        case 'C54': body = { expectedSessionVersion: ctx.beforeRescheduleVersion, scheduledAt: ctx.staleRescheduleAt, zoneId: ctx.availabilityZone }; break;
      }
      const actor = scenario.actor === 'anonymous' ? null : ctx.sessions[scenario.actor];
      required(scenario.actor === 'anonymous' || actor, 'Scenario actor missing');
      ctx.actorUserId = actor?.userId; ctx.submittedBody = body;
      const path = scenario.apiPath.replace(/\{(\w+)\}/g, (_, name) => {
        required(ctx[name] !== undefined && ctx[name] !== null, 'Unresolved endpoint field: ' + name); return String(ctx[name]);
      });
      // Canonical before/after state comes from the real owning credential, never the denied actor.
      const stateRead = async () => {
        if (scenario.id === 'C35') return snapshot((await checked('GET', '/operations/visit-requests/' + ctx.secondRequestId, ctx.sessions['oe-alpha'].token)).json);
        if (scenario.id === 'C19') {
          const available = await api('GET', '/operations/claimable-work/SESSION/' + ctx.sessionId, ctx.sessions['oe-alpha'].token);
          if (available.status === 200) return snapshot(available.json);
        }
        if (ctx.sessionId) return snapshot((await sessionRead(ctx.sessionId)).json);
        const list = await checked('GET', '/operations/claimable-work?page=0&size=100', ctx.sessions['oe-alpha'].token);
        const owned = await checked('GET', '/operations/my-work?page=0&size=100', ctx.sessions['oe-alpha'].token);
        const requestId = ['C16','C17','C18','C35'].includes(scenario.id) ? ctx.secondRequestId : ctx.requestId;
        const mine = owned.json.items.find(i => i.targetType === 'REQUEST' && i.targetId === requestId);
        const item = mine ?? list.json.items.find(i => i.targetType === 'REQUEST' && i.targetId === requestId);
        return item ? snapshot({ ...item, observedOwnerUserId: mine ? ctx.sessions['oe-alpha'].userId : null }) : null;
      };
      const before = await stateRead();
      const response = await api(scenario.method, path, actor?.token, body);
      const after = scenario.id === 'C32' && response.status === 200 ? snapshot((await sessionRead(response.json.sessionId)).json) : scenario.id === 'C20' ? snapshot({ ...(await checked('GET', '/operations/claimable-work/SESSION/' + ctx.sessionId, ctx.sessions['oe-alpha'].token)).json, observedOwnerUserId: null })
        : await stateRead();
      let result = assertResponse(scenario, response, ctx, before, after);
      if (['C52'].includes(scenario.id) && !unchanged(before, after)) result = 'Idempotent retry changed state';
      if (scenario.id === 'C26' && response.json?.message !== ctx.hiddenRequestMessage) result = 'Missing and hidden Request error contracts differ';
      if (scenario.id === 'C23') ctx.hiddenRequestMessage = response.json?.message;
      const resultView = view(response);
      if (scenario.id === 'C32' && response.status === 200) ctx.primarySessionId = resultView.sessionId;
      if (scenario.id === 'C44') ctx.adminCandidate = response.json?.candidates?.find(c => c.groundExecutiveUserId === ctx.geUserId && c.feasibilityStatus === 'FEASIBLE');
      if (scenario.id === 'C46') ctx.ownerCandidate = response.json?.candidates?.find(c => c.groundExecutiveUserId === ctx.geUserId && c.feasibilityStatus === 'FEASIBLE');
      if (scenario.id === 'C50') ctx.scheduledVersion = response.json?.version;
      if (scenario.id === 'C53') ctx.beforeRescheduleVersion = before?.version;
      if (scenario.id === 'C27') ctx.contactValues = [response.json?.name, response.json?.mobile].filter(Boolean);
      if (scenario.id === 'C61') ctx.finalPrimaryVersion = (await sessionRead(ctx.primarySessionId)).json.version;
      evidence.record(scenario.id, { actor: scenario.actor, authority: authority(scenario.actor), method: scenario.method,
        endpoint: path, httpStatus: response.status, requiredFields: response.json ? Object.keys(response.json).filter(k => !/token|email|mobile|name/i.test(k)) : [],
        supportingCalls: ctx.fixtureCalls.slice(supportingStart),
        before, after, result: result === true ? 'PASS' : 'FAIL', reason: result === true ? null : String(result) });
      activeId = null;
      required(result === true, 'Scenario ' + scenario.id + ' failed; remaining required scenarios are NOT TESTED');
    }
    required(evidence.complete(), 'Incomplete required evidence');
    console.log('API integration: ' + SCENARIOS.length + ' distinct required entries passed. Browser acceptance is separate.');
    for (const area of PHASE2A_AREAS) console.log(area.id + ': ' + SCENARIOS.filter(s => s.area === area.id).length + ' passed');
    succeeded = true;
  } catch (error) {
    if (activeId && evidence.records.get(activeId)?.result === 'NOT TESTED') {
      const scenario = SCENARIOS.find(s => s.id === activeId);
      evidence.record(activeId, { result: 'FAIL', authority: authority(scenario.actor), reason: 'Scenario aborted; inspect sanitized endpoint/status evidence' });
    }
    console.error('API integration failed: ' + String(error.message).replace(/Bearer\s+\S+/g, 'Bearer [redacted]'));
    process.exitCode = 1;
  } finally { evidence.finish(succeeded ? 'PASSED' : 'FAILED'); }
}
main().catch(() => { console.error('REFUSED: disposable run binding or authorization is absent'); process.exitCode = 90; });
