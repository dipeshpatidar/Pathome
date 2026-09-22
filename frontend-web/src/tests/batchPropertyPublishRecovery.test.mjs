import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

describe('P0 Batch Property Publishing + Media Recovery Hardening Suite', () => {
  let mockDrafts;
  let mockDraftMedia;
  let mockListings;
  let mockMediaAssets;
  let cloudinaryUploads;

  // Stable upload request ID function matching propertyService.ts
  function createStableUploadRequestId(propertyId, file, draftMediaId) {
    const effectiveDraftMediaId = draftMediaId || file?.draftMediaId;
    if (effectiveDraftMediaId && typeof effectiveDraftMediaId === 'string' && effectiveDraftMediaId.trim()) {
      const cleanId = effectiveDraftMediaId.trim().replace(/[^a-zA-Z0-9_-]/g, '_');
      return `media-${propertyId}-${cleanId}`;
    }

    if (file) {
      const source = `${propertyId}:${file.name}:${file.size}:${file.lastModified}`;
      let hash = 2166136261;
      for (let index = 0; index < source.length; index += 1) {
        hash ^= source.charCodeAt(index);
        hash = Math.imul(hash, 16777619);
      }
      return `media-${propertyId}-${(hash >>> 0).toString(16)}`;
    }

    return `media-${propertyId}-${Date.now()}`;
  }

  // Safe media helpers matching BatchPropertyIngestionStudio.tsx
  function isMediaVideo(item) {
    if (item.file?.type) return item.file.type.startsWith('video/');
    if (item.contentType) return item.contentType.startsWith('video/');
    if (item.originalFilename) return /\.(mp4|mov|webm|m4v|3gp)$/i.test(item.originalFilename);
    return false;
  }

  function getMediaFilename(item) {
    return item.file?.name || item.originalFilename || 'Media file';
  }

  function getMediaFileSize(item) {
    return item.file?.size ?? item.fileSizeBytes ?? 0;
  }

  function computeCtaTexts(stagedCards) {
    const readyToPublishCount = stagedCards.filter((c) => c.isValid).length;
    const totalCardsCount = stagedCards.length;
    const unconfirmedCount = stagedCards.filter((c) => !c.isValid).length;

    let desktopCta = '';
    if (readyToPublishCount === 0) {
      desktopCta = 'Confirm properties to publish';
    } else if (totalCardsCount === 1) {
      desktopCta = 'Publish 1 property';
    } else if (readyToPublishCount === totalCardsCount) {
      desktopCta = `Publish all ${totalCardsCount} properties`;
    } else {
      desktopCta = `Publish ${readyToPublishCount} of ${totalCardsCount} confirmed properties`;
    }

    let mobileCta = '';
    if (readyToPublishCount === 0) {
      mobileCta = 'Confirm properties';
    } else if (totalCardsCount === 1) {
      mobileCta = 'Publish 1 property';
    } else if (readyToPublishCount === totalCardsCount) {
      mobileCta = `Publish all ${totalCardsCount} properties`;
    } else {
      mobileCta = `Publish ${readyToPublishCount} of ${totalCardsCount} confirmed`;
    }

    let supportingInfo = '';
    if (unconfirmedCount > 0) {
      supportingInfo = `${unconfirmedCount} ${unconfirmedCount === 1 ? 'property still needs' : 'properties still need'} confirmation.`;
    } else {
      supportingInfo = `${totalCardsCount} of ${totalCardsCount} properties confirmed and ready to publish.`;
    }

    return {
      desktopCta,
      mobileCta,
      supportingInfo,
      compactBadge: `${readyToPublishCount}/${totalCardsCount}`
    };
  }

  // Simulated backend PropertyDraftService & PropertyController batch publication
  class MockBatchBackend {
    stageMedia(draftId, cardId, file, options = {}) {
      if (!mockDrafts.has(draftId)) {
        mockDrafts.set(draftId, {
          draftId,
          draftType: 'BATCH',
          status: 'DRAFT',
          payload: JSON.stringify({ stagedCards: [] }),
          itemCount: 1,
          version: 1
        });
      }

      const mediaId = options.mediaId || `dm-${Math.random().toString(36).substring(2, 10)}${Date.now()}`;
      const isCover = Boolean(options.isCover);
      if (isCover && cardId) {
        // Clear existing cover for this card
        mockDraftMedia.forEach((m) => {
          if (m.draftId === draftId && m.cardId === cardId) {
            m.isCover = false;
          }
        });
      }

      const mediaRecord = {
        mediaId,
        draftId,
        cardId,
        originalFilename: file.name,
        fileSizeBytes: file.size,
        contentType: file.type || 'image/jpeg',
        roomTag: options.roomTag || 'LIVING_ROOM',
        isCover,
        stagingObjectKey: `drafts/admin/${draftId}/${mediaId}_${file.name}`,
        previewUrl: `http://localhost:8080/api/admin/drafts/${draftId}/media/${mediaId}`
      };
      mockDraftMedia.set(mediaId, mediaRecord);
      return mediaRecord;
    }

    getDraft(draftId) {
      const draft = mockDrafts.get(draftId);
      if (!draft) return null;
      const media = Array.from(mockDraftMedia.values()).filter((m) => m.draftId === draftId);
      return {
        ...draft,
        media
      };
    }

    createBatch(draftId, listings) {
      const createdListings = [];
      const createdIds = [];

      for (let i = 0; i < listings.length; i++) {
        const item = listings[i];
        const cardId = item.cardId;
        const compositeOriginDraftId = draftId && cardId ? `${draftId}:${cardId}` : (item.originDraftId || null);

        // Check idempotency via compositeOriginDraftId
        let existingListing = null;
        if (compositeOriginDraftId) {
          for (const listing of mockListings.values()) {
            if (listing.originDraftId === compositeOriginDraftId) {
              existingListing = listing;
              break;
            }
          }
        }

        if (existingListing) {
          createdIds.push(existingListing.id);
          createdListings.push({
            id: existingListing.id,
            requestIndex: i,
            propertyNumber: i + 1,
            title: existingListing.title,
            status: 'REPLAYED'
          });
          continue;
        }

        const newId = mockListings.size + 100;
        const newListing = {
          id: newId,
          originDraftId: compositeOriginDraftId,
          title: item.title || `Property ${newId}`,
          sector: item.sector || 'Vijay Nagar',
          status: 'ACTIVE'
        };
        mockListings.set(newId, newListing);
        createdIds.push(newId);
        createdListings.push({
          id: newId,
          requestIndex: i,
          propertyNumber: i + 1,
          title: newListing.title,
          status: 'CREATED'
        });
      }

      // Mark parent draft as PUBLISHING
      if (draftId && mockDrafts.has(draftId) && createdIds.length > 0) {
        const draft = mockDrafts.get(draftId);
        if (draft.status !== 'PUBLISHED') {
          draft.status = 'PUBLISHING';
        }
      }

      return {
        total: listings.length,
        successCount: createdIds.length,
        failedCount: 0,
        createdIds,
        createdListings,
        failedListings: []
      };
    }

    uploadMediaToCloudinary(propertyId, uploadRequestId, options = {}) {
      // Check existing media assets
      const existing = mockMediaAssets.get(`${propertyId}:${uploadRequestId}`);
      if (existing) {
        return { asset: existing, replayed: true };
      }

      cloudinaryUploads.push({ propertyId, uploadRequestId });
      const asset = {
        id: mockMediaAssets.size + 1,
        propertyId,
        uploadRequestId,
        url: `https://res.cloudinary.com/pathome/image/upload/v1/${uploadRequestId}.jpg`,
        isPrimaryCover: Boolean(options.isPrimaryCover),
        roomTag: options.roomTag || 'LIVING_ROOM'
      };
      mockMediaAssets.set(`${propertyId}:${uploadRequestId}`, asset);
      return { asset, replayed: false };
    }

    reconcileBatchDraft(draftId, publishedCardIds) {
      const draft = mockDrafts.get(draftId);
      if (!draft) return null;

      const publishedSet = new Set(publishedCardIds);

      // Clean media for published cards only
      for (const [mediaId, m] of mockDraftMedia.entries()) {
        if (m.draftId === draftId && m.cardId && publishedSet.has(m.cardId)) {
          mockDraftMedia.delete(mediaId);
        }
      }

      const payload = JSON.parse(draft.payload || '{}');
      const cards = payload.stagedCards || [];
      const remainingCards = cards.filter((c) => !publishedSet.has(c.id));

      if (remainingCards.length === 0) {
        // All cards completed -> terminal PUBLISHED tombstone
        draft.status = 'PUBLISHED';
        draft.payload = '{}';
        draft.itemCount = 0;
        return null;
      }

      draft.payload = JSON.stringify({ stagedCards: remainingCards });
      draft.itemCount = remainingCards.length;
      draft.version += 1;
      return this.getDraft(draftId);
    }

    markPublished(draftId, listingId = null) {
      const draft = mockDrafts.get(draftId);
      if (!draft) return;

      if (draft.draftType === 'BATCH') {
        const payload = JSON.parse(draft.payload || '{}');
        const cards = payload.stagedCards || [];
        const hasUnpublished = cards.some((c) => !c.publishedId);
        if (hasUnpublished) {
          // Protected: refuse premature tombstone
          return;
        }
      }

      draft.status = 'PUBLISHED';
      draft.payload = '{}';
      draft.itemCount = 0;
      draft.publishedPropertyId = listingId;
    }
  }

  beforeEach(() => {
    mockDrafts = new Map();
    mockDraftMedia = new Map();
    mockListings = new Map();
    mockMediaAssets = new Map();
    cloudinaryUploads = [];
  });

  // ============================================================
  // A. DURABLE MEDIA (Tests 1 - 9)
  // ============================================================
  test('A1. batch media stages with correct draftId + cardId', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_batch_001';
    const cardId = 'card_alpha';
    const fakeFile = { name: 'living_room.jpg', size: 1024 * 1024, type: 'image/jpeg' };

    const staged = backend.stageMedia(draftId, cardId, fakeFile, { roomTag: 'LIVING_ROOM' });
    assert.equal(staged.draftId, draftId);
    assert.equal(staged.cardId, cardId);
    assert.ok(staged.mediaId.startsWith('dm-'));
    assert.ok(mockDraftMedia.has(staged.mediaId));
  });

  test('A2. multiple cards keep media isolated', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_batch_001';
    backend.stageMedia(draftId, 'card_A', { name: 'card_a_photo.jpg', size: 5000, type: 'image/jpeg' });
    backend.stageMedia(draftId, 'card_B', { name: 'card_b_photo.jpg', size: 6000, type: 'image/jpeg' });

    const draftDetail = backend.getDraft(draftId);
    const cardAMedia = draftDetail.media.filter((m) => m.cardId === 'card_A');
    const cardBMedia = draftDetail.media.filter((m) => m.cardId === 'card_B');

    assert.equal(cardAMedia.length, 1);
    assert.equal(cardAMedia[0].originalFilename, 'card_a_photo.jpg');
    assert.equal(cardBMedia.length, 1);
    assert.equal(cardBMedia[0].originalFilename, 'card_b_photo.jpg');
  });

  test('A3. roomTag survives staging', () => {
    const backend = new MockBatchBackend();
    const staged = backend.stageMedia('draft_1', 'card_1', { name: 'p.jpg', size: 1000, type: 'image/jpeg' }, { roomTag: 'KITCHEN' });
    assert.equal(staged.roomTag, 'KITCHEN');
  });

  test('A4. isCover survives staging and strictly respects per-card cover', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_1';
    const m1 = backend.stageMedia(draftId, 'card_1', { name: 'p1.jpg', size: 1000, type: 'image/jpeg' }, { isCover: true });
    assert.equal(m1.isCover, true);

    const m2 = backend.stageMedia(draftId, 'card_1', { name: 'p2.jpg', size: 1000, type: 'image/jpeg' }, { isCover: true });
    assert.equal(m2.isCover, true);

    // Old cover on same card was cleared
    assert.equal(mockDraftMedia.get(m1.mediaId).isCover, false);
  });

  test('A5. original filename survives', () => {
    const backend = new MockBatchBackend();
    const staged = backend.stageMedia('draft_1', 'card_1', { name: 'spacious_balcony_view.jpg', size: 2000, type: 'image/jpeg' });
    assert.equal(staged.originalFilename, 'spacious_balcony_view.jpg');
  });

  test('A6. image/video identity survives', () => {
    const backend = new MockBatchBackend();
    const image = backend.stageMedia('draft_1', 'card_1', { name: 'img.webp', size: 1000, type: 'image/webp' });
    const video = backend.stageMedia('draft_1', 'card_1', { name: 'tour.mp4', size: 5000000, type: 'video/mp4' });

    assert.equal(image.contentType, 'image/webp');
    assert.equal(isMediaVideo(image), false);

    assert.equal(video.contentType, 'video/mp4');
    assert.equal(isMediaVideo(video), true);
  });

  test('A7. restore reconstructs media without requiring original browser File', () => {
    const restoredItem = {
      id: 'dm-12345',
      previewUrl: 'http://localhost:8080/api/admin/drafts/draft_1/media/dm-12345',
      roomTag: 'BEDROOM',
      isCover: true,
      originalFilename: 'master_bedroom.jpg',
      fileSizeBytes: 204800,
      contentType: 'image/jpeg',
      file: undefined // native browser File is absent
    };

    assert.equal(getMediaFilename(restoredItem), 'master_bedroom.jpg');
    assert.equal(getMediaFileSize(restoredItem), 204800);
    assert.equal(isMediaVideo(restoredItem), false);
  });

  test('A8. reselect/additional media does not crash restored items', () => {
    const restoredItem = {
      id: 'dm-restored',
      previewUrl: 'http://stream/dm-restored',
      roomTag: 'LIVING_ROOM',
      isCover: true,
      originalFilename: 'restored.jpg',
      fileSizeBytes: 10000,
      contentType: 'image/jpeg'
    };

    const newlyAttachedFile = { name: 'new.jpg', size: 20000, type: 'image/jpeg' };
    const newItem = {
      id: 'dm-new',
      file: newlyAttachedFile,
      previewUrl: 'blob:http://preview/new',
      roomTag: 'BEDROOM',
      isCover: false
    };

    const combined = [restoredItem, newItem];
    const filenames = combined.map(getMediaFilename);
    const fileSizes = combined.map(getMediaFileSize);

    assert.deepEqual(filenames, ['restored.jpg', 'new.jpg']);
    assert.deepEqual(fileSizes, [10000, 20000]);
  });

  test('A9. no direct undefined item.file crash on render helpers', () => {
    const itemWithoutFile = {
      id: 'dm-orphan',
      originalFilename: 'tour.mp4',
      fileSizeBytes: 4500000
    };

    assert.doesNotThrow(() => {
      const isVid = isMediaVideo(itemWithoutFile);
      const name = getMediaFilename(itemWithoutFile);
      const size = getMediaFileSize(itemWithoutFile);
      assert.equal(isVid, true);
      assert.equal(name, 'tour.mp4');
      assert.equal(size, 4500000);
    });
  });

  // ============================================================
  // B. PARTIAL PUBLISH (Tests 10 - 17)
  // ============================================================
  test('B10. 2 cards, only 1 confirmed: validCards filters strictly', () => {
    const stagedCards = [
      { id: 'card_1', isValid: true, isConfirmed: true, publishedId: undefined },
      { id: 'card_2', isValid: false, isConfirmed: false, publishedId: undefined }
    ];

    const validCards = stagedCards.filter((c) => c.isValid && !c.publishedId);
    assert.equal(validCards.length, 1);
    assert.equal(validCards[0].id, 'card_1');
  });

  test('B11. CTA says 1 of 2 confirmed properties', () => {
    const stagedCards = [
      { id: 'card_1', isValid: true, isConfirmed: true, publishedId: undefined },
      { id: 'card_2', isValid: false, isConfirmed: false, publishedId: undefined }
    ];

    const cta = computeCtaTexts(stagedCards);
    assert.equal(cta.desktopCta, 'Publish 1 of 2 confirmed properties');
    assert.equal(cta.supportingInfo, '1 property still needs confirmation.');
    assert.equal(cta.compactBadge, '1/2');
    assert.equal(cta.mobileCta, 'Publish 1 of 2 confirmed');
  });

  test('B12. publish only confirmed card', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_partial_1';
    const stagedCards = [
      { id: 'card_1', title: '2 BHK in Vijay Nagar', isValid: true, isConfirmed: true, publishedId: undefined },
      { id: 'card_2', title: '1 BHK in Palasia', isValid: false, isConfirmed: false, publishedId: undefined }
    ];

    const validCards = stagedCards.filter((c) => c.isValid && !c.publishedId);
    const res = backend.createBatch(draftId, validCards.map((c) => ({ cardId: c.id, title: c.title })));

    assert.equal(res.successCount, 1);
    assert.equal(res.createdListings.length, 1);
    assert.equal(res.createdListings[0].title, '2 BHK in Vijay Nagar');
  });

  test('B13. successful card gets durable listing association', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_partial_1';
    const res = backend.createBatch(draftId, [{ cardId: 'card_1', title: '2 BHK' }]);
    const listingId = res.createdIds[0];

    const listing = mockListings.get(listingId);
    assert.equal(listing.originDraftId, 'draft_partial_1:card_1');
  });

  test('B14 & B15. remaining card remains in draft and parent draft is NOT tombstoned', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_partial_1';
    mockDrafts.set(draftId, {
      draftId,
      draftType: 'BATCH',
      status: 'DRAFT',
      payload: JSON.stringify({
        stagedCards: [
          { id: 'card_1', title: 'Card 1' },
          { id: 'card_2', title: 'Card 2' }
        ]
      }),
      itemCount: 2,
      version: 1
    });

    // Reconcile after publishing card_1 only
    const remaining = backend.reconcileBatchDraft(draftId, ['card_1']);
    assert.ok(remaining, 'Draft must NOT be null');
    assert.equal(remaining.status, 'DRAFT');
    assert.equal(remaining.itemCount, 1);

    const payload = JSON.parse(remaining.payload);
    assert.equal(payload.stagedCards.length, 1);
    assert.equal(payload.stagedCards[0].id, 'card_2');
  });

  test('B16. remaining card media is retained while published card media is cleaned', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_partial_media';
    mockDrafts.set(draftId, {
      draftId,
      draftType: 'BATCH',
      status: 'DRAFT',
      payload: JSON.stringify({
        stagedCards: [{ id: 'card_1' }, { id: 'card_2' }]
      }),
      itemCount: 2,
      version: 1
    });

    backend.stageMedia(draftId, 'card_1', { name: 'card1.jpg', size: 1000, type: 'image/jpeg' });
    backend.stageMedia(draftId, 'card_2', { name: 'card2.jpg', size: 2000, type: 'image/jpeg' });

    backend.reconcileBatchDraft(draftId, ['card_1']);

    const remainingDraft = backend.getDraft(draftId);
    assert.equal(remainingDraft.media.length, 1);
    assert.equal(remainingDraft.media[0].cardId, 'card_2');
    assert.equal(remainingDraft.media[0].originalFilename, 'card2.jpg');
  });

  test('B17. Resume does not recreate published card', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_resume';
    // Card 1 was already published into listing #101
    const res1 = backend.createBatch(draftId, [{ cardId: 'card_1', title: 'Prop 1' }]);
    const firstListingId = res1.createdIds[0];

    // Card 1 has publishedId: firstListingId
    const resumedCards = [
      { id: 'card_1', publishedId: firstListingId, isValid: false },
      { id: 'card_2', publishedId: undefined, isValid: true }
    ];

    // On publish action: validCards excludes publishedId
    const validCards = resumedCards.filter((c) => c.isValid && !c.publishedId);
    assert.equal(validCards.length, 1);
    assert.equal(validCards[0].id, 'card_2');

    const res2 = backend.createBatch(draftId, validCards.map((c) => ({ cardId: c.id, title: 'Prop 2' })));
    assert.equal(res2.createdIds.length, 1);
    assert.notEqual(res2.createdIds[0], firstListingId);
    assert.equal(mockListings.size, 2);
  });

  // ============================================================
  // C. FULL PUBLISH (Tests 18 - 23)
  // ============================================================
  test('C18. 2/2 confirmed displays accurate CTA', () => {
    const stagedCards = [
      { id: 'c1', isValid: true, isConfirmed: true },
      { id: 'c2', isValid: true, isConfirmed: true }
    ];
    const cta = computeCtaTexts(stagedCards);
    assert.equal(cta.desktopCta, 'Publish all 2 properties');
    assert.equal(cta.mobileCta, 'Publish all 2 properties');
    assert.equal(cta.compactBadge, '2/2');
    assert.equal(cta.supportingInfo, '2 of 2 properties confirmed and ready to publish.');
  });

  test('C19. both listings created with distinct durable originDraftIds', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_full';
    const res = backend.createBatch(draftId, [
      { cardId: 'c1', title: 'Listing 1' },
      { cardId: 'c2', title: 'Listing 2' }
    ]);

    assert.equal(res.createdIds.length, 2);
    const l1 = mockListings.get(res.createdIds[0]);
    const l2 = mockListings.get(res.createdIds[1]);

    assert.equal(l1.originDraftId, 'draft_full:c1');
    assert.equal(l2.originDraftId, 'draft_full:c2');
  });

  test('C20 & C21. media uploaded with stable uploadRequestId', () => {
    const backend = new MockBatchBackend();
    const propId = 200;
    const fakeFile = { name: 'photo.jpg', size: 12345, lastModified: 1000, draftMediaId: 'dm-photo-1' };
    const reqId = createStableUploadRequestId(propId, fakeFile, 'dm-photo-1');

    const res = backend.uploadMediaToCloudinary(propId, reqId, { isPrimaryCover: true });
    assert.equal(res.replayed, false);
    assert.equal(res.asset.uploadRequestId, 'media-200-dm-photo-1');
  });

  test('C22 & C23. only when all cards complete, final PUBLISHED tombstone and cleanup occur', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_full_done';
    mockDrafts.set(draftId, {
      draftId,
      draftType: 'BATCH',
      status: 'PUBLISHING',
      payload: JSON.stringify({
        stagedCards: [{ id: 'c1' }, { id: 'c2' }]
      }),
      itemCount: 2,
      version: 1
    });
    backend.stageMedia(draftId, 'c1', { name: 'm1.jpg', size: 100, type: 'image/jpeg' });
    backend.stageMedia(draftId, 'c2', { name: 'm2.jpg', size: 100, type: 'image/jpeg' });

    // All cards published
    const remaining = backend.reconcileBatchDraft(draftId, ['c1', 'c2']);
    assert.equal(remaining, null);

    const draft = mockDrafts.get(draftId);
    assert.equal(draft.status, 'PUBLISHED');
    assert.equal(draft.payload, '{}');
    assert.equal(draft.itemCount, 0);

    // Media cleaned
    const remainingMedia = Array.from(mockDraftMedia.values()).filter((m) => m.draftId === draftId);
    assert.equal(remainingMedia.length, 0);
  });

  // ============================================================
  // D. NETWORK INTERRUPTION & RESUME (Tests 24 - 28)
  // ============================================================
  test('D24 & D25 & D26. interruption after listing 1 creation: Resume reuses listing 1 with no duplicate', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_network_interruption';

    // Listing 1 was created before network drop
    const res1 = backend.createBatch(draftId, [{ cardId: 'c1', title: 'Listing 1' }]);
    const listingId1 = res1.createdIds[0];

    // User resumes and attempts to publish again (sending c1 and c2)
    const res2 = backend.createBatch(draftId, [
      { cardId: 'c1', title: 'Listing 1' },
      { cardId: 'c2', title: 'Listing 2' }
    ]);

    assert.equal(res2.createdListings[0].status, 'REPLAYED');
    assert.equal(res2.createdListings[0].id, listingId1);
    assert.equal(res2.createdListings[1].status, 'CREATED');
    assert.equal(mockListings.size, 2, 'Total listings must be exactly 2, zero duplicates');
  });

  test('D27 & D28. media resumes without re-uploading already completed items', () => {
    const backend = new MockBatchBackend();
    const propId = 300;
    const reqId1 = 'media-300-dm_photo_1';
    const reqId2 = 'media-300-dm_photo_2';

    // Media 1 was uploaded before network drop
    backend.uploadMediaToCloudinary(propId, reqId1);
    assert.equal(cloudinaryUploads.length, 1);

    // Resume uploads media 1 (replayed) and media 2 (new)
    const upload1 = backend.uploadMediaToCloudinary(propId, reqId1);
    assert.equal(upload1.replayed, true);
    assert.equal(cloudinaryUploads.length, 1, 'Cloudinary upload count did NOT increment for media 1');

    const upload2 = backend.uploadMediaToCloudinary(propId, reqId2);
    assert.equal(upload2.replayed, false);
    assert.equal(cloudinaryUploads.length, 2);
  });

  // ============================================================
  // E. HARD REFRESH DURING PUBLISHING (Tests 29 - 35)
  // ============================================================
  test('E29 & E30 & E31. hard refresh during PUBLISHING: batch state and staged media survive', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_hard_refresh';
    mockDrafts.set(draftId, {
      draftId,
      draftType: 'BATCH',
      status: 'PUBLISHING',
      payload: JSON.stringify({
        stagedCards: [
          { id: 'c1', title: 'Villa in Bypass', publishedId: 501 },
          { id: 'c2', title: 'Flat in AB Road', publishedId: undefined }
        ]
      }),
      itemCount: 2,
      version: 2
    });
    backend.stageMedia(draftId, 'c2', { name: 'flat_hall.jpg', size: 50000, type: 'image/jpeg' });

    const restoredDraft = backend.getDraft(draftId);
    assert.equal(restoredDraft.status, 'PUBLISHING');
    const payload = JSON.parse(restoredDraft.payload);
    assert.equal(payload.stagedCards[0].publishedId, 501);
    assert.equal(payload.stagedCards[1].publishedId, undefined);
    assert.equal(restoredDraft.media.length, 1);
    assert.equal(restoredDraft.media[0].cardId, 'c2');
  });

  test('E32 & E33 & E34 & E35. completed work recognized and missing work resumes with no duplicates', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_hard_refresh';
    mockDrafts.set(draftId, {
      draftId,
      draftType: 'BATCH',
      status: 'PUBLISHING',
      payload: JSON.stringify({
        stagedCards: [{ id: 'c1' }, { id: 'c2' }]
      }),
      itemCount: 2,
      version: 1
    });
    // Existing listing for c1
    mockListings.set(501, { id: 501, originDraftId: `${draftId}:c1`, title: 'Villa in Bypass' });

    // Missing work: publish c2
    const res = backend.createBatch(draftId, [{ cardId: 'c2', title: 'Flat in AB Road' }]);
    assert.equal(res.successCount, 1);
    assert.equal(mockListings.size, 2);

    // Finalize
    backend.reconcileBatchDraft(draftId, ['c1', 'c2']);
    assert.equal(mockDrafts.get(draftId).status, 'PUBLISHED');
  });

  // ============================================================
  // F. DUPLICATE PUBLISH GUARD (Tests 36 - 38)
  // ============================================================
  test('F36 & F37 & F38. double publish request maps to same listing, zero duplicate rows', () => {
    const backend = new MockBatchBackend();
    const draftId = 'draft_double_click';

    const req = [
      { cardId: 'c1', title: 'Flat 101' },
      { cardId: 'c2', title: 'Flat 102' }
    ];

    // Click 1
    const res1 = backend.createBatch(draftId, req);
    // Click 2 (identical repeated request)
    const res2 = backend.createBatch(draftId, req);

    assert.deepEqual(res1.createdIds, res2.createdIds);
    assert.equal(res2.createdListings[0].status, 'REPLAYED');
    assert.equal(res2.createdListings[1].status, 'REPLAYED');
    assert.equal(mockListings.size, 2, 'No duplicate listing rows in database');
  });

  // ============================================================
  // G. RENDER SAFETY & BLANK SCREEN PROTECTION (Tests 39 - 44)
  // ============================================================
  test('G39 & G40. restored media with no native File renders with filename fallback', () => {
    const item = {
      id: 'dm-1',
      originalFilename: 'master_bedroom.png',
      fileSizeBytes: 1048576,
      previewUrl: 'http://cdn/stream/dm-1',
      roomTag: 'BEDROOM',
      isCover: false
      // file: undefined
    };

    assert.equal(getMediaFilename(item), 'master_bedroom.png');
  });

  test('G41. media type fallback works without item.file.type', () => {
    const photo = { id: 'p', originalFilename: 'living.jpg', contentType: 'image/jpeg' };
    const video = { id: 'v', originalFilename: 'walkthrough.mp4', contentType: 'video/mp4' };

    assert.equal(isMediaVideo(photo), false);
    assert.equal(isMediaVideo(video), true);
  });

  test('G42. file size fallback works without item.file.size', () => {
    const item = { id: 'dm-2', fileSizeBytes: 5242880 };
    assert.equal(getMediaFileSize(item), 5242880);
  });

  test('G43 & G44. add new media after restore works cleanly without blank screen', () => {
    const restored = {
      id: 'dm-1',
      originalFilename: 'old.jpg',
      fileSizeBytes: 1000,
      contentType: 'image/jpeg'
    };
    const added = {
      id: 'dm-2',
      file: { name: 'new.jpg', size: 2000, type: 'image/jpeg' },
      previewUrl: 'blob:preview'
    };

    const gallery = [restored, added];
    assert.doesNotThrow(() => {
      gallery.forEach((m) => {
        isMediaVideo(m);
        getMediaFilename(m);
        getMediaFileSize(m);
      });
    });
  });

  // ============================================================
  // H. UX COPYWRITING & COMPACT PROGRESS (Tests 45 - 49)
  // ============================================================
  test('H45. 1/2 confirmed display', () => {
    const cards = [
      { id: '1', isValid: true },
      { id: '2', isValid: false }
    ];
    const cta = computeCtaTexts(cards);
    assert.equal(cta.compactBadge, '1/2');
    assert.equal(cta.desktopCta, 'Publish 1 of 2 confirmed properties');
  });

  test('H46. 2/2 confirmed display', () => {
    const cards = [
      { id: '1', isValid: true },
      { id: '2', isValid: true }
    ];
    const cta = computeCtaTexts(cards);
    assert.equal(cta.compactBadge, '2/2');
    assert.equal(cta.desktopCta, 'Publish all 2 properties');
  });

  test('H47. singular grammar for 1 property batch', () => {
    const cards = [{ id: '1', isValid: true }];
    const cta = computeCtaTexts(cards);
    assert.equal(cta.desktopCta, 'Publish 1 property');
    assert.equal(cta.mobileCta, 'Publish 1 property');
  });

  test('H48. published / remaining state is understandable', () => {
    const cards = [
      { id: '1', isValid: false }, // needs confirmation
      { id: '2', isValid: false }, // needs confirmation
      { id: '3', isValid: true }   // ready
    ];
    const cta = computeCtaTexts(cards);
    assert.equal(cta.supportingInfo, '2 properties still need confirmation.');
    assert.equal(cta.desktopCta, 'Publish 1 of 3 confirmed properties');
  });

  test('H49. mobile compact progress shows ready/total with confirmed label', () => {
    const cards = [
      { id: '1', isValid: true },
      { id: '2', isValid: false }
    ];
    const cta = computeCtaTexts(cards);
    assert.equal(cta.compactBadge, '1/2');
    assert.equal(cta.mobileCta, 'Publish 1 of 2 confirmed');
  });
});
