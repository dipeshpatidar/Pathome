/**
 * Bounded genuine P1 uploader journey:
 * synthetic password login -> built React -> real Spring Boot -> disposable PostgreSQL.
 *
 * The script uses only the positively reserved loopback disposable database, creates one run-owned
 * schema, uses synthetic identity/role/profile/grant data, makes no media-provider request and drops
 * only its own schema. Evidence is written outside the repository.
 */
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import playwright from '/opt/homebrew/lib/node_modules/playwright/index.js';

const { chromium } = playwright;
const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
const DIST = join(ROOT, 'frontend-web/dist');
const DB = {
  host: 'localhost',
  port: '5432',
  user: 'pathome',
  name: 'pathome_oe_test_integration'
};
const RUN = `${new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)}-${Math.random()
  .toString(16).slice(2, 10)}`;
const SCHEMA = `p1_uploader_e2e_${RUN.replace(/[^a-z0-9]/g, '').slice(0, 24)}`;
const BACKEND_PORT = 18080;
const PROXY_PORT = 18081;
const ORIGIN = `http://127.0.0.1:${PROXY_PORT}`;
const EVIDENCE_DIR = join('/private/tmp', `pathome-uploader-closure-${RUN}`);
const checks = [];
const sha256 = async path => createHash('sha256').update(await readFile(path)).digest('hex');

const psql = (args, input) => execFileSync('psql', [
  '-h', DB.host, '-p', DB.port, '-U', DB.user, '-d', DB.name,
  '-v', 'ON_ERROR_STOP=1',
  ...args
], {
  input,
  encoding: 'utf8',
  stdio: input == null ? ['ignore', 'pipe', 'pipe'] : ['pipe', 'pipe', 'pipe']
}).trim();

const check = (label, ok, detail = '') => {
  checks.push({ label, pass: Boolean(ok), detail });
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` (${detail})` : ''}`);
  if (!ok) throw new Error(`${label}${detail ? `: ${detail}` : ''}`);
};

const api = async (path, options = {}, token) => {
  const response = await fetch(`http://127.0.0.1:${BACKEND_PORT}${path}`, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {})
    }
  });
  const text = await response.text();
  let body = text;
  try { body = text ? JSON.parse(text) : null; } catch {}
  return { status: response.status, body, text };
};

const waitForBackend = async (backendLogs) => {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${BACKEND_PORT}/api/v1/properties?page=0&size=1`);
      if (response.status === 200) return;
    } catch {}
    if (backendLogs.some(line => /APPLICATION FAILED TO START|BUILD FAILURE/.test(line))) {
      throw new Error(`Backend failed to start:\n${backendLogs.slice(-40).join('\n')}`);
    }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`Backend did not become ready:\n${backendLogs.slice(-40).join('\n')}`);
};

const startProxy = () => new Promise(resolve => {
  const contentTypes = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.ico': 'image/x-icon',
    '.woff2': 'font/woff2'
  };
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, ORIGIN);
    if (url.pathname.startsWith('/api/')) {
      const body = [];
      for await (const chunk of request) body.push(chunk);
      const upstream = await fetch(`http://127.0.0.1:${BACKEND_PORT}${url.pathname}${url.search}`, {
        method: request.method,
        headers: request.headers,
        body: ['GET', 'HEAD'].includes(request.method) ? undefined : Buffer.concat(body)
      });
      response.statusCode = upstream.status;
      upstream.headers.forEach((value, key) => {
        if (!['content-length', 'transfer-encoding'].includes(key)) response.setHeader(key, value);
      });
      response.end(Buffer.from(await upstream.arrayBuffer()));
      return;
    }
    const requested = url.pathname === '/' ? '/index.html' : url.pathname;
    try {
      const payload = await readFile(join(DIST, requested));
      response.statusCode = 200;
      response.setHeader('Content-Type', contentTypes[extname(requested)] || 'application/octet-stream');
      response.end(payload);
    } catch {
      response.statusCode = 200;
      response.setHeader('Content-Type', 'text/html; charset=utf-8');
      response.end(await readFile(join(DIST, 'index.html')));
    }
  });
  server.listen(PROXY_PORT, '127.0.0.1', () => resolve(server));
});

let backend;
let proxy;
let browser;
let schemaCreated = false;
try {
  console.log(`P1 uploader genuine synthetic journey\nschema=${SCHEMA}`);
  mkdir(EVIDENCE_DIR, { recursive: true, mode: 0o700 });
  const guardEnvironment = {
    ...process.env,
    PATHOME_OE_TEST_ALLOW_DISPOSABLE: 'true',
    PATHOME_OE_TEST_DB: DB.name,
    PATHOME_OE_TEST_DB_HOST: DB.host,
    PATHOME_OE_TEST_DB_PORT: DB.port,
    PATHOME_OE_TEST_DB_APP_USER: DB.user,
    PATHOME_OE_TEST_RUN_DIR: '/private/tmp/pathome-oe-test'
  };
  execFileSync('bash', [
    join(ROOT, 'tools/oe-integration-env/lib/db-guard.sh'),
    'check',
    '--destructive'
  ], { env: guardEnvironment, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  const liveAttestation = psql(['-Atqc',
    "select current_database() || '|' || current_user || '|' || host(inet_server_addr()) || '|' "
    + "|| (select count(*) from pathome_oe_test_env where env_marker='DISPOSABLE' "
    + "and db_name=current_database());"
  ]);
  check('project target guard and live disposable marker passed before schema creation',
    liveAttestation === `${DB.name}|${DB.user}|::1|1`, liveAttestation);
  for (const port of [BACKEND_PORT, PROXY_PORT]) {
    try {
      const owner = execFileSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'],
        { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
      if (owner) throw new Error(`Port ${port} is already owned by PID ${owner.split('\n')[0]}`);
    } catch (error) {
      if (error.status !== 1) throw error;
    }
  }
  const assetFiles = await readdir(join(DIST, 'assets'));
  const buildManifest = {};
  for (const asset of assetFiles.filter(name => name.endsWith('.js') || name.endsWith('.css'))) {
    buildManifest[`dist/assets/${asset}`] = await sha256(join(DIST, 'assets', asset));
  }
  buildManifest['dist/index.html'] = await sha256(join(DIST, 'index.html'));
  const sourceManifest = {};
  for (const relative of [
    'frontend-web/src/hooks/usePropertyDraft.ts',
    'frontend-web/src/services/draftService.ts',
    'frontend-web/src/services/localDraftStorage.ts',
    'frontend-web/src/components/MasterAdminDashboard.tsx',
    'frontend-web/src/components/BatchPropertyIngestionStudio.tsx',
    'backend/src/main/java/com/indore/pathome/spaces/controller/PropertyController.java',
    'backend/src/main/java/com/indore/pathome/spaces/service/FailedUploadService.java'
  ]) {
    sourceManifest[relative] = await sha256(join(ROOT, relative));
  }
  await writeFile(join(EVIDENCE_DIR, 'target-attestation-before.json'), JSON.stringify({
    target: liveAttestation,
    schema: SCHEMA,
    guard: 'tools/oe-integration-env/lib/db-guard.sh check --destructive',
    capturedAt: new Date().toISOString(),
    buildManifest,
    sourceManifest
  }, null, 2), { mode: 0o600 });
  console.log('  PASS  positively reserved loopback disposable target');
  psql(['-c', `create schema ${SCHEMA}`]);
  schemaCreated = true;
  psql(['-c', `set search_path to ${SCHEMA},public`, '-f',
    join(ROOT, 'backend/src/test/resources/db/baseline/pathome-v1-legacy-core.sql')]);

  const backendLogs = [];
  backend = spawn('mvn', [
    '-q', '-f', join(ROOT, 'backend/pom.xml'), 'spring-boot:run',
    `-Dspring-boot.run.arguments=--server.port=${BACKEND_PORT}`
  ], {
    cwd: ROOT,
    detached: true,
    env: {
      ...process.env,
      SPRING_DATASOURCE_URL: `jdbc:postgresql://${DB.host}:${DB.port}/${DB.name}?currentSchema=${SCHEMA},public`,
      SPRING_DATASOURCE_USERNAME: DB.user,
      SPRING_DATASOURCE_PASSWORD: '',
      SPRING_FLYWAY_BASELINE_ON_MIGRATE: 'true',
      SPRING_FLYWAY_BASELINE_VERSION: '1',
      SPRING_JPA_HIBERNATE_DDL_AUTO: 'none',
      APP_JWT_SECRET: 'p1-uploader-e2e-jwt-secret-32-bytes-minimum',
      PATHOME_VISIT_OTP_HMAC_SECRET: 'p1-uploader-e2e-otp-secret-32-bytes',
      CLOUDINARY_CLOUD_NAME: 'p1-uploader-disabled',
      CLOUDINARY_API_KEY: 'p1-uploader-disabled',
      CLOUDINARY_API_SECRET: 'p1-uploader-disabled',
      APP_FIRST_ADMIN_PROVISIONING_ENABLED: 'false',
      PATHOME_VISIT_OUTBOX_POLL_DELAY_MS: '3600000',
      PATHOME_VISIT_NO_SHOW_SETTLEMENT_DELAY_MS: '3600000'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  for (const stream of [backend.stdout, backend.stderr]) {
    stream.on('data', chunk => {
      for (const line of chunk.toString().split('\n')) if (line.trim()) backendLogs.push(line);
    });
  }
  await waitForBackend(backendLogs);
  console.log('  PASS  genuine Spring Boot started on isolated schema');

  const email = `p1-e2e-${RUN}@example.test`;
  const password = 'Synthetic-Uploader-Pass-2026!';
  const registration = await api('/api/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({
      email,
      password,
      fullName: 'P1 Synthetic Admin',
      phoneNumber: `98765${String(Math.floor(Math.random() * 100000)).padStart(5, '0')}`
    })
  });
  check('real password registration created the synthetic User',
    registration.status === 200 && registration.body?.userId > 0, `status=${registration.status}`);
  const userId = registration.body.userId;
  psql(['-c', `
    set search_path to ${SCHEMA};
    update users set role='ROLE_ADMIN' where id=${userId};
    insert into employee_profiles
      (user_id, role_type, base_salary, closed_deals_count, staff_active,
       staff_activated_at, version)
    values (${userId}, 'LEGACY_ADMIN', 0, 0, true, clock_timestamp(), 0);
    insert into staff_access_grants
      (user_id, capability, scope_type, effective_at, created_at,
       grant_reason_code, provisioning_source)
    values (${userId}, 'STAFF_ADMIN', 'GLOBAL', clock_timestamp() - interval '1 minute',
            clock_timestamp(), 'P1_E2E_SYNTHETIC', 'ADMIN_API');
  `]);
  console.log('  PASS  synthetic role, active profile and global grant provisioned in disposable schema');

  proxy = await startProxy();
  browser = await chromium.launch({ channel: 'chromium' });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const network = [];
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/api/')) network.push({
      method: response.request().method(),
      path: url.pathname,
      status: response.status(),
      auth: response.request().headers().authorization || ''
    });
  });

  await page.goto(ORIGIN, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: /Sign in/i }).first().click();
  await page.locator('#pathome-auth-email').fill(email);
  await page.locator('#pathome-auth-password').fill(password);
  await page.getByRole('dialog').getByRole('button', { name: 'Sign In', exact: true }).click();
  await page.waitForFunction(() => localStorage.getItem('pathome_auth_token')?.length > 20);
  const token = await page.evaluate(() => localStorage.getItem('pathome_auth_token'));
  check('real browser login returned a synthetic Admin token',
    network.some(call => call.path === '/api/v1/auth/login' && call.status === 200));

  await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
  const prompt = page.getByPlaceholder(/Type or edit property details/);
  await prompt.waitFor({ timeout: 20000 });
  const singleText = '2 BHK flat in Vijay Nagar Indore for rent 22000, deposit 44000, owner 9826012345';
  await prompt.fill(singleText);
  await page.waitForTimeout(1400);
  const singleDraftId = await page.evaluate((adminEmail) =>
    localStorage.getItem(`pathome_active_draft_single_${adminEmail}`), email);
  check('built React persisted a fresh text-only SINGLE draft',
    Boolean(singleDraftId) && network.some(call => call.method === 'POST'
      && call.path === '/api/v1/admin/drafts' && call.status === 200),
    singleDraftId || 'missing draft id');
  const resumedSingle = await api(`/api/v1/admin/drafts/${encodeURIComponent(singleDraftId)}`, {}, token);
  check('fresh SINGLE draft resumes from PostgreSQL with its text',
    resumedSingle.status === 200 && resumedSingle.text.includes(singleText),
    `status=${resumedSingle.status}`);

  await page.getByRole('button', { name: /Review property details/i }).click();
  await page.getByRole('button', { name: /Publish Reviewed Listing/i }).waitFor({ timeout: 20000 });
  const singleCreateResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/properties/create-from-parsed');
  await page.getByRole('button', { name: /Publish Reviewed Listing/i }).click();
  const singleCreate = await singleCreateResponse;
  const singleCreated = await singleCreate.json();
  check('genuine React reviewed-text single creation succeeded',
    singleCreate.status() === 200 && Number(singleCreated.propertyId) > 0,
    `status=${singleCreate.status()}`);

  const batchText = [
    '2 BHK flat in Vijay Nagar, Indore, rent 25000, deposit 50000, owner 98260 11111',
    'next property',
    '3 BHK flat in Palasia, Indore, rent 35000, deposit 70000'
  ].join('\n');
  await prompt.fill(batchText);
  await page.waitForTimeout(100);
  const batchParseResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/properties/parse-batch');
  await page.getByRole('button', { name: /Review multiple properties/i }).click();
  const batchParse = await batchParseResponse;
  const parsedBatchBody = await batchParse.json();
  check('actual React bulk text operation reached the genuine parse endpoint',
    batchParse.status() === 200 && Array.isArray(parsedBatchBody) && parsedBatchBody.length === 2,
    `status=${batchParse.status()} count=${Array.isArray(parsedBatchBody) ? parsedBatchBody.length : 'not-array'}`);
  const batchPrompt = page.getByPlaceholder(/Type, paste, or dictate property details here/i);
  await batchPrompt.waitFor({ timeout: 20000 });
  check('actual React bulk editor retained the entered text',
    (await batchPrompt.inputValue()).includes('next property'));
  const rentEditor = page.locator('input[placeholder="Enter monthly rent"]:visible').first();
  const batchAutosaveResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/admin/drafts'
    && response.request().method() === 'POST'
    && (response.request().postData() || '').includes('"draftType":"BATCH"'));
  await rentEditor.fill('26000');
  const batchAutosave = await batchAutosaveResponse;
  check('actual React bulk autosave committed the fresh BATCH draft before publish',
    batchAutosave.status() === 200, `status=${batchAutosave.status()}`);
  check('actual React bulk review accepted a manual field edit',
    await rentEditor.inputValue() === '26000');
  await page.getByText('I reviewed these details and confirm this property is ready to publish.',
    { exact: true }).first().click();
  await page.getByText('Ready to publish', { exact: true }).first().waitFor({ timeout: 15000 });
  await page.locator('button').filter({ hasText: '3 BHK Palasia' }).first().click();
  await page.locator('input[placeholder="Enter mobile number"]:visible').first().fill('9826022222');
  await page.locator('input[placeholder="Enter security deposit"]:visible').first().fill('0');
  await page.getByText('I reviewed these details and confirm this property is ready to publish.',
    { exact: true }).first().click();
  await page.getByText(/2 properties confirmed and ready to publish/i)
    .first().waitFor({ timeout: 15000 });
  check('two React-reviewed cards are confirmed before the mixed server-validation batch',
    /2 properties confirmed and ready to publish/i.test(await page.locator('body').innerText()));
  const batchDraftId = await page.evaluate(adminEmail =>
    localStorage.getItem(`pathome_active_draft_batch_${adminEmail}`), email);
  check('actual React bulk editor created a fresh text-only BATCH draft',
    Boolean(batchDraftId)
      && network.some(call => call.method === 'POST'
        && call.path === '/api/v1/admin/drafts'), batchDraftId || 'missing');

  const batchPublishButton = page.locator('button:visible')
    .filter({ hasText: /Publish all 2 (?:confirmed )?properties/i }).first();
  try {
    await batchPublishButton.waitFor({ timeout: 15000 });
  } catch (error) {
    await writeFile(join(EVIDENCE_DIR, 'bulk-publish-button-timeout.txt'),
      await page.locator('body').innerText(), { mode: 0o600 });
    throw error;
  }
  const batchPublishResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/properties/create-batch');
  await batchPublishButton.click();
  const batchPublisher = await batchPublishResponse;
  const batch = {
    status: batchPublisher.status(),
    body: await batchPublisher.json()
  };
  check('actual React mixed-validity batch preserved per-card partial success',
    batch.status === 200 && batch.body?.successCount === 1 && batch.body?.failedCount === 1,
    `status=${batch.status} success=${batch.body?.successCount} failed=${batch.body?.failedCount}`);

  const listingCountBeforeRevocation = Number(psql([
    '-Atqc', `set search_path to ${SCHEMA}; select count(*) from listings`
  ]).split('\n').pop());
  psql(['-c', `
    set search_path to ${SCHEMA};
    update staff_access_grants
       set revoked_at=clock_timestamp(), revoked_by_user_id=${userId},
           revoke_reason_code='P1_E2E_REVOKE'
     where user_id=${userId} and capability='STAFF_ADMIN'
       and scope_type='GLOBAL' and revoked_at is null;
  `]);
  const uiDeniedResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/admin/drafts'
    && response.request().method() === 'GET');
  await page.goto(`${ORIGIN}/admin`, { waitUntil: 'domcontentloaded' });
  const uiDraftDenial = await uiDeniedResponse;
  await page.getByText(/Access denied/i).first().waitFor({ timeout: 10000 }).catch(() => {});
  check('current synthetic browser session receives UI permission denial after revocation',
    uiDraftDenial.status() === 403
      && /access denied|no longer have permission|do not have permission/i.test(
        await page.locator('body').innerText()));
  check('current-session 403 keeps the retained browser credential',
    await page.evaluate(() => localStorage.getItem('pathome_auth_token')) === token);
  const deniedCandidate = {
    adminVerified: true,
    title: 'Synthetic retained-token denial',
    description: 'Synthetic denied create after current-session revocation',
    bhk: '2 BHK',
    type: 'Flat',
    status: 'LIVE',
    city: 'Indore',
    sector: 'Vijay Nagar',
    ownerPhone: '+91 98260 12345',
    rentAmount: 23000,
    depositVal: '46000 Security Deposit',
    mediaUrls: []
  };
  const denied = await api('/api/v1/properties/create-from-parsed', {
    method: 'POST',
    body: JSON.stringify(deniedCandidate)
  }, token);
  const listingCountAfterRevocation = Number(psql([
    '-Atqc', `set search_path to ${SCHEMA}; select count(*) from listings`
  ]).split('\n').pop());
  check('retained synthetic Admin token is denied after current grant revocation',
    denied.status === 403 && listingCountAfterRevocation === listingCountBeforeRevocation,
    `status=${denied.status}`);

  const persisted = JSON.parse(psql([
    '-Atqc',
    `set search_path to ${SCHEMA};
     select json_build_object(
       'user_id', (select id from users where email='${email.replaceAll("'", "''")}'),
       'single_listing', (select status from listings where id=${singleCreated.propertyId}),
       'batch_listing_count', (select count(*) from listings where origin_draft_id like '${batchDraftId}%'),
       'single_draft_status', (select status from property_upload_drafts where draft_id='${singleDraftId.replaceAll("'", "''")}')
     );`
  ]).split('\n').pop());
  check('PostgreSQL contains the genuine listing and independent batch result',
    persisted.user_id === userId
      && persisted.batch_listing_count === 1
      && ['PUBLISHING', 'PUBLISHED'].includes(persisted.single_draft_status),
    JSON.stringify(persisted));

  const evidence = {
    run: RUN,
    schema: SCHEMA,
    target: 'loopback pathome_oe_test_integration / DISPOSABLE',
    userId,
    token: '[REDACTED]',
    singleDraftId,
    singlePropertyId: singleCreated.propertyId,
    batchDraftId,
    batchResponse: batch.body,
    listingCountBeforeRevocation,
    listingCountAfterRevocation,
    persisted,
    liveAttestation,
    buildManifest,
    sourceManifest,
    assertionCount: checks.filter(item => item.pass).length,
    checks,
    provider: 'NOT CONTACTED',
    capturedAt: new Date().toISOString()
  };
  await writeFile(join(EVIDENCE_DIR, 'uploader-closure.json'),
    JSON.stringify(evidence, null, 2), { mode: 0o600 });
  console.log(`  evidence: ${join(EVIDENCE_DIR, 'uploader-closure.json')}`);
} finally {
  try { await browser?.close(); } catch {}
  try { proxy?.close(); } catch {}
  try {
    if (backend && backend.exitCode === null) {
      try { process.kill(-backend.pid, 'SIGTERM'); }
      catch { try { backend.kill('SIGTERM'); } catch {} }
      await Promise.race([
        new Promise(resolve => backend.once('exit', resolve)),
        new Promise(resolve => setTimeout(resolve, 2500))
      ]);
      if (backend.exitCode === null) {
        try { process.kill(-backend.pid, 'SIGKILL'); }
        catch { try { backend.kill('SIGKILL'); } catch {} }
      }
    }
  } catch {
    try { backend?.kill('SIGTERM'); } catch {}
  }
  if (schemaCreated) {
    try {
      psql(['-c', `drop schema if exists ${SCHEMA} cascade`]);
      schemaCreated = false;
      const postAttestation = psql(['-Atqc',
        "select current_database() || '|' || current_user || '|' "
        + "|| (select count(*) from information_schema.schemata "
        + `where schema_name='${SCHEMA.replaceAll("'", "''")}');`
      ]);
      await writeFile(join(EVIDENCE_DIR, 'target-attestation-after.json'), JSON.stringify({
        target: postAttestation,
        droppedSchema: SCHEMA,
        remainingOwnedSchemaCount: Number(postAttestation.split('|').pop()),
        capturedAt: new Date().toISOString()
      }, null, 2), { mode: 0o600 });
      console.log(`  PASS  dropped run-owned disposable schema ${SCHEMA}`);
    } catch (error) {
      console.error(`  FAIL  schema cleanup: ${error.message}`);
      process.exitCode = 1;
    }
  }
}

if (process.exitCode) throw new Error('P1 uploader genuine journey failed');
