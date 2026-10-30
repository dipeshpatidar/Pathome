/**
 * Controlled Chromium evidence for G10 asynchronous corrections.
 *
 * This script serves the production build and intercepts only the API transport. Every response is
 * synthetic. A PASS here is controlled-browser evidence, never Spring/PostgreSQL or provider evidence.
 */
import { accessSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const EVIDENCE = process.env.PATHOME_FIDELITY_EVIDENCE_DIR
  ? resolve(process.env.PATHOME_FIDELITY_EVIDENCE_DIR)
  : fileURLToPath(new URL(
      '../../../tools/oe-integration-env/evidence/p1-v0-fidelity-20261022/', import.meta.url));
const BASELINE_ANCHOR = process.env.PATHOME_P1_BASELINE_ANCHOR === 'true';
const BYPASS_FINAL_GUARD = process.env.PATHOME_P1_BYPASS_FINAL_GUARD === 'true';
const C2_12_ONLY = process.env.PATHOME_C2_12_ONLY === 'true';
const C2_12_MUTATION = process.env.PATHOME_C2_12_MUTATION?.trim() ?? '';
const BASE_IDENTITY = {
  token: 'controlled-owner-token',
  id: 90,
  role: 'TENANT',
  name: 'Controlled Owner'
};
const PEER_IDENTITY = {
  token: 'controlled-peer-token',
  id: 91,
  role: 'TENANT',
  name: 'Controlled Peer'
};
const ADMIN_IDENTITY = {
  token: 'controlled-admin-token',
  id: 92,
  role: 'TENANT',
  name: 'Controlled Admin'
};
const CITY = {
  id: 7,
  name: 'Bhopal Municipal Central Residential Corridor',
  teamId: 19,
  teamName: 'North Bhopal Residential Coordination Team'
};

await mkdir(EVIDENCE, { recursive: true });

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
};

const server = createServer(async (incoming, outgoing) => {
  const url = new URL(incoming.url, 'http://g10-controlled.local');
  const file = url.pathname === '/' ? '/index.html' : url.pathname;
  try {
    const contents = await readFile(join(DIST, file));
    outgoing.writeHead(200, {
      'Content-Type': CONTENT_TYPES[extname(file)] || 'application/octet-stream'
    });
    outgoing.end(contents);
  } catch {
    const shell = await readFile(join(DIST, 'index.html'));
    outgoing.writeHead(200, { 'Content-Type': CONTENT_TYPES['.html'] });
    outgoing.end(shell);
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const BASE = `http://127.0.0.1:${server.address().port}`;

const executableCandidates = [
  process.env.PATHOME_BROWSER_CHROMIUM_EXECUTABLE,
  '/Users/dipeshpatidar/Library/Caches/ms-playwright/chromium-1200/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
].filter(Boolean);
const executablePath = executableCandidates.find((candidate) => {
  try {
    accessSync(candidate);
    return true;
  } catch {
    return false;
  }
});
const browser = await chromium.launch({
  headless: true,
  ...(executablePath ? { executablePath } : {})
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  permissions: ['clipboard-read', 'clipboard-write']
});
const waNavigations = [];
await context.route('https://wa.me/**', async (route) => {
  waNavigations.push(route.request().url());
  await route.fulfill({
    status: 200,
    contentType: 'text/html',
    body: '<!doctype html><title>Controlled WhatsApp destination</title><p>No provider request was made.</p>'
  });
});
await context.route('https://res.cloudinary.com/**', async (route) => {
  await route.fulfill({
    status: 200,
    contentType: 'image/svg+xml',
    body: '<svg xmlns="http://www.w3.org/2000/svg" width="800" height="500">'
      + '<rect width="800" height="500" fill="#dbe8df"/>'
      + '<path d="M190 360V220l210-130 210 130v140z" fill="#315d43"/>'
      + '<rect x="350" y="275" width="100" height="85" fill="#f7fbf8"/></svg>'
  });
});
const page = await context.newPage();
const checks = [];
const browserErrors = [];
const apiTrace = [];
const check = (name, passed, detail = '') => {
  checks.push({ name, passed: Boolean(passed), detail });
  process.stdout.write(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}\n`);
  if (!passed) throw new Error(`${name}${detail ? `: ${detail}` : ''}`);
};

const assertChangedShareScreenResponsive = async (screen, titleId) => {
  for (const width of [320, 375, 390, 430, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: width < 600 ? 760 : 900 });
    await page.keyboard.press('Tab');
    const layout = await page.locator(`section[aria-labelledby="${titleId}"]`).evaluate((section) => {
      const visible = (element) => element.getClientRects().length > 0;
      const controls = [...section.querySelectorAll('button, a, input, select, textarea, summary')]
        .filter(visible)
        .map((element) => {
          const rect = element.getBoundingClientRect();
          return {
            tag: element.tagName,
            text: element.textContent?.trim() ?? '',
            width: rect.width,
            height: rect.height,
            clipped: element.scrollWidth > element.clientWidth + 1,
            inside: rect.left >= -1 && rect.right <= window.innerWidth + 1
          };
        });
      const buttons = controls.filter((control) => control.text);
      const touchTargets = controls.filter((control) =>
        ['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA', 'SUMMARY'].includes(control.tag));
      const textBlocks = [...section.querySelectorAll('h2, p, dt, dd, .ld-notice')]
        .filter(visible)
        .map((element) => {
          const rect = element.getBoundingClientRect();
          return {
            text: element.textContent?.trim().slice(0, 120) ?? '',
            clipped: element.scrollWidth > element.clientWidth + 1,
            inside: rect.left >= -1 && rect.right <= window.innerWidth + 1
          };
        });
      const focusTarget = buttons.length
        ? [...section.querySelectorAll('button')].find(visible)
        : null;
      focusTarget?.focus();
      const focus = focusTarget
        ? getComputedStyle(focusTarget)
        : null;
      return {
        overflow: document.documentElement.scrollWidth > window.innerWidth,
        overflowDetail: {
          documentWidth: document.documentElement.scrollWidth,
          viewportWidth: window.innerWidth,
          offenders: [...document.querySelectorAll('body *')]
            .filter(visible)
            .map((element) => {
              const rect = element.getBoundingClientRect();
              return {
                tag: element.tagName,
                className: typeof element.className === 'string' ? element.className : '',
                text: element.textContent?.trim().slice(0, 80) ?? '',
                left: Math.round(rect.left),
                right: Math.round(rect.right),
                width: Math.round(rect.width)
              };
            })
            .filter((element) => element.left < -1 || element.right > window.innerWidth + 1)
            .slice(0, 12)
        },
        controls: controls.filter((control) => control.text),
        touchTargets,
        textBlocks,
        focusOutline: focus
          ? { style: focus.outlineStyle, width: Number.parseFloat(focus.outlineWidth) }
          : null
      };
    });
    check(`${screen} screen has no horizontal overflow at ${width}px`,
      !layout.overflow,
      JSON.stringify(layout.overflowDetail));
    check(`${screen} copy wraps without clipping at ${width}px`,
      layout.textBlocks.every((block) => !block.clipped && block.inside),
      JSON.stringify(layout.textBlocks));
    check(`${screen} controls remain visible, readable and touch-sized at ${width}px`,
      layout.controls.every((control) => !control.clipped && control.inside)
      && layout.touchTargets.every((control) => control.height >= 43),
      JSON.stringify({ controls: layout.controls, touchTargets: layout.touchTargets }));
    check(`${screen} controls retain a visible keyboard focus at ${width}px`,
      layout.focusOutline?.style !== 'none' && layout.focusOutline?.width >= 2,
      JSON.stringify(layout.focusOutline));
  }
  await page.setViewportSize({ width: 1440, height: 900 });
};

const identityStorage = (identity) => [
  ['pathome_auth_token', identity.token],
  ['pathome_role', identity.role],
  ['pathome_user', JSON.stringify({
    id: identity.id,
    role: identity.role,
    fullName: identity.name,
    email: `controlled-${identity.id}@pathome.test`
  })]
];
await page.addInitScript((entries) => {
  if (!window.localStorage.getItem('pathome_auth_token')) {
    entries.forEach(([key, value]) => window.localStorage.setItem(key, value));
  }
  Object.defineProperty(window, '__clipboardWrites', {
    configurable: true,
    writable: true,
    value: 0
  });
  Object.defineProperty(window, '__clipboardAttempts', {
    configurable: true,
    writable: true,
    value: 0
  });
  const clipboard = navigator.clipboard;
  if (!clipboard) return;
  const original = clipboard.writeText.bind(clipboard);
  try {
    Object.defineProperty(clipboard, 'writeText', {
      configurable: true,
      value: async (text) => {
        window.__clipboardAttempts += 1;
        if (window.__pathomeClipboardGate) await window.__pathomeClipboardGate;
        if (window.__pathomeRejectClipboard) {
          window.__pathomeRejectClipboard = false;
          throw new DOMException('Controlled clipboard denial', 'NotAllowedError');
        }
        window.__clipboardWrites += 1;
        return original(text);
      }
    });
  } catch {
    // Permissions still allow the production clipboard call if this wrapper cannot be installed.
  }
  const open = window.open.bind(window);
  window.open = (...args) => window.__pathomeBlockPopup ? null : open(...args);
}, identityStorage(BASE_IDENTITY));
page.on('console', (message) => {
  if (message.type() === 'error' && !message.text().startsWith('Failed to load resource:')) {
    browserErrors.push(message.text());
  }
});
page.on('pageerror', (error) => browserErrors.push(error.message));

let leadVersion = 7;
let preferenceRevision = 0;
let consentStatus = 'GRANTED';
let destinationChoice = 'SAME';
let consentReceipts = new Map();
let consentFailureStatus = null;
let consentNetworkFailure = false;
let consentDropAcknowledgement = false;
let consentRevokeAfterCommit = false;
let consentPosts = 0;
let shortlistVersion = 0;
let shortlistListingIds = [];
let sharePrepared = false;
let sharePreparePosts = 0;
let sharePrepareFailureStatus = null;
let sharePrepareFailureBody = null;
let sharePrepareDropAcknowledgement = false;
let shareReceipts = new Map();
let sharePrepareOperationIds = [];
let heldPublicPreview = null;
let publicPreview203Observed = false;
let previewPropertyId = 201;
let publicFlags = {
  currentFactsMatch: true,
  currentConsentMatches: true,
  currentDestinationMatches: true,
  currentAuthorityMatches: true,
  actionReady: true
};
let shareEvents = [];
let lostEventAcknowledgement = false;
let malformedEventSuccess = false;
let duplicateGate = null;
let discoveryGate = null;
let capturePosts = 0;
let capturePayloads = [];
let captureDropAcknowledgement = false;
let captureReceiptGets = 0;
const captureReceipts = new Set();
const captureOperationIds = [];
let captureAcknowledgementMode = 'normal';
let captureReceiptDeferred = false;
const captureReceiptChecks = [];
let shortlistPosts = 0;
let shortlistDropAcknowledgement = false;
let shortlistReceiptGets = 0;
const shortlistReceipts = new Set();
const shortlistOperationIds = [];
let shortlistAcknowledgementMode = 'normal';
let shortlistReceiptDeferred = false;
const shortlistReceiptChecks = [];
let manualAttached = false;
let shareEventPosts = 0;
const shareEventOperationIds = [];
let handoffEventPosts = 0;
let clipboardBefore = 0;
let detailGate = null;
let eventGate = null;
let eventGateReached = false;
let eventFailureStatus = null;
let eventNetworkFailure = false;
let shareHistoryFailureStatus = null;
let failHistoryAfterEventAcknowledgement = false;
let shareHistoryFailuresServed = 0;
let recoveryHistoryItem = null;
const shareHistoryReads = [];
let c2PrivacyEvidence = null;

const summary = () => ({
  id: 41,
  version: leadVersion,
  customerName: 'Controlled Customer',
  maskedContact: '********3210',
  status: 'QUALIFYING',
  intent: 'RENT',
  cityId: CITY.id,
  cityName: CITY.name,
  teamId: CITY.teamId,
  teamName: CITY.teamName,
  ownerUserId: BASE_IDENTITY.id,
  ownerDisplayName: BASE_IDENTITY.name,
  followUpAt: '2026-10-23T10:30:00Z',
  followUpZoneId: 'Asia/Kolkata',
  lastActivityAt: '2026-10-22T10:00:00Z',
  qualificationGaps: ['BUDGET', 'PROPERTY_TYPE']
});

const detail = () => ({
  summary: summary(),
  customerSaid: 'Controlled requirement',
  propertyType: 'Flat',
  bhk: '2 BHK',
  budgetMin: '18000.00',
  budgetMax: '24000.00',
  currency: 'INR',
  targetMoveInDate: null,
  furnishing: null,
  tenantProfile: null,
  parkingRequired: null,
  petsAllowed: null,
  liftRequired: null,
  floorRequirement: null,
  preferredLocalityIds: [],
  sourceAttributions: manualAttached ? [{
    sourceEventId: 'controlled-source-event',
    eventKind: 'MANUAL_PHONE',
    channel: 'PHONE_CALL',
    campaignRef: null,
    referralRef: null,
    receivedAt: '2026-10-22T10:00:00Z',
    state: 'PROCESSED',
    fingerprintVersion: 'MANUAL_ATTACHED_V1',
    manualEnquiry: {
      eventKind: 'MANUAL_PHONE',
      channel: 'PHONE_CALL',
      capturedAt: '2026-10-22T10:00:00Z',
      state: 'PROCESSED',
      snapshotVersion: 'MANUAL_ENQUIRY_ATTACHED_V1',
      protectedPayloadRetained: true
    }
  }] : [],
  communicationPrerequisites: [],
  conversionPrerequisites: [],
  linkedCustomerAccount: false,
  provenance: 'VERIFIED_MANUAL',
  captureOutcome: null
});

const preference = () => ({
  leadId: 41,
  revision: preferenceRevision,
  leadVersion,
  channel: destinationChoice === 'NONE' ? 'OTHER' : 'WHATSAPP',
  destinationChoice,
  maskedDestination: destinationChoice === 'NONE' ? null : '********3210',
  consentStatus,
  consentPurpose: 'PROPERTY_SHARING',
  consentWordingVersion: 'property-sharing-v1',
  evidenceMethod: 'CUSTOMER_VERBAL',
  recordedAt: '2026-10-22T10:05:00Z'
});

const shortlist = () => ({
  id: 7,
  version: shortlistVersion,
  leadVersion,
  listingIds: shortlistListingIds,
  createdAt: '2026-10-22T10:06:00Z',
  updatedAt: '2026-10-22T10:06:00Z'
});

const share = () => ({
  id: '11111111-1111-4111-8111-111111111111',
  leadId: 41,
  shortlistId: 7,
  shortlistVersion,
  contactPreferenceRevision: preferenceRevision,
  mode: destinationChoice === 'NONE' ? 'COPY_LINKS' : 'MANUAL_BUSINESS_HANDOFF',
  publicLinks: [`https://pathome.test/property/${previewPropertyId}`],
  previewText: '🏡 Your shortlisted home from Pathome\n'
    + 'Fresh public home\n'
    + 'Arera Colony, Bhopal Municipal Central Residential Corridor\n'
    + 'Rent: INR 1,23,45,678.99 / month · 2 BHK · 850 sq ft\n'
    + `View property details: https://pathome.test/property/${previewPropertyId}\n`
    + 'Interested? Open Pathome to request a visit.\n'
    + 'Pathome — Your Dreams, Our Efforts.',
  maskedDestination: '********3210',
  recordedAt: '2026-10-22T10:07:00Z'
});

const historyItem = () => ({
  id: share().id,
  shortlistId: 7,
  shortlistVersion,
  contactPreferenceRevision: preferenceRevision,
  mode: destinationChoice === 'NONE' ? 'COPY_LINKS' : 'MANUAL_BUSINESS_HANDOFF',
  publicLinks: share().publicLinks,
  previewText: share().previewText,
  maskedDestination: '********3210',
  recordedAt: share().recordedAt,
  events: [...shareEvents],
  ...publicFlags
});

const pageEnvelope = (items) => ({
  items,
  totalElements: items.length,
  page: 0,
  size: 20,
  totalPages: items.length ? 1 : 0
});

const json = (route, status, body) => route.fulfill({
  status,
  contentType: 'application/json',
  headers: { 'Cache-Control': 'no-store' },
  body: JSON.stringify(body)
});

const property = (id, title) => ({
  id,
  title,
  listingType: 'RENT',
  propertyType: 'FLAT',
  city: CITY.name,
  sector: 'Arera Colony',
  bhk: '2 BHK',
  monthlyRent: 12345678.99,
  securityDeposit: 42000,
  coverImageUrl: null,
  furnishingStatus: 'SEMI_FURNISHED'
});

const publicPropertyDetail = () => ({
  ...property(201, 'Fresh public home'),
  totalAreaSqFt: 850,
  media: [{
    mediaUrl: 'https://res.cloudinary.com/pathome/image/upload/public-cover.jpg',
    mediaType: 'IMAGE',
    roomTag: 'EXTERIOR',
    primaryCover: true,
    caption: null,
    sortOrder: 0
  }]
});

await page.route('**/api/v1/**', async (route) => {
  const request = route.request();
  const url = new URL(request.url());
  const path = url.pathname;
  const method = request.method();
  apiTrace.push({ method, path });
  if (path === '/api/v1/staff/me/capabilities') {
    const isAdmin = (request.headers().authorization ?? '').includes(ADMIN_IDENTITY.token);
    return json(route, 200, {
      staffActive: true,
      serverTime: '2026-10-22T10:00:00Z',
      capabilities: isAdmin ? [{
        capability: 'STAFF_ADMIN',
        scopeType: 'GLOBAL',
        cityId: null,
        cityDisplayName: null,
        teamId: null,
        teamDisplayName: null
      }] : [{
        capability: 'OPS_COORDINATE',
        scopeType: 'TEAM',
        cityId: CITY.id,
        cityDisplayName: CITY.name,
        teamId: CITY.teamId,
        teamDisplayName: CITY.teamName
      }]
    });
  }
  if (path === '/api/v1/operations/leads/source-exceptions' && method === 'GET') {
    return json(route, 200, pageEnvelope([{
      eventId: '33333333-3333-4333-8333-333333333333',
      eventKind: 'META_LEADGEN',
      sourceChannel: 'META',
      receivedAt: '2026-10-22T10:00:00Z',
      state: 'QUARANTINED',
      attempts: 1,
      errorCode: 'SOURCE_EXTRACTION_FAILED',
      fingerprintVersion: 'EVENT_CANONICAL_V2',
      attachedLeadId: null,
      resolutionLeadId: null,
      extractedPayloadRetained: true
    }]));
  }
  if (path === '/api/v1/operations/leads' && method === 'GET') {
    return json(route, 200, pageEnvelope([summary()]));
  }
  if (path === '/api/v1/operations/leads' && method === 'POST') {
    const operationId = request.headers()['idempotency-key'];
    if (!operationId) return json(route, 400, { message: 'Idempotency-Key is required' });
    capturePosts += 1;
    const body = request.postDataJSON();
    captureOperationIds.push(operationId);
    capturePayloads.push(body);
    manualAttached = true;
    if (!captureReceiptDeferred) captureReceipts.add(operationId);
    if (captureDropAcknowledgement || captureAcknowledgementMode === 'drop') {
      captureDropAcknowledgement = false;
      captureAcknowledgementMode = 'normal';
      return route.abort('failed');
    }
    if (captureAcknowledgementMode === 'malformed') {
      captureAcknowledgementMode = 'normal';
      return route.fulfill({
        status: 200,
        contentType: 'text/plain',
        body: 'capture-acknowledgement-not-json'
      });
    }
    if (captureAcknowledgementMode === 'bodyless') {
      captureAcknowledgementMode = 'normal';
      return route.fulfill({ status: 204 });
    }
    return json(route, 201, {
      ...detail(),
      captureOutcome: {
        resolution: 'ATTACHED_TO_EXISTING',
        leadId: 41,
        leadVersion,
        reasonCode: body.reasonCode,
        manualEnquiry: detail().sourceAttributions[0].manualEnquiry
      }
    });
  }
  if (path === '/api/v1/operations/leads/duplicate-candidates') {
    if (duplicateGate) await duplicateGate.promise;
    return json(route, 200, {
      candidates: [{
        leadId: 41,
        version: leadVersion,
        maskedContact: '********3210',
        customerName: 'Controlled Customer',
        status: 'QUALIFYING',
        cityId: CITY.id,
        teamId: CITY.teamId,
        currentlyOwnedByActor: true
      }],
      existingAccountMatchAvailable: false
    });
  }
  if (path === '/api/v1/properties' && method === 'GET') {
    const requestedPage = url.searchParams.get('page');
    if (requestedPage === '1' && discoveryGate) await discoveryGate.promise;
    if (requestedPage === '1') {
      return json(route, 200, {
        properties: [property(899, 'Stale old-page home')],
        hasMore: false,
        page: 1
      });
    }
    if (url.searchParams.get('q') === 'fresh query') {
      return json(route, 200, {
        properties: [property(201, 'Fresh public home')],
        hasMore: true,
        page: 0
      });
    }
    return json(route, 200, {
      properties: Array.from({ length: 6 }, (_, index) =>
        property(101 + index, `Initial public home ${index + 1}`)),
      hasMore: true,
      page: 0
    });
  }
  if (/^\/api\/v1\/properties\/(?:201|203)$/.test(path) && method === 'GET') {
    const requestedPropertyId = Number(path.split('/').pop());
    if (requestedPropertyId === 203 && !publicPreview203Observed) {
      publicPreview203Observed = true;
      await new Promise((resolve) => {
        heldPublicPreview = { route, resolve };
      });
      try {
        return await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'expired preview attempt' })
        });
      } catch {
        return undefined;
      }
    }
    return json(route, 200, requestedPropertyId === 201
      ? publicPropertyDetail()
      : { ...publicPropertyDetail(), id: requestedPropertyId });
  }
  if (path === '/api/v1/properties/202' && method === 'GET') {
    return json(route, 200, {
      ...publicPropertyDetail(),
      id: 202,
      title: 'Second public home',
      sector: 'Nipania'
    });
  }
  if (path === '/api/v1/operations/leads/41' && method === 'GET') {
    const authorization = request.headers().authorization ?? '';
    if (authorization.includes(BASE_IDENTITY.token) && detailGate) {
      await detailGate.promise;
    }
    if (authorization.includes(PEER_IDENTITY.token)) {
      return json(route, 404, { message: 'Lead not found' });
    }
    return json(route, 200, detail());
  }
  if (path === '/api/v1/operations/leads/41/activities') {
    return json(route, 200, pageEnvelope(manualAttached ? [{
      id: 1,
      type: 'MANUAL_ENQUIRY_ATTACHED',
      occurredAt: '2026-10-22T10:00:00Z',
      note: null
    }] : []));
  }
  if (path === '/api/v1/operations/leads/41/contact-preferences' && method === 'GET') {
    return json(route, 200, preferenceRevision ? [preference()] : []);
  }
  if (path === '/api/v1/operations/leads/41/contact-preferences' && method === 'POST') {
    const body = request.postDataJSON();
    const operationId = request.headers()['idempotency-key'];
    if (!operationId) return json(route, 400, { message: 'Idempotency-Key is required' });
    if (consentNetworkFailure) {
      consentNetworkFailure = false;
      return route.abort('failed');
    }
    if (consentFailureStatus) {
      const status = consentFailureStatus;
      consentFailureStatus = null;
      return json(route, status, { message: `Controlled consent service response ${status}` });
    }
    const replay = consentReceipts.get(operationId);
    if (replay) return json(route, 201, replay.preference);
    consentPosts += 1;
    preferenceRevision += 1;
    leadVersion += 1;
    consentStatus = body.consentStatus;
    destinationChoice = body.destinationChoice;
    const recorded = preference();
    consentReceipts.set(operationId, { preference: recorded, revision: preferenceRevision });
    if (consentRevokeAfterCommit) {
      consentRevokeAfterCommit = false;
      consentStatus = 'REVOKED';
      destinationChoice = 'NONE';
      preferenceRevision += 1;
      leadVersion += 1;
    }
    if (consentDropAcknowledgement) {
      consentDropAcknowledgement = false;
      return route.abort('failed');
    }
    return json(route, 201, recorded);
  }
  if (path.startsWith('/api/v1/operations/lead-commands/') && method === 'GET') {
    const operationId = path.split('/').pop();
    const action = url.searchParams.get('action');
    if (action === 'LEAD_CAPTURE') {
      captureReceiptGets += 1;
      captureReceiptChecks.push(operationId);
      return json(route, 200, captureReceipts.has(operationId)
        ? {
            state: 'RECORDED',
            action,
            resultReference: '41',
            recordedAt: '2026-10-22T10:00:00Z'
          }
        : { state: 'NOT_RECORDED', action, resultReference: null, recordedAt: null });
    }
    if (action === 'LEAD_SHORTLIST_CREATE') {
      shortlistReceiptGets += 1;
      shortlistReceiptChecks.push(operationId);
      return json(route, 200, shortlistReceipts.has(operationId)
        ? {
            state: 'RECORDED',
            action,
            resultReference: '7',
            recordedAt: '2026-10-22T10:06:00Z'
          }
        : { state: 'NOT_RECORDED', action, resultReference: null, recordedAt: null });
    }
    if (action === 'LEAD_SHARE_PREPARE') {
      return json(route, 200, shareReceipts.get(operationId)
        ? {
            state: 'RECORDED',
            action,
            resultReference: share().id,
            recordedAt: share().recordedAt
          }
        : { state: 'NOT_RECORDED', action, resultReference: null, recordedAt: null });
    }
    const recorded = consentReceipts.get(operationId);
    return json(route, 200, recorded
      ? {
          state: 'RECORDED',
          action,
          resultReference: `preference:${recorded.revision}:revision:${recorded.revision}:leadVersion:${recorded.preference.leadVersion}`,
          recordedAt: recorded.preference.recordedAt
        }
      : { state: 'NOT_RECORDED', action, resultReference: null, recordedAt: null });
  }
  if (path === '/api/v1/operations/leads/41/contact-preferences/1/destination'
    || path === `/api/v1/operations/leads/41/contact-preferences/${preferenceRevision}/destination`) {
    return json(route, 200, {
      leadId: 41,
      revision: preferenceRevision,
      fullDestination: '+919881122447',
      recordedAt: '2026-10-22T10:05:00Z'
    });
  }
  if (path === '/api/v1/operations/leads/41/shortlists' && method === 'POST') {
    const operationId = request.headers()['idempotency-key'];
    if (!operationId) return json(route, 400, { message: 'Idempotency-Key is required' });
    shortlistPosts += 1;
    const body = request.postDataJSON();
    shortlistOperationIds.push(operationId);
    shortlistListingIds = body.listingIds;
    shortlistVersion = 0;
    if (!shortlistReceiptDeferred) shortlistReceipts.add(operationId);
    if (shortlistDropAcknowledgement || shortlistAcknowledgementMode === 'drop') {
      shortlistDropAcknowledgement = false;
      shortlistAcknowledgementMode = 'normal';
      return route.abort('failed');
    }
    if (shortlistAcknowledgementMode === 'malformed') {
      shortlistAcknowledgementMode = 'normal';
      return route.fulfill({
        status: 200,
        contentType: 'text/plain',
        body: 'shortlist-acknowledgement-not-json'
      });
    }
    if (shortlistAcknowledgementMode === 'bodyless') {
      shortlistAcknowledgementMode = 'normal';
      return route.fulfill({ status: 204 });
    }
    return json(route, 201, shortlist());
  }
  if (path === '/api/v1/operations/leads/41/shortlists/7' && method === 'PUT') {
    const body = request.postDataJSON();
    shortlistListingIds = body.listingIds;
    shortlistVersion += 1;
    return json(route, 200, shortlist());
  }
  if (path === '/api/v1/operations/leads/41/shortlists/7' && method === 'GET') {
    return json(route, 200, shortlist());
  }
  if (path === '/api/v1/operations/leads/41/shares' && method === 'GET') {
    shareHistoryReads.push(url.search);
    if (shareHistoryFailureStatus) {
      const status = shareHistoryFailureStatus;
      shareHistoryFailureStatus = null;
      shareHistoryFailuresServed += 1;
      return json(route, status, {
        message: `Controlled share history response ${status}`
      });
    }
    return json(route, 200, pageEnvelope([
      ...(sharePrepared ? [historyItem()] : []),
      ...(recoveryHistoryItem ? [recoveryHistoryItem] : [])
    ]));
  }
  if (path === '/api/v1/operations/leads/41/shares' && method === 'POST') {
    sharePreparePosts += 1;
    const operationId = request.postDataJSON().operationId;
    sharePrepareOperationIds.push(operationId);
    if (sharePrepareFailureStatus) {
      const status = sharePrepareFailureStatus;
      sharePrepareFailureStatus = null;
      const body = sharePrepareFailureBody
        ?? { message: `Controlled property message response ${status}` };
      sharePrepareFailureBody = null;
      return json(route, status, body);
    }
    sharePrepared = true;
    shareEvents = [];
    shareReceipts.set(operationId, true);
    if (sharePrepareDropAcknowledgement) {
      sharePrepareDropAcknowledgement = false;
      return route.abort('failed');
    }
    return json(route, 201, share());
  }
  if (path === `/api/v1/operations/leads/41/shares/${share().id}/events` && method === 'POST') {
    shareEventPosts += 1;
    const body = request.postDataJSON();
    shareEventOperationIds.push(body.eventId);
    if (body.type === 'HANDOFF_ATTEMPTED') handoffEventPosts += 1;
    if (eventGate) {
      eventGateReached = true;
      await eventGate.promise;
    }
    if (eventNetworkFailure) {
      eventNetworkFailure = false;
      return route.abort('failed');
    }
    if (eventFailureStatus) {
      const status = eventFailureStatus;
      eventFailureStatus = null;
      return json(route, status, { message: `Controlled final authorization ${status}` });
    }
    if ((body.type === 'HANDOFF_ATTEMPTED' || body.type === 'CLIPBOARD_COPIED')
        && !BYPASS_FINAL_GUARD
        && (!publicFlags.actionReady || !publicFlags.currentConsentMatches
          || !publicFlags.currentDestinationMatches
          || !publicFlags.currentFactsMatch
          || !publicFlags.currentAuthorityMatches)) {
      return json(route, 409, { message: 'Current sharing authority is no longer valid' });
    }
    shareEvents.push({
      eventId: body.eventId,
      type: body.type,
      occurredAt: '2026-10-22T10:08:00Z'
    });
    if (malformedEventSuccess) {
      malformedEventSuccess = false;
      return route.fulfill({
        status: 200,
        contentType: 'text/plain',
        body: 'not-json'
      });
    }
    if (lostEventAcknowledgement) {
      lostEventAcknowledgement = false;
      if (failHistoryAfterEventAcknowledgement) {
        failHistoryAfterEventAcknowledgement = false;
        shareHistoryFailureStatus = 503;
      }
      return json(route, 503, { message: 'Controlled lost event acknowledgement' });
    }
    return json(route, 200, {
      id: body.eventId,
      shareId: share().id,
      type: body.type,
      occurredAt: '2026-10-22T10:08:00Z'
    });
  }
  return json(route, 404, { message: 'Controlled API route not found' });
});

const openConsentForm = async () => {
  await page.goto(`${BASE}/operations/leads/41/share`);
  await page.getByRole('heading', { name: /Find current public rentals/ }).waitFor();
  await page.getByLabel('Locality or property').fill('fresh query');
  await page.getByRole('heading', { name: 'Fresh public home', exact: true }).waitFor();
  await page.screenshot({
    path: join(EVIDENCE, 'production-share-properties-1440.png'),
    fullPage: true
  });
  if (await page.getByRole('button', { name: 'Added #1', exact: true }).count() === 0) {
    await page.getByRole('button', { name: 'Add', exact: true }).click();
  }
  await page.getByRole('button', { name: 'Save shortlist and continue' }).click();
  await page.getByRole('heading', { name: 'Confirm the customer’s WhatsApp number' }).waitFor();
};

const runToHandoff = async (choice) => {
  await openConsentForm();
  if (choice === 'DIFFERENT') {
    await page.getByText('Different WhatsApp number', { exact: true }).click();
    await page.getByLabel('Country calling code').fill('+1');
    await page.getByLabel('Destination number').fill('4155550123');
  } else if (choice === 'NONE') {
    await page.getByText('No WhatsApp / agreed alternative', { exact: true }).click();
  } else {
    await page.getByText('Same as the customer’s contact number', { exact: true }).click();
  }
  await page.getByRole('checkbox', { name: /customer clearly gave this agreement/i }).check();
  await page.getByText('Verbal on this contact', { exact: true }).click();
  await page.getByRole('button', { name: 'Save permission and continue' }).click();
  await page.getByRole('heading', { name: 'Prepare the property message' }).waitFor();
  await page.getByRole('button', { name: 'Prepare property message' }).click();
  await page.getByRole('heading', {
    name: choice === 'NONE'
      ? 'Copy the message through the agreed alternative'
      : 'Open WhatsApp and send it yourself'
  }).waitFor();
};

const submitSameConsent = async () => {
  await page.getByText('Same as the customer’s contact number', { exact: true }).click();
  await page.getByRole('checkbox', { name: /customer clearly gave this agreement/i }).check();
  await page.getByText('Verbal on this contact', { exact: true }).click();
  await page.getByRole('button', { name: 'Save permission and continue' }).click();
};

const resetConsentFixture = async () => {
  preferenceRevision = 0;
  consentStatus = 'GRANTED';
  destinationChoice = 'SAME';
  shortlistVersion = 0;
  leadVersion = 7;
  sharePrepared = false;
  shareEvents = [];
  consentReceipts = new Map();
  consentFailureStatus = null;
  consentNetworkFailure = false;
  consentDropAcknowledgement = false;
  consentRevokeAfterCommit = false;
  consentPosts = 0;
  sharePreparePosts = 0;
  sharePrepareFailureStatus = null;
  sharePrepareFailureBody = null;
  sharePrepareDropAcknowledgement = false;
  shareReceipts = new Map();
  sharePrepareOperationIds = [];
  waNavigations.length = 0;
  await page.evaluate(() => {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const key = sessionStorage.key(index);
      if (key?.startsWith('pathome_oe_share_prepare_v1:')) sessionStorage.removeItem(key);
    }
  });
  await openConsentForm();
};

const openVerifiedWhatsAppDialog = async () => {
  await page.locator('.ld-handoff-actions')
    .getByRole('button', { name: 'Open WhatsApp', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Confirm the exact WhatsApp recipient' });
  await dialog.waitFor();
  await dialog.getByRole('button', { name: 'Verify current conditions' }).click();
  const finalButton = dialog.getByRole('button', { name: 'Open WhatsApp', exact: true });
  const legacyAnchor = dialog.getByRole('link', { name: /Open WhatsApp/ });
  await Promise.race([
    finalButton.waitFor().catch(() => undefined),
    legacyAnchor.waitFor().catch(() => undefined)
  ]);
  return dialog;
};

const finalHandoffAction = (dialog) => {
  const button = dialog.getByRole('button', { name: 'Open WhatsApp', exact: true });
  return button.count().then((count) => count
    ? button
    : dialog.getByRole('link', { name: /Open WhatsApp/ }));
};

const resetHandoffFixture = async (choice = 'SAME') => {
  preferenceRevision = 0;
  consentStatus = 'GRANTED';
  destinationChoice = choice;
  consentReceipts = new Map();
  consentFailureStatus = null;
  consentNetworkFailure = false;
  consentDropAcknowledgement = false;
  consentRevokeAfterCommit = false;
  shortlistVersion = 0;
  leadVersion = 7;
  sharePrepared = false;
  shareEvents = [];
  publicFlags = {
    currentFactsMatch: true,
    currentConsentMatches: true,
    currentDestinationMatches: true,
    currentAuthorityMatches: true,
    actionReady: true
  };
  await page.evaluate(() => {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const key = sessionStorage.key(index);
      if (key?.startsWith('pathome_oe_command_recovery_v1:')) {
        sessionStorage.removeItem(key);
      }
    }
  });
  await runToHandoff(choice);
};

const commandRecoveryPrefix = (identity) => {
  let hash = 0x811c9dc5;
  for (let index = 0; index < identity.token.length; index += 1) {
    hash ^= identity.token.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return `pathome_oe_command_recovery_v1:${identity.id}|${identity.role}|`
    + hash.toString(16).padStart(8, '0');
};
const RECOVERY_PREFIX = commandRecoveryPrefix(BASE_IDENTITY);
const recoveryKey = (suffix) => `${RECOVERY_PREFIX}:${suffix}`;

const clearCommandRecovery = async () => {
  await page.evaluate((prefix) => {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const key = sessionStorage.key(index);
      if (key?.startsWith(`${prefix}:`)) sessionStorage.removeItem(key);
    }
  }, RECOVERY_PREFIX);
};

const seedRecovery = async (suffix, value) => {
  const key = recoveryKey(suffix);
  await page.evaluate(({ key, value }) => {
    sessionStorage.setItem(key, typeof value === 'string' ? value : JSON.stringify(value));
  }, { key, value });
  return key;
};

const openNewCapture = async () => {
  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  await page.getByRole('button', { name: 'New enquiry' }).click();
  await page.getByRole('heading', { name: 'Capture an enquiry' }).waitFor();
};

const installIdentity = async (identity) => {
  await page.evaluate(({ identity }) => {
    window.localStorage.setItem('pathome_auth_token', identity.token);
    window.localStorage.setItem('pathome_role', identity.role);
    window.localStorage.setItem('pathome_user', JSON.stringify({
      id: identity.id,
      role: identity.role,
      fullName: identity.name,
      email: `controlled-${identity.id}@pathome.test`
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  }, { identity });
};

const clearIdentity = async () => {
  await page.evaluate(() => {
    window.localStorage.removeItem('pathome_auth_token');
    window.localStorage.removeItem('pathome_role');
    window.localStorage.removeItem('pathome_user');
    window.dispatchEvent(new Event('pathome_auth_changed'));
    window.dispatchEvent(new Event('pathome_session_expired'));
  });
};

const C2_12_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const C2_12_EVENT_TYPES = new Set([
  'CLIPBOARD_COPIED',
  'HANDOFF_ATTEMPTED',
  'OPERATOR_REPORTED_MANUAL_SEND',
  'HANDOFF_CANCELLED'
]);
const C2_12_SCHEMAS = Object.freeze({
  LEAD_CAPTURE: {
    keyPattern: /^capture$/,
    keys: ['kind', 'operationId', 'savedAtEpochMs', 'version']
  },
  LEAD_SHORTLIST_CREATE: {
    keyPattern: /^shortlist-create:([1-9]\d*)$/,
    keys: ['kind', 'leadId', 'operationId', 'savedAtEpochMs', 'version']
  },
  LEAD_SHARE_EVENT: {
    keyPattern: /^share-event:([1-9]\d*)$/,
    keys: ['eventType', 'kind', 'leadId', 'operationId', 'savedAtEpochMs', 'shareId', 'version']
  }
});
const C2_12_FIELD_MUTATIONS = Object.freeze({
  name: { key: 'customerName', value: 'C2-12 Synthetic Customer' },
  email: { key: 'email', value: 'c2-12@pathome.test' },
  token: { key: 'authToken', value: 'c2-12-controlled-token' },
  otp: { key: 'otp', value: '481516' },
  'message-body': { key: 'messageBody', value: 'C2-12 controlled message body' },
  'cross-schema': { key: 'leadId', value: 41 },
  'wrong-type': { key: 'version', value: '1' },
  nested: {
    key: 'customerIdentity',
    value: {
      customerName: 'C2-12 Synthetic Customer',
      email: 'c2-12@pathome.test',
      token: 'c2-12-controlled-token',
      otp: '481516',
      messageBody: 'C2-12 controlled message body'
    }
  }
});
const C2_12_MUTATION_NAMES = new Set([
  ...Object.keys(C2_12_FIELD_MUTATIONS),
  'malformed'
]);

if (C2_12_ONLY && C2_12_MUTATION && !C2_12_MUTATION_NAMES.has(C2_12_MUTATION)) {
  throw new Error(`Unsupported C2-12 mutation: ${C2_12_MUTATION}`);
}

const readC2RecoveryEntries = async () => page.evaluate((prefix) => {
  const entries = [];
  const keyPrefix = `${prefix}:`;
  for (let index = 0; index < sessionStorage.length; index += 1) {
    const key = sessionStorage.key(index);
    if (!key?.startsWith(keyPrefix)) continue;
    entries.push({ key, raw: sessionStorage.getItem(key) });
  }
  return entries;
}, RECOVERY_PREFIX);

const c2InspectionSideEffects = async () => ({
  capturePosts,
  shortlistPosts,
  shareEventPosts,
  clipboardAttempts: await page.evaluate(() => window.__clipboardAttempts),
  clipboardWrites: await page.evaluate(() => window.__clipboardWrites),
  whatsAppNavigations: waNavigations.length,
  apiRequests: apiTrace.length
});

const assertNoC2InspectionSideEffects = async (label, before) => {
  const after = await c2InspectionSideEffects();
  check(`C2-12 ${label} inspection adds no POST, clipboard or WhatsApp action`,
    JSON.stringify(after) === JSON.stringify(before),
    `before=${JSON.stringify(before)} after=${JSON.stringify(after)}`);
};

const installC2PersistenceMutation = async (mutation) => {
  if (!mutation) return;
  const injected = mutation === 'malformed'
    ? { malformed: true }
    : C2_12_FIELD_MUTATIONS[mutation];
  await page.evaluate(({ prefix, injected }) => {
    const original = Storage.prototype.setItem;
    window.__pathomeC2OriginalSetItem = original;
    Storage.prototype.setItem = function setItem(key, value) {
      if (this === window.sessionStorage && String(key).startsWith(`${prefix}:`)) {
        if (injected.malformed) {
          value = '{"version":1';
        } else {
          const record = JSON.parse(value);
          record[injected.key] = injected.value;
          value = JSON.stringify(record);
        }
      }
      return original.call(this, key, value);
    };
  }, { prefix: RECOVERY_PREFIX, injected });
};

const restoreC2PersistenceMutation = async () => {
  await page.evaluate(() => {
    if (!window.__pathomeC2OriginalSetItem) return;
    Storage.prototype.setItem = window.__pathomeC2OriginalSetItem;
    delete window.__pathomeC2OriginalSetItem;
  });
};

const inspectC2RecoveryEntries = (
  entries,
  {
    mutation = '',
    now = Date.now(),
    expectedOperationIds,
    requiredKinds
  }
) => {
  const failures = [];
  const counts = new Map();
  const auditEntries = [];
  const keyBodies = new Set(Object.values(C2_12_SCHEMAS).flatMap((schema) => schema.keys));

  if (!entries.length) failures.push('no recovery entry was written by the mounted handler');

  for (const entry of entries) {
    const keyPrefix = `${RECOVERY_PREFIX}:`;
    const suffix = entry.key.startsWith(keyPrefix)
      ? entry.key.slice(keyPrefix.length)
      : '<outside-session-prefix>';
    let record;
    try {
      record = JSON.parse(entry.raw);
    } catch {
      failures.push(`${suffix}: malformed JSON`);
      auditEntries.push({ keySuffix: suffix, parse: 'FAILED', keys: [], types: {} });
      continue;
    }
    if (!record || typeof record !== 'object' || Array.isArray(record)
      || Object.getPrototypeOf(record) !== Object.prototype) {
      failures.push(`${suffix}: record is not a plain object`);
      auditEntries.push({
        keySuffix: suffix,
        parse: 'PARSED',
        keys: [],
        types: { record: Array.isArray(record) ? 'array' : typeof record }
      });
      continue;
    }

    const keys = Object.keys(record).sort();
    const types = Object.fromEntries(keys.map((key) => [
      key,
      record[key] === null
        ? 'null'
        : Array.isArray(record[key])
          ? 'array'
          : typeof record[key]
    ]));
    const kind = record.kind;
    const schema = C2_12_SCHEMAS[kind];
    const audit = { keySuffix: suffix, kind: typeof kind === 'string' ? kind : null, keys, types };
    auditEntries.push(audit);
    if (!schema) {
      failures.push(`${suffix}: unexpected kind ${String(kind)}`);
      continue;
    }
    counts.set(kind, (counts.get(kind) ?? 0) + 1);

    const keyMatch = schema.keyPattern.exec(suffix);
    if (!keyMatch) {
      failures.push(`${suffix}: key does not match ${kind}`);
    }
    const expectedKeys = [...schema.keys].sort();
    const unexpectedKeys = keys.filter((key) => !keyBodies.has(key));
    const schemaExtras = keys.filter((key) => !expectedKeys.includes(key));
    const missingKeys = expectedKeys.filter((key) => !keys.includes(key));
    if (unexpectedKeys.length) {
      failures.push(`${suffix}: unexpected property ${unexpectedKeys.join(', ')}`);
    }
    if (schemaExtras.length) {
      failures.push(`${suffix}: property not allowed for ${kind}: ${schemaExtras.join(', ')}`);
    }
    if (missingKeys.length) {
      failures.push(`${suffix}: missing property ${missingKeys.join(', ')}`);
    }

    if (record.version !== 1 || !Number.isSafeInteger(record.version)) {
      failures.push(`${suffix}: version must be integer 1`);
    }
    if (typeof record.operationId !== 'string' || !C2_12_UUID.test(record.operationId)) {
      failures.push(`${suffix}: operationId must be a UUID v4`);
    } else if (record.operationId !== expectedOperationIds?.[kind]) {
      failures.push(`${suffix}: operationId does not match the actual request header`);
    }
    if (!Number.isSafeInteger(record.savedAtEpochMs)
      || record.savedAtEpochMs < now - (24 * 60 * 60 * 1000)
      || record.savedAtEpochMs > now + (60 * 1000)) {
      failures.push(`${suffix}: savedAtEpochMs is outside the 24-hour persisted bound`);
    }

    if (kind === 'LEAD_SHORTLIST_CREATE' || kind === 'LEAD_SHARE_EVENT') {
      if (!Number.isSafeInteger(record.leadId) || record.leadId <= 0) {
        failures.push(`${suffix}: leadId must be a positive safe integer`);
      }
      if (keyMatch && Number(keyMatch[1]) !== record.leadId) {
        failures.push(`${suffix}: key leadId and persisted leadId differ`);
      }
    }
    if (kind === 'LEAD_SHARE_EVENT') {
      if (typeof record.shareId !== 'string' || !C2_12_UUID.test(record.shareId)) {
        failures.push(`${suffix}: shareId must be a UUID v4`);
      }
      if (typeof record.eventType !== 'string' || !C2_12_EVENT_TYPES.has(record.eventType)) {
        failures.push(`${suffix}: eventType is not an approved share-event value`);
      }
    }
  }

  for (const kind of requiredKinds) {
    if (counts.get(kind) !== 1) {
      failures.push(`expected exactly one ${kind} entry, observed ${counts.get(kind) ?? 0}`);
    }
  }

  return {
    passed: failures.length === 0,
    detail: failures.join('; '),
    entries: auditEntries,
    allowedSchemas: Object.fromEntries(Object.entries(C2_12_SCHEMAS).map(([kind, schema]) => [
      kind,
      { keys: schema.keys, keyPattern: schema.keyPattern.source }
    ])),
    mutation: mutation || null
  };
};

const runC2_12PrivacyOracle = async () => {
  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  await clearCommandRecovery();
  const actualWrites = [];
  const expectedOperationIds = {};
  const requiredKinds = C2_12_MUTATION
    ? ['LEAD_CAPTURE']
    : ['LEAD_CAPTURE', 'LEAD_SHORTLIST_CREATE', 'LEAD_SHARE_EVENT'];

  await openNewCapture();
  await page.getByLabel('Customer name').fill('C2-12 Synthetic Customer');
  await page.getByLabel(/Source/).fill('Direct call');
  captureReceiptDeferred = true;
  captureAcknowledgementMode = 'drop';
  await installC2PersistenceMutation(C2_12_MUTATION);
  await page.getByRole('button', { name: 'Review and save enquiry' }).click();
  await page.getByText(/couldn’t confirm the save/i).waitFor();
  const captureBeforeInspection = await c2InspectionSideEffects();
  expectedOperationIds.LEAD_CAPTURE = captureOperationIds.at(-1);
  actualWrites.push(...await readC2RecoveryEntries());
  await assertNoC2InspectionSideEffects('capture', captureBeforeInspection);
  captureReceiptDeferred = false;
  captureAcknowledgementMode = 'normal';
  await restoreC2PersistenceMutation();
  await clearCommandRecovery();

  if (C2_12_MUTATION) {
    c2PrivacyEvidence = inspectC2RecoveryEntries(actualWrites, {
      mutation: C2_12_MUTATION,
      expectedOperationIds,
      requiredKinds
    });
    check(`C2-12 exact recovery schema rejects ${C2_12_MUTATION} injected at the mounted persistence boundary`,
      c2PrivacyEvidence.passed, c2PrivacyEvidence.detail);
    return;
  }

  await page.goto(`${BASE}/operations/leads/41/share`);
  await page.getByRole('heading', { name: /Find current public rentals/ }).waitFor();
  if (await page.getByRole('button', { name: 'Added #1', exact: true }).count() === 0) {
    await page.getByRole('button', { name: 'Add', exact: true }).first().click();
  }
  shortlistReceiptDeferred = true;
  shortlistAcknowledgementMode = 'drop';
  await page.getByRole('button', { name: 'Save shortlist and continue' }).click();
  await page.getByText(/couldn’t confirm the shortlist save/i).waitFor();
  const shortlistBeforeInspection = await c2InspectionSideEffects();
  expectedOperationIds.LEAD_SHORTLIST_CREATE = shortlistOperationIds.at(-1);
  actualWrites.push(...await readC2RecoveryEntries());
  await assertNoC2InspectionSideEffects('shortlist', shortlistBeforeInspection);
  shortlistReceiptDeferred = false;
  shortlistAcknowledgementMode = 'normal';
  await clearCommandRecovery();

  await resetHandoffFixture('SAME');
  await clearCommandRecovery();
  lostEventAcknowledgement = true;
  failHistoryAfterEventAcknowledgement = true;
  await page.getByRole('button', { name: 'Copy message' }).click();
  await page.getByText(/history event is not yet confirmed/i).waitFor();
  const shareEventBeforeInspection = await c2InspectionSideEffects();
  expectedOperationIds.LEAD_SHARE_EVENT = shareEventOperationIds.at(-1);
  actualWrites.push(...await readC2RecoveryEntries());
  await assertNoC2InspectionSideEffects('share event', shareEventBeforeInspection);
  await clearCommandRecovery();

  c2PrivacyEvidence = inspectC2RecoveryEntries(actualWrites, {
    expectedOperationIds,
    requiredKinds
  });
  check('C2-12 mounted handlers persist only exact approved recovery schemas',
    c2PrivacyEvidence.passed, c2PrivacyEvidence.detail);
  check('C2-12 focused mounted run has no browser errors',
    browserErrors.length === 0, browserErrors.join(' | '));
};

try {
  if (C2_12_ONLY) {
    await runC2_12PrivacyOracle();
  } else {
  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  const expiredCaptureId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  const expiredCaptureKey = await seedRecovery('capture', {
    version: 1,
    kind: 'LEAD_CAPTURE',
    operationId: expiredCaptureId,
    savedAtEpochMs: Date.now() - (24 * 60 * 60 * 1000) - 1
  });
  await openNewCapture();
  await page.getByText(/outside the 24-hour recovery window/i).waitFor();
  check('mounted capture treats an expired pending command as uncertain and blocks a replacement',
    await page.getByLabel('Customer name').isDisabled()
    && await page.getByText(/outcome is still unknown/i).isVisible()
    && capturePosts === 0
    && await page.evaluate((key) => sessionStorage.getItem(key) !== null, expiredCaptureKey));
  await page.evaluate((key) => sessionStorage.removeItem(key), expiredCaptureKey);

  for (const metadataCase of [
    {
      name: 'corrupt JSON',
      value: '{broken',
      copy: /unreadable.*unknown/i
    },
    {
      name: 'null value',
      value: 'null',
      copy: /unreadable.*unknown/i
    },
    {
      name: 'future timestamp',
      value: {
        version: 1,
        kind: 'LEAD_CAPTURE',
        operationId: 'abababab-abab-4bab-8bab-abababababab',
        savedAtEpochMs: Date.now() + (10 * 60 * 1000)
      },
      copy: /invalid timestamp/i
    }
  ]) {
    const blockedKey = await seedRecovery('capture', metadataCase.value);
    await openNewCapture();
    await page.getByText(metadataCase.copy).waitFor();
    check(`mounted capture keeps ${metadataCase.name} metadata as a fail-closed blocker`,
      capturePosts === 0
      && await page.getByLabel('Customer name').isDisabled()
      && await page.evaluate((key) => sessionStorage.getItem(key) !== null, blockedKey));
    await page.evaluate((key) => sessionStorage.removeItem(key), blockedKey);
  }

  await openNewCapture();
  await page.getByLabel('Customer name').fill('Dropped acknowledgement customer');
  await page.getByLabel(/Source/).fill('Direct call');
  captureReceiptDeferred = true;
  captureAcknowledgementMode = 'drop';
  await page.getByRole('button', { name: 'Review and save enquiry' }).click();
  await page.getByText(/couldn’t confirm the save/i).waitFor();
  const originalCaptureId = captureOperationIds.at(-1);
  await openNewCapture();
  await page.getByText(/A previous enquiry command is still unconfirmed/i).waitFor();
  await page.getByRole('button', { name: 'Check whether it was saved' }).click();
  await page.getByText(/No saved result is visible yet/i).waitFor();
  check('mounted capture remount preserves the original UUID while its receipt is temporarily absent',
    originalCaptureId
    && capturePosts === 1
    && captureReceiptChecks.at(-1) === originalCaptureId
    && await page.evaluate(({ key, expected }) => {
      const metadata = JSON.parse(sessionStorage.getItem(key) ?? 'null');
      return metadata?.operationId === expected;
    }, { key: recoveryKey('capture'), expected: originalCaptureId }));
  captureReceipts.add(originalCaptureId);
  captureReceiptDeferred = false;
  await page.getByRole('button', { name: 'Check whether it was saved' }).click();
  await page.getByRole('dialog', { name: 'Leave this unsaved enquiry?' }).waitFor();
  await page.getByRole('button', { name: 'Discard and leave' }).click();
  await page.getByRole('heading', { name: 'Controlled Customer' }).waitFor();
  check('mounted capture delayed completion recovers through the same UUID and never reposts',
    capturePosts === 1
    && captureReceiptGets === 2
    && captureReceiptChecks.every((operationId) => operationId === originalCaptureId)
    && await page.evaluate(() => Array.from({ length: sessionStorage.length },
      (_, index) => sessionStorage.key(index))
      .every((key) => !key?.startsWith('pathome_oe_command_recovery_v1:'))));
  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  capturePosts = 0;
  capturePayloads = [];
  captureReceiptGets = 0;
  captureReceipts.clear();
  manualAttached = false;
  await page.getByRole('button', { name: 'New enquiry' }).click();
  await page.getByRole('heading', { name: 'Capture an enquiry' }).waitFor();
  const capacityKeys = await page.evaluate(({ identity }) => {
    let hash = 0x811c9dc5;
    for (let index = 0; index < identity.token.length; index += 1) {
      hash ^= identity.token.charCodeAt(index);
      hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    const prefix = `pathome_oe_command_recovery_v1:${identity.id}|${identity.role}|`
      + `${hash.toString(16).padStart(8, '0')}`;
    const keys = [];
    for (let leadId = 1; leadId <= 12; leadId += 1) {
      const key = `${prefix}:shortlist-create:${leadId}`;
      sessionStorage.setItem(key, JSON.stringify({
        version: 1,
        kind: 'LEAD_SHORTLIST_CREATE',
        operationId: `cccccccc-cccc-4ccc-8ccc-${String(leadId).padStart(12, '0')}`,
        leadId,
        savedAtEpochMs: Date.now()
      }));
      keys.push(key);
    }
    return keys;
  }, { identity: BASE_IDENTITY });
  const capacityBefore = await page.evaluate((keys) =>
    Object.fromEntries(keys.map((key) => [key, sessionStorage.getItem(key)])), capacityKeys);
  const clipboardAttemptsBeforeCapacity = await page.evaluate(
    () => window.__clipboardAttempts);
  await page.getByLabel('Customer name').fill('Capacity blocked customer');
  await page.getByLabel(/Source/).fill('Direct call');
  await page.getByRole('button', { name: 'Review and save enquiry' }).click();
  await page.getByText(/Too many earlier commands/i).waitFor();
  check('mounted capture refuses the thirteenth reservation without network or clipboard side effects',
    capturePosts === 0
    && await page.evaluate(({ keys, before }) =>
      JSON.stringify(Object.fromEntries(
        keys.map((key) => [key, sessionStorage.getItem(key)]))) === JSON.stringify(before),
    { keys: capacityKeys, before: capacityBefore })
    && await page.evaluate(() => window.__clipboardAttempts) === clipboardAttemptsBeforeCapacity);
  await page.evaluate((keys) => {
    keys.forEach((key) => sessionStorage.removeItem(key));
  }, capacityKeys);

  const readRefusalRecord = {
    version: 1,
    kind: 'LEAD_CAPTURE',
    operationId: 'acacacac-acac-4cac-8cac-acacacacacac',
    savedAtEpochMs: Date.now()
  };
  const readRefusalKey = await seedRecovery('capture', readRefusalRecord);
  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  await page.evaluate(() => {
    window.__pathomeOriginalStorageGetItem = Storage.prototype.getItem;
    Storage.prototype.getItem = function getItem(key) {
      if (String(key).startsWith('pathome_oe_command_recovery_v1:')) {
        throw new DOMException('Controlled read refusal', 'SecurityError');
      }
      return window.__pathomeOriginalStorageGetItem.call(this, key);
    };
  });
  capturePosts = 0;
  captureOperationIds.length = 0;
  await page.getByRole('button', { name: 'New enquiry' }).click();
  await page.getByRole('heading', { name: 'Capture an enquiry' }).waitFor();
  await page.getByText(/could not read the saved enquiry command state/i).waitFor();
  check('mounted capture treats storage read refusal as blocked before dispatch',
    capturePosts === 0
    && await page.getByLabel('Customer name').isDisabled());
  await page.evaluate(() => {
    Storage.prototype.getItem = window.__pathomeOriginalStorageGetItem;
    delete window.__pathomeOriginalStorageGetItem;
  });
  check('storage read refusal preserves the unresolved record byte-for-byte',
    await page.evaluate(({ key, expected }) =>
      sessionStorage.getItem(key) === expected,
    { key: readRefusalKey, expected: JSON.stringify(readRefusalRecord) }));
  await page.evaluate((key) => sessionStorage.removeItem(key), readRefusalKey);
  await openNewCapture();
  await page.getByLabel('Customer name').fill('Storage write blocked customer');
  await page.getByLabel(/Source/).fill('Direct call');
  await page.evaluate(() => {
    window.__pathomeOriginalStorageSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function setItem(key, value) {
      if (String(key).startsWith('pathome_oe_command_recovery_v1:')) {
        throw new DOMException('Controlled write refusal', 'QuotaExceededError');
      }
      return window.__pathomeOriginalStorageSetItem.call(this, key, value);
    };
  });
  await page.getByRole('button', { name: 'Review and save enquiry' }).click();
  await page.getByText(/could not keep the enquiry command\. Nothing was sent/i).waitFor();
  check('mounted capture treats storage write refusal as blocked before dispatch',
    capturePosts === 0
    && await page.getByLabel('Customer name').inputValue() === 'Storage write blocked customer');
  await page.evaluate(() => {
    Storage.prototype.setItem = window.__pathomeOriginalStorageSetItem;
    delete window.__pathomeOriginalStorageSetItem;
  });

  for (const acknowledgementMode of ['bodyless', 'malformed']) {
    capturePosts = 0;
    captureReceiptGets = 0;
    captureReceipts.clear();
    captureOperationIds.length = 0;
    captureReceiptChecks.length = 0;
    captureReceiptDeferred = false;
    await openNewCapture();
    await page.getByLabel('Customer name').fill(
      `${acknowledgementMode} acknowledgement customer`);
    await page.getByLabel(/Source/).fill('Direct call');
    captureAcknowledgementMode = acknowledgementMode;
    await page.getByRole('button', { name: 'Review and save enquiry' }).click();
    await page.getByText(/couldn’t confirm the save/i).waitFor();
    const originalId = captureOperationIds.at(-1);
    await openNewCapture();
    await page.getByRole('button', { name: 'Check whether it was saved' }).click();
    await page.getByRole('dialog', { name: 'Leave this unsaved enquiry?' }).waitFor();
    await page.getByRole('button', { name: 'Discard and leave' }).click();
    await page.getByRole('heading', { name: 'Controlled Customer' }).waitFor();
    check(`mounted capture keeps a ${acknowledgementMode} 2xx uncertain and recovers without replacement`,
      originalId
      && capturePosts === 1
      && captureReceiptChecks.length === 1
      && captureReceiptChecks[0] === originalId);
  }

  capturePosts = 0;
  capturePayloads = [];
  captureReceiptGets = 0;
  captureReceipts.clear();
  captureOperationIds.length = 0;
  captureReceiptChecks.length = 0;
  manualAttached = false;
  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  await page.getByRole('button', { name: 'New enquiry' }).click();
  await page.getByRole('heading', { name: 'Capture an enquiry' }).waitFor();
  check('Lead capture mounts inside the persistent Operations sidebar shell',
    await page.locator('.oe-sidebar').isVisible()
    && await page.locator('.oe-shell').count() === 1
    && await page.locator('.ld-workspace-nav').count() === 0
    && await page.locator('.oe-sidebar-btn').filter({ hasText: 'Leads' }).getAttribute('aria-current') === 'page');
  await page.screenshot({ path: join(EVIDENCE, 'production-capture-1440.png'), fullPage: true });
  await page.getByLabel('Customer name').fill('Unsaved draft');
  await page.goBack();
  await page.getByRole('dialog', { name: 'Leave this unsaved enquiry?' }).waitFor();
  await page.getByRole('button', { name: 'Keep editing' }).click();
  check('browser Back from a dirty capture is intercepted and the draft survives Keep Editing',
    await page.getByRole('heading', { name: 'Capture an enquiry' }).isVisible()
    && await page.getByLabel('Customer name').inputValue() === 'Unsaved draft');
  await page.getByRole('button', { name: 'Back to Leads' }).click();
  await page.getByRole('dialog', { name: 'Leave this unsaved enquiry?' }).waitFor();
  await page.getByRole('button', { name: 'Keep editing' }).click();
  check('destructive capture navigation warns and keeps the draft on explicit cancel',
    await page.getByLabel('Customer name').inputValue() === 'Unsaved draft');
  await page.locator('.oe-sidebar-nav').getByRole('button', { name: 'Leads' }).click();
  await page.getByRole('dialog', { name: 'Leave this unsaved enquiry?' }).waitFor();
  await page.getByRole('button', { name: 'Keep editing' }).click();
  await page.getByLabel('Phone number').fill('+91 98811 22447');
  await page.getByLabel(/Source/).fill('Phone call');
  let releaseDuplicate;
  duplicateGate = { promise: new Promise((resolve) => { releaseDuplicate = resolve; }) };
  await page.getByRole('button', { name: 'Review and save enquiry' }).click();
  await page.getByRole('button', { name: 'Clear form' }).click();
  releaseDuplicate();
  await page.waitForTimeout(300);
  duplicateGate = null;
  check('Clear during a held duplicate lookup sends zero capture and clears private input',
    capturePosts === 0
    && await page.getByLabel('Phone number').inputValue() === ''
    && await page.getByLabel(/Source/).inputValue() === '');

  await page.getByLabel('Phone number').fill('+91 98811 22447');
  await page.getByLabel(/Source/).fill('Phone call');
  await page.getByRole('button', { name: 'Review and save enquiry' }).click();
  await page.getByRole('heading', { name: /This number appears on 1 visible Lead/ }).waitFor();
  await page.getByRole('button', { name: 'Attach this enquiry' }).click();
  await page.getByRole('button', { name: 'Attach to selected Lead' }).click();
  await page.getByRole('heading', { name: 'Controlled Customer' }).waitFor();
  check('explicit manual attach sends the selected Lead/version and displays protected evidence',
    capturePosts === 1
    && capturePayloads[0]?.duplicateDecision === 'ATTACH_ENQUIRY'
    && capturePayloads[0]?.existingLeadId === 41
    && capturePayloads[0]?.expectedExistingLeadVersion === leadVersion
    && await page.getByText('A manual enquiry is attached to this Lead',
      { exact: true }).isVisible());
  await page.screenshot({ path: join(EVIDENCE, 'production-detail-1440.png'), fullPage: true });
  const persistedGuidance = await page.locator('.ld-next').innerText();
  check('Lead guidance uses persisted gaps and follow-up without inventing a schedule',
    await page.getByText('2 details are still unknown', { exact: true }).isVisible()
    && await page.getByText('Budget and Property type. Ask on the next call. Unknown values are never guessed.',
      { exact: true }).isVisible()
    && await page.getByRole('heading', { name: 'Next action', exact: true }).isVisible()
    && persistedGuidance.includes('Ask about Budget and Property type with the customer.')
    && persistedGuidance.includes('Follow-up')
    && await page.getByText('Manual enquiry attached', { exact: true }).isVisible()
    && !/tomorrow|next week|auto.?follow/i.test(persistedGuidance));

  await page.goto(`${BASE}/operations/leads/41/share`);
  await page.getByRole('heading', { name: /Find current public rentals/ }).waitFor();
  if (await page.getByRole('button', { name: 'Added #1', exact: true }).count() === 0) {
    await page.getByRole('button', { name: 'Add', exact: true }).first().click();
  }
  shortlistPosts = 0;
  shortlistReceiptGets = 0;
  shortlistReceipts.clear();
  shortlistOperationIds.length = 0;
  shortlistReceiptChecks.length = 0;
  shortlistReceiptDeferred = true;
  shortlistAcknowledgementMode = 'drop';
  await page.getByRole('button', { name: 'Save shortlist and continue' }).click();
  await page.getByText(/couldn’t confirm the shortlist save/i).waitFor();
  const originalShortlistId = shortlistOperationIds.at(-1);
  await page.goto(`${BASE}/operations/leads/41/share?shortlist=7`);
  await page.getByRole('heading', { name: /Find current public rentals/ }).waitFor();
  await page.getByText(/A previous shortlist save is still unconfirmed/i).waitFor();
  await page.getByRole('button', { name: 'Check saved shortlist' }).click();
  await page.getByText(/No saved shortlist is visible yet/i).waitFor();
  check('mounted shortlist remount preserves the original UUID while its receipt is temporarily absent',
    originalShortlistId
    && shortlistPosts === 1
    && shortlistReceiptChecks.at(-1) === originalShortlistId
    && await page.evaluate(({ key, expected }) => {
      const metadata = JSON.parse(sessionStorage.getItem(key) ?? 'null');
      return metadata?.operationId === expected;
    }, { key: recoveryKey('shortlist-create:41'), expected: originalShortlistId }));
  shortlistReceipts.add(originalShortlistId);
  shortlistReceiptDeferred = false;
  await page.getByRole('button', { name: 'Check saved shortlist' }).click();
  await page.getByRole('heading', {
    name: 'Confirm the customer’s WhatsApp number'
  }).waitFor();
  const shortlistMarkerRemaining = await page.evaluate(() =>
    Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
      .some((key) => key?.startsWith('pathome_oe_command_recovery_v1:')));
  check('mounted shortlist delayed completion recovers through the same UUID and never reposts',
    shortlistPosts === 1
    && shortlistReceiptGets >= 2
    && shortlistReceiptChecks.every((operationId) => operationId === originalShortlistId)
    && !shortlistMarkerRemaining,
    `posts=${shortlistPosts} receipts=${shortlistReceiptGets} `
      + `marker=${shortlistMarkerRemaining}`);
  shortlistPosts = 0;
  shortlistReceiptGets = 0;
  shortlistReceipts.clear();

  const expiredShortlistKey = await page.evaluate(({ identity, operationId }) => {
    let hash = 0x811c9dc5;
    for (let index = 0; index < identity.token.length; index += 1) {
      hash ^= identity.token.charCodeAt(index);
      hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    const key = `pathome_oe_command_recovery_v1:${identity.id}|${identity.role}|`
      + `${hash.toString(16).padStart(8, '0')}:shortlist-create:41`;
    sessionStorage.setItem(key, JSON.stringify({
      version: 1,
      kind: 'LEAD_SHORTLIST_CREATE',
      operationId,
      leadId: 41,
      savedAtEpochMs: Date.now() - (24 * 60 * 60 * 1000) - 1
    }));
    return key;
  }, {
    identity: BASE_IDENTITY,
    operationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
  });
  await page.goto(`${BASE}/operations/leads/41/share?shortlist=7`);
  await page.getByRole('heading', { name: /Find current public rentals/ }).waitFor();
  await page.getByText(/outside the 24-hour recovery window/i).waitFor();
  check('mounted shortlist creation treats an expired command as uncertain and sends no replacement',
    await page.getByRole('button', { name: 'Save shortlist and continue' }).isDisabled()
    && shortlistPosts === 0);
  await page.evaluate((key) => sessionStorage.removeItem(key), expiredShortlistKey);
  await page.goto(`${BASE}/operations/leads/41/share?shortlist=7`);
  await page.getByRole('heading', { name: /Find current public rentals/ }).waitFor();
  await page.getByRole('heading', { name: 'Initial public home 1', exact: true }).waitFor();
  let releaseDiscovery;
  discoveryGate = { promise: new Promise((resolve) => { releaseDiscovery = resolve; }) };
  await page.getByRole('button', { name: 'Load more public homes' }).click();
  await page.getByLabel('Locality or property').fill('fresh query');
  await page.getByText('Fresh public home', { exact: true }).waitFor();
  releaseDiscovery();
  await page.waitForTimeout(300);
  discoveryGate = null;
  check('old Smart Search pagination cannot mix stale results into the accepted query',
    await page.getByText('Fresh public home', { exact: true }).isVisible()
    && await page.getByText('Stale old-page home', { exact: true }).count() === 0
    && await page.getByText('Initial public home 1', { exact: true }).count() === 0);

  await runToHandoff('SAME');

  await clearCommandRecovery();
  const deniedClipboardWrites = await page.evaluate(() => window.__clipboardWrites);
  const deniedClipboardAttempts = await page.evaluate(() => window.__clipboardAttempts);
  const deniedClipboardPosts = shareEventPosts;
  await page.evaluate(() => { window.__pathomeRejectClipboard = true; });
  await page.getByRole('button', { name: 'Copy message' }).click();
  await page.getByText(/Clipboard access failed/i).waitFor();
  await page.waitForTimeout(350);
  check('mounted clipboard denial removes its unused reservation and never retries automatically',
    await page.evaluate(() => window.__clipboardAttempts) === deniedClipboardAttempts + 1
    && await page.evaluate(() => window.__clipboardWrites) === deniedClipboardWrites
    && shareEventPosts === deniedClipboardPosts
    && await page.getByLabel('Message to copy').isVisible()
    && await page.evaluate((prefix) =>
      !Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
        .some((key) => key?.startsWith(`${prefix}:`)), RECOVERY_PREFIX));

  await clearCommandRecovery();
  const historyFailureClipboardWrites = await page.evaluate(() => window.__clipboardWrites);
  const historyFailurePosts = shareEventPosts;
  lostEventAcknowledgement = true;
  failHistoryAfterEventAcknowledgement = true;
  const historyFailureCountBefore = shareHistoryFailuresServed;
  await page.getByRole('button', { name: 'Copy message' }).click();
  await page.getByText(/history event is not yet confirmed/i).waitFor();
  check('a completed clipboard action with unavailable history remains truthful and unresolved',
    await page.evaluate(() => window.__clipboardWrites) === historyFailureClipboardWrites + 1
    && shareEventPosts === historyFailurePosts + 1
    && shareHistoryFailuresServed === historyFailureCountBefore + 1
    && await page.getByText(/Copy and WhatsApp stay disabled until/i).isVisible()
    && await page.getByRole('button', { name: 'Copy message' }).isDisabled(),
  `writes=${await page.evaluate(() => window.__clipboardWrites)} `
    + `expectedWrites=${historyFailureClipboardWrites + 1} `
    + `posts=${shareEventPosts} expectedPosts=${historyFailurePosts + 1} `
    + `historyFailures=${shareHistoryFailuresServed} `
    + `expectedHistoryFailures=${historyFailureCountBefore + 1}`);
  await page.getByRole('button', { name: 'Check if it was recorded' }).click();
  await page.getByText(/Share history confirmed .*No action was repeated/i).waitFor();
  check('later history availability confirms the same clipboard event without recopying',
    await page.evaluate(() => window.__clipboardWrites) === historyFailureClipboardWrites + 1
    && shareEventPosts === historyFailurePosts + 1);

  await clearCommandRecovery();
  const interruptedAttempts = await page.evaluate(() => window.__clipboardAttempts);
  const interruptedPosts = shareEventPosts;
  await page.evaluate(() => {
    window.__pathomeClipboardGate = new Promise((resolve) => {
      window.__pathomeReleaseClipboard = resolve;
    });
  });
  await page.getByRole('button', { name: 'Copy message' }).click();
  for (let attempt = 0;
    attempt < 40 && await page.evaluate(() => window.__clipboardAttempts) === interruptedAttempts;
    attempt += 1) {
    await page.waitForTimeout(50);
  }
  await page.locator('.oe-sidebar-nav').getByRole('button', { name: 'Leads' }).click();
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  await page.evaluate(() => {
    window.__pathomeReleaseClipboard?.();
    delete window.__pathomeClipboardGate;
    delete window.__pathomeReleaseClipboard;
  });
  await page.waitForTimeout(500);
  const interruptedMarker = await page.evaluate((prefix) =>
    Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
      .some((key) => key?.startsWith(`${prefix}:share-event:41`)), RECOVERY_PREFIX);
  check('unmount after reservation interrupts history recording without a speculative event POST',
    await page.evaluate(() => window.__clipboardAttempts) === interruptedAttempts + 1
    && shareEventPosts === interruptedPosts
    && interruptedMarker);

  await clearCommandRecovery();
  await runToHandoff('SAME');

  await page.evaluate(() => {
    window.__pathomeOriginalStorageSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function setItem(key, value) {
      if (String(key).startsWith('pathome_oe_command_recovery_v1:')) {
        throw new DOMException('Storage quota exceeded', 'QuotaExceededError');
      }
      return window.__pathomeOriginalStorageSetItem.call(this, key, value);
    };
  });
  clipboardBefore = await page.evaluate(() => window.__clipboardWrites);
  const eventPostsBeforeDeniedReservation = shareEventPosts;
  await page.getByRole('button', { name: 'Copy message' }).click();
  await page.getByText(/could not keep the share action reference/i).waitFor();
  check('mounted clipboard action is not attempted when its event identity cannot be reserved',
    await page.evaluate(() => window.__clipboardWrites) === clipboardBefore
    && shareEventPosts === eventPostsBeforeDeniedReservation);
  await page.evaluate(() => {
    Storage.prototype.setItem = window.__pathomeOriginalStorageSetItem;
    delete window.__pathomeOriginalStorageSetItem;
  });

  clipboardBefore = await page.evaluate(() => window.__clipboardWrites);
  const staleConsentPostsBefore = shareEventPosts;
  publicFlags = {
    currentFactsMatch: true,
    currentConsentMatches: false,
    currentDestinationMatches: false,
    currentAuthorityMatches: false,
    actionReady: false
  };
  await page.getByRole('button', { name: 'Copy message' }).click();
  await page.waitForTimeout(300);
  check('stale ownership and consent produce zero clipboard action and zero event mutation',
    await page.evaluate(() => window.__clipboardWrites) === clipboardBefore
    && shareEventPosts === staleConsentPostsBefore);
  await page.getByRole('button', { name: 'Customer withdrew permission' }).click();
  await page.getByText(/Customer withdrawal saved/).waitFor();
  check('approved withdrawal behavior persists a new non-granted revision',
    preferenceRevision >= 2 && consentStatus === 'REVOKED'
    && destinationChoice === 'NONE');

  sharePrepared = false;
  shareEvents = [];
  preferenceRevision = 0;
  consentStatus = 'GRANTED';
  destinationChoice = 'SAME';
  shortlistVersion = 0;
  shortlistListingIds = [];
  leadVersion = 7;
  publicFlags = {
    currentFactsMatch: true,
    currentConsentMatches: true,
    currentDestinationMatches: true,
    currentAuthorityMatches: true,
    actionReady: true
  };
  lostEventAcknowledgement = true;
  shareEventPosts = 0;
  previewPropertyId = 203;
  await runToHandoff('DIFFERENT');
  const publicPreview = page.locator('.ld-public-preview');
  await publicPreview.getByRole('heading', {
    name: 'The customer’s first shortlisted home'
  }).waitFor();
  await publicPreview.locator('.ld-public-preview-loading').waitFor();
  const retryPreview = publicPreview.getByRole('button', { name: 'Retry preview' });
  await retryPreview.waitFor({ timeout: 15_000 });
  check('a hung public preview leaves loading for the existing retry state at the bounded deadline',
    await retryPreview.isVisible()
    && await publicPreview.locator('.ld-public-preview-loading').count() === 0
    && await publicPreview.getByText(/could not be loaded/).isVisible());
  heldPublicPreview?.resolve();
  heldPublicPreview = null;
  await page.waitForTimeout(150);
  check('a late expired response cannot replace the recoverable timeout state',
    await retryPreview.isVisible()
    && await publicPreview.locator('.ld-public-preview-loading').count() === 0);
  await retryPreview.click();
  const previewImage = publicPreview.getByRole('img', {
    name: /Public cover photo of Fresh public home/
  });
  await previewImage.waitFor();
  check('handoff shows the genuine public primary cover and current approved facts',
    await previewImage.getAttribute('alt')
      === 'Public cover photo of Fresh public home in Arera Colony, '
        + 'Bhopal Municipal Central Residential Corridor'
    && await publicPreview.getByText(/₹1,23,45,678\.99/).isVisible()
    && await publicPreview.getByText(/Arera Colony, Bhopal/).isVisible()
    && await publicPreview.getByText(/850 sq ft/).isVisible()
    && await page.getByText(/Rent: INR 1,23,45,678\.99 \/ month/).isVisible());
  await page.setViewportSize({ width: 390, height: 844 });
  await publicPreview.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: join(EVIDENCE, 'production-handoff-preview-390.png'),
    fullPage: true
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.screenshot({ path: join(EVIDENCE, 'production-handoff-1440.png'), fullPage: true });
  clipboardBefore = await page.evaluate(() => window.__clipboardWrites);
  await page.getByRole('button', { name: 'Copy message' }).click();
  await page.getByText(/saved history confirmed the earlier event without another copy/i).waitFor();
  check('lost event acknowledgement reconciles through history without a second clipboard action',
    await page.evaluate(() => window.__clipboardWrites) === clipboardBefore + 1
    && shareEventPosts === 1
    && destinationChoice === 'DIFFERENT'
    && await page.getByRole('button', { name: 'Copy message' }).isEnabled());

  clipboardBefore = await page.evaluate(() => window.__clipboardWrites);
  const malformedPostsBefore = shareEventPosts;
  malformedEventSuccess = true;
  await page.getByRole('button', { name: 'Copy message' }).click();
  await page.getByText(/saved history confirmed the earlier event/i).waitFor();
  check('a bodyless or malformed 2xx remains uncertain and reconciles the original event identity',
    await page.evaluate(() => window.__clipboardWrites) === clipboardBefore + 1
    && shareEventPosts === malformedPostsBefore + 1
    && shareEvents.length === 2
    && await page.getByRole('button', { name: 'Copy message' }).isEnabled());

  await clearCommandRecovery();
  const sharedMessageEventCount = shareEvents.length;
  const sharedMessagePosts = shareEventPosts;
  const sharedMessageClipboard = await page.evaluate(() => window.__clipboardWrites);
  const recoveryShareId = '99999999-9999-4999-8999-999999999999';
  const recoveryEventId = '98989898-9898-4898-8898-989898989898';
  await seedRecovery('share-event:41', {
    version: 1,
    kind: 'LEAD_SHARE_EVENT',
    operationId: recoveryEventId,
    leadId: 41,
    shareId: recoveryShareId,
    eventType: 'CLIPBOARD_COPIED',
    savedAtEpochMs: Date.now()
  });
  await page.getByRole('button', { name: 'Message history' }).click();
  await page.getByRole('tab', { name: 'New message' }).click();
  await page.getByRole('heading', {
    name: 'Open WhatsApp and send it yourself'
  }).waitFor();
  await page.getByText(/another saved message is still unconfirmed/i).waitFor();
  const recoveryHistoryReads = shareHistoryReads.length;
  const recoveryHistoryFailures = shareHistoryFailuresServed;
  shareHistoryFailureStatus = 503;
  await page.getByRole('button', { name: 'Check if it was recorded' }).click();
  await page.getByText(/We could not complete this request right now|Share history could not confirm/i)
    .waitFor();
  check('displayed share B never replaces saved share A when A history is unavailable',
    shareEventPosts === sharedMessagePosts
    && await page.evaluate(() => window.__clipboardWrites) === sharedMessageClipboard
    && shareEvents.length === sharedMessageEventCount
    && shareHistoryReads.length === recoveryHistoryReads + 1
    && shareHistoryFailuresServed === recoveryHistoryFailures + 1
    && await page.evaluate(({ key, expected }) => {
      const metadata = JSON.parse(sessionStorage.getItem(key) ?? 'null');
      return metadata?.shareId === expected;
    }, { key: recoveryKey('share-event:41'), expected: recoveryShareId }));

  recoveryHistoryItem = {
    id: recoveryShareId,
    shortlistId: 8,
    shortlistVersion,
    contactPreferenceRevision: preferenceRevision,
    mode: 'MANUAL_BUSINESS_HANDOFF',
    publicLinks: ['https://pathome.test/property/209'],
    previewText: 'Saved share A message',
    maskedDestination: '********3210',
    recordedAt: '2026-10-22T10:04:00Z',
    events: [{
      eventId: recoveryEventId,
      type: 'CLIPBOARD_COPIED',
      occurredAt: '2026-10-22T10:08:30Z'
    }],
    ...publicFlags
  };
  await page.getByRole('button', { name: 'Check if it was recorded' }).click();
  await page.getByText(/Share history confirmed .*No action was repeated/i).waitFor();
  check('saved share A later confirms through A history while B remains displayed and unchanged',
    shareEventPosts === sharedMessagePosts
    && await page.evaluate(() => window.__clipboardWrites) === sharedMessageClipboard
    && shareEvents.length === sharedMessageEventCount
    && await page.evaluate((prefix) =>
      !Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
        .some((key) => key?.startsWith(`${prefix}:`)), RECOVERY_PREFIX));

  await seedRecovery('share-event:41', {
    version: 1,
    kind: 'LEAD_SHARE_EVENT',
    operationId: '97979797-9797-4797-8797-979797979797',
    leadId: 41,
    shareId: recoveryShareId,
    eventType: 'HANDOFF_ATTEMPTED',
    savedAtEpochMs: Date.now()
  });
  await installIdentity({ ...BASE_IDENTITY, token: 'replacement-token' });
  check('token replacement clears the prior authenticated-session recovery authority',
    await page.evaluate((prefix) =>
      !Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
        .some((key) => key?.startsWith(`${prefix}:`)), RECOVERY_PREFIX));
  await clearIdentity();
  check('logout clears every pending command and removes the old unresolved notice',
    await page.evaluate((prefix) =>
      !Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
        .some((key) => key?.startsWith(`${prefix}:`)), RECOVERY_PREFIX)
    && await page.getByText(/another saved message is still unconfirmed/i).count() === 0);
  await installIdentity(BASE_IDENTITY);
  await resetHandoffFixture('SAME');

  const handoffCountBefore = handoffEventPosts;
  const waCountBefore = waNavigations.length;
  let dialog = await openVerifiedWhatsAppDialog();
  const firstPopup = context.waitForEvent('page');
  if (BASELINE_ANCHOR) {
    await (await finalHandoffAction(dialog)).click();
  } else {
    await (await finalHandoffAction(dialog)).dblclick();
  }
  const popup = await firstPopup;
  await popup.waitForURL(/^https:\/\/wa\.me\//, { timeout: 10_000 });
  const openerDetached = await popup.evaluate(() => window.opener === null);
  if (!BASELINE_ANCHOR) {
    check('valid final activation reserves no usable URL, records once and opens one noopener tab',
      handoffEventPosts === handoffCountBefore + 1
      && waNavigations.length === waCountBefore + 1
      && openerDetached, `events=${handoffEventPosts - handoffCountBefore} navigations=${waNavigations.length - waCountBefore}`);
  }
  await popup.close();

  const keyboardEventCountBefore = handoffEventPosts;
  const keyboardWaCountBefore = waNavigations.length;
  dialog = await openVerifiedWhatsAppDialog();
  const keyboardPopupPromise = context.waitForEvent('page');
  await (await finalHandoffAction(dialog)).press('Enter');
  const keyboardPopup = await keyboardPopupPromise;
  await keyboardPopup.waitForURL(/^https:\/\/wa\.me\//, { timeout: 10_000 });
  check('keyboard final activation records once and opens one noopener tab',
    handoffEventPosts === keyboardEventCountBefore + 1
    && waNavigations.length === keyboardWaCountBefore + 1
    && await keyboardPopup.evaluate(() => window.opener === null),
    `events=${handoffEventPosts - keyboardEventCountBefore} `
      + `navigations=${waNavigations.length - keyboardWaCountBefore}`);
  await keyboardPopup.close();

  dialog = await openVerifiedWhatsAppDialog();
  const eventsBeforeRevocation = shareEvents.length;
  const waBeforeRevocation = waNavigations.length;
  publicFlags = {
    currentFactsMatch: false,
    currentConsentMatches: false,
    currentDestinationMatches: false,
    currentAuthorityMatches: false,
    actionReady: false
  };
  consentStatus = 'REVOKED';
  destinationChoice = 'NONE';
  preferenceRevision += 1;
  await (await finalHandoffAction(dialog)).click();
  await page.waitForTimeout(600);
  check('consent withdrawal while the dialog is open produces no WhatsApp navigation or persisted attempt',
    waNavigations.length === waBeforeRevocation
    && shareEvents.length === eventsBeforeRevocation, `events=${shareEvents.length - eventsBeforeRevocation} navigations=${waNavigations.length - waBeforeRevocation}`);

  preferenceRevision = 0;
  consentStatus = 'GRANTED';
  destinationChoice = 'DIFFERENT';
  shortlistVersion = 0;
  leadVersion = 7;
  sharePrepared = false;
  shareEvents = [];
  publicFlags = {
    currentFactsMatch: true,
    currentConsentMatches: true,
    currentDestinationMatches: true,
    currentAuthorityMatches: true,
    actionReady: true
  };
  await runToHandoff('DIFFERENT');
  dialog = await openVerifiedWhatsAppDialog();
  let releaseEvent;
  eventGate = { promise: new Promise((resolve) => { releaseEvent = resolve; }) };
  eventGateReached = false;
  const waBeforeCancel = waNavigations.length;
  await (await finalHandoffAction(dialog)).click();
  for (let attempt = 0; attempt < 40 && !eventGateReached; attempt += 1) {
    await page.waitForTimeout(50);
  }
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  releaseEvent();
  await page.waitForTimeout(500);
  eventGate = null;
  check('Cancel while final authorization is held closes the reserved blank context without launch',
    eventGateReached && waNavigations.length === waBeforeCancel);

  await resetHandoffFixture('DIFFERENT');
  dialog = await openVerifiedWhatsAppDialog();
  let releaseUnmountEvent;
  eventGate = { promise: new Promise((resolve) => { releaseUnmountEvent = resolve; }) };
  eventGateReached = false;
  const waBeforeUnmount = waNavigations.length;
  await (await finalHandoffAction(dialog)).click();
  for (let attempt = 0; attempt < 40 && !eventGateReached; attempt += 1) {
    await page.waitForTimeout(50);
  }
  await page.goto(`${BASE}/operations/leads`);
  releaseUnmountEvent();
  await page.waitForTimeout(500);
  eventGate = null;
  check('route unmount while final authorization is held cannot launch from the late result',
    eventGateReached && waNavigations.length === waBeforeUnmount);

  await resetHandoffFixture('DIFFERENT');
  dialog = await openVerifiedWhatsAppDialog();
  let releaseAccountEvent;
  eventGate = { promise: new Promise((resolve) => { releaseAccountEvent = resolve; }) };
  eventGateReached = false;
  const waBeforeAccountSwitch = waNavigations.length;
  await (await finalHandoffAction(dialog)).click();
  for (let attempt = 0; attempt < 40 && !eventGateReached; attempt += 1) {
    await page.waitForTimeout(50);
  }
  for (const identity of [PEER_IDENTITY, BASE_IDENTITY]) {
    await page.evaluate(({ identity }) => {
      window.localStorage.setItem('pathome_auth_token', identity.token);
      window.localStorage.setItem('pathome_role', identity.role);
      window.localStorage.setItem('pathome_user', JSON.stringify({
        id: identity.id,
        role: identity.role,
        fullName: identity.name,
        email: `controlled-${identity.id}@pathome.test`
      }));
      window.dispatchEvent(new Event('pathome_auth_changed'));
    }, { identity });
  }
  releaseAccountEvent();
  await page.waitForTimeout(700);
  eventGate = null;
  check('account A→B→A invalidates the held activation generation and never restores its destination',
    eventGateReached && waNavigations.length === waBeforeAccountSwitch);

  preferenceRevision = 0;
  consentStatus = 'GRANTED';
  destinationChoice = 'SAME';
  shortlistVersion = 0;
  leadVersion = 7;
  sharePrepared = false;
  shareEvents = [];
  publicFlags = {
    currentFactsMatch: true,
    currentConsentMatches: true,
    currentDestinationMatches: true,
    currentAuthorityMatches: true,
    actionReady: true
  };
  await runToHandoff('SAME');
  dialog = await openVerifiedWhatsAppDialog();
  const eventsBeforePopupBlock = handoffEventPosts;
  const waBeforePopupBlock = waNavigations.length;
  await page.evaluate(() => { window.__pathomeBlockPopup = true; });
  await (await finalHandoffAction(dialog)).click();
  await page.getByText(/browser blocked the empty WhatsApp tab/i).waitFor();
  check('a blocked popup records no attempt and gives an authorized retry path',
    handoffEventPosts === eventsBeforePopupBlock
    && waNavigations.length === waBeforePopupBlock);
  await page.evaluate(() => { window.__pathomeBlockPopup = false; });

  await resetHandoffFixture('SAME');
  dialog = await openVerifiedWhatsAppDialog();
  const lostHandoffPostsBefore = handoffEventPosts;
  const lostHandoffWaBefore = waNavigations.length;
  const lostHandoffClipboardBefore = await page.evaluate(() => window.__clipboardWrites);
  lostEventAcknowledgement = true;
  const lostHandoffPopupPromise = context.waitForEvent('page');
  await (await finalHandoffAction(dialog)).click();
  const lostHandoffPopup = await lostHandoffPopupPromise;
  await page.getByText(
    /We recorded your attempt to open WhatsApp, but couldn’t confirm that it opened/i
  ).waitFor();
  await page.waitForTimeout(150);
  check('lost HANDOFF_ATTEMPTED acknowledgement recovers without WhatsApp, a second post or clipboard work',
    handoffEventPosts === lostHandoffPostsBefore + 1
    && waNavigations.length === lostHandoffWaBefore
    && await page.evaluate(() => window.__clipboardWrites) === lostHandoffClipboardBefore
    && shareEvents.length === 1
    && lostHandoffPopup.isClosed()
    && await page.getByText(/Check WhatsApp before trying again/i).isVisible(),
    `posts=${handoffEventPosts - lostHandoffPostsBefore} `
      + `navigations=${waNavigations.length - lostHandoffWaBefore} events=${shareEvents.length}`);

  for (const status of [401, 403, 404, 409, 503]) {
    await resetHandoffFixture('SAME');
    dialog = await openVerifiedWhatsAppDialog();
    const waBeforeFailure = waNavigations.length;
    eventFailureStatus = status;
    await (await finalHandoffAction(dialog)).click();
    await page.waitForTimeout(700);
    check(`final authorization ${status} fails closed with no launch or persisted attempt`,
      waNavigations.length === waBeforeFailure
      && shareEvents.length === 0
      && await page.getByText(/Pathome asked the browser to open WhatsApp/).count() === 0);
    if (status === 401) {
      await page.evaluate(({ identity }) => {
        window.localStorage.setItem('pathome_auth_token', identity.token);
        window.localStorage.setItem('pathome_role', identity.role);
        window.localStorage.setItem('pathome_user', JSON.stringify({
          id: identity.id,
          role: identity.role,
          fullName: identity.name,
          email: `controlled-${identity.id}@pathome.test`
        }));
        window.dispatchEvent(new Event('pathome_auth_changed'));
      }, { identity: BASE_IDENTITY });
    }
  }

  await resetHandoffFixture('SAME');
  dialog = await openVerifiedWhatsAppDialog();
  const waBeforeNetworkFailure = waNavigations.length;
  eventNetworkFailure = true;
  await (await finalHandoffAction(dialog)).click();
  await page.waitForTimeout(700);
  check('network failure during final authorization fails closed without a launch',
    waNavigations.length === waBeforeNetworkFailure
    && shareEvents.length === 0
    && await page.getByText(/Pathome asked the browser to open WhatsApp/).count() === 0);

  sharePrepared = false;
  shareEvents = [];
  await runToHandoff('NONE');
  check('NONE uses copy-links mode without opening a WhatsApp handoff',
    destinationChoice === 'NONE'
    && await page.getByRole('heading', {
      name: 'Copy the message through the agreed alternative'
    }).isVisible()
    && await page.getByRole('button', { name: 'Open WhatsApp' }).count() === 0);
  await page.getByRole('tab', { name: /History/ }).click();
  await page.getByRole('tab', { name: /History/ }).waitFor();
  await page.screenshot({ path: join(EVIDENCE, 'production-history-1440.png'), fullPage: true });

  await resetConsentFixture();
  sharePreparePosts = 0;
  sharePrepareFailureBody = null;
  shareReceipts = new Map();
  sharePrepareFailureStatus = 503;
  await submitSameConsent();
  await page.getByRole('heading', { name: 'Prepare the property message' }).waitFor();
  await assertChangedShareScreenResponsive('Preparation', 'ld-prepare-title');
  await page.getByRole('button', { name: 'Prepare property message' }).click();
  await page.getByText(/couldn’t confirm that the property message is ready/i).waitFor();
  check('a generic 503 remains uncertain and preserves read-only receipt recovery',
    sharePreparePosts === 1
    && await page.getByText(/prepare reply was lost/i).count() === 0
    && await page.getByRole('button', { name: 'Check if message is ready' }).isVisible());
  await page.getByRole('button', { name: 'Check if message is ready' }).click();
  await page.getByText(/property message is not ready yet/i).waitFor();
  check('status checking is GET-only and preserves the original prepared request',
    sharePreparePosts === 1
    && await page.getByRole('button', { name: 'Check if message is ready' }).isVisible());

  await resetConsentFixture();
  sharePreparePosts = 0;
  sharePrepareFailureBody = null;
  shareReceipts = new Map();
  sharePrepareOperationIds = [];
  sharePrepareFailureStatus = 503;
  sharePrepareFailureBody = {
    status: 503,
    error: 'Service Unavailable',
    message: 'A canonical public property origin is not configured'
  };
  await submitSameConsent();
  await page.getByRole('heading', { name: 'Prepare the property message' }).waitFor();
  await page.getByRole('button', { name: 'Prepare property message' }).click();
  await page.getByText(/customer property links are not available yet/i).waitFor();
  check('only a trusted no-write configuration response ends the rejected pending operation',
    sharePreparePosts === 1
    && await page.getByRole('button', { name: 'Check if message is ready' }).count() === 0
    && await page.getByRole('button', { name: 'Prepare message' }).isVisible());
  await page.getByRole('button', { name: 'Prepare message' }).click();
  await page.getByRole('heading', { name: 'Open WhatsApp and send it yourself' }).waitFor();
  await assertChangedShareScreenResponsive('Handoff', 'ld-handoff-title');
  check('a deliberate retry uses one new operation identity and no second automatic POST',
    sharePreparePosts === 2
    && sharePrepareOperationIds.length === 2
    && sharePrepareOperationIds[0] !== sharePrepareOperationIds[1]
    && shareReceipts.size === 1);

  await resetConsentFixture();
  sharePreparePosts = 0;
  sharePrepareFailureBody = null;
  shareReceipts = new Map();
  sharePrepareOperationIds = [];
  await submitSameConsent();
  await page.getByRole('heading', { name: 'Prepare the property message' }).waitFor();
  sharePrepareDropAcknowledgement = true;
  await page.getByRole('button', { name: 'Prepare property message' }).click();
  await page.getByText(/couldn’t confirm that the property message is ready/i).waitFor();
  await page.getByRole('button', { name: 'Check if message is ready' }).click();
  await page.getByRole('heading', { name: 'Open WhatsApp and send it yourself' }).waitFor();
  check('a lost preparation acknowledgement recovers the exact authorized history share once',
    sharePreparePosts === 1
    && shareReceipts.size === 1
    && await page.getByRole('button', { name: 'Open WhatsApp', exact: true }).isVisible());

  await resetConsentFixture();
  const postsBeforeStorageDenial = consentPosts;
  await page.evaluate(() => {
    window.__pathomeOriginalStorageSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function setItem(key, value) {
      if (String(key).startsWith('pathome_oe_consent_v1:')) {
        throw new DOMException('Storage quota exceeded', 'QuotaExceededError');
      }
      return window.__pathomeOriginalStorageSetItem.call(this, key, value);
    };
  });
  await submitSameConsent();
  await page.getByText(/could not keep the permission request\. Nothing was sent/i).waitFor();
  check('denied permission storage gives visible feedback and sends no consent request',
    consentPosts === postsBeforeStorageDenial
    && await page.getByRole('checkbox', { name: /customer clearly gave this agreement/i }).isChecked()
    && await page.getByRole('button', { name: 'Save permission and continue' }).isEnabled());
  await page.evaluate(() => {
    Storage.prototype.setItem = window.__pathomeOriginalStorageSetItem;
    delete window.__pathomeOriginalStorageSetItem;
  });

  await resetConsentFixture();
  consentFailureStatus = 503;
  await submitSameConsent();
  await page.getByText(/permission service (?:is|was) unavailable/).waitFor();
  check('consent 503 is reported as unavailable rather than a lost reply',
    await page.getByText(/consent reply was lost/i).count() === 0
    && await page.getByText(/permission service (?:is|was) unavailable/).isVisible()
    && consentPosts === 0
    && consentReceipts.size === 0
    && await page.getByRole('heading', { name: 'Prepare the property message' }).count() === 0);
  check('a 503 keeps only a non-sensitive pending command identity',
    await page.evaluate(() => {
      const storageKey = Array.from({ length: sessionStorage.length },
        (_, index) => sessionStorage.key(index))
        .find((entry) => entry?.startsWith('pathome_oe_consent_v1:'));
      const value = storageKey ? sessionStorage.getItem(storageKey) : null;
      return Boolean(value)
        && !/9123456789|CUSTOMER_VERBAL|CUSTOMER_DIGITAL|auth_token/.test(value);
    }));
  await page.reload();
  await page.getByRole('heading', { name: /Find current public rentals/ }).waitFor();
  await page.getByRole('button', { name: 'Save shortlist and continue' }).click();
  await page.getByRole('heading', { name: 'Confirm the customer’s WhatsApp number' }).waitFor();
  await page.getByText(/the saved permission request is not recorded yet/i).waitFor();
  check('refresh recovery checks the exact command and performs no automatic replay',
    consentPosts === 0
    && await page.getByRole('button', { name: 'Check saved request again' }).isVisible()
    && await page.getByRole('button', { name: 'Save permission and continue' }).isDisabled());
  await page.evaluate(({ peer, owner }) => {
    window.localStorage.setItem('pathome_auth_token', peer.token);
    window.localStorage.setItem('pathome_user', JSON.stringify({
      id: peer.id, role: peer.role, fullName: peer.name
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
    window.localStorage.setItem('pathome_auth_token', owner.token);
    window.localStorage.setItem('pathome_user', JSON.stringify({
      id: owner.id, role: owner.role, fullName: owner.name
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  }, { peer: PEER_IDENTITY, owner: BASE_IDENTITY });
  check('account A→B→A clears the prior consent command recovery identity',
    await page.evaluate(() => Array.from({ length: sessionStorage.length },
      (_, index) => sessionStorage.key(index))
      .every((entry) => !entry?.startsWith('pathome_oe_consent_v1:'))));

  await resetConsentFixture();
  consentFailureStatus = 400;
  await submitSameConsent();
  await page.getByText(/Controlled consent service response 400/).waitFor();
  check('a known consent validation rejection is not mislabeled as uncertain',
    await page.getByText(/consent reply was lost/i).count() === 0
    && await page.getByRole('button', { name: 'Check saved request again' }).count() === 0);

  for (const scenario of [
    { status: 403, text: /cannot change customer permission for this Lead/ },
    { status: 404, text: /Lead or its current sharing details are no longer available/ },
    { status: 409, text: /Lead or customer permission changed/ }
  ]) {
    await resetConsentFixture();
    consentFailureStatus = scenario.status;
    await submitSameConsent();
    await page.getByText(scenario.text).waitFor();
    check(`consent ${scenario.status} uses its known failure class without entering recovery`,
      await page.getByText(scenario.text).isVisible()
      && await page.getByText(/consent reply was lost/i).count() === 0
      && await page.getByRole('button', { name: 'Check saved request again' }).count() === 0);
  }

  await resetConsentFixture();
  consentDropAcknowledgement = true;
  await submitSameConsent();
  await page.getByRole('button', { name: 'Continue with saved permission' }).waitFor();
  check('lost consent acknowledgement recovers the exact committed revision once',
    consentPosts === 1
    && consentReceipts.size === 1
    && preferenceRevision === 1
    && consentStatus === 'GRANTED'
    && waNavigations.length === 0
    && await page.getByText(/Pathome confirmed that the customer has agreed to receive property links/).isVisible(),
    JSON.stringify({ consentPosts, receipts: consentReceipts.size, preferenceRevision,
      consentStatus, navigations: waNavigations.length }));
  await page.getByRole('button', { name: 'Continue with saved permission' }).click();
  await page.getByRole('heading', { name: 'Prepare the property message' }).waitFor();
  check('recovered current consent enables continuation without opening WhatsApp',
    waNavigations.length === 0
    && await page.getByRole('heading', { name: 'Prepare the property message' }).isVisible());

  await resetConsentFixture();
  consentDropAcknowledgement = true;
  consentRevokeAfterCommit = true;
  await submitSameConsent();
  await page.getByText(/permission was saved, but it has since changed/).waitFor();
  check('an older successful consent receipt cannot override the latest revoked consent',
    conservativeReceiptCheck());

  function conservativeReceiptCheck() {
    return consentReceipts.size === 1
      && preferenceRevision === 2
      && consentStatus === 'REVOKED'
      && waNavigations.length === 0;
  }
  check('superseded consent offers no continuation or sharing action',
    await page.getByRole('button', { name: 'Continue with saved permission' }).count() === 0
    && await page.getByRole('heading', { name: 'Prepare the property message' }).count() === 0);

  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  await page.locator('.oe-sidebar-nav').getByRole('button', { name: 'Overview' }).click();
  await page.waitForURL(`${BASE}/operations`);
  await page.waitForFunction(() =>
    document.querySelector('.oe-sidebar-btn[aria-current="page"]')?.textContent?.includes('Overview')
  );
  check('Leads → Overview keeps the same shared Operations shell and active navigation',
    await page.locator('.oe-shell').count() === 1
    && await page.locator('.oe-sidebar').isVisible()
    && (await page.locator('.oe-sidebar-btn[aria-current="page"]').textContent())?.includes('Overview'));
  await page.locator('.oe-sidebar-nav').getByRole('button', { name: 'Leads' }).click();
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  const filters = page.locator('.ld-filters select');
  await filters.nth(0).selectOption(String(CITY.id));
  await page.waitForTimeout(100);
  await filters.nth(1).selectOption(String(CITY.teamId));
  await filters.nth(2).selectOption('QUALIFYING');
  for (const width of [320, 375, 390, 430, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: width < 600 ? 760 : 900 });
    const layout = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth > window.innerWidth,
      columns: new Set([...document.querySelectorAll('.ld-filters > label')]
        .map((label) => Math.round(label.getBoundingClientRect().left))).size,
      values: [...document.querySelectorAll('.ld-filter-selected')].map((value) => ({
        text: value.textContent?.trim(),
        readable: value.scrollWidth <= value.clientWidth + 1
      })),
      mobileTabs: [...document.querySelectorAll('.oe-tab-btn')]
        .filter((tab) => tab.getClientRects().length > 0)
        .map((tab) => ({ text: tab.textContent?.trim(), top: Math.round(tab.getBoundingClientRect().top) })),
      duplicateLeadNav: document.querySelectorAll('.ld-workspace-nav').length
    }));
    check(`controlled filters stay within the viewport at ${width}px`, !layout.overflow);
    if (width <= 430) {
      check(`controlled long filter values are complete and readable at ${width}px`,
        layout.columns === 1
        && layout.values.every((value) => value.readable)
        && layout.values.some((value) => value.text?.includes('Bhopal Municipal Central'))
        && layout.values.some((value) => value.text?.includes('North Bhopal Residential')),
        JSON.stringify(layout));
      check(`mobile workspace navigation remains a single approved tabbar at ${width}px`,
        layout.duplicateLeadNav === 0
        && layout.mobileTabs.length === 5
        && new Set(layout.mobileTabs.map((tab) => tab.top)).size === 1,
        JSON.stringify(layout.mobileTabs));
    }
    if ([320, 375, 390, 430, 768, 1024, 1280, 1440].includes(width)) {
      await page.screenshot({
        path: join(EVIDENCE, `production-leads-${width}.png`),
        fullPage: true
      });
    }
  }
  await page.setViewportSize({ width: 375, height: 760 });
  await page.screenshot({ path: join(EVIDENCE, 'controlled-filters-375.png'), fullPage: true });
  await page.goto(`${BASE}/operations/leads/new`);
  await page.getByRole('heading', { name: 'Capture an enquiry' }).waitFor();
  const stickyActionMetrics = async () => page.locator('.ld-actions--sticky').first().evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return {
      top: rect.top,
      bottom: rect.bottom,
      viewportHeight: window.innerHeight,
      overflow: document.documentElement.scrollWidth > window.innerWidth
    };
  });
  await page.evaluate(() => window.scrollTo(0, 0));
  const stickyAtTop = await stickyActionMetrics();
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  const stickyAtBottom = await stickyActionMetrics();
  check('mobile sticky capture actions stay inside the actual viewport without overflow',
    !stickyAtTop.overflow
    && !stickyAtBottom.overflow
    && stickyAtTop.top >= 0
    && stickyAtTop.bottom <= stickyAtTop.viewportHeight + 1
    && stickyAtBottom.top >= 0
    && stickyAtBottom.bottom <= stickyAtBottom.viewportHeight + 1,
    JSON.stringify({ stickyAtTop, stickyAtBottom }));
  await page.goto(`${BASE}/operations/leads`);
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  await page.locator('.oe-tab-btn').filter({ hasText: 'Home' }).click();
  await page.waitForURL(`${BASE}/operations`);
  await page.waitForFunction(() =>
    document.querySelector('.oe-tab-btn[aria-current="page"]')?.textContent?.includes('Home')
  );
  check('mobile tabbar remains mounted across Leads → Overview with the active destination',
    await page.locator('.oe-tabbar').isVisible()
    && await page.locator('.oe-tab-btn').filter({ hasText: 'Home' })
      .getAttribute('aria-current') === 'page');
  await page.locator('.oe-tab-btn').filter({ hasText: 'Leads' }).click();
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  const leadSearch = page.getByRole('searchbox', { name: 'Search Leads' });
  const followUpFilter = page.getByRole('combobox', { name: /Follow-up/ });
  await leadSearch.fill('Green Park');
  await followUpFilter.selectOption('TODAY');
  await page.waitForTimeout(150);
  const approvedFilterUrl = new URL(page.url());
  check('approved Lead search and follow-up controls use server parameters without URL search leakage',
    await leadSearch.inputValue() === 'Green Park'
    && await followUpFilter.inputValue() === 'TODAY'
    && !approvedFilterUrl.searchParams.has('q')
    && approvedFilterUrl.searchParams.get('followUp') === 'TODAY'
    && approvedFilterUrl.searchParams.get('followUpZone') === 'Asia/Kolkata');
  await leadSearch.fill('');
  await followUpFilter.selectOption('ANY');
  await filters.nth(0).selectOption(String(CITY.id));
  await page.waitForTimeout(100);
  await filters.nth(1).selectOption(String(CITY.teamId));
  await filters.nth(2).selectOption('QUALIFYING');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.screenshot({ path: join(EVIDENCE, 'production-leads-1440.png'), fullPage: true });
  await page.locator('.ld-row').first().click();
  await page.getByRole('heading', { name: 'Controlled Customer' }).waitFor();
  await page.screenshot({ path: join(EVIDENCE, 'production-detail-from-queue-1440.png'), fullPage: true });
  await page.getByRole('button', { name: 'Back to Leads' }).click();
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  const selectedFilterValues = async () => Promise.all([
    filters.nth(0).inputValue(),
    filters.nth(1).inputValue(),
    filters.nth(2).inputValue(),
    page.getByRole('combobox', { name: /Follow-up/ }).inputValue()
  ]);
  check('same-user detail navigation preserves queue scope and status',
    JSON.stringify(await selectedFilterValues())
      === JSON.stringify([String(CITY.id), String(CITY.teamId), 'QUALIFYING', 'ANY']));
  await page.reload();
  await page.getByRole('heading', { name: 'Leads', exact: true }).waitFor();
  check('queue filters survive refresh through non-sensitive URL context',
    JSON.stringify(await selectedFilterValues())
      === JSON.stringify([String(CITY.id), String(CITY.teamId), 'QUALIFYING', 'ANY']));

  await page.evaluate(({ identity }) => {
    window.localStorage.setItem('pathome_auth_token', identity.token);
    window.localStorage.setItem('pathome_role', identity.role);
    window.localStorage.setItem('pathome_user', JSON.stringify({
      id: identity.id,
      role: identity.role,
      fullName: identity.name,
      email: `controlled-${identity.id}@pathome.test`
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  }, { identity: ADMIN_IDENTITY });
  await page.goto(`${BASE}/operations/leads/source-exceptions`);
  await page.getByRole('heading', { name: 'Import issues', exact: true }).waitFor();
  check('authorized diagnostics render inside the same Operations shell with conditional navigation',
    await page.locator('.oe-sidebar').isVisible()
    && await page.locator('.oe-sidebar-btn').filter({ hasText: 'Leads' }).count() === 1
    && await page.locator('.oe-sidebar-btn').filter({ hasText: 'Import issues' }).count() === 1
    && await page.locator('.oe-sidebar-btn[aria-current="page"]').filter({ hasText: 'Import issues' }).count() === 1);
  await page.screenshot({ path: join(EVIDENCE, 'production-exceptions-1440.png'), fullPage: true });
  await page.locator('.ld-exception-card summary').click();
  check('source exception details expand on demand without losing safe retry controls',
    await page.locator('.ld-exception-card details[open]').count() === 1
    && await page.getByRole('button', { name: 'Retry import' }).isVisible());
  await page.screenshot({ path: join(EVIDENCE, 'production-exception-detail-1440.png'), fullPage: true });

  await page.goto(`${BASE}/operations/leads/41`);
  await page.getByRole('heading', { name: 'Controlled Customer' }).waitFor();
  let releaseDetail;
  detailGate = { promise: new Promise((resolve) => { releaseDetail = resolve; }) };
  await page.getByRole('button', { name: 'Refresh' }).click();
  await page.waitForTimeout(100);
  await page.evaluate(({ identity }) => {
    window.localStorage.setItem('pathome_auth_token', identity.token);
    window.localStorage.setItem('pathome_role', identity.role);
    window.localStorage.setItem('pathome_user', JSON.stringify({
      id: identity.id,
      role: identity.role,
      fullName: identity.name,
      email: `controlled-${identity.id}@pathome.test`
    }));
    window.dispatchEvent(new Event('pathome_auth_changed'));
  }, { identity: PEER_IDENTITY });
  releaseDetail();
  await page.waitForTimeout(1000);
  detailGate = null;
  check('same-mounted-page account switch discards the held prior-account Lead response',
    !(await page.locator('body').innerText()).includes('Controlled Customer'));

  await page.goto(`${BASE}/property/201`);
  await page.waitForFunction(() =>
    document.title === 'Fresh public home | Pathome');
  const propertyMetadata = async (path) => page.evaluate((expectedPath) => {
    const tags = [...document.querySelectorAll('meta[data-pathome-public-metadata], '
      + 'link[data-pathome-public-metadata]')];
    const values = tags.map((tag) => ({
      key: tag.getAttribute('property') || tag.getAttribute('name') || tag.getAttribute('rel'),
      content: tag.getAttribute('content') || tag.getAttribute('href')
    }));
    return {
      title: document.title,
      path: window.location.pathname,
      canonical: values.filter((item) => item.key === 'canonical').map((item) => item.content),
      ogUrl: values.filter((item) => item.key === 'og:url').map((item) => item.content),
      ogTitle: values.filter((item) => item.key === 'og:title').map((item) => item.content),
      all: values,
      expectedPath
    };
  }, path);
  const firstMetadata = await propertyMetadata('/property/201');
  check('client navigation synchronizes one property-specific canonical and OG set',
    firstMetadata.canonical.length === 1
    && firstMetadata.ogUrl.length === 1
    && firstMetadata.ogTitle.length === 1
    && firstMetadata.canonical[0] === `${BASE}/property/201`
    && firstMetadata.ogUrl[0] === `${BASE}/property/201`
    && firstMetadata.ogTitle[0] === 'Fresh public home');

  await page.evaluate(() => {
    window.history.pushState({}, '', '/property/202');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await page.waitForFunction(() => document.title === 'Second public home | Pathome');
  const secondMetadata = await propertyMetadata('/property/202');
  check('property A to B client navigation replaces all property-A metadata without duplicates',
    secondMetadata.canonical.length === 1
    && secondMetadata.ogUrl.length === 1
    && secondMetadata.canonical[0] === `${BASE}/property/202`
    && secondMetadata.ogTitle[0] === 'Second public home'
    && !secondMetadata.all.some((item) => String(item.content).includes('/property/201')));

  await page.evaluate(() => {
    window.history.pushState({}, '', '/');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await page.waitForFunction(() => document.title === 'Pathome | Your Dreams, Our Efforts');
  const removedMetadata = await page.evaluate(() =>
    document.querySelectorAll('meta[data-pathome-public-metadata], '
      + 'link[data-pathome-public-metadata]').length);
  check('property to another route removes every property metadata tag',
    removedMetadata === 0);
  await page.goBack();
  await page.waitForFunction(() => document.title === 'Second public home | Pathome');
  const historyRestored = await propertyMetadata('/property/202');
  check('browser Back restores the current property metadata exactly once',
    historyRestored.canonical.length === 1
    && historyRestored.ogUrl[0] === `${BASE}/property/202`
    && historyRestored.ogTitle[0] === 'Second public home');
  await page.goForward();
  await page.waitForFunction(() => document.title === 'Pathome | Your Dreams, Our Efforts');
  check('browser Forward removes the property metadata again',
    await page.evaluate(() =>
      document.querySelectorAll('meta[data-pathome-public-metadata], '
        + 'link[data-pathome-public-metadata]').length) === 0);

  check('controlled browser completed with no console or page exception',
    browserErrors.length === 0, browserErrors.join(' | '));
  }
} catch (error) {
  checks.push({ name: 'controlled journey completed', passed: false, detail: error.message });
  await page.screenshot({ path: join(EVIDENCE, 'controlled-failure.png'), fullPage: true }).catch(() => {});
  throw error;
} finally {
  await writeFile(join(EVIDENCE, 'controlled-results.json'), JSON.stringify({
    executedAt: new Date().toISOString(),
    evidenceType: 'controlled Chromium with synthetic API transport',
    checks,
    browserErrors,
    apiTrace,
    ...(c2PrivacyEvidence ? { c2Privacy: c2PrivacyEvidence } : {})
  }, null, 2));
  await browser.close();
  await new Promise((resolve) => server.close(resolve));
}
