import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { promisify } from 'node:util';
import {
  GateContextClosedError,
  GateLifecycle,
  GateTimeoutError,
  RequestContractError,
  assertHeaderContract,
  assertObjectContract,
  assertQueryContract,
  consumeExpectedConsoleError,
  hasDeclaredGate,
  writeReceiptAtomic
} from './runner-integrity.mjs';

const execute = promisify(execFile);
const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

test('gate lifecycle records successful transport and JSON gates without false failures', async () => {
  const lifecycle = new GateLifecycle({ timeoutMs: 200 });
  const transport = deferred();
  const json = deferred();
  const transportWait = lifecycle.wait(transport.promise, {
    type: 'transport', contextId: 'context-1', scenario: 'happy', label: 'transport'
  });
  const jsonWait = lifecycle.wait(json.promise, {
    type: 'json', contextId: 'context-1', scenario: 'happy', label: 'json'
  });

  assert.equal(lifecycle.activeWaiterCount, 2);
  assert.equal(lifecycle.failures().length, 2);
  transport.resolve();
  json.resolve();
  await Promise.all([transportWait, jsonWait]);

  assert.equal(lifecycle.activeWaiterCount, 0);
  assert.deepEqual(lifecycle.failures(), []);
  assert.deepEqual(lifecycle.unfinished(), []);
  assert.deepEqual(lifecycle.snapshot().map(entry => entry.status), ['settled', 'settled']);
});

test('shared Promise waiters and independent gates remain independently attributable', async () => {
  const lifecycle = new GateLifecycle({ timeoutMs: 200 });
  const shared = deferred();
  const independent = deferred();
  const waits = [
    lifecycle.wait(shared.promise, {
      type: 'transport', contextId: 'context-1', scenario: 'shared', label: 'shared-a'
    }),
    lifecycle.wait(shared.promise, {
      type: 'transport', contextId: 'context-1', scenario: 'shared', label: 'shared-b'
    }),
    lifecycle.wait(independent.promise, {
      type: 'transport', contextId: 'context-2', scenario: 'independent', label: 'independent'
    })
  ];

  assert.equal(lifecycle.snapshot()[0].activeWaiters, 2);
  assert.equal(lifecycle.snapshot()[0].totalWaiters, 2);
  shared.resolve();
  independent.resolve();
  await Promise.all(waits);
  assert.equal(lifecycle.snapshot().filter(entry => entry.status === 'settled').length, 2);
});

for (const value of [undefined, null, false, 0, {}]) {
  test(`malformed gate ${String(value)} is rejected rather than bypassed`, async () => {
    const lifecycle = new GateLifecycle({ timeoutMs: 50 });
    await assert.rejects(
      lifecycle.wait(value, {
        type: 'json', contextId: 'context-1', scenario: 'malformed', label: 'malformed'
      }),
      error => error instanceof RequestContractError && error.kind === 'malformed-gate'
    );
  });
}

test('gate rejection, timeout and page closure produce failed lifecycle records', async () => {
  const rejectionLifecycle = new GateLifecycle({ timeoutMs: 200 });
  const rejected = deferred();
  const rejectionWait = rejectionLifecycle.wait(rejected.promise, {
    type: 'transport', contextId: 'context-1', scenario: 'rejection', label: 'rejected'
  });
  rejected.reject(new Error('fixture rejected'));
  await assert.rejects(rejectionWait, /fixture rejected/);
  assert.equal(rejectionLifecycle.failures()[0].status, 'rejected');

  const timeoutLifecycle = new GateLifecycle({ timeoutMs: 20 });
  await assert.rejects(
    timeoutLifecycle.wait(deferred().promise, {
      type: 'transport', contextId: 'context-2', scenario: 'timeout', label: 'timeout'
    }),
    GateTimeoutError
  );
  assert.equal(timeoutLifecycle.failures()[0].status, 'timed-out');

  const closedLifecycle = new GateLifecycle({ timeoutMs: 500 });
  const closedGate = deferred();
  const closedWait = closedLifecycle.wait(closedGate.promise, {
    type: 'json', contextId: 'context-3', scenario: 'closed', label: 'closed'
  });
  closedLifecycle.contextClosed('context-3');
  await assert.rejects(closedWait, GateContextClosedError);
  const closedRecord = closedLifecycle.failures()[0];
  assert.equal(closedRecord.status, 'context-closed');
  assert.match(closedRecord.error, /closed/i);

  closedGate.resolve();
  await wait(0);
  assert.equal(closedLifecycle.failures()[0].status, 'context-closed');
});

test('single, multiple and no gates remain deterministic', async () => {
  const lifecycle = new GateLifecycle({ timeoutMs: 100 });
  await lifecycle.wait(Promise.resolve(), {
    type: 'transport', contextId: 'single', scenario: 'single', label: 'single'
  });
  const gates = [deferred(), deferred(), deferred()];
  const waits = gates.map((gate, index) => lifecycle.wait(gate.promise, {
    type: index === 1 ? 'json' : 'transport',
    contextId: 'multiple',
    scenario: 'multiple',
    label: `multiple-${index}`
  }));
  gates.reverse().forEach(gate => gate.resolve());
  await Promise.all(waits);
  assert.equal(lifecycle.snapshot().length, 4);
  assert.equal(lifecycle.failures().length, 0);
});

test('query contracts reject missing, unknown, duplicate and wrong values', () => {
  const contract = {
    required: ['page'],
    optional: ['q', 'maxRent'],
    exact: { page: '0', maxRent: '30000' },
    nonEmpty: ['q']
  };

  assert.doesNotThrow(() => assertQueryContract(
    new URL('http://pathome.local/api/v1/properties?page=0&q=vijay&maxRent=30000'),
    contract
  ));
  assert.throws(
    () => assertQueryContract(new URL('http://pathome.local/api/v1/properties?maxRent=30000'), contract),
    error => error.kind === 'missing-query'
  );
  assert.throws(
    () => assertQueryContract(
      new URL('http://pathome.local/api/v1/properties?page=0&maxRent=30000&unregistered=1'),
      contract
    ),
    error => error.kind === 'unexpected-query'
  );
  assert.throws(
    () => assertQueryContract(
      new URL('http://pathome.local/api/v1/properties?page=0&page=1&maxRent=30000'),
      contract
    ),
    error => error.kind === 'duplicate-query'
  );
  assert.throws(
    () => assertQueryContract(
      new URL('http://pathome.local/api/v1/properties?page=0&maxRent=25000'),
      contract
    ),
    error => error.kind === 'unexpected-query-value'
  );
});

test('query contracts permit only explicitly reusable repeated keys', () => {
  const url = new URL('http://pathome.local/api/v1/favorites?propertyIds=1&propertyIds=2');
  assert.doesNotThrow(() => assertQueryContract(url, {
    optional: ['propertyIds'], repeatable: ['propertyIds']
  }));
  assert.throws(
    () => assertQueryContract(new URL('http://pathome.local/api/v1/properties?q=a&q=b'), {
      optional: ['q']
    }),
    error => error.kind === 'duplicate-query'
  );
  assert.doesNotThrow(() => assertQueryContract(
    new URL('http://pathome.local/api/v1/favorites/properties?page=0&size=24'),
    {
      required: ['page', 'size'],
      allowedValues: { page: ['0'], size: ['6', '24'] }
    }
  ));
  assert.throws(
    () => assertQueryContract(
      new URL('http://pathome.local/api/v1/favorites/properties?page=0&size=25'),
      {
        required: ['page', 'size'],
        allowedValues: { page: ['0'], size: ['6', '24'] }
      }
    ),
    error => error.kind === 'unexpected-query-value'
  );
});

test('declared gate presence is distinct from falsy malformed gate values', () => {
  assert.equal(hasDeclaredGate({}), false);
  assert.equal(hasDeclaredGate({ gate: false }), true);
  assert.equal(hasDeclaredGate({ gate: 0 }), true);
  assert.equal(hasDeclaredGate({ gate: undefined }), true);
});

test('header and payload contracts enforce expected identity and allowed fields', () => {
  assert.doesNotThrow(() => assertHeaderContract({ 'if-match': '12' }, { 'If-Match': '12' }));
  assert.throws(
    () => assertHeaderContract({}, { 'If-Match': '12' }),
    error => error.kind === 'unexpected-header'
  );
  const body = { operationId: '1bfbcac9-81cf-4f7b-954f-a4b896fe6ea3', reason: 'Tenant request' };
  assert.doesNotThrow(() => assertObjectContract(body, {
    allowedKeys: ['operationId', 'reason'],
    requiredKeys: ['operationId', 'reason'],
    exact: { operationId: body.operationId }
  }));
  assert.throws(
    () => assertObjectContract({ ...body, listingId: 999 }, {
      allowedKeys: ['operationId', 'reason']
    }),
    error => error.kind === 'unexpected-payload'
  );
});

test('console suppression requires the exact registered response origin and status', () => {
  const expectedUrl = 'http://pathome.local/api/v1/properties/90';
  const state = {
    expectedHttpErrors: [
      { url: expectedUrl, status: 404, consumed: false }
    ],
    expectedResourceFailures: []
  };
  const registeredMessage = {
    text: () => 'Failed to load resource: the server responded with a status of 404 (Not Found)',
    location: () => ({ url: expectedUrl })
  };
  assert.ok(consumeExpectedConsoleError(state, registeredMessage));

  state.expectedHttpErrors.push({ url: expectedUrl, status: 404, consumed: false });
  const unrelatedMessage = {
    text: () => 'Failed to load resource: the server responded with a status of 404 (Not Found)',
    location: () => ({ url: 'http://pathome.local/' })
  };
  assert.equal(consumeExpectedConsoleError(state, unrelatedMessage), null);
  assert.equal(
    unrelatedMessage.text().includes('Failed to load resource'),
    true,
    'The legacy broad substring rule would have suppressed this unrelated error.'
  );
});

test('legacy parser-blind gate tracking misses the unreleased JSON gate', async () => {
  const lifecycle = new GateLifecycle({ timeoutMs: 100 });
  const parserGate = deferred();
  const legacyTransportOnly = new Set();
  const wait = lifecycle.wait(parserGate.promise, {
    type: 'json', contextId: 'context-legacy-control', scenario: 'legacy-control', label: 'parser'
  });
  assert.equal(legacyTransportOnly.size, 0);
  assert.equal(lifecycle.failures().length, 1);
  assert.equal(
    lifecycle.snapshot()[0].status === 'pending',
    true,
    'Removing parser-gate registration reproduces the original false-green condition.'
  );
  parserGate.resolve();
  await wait;
});

test('atomic receipt writer replaces an older receipt as one complete JSON document', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'pathome-receipt-'));
  const receiptPath = join(directory, 'browser', 'receipt.json');
  await writeReceiptAtomic(receiptPath, { status: 'PASS', runId: 'old' });
  await writeReceiptAtomic(receiptPath, { status: 'FAIL', runId: 'new' });
  const receipt = JSON.parse(await readFile(receiptPath, 'utf8'));
  assert.deepEqual(receipt, { status: 'FAIL', runId: 'new' });
});

const runChild = async (mode, outputDirectory, extraEnv = {}) => {
  const runner = new URL('./f3-f4-final-run.mjs', import.meta.url);
  try {
    const result = await execute(process.execPath, [runner.pathname], {
      cwd: new URL('../../../', import.meta.url).pathname,
      env: {
        ...process.env,
        PATHOME_RUNNER_SELFTEST: mode,
        PATHOME_RUNNER_EVIDENCE_ROOT: outputDirectory,
        ...extraEnv
      },
      timeout: 45_000,
      maxBuffer: 4 * 1024 * 1024
    });
    return { ...result, code: 0 };
  } catch (error) {
    return {
      stdout: error.stdout ?? '',
      stderr: error.stderr ?? '',
      code: typeof error.code === 'number' ? error.code : null
    };
  }
};

const readReceipt = async outputDirectory =>
  JSON.parse(await readFile(join(outputDirectory, 'browser', 'receipt.json'), 'utf8'));

const runAcceptanceRunner = async (outputDirectory, { runnerPath, env = {} } = {}) => {
  const runner = runnerPath
    ?? new URL('./f3-f4-final-run.mjs', import.meta.url).pathname;
  try {
    const result = await execute(process.execPath, [runner], {
      cwd: new URL('../../../', import.meta.url).pathname,
      env: {
        ...process.env,
        PATHOME_RUNNER_EVIDENCE_ROOT: outputDirectory,
        ...env
      },
      timeout: 45_000,
      maxBuffer: 4 * 1024 * 1024
    });
    return { ...result, code: 0 };
  } catch (error) {
    return {
      stdout: error.stdout ?? '',
      stderr: error.stderr ?? '',
      code: typeof error.code === 'number' ? error.code : null
    };
  }
};

test('discovery query matrix covers page-only, declared, optional and side-effect controls', async () => {
  const outputDirectory = await mkdtemp(join(tmpdir(), 'pathome-discovery-query-matrix-'));
  const result = await runChild('discovery-query-matrix', outputDirectory);
  assert.notEqual(result.code, 0, 'the invalid-query matrix must produce a red receipt');
  const receipt = await readReceipt(outputDirectory);
  assert.equal(receipt.status, 'FAIL');
  assert.equal(receipt.selftest.requested, 'discovery-query-matrix');
  assert.ok(receipt.selftest.results.length >= 18);
  assert.ok(receipt.selftest.results.every(entry => entry.detected === true),
    JSON.stringify(receipt.selftest.results));

  const missing = receipt.fixtureIntegrity.entries.filter(entry => entry.kind === 'missing-query');
  assert.ok(missing.some(entry => entry.detail?.key === 'maxRent'));
  assert.ok(missing.some(entry => entry.detail?.key === 'city'));
  assert.ok(receipt.fixtureIntegrity.entries.some(entry => entry.kind === 'unexpected-query'));
  assert.ok(receipt.fixtureIntegrity.entries.some(entry => entry.kind === 'duplicate-query'));
  assert.ok(receipt.fixtureIntegrity.entries.some(entry => entry.kind === 'unexpected-query-value'));
});

test('preserved C1 page-1 maxRent omission fails naturally without manual error insertion', async () => {
  const outputDirectory = await mkdtemp(join(tmpdir(), 'pathome-discovery-filter-fixed-'));
  const result = await runAcceptanceRunner(outputDirectory, {
    env: { PATHOME_RUNNER_DISCOVERY_FILTER_FAULT: 'omit-page-1' }
  });
  assert.notEqual(result.code, 0);
  const receipt = await readReceipt(outputDirectory);
  assert.equal(receipt.status, 'FAIL');
  assert.equal(receipt.expectedFailureInjection, false);
  const missing = receipt.fixtureIntegrity.entries.find(entry =>
    entry.kind === 'missing-query' && entry.detail?.key === 'maxRent');
  assert.ok(missing, JSON.stringify(receipt.fixtureIntegrity.entries));
  assert.match(missing.detail.url, /[?&]page=1(?:&|$)/);
  assert.doesNotMatch(missing.detail.url, /[?&]maxRent=/);
  assert.equal(receipt.discoveryFilterFault?.pageZeroRequest?.query?.maxRent, '30000');
  assert.equal(receipt.discoveryFilterFault?.pageZeroRequest?.page, 0);
});

test('legacy permissive matcher produces the same omission as a false green', async () => {
  const sourcePath = new URL('./f3-f4-final-run.mjs', import.meta.url).pathname;
  const source = await readFile(sourcePath, 'utf8');
  const start = '// C3_DISCOVERY_CONTRACT_START';
  const end = '// C3_DISCOVERY_CONTRACT_END';
  const startIndex = source.indexOf(start);
  const endIndex = source.indexOf(end);
  assert.ok(startIndex >= 0 && endIndex > startIndex, 'discovery contract markers are required');
  const legacyMatcher = `${start}
        const queryError = assertFixtureQuery(ledger, route, {
          required: ['page'],
          optional: [...DISCOVERY_QUERY_KEYS].filter(key => key !== 'page'),
          exact: { page: expectedPage },
          allowedValues: Object.fromEntries(Object.entries(state.discoveryQuery ?? {})
            .map(([key, value]) => [key, [String(value)]])),
          nonEmpty: ['q']
        });
        ${end}`;
  const mutantSource = source.slice(0, startIndex)
    + legacyMatcher
    + source.slice(endIndex + end.length);
  assert.notEqual(mutantSource, source);

  const mutantPath = join(
    dirname(sourcePath),
    `.f3-f4-discovery-permissive-${process.pid}-${Date.now()}.mjs`
  );
  const outputDirectory = await mkdtemp(join(tmpdir(), 'pathome-discovery-filter-mutant-'));
  await writeFile(mutantPath, mutantSource, 'utf8');
  try {
    const result = await runAcceptanceRunner(outputDirectory, {
      runnerPath: mutantPath,
      env: { PATHOME_RUNNER_DISCOVERY_FILTER_FAULT: 'omit-page-1' }
    });
    assert.equal(result.code, 0, `${result.stderr}\n${result.stdout}`);
    const receipt = await readReceipt(outputDirectory);
    assert.equal(receipt.status, 'PASS');
    assert.equal(receipt.fixtureIntegrity.errorCount, 0);
    assert.equal(receipt.fixtureIntegrity.entries.some(entry => entry.kind === 'missing-query'), false);
  } finally {
    await rm(mutantPath, { force: true });
  }
});

for (const mode of [
  'unreleased-json-gate',
  'unreleased-gate',
  'wrong-detail-query',
  'console-substring',
  'rejected-gate',
  'gate-timeout',
  'runner-exception-while-pending',
  'route-response-failure',
  'browser-launch',
  'suite-exception',
  'build-failure',
  'source-hash-failure',
  'cleanup-failure',
  'suite-deadline'
]) {
  test(`unchanged runner finalizer produces fresh red evidence for ${mode}`, async () => {
    const outputDirectory = await mkdtemp(join(tmpdir(), `pathome-${mode}-`));
    const result = await runChild(mode, outputDirectory,
      mode === 'gate-timeout' ? { PATHOME_RUNNER_GATE_TIMEOUT_MS: '50' }
        : mode === 'suite-deadline' ? { PATHOME_RUNNER_SUITE_DEADLINE_MS: '100' }
          : {});
    assert.notEqual(result.code, 0, `${mode} unexpectedly exited zero`);
    const receipt = await readReceipt(outputDirectory);
    assert.equal(receipt.status, 'FAIL');
    assert.equal(receipt.expectedFailureInjection, true);
    assert.equal(receipt.runnerMode, mode);
    assert.ok(receipt.failures > 0 || receipt.fixtureIntegrity.pendingGateCount > 0
      || receipt.runtimeErrorCount > 0);
    if (mode === 'unreleased-json-gate') {
      assert.ok(receipt.fixtureIntegrity.pendingGateCount >= 1);
      assert.ok(receipt.gateLifecycle.some(gate => gate.type === 'json'
        && gate.status === 'context-closed'));
    }
    if (mode === 'wrong-detail-query') {
      assert.ok(receipt.fixtureIntegrity.entries.some(entry => entry.kind === 'unexpected-query'));
    }
    if (mode === 'console-substring') {
      assert.ok(receipt.fixtureIntegrity.entries.some(entry => entry.kind === 'unexpected-console-error'));
    }
    if (mode === 'browser-launch') {
      assert.ok(receipt.runnerFailure || receipt.runtimeErrorCount > 0);
    }
  });
}

test('an unwritable evidence destination exits nonzero without claiming persistence', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'pathome-unwritable-'));
  const outputFile = join(directory, 'not-a-directory');
  await writeFile(outputFile, 'occupied', 'utf8');
  const result = await runChild('wrong-detail-query', outputFile);
  assert.notEqual(result.code, 0);
  assert.match(result.stderr, /receipt could not be persisted|EVIDENCE INITIALIZATION FAILURE/i);
  assert.doesNotMatch(result.stdout, /receipt persisted/i);
});

test('a failed final receipt write preserves INCOMPLETE startup evidence and exits nonzero', async () => {
  const outputDirectory = await mkdtemp(join(tmpdir(), 'pathome-final-write-'));
  const result = await runChild('receipt-write-failure', outputDirectory);
  assert.notEqual(result.code, 0);
  const receipt = await readReceipt(outputDirectory);
  assert.equal(receipt.status, 'INCOMPLETE');
  assert.match(result.stderr, /RUNNER RECEIPT WRITE FAILURE/);
});

test('an older green receipt cannot stand in for a newer failed run', async () => {
  const outputDirectory = await mkdtemp(join(tmpdir(), 'pathome-old-green-'));
  await writeReceiptAtomic(join(outputDirectory, 'browser', 'receipt.json'), {
    status: 'PASS',
    runId: 'historical-green',
    failures: 0
  });
  const result = await runChild('wrong-detail-query', outputDirectory);
  assert.notEqual(result.code, 0);
  const receipt = await readReceipt(outputDirectory);
  assert.equal(receipt.status, 'FAIL');
  assert.notEqual(receipt.runId, 'historical-green');
  assert.ok(receipt.fixtureIntegrity.entries.some(entry => entry.kind === 'unexpected-query'));
});
