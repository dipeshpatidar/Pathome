import assert from 'node:assert/strict';
import test from 'node:test';

import {
  formatPublicArea,
  formatPublicRent,
  loadPublicPropertyPreview,
  PublicPreviewTimeoutError
} from '../utils/publicPropertyPreview.ts';

const property = (id) => ({
  id,
  title: `Property ${id}`,
  city: 'Indore',
  sector: 'Vijay Nagar',
  bhk: '2 BHK',
  monthlyRent: 21000.01,
  totalAreaSqFt: 850
});

test('F1 rent formatting preserves fractional truth up to two decimal places', () => {
  assert.equal(formatPublicRent(21000), '₹21,000');
  assert.equal(formatPublicRent(21000.01), '₹21,000.01');
  assert.equal(formatPublicRent(21000.49), '₹21,000.49');
  assert.equal(formatPublicRent(21000.5), '₹21,000.5');
  assert.equal(formatPublicRent(21000.99), '₹21,000.99');
  assert.equal(formatPublicRent(21000.125), '₹21,000.13');
  assert.equal(formatPublicRent(null), null);
  assert.equal(formatPublicRent(Number.NaN), null);
  assert.equal(formatPublicRent(Number.POSITIVE_INFINITY), null);
  assert.equal(formatPublicRent(0), null);
  assert.equal(formatPublicArea(850), '850 sq ft');

  const legacyWholeRupeeFormatter = new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0
  });
  assert.notEqual(legacyWholeRupeeFormatter.format(21000.01), formatPublicRent(21000.01));
});

test('F2 a never-settling transport is aborted and explicitly times out', async () => {
  let scheduled = null;
  let cancelled = null;
  let attemptSignal = null;
  const pending = loadPublicPropertyPreview(
    201,
    async (_id, signal) => {
      attemptSignal = signal;
      return new Promise(() => {});
    },
    {
      timeoutMs: 10_000,
      timers: {
        schedule: (callback) => {
          scheduled = callback;
          return 1;
        },
        cancel: (timer) => {
          cancelled = timer;
        }
      }
    }
  );

  await Promise.resolve();
  assert.equal(typeof scheduled, 'function');
  scheduled();
  await assert.rejects(pending, PublicPreviewTimeoutError);
  assert.equal(attemptSignal.aborted, true);
  assert.equal(cancelled, 1);
});

test('F2 external abort cancels the timer and never surfaces a timeout', async () => {
  const controller = new AbortController();
  let scheduled = null;
  let cancelled = null;
  const pending = loadPublicPropertyPreview(
    201,
    async (_id, signal) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }),
    {
      externalSignal: controller.signal,
      timers: {
        schedule: (callback) => {
          scheduled = callback;
          return 2;
        },
        cancel: (timer) => {
          cancelled = timer;
        }
      }
    }
  );

  controller.abort();
  await assert.rejects(pending, (error) => error?.name === 'AbortError');
  assert.equal(cancelled, 2);
  assert.equal(typeof scheduled, 'function');
});

test('F2 an expired attempt cannot replace a successful retry', async () => {
  let expireFirstAttempt;
  const first = loadPublicPropertyPreview(
    201,
    async () => new Promise((resolve) => {
      expireFirstAttempt = () => resolve(property(201));
    }),
    {
      timers: {
        schedule: (callback) => {
          callback();
          return 1;
        },
        cancel: () => {}
      }
    }
  );
  await assert.rejects(first, PublicPreviewTimeoutError);

  const retry = await loadPublicPropertyPreview(201, async () => property(201));
  expireFirstAttempt();
  assert.equal(retry.id, 201);
});

test('F2 retry is a new attempt and property identity mismatch remains recoverable', async () => {
  let requests = 0;
  const attempts = [];
  const load = () => loadPublicPropertyPreview(201, async (_id, signal) => {
    requests += 1;
    attempts.push(signal);
    return requests === 1 ? property(202) : property(201);
  });

  await assert.rejects(load(), /identity mismatch/);
  const result = await load();
  assert.equal(result.id, 201);
  assert.equal(requests, 2);
  assert.notEqual(attempts[0], attempts[1]);
});
