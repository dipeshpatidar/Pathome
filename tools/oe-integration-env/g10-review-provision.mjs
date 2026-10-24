/**
 * Provisions the retained synthetic owner-review dataset through supported HTTP APIs only.
 * Credentials are read from the approved ephemeral run and are never printed or stored in Git.
 */
import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const API = `${process.env.G10_REVIEW_BACKEND ?? 'http://127.0.0.1:8080'}/api/v1`;
const ADMIN_EMAIL = process.env.OE_BOOTSTRAP_ADMIN_EMAIL;
const ADMIN_PASSWORD = process.env.OE_BOOTSTRAP_ADMIN_PASSWORD;
const STAFF_PASSWORD = process.env.OE_STAFF_PASSWORD;
const MEDIA_FILE = process.env.G10_REVIEW_MEDIA_FILE;
const STATE_FILE = process.env.G10_REVIEW_STATE_FILE;
const LOGIN_FILE = process.env.G10_REVIEW_LOGIN_FILE;

for (const [name, value] of Object.entries({
  OE_BOOTSTRAP_ADMIN_EMAIL: ADMIN_EMAIL,
  OE_BOOTSTRAP_ADMIN_PASSWORD: ADMIN_PASSWORD,
  OE_STAFF_PASSWORD: STAFF_PASSWORD,
  G10_REVIEW_MEDIA_FILE: MEDIA_FILE,
  G10_REVIEW_STATE_FILE: STATE_FILE,
  G10_REVIEW_LOGIN_FILE: LOGIN_FILE
})) {
  if (!value) throw new Error(`${name} is required`);
}

const request = async (method, path, token, body, headers = {}) => {
  const isForm = body instanceof FormData;
  const response = await fetch(`${API}${path}`, {
    method,
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body !== undefined && !isForm ? { 'Content-Type': 'application/json' } : {}),
      ...headers
    },
    body: body === undefined
      ? undefined
      : isForm
        ? body
        : JSON.stringify(body)
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    // A malformed body is reported below with status and bounded response text.
  }
  return { status: response.status, json, text };
};

const requireStatus = (response, expected, label) => {
  const accepted = Array.isArray(expected) ? expected.includes(response.status) : response.status === expected;
  if (!accepted) {
    throw new Error(`${label} failed with HTTP ${response.status}: ${response.text.slice(0, 500)}`);
  }
  return response.json;
};

const signIn = async (email, password, label) => {
  const response = await request('POST', '/auth/login', null, { email, password });
  const session = requireStatus(response, 200, `${label} sign-in`);
  return { email, userId: session.userId, token: session.token };
};

const registerOrSignIn = async (email, fullName, phoneNumber, label) => {
  const registration = await request('POST', '/auth/register', null, {
    email,
    password: STAFF_PASSWORD,
    fullName,
    phoneNumber
  });
  if (registration.status === 200) {
    return {
      email,
      userId: registration.json.userId,
      token: registration.json.token,
      fullName
    };
  }
  if (registration.json?.error !== 'EMAIL_ALREADY_REGISTERED') {
    requireStatus(registration, 200, `${label} registration`);
  }
  const session = await signIn(email, STAFF_PASSWORD, label);
  return { ...session, fullName };
};

const activateStaff = async (adminToken, user, label) => {
  const state = await request('GET', `/staff/admin/users/${user.userId}`, adminToken);
  const profile = requireStatus(state, 200, `${label} staff read`);
  if (profile.staffActive === true) return;
  requireStatus(await request('POST', `/staff/admin/users/${user.userId}/activate`, adminToken, {
    expectedVersion: profile.version ?? null,
    reasonCode: 'G10_OWNER_REVIEW_ACTIVATION'
  }), 200, `${label} activation`);
};

const ensureGrant = async (adminToken, user, spec, label) => {
  const state = await request('GET', `/staff/admin/users/${user.userId}`, adminToken);
  const profile = requireStatus(state, 200, `${label} grant read`);
  const exists = (profile.grants ?? []).some((grant) =>
    grant.capability === spec.capability
    && grant.scopeType === spec.scopeType
    && (spec.teamId === undefined || grant.teamId === spec.teamId)
    && grant.revokedAt == null);
  if (!exists) {
    requireStatus(await request('POST', `/staff/admin/users/${user.userId}/grants`, adminToken, {
      ...spec,
      reasonCode: spec.reasonCode
    }), 200, `${label} grant`);
  }
};

const createLead = async (ownerToken, city, team, index) => {
  const now = new Date();
  const followUp = index < 4
    ? new Date(now.getTime() + (index - 2) * 60 * 60 * 1000).toISOString()
    : null;
  const names = [
    'Ananya Iyer',
    'Imran Shaikh',
    'Rohit Verma',
    'Farah Khan',
    'Aditya Kulkarni',
    'Meera Joshi',
    'Kabir Sethi',
    'Nisha Rao'
  ];
  const phone = `+91988${String(22000000 + index).padStart(8, '0')}`;
  const command = {
    cityId: city.id,
    teamId: team.id,
    customerName: names[index],
    contactPhone: phone,
    customerSaid: 'Synthetic owner-review requirement. No customer contact is performed.',
    intent: 'RENT',
    propertyType: index % 3 === 0 ? null : 'Flat',
    bhk: `${2 + (index % 3)} BHK`,
    budgetMin: index % 3 === 0 ? null : 16000 + index * 1500,
    budgetMax: index % 3 === 0 ? null : 22000 + index * 1500,
    currency: 'INR',
    targetMoveInDate: null,
    furnishing: index % 2 === 0 ? 'SEMI_FURNISHED' : null,
    tenantProfile: 'Working professionals',
    parkingRequired: index % 2 === 0,
    petsAllowed: null,
    liftRequired: true,
    floorRequirement: null,
    preferredLocalityIds: [],
    followUpAt: followUp,
    followUpZoneId: followUp ? 'Asia/Kolkata' : null,
    sourceChannel: 'PHONE_CALL',
    campaignRef: null,
    referralRef: null,
    duplicateDecision: null,
    existingLeadId: null,
    expectedExistingLeadVersion: null,
    reasonCode: null
  };
  const existing = await request(
    'GET',
    `/operations/leads?q=${encodeURIComponent(names[index])}&cityId=${city.id}&teamId=${team.id}&page=0&size=20`,
    ownerToken
  );
  const page = requireStatus(existing, 200, `review Lead search ${index}`);
  if (page.items?.length) return page.items[0];
  return requireStatus(await request('POST', '/operations/leads', ownerToken, command, {
    'Idempotency-Key': randomUUID()
  }), 201, `review Lead capture ${index}`);
};

const provisionPublishedHome = async (adminToken) => {
  const lessor = await registerOrSignIn(
    'codex.g10.review.lessor@pathome.test',
    'G10 Review Lessor',
    '+91 98822 90001',
    'review lessor'
  );
  const draft = requireStatus(await request('POST', '/lessor/properties/drafts', lessor.token, {
    propertyType: 'FLAT',
    rentalMode: 'LONG_TERM_RENTAL',
    bhkCount: '2BHK'
  }), 200, 'review property draft');
  const draftId = draft.draftId;
  const patch = async (section, payload) => {
    const current = requireStatus(
      await request('GET', `/lessor/properties/drafts/${draftId}`, lessor.token),
      200,
      `${section} draft read`
    );
    return requireStatus(await request(
      'PATCH',
      `/lessor/properties/drafts/${draftId}/sections/${section}`,
      lessor.token,
      payload,
      { 'If-Match': String(current.version) }
    ), 200, `${section} draft save`);
  };
  await patch('pricing', { monthlyRent: 19500, securityDeposit: 39000 });
  await patch('location', {
    city: 'Indore',
    canonicalLocalityId: null,
    localityInput: 'Vijay Nagar',
    address: 'Synthetic review address',
    landmark: 'Synthetic review landmark',
    resolutionType: 'MANUAL_PENDING',
    provider: null,
    providerPlaceId: null,
    selectionToken: null
  });
  await patch('details', {
    availableFrom: '2026-11-01',
    furnishingStatus: 'SEMI_FURNISHED',
    totalAreaSqFt: 1150,
    floorNumber: 3,
    totalFloors: 7,
    amenities: 'Lift, Covered parking',
    description: 'Synthetic owner-review rental home. Published through the authorized review workflow.'
  });
  const suggestions = requireStatus(
    await request('GET', '/lessor/locations/suggestions?city=Indore&q=Vijay', lessor.token),
    200,
    'review locality suggestions'
  );
  const locality = (suggestions ?? []).find((item) => item.city === 'Indore');
  if (!locality) throw new Error('No canonical Indore locality was returned');

  const bytes = await readFile(MEDIA_FILE);
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: 'image/jpeg' }), 'g10-review-cover.jpg');
  const mediaId = randomUUID();
  requireStatus(await request(
    'POST',
    `/lessor/properties/drafts/${draftId}/media?mediaId=${mediaId}`,
    lessor.token,
    form
  ), 200, 'review cover upload');
  requireStatus(await request(
    'PUT',
    `/lessor/properties/drafts/${draftId}/media/${mediaId}/cover`,
    lessor.token
  ), 200, 'review cover selection');
  const submitted = requireStatus(
    await request('POST', `/lessor/properties/drafts/${draftId}/submit`, lessor.token),
    200,
    'review property submit'
  );
  const listingId = submitted.listingId;
  requireStatus(await request(
    'POST',
    `/admin/lessor-review/listings/${listingId}/start-review`,
    adminToken
  ), 200, 'review property start');
  requireStatus(await request(
    'POST',
    `/admin/lessor-review/listings/${listingId}/link-locality`,
    adminToken,
    { canonicalLocalityId: locality.id }
  ), 200, 'review property locality');
  const published = requireStatus(await request(
    'POST',
    `/admin/lessor-review/listings/${listingId}/approve`,
    adminToken
  ), 200, 'review property publish');
  return { listingId, localityId: locality.id, status: published.status };
};

const admin = await signIn(ADMIN_EMAIL, ADMIN_PASSWORD, 'bootstrap admin');
const cities = requireStatus(
  await request('GET', '/staff/admin/operating-teams/cities?page=0&size=50', admin.token),
  200,
  'review cities'
);
let city = (cities.items ?? []).find((item) => item.code === 'indore');
if (!city) throw new Error('The active Indore City was not found');

const teamsBefore = requireStatus(
  await request('GET', `/staff/admin/cities/${city.id}/operating-teams?page=0&size=100`, admin.token),
  200,
  'review teams'
);
let team = (teamsBefore.items ?? []).find((item) => item.code === 'g10-review-team');
if (!team) {
  team = requireStatus(await request(
    'POST',
    `/staff/admin/cities/${city.id}/operating-teams`,
    admin.token,
    {
      code: 'g10-review-team',
      displayName: 'North Indore Residential Coordination Team',
      expectedCityVersion: city.version,
      reasonCode: 'G10_OWNER_REVIEW'
    }
  ), 201, 'review Team');
}

const owner = await registerOrSignIn(
  'codex.g10.review.oe@pathome.test',
  'G10 Review Owner',
  '+91 98822 91001',
  'review owner'
);
const supervisor = await registerOrSignIn(
  'codex.g10.review.supervisor@pathome.test',
  'G10 Review Supervisor',
  '+91 98822 91002',
  'review supervisor'
);
const adminViewer = await registerOrSignIn(
  'codex.g10.review.admin@pathome.test',
  'G10 Review Staff Admin',
  '+91 98822 91003',
  'review diagnostic admin'
);
for (const [user, label] of [[owner, 'review owner'], [supervisor, 'review supervisor'], [adminViewer, 'review diagnostic admin']]) {
  await activateStaff(admin.token, user, label);
}
await ensureGrant(admin.token, owner, {
  capability: 'OPS_COORDINATE',
  scopeType: 'TEAM',
  teamId: team.id,
  reasonCode: 'G10_OWNER_REVIEW'
}, 'review owner');
await ensureGrant(admin.token, supervisor, {
  capability: 'OPS_SUPERVISE',
  scopeType: 'TEAM',
  teamId: team.id,
  reasonCode: 'G10_OWNER_REVIEW'
}, 'review supervisor');
await ensureGrant(admin.token, adminViewer, {
  capability: 'STAFF_ADMIN',
  scopeType: 'GLOBAL',
  reasonCode: 'G10_OWNER_REVIEW'
}, 'review diagnostic admin');

const ownerSession = await signIn(owner.email, STAFF_PASSWORD, 'review owner');
const leads = [];
for (let index = 0; index < 8; index += 1) {
  leads.push(await createLead(ownerSession.token, city, team, index));
}
let home = null;
try {
  const previous = JSON.parse(await readFile(STATE_FILE, 'utf8'));
  if (previous.publishedHome?.listingId) {
    const existingHome = await request('GET', `/properties/${previous.publishedHome.listingId}`, null);
    if (existingHome.status === 200) home = previous.publishedHome;
  }
} catch {
  // No prior retained review state is a normal first-run condition.
}
if (!home) home = await provisionPublishedHome(admin.token);
const retainedLeads = [...new Map(
  leads.map((lead) => [lead.summary?.id ?? lead.id, {
    id: lead.summary?.id ?? lead.id,
    version: lead.summary?.version ?? lead.version
  }])
).values()];
const state = {
  createdAt: new Date().toISOString(),
  database: 'pathome_oe_test_g10_20261022',
  cityId: city.id,
  teamId: team.id,
  users: {
    owner: { userId: owner.userId, email: owner.email },
    supervisor: { userId: supervisor.userId, email: supervisor.email },
    diagnosticAdmin: { userId: adminViewer.userId, email: adminViewer.email }
  },
  leads: retainedLeads,
  publishedHome: home
};
await writeFile(STATE_FILE, JSON.stringify(state, null, 2) + '\n', { mode: 0o600 });
await writeFile(LOGIN_FILE,
  `email=${owner.email}\npassword=${STAFF_PASSWORD}\npath=http://localhost:5173/operations/leads\n`,
  { mode: 0o600 });
console.log(`REVIEW DATA READY leads=${state.leads.length} listing=${home.listingId} state=${STATE_FILE}`);
