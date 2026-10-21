import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';

registerHooks({ resolve: resolveOperationsModule });

const storage = new Map();
globalThis.localStorage = {
  getItem: key => storage.has(key) ? storage.get(key) : null,
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: key => storage.delete(key),
  clear: () => storage.clear()
};
storage.set('pathome_auth_token', 'tenant-token-a');
storage.set('pathome_user', JSON.stringify({ id: 7, role: 'TENANT' }));

const { tenantVisitService } = await import('../services/tenantVisitService.ts');
const {
  buildTenantAvailabilityDraftCommand,
  clearTenantAvailability,
  createTenantAvailabilityDraft,
  editTenantAvailabilityDraft,
  reconcileTenantAvailabilityDraft,
  tenantAvailabilityFactsFromRequest
} = await import('../utils/tenantAvailability.ts');

const request = (overrides = {}) => ({
  requestId: 51,
  version: 3,
  propertyId: 10,
  propertyTitle: 'Garden apartment',
  propertyType: 'FLAT',
  bhk: '2BHK',
  city: 'Indore',
  sector: 'Vijay Nagar',
  propertyAvailable: true,
  coverImageUrl: null,
  preferredVisitTiming: 'Saturday afternoon',
  status: 'COORDINATING',
  requestedAt: '2026-10-18T09:00:00',
  availabilityStartAt: '2026-11-21T04:30:00Z',
  availabilityEndAt: '2026-11-21T07:30:00Z',
  availabilityZoneId: 'Asia/Kolkata',
  preferredAt: null,
  ...overrides
});

const page = (requests, pageNumber, hasMore = false) => ({
  ok: true,
  status: 200,
  requests,
  hasMore,
  json: async () => ({ userId: 7, requests, totalCount: requests.length, page: pageNumber, hasMore })
});

const dispatchedDraft = (base, edit = true) => {
  let draft = createTenantAvailabilityDraft({ request: base, identityKey: '7:tenant-token-a', generation: 1 });
  if (edit) draft = editTenantAvailabilityDraft(draft, 'start', 'startTime', '11:00');
  const built = buildTenantAvailabilityDraftCommand(draft.form, base.version, draft.opening, draft.provenance);
  assert.equal(built.ok, true);
  return {
    ...draft,
    attempt: built.command,
    dispatchRevision: draft.editRevision,
    dispatchFieldRevisions: { ...draft.fieldRevisions },
    dispatchZoneRevision: draft.zoneRevision,
    status: 'saving'
  };
};

const reconcileAcrossPages = async (draft, pages) => {
  for (const entry of pages) {
    if (entry instanceof Error) throw entry;
    const found = entry.requests.find(item => item.requestId === draft.requestId);
    if (found) return reconcileTenantAvailabilityDraft(draft, tenantAvailabilityFactsFromRequest(found), 'matched');
    if (!entry.hasMore) break;
  }
  return reconcileTenantAvailabilityDraft(draft, null, 'unresolved');
};

test('a committed PUT whose response was lost reconciles to a matching read without a second write', async () => {
  const base = request();
  const draft = dispatchedDraft(base);
  const calls = [];
  globalThis.fetch = async (_url, init = {}) => {
    calls.push({ method: init.method ?? 'GET', body: init.body ? JSON.parse(init.body) : null });
    if (init.method === 'PUT') throw new TypeError('connection dropped after dispatch');
    return page([request({
      version: 4,
      availabilityStartAt: draft.attempt.availabilityStartAt,
      availabilityEndAt: draft.attempt.availabilityEndAt,
      availabilityZoneId: draft.attempt.availabilityZoneId,
      preferredAt: draft.attempt.preferredAt
    })], 0);
  };
  await assert.rejects(() => tenantVisitService.updateAvailability(51, draft.attempt), /connection dropped/);
  const firstPage = await tenantVisitService.list(0);
  const reconciled = await reconcileAcrossPages(draft, [firstPage]);
  assert.equal(reconciled.reconciliation, 'matched');
  assert.equal(reconciled.reviewRequired, false);
  assert.match(reconciled.notice, /Current saved times match/);
  assert.match(reconciled.notice, /remains unconfirmed/);
  assert.equal(calls.filter(call => call.method === 'PUT').length, 1, 'no automatic retry is issued');
});

test('a rolled-back PUT whose response was lost is reviewed, never described as applied', async () => {
  const base = request();
  const draft = dispatchedDraft(base);
  const old = await Promise.resolve(page([request()], 0));
  const reconciled = await reconcileAcrossPages(draft, [old]);
  assert.equal(reconciled.reconciliation, 'mismatch');
  assert.equal(reconciled.reviewRequired, true);
  assert.equal(reconciled.form.startTime, '11:00', 'the attempted draft is retained');
  assert.equal(reconciled.currentFacts.startAt, base.availabilityStartAt);
  assert.doesNotMatch(reconciled.notice, /saved successfully/i);
});

test('repeated read failures and a Request absent from bounded pages remain unresolved', async () => {
  const draft = dispatchedDraft(request());
  const failed = reconcileTenantAvailabilityDraft(draft, null, 'failed');
  assert.equal(failed.reconciliation, 'failed');
  assert.equal(failed.reviewRequired, true);
  assert.equal(failed.form.startTime, draft.form.startTime);

  const missing = await reconcileAcrossPages(draft, [
    page([], 0, true),
    page([request({ requestId: 99 })], 1, false)
  ]);
  assert.equal(missing.reconciliation, 'unresolved');
  assert.match(missing.notice, /outcome remains unresolved/);
  assert.equal(missing.form.startTime, '11:00');
});

test('a Request displaced to a later page is found and a matching clear is recognised exactly', async () => {
  const draft = dispatchedDraft(request());
  const later = await Promise.resolve(page([request({
    requestId: 51,
    version: 4,
    availabilityStartAt: draft.attempt.availabilityStartAt,
    availabilityEndAt: draft.attempt.availabilityEndAt,
    availabilityZoneId: draft.attempt.availabilityZoneId
  })], 1, false));
  const found = await reconcileAcrossPages(draft, [page([], 0, true), later]);
  assert.equal(found.reconciliation, 'matched');
  assert.equal(found.lastAcceptedVersion, 4);

  const clearDraft = {
    ...draft,
    attempt: clearTenantAvailability(3)
  };
  const cleared = reconcileTenantAvailabilityDraft(clearDraft, {
    requestId: 51, version: 4, startAt: null, endAt: null, zoneId: null, preferredAt: null
  }, 'matched');
  assert.equal(cleared.reconciliation, 'matched');
  assert.equal(cleared.notice.includes('Current saved times match'), true);
});

test('an uncertain clear that committed all nulls blanks every temporal field without a retry', async () => {
  const base = request();
  let draft = createTenantAvailabilityDraft({
    request: base,
    identityKey: '7:tenant-token-a',
    generation: 1
  });
  const attempt = clearTenantAvailability(base.version);
  draft = {
    ...draft,
    attempt,
    dispatchRevision: draft.editRevision,
    dispatchFieldRevisions: { ...draft.fieldRevisions },
    dispatchZoneRevision: draft.zoneRevision,
    status: 'saving'
  };
  const calls = [];
  globalThis.fetch = async (_url, init = {}) => {
    calls.push({ method: init.method ?? 'GET' });
    if (init.method === 'PUT') throw new TypeError('connection dropped after the clear committed');
    return page([request({
      version: 4,
      availabilityStartAt: null,
      availabilityEndAt: null,
      availabilityZoneId: null,
      preferredAt: null
    })], 0);
  };

  await assert.rejects(() => tenantVisitService.updateAvailability(51, attempt), /connection dropped/);
  const found = await tenantVisitService.list(0);
  const reconciled = reconcileTenantAvailabilityDraft(
    draft,
    tenantAvailabilityFactsFromRequest(found.requests[0]),
    'matched'
  );

  assert.equal(reconciled.reconciliation, 'matched');
  assert.equal(reconciled.reviewRequired, false);
  assert.equal(reconciled.currentFacts.version, 4);
  assert.equal(reconciled.openingVersion, 4);
  assert.equal(reconciled.form.startDate, '');
  assert.equal(reconciled.form.startTime, '');
  assert.equal(reconciled.form.endDate, '');
  assert.equal(reconciled.form.endTime, '');
  assert.equal(reconciled.form.preferredDate, '');
  assert.equal(reconciled.form.preferredTime, '');
  assert.deepEqual(reconciled.provenance, { start: 'EDITED', end: 'EDITED', preferred: 'EDITED' });
  assert.deepEqual(
    buildTenantAvailabilityDraftCommand(
      reconciled.form,
      reconciled.reviewedFacts.version,
      reconciled.opening,
      reconciled.provenance
    ),
    { ok: false, error: 'missing' }
  );
  assert.equal(calls.filter(call => call.method === 'PUT').length, 1, 'the uncertain clear is never retried');
});

test('drafts are scoped by authenticated session identity and never written to browser storage', () => {
  const a = createTenantAvailabilityDraft({ request: request(), identityKey: '7:tenant-token-a', generation: 1 });
  const b = createTenantAvailabilityDraft({ request: request(), identityKey: '7:tenant-token-b', generation: 2 });
  assert.notEqual(a.identityKey, b.identityKey);
  assert.equal(a.requestId, b.requestId);
  assert.deepEqual([...storage.keys()].sort(), ['pathome_auth_token', 'pathome_user']);
});
