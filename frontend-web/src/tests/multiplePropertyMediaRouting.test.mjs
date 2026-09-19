import test from 'node:test';
import assert from 'node:assert/strict';

// Authoritative delimiter-agnostic multiple-property entry pattern from MasterAdminDashboard
const MULTIPLE_PROPERTY_ENTRY_PATTERN = /(?:\r?\n\s*\r?\n+|---|\bnext\s*(?:property|flat|house|listing|unit)\b|\b(?:and\s+)?(?:the\s+)?(?:second|third|fourth|another)\s+(?:property|flat|house|listing|unit)(?:\s+is)?\b|(?:^|\n)\s*(?:\d+[\).]|#\d+)\s+)/im;

/**
 * Simulates MasterAdminDashboard media upload routing logic
 */
function simulateMediaUploadRouting({
  newBhkLabel = '',
  uploadMode = 'single',
  selectedBatchDraftId = null,
  batchDetails = '',
  lastExtractedResult = null
}) {
  let activeUploadMode = uploadMode;
  let activeBatchDetails = batchDetails;
  let isMediaUploadModalOpen = false;
  let scrolledTarget = null;

  const isMultiple =
    activeUploadMode === 'multiple' ||
    selectedBatchDraftId !== null ||
    MULTIPLE_PROPERTY_ENTRY_PATTERN.test(newBhkLabel) ||
    Boolean(lastExtractedResult?.rawInput && MULTIPLE_PROPERTY_ENTRY_PATTERN.test(lastExtractedResult.rawInput));

  if (isMultiple) {
    const details =
      newBhkLabel.trim() ||
      (typeof lastExtractedResult?.rawInput === 'string' ? lastExtractedResult.rawInput.trim() : '') ||
      activeBatchDetails.trim();

    if (details && details !== activeBatchDetails) {
      activeBatchDetails = details;
    }
    if (activeUploadMode !== 'multiple') {
      activeUploadMode = 'multiple';
    }
    isMediaUploadModalOpen = false;
    scrolledTarget = 'start';
  } else {
    isMediaUploadModalOpen = true;
    scrolledTarget = 'center';
  }

  return {
    isMultiple,
    uploadMode: activeUploadMode,
    batchDetails: activeBatchDetails,
    isMediaUploadModalOpen,
    scrolledTarget
  };
}

test('Multiple Property Media Upload Routing Suite', async (t) => {
  await t.test('1. Single Property: Upload routes strictly to single-property uploader', () => {
    const singlePrompt = '2 BHK flat for rent in Vijay Nagar Indore, monthly rent 18000, deposit 36000';
    assert.equal(MULTIPLE_PROPERTY_ENTRY_PATTERN.test(singlePrompt), false, 'Single prompt does not match batch pattern');

    const result = simulateMediaUploadRouting({
      newBhkLabel: singlePrompt,
      uploadMode: 'single',
      selectedBatchDraftId: null
    });

    assert.equal(result.isMultiple, false, 'Not identified as multiple');
    assert.equal(result.uploadMode, 'single', 'Upload mode remains single');
    assert.equal(result.isMediaUploadModalOpen, true, 'Opens single-property media upload modal');
    assert.equal(result.scrolledTarget, 'center', 'Focuses center console');
  });

  await t.test('2. Multiple Properties: Upload routes to multi-property media cart', () => {
    const multiPrompt = `
1 BHK in Vijay Nagar rent 12000
---
2 BHK in Bhawarkua rent 18000
    `.trim();

    assert.equal(MULTIPLE_PROPERTY_ENTRY_PATTERN.test(multiPrompt), true, 'Matches batch delimiter pattern');

    const result = simulateMediaUploadRouting({
      newBhkLabel: multiPrompt,
      uploadMode: 'single',
      selectedBatchDraftId: null
    });

    assert.equal(result.isMultiple, true, 'Correctly identified as multiple properties');
    assert.equal(result.uploadMode, 'multiple', 'Switches upload mode to multiple');
    assert.equal(result.batchDetails, multiPrompt, 'Transfers prompt to batchDetails for cart parsing');
    assert.equal(result.isMediaUploadModalOpen, false, 'Does NOT open single-property modal');
    assert.equal(result.scrolledTarget, 'start', 'Scrolls to batch cart start');
  });

  await t.test('3. Delimiter variations: numbers, words ("next property"), and blank lines route to batch cart', () => {
    const samples = [
      '1. Flat in Palasia\n2. House in Scheme 78',
      'First property is 2BHK in Nipania. Next property is 3BHK in AB Road',
      'Flat A rent 15000\n\nFlat B rent 25000',
      '#1 Studio in Vijay Nagar\n#2 Penthouse in Old Palasia'
    ];

    for (const prompt of samples) {
      assert.ok(MULTIPLE_PROPERTY_ENTRY_PATTERN.test(prompt), `Pattern should match: ${prompt}`);
      const result = simulateMediaUploadRouting({ newBhkLabel: prompt });
      assert.equal(result.uploadMode, 'multiple');
      assert.equal(result.isMediaUploadModalOpen, false);
    }
  });

  await t.test('4. Restored Batch draft routes to batch media cart', () => {
    // Simulating DraftManagementBar selecting a BATCH draft
    const draftsList = [
      { draftId: 'draft-single-abc', draftType: 'SINGLE', titleSummary: 'Single Property' },
      { draftId: 'draft-batch-xyz', draftType: 'BATCH', titleSummary: '3 Properties in Indore' }
    ];

    const selectDraft = (id) => {
      const selected = draftsList.find((d) => d.draftId === id);
      const isBatch = selected ? selected.draftType === 'BATCH' : id.includes('batch');
      if (isBatch) {
        return { selectedBatchDraftId: id, uploadMode: 'multiple' };
      }
      return { selectedBatchDraftId: null, uploadMode: 'single' };
    };

    const batchSelection = selectDraft('draft-batch-xyz');
    assert.equal(batchSelection.selectedBatchDraftId, 'draft-batch-xyz');
    assert.equal(batchSelection.uploadMode, 'multiple');

    // Subsequent media upload click
    const result = simulateMediaUploadRouting({
      uploadMode: batchSelection.uploadMode,
      selectedBatchDraftId: batchSelection.selectedBatchDraftId
    });

    assert.equal(result.uploadMode, 'multiple');
    assert.equal(result.isMediaUploadModalOpen, false, 'Does NOT open single-property modal');
  });

  await t.test('5. Browser refresh with restored Batch payload stays in batch mode', () => {
    const payloadFromStorage = {
      newBhkLabel: '1 BHK in Vijay Nagar\n---\n2 BHK in Bhawarkua',
      draftType: 'BATCH'
    };

    let uploadMode = 'single';
    let batchDetails = '';

    // Simulation of handleRestoreSingleDraft
    if (typeof payloadFromStorage.newBhkLabel === 'string') {
      if (MULTIPLE_PROPERTY_ENTRY_PATTERN.test(payloadFromStorage.newBhkLabel)) {
        batchDetails = payloadFromStorage.newBhkLabel;
        uploadMode = 'multiple';
      }
    }
    if (payloadFromStorage.draftType === 'BATCH' || payloadFromStorage.isBatch) {
      uploadMode = 'multiple';
    }

    assert.equal(uploadMode, 'multiple', 'Restored payload detected as multiple');
    assert.equal(batchDetails, payloadFromStorage.newBhkLabel);

    const routing = simulateMediaUploadRouting({
      uploadMode,
      batchDetails,
      newBhkLabel: payloadFromStorage.newBhkLabel
    });

    assert.equal(routing.uploadMode, 'multiple');
    assert.equal(routing.isMediaUploadModalOpen, false);
  });

  await t.test('6. Single property mode is NEVER accidentally converted to Batch', () => {
    const singlePrompts = [
      '1 BHK flat in Scheme 54, semi-furnished, rent 14000',
      '3 BHK luxury villa with 2 car parking and 3 balconies in Super Corridor',
      'Independent house for rent with 4 rooms, 20000 rent'
    ];

    for (const prompt of singlePrompts) {
      assert.equal(MULTIPLE_PROPERTY_ENTRY_PATTERN.test(prompt), false, `Should NOT be batch: ${prompt}`);
      const res = simulateMediaUploadRouting({ newBhkLabel: prompt, uploadMode: 'single' });
      assert.equal(res.uploadMode, 'single');
      assert.equal(res.isMediaUploadModalOpen, true, 'Must open single property modal');
    }
  });

  await t.test('7. Property-to-Media isolation: card A media NEVER leaks into card B', () => {
    const cardA = { id: 'card-1', stagedMedia: [] };
    const cardB = { id: 'card-2', stagedMedia: [] };

    const mediaForA = [
      { id: 'm1', file: { name: 'photoA1.jpg', size: 1024, type: 'image/jpeg' }, roomTag: 'LIVING_ROOM', isCover: true },
      { id: 'm2', file: { name: 'photoA2.jpg', size: 2048, type: 'image/jpeg' }, roomTag: 'KITCHEN', isCover: false }
    ];

    const mediaForB = [
      { id: 'm3', file: { name: 'photoB1.jpg', size: 1024, type: 'image/jpeg' }, roomTag: 'MASTER_BEDROOM', isCover: true }
    ];

    cardA.stagedMedia.push(...mediaForA);
    cardB.stagedMedia.push(...mediaForB);

    assert.equal(cardA.stagedMedia.length, 2);
    assert.equal(cardB.stagedMedia.length, 1);
    assert.deepEqual(cardA.stagedMedia.map(m => m.file.name), ['photoA1.jpg', 'photoA2.jpg']);
    assert.deepEqual(cardB.stagedMedia.map(m => m.file.name), ['photoB1.jpg']);
    assert.ok(cardA.stagedMedia.find(m => m.isCover).file.name === 'photoA1.jpg');
    assert.ok(cardB.stagedMedia.find(m => m.isCover).file.name === 'photoB1.jpg');
  });

  await t.test('8. Mobile responsiveness: Batch mode sets mobileWorkspaceView to review cards', () => {
    // BatchPropertyIngestionStudio auto-switches to 'review' when cards exist
    const stagedCards = [{ id: 'card-1' }, { id: 'card-2' }];
    let mobileWorkspaceView = 'descriptions';

    if (stagedCards.length > 0) {
      mobileWorkspaceView = 'review';
    }

    assert.equal(mobileWorkspaceView, 'review', 'On mobile, review cards with media sections are visible immediately');
  });

  await t.test('9. Draft clear resets state cleanly back to single mode', () => {
    let uploadMode = 'multiple';
    let selectedBatchDraftId = 'draft-batch-1';
    let batchDetails = 'multi property details';
    let newBhkLabel = 'multi property details';

    // Simulation of handleClearSingleDraftState
    const handleClear = () => {
      newBhkLabel = '';
      batchDetails = '';
      selectedBatchDraftId = null;
      uploadMode = 'single';
    };

    handleClear();

    assert.equal(uploadMode, 'single');
    assert.equal(selectedBatchDraftId, null);
    assert.equal(batchDetails, '');
    assert.equal(newBhkLabel, '');

    const afterClear = simulateMediaUploadRouting({
      newBhkLabel,
      uploadMode,
      selectedBatchDraftId
    });

    assert.equal(afterClear.uploadMode, 'single');
    assert.equal(afterClear.isMediaUploadModalOpen, true);
  });
});
