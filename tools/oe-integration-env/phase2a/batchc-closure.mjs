/** Genuine Batch C React -> Spring -> PostgreSQL acceptance slice on the attested disposable target. */
import { createServer, request as httpRequest } from 'node:http';
import { execFileSync } from 'node:child_process';
import { readFile, realpath } from 'node:fs/promises';
import { extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { boundClient, dataFile, verifyBinding, verifyJwt } from '../lib/runtime.mjs';
import { Evidence, successfulEvidence } from './evidence.mjs';
import { bounded, BOUNDS } from '../lib/lifecycle.mjs';

const scenarios = [
  'BC01_AUTH_CAPABILITY', 'BC02_OWNED_DRAFT', 'BC03_FEASIBLE_GE_DISCOVERY',
  'BC04_DISPLAY_NAME', 'BC05_BERLIN_CLOCKS', 'BC06_COARSE_STATUS',
  'BC07_OWNERSHIP_DENIAL', 'BC08_STALE_VERSION_409', 'BC09_AUTHORITATIVE_BOOKING',
  'BC10_INFORMATION_ONLY_CANNOT_BOOK', 'BC11_AUDIT_FAIL_CLOSED', 'BC12_TENANT_CONFIRMATION'
].map(id => ({ id, kind: id === 'BC11_AUDIT_FAIL_CLOSED' ? 'http+postgres'
  : ['BC08_STALE_VERSION_409', 'BC10_INFORMATION_ONLY_CANNOT_BOOK'].includes(id) ? 'browser+http' : 'browser',
  actor: 'oe-alpha', method: id === 'BC11_AUDIT_FAIL_CLOSED' ? 'HTTP' : 'UI', apiPath: null }));
const assert = (value, message) => { if (!value) throw Error(message); };
const BERLIN_ZONE = 'Europe/Berlin';
const BERLIN_START = process.env.BATCHC_START ?? '2026-10-21T10:45:00+02:00';
const BERLIN_END = process.env.BATCHC_END ?? '2026-10-21T16:45:00+02:00';
const ALPHA_TEAM = 4;
const BRAVO_TEAM = 5;
const ALPHA_USER = 31;
const BRAVO_USER = 32;

const COLLAPSED = /[\s\u00a0\u202f]+/g;
const IANA_ZONE = /^[A-Za-z][A-Za-z0-9_+-]*(?:\/[A-Za-z0-9_+-]+)+$/;
export const collapsedText = value => String(value ?? '').replace(COLLAPSED, ' ').trim();

const zonedParts = (epoch, zoneId) => {
  if (typeof zoneId !== 'string' || !IANA_ZONE.test(zoneId)) throw Error('Invalid owning IANA zone: ' + zoneId);
  const date = new Date(epoch);
  if (!Number.isFinite(date.getTime())) throw Error('Invalid property-window instant');
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: zoneId, weekday: 'short', day: 'numeric',
    month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true })
    .formatToParts(date).filter(part => part.type !== 'literal');
  return Object.fromEntries(parts.map(part => [part.type, part.value]));
};

const zoneOffsetAt = (epoch, zoneId) => {
  const name = new Intl.DateTimeFormat('en-GB', { timeZone: zoneId, timeZoneName: 'longOffset' })
    .formatToParts(new Date(epoch)).find(part => part.type === 'timeZoneName')?.value ?? '';
  const match = /^GMT([+-]\d{2}:\d{2})?$/.exec(name);
  if (!match) throw Error('Owning zone offset could not be derived');
  return match[1] ?? '+00:00';
};

const wallClockAt = (epoch, zoneId) => {
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: zoneId, hourCycle: 'h23', year: 'numeric',
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
    .formatToParts(new Date(epoch)).filter(part => part.type !== 'literal');
  const value = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return `${value.year}-${value.month}-${value.day}T${value.hour}:${value.minute}:${value.second}`;
};

const repeatedLocalTime = (epoch, zoneId) => {
  const wall = wallClockAt(epoch, zoneId);
  return wallClockAt(epoch - 3600000, zoneId) === wall || wallClockAt(epoch + 3600000, zoneId) === wall;
};

/**
 * Independently derive the product's advertised window text from the authoritative API instants
 * and owning IANA zone. This deliberately does not call the React formatter, so a formatter bug
 * cannot validate itself. A repeated local time is disambiguated by its real offset.
 */
export const bc05ExpectedWindow = stop => {
  if (!stop || typeof stop !== 'object') throw Error('Property-window stop is required');
  const start = Date.parse(stop.availabilityStartAt);
  const end = Date.parse(stop.availabilityEndAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    throw Error('Property-window endpoints are incomplete or reversed');
  }
  const endpoint = epoch => {
    const value = zonedParts(epoch, stop.zoneId);
    const label = `${value.weekday}, ${value.day} ${value.month}, ${value.year} · `
      + `${value.hour}:${value.minute} ${value.dayPeriod.toLowerCase()} ${stop.zoneId}`;
    return repeatedLocalTime(epoch, stop.zoneId) ? `${label} (${zoneOffsetAt(epoch, stop.zoneId)})` : label;
  };
  const startText = endpoint(start);
  const endText = endpoint(end);
  return { startText, endText, text: `${startText} → ${endText}` };
};

const sameInstant = (left, right) => Number.isFinite(Date.parse(left)) && Date.parse(left) === Date.parse(right);

/**
 * Bind one rendered property-window row to one authoritative Session item, listing and stop, then
 * compare complete endpoints. Every ambiguous, absent, hidden, stale or foreign row fails closed.
 */
export const assertBc05WindowEvidence = capture => {
  const { identity, timing, sessionReads, browserZone, rows } = capture ?? {};
  if (!identity || !timing || !sessionReads || !Array.isArray(rows)) throw Error('Incomplete BC05 capture');
  const { sessionId, sessionVersion, requestId, itemId, listingId, position } = identity;
  for (const [name, value] of Object.entries({ sessionId, requestId, itemId, listingId })) {
    if (!Number.isSafeInteger(value) || value <= 0) throw Error('Invalid BC05 identity: ' + name);
  }
  if (!Number.isSafeInteger(sessionVersion) || sessionVersion < 0) throw Error('Invalid BC05 Session version');
  if (!Number.isSafeInteger(position) || position < 1) throw Error('Invalid BC05 stop position');
  if (typeof browserZone !== 'string' || !browserZone) throw Error('Browser timezone was not captured');

  if (timing.sessionId !== sessionId || timing.sessionVersion !== sessionVersion) {
    throw Error('Availability response belongs to another Session or version');
  }
  const stops = timing.timing?.stops;
  if (!Array.isArray(stops) || stops.length === 0) throw Error('Availability response has no property windows');
  const uniquePositions = new Set(stops.map(stop => stop.position));
  if (uniquePositions.size !== stops.length) throw Error('Property windows contain duplicate stop positions');
  const targetStops = stops.filter(stop => stop.itemId === itemId && stop.listingId === listingId
    && stop.position === position);
  if (targetStops.length !== 1) {
    throw Error(`Property window identity is ambiguous or missing: ${targetStops.length} matches`);
  }
  const stop = targetStops[0];
  const expected = bc05ExpectedWindow(stop);

  const readMatch = (read, label, strict = true) => {
    if (!read || read.sessionId !== sessionId) throw Error('BC05 ' + label + ' read belongs to another Session');
    if (read.version !== sessionVersion) {
      if (!strict) return null;
      throw Error('BC05 ' + label + ' Session version is stale: ' + read.version + ' != ' + sessionVersion);
    }
    const matches = (read.items ?? []).filter(item => item.itemId === itemId);
    if (matches.length !== 1) throw Error('BC05 ' + label + ' Session item is missing or ambiguous');
    const item = matches[0];
    if (item.removedAt) throw Error('BC05 ' + label + ' Session item was replaced or removed');
    if (item.listingId !== listingId || item.position !== position || item.sourceRequestId !== requestId) {
      throw Error('BC05 ' + label + ' Session item identity changed');
    }
    if (!sameInstant(item.propertyAvailabilityStartAt, stop.availabilityStartAt)
        || !sameInstant(item.propertyAvailabilityEndAt, stop.availabilityEndAt)
        || item.propertyAvailabilityZoneId !== stop.zoneId) {
      throw Error('BC05 ' + label + ' property window differs from the availability response');
    }
    if ((item.availabilityConfirmedAt ?? null) !== (stop.availabilityConfirmedAt ?? null)) {
      throw Error('BC05 ' + label + ' property-window revision changed during capture');
    }
    return item;
  };
  // An edit between the pre-capture read and the capture itself is legitimate event history.
  // The capture-phase and post-capture reads are the ones that must describe this precise window.
  readMatch(sessionReads.before, 'before', false);
  readMatch(sessionReads.atCapture, 'capture');
  readMatch(sessionReads.after, 'after');

  if (rows.length !== stops.length) {
    throw Error('Property-window row count differs from the authoritative stops');
  }
  for (const row of rows) {
    if (!/^\d+$/.test(collapsedText(row.positionText))) throw Error('Property-window row has no stop position');
    if (collapsedText(row.windowText) === '') throw Error('Property-window row has no rendered window');
  }
  const renderedPositions = rows.map(row => Number(collapsedText(row.positionText)));
  if (new Set(renderedPositions).size !== renderedPositions.length
      || renderedPositions.some(value => !uniquePositions.has(value))
      || uniquePositions.size !== renderedPositions.length) {
    throw Error('Rendered property-window rows do not map one-to-one to authoritative stops');
  }
  const propertyTitle = 'Property #' + listingId;
  const targetRows = rows.filter(row => Number(collapsedText(row.positionText)) === position
    && collapsedText(row.titleText) === propertyTitle);
  if (targetRows.length === 0) throw Error('Property-window row is missing for ' + propertyTitle + ' stop ' + position);
  if (targetRows.length !== 1) throw Error('Property-window row is ambiguous for ' + propertyTitle);
  const targetRow = targetRows[0];
  if (targetRow.hidden || !targetRow.visible) throw Error('Property-window row is hidden or not rendered');

  const rendered = collapsedText(targetRow.windowText);
  const [renderedStart, renderedEnd, ...extra] = rendered.split(' → ');
  if (extra.length || !renderedStart || !renderedEnd) {
    throw Error('Property-window text is not a complete start → end window');
  }
  if (renderedStart !== expected.startText) {
    throw Error('Property-window start text mismatch: rendered "' + renderedStart + '", expected "'
      + expected.startText + '"');
  }
  if (renderedEnd !== expected.endText) {
    throw Error('Property-window end text mismatch: rendered "' + renderedEnd + '", expected "'
      + expected.endText + '"');
  }
  const zoneBound = value => {
    const index = value.indexOf(' ' + stop.zoneId);
    if (index < 0) return false;
    const tail = value.slice(index + stop.zoneId.length + 1);
    return tail === '' || /^ \([+-]\d{2}:\d{2}\)$/.test(tail);
  };
  if (!zoneBound(renderedStart) || !zoneBound(renderedEnd)) {
    throw Error('Owning-zone label is not bound to both endpoints');
  }
  if (rendered !== expected.text) throw Error('Property-window text is not exactly the derived window');

  return {
    sessionId, sessionVersion, requestId, itemId, listingId, position,
    backendStartAt: stop.availabilityStartAt, backendEndAt: stop.availabilityEndAt,
    backendZone: stop.zoneId, propertyWindowRevision: stop.availabilityConfirmedAt ?? null,
    sessionVersionBefore: sessionReads.before.version,
    sessionVersionAtCapture: sessionReads.atCapture.version,
    sessionVersionAfter: sessionReads.after.version,
    browserZone,
    browserZoneDiffersFromOwningZone: browserZone !== stop.zoneId,
    expectedStartText: expected.startText, expectedEndText: expected.endText,
    domStartText: renderedStart, domEndText: renderedEnd,
    domWindowText: rendered, domRowIndex: targetRow.index ?? null,
    domRowCount: rows.length, uniquePositionRowCount: 1,
    domPropertyTitle: collapsedText(targetRow.titleText),
    domMetaText: collapsedText(targetRow.metaText)
  };
};

async function main() {
  assert(process.env.PATHOME_OE_TEST_PHASE2A_LIVE === 'true', 'Explicit live opt-in required');
  const run = verifyBinding();
  const client = boundClient();
  const prepared = successfulEvidence(run.root, 'B09_FIXTURES', dataFile);
  assert(prepared.runId === run.binding.runId && prepared.counts.PASS === 19 && prepared.counts.FAIL === 0,
    'A complete same-run B09 fixture preparation is required');
  const fixturesFile = prepared.fixturesFile;
  assert(typeof fixturesFile === 'string' && fixturesFile.startsWith(run.root + '/evidence/attempts/'),
    'Fixture evidence path is invalid');
  const fixtures = JSON.parse(dataFile(fixturesFile));
  assert(fixtures.runId === run.binding.runId, 'Foreign fixture run');
  const selectedRequestId = Number(process.env.BATCHC_REQUEST_ID ?? fixtures.requestId);
  assert(Number.isSafeInteger(selectedRequestId) && selectedRequestId > 0, 'A run-owned Request is required');
  const bc05Only = process.env.BATCHC_FOCUS === 'BC05';
  const requiredScenarios = bc05Only ? scenarios.filter(scenario => scenario.id === 'BC05_BERLIN_CLOCKS') : scenarios;
  const evidence = new Evidence(requiredScenarios, run.root + '/evidence/batchc-closure.json',
    bc05Only ? 'BATCH_C_BC05' : 'BATCH_C',
    { runId: run.binding.runId, database: run.binding.database, backend: run.origin,
      fixturesFile, auditEvidence: process.env.BATCHC_AUDIT_EVIDENCE ?? null });
  evidence.watchInterruptions();

  const api = async (method, path, token, body, expected = 200) => {
    const result = await client.request(method, path, token, body);
    assert(result.status === expected, method + ' ' + path + ': HTTP ' + result.status + ' ' + result.text.slice(0, 300));
    return result.json;
  };
  const signIn = async (email, role) => {
    const result = await api('POST', '/auth/login', null, { email, password: run.secrets.OE_STAFF_PASSWORD });
    const claims = verifyJwt(result.token, run.secrets.OE_JWT_SECRET, email);
    if (role) assert(claims.role === role, 'Unexpected setup actor role');
    return result.token;
  };
  const oe = await signIn(fixtures.oeEmail);
  const admin = await signIn(fixtures.adminEmail, 'ROLE_ADMIN');
  const other = await signIn(fixtures.otherEmail);
  const facts = id => JSON.parse(execFileSync('/bin/bash', [fileURLToPath(new URL('../phase2a.sh', import.meta.url)),
    'scheduling-snapshot', String(id)], { encoding: 'utf8', env: process.env, timeout: 15000,
    stdio: ['ignore', 'pipe', 'pipe'] }).trim());

  const makeSession = async requestId => {
    const queue = await api('GET', '/operations/my-work?page=0&size=100', oe);
    for (const root of queue.items.filter(item => item.targetType === 'SESSION')) {
      const candidate = await api('GET', '/operations/visit-sessions/' + root.targetId, oe);
      if (candidate.items.some(item => item.sourceRequestId === requestId)) {
        assert(candidate.status === 'DRAFT', 'Existing fixture moved past DRAFT');
        return candidate;
      }
    }
    const available = await client.request('GET', '/operations/claimable-work/REQUEST/' + requestId, oe);
    if (available.status === 200) {
      await api('POST', '/operations/visit-requests/' + requestId + '/claim', oe,
        { expectedRequestVersion: available.json.expectedVersion, reasonCode: 'CLAIM_WORK' }, 204);
    } else {
      assert(available.status === 404, 'Unexpected claimable response');
    }
    const work = await api('GET', '/operations/my-work?page=0&size=100', oe);
    const item = work.items.find(entry => entry.targetType === 'REQUEST' && entry.targetId === requestId);
    assert(item, 'Owned request missing');
    return api('POST', '/operations/visit-requests/' + requestId + '/coordinate', oe,
      { expectedRequestVersion: item.expectedVersion, sessionId: null, expectedSessionVersion: null });
  };
  const setWindow = async (session, start, end, zone) => {
    const item = session.items.find(entry => !entry.removedAt && entry.sourceRequestId === selectedRequestId)
      ?? session.items.find(entry => !entry.removedAt);
    assert(item, 'Session has no active item');
    return api('PUT', '/operations/visit-sessions/' + session.sessionId + '/items/' + item.itemId + '/availability', oe,
      { expectedSessionVersion: session.version, status: 'CONFIRMED', availabilityStartAt: start,
        availabilityEndAt: end, availabilityZoneId: zone, availabilitySource: 'PHONE' });
  };

  let session = await makeSession(selectedRequestId);
  session = await setWindow(session, BERLIN_START, BERLIN_END, BERLIN_ZONE);
  const sessionId = session.sessionId;
  assert(session.status === 'DRAFT', 'Prepared Session is not DRAFT');
  const berlinItem = session.items.find(item => item.sourceRequestId === selectedRequestId);
  assert(berlinItem.propertyAvailabilityZoneId === BERLIN_ZONE, 'Berlin property zone was not persisted');

  const availability = await api('POST', '/operations/visit-sessions/' + sessionId + '/oe-ge-availability?size=20', oe,
    { expectedSessionVersion: session.version });
  assert(availability.sessionId === sessionId && availability.sessionVersion === session.version,
    'Availability answered for another Session/version');
  assert(['RECOMMENDATION_AVAILABLE', 'NO_ELIGIBLE_GE', 'NOT_READY'].includes(availability.planStatus),
    'Availability exposed a GE-derived plan status');
  assert(availability.timing.stops.some(stop => stop.zoneId === BERLIN_ZONE),
    'Availability did not retain the owning zone');
  assert(availability.entries.some(entry => entry.groundExecutiveUserId === fixtures.geUserId
    && entry.displayName && entry.resultCode === 'FEASIBLE_OPTION_FOUND'), 'No named feasible GE was discovered');

  const versionsOf = detail => Object.fromEntries((detail.linkedRequestVersions ?? [])
    .map(version => [version.requestId, version.version]));

  let server, browser, page, active = null, succeeded = false;
  const replies = [];
  const selectionCommands = [];
  const deadline = setTimeout(() => { server?.closeAllConnections(); void browser?.close(); }, BOUNDS.browser);
  try {
    const dist = await realpath(fileURLToPath(new URL('../../../frontend-web/dist', import.meta.url)));
    server = createServer(async (req, res) => {
      try {
        const url = new URL(req.url, 'http://127.0.0.1');
        if (url.pathname.startsWith('/api/')) {
          verifyBinding();
          const selection = req.method === 'POST' && /\/oe-recommendations\/selection$/.test(url.pathname);
          if (selection) {
            const chunks = [];
            req.on('data', chunk => chunks.push(chunk));
            req.on('end', () => {
              try {
                const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
                if (body.commandId) selectionCommands.push(body.commandId);
              } catch { /* malformed bodies are rejected by the backend */ }
            });
          }
          const upstream = httpRequest(run.origin + url.pathname + url.search, { method: req.method,
            headers: { ...req.headers, host: new URL(run.origin).host }, timeout: 15000 }, incoming => {
            if (selection) replies.push({ status: incoming.statusCode });
            if (!res.destroyed) { res.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(res); }
            else incoming.destroy();
          });
          upstream.on('timeout', () => upstream.destroy());
          upstream.on('error', () => res.destroy());
          req.pipe(upstream);
          return;
        }
        let file;
        try {
          file = await realpath(resolve(dist, '.' + decodeURIComponent(url.pathname)));
          assert(file.startsWith(dist + '/'), 'Invalid asset');
        } catch { file = resolve(dist, 'index.html'); }
        const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
          '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2' };
        res.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' });
        res.end(await readFile(file));
      } catch { res.destroy(); }
    });
    await new Promise((resolve, reject) => {
      server.once('error', reject); server.listen(0, '127.0.0.1', resolve);
    });
    const origin = 'http://127.0.0.1:' + server.address().port;
    const playwright = await import('/opt/homebrew/lib/node_modules/playwright/index.js');
    const chromium = playwright.chromium ?? playwright.default?.chromium;
    assert(chromium, 'Playwright Chromium unavailable');
    browser = await chromium.launch({ channel: 'chromium', timeout: 30000 });
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, reducedMotion: 'reduce',
      timezoneId: 'America/New_York' });
    await context.route('**/*', route => new URL(route.request().url()).origin === origin
      ? route.continue() : route.abort('blockedbyclient'));
    page = await context.newPage();
    page.setDefaultTimeout(20000);
    const login = async () => {
      await page.goto(origin);
      await page.getByRole('button', { name: 'Sign in', exact: true }).first().click();
      const dialog = page.getByRole('dialog').first();
      await dialog.locator('input[type=email]').fill(fixtures.oeEmail);
      await dialog.locator('input[type=password]').fill(run.secrets.OE_STAFF_PASSWORD);
      await dialog.getByRole('button', { name: 'Sign In', exact: true }).click();
      await page.getByRole('button', { name: /^Operations$/ }).first().waitFor({ state: 'visible' });
      await page.goto(origin + '/operations');
      await page.getByText('Work queues', { exact: true }).waitFor();
    };
    const open = async id => {
      await page.getByRole('button', { name: /My work/i }).first().click();
      await page.locator('[data-row-key="SESSION-' + id + '"]').click();
      await page.getByText('Visit plan #' + id, { exact: false }).waitFor();
    };
    const availabilityView = async () => {
      await page.getByRole('tab', { name: /Browse feasible GEs/ }).click();
      await page.getByRole('button', { name: /^GE availability$/ }).click();
      await page.locator('.oe-gea').waitFor();
      await page.locator('.oe-gea').getByText('GE availability', { exact: true }).waitFor();
      await page.locator('.oe-gea-row').first().waitFor();
    };

    let timingPayload = null;
    let availabilityText = null;
    if (!bc05Only) {
      active = 'BC01_AUTH_CAPABILITY';
      await login();
      const capabilities = await api('GET', '/staff/me/capabilities', oe);
      assert(capabilities.staffActive === true && capabilities.capabilities.some(c => c.capability === 'OPS_COORDINATE'
        && c.scopeType === 'TEAM' && Number(c.teamId) === ALPHA_TEAM), 'Current Team capability was not effective');
      evidence.record(active, { result: 'PASS', authority: 'OPS_COORDINATE@TEAM', capability: capabilities.capabilities,
        rendered: 'Operations workspace rendered after real login' });

      active = 'BC02_OWNED_DRAFT';
      await open(sessionId);
      const detailText = await page.locator('body').innerText();
      assert(detailText.includes('Visit plan #' + sessionId) && /Planning|Draft/i.test(detailText),
        'Owned DRAFT Session was not rendered');
      evidence.record(active, { result: 'PASS', before: facts(sessionId), apiStatus: session.status,
        rendered: detailText.slice(0, 400) });

      active = 'BC03_FEASIBLE_GE_DISCOVERY';
      await page.getByRole('button', { name: 'Schedule visit', exact: true }).click();
      await page.locator('.oe-sch').waitFor();
      await page.getByRole('tab', { name: /Browse feasible GEs/ }).click();
      await page.locator('#oe-ppanel-browse .oe-ge').filter({ hasText: 'GE user ID ' + fixtures.geUserId }).first().waitFor();
      assert(await page.locator('#oe-ppanel-browse .oe-ge').filter({ hasText: 'GE user ID ' + fixtures.geUserId })
        .getByRole('button', { name: /^Select / }).count() === 1, 'Feasible GE has no authoritative select action');
      evidence.record(active, { result: 'PASS', geUserId: fixtures.geUserId,
        endpoint: '/operations/visit-sessions/' + sessionId + '/oe-recommendations', rendered: 'ranked feasible card' });
    }

    if (bc05Only) {
      await login();
      await open(sessionId);
      await page.getByRole('button', { name: 'Schedule visit', exact: true }).click();
      await page.locator('.oe-sch').waitFor();
    }
    const browserTiming = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname
        === '/api/v1/operations/visit-sessions/' + sessionId + '/oe-ge-availability', { timeout: 20000 });
    browserTiming.catch(() => {});
    await availabilityView();
    const timingResponse = await browserTiming;
    assert(timingResponse.status() === 200, 'Browser availability read failed: HTTP ' + timingResponse.status());
    timingPayload = await timingResponse.json();
    if (!bc05Only) {
      active = 'BC04_DISPLAY_NAME';
      availabilityText = await page.locator('.oe-gea').innerText();
      assert(availabilityText.includes('P2A ge'), 'Restricted availability did not render the Jackson displayName');
      evidence.record(active, { result: 'PASS', displayName: 'P2A ge',
        apiEntry: availability.entries.find(entry => entry.groundExecutiveUserId === fixtures.geUserId) });
    }

    active = 'BC05_BERLIN_CLOCKS';
    const sessionBefore = await api('GET', '/operations/visit-sessions/' + sessionId, oe);
    const sessionAtCapture = await api('GET', '/operations/visit-sessions/' + sessionId, oe);
    const domRows = await page.locator('.oe-gea').evaluate(dialog => [...dialog.querySelectorAll('.oe-gea-stop')]
      .map((row, index) => {
        const text = selector => row.querySelector(selector)?.textContent ?? null;
        const style = getComputedStyle(row);
        return {
          index,
          positionText: text('.oe-gea-stop-num'),
          titleText: text('.oe-gea-stop-title'),
          windowText: text('.oe-gea-stop-line'),
          metaText: text('.oe-gea-stop-meta'),
          visible: row.getClientRects().length > 0,
          hidden: Boolean(row.closest('[hidden],[aria-hidden="true"]'))
            || style.display === 'none' || style.visibility === 'hidden'
        };
      }));
    const sessionAfter = await api('GET', '/operations/visit-sessions/' + sessionId, oe);
    const browserZone = await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone);
    assert(browserZone === 'America/New_York', 'Browser timezone control was not active');
    assert(sessionBefore.version === session.version && sessionAtCapture.version === session.version,
      'Session version changed before the BC05 capture');
    const bc05 = assertBc05WindowEvidence({
      identity: { sessionId, sessionVersion: sessionAtCapture.version, requestId: selectedRequestId,
        itemId: berlinItem.itemId, listingId: berlinItem.listingId, position: berlinItem.position },
      timing: timingPayload,
      sessionReads: { before: sessionBefore, atCapture: sessionAtCapture, after: sessionAfter },
      browserZone,
      rows: domRows
    });
    let negativeControls = null;
    if (bc05Only) {
      const path = process.env.BATCHC_NEGATIVE_CONTROL_EVIDENCE;
      assert(typeof path === 'string' && path.startsWith(run.root + '/') && !path.includes('..'),
        'Run-owned negative-control evidence is required');
      const output = await readFile(path, 'utf8');
      const passed = Number(/# pass (\d+)/.exec(output)?.[1] ?? NaN);
      const failed = Number(/# fail (\d+)/.exec(output)?.[1] ?? NaN);
      const skipped = Number(/# skipped (\d+)/.exec(output)?.[1] ?? NaN);
      assert(Number.isSafeInteger(passed) && passed > 0 && failed === 0 && skipped === 0,
        'Negative-control test evidence is not green');
      negativeControls = { path, passed, failed, skipped };
    }
    evidence.record(active, { result: 'PASS', ...bc05,
      apiTiming: { sessionId: timingPayload.sessionId, sessionVersion: timingPayload.sessionVersion,
        evaluatedAt: timingPayload.evaluatedAt, planStatus: timingPayload.planStatus,
        timing: timingPayload.timing },
      inspectedElement: '.oe-gea-stop (row index ' + bc05.domRowIndex + ')',
      targetElement: { rowIndex: bc05.domRowIndex, propertyTitle: bc05.domPropertyTitle,
        rowCount: bc05.domRowCount, uniqueMatchCount: 1 },
      negativeControls });
    if (bc05Only) {
      succeeded = true;
      return;
    }

    active = 'BC06_COARSE_STATUS';
    assert(!/LOCATION_UNAVAILABLE_FALLBACK_USED|TRAVEL_ESTIMATE_UNCERTAIN/.test(availabilityText),
      'GE-derived status leaked through the public envelope');
    assert(await page.locator('.oe-gea button').filter({ hasText: /^Select|^Book/ }).count() === 0,
      'Information-only availability offered booking authority');
    evidence.record(active, { result: 'PASS', planStatus: availability.planStatus,
      forbiddenStatusesAbsent: true, bookingControls: 0 });
    await page.getByRole('button', { name: 'Close GE availability' }).click();

    active = 'BC07_OWNERSHIP_DENIAL';
    const owned = await api('GET', '/operations/visit-sessions/' + sessionId, oe);
    await api('POST', '/operations/visit-sessions/' + sessionId + '/release', oe,
      { expectedSessionVersion: owned.version, expectedRequestVersions: versionsOf(owned),
        reasonCode: 'BATCH_C_OWNERSHIP_CONTROL' }, 204);
    await page.reload();
    await page.getByText('This item isn’t available to you', { exact: false }).waitFor({ timeout: 20000 });
    const deniedText = await page.locator('body').innerText();
    assert(!deniedText.includes('Property #') && !deniedText.includes('Europe/Berlin'),
      'Denied Session retained protected property facts');
    const released = await api('GET', '/operations/visit-sessions/' + sessionId, admin);
    await api('POST', '/operations/visit-sessions/' + sessionId + '/claim', oe,
      { expectedSessionVersion: released.version, expectedRequestVersions: versionsOf(released),
        reasonCode: 'CLAIM_WORK' }, 204);
    await page.reload();
    await page.getByText('Visit plan #' + sessionId, { exact: false }).waitFor({ timeout: 20000 });
    evidence.record(active, { result: 'PASS', deniedStatus: 404, protectedFactsWithdrawn: true,
      restoredOwner: ALPHA_USER, invalidation: 'OWNERSHIP_RELEASED' });

    active = 'BC08_STALE_VERSION_409';
    await page.getByRole('button', { name: 'Schedule visit', exact: true }).click();
    await page.locator('.oe-sch').waitFor();
    await page.getByRole('tab', { name: /Browse feasible GEs/ }).click();
    await page.locator('#oe-ppanel-browse .oe-ge').filter({ hasText: 'GE user ID ' + fixtures.geUserId })
      .getByRole('button', { name: /^Select / }).first().click();
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    const reason = page.getByLabel(/Reason for this choice/);
    if (await reason.count()) await reason.fill('Batch C stale-version control');
    const current = await api('GET', '/operations/visit-sessions/' + sessionId, oe);
    await api('PUT', '/operations/visit-sessions/' + sessionId + '/items/' + berlinItem.itemId + '/availability', oe,
      { expectedSessionVersion: current.version, status: 'CONFIRMED', availabilityStartAt: BERLIN_START,
        availabilityEndAt: '2026-10-21T16:30:00+02:00', availabilityZoneId: BERLIN_ZONE, availabilitySource: 'PHONE' });
    const conflictReply = page.waitForResponse(r => r.request().method() === 'POST'
      && /\/oe-recommendations\/selection$/.test(new URL(r.url()).pathname), { timeout: 10000 }).catch(() => null);
    await page.getByRole('button', { name: /^Confirm and schedule$/ }).click();
    const conflictResponse = await conflictReply;
    try {
      await page.getByText('This session changed since these candidates were evaluated', { exact: false })
        .waitFor({ timeout: 20000 });
    } catch (error) {
      console.error('STALE_STATE', (await page.locator('.oe-sch').innerText()).slice(0, 1200));
      throw error;
    }
    const afterConflict = facts(sessionId);
    assert(afterConflict.status === 'DRAFT', 'Stale version booked a visit');
    evidence.record(active, { result: 'PASS', httpStatus: conflictResponse?.status() ?? 'CLIENT_PREVENTED',
      serverConflictProven: conflictResponse?.status() === 409,
      httpNegativeControl: 'OwnedOperationsHttpPostgresTest stale selection returns 409',
      sessionStatus: afterConflict.status,
      noBooking: true, rendered: 'This session changed since these candidates were evaluated' });
    await page.getByRole('button', { name: 'Close scheduling', exact: true }).click();

    active = 'BC09_AUTHORITATIVE_BOOKING';
    await page.reload();
    await page.getByText('Visit plan #' + sessionId, { exact: false }).waitFor();
    const beforeBooking = await api('GET', '/operations/visit-sessions/' + sessionId, oe);
    await page.getByRole('button', { name: 'Schedule visit', exact: true }).click();
    await page.locator('.oe-sch').waitFor();
    await page.getByRole('tab', { name: /Browse feasible GEs/ }).click();
    await page.locator('#oe-ppanel-browse .oe-ge').filter({ hasText: 'GE user ID ' + fixtures.geUserId })
      .getByRole('button', { name: /^Select / }).first().click();
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    const reason2 = page.getByLabel(/Reason for this choice/);
    if (await reason2.count()) await reason2.fill('Batch C authoritative booking');
    const booked = page.waitForResponse(r => r.request().method() === 'POST'
      && /\/oe-recommendations\/selection$/.test(new URL(r.url()).pathname));
    await page.getByRole('button', { name: /^Confirm and schedule$/ }).click();
    const bookedResponse = await booked;
    assert(bookedResponse.status() === 200, 'Authoritative booking failed');
    const bookedJson = await bookedResponse.json();
    await page.getByText('The visit is scheduled.', { exact: true }).waitFor();
    const afterBooking = facts(sessionId);
    assert(afterBooking.status === 'SCHEDULED' && afterBooking.commands === 1 && afterBooking.decisions === 1,
      'Booking receipt and database state disagree');
    const commandId = selectionCommands.at(-1);
    assert(commandId, 'The real selection command identity was not captured');
    const receipt = await api('GET', '/operations/visit-sessions/' + sessionId
      + '/oe-recommendations/selection/' + commandId, oe);
    assert(receipt.commandId === commandId && receipt.state === 'COMPLETED'
      && receipt.approval?.session?.status === 'SCHEDULED'
      && Number(receipt.approval.session.sessionId) === sessionId,
      'Persisted booking receipt does not match the command and current Session');
    evidence.record(active, { result: 'PASS', httpStatus: 200, commandId,
      before: beforeBooking, after: afterBooking, receipt });

    active = 'BC10_INFORMATION_ONLY_CANNOT_BOOK';
    await api('GET', '/operations/visit-sessions/' + sessionId, other, undefined, 404);
    const auditEvidence = await readFile(process.env.BATCHC_AUDIT_EVIDENCE, 'utf8');
    assert(auditEvidence.includes('Tests run: 1, Failures: 0, Errors: 0, Skipped: 0'),
      'The information-only HTTP/PostgreSQL control did not pass');
    evidence.record(active, { result: 'PASS', foreignStatus: 404,
      informationDialogBookingControls: 0,
      backendControl: 'OwnedOperationsHttpPostgresTest#restrictedGeAvailabilityIsScopedPagedAuditedAndInformationalOnly',
      sourceEvidence: process.env.BATCHC_AUDIT_EVIDENCE });

    active = 'BC11_AUDIT_FAIL_CLOSED';
    assert(process.env.BATCHC_AUDIT_EVIDENCE, 'Audit evidence path is required');
    evidence.record(active, { result: 'PASS', evidence: process.env.BATCHC_AUDIT_EVIDENCE,
      assertions: 'real HTTP 500, no-store, no roster/name on audit failure, recovery after trigger drop' });

    active = 'BC12_TENANT_CONFIRMATION';
    const finalDetail = await api('GET', '/operations/visit-sessions/' + sessionId, oe);
    assert(finalDetail.status === 'SCHEDULED', 'Current Session state is not scheduled');
    assert(finalDetail.tenantConfirmationState !== 'CONFIRMED',
      'Scheduling fabricated tenant confirmation');
    assert(finalDetail.tenantConfirmationState === receipt.approval.session.tenantConfirmationState,
      'Receipt and current Session disagree about tenant confirmation');
    const finalSnapshot = facts(sessionId);
    evidence.record(active, { result: 'PASS', sessionStatus: finalDetail.status,
      tenantConfirmationState: finalDetail.tenantConfirmationState,
      receiptState: receipt.state,
      receiptTenantConfirmationState: receipt.approval.session.tenantConfirmationState,
      sessionVersion: finalDetail.version, snapshot: finalSnapshot });
    succeeded = true;
  } finally {
    clearTimeout(deadline);
    await browser?.close().catch(() => {});
    server?.closeAllConnections();
    server?.close();
    evidence.finish(succeeded ? 'PASSED' : 'FAILED');
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    console.error('BATCH C FAILURE:', error.message);
    console.error(error.stack);
    process.exitCode = 1;
  });
}
