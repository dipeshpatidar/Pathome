import { createHash } from 'node:crypto';
import { promises as fs } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repo = '/Users/dipeshpatidar/Documents/Pathome-tenant-ui-v0';
const evidenceRoot = dirname(fileURLToPath(import.meta.url));
const logsRoot = join(evidenceRoot, 'negative-controls');
const tempRoot = await fs.mkdtemp(join(tmpdir(), 'pathome-f4-callback-'));
const frontend = join(tempRoot, 'frontend-web');
const probes = join(tempRoot, 'probes');

await fs.mkdir(logsRoot, { recursive: true });
await fs.mkdir(probes, { recursive: true });

const sourcePaths = {
  draftService: join(frontend, 'src/services/draftService.ts'),
  hook: join(frontend, 'src/hooks/usePropertyDraft.ts'),
  single: join(frontend, 'src/components/MasterAdminDashboard.tsx'),
  bar: join(frontend, 'src/components/DraftManagementBar.tsx')
};
const originalRoots = {
  draftService: join(repo, 'frontend-web/src/services/draftService.ts'),
  hook: join(repo, 'frontend-web/src/hooks/usePropertyDraft.ts'),
  single: join(repo, 'frontend-web/src/components/MasterAdminDashboard.tsx'),
  bar: join(repo, 'frontend-web/src/components/DraftManagementBar.tsx')
};

const sha = value => createHash('sha256').update(value).digest('hex');
const readFileHash = async path => sha(await fs.readFile(path));
const buildHash = async () => {
  const hash = createHash('sha256');
  const visit = async (directory, prefix = '') => {
    const entries = await fs.readdir(directory, { withFileTypes: true });
    for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
      const relative = `${prefix}/${entry.name}`;
      const fullPath = join(directory, entry.name);
      hash.update(relative);
      if (entry.isDirectory()) await visit(fullPath, relative);
      else hash.update(await fs.readFile(fullPath));
    }
  };
  await visit(join(frontend, 'dist'));
  return hash.digest('hex');
};
const reset = async (...names) => {
  for (const name of names) {
    await fs.writeFile(sourcePaths[name], original[name]);
  }
};
const mutate = async (name, replacements) => {
  let source = original[name];
  for (const [from, to] of replacements) {
    if (!source.includes(from)) throw new Error(`Mutant source anchor missing in ${name}: ${from.slice(0, 80)}`);
    source = source.replace(from, to);
  }
  await fs.writeFile(sourcePaths[name], source);
};
const run = async (command, args, options = {}) => {
  const child = spawn(command, args, {
    cwd: options.cwd || frontend,
    env: { ...process.env, ...options.env },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  const stdout = [];
  const stderr = [];
  child.stdout.on('data', chunk => stdout.push(chunk));
  child.stderr.on('data', chunk => stderr.push(chunk));
  const timeout = setTimeout(() => child.kill('SIGTERM'), options.timeoutMs || 300000);
  const code = await new Promise(resolve => child.on('close', resolve));
  clearTimeout(timeout);
  return { code, output: Buffer.concat([...stdout, ...stderr]).toString('utf8') };
};
const runChecked = async (command, args, options) => {
  const result = await run(command, args, options);
  if (result.code !== 0) throw new Error(`${command} failed (${result.code})\n${result.output}`);
  return result;
};
await fs.cp(join(repo, 'frontend-web'), frontend, {
  recursive: true,
  filter: source => {
    const relative = source.slice(join(repo, 'frontend-web').length);
    return !relative.startsWith('/node_modules') && !relative.startsWith('/dist');
  }
});
await fs.symlink(join(repo, 'frontend-web', 'node_modules'), join(frontend, 'node_modules'), 'dir');

const original = {};
for (const [name, path] of Object.entries(originalRoots)) {
  original[name] = await fs.readFile(path, 'utf8');
  await fs.writeFile(sourcePaths[name], original[name]);
}

const externalProbePath = join(probes, 'external-denial-restore-probe.mjs');
const bulkProbePath = join(probes, 'bulk-denial-probe.mjs');
const originalExternalProbe = await fs.readFile(
  '/Users/dipeshpatidar/.codex/visualizations/2026/10/21/01a15f0e-7dbc-7440-baa3-181f889aa9a5/external-denial-restore-probe.mjs',
  'utf8'
);
const originalBulkProbe = await fs.readFile(
  '/Users/dipeshpatidar/.codex/visualizations/2026/10/21/01a15f0e-7dbc-7440-baa3-181f889aa9a5/bulk-denial-probe.mjs',
  'utf8'
);
await fs.writeFile(
  externalProbePath,
  originalExternalProbe.replace(/const DIST = '[^']+';/, `const DIST = '${join(frontend, 'dist')}';`)
);
await fs.writeFile(
  bulkProbePath,
  originalBulkProbe.replace(/const DIST = '[^']+';/, `const DIST = '${join(frontend, 'dist')}';`)
);

const build = async label => {
  const result = await run('npm', ['run', 'build']);
  await fs.writeFile(join(logsRoot, `${label}-build.log`), result.output);
  if (result.code !== 0) throw new Error(`${label} build failed`);
  return buildHash();
};
const assertIntentionalFailure = async ({ label, command, args, env, pattern }) => {
  const result = await run(command, args, { env });
  await fs.writeFile(join(logsRoot, `${label}-intended-failure.log`), result.output);
  if (result.code === 0 || !result.output.includes(pattern)) {
    throw new Error(`${label} did not produce intended failure "${pattern}"\n${result.output}`);
  }
  console.log(`PASS ${label}: ${pattern}`);
};
const assertGreen = async ({ label, command, args, env }) => {
  const result = await run(command, args, { env });
  await fs.writeFile(join(logsRoot, `${label}-restored-green.log`), result.output);
  if (result.code !== 0) throw new Error(`${label} restored-green check failed\n${result.output}`);
  console.log(`PASS ${label}: restored green`);
};

const manifest = {
  tempRoot,
  controls: [],
  beforeSourceHashes: Object.fromEntries(
    await Promise.all(Object.entries(originalRoots).map(async ([name, path]) =>
      [name, await readFileHash(path)]))
  ),
  beforeBuildHash: null
};

manifest.beforeBuildHash = await build('baseline');

await mutate('draftService', [
  [
    `    const operationActive = (): boolean =>
      !terminalError
      && isUploaderSessionActive(session)
      && (!operation || (!operation.signal.aborted && operation.isActive()));`,
    `    const operationActive = (): boolean =>
      !terminalError && isUploaderSessionActive(session);`
  ],
  [
    `    if (operation) {
      operation.signal.addEventListener('abort', settleRetiredOperation, { once: true });
    }`,
    ''
  ]
]);
let mutantHash = await readFileHash(sourcePaths.draftService);
let mutantBuildHash = await build('M1-media-epoch-dispatch');
await assertIntentionalFailure({
  label: 'M1-media-epoch-dispatch',
  command: 'node',
  args: [externalProbePath],
  pattern: 'FAIL  retired restore must not claim additional protected media after save403'
});
manifest.controls.push({
  control: 'M1 media worker epoch queue/dispatch guard',
  source: 'draftService.ts',
  mutantHash,
  mutantBuildHash,
  failure: 'retired restore claims additional protected media'
});
await reset('draftService');

await mutate('single', [[
  `      const clearOwnedProgress = () => {
        if (mediaRestorationOwnerRef.current !== restorationOwnerId) return;
        mediaRestorationOwnerRef.current = null;
        setMediaRestorationProgress(null);
      };`,
  `      const clearOwnedProgress = () => {};`
]]);
mutantHash = await readFileHash(sourcePaths.single);
mutantBuildHash = await build('M2-owned-progress');
await assertIntentionalFailure({
  label: 'M2-owned-progress',
  command: 'node',
  args: [externalProbePath],
  pattern: 'FAIL  denial remains visible'
});
manifest.controls.push({
  control: 'M2 owned restoration-progress settlement/finalizer',
  source: 'MasterAdminDashboard.tsx',
  mutantHash,
  mutantBuildHash,
  failure: 'fetching progress hides terminal denial'
});
await reset('single');

await mutate('hook', [[
  `                // Retirement happens synchronously inside the failing worker, before sibling settlement.
                withdrawDeniedProtectedDraft(
                  err,
                  session,
                  'You no longer have permission to discard saved drafts.'
                );`,
  ''
]]);
mutantHash = await readFileHash(sourcePaths.hook);
mutantBuildHash = await build('M3-bulk-immediate-retirement');
await assertIntentionalFailure({
  label: 'M3-bulk-immediate-retirement',
  command: 'node',
  args: [bulkProbePath],
  pattern: 'FAIL  bulk terminal denial immediately withdraws editor while sibling is held'
});
manifest.controls.push({
  control: 'M3 immediate bulk denial retirement',
  source: 'usePropertyDraft.ts',
  mutantHash,
  mutantBuildHash,
  failure: 'editor and autosave remain live after DELETE403'
});
await reset('hook');

await mutate('hook', [
  [
    `            const outcomeIndex = i + offset;
            if (!operation.isActive()) return;`,
    `            const outcomeIndex = i + offset;`
  ],
  [
    `              await draftService.discardDraft(draftId, session, {
                signal: operation.signal
              });`,
    `              await draftService.discardDraft(draftId, session);`
  ],
  [
    `        if (!operation.isActive()) break;`,
    ''
  ]
]);
mutantHash = await readFileHash(sourcePaths.hook);
mutantBuildHash = await build('M4-bulk-next-item');
await assertIntentionalFailure({
  label: 'M4-bulk-next-item',
  command: 'node',
  args: ['browser-verification/property-uploader-session/f4-callback-ownership.mjs'],
  env: { F4_ONLY: 'middle' },
  pattern: 'denial or completion stops bulk dispatch at the owned boundary'
});
manifest.controls.push({
  control: 'M4 bulk next-item dispatch ownership',
  source: 'usePropertyDraft.ts',
  mutantHash,
  mutantBuildHash,
  failure: 'a post-denial chunk dispatches another protected DELETE'
});
await reset('hook');

await mutate('bar', [[
  `      if (!result.canFinalize) return;`,
  ''
]]);
mutantHash = await readFileHash(sourcePaths.bar);
mutantBuildHash = await build('M5-bulk-caller-owner');
await assertIntentionalFailure({
  label: 'M5-bulk-caller-owner',
  command: 'node',
  args: ['browser-verification/property-uploader-session/discard-callback-harness.mjs'],
  pattern: 'FAIL  stale bulk completion cannot mutate replacement dialog or notices'
});
manifest.controls.push({
  control: 'M5 bulk caller initiating-session finalization guard',
  source: 'DraftManagementBar.tsx',
  mutantHash,
  mutantBuildHash,
  failure: 'stale completion mutates the replacement owner UI'
});
await reset('bar');

const restoredBuildHash = await build('restored');
await assertGreen({
  label: 'W1-restored',
  command: 'node',
  args: [externalProbePath]
});
await assertGreen({
  label: 'B1-restored',
  command: 'node',
  args: [bulkProbePath]
});
await assertGreen({
  label: 'M4-restored',
  command: 'node',
  args: ['browser-verification/property-uploader-session/f4-callback-ownership.mjs'],
  env: { F4_ONLY: 'middle' }
});
await assertGreen({
  label: 'M5-restored',
  command: 'node',
  args: ['browser-verification/property-uploader-session/discard-callback-harness.mjs']
});

manifest.afterSourceHashes = Object.fromEntries(
  await Promise.all(Object.entries(sourcePaths).map(async ([name, path]) =>
    [name, await readFileHash(path)]))
);
manifest.restoredBuildHash = restoredBuildHash;
manifest.restoredHashesMatch = Object.entries(manifest.beforeSourceHashes)
  .every(([name, hash]) => manifest.afterSourceHashes[name] === hash);
if (!manifest.restoredHashesMatch) throw new Error('mutant cleanup did not restore every original source hash');

await fs.writeFile(join(logsRoot, 'negative-control-manifest.json'), JSON.stringify(manifest, null, 2));
console.log(`negative controls complete: ${manifest.controls.length}/5; restored hashes match`);
