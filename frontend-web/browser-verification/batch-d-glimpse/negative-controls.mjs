/**
 * Isolated source-mutant controls for the two approved card corrections.
 * Copies are rewritten under the OS temporary directory; live source is never mutated.
 */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { copyFile, mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB_ROOT = fileURLToPath(new URL('../../', import.meta.url));
const SOURCE = join(WEB_ROOT, 'src/utils/publicPropertyPreview.ts');
const TEST = join(WEB_ROOT, 'src/tests/publicPropertyPreview.test.mjs');

const execute = (command, args) => new Promise((resolve) => {
  const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', (chunk) => {
    output += chunk;
  });
  child.stderr.on('data', (chunk) => {
    output += chunk;
  });
  child.on('close', (code) => resolve({ code, output }));
});

const mutant = async (name, transform, testNamePattern) => {
  const root = await mkdtemp(join(tmpdir(), 'pathome-batch-d-glimpse-'));
  await mkdir(join(root, 'src/utils'), { recursive: true });
  await mkdir(join(root, 'src/tests'), { recursive: true });
  const sourceTarget = join(root, 'src/utils/publicPropertyPreview.ts');
  const testTarget = join(root, 'src/tests/publicPropertyPreview.test.mjs');
  await copyFile(TEST, testTarget);
  const original = await readFile(SOURCE, 'utf8');
  const changed = transform(original);
  assert.notEqual(changed, original, `${name} mutation did not apply`);
  await writeFile(sourceTarget, changed);
  const result = await execute(process.execPath, [
    '--test',
    `--test-name-pattern=${testNamePattern}`,
    testTarget
  ]);
  assert.notEqual(result.code, 0, `${name} mutant unexpectedly passed`);
  process.stdout.write(`EXPECTED RED  ${name}\n`);
  process.stdout.write(`${result.output.split('\n').slice(-8).join('\n')}\n`);
};

await mutant(
  'whole-rupee F1 formatter reintroduced',
  (source) => source.replace(
    'maximumFractionDigits: 2\n  }).format(value)',
    'maximumFractionDigits: 0\n  }).format(value)'
  ),
  'F1 rent formatting'
);

await mutant(
  'F2 deadline scheduling removed',
  (source) => source.replace(
    /    timer = timers\.schedule\(\(\) => \{[\s\S]*?\}, timeoutMs\);/,
    '    timer = null;'
  ),
  'F2 a never-settling transport'
);

process.stdout.write('All isolated negative controls failed as expected; live source was not mutated.\n');
