/** Genuine React interactions. HTTP proxy streams backend status/headers/body without substitution. */
import { createServer, request as httpRequest } from 'node:http';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile, realpath } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyBinding, dataFile, boundClient, verifyJwt } from '../lib/runtime.mjs';
import { Evidence, snapshot, successfulEvidence } from './evidence.mjs';
import { bounded, BOUNDS } from '../lib/lifecycle.mjs';
import { SCENARIOS } from './manifest.mjs';

export const BROWSER_SCENARIOS = [
  ['B01', 'Real login and capability routing'], ['B02', 'My Work and Claimable Work'],
  ['B03', 'Claim, Release and claim again'], ['B04', 'Lessor contact reveal'],
  ['B05', 'Coordinate Request into DRAFT Session'], ['B06', 'Add, reorder and remove a derived stop'],
  ['B07', 'Record complete property availability'], ['B08', 'Discover and select a real eligible GE'],
  ['B09', 'Confirm scheduling with an explicitly interrupted real acknowledgement'],
  ['B12', 'Reconcile an uncertain booking without automatically resending it'], ['B10', 'Tenant confirmation'],
  ['B11', 'Real account change with an outstanding old response']
].map(([id, note]) => ({ id, note, kind: 'browser', actor: id === 'B10' ? 'plain-tenant' : id === 'B11' ? 'oe-bravo' : 'oe-alpha', method: 'UI', apiPath: null }));

export const safeBrowserDiagnostic = (error, secrets = {}) => {
  let message = String(error?.message ?? error ?? 'unknown').split(/\r?\n/, 1)[0];
  for (const value of Object.values(secrets)) if (typeof value === 'string' && value) message = message.replaceAll(value, '[redacted]');
  return message.replace(/[A-Za-z0-9._-]{18,}/g, '[redacted]').slice(0, 180) || 'unknown';
};

export async function runBrowser({ resumeScheduling = false } = {}) {
  const overallEnd = Date.now() + BOUNDS.browser;
  if (process.env.PATHOME_OE_TEST_PHASE2A_LIVE !== 'true') throw Error('Explicit browser live opt-in required');
  const run = verifyBinding();
  const apiReport = successfulEvidence(run.root, 'API', dataFile);
  if (!apiReport.fixturesFile?.startsWith(run.root + '/evidence/attempts/') || apiReport.fixturesFile.includes('..')) throw Error('Invalid browser fixture provenance');
  const fixtures = JSON.parse(dataFile(apiReport.fixturesFile));
  if (fixtures.runId !== run.binding.runId) throw Error('Browser fixtures are from another run');
  if (apiReport.runId !== run.binding.runId || apiReport.counts.PASS !== 82 || apiReport.counts.FAIL !== 0
      || apiReport.counts['NOT TESTED'] !== 0 || apiReport.records.length !== 82
      || !SCENARIOS.every(s => apiReport.records.filter(r => r.id === s.id && r.result === 'PASS').length === 1)) {
    throw Error('Browser requires complete API fixture/coverage evidence for this run');
  }
  let previous = null;
  if (resumeScheduling) {
    const pointer = JSON.parse(dataFile(run.root + '/evidence/latest-attempt-react_browser.json'));
    if (!pointer.file.startsWith(run.root + '/evidence/attempts/') || pointer.file.includes('..')) throw Error('Invalid prior browser evidence');
    previous = JSON.parse(dataFile(pointer.file));
    if (previous.runId !== run.binding.runId || !['B09', 'B12'].every(id => previous.records.some(r => r.id === id && r.result === 'PASS')))
      throw Error('Tail requires actual B09/B12 success in this same disposable run');
  }
  const evidence = new Evidence(resumeScheduling ? BROWSER_SCENARIOS.filter(s => ['B10','B11'].includes(s.id)) : BROWSER_SCENARIOS,
    run.root + '/evidence/phase2a-browser.json', resumeScheduling ? 'REACT_BROWSER_TAIL' : 'REACT_BROWSER',
    { runId: run.binding.runId, database: run.binding.database, backend: run.origin });
  evidence.watchInterruptions();
  let server, browser, succeeded = false;
  const deadline = setTimeout(() => {
    evidence.finish('TIMED_OUT'); server?.closeAllConnections();
    void bounded(() => browser?.close(), BOUNDS.browserShutdown, 'Browser deadline shutdown').catch(() => {});
  }, Math.max(1, overallEnd - Date.now()));
  try {
  const dist = await realpath(fileURLToPath(new URL('../../../frontend-web/dist', import.meta.url)));
  let holdNext = false, pending = null, loseSelection = false, lostSelection = null, selectionPosts = 0, selectionReceipt = null;
  let withholdSelectionAcknowledgements = false, withholdCommandReads = false, committedBooking = null;
  const selectionRequests = [];
  const databaseSnapshot = id => JSON.parse(execFileSync('/bin/bash',
    [fileURLToPath(new URL('../phase2a.sh', import.meta.url)), 'scheduling-snapshot', String(id)],
    { encoding: 'utf8', env: process.env, timeout: 15000 }).trim());
  const selectionResponses = [];
  let switchStarted = false, oldAbortedBeforeSwitch = false;
  const failures = [];
  server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://127.0.0.1');
      if (url.pathname.startsWith('/api/')) {
        verifyBinding();
        const isSelection = req.method === 'POST' && /\/oe-recommendations\/selection$/.test(url.pathname);
        if (isSelection) selectionPosts++;
        const selectionSequence = selectionPosts;
        if (isSelection) {
          const chunks = [];
          req.on('data', chunk => chunks.push(chunk));
          req.on('end', () => {
            const raw = Buffer.concat(chunks);
            let payload = null; try { payload = JSON.parse(raw.toString()); } catch { /* invalid remains diagnostic */ }
            selectionRequests.push({ sequence: selectionSequence, requestId: 'wire-' + selectionSequence,
              commandId: payload?.commandId ?? null, payloadSha256: createHash('sha256').update(raw).digest('hex') });
          });
        }
        const upstream = httpRequest(run.origin + url.pathname + url.search, { method: req.method,
          headers: { ...req.headers, host: new URL(run.origin).host }, timeout: 15000 }, incoming => {
          if (isSelection && selectionResponses.length < 5) selectionResponses.push({ sequence: selectionSequence, httpStatus: incoming.statusCode });
          if (loseSelection && isSelection) {
            loseSelection = false;
            // A deliberate transport failure AFTER the real backend decides. No replacement HTTP
            // status, body or headers are manufactured. The UI must report the outcome as unknown.
            let bytes = 0; const chunks = [];
            incoming.on('data', chunk => { bytes += chunk.length; if (bytes > 1048576) incoming.destroy(); else chunks.push(chunk); });
            incoming.on('end', () => {
              let json = null; try { json = JSON.parse(Buffer.concat(chunks).toString()); } catch { /* rejected below */ }
              selectionReceipt = { httpStatus: incoming.statusCode, sessionId: json?.session?.sessionId ?? null,
                sessionStatus: json?.session?.status ?? null };
              lostSelection?.({ status: incoming.statusCode, json, method: req.method, endpoint: url.pathname,
                transport: 'ACKNOWLEDGEMENT_DROPPED_AFTER_REAL_BACKEND_RESPONSE' });
              res.destroy();
            });
            return;
          }
          if (withholdSelectionAcknowledgements && isSelection) {
            // Chromium can retry a closed reused socket before JS sees a result. Withhold that
            // real replay acknowledgement too, so the real client deadline exercises uncertainty.
            // The upstream result is recorded above; no status/body is substituted.
            incoming.resume(); return;
          }
          if (withholdCommandReads && req.method === 'GET' && /\/oe-recommendations\/selection\/[a-f0-9-]+$/.test(url.pathname)) {
            incoming.resume(); return;
          }
          const relay = () => { if (!res.destroyed) { res.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(res); } else incoming.destroy(); };
          if (holdNext && req.method === 'GET' && url.pathname === '/api/v1/operations/my-work'
              && url.searchParams.get('attentionOnly') !== 'true') {
            holdNext = false; incoming.pause();
            res.once('close',()=>{if(!switchStarted)oldAbortedBeforeSwitch=true;});
            const finishRelay = () => new Promise(resolve => {
              if (res.destroyed) { incoming.destroy(); resolve('ABORTED_BY_BROWSER'); return; }
              res.once('finish', () => resolve('DELIVERED_AFTER_ACCOUNT_CHANGE'));
              res.once('close', () => resolve('CLOSED_AFTER_ACCOUNT_CHANGE'));
              relay();
            });
            const timeout = setTimeout(() => { pending = null; void finishRelay(); }, 15000);
            pending = () => { clearTimeout(timeout); pending = null; return finishRelay(); };
          } else relay();
        });
        upstream.on('timeout', () => upstream.destroy(Error('Proxy deadline')));
        upstream.on('error', () => res.destroy());
        req.pipe(upstream); return;
      }
      if (req.method !== 'GET') { res.destroy(); return; }
      let file;
      try {
        file = await realpath(resolve(dist, '.' + decodeURIComponent(url.pathname)));
        if (!file.startsWith(dist + '/')) throw Error('Invalid asset');
      } catch { file = resolve(dist, 'index.html'); }
      const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2' };
      res.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' });
      res.end(await readFile(file));
    } catch { res.destroy(); }
  });
  const playwright = await import('/opt/homebrew/lib/node_modules/playwright/index.js');
  const chromium = playwright.chromium ?? playwright.default?.chromium;
  if (!chromium) throw Error('Playwright Chromium is unavailable');
  browser = await bounded(() => chromium.launch({ channel: 'chromium', timeout: BOUNDS.browserStartup,
    args: ['--log-net-log=' + evidence.file.replace(/[^/]+$/, 'chromium-netlog.json')] }),
    Math.min(BOUNDS.browserStartup, overallEnd - Date.now()), 'Browser startup');
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const origin = 'http://127.0.0.1:' + server.address().port;
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, reducedMotion: 'reduce' });
  // No API response is intercepted. This guard only refuses external browser destinations.
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort('blockedbyclient'));
  context.setDefaultTimeout(12000);
  const page = await context.newPage();
  let browserSelectionRequests = 0;
  page.on('request', request => {
    if (request.method() === 'POST' && /\/oe-recommendations\/selection$/.test(new URL(request.url()).pathname)) browserSelectionRequests++;
  });
  let initialMyWork, initialClaimable;
  let active = null, sessionId, directItemId, authority = null, b09Checkpoint = 'NOT_STARTED', b11Checkpoint = 'NOT_STARTED';
  const assert = (condition, message) => { if (!condition) throw Error(message); };
  const checkSchedulingLayout = async state => {
    const checks = [];
    for (const width of [320, 375, 390, 414, 430, 768, 1024, 1280, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const measured = await page.evaluate(() => {
        const dialog = document.querySelector('.oe-sch'); const rect = dialog.getBoundingClientRect();
        const controls = [...dialog.querySelectorAll('button,summary')].map(node => {
          const r = node.getBoundingClientRect();
          return { width: r.width, height: r.height, named: Boolean(node.getAttribute('aria-label') || node.textContent.trim()) };
        }).filter(r => r.width && r.height);
        return { viewport: innerWidth, scrollWidth: document.documentElement.scrollWidth,
          dialogLeft: rect.left, dialogRight: rect.right, controls,
          modal: dialog.getAttribute('aria-modal'), labelled: Boolean(document.getElementById(dialog.getAttribute('aria-labelledby'))) };
      });
      assert(measured.scrollWidth <= width && measured.dialogLeft >= -1 && measured.dialogRight <= width + 1,
        'Scheduling ' + state + ' overflows viewport ' + width);
      assert(measured.controls.every(c => c.width >= 44 && c.height >= 44 && c.named)
        && measured.modal === 'true' && measured.labelled, 'Scheduling controls failed target/name/modal checks at ' + width);
      checks.push({ width, height: 900, overflow: false, controlsAccessible: true });
      if ([375,1280].includes(width)) await page.locator('.oe-sch').screenshot({ path: evidence.directory + '/' + state + '-' + width + '.png' });
    }
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.keyboard.press('Tab');
    assert(await page.evaluate(() => Boolean(document.activeElement?.closest('.oe-sch'))), 'Tab escaped scheduling dialog');
    await page.keyboard.press('Shift+Tab');
    assert(await page.evaluate(() => Boolean(document.activeElement?.closest('.oe-sch'))), 'Reverse Tab escaped scheduling dialog');
    return checks;
  };
  const waitAction = async (tab, method, path, operation) => {
    const received = tab.waitForResponse(response => response.request().method() === method
      && path.test(new URL(response.url()).pathname), { timeout: 15000 });
    received.catch(() => {});
    await operation(); const response = await received;
    let json = null; try { json = await response.json(); } catch { /* 204 has no body */ }
    assert(response.status() >= 200 && response.status() < 300, 'Real backend rejected UI command: ' + response.status());
    return { status: response.status(), json, endpoint: new URL(response.url()).pathname, method,
      cacheControl: response.headers()['cache-control'], headers: response.headers() };
  };
  const step = async (id, operation) => {
    active = id;
    const result = await bounded(operation, Math.min(BOUNDS.scenario, overallEnd - Date.now()), 'Browser scenario ' + id);
    evidence.record(id, { result: 'PASS', authority, method: result.method, endpoint: result.endpoint,
      httpStatus: result.status, before: result.before ?? null, after: result.after ?? snapshot(result.json?.session ?? result.json),
      transport: result.transport ?? 'NORMAL',
      supportingCalls: result.supportingCalls ?? [],
      requiredFields: result.json ? Object.keys(result.json).filter(key => !/token|mobile|name|email/i.test(key)) : [] });
    active = null; return result;
  };
  const login = async (tab, email, staff = true) => {
    await tab.getByRole('button', { name: 'Sign in', exact: true }).first().click();
    const dialog = tab.getByRole('dialog').first();
    await dialog.locator('input[type=email]').fill(email);
    await dialog.locator('input[type=password]').fill(run.secrets.OE_STAFF_PASSWORD);
    const capability = tab.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/staff/me/capabilities' && r.status() === 200);
    capability.catch(() => {});
    await waitAction(tab, 'POST', /\/auth\/login$/, () => dialog.getByRole('button', { name: 'Sign In', exact: true }).click());
    const response = await capability; const json = await response.json();
    assert(typeof json.staffActive === 'boolean' && Array.isArray(json.capabilities) && (!staff || json.staffActive), 'No live capability facts');
    authority = { staffActive: json.staffActive, capabilities: json.capabilities };
    return { status: response.status(), json, method: 'GET', endpoint: '/api/v1/staff/me/capabilities' };
  };
  const openRequest = async () => {
    await page.getByRole('button', { name: /Claimable work/i }).first().click();
    await page.locator('[data-row-key="REQUEST-' + fixtures.requestId + '"]').click();
  };
  try {
    await page.goto(origin);
    if (resumeScheduling) {
      await login(page, fixtures.oeEmail);
      await page.getByRole('button', { name: /^Operations$/ }).first().click();
      await page.locator('.oe-subnav').waitFor();
      sessionId = previous.records.find(r => r.id === 'B09').after.sessionId;
      const facts = databaseSnapshot(sessionId);
      assert(facts.commands === 1 && facts.decisions === 1 && facts.status === 'SCHEDULED', 'Tail booking truth changed');
      evidence.provenance.priorB09AndB12 = previous.startedAt;
    } else {
    await step('B01', async () => {
      const result = await login(page, fixtures.oeEmail);
      assert(result.json.capabilities.some(c => c.capability === 'OPS_COORDINATE'), 'OE capability absent');
      // Opening Operations mounts the workspace and initiates these reads. Queue navigation only
      // changes the visible view, so both observers must exist before this actual trigger.
      initialMyWork = observeQueue(page, 'my-work'); initialClaimable = observeQueue(page, 'claimable-work');
      await page.getByRole('button', { name: /^Operations$/ }).first().click();
      await page.locator('.oe-subnav').waitFor(); return result;
    });
    await step('B02', async () => {
      const result = await queueResult(await initialMyWork); await initialClaimable;
      await page.getByRole('button', { name: /My work/i }).first().click();
      assert(Array.isArray(result.json.items) && Number.isInteger(result.json.totalCount), 'My Work response is malformed'); return result;
    });
    await step('B03', async () => {
      await openRequest();
      await waitAction(page, 'POST', /\/claim$/, () => page.getByRole('button', { name: /^Claim request$/ }).click());
      await page.getByRole('button', { name: /My work/i }).first().click();
      await page.locator('[data-row-key="REQUEST-' + fixtures.requestId + '"]').click();
      await waitAction(page, 'POST', /\/release$/, () => page.getByRole('button', { name: /^Release request$/ }).click());
      await openRequest();
      const refreshed = observeQueue(page, 'my-work');
      const result = await waitAction(page, 'POST', /\/claim$/, () => page.getByRole('button', { name: /^Claim request$/ }).click());
      const owned = await queueResult(await refreshed);
      await page.getByRole('button', { name: /My work/i }).first().click();
      const item = owned.json.items.find(i => i.targetType === 'REQUEST' && i.targetId === fixtures.requestId);
      assert(item && Number.isInteger(item.expectedVersion), 'Claim was not reflected in authoritative My Work');
      return { ...result, after: snapshot(item), supportingCalls: [{ method: 'GET', endpoint: owned.endpoint, httpStatus: owned.status }] };
    });
    await page.getByRole('button', { name: /My work/i }).first().click();
    await page.locator('[data-row-key="REQUEST-' + fixtures.requestId + '"]').click();
    await step('B04', async () => {
      const result = await waitAction(page, 'GET', /\/lessor-contact$/, () => page.getByRole('button', { name: /^Show lessor contact/ }).first().click());
      assert(result.json.status === 'CONTACT_AVAILABLE' && result.json.listingId === fixtures.listingId && result.json.mobile, 'Contact not returned by backend');
      assert(String(result.cacheControl).includes('no-store'), 'Backend contact header is cacheable'); return result;
    });
    await step('B05', async () => {
      await page.getByRole('button', { name: 'Coordinate into a new session', exact: true }).click();
      const result = await waitAction(page, 'POST', /\/coordinate$/, () => page.getByRole('button', { name: 'Create draft session', exact: true }).click());
      assert(result.json.status === 'DRAFT' && result.json.sessionId > 0, 'No DRAFT Session');
      sessionId = result.json.sessionId; directItemId = result.json.items.find(i => i.sourceRequestId === fixtures.requestId).itemId; return result;
    });
    await step('B06', async () => {
      await page.getByRole('button', { name: 'Add a stop', exact: true }).last().click();
      await page.getByLabel('Find a property', { exact: true }).fill(fixtures.localityName);
      const choice = page.locator('label.oe-choice').filter({ hasText: new RegExp('#' + fixtures.nearbyListingId + '\\b') }).locator('input[name=oe-add-listing-choice]');
      await choice.check();
      const added = await waitAction(page, 'POST', /\/items$/, () => page.getByRole('button', { name: 'Add stop', exact: true }).click());
      const derived = added.json.items.find(i => i.listingId === fixtures.nearbyListingId && !i.removedAt);
      assert(derived && derived.derivedFromRequestId === fixtures.requestId, 'Derived stop absent');
      await page.getByRole('button', { name: /^Move .* earlier$/ }).last().click();
      const ordered = await waitAction(page, 'PUT', /\/items\/order$/, () => page.getByRole('button', { name: 'Save order', exact: true }).click());
      assert(ordered.json.items.find(i => i.itemId === derived.itemId).position !== derived.position, 'Order did not change');
      await page.getByRole('button', { name: /^Remove / }).last().click();
      const removed = await waitAction(page, 'DELETE', /\/items\/\d+$/, () => page.getByRole('button', { name: /^(Remove stop|Confirm removal)$/ }).click());
      assert(removed.json.items.some(i => i.itemId === derived.itemId && i.removedAt), 'Derived removal absent');
      assert(removed.json.items.some(i => i.itemId === directItemId && !i.removedAt), 'Direct stop was removed');
      return { ...removed, before: snapshot(added.json), supportingCalls: [added, ordered].map(r => ({ method: r.method, endpoint: r.endpoint, httpStatus: r.status })) };
    });
    await step('B07', async () => {
      await page.locator('#oe-avail-btn-' + directItemId).click();
      await page.getByRole('radio', { name: 'Available, record the window now', exact: true }).check();
      await page.getByLabel('Available from', { exact: true }).fill(fixtures.availabilityStart.slice(0, 16));
      await page.getByLabel('Available until', { exact: true }).fill(fixtures.availabilityEnd.slice(0, 16));
      await page.getByLabel('Time zone', { exact: true }).selectOption('Asia/Kolkata');
      await page.getByLabel('Recorded source', { exact: true }).selectOption('PHONE');
      const result = await waitAction(page, 'PUT', /\/availability$/, () => page.getByRole('button', { name: 'Save availability', exact: true }).click());
      assert(result.json.items.find(i => i.itemId === directItemId)?.propertyAvailabilityStartAt, 'Window not persisted'); return result;
    });
    await step('B08', async () => {
      const result = await waitAction(page, 'POST', /\/oe-recommendations$/, () => page.getByRole('button', { name: 'Schedule visit', exact: true }).click());
      assert(result.json.candidates.some(c => c.groundExecutiveUserId === fixtures.geUserId && c.feasibilityStatus === 'FEASIBLE'), 'Real eligible GE absent');
      await page.locator('.oe-ge').filter({ hasText: 'GE user ID ' + fixtures.geUserId }).getByRole('button', { name: /^Select / }).first().click();
      await page.getByRole('button', { name: 'Continue', exact: true }).click(); return result;
    });
    await step('B09', async () => {
      const reason = page.getByLabel(/Reason for this choice/);
      if (await reason.count()) await reason.fill('Disposable browser acceptance candidate selection');
      b09Checkpoint = 'REASON_PREPARED';
      const receipt = new Promise(resolve => { lostSelection = resolve; });
      loseSelection = true;
      withholdSelectionAcknowledgements = true;
      const before = databaseSnapshot(sessionId);
      b09Checkpoint = 'ACK_DROP_ARMED';
      // Two real rapid clicks exercise the UI guard; Chromium may separately replay the one fetch.
      await page.getByRole('button', { name: /^Confirm/ }).dblclick({ delay: 30 });
      b09Checkpoint = 'CONFIRM_CLICKED';
      let timeout;
      const result = await Promise.race([receipt, new Promise((_, reject) => { timeout = setTimeout(() => reject(Error('No real scheduling receipt')), 15000); })]).finally(() => clearTimeout(timeout));
      b09Checkpoint = 'BACKEND_RECEIPT';
      assert(result.status === 200, 'Real backend did not accept scheduling');
      assert(result.json.session?.sessionId === sessionId && result.json.session.status === 'SCHEDULED', 'Booking not confirmed by backend');
      b09Checkpoint = 'WAITING_FOR_UNCERTAIN_UI';
      await page.getByText('We can’t tell whether this was scheduled', { exact: true }).waitFor();
      b09Checkpoint = 'UNCERTAIN_UI_VISIBLE';
      assert(!await page.getByText(/nothing was booked/i).count(), 'Uncertainty must not claim noncompletion');
      committedBooking = databaseSnapshot(sessionId);
      assert(committedBooking.status === 'SCHEDULED' && committedBooking.version === before.version + 1
        && committedBooking.commands === 1 && committedBooking.completedCommands === 1 && committedBooking.decisions === 1
        && committedBooking.outbox === 2 && committedBooking.notifications <= 2, 'Booking/receipt/side effects were not exactly once');
      assert(browserSelectionRequests === 1 && selectionRequests.length >= 1
        && selectionRequests.every(r => r.commandId && r.commandId === selectionRequests[0].commandId
          && r.payloadSha256 === selectionRequests[0].payloadSha256)
        && selectionResponses.every(r => r.httpStatus === 200), 'Transport replay changed command or failed to return the original success');
      const viewportChecks = await checkSchedulingLayout('uncertain');
      return { ...result, before, after: committedBooking,
        supportingCalls: [{ operatorGesture: 'DOUBLE_CLICK', browserRequests: browserSelectionRequests,
          wireRequests: selectionRequests, responses: selectionResponses, viewportChecks }] };
    });
    await step('B12', async () => {
      withholdCommandReads = true;
      await page.getByRole('button', { name: 'Check the session', exact: true }).click();
      await page.getByText('The booking could not be verified. Check again when the connection is available.', { exact: true }).waitFor();
      assert(!await page.getByText(/nothing was booked/i).count(), 'A failed verification must remain uncertain');
      withholdCommandReads = false;
      // A genuine reload loses component memory. The saved tab intent must restore verification,
      // and must never generate a replacement command, even though the Session is already booked.
      await page.reload();
      await page.locator('.oe-subnav').waitFor();
      await page.getByRole('button', { name: /My work/i }).first().click();
      await page.locator('[data-row-key="SESSION-' + sessionId + '"]').click();
      await page.getByText('We can’t tell whether this was scheduled', { exact: true }).waitFor();
      const postsBefore = selectionPosts;
      const commandRead = page.waitForResponse(r => r.request().method() === 'GET' && /\/oe-recommendations\/selection\/[a-f0-9-]+$/.test(new URL(r.url()).pathname));
      const result = await waitAction(page, 'GET', /\/operations\/visit-sessions\/\d+$/,
        () => page.getByRole('button', { name: 'Check the session', exact: true }).click());
      const receipt = await commandRead; const receiptJson = await receipt.json();
      assert(receipt.status() === 200 && receiptJson.state === 'COMPLETED'
        && receiptJson.commandId === selectionRequests[0].commandId, 'Authoritative command receipt was not recovered');
      assert(result.json.sessionId === sessionId && result.json.status === 'SCHEDULED', 'Authoritative reconciliation did not reveal the accepted booking');
      await page.getByText('The visit is scheduled.', { exact: true }).waitFor();
      const after = databaseSnapshot(sessionId);
      assert(selectionPosts === postsBefore && browserSelectionRequests === 1, 'Reconciliation resent the command');
      assert(after.version === committedBooking.version && after.commands === 1 && after.decisions === 1
        && after.outbox === committedBooking.outbox && after.audit === committedBooking.audit && after.notifications <= 2,
        'Reconciliation duplicated a transition or business side effect');
      const viewportChecks = await checkSchedulingLayout('confirmed');
      return { ...result, before: committedBooking, after,
        supportingCalls: [{ method: 'GET', endpoint: new URL(receipt.url()).pathname, httpStatus: receipt.status(),
          commandId: receiptJson.commandId, state: receiptJson.state, browserRequests: browserSelectionRequests,
          failedVerification: 'REAL_RECEIPT_ACK_WITHHELD_UNTIL_CLIENT_DEADLINE', wirePosts: selectionPosts, viewportChecks }] };
    });
    }
    const oeAuthority = authority;
    await step('B10', async () => {
      // API setup proposes a new time as the real legacy Admin; confirmation itself uses the actual
      // TenantDashboard control and its own genuine login in an independent browser context.
      const api = boundClient().request;
      const admin = await api('POST', '/auth/login', null, { email: fixtures.adminEmail, password: run.secrets.OE_STAFF_PASSWORD });
      assert(admin.status === 200 && verifyJwt(admin.json.token, run.secrets.OE_JWT_SECRET, fixtures.adminEmail).role === 'ROLE_ADMIN', 'Admin setup identity unavailable');
      const current = await api('GET', '/operations/visit-sessions/' + sessionId, admin.json.token);
      const proposedAt = Date.parse(current.json.scheduledAt) === Date.parse(fixtures.staleRescheduleAt)
        ? fixtures.scheduledAt : fixtures.staleRescheduleAt;
      const proposed = await api('PUT', '/operations/visit-sessions/' + sessionId + '/schedule', admin.json.token,
        { expectedSessionVersion: current.json.version, scheduledAt: proposedAt, zoneId: fixtures.availabilityZone });
      assert(proposed.status === 200 && proposed.json.tenantConfirmationState === 'PENDING', 'No real time proposal to confirm');
      const tenantContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
      await tenantContext.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort('blockedbyclient'));
      const tenantPage = await tenantContext.newPage(); tenantPage.setDefaultTimeout(12000);
      try {
        await tenantPage.goto(origin); await login(tenantPage, fixtures.tenantEmail, false);
        await tenantPage.getByRole('button', { name: 'Visit requests', exact: true }).first().click();
        const result = await waitAction(tenantPage, 'POST', /\/tenant\/visit-sessions\/\d+\/confirmation$/,
          () => tenantPage.locator('#visit-session-' + sessionId).getByRole('button', { name: 'Accept new time', exact: true }).click());
        assert(result.json.sessionId === sessionId && result.json.tenantConfirmationState === 'CONFIRMED', 'Tenant UI did not persist confirmation');
        return { ...result, supportingCalls: [
          { mode: 'API_SETUP', method: 'PUT', endpoint: '/operations/visit-sessions/' + sessionId + '/schedule', httpStatus: proposed.status }
        ] };
      } catch (error) {
        await tenantPage.screenshot({ path: evidence.directory + '/tenant-failure.png', fullPage: true }).catch(() => {});
        const visible = await tenantPage.locator('#visit-session-' + sessionId).count().catch(() => 0);
        throw Error('Tenant confirmation: ' + safeBrowserDiagnostic(error, run.secrets) + '; sessionCards=' + visible);
      } finally { await bounded(() => tenantContext.close(), BOUNDS.browserShutdown, 'Tenant browser shutdown'); }
    });
    authority = oeAuthority;
    const closeScheduling = page.getByRole('button', { name: 'Close scheduling', exact: true });
    if (await closeScheduling.count()) await closeScheduling.click();
    await step('B11', async () => {
      b11Checkpoint = 'ARMED';
      holdNext = true;
      let oldRequest = null, settleOld, oldSettledBeforeSwitch = false;
      const oldCompletion = new Promise(resolve => { settleOld = resolve; });
      const observeOld = request => {
        if (!oldRequest && request.method() === 'GET' && new URL(request.url()).pathname === '/api/v1/operations/my-work'
            && new URL(request.url()).searchParams.get('attentionOnly') !== 'true') oldRequest = request;
      };
      const failedOld = request => {
        if (request === oldRequest) { if (!switchStarted) oldSettledBeforeSwitch = true; settleOld('BROWSER_ABORTED_OLD_RESPONSE'); }
      };
      const finishedOld = request => {
        if (request === oldRequest) { if (!switchStarted) oldSettledBeforeSwitch = true; settleOld('BROWSER_RECEIVED_OLD_RESPONSE'); }
      };
      page.on('request', observeOld); page.on('requestfailed', failedOld); page.on('requestfinished', finishedOld);
      try {
      // A real browser reload remounts React and starts a new old-account queue read. It does not
      // alter application storage or fabricate an API response.
      await page.reload({ waitUntil: 'domcontentloaded', timeout: 15000 });
      const waitUntil = Date.now() + 10000;
      while (!pending && Date.now() < waitUntil) await new Promise(resolve => setTimeout(resolve, 50));
      assert(pending, 'No real outstanding old-account response was held');
      b11Checkpoint = 'OLD_RESPONSE_HELD';
      const peer = await context.newPage(); await peer.goto(origin);
      await peer.getByRole('button', { name: /Profile menu for/i }).first().click();
      assert(!oldAbortedBeforeSwitch, 'Old request ended before the real account switch');
      assert(!oldSettledBeforeSwitch, 'Observed old browser request settled before the actual switch');
      switchStarted = true;
      await peer.getByRole('button', { name: 'Log Out Session', exact: true }).click();
      const result = await login(peer, fixtures.otherEmail);
      b11Checkpoint = 'NEW_ACCOUNT_LOGGED_IN';
      assert(pending, 'Old response was released before the new account authenticated');
      assert(await page.locator('[data-row-key="SESSION-' + sessionId + '"]').count() === 0, 'Old account row survived logout/account change');
      const delivery = await pending();
      const browserDelivery = await bounded(() => oldCompletion, BOUNDS.http, 'Held old browser request settlement');
      assert(!oldSettledBeforeSwitch, 'Old request did not span the actual account switch');
      b11Checkpoint = 'OLD_BROWSER_REQUEST_SETTLED';
      await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 0)));
      assert(await page.locator('[data-row-key="SESSION-' + sessionId + '"]').count() === 0, 'Delivered old response repopulated the new account before refresh');
      const refreshed = observeQueue(page, 'my-work');
      await page.reload({ waitUntil: 'domcontentloaded', timeout: 15000 });
      b11Checkpoint = 'WAITING_NEW_ACCOUNT_QUEUE';
      // Logout intentionally retires the old Operations entry. Open the new account's actual
      // capability-gated surface before expecting its workspace queues to mount.
      await page.getByRole('button', { name: /^Operations$/ }).first().click();
      const own = await queueResult(await refreshed);
      assert(!own.json.items.some(i => i.targetType === 'SESSION' && i.targetId === sessionId), 'New account API contains old work');
      await page.getByRole('button', { name: /My work/i }).first().click();
      await page.locator('.oe-subnav').waitFor();
      assert(await page.locator('[data-row-key="SESSION-' + sessionId + '"]').count() === 0, 'Old response repopulated the new account');
      await peer.close(); return { ...result, transport: delivery,
        supportingCalls: [{ proxyDelivery: delivery, browserDelivery }] };
      } finally {
        page.off('request', observeOld); page.off('requestfailed', failedOld); page.off('requestfinished', finishedOld);
      }
    });
  } catch (error) {
    await page.screenshot({ path: evidence.directory + '/failure.png', fullPage: true }).catch(() => {});
    failures.push('Browser scenario failed; remaining scenarios have no acceptance evidence');
    const categories = active === 'B09' ? await page.locator('.oe-conflict-cat').allTextContents().catch(() => []) : [];
    const category = categories[0] ?? null;
    const uiCategory = category === null ? 'ABSENT'
      : ['Access', 'Outcome unknown', 'Review required', 'Not sent', 'Conflict', 'Refused'].includes(category) ? category : 'OTHER';
    if (active && evidence.records.get(active)?.result === 'NOT TESTED') evidence.record(active,
      { result: 'FAIL', authority,
        reason: 'UI interaction or live backend assertion failed; no response was substituted; checkpoint=' +
          (active === 'B09' ? b09Checkpoint : active === 'B11' ? b11Checkpoint : active) + '; diagnostic=' + safeBrowserDiagnostic(error, run.secrets),
        supportingCalls: active === 'B09' ? [{ method: 'POST', endpoint: '/api/v1/operations/visit-sessions/{id}/oe-recommendations/selection',
          observedPosts: selectionPosts, browserRequests: browserSelectionRequests, responses: selectionResponses,
          uiCategory, dialogPresent: await page.locator('.oe-sch').count().catch(() => 0) > 0,
          httpStatus: selectionReceipt?.httpStatus ?? null,
          backendStatus: selectionReceipt?.sessionStatus ?? null,
          transport: selectionReceipt ? 'ACKNOWLEDGEMENT_DROPPED_AFTER_REAL_BACKEND_RESPONSE' : 'NO_BACKEND_RECEIPT' }] : [] });
  } finally {
    clearTimeout(deadline); pending?.(); await bounded(() => browser.close(), BOUNDS.browserShutdown, 'Browser shutdown');
    server.closeAllConnections(); await bounded(() => new Promise(resolve => server.close(resolve)), 2000, 'Proxy shutdown'); evidence.flush();
  }
  if (failures.length || !evidence.complete()) process.exitCode = 1;
  else succeeded = true;
  } finally {
    clearTimeout(deadline);
    await bounded(() => browser?.close(), BOUNDS.browserShutdown, 'Final browser shutdown').catch(() => {});
    if (server?.listening) { server.closeAllConnections(); await bounded(() => new Promise(resolve => server.close(resolve)), 2000, 'Final proxy shutdown'); }
    evidence.finish(succeeded ? 'PASSED' : 'FAILED');
  }
}
export const observeQueue = (page, queue) => {
  const observed = page.waitForResponse(response => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/operations/' + queue
    && new URL(response.url()).searchParams.get('attentionOnly') !== 'true', { timeout: 15000 });
  // Observers can outlive an earlier failing UI action; keep their rejection handled until awaited.
  observed.catch(() => {}); return observed;
};
const queueResult = async response => {
  if (response.status() !== 200) throw Error('Queue read failed');
  const json = await response.json();
  if (!Array.isArray(json?.items)) throw Error('Malformed queue response');
  return { status: 200, json, method: 'GET', endpoint: new URL(response.url()).pathname };
};
if (process.argv[1] === fileURLToPath(import.meta.url)) runBrowser({ resumeScheduling: process.argv.includes('--scheduling-tail') })
  .catch(() => { console.error('REFUSED: browser prerequisites or binding unavailable'); process.exitCode = 90; });
