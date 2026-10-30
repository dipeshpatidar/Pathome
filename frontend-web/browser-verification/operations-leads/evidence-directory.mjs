import { randomUUID } from 'node:crypto';
import { open, mkdir, lstat, readdir } from 'node:fs/promises';
import { dirname, isAbsolute, join, resolve } from 'node:path';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

async function rejectSymlinkComponents(path) {
  const ancestors = [];
  let current = path;
  while (dirname(current) !== current) {
    ancestors.push(current);
    current = dirname(current);
  }
  for (const ancestor of ancestors.reverse()) {
    let metadata;
    try {
      metadata = await lstat(ancestor);
    } catch (error) {
      if (error?.code === 'ENOENT' && ancestor === path) return false;
      throw new Error(`evidence directory path is unavailable: ${ancestor}`, { cause: error });
    }
    if (metadata.isSymbolicLink()) {
      throw new Error(`evidence directory path contains a symlink: ${ancestor}`);
    }
    if (ancestor !== path && !metadata.isDirectory()) {
      throw new Error(`evidence directory parent is not a directory: ${ancestor}`);
    }
  }
  return true;
}

export async function claimEvidenceDirectory(path, {
  runId = randomUUID(),
  startedAt = new Date().toISOString()
} = {}) {
  if (!isAbsolute(path)) {
    throw new Error('browser evidence directory must be absolute');
  }
  if (!UUID.test(runId)) {
    throw new Error('browser evidence run ID must be a UUID');
  }
  const normalized = resolve(path);
  const parent = dirname(normalized);
  if (await rejectSymlinkComponents(parent)) {
    const metadata = await lstat(parent);
    if (!metadata.isDirectory()) {
      throw new Error('browser evidence parent is not a directory');
    }
  }

  if (await rejectSymlinkComponents(normalized)) {
    throw new Error('browser evidence directory already exists; sealed paths are never reusable');
  }
  try {
    await mkdir(normalized, { recursive: false, mode: 0o700 });
  } catch (error) {
    if (error?.code === 'EEXIST') {
      throw new Error('browser evidence directory already exists; output was not written');
    }
    throw new Error('browser evidence directory could not be claimed', { cause: error });
  }

  const entries = await readdir(normalized);
  if (entries.length !== 0) {
    throw new Error('browser evidence directory was not empty after exclusive creation');
  }
  const markerPath = join(normalized, 'RUN_CLAIM.json');
  const marker = await open(markerPath, 'wx', 0o600);
  try {
    await marker.writeFile(`${JSON.stringify({
      schemaVersion: 1,
      runId,
      startedAt,
      exclusiveOutput: true
    }, null, 2)}\n`, 'utf8');
    await marker.sync();
  } finally {
    await marker.close();
  }
  return {
    path: normalized,
    markerPath,
    runId,
    startedAt
  };
}
