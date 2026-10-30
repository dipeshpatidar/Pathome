/**
 * Controlled browser verification for the new Backoffice and property-removal screens.
 *
 * This runner uses the production Vite build and production React components. All API responses are
 * explicit in-memory fixtures: it is visual/interaction evidence only, never PostgreSQL, HTTP-contract,
 * authorization or release evidence. The v0 reference project does not contain exact Backoffice or
 * removal screens, so screenshot acceptance remains pending owner visual review.
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const EVIDENCE = process.env.PATHOME_BROWSER_EVIDENCE
  ? process.env.PATHOME_BROWSER_EVIDENCE
  : fileURLToPath(new URL(
    '../../../tools/oe-integration-env/evidence/backoffice-removal-f3-f4-f5-20261025/browser/',
    import.meta.url));
const ORIGIN = 'http://pathome.local';
const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon'
};

let checks = 0;
let failures = 0;
const check = (label, condition, detail = '') => {
  checks += 1;
  if (condition) console.log(`  PASS  ${label}`);
  else {
    failures += 1;
    console.log(`  FAIL  ${label}${detail ? ` (${detail})` : ''}`);
  }
};

const snapshot = {
  title: 'Light-filled family apartment',
  titleProvenance: 'EXPLICIT',
  description: 'A calm, well-connected home with generous natural light.',
  propertyType: 'FLAT',
  bhkCount: '2BHK',
  monthlyRent: 28500,
  securityDeposit: 57000,
  availableFrom: '2026-11-01',
  maintenanceCharge: 1800,
  brokerageAmount: 0,
  brokerageDays: 0,
  securityDepositMonths: 2,
  totalAreaSqFt: 890,
  floorNumber: 4,
  totalFloors: 9,
  furnishingStatus: 'SEMI_FURNISHED',
  amenities: 'Lift, covered parking, power backup',
  bathroomCount: 2,
  vastuFacing: 'East',
  bachelorAllowed: true,
  preferredTenant: 'Working professionals',
  address: '12 Palm Residency, Vijay Nagar',
  landmark: 'Near the district park',
  colony: 'Palm Residency',
  pincode: '452010',
  media: [{
    sourceMediaAssetId: 501,
    mediaId: '88a41cfa-25fb-41f9-9688-4f45895dbebd',
    url: '/pathome-house.png',
    mediaType: 'IMAGE',
    roomTag: 'LIVING_ROOM',
    caption: 'Living room',
    sortOrder: 0,
    cover: true,
    uploadStatus: 'PROCESSING'
  }, {
    sourceMediaAssetId: 502,
    mediaId: '1db4a8d7-120f-4076-8ad4-4f89d92fc201',
    url: '/pathome-house.png',
    mediaType: 'IMAGE',
    roomTag: 'KITCHEN',
    caption: 'Kitchen',
    sortOrder: 1,
    cover: false,
    uploadStatus: 'UPLOADED'
  }]
};

const detail = {
  listingId: 42,
  status: 'PUBLISHED',
  version: 12,
  city: 'Indore',
  locality: 'Vijay Nagar',
  content: snapshot,
  openRevisionDraftId: null,
  openRevisionStatus: null,
  openRevisionVersion: null,
  publicationVersion: 3,
  publishedAt: '2026-08-12T10:00:00Z',
  lastContentPublishedAt: '2026-10-23T09:00:00Z'
};

const revision = {
  draftId: 'staff-7f635f24-4411-4e91-a3c4-c350c2398107',
  listingId: 42,
  revisionMode: 'STAFF_DIRECT',
  status: 'DRAFT',
  version: 1,
  revisionBaseVersion: 12,
  content: snapshot,
  changes: [],
  preview: {
    ready: true,
    blockers: [],
    warnings: ['Media approval history is unavailable for legacy resources.'],
    changes: [],
    baseVersion: 12,
    currentVersion: 12
  }
};

const ownerView = {
  listingId: 42,
  status: 'PUBLISHED',
  version: 12,
  openRevisionDraftId: null,
  openRevisionStatus: null,
  reviewNote: null,
  preview: {
    title: snapshot.title,
    propertyType: 'FLAT',
    bhkCount: '2BHK',
    city: 'Indore',
    locality: 'Vijay Nagar',
    monthlyRent: 28500,
    securityDeposit: 57000,
    availableFrom: '2026-11-01',
    furnishingStatus: 'SEMI_FURNISHED',
    totalAreaSqFt: 890,
    amenities: snapshot.amenities,
    description: snapshot.description,
    media: [{
      mediaId: '501',
      filename: 'living.jpg',
      contentType: 'image/jpeg',
      url: '/pathome-house.png',
      status: 'UPLOADED',
      cover: true,
      sortOrder: 0,
      roomTag: 'LIVING_ROOM',
      caption: 'Living room'
    }],
    missingAttributes: []
  }
};

const removalPending = {
  requestId: 77,
  listingId: 42,
  requestStatus: 'PENDING',
  requestVersion: 0,
  currentListingStatus: 'PUBLISHED',
  currentListingVersion: 12,
  requestedAt: '2026-10-24T08:30:00Z',
  terminalAt: null,
  ownerReason: 'The family is relocating.',
  decisionReason: null,
  permittedActions: ['WITHDRAW']
};

const adminDetail = {
  request: removalPending,
  propertyTitle: snapshot.title,
  city: 'Indore',
  locality: 'Vijay Nagar',
  scheduledAppointmentCount: 2,
  startedAppointmentCount: 1,
  openRevisionStatus: 'DRAFT',
  openRevisionDraftId: revision.draftId
};

const json = (route, body, status = 200) => route.fulfill({
  status,
  contentType: 'application/json',
  headers: { 'Cache-Control': 'no-store' },
  body: JSON.stringify(body)
});

const installRoutes = async (page, identity, apiState) => {
  await page.addInitScript(({ user }) => {
    localStorage.setItem('pathome_auth_token', 'controlled-browser-token');
    localStorage.setItem('pathome_user', JSON.stringify(user));
    localStorage.setItem('pathome_role', user.role);
  }, { user: identity });
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.origin !== ORIGIN) return route.continue();
    if (url.pathname.startsWith('/api/')) {
      const path = url.pathname;
      const method = route.request().method();
      if (path === '/api/v1/staff/me/capabilities') {
        return json(route, identity.role === 'ADMIN' ? {
          staffActive: true,
          serverTime: '2026-10-24T08:00:00Z',
          capabilities: [
            { capability: 'PROPERTY_EDIT_PUBLISH', scopeType: 'CITY', cityId: 1, cityDisplayName: 'Indore', teamId: null, teamDisplayName: null },
            { capability: 'PROPERTY_AUDIT_READ', scopeType: 'CITY', cityId: 1, cityDisplayName: 'Indore', teamId: null, teamDisplayName: null },
            { capability: 'PROPERTY_LIFECYCLE', scopeType: 'CITY', cityId: 1, cityDisplayName: 'Indore', teamId: null, teamDisplayName: null }
          ]
        } : { staffActive: false, serverTime: null, capabilities: [] });
      }
      if (path === '/api/v1/notifications' || path.startsWith('/api/v1/notifications/')) return json(route, []);
      if (path === '/api/v1/properties' && method === 'GET' && apiState.homeRace) {
        return json(route, {
          properties: [
            { id: 80, title: 'Listing v2 authoritative', listingType: 'RENT', propertyType: 'FLAT',
              city: 'Indore', sector: 'Vijay Nagar', bhk: '2BHK', monthlyRent: 25000,
              securityDeposit: 50000, coverImageUrl: '/pathome-house.png', listingVersion: 2 },
            { id: 81, title: 'Listing v2 poll anchor', listingType: 'RENT', propertyType: 'FLAT',
              city: 'Indore', sector: 'Vijay Nagar', bhk: '2BHK', monthlyRent: 26000,
              securityDeposit: 52000, coverImageUrl: '/pathome-house.png', listingVersion: 2 },
            { id: 82, title: 'Listing scheduled for archive', listingType: 'RENT', propertyType: 'FLAT',
              city: 'Indore', sector: 'Vijay Nagar', bhk: '2BHK', monthlyRent: 27000,
              securityDeposit: 54000, coverImageUrl: '/pathome-house.png', listingVersion: 2 }
          ],
          page: 0,
          hasMore: false
        });
      }
      if (path.startsWith('/api/v1/properties/') && method === 'GET' && apiState.homeRace) {
        const listingId = Number(path.split('/').pop());
        const call = (apiState.homeCalls[listingId] || 0) + 1;
        apiState.homeCalls[listingId] = call;
        const scenarios = {
          80: [
            { delay: 350, property: { id: 80, title: 'Listing v1 stale event', listingVersion: 1,
              monthlyRent: 11111 } }
          ],
          81: [
            { delay: 400, status: 404 },
            { delay: 40, property: { id: 81, title: 'Listing v3 newest', listingVersion: 3,
              monthlyRent: 33333 } }
          ],
          82: [
            { delay: 400, property: { id: 82, title: 'Listing v1 stale response', listingVersion: 1,
              monthlyRent: 44444 } },
            { delay: 40, status: 404 }
          ]
        };
        const scenario = scenarios[listingId]?.[call - 1];
        if (!scenario) return json(route, { message: 'No controlled race response.' }, 500);
        if (scenario.delay) await new Promise(resolve => setTimeout(resolve, scenario.delay));
        if (scenario.status) return json(route, { message: 'Controlled unavailable property.' }, scenario.status);
        return json(route, {
          ...scenario.property,
          listingType: 'RENT',
          propertyType: 'FLAT',
          city: 'Indore',
          sector: 'Vijay Nagar',
          bhk: '2BHK',
          securityDeposit: 50000,
          media: []
        });
      }
      if (path === '/api/v1/lessor/capability') return json(route, {
        userId: identity.id, enabled: true, hasLessorProfile: true, activatedAt: '2026-08-01T00:00:00Z'
      });
      if (path === '/api/v1/lessor/properties/42') return json(route, ownerView);
      if (path === '/api/v1/lessor/properties/42/removal-requests' && method === 'GET') {
        return json(route, { items: apiState.ownerRequest ? [apiState.ownerRequest] : [], page: 0, hasMore: false });
      }
      if (path === '/api/v1/lessor/properties/42/removal-requests' && method === 'POST') {
        apiState.ownerRequest = removalPending;
        return json(route, { request: removalPending, created: true }, 201);
      }
      if (path === '/api/v1/lessor/drafts') return json(route, { items: [], page: 0, hasMore: false });
      if (path === '/api/v1/backoffice/properties' || path === '/api/v1/backoffice/properties/') {
        return json(route, {
          items: [{
            listingId: 42,
            title: snapshot.title,
            city: 'Indore',
            locality: 'Vijay Nagar',
            coverUrl: '/pathome-house.png',
            monthlyRent: 28500,
            status: 'PUBLISHED',
            version: 12,
            updatedAt: '2026-10-23T09:00:00Z',
            openRevisionDraftId: null,
            openRevisionStatus: null
          }],
          page: 0,
          hasMore: false
        });
      }
      if (path === '/api/v1/backoffice/properties/42') return json(route, detail);
      if (path === '/api/v1/backoffice/properties/42/revisions' && method === 'POST') return json(route, revision);
      if (path === `/api/v1/backoffice/properties/42/revisions/${revision.draftId}/media` && method === 'GET') {
        return json(route, snapshot.media);
      }
      if (path === `/api/v1/backoffice/properties/42/revisions/${revision.draftId}/media` && method === 'PUT') {
        const submitted = JSON.parse(route.request().postData() || '[]');
        const media = submitted.map((entry, index) => {
          const base = snapshot.media.find(item => item.mediaId === entry.mediaId);
          return {
            ...base,
            ...entry,
            sortOrder: index,
            url: entry.mediaId === snapshot.media[0].mediaId ? '/recovered-a.png' : base?.url,
            uploadStatus: 'UPLOADED'
          };
        });
        return json(route, {
          ...revision,
          version: revision.version + 1,
          content: { ...revision.content, media },
          changes: []
        });
      }
      if (path === `/api/v1/backoffice/properties/42/revisions/${revision.draftId}/media/${snapshot.media[0].mediaId}/recover`
          && method === 'POST') {
        await new Promise(resolve => setTimeout(resolve, 350));
        return json(route, {
          ...snapshot.media[0],
          url: '/recovered-a.png',
          uploadStatus: 'UPLOADED'
        });
      }
      if (path === `/api/v1/backoffice/properties/42/revisions/${revision.draftId}/basics` && method === 'PUT') {
        const body = JSON.parse(route.request().postData() || '{}');
        return json(route, {
          ...revision,
          version: revision.version + 1,
          content: { ...revision.content, ...body },
          changes: [{
            field: 'title',
            label: 'Property title',
            before: revision.content.title,
            after: body.title
          }]
        });
      }
      if (path === '/api/v1/backoffice/properties/42/activity') {
        if (url.searchParams.get('cursor') === 'activity-page-2') return json(route, {
          items: [{
            eventId: 'REVISION:2',
            kind: 'REVISION',
            eventType: 'STAFF_DRAFT_SUPERSEDED',
            draftId: revision.draftId,
            actorUserId: 9,
            actorDisplayName: 'Admin Staff',
            occurredAt: '2026-10-22T09:00:00Z',
            version: 2,
            summary: 'Staff draft superseded',
            details: { reason: 'Replaced with a corrected draft.' }
          }],
          page: 1,
          hasMore: false,
          nextCursor: null
        });
        return json(route, {
        items: [{
          eventId: 'PUBLICATION:1',
          kind: 'PUBLICATION',
          eventType: 'STAFF_DIRECT',
          draftId: revision.draftId,
          actorUserId: 9,
          actorDisplayName: 'Admin Staff',
          occurredAt: '2026-10-23T09:00:00Z',
          version: 3,
          summary: 'Published version 3 with 2 changed section(s).',
          details: {
            monthlyRent: { before: '27000', after: '28500' },
            media: {
              before: [{ resourceId: 501, url: 'https://x.test/Photo_A.jpg', sortOrder: 0, cover: true, roomTag: 'LIVING_ROOM', caption: 'Old caption' }],
              after: [{ resourceId: 501, url: 'https://x.test/Photo_B.jpg', sortOrder: 0, cover: true, roomTag: 'LIVING_ROOM', caption: 'New caption' }]
            }
          }
        }],
        page: 0,
        hasMore: true,
        nextCursor: 'activity-page-2'
      });
      }
      if (path === '/api/v1/admin/property-removal-requests') {
        const requestedStatus = url.searchParams.get('status');
        const requestedPage = Number(url.searchParams.get('page') || '0');
        if (apiState.queueDelays?.[requestedStatus]) {
          await new Promise(resolve => setTimeout(resolve, apiState.queueDelays[requestedStatus]));
        }
        if (apiState.adminApproved && requestedStatus === 'PENDING') {
          return json(route, { items: [], page: 0, hasMore: false });
        }
        if (requestedPage === 1) {
          return json(route, {
            items: [{
              requestId: 76,
              listingId: 43,
              propertyTitle: 'Older removal request',
              city: 'Indore',
              locality: 'Palasia',
              requestStatus: 'PENDING',
              requestVersion: 0,
              currentListingVersion: 8,
              currentListingStatus: 'PUBLISHED',
              requestedAt: '2026-10-20T08:30:00Z',
              ownerReason: 'Older request',
              activeAppointmentCount: 0
            }],
            page: 1,
            hasMore: false
          });
        }
        return json(route, {
        items: [{
          requestId: 77,
          listingId: 42,
          propertyTitle: requestedStatus === 'APPROVED'
            ? 'Approved stale property'
            : requestedStatus === 'REJECTED'
              ? 'Rejected current property'
              : snapshot.title,
          city: 'Indore',
          locality: 'Vijay Nagar',
          requestStatus: requestedStatus || 'PENDING',
          requestVersion: 0,
          currentListingVersion: 12,
          currentListingStatus: 'PUBLISHED',
          requestedAt: '2026-10-24T08:30:00Z',
          ownerReason: removalPending.ownerReason,
          activeAppointmentCount: 3
        }],
        page: 0, hasMore: true
        });
      }
      if (path === '/api/v1/admin/property-removal-requests/77') {
        apiState.adminDetailCount = (apiState.adminDetailCount || 0) + 1;
        return json(route, apiState.adminDetailCount > 1
          ? { ...adminDetail, propertyTitle: 'Approved request B' }
          : adminDetail);
      }
      if (path === '/api/v1/admin/property-removal-requests/77/decision') {
        if (apiState.decisionDelay) {
          await new Promise(resolve => setTimeout(resolve, apiState.decisionDelay));
        }
        if (apiState.decisionFailureStatus) {
          return json(route, { message: 'Controlled decision failure.' }, apiState.decisionFailureStatus);
        }
        apiState.adminApproved = true;
        return json(route, { ...removalPending, requestStatus: 'APPROVED', requestVersion: 1,
          terminalAt: '2026-10-24T09:00:00Z', decisionReason: 'Approved after review',
          currentListingStatus: 'ARCHIVED', currentListingVersion: 13, permittedActions: [] });
      }
      return json(route, { message: 'Controlled fixture has no route for this request.' }, 404);
    }
    const file = join(DIST, url.pathname === '/' ? '/index.html' : url.pathname);
    try {
      return route.fulfill({
        status: 200,
        contentType: CONTENT_TYPES[extname(file)] ?? 'application/octet-stream',
        body: await readFile(file)
      });
    } catch {
      return route.fulfill({
        status: 200,
        contentType: 'text/html; charset=utf-8',
        body: await readFile(join(DIST, 'index.html'))
      });
    }
  });
};

const waitVisible = async (page, selector, timeout = 12000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await page.locator(selector).first().isVisible().catch(() => false)) return true;
    await page.waitForTimeout(100);
  }
  return false;
};

await mkdir(EVIDENCE, { recursive: true });
const browser = await chromium.launch({ channel: 'chromium' });
const receipt = { kind: 'controlled-browser', checks, failures: 0, screenshots: [], viewports: [] };
try {
  const adminContext = await browser.newContext({ viewport: { width: 1440, height: 960 } });
  const adminPage = await adminContext.newPage();
  const adminErrors = [];
  adminPage.on('pageerror', error => adminErrors.push(error.message));
  await installRoutes(adminPage, {
    id: 9, email: 'admin@pathome.in', fullName: 'Admin Staff', role: 'ADMIN',
    freeVisitsUsed: 0, walletBalance: 0
  }, {});
  await adminPage.goto(`${ORIGIN}/backoffice/properties`, { waitUntil: 'domcontentloaded' });
  check('staff inventory renders from the production route',
    await waitVisible(adminPage, '#backoffice-inventory-title'));
  check('inventory shows the authorized property', await waitVisible(adminPage, 'text=Light-filled family apartment'));
  await adminPage.screenshot({ path: join(EVIDENCE, 'backoffice-inventory-1440.png'), fullPage: true });
  receipt.screenshots.push('backoffice-inventory-1440.png');

  for (const width of [320, 375, 390, 430, 768, 1024, 1280, 1440]) {
    await adminPage.setViewportSize({ width, height: 900 });
    await adminPage.waitForTimeout(120);
    const dimensions = await adminPage.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth
    }));
    check(`inventory has no horizontal overflow at ${width}px`,
      dimensions.scrollWidth <= dimensions.innerWidth, JSON.stringify(dimensions));
    receipt.viewports.push({ screen: 'inventory', width, ...dimensions });
  }
  await adminPage.setViewportSize({ width: 1024, height: 900 });
  await adminPage.getByRole('button', { name: /Open property/ }).click();
  check('property detail renders', await waitVisible(adminPage, '#backoffice-property-title'));
  await adminPage.getByRole('button', { name: /Start staff revision/ }).click();
  check('typed staff revision editor renders', await waitVisible(adminPage, 'text=How renters understand this home'));
  const mediaRows = adminPage.locator('.backoffice-media-row');
  check('editor renders both stable media resources', await mediaRows.count() === 2);
  for (const heading of ['Rent, deposit and availability', 'Space, fixtures and preferences',
    'Presentation address', 'Property photos and walkthrough', 'Review saved changes before publishing']) {
    check(`editor exposes ${heading}`, await waitVisible(adminPage, `text=${heading}`, 3000));
  }
  check('editor exposes persistent section navigation',
    await waitVisible(adminPage, 'nav[aria-label="Editor sections"]'));
  const publishButton = adminPage.getByRole('button', { name: /Publish updates/ });
  check('publish is available when all real sections are saved', await publishButton.isEnabled());
  await adminPage.locator('#bo-title').fill('Controlled corrected title');
  check('one real edited section is marked unsaved',
    await waitVisible(adminPage, 'text=1 section with changes not saved'));
  check('dirty section appears in the persistent rail',
    await waitVisible(adminPage, 'button[aria-label="Identity and description, Unsaved changes"]'));
  check('unsaved changes block publication before server confirmation', await publishButton.isDisabled());
  await adminPage.getByRole('button', { name: /Commercial terms, Saved/ }).click();
  check('switching sections preserves the unsaved field value',
    await adminPage.locator('#bo-title').inputValue() === 'Controlled corrected title');
  await adminPage.getByRole('button', { name: /Save basics/ }).click();
  check('section Save reports only after the controlled server response',
    await waitVisible(adminPage, 'text=Identity and description saved.'));
  check('server-confirmed section becomes saved', (await publishButton.isEnabled()));
  for (const width of [320, 375, 390, 430, 768, 1024, 1280, 1440]) {
    await adminPage.setViewportSize({ width, height: 900 });
    await adminPage.waitForTimeout(100);
    const dimensions = await adminPage.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth
    }));
    check(`revision editor has no horizontal overflow at ${width}px`,
      dimensions.scrollWidth <= dimensions.innerWidth, JSON.stringify(dimensions));
    receipt.viewports.push({ screen: 'revision-editor', width, ...dimensions });
  }
  await adminPage.setViewportSize({ width: 1024, height: 900 });
  await adminPage.evaluate(() => window.scrollTo(0, 1100));
  await adminPage.waitForTimeout(120);
  const stickyStatusBox = await adminPage.locator('.backoffice-editor-status').boundingBox();
  check('editor status stays visible below the global navigation while scrolling',
    Boolean(stickyStatusBox && stickyStatusBox.y >= 70 && stickyStatusBox.y <= 80),
    JSON.stringify(stickyStatusBox));
  await adminPage.evaluate(() => window.scrollTo(0, 0));
  await adminPage.screenshot({ path: join(EVIDENCE, 'backoffice-editor-1024.png'), fullPage: true });
  receipt.screenshots.push('backoffice-editor-1024.png');
  await mediaRows.nth(0).getByRole('button', { name: 'Recover upload' }).click();
  await mediaRows.nth(1).getByRole('button', { name: 'Move media up' }).click();
  check('media recovery completion leaves the user’s reordered rows intact',
    await waitVisible(adminPage, 'text=Media upload recovered'));
  await mediaRows.nth(0).locator('img').waitFor();
  await mediaRows.nth(1).locator('img').waitFor();
  check('media recovery updates only the stable recovered resource after reorder',
    await mediaRows.nth(0).locator('img').getAttribute('src') === '/pathome-house.png'
      && await mediaRows.nth(1).locator('img').getAttribute('src') === '/recovered-a.png',
    JSON.stringify(await mediaRows.locator('img').evaluateAll(images =>
      images.map(image => image.getAttribute('src')))));
  await adminPage.getByRole('button', { name: /Activity history/ }).click();
  const loadOlder = adminPage.getByRole('button', { name: /Load older activity/i });
  check('activity history exposes the real Load older activity control',
    await waitVisible(adminPage, 'text=Load older activity'));
  await adminPage.getByText('View exact before and after changes').first().click();
  check('media history explains stable-resource changes rather than counts only',
    await waitVisible(adminPage, 'text=Media: 1 updated.'));
  const evidenceText = await adminPage.locator('.backoffice-media-history').innerText();
  check('actual old and new captions are rendered with exact case',
    evidenceText.includes('Old caption') && evidenceText.includes('New caption'), evidenceText);
  check('resource identifiers retain case and underscores',
    evidenceText.includes('https://x.test/Photo_A.jpg')
      && evidenceText.includes('https://x.test/Photo_B.jpg'), evidenceText);
  await loadOlder.click();
  check('older activity appends the next server page',
    await waitVisible(adminPage, 'text=Staff draft superseded'));
  check('the older-activity action disappears on the final page', await loadOlder.count() === 0);
  check('no uncaught error on staff screens', adminErrors.length === 0, adminErrors.join(' | '));
  await adminContext.close();

  const ownerContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const ownerPage = await ownerContext.newPage();
  const ownerErrors = [];
  ownerPage.on('pageerror', error => ownerErrors.push(error.message));
  await installRoutes(ownerPage, {
    id: 5, email: 'owner@pathome.in', fullName: 'Property Owner', role: 'TENANT',
    freeVisitsUsed: 0, walletBalance: 0, hasLessorProfile: true
  }, {});
  await ownerPage.goto(`${ORIGIN}/lessor/listings/42`, { waitUntil: 'domcontentloaded' });
  check('owner property page renders', await waitVisible(ownerPage, 'text=Request property removal'));
  check('Listing changes and Removal are separate owner sections',
    await waitVisible(ownerPage, '#lessor-listing-changes-title')
      && await waitVisible(ownerPage, '#lessor-removal-title'));
  check('owner Pause remains in Listing changes',
    await ownerPage.getByRole('button', { name: /Pause listing/ }).count() === 1);
  await ownerPage.getByRole('button', { name: /Request property removal/ }).click();
  await ownerPage.locator('#lessor-removal-reason').fill('The family is relocating.');
  await ownerPage.getByRole('button', { name: /Send removal request/ }).click();
  check('owner receives pending status only after the command response',
    await waitVisible(ownerPage, 'text=Removal request pending'));
  check('pending copy preserves current state and appointments',
    await waitVisible(ownerPage, 'text=Existing visits are not cancelled'));
  check('pending removal does not replace the separate Listing changes card',
    await waitVisible(ownerPage, '#lessor-listing-changes-title'));
  const ownerDimensions = await ownerPage.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth
  }));
  check('owner removal state has no horizontal overflow at 390px',
    ownerDimensions.scrollWidth <= ownerDimensions.innerWidth, JSON.stringify(ownerDimensions));
  for (const width of [320, 375, 390, 430, 768, 1024, 1280, 1440]) {
    await ownerPage.setViewportSize({ width, height: 900 });
    await ownerPage.waitForTimeout(100);
    const dimensions = await ownerPage.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth
    }));
    check(`owner removal state has no horizontal overflow at ${width}px`,
      dimensions.scrollWidth <= dimensions.innerWidth, JSON.stringify(dimensions));
    receipt.viewports.push({ screen: 'owner-removal', width, ...dimensions });
  }
  await ownerPage.setViewportSize({ width: 390, height: 844 });
  await ownerPage.screenshot({ path: join(EVIDENCE, 'owner-removal-pending-390.png'), fullPage: true });
  receipt.screenshots.push('owner-removal-pending-390.png');
  check('no uncaught error on owner removal flow', ownerErrors.length === 0, ownerErrors.join(' | '));
  await ownerContext.close();

  const removalContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const removalPage = await removalContext.newPage();
  const removalErrors = [];
  removalPage.on('pageerror', error => removalErrors.push(error.message));
  await installRoutes(removalPage, {
    id: 9, email: 'admin@pathome.in', fullName: 'Admin Staff', role: 'ADMIN',
    freeVisitsUsed: 0, walletBalance: 0
  }, { queueDelays: { APPROVED: 350, REJECTED: 20 } });
  await removalPage.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
  check('Admin navigation exposes removal requests', await waitVisible(removalPage, 'text=Removal requests'));
  const collapseButton = removalPage.getByRole('button', { name: /Collapse sidebar navigation/ });
  const collapseBox = await collapseButton.boundingBox();
  check('desktop sidebar collapse target is at least 44 by 44',
    Boolean(collapseBox && collapseBox.width >= 43.99 && collapseBox.height >= 43.99),
    JSON.stringify(collapseBox));
  await removalPage.getByRole('button', { name: /Removal requests/ }).first().click();
  check('Admin removal queue renders', await waitVisible(removalPage, '#removal-queue-title'));
  check('Admin removal queue exposes server-backed Next for more results',
    await removalPage.getByRole('button', { name: 'Next' }).isVisible());
  await removalPage.getByRole('button', { name: 'Next' }).click();
  check('Admin removal queue loads the next server page',
    await waitVisible(removalPage, 'text=Older removal request'));
  await removalPage.getByRole('button', { name: 'Previous' }).click();
  check('Admin removal queue returns to the first server page',
    await waitVisible(removalPage, 'text=Light-filled family apartment'));
  const filters = removalPage.getByRole('group', { name: 'Filter by status' });
  check('all five server-backed filters are present', await filters.getByRole('button').count() === 5);
  const filterBoxes = await filters.getByRole('button').evaluateAll(buttons =>
    buttons.map(button => {
      const box = button.getBoundingClientRect();
      return { width: box.width, height: box.height };
    }));
  check('every removal filter has a 44 by 44 minimum target',
    filterBoxes.every(box => box.width >= 44 && box.height >= 44), JSON.stringify(filterBoxes));
  await filters.getByRole('button', { name: 'APPROVED' }).click();
  await filters.getByRole('button', { name: 'REJECTED' }).click();
  check('rapid filter changes ignore the stale earlier response',
    await waitVisible(removalPage, 'text=Rejected current property'));
  await removalPage.waitForTimeout(450);
  check('late prior filter response does not overwrite the latest filter',
    await removalPage.getByText('Approved stale property').count() === 0);
  await filters.getByRole('button', { name: 'PENDING' }).click();
  await removalPage.getByRole('button', { name: /Light-filled family apartment/ }).click();
  check('Admin detail shows appointment and revision advisory',
    await waitVisible(removalPage, 'text=2 scheduled · 1 started'));
  for (const width of [320, 375, 390, 430, 768, 1024, 1280, 1440]) {
    await removalPage.setViewportSize({ width, height: 900 });
    await removalPage.waitForTimeout(100);
    const dimensions = await removalPage.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth
    }));
    check(`Admin removal detail has no horizontal overflow at ${width}px`,
      dimensions.scrollWidth <= dimensions.innerWidth, JSON.stringify(dimensions));
    receipt.viewports.push({ screen: 'admin-removal-detail', width, ...dimensions });
  }
  await removalPage.setViewportSize({ width: 390, height: 900 });
  await removalPage.waitForTimeout(120);
  const mobileStackBox = await removalPage.locator('.backoffice-removal-detail-stack').boundingBox();
  const mobileRailBox = await removalPage.locator('.backoffice-removal-decision-rail').boundingBox();
  check('mobile removal decision stacks below the request facts',
    Boolean(mobileStackBox && mobileRailBox && mobileRailBox.y > mobileStackBox.y + mobileStackBox.height - 2),
    JSON.stringify({ mobileStackBox, mobileRailBox }));
  await removalPage.setViewportSize({ width: 1280, height: 900 });
  await removalPage.waitForTimeout(120);
  const detailStackBox = await removalPage.locator('.backoffice-removal-detail-stack').boundingBox();
  const decisionRailBox = await removalPage.locator('.backoffice-removal-decision-rail').boundingBox();
  check('desktop removal decision uses side-by-side composition',
    Boolean(detailStackBox && decisionRailBox && decisionRailBox.x > detailStackBox.x + detailStackBox.width - 2),
    JSON.stringify({ detailStackBox, decisionRailBox }));
  await removalPage.screenshot({ path: join(EVIDENCE, 'admin-removal-detail-1280.png'), fullPage: true });
  receipt.screenshots.push('admin-removal-detail-1280.png');
  await removalPage.getByRole('button', { name: /Approve removal/ }).click();
  check('decision dialog initially focuses its required reason', await removalPage.evaluate(() =>
    document.activeElement?.id === 'removal-decision-reason'));
  await removalPage.keyboard.press('Escape');
  check('closing the decision dialog restores focus to Approve removal',
    await removalPage.evaluate(() => document.activeElement?.textContent?.includes('Approve removal')));
  await removalPage.getByRole('button', { name: /Approve removal/ }).click();
  await removalPage.locator('#removal-decision-reason').fill('Approved after review');
  await removalPage.getByRole('button', { name: /Confirm approval/ }).click();
  check('Admin receives committed approval feedback',
    await waitVisible(removalPage, 'text=Removal approved'));
  await removalPage.screenshot({ path: join(EVIDENCE, 'admin-removal-approved-1280.png'), fullPage: true });
  receipt.screenshots.push('admin-removal-approved-1280.png');
  await removalPage.setViewportSize({ width: 390, height: 844 });
  const mobileMenuButton = removalPage.locator('button[aria-controls="admin-mobile-navigation"]');
  const mobileOpenBox = await mobileMenuButton.boundingBox();
  check('mobile navigation open target is at least 44 by 44',
    Boolean(mobileOpenBox && mobileOpenBox.width >= 44 && mobileOpenBox.height >= 44),
    JSON.stringify(mobileOpenBox));
  await mobileMenuButton.click();
  check('mobile navigation opens with aria-expanded true',
    await mobileMenuButton.getAttribute('aria-expanded') === 'true');
  await removalPage.waitForTimeout(50);
  await removalPage.screenshot({ path: join(EVIDENCE, 'admin-navigation-mobile-390.png'), fullPage: true });
  receipt.screenshots.push('admin-navigation-mobile-390.png');
  check('mobile drawer close receives focus', await removalPage.evaluate(() =>
    document.activeElement?.getAttribute('aria-label') === 'Close menu'));
  const closeBox = await removalPage.getByRole('button', { name: 'Close menu' }).boundingBox();
  check('mobile drawer close target is at least 44 by 44',
    Boolean(closeBox && closeBox.width >= 44 && closeBox.height >= 44), JSON.stringify(closeBox));
  await removalPage.keyboard.press('Escape');
  await removalPage.waitForTimeout(50);
  check('Escape closes mobile navigation and restores focus to its opener',
    await removalPage.getByRole('button', { name: /Open navigation/ }).evaluate(button =>
      button.getAttribute('aria-expanded') === 'false' && document.activeElement === button));
  check('no uncaught error on Admin removal flow', removalErrors.length === 0, removalErrors.join(' | '));
  await removalContext.close();

  for (const failureStatus of [null, 409, 403]) {
    const raceContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const racePage = await raceContext.newPage();
    const raceErrors = [];
    racePage.on('pageerror', error => raceErrors.push(error.message));
    await installRoutes(racePage, {
      id: 9, email: 'admin@pathome.in', fullName: 'Admin Staff', role: 'ADMIN',
      freeVisitsUsed: 0, walletBalance: 0
    }, { decisionDelay: 600, decisionFailureStatus: failureStatus });
    await racePage.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
    await racePage.getByRole('button', { name: /Removal requests/ }).first().click();
    await racePage.getByRole('button', { name: /Light-filled family apartment/ }).click();
    await waitVisible(racePage, '#removal-detail-title');
    await racePage.getByRole('button', { name: /Approve removal/ }).click();
    await racePage.locator('#removal-decision-reason').fill('Approved after review');
    await racePage.getByRole('button', { name: /Confirm approval/ }).click();
    await racePage.locator('.backoffice-removal-filters button')
      .filter({ hasText: 'APPROVED' }).evaluate(button => button.click());
    check(`decision race ${failureStatus ?? 200}: new filter response is shown`,
      await waitVisible(racePage, 'text=Approved stale property'));
    await racePage.getByRole('button', { name: /Approved stale property/ }).click();
    check(`decision race ${failureStatus ?? 200}: replacement detail B is shown`,
      await waitVisible(racePage, '#removal-detail-title', 5000)
        && await racePage.locator('#removal-detail-title').textContent().catch(() => null)
          === 'Approved request B');
    await racePage.waitForTimeout(750);
    check(`decision race ${failureStatus ?? 200}: late A completion cannot replace B`,
      await racePage.locator('#removal-detail-title').textContent().catch(() => null)
        === 'Approved request B');
    check(`decision race ${failureStatus ?? 200}: late A completion cannot state success`,
      await racePage.getByText('Removal approved').count() === 0
        && await racePage.getByText('The decision was not completed.').count() === 0);
    check(`decision race ${failureStatus ?? 200}: no uncaught error`,
      raceErrors.length === 0, raceErrors.join(' | '));
    await raceContext.close();
  }

  const denialContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const denialPage = await denialContext.newPage();
  await installRoutes(denialPage, {
    id: 9, email: 'admin@pathome.in', fullName: 'Admin Staff', role: 'ADMIN',
    freeVisitsUsed: 0, walletBalance: 0
  }, { decisionFailureStatus: 403 });
  await denialPage.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
  await denialPage.getByRole('button', { name: /Removal requests/ }).first().click();
  await denialPage.getByRole('button', { name: /Light-filled family apartment/ }).click();
  await denialPage.getByRole('button', { name: /Approve removal/ }).click();
  await denialPage.locator('#removal-decision-reason').fill('Approved after review');
  await denialPage.getByRole('button', { name: /Confirm approval/ }).click();
  check('current authorization denial clears protected Admin detail and queue rows',
    await waitVisible(denialPage, '.backoffice-alert--error')
      && await denialPage.locator('#removal-detail-title').count() === 0
      && await denialPage.locator('.backoffice-removal-row').count() === 0);
  await denialContext.close();

  const homeContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const homePage = await homeContext.newPage();
  const homeErrors = [];
  homePage.on('pageerror', error => homeErrors.push(error.message));
  await installRoutes(homePage, {
    id: 1, email: 'guest@pathome.in', fullName: 'Guest', role: 'GUEST',
    freeVisitsUsed: 0, walletBalance: 0
  }, { homeRace: true, homeCalls: {} });
  await homePage.goto(`${ORIGIN}/`, { waitUntil: 'domcontentloaded' });
  check('Home production handler renders the authoritative discovery cards',
    await waitVisible(homePage, '#property-card-80')
      && await waitVisible(homePage, '#property-card-81')
      && await waitVisible(homePage, '#property-card-82'));
  const emitCurrent = listingId => homePage.evaluate(id => {
    window.dispatchEvent(new CustomEvent('pathome_property_current_changed', {
      detail: { listingId: id, reason: 'PUBLISHED', sourceId: 'controlled-home-race' }
    }));
  }, listingId);
  await emitCurrent(80);
  await emitCurrent(81);
  await new Promise(resolve => setTimeout(resolve, 25));
  await emitCurrent(81);
  await emitCurrent(82);
  await new Promise(resolve => setTimeout(resolve, 25));
  await emitCurrent(82);
  await homePage.waitForTimeout(650);
  check('older event response cannot replace discovery Listing v2',
    await homePage.locator('#property-card-80 .lp-card-rent').innerText() === '₹25,000/month');
  check('older 404 cannot remove the newer successful poll state',
    await homePage.locator('#property-card-81 .lp-card-rent').innerText() === '₹33,333/month');
  check('older 200 cannot resurrect a newer authoritative archive',
    await homePage.locator('#property-card-82').count() === 0);
  check('Home refresh races produce no uncaught error',
    homeErrors.length === 0, homeErrors.join(' | '));
  await homeContext.close();

  receipt.checks = checks;
  receipt.failures = failures;
  receipt.status = failures === 0 ? 'PASS' : 'FAIL';
  receipt.limitations = [
    'Controlled API fixtures, not genuine Spring HTTP or PostgreSQL.',
    'Production screenshots represent an implementation extension; owner v0 visual approval remains pending.',
    'No physical iPhone, VoiceOver, Firefox, WebKit or release acceptance.'
  ];
  await writeFile(join(EVIDENCE, 'receipt.json'), JSON.stringify(receipt, null, 2));
  console.log(`\n${receipt.status}: ${checks - failures}/${checks} checks passed`);
  if (failures > 0) process.exitCode = 1;
} finally {
  await browser.close();
}

