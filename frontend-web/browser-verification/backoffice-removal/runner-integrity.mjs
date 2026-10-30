import { mkdir, rename, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { randomUUID } from 'node:crypto';

export class RequestContractError extends Error {
  constructor(kind, message, detail = {}) {
    super(message);
    this.name = 'RequestContractError';
    this.kind = kind;
    this.detail = detail;
  }
}

export class GateTimeoutError extends Error {
  constructor(message) {
    super(message);
    this.name = 'GateTimeoutError';
  }
}

export class GateContextClosedError extends Error {
  constructor(message) {
    super(message);
    this.name = 'GateContextClosedError';
  }
}

export const hasDeclaredGate = value =>
  value !== null
  && typeof value === 'object'
  && Object.prototype.hasOwnProperty.call(value, 'gate');

const normalizedValues = value => {
  if (Array.isArray(value)) return value.map(String);
  if (value === undefined) return [];
  return [String(value)];
};

/**
 * Validates an already-parsed URL against one fixture-owned query contract.
 * Unknown, duplicate, missing and unexpected values are all request failures.
 */
export const assertQueryContract = (url, contract = {}) => {
  const required = new Set(contract.required ?? []);
  const optional = new Set(contract.optional ?? []);
  const allowed = new Set([...required, ...optional]);
  const repeatable = new Set(contract.repeatable ?? []);
  const seen = new Set();

  for (const [key, value] of url.searchParams) {
    if (!allowed.has(key)) {
      throw new RequestContractError('unexpected-query', `Unexpected query parameter: ${key}.`, {
        key,
        value
      });
    }
    if (seen.has(key) && !repeatable.has(key)) {
      throw new RequestContractError('duplicate-query', `Duplicate query parameter: ${key}.`, {
        key,
        values: url.searchParams.getAll(key)
      });
    }
    seen.add(key);
  }

  for (const key of required) {
    if (!url.searchParams.has(key)) {
      throw new RequestContractError('missing-query', `Missing query parameter: ${key}.`, { key });
    }
  }

  for (const [key, expected] of Object.entries(contract.exact ?? {})) {
    const actual = url.searchParams.getAll(key);
    const wanted = normalizedValues(expected);
    if (actual.length !== wanted.length || actual.some((value, index) => value !== wanted[index])) {
      throw new RequestContractError('unexpected-query-value', `Unexpected value for ${key}.`, {
        key,
        expected: wanted,
        actual
      });
    }
  }

  for (const [key, values] of Object.entries(contract.allowedValues ?? {})) {
    const allowedValues = new Set(normalizedValues(values));
    const actual = url.searchParams.getAll(key);
    if (actual.some(value => !allowedValues.has(value))) {
      throw new RequestContractError('unexpected-query-value', `Unexpected value for ${key}.`, {
        key,
        expected: [...allowedValues],
        actual
      });
    }
  }

  for (const key of contract.nonEmpty ?? []) {
    if (url.searchParams.has(key) && !url.searchParams.getAll(key).every(value => value.trim())) {
      throw new RequestContractError('unexpected-query-value', `Empty value for ${key}.`, { key });
    }
  }
};

export const assertHeaderContract = (headers, contract = {}) => {
  for (const [name, expected] of Object.entries(contract)) {
    const actual = headers[name.toLowerCase()] ?? null;
    if (actual !== String(expected)) {
      throw new RequestContractError('unexpected-header', `Unexpected ${name} header.`, {
        header: name,
        expected: String(expected),
        actual
      });
    }
  }
};

export const assertObjectContract = (value, contract = {}) => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new RequestContractError('unexpected-payload', 'Request payload must be a JSON object.', {
      valueType: value === null ? 'null' : typeof value
    });
  }
  const allowed = new Set(contract.allowedKeys ?? []);
  for (const key of Object.keys(value)) {
    if (allowed.size > 0 && !allowed.has(key)) {
      throw new RequestContractError('unexpected-payload', `Unexpected payload field: ${key}.`, {
        key
      });
    }
  }
  const required = new Set(contract.requiredKeys ?? []);
  for (const key of required) {
    if (!Object.prototype.hasOwnProperty.call(value, key)) {
      throw new RequestContractError('unexpected-payload', `Missing payload field: ${key}.`, {
        key
      });
    }
  }
  for (const [key, expected] of Object.entries(contract.exact ?? {})) {
    if (value[key] !== expected) {
      throw new RequestContractError('unexpected-payload', `Unexpected payload field: ${key}.`, {
        key,
        expected,
        actual: value[key]
      });
    }
  }
};

export const consumeExpectedConsoleError = (state, message) => {
  const locationUrl = message.location()?.url ?? '';
  const text = message.text();
  const expectedHttp = (state.expectedHttpErrors ?? []).find(entry =>
    !entry.consumed
    && entry.url === locationUrl
    && new RegExp(`\\b${entry.status}\\b`).test(text));
  if (expectedHttp) {
    expectedHttp.consumed = true;
    return expectedHttp;
  }
  const expectedResource = (state.expectedResourceFailures ?? []).find(entry =>
    !entry.consumed
    && entry.url === locationUrl
    && /Failed to load resource: net::ERR_BLOCKED_BY_CLIENT(?:\.Inspector)?/.test(text));
  if (!expectedResource) return null;
  expectedResource.consumed = true;
  return expectedResource;
};

const serializeGate = record => ({
  id: record.id,
  type: record.type,
  label: record.label,
  contextId: record.contextId,
  scenario: record.scenario,
  status: record.status,
  enteredAt: record.enteredAt,
  releasedAt: record.releasedAt,
  settledAt: record.settledAt,
  activeWaiters: record.activeWaiters,
  totalWaiters: record.totalWaiters,
  error: record.error,
  contextClosedAt: record.contextClosedAt
});

/**
 * One authoritative lifecycle for every promise that can hold a runner request.
 * Records represented by the same Promise share release/settlement state while
 * retaining independently attributable waiters.
 */
export class GateLifecycle {
  constructor({ timeoutMs = 45_000 } = {}) {
    this.timeoutMs = timeoutMs;
    this.recordsByPromise = new Map();
    this.records = [];
    this.cancelersByContext = new Map();
  }

  #recordFor(promise, details) {
    let record = this.recordsByPromise.get(promise);
    if (record) {
      if (record.type !== details.type) {
        throw new RequestContractError(
          'malformed-gate',
          `The same Promise cannot be both ${record.type} and ${details.type}.`,
          { label: details.label }
        );
      }
      return record;
    }
    record = {
      id: `gate-${this.records.length + 1}`,
      type: details.type,
      label: details.label,
      contextId: details.contextId,
      scenario: details.scenario,
      status: 'pending',
      enteredAt: new Date().toISOString(),
      releasedAt: null,
      settledAt: null,
      activeWaiters: 0,
      totalWaiters: 0,
      error: null,
      contextClosedAt: null
    };
    this.recordsByPromise.set(promise, record);
    this.records.push(record);
    Promise.resolve(promise).then(
      () => {
        record.releasedAt = new Date().toISOString();
        if (record.status === 'pending') record.status = 'released';
      },
      error => {
        record.error = error instanceof Error ? error.message : String(error);
        if (record.status === 'pending' || record.status === 'released') record.status = 'rejected';
      }
    );
    return record;
  }

  async wait(promise, details) {
    if (promise === undefined || promise === null || typeof promise?.then !== 'function') {
      throw new RequestContractError(
        'malformed-gate',
        `Controlled fixture gate must be a Promise<void>: ${details.label}.`,
        {
          label: details.label,
          valueType: promise === null ? 'null' : typeof promise
        }
      );
    }

    const record = this.#recordFor(promise, details);
    record.activeWaiters += 1;
    record.totalWaiters += 1;
    const owner = `${details.contextId}:${record.id}:${record.totalWaiters}`;
    let timeoutId;
    let rejectContextClose;
    const contextClosed = new Promise((_, reject) => {
      rejectContextClose = reject;
    });
    if (!this.cancelersByContext.has(details.contextId)) {
      this.cancelersByContext.set(details.contextId, new Set());
    }
    this.cancelersByContext.get(details.contextId).add(rejectContextClose);

    try {
      await Promise.race([
        Promise.resolve(promise),
        new Promise((_, reject) => {
          timeoutId = setTimeout(() => reject(new GateTimeoutError(
            `Fixture gate timed out: ${details.label}.`
          )), this.timeoutMs);
        }),
        contextClosed
      ]);
    } catch (error) {
      if (error instanceof GateTimeoutError) {
        record.status = 'timed-out';
        record.error = error.message;
      } else if (error instanceof GateContextClosedError) {
        record.status = 'context-closed';
        record.error = error.message;
      } else {
        record.status = 'rejected';
        record.error = error instanceof Error ? error.message : String(error);
      }
      throw error;
    } finally {
      clearTimeout(timeoutId);
      this.cancelersByContext.get(details.contextId)?.delete(rejectContextClose);
      record.activeWaiters = Math.max(0, record.activeWaiters - 1);
      if (record.activeWaiters === 0 && record.status === 'released') {
        record.status = 'settled';
        record.settledAt = new Date().toISOString();
      }
      void owner;
    }
  }

  contextClosed(contextId) {
    const closedAt = new Date().toISOString();
    for (const record of this.records) {
      if (record.contextId === contextId && record.status === 'pending') {
        record.status = 'context-closed';
        record.contextClosedAt = closedAt;
        record.error = `Browser context ${contextId} closed with an unfinished ${record.type} gate.`;
      }
    }
    for (const reject of this.cancelersByContext.get(contextId) ?? []) {
      reject(new GateContextClosedError(
        `Browser context ${contextId} closed while a fixture gate was pending.`
      ));
    }
    this.cancelersByContext.delete(contextId);
  }

  unfinished() {
    return this.records
      .filter(record => !['settled', 'rejected', 'timed-out'].includes(record.status))
      .map(serializeGate);
  }

  failures() {
    return this.records
      .filter(record => ['pending', 'released', 'context-closed', 'timed-out', 'rejected']
        .includes(record.status))
      .map(serializeGate);
  }

  snapshot() {
    return this.records.map(serializeGate);
  }

  get activeWaiterCount() {
    return this.records.reduce((total, record) => total + record.activeWaiters, 0);
  }
}

export const createEvidenceRunId = () =>
  `${new Date().toISOString().replace(/[-:.TZ]/g, '')}-${process.pid}-${randomUUID().slice(0, 8)}`;

export const writeReceiptAtomic = async (receiptPath, receipt) => {
  await mkdir(dirname(receiptPath), { recursive: true });
  const temporaryPath = join(
    dirname(receiptPath),
    `.${process.pid}-${randomUUID()}.receipt.tmp`
  );
  await writeFile(temporaryPath, `${JSON.stringify(receipt, null, 2)}\n`, 'utf8');
  await rename(temporaryPath, receiptPath);
};
