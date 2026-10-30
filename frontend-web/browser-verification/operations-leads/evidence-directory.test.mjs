import assert from 'node:assert/strict';
import {
  access, mkdir, mkdtemp, readFile, readdir, realpath, symlink, writeFile
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { claimEvidenceDirectory } from './evidence-directory.mjs';

const RUN_ID = 'b6fe5505-858d-424a-b1e6-0369489f3360';

async function exists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

test('claims one empty absolute directory and refuses every reuse before output', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'pathome-evidence-guard-')));
  const evidence = join(root, RUN_ID);
  const claim = await claimEvidenceDirectory(evidence, {
    runId: RUN_ID,
    startedAt: '2026-10-26T17:30:00.000Z'
  });
  const marker = JSON.parse(await readFile(claim.markerPath, 'utf8'));
  assert.equal(marker.runId, RUN_ID);
  assert.equal(marker.exclusiveOutput, true);
  const snapshot = await readdir(evidence);

  await assert.rejects(
    claimEvidenceDirectory(evidence, { runId: RUN_ID }),
    /already exists/
  );
  assert.deepEqual(await readdir(evidence), snapshot);
});

test('refuses existing manifests, partial files, relative paths and symlink aliases', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'pathome-evidence-guard-')));
  const sealed = join(root, 'sealed-evidence');
  await mkdir(sealed);
  await writeFile(join(sealed, 'browser-results.json'), '{"sealed":true}\n');
  const snapshot = await readdir(sealed);

  await assert.rejects(
    claimEvidenceDirectory(sealed, { runId: RUN_ID }),
    /already exists/
  );
  assert.deepEqual(await readdir(sealed), snapshot);

  const partial = join(root, 'partial-evidence');
  await mkdir(partial);
  await writeFile(join(partial, 'leads-320.png'), 'partial');
  await assert.rejects(
    claimEvidenceDirectory(partial, { runId: RUN_ID }),
    /already exists/
  );
  assert.deepEqual(await readdir(partial), ['leads-320.png']);

  await assert.rejects(
    claimEvidenceDirectory('relative-evidence', { runId: RUN_ID }),
    /absolute/
  );

  const actual = join(root, 'actual');
  await mkdir(actual);
  const alias = join(root, 'alias');
  await symlink(actual, alias);
  await assert.rejects(
    claimEvidenceDirectory(alias, { runId: RUN_ID }),
    /symlink/
  );
  assert.deepEqual(await readdir(actual), []);
  assert.equal(await exists(join(actual, 'RUN_CLAIM.json')), false);
});

test('the legacy recursive-write mutant is killed by the collision control', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'pathome-evidence-guard-')));
  const sealed = join(root, 'sealed-evidence');
  await mkdir(sealed);
  await writeFile(join(sealed, 'browser-results.json'), '{"sealed":true}\n');

  const legacyMutant = () => mkdir(sealed, { recursive: true });
  await legacyMutant();

  await assert.rejects(
    claimEvidenceDirectory(sealed, { runId: RUN_ID }),
    /already exists/,
    'removing the exclusive-directory guard must be detected'
  );
});

// Content inventory, including listing, is the failed-claim invariant (not existence alone).
async function digestTree(path) {
  const { createHash } = await import('node:crypto');
  const { lstat, readlink } = await import('node:fs/promises');
  const result = {};
  async function visit(current, name) {
    const stat = await lstat(current);
    if (stat.isSymbolicLink()) result[name] = `link:${await readlink(current)}`;
    else if (stat.isDirectory()) {
      result[name] = 'directory';
      for (const file of (await readdir(current)).sort()) await visit(join(current, file), `${name}/${file}`);
    } else result[name] = createHash('sha256').update(await readFile(current)).digest('hex');
  }
  await visit(path, '');
  return result;
}

test('all failed existing claims preserve SHA-256 bytes and complete file lists', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'pathome-evidence-content-')));
  for (const name of ['SHA256SUMS', 'browser-results.json', 'partial.png', 'RUN_CLAIM.json']) {
    const target = join(root, name.replaceAll('.', '-'));
    await mkdir(target);
    await writeFile(join(target, name), `foreign ${name}\n`);
    const before = await digestTree(target);
    await assert.rejects(claimEvidenceDirectory(target));
    assert.deepEqual(await digestTree(target), before);
  }
  const real = join(root, 'real'); await mkdir(real);
  await writeFile(join(real, 'sealed'), 'original bytes');
  const alias = join(root, 'alias'); await symlink(real, alias);
  const before = await digestTree(real);
  await assert.rejects(claimEvidenceDirectory(alias));
  await assert.rejects(claimEvidenceDirectory(join(alias, 'child')));
  assert.deepEqual(await digestTree(real), before);
  assert.equal(await exists(join(real, 'child')), false);
  await writeFile(join(root, 'blocked'), 'block');
  await assert.rejects(claimEvidenceDirectory(join(root, 'blocked', 'child')));
  assert.equal(await readFile(join(root, 'blocked'), 'utf8'), 'block');
});

test('concurrent starts produce one exclusive owner and an immutable losing claim', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'pathome-evidence-concurrent-')));
  const target = join(root, 'race');
  const results = await Promise.allSettled([claimEvidenceDirectory(target), claimEvidenceDirectory(target)]);
  assert.equal(results.filter(x => x.status === 'fulfilled').length, 1);
  assert.equal(results.filter(x => x.status === 'rejected').length, 1);
  assert.deepEqual(await readdir(target), ['RUN_CLAIM.json']);
  const before = await digestTree(target);
  await assert.rejects(claimEvidenceDirectory(target));
  assert.deepEqual(await digestTree(target), before);
});

test('invalid run identity and relative path fail before creation; sequential paths preserve history', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'pathome-evidence-identity-')));
  const invalid = join(root, 'invalid');
  await assert.rejects(claimEvidenceDirectory(invalid, { runId: 'invalid' }));
  assert.equal(await exists(invalid), false);
  await assert.rejects(claimEvidenceDirectory('relative'));
  const old = join(root, 'old'); await claimEvidenceDirectory(old);
  await writeFile(join(old, 'screenshot.png'), 'screenshot bytes');
  await writeFile(join(old, 'SHA256SUMS'), 'manifest bytes');
  const before = await digestTree(old);
  await claimEvidenceDirectory(join(root, 'new'));
  assert.deepEqual(await digestTree(old), before);
});

test('marker write failure cannot produce a successful claim or mutate a foreign target', async () => {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'pathome-evidence-marker-')));
  const module = await readFile(new URL('./evidence-directory.mjs', import.meta.url), 'utf8');
  // Isolated fault injection in a fresh module: actual mkdir succeeds, marker open fails.
  const fault = module.replace("const marker = await open(markerPath, 'wx', 0o600);",
    "throw new Error('INJECTED_MARKER_FAILURE'); const marker = await open(markerPath, 'wx', 0o600);");
  const { claimEvidenceDirectory: failingClaim } = await import(`data:text/javascript;base64,${Buffer.from(fault).toString('base64')}`);
  const target = join(root, 'owned');
  await assert.rejects(failingClaim(target), /INJECTED_MARKER_FAILURE/);
  assert.deepEqual(await readdir(target), []);
  const before = await digestTree(target);
  await assert.rejects(claimEvidenceDirectory(target));
  assert.deepEqual(await digestTree(target), before);
});
