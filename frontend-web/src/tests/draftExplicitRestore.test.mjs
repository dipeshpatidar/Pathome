import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

// In-memory mock localStorage
class MockLocalStorage {
  constructor() {
    this.store = new Map();
  }
  getItem(key) {
    return this.store.get(key) || null;
  }
  setItem(key, value) {
    this.store.set(key, String(value));
  }
  removeItem(key) {
    this.store.delete(key);
  }
  clear() {
    this.store.clear();
  }
}

describe('Draft Explicit Restore & Independence State Machine', () => {
  let localStorage;
  let mockServerDrafts;

  beforeEach(() => {
    localStorage = new MockLocalStorage();
    mockServerDrafts = new Map();
  });

  const generateDraftId = (type) =>
    `draft-${type.toLowerCase()}-${Date.now().toString(36)}-${Math.random().toString(36).substring(2, 7)}`;

  // Simulates usePropertyDraft initialization & restore workflow
  class PropertyDraftManager {
    constructor({ draftType, onRestoreDraft, onClearDraftState, initialDraftId = null, adminEmail = 'admin@pathome.in' }) {
      this.draftType = draftType;
      this.onRestoreDraft = onRestoreDraft;
      this.onClearDraftState = onClearDraftState;
      this.initialDraftId = initialDraftId;
      this.adminEmail = adminEmail;

      this.currentDraftId = null;
      this.draftVersion = 1;
      this.autosaveStatus = 'idle';
      this.lastSavedAt = null;
      this.draftsList = [];
    }

    getActiveStorageKey() {
      return `pathome_active_draft_${this.draftType.toLowerCase()}_${this.adminEmail}`;
    }

    async refreshDraftsList() {
      this.draftsList = Array.from(mockServerDrafts.values()).map(d => ({
        draftId: d.draftId,
        draftType: d.draftType,
        titleSummary: d.titleSummary,
        version: d.version,
        updatedAt: d.updatedAt
      }));
    }

    // Approved mount initialization: loads drafts list without auto-injecting into editor
    async initialize() {
      await this.refreshDraftsList();

      // Clear any active storage pointer so fresh input begins on a new draft identity
      localStorage.removeItem(this.getActiveStorageKey());

      this.currentDraftId = null;
      this.draftVersion = 1;
      this.autosaveStatus = 'idle';
      this.lastSavedAt = null;

      // Restore ONLY if an explicit draft identity was provided (e.g. batch continue from parent)
      if (this.initialDraftId) {
        await this.loadDraft(this.initialDraftId);
      }
    }

    ensureDraftId() {
      if (this.currentDraftId) return this.currentDraftId;
      const newId = generateDraftId(this.draftType);
      this.currentDraftId = newId;
      localStorage.setItem(this.getActiveStorageKey(), newId);
      return newId;
    }

    async scheduleAutosave(payload, titleSummary, itemCount = 1) {
      if (!this.currentDraftId) {
        this.currentDraftId = generateDraftId(this.draftType);
        localStorage.setItem(this.getActiveStorageKey(), this.currentDraftId);
      }

      const existing = mockServerDrafts.get(this.currentDraftId);
      const nextVersion = existing ? existing.version + 1 : 1;

      mockServerDrafts.set(this.currentDraftId, {
        draftId: this.currentDraftId,
        draftType: this.draftType,
        titleSummary,
        itemCount,
        version: nextVersion,
        payload: JSON.stringify(payload),
        updatedAt: new Date().toISOString()
      });

      this.draftVersion = nextVersion;
      this.lastSavedAt = new Date();
      this.autosaveStatus = 'saved';
      await this.refreshDraftsList();
    }

    async loadDraft(targetDraftId) {
      this.onClearDraftState();
      this.currentDraftId = targetDraftId;
      localStorage.setItem(this.getActiveStorageKey(), targetDraftId);

      const draft = mockServerDrafts.get(targetDraftId);
      if (draft) {
        this.draftVersion = draft.version;
        this.lastSavedAt = new Date(draft.updatedAt);
        this.autosaveStatus = 'saved';
        await this.onRestoreDraft(JSON.parse(draft.payload), []);
      }
      await this.refreshDraftsList();
    }

    async startNewDraft() {
      this.currentDraftId = null;
      this.draftVersion = 1;
      this.autosaveStatus = 'idle';
      this.lastSavedAt = null;
      localStorage.removeItem(this.getActiveStorageKey());
      this.onClearDraftState();
      await this.refreshDraftsList();
    }

    async discardDraft(draftId) {
      mockServerDrafts.delete(draftId);
      if (this.currentDraftId === draftId) {
        this.currentDraftId = null;
        this.draftVersion = 1;
        this.autosaveStatus = 'idle';
        this.lastSavedAt = null;
        localStorage.removeItem(this.getActiveStorageKey());
        this.onClearDraftState();
      }
      await this.refreshDraftsList();
    }
  }

  test('A. No existing drafts: Property Upload opens empty', async () => {
    let editorPrompt = '';
    const manager = new PropertyDraftManager({
      draftType: 'SINGLE',
      onRestoreDraft: (p) => { editorPrompt = p.newBhkLabel; },
      onClearDraftState: () => { editorPrompt = ''; }
    });

    await manager.initialize();
    assert.equal(editorPrompt, '', 'Editor prompt must remain empty');
    assert.equal(manager.currentDraftId, null, 'No draft ID allocated before user input');
    assert.equal(manager.draftsList.length, 0, 'Drafts list is empty');
  });

  test('B. One saved draft exists: Property Upload still opens empty on initialization/refresh', async () => {
    mockServerDrafts.set('draft-single-1', {
      draftId: 'draft-single-1',
      draftType: 'SINGLE',
      titleSummary: '2 BHK in Vijay Nagar',
      version: 1,
      payload: JSON.stringify({ newBhkLabel: '2 BHK in Vijay Nagar Indore' }),
      updatedAt: new Date().toISOString()
    });
    localStorage.setItem('pathome_active_draft_single_admin@pathome.in', 'draft-single-1');

    let editorPrompt = '';
    const manager = new PropertyDraftManager({
      draftType: 'SINGLE',
      onRestoreDraft: (p) => { editorPrompt = p.newBhkLabel; },
      onClearDraftState: () => { editorPrompt = ''; }
    });

    await manager.initialize();
    assert.equal(editorPrompt, '', 'Editor prompt must NOT be populated automatically');
    assert.equal(manager.currentDraftId, null, 'Current draft ID must be null on start');
    assert.equal(manager.draftsList.length, 1, 'Draft remains safely available in list');
    assert.equal(manager.draftsList[0].draftId, 'draft-single-1');
  });

  test('C. Multiple saved drafts exist: Property Upload opens empty and all remain available', async () => {
    mockServerDrafts.set('draft-A', { draftId: 'draft-A', draftType: 'SINGLE', titleSummary: 'Draft A', version: 1, payload: '{}', updatedAt: new Date().toISOString() });
    mockServerDrafts.set('draft-B', { draftId: 'draft-B', draftType: 'SINGLE', titleSummary: 'Draft B', version: 1, payload: '{}', updatedAt: new Date().toISOString() });
    mockServerDrafts.set('draft-C', { draftId: 'draft-C', draftType: 'BATCH', titleSummary: 'Draft C', version: 1, payload: '{}', updatedAt: new Date().toISOString() });

    let editorPrompt = '';
    const manager = new PropertyDraftManager({
      draftType: 'SINGLE',
      onRestoreDraft: (p) => { editorPrompt = p.newBhkLabel; },
      onClearDraftState: () => { editorPrompt = ''; }
    });

    await manager.initialize();
    assert.equal(editorPrompt, '');
    assert.equal(manager.currentDraftId, null);
    assert.equal(manager.draftsList.length, 3);
  });

  test('D. Explicit Continue Draft B: restores Draft B into editor', async () => {
    mockServerDrafts.set('draft-A', { draftId: 'draft-A', draftType: 'SINGLE', titleSummary: 'Draft A', version: 1, payload: JSON.stringify({ newBhkLabel: 'Prompt A' }), updatedAt: new Date().toISOString() });
    mockServerDrafts.set('draft-B', { draftId: 'draft-B', draftType: 'SINGLE', titleSummary: 'Draft B', version: 2, payload: JSON.stringify({ newBhkLabel: 'Prompt B for 3 BHK' }), updatedAt: new Date().toISOString() });

    let editorPrompt = '';
    const manager = new PropertyDraftManager({
      draftType: 'SINGLE',
      onRestoreDraft: (p) => { editorPrompt = p.newBhkLabel; },
      onClearDraftState: () => { editorPrompt = ''; }
    });

    await manager.initialize();
    assert.equal(editorPrompt, '');

    // User explicitly selects Draft B -> Continue
    await manager.loadDraft('draft-B');
    assert.equal(editorPrompt, 'Prompt B for 3 BHK', 'Draft B text restored into editor');
    assert.equal(manager.currentDraftId, 'draft-B', 'Current draft pointer set to Draft B');
  });

  test('E. Continue B: Draft A and Draft C remain completely unchanged', async () => {
    mockServerDrafts.set('draft-A', { draftId: 'draft-A', draftType: 'SINGLE', titleSummary: 'Draft A', version: 1, payload: JSON.stringify({ newBhkLabel: 'Prompt A' }), updatedAt: new Date().toISOString() });
    mockServerDrafts.set('draft-B', { draftId: 'draft-B', draftType: 'SINGLE', titleSummary: 'Draft B', version: 1, payload: JSON.stringify({ newBhkLabel: 'Prompt B' }), updatedAt: new Date().toISOString() });

    let editorPrompt = '';
    const manager = new PropertyDraftManager({
      draftType: 'SINGLE',
      onRestoreDraft: (p) => { editorPrompt = p.newBhkLabel; },
      onClearDraftState: () => { editorPrompt = ''; }
    });

    await manager.initialize();
    await manager.loadDraft('draft-B');

    // Admin edits Draft B
    editorPrompt = 'Prompt B modified';
    await manager.scheduleAutosave({ newBhkLabel: editorPrompt }, 'Draft B modified');

    assert.equal(mockServerDrafts.get('draft-A').payload, JSON.stringify({ newBhkLabel: 'Prompt A' }), 'Draft A must remain untouched');
    assert.equal(mockServerDrafts.get('draft-B').version, 2, 'Draft B version incremented');
  });

  test('F. Refresh after editing B: editor opens empty, B remains safely stored', async () => {
    mockServerDrafts.set('draft-B', { draftId: 'draft-B', draftType: 'SINGLE', titleSummary: 'Draft B', version: 3, payload: JSON.stringify({ newBhkLabel: 'Saved edits in B' }), updatedAt: new Date().toISOString() });

    // Simulate page refresh (new manager instance mounting)
    let editorPrompt = '';
    const refreshedManager = new PropertyDraftManager({
      draftType: 'SINGLE',
      onRestoreDraft: (p) => { editorPrompt = p.newBhkLabel; },
      onClearDraftState: () => { editorPrompt = ''; }
    });

    await refreshedManager.initialize();
    assert.equal(editorPrompt, '', 'Editor opens EMPTY after refresh');
    assert.equal(refreshedManager.currentDraftId, null);
    assert.equal(mockServerDrafts.get('draft-B').payload, JSON.stringify({ newBhkLabel: 'Saved edits in B' }), 'Draft B persists safely in drafts');
  });

  test('G. After refresh, typing a new property creates new Draft C; B is NOT overwritten', async () => {
    mockServerDrafts.set('draft-B', { draftId: 'draft-B', draftType: 'SINGLE', titleSummary: 'Draft B', version: 1, payload: JSON.stringify({ newBhkLabel: 'Prompt B' }), updatedAt: new Date().toISOString() });

    let editorPrompt = '';
    const manager = new PropertyDraftManager({
      draftType: 'SINGLE',
      onRestoreDraft: (p) => { editorPrompt = p.newBhkLabel; },
      onClearDraftState: () => { editorPrompt = ''; }
    });

    await manager.initialize();
    assert.equal(manager.currentDraftId, null);

    // Admin types a brand new property
    editorPrompt = 'Brand new 1 BHK flat in Saket';
    await manager.scheduleAutosave({ newBhkLabel: editorPrompt }, '1 BHK in Saket');

    const newDraftId = manager.currentDraftId;
    assert.ok(newDraftId, 'New draft ID allocated');
    assert.notEqual(newDraftId, 'draft-B', 'New draft ID must NOT be draft-B');
    assert.equal(mockServerDrafts.get('draft-B').payload, JSON.stringify({ newBhkLabel: 'Prompt B' }), 'Draft B is NOT overwritten');
    assert.equal(mockServerDrafts.size, 2, 'Both Draft B and Draft C exist');
  });

  test('H & I. Saved batch draft exists: page stays in single; explicit Continue batch draft restores batch', async () => {
    mockServerDrafts.set('draft-batch-1', {
      draftId: 'draft-batch-1',
      draftType: 'BATCH',
      titleSummary: 'Batch 3 Properties',
      version: 1,
      payload: JSON.stringify({ rawPrompts: 'Property 1... Property 2...', stagedCards: [{ id: 'card-1' }, { id: 'card-2' }] }),
      updatedAt: new Date().toISOString()
    });

    let uploadMode = 'single';
    let singleEditor = '';
    const singleManager = new PropertyDraftManager({
      draftType: 'SINGLE',
      onRestoreDraft: (p) => { singleEditor = p.newBhkLabel; },
      onClearDraftState: () => { singleEditor = ''; }
    });

    await singleManager.initialize();
    // H: Page does NOT automatically enter Batch Studio
    assert.equal(uploadMode, 'single', 'Page stays in single mode');
    assert.equal(singleEditor, '', 'Editor is empty');

    // I: Admin clicks Continue on batch draft
    const chosenDraft = singleManager.draftsList.find(d => d.draftId === 'draft-batch-1');
    assert.equal(chosenDraft.draftType, 'BATCH');

    if (chosenDraft.draftType === 'BATCH') {
      uploadMode = 'multiple';
    }
    assert.equal(uploadMode, 'multiple', 'Mode switches to multiple on explicit batch continue');

    // Batch studio mounts with initialDraftId
    let restoredBatchCards = [];
    const batchManager = new PropertyDraftManager({
      draftType: 'BATCH',
      initialDraftId: 'draft-batch-1',
      onRestoreDraft: (p) => { restoredBatchCards = p.stagedCards; },
      onClearDraftState: () => { restoredBatchCards = []; }
    });

    await batchManager.initialize();
    assert.equal(batchManager.currentDraftId, 'draft-batch-1');
    assert.equal(restoredBatchCards.length, 2, 'Batch cards restored into Batch studio');
  });

  test('L. Discard isolation: discarding Draft B leaves Draft A and Draft C untouched', async () => {
    mockServerDrafts.set('draft-A', { draftId: 'draft-A', draftType: 'SINGLE', titleSummary: 'Draft A', version: 1, payload: '{}', updatedAt: new Date().toISOString() });
    mockServerDrafts.set('draft-B', { draftId: 'draft-B', draftType: 'SINGLE', titleSummary: 'Draft B', version: 1, payload: '{}', updatedAt: new Date().toISOString() });
    mockServerDrafts.set('draft-C', { draftId: 'draft-C', draftType: 'SINGLE', titleSummary: 'Draft C', version: 1, payload: '{}', updatedAt: new Date().toISOString() });

    const manager = new PropertyDraftManager({
      draftType: 'SINGLE',
      onRestoreDraft: () => {},
      onClearDraftState: () => {}
    });

    await manager.initialize();
    assert.equal(manager.draftsList.length, 3);

    // Explicitly discard Draft B
    await manager.discardDraft('draft-B');
    assert.equal(mockServerDrafts.has('draft-B'), false, 'Draft B deleted');
    assert.equal(mockServerDrafts.has('draft-A'), true, 'Draft A untouched');
    assert.equal(mockServerDrafts.has('draft-C'), true, 'Draft C untouched');
    assert.equal(manager.draftsList.length, 2);
  });

  test('M. Admin section refresh persistence: preserves valid admin sections across reload', () => {
    const VALID_ADMIN_TABS = new Set([
      'overview',
      'funnel',
      'crm',
      'employees',
      'payroll',
      'approval',
      'learning',
      'config',
      'media',
      'failed-uploads'
    ]);

    const getInitialAdminTab = (storage) => {
      try {
        const saved = storage.getItem('pathome_active_admin_tab');
        if (saved && VALID_ADMIN_TABS.has(saved)) {
          return saved;
        }
      } catch (_) {}
      return 'overview';
    };

    // 1. Fresh state: defaults to overview
    assert.equal(getInitialAdminTab(localStorage), 'overview');

    // 2. Admin on Property Upload (media) -> reload preserves media
    localStorage.setItem('pathome_active_admin_tab', 'media');
    assert.equal(getInitialAdminTab(localStorage), 'media');

    // 3. Admin on Failed Uploads -> reload preserves failed-uploads
    localStorage.setItem('pathome_active_admin_tab', 'failed-uploads');
    assert.equal(getInitialAdminTab(localStorage), 'failed-uploads');

    // 4. Invalid or obsolete section -> falls back safely to overview
    localStorage.setItem('pathome_active_admin_tab', 'malicious_tab');
    assert.equal(getInitialAdminTab(localStorage), 'overview');

    // 5. Logout removes persisted tab
    localStorage.setItem('pathome_active_admin_tab', 'crm');
    localStorage.removeItem('pathome_active_admin_tab');
    assert.equal(getInitialAdminTab(localStorage), 'overview');
  });

  test('N. Property Upload refresh does NOT auto-inject draft; editor starts empty', async () => {
    localStorage.setItem('pathome_active_admin_tab', 'media');
    mockServerDrafts.set('draft-saved-1', {
      draftId: 'draft-saved-1',
      draftType: 'SINGLE',
      titleSummary: '2 BHK Luxury Apartment',
      version: 2,
      payload: JSON.stringify({ newBhkLabel: '2 BHK Luxury in Vijay Nagar' }),
      updatedAt: new Date().toISOString()
    });

    let editorContent = '';
    const manager = new PropertyDraftManager({
      draftType: 'SINGLE',
      onRestoreDraft: (p) => { editorContent = p.newBhkLabel; },
      onClearDraftState: () => { editorContent = ''; }
    });

    await manager.initialize();

    // The editor must remain completely empty on mount/refresh
    assert.equal(editorContent, '', 'Prompt editor starts strictly empty');
    // But drafts list is available for explicit selection
    assert.equal(manager.draftsList.length, 1);
    assert.equal(manager.draftsList[0].draftId, 'draft-saved-1');
  });

  test('O. Parallel bounded media restoration: enforces max concurrency 3, preserves order, room tags, cover, and handles partial failure', async () => {
    const CONCURRENCY_LIMIT = 3;
    let activeWorkers = 0;
    let maxObservedConcurrency = 0;
    const progressUpdates = [];

    const mockMediaItems = [
      { draftId: 'd1', mediaId: 'm1', originalFilename: 'living_room.jpg', roomTag: 'Living Room', isCover: false, delayMs: 30 },
      { draftId: 'd1', mediaId: 'm2', originalFilename: 'bedroom.jpg', roomTag: 'Master Bedroom', isCover: true, delayMs: 10 }, // finishes early
      { draftId: 'd1', mediaId: 'm3', originalFilename: 'corrupt.jpg', roomTag: 'Kitchen', isCover: false, shouldFail: true, delayMs: 20 },
      { draftId: 'd1', mediaId: 'm4', originalFilename: 'balcony.jpg', roomTag: 'Balcony', isCover: false, delayMs: 15 },
      { draftId: 'd1', mediaId: 'm5', originalFilename: 'bathroom.jpg', roomTag: 'Washroom', isCover: false, delayMs: 25 }
    ];

    const total = mockMediaItems.length;
    let completedCount = 0;
    const results = new Array(total).fill(null);

    let nextIndex = 0;
    const worker = async () => {
      while (nextIndex < total) {
        const i = nextIndex;
        nextIndex += 1;
        const item = mockMediaItems[i];

        activeWorkers += 1;
        if (activeWorkers > maxObservedConcurrency) {
          maxObservedConcurrency = activeWorkers;
        }

        try {
          await new Promise(r => setTimeout(r, item.delayMs));
          if (!item.shouldFail) {
            results[i] = {
              file: { name: item.originalFilename },
              roomTag: item.roomTag,
              isCover: item.isCover
            };
          }
        } finally {
          activeWorkers -= 1;
          completedCount += 1;
          progressUpdates.push({ loaded: completedCount, total });
        }
      }
    };

    const workerCount = Math.min(CONCURRENCY_LIMIT, total);
    const workers = [];
    for (let w = 0; w < workerCount; w += 1) {
      workers.push(worker());
    }
    await Promise.all(workers);

    // Filter results into reconstructed list
    const files = [];
    const tags = {};
    let coverIndex = 0;

    for (let i = 0; i < total; i += 1) {
      const res = results[i];
      if (res) {
        files.push(res.file);
        const finalIndex = files.length - 1;
        if (res.roomTag) tags[finalIndex] = res.roomTag;
        if (res.isCover) coverIndex = finalIndex;
      }
    }

    // 1. Concurrency is strictly bounded <= 3
    assert.ok(maxObservedConcurrency <= 3, `Max observed concurrency was ${maxObservedConcurrency}, must be <= 3`);
    assert.equal(maxObservedConcurrency, 3, 'Utilized all 3 concurrent workers');

    // 2. Partial failure did not abort the restoration
    assert.equal(files.length, 4, '4 successful files restored despite m3 failing');

    // 3. Original order is preserved regardless of completion order
    assert.equal(files[0].name, 'living_room.jpg');
    assert.equal(files[1].name, 'bedroom.jpg');
    assert.equal(files[2].name, 'balcony.jpg');
    assert.equal(files[3].name, 'bathroom.jpg');

    // 4. Room tags mapped to final indices
    assert.equal(tags[0], 'Living Room');
    assert.equal(tags[1], 'Master Bedroom');
    assert.equal(tags[2], 'Balcony');
    assert.equal(tags[3], 'Washroom');

    // 5. Cover index preserved
    assert.equal(coverIndex, 1, 'bedroom.jpg remains cover photo at index 1');

    // 6. Progress updates tracked each item
    assert.equal(progressUpdates.length, 5);
    assert.deepEqual(progressUpdates[progressUpdates.length - 1], { loaded: 5, total: 5 });
  });
});
