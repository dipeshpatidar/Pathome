import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { resolve as resolveOperationsModule } from './support/operationsServiceLoader.mjs';

registerHooks({ resolve: resolveOperationsModule });

const {
  buildTenantAvailabilityDraftCommand,
  buildTenantAvailabilityWindow,
  changeTenantAvailabilityDraftZone,
  changeTenantAvailabilityZone,
  clearTenantAvailability,
  createTenantAvailabilityDraft,
  editTenantAvailabilityDraft,
  formatTenantAvailability,
  observeAuthoritativeTenantAvailability,
  rebaseTenantAvailabilityDraft,
  reconcileTenantAvailabilityDraft,
  settleTenantAvailabilitySaveAcknowledgement,
  tenantAvailabilityFactsEqual,
  tenantAvailabilityFormFromRequest,
  tenantAvailabilityProvenanceFromRequest,
  tenantAvailabilityReviewedVersion,
  tenantRequestForDraft,
  tenantVisitMinimumDate,
  TENANT_VISIT_ZONE
} = await import('../utils/tenantAvailability.ts');
const { compareOperationsInstants } = await import('../utils/operationsWorkspacePresentation.ts');

const baseRequest = (overrides = {}) => ({
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
  status: 'RECEIVED',
  requestedAt: '2026-10-18T09:00:00',
  availabilityStartAt: null,
  availabilityEndAt: null,
  availabilityZoneId: null,
  preferredAt: null,
  ...overrides
});

const form = (overrides = {}) => ({
  startDate: '2026-11-21',
  startTime: '10:00',
  endDate: '2026-11-21',
  endTime: '13:00',
  preferredDate: '',
  preferredTime: '',
  zoneId: TENANT_VISIT_ZONE,
  ...overrides
});

const openingFor = (request) => ({
  zoneId: request.availabilityZoneId,
  startAt: request.availabilityStartAt,
  endAt: request.availabilityEndAt,
  preferredAt: request.preferredAt
});

const buildRecorded = (request, formOverrides = {}, provenanceOverrides = {}) => {
  const recordedForm = { ...tenantAvailabilityFormFromRequest(request), ...formOverrides };
  return buildTenantAvailabilityWindow(recordedForm, request.version, {
    startAt: request.availabilityStartAt,
    endAt: request.availabilityEndAt,
    preferredAt: request.preferredAt,
    form: tenantAvailabilityFormFromRequest(request),
    provenance: { ...tenantAvailabilityProvenanceFromRequest(request), ...provenanceOverrides }
  });
};

test('creation keeps the free-text request and records complete fields in the selected zone', () => {
  const built = buildTenantAvailabilityWindow(form({ preferredDate: '2026-11-21', preferredTime: '11:30' }), 3);
  assert.equal(built.ok, true);
  assert.equal(built.command.availabilityStartAt, '2026-11-21T10:00:00+05:30');
  assert.equal(built.command.availabilityEndAt, '2026-11-21T13:00:00+05:30');
  assert.equal(built.command.preferredAt, '2026-11-21T11:30:00+05:30');
  assert.equal(built.command.availabilityZoneId, 'Asia/Kolkata');

  const modal = readFileSync(new URL('../components/VisitRequestModal.tsx', import.meta.url), 'utf8');
  assert.match(modal, /preferredVisitTiming: `\$\{selectedDate\} · \$\{selectedWindow\.value\}/);
  assert.match(modal, /TENANT_VISIT_ZONES\.map/);
  assert.match(modal, /tenantVisitMinimumDate\(visitZone\)/);
  assert.match(modal, /availabilityZoneId: availability\.command\.availabilityZoneId/);
});

test('New York and Kathmandu existing requests preserve each untouched field independently', () => {
  const newYork = baseRequest({
    availabilityStartAt: '2026-11-21T04:30:00.123456Z',
    availabilityEndAt: '2026-11-21T07:30:00.987654Z',
    availabilityZoneId: 'America/New_York',
    preferredAt: '2026-11-21T05:00:00.000200Z'
  });
  const formValue = tenantAvailabilityFormFromRequest(newYork);
  assert.equal(formValue.zoneId, 'America/New_York');
  assert.equal(formValue.startDate, '2026-11-20');
  assert.equal(formValue.startTime, '23:30');

  const startOnly = buildRecorded(newYork, { startTime: '23:45' }, { start: 'EDITED' });
  assert.equal(startOnly.ok, true);
  assert.equal(startOnly.command.availabilityStartAt, '2026-11-20T23:45:00-05:00');
  assert.match(startOnly.command.availabilityEndAt, /\.987654-05:00$/);
  assert.match(startOnly.command.preferredAt, /\.0002-05:00$/);

  const endOnly = buildRecorded(newYork, { endTime: '02:45', endDate: '2026-11-21' }, { end: 'EDITED' });
  assert.match(endOnly.command.availabilityStartAt, /\.123456-05:00$/);
  assert.equal(endOnly.command.availabilityEndAt, '2026-11-21T02:45:00-05:00');

  const preferredOnly = buildRecorded(newYork, { preferredTime: '01:00', preferredDate: '2026-11-21' },
    { preferred: 'EDITED' });
  assert.match(preferredOnly.command.availabilityStartAt, /\.123456-05:00$/);
  assert.match(preferredOnly.command.availabilityEndAt, /\.987654-05:00$/);
  assert.equal(preferredOnly.command.preferredAt, '2026-11-21T01:00:00-05:00');

  const untouched = buildRecorded(newYork);
  assert.equal(untouched.ok, true);
  assert.match(untouched.command.availabilityStartAt, /\.123456-05:00$/);
  assert.match(untouched.command.availabilityEndAt, /\.987654-05:00$/);

  const kathmandu = baseRequest({
    availabilityStartAt: '2026-07-14T03:45:00Z',
    availabilityEndAt: '2026-07-14T06:45:00Z',
    availabilityZoneId: 'Asia/Kathmandu'
  });
  const kathmanduForm = tenantAvailabilityFormFromRequest(kathmandu);
  assert.equal(kathmanduForm.startTime, '09:30');
  const kathmanduBuilt = buildRecorded(kathmandu);
  assert.equal(kathmanduBuilt.command.availabilityStartAt, '2026-07-14T09:30:00+05:45');

  assert.deepEqual(clearTenantAvailability(8), {
    expectedRequestVersion: 8,
    availabilityStartAt: null,
    availabilityEndAt: null,
    availabilityZoneId: null,
    preferredAt: null
  });
});

test('the committed instants do not depend on the browser environment zone', () => {
  const loader = new URL('./support/operationsServiceLoader.mjs', import.meta.url).href;
  const module = new URL('../utils/tenantAvailability.ts', import.meta.url).href;
  const script = `
    import { registerHooks } from 'node:module';
    import { resolve } from ${JSON.stringify(loader)};
    registerHooks({ resolve });
    const m = await import(${JSON.stringify(module)});
    const built = m.buildTenantAvailabilityWindow({
      startDate: '2026-11-21', startTime: '22:00',
      endDate: '2026-11-22', endTime: '02:00',
      preferredDate: '2026-11-22', preferredTime: '01:00',
      zoneId: 'America/New_York'
    }, 3);
    process.stdout.write(JSON.stringify({ ok: built.ok, command: built.command, zone: Intl.DateTimeFormat().resolvedOptions().timeZone }));
  `;
  const results = ['UTC', 'Asia/Kolkata', 'America/New_York'].map(TZ => JSON.parse(execFileSync(
    process.execPath,
    ['--input-type=module', '-e', script],
    { cwd: new URL('..', import.meta.url).pathname, env: { ...process.env, TZ }, encoding: 'utf8' }
  )));
  assert.equal(results[0].zone === results[1].zone, false, 'the child processes really use different browser zones');
  for (const result of results) {
    assert.equal(result.ok, true);
    assert.equal(result.command.availabilityStartAt, '2026-11-21T22:00:00-05:00');
    assert.equal(result.command.availabilityEndAt, '2026-11-22T02:00:00-05:00');
    assert.equal(result.command.preferredAt, '2026-11-22T01:00:00-05:00');
  }
});

test('equivalent offsets, exact fractions and microsecond ordering stay distinct', () => {
  const request = baseRequest({
    availabilityStartAt: '2026-11-21T04:30:00.000200Z',
    availabilityEndAt: '2026-11-21T04:30:00.000800Z',
    availabilityZoneId: 'Asia/Kolkata'
  });
  const built = buildRecorded(request);
  assert.match(built.command.availabilityStartAt, /\.0002\+05:30$/);
  assert.match(built.command.availabilityEndAt, /\.0008\+05:30$/);
  assert.equal(compareOperationsInstants(built.command.availabilityStartAt, built.command.availabilityEndAt), -1);
  assert.equal(tenantAvailabilityFactsEqual(
    { availabilityStartAt: '2026-11-21T04:30:00Z', availabilityEndAt: '2026-11-21T07:30:00Z',
      availabilityZoneId: 'Asia/Kolkata', preferredAt: null },
    { availabilityStartAt: '2026-11-21T10:00:00+05:30', availabilityEndAt: '2026-11-21T13:00:00+05:30',
      availabilityZoneId: 'Asia/Kolkata', preferredAt: null }
  ), true);
});

test('preferred time is inclusive at both endpoints and one persisted microsecond outside is refused', () => {
  const inside = baseRequest({
    availabilityStartAt: '2026-11-21T04:30:00.000200Z',
    availabilityEndAt: '2026-11-21T04:30:00.000800Z',
    availabilityZoneId: 'Asia/Kolkata',
    preferredAt: '2026-11-21T04:30:00.000200Z'
  });
  assert.equal(buildRecorded(inside).ok, true, 'equal to start is inside the window');
  assert.equal(buildRecorded({ ...inside, preferredAt: inside.availabilityEndAt }).ok, true,
    'equal to end is inside the window');
  const outside = buildRecorded({ ...inside, preferredAt: '2026-11-21T04:30:00.000199Z' });
  assert.deepEqual(outside, { ok: false, error: 'preferred-outside' });
  assert.equal(compareOperationsInstants('2026-11-21T04:30:00.000199Z', '2026-11-21T04:30:00.000200Z'), -1,
    'lossy millisecond ordering would call these equal; the precise comparator does not');
});

test('edit-revert keeps EDITED provenance and zone A to B to A preserves untouched instants', () => {
  const request = baseRequest({
    availabilityStartAt: '2026-11-21T04:30:00.123456Z',
    availabilityEndAt: '2026-11-21T07:30:00.987654Z',
    availabilityZoneId: 'Asia/Kolkata'
  });
  const opening = openingFor(request);
  const original = tenantAvailabilityFormFromRequest(request);
  const edited = { ...original, startTime: '10:00' };
  const editedBuilt = buildTenantAvailabilityDraftCommand(
    edited, 3, opening, { ...tenantAvailabilityProvenanceFromRequest(request), start: 'EDITED' }
  );
  assert.equal(editedBuilt.ok, true);
  assert.equal(editedBuilt.command.availabilityStartAt, '2026-11-21T10:00:00+05:30',
    'a deliberate edit uses the typed civil minute, not the recorded fraction');

  const untouched = tenantAvailabilityProvenanceFromRequest(request);
  const toNewYork = changeTenantAvailabilityZone(original, 'America/New_York', opening, untouched);
  assert.equal(toNewYork.startTime, '23:30');
  const back = changeTenantAvailabilityZone(toNewYork, 'Asia/Kolkata', opening, untouched);
  assert.equal(back.startTime, '10:00');
  const builtBack = buildTenantAvailabilityDraftCommand(back, 3, opening, untouched);
  assert.equal(builtBack.command.availabilityStartAt, '2026-11-21T10:00:00.123456+05:30');
});

test('overnight, multi-day and year rollover windows keep separate dates', () => {
  const overnight = buildTenantAvailabilityWindow(form({
    startTime: '22:00', endDate: '2026-11-22', endTime: '02:00',
    preferredDate: '2026-11-22', preferredTime: '01:00'
  }), 4);
  assert.equal(overnight.ok, true);
  assert.equal(overnight.command.availabilityStartAt, '2026-11-21T22:00:00+05:30');
  assert.equal(overnight.command.availabilityEndAt, '2026-11-22T02:00:00+05:30');

  const multiDay = buildTenantAvailabilityWindow(form({
    startTime: '23:00', endDate: '2026-12-02', endTime: '10:00'
  }), 4);
  assert.equal(multiDay.ok, true);

  const rollover = buildTenantAvailabilityWindow(form({
    startDate: '2026-12-31', startTime: '23:00',
    endDate: '2027-01-01', endTime: '01:00',
    preferredDate: '2027-01-01', preferredTime: '00:30'
  }), 4);
  assert.equal(rollover.ok, true);
  assert.equal(rollover.command.availabilityEndAt, '2027-01-01T01:00:00+05:30');
});

test('DST gaps, both saved folds, a transition-spanning window and malformed zones are handled explicitly', () => {
  assert.deepEqual(buildTenantAvailabilityWindow(form({
    startDate: '2027-03-14', startTime: '02:30', endDate: '2027-03-14', endTime: '04:00',
    zoneId: 'America/New_York'
  }), 3), { ok: false, error: 'gap' });
  assert.deepEqual(buildTenantAvailabilityWindow(form({
    startDate: '2027-11-07', startTime: '01:30', endDate: '2027-11-07', endTime: '03:00',
    zoneId: 'America/New_York'
  }), 3), { ok: false, error: 'ambiguous' });

  for (const [startAt, offset] of [
    ['2027-11-07T05:30:00Z', '-04:00'],
    ['2027-11-07T06:30:00Z', '-05:00']
  ]) {
    const request = baseRequest({
      availabilityStartAt: startAt,
      availabilityEndAt: '2027-11-07T08:00:00Z',
      availabilityZoneId: 'America/New_York'
    });
    const built = buildRecorded(request);
    assert.equal(built.ok, true);
    assert.match(built.command.availabilityStartAt, new RegExp(`${offset}$`));
  }

  const spanning = buildTenantAvailabilityWindow(form({
    startDate: '2027-11-07', startTime: '00:30', endDate: '2027-11-07', endTime: '03:30',
    zoneId: 'America/New_York'
  }), 3);
  assert.equal(spanning.ok, true);
  assert.equal(spanning.command.availabilityStartAt, '2027-11-07T00:30:00-04:00');
  assert.equal(spanning.command.availabilityEndAt, '2027-11-07T03:30:00-05:00');

  assert.deepEqual(buildTenantAvailabilityWindow(form({ zoneId: 'Mars/Olympus' }), 3),
    { ok: false, error: 'zone' });
  const malformed = baseRequest({
    availabilityStartAt: '2026-11-21T04:30:00Z',
    availabilityEndAt: '2026-11-21T07:30:00Z',
    availabilityZoneId: 'Not/AZone'
  });
  assert.equal(tenantAvailabilityFormFromRequest(malformed).zoneId, '');
  assert.deepEqual(buildRecorded(malformed), { ok: false, error: 'zone' });
});

test('null legacy data, partial input, equal or reversed windows and preference removal never write', () => {
  const legacy = baseRequest();
  assert.equal(tenantAvailabilityFormFromRequest(legacy).zoneId, '');
  assert.deepEqual(buildRecorded(legacy), { ok: false, error: 'zone' });
  assert.deepEqual(buildTenantAvailabilityWindow(form({ zoneId: '' }), 3), { ok: false, error: 'zone' });
  assert.deepEqual(buildTenantAvailabilityWindow(form({ startDate: '' }), 3), { ok: false, error: 'missing' });
  assert.deepEqual(buildTenantAvailabilityWindow(form({ startTime: '13:00' }), 3), { ok: false, error: 'order' });
  assert.deepEqual(buildTenantAvailabilityWindow(form({ startTime: '13:00', endTime: '13:00' }), 3),
    { ok: false, error: 'order' });
  assert.deepEqual(buildTenantAvailabilityWindow(form({
    startTime: '10:00', endTime: '13:00', preferredDate: '2026-11-21', preferredTime: '15:00'
  }), 3), { ok: false, error: 'preferred-outside' });
  assert.deepEqual(buildTenantAvailabilityWindow(form({
    preferredDate: '2026-11-21', preferredTime: '11:00'
  }), 3).command.preferredAt, '2026-11-21T11:00:00+05:30');
  const removed = buildTenantAvailabilityWindow(form({ preferredDate: '', preferredTime: '' }), 3);
  assert.equal(removed.command.preferredAt, null, 'removing the preference is a partial update, not a clear');
});

test('dirty history refresh keeps the reviewed baseline and latches explicit review across edits', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  let draft = createTenantAvailabilityDraft({ request: opening, identityKey: '7:token-a', generation: 1 });
  draft = editTenantAvailabilityDraft(draft, 'start', 'startTime', '11:00');
  const newer = baseRequest({
    version: 4,
    availabilityStartAt: '2026-11-21T12:00:00+05:30',
    availabilityEndAt: '2026-11-21T15:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });

  const observed = observeAuthoritativeTenantAvailability(draft, newer);
  assert.equal(observed.reviewRequired, true);
  assert.equal(observed.reviewedFacts.version, 3);
  assert.equal(observed.currentFacts.version, 4);
  assert.equal(observed.form.startTime, '11:00', 'unsaved intent is preserved');
  assert.equal(tenantAvailabilityReviewedVersion(observed), 3,
    'the write baseline cannot silently advance with the displayed history');

  const editedAgain = editTenantAvailabilityDraft(observed, 'end', 'endTime', '14:00');
  assert.equal(editedAgain.reviewRequired, true);
  const zoneChanged = changeTenantAvailabilityDraftZone(editedAgain, 'Asia/Dubai');
  assert.equal(zoneChanged.reviewRequired, true);
  assert.equal(tenantAvailabilityReviewedVersion(zoneChanged), 3);

  const rebased = rebaseTenantAvailabilityDraft(zoneChanged);
  assert.equal(rebased.reviewRequired, false);
  assert.equal(tenantAvailabilityReviewedVersion(rebased), 4);
  assert.equal(rebased.form.startTime, '12:00');
  assert.equal(rebased.form.endTime, '15:00');
});

test('a zone-only edit is dirty, blocks silent adoption, and is discarded only by explicit rebase', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  const pristine = createTenantAvailabilityDraft({
    request: opening,
    identityKey: '7:token-a',
    generation: 1
  });

  const sameZone = changeTenantAvailabilityDraftZone(pristine, 'Asia/Kolkata');
  assert.equal(sameZone.editRevision, 0, 're-selecting the same zone is not dirty intent');
  assert.deepEqual(sameZone.form, pristine.form);

  const kathmandu = changeTenantAvailabilityDraftZone(pristine, 'Asia/Kathmandu');
  assert.equal(kathmandu.form.zoneId, 'Asia/Kathmandu');
  assert.equal(kathmandu.form.startTime, '10:15', 'the untouched start is re-expressed in the new zone');
  assert.equal(kathmandu.form.endTime, '13:15', 'the untouched end is re-expressed in the new zone');

  const built = buildTenantAvailabilityDraftCommand(
    kathmandu.form,
    tenantAvailabilityReviewedVersion(kathmandu),
    kathmandu.opening,
    kathmandu.provenance
  );
  assert.equal(built.ok, true);
  assert.equal(built.command.availabilityZoneId, 'Asia/Kathmandu');
  assert.equal(compareOperationsInstants(
    built.command.availabilityStartAt,
    '2026-11-21T10:00:00+05:30'
  ), 0, 'the untouched start instant does not move with the display zone');
  const newer = baseRequest({
    version: 4,
    availabilityStartAt: '2026-11-21T12:00:00+05:30',
    availabilityEndAt: '2026-11-21T15:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  const observed = observeAuthoritativeTenantAvailability(kathmandu, newer);
  assert.equal(observed.reviewRequired, true, 'newer history cannot silently authorize a write');
  assert.equal(observed.currentFacts.version, 4);
  assert.equal(observed.reviewedFacts.version, 3);
  assert.equal(observed.form.zoneId, 'Asia/Kathmandu', 'the unsaved zone intent survives adoption');
  assert.equal(observed.form.startTime, '10:15');
  assert.equal(tenantAvailabilityReviewedVersion(observed), 3);
  assert.equal(sameZone.zoneRevision, 0);
  assert.equal(kathmandu.editRevision, 1, 'the effective zone change is dirty');
  assert.equal(kathmandu.zoneRevision, 1, 'the zone has its own dispatchable revision');
  assert.deepEqual(kathmandu.fieldRevisions, { start: 0, end: 0, preferred: 0 },
    'a zone-only change does not fabricate temporal field edits');

  const backToKolkata = changeTenantAvailabilityDraftZone(observed, 'Asia/Kolkata');
  assert.equal(backToKolkata.form.zoneId, 'Asia/Kolkata');
  assert.equal(backToKolkata.form.startTime, '10:00', 'A→B→A restores the original civil text');
  assert.equal(backToKolkata.form.endTime, '13:00');
  assert.equal(backToKolkata.editRevision, 2);
  assert.equal(backToKolkata.zoneRevision, 2);
  assert.equal(backToKolkata.reviewRequired, true, 'review remains latched until an explicit boundary');

  const rebased = rebaseTenantAvailabilityDraft(backToKolkata);
  assert.equal(rebased.reviewRequired, false);
  assert.equal(rebased.editRevision, 0);
  assert.equal(rebased.zoneRevision, 0);
  assert.equal(rebased.form.zoneId, 'Asia/Kolkata');
  assert.equal(rebased.form.startTime, '12:00', 'explicit rebase adopts the newest saved window');
  assert.equal(rebased.form.endTime, '15:00');
  assert.equal(tenantAvailabilityReviewedVersion(rebased), 4);
});

test('zone changes compose independently with each temporal edit and preserve exact field provenance', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata',
    preferredAt: '2026-11-21T11:00:00+05:30'
  });
  for (const [field, key, value] of [
    ['start', 'startTime', '09:30'],
    ['end', 'endTime', '14:30'],
    ['preferred', 'preferredTime', '12:30']
  ]) {
    let draft = createTenantAvailabilityDraft({
      request: opening,
      identityKey: '7:token-a',
      generation: 1
    });
    draft = editTenantAvailabilityDraft(draft, field, key, value);
    const beforeZone = { ...draft.form };
    draft = changeTenantAvailabilityDraftZone(draft, 'Asia/Kathmandu');
    assert.equal(draft.form.zoneId, 'Asia/Kathmandu');
    assert.equal(draft.form[key], value, `${field} keeps its deliberately typed wall clock`);
    assert.equal(draft.provenance[field], 'EDITED');
    for (const untouched of ['start', 'end', 'preferred'].filter(item => item !== field)) {
      assert.equal(draft.provenance[untouched], 'UNTOUCHED');
      assert.notEqual(draft.form[`${untouched}Time`], beforeZone[`${untouched}Time`],
        `${untouched} is re-expressed from its untouched instant`);
    }
    assert.equal(draft.editRevision, 2);
    assert.equal(draft.zoneRevision, 2);
  }
});

test('zone intent made after an uncertain dispatch survives matching and failed reads until rebase', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  let draft = createTenantAvailabilityDraft({
    request: opening,
    identityKey: '7:token-a',
    generation: 1
  });
  draft = changeTenantAvailabilityDraftZone(draft, 'Asia/Kathmandu');
  const built = buildTenantAvailabilityDraftCommand(
    draft.form,
    tenantAvailabilityReviewedVersion(draft),
    draft.opening,
    draft.provenance
  );
  assert.equal(built.ok, true);
  draft = {
    ...draft,
    attempt: built.command,
    dispatchRevision: draft.editRevision,
    dispatchFieldRevisions: { ...draft.fieldRevisions },
    dispatchZoneRevision: draft.zoneRevision,
    status: 'saving'
  };
  draft = changeTenantAvailabilityDraftZone(draft, 'Asia/Dubai');
  assert.equal(draft.form.zoneId, 'Asia/Dubai');
  assert.equal(draft.form.startTime, '08:30');
  assert.equal(draft.form.endTime, '11:30');
  assert.equal(draft.zoneRevision, draft.dispatchZoneRevision + 1);

  const matched = reconcileTenantAvailabilityDraft(draft, {
    requestId: 51,
    version: 4,
    startAt: built.command.availabilityStartAt,
    endAt: built.command.availabilityEndAt,
    zoneId: built.command.availabilityZoneId,
    preferredAt: built.command.preferredAt
  }, 'matched');
  assert.equal(matched.reconciliation, 'matched');
  assert.equal(matched.currentFacts.version, 4);
  assert.equal(matched.reviewedFacts.version, 4);
  assert.equal(matched.form.zoneId, 'Asia/Dubai', 'the newer zone intent is not replaced by the acknowledgement');
  assert.equal(matched.form.startTime, '08:30');
  assert.equal(matched.form.endTime, '11:30');
  assert.equal(matched.attempt, built.command, 'the immutable uncertain attempt remains available');

  const failed = reconcileTenantAvailabilityDraft(matched, null, 'failed');
  assert.equal(failed.reviewRequired, true);
  assert.equal(failed.currentFacts.version, 4);
  assert.equal(failed.form.zoneId, 'Asia/Dubai');
  assert.equal(failed.form.startTime, '08:30');

  const rebased = rebaseTenantAvailabilityDraft(failed);
  assert.equal(rebased.reviewRequired, false);
  assert.equal(rebased.form.zoneId, 'Asia/Kathmandu');
  assert.equal(rebased.form.startTime, '10:15');
  assert.equal(rebased.form.endTime, '13:15');
  assert.equal(rebased.zoneRevision, 0);
  assert.equal(rebased.dispatchZoneRevision, null);
});

const dispatchClearDraft = (opening) => {
  const draft = createTenantAvailabilityDraft({
    request: opening,
    identityKey: '7:token-a',
    generation: 1
  });
  return {
    ...draft,
    attempt: clearTenantAvailability(opening.version),
    dispatchRevision: draft.editRevision,
    dispatchFieldRevisions: { ...draft.fieldRevisions },
    dispatchZoneRevision: draft.zoneRevision,
    status: 'saving'
  };
};

const ALL_NULL_V4 = {
  requestId: 51,
  version: 4,
  startAt: null,
  endAt: null,
  zoneId: null,
  preferredAt: null
};

test('a matching all-null read clears untouched stale values without discarding a newer zone', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  const dispatched = dispatchClearDraft(opening);
  const withNewerZone = changeTenantAvailabilityDraftZone(dispatched, 'Asia/Dubai');
  assert.equal(withNewerZone.form.startTime, '08:30', 'the pre-read display was still based on v3');
  assert.equal(withNewerZone.form.endTime, '11:30');

  const matched = reconcileTenantAvailabilityDraft(withNewerZone, ALL_NULL_V4, 'matched');
  assert.equal(matched.reconciliation, 'matched');
  assert.equal(matched.reviewRequired, false);
  assert.equal(matched.form.zoneId, 'Asia/Dubai', 'the genuine newer zone selection is retained');
  for (const key of ['startDate', 'startTime', 'endDate', 'endTime', 'preferredDate', 'preferredTime']) {
    assert.equal(matched.form[key], '', `${key} is blank rather than an obsolete v3 value`);
  }
  assert.deepEqual(matched.provenance, { start: 'EDITED', end: 'EDITED', preferred: 'EDITED' },
    'no null field keeps UNTOUCHED provenance from the cleared window');
  assert.equal(matched.opening.startAt, null);
  assert.equal(matched.opening.endAt, null);
  assert.equal(matched.openingVersion, 4);
  assert.equal(matched.reviewedFacts.version, 4, 'the reviewed baseline advances to the authoritative v4');
  assert.strictEqual(matched.attempt, dispatched.attempt, 'the immutable clear intent remains untouched');
  assert.deepEqual(
    buildTenantAvailabilityDraftCommand(
      matched.form,
      tenantAvailabilityReviewedVersion(matched),
      matched.opening,
      matched.provenance
    ),
    { ok: false, error: 'missing' },
    'a blank window cannot silently build a write'
  );

  const withoutZoneEdit = reconcileTenantAvailabilityDraft(dispatchClearDraft(opening), ALL_NULL_V4, 'matched');
  assert.equal(withoutZoneEdit.form.zoneId, 'Asia/Kolkata', 'the previously chosen zone remains selectable');
  assert.equal(withoutZoneEdit.form.startTime, '');
  assert.equal(withoutZoneEdit.form.endTime, '');
  assert.deepEqual(withoutZoneEdit.provenance, { start: 'EDITED', end: 'EDITED', preferred: 'EDITED' });
});

test('genuine post-dispatch edits survive an all-null read independently in either order', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  const cases = [
    {
      name: 'start only',
      edits: [['start', 'startTime', '09:30']],
      preserved: { startTime: '09:30' },
      build: { ok: false, error: 'missing' }
    },
    {
      name: 'end only',
      edits: [['end', 'endTime', '12:00']],
      preserved: { endTime: '12:00' },
      build: { ok: false, error: 'missing' }
    },
    {
      name: 'start and end',
      edits: [['start', 'startTime', '09:30'], ['end', 'endTime', '12:00']],
      preserved: { startTime: '09:30', endTime: '12:00' },
      build: {
        ok: true,
        command: {
          expectedRequestVersion: 4,
          availabilityStartAt: '2026-11-21T09:30:00+04:00',
          availabilityEndAt: '2026-11-21T12:00:00+04:00',
          availabilityZoneId: 'Asia/Dubai',
          preferredAt: null
        }
      }
    },
    {
      name: 'preferred only',
      edits: [['preferred', 'preferredDate', '2026-11-21'], ['preferred', 'preferredTime', '10:00']],
      preserved: { preferredDate: '2026-11-21', preferredTime: '10:00' },
      build: { ok: false, error: 'missing' }
    },
    {
      name: 'all three',
      edits: [
        ['start', 'startTime', '09:30'],
        ['end', 'endTime', '12:00'],
        ['preferred', 'preferredDate', '2026-11-21'],
        ['preferred', 'preferredTime', '10:30']
      ],
      preserved: {
        startTime: '09:30',
        endTime: '12:00',
        preferredDate: '2026-11-21',
        preferredTime: '10:30'
      },
      build: {
        ok: true,
        command: {
          expectedRequestVersion: 4,
          availabilityStartAt: '2026-11-21T09:30:00+04:00',
          availabilityEndAt: '2026-11-21T12:00:00+04:00',
          availabilityZoneId: 'Asia/Dubai',
          preferredAt: '2026-11-21T10:30:00+04:00'
        }
      }
    }
  ];

  for (const scenario of cases) {
    for (const order of ['edits-then-zone', 'zone-then-edits']) {
      let draft = dispatchClearDraft(opening);
      if (order === 'zone-then-edits') draft = changeTenantAvailabilityDraftZone(draft, 'Asia/Dubai');
      for (const [field, key, value] of scenario.edits) {
        draft = editTenantAvailabilityDraft(draft, field, key, value);
      }
      if (order === 'edits-then-zone') draft = changeTenantAvailabilityDraftZone(draft, 'Asia/Dubai');

      const matched = reconcileTenantAvailabilityDraft(draft, ALL_NULL_V4, 'matched');
      const label = `${scenario.name} (${order})`;
      assert.equal(matched.form.zoneId, 'Asia/Dubai', label);
      for (const [key, value] of Object.entries(scenario.preserved)) {
        assert.equal(matched.form[key], value, `${label}: ${key} keeps the genuine edit`);
      }
      for (const field of ['start', 'end', 'preferred']) {
        const edited = scenario.edits.some(([name]) => name === field);
        if (edited) {
          assert.equal(matched.provenance[field], 'EDITED', `${label}: ${field} stays deliberately edited`);
        } else {
          const dateKey = field === 'start' ? 'startDate' : field === 'end' ? 'endDate' : 'preferredDate';
          const timeKey = field === 'start' ? 'startTime' : field === 'end' ? 'endTime' : 'preferredTime';
          assert.equal(matched.form[dateKey], '', `${label}: untouched ${field} date is blank`);
          assert.equal(matched.form[timeKey], '', `${label}: untouched ${field} time is blank`);
          assert.equal(matched.provenance[field], 'EDITED', `${label}: cleared ${field} provenance is reset`);
        }
      }
      assert.deepEqual(
        buildTenantAvailabilityDraftCommand(
          matched.form,
          tenantAvailabilityReviewedVersion(matched),
          matched.opening,
          matched.provenance
        ),
        scenario.build,
        `${label}: only a complete edited window may build`
      );
      assert.equal(matched.reviewRequired, false, `${label}: a matching clear read needs no rebase`);
    }
  }
});

test('a newer zone keeps authoritative non-null instants exact after acknowledgement', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T04:30:00.123456Z',
    availabilityEndAt: '2026-11-21T07:30:00.987654Z',
    availabilityZoneId: 'Asia/Kolkata'
  });
  let untouchedDraft = createTenantAvailabilityDraft({
    request: opening,
    identityKey: '7:token-a',
    generation: 1
  });
  const untouchedBuilt = buildTenantAvailabilityDraftCommand(
    untouchedDraft.form,
    opening.version,
    untouchedDraft.opening,
    untouchedDraft.provenance
  );
  assert.equal(untouchedBuilt.ok, true);
  untouchedDraft = {
    ...untouchedDraft,
    attempt: untouchedBuilt.command,
    dispatchRevision: untouchedDraft.editRevision,
    dispatchFieldRevisions: { ...untouchedDraft.fieldRevisions },
    dispatchZoneRevision: untouchedDraft.zoneRevision,
    status: 'saving'
  };
  untouchedDraft = changeTenantAvailabilityDraftZone(untouchedDraft, 'Asia/Dubai');
  const acknowledged = settleTenantAvailabilitySaveAcknowledgement(untouchedDraft, {
    requestId: 51,
    version: 4,
    startAt: untouchedBuilt.command.availabilityStartAt,
    endAt: untouchedBuilt.command.availabilityEndAt,
    zoneId: untouchedBuilt.command.availabilityZoneId,
    preferredAt: null
  });
  assert.equal(acknowledged.form.zoneId, 'Asia/Dubai');
  assert.equal(acknowledged.form.startTime, '08:30');
  assert.equal(acknowledged.form.endTime, '11:30');
  assert.deepEqual(acknowledged.provenance, { start: 'UNTOUCHED', end: 'UNTOUCHED', preferred: 'EDITED' });
  const relabelled = buildTenantAvailabilityDraftCommand(
    acknowledged.form,
    tenantAvailabilityReviewedVersion(acknowledged),
    acknowledged.opening,
    acknowledged.provenance
  );
  assert.equal(relabelled.ok, true);
  assert.match(relabelled.command.availabilityStartAt, /\.123456\+04:00$/);
  assert.match(relabelled.command.availabilityEndAt, /\.987654\+04:00$/);
  assert.equal(compareOperationsInstants(
    relabelled.command.availabilityStartAt,
    untouchedBuilt.command.availabilityStartAt
  ), 0, 'changing the display zone never moves an untouched instant');

  let editedDraft = createTenantAvailabilityDraft({
    request: opening,
    identityKey: '7:token-a',
    generation: 1
  });
  editedDraft = editTenantAvailabilityDraft(editedDraft, 'start', 'startTime', '11:00');
  editedDraft = editTenantAvailabilityDraft(editedDraft, 'end', 'endTime', '14:30');
  const editedBuilt = buildTenantAvailabilityDraftCommand(
    editedDraft.form,
    opening.version,
    editedDraft.opening,
    editedDraft.provenance
  );
  assert.equal(editedBuilt.ok, true);
  editedDraft = {
    ...editedDraft,
    attempt: editedBuilt.command,
    dispatchRevision: editedDraft.editRevision,
    dispatchFieldRevisions: { ...editedDraft.fieldRevisions },
    dispatchZoneRevision: editedDraft.zoneRevision,
    status: 'saving'
  };
  editedDraft = changeTenantAvailabilityDraftZone(editedDraft, 'Asia/Dubai');
  assert.equal(editedDraft.form.startTime, '11:00', 'the pre-dispatch edit keeps its typed wall clock');
  const settled = settleTenantAvailabilitySaveAcknowledgement(editedDraft, {
    requestId: 51,
    version: 4,
    startAt: editedBuilt.command.availabilityStartAt,
    endAt: editedBuilt.command.availabilityEndAt,
    zoneId: editedBuilt.command.availabilityZoneId,
    preferredAt: null
  });
  assert.equal(settled.form.zoneId, 'Asia/Dubai');
  assert.equal(settled.form.startTime, '09:30', 'the acknowledged 11:00 Kolkata start is 09:30 in Dubai');
  assert.equal(settled.form.endTime, '13:00', 'the acknowledged 14:30 Kolkata end is 13:00 in Dubai');
  assert.deepEqual(settled.provenance, { start: 'UNTOUCHED', end: 'UNTOUCHED', preferred: 'EDITED' },
    'saved instants are relabelled by the retained zone, not re-interpreted as edited civil text');
  const rebuilt = buildTenantAvailabilityDraftCommand(
    settled.form,
    tenantAvailabilityReviewedVersion(settled),
    settled.opening,
    settled.provenance
  );
  assert.equal(rebuilt.ok, true);
  assert.equal(compareOperationsInstants(
    rebuilt.command.availabilityStartAt,
    editedBuilt.command.availabilityStartAt
  ), 0);
  assert.equal(compareOperationsInstants(
    rebuilt.command.availabilityEndAt,
    editedBuilt.command.availabilityEndAt
  ), 0);
  assert.equal(rebuilt.command.availabilityZoneId, 'Asia/Dubai');
});

test('a late current acknowledgement preserves a newer zone choice and cannot advance reviewed v3', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  let draft = createTenantAvailabilityDraft({
    request: opening,
    identityKey: '7:token-a',
    generation: 1
  });
  draft = changeTenantAvailabilityDraftZone(draft, 'Asia/Kathmandu');
  const built = buildTenantAvailabilityDraftCommand(
    draft.form,
    3,
    draft.opening,
    draft.provenance
  );
  assert.equal(built.ok, true);
  draft = {
    ...draft,
    attempt: built.command,
    dispatchRevision: draft.editRevision,
    dispatchFieldRevisions: { ...draft.fieldRevisions },
    dispatchZoneRevision: draft.zoneRevision,
    status: 'saving'
  };
  draft = changeTenantAvailabilityDraftZone(draft, 'Asia/Dubai');
  const newer = baseRequest({
    version: 5,
    availabilityStartAt: '2026-11-21T12:00:00+05:30',
    availabilityEndAt: '2026-11-21T15:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  draft = observeAuthoritativeTenantAvailability(draft, newer);
  assert.equal(draft.reviewRequired, true);
  const settled = settleTenantAvailabilitySaveAcknowledgement(draft, {
    requestId: 51,
    version: 4,
    startAt: built.command.availabilityStartAt,
    endAt: built.command.availabilityEndAt,
    zoneId: built.command.availabilityZoneId,
    preferredAt: built.command.preferredAt
  });
  assert.equal(settled.reviewRequired, true);
  assert.equal(settled.currentFacts.version, 5);
  assert.equal(settled.reviewedFacts.version, 3);
  assert.equal(settled.form.zoneId, 'Asia/Dubai');
  assert.equal(settled.form.startTime, '08:30');
  assert.equal(settled.dispatchZoneRevision, null);
  assert.equal(tenantAvailabilityReviewedVersion(settled), 3);
});

test('a pristine draft adopts newer facts and older delayed pages never regress them', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  const draft = createTenantAvailabilityDraft({ request: opening, identityKey: '7:token-a', generation: 1 });
  const newer = baseRequest({
    version: 4,
    availabilityStartAt: '2026-11-21T12:00:00+05:30',
    availabilityEndAt: '2026-11-21T15:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  const adopted = observeAuthoritativeTenantAvailability(draft, newer);
  assert.equal(adopted.reviewRequired, false);
  assert.equal(adopted.editRevision, 0);
  assert.equal(tenantAvailabilityReviewedVersion(adopted), 4);
  assert.equal(adopted.form.startTime, '12:00');

  const lateV3 = observeAuthoritativeTenantAvailability(adopted, opening);
  assert.strictEqual(lateV3, adopted, 'an older history response is ignored entirely');
});

test('a late successful acknowledgement cannot clear a newer observed review latch', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  let draft = createTenantAvailabilityDraft({ request: opening, identityKey: '7:token-a', generation: 1 });
  draft = editTenantAvailabilityDraft(draft, 'start', 'startTime', '11:00');
  const built = buildTenantAvailabilityDraftCommand(draft.form, 3, draft.opening, draft.provenance);
  assert.equal(built.ok, true);
  draft = {
    ...draft,
    attempt: built.command,
    dispatchRevision: draft.editRevision,
    dispatchFieldRevisions: { ...draft.fieldRevisions },
    status: 'saving'
  };
  const v5 = baseRequest({
    version: 5,
    availabilityStartAt: '2026-11-21T12:00:00+05:30',
    availabilityEndAt: '2026-11-21T15:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  draft = observeAuthoritativeTenantAvailability(draft, v5);
  assert.equal(draft.reviewRequired, true);
  assert.equal(draft.currentFacts.version, 5);
  assert.equal(draft.reviewedFacts.version, 3);

  const settled = settleTenantAvailabilitySaveAcknowledgement(draft, {
    requestId: opening.requestId,
    version: 4,
    startAt: built.command.availabilityStartAt,
    endAt: built.command.availabilityEndAt,
    zoneId: built.command.availabilityZoneId,
    preferredAt: built.command.preferredAt
  });
  assert.equal(settled.currentFacts.version, 5, 'the newer observed snapshot is preserved');
  assert.equal(settled.reviewedFacts.version, 3, 'the reviewed baseline cannot advance behind the tenant');
  assert.equal(settled.reviewRequired, true, 'the review latch survives the late acknowledgement');
  assert.equal(settled.form.startTime, '11:00', 'the unsaved form is preserved');
  assert.equal(settled.attempt, null, 'only this acknowledgement is settled');
  assert.equal(settled.status, 'uncertain');
  assert.match(settled.notice, /earlier save was accepted/i);
  assert.match(settled.notice, /newer saved times/i);
  assert.equal(tenantAvailabilityReviewedVersion(settled), 3);

  const edited = changeTenantAvailabilityDraftZone(
    editTenantAvailabilityDraft(settled, 'preferred', 'preferredTime', ''),
    'America/New_York'
  );
  assert.equal(edited.reviewRequired, true, 'more edits and a zone change keep the latch');
  assert.equal(tenantAvailabilityReviewedVersion(edited), 3);
  const rebased = rebaseTenantAvailabilityDraft(edited);
  assert.equal(rebased.reviewRequired, false);
  assert.equal(rebased.generation, edited.generation + 1);
  assert.equal(tenantAvailabilityReviewedVersion(rebased), 5);
  assert.equal(rebased.form.startTime, '12:00');
});

test('a current successful acknowledgement settles normally and older acknowledgements never regress', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  let draft = createTenantAvailabilityDraft({ request: opening, identityKey: '7:token-a', generation: 1 });
  draft = editTenantAvailabilityDraft(draft, 'end', 'endTime', '14:00');
  const built = buildTenantAvailabilityDraftCommand(draft.form, 3, draft.opening, draft.provenance);
  assert.equal(built.ok, true);
  draft = {
    ...draft,
    attempt: built.command,
    dispatchRevision: draft.editRevision,
    dispatchFieldRevisions: { ...draft.fieldRevisions },
    status: 'saving'
  };
  const v4 = {
    requestId: opening.requestId,
    version: 4,
    startAt: built.command.availabilityStartAt,
    endAt: built.command.availabilityEndAt,
    zoneId: built.command.availabilityZoneId,
    preferredAt: built.command.preferredAt
  };
  const saved = settleTenantAvailabilitySaveAcknowledgement(draft, v4);
  assert.equal(saved.status, 'saved');
  assert.equal(saved.reviewRequired, false);
  assert.equal(saved.reviewedFacts.version, 4);
  assert.equal(saved.currentFacts.version, 4);
  assert.equal(saved.form.endTime, '14:00');
  assert.equal(saved.notice, 'Available times saved.');

  const lateV3 = settleTenantAvailabilitySaveAcknowledgement(saved, {
    ...v4,
    version: 3,
    endAt: '2026-11-21T13:00:00+05:30'
  });
  assert.equal(lateV3.currentFacts.version, 4, 'a delayed older acknowledgement never rolls back authority');
  assert.equal(lateV3.reviewedFacts.version, 4);
  assert.equal(lateV3.form.endTime, '14:00');
  assert.equal(lateV3.reviewRequired, true);
});

test('a zone-only Save settles its zone and exact instants on the current acknowledgement', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata'
  });
  let draft = createTenantAvailabilityDraft({
    request: opening,
    identityKey: '7:token-a',
    generation: 1
  });
  draft = changeTenantAvailabilityDraftZone(draft, 'Asia/Kathmandu');
  const built = buildTenantAvailabilityDraftCommand(draft.form, 3, draft.opening, draft.provenance);
  assert.equal(built.ok, true);
  draft = {
    ...draft,
    attempt: built.command,
    dispatchRevision: draft.editRevision,
    dispatchFieldRevisions: { ...draft.fieldRevisions },
    dispatchZoneRevision: draft.zoneRevision,
    status: 'saving'
  };
  const settled = settleTenantAvailabilitySaveAcknowledgement(draft, {
    requestId: 51,
    version: 4,
    startAt: built.command.availabilityStartAt,
    endAt: built.command.availabilityEndAt,
    zoneId: built.command.availabilityZoneId,
    preferredAt: built.command.preferredAt
  });
  assert.equal(settled.status, 'saved');
  assert.equal(settled.reviewRequired, false);
  assert.equal(settled.currentFacts.version, 4);
  assert.equal(settled.reviewedFacts.version, 4);
  assert.equal(settled.form.zoneId, 'Asia/Kathmandu');
  assert.equal(settled.form.startTime, '10:15');
  assert.equal(settled.form.endTime, '13:15');
  assert.equal(settled.notice, 'Available times saved.');
  assert.equal(settled.dispatchZoneRevision, null);
});

test('the retained-draft projection uses an existing snapshot including explicit nulls', () => {
  const opening = baseRequest({
    availabilityStartAt: '2026-11-21T10:00:00+05:30',
    availabilityEndAt: '2026-11-21T13:00:00+05:30',
    availabilityZoneId: 'Asia/Kolkata',
    preferredAt: '2026-11-21T11:00:00+05:30'
  });
  const draft = createTenantAvailabilityDraft({ request: opening, identityKey: '7:token-a', generation: 1 });
  const cleared = {
    ...draft,
    currentFacts: {
      requestId: opening.requestId,
      version: 4,
      startAt: null,
      endAt: null,
      zoneId: null,
      preferredAt: null
    }
  };
  const projected = tenantRequestForDraft(cleared);
  assert.equal(projected.version, 4);
  assert.equal(projected.availabilityStartAt, null);
  assert.equal(projected.availabilityEndAt, null);
  assert.equal(projected.availabilityZoneId, null);
  assert.equal(projected.preferredAt, null);
  assert.equal(formatTenantAvailability(projected), null, 'no obsolete opening window is resurrected');

  const fallback = tenantRequestForDraft({ ...draft, currentFacts: null });
  assert.equal(fallback.availabilityStartAt, opening.availabilityStartAt);
  assert.equal(fallback.version, opening.version);
});

test('the minimum modal date is tomorrow in the selected visit zone', () => {
  const now = new Date('2026-10-18T18:30:00Z');
  assert.equal(tenantVisitMinimumDate('Asia/Kolkata', now), '2026-10-20');
  assert.equal(tenantVisitMinimumDate('America/New_York', now), '2026-10-19');
  assert.equal(tenantVisitMinimumDate('Mars/Olympus', now), '');
});

test('drafts preserve provenance through uncertainty and never overwrite newer edits or versions', () => {
  const request = baseRequest({
    availabilityStartAt: '2026-11-21T04:30:00.123456Z',
    availabilityEndAt: '2026-11-21T07:30:00.987654Z',
    availabilityZoneId: 'Asia/Kolkata'
  });
  let draft = createTenantAvailabilityDraft({ request, identityKey: 'tenant:token', generation: 1 });
  assert.deepEqual(draft.provenance, { start: 'UNTOUCHED', end: 'UNTOUCHED', preferred: 'EDITED' });
  draft = editTenantAvailabilityDraft(draft, 'start', 'startTime', '00:30');
  assert.equal(draft.provenance.start, 'EDITED');
  assert.equal(draft.provenance.end, 'UNTOUCHED');
  draft = changeTenantAvailabilityDraftZone(draft, 'America/New_York');
  assert.equal(draft.form.startTime, '00:30', 'the edited wall clock keeps its typed intent');
  assert.equal(draft.form.endTime, '02:30', 'the untouched end instant is re-expressed');

  const attempted = buildTenantAvailabilityDraftCommand(draft.form, request.version, draft.opening, draft.provenance);
  assert.equal(attempted.ok, true);
  draft = { ...draft, attempt: attempted.command, dispatchRevision: draft.editRevision,
    dispatchFieldRevisions: { ...draft.fieldRevisions }, status: 'saving' };
  draft = editTenantAvailabilityDraft(draft, 'preferred', 'preferredTime', '12:00');
  const newerFacts = {
    requestId: request.requestId, version: request.version + 1,
    startAt: attempted.command.availabilityStartAt,
    endAt: attempted.command.availabilityEndAt,
    zoneId: attempted.command.availabilityZoneId,
    preferredAt: null
  };
  const matched = reconcileTenantAvailabilityDraft(draft, newerFacts, 'matched');
  assert.equal(matched.reconciliation, 'matched');
  assert.equal(matched.reviewRequired, false);
  assert.match(matched.notice, /Current saved times match/);
  assert.match(matched.notice, /remains unconfirmed/);
  assert.equal(matched.form.startTime, '00:30');
  assert.equal(matched.form.preferredTime, '12:00', 'an edit made while saving survives the read');
  assert.equal(matched.lastAcceptedVersion, request.version + 1);

  const older = { ...newerFacts, version: request.version, startAt: '2026-11-21T04:30:00Z' };
  const unchanged = reconcileTenantAvailabilityDraft(matched, older, 'mismatch');
  assert.equal(unchanged.lastAcceptedVersion, request.version + 1, 'an older read cannot regress accepted state');
  assert.equal(unchanged.currentFacts.version, request.version + 1);

  const mismatch = reconcileTenantAvailabilityDraft(matched, {
    ...newerFacts,
    version: request.version + 2,
    startAt: '2026-11-21T05:00:00Z'
  }, 'matched');
  assert.equal(mismatch.reconciliation, 'mismatch');
  assert.equal(mismatch.reviewRequired, true);
  assert.equal(mismatch.form.startTime, matched.form.startTime, 'the attempted draft is retained separately');

  const rebased = rebaseTenantAvailabilityDraft(mismatch);
  assert.equal(rebased.reviewRequired, false);
  assert.equal(rebased.generation, mismatch.generation + 1, 'an explicit rebase starts a new editor generation');
  assert.equal(rebased.form.startTime, '00:00');
});

test('the mounted editor exposes accessible date, time and timezone controls', () => {
  const editor = readFileSync(new URL('../components/TenantAvailabilityEditor.tsx', import.meta.url), 'utf8');
  assert.match(editor, /Visit timezone[\s\S]{0,200}<select/);
  assert.match(editor, /type="date"/);
  assert.match(editor, /type="time"/);
  assert.match(editor, /min-h-11/);
  assert.match(editor, /role="alert"/);
  assert.match(editor, /aria-live="polite"/);
  assert.match(editor, /grid gap-2 sm:grid-cols-2/);
});
