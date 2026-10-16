/**
 * The genuine Lessor -> Tenant -> Operations Executive journey, over real HTTP against the live backend
 * on the designated disposable PostgreSQL database.
 *
 * Nothing here is seeded through SQL: every identity is registered through `POST /api/v1/auth/register`,
 * every capability is created through `POST /api/v1/staff/admin/**`, the locality is discovered through
 * the lessor location-suggestion API, the media really goes to the configured object store, the listing
 * is really published by the review API, and the Visit Request is created by the real tenant intake
 * route. No canonical column is written by hand and no publication is fabricated.
 *
 * Usage (see package4.sh journey):
 *   P4_BACKEND_URL=http://localhost:8080 \
 *   P4_ADMIN_EMAIL=... P4_ADMIN_PASSWORD=... P4_STAFF_PASSWORD=... \
 *   P4_MEDIA_FILE=<path to a jpeg> P4_STATE_FILE=<path> \
 *   node journey.mjs
 *
 * Prints one line per check and exits non-zero on the first failed stage's end. The state file it writes
 * holds the disposable identities and identifiers the browser pass then reuses. It holds e-mail
 * addresses and ids only: no password and no bearer token is ever written to it, printed, or committed.
 * Credentials come exclusively from the ephemeral run (`$PATHOME_OE_TEST_RUN_DIR/secrets.env`), never
 * from this file and never from the repository.
 */
import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { boundClient } from './lib/runtime.mjs';

const bound = boundClient();
const BACKEND = bound.origin;
const API = `${BACKEND}/api/v1`;
const ADMIN = { email: process.env.P4_ADMIN_EMAIL, password: process.env.P4_ADMIN_PASSWORD };
const STAFF_PASSWORD = process.env.P4_STAFF_PASSWORD;
if (!ADMIN.password || !STAFF_PASSWORD) {
  throw new Error('disposable run credentials are required: P4_ADMIN_PASSWORD and P4_STAFF_PASSWORD come '
    + 'from $PATHOME_OE_TEST_RUN_DIR/secrets.env (see package4.sh journey). No password is defaulted here.');
}
const MEDIA_FILE = process.env.P4_MEDIA_FILE;
const STATE_FILE = process.env.P4_STATE_FILE;
const CITY_CODE = process.env.P4_CITY_CODE ?? 'indore';
const LOCALITY_CITY = process.env.P4_LOCALITY_CITY ?? 'Indore';
const LOCALITY_TERM = process.env.P4_LOCALITY_TERM ?? 'Vijay';

let checks = 0;
let failures = 0;
const check = (label, condition, detail) => {
  checks += 1;
  if (condition) console.log(`  PASS  ${label}`);
  else {
    failures += 1;
    console.log(`  FAIL  ${label}${detail === undefined ? '' : `  (${detail})`}`);
  }
};
const stage = (name) => console.log(`\n=== ${name} ===`);
const suffix = () => randomUUID().replace(/-/g, '').slice(0, 10);

const api = async (method, path, token, body, headers = {}) => {
  const request = {
    method,
    headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers }
  };
  if (body !== undefined) {
    request.headers['Content-Type'] = 'application/json';
    request.body = JSON.stringify(body);
  }
  return bound.request(method, path, token, body, headers);
};
const multipart = async (path, token, form) => {
  return bound.request('POST', path, token, form);
};

const register = async (email, fullName, phoneNumber) => {
  const created = await api('POST', '/auth/register', null, {
    email,
    password: STAFF_PASSWORD,
    fullName,
    phoneNumber
  });
  if (created.status !== 200 || !created.json?.token) {
    throw new Error(`registration failed for ${email} (HTTP ${created.status}): ${created.text}`);
  }
  return {
    email,
    password: STAFF_PASSWORD,
    userId: created.json.userId,
    role: created.json.role,
    token: created.json.token
  };
};
const signIn = async (identity) => {
  const session = await api('POST', '/auth/login', null, { email: identity.email, password: identity.password });
  if (session.status !== 200 || !session.json?.token) {
    throw new Error(`sign-in failed for ${identity.email} (HTTP ${session.status})`);
  }
  return { ...identity, token: session.json.token };
};

/** Grants a capability through the genuine staff-administration API and returns the grant id. */
const grant = async (provisioner, userId, body) => {
  const created = await api('POST', `/staff/admin/users/${userId}/grants`, provisioner.token, body);
  if (created.status !== 200) throw new Error(`grant failed (HTTP ${created.status}): ${created.text}`);
  return created.json.id;
};
const ensureStaff = async (provisioner, userId) => {
  const state = await api('GET', `/staff/admin/users/${userId}`, provisioner.token);
  if (state.status !== 200) throw new Error(`staff inspection failed (HTTP ${state.status})`);
  if (state.json.staffActive === true) return;
  const activated = await api('POST', `/staff/admin/users/${userId}/activate`, provisioner.token, {
    expectedVersion: state.json.version ?? null,
    reasonCode: 'P4_JOURNEY_ACTIVATION'
  });
  if (activated.status !== 200) throw new Error(`staff activation failed (HTTP ${activated.status})`);
};

const run = async () => {
  console.log('Pathome genuine lessor -> tenant -> OE journey');
  console.log(`backend: ${BACKEND}\n`);
  const tag = suffix();

  stage('0. environment and designated target');
  const anonymous = await api('GET', '/staff/me/capabilities', null);
  check('the backend answers an anonymous capability read with 401', anonymous.status === 401, `${anonymous.status}`);
  const admin = await signIn(ADMIN);
  check('the disposable bootstrap administrator signs in', typeof admin.token === 'string' && admin.token.length > 20);
  const cities = await api('GET', '/staff/admin/operating-teams/cities?page=0&size=50', admin.token);
  check('the designated database answers with its own active canonical cities', cities.status === 200,
    `${cities.status} ${cities.text}`);
  const city = (cities.json?.items ?? []).find((item) => item.code === CITY_CODE);
  check(`the canonical "${CITY_CODE}" City is active with a positive version`,
    Boolean(city) && city.active === true && Number(city.version) >= 0,
    JSON.stringify(cities.json?.items ?? []));
  if (!city) throw new Error('the designated database has no active canonical City to run the journey in');
  check('the canonical City read is never cached', String(cities.cacheControl).includes('no-store'),
    cities.cacheControl);

  stage('1. lessor registration (genuine self-service account)');
  const lessorEmail = `codex.p4.lessor.${tag}@pathome.test`;
  const lessor = await register(lessorEmail, 'Codex P4 Lessor', '+91 98765 ' + String(10000 + (checks % 80000)));
  check('a fresh lessor account is created through public registration', Number(lessor.userId) > 0, lessorEmail);
  check('public registration grants no role beyond the tenant identity', lessor.role === 'ROLE_TENANT', lessor.role);
  const capability = await api('GET', '/lessor/capability', lessor.token);
  check('the new account can open lessor onboarding', capability.status === 200, `${capability.status}`);
  check('opening onboarding does not itself grant the lessor capability',
    capability.json?.enabled === false && capability.json?.hasLessorProfile === false,
    JSON.stringify(capability.json));

  stage('2. property draft (city, locality, pricing, details)');
  const draft = await api('POST', '/lessor/properties/drafts', lessor.token, {
    propertyType: 'FLAT',
    rentalMode: 'LONG_TERM_RENTAL',
    bhkCount: '2BHK'
  });
  check('a draft is created', draft.status === 200 && typeof draft.json?.draftId === 'string',
    `${draft.status} ${draft.text}`);
  const draftId = draft.json.draftId;

  const applySection = async (section, payload) => {
    const current = await api('GET', `/lessor/properties/drafts/${draftId}`, lessor.token);
    const patched = await api('PATCH', `/lessor/properties/drafts/${draftId}/sections/${section}`, lessor.token,
      payload, { 'If-Match': String(current.json.version) });
    return patched;
  };
  const pricing = await applySection('pricing', { monthlyRent: 19500, securityDeposit: 39000 });
  check('pricing is saved', pricing.status === 200, `${pricing.status} ${pricing.text}`);
  const location = await applySection('location', {
    city: LOCALITY_CITY,
    canonicalLocalityId: null,
    localityInput: `${LOCALITY_TERM} Nagar`,
    address: 'P4 journey private address',
    landmark: 'P4 journey landmark',
    resolutionType: 'MANUAL_PENDING',
    provider: null,
    providerPlaceId: null,
    selectionToken: null
  });
  check('an unresolved locality is saved for administrator confirmation',
    location.status === 200 && location.json?.data?.location?.canonicalLocalityId === null,
    `${location.status} ${location.text}`);
  const details = await applySection('details', {
    availableFrom: '2026-11-01',
    furnishingStatus: 'SEMI_FURNISHED',
    totalAreaSqFt: 1150,
    floorNumber: 3,
    totalFloors: 7,
    amenities: 'Lift, Covered parking',
    description: 'P4 journey genuine submission.'
  });
  check('availability and details are saved', details.status === 200, `${details.status} ${details.text}`);

  stage('3. canonical locality discovery (lessor suggestion API)');
  const suggestions = await api('GET',
    `/lessor/locations/suggestions?city=${encodeURIComponent(LOCALITY_CITY)}&q=${encodeURIComponent(LOCALITY_TERM)}`,
    lessor.token);
  const canonicalLocality = (suggestions.json ?? []).find((option) => option.city === LOCALITY_CITY);
  check('the canonical "Vijay Nagar" locality is discoverable through the real API',
    suggestions.status === 200 && Boolean(canonicalLocality) && canonicalLocality.id > 0,
    `${suggestions.status} ${suggestions.text}`);

  stage('4. media (real object storage)');
  const mediaId = randomUUID();
  const bytes = await readFile(MEDIA_FILE);
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: 'image/jpeg' }), 'p4-journey-cover.jpg');
  const uploaded = await multipart(`/lessor/properties/drafts/${draftId}/media?mediaId=${mediaId}`, lessor.token,
    form);
  check('the cover photo is uploaded and stored',
    uploaded.status === 200 && uploaded.json?.status === 'UPLOADED' &&
      /^https:\/\//.test(String(uploaded.json?.url ?? '')),
    `${uploaded.status} ${uploaded.text}`);
  if (uploaded.status !== 200) {
    throw new Error('media upload did not succeed; the journey cannot continue without a genuine cover photo. '
      + `HTTP ${uploaded.status}: ${uploaded.text}`);
  }
  const covered = await api('PUT', `/lessor/properties/drafts/${draftId}/media/${mediaId}/cover`, lessor.token);
  check('the photo is designated as the cover', covered.status === 200, `${covered.status} ${covered.text}`);

  stage('5. submission');
  const preview = await api('GET', `/lessor/properties/drafts/${draftId}/preview`, lessor.token);
  check('the preview is complete enough to submit', preview.status === 200 &&
    Array.isArray(preview.json?.missingRequirements) && preview.json.missingRequirements.length === 0,
    `${preview.status} ${JSON.stringify(preview.json?.missingRequirements)}`);
  const submitted = await api('POST', `/lessor/properties/drafts/${draftId}/submit`, lessor.token);
  const listingId = submitted.json?.listingId;
  check('the submission creates a submitted listing',
    submitted.status === 200 && submitted.json?.status === 'SUBMITTED' && Number(listingId) > 0,
    `${submitted.status} ${submitted.text}`);
  const publicBefore = await api('GET', `/properties/${listingId}`, null);
  check('an unpublished submission is not publicly visible', publicBefore.status === 404, `${publicBefore.status}`);

  stage('6. authorized review and publication (live STAFF_ADMIN@GLOBAL)');
  const reviewer = await register(`codex.p4.reviewer.${tag}@pathome.test`, 'Codex P4 Reviewer',
    '+91 98765 ' + String(20000 + (checks % 70000)));
  await ensureStaff(admin, reviewer.userId);
  const reviewerGrantId = await grant(admin, reviewer.userId, {
    capability: 'STAFF_ADMIN',
    scopeType: 'GLOBAL',
    reasonCode: 'P4_REVIEW_AUTHORITY'
  });
  check('the review authority is a genuine audited global staff grant', Number(reviewerGrantId) > 0);
  const reviewerSession = await signIn(reviewer);

  const tenantProbe = await register(`codex.p4.probe.${tag}@pathome.test`, 'Codex P4 Probe',
    '+91 98765 ' + String(30000 + (checks % 60000)));
  const noGrant = await register(`codex.p4.nogrant.${tag}@pathome.test`, 'Codex P4 No Grant',
    '+91 98765 ' + String(40000 + (checks % 50000)));
  await ensureStaff(admin, noGrant.userId);

  const queueAnonymous = await api('GET', '/admin/lessor-review/listings', null);
  check('the review queue refuses an anonymous caller with 401', queueAnonymous.status === 401,
    `${queueAnonymous.status}`);
  const queueTenant = await api('GET', '/admin/lessor-review/listings', tenantProbe.token);
  check('the review queue refuses an ordinary tenant with 403', queueTenant.status === 403, `${queueTenant.status}`);
  const queueNoGrant = await api('GET', '/admin/lessor-review/listings', noGrant.token);
  check('the review queue refuses a staff profile with no grant with 403', queueNoGrant.status === 403,
    `${queueNoGrant.status}`);
  const queueStaff = await api('GET', '/admin/lessor-review/listings?page=0', reviewerSession.token);
  const queued = (queueStaff.json?.items ?? []).find((item) => item.listingId === listingId);
  check('the global staff administrator reads the real review queue containing the new submission',
    queueStaff.status === 200 && Boolean(queued) && queued.status === 'SUBMITTED',
    `${queueStaff.status} ${queueStaff.text}`);
  const detail = await api('GET', `/admin/lessor-review/listings/${listingId}`, reviewerSession.token);
  check('the review detail opens for the staff administrator',
    detail.status === 200 && detail.json?.listingId === listingId, `${detail.status} ${detail.text}`);
  const started = await api('POST', `/admin/lessor-review/listings/${listingId}/start-review`, reviewerSession.token);
  check('start review moves the listing into review',
    started.status === 200 && started.json?.status === 'UNDER_REVIEW', `${started.status} ${started.text}`);
  const premature = await api('POST', `/admin/lessor-review/listings/${listingId}/approve`, reviewerSession.token);
  check('publication without a canonical locality is refused', premature.status >= 400,
    `${premature.status} ${premature.text}`);
  const stillReview = await api('GET', `/admin/lessor-review/listings/${listingId}`, reviewerSession.token);
  check('the refused publication changed nothing', stillReview.json?.status === 'UNDER_REVIEW');
  const linked = await api('POST', `/admin/lessor-review/listings/${listingId}/link-locality`, reviewerSession.token,
    { canonicalLocalityId: canonicalLocality.id });
  check('the canonical locality is linked to the submission',
    linked.status === 200 && linked.json?.status === 'UNDER_REVIEW', `${linked.status} ${linked.text}`);
  const published = await api('POST', `/admin/lessor-review/listings/${listingId}/approve`, reviewerSession.token);
  check('publication approval publishes the listing',
    published.status === 200 && published.json?.status === 'PUBLISHED', `${published.status} ${published.text}`);
  const publicAfter = await api('GET', `/properties/${listingId}`, null);
  check('the published listing is genuinely public and active',
    publicAfter.status === 200 && publicAfter.json?.id === listingId, `${publicAfter.status} ${publicAfter.text}`);
  const retry = await api('POST', `/admin/lessor-review/listings/${listingId}/approve`, reviewerSession.token);
  check('a repeated approval is refused with a conflict', retry.status === 409, `${retry.status}`);
  const unrelatedAdmin = await api('GET', '/admin/drafts', reviewerSession.token);
  check('the staff administrator gains no general administrative access', unrelatedAdmin.status === 403,
    `${unrelatedAdmin.status}`);
  const nonApproved = await api('POST', `/admin/lessor-review/listings/${listingId}/request-changes`,
    reviewerSession.token, { note: 'Please clarify the available date.' });
  check('the review operations outside the approved four stay administrator-only', nonApproved.status === 403,
    `${nonApproved.status}`);

  stage('7. tenant Visit Request');
  const tenant = await register(`codex.p4.tenant.${tag}@pathome.test`, 'Codex P4 Tenant',
    '+91 98765 ' + String(50000 + (checks % 40000)));
  const requested = await api('POST', `/properties/${listingId}/visit-requests`, tenant.token,
    { preferredVisitTiming: 'Tomorrow 10 AM' });
  const requestId = requested.json?.requestId;
  check('the tenant creates a genuine Visit Request against the published listing',
    requested.status === 201 && Number(requestId) > 0, `${requested.status} ${requested.text}`);
  check('the Visit Request is recorded as received', requested.json?.status === 'RECEIVED',
    JSON.stringify(requested.json));

  stage('8. explicit Admin Team routing');
  const teamCode = `p4-journey-${tag}`;
  const teamCreated = await api('POST', `/staff/admin/cities/${city.id}/operating-teams`, admin.token, {
    code: teamCode,
    displayName: `P4 Journey ${tag}`,
    expectedCityVersion: city.version,
    reasonCode: 'P4_JOURNEY_TEAM'
  });
  check('a governed Operating Team is created for the journey',
    teamCreated.status === 201 && Number(teamCreated.json?.id) > 0,
    `${teamCreated.status} ${teamCreated.text}`);
  const team = teamCreated.json;
  const oe = await register(`codex.p4.oe.${tag}@pathome.test`, 'Codex P4 OE',
    '+91 98765 ' + String(60000 + (checks % 30000)));
  await ensureStaff(admin, oe.userId);
  await grant(admin, oe.userId, {
    capability: 'OPS_COORDINATE',
    scopeType: 'TEAM',
    teamId: team.id,
    reasonCode: 'P4_JOURNEY_OE'
  });
  const oeSession = await signIn(oe);
  const otherTeam = await api('POST', `/staff/admin/cities/${city.id}/operating-teams`, admin.token, {
    code: `${teamCode}-other`,
    displayName: `P4 Journey Other ${tag}`,
    expectedCityVersion: city.version,
    reasonCode: 'P4_JOURNEY_TEAM'
  });
  const otherOe = await register(`codex.p4.oeother.${tag}@pathome.test`, 'Codex P4 Other OE',
    '+91 98765 ' + String(70000 + (checks % 20000)));
  await ensureStaff(admin, otherOe.userId);
  const otherGrant = await grant(admin, otherOe.userId, {
    capability: 'OPS_COORDINATE',
    scopeType: 'TEAM',
    teamId: otherTeam.json.id,
    reasonCode: 'P4_JOURNEY_OE'
  });
  check('a second Team is created for the isolation control',
    Number(otherTeam.json?.id) > 0 && Number(otherGrant) > 0, JSON.stringify(otherTeam.json));
  const otherSession = await signIn(otherOe);

  const oeBeforeRouting = await api('GET', '/operations/claimable-work?page=0&size=20', oeSession.token);
  check('an un-routed Request is not claimable work for the Team',
    oeBeforeRouting.status === 200 &&
      !(oeBeforeRouting.json?.items ?? []).some((item) => item.targetId === requestId),
    `${oeBeforeRouting.status} ${oeBeforeRouting.text}`);

  const eligible = await api('GET', '/operations/intake-routing/visit-requests?page=0&size=100', admin.token);
  const routingRow = (eligible.json?.items ?? []).find((item) => item.requestId === requestId);
  check('the global staff administrator discovers the eligible intake',
    eligible.status === 200 && Boolean(routingRow) && routingRow.initialRoutingEligible === true,
    `${eligible.status} ${eligible.text}`);
  check('eligibility is never an automatic Team choice', routingRow?.teamReady === false);
  const candidates = await api('GET',
    `/operations/intake-routing/visit-requests/${requestId}/candidates?cityId=${city.id}`, admin.token);
  check('the candidate read returns the explicitly selected City and its eligible Teams',
    candidates.status === 200 && candidates.json?.city?.id === city.id &&
      candidates.json?.teams?.items?.length >= 2,
    `${candidates.status} ${candidates.text}`);
  check('the routing read is never cached', String(candidates.cacheControl).includes('no-store'),
    candidates.cacheControl);
  const routed = await api('POST', `/operations/intake-routing/visit-requests/${requestId}/route`, admin.token, {
    expectedRequestVersion: candidates.json.request.requestVersion,
    cityId: city.id,
    expectedCityVersion: candidates.json.city.version,
    teamId: team.id,
    expectedTeamVersion: candidates.json.teams.items.find((item) => item.id === team.id)?.version,
    reasonCode: 'INITIAL_ROUTING'
  });
  check('routing is an explicit Admin command with an explicit Team', routed.status === 204,
    `${routed.status} ${routed.text}`);
  const oeAfterRouting = await api('GET', '/operations/claimable-work?page=0&size=20', oeSession.token);
  const claimable = (oeAfterRouting.json?.items ?? []).find((item) => item.targetId === requestId);
  check('the routed Request becomes claimable work for exactly the routed Team',
    oeAfterRouting.status === 200 && Boolean(claimable) && claimable.teamId === team.id,
    `${oeAfterRouting.status} ${oeAfterRouting.text}`);
  const otherTeamView = await api('GET', '/operations/claimable-work?page=0&size=20', otherSession.token);
  check('a different Team Executive cannot see the routed Request',
    !(otherTeamView.json?.items ?? []).some((item) => item.targetId === requestId));

  stage('9. real OE login, Claim and My Work');
  const oeLogin = await api('POST', '/auth/login', null, { email: oe.email, password: oe.password });
  check('the Operations Executive signs in with its own credential', oeLogin.status === 200 &&
    typeof oeLogin.json?.token === 'string', `${oeLogin.status}`);
  const staleClaim = await api('POST', `/operations/visit-requests/${requestId}/claim`, oeSession.token,
    { expectedRequestVersion: (claimable?.expectedVersion ?? 0) + 5, reasonCode: 'CLAIM_WORK' });
  check('a claim with a stale version is refused with a conflict', staleClaim.status === 409,
    `${staleClaim.status} ${staleClaim.text}`);
  const claimed = await api('POST', `/operations/visit-requests/${requestId}/claim`, oeSession.token,
    { expectedRequestVersion: claimable.expectedVersion, reasonCode: 'CLAIM_WORK' });
  check('the Operations Executive claims the routed Request', claimed.status === 204,
    `${claimed.status} ${claimed.text}`);
  const myWork = await api('GET', '/operations/my-work?page=0&size=20&attentionOnly=false', oeSession.token);
  const owned = (myWork.json?.items ?? []).find((item) => item.targetId === requestId);
  check('My Work shows the owned Request', myWork.status === 200 && Boolean(owned),
    `${myWork.status} ${myWork.text}`);
  check('the owned view exposes the post-claim optimistic version',
    owned?.expectedVersion === claimable.expectedVersion + 1,
    `${owned?.expectedVersion} vs ${claimable?.expectedVersion}`);
  check('My Work is never cached', String(myWork.cacheControl).includes('no-store'), myWork.cacheControl);
  const otherMyWork = await api('GET', '/operations/my-work?page=0&size=20&attentionOnly=false', otherSession.token);
  check('another Team Executive does not see the owned Request',
    !(otherMyWork.json?.items ?? []).some((item) => item.targetId === requestId));
  const released = await api('POST', `/operations/visit-requests/${requestId}/release`, oeSession.token,
    { expectedRequestVersion: owned.expectedVersion, reasonCode: 'RELEASE_WORK' });
  check('the release returns the Request to claimable work', released.status === 204,
    `${released.status} ${released.text}`);
  const claimableAgain = await api('GET', '/operations/claimable-work?page=0&size=20', oeSession.token);
  check('the released Request is claimable again by its Team',
    (claimableAgain.json?.items ?? []).some((item) => item.targetId === requestId));

  const state = {
    run: tag,
    backend: BACKEND,
    cityId: city.id,
    cityCode: city.code,
    teamId: team.id,
    localityId: canonicalLocality.id,
    listingId,
    requestId,
    canonicalLocalityName: canonicalLocality.name,
    // Credentials are deliberately absent: the browser pass resolves the shared disposable password
    // from the ephemeral run environment (P4_STAFF_PASSWORD), so no secret is ever written to disk.
    identities: {
      lessor: { email: lessorEmail },
      tenant: { email: tenant.email },
      oe: { email: oe.email },
      reviewer: { email: reviewer.email }
    }
  };
  if (STATE_FILE) await writeFile(STATE_FILE, `${JSON.stringify(state, null, 2)}\n`, { mode: 0o600 });
  console.log(`\n${checks - failures}/${checks} checks passed`);
  if (failures > 0) {
    console.error(`Genuine journey: ${failures} failure(s)`);
    process.exit(1);
  }
  console.log('Genuine journey: all checks passed');
};

run().catch((error) => {
  console.error(`\nJOURNEY ABORTED: ${error.message}`);
  process.exit(2);
});
