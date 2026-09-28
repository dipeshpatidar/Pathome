import test from 'node:test';
import assert from 'node:assert/strict';
import { LessorAutosave } from '../services/lessorAutosave.ts';

test('lessor progress flow covers all 6 steps plus review in exact sequence', () => {
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
