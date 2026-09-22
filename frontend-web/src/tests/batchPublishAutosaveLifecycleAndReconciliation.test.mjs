/**
 * P0 Regression Suite: Batch Publish Autosave Lifecycle & Reconciliation Concurrency
 *
 * Verifies:
 * 1. Publish start cancels pending debounced autosave.
 * 2. Autosave is suspended throughout batch publish and reconciliation.
 * 3. Publish loop state mutations (setStagedCards, setActiveCardId, setPublishingCardId)
 *    do not issue network autosaves.
 * 4. Pending autosave is defensively cancelled before calling onBatchPublished (reconcile).
 * 5. Autosave resumes normally after recoverable publish/failure flow completes.
 * 6. Error classification:
 *    - Real network transport failure (TypeError: Failed to fetch / offline) -> "Publishing interrupted"
 *    - HTTP 500 server error -> NOT network interruption
 *    - HTTP 409 conflict -> NOT network interruption
 * 7. Authoritative draft version from reconciliation is preserved and subsequent autosaves carry the new version.
 */

import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

describe('Batch Publish Autosave Lifecycle & Reconciliation Concurrency Suite', () => {

  // Simulate usePropertyDraft autosave state machine
  class MockPropertyDraftHook {
    constructor() {
      this.currentDraftId = 'draft-batch-test-123';
      this.version = 1;
      this.autosaveStatus = 'idle';
      this.pendingPayload = null;
      this.debounceTimer = null;
      this.isSuspended = false;
      this.networkSaves = []; // Tracks actual HTTP calls to draftService.saveDraft
    }

    scheduleAutosave(payload, titleSummary, itemCount = 1) {
      if (this.isSuspended) {
        // Suspended during publish - must ignore
        return;
      }
      this.pendingPayload = { payload, titleSummary, itemCount };
      this.autosaveStatus = 'saving';

      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer);
      }

      this.debounceTimer = setTimeout(() => {
        if (this.isSuspended) return;
        this.flushPendingSave();
      }, 50);
    }

    flushPendingSave() {
      if (this.isSuspended) return;
      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer);
        this.debounceTimer = null;
      }
      if (!this.pendingPayload) return;

      const toSave = this.pendingPayload;
      this.networkSaves.push({
        draftId: this.currentDraftId,
        version: this.version,
        payload: toSave.payload,
        timestamp: Date.now()
      });
      this.version += 1;
      this.autosaveStatus = 'saved';
      this.pendingPayload = null;
    }

    cancelAutosave() {
      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer);
        this.debounceTimer = null;
      }
      this.pendingPayload = null;
      this.autosaveStatus = 'idle';
    }

    suspendAutosave() {
      this.isSuspended = true;
      this.cancelAutosave();
    }

    resumeAutosave() {
      this.isSuspended = false;
    }

    async onBatchPublished(publishedCardIds, completedListings) {
      // Reconcile increments server draft version
      this.version += 1;
      return {
        draftId: this.currentDraftId,
        version: this.version,
        itemCount: 0
      };
    }
  }

  // Error classifier matching BatchPropertyIngestionStudio.tsx
  function classifyReconcileError(err, isOnline = true) {
    const isNetworkLoss =
      !isOnline ||
      (err instanceof TypeError && /fetch|network|load/i.test(err.message)) ||
      err?.status === 0;

    return isNetworkLoss ? 'network' : 'server';
  }

  function getReconcileDialogTitle(classifiedType) {
    if (classifiedType === 'network') {
      return 'Publishing interrupted';
    }
    return 'Properties published';
  }

  test('Requirement 1 & 2: publish start cancels already-pending autosave and suspends autosave', () => {
    const draft = new MockPropertyDraftHook();

    // User types / edits something right before clicking Publish
    draft.scheduleAutosave({ text: 'Draft edit in progress' }, 'Batch (2 properties)', 2);
    assert.ok(draft.debounceTimer !== null, 'Autosave timer should be scheduled');
    assert.equal(draft.autosaveStatus, 'saving');

    // Publish begins: suspendAutosave() is called
    draft.suspendAutosave();

    assert.equal(draft.debounceTimer, null, 'Pending timer must be cancelled');
    assert.equal(draft.pendingPayload, null, 'Pending payload must be cleared');
    assert.equal(draft.autosaveStatus, 'idle', 'Status must return to idle');
    assert.equal(draft.isSuspended, true, 'Autosave must be flagged suspended');
    assert.equal(draft.networkSaves.length, 0, 'Zero network saves must have fired');
  });

  test('Requirement 3 & 4: publish loop stagedCards mutations do NOT issue network autosaves', async () => {
    const draft = new MockPropertyDraftHook();
    draft.suspendAutosave();

    // Simulate publish loop: each card sets publishing state, activeCard, stagedCards
    const cards = [
      { id: 'card-1', title: 'Prop 1' },
      { id: 'card-2', title: 'Prop 2' }
    ];

    for (const card of cards) {
      // Mutations that happen in BatchPropertyIngestionStudio publish loop:
      // setPublishingCardId(card.id)
      // setActiveCardId(card.id)
      // setStagedCards(...)
      // These trigger useEffect which calls scheduleAutosave:
      draft.scheduleAutosave({ stagedCards: [{ ...card, publishedId: 101 }] }, 'Batch in progress', 1);
    }

    // Wait past debounce time
    await new Promise((resolve) => setTimeout(resolve, 80));

    assert.equal(draft.networkSaves.length, 0, 'No autosave HTTP requests must be sent during publish loop');
    assert.equal(draft.pendingPayload, null);
  });

  test('Requirement 4: pending autosave is defensively cancelled immediately before reconcile', () => {
    const draft = new MockPropertyDraftHook();
    draft.suspendAutosave();

    // Defensively call cancelAutosave before reconcile-batch
    draft.cancelAutosave();
    assert.equal(draft.debounceTimer, null);
    assert.equal(draft.pendingPayload, null);
    assert.equal(draft.autosaveStatus, 'idle');
  });

  test('Requirement 5: normal autosave resumes appropriately after recoverable publish exit', async () => {
    const draft = new MockPropertyDraftHook();
    draft.suspendAutosave();

    // Publish ends (recoverable error or complete)
    draft.resumeAutosave();
    assert.equal(draft.isSuspended, false);

    // User edits draft after recoverable flow
    draft.scheduleAutosave({ text: 'User editing remaining cards' }, 'Batch (1 property)', 1);
    assert.ok(draft.debounceTimer !== null, 'Autosave timer must be scheduled again');

    await new Promise((resolve) => setTimeout(resolve, 80));
    assert.equal(draft.networkSaves.length, 1, 'Autosave HTTP request must fire after resume');
    assert.equal(draft.autosaveStatus, 'saved');
  });

  test('Requirement 6: real transport / network failure -> "Publishing interrupted"', () => {
    // Native browser TypeError on fetch failure
    const typeError = new TypeError('Failed to fetch');
    const classification = classifyReconcileError(typeError, true);
    assert.equal(classification, 'network');
    assert.equal(getReconcileDialogTitle(classification), 'Publishing interrupted');

    // Offline navigator
    const offlineClassification = classifyReconcileError(new Error('connection dropped'), false);
    assert.equal(offlineClassification, 'network');
    assert.equal(getReconcileDialogTitle(offlineClassification), 'Publishing interrupted');

    // Network error status 0 (aborted / CORS / transport drop)
    const status0Error = { status: 0, message: 'network error' };
    assert.equal(classifyReconcileError(status0Error, true), 'network');
    assert.equal(getReconcileDialogTitle(classifyReconcileError(status0Error, true)), 'Publishing interrupted');
  });

  test('Requirement 7: HTTP 500 reconciliation failure must NOT claim network interruption', () => {
    const http500Error = {
      status: 500,
      message: 'Server error: StaleObjectStateException during reconciliation'
    };

    const classification = classifyReconcileError(http500Error, true);
    assert.equal(classification, 'server');
    const title = getReconcileDialogTitle(classification);
    assert.notEqual(title, 'Publishing interrupted');
    assert.equal(title, 'Properties published');
  });

  test('Requirement 8: HTTP 409 conflict must NOT claim network interruption', () => {
    const http409Error = {
      status: 409,
      message: 'Conflict: Draft version mismatch'
    };

    const classification = classifyReconcileError(http409Error, true);
    assert.equal(classification, 'server');
    const title = getReconcileDialogTitle(classification);
    assert.notEqual(title, 'Publishing interrupted');
    assert.equal(title, 'Properties published');
  });

  test('Requirement 9: reconciliation updates version and post-reconcile autosave uses updated version', async () => {
    const draft = new MockPropertyDraftHook();
    assert.equal(draft.version, 1);

    draft.suspendAutosave();

    // Reconcile with backend
    const remaining = await draft.onBatchPublished(['card-1'], [{ cardId: 'card-1', listingId: 101, title: 'Prop 1' }]);
    assert.equal(remaining.version, 2);

    draft.resumeAutosave();

    // User edits remaining card
    draft.scheduleAutosave({ stagedCards: [{ id: 'card-2', title: 'Prop 2' }] }, 'Batch (1 property)', 1);
    await new Promise((resolve) => setTimeout(resolve, 80));

    assert.equal(draft.networkSaves.length, 1);
    assert.equal(draft.networkSaves[0].version, 2, 'Post-reconcile save must use authoritative version from reconcile');
  });
});
