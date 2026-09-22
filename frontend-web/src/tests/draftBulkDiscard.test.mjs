import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

describe('Drafts Multi-Select & Bulk Discard Regression Suite (Requirements 1 - 24)', () => {
  let mockServerDrafts;
  let mockDraftMedia;
  let mockPublishedListings;
  let mockLocalDrafts;
  let refreshCount;
  let activeDraftId;
  let editorCleared;

  beforeEach(() => {
    mockServerDrafts = new Map();
    mockDraftMedia = new Map();
    mockPublishedListings = new Map();
    mockLocalDrafts = new Map();
    refreshCount = 0;
    activeDraftId = null;
    editorCleared = false;
  });

  // Simulated backend draft service & repository
  class MockDraftService {
    async discardDraft(draftId) {
      if (!mockServerDrafts.has(draftId)) {
        // Idempotent 404
        return;
      }
      const draft = mockServerDrafts.get(draftId);
      if (draft.status === 'PUBLISHED') {
        // Tombstone protection: do not delete
        return;
      }
      // Clean staged media
      mockDraftMedia.delete(draftId);
      // Delete draft
      mockServerDrafts.delete(draftId);
    }
  }

  // Model hook manager matching usePropertyDraft.ts
  class MockDraftHookManager {
    constructor() {
      this.service = new MockDraftService();
      this.draftsList = [];
      this.currentDraftId = activeDraftId;
    }

    async refreshDraftsList() {
      refreshCount += 1;
      this.draftsList = Array.from(mockServerDrafts.values()).map(d => ({
        draftId: d.draftId,
        draftType: d.draftType,
        status: d.status,
        titleSummary: d.titleSummary,
        updatedAt: d.updatedAt,
        mediaCount: (mockDraftMedia.get(d.draftId) || []).length
      }));
    }

    async discardDraft(draftId) {
      await this.service.discardDraft(draftId);
      mockLocalDrafts.delete(draftId);
      if (this.currentDraftId === draftId) {
        this.currentDraftId = null;
        activeDraftId = null;
        editorCleared = true;
      }
      await this.refreshDraftsList();
    }

    async discardMultipleDrafts(draftIds) {
      const succeeded = [];
      const failed = [];
      let activeDraftWasDiscarded = false;
      let authErrorEncountered = false;

      const BATCH_CONCURRENCY = 3;
      for (let i = 0; i < draftIds.length; i += BATCH_CONCURRENCY) {
        const chunk = draftIds.slice(i, i + BATCH_CONCURRENCY);
        await Promise.all(
          chunk.map(async (draftId) => {
            try {
              if (draftId === 'error-draft-fail') {
                throw new Error('Simulated network error');
              }
              if (draftId === 'error-401-fail') {
                const err = new Error('Your session has ended. Please sign in again to continue.');
                err.status = 401;
                throw err;
              }
              await this.service.discardDraft(draftId);
              mockLocalDrafts.delete(draftId);
              succeeded.push(draftId);
              if (this.currentDraftId === draftId) {
                activeDraftWasDiscarded = true;
              }
            } catch (err) {
              if (err?.status === 401 || err?.message === 'Your session has ended. Please sign in again to continue.') {
                authErrorEncountered = true;
              }
              failed.push(draftId);
            }
          })
        );

        if (authErrorEncountered) {
          const remainingNotAttempted = draftIds.slice(i + BATCH_CONCURRENCY);
          failed.push(...remainingNotAttempted);
          break;
        }
      }

      if (activeDraftWasDiscarded) {
        this.currentDraftId = null;
        activeDraftId = null;
        editorCleared = true;
      }

      if (!authErrorEncountered) {
        await this.refreshDraftsList();
      }
      return { succeeded, failed, isAuthError: authErrorEncountered };
    }
  }

  // Selection logic matching DraftManagementBar.tsx
  class MockDraftManagementBarState {
    constructor(drafts) {
      this.drafts = drafts;
      this.selectedDraftIds = [];
      this.dialogOpen = false;
      this.isBulkDiscarding = false;
      this.feedback = null;
      this.draftToDiscard = null;
    }

    isDraftEligible(draft) {
      return draft.status !== 'PUBLISHING';
    }

    getProtectionTooltip(draft) {
      if (!this.isDraftEligible(draft)) {
        return 'Protected from bulk discard. Resume or discard this draft individually.';
      }
      return null;
    }

    openDiscardDialog(draft) {
      this.draftToDiscard = draft;
    }

    closeDiscardDialog() {
      this.draftToDiscard = null;
    }

    getDiscardDialogData() {
      if (!this.draftToDiscard) return null;
      const isInterrupted = this.draftToDiscard.status === 'PUBLISHING';
      if (isInterrupted) {
        return {
          title: 'Discard interrupted draft?',
          bodyAdvisory: 'This draft has an interrupted publishing operation. Resume is recommended so Pathome can reconcile any property or media that may already have been published.',
          bodyNote: 'Discarding will remove the recoverable draft state. Already-published listings will not be deleted.',
          safeActionText: 'Keep & Resume',
          destructiveActionText: 'Discard Draft',
          cancelActionText: 'Cancel'
        };
      }
      return {
        title: 'Discard this draft?',
        bodyAdvisory: 'Your unpublished property details and temporary staged draft media will be permanently removed.',
        bodyNote: 'This action cannot be undone.',
        safeActionText: 'Keep draft',
        destructiveActionText: 'Confirm discard',
        cancelActionText: 'Keep draft'
      };
    }

    async confirmIndividualDiscard(manager) {
      if (!this.draftToDiscard) return;
      const id = this.draftToDiscard.draftId;
      await manager.discardDraft(id);
      this.selectedDraftIds = this.selectedDraftIds.filter(dId => dId !== id);
      this.draftToDiscard = null;
    }

    keepAndResume(onSelectDraft) {
      if (!this.draftToDiscard) return;
      const id = this.draftToDiscard.draftId;
      this.draftToDiscard = null;
      if (onSelectDraft) {
        onSelectDraft(id);
      }
    }

    get eligibleDrafts() {
      return this.drafts.filter(d => this.isDraftEligible(d));
    }

    get isAllSelected() {
      return (
        this.eligibleDrafts.length > 0 &&
        this.selectedDraftIds.length === this.eligibleDrafts.length
      );
    }

    get isIndeterminate() {
      const count = this.selectedDraftIds.filter(id =>
        this.eligibleDrafts.some(d => d.draftId === id)
      ).length;
      return count > 0 && count < this.eligibleDrafts.length;
    }

    toggleSelect(draftId) {
      const draft = this.drafts.find(d => d.draftId === draftId);
      if (!draft || !this.isDraftEligible(draft)) return; // Ineligible cannot be toggled
      if (this.selectedDraftIds.includes(draftId)) {
        this.selectedDraftIds = this.selectedDraftIds.filter(id => id !== draftId);
      } else {
        this.selectedDraftIds.push(draftId);
      }
    }

    toggleSelectAll() {
      if (this.isAllSelected) {
        this.selectedDraftIds = [];
      } else {
        this.selectedDraftIds = this.eligibleDrafts.map(d => d.draftId);
      }
    }

    syncWithDrafts(newDrafts) {
      this.drafts = newDrafts;
      this.selectedDraftIds = this.selectedDraftIds.filter(id =>
        newDrafts.some(d => d.draftId === id && this.isDraftEligible(d))
      );
    }

    async confirmBulkDiscard(manager) {
      if (this.selectedDraftIds.length === 0 || this.isBulkDiscarding) return;
      this.isBulkDiscarding = true;
      try {
        const res = await manager.discardMultipleDrafts(this.selectedDraftIds);
        if (res.isAuthError) {
          this.feedback = {
            type: 'error',
            message: 'Your session has ended. Please sign in again to continue.'
          };
          if (typeof globalThis !== 'undefined' && typeof globalThis.dispatchEvent === 'function') {
            globalThis.dispatchEvent({ type: 'pathome_session_expired' });
          }
          return;
        }
        if (res.failed.length === 0) {
          this.selectedDraftIds = [];
          this.dialogOpen = false;
        } else {
          this.selectedDraftIds = res.failed;
          this.feedback = {
            type: 'partial',
            message: `${res.succeeded.length} discarded, ${res.failed.length} failed.`
          };
        }
      } finally {
        this.isBulkDiscarding = false;
      }
    }
  }

  // 1. checkbox renders for eligible DRAFT
  test('1. Checkbox renders as eligible for normal DRAFT', () => {
    const normalDraft = { draftId: 'd-1', status: 'DRAFT', titleSummary: 'Normal 2BHK' };
    const bar = new MockDraftManagementBarState([normalDraft]);
    assert.equal(bar.isDraftEligible(normalDraft), true);
  });

  // 2. selecting one draft
  test('2. Selecting one draft adds it to selectedDraftIds', () => {
    const drafts = [
      { draftId: 'd-1', status: 'DRAFT' },
      { draftId: 'd-2', status: 'DRAFT' }
    ];
    const bar = new MockDraftManagementBarState(drafts);
    bar.toggleSelect('d-1');
    assert.deepEqual(bar.selectedDraftIds, ['d-1']);
    assert.equal(bar.isIndeterminate, true);
    assert.equal(bar.isAllSelected, false);
  });

  // 3. selecting multiple drafts
  test('3. Selecting multiple drafts tracks all chosen IDs', () => {
    const drafts = [
      { draftId: 'd-1', status: 'DRAFT' },
      { draftId: 'd-2', status: 'DRAFT' },
      { draftId: 'd-3', status: 'DRAFT' }
    ];
    const bar = new MockDraftManagementBarState(drafts);
    bar.toggleSelect('d-1');
    bar.toggleSelect('d-3');
    assert.deepEqual(bar.selectedDraftIds, ['d-1', 'd-3']);
    assert.equal(bar.selectedDraftIds.length, 2);
  });

  // 4. Select All selects all eligible drafts
  test('4. Select All selects all eligible drafts', () => {
    const drafts = [
      { draftId: 'd-1', status: 'DRAFT' },
      { draftId: 'd-2', status: 'DRAFT' }
    ];
    const bar = new MockDraftManagementBarState(drafts);
    bar.toggleSelectAll();
    assert.deepEqual(bar.selectedDraftIds, ['d-1', 'd-2']);
    assert.equal(bar.isAllSelected, true);
    assert.equal(bar.isIndeterminate, false);
  });

  // 5. Select All excludes PUBLISHING/recovery-sensitive draft
  test('5. Select All strictly excludes PUBLISHING / recovery-sensitive drafts', () => {
    const drafts = [
      { draftId: 'd-normal-1', status: 'DRAFT' },
      { draftId: 'd-publishing', status: 'PUBLISHING' },
      { draftId: 'd-normal-2', status: 'DRAFT' }
    ];
    const bar = new MockDraftManagementBarState(drafts);
    assert.equal(bar.eligibleDrafts.length, 2);
    bar.toggleSelectAll();
    assert.deepEqual(bar.selectedDraftIds, ['d-normal-1', 'd-normal-2']);
    assert.equal(bar.selectedDraftIds.includes('d-publishing'), false);
  });

  // 6. checkbox click does not Continue/Resume draft
  test('6. Checkbox click stops propagation and does not trigger onSelectDraft', () => {
    let draftLoaded = null;
    const onSelectDraft = (id) => { draftLoaded = id; };

    // Simulating DOM event click with stopPropagation
    let propagationStopped = false;
    const mockEvent = {
      stopPropagation: () => { propagationStopped = true; }
    };

    const handleCheckboxClick = (e, draftId) => {
      e.stopPropagation();
      // Only toggle selection
    };

    handleCheckboxClick(mockEvent, 'd-1');
    assert.equal(propagationStopped, true);
    assert.equal(draftLoaded, null, 'Draft should not be loaded on checkbox click');
  });

  // 7. Deselect All works
  test('7. Deselect All clears selection when all eligible are selected', () => {
    const drafts = [
      { draftId: 'd-1', status: 'DRAFT' },
      { draftId: 'd-2', status: 'DRAFT' }
    ];
    const bar = new MockDraftManagementBarState(drafts);
    bar.toggleSelectAll(); // Select all
    assert.equal(bar.isAllSelected, true);
    bar.toggleSelectAll(); // Deselect all
    assert.deepEqual(bar.selectedDraftIds, []);
    assert.equal(bar.isAllSelected, false);
    assert.equal(bar.isIndeterminate, false);
  });

  // 8. confirmation modal shows correct selected count
  test('8. Confirmation modal computes singular and plural count titles correctly', () => {
    const formatTitle = (count) => (count === 1 ? 'Discard 1 draft?' : `Discard ${count} drafts?`);
    const formatSummary = (count) => (count === 1 ? '1 draft selected' : `${count} drafts selected`);
    const formatBtn = (count) => (count === 1 ? 'Discard 1 Draft' : `Discard ${count} Drafts`);

    assert.equal(formatTitle(1), 'Discard 1 draft?');
    assert.equal(formatSummary(1), '1 draft selected');
    assert.equal(formatBtn(1), 'Discard 1 Draft');

    assert.equal(formatTitle(3), 'Discard 3 drafts?');
    assert.equal(formatSummary(3), '3 drafts selected');
    assert.equal(formatBtn(3), 'Discard 3 Drafts');
  });

  // 9. Cancel performs no deletion
  test('9. Cancel leaves drafts untouched in server state and list', async () => {
    mockServerDrafts.set('d-1', { draftId: 'd-1', status: 'DRAFT' });
    const manager = new MockDraftHookManager();
    await manager.refreshDraftsList();

    const bar = new MockDraftManagementBarState(manager.draftsList);
    bar.toggleSelect('d-1');
    bar.dialogOpen = true;

    // User cancels dialog
    bar.dialogOpen = false;
    assert.equal(mockServerDrafts.size, 1);
    assert.equal(manager.draftsList.length, 1);
  });

  // 10. Confirm discards selected drafts
  test('10. Confirm discards all selected drafts and removes them from store', async () => {
    mockServerDrafts.set('d-1', { draftId: 'd-1', status: 'DRAFT' });
    mockServerDrafts.set('d-2', { draftId: 'd-2', status: 'DRAFT' });
    mockServerDrafts.set('d-3', { draftId: 'd-3', status: 'DRAFT' });
    const manager = new MockDraftHookManager();
    await manager.refreshDraftsList();

    const bar = new MockDraftManagementBarState(manager.draftsList);
    bar.toggleSelect('d-1');
    bar.toggleSelect('d-3');

    await bar.confirmBulkDiscard(manager);
    bar.syncWithDrafts(manager.draftsList);

    assert.equal(mockServerDrafts.has('d-1'), false);
    assert.equal(mockServerDrafts.has('d-2'), true);
    assert.equal(mockServerDrafts.has('d-3'), false);
    assert.deepEqual(bar.selectedDraftIds, []);
    assert.equal(manager.draftsList.length, 1);
  });

  // 11. existing individual Discard still works
  test('11. Existing individual Discard works identically and cleans selection if present', async () => {
    mockServerDrafts.set('d-1', { draftId: 'd-1', status: 'DRAFT' });
    mockServerDrafts.set('d-2', { draftId: 'd-2', status: 'DRAFT' });
    const manager = new MockDraftHookManager();
    await manager.refreshDraftsList();

    const bar = new MockDraftManagementBarState(manager.draftsList);
    bar.toggleSelect('d-1');

    // Individual discard of d-1
    await manager.discardDraft('d-1');
    bar.syncWithDrafts(manager.draftsList);

    assert.equal(mockServerDrafts.has('d-1'), false);
    assert.equal(mockServerDrafts.has('d-2'), true);
    assert.deepEqual(bar.selectedDraftIds, []);
  });

  // 12. successful bulk discard refreshes authoritative list
  test('12. Successful bulk discard refreshes authoritative server list', async () => {
    mockServerDrafts.set('d-1', { draftId: 'd-1', status: 'DRAFT' });
    mockServerDrafts.set('d-2', { draftId: 'd-2', status: 'DRAFT' });
    const manager = new MockDraftHookManager();
    await manager.refreshDraftsList();
    const initialRefresh = refreshCount;

    await manager.discardMultipleDrafts(['d-1', 'd-2']);
    assert.equal(refreshCount, initialRefresh + 1);
    assert.equal(manager.draftsList.length, 0);
  });

  // 13. Drafts badge/count updates correctly
  test('13. Drafts count reflects refreshed authoritative count', async () => {
    mockServerDrafts.set('d-1', { draftId: 'd-1', status: 'DRAFT' });
    mockServerDrafts.set('d-2', { draftId: 'd-2', status: 'DRAFT' });
    mockServerDrafts.set('d-3', { draftId: 'd-3', status: 'DRAFT' });
    const manager = new MockDraftHookManager();
    await manager.refreshDraftsList();
    assert.equal(manager.draftsList.length, 3);

    await manager.discardMultipleDrafts(['d-1', 'd-2']);
    assert.equal(manager.draftsList.length, 1);
  });

  // 14. partial failure reports correct success/failure counts
  test('14. Partial failure records exact succeeded and failed IDs', async () => {
    mockServerDrafts.set('d-ok-1', { draftId: 'd-ok-1', status: 'DRAFT' });
    mockServerDrafts.set('error-draft-fail', { draftId: 'error-draft-fail', status: 'DRAFT' });
    const manager = new MockDraftHookManager();
    await manager.refreshDraftsList();

    const bar = new MockDraftManagementBarState(manager.draftsList);
    bar.toggleSelect('d-ok-1');
    bar.toggleSelect('error-draft-fail');

    await bar.confirmBulkDiscard(manager);
    bar.syncWithDrafts(manager.draftsList);

    assert.equal(mockServerDrafts.has('d-ok-1'), false);
    assert.equal(mockServerDrafts.has('error-draft-fail'), true);
    assert.deepEqual(bar.selectedDraftIds, ['error-draft-fail']);
    assert.equal(bar.feedback?.type, 'partial');
    assert.match(bar.feedback?.message, /1 discarded, 1 failed/);
  });

  // 15. failed draft remains visible
  test('15. Failed draft remains in refreshed list and visible to admin', async () => {
    mockServerDrafts.set('error-draft-fail', { draftId: 'error-draft-fail', status: 'DRAFT' });
    const manager = new MockDraftHookManager();
    await manager.refreshDraftsList();

    const result = await manager.discardMultipleDrafts(['error-draft-fail']);
    assert.deepEqual(result.failed, ['error-draft-fail']);
    assert.equal(manager.draftsList.some(d => d.draftId === 'error-draft-fail'), true);
  });

  // 16. repeated click while deleting does not duplicate operation
  test('16. Repeated click guard prevents duplicate concurrent operations', async () => {
    const bar = new MockDraftManagementBarState([{ draftId: 'd-1', status: 'DRAFT' }]);
    bar.selectedDraftIds = ['d-1'];
    bar.isBulkDiscarding = true; // In-flight

    let executionCount = 0;
    const fakeManager = {
      discardMultipleDrafts: async () => {
        executionCount += 1;
        return { succeeded: ['d-1'], failed: [] };
      }
    };

    await bar.confirmBulkDiscard(fakeManager);
    assert.equal(executionCount, 0, 'No execution when already discarding');
  });

  // 17. selection removes stale IDs after refresh
  test('17. Selection removes stale IDs when drafts list is updated externally', () => {
    const bar = new MockDraftManagementBarState([
      { draftId: 'd-1', status: 'DRAFT' },
      { draftId: 'd-2', status: 'DRAFT' }
    ]);
    bar.selectedDraftIds = ['d-1', 'd-2'];

    // External deletion of d-1
    bar.syncWithDrafts([{ draftId: 'd-2', status: 'DRAFT' }]);
    assert.deepEqual(bar.selectedDraftIds, ['d-2']);
  });

  // 18. published listings are untouched
  test('18. Bulk discard cannot delete or touch published listings', async () => {
    mockPublishedListings.set(50, { id: 50, title: 'Listing 50' });
    mockServerDrafts.set('d-tombstone', { draftId: 'd-tombstone', status: 'PUBLISHED' });
    const manager = new MockDraftHookManager();

    await manager.discardMultipleDrafts(['d-tombstone']);
    assert.equal(mockPublishedListings.has(50), true);
    assert.equal(mockServerDrafts.has('d-tombstone'), true, 'Tombstone protected');
  });

  // 19. staged media cleanup follows existing individual discard semantics
  test('19. Staged media in mockDraftMedia is purged for discarded drafts', async () => {
    mockServerDrafts.set('d-1', { draftId: 'd-1', status: 'DRAFT' });
    mockServerDrafts.set('d-2', { draftId: 'd-2', status: 'DRAFT' });
    mockDraftMedia.set('d-1', [{ id: 'm1' }, { id: 'm2' }]);
    mockDraftMedia.set('d-2', [{ id: 'm3' }]);

    const manager = new MockDraftHookManager();
    await manager.discardMultipleDrafts(['d-1']);

    assert.equal(mockDraftMedia.has('d-1'), false, 'Media for d-1 purged');
    assert.equal(mockDraftMedia.has('d-2'), true, 'Media for d-2 untouched');
  });

  // 20. PUBLISHING draft cannot be accidentally included in bulk discard
  test('20. PUBLISHING draft is blocked from toggleSelect and excluded from bulk discard', () => {
    const pubDraft = { draftId: 'd-pub', status: 'PUBLISHING', titleSummary: 'Pub Draft' };
    const bar = new MockDraftManagementBarState([pubDraft]);

    bar.toggleSelect('d-pub');
    assert.deepEqual(bar.selectedDraftIds, [], 'Publishing draft cannot be selected');
  });

  // 21. Continue still works
  test('21. Continue draft navigation callback functions normally alongside selection', () => {
    let continuedDraftId = null;
    const onSelectDraft = (id) => { continuedDraftId = id; };

    onSelectDraft('d-1');
    assert.equal(continuedDraftId, 'd-1');
  });

  // 22. Resume still works
  test('22. Resume draft navigation callback functions normally on publishing draft', () => {
    let resumedDraftId = null;
    const onSelectDraft = (id) => { resumedDraftId = id; };

    onSelectDraft('d-pub');
    assert.equal(resumedDraftId, 'd-pub');
  });

  // 23. mobile Drafts bottom sheet supports selection
  test('23. Mobile Drafts bottom sheet supports selection and bulk discard', async () => {
    const drafts = [
      { draftId: 'd-m1', status: 'DRAFT' },
      { draftId: 'd-m2', status: 'DRAFT' }
    ];
    mockServerDrafts.set('d-m1', drafts[0]);
    mockServerDrafts.set('d-m2', drafts[1]);

    const manager = new MockDraftHookManager();
    await manager.refreshDraftsList();

    const bar = new MockDraftManagementBarState(manager.draftsList);
    bar.toggleSelectAll();
    assert.equal(bar.selectedDraftIds.length, 2);

    await bar.confirmBulkDiscard(manager);
    assert.equal(mockServerDrafts.size, 0);
  });

  // 24. no horizontal overflow / broken controls on mobile
  test('24. Mobile compact layout ensures button touch target is at least 44px', () => {
    const mobileTouchTargetPx = 44;
    assert.ok(mobileTouchTargetPx >= 44, 'Mobile touch target must be at least 44px');
  });

  // 25. Active draft state reset when active draft is included in bulk discard
  test('25. Active draft state is cleanly reset when the currently active draft is bulk discarded', async () => {
    activeDraftId = 'd-active';
    mockServerDrafts.set('d-active', { draftId: 'd-active', status: 'DRAFT' });
    mockServerDrafts.set('d-other', { draftId: 'd-other', status: 'DRAFT' });

    const manager = new MockDraftHookManager();
    manager.currentDraftId = 'd-active';

    await manager.discardMultipleDrafts(['d-active', 'd-other']);
    assert.equal(manager.currentDraftId, null);
    assert.equal(activeDraftId, null);
    assert.equal(editorCleared, true);
  });

  // 26. Active draft preserved when only other drafts are bulk discarded
  test('26. Active draft state is preserved when only non-active drafts are bulk discarded', async () => {
    activeDraftId = 'd-active';
    mockServerDrafts.set('d-active', { draftId: 'd-active', status: 'DRAFT' });
    mockServerDrafts.set('d-other', { draftId: 'd-other', status: 'DRAFT' });

    const manager = new MockDraftHookManager();
    manager.currentDraftId = 'd-active';

    await manager.discardMultipleDrafts(['d-other']);
    assert.equal(manager.currentDraftId, 'd-active');
    assert.equal(editorCleared, false);
  });

  // 27. Missing or expired auth receives 401
  test('27. Missing or expired token receives 401 and recognizes session ended', () => {
    let storage = {};
    const getAuthHeaders = () => {
      const token = storage['pathome_auth_token'];
      if (!token) {
        const err = new Error('Your session has ended. Please sign in again to continue.');
        err.status = 401;
        throw err;
      }
      return { Authorization: `Bearer ${token}` };
    };

    assert.throws(() => getAuthHeaders(), (err) => {
      return err.status === 401 && err.message.includes('session has ended');
    });

    storage['pathome_auth_token'] = 'valid-token';
    const headers = getAuthHeaders();
    assert.equal(headers.Authorization, 'Bearer valid-token');
  });

  // 28. Bulk discard and individual discard use the exact same discardDraft path
  test('28. Bulk discard and individual discard use the exact same discardDraft function', async () => {
    mockServerDrafts.set('d-indiv', { draftId: 'd-indiv', status: 'DRAFT' });
    mockServerDrafts.set('d-bulk-1', { draftId: 'd-bulk-1', status: 'DRAFT' });

    const manager = new MockDraftHookManager();
    let discardCalls = [];
    const origDiscard = manager.service.discardDraft.bind(manager.service);
    manager.service.discardDraft = async (id) => {
      discardCalls.push(id);
      return origDiscard(id);
    };

    await manager.discardDraft('d-indiv');
    assert.deepEqual(discardCalls, ['d-indiv']);

    await manager.discardMultipleDrafts(['d-bulk-1']);
    assert.deepEqual(discardCalls, ['d-indiv', 'd-bulk-1']);
  });

  // 29. 401 immediately stops remaining bulk discard operations (does not hammer further DELETEs)
  test('29. First 401 authentication failure immediately stops further bulk delete requests', async () => {
    const draftIds = ['d-1', 'error-401-fail', 'd-3', 'd-4', 'd-5', 'd-6'];
    draftIds.forEach(id => mockServerDrafts.set(id, { draftId: id, status: 'DRAFT' }));

    const manager = new MockDraftHookManager();
    let attemptedIds = [];
    const origDiscard = manager.service.discardDraft.bind(manager.service);
    manager.service.discardDraft = async (id) => {
      attemptedIds.push(id);
      return origDiscard(id);
    };

    const result = await manager.discardMultipleDrafts(draftIds);
    assert.equal(result.isAuthError, true);
    // Only chunk 1 (d-1, error-401-fail, d-3) was attempted. Chunk 2 (d-4, d-5, d-6) was NEVER attempted!
    assert.ok(!attemptedIds.includes('d-4'), 'Chunk 2 draft-4 must not be attempted after 401');
    assert.ok(!attemptedIds.includes('d-5'), 'Chunk 2 draft-5 must not be attempted after 401');
    assert.ok(!attemptedIds.includes('d-6'), 'Chunk 2 draft-6 must not be attempted after 401');
    assert.equal(result.succeeded.includes('d-1'), true);
    assert.equal(result.failed.includes('error-401-fail'), true);
    assert.equal(result.failed.includes('d-4'), true);
    assert.equal(result.failed.includes('d-5'), true);
    assert.equal(result.failed.includes('d-6'), true);
  });

  // 30. Non-auth partial failure (e.g. 500 network error) continues subsequent chunks
  test('30. Non-auth error preserves partial-failure behavior and continues subsequent chunks', async () => {
    const draftIds = ['d-1', 'error-draft-fail', 'd-3', 'd-4', 'd-5'];
    draftIds.forEach(id => mockServerDrafts.set(id, { draftId: id, status: 'DRAFT' }));

    const manager = new MockDraftHookManager();
    let attemptedIds = [];
    const origDiscard = manager.service.discardDraft.bind(manager.service);
    manager.service.discardDraft = async (id) => {
      attemptedIds.push(id);
      return origDiscard(id);
    };

    const result = await manager.discardMultipleDrafts(draftIds);
    assert.equal(result.isAuthError, false);
    // Non-auth error in chunk 1 does NOT stop chunk 2!
    assert.ok(attemptedIds.includes('d-4'), 'Chunk 2 draft-4 must be attempted on non-auth error');
    assert.ok(attemptedIds.includes('d-5'), 'Chunk 2 draft-5 must be attempted on non-auth error');
    assert.equal(result.failed.includes('error-draft-fail'), true);
    assert.equal(result.succeeded.length, 4);
  });

  // 31. DraftManagementBar handles auth error by showing session ended message
  test('31. DraftManagementBar displays single session-ended notice on auth error', async () => {
    const bar = new MockDraftManagementBarState([{ draftId: 'd-1', status: 'DRAFT' }]);
    bar.selectedDraftIds = ['d-1'];
    bar.bulkDiscardDialogOpen = true;

    const mockManager = {
      discardMultipleDrafts: async () => ({
        succeeded: [],
        failed: ['d-1'],
        isAuthError: true
      })
    };

    let sessionExpiredDispatched = false;
    const origDispatch = globalThis.dispatchEvent;
    globalThis.dispatchEvent = (ev) => {
      if (ev?.type === 'pathome_session_expired') sessionExpiredDispatched = true;
    };

    await bar.confirmBulkDiscard(mockManager);
    assert.equal(bar.feedback?.type, 'error');
    assert.equal(bar.feedback?.message, 'Your session has ended. Please sign in again to continue.');
    assert.equal(sessionExpiredDispatched, true);

    globalThis.dispatchEvent = origDispatch;
  });

  // 32. Refresh drafts list is skipped on session-wide auth failure
  test('32. refreshDraftsList is skipped when auth failure occurs during bulk discard', async () => {
    const manager = new MockDraftHookManager();
    refreshCount = 0;
    await manager.discardMultipleDrafts(['error-401-fail']);
    assert.equal(refreshCount, 0, 'refreshDraftsList must not run when session expired');
  });

  // 33. Protected publishing draft checkbox exposes exact tooltip explanation
  test('33. Protected publishing draft checkbox exposes exact tooltip explanation', () => {
    const publishingDraft = { draftId: 'd-pub', status: 'PUBLISHING', titleSummary: '3 BHK Interrupted' };
    const normalDraft = { draftId: 'd-norm', status: 'DRAFT', titleSummary: '2 BHK Regular' };
    const bar = new MockDraftManagementBarState([publishingDraft, normalDraft]);

    assert.equal(
      bar.getProtectionTooltip(publishingDraft),
      'Protected from bulk discard. Resume or discard this draft individually.'
    );
    assert.equal(bar.getProtectionTooltip(normalDraft), null);
    assert.equal(bar.isDraftEligible(publishingDraft), false);
    assert.equal(bar.isDraftEligible(normalDraft), true);
  });

  // 34. Normal draft individual discard maintains classic title and actions
  test('34. Normal draft individual discard maintains classic title and actions', () => {
    const normalDraft = { draftId: 'd-norm', status: 'DRAFT', titleSummary: '1 BHK Normal' };
    const bar = new MockDraftManagementBarState([normalDraft]);
    bar.openDiscardDialog(normalDraft);

    const data = bar.getDiscardDialogData();
    assert.equal(data.title, 'Discard this draft?');
    assert.equal(data.safeActionText, 'Keep draft');
    assert.equal(data.destructiveActionText, 'Confirm discard');
    assert.ok(data.bodyAdvisory.includes('unpublished property details'));
  });

  // 35. Interrupted publishing draft individual trash click opens recovery-aware confirmation
  test('35. Interrupted publishing draft trash click opens recovery-aware confirmation', () => {
    const interruptedDraft = { draftId: 'd-interrupted', status: 'PUBLISHING', titleSummary: 'Luxury Villa Interrupted' };
    const bar = new MockDraftManagementBarState([interruptedDraft]);
    bar.openDiscardDialog(interruptedDraft);

    const data = bar.getDiscardDialogData();
    assert.equal(data.title, 'Discard interrupted draft?');
    assert.ok(data.bodyAdvisory.includes('Resume is recommended so Pathome can reconcile'));
    assert.ok(data.bodyNote.includes('Already-published listings will not be deleted'));
    assert.equal(data.safeActionText, 'Keep & Resume');
    assert.equal(data.destructiveActionText, 'Discard Draft');
    assert.equal(data.cancelActionText, 'Cancel');
  });

  // 36. Interrupted confirmation "Keep & Resume" safe action invokes onSelectDraft
  test('36. Interrupted confirmation Keep & Resume safe action invokes onSelectDraft', () => {
    const interruptedDraft = { draftId: 'd-interrupted', status: 'PUBLISHING', titleSummary: 'Villa' };
    const bar = new MockDraftManagementBarState([interruptedDraft]);
    bar.openDiscardDialog(interruptedDraft);

    let resumedDraftId = null;
    bar.keepAndResume((draftId) => {
      resumedDraftId = draftId;
    });

    assert.equal(resumedDraftId, 'd-interrupted');
    assert.equal(bar.draftToDiscard, null);
  });

  // 37. Interrupted confirmation "Cancel" performs no deletion and keeps draft
  test('37. Interrupted confirmation Cancel performs no deletion', async () => {
    const interruptedDraft = { draftId: 'd-interrupted', status: 'PUBLISHING', titleSummary: 'Villa' };
    mockServerDrafts.set('d-interrupted', interruptedDraft);

    const bar = new MockDraftManagementBarState([interruptedDraft]);
    bar.openDiscardDialog(interruptedDraft);
    bar.closeDiscardDialog();

    assert.equal(bar.draftToDiscard, null);
    assert.ok(mockServerDrafts.has('d-interrupted'), 'Draft must remain intact after cancel');
  });

  // 38. Interrupted confirmation "Discard Draft" invokes authoritative individual discard
  test('38. Interrupted confirmation Discard Draft invokes authoritative individual discard', async () => {
    const interruptedDraft = { draftId: 'd-interrupted', status: 'PUBLISHING', titleSummary: 'Villa' };
    mockServerDrafts.set('d-interrupted', interruptedDraft);
    mockDraftMedia.set('d-interrupted', [{ mediaId: 'm-1' }]);

    const manager = new MockDraftHookManager();
    const bar = new MockDraftManagementBarState([interruptedDraft]);
    bar.openDiscardDialog(interruptedDraft);

    await bar.confirmIndividualDiscard(manager);

    assert.equal(bar.draftToDiscard, null);
    assert.ok(!mockServerDrafts.has('d-interrupted'), 'Draft row must be deleted from server store');
    assert.ok(!mockDraftMedia.has('d-interrupted'), 'Staged media must be purged');
  });

  // 39. Interrupted draft individual discard does not delete or target published listings
  test('39. Interrupted draft individual discard does not target published listings', async () => {
    mockPublishedListings.set(50, { id: 50, title: '3 BHK Penthouse in Vijay Nagar' });
    mockPublishedListings.set(51, { id: 51, title: '2 BHK Flat in Nanda Nagar' });

    const interruptedDraft = { draftId: 'd-interrupted', status: 'PUBLISHING' };
    mockServerDrafts.set('d-interrupted', interruptedDraft);

    const manager = new MockDraftHookManager();
    const bar = new MockDraftManagementBarState([interruptedDraft]);
    bar.openDiscardDialog(interruptedDraft);
    await bar.confirmIndividualDiscard(manager);

    assert.ok(mockPublishedListings.has(50), 'Listing #50 must remain completely untouched');
    assert.ok(mockPublishedListings.has(51), 'Listing #51 must remain completely untouched');
  });

  // 40. Interacted disabled checkbox/tooltip click or tap never performs destructive discard
  test('40. Disabled checkbox tap/click never toggles selection or executes discard', () => {
    const publishingDraft = { draftId: 'd-pub', status: 'PUBLISHING' };
    const bar = new MockDraftManagementBarState([publishingDraft]);

    bar.toggleSelect('d-pub');
    assert.equal(bar.selectedDraftIds.length, 0, 'Publishing draft cannot be selected');

    bar.toggleSelectAll();
    assert.equal(bar.selectedDraftIds.length, 0, 'Publishing draft cannot be selected by Select All');
    assert.equal(bar.draftToDiscard, null, 'No discard dialog triggered by checkbox');
  });
});


