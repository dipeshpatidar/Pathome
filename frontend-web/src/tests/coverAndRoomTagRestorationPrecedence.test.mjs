/**
 * Regression tests for P0 fix: Cover photo and room tag restoration precedence.
 *
 * Problem:
 *   When users modify cover photos or room tags in the Batch Ingestion Studio,
 *   the changes are saved into `property_upload_drafts.payload.stagedCards[].stagedMedia[]`.
 *   The backend `property_draft_media` DB table only records initial upload-time values.
 *
 *   Previously, `handleRestoreBatchDraft` gave `serverMedia` (from `property_draft_media`)
 *   precedence over the saved payload:
 *     roomTag: m.roomTag || prev?.roomTag || 'LIVING_ROOM'
 *     isCover: typeof m.isCover === 'boolean' ? m.isCover : Boolean(prev?.isCover)
 *
 *   Because `m.isCover` is always a boolean and `m.roomTag` is always a non-empty string,
 *   this unconditionally wiped out user-edited cover selections and room tags upon hard refresh!
 *
 * Fix:
 *   Saved payload metadata (`prev`) is authoritative and takes precedence over initial server staging:
 *     roomTag: prev?.roomTag || m.roomTag || 'LIVING_ROOM'
 *     isCover: !isVideo && (typeof prev?.isCover === 'boolean' ? prev.isCover : (typeof m.isCover === 'boolean' ? m.isCover : false))
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

// ---------------------------------------------------------------------------
// Helpers mirroring the exact logic in BatchPropertyIngestionStudio.tsx
// ---------------------------------------------------------------------------

function isMediaVideo(item) {
  if (item.file?.type) return item.file.type.startsWith('video/');
  if (item.contentType) return item.contentType.startsWith('video/');
  if (item.originalFilename) return /\.(mp4|mov|webm|m4v|3gp)$/i.test(item.originalFilename);
  return false;
}

function restoreBatchDraftCards(payloadCards, serverMedia) {
  return payloadCards.map((card, idx) => {
    const cardMedia = serverMedia.filter((m) => m.cardId === card.id || (!m.cardId && idx === 0));
    const existingStaged = Array.isArray(card.stagedMedia) ? card.stagedMedia : [];

    const mergedMediaMap = new Map();

    // 1. Add saved staged media metadata from payload
    existingStaged.forEach((item) => {
      mergedMediaMap.set(item.id, {
        ...item,
        originalFilename: item.originalFilename || item.file?.name || '',
        fileSizeBytes: item.fileSizeBytes || item.file?.size || 0,
        previewUrl: item.previewUrl || ''
      });
    });

    // 2. Overlay server media records with payload-authoritative metadata precedence
    cardMedia.forEach((m) => {
      const prev = mergedMediaMap.get(m.mediaId);
      const isVideo = isMediaVideo({
        id: m.mediaId,
        file: prev?.file,
        previewUrl: '',
        roomTag: prev?.roomTag || m.roomTag || 'LIVING_ROOM',
        isCover: false,
        originalFilename: m.originalFilename || prev?.originalFilename || '',
        contentType: m.contentType || prev?.contentType || ''
      });
      const candidateCover = typeof prev?.isCover === 'boolean'
        ? prev.isCover
        : typeof m.isCover === 'boolean'
          ? m.isCover
          : false;

      mergedMediaMap.set(m.mediaId, {
        id: m.mediaId,
        file: prev?.file,
        previewUrl: m.previewUrl || prev?.previewUrl || '',
        roomTag: prev?.roomTag || m.roomTag || 'LIVING_ROOM',
        isCover: !isVideo && candidateCover,
        originalFilename: m.originalFilename || prev?.originalFilename || '',
        fileSizeBytes: m.fileSizeBytes || prev?.fileSizeBytes || 0,
        contentType: m.contentType || prev?.contentType || 'image/jpeg',
        stagingStatus: 'staged'
      });
    });

    const stagedMedia = Array.from(mergedMediaMap.values());
    return {
      ...card,
      stagedMedia,
      localPhotos: stagedMedia.map((m) => m.file).filter(Boolean),
      localPhotoPreviews: stagedMedia.map((m) => m.previewUrl).filter(Boolean)
    };
  });
}

describe('P0 Fix — Cover Photo & Room Tag Restoration Precedence Suite', () => {

  test('1. Core Restoration Precedence: User-edited payload cover and tags survive restore over initial server metadata', () => {
    // Initial server staging state (from initial upload into property_draft_media)
    const serverMedia = [
      { mediaId: 'media-A', cardId: 'card-1', originalFilename: 'photoA.jpg', contentType: 'image/jpeg', roomTag: 'LIVING_ROOM', isCover: true, fileSizeBytes: 50000, previewUrl: '/api/preview/A' },
      { mediaId: 'media-B', cardId: 'card-1', originalFilename: 'photoB.jpg', contentType: 'image/jpeg', roomTag: 'BEDROOM', isCover: false, fileSizeBytes: 60000, previewUrl: '/api/preview/B' }
    ];

    // User subsequently changed cover to media-B and edited room tags in the UI, saved into draft payload
    const payloadCards = [
      {
        id: 'card-1',
        title: '2 BHK Apartment in Vijay Nagar',
        stagedMedia: [
          { id: 'media-A', roomTag: 'KITCHEN', isCover: false, originalFilename: 'photoA.jpg', contentType: 'image/jpeg', fileSizeBytes: 50000 },
          { id: 'media-B', roomTag: 'BALCONY', isCover: true, originalFilename: 'photoB.jpg', contentType: 'image/jpeg', fileSizeBytes: 60000 }
        ]
      }
    ];

    const restoredCards = restoreBatchDraftCards(payloadCards, serverMedia);
    assert.equal(restoredCards.length, 1);

    const card = restoredCards[0];
    assert.equal(card.stagedMedia.length, 2);

    const itemA = card.stagedMedia.find((m) => m.id === 'media-A');
    const itemB = card.stagedMedia.find((m) => m.id === 'media-B');

    // Prove user-edited payload tags & cover took precedence
    assert.equal(itemA.roomTag, 'KITCHEN', 'media-A roomTag must be KITCHEN (from payload), not LIVING_ROOM (from server)');
    assert.equal(itemA.isCover, false, 'media-A isCover must be false (from payload), not true (from server)');

    assert.equal(itemB.roomTag, 'BALCONY', 'media-B roomTag must be BALCONY (from payload), not BEDROOM (from server)');
    assert.equal(itemB.isCover, true, 'media-B isCover must be true (from payload), not false (from server)');
  });

  test('2. Multi-Property Card Isolation with Duplicate Filenames', () => {
    // Both P1 and P2 have identical filenames ('images__1_.webp', 'images__2_.webp')
    const serverMedia = [
      // P1 initial upload
      { mediaId: 'dm-p1-a', cardId: 'card-1', originalFilename: 'images__1_.webp', contentType: 'image/webp', roomTag: 'LIVING_ROOM', isCover: true, fileSizeBytes: 20000 },
      { mediaId: 'dm-p1-b', cardId: 'card-1', originalFilename: 'images__2_.webp', contentType: 'image/webp', roomTag: 'BEDROOM', isCover: false, fileSizeBytes: 25000 },
      // P2 initial upload
      { mediaId: 'dm-p2-a', cardId: 'card-2', originalFilename: 'images__1_.webp', contentType: 'image/webp', roomTag: 'LIVING_ROOM', isCover: true, fileSizeBytes: 20000 },
      { mediaId: 'dm-p2-b', cardId: 'card-2', originalFilename: 'images__2_.webp', contentType: 'image/webp', roomTag: 'BEDROOM', isCover: false, fileSizeBytes: 25000 }
    ];

    // User customized both cards independently
    const payloadCards = [
      {
        id: 'card-1',
        title: 'P1 - Bhawarkua',
        stagedMedia: [
          { id: 'dm-p1-a', roomTag: 'BEDROOM', isCover: false, originalFilename: 'images__1_.webp', contentType: 'image/webp' },
          { id: 'dm-p1-b', roomTag: 'BALCONY', isCover: true, originalFilename: 'images__2_.webp', contentType: 'image/webp' }
        ]
      },
      {
        id: 'card-2',
        title: 'P2 - Rau',
        stagedMedia: [
          { id: 'dm-p2-a', roomTag: 'KITCHEN', isCover: true, originalFilename: 'images__1_.webp', contentType: 'image/webp' },
          { id: 'dm-p2-b', roomTag: 'MASTER_BEDROOM', isCover: false, originalFilename: 'images__2_.webp', contentType: 'image/webp' }
        ]
      }
    ];

    const restoredCards = restoreBatchDraftCards(payloadCards, serverMedia);
    assert.equal(restoredCards.length, 2);

    // Verify P1
    const p1 = restoredCards.find((c) => c.id === 'card-1');
    const p1A = p1.stagedMedia.find((m) => m.id === 'dm-p1-a');
    const p1B = p1.stagedMedia.find((m) => m.id === 'dm-p1-b');
    assert.equal(p1A.roomTag, 'BEDROOM');
    assert.equal(p1A.isCover, false);
    assert.equal(p1B.roomTag, 'BALCONY');
    assert.equal(p1B.isCover, true);

    // Verify P2
    const p2 = restoredCards.find((c) => c.id === 'card-2');
    const p2A = p2.stagedMedia.find((m) => m.id === 'dm-p2-a');
    const p2B = p2.stagedMedia.find((m) => m.id === 'dm-p2-b');
    assert.equal(p2A.roomTag, 'KITCHEN');
    assert.equal(p2A.isCover, true);
    assert.equal(p2B.roomTag, 'MASTER_BEDROOM');
    assert.equal(p2B.isCover, false);

    // Card-level isolation: P1 has exactly 1 cover, P2 has exactly 1 cover
    assert.equal(p1.stagedMedia.filter((m) => m.isCover).length, 1);
    assert.equal(p2.stagedMedia.filter((m) => m.isCover).length, 1);
  });

  test('3. Fallback & Backward Compatibility: Server media provides authoritative fallback when payload lacks roomTag/isCover', () => {
    const serverMedia = [
      { mediaId: 'dm-legacy-1', cardId: 'card-legacy', originalFilename: 'legacy1.jpg', contentType: 'image/jpeg', roomTag: 'MASTER_BEDROOM', isCover: true },
      { mediaId: 'dm-legacy-2', cardId: 'card-legacy', originalFilename: 'legacy2.jpg', contentType: 'image/jpeg', roomTag: 'KITCHEN', isCover: false }
    ];

    // Payload from legacy draft where stagedMedia has no roomTag/isCover or is completely omitted
    const payloadCards = [
      {
        id: 'card-legacy',
        title: 'Legacy Draft Without Tags in Payload',
        stagedMedia: [
          { id: 'dm-legacy-1', originalFilename: 'legacy1.jpg' },
          { id: 'dm-legacy-2', originalFilename: 'legacy2.jpg' }
        ]
      }
    ];

    const restoredCards = restoreBatchDraftCards(payloadCards, serverMedia);
    const card = restoredCards[0];
    assert.equal(card.stagedMedia.length, 2);

    const m1 = card.stagedMedia.find((m) => m.id === 'dm-legacy-1');
    const m2 = card.stagedMedia.find((m) => m.id === 'dm-legacy-2');

    // Server values must be used as fallback
    assert.equal(m1.roomTag, 'MASTER_BEDROOM', 'Falls back to server roomTag when payload is undefined');
    assert.equal(m1.isCover, true, 'Falls back to server isCover when payload is undefined');
    assert.equal(m2.roomTag, 'KITCHEN', 'Falls back to server roomTag when payload is undefined');
    assert.equal(m2.isCover, false, 'Falls back to server isCover when payload is undefined');
  });

  test('4. Video Cover Protection Invariant: Video can NEVER become cover even if corrupt payload or server marked it', () => {
    const serverMedia = [
      { mediaId: 'dm-video-1', cardId: 'card-1', originalFilename: 'walkthrough.mp4', contentType: 'video/mp4', roomTag: 'LIVING_ROOM', isCover: false },
      { mediaId: 'dm-image-1', cardId: 'card-1', originalFilename: 'photo1.jpg', contentType: 'image/jpeg', roomTag: 'BEDROOM', isCover: true }
    ];

    // Corrupted payload attempting to set video as cover
    const payloadCards = [
      {
        id: 'card-1',
        title: 'Property With Video',
        stagedMedia: [
          { id: 'dm-video-1', originalFilename: 'walkthrough.mp4', contentType: 'video/mp4', roomTag: 'LIVING_ROOM', isCover: true },
          { id: 'dm-image-1', originalFilename: 'photo1.jpg', contentType: 'image/jpeg', roomTag: 'BEDROOM', isCover: false }
        ]
      }
    ];

    const restoredCards = restoreBatchDraftCards(payloadCards, serverMedia);
    const card = restoredCards[0];

    const videoItem = card.stagedMedia.find((m) => m.id === 'dm-video-1');
    assert.equal(videoItem.isCover, false, 'Video MUST NEVER be cover under any circumstances');
  });

  test('5. Order Preservation: Saved draft payload media order is strictly preserved during restoration', () => {
    const serverMedia = [
      { mediaId: 'm1', cardId: 'c1', originalFilename: '1.jpg', contentType: 'image/jpeg', roomTag: 'LIVING_ROOM', isCover: true },
      { mediaId: 'm2', cardId: 'c1', originalFilename: '2.jpg', contentType: 'image/jpeg', roomTag: 'BEDROOM', isCover: false },
      { mediaId: 'm3', cardId: 'c1', originalFilename: '3.jpg', contentType: 'image/jpeg', roomTag: 'KITCHEN', isCover: false }
    ];

    // User reordered items in payload: m3, m1, m2
    const payloadCards = [
      {
        id: 'c1',
        title: 'Ordered Property',
        stagedMedia: [
          { id: 'm3', roomTag: 'KITCHEN', isCover: false, originalFilename: '3.jpg' },
          { id: 'm1', roomTag: 'LIVING_ROOM', isCover: true, originalFilename: '1.jpg' },
          { id: 'm2', roomTag: 'BEDROOM', isCover: false, originalFilename: '2.jpg' }
        ]
      }
    ];

    const restoredCards = restoreBatchDraftCards(payloadCards, serverMedia);
    const order = restoredCards[0].stagedMedia.map((m) => m.id);
    assert.deepEqual(order, ['m3', 'm1', 'm2'], 'Insertion order from payload must be preserved');
  });

  test('6. Unregistered server media appended gracefully', () => {
    const serverMedia = [
      { mediaId: 'm1', cardId: 'c1', originalFilename: '1.jpg', contentType: 'image/jpeg', roomTag: 'LIVING_ROOM', isCover: true },
      { mediaId: 'm2-late', cardId: 'c1', originalFilename: '2-late.jpg', contentType: 'image/jpeg', roomTag: 'BATHROOM', isCover: false }
    ];

    // Payload only has m1
    const payloadCards = [
      {
        id: 'c1',
        title: 'Property',
        stagedMedia: [
          { id: 'm1', roomTag: 'LIVING_ROOM', isCover: true, originalFilename: '1.jpg' }
        ]
      }
    ];

    const restoredCards = restoreBatchDraftCards(payloadCards, serverMedia);
    assert.equal(restoredCards[0].stagedMedia.length, 2);
    assert.equal(restoredCards[0].stagedMedia[1].id, 'm2-late');
    assert.equal(restoredCards[0].stagedMedia[1].roomTag, 'BATHROOM');
  });
});
