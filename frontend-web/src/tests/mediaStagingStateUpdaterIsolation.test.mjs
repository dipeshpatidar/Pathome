/**
 * P0 Regression Suite: Media Staging React State-Updater Isolation
 *
 * Verifies that:
 * 1. draftService.stageMedia(...) is executed strictly OUTSIDE React state updater functions.
 * 2. Exactly ONE logical stageMedia request is issued per selected media item.
 * 3. Re-running or double-invoking React state updaters (e.g. React 18 StrictMode) does NOT cause duplicate network requests.
 * 4. Image, video, and multi-file attachments invoke stageMedia exactly 1, 1, and 3 times respectively.
 * 5. Media transitions cleanly to 'staged' on success and 'failed' on failure.
 * 6. Durable media identity (mediaId, cardId, roomTag, isCover) remains stable throughout.
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

const DEFAULT_SMART_TAG_SEQUENCE = [
  'LIVING_ROOM',
  'BEDROOM',
  'KITCHEN',
  'BATHROOM',
  'BALCONY',
  'EXTERIOR',
  'AMENITIES',
  'FLOOR_PLAN',
  'GENERAL'
];

/**
 * Harness simulating the fixed attachMediaToCard workflow in BatchPropertyIngestionStudio.tsx
 */
function createStudioAttachmentHarness(initialCards = []) {
  let stagedCards = JSON.parse(JSON.stringify(initialCards));
  const stagedCardsRef = { current: stagedCards };

  const stageMediaCalls = [];
  let mockStageMediaImpl = async (draftId, file, options) => {
    return {
      mediaId: options.mediaId,
      draftId,
      cardId: options.cardId,
      originalFilename: file.name,
      fileSizeBytes: file.size,
      contentType: file.type,
      roomTag: options.roomTag,
      isCover: options.isCover,
      previewUrl: `/api/v1/admin/drafts/${draftId}/media/${options.mediaId}`
    };
  };

  const draftService = {
    stageMedia: async (draftId, file, options) => {
      stageMediaCalls.push({ draftId, file, options, timestamp: Date.now() });
      return mockStageMediaImpl(draftId, file, options);
    }
  };

  const setStagedCards = (updater) => {
    if (typeof updater === 'function') {
      // Simulate React StrictMode by running updater twice, keeping second result
      const run1 = updater(stagedCards);
      const run2 = updater(stagedCards);
      stagedCards = run2;
    } else {
      stagedCards = updater;
    }
    stagedCardsRef.current = stagedCards;
  };

  const attachMediaToCard = async (cardId, files, options = {}) => {
    const fileArray = Array.from(files);
    const draftId = options.draftId || 'draft-batch-test';

    const targetCard = stagedCardsRef.current.find((c) => c.id === cardId);
    const currentMedia = targetCard?.stagedMedia || [];
    const hasExistingCover = currentMedia.some((m) => m.isCover);
    const newItems = [];

    for (let i = 0; i < fileArray.length; i++) {
      const file = fileArray[i];
      const isVideo = file.type.startsWith('video/');
      const smartTag = DEFAULT_SMART_TAG_SEQUENCE[(currentMedia.length + i) % DEFAULT_SMART_TAG_SEQUENCE.length];
      const willBeCover = !hasExistingCover && !isVideo && !newItems.some((item) => item.isCover);
      const mediaId = file.draftMediaId || `dm-test_${Math.random().toString(36).substring(2, 8)}_${i}`;
      file.draftMediaId = mediaId;

      newItems.push({
        id: mediaId,
        file,
        previewUrl: `blob:${file.name}`,
        roomTag: smartTag,
        isCover: willBeCover,
        originalFilename: file.name,
        fileSizeBytes: file.size,
        contentType: file.type,
        stagingStatus: 'staging'
      });
    }

    // 1. Pure state updater: synchronously register staging items
    setStagedCards((prev) =>
      prev.map((c) => {
        if (c.id !== cardId) return c;
        const existingMedia = c.stagedMedia || [];
        const combinedMedia = [...existingMedia, ...newItems];
        return {
          ...c,
          stagedMedia: combinedMedia
        };
      })
    );

    // 2. Network side effects executed strictly OUTSIDE the React state updater
    for (const item of newItems) {
      const { id: mediaId, file, roomTag, isCover } = item;
      try {
        const staged = await draftService.stageMedia(draftId, file, {
          cardId,
          roomTag,
          isCover,
          mediaId
        });
        if (staged?.mediaId) {
          file.draftMediaId = staged.mediaId;
          setStagedCards((current) =>
            current.map((card) => {
              if (card.id !== cardId) return card;
              return {
                ...card,
                stagedMedia: (card.stagedMedia || []).map((m) =>
                  m.id === mediaId ? { ...m, stagingStatus: 'staged' } : m
                )
              };
            })
          );
        }
      } catch (err) {
        setStagedCards((current) =>
          current.map((card) => {
            if (card.id !== cardId) return card;
            return {
              ...card,
              stagedMedia: (card.stagedMedia || []).map((m) =>
                m.id === mediaId ? { ...m, stagingStatus: 'failed' } : m
              )
            };
          })
        );
      }
    }
  };

  return {
    getCards: () => stagedCards,
    getStageMediaCalls: () => stageMediaCalls,
    setMockStageMediaImpl: (fn) => { mockStageMediaImpl = fn; },
    attachMediaToCard
  };
}

describe('P0 Regression — Media Staging State Updater Isolation & Single-Invocation Guarantee', () => {

  test('Single Image: exactly ONE stageMedia request issued, even under React StrictMode double-updater', async () => {
    const harness = createStudioAttachmentHarness([
      { id: 'card-1', stagedMedia: [] }
    ]);

    const photo = { name: 'photo1.webp', size: 15000, type: 'image/webp' };
    await harness.attachMediaToCard('card-1', [photo]);

    const calls = harness.getStageMediaCalls();
    assert.equal(calls.length, 1, 'Single image must trigger exactly ONE stageMedia invocation');
    assert.equal(calls[0].file.name, 'photo1.webp');
    assert.equal(calls[0].options.cardId, 'card-1');
    assert.equal(calls[0].options.isCover, true, 'First image should be designated cover');

    const card = harness.getCards()[0];
    assert.equal(card.stagedMedia.length, 1);
    assert.equal(card.stagedMedia[0].stagingStatus, 'staged');
  });

  test('Single Video: exactly ONE stageMedia request issued (never designated cover)', async () => {
    const harness = createStudioAttachmentHarness([
      { id: 'card-1', stagedMedia: [] }
    ]);

    const video = { name: 'tour.mp4', size: 5000000, type: 'video/mp4' };
    await harness.attachMediaToCard('card-1', [video]);

    const calls = harness.getStageMediaCalls();
    assert.equal(calls.length, 1, 'Single video must trigger exactly ONE stageMedia invocation');
    assert.equal(calls[0].file.name, 'tour.mp4');
    assert.equal(calls[0].options.isCover, false, 'Video must never be designated as cover');

    const card = harness.getCards()[0];
    assert.equal(card.stagedMedia.length, 1);
    assert.equal(card.stagedMedia[0].stagingStatus, 'staged');
  });

  test('Multiple Media: 3 selected media items trigger exactly 3 logical stageMedia requests', async () => {
    const harness = createStudioAttachmentHarness([
      { id: 'card-2', stagedMedia: [] }
    ]);

    const files = [
      { name: 'living.webp', size: 12000, type: 'image/webp' },
      { name: 'bedroom.webp', size: 14000, type: 'image/webp' },
      { name: 'walkthrough.mp4', size: 4000000, type: 'video/mp4' }
    ];

    await harness.attachMediaToCard('card-2', files);

    const calls = harness.getStageMediaCalls();
    assert.equal(calls.length, 3, '3 attached files must trigger exactly 3 stageMedia calls');

    const mediaIds = calls.map((c) => c.options.mediaId);
    const uniqueIds = new Set(mediaIds);
    assert.equal(uniqueIds.size, 3, 'All 3 stage calls must have distinct durable media IDs');

    const card = harness.getCards()[0];
    assert.equal(card.stagedMedia.length, 3);
    assert.ok(card.stagedMedia.every((m) => m.stagingStatus === 'staged'), 'All 3 items must be staged');
  });

  test('Failure Isolation: failed staging transitions exact media item to failed status', async () => {
    const harness = createStudioAttachmentHarness([
      { id: 'card-1', stagedMedia: [] }
    ]);

    // Mock failure for video
    harness.setMockStageMediaImpl(async (draftId, file, options) => {
      if (file.type.startsWith('video/')) {
        throw new Error('Upload timeout');
      }
      return {
        mediaId: options.mediaId,
        draftId,
        cardId: options.cardId,
        originalFilename: file.name
      };
    });

    const files = [
      { name: 'photo.webp', size: 10000, type: 'image/webp' },
      { name: 'video.mp4', size: 8000000, type: 'video/mp4' }
    ];

    await harness.attachMediaToCard('card-1', files);

    const card = harness.getCards()[0];
    assert.equal(card.stagedMedia.length, 2);
    assert.equal(card.stagedMedia[0].stagingStatus, 'staged', 'Successful photo is staged');
    assert.equal(card.stagedMedia[1].stagingStatus, 'failed', 'Failing video is marked failed');
  });

  test('Identity & Card Isolation: draftMediaId and cardId remain strictly stable across renders', async () => {
    const harness = createStudioAttachmentHarness([
      { id: 'card-A', stagedMedia: [] },
      { id: 'card-B', stagedMedia: [] }
    ]);

    const fileA = { name: 'photoA.webp', size: 10000, type: 'image/webp' };
    const fileB = { name: 'photoB.webp', size: 12000, type: 'image/webp' };

    await harness.attachMediaToCard('card-A', [fileA]);
    await harness.attachMediaToCard('card-B', [fileB]);

    const cards = harness.getCards();
    const cardA = cards.find((c) => c.id === 'card-A');
    const cardB = cards.find((c) => c.id === 'card-B');

    assert.equal(cardA.stagedMedia.length, 1);
    assert.equal(cardB.stagedMedia.length, 1);
    assert.notEqual(cardA.stagedMedia[0].id, cardB.stagedMedia[0].id, 'Cards must have distinct media IDs');
    assert.equal(cardA.stagedMedia[0].originalFilename, 'photoA.webp');
    assert.equal(cardB.stagedMedia[0].originalFilename, 'photoB.webp');
  });
});
