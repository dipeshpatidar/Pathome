/** Local-only preflight facts. No HTTP, database client, Docker or application is started here. */
import { readFileSync, readdirSync, lstatSync, existsSync, accessSync, constants, realpathSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { dataFile, validateMedia, safePort, writePrivate, PHASE2A_APPROVED_DB, PHASE2A_APPROVED_RUN_DIR } from './runtime.mjs';
import { privateDirectory } from './lifecycle.mjs';

const repo = fileURLToPath(new URL('../../../', import.meta.url));
const mediaKeys = ['PATHOME_OE_TEST_MEDIA_MODE','PATHOME_OE_TEST_ALLOW_EXTERNAL_MEDIA_UPLOAD','OE_TEST_CLOUDINARY_CLOUD_NAME','OE_TEST_CLOUDINARY_API_KEY','OE_TEST_CLOUDINARY_API_SECRET'];
export function mediaConfiguration(file) {
  if (!file?.startsWith('/') || file.startsWith(repo) || file.endsWith('/.env.local')) throw Error('Explicit external disposable-media file required');
  if ((lstatSync(file).mode & 0o777) !== 0o600) throw Error('Disposable-media file requires mode 600');
  const values = {};
  for (const line of dataFile(file).split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const split = line.indexOf('='), key = line.slice(0, split), value = line.slice(split + 1);
    if (split < 1 || !mediaKeys.includes(key) || key in values || /[`$"';\\\r]/.test(value)) throw Error('Invalid literal disposable-media configuration');
    values[key] = value;
  }
  validateMedia(values); return values;
}
export const digest = value => createHash('sha256').update(value).digest('hex');
export function treeDigest(paths) {
  const hash = createHash('sha256');
  const visit = path => {
    const stat = lstatSync(path);
    if (stat.isSymbolicLink()) throw Error('Symlinked build input refused');
    if (stat.isDirectory()) for (const entry of readdirSync(path).sort()) visit(join(path, entry));
    else { hash.update(path); hash.update(readFileSync(path)); }
  };
  paths.forEach(visit); return hash.digest('hex');
}
const inputPaths = () => ['backend/pom.xml','backend/src/main','frontend-web/src','frontend-web/package.json',
  'frontend-web/package-lock.json','frontend-web/index.html', ...readdirSync(repo + 'frontend-web').filter(f => /^(vite|tsconfig|tailwind|postcss).*\.(ts|js|json)$/.test(f)).map(f => 'frontend-web/' + f)]
  .map(p => repo + p);
const target = env => ({ database: env.PATHOME_OE_TEST_DB, host: env.PATHOME_OE_TEST_DB_HOST,
  port: env.PATHOME_OE_TEST_DB_PORT, adminUser: env.PATHOME_OE_TEST_DB_ADMIN_USER, appUser: env.PATHOME_OE_TEST_DB_APP_USER,
  backendPort: safePort(env.PATHOME_OE_TEST_BACKEND_PORT ?? 8080), root: env.PATHOME_OE_TEST_RUN_DIR });
const systemEnv = { PATH: '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin', LC_ALL: 'C' };
export function vacantPort(port, inspect = (p) => {
  try { return execFileSync('/usr/sbin/lsof', ['-nP', '-iTCP:' + p, '-sTCP:LISTEN', '-t'], { encoding: 'utf8', env: systemEnv, timeout: 2000 }).trim(); }
  catch (error) { if (error.status === 1 && !String(error.stdout ?? '').trim()) return ''; throw Error('Cannot prove vacant backend port'); }
}) { if (inspect(safePort(port))) throw Error('Backend port occupied; stop/recover the owned run first'); }

export function precheck(env = process.env) {
  const facts = target(env);
  if (facts.database !== PHASE2A_APPROVED_DB || facts.root !== PHASE2A_APPROVED_RUN_DIR
      || !['localhost','127.0.0.1'].includes(facts.host) || facts.port !== '5432') throw Error('Unapproved integration coordinates');
  const root = privateDirectory(facts.root);
  for (const file of ['backend.pid','backend-binding.json','backend-startup.json']) if (existsSync(join(root, file))) throw Error('Runtime artifacts require explicit recover before setup');
  vacantPort(facts.backendPort);
  accessSync(root, constants.W_OK);
  for (const file of ['secrets.env','secrets.previous.env']) if (existsSync(join(root,file))) dataFile(join(root,file));
  const media = mediaConfiguration(env.PATHOME_OE_TEST_MEDIA_ENV_FILE);
  const image = env.PATHOME_OE_TEST_MEDIA_FILE ?? repo + 'sample_upload_media/interior_living.jpg';
  const stat = lstatSync(image), bytes = readFileSync(image);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size < 4 || stat.size > 10 * 1024 * 1024
      || !(bytes[0] === 0xff && bytes[1] === 0xd8 && bytes.at(-2) === 0xff && bytes.at(-1) === 0xd9)) throw Error('Harmless local JPEG fixture required (maximum 10 MiB)');
  for (const file of ['.mvn','backend/.mvn','.npmrc','frontend-web/.npmrc']) if (existsSync(repo + file)) throw Error('Unreviewed project runtime configuration');
  return { ...facts, image, imageHash: digest(bytes), mediaHash: digest(JSON.stringify(media)), sourceHash: treeDigest(inputPaths()) };
}
export async function browserPrerequisite() {
  const playwright = await import('/opt/homebrew/lib/node_modules/playwright/index.js');
  const chromium = playwright.chromium ?? playwright.default?.chromium;
  if (!chromium) throw Error('Playwright Chromium is unavailable');
  const executable = chromium.executablePath(); accessSync(executable, constants.X_OK);
  return executable;
}
export function validateReadiness(receipt, facts, distHash, now = Date.now()) {
  const age = now - Date.parse(receipt.completedAt);
  if (!Number.isFinite(age) || age < 0 || age > 15 * 60000 || !receipt.offlineMaven || !receipt.frontendBuilt || !receipt.chromium
      || Object.entries(facts).some(([key,value]) => receipt[key] !== value)
      || receipt.distHash !== distHash) throw Error('Preflight absent, stale or changed; run preflight before destructive setup');
  return receipt;
}
export function ready(env = process.env, facts = precheck(env)) {
  return validateReadiness(JSON.parse(dataFile(join(facts.root,'preflight.json'))),facts,treeDigest([repo+'frontend-web/dist']));
}
async function main(command) {
  const facts = precheck();
  if (command === 'check') { await browserPrerequisite(); return; }
  if (command === 'require') { ready(process.env, facts); await browserPrerequisite(); return; }
  if (command === 'record') {
    const chromium = await browserPrerequisite();
    writePrivate(join(facts.root, 'preflight.json'), { ...facts, chromium, offlineMaven: true, frontendBuilt: true,
      distHash: treeDigest([repo + 'frontend-web/dist']), completedAt: new Date().toISOString() }); return;
  }
  throw Error('Unknown preflight operation');
}
if (process.argv[1] === fileURLToPath(import.meta.url)) main(process.argv[2]).catch(error => { console.error('REFUSED: ' + error.message); process.exitCode = 90; });
