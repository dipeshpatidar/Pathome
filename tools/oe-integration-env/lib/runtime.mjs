import { readFileSync, writeFileSync, renameSync, lstatSync, existsSync, realpathSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHmac, timingSafeEqual, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

export const PHASE2A_APPROVED_DB = 'pathome_oe_test_phase2a_20261015';
export const PHASE2A_APPROVED_RUN_DIR = '/private/tmp/pathome-oe-phase2a-20261015';
export const G10_APPROVED_DB = 'pathome_oe_test_g10_20261022';
export const G10_APPROVED_RUN_DIR = '/private/tmp/pathome-oe-g10-20261022';
export const ROUTING_ACCEPTANCE_ID = 'routing-20261023';
export const ROUTING_ACCEPTANCE_FILE = resolve(dirname(fileURLToPath(import.meta.url)), '../routing-acceptance-target.env');
const APPROVED_COORDINATES = new Map([
  [PHASE2A_APPROVED_DB, PHASE2A_APPROVED_RUN_DIR],
  [G10_APPROVED_DB, G10_APPROVED_RUN_DIR]
]);
const ROUTING_KEYS = new Set(['PATHOME_OE_TEST_ACCEPTANCE','PATHOME_OE_TEST_ALLOW_DISPOSABLE',
  'PATHOME_OE_TEST_DB','PATHOME_OE_TEST_DB_HOST','PATHOME_OE_TEST_DB_PORT','PATHOME_OE_TEST_DB_ADMIN_USER',
  'PATHOME_OE_TEST_DB_MIGRATOR_USER','PATHOME_OE_TEST_DB_APP_USER','PATHOME_OE_TEST_RUN_DIR',
  'PATHOME_OE_TEST_RUN_ID','PATHOME_OE_TEST_SCHEMA','PATHOME_OE_TEST_FIXTURE_PREFIX']);
export function routingAcceptanceTarget(env = process.env, file = env.PATHOME_OE_TEST_TARGET_FILE,
  { requireRuntimeEnvironment = true } = {}) {
  if (file !== ROUTING_ACCEPTANCE_FILE) throw Error('Unapproved routing acceptance target file');
  const stat = lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.uid !== process.getuid() || (stat.mode & 0o077))
    throw Error('Routing acceptance target file must be owner-only mode 600');
  const values = {};
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const split = line.indexOf('=');
    if (split < 1 || /[`$"';\\\r]/.test(line)) throw Error('Invalid routing acceptance target data');
    const key = line.slice(0, split), value = line.slice(split + 1);
    if (!ROUTING_KEYS.has(key) || key in values) throw Error('Unknown or duplicate routing acceptance key');
    values[key] = value;
  }
  const expected = {
    PATHOME_OE_TEST_ACCEPTANCE: ROUTING_ACCEPTANCE_ID,
    PATHOME_OE_TEST_ALLOW_DISPOSABLE: 'true',
    PATHOME_OE_TEST_DB: 'pathome_oe_test_c07_correction_20261024',
    PATHOME_OE_TEST_DB_HOST: '127.0.0.1',
    PATHOME_OE_TEST_DB_PORT: '5432',
    PATHOME_OE_TEST_DB_ADMIN_USER: 'postgres',
    PATHOME_OE_TEST_DB_MIGRATOR_USER: 'routing_c07_correction_20261024_01_migrator',
    PATHOME_OE_TEST_DB_APP_USER: 'routing_c07_correction_20261024_01_app',
    PATHOME_OE_TEST_RUN_DIR: '/private/tmp/pathome-routing-c07-correction-20261024-01',
    PATHOME_OE_TEST_RUN_ID: 'routing-c07-correction-20261024-01',
    PATHOME_OE_TEST_SCHEMA: 'routing_c07_correction_20261024_01',
    PATHOME_OE_TEST_FIXTURE_PREFIX: 'routing_c07_correction_20261024_01_'
  };
  for (const [key,value] of Object.entries(expected)) if (values[key] !== value) throw Error('Routing acceptance target differs at '+key);
  for (const [key,value] of Object.entries(expected)) if (env[key] !== value) throw Error('Effective routing acceptance value differs at '+key);
  if (requireRuntimeEnvironment) {
    if (env.SPRING_DATASOURCE_URL !== `jdbc:postgresql://${expected.PATHOME_OE_TEST_DB_HOST}:${expected.PATHOME_OE_TEST_DB_PORT}/${expected.PATHOME_OE_TEST_DB}`)
      throw Error('Routing acceptance datasource mismatch');
    if (env.SPRING_DATASOURCE_USERNAME !== expected.PATHOME_OE_TEST_DB_APP_USER
        || env.SPRING_FLYWAY_USER !== expected.PATHOME_OE_TEST_DB_MIGRATOR_USER)
      throw Error('Routing acceptance role mismatch');
  }
  return expected;
}
export const approvedIntegrationCoordinates = (database, root, env = process.env) => {
  if (env.PATHOME_OE_TEST_ACCEPTANCE === ROUTING_ACCEPTANCE_ID) {
    const target = routingAcceptanceTarget(env, env.PATHOME_OE_TEST_TARGET_FILE,
      { requireRuntimeEnvironment: false });
    return database === target.PATHOME_OE_TEST_DB && root === target.PATHOME_OE_TEST_RUN_DIR;
  }
  return APPROVED_COORDINATES.get(database) === root;
};

export const safePort = (value) => {
  if (!/^[1-9]\d{0,4}$/.test(String(value)) || Number(value) > 65535) throw Error('Invalid port');
  return Number(value);
};
export const safeOrigin = (value, port) => {
  const url = new URL(value);
  if (url.origin !== `http://127.0.0.1:${safePort(port)}` || value !== url.origin
      || url.username || url.password || url.search || url.hash) throw Error('Unapproved HTTP origin');
  return url.origin;
};
export const dataFile = (file) => {
  const stat = lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.uid !== process.getuid() || (stat.mode & 0o077)) throw Error('Private run file required');
  return readFileSync(file, 'utf8');
};
export const readSecrets = (root) => Object.fromEntries(dataFile(`${root}/secrets.env`).split('\n')
  .filter(line => line && !line.startsWith('#')).map(line => {
    const split = line.indexOf('=');
    if (split < 1 || /[`$\r]/.test(line)) throw Error('Invalid secrets data');
    return [line.slice(0, split), line.slice(split + 1)];
  }));
export const validateMedia = secrets => {
  const cloud = secrets.OE_TEST_CLOUDINARY_CLOUD_NAME;
  if (secrets.PATHOME_OE_TEST_MEDIA_MODE !== 'disposable-account'
      || secrets.PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD !== 'true'
      || !/^[a-z0-9_-]+$/.test(cloud ?? '') || cloud === 'hbgzetjy' || /prod|live|stage/.test(cloud)
      || !secrets.OE_TEST_CLOUDINARY_API_KEY || !secrets.OE_TEST_CLOUDINARY_API_SECRET) throw Error('Unapproved disposable media prerequisite');
  return cloud;
};
export const writePrivate = (file, value) => {
  const temporary = `${file}.${randomUUID()}.tmp`;
  writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  renameSync(temporary, file);
};
const system = (cmd, args) => execFileSync(cmd, args, {
  encoding: 'utf8', timeout: 1000, env: { PATH: '/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin', LC_ALL: 'C' }, stdio: ['ignore', 'pipe', 'pipe']
}).trim();
export const processIdentity = pid => {
  if (!/^[1-9]\d*$/.test(String(pid))) throw Error('Invalid PID');
  return system('/bin/ps', ['-p', String(pid), '-o', 'uid=,lstart=,command=']);
};
export const processStamp = pid => system('/bin/ps', ['-p', String(pid), '-o', 'uid=,lstart=']);
export const listenerPid = port => {
  const pids = system('/usr/sbin/lsof', ['-nP', `-iTCP:${safePort(port)}`, '-sTCP:LISTEN', '-t']).split('\n');
  if (new Set(pids).size !== 1 || !/^\d+$/.test(pids[0])) throw Error('Ambiguous listener');
  return Number(pids[0]);
};
const descendant = (child, parent) => {
  for (let n = 0; n < 12 && child > 1; n++) {
    if (child === parent) return true;
    child = Number(system('/bin/ps', ['-p', String(child), '-o', 'ppid=']));
  }
  return false;
};
export const verifyJwt = (token, secret, email) => {
  const [head, body, sig, extra] = String(token).split('.');
  if (extra || !head || !body || !sig) throw Error('Invalid run credential');
  const header = JSON.parse(Buffer.from(head, 'base64url'));
  // JwtUtils uses JJWT Keys.hmacShaKeyFor(UTF-8 secret) and signWith(key), which selects by key size.
  const length = Buffer.byteLength(secret, 'utf8');
  const algorithm = length >= 64 ? 'HS512' : length >= 48 ? 'HS384' : 'HS256';
  if (length < 32 || header.alg !== algorithm) throw Error('Unexpected JWT algorithm');
  const actual = Buffer.from(sig, 'base64url');
  const expected = createHmac({ HS256: 'sha256', HS384: 'sha384', HS512: 'sha512' }[algorithm], secret).update(`${head}.${body}`).digest();
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) throw Error('Backend uses another signing secret');
  const claims = JSON.parse(Buffer.from(body, 'base64url'));
  if (claims.sub !== email || !(claims.exp > Date.now() / 1000) || !(claims.user_id > 0)) throw Error('Invalid run identity');
  return claims;
};
export const verifyBinding = (env = process.env, { requireAttestation = true,
  inspect = { processIdentity, listenerPid, descendant }, now = Date.now() } = {}) => {
  const root = env.PATHOME_OE_TEST_RUN_DIR;
  if (env.PATHOME_OE_TEST_ACCEPTANCE === ROUTING_ACCEPTANCE_ID) {
    if (!approvedIntegrationCoordinates(env.PATHOME_OE_TEST_DB, root, env))
      throw Error('Unapproved integration coordinates');
    routingAcceptanceTarget(env, env.PATHOME_OE_TEST_TARGET_FILE, { requireRuntimeEnvironment: false });
  } else {
    if (!APPROVED_COORDINATES.has(env.PATHOME_OE_TEST_DB))
      throw Error('Unapproved Phase 2A database');
    if (!/^\/(private\/)?tmp\/pathome-oe-[A-Za-z0-9_/-]+$/.test(root ?? '') || root.includes('..'))
      throw Error('Unapproved run directory');
  }
  const canonical = realpathSync(root);
  if (canonical !== root && !(process.platform === 'darwin' && root.startsWith('/tmp/') && canonical === '/private' + root)) throw Error('Symlinked run directory');
  const binding = JSON.parse(dataFile(`${root}/backend-binding.json`));
  const runId = dataFile(`${root}/run-id`).trim();
  const secrets = readSecrets(root);
  const mediaCloudName = env.PATHOME_OE_TEST_ACCEPTANCE === ROUTING_ACCEPTANCE_ID
    ? 'routing-acceptance-disabled' : validateMedia(secrets);
  const origin = safeOrigin(env.P4_BACKEND_URL ?? binding.origin, env.PATHOME_OE_TEST_BACKEND_PORT ?? 8080);
  const age = now - Date.parse(binding.createdAt);
  if (binding.origin !== origin || binding.runId !== runId || secrets.OE_RUN_ID !== runId
      || binding.database !== env.PATHOME_OE_TEST_DB
      || binding.mediaCloudName !== mediaCloudName || !/^[a-z0-9-]{16,64}$/.test(runId)
      || !Number.isInteger(binding.parentPid) || !Number.isInteger(binding.listenerPid)
      || !Number.isFinite(age) || age < -5000 || age > 12 * 60 * 60 * 1000
      || inspect.processIdentity(binding.parentPid) !== binding.parentIdentity
      || inspect.processIdentity(binding.listenerPid) !== binding.listenerIdentity
      || inspect.listenerPid(new URL(origin).port) !== binding.listenerPid
      || !inspect.descendant(binding.listenerPid, binding.parentPid)) throw Error('Stale or foreign backend binding');
  if (requireAttestation) {
    const text = dataFile(`${root}/evidence/target-attestation.txt`);
    for (const line of [`live_current_database=${binding.database}`, `run_id=${runId}`, `backend_origin=${origin}`, `backend_listener_pid=${binding.listenerPid}`]) {
      const lines = text.split('\n');
      if (!lines.includes(line) || lines.filter(value => value.startsWith(line.split('=')[0] + '=')).length !== 1) throw Error('Attestation does not bind this backend run');
    }
  }
  return { binding, secrets, origin, root };
};
export const boundClient = (env = process.env, options) => {
  const context = verifyBinding(env, options);
  const deadline = Date.now() + 540000;
  return { ...context, async request(method, path, token, body, extraHeaders = {}) {
    verifyBinding(env, options);
    if (!path.startsWith('/') || path.includes('://') || Date.now() >= deadline) throw Error('Invalid request or run deadline exceeded');
    const form = body instanceof FormData;
    const response = await fetch(`${context.origin}/api/v1${path}`, {
      method, headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body === undefined || form ? {} : { 'Content-Type': 'application/json' }), ...extraHeaders },
      body: body === undefined ? undefined : form ? body : JSON.stringify(body), redirect: 'error',
      signal: AbortSignal.timeout(Math.max(1, Math.min(15000, deadline - Date.now())))
    });
    const text = await response.text();
    let json = null; try { json = JSON.parse(text); } catch { /* assertions require valid shapes */ }
    return { status: response.status, json, text, headers: Object.fromEntries(response.headers), cacheControl: response.headers.get('cache-control') };
  } };
};

const alive = pid => { try { process.kill(pid, 0); return true; } catch (error) { if (error.code === 'ESRCH') return false; throw error; } };
const optionalListener = port => {
  try { return listenerPid(port); } catch (error) {
    // lsof exit 1 with no output specifically means there is no listener. Other failures stay closed.
    if (error.status === 1 && !String(error.stdout ?? '').trim()) return null;
    throw error;
  }
};
export function recoveryPlan({ pid, runId, database, binding, startup, listener }, inspect) {
  if (!Number.isSafeInteger(pid) || pid <= 1 || !/^[a-z0-9-]{16,64}$/.test(runId)) throw Error('Invalid recovery identity');
  for (const record of [binding,startup].filter(Boolean)) if (record.runId !== runId || record.database !== database || record.parentPid !== pid) throw Error('Foreign recovery record');
  const pids = [];
  if (inspect.alive(pid)) {
    const owned = binding ? inspect.processIdentity(pid) === binding.parentIdentity
      : startup && inspect.processStamp(pid) === startup.parentStamp;
    if (!owned) throw Error('Live PID ownership cannot be proven');
    if (listener && listener !== pid && !inspect.descendant(listener,pid)) throw Error('Foreign listener during recovery');
    // Include all current descendants, even an application that has not opened its listener yet.
    for (const child of inspect.children(pid)) {
      if (!inspect.descendant(child,pid)) throw Error('Process tree changed during recovery');
      pids.push(child);
    }
    pids.push(pid);
  } else if (listener) {
    if (!binding || listener !== binding.listenerPid || inspect.processIdentity(listener) !== binding.listenerIdentity) throw Error('Unbound orphan listener; automatic recovery refused');
    pids.push(listener);
  }
  return [...new Set(pids)];
}
const processChildren = parent => {
  const rows = system('/bin/ps',['-axo','pid=,ppid=']).split('\n').map(line=>line.trim().split(/\s+/).map(Number));
  const found = [], visit = (pid, depth=0) => {
    if (depth > 12 || found.length > 128) throw Error('Ambiguous backend process tree');
    for (const [child,ppid] of rows) if (ppid === pid) { visit(child,depth+1); found.push(child); }
  };
  visit(parent); return found;
};
export function archiveRuntime(root,directory,names) {
  for(const name of names) {
    if(name.includes('/') || existsSync(directory+'/'+name)) throw Error('Recovery archive collision');
    if(existsSync(root+'/'+name)) dataFile(root+'/'+name);
  }
  for(const name of names) if(existsSync(root+'/'+name)) renameSync(root+'/'+name,directory+'/'+name);
}
async function recover(env) {
  const root=env.PATHOME_OE_TEST_RUN_DIR, directory=env.OE_COMMAND_ATTEMPT_DIR;
  // Shell caller proves the live disposable database/run first. Recovery additionally proves every
  // process identity and archives artifacts, rather than deleting evidence or trusting a stale PID.
  const canonical=realpathSync(root);
  if (canonical!==root && !(process.platform==='darwin' && root.startsWith('/tmp/') && canonical==='/private'+root)) throw Error('Symlinked recovery root');
  if (!directory?.startsWith(root+'/evidence/commands/') || directory.includes('..')) throw Error('Supervised recovery required');
  const runId=dataFile(root+'/run-id').trim(), secrets=readSecrets(root);
  if (secrets.OE_RUN_ID!==runId) throw Error('Foreign recovery secrets');
  const read = name => existsSync(root+'/'+name) ? JSON.parse(dataFile(root+'/'+name)) : null;
  if (!existsSync(root+'/backend.pid')) {
    if (optionalListener(env.PATHOME_OE_TEST_BACKEND_PORT??8080)) throw Error('Foreign listener; no owned PID');
    for(const name of ['backend-binding.json','backend-startup.json']) if(existsSync(root+'/'+name)) {
      const record=read(name);
      if(record.runId!==runId || record.database!==env.PATHOME_OE_TEST_DB || alive(record.parentPid)) throw Error('Ambiguous orphan runtime artifact');
      archiveRuntime(root,directory,[name]);
    }
    return;
  }
  const pid=Number(dataFile(root+'/backend.pid').trim());
  const pids=recoveryPlan({pid,runId,database:env.PATHOME_OE_TEST_DB,binding:read('backend-binding.json'),startup:read('backend-startup.json'),
    listener:optionalListener(env.PATHOME_OE_TEST_BACKEND_PORT??8080)},
    {alive,processIdentity,processStamp,descendant,children:processChildren});
  // Snapshot identities immediately before signals; PID reuse is refused, including on descendants.
  const identities=new Map(pids.map(p=>[p,processStamp(p)]));
  for(const p of pids) if(alive(p)) {
    if(processStamp(p)!==identities.get(p)) throw Error('PID reused during shutdown');
    process.kill(p,'SIGTERM');
  }
  const end=Date.now()+8000;
  while(pids.some(alive) && Date.now()<end) await new Promise(resolve=>setTimeout(resolve,100));
  if(pids.some(alive)) throw Error('Owned backend shutdown deadline exceeded; evidence retained');
  archiveRuntime(root,directory,['backend.pid','backend-binding.json','backend-startup.json']);
}

async function main(command) {
  const env = process.env, root = env.PATHOME_OE_TEST_RUN_DIR;
  if (command === 'verify') { verifyBinding(env, { requireAttestation: false }); return; }
  if (command === 'stop' || command === 'recover') { await recover(env); return; }
  if (command === 'record-startup') {
    const parentPid=Number(dataFile(root+'/backend.pid').trim()),runId=dataFile(root+'/run-id').trim();
    if (!Number.isSafeInteger(parentPid) || parentPid<=1 || readSecrets(root).OE_RUN_ID!==runId) throw Error('Invalid startup identity');
    const stamp=processStamp(parentPid);
    if(Number(stamp.trim().split(/\s+/)[0])!==process.getuid()) throw Error('Foreign startup owner');
    writePrivate(root+'/backend-startup.json',{runId,database:env.PATHOME_OE_TEST_DB,parentPid,parentStamp:stamp,createdAt:new Date().toISOString()});
    return;
  }
  if (command === 'record') {
    const port = safePort(env.PATHOME_OE_TEST_BACKEND_PORT ?? 8080);
    const parentPid = Number(dataFile(`${root}/backend.pid`).trim());
    const pid = listenerPid(port);
    if (!descendant(pid, parentPid)) throw Error('Listener is not the launched backend');
    const parentIdentity = processIdentity(parentPid), listenerIdentity = processIdentity(pid);
    if (!parentIdentity.includes('mvn') && !parentIdentity.includes('java')) throw Error('Foreign parent process');
    const secrets = readSecrets(root), runId = dataFile(`${root}/run-id`).trim();
    const mediaCloudName = env.PATHOME_OE_TEST_ACCEPTANCE === ROUTING_ACCEPTANCE_ID
      ? 'routing-acceptance-disabled' : validateMedia(secrets);
    if (secrets.OE_RUN_ID !== runId) throw Error('Foreign secrets');
    const origin = `http://127.0.0.1:${port}`;
    // Process/listener ownership is proven before this authentication-only request.
    const response = await fetch(`${origin}/api/v1/auth/login`, { method: 'POST', redirect: 'error',
      headers: { 'Content-Type': 'application/json' }, signal: AbortSignal.timeout(5000),
      body: JSON.stringify({ email: secrets.OE_BOOTSTRAP_ADMIN_EMAIL, password: secrets.OE_BOOTSTRAP_ADMIN_PASSWORD }) });
    if (response.status !== 200) throw Error('Run bootstrap authentication failed');
    const auth = await response.json();
    verifyJwt(auth.token, secrets.OE_JWT_SECRET, secrets.OE_BOOTSTRAP_ADMIN_EMAIL);
    writePrivate(`${root}/backend-binding.json`, { runId, database: env.PATHOME_OE_TEST_DB, origin,
      mediaCloudName,
      parentPid, listenerPid: pid, parentIdentity, listenerIdentity, createdAt: new Date().toISOString() });
    return;
  }
  throw Error('Unknown binding command');
}
if (process.argv[1] === fileURLToPath(import.meta.url)) main(process.argv[2]).catch(() => { console.error('REFUSED: backend binding could not be proven'); process.exitCode = 90; });
