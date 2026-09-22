/**
 * Regression tests for P0 fix: ghost media accumulation prevention.
 *
 * Root cause (2026-09-21):
 *   The autosave serializer in BatchPropertyIngestionStudio.tsx previously used
 *   `.filter(m => m.stagingStatus !== 'failed')`, which allowed media items stuck
 *   in 'staging' (upload silently failed or was interrupted before .catch() fired)
 *   to be persisted in the draft payload as un-resolvable ghost entries.
 *
 *   After hard refresh these ghost entries had no property_draft_media DB row and
 *   no B2 binary, producing broken thumbnails and inflated per-card media counts.
 *
 *   Confirmed incident: draft-batch-muaw982e-zqvsz, P3 Scheme 140
 *     Expected 3 media → payload contained 7 (4 ghosts + 3 real)
 *
 * Fix:
 *   Changed filter from `!== 'failed'` to `=== 'staged'`.
 *   This ensures ONLY durably B2-staged media is persisted.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

// ---------------------------------------------------------------------------
// Helpers mirroring the exact logic in BatchPropertyIngestionStudio.tsx
// ---------------------------------------------------------------------------

function getMediaFilename(item) {
  return item.file?.name || item.originalFilename || 'Media file';
}

function getMediaFileSize(item) {
  return item.file?.size ?? item.fileSizeBytes ?? 0;
}

/**
 * Mirrors the FIXED autosave serializer from BatchPropertyIngestionStudio.tsx
 */
function serializeCardForAutosave(card) {
  return {
    ...card,
    localPhotos: [],
    localPhotoPreviews: [],
    stagedMedia: (card.stagedMedia || [])
      // FIXED: was `.filter((m) => m.stagingStatus !== 'failed')`
      // NOW:   only persist items with confirmed durable B2 staging
      .filter((m) => m.stagingStatus === 'staged')
      .map((m) => ({
        id: m.id,
        roomTag: m.roomTag,
        isCover: m.isCover,
        originalFilename: getMediaFilename(m),
        fileSizeBytes: getMediaFileSize(m),
        contentType: m.contentType || m.file?.type || 'image/jpeg'
      }))
  };
}

/**
 * Mirrors handleRestoreBatchDraft restoration logic.
 */
function simulateRestoration(payloadCards, serverMedia) {
  const result = [];
  for (let idx = 0; idx < payloadCards.length; idx++) {
    const card = payloadCards[idx];
    const cardMedia = serverMedia.filter(
      (m) => m.cardId === card.id || (!m.cardId && idx === 0)
    );
    const existingStaged = Array.isArray(card.stagedMedia) ? card.stagedMedia : [];
    const mergedMap = new Map();

    for (const item of existingStaged) {
      mergedMap.set(item.id, {
        ...item,
        originalFilename: item.originalFilename || '',
        fileSizeBytes: item.fileSizeBytes || 0,
        previewUrl: item.previewUrl || ''
      });
    }
    for (const m of cardMedia) {
      const prev = mergedMap.get(m.mediaId);
      mergedMap.set(m.mediaId, {
        id: m.mediaId,
        file: prev?.file,
        previewUrl: m.previewUrl || prev?.previewUrl || '',
        roomTag: m.roomTag || prev?.roomTag || 'LIVING_ROOM',
        isCover: typeof m.isCover === 'boolean' ? m.isCover : Boolean(prev?.isCover),
        originalFilename: m.originalFilename || prev?.originalFilename || '',
        fileSizeBytes: m.fileSizeBytes || prev?.fileSizeBytes || 0,
        contentType: m.contentType || prev?.contentType || 'image/jpeg',
        stagingStatus: 'staged'
      });
    }
    result.push({ cardId: card.id, count: mergedMap.size, items: Array.from(mergedMap.values()) });
  }
  return result;
}

function makeStagedItem(id, status, opts = {}) {
  return {
    id,
    stagingStatus: status,
    roomTag: opts.roomTag || 'LIVING_ROOM',
    isCover: opts.isCover ?? false,
    originalFilename: opts.filename || `${id}.webp`,
    fileSizeBytes: opts.size || 26790,
    contentType: opts.contentType || 'image/webp',
    previewUrl: ''
  };
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

describe('P0 Fix — Ghost Media Accumulation Prevention', () => {

  test('Case 1 — staged media is persisted', () => {
    const card = {
      id: 'card-1',
      stagedMedia: [
        makeStagedItem('dm-aaa', 'staged'),
        makeStagedItem('dm-bbb', 'staged'),
      ]
    };
    const serialized = serializeCardForAutosave(card);
    assert.strictEqual(serialized.stagedMedia.length, 2,
      'Both staged items must appear in persisted payload');
    assert.ok(serialized.stagedMedia.every(m => m.id), 'All persisted items must have an ID');
  });

  test('Case 2 — staging media is NOT persisted', () => {
    const card = {
      id: 'card-1',
      stagedMedia: [
        makeStagedItem('dm-aaa', 'staged'),
        makeStagedItem('dm-ghost-1', 'staging'),
        makeStagedItem('dm-ghost-2', 'staging'),
      ]
    };
    const serialized = serializeCardForAutosave(card);
    assert.strictEqual(serialized.stagedMedia.length, 1,
      'Only the staged item may be persisted; staging items are ghost candidates');
    assert.strictEqual(serialized.stagedMedia[0].id, 'dm-aaa');
    const ids = serialized.stagedMedia.map(m => m.id);
    assert.ok(!ids.includes('dm-ghost-1'), 'dm-ghost-1 must not be persisted');
    assert.ok(!ids.includes('dm-ghost-2'), 'dm-ghost-2 must not be persisted');
  });

  test('Case 3 — failed media is NOT persisted', () => {
    const card = {
      id: 'card-1',
      stagedMedia: [
        makeStagedItem('dm-aaa', 'staged'),
        makeStagedItem('dm-failed-1', 'failed'),
        makeStagedItem('dm-failed-2', 'failed'),
      ]
    };
    const serialized = serializeCardForAutosave(card);
    assert.strictEqual(serialized.stagedMedia.length, 1,
      'Only the staged item must appear; failed items are excluded');
    assert.strictEqual(serialized.stagedMedia[0].id, 'dm-aaa');
  });

  test('Case 4 — realistic 3-card batch persists 4/4/3=11, not 4/4/7=15', () => {
    const p1 = {
      id: 'staged-0-1789973920408', sector: 'Vijay Nagar',
      stagedMedia: [
        makeStagedItem('dm-79o7nxq4muaxa7pm_0', 'staged', { isCover: true,  roomTag: 'LIVING_ROOM' }),
        makeStagedItem('dm-4hb3a55jmuaxa7po_3', 'staged', { roomTag: 'BATHROOM' }),
        makeStagedItem('dm-8wisnq09muaxa7pn_1', 'staged', { roomTag: 'BEDROOM' }),
        makeStagedItem('dm-nbc7kneymuaxa7po_2', 'staged', { roomTag: 'KITCHEN' }),
      ]
    };
    const p2 = {
      id: 'staged-1-1789973920409', sector: 'Nipania',
      stagedMedia: [
        makeStagedItem('dm-1dqsw7vwmuaxlwi9_0', 'staged', { roomTag: 'LIVING_ROOM', contentType: 'video/mp4', filename: 'WhatsApp_Video.mp4' }),
        makeStagedItem('dm-kvoeq9ztmuaxn0wy_2', 'staged', { roomTag: 'BATHROOM' }),
        makeStagedItem('dm-6etf3la6muaxn0wx_1', 'staged', { roomTag: 'KITCHEN' }),
        makeStagedItem('dm-db0xjqm1muaxn0wx_0', 'staged', { isCover: true, roomTag: 'BEDROOM' }),
      ]
    };
    const p3 = {
      id: 'staged-2-1789973920409', sector: 'Scheme 140',
      stagedMedia: [
        // 4 ghost entries (the actual failed uploads from the incident)
        makeStagedItem('dm-i27jgyh5muax8gr3_0', 'staging', { roomTag: 'LIVING_ROOM' }),
        makeStagedItem('dm-c4ilwt5emuax8gr4_1', 'staging', { roomTag: 'BEDROOM' }),
        makeStagedItem('dm-040rjkiomuax8gr4_2', 'failed',  { roomTag: 'KITCHEN' }),
        makeStagedItem('dm-i2n6696zmuax8gr4_3', 'failed',  { roomTag: 'BATHROOM' }),
        // 3 real staged items
        makeStagedItem('dm-j4tedz5vmuaxosli_1', 'staged', { roomTag: 'BEDROOM' }),
        makeStagedItem('dm-yb5g7x6mmuaxoslg_0', 'staged', { isCover: true, roomTag: 'LIVING_ROOM' }),
        makeStagedItem('dm-rb4bdsommuaxosli_2', 'staged', { roomTag: 'KITCHEN' }),
      ]
    };

    const serializedCards = [p1, p2, p3].map(serializeCardForAutosave);
    const [p1Count, p2Count, p3Count] = serializedCards.map(c => c.stagedMedia.length);
    const total = p1Count + p2Count + p3Count;

    assert.strictEqual(p1Count, 4, `P1 must have 4 persisted items, got ${p1Count}`);
    assert.strictEqual(p2Count, 4, `P2 must have 4 persisted items, got ${p2Count}`);
    assert.strictEqual(p3Count, 3, `P3 must have exactly 3 persisted items (not 7), got ${p3Count}`);
    assert.strictEqual(total,  11, `Total must be 11, got ${total}`);

    const p3Ids = serializedCards[2].stagedMedia.map(m => m.id);
    for (const ghostId of ['dm-i27jgyh5muax8gr3_0', 'dm-c4ilwt5emuax8gr4_1',
                            'dm-040rjkiomuax8gr4_2', 'dm-i2n6696zmuax8gr4_3']) {
      assert.ok(!p3Ids.includes(ghostId), `Ghost ID ${ghostId} must NOT appear in P3 payload`);
    }
    for (const realId of ['dm-j4tedz5vmuaxosli_1', 'dm-yb5g7x6mmuaxoslg_0', 'dm-rb4bdsommuaxosli_2']) {
      assert.ok(p3Ids.includes(realId), `Real staged ID ${realId} must appear in P3 payload`);
    }
  });

  test('Case 5 — hard-refresh restore from clean payload: 4/4/3=11, no ghosts, valid previews', () => {
    const cleanPayloadCards = [
      {
        id: 'staged-0-1789973920408',
        stagedMedia: [
          { id: 'dm-79o7nxq4muaxa7pm_0', roomTag: 'LIVING_ROOM', isCover: true,  originalFilename: 'images__1_.webp', fileSizeBytes: 26790, contentType: 'image/webp' },
          { id: 'dm-4hb3a55jmuaxa7po_3', roomTag: 'BATHROOM',    isCover: false, originalFilename: 'images.webp',     fileSizeBytes: 10848, contentType: 'image/webp' },
          { id: 'dm-8wisnq09muaxa7pn_1', roomTag: 'BEDROOM',     isCover: false, originalFilename: 'images__2_.webp', fileSizeBytes: 42828, contentType: 'image/webp' },
          { id: 'dm-nbc7kneymuaxa7po_2', roomTag: 'KITCHEN',     isCover: false, originalFilename: 'images__3_.webp', fileSizeBytes: 8236,  contentType: 'image/webp' },
        ]
      },
      {
        id: 'staged-1-1789973920409',
        stagedMedia: [
          { id: 'dm-1dqsw7vwmuaxlwi9_0', roomTag: 'LIVING_ROOM', isCover: false, originalFilename: 'WhatsApp_Video.mp4', fileSizeBytes: 3268024, contentType: 'video/mp4' },
          { id: 'dm-kvoeq9ztmuaxn0wy_2', roomTag: 'BATHROOM',    isCover: false, originalFilename: 'images__4_.webp',    fileSizeBytes: 4502,    contentType: 'image/webp' },
          { id: 'dm-6etf3la6muaxn0wx_1', roomTag: 'KITCHEN',     isCover: false, originalFilename: 'images__2_.webp',   fileSizeBytes: 42828,   contentType: 'image/webp' },
          { id: 'dm-db0xjqm1muaxn0wx_0', roomTag: 'BEDROOM',     isCover: true,  originalFilename: 'images__1_.webp',   fileSizeBytes: 26790,   contentType: 'image/webp' },
        ]
      },
      {
        id: 'staged-2-1789973920409',
        stagedMedia: [
          { id: 'dm-j4tedz5vmuaxosli_1', roomTag: 'BEDROOM',     isCover: false, originalFilename: 'images__2_.webp', fileSizeBytes: 10840, contentType: 'image/webp' },
          { id: 'dm-yb5g7x6mmuaxoslg_0', roomTag: 'LIVING_ROOM', isCover: true,  originalFilename: 'images__1_.webp', fileSizeBytes: 37868, contentType: 'image/webp' },
          { id: 'dm-rb4bdsommuaxosli_2', roomTag: 'KITCHEN',     isCover: false, originalFilename: 'images.webp',     fileSizeBytes: 21556, contentType: 'image/webp' },
        ]
      }
    ];

    const serverMedia = [
      { mediaId: 'dm-79o7nxq4muaxa7pm_0', cardId: 'staged-0-1789973920408', roomTag: 'LIVING_ROOM', isCover: true,  originalFilename: 'images__1_.webp',   fileSizeBytes: 26790,   contentType: 'image/webp', previewUrl: '/api/v1/admin/drafts/x/media/dm-79o7nxq4muaxa7pm_0' },
      { mediaId: 'dm-4hb3a55jmuaxa7po_3', cardId: 'staged-0-1789973920408', roomTag: 'BATHROOM',    isCover: false, originalFilename: 'images.webp',        fileSizeBytes: 10848,   contentType: 'image/webp', previewUrl: '/api/v1/admin/drafts/x/media/dm-4hb3a55jmuaxa7po_3' },
      { mediaId: 'dm-8wisnq09muaxa7pn_1', cardId: 'staged-0-1789973920408', roomTag: 'BEDROOM',     isCover: false, originalFilename: 'images__2_.webp',   fileSizeBytes: 42828,   contentType: 'image/webp', previewUrl: '/api/v1/admin/drafts/x/media/dm-8wisnq09muaxa7pn_1' },
      { mediaId: 'dm-nbc7kneymuaxa7po_2', cardId: 'staged-0-1789973920408', roomTag: 'KITCHEN',     isCover: false, originalFilename: 'images__3_.webp',   fileSizeBytes: 8236,    contentType: 'image/webp', previewUrl: '/api/v1/admin/drafts/x/media/dm-nbc7kneymuaxa7po_2' },
      { mediaId: 'dm-1dqsw7vwmuaxlwi9_0', cardId: 'staged-1-1789973920409', roomTag: 'LIVING_ROOM', isCover: false, originalFilename: 'WhatsApp_Video.mp4', fileSizeBytes: 3268024, contentType: 'video/mp4',  previewUrl: '/api/v1/admin/drafts/x/media/dm-1dqsw7vwmuaxlwi9_0' },
      { mediaId: 'dm-kvoeq9ztmuaxn0wy_2', cardId: 'staged-1-1789973920409', roomTag: 'BATHROOM',    isCover: false, originalFilename: 'images__4_.webp',   fileSizeBytes: 4502,    contentType: 'image/webp', previewUrl: '/api/v1/admin/drafts/x/media/dm-kvoeq9ztmuaxn0wy_2' },
      { mediaId: 'dm-6etf3la6muaxn0wx_1', cardId: 'staged-1-1789973920409', roomTag: 'KITCHEN',     isCover: false, originalFilename: 'images__2_.webp',   fileSizeBytes: 42828,   contentType: 'image/webp', previewUrl: '/api/v1/admin/drafts/x/media/dm-6etf3la6muaxn0wx_1' },
      { mediaId: 'dm-db0xjqm1muaxn0wx_0', cardId: 'staged-1-1789973920409', roomTag: 'BEDROOM',     isCover: true,  originalFilename: 'images__1_.webp',   fileSizeBytes: 26790,   contentType: 'image/webp', previewUrl: '/api/v1/admin/drafts/x/media/dm-db0xjqm1muaxn0wx_0' },
      { mediaId: 'dm-j4tedz5vmuaxosli_1', cardId: 'staged-2-1789973920409', roomTag: 'BEDROOM',     isCover: false, originalFilename: 'images__2_.webp',   fileSizeBytes: 10840,   contentType: 'image/webp', previewUrl: '/api/v1/admin/drafts/x/media/dm-j4tedz5vmuaxosli_1' },
      { mediaId: 'dm-yb5g7x6mmuaxoslg_0', cardId: 'staged-2-1789973920409', roomTag: 'LIVING_ROOM', isCover: true,  originalFilename: 'images__1_.webp',   fileSizeBytes: 37868,   contentType: 'image/webp', previewUrl: '/api/v1/admin/drafts/x/media/dm-yb5g7x6mmuaxoslg_0' },
      { mediaId: 'dm-rb4bdsommuaxosli_2', cardId: 'staged-2-1789973920409', roomTag: 'KITCHEN',     isCover: false, originalFilename: 'images.webp',        fileSizeBytes: 21556,   contentType: 'image/webp', previewUrl: '/api/v1/admin/drafts/x/media/dm-rb4bdsommuaxosli_2' },
    ];

    const restored = simulateRestoration(cleanPayloadCards, serverMedia);

    assert.strictEqual(restored[0].count, 4, `P1 restored: expected 4, got ${restored[0].count}`);
    assert.strictEqual(restored[1].count, 4, `P2 restored: expected 4, got ${restored[1].count}`);
    assert.strictEqual(restored[2].count, 3, `P3 restored: expected 3 (no ghosts), got ${restored[2].count}`);
    assert.strictEqual(restored.reduce((s, c) => s + c.count, 0), 11, 'Total restored must be 11');

    const allIds = restored.flatMap(c => c.items.map(m => m.id));
    for (const ghostId of ['dm-i27jgyh5muax8gr3_0', 'dm-c4ilwt5emuax8gr4_1',
                            'dm-040rjkiomuax8gr4_2', 'dm-i2n6696zmuax8gr4_3']) {
      assert.ok(!allIds.includes(ghostId), `Ghost ${ghostId} must not appear after restore`);
    }
    for (const item of restored[2].items) {
      assert.ok(item.previewUrl && item.previewUrl.length > 0,
        `P3 item ${item.id} must have a valid previewUrl from server overlay`);
    }
  });

  test('Case 6 — staging→staged transition: subsequent autosave captures the newly staged item', () => {
    // Autosave A: item still in-flight
    const cardA = {
      id: 'card-1',
      stagedMedia: [
        makeStagedItem('dm-existing', 'staged'),
        makeStagedItem('dm-inflight', 'staging'),
      ]
    };
    const serializedA = serializeCardForAutosave(cardA);
    assert.strictEqual(serializedA.stagedMedia.length, 1, 'Autosave A: 1 item (in-flight excluded)');
    assert.strictEqual(serializedA.stagedMedia[0].id, 'dm-existing');
    assert.ok(!serializedA.stagedMedia.some(m => m.id === 'dm-inflight'),
      'Autosave A: in-flight item must NOT be persisted');

    // B2 upload succeeds → setStagedCards updates → useEffect re-fires → Autosave B
    const cardB = {
      ...cardA,
      stagedMedia: cardA.stagedMedia.map(m =>
        m.id === 'dm-inflight' ? { ...m, stagingStatus: 'staged' } : m
      )
    };
    const serializedB = serializeCardForAutosave(cardB);
    assert.strictEqual(serializedB.stagedMedia.length, 2,
      'Autosave B: both items must now be persisted');
    assert.ok(serializedB.stagedMedia.some(m => m.id === 'dm-existing'), 'Existing item preserved');
    assert.ok(serializedB.stagedMedia.some(m => m.id === 'dm-inflight'), 'Newly staged item included');

    const newItem = serializedB.stagedMedia.find(m => m.id === 'dm-inflight');
    assert.ok(newItem, 'Newly staged item must be in serialized output');
    assert.ok(newItem.roomTag, 'Must have roomTag');
    assert.ok('isCover' in newItem, 'Must have isCover');
    assert.ok(newItem.originalFilename, 'Must have originalFilename');
    assert.ok(typeof newItem.fileSizeBytes === 'number', 'Must have fileSizeBytes');
    assert.ok(!('stagingStatus' in newItem),
      'stagingStatus must NOT be persisted in payload (session-transient)');
  });

  test('Edge — undefined stagingStatus items are excluded (no confirmed durable staging)', () => {
    const card = {
      id: 'card-1',
      stagedMedia: [
        makeStagedItem('dm-good', 'staged'),
        {
          id: 'dm-legacy-undefined',
          roomTag: 'LIVING_ROOM', isCover: false,
          originalFilename: 'old.webp', fileSizeBytes: 1000, contentType: 'image/webp',
          previewUrl: ''
          // stagingStatus: undefined — restored from old payload without status
        }
      ]
    };
    const serialized = serializeCardForAutosave(card);
    assert.strictEqual(serialized.stagedMedia.length, 1,
      'Items with undefined stagingStatus must be excluded');
    assert.strictEqual(serialized.stagedMedia[0].id, 'dm-good');
  });

});
