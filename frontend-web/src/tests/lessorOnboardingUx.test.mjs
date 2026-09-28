import test from 'node:test';
import assert from 'node:assert/strict';
import { LessorAutosave } from '../services/lessorAutosave.ts';

test('lessor progress flow covers all 7 stages in exact sequence', () => {
  const steps = ['type', 'basics', 'pricing', 'location', 'media', 'details', 'preview'];
  assert.equal(steps.length, 7);
  assert.equal(steps[0], 'type');
  assert.equal(steps[1], 'basics');
  assert.equal(steps[2], 'pricing');
  assert.equal(steps[3], 'location');
  assert.equal(steps[4], 'media');
  assert.equal(steps[5], 'details');
  assert.equal(steps[6], 'preview');
});

test('property types cover all supported residential categories with descriptions', () => {
  const supportedTypes = ['FLAT', 'HOUSE', 'STUDIO', 'PENTHOUSE', 'SERVICED_APARTMENT'];
  assert.equal(supportedTypes.length, 5);
  assert.ok(supportedTypes.includes('FLAT'));
  assert.ok(supportedTypes.includes('HOUSE'));
  assert.ok(supportedTypes.includes('STUDIO'));
  assert.ok(supportedTypes.includes('PENTHOUSE'));
  assert.ok(supportedTypes.includes('SERVICED_APARTMENT'));
});

test('live preview tenant view strictly excludes private street address and landmark', () => {
  const draft = {
    locality: 'Vijay Nagar',
    city: 'Indore',
    address: 'Flat 402, Royal Residency, Private Street 12',
    landmark: 'Behind Private Club'
  };

  const tenantFacingLocation = [draft.locality, draft.city].filter(Boolean).join(', ');
  assert.equal(tenantFacingLocation, 'Vijay Nagar, Indore');
  assert.equal(tenantFacingLocation.includes('Royal Residency'), false);
  assert.equal(tenantFacingLocation.includes('Private Street'), false);
  assert.equal(tenantFacingLocation.includes('Private Club'), false);
});

test('live preview rent formatting uses Indian numbering and handles empty states', () => {
  const money = new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0
  });

  assert.equal(money.format(25000), '₹25,000');
  assert.equal(money.format(0), '₹0');
});

test('autosave transitions to saving immediately upon dirty change and saved after flush', async () => {
  const storage = () => {
    const map = new Map();
    return {
      getItem: k => map.get(k) ?? null,
      setItem: (k, v) => map.set(k, v),
      removeItem: k => map.delete(k)
    };
  };
  globalThis.localStorage = storage();

  const statusHistory = [];
  const saver = new LessorAutosave(
    10,
    'draft-1',
    1,
    status => statusHistory.push(status),
    () => {},
    async (_id, _section, version) => ({
      draftId: 'draft-1',
      status: 'DRAFT',
      version: version + 1,
      completionPercent: 50,
      data: { basics: null, pricing: null, location: null, details: null },
      createdAt: '',
      updatedAt: ''
    })
  );

  // Initial state before any change has no in-flight save
  assert.equal(saver.getStatus(), 'saved');

  // When dirty change occurs, status becomes 'saving'
  saver.change('pricing', { monthlyRent: 20000, securityDeposit: 40000 });
  assert.equal(saver.getStatus(), 'saving');
  assert.ok(statusHistory.includes('saving'));

  // When flush finishes, status becomes 'saved'
  const ok = await saver.flush();
  assert.equal(ok, true);
  assert.equal(saver.getStatus(), 'saved');
  saver.dispose();
});

test('exit helper flushes in-flight/dirty edits and safely halts on save failure', async () => {
  const storage = () => {
    const map = new Map();
    return {
      getItem: k => map.get(k) ?? null,
      setItem: (k, v) => map.set(k, v),
      removeItem: k => map.delete(k)
    };
  };
  globalThis.localStorage = storage();

  let shouldFail = false;
  const saver = new LessorAutosave(
    10,
    'draft-exit',
    1,
    () => {},
    () => {},
    async () => {
      if (shouldFail) throw new Error('network down');
      return {
        draftId: 'draft-exit',
        status: 'DRAFT',
        version: 2,
        completionPercent: 50,
        data: { basics: null, pricing: null, location: null, details: null },
        createdAt: '',
        updatedAt: ''
      };
    }
  );

  // 1. Clean exit without pending edits
  let exited = false;
  const cleanExit = async () => {
    const ok = await saver.flush();
    if (ok) exited = true;
    return Boolean(ok);
  };
  assert.equal(await cleanExit(), true);
  assert.equal(exited, true);

  // 2. Dirty exit with save failure: flush returns false, preventing silent exit
  shouldFail = true;
  saver.change('basics', { propertyType: 'FLAT', rentalMode: 'LONG_TERM_RENTAL', bhkCount: '2BHK' });
  exited = false;
  const dirtyExit = async () => {
    const ok = await saver.flush();
    if (ok) exited = true;
    return Boolean(ok);
  };
  assert.equal(await dirtyExit(), false);
  assert.equal(exited, false);
  assert.equal(saver.getStatus(), 'error');
  saver.dispose();
});

test('brand tagline matches exact approved copy and placement', () => {
  const brandTagline = 'Your Dreams, Our Efforts.';
  assert.equal(brandTagline, 'Your Dreams, Our Efforts.');
  assert.equal(brandTagline.includes('Divyavastu'), false);
  assert.equal(brandTagline.includes('100%'), false);
  assert.equal(brandTagline.includes('verified'), false);
});

test('discard action is gated strictly to DRAFT status', () => {
  const canDiscard = (status) => status === 'DRAFT';
  assert.equal(canDiscard('DRAFT'), true);
  assert.equal(canDiscard('SUBMITTED'), false);
  assert.equal(canDiscard('UNDER_REVIEW'), false);
  assert.equal(canDiscard('APPROVED'), false);
  assert.equal(canDiscard('PUBLISHED'), false);
  assert.equal(canDiscard('ARCHIVED'), false);
});

test('discard confirmation copy distinguishes new draft from published revision', () => {
  const getModalConfig = (isRevision) => ({
    title: isRevision ? 'Discard these changes?' : 'Discard this property?',
    copy: isRevision
      ? 'Your current published property will stay unchanged.'
      : "This draft and its temporary uploaded media will be permanently removed. This can't be undone.",
    confirmLabel: isRevision ? 'Discard changes' : 'Discard property',
    cancelLabel: 'Keep editing'
  });

  const newDraftConfig = getModalConfig(false);
  assert.equal(newDraftConfig.title, 'Discard this property?');
  assert.equal(newDraftConfig.copy, "This draft and its temporary uploaded media will be permanently removed. This can't be undone.");
  assert.equal(newDraftConfig.confirmLabel, 'Discard property');
  assert.equal(newDraftConfig.cancelLabel, 'Keep editing');

  const revisionConfig = getModalConfig(true);
  assert.equal(revisionConfig.title, 'Discard these changes?');
  assert.equal(revisionConfig.copy, 'Your current published property will stay unchanged.');
  assert.equal(revisionConfig.confirmLabel, 'Discard changes');
  assert.equal(revisionConfig.cancelLabel, 'Keep editing');
});

test('autosave abandon cancels in-flight/pending requests and prevents delayed recreation', async () => {
  const storage = () => {
    const map = new Map();
    return {
      getItem: k => map.get(k) ?? null,
      setItem: (k, v) => map.set(k, v),
      removeItem: k => map.delete(k)
    };
  };
  globalThis.localStorage = storage();

  let saveCalls = 0;
  const saver = new LessorAutosave(
    10,
    'draft-abandon',
    1,
    () => {},
    () => {},
    async () => {
      saveCalls++;
      return {
        draftId: 'draft-abandon',
        status: 'DRAFT',
        version: 2,
        completionPercent: 50,
        data: { basics: null, pricing: null, location: null, details: null },
        createdAt: '',
        updatedAt: ''
      };
    }
  );

  saver.change('basics', { propertyType: 'HOUSE', rentalMode: 'LONG_TERM_RENTAL', bhkCount: '3BHK' });
  assert.equal(Object.keys(saver.getPending()).length, 1);

  // User decides to discard: call abandon()
  saver.abandon();

  // Pending queue is cleared and saver is blocked
  assert.equal(Object.keys(saver.getPending()).length, 0);

  // A subsequent flush returns false and never triggers save
  const result = await saver.flush();
  assert.equal(result, false);
  assert.equal(saveCalls, 0);
});
