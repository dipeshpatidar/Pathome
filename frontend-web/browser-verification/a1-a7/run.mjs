/**
 * Controlled A1-A7 browser checks. Production React build + intercepted API only.
 * No PostgreSQL, Spring Boot, provider, customer record or external message is contacted.
 */
import { accessSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const DIST = fileURLToPath(new URL('../../dist/', import.meta.url));
const token = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV';
const sourceEventId = '33333333-3333-4333-8333-333333333333';
const leadVersion = 5;
const contentTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2'
};

const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://controlled.local');
  const file = url.pathname === '/' ? '/index.html' : url.pathname;
  try {
    const body = await readFile(join(DIST, file));
    response.writeHead(200, { 'Content-Type': contentTypes[extname(file)] || 'application/octet-stream' });
    response.end(body);
  } catch {
    const body = await readFile(join(DIST, 'index.html'));
    response.writeHead(200, { 'Content-Type': contentTypes['.html'] });
    response.end(body);
  }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;

const executableCandidates = [
  process.env.PATHOME_BROWSER_CHROMIUM_EXECUTABLE,
  '/Users/dipeshpatidar/Library/Caches/ms-playwright/chromium-1200/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
].filter(Boolean);
const executablePath = executableCandidates.find(candidate => {
  try { accessSync(candidate); return true; } catch { return false; }
});
const browser = await chromium.launch({
  headless: true,
  ...(executablePath ? { executablePath } : {})
});
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const page = await context.newPage();
const calls = [];
const checks = [];
let heldReveal = null;
let denyReveal = false;
let phoneIssued = false;
let phoneConfirmAttempts = 0;
const check = (name, pass, detail = '') => {
  checks.push({ name, pass: Boolean(pass), detail });
  if (!pass) throw new Error(`${name}${detail ? `: ${detail}` : ''}`);
};

await page.route('**/api/v1/**', async route => {
  const request = route.request();
  const url = new URL(request.url());
  const body = request.postDataJSON?.() ?? null;
  calls.push({ method: request.method(), path: url.pathname, body });
  const json = (status, payload, headers = {}) => route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'Cache-Control': 'no-store', ...headers },
    body: JSON.stringify(payload)
  });

  if (url.pathname === '/api/v1/lead-phone-verifications/exchange') {
    return json(200, {
      state: 'PENDING', deliveryState: 'ACCEPTED', maskedDestination: '*******3210',
      attemptsRemaining: 5, expiresAt: '2026-10-27T10:05:00Z',
      message: 'Enter the six-digit enquiry code from the SMS.'
    }, { 'Referrer-Policy': 'no-referrer' });
  }
  if (url.pathname === '/api/v1/lead-phone-verifications/confirm') {
    return json(200, {
      state: 'VERIFIED', deliveryState: 'ACCEPTED', maskedDestination: '*******3210',
      attemptsRemaining: 4, expiresAt: '2026-10-27T10:05:00Z',
      message: 'This enquiry phone is already confirmed.'
    }, { 'Referrer-Policy': 'no-referrer' });
  }
  if (url.pathname === '/api/v1/lead-visit-handoffs/exchange') {
    return json(200, {
      contextId: '11111111-1111-4111-8111-111111111111',
      proposalId: '22222222-2222-4222-8222-222222222222',
      expiresAt: '2026-10-27T10:30:00Z',
      property: {
        id: 31, title: '2 BHK Flat in Vijay Nagar, Indore',
        location: 'Indore / Vijay Nagar', bhk: '2 BHK',
        monthlyRent: 25000, version: 4
      }
    }, {
      'Set-Cookie': 'pathome_visit_context=opaque; Path=/api/v1; HttpOnly; Secure; SameSite=Lax',
      'Referrer-Policy': 'no-referrer'
    });
  }
  if (url.pathname === '/api/v1/lead-visit-handoffs/current') return json(404, { message: 'Not found' });
  if (url.pathname === '/api/v1/properties/31') {
    return json(200, {
      id: 31, title: '2 BHK Flat in Vijay Nagar, Indore',
      description: null, listingType: 'RENT', propertyType: 'FLAT',
      city: 'Indore', sector: 'Vijay Nagar', bhk: '2 BHK',
      monthlyRent: 25000, securityDeposit: 50000, totalAreaSqFt: 900,
      listingVersion: 4, media: []
    });
  }
  if (url.pathname === '/api/v1/staff/me/capabilities') {
    return json(200, {
      staffActive: true,
      capabilities: [{
        capability: 'OPS_COORDINATE',
        scopeType: 'TEAM',
        cityId: 1,
        cityDisplayName: 'Indore',
        teamId: 2,
        teamDisplayName: 'Central Team'
      }],
      serverTime: '2026-10-27T10:00:00Z'
    });
  }
  if (url.pathname === '/api/v1/operations/leads/1' && request.method() === 'GET') {
    return json(200, {
      summary: {
        id: 1, version: leadVersion, customerName: 'Rahul',
        maskedContact: '**********3210', status: 'QUALIFIED', intent: 'RENT',
        cityId: 1, cityName: 'Indore', teamId: 2, teamName: 'Central Team',
        ownerUserId: 5, ownerDisplayName: 'Ravi', followUpAt: null,
        followUpZoneId: 'Asia/Kolkata', lastActivityAt: '2026-10-27T09:50:00Z',
        qualificationGaps: []
      },
      customerSaid: 'Looking for a two-bedroom home.',
      propertyType: 'Flat', bhk: '2 BHK', budgetMin: 20000, budgetMax: 30000,
      currency: 'INR', targetMoveInDate: null, furnishing: null,
      tenantProfile: null, parkingRequired: null, petsAllowed: null,
      liftRequired: null, floorRequirement: null, preferredLocalityIds: [],
      sourceAttributions: [{
        sourceEventId, eventKind: 'MANUAL_PHONE', channel: 'Phone call',
        campaignRef: null, referralRef: null, receivedAt: '2026-10-27T09:45:00Z',
        state: 'PROCESSED', fingerprintVersion: 'MANUAL_CANONICAL_V1', manualEnquiry: null
      }],
      communicationPrerequisites: [], conversionPrerequisites: [],
      linkedCustomerAccount: false, provenance: 'VERIFIED_MANUAL',
      captureOutcome: null, contactPhoneRevision: 0
    });
  }
  if (url.pathname === '/api/v1/operations/leads/1/activities') {
    return json(200, { items: [], totalElements: 0, page: 0, size: 20, totalPages: 0 });
  }
  if (url.pathname === '/api/v1/operations/leads/1/contact-preferences') {
    return json(200, [{
      leadId: 1, revision: 1, leadVersion, channel: 'WHATSAPP',
      destinationChoice: 'SAME', maskedDestination: '**********3210',
      consentStatus: 'GRANTED', consentPurpose: 'PROPERTY_SHARING',
      consentWordingVersion: 'V1', evidenceMethod: 'CUSTOMER_VERBAL',
      recordedAt: '2026-10-27T09:50:00Z'
    }]);
  }
  if (url.pathname === '/api/v1/operations/leads/1/shares') {
    return json(200, { items: [], totalElements: 0, page: 0, size: 20, totalPages: 0 });
  }
  if (url.pathname === '/api/v1/operations/leads/1/shortlists/9') {
    return json(200, {
      id: 9, version: 2, leadVersion, listingIds: [31],
      createdAt: '2026-10-27T09:55:00Z', updatedAt: '2026-10-27T09:55:00Z'
    });
  }
  if (url.pathname === '/api/v1/properties') {
    return json(200, {
      properties: [{
        id: 31, title: '2 BHK Flat in Vijay Nagar, Indore',
        listingType: 'RENT', propertyType: 'FLAT', city: 'Indore',
        sector: 'Vijay Nagar', bhk: '2 BHK', monthlyRent: 25000,
        securityDeposit: 50000, totalAreaSqFt: 900,
        coverImageUrl: null, mediaCount: 0, hasVideo: false, hasMore: false,
        listingVersion: 4, updatedAt: '2026-10-27T09:40:00Z'
      }],
      hasMore: false, page: 0, pageSize: 6
    });
  }
  if (url.pathname === '/api/v1/operations/leads/1/availability-enquiries'
      && request.method() === 'POST') {
    return json(201, {
      enquiryId: '44444444-4444-4444-8444-444444444444', leadId: 1,
      shortlistId: 9, listingId: 31, sourceEventId, outcome: 'PENDING',
      createdAt: '2026-10-27T10:00:00Z', expiresAt: '2026-10-28T10:00:00Z',
      reportedAt: null, version: 0, leadVersion, shortlistVersion: 2,
      listingVersion: 4, contactPhoneRevision: 0, contactRevealEligible: true,
      phoneVerificationState: 'VERIFIED', message: 'Pending availability enquiry is saved.'
    });
  }
  if (url.pathname === '/api/v1/operations/leads/1/visit-proposals'
      && request.method() === 'POST') {
    return json(201, {
      proposalId: '66666666-6666-4666-8666-666666666666', leadId: 1,
      listingId: 31, state: 'OPEN', expiresAt: '2026-10-27T10:30:00Z',
      version: 0,
      handoffUrl: `https://app.pathome.test/property/31#visit=${token}`
    });
  }
  if (/^\/api\/v1\/operations\/leads\/1\/availability-enquiries\/[^/]+\/lessor-contact$/
    .test(url.pathname)) {
    if (heldReveal) await heldReveal.promise;
    if (denyReveal) return json(403, { message: 'Lessor contact access is no longer available.' });
    return json(200, {
      listingId: 31, name: 'Synthetic Lessor', mobile: '+919800000001',
      status: 'CONTACT_AVAILABLE'
    });
  }
  if (url.pathname === '/api/v1/operations/leads/1/phone-verifications/latest') {
    if (!phoneIssued) return json(404, { message: 'Phone verification not found' });
    return json(200, {
      challengeId: '55555555-5555-4555-8555-555555555555', leadId: 1,
      state: 'PENDING', deliveryState: 'SUPPRESSED',
      maskedDestination: '**********3210', failedAttempts: phoneConfirmAttempts > 0 ? 1 : 0,
      maxFailedAttempts: 5, generation: 1,
      challengeVersion: phoneConfirmAttempts > 0 ? 1 : 0,
      contactPhoneRevision: 0, boundContactPhoneRevision: 0,
      currentProofUsable: false, proofUsableUntil: null,
      createdAt: '2026-10-27T10:00:00Z',
      expiresAt: '2026-10-27T10:05:00Z', verifiedAt: null, entryMethod: null,
      message: 'The enquiry code is pending confirmation.'
    });
  }
  if (url.pathname === '/api/v1/operations/leads/1/phone-verifications'
      && request.method() === 'POST') {
    phoneIssued = true;
    return json(201, {
      challengeId: '55555555-5555-4555-8555-555555555555', leadId: 1,
      state: 'PENDING', deliveryState: 'SUPPRESSED',
      maskedDestination: '**********3210', failedAttempts: 0,
      maxFailedAttempts: 5, generation: 1, challengeVersion: 0,
      contactPhoneRevision: 0, boundContactPhoneRevision: 0,
      currentProofUsable: false, proofUsableUntil: null,
      createdAt: '2026-10-27T10:00:00Z',
      expiresAt: '2026-10-27T10:05:00Z', verifiedAt: null, entryMethod: null,
      message: 'SMS confirmation is unavailable. The Lead and follow-up remain saved.'
    });
  }
  if (/^\/api\/v1\/operations\/leads\/1\/phone-verifications\/[^/]+\/confirm$/
    .test(url.pathname)) {
    phoneConfirmAttempts += 1;
    if (phoneConfirmAttempts === 1) {
      return json(400, { message: 'The enquiry code is incorrect. 4 attempts remain.' });
    }
    return json(200, {
      challengeId: '55555555-5555-4555-8555-555555555555', leadId: 1,
      state: 'VERIFIED', deliveryState: 'SUPPRESSED',
      maskedDestination: '**********3210', failedAttempts: 1,
      maxFailedAttempts: 5, generation: 1, challengeVersion: 2,
      contactPhoneRevision: 0, boundContactPhoneRevision: 0,
      currentProofUsable: true, proofUsableUntil: '2026-10-28T10:01:00Z',
      createdAt: '2026-10-27T10:00:00Z',
      expiresAt: '2026-10-27T10:05:00Z', verifiedAt: '2026-10-27T10:01:00Z',
      entryMethod: 'OE_ENTERED',
      message: 'Phone possession is current for this enquiry source and phone revision.'
    });
  }
  return json(404, { message: 'Controlled endpoint not found' });
});

await page.goto(`${base}/verify-enquiry-phone#token=${token}`);
await page.getByRole('heading', { name: 'Confirm your enquiry number.' }).waitFor();
check('OTP fragment is removed before exchange', !new URL(page.url()).hash);
check('OTP exchange received exactly one controlled POST',
  calls.filter(call => call.path === '/api/v1/lead-phone-verifications/exchange').length === 1);
const phonePageText = await page.locator('body').innerText();
check('OTP token/code is absent from visible page', !phonePageText.includes(token) && !/\\b\\d{6}\\b/.test(phonePageText));
const stored = await page.evaluate(() => ({
  local: { ...localStorage }, session: { ...sessionStorage }
}));
check('OTP token is absent from browser storage', !JSON.stringify(stored).includes(token));
await page.getByLabel('Six-digit enquiry code').fill('000123');
await page.getByRole('button', { name: 'Confirm enquiry number' }).click();
await page.getByText('Enquiry number confirmed').waitFor();
check('Leading-zero customer code reaches confirmation', calls.some(call =>
  call.path === '/api/v1/lead-phone-verifications/confirm' && call.body?.code === '000123'));

calls.length = 0;
await page.goto(`${base}/property/31#visit=${token}`);
await page.getByText('This is an exact-property Visit invitation').waitFor();
check('Visit fragment is stripped before browser storage or requester', !new URL(page.url()).hash);
check('Invitation page load does not create a Visit Request', !calls.some(call =>
  call.path.includes('/visit-requests') || call.path.includes('/property-visit-commands')));
const invitationBody = await page.locator('body').innerText();
check('Invitation banner makes no delivery or booking claim',
  !/(?:your visit is booked|appointment confirmed|message delivered)/i.test(invitationBody));

for (const width of [320, 375, 390, 414, 430, 768, 1024, 1280, 1440]) {
  await page.setViewportSize({ width, height: 900 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  check(`Invitation property has no horizontal overflow at ${width}px`, overflow <= 1, `${overflow}px`);
}

await page.setViewportSize({ width: 320, height: 900 });
await page.goto(`${base}/verify-enquiry-phone#token=${token}`);
await page.getByRole('heading', { name: 'Confirm your enquiry number.' }).waitFor();
await page.getByLabel('Six-digit enquiry code').waitFor();
for (const width of [320, 375, 390, 414, 430, 768, 1024, 1280, 1440]) {
  await page.setViewportSize({ width, height: 900 });
  const phoneOverflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  check(`OTP confirmation has no horizontal overflow at ${width}px`, phoneOverflow <= 1, `${phoneOverflow}px`);
}
await page.setViewportSize({ width: 320, height: 900 });
const phoneControls = await page.evaluate(() => {
  const input = document.querySelector('input[autocomplete="one-time-code"]');
  const button = document.querySelector('button[type="submit"]');
  return {
    fontSize: input ? Number.parseFloat(getComputedStyle(input).fontSize) : 0,
    buttonHeight: button ? button.getBoundingClientRect().height : 0
  };
});
check('OTP input uses at least 16px effective text', phoneControls.fontSize >= 16);
check('OTP confirmation action is at least 44px tall', phoneControls.buttonHeight >= 44);
const phoneZoomOverflow = await page.evaluate(() => {
  document.documentElement.style.zoom = '2';
  const overflow = document.documentElement.scrollWidth - window.innerWidth;
  document.documentElement.style.zoom = '';
  return overflow;
});
check('OTP confirmation remains bounded at emulated 200% zoom', phoneZoomOverflow <= 1,
  `${phoneZoomOverflow}px`);
const focusedInputRing = await page.evaluate(() => {
  const input = document.querySelector('input[autocomplete="one-time-code"]');
  input.focus();
  const style = getComputedStyle(input);
  return style.outlineStyle !== 'none' || style.boxShadow !== 'none';
});
check('OTP keyboard focus exposes a visible focus indicator', focusedInputRing);
await page.emulateMedia({ reducedMotion: 'reduce' });
check('reduced-motion media preference is honored by Chromium context',
  await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches));

calls.length = 0;
await page.evaluate(() => {
  localStorage.setItem('pathome_auth_token', 'staff-token');
  localStorage.setItem('pathome_role', 'EMPLOYEE');
  localStorage.setItem('pathome_user', JSON.stringify({
    id: 5, role: 'EMPLOYEE', fullName: 'Ravi',
    email: 'ravi@pathome.test'
  }));
  window.dispatchEvent(new Event('pathome_auth_changed'));
});
await page.goto(`${base}/operations/leads/1`);
await page.getByRole('heading', { name: 'Rahul' }).waitFor();
await page.getByRole('heading', { name: 'Enquiry phone proof' }).waitFor();
await page.getByText('No enquiry-phone code has been requested.').waitFor();
const sendCode = page.getByRole('button', { name: 'Send enquiry code' });
check('Enquiry code send is disabled until notice agreement', await sendCode.isDisabled());
await page.getByText('I explained this is enquiry-phone proof only', { exact: false }).click();
await sendCode.click();
await page.getByText('SMS confirmation is unavailable', { exact: false }).waitFor();
const phoneIssue = calls.find(call => call.path === '/api/v1/operations/leads/1/phone-verifications'
  && call.method === 'POST');
check('OE phone issue is bound to exact Lead/source/revision', phoneIssue?.body?.expectedLeadVersion === leadVersion
  && phoneIssue?.body?.sourceEvent?.id === sourceEventId
  && phoneIssue?.body?.expectedContactPhoneRevision === 0
  && phoneIssue?.body?.noticeAgreed === true);
check('Suppressed SMS is not labelled verified',
  !(await page.locator('body').innerText()).includes('Phone possession confirmed'));
await page.getByLabel('Feature-phone fallback · customer dictates the code').fill('000123');
await page.getByRole('button', { name: 'Confirm dictated code' }).click();
await page.getByText('4 attempts remain').waitFor();
await page.getByLabel('Feature-phone fallback · customer dictates the code').fill('000124');
await page.getByRole('button', { name: 'Confirm dictated code' }).click();
await page.getByText(/Current for source, phone revision 0/).waitFor();
const correctedConfirm = calls.filter(call => call.path.endsWith('/confirm')).at(-1);
check('Rejected OTP reloads the advanced version before corrected retry',
  correctedConfirm?.body?.expectedChallengeVersion === 1);
check('Only authoritative current proof receives the usable label',
  (await page.locator('body').innerText()).includes('Current for source, phone revision 0'));

calls.length = 0;
await page.goto(`${base}/operations/leads/1/share?shortlist=9`);
await page.getByRole('heading', { name: 'Lessor availability' }).waitFor();
await page.getByText('The customer has a current, genuine viewing-availability question').click();
await page.getByRole('button', { name: 'Check lessor availability' }).click();
await page.getByText('Synthetic Lessor').waitFor();
check('One availability action persists PENDING then returns fresh canonical contact',
  calls.some(call => call.path === '/api/v1/operations/leads/1/availability-enquiries')
  && calls.some(call => call.path.endsWith('/lessor-contact')));
const availabilityBody = await page.locator('body').innerText();
check('Lessor report UI explicitly denies booking/reservation side effects',
  /creates no Visit Request,\s*reservation/i.test(availabilityBody)
  && /not a booking/i.test(availabilityBody));
for (const width of [320, 375, 390, 414, 430, 768, 1024, 1280, 1440]) {
  await page.setViewportSize({ width, height: 900 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  check(`OE availability workspace has no horizontal overflow at ${width}px`, overflow <= 1, `${overflow}px`);
}
const availabilityZoomOverflow = await page.evaluate(() => {
  document.documentElement.style.zoom = '2';
  const overflow = document.documentElement.scrollWidth - window.innerWidth;
  document.documentElement.style.zoom = '';
  return overflow;
});
check('OE availability remains bounded at emulated 200% zoom',
  availabilityZoomOverflow <= 1, `${availabilityZoomOverflow}px`);

await page.setViewportSize({ width: 1280, height: 900 });
await page.evaluate(() => {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => 'hidden'
  });
  document.dispatchEvent(new Event('visibilitychange'));
});
check('Backgrounding synchronously withdraws revealed lessor contact',
  !(await page.locator('body').innerText()).includes('Synthetic Lessor'));
await page.evaluate(() => {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => 'visible'
  });
  document.dispatchEvent(new Event('visibilitychange'));
});
check('Foregrounding does not restore contact without a fresh request',
  !(await page.locator('body').innerText()).includes('Synthetic Lessor'));

await page.getByRole('button', { name: 'Reveal fresh lessor contact' }).click();
await page.getByText('Synthetic Lessor').waitFor();
denyReveal = true;
await page.getByRole('button', { name: 'Reveal fresh lessor contact' }).click();
await page.waitForTimeout(150);
check('Authorization denial synchronously clears previously revealed contact',
  !(await page.locator('body').innerText()).includes('Synthetic Lessor'));

const gate = {};
gate.promise = new Promise(resolve => { gate.resolve = resolve; });
heldReveal = gate;
denyReveal = false;
await page.getByRole('button', { name: 'Reveal fresh lessor contact' }).click();
await page.evaluate(() => {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => 'hidden'
  });
  document.dispatchEvent(new Event('visibilitychange'));
});
gate.resolve();
await page.waitForTimeout(100);
await page.evaluate(() => {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => 'visible'
  });
  document.dispatchEvent(new Event('visibilitychange'));
});
await page.waitForTimeout(100);
heldReveal = null;
check('Held old reveal success cannot repopulate backgrounded or foregrounded state',
  !(await page.locator('body').innerText()).includes('Synthetic Lessor'));

await page.getByRole('button', { name: 'Reveal fresh lessor contact' }).click();
await page.getByText('Synthetic Lessor').waitFor();
await page.evaluate(() => {
  localStorage.setItem('pathome_auth_token', 'new-account-token-abcdefghijklmnopqrstuvwxyz');
  localStorage.setItem('pathome_user', JSON.stringify({
    id: 6, role: 'EMPLOYEE', fullName: 'Synthetic Next Account',
    email: 'next-account@pathome.test'
  }));
  window.dispatchEvent(new Event('pathome_auth_changed'));
});
check('Account switch synchronously withdraws prior lessor contact',
  !(await page.locator('body').innerText()).includes('Synthetic Lessor'));

await browser.close();
server.close();
console.log(JSON.stringify({ checks: checks.length, passed: checks.filter(item => item.pass).length }, null, 2));
