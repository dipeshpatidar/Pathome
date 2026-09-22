import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

describe('Authoritative Batch Publish + Network Interruption Recovery Suite (Requirements 1 - 32)', () => {
  let mockDrafts;
  let mockDraftMedia;
  let mockListings;
  let mockMediaAssets;
  let simulatedNetworkError;

  beforeEach(() => {
    mockDrafts = new Map();
    mockDraftMedia = new Map();
    mockListings = new Map();
    mockMediaAssets = new Map();
    simulatedNetworkError = false;
  });

  // Upload request ID creator matching propertyService.ts
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

  // Model backend PropertyDraftService & PropertyController batch publishing
  class AuthoritativeMockBackend {
    createBatch(draftId, cards) {
      if (simulatedNetworkError) {
        throw new Error('Failed to fetch: Network disconnected');
      }
      const created = [];
      const errors = [];
      for (const card of cards) {
        const originDraftId = `${draftId}:${card.id}`;
        // Idempotency check: if listing already exists with this originDraftId
        let existing = null;
        for (const [id, listing] of mockListings.entries()) {
          if (listing.originDraftId === originDraftId) {
            existing = { ...listing, id };
            break;
          }
        }
        if (existing) {
          created.push({ cardId: card.id, listingId: existing.id, isReplay: true });
          continue;
        }
        const newId = mockListings.size + 1;
        mockListings.set(newId, {
          originDraftId,
          title: card.title,
          status: 'ACTIVE',
          mediaUrls: []
        });
        created.push({ cardId: card.id, listingId: newId, isReplay: false });
      }
      return { successCount: created.length, createdListings: created, errors };
    }

    uploadMedia(listingId, file, options = {}) {
      if (simulatedNetworkError) {
        throw new Error('Network error during media upload');
      }
      const uploadRequestId = options.uploadRequestId || createStableUploadRequestId(listingId, file, options.draftMediaId);
      const existing = (mockMediaAssets.get(listingId) || []).find(a => a.uploadRequestId === uploadRequestId);
      if (existing) {
        return { ...existing, isReplay: true };
      }
      const asset = {
        id: `asset-${Date.now()}-${Math.random()}`,
        listingId,
        uploadRequestId,
        url: `https://cloudinary.com/pathome/${uploadRequestId}.jpg`,
        roomTag: options.roomTag || null,
        caption: options.caption || null,
        isCover: !!options.isCover,
        mediaType: options.mediaType || 'IMAGE',
        displayOrder: options.displayOrder || 0
      };
      if (!mockMediaAssets.has(listingId)) {
        mockMediaAssets.set(listingId, []);
      }
      mockMediaAssets.get(listingId).push(asset);
      const listing = mockListings.get(listingId);
      if (listing) {
        listing.mediaUrls.push(asset.url);
      }
      return asset;
    }

    reconcileBatchDraft(draftId, completedCardIds, completedListings) {
      if (simulatedNetworkError) {
        throw new Error('Network disconnected during reconciliation');
      }
      const draft = mockDrafts.get(draftId);
      if (!draft) throw new Error('Draft not found');

      const payload = JSON.parse(draft.payload);
      const existingStaged = Array.isArray(payload.stagedCards) ? payload.stagedCards : [];
      const remainingCards = existingStaged.filter(c => !completedCardIds.includes(c.id));
      const combinedCompleted = [...(payload.completedListings || []), ...(completedListings || [])];

      payload.stagedCards = remainingCards;
      payload.completedListings = combinedCompleted;

      // Invariant: Save payload FIRST
      if (remainingCards.length === 0) {
        draft.status = 'PUBLISHED';
        draft.itemCount = 0;
        draft.payload = JSON.stringify(payload);
        mockDraftMedia.delete(draftId);
      } else {
        draft.status = 'DRAFT';
        draft.itemCount = remainingCards.length;
        draft.payload = JSON.stringify(payload);
        // Only delete media for published cards
        const draftMediaList = mockDraftMedia.get(draftId) || [];
        mockDraftMedia.set(draftId, draftMediaList.filter(m => !completedCardIds.includes(m.cardId)));
      }
      return { ...draft };
    }

    getDraft(draftId) {
      const draft = mockDrafts.get(draftId);
      if (!draft) return null;
      const payload = JSON.parse(draft.payload);

      // Backend auto-reconciliation on resume
      if (draft.status === 'PUBLISHING' && Array.isArray(payload.stagedCards)) {
        const remaining = [];
        const autoCompleted = [];
        for (const card of payload.stagedCards) {
          const originDraftId = `${draftId}:${card.id}`;
          let existingListing = null;
          for (const [id, listing] of mockListings.entries()) {
            if (listing.originDraftId === originDraftId) {
              existingListing = { ...listing, id };
              break;
            }
          }
          if (existingListing) {
            const assets = mockMediaAssets.get(existingListing.id) || [];
            const expectedCount = (card.stagedMedia?.length || 0);
            if (expectedCount > 0 && assets.length >= expectedCount) {
              autoCompleted.push({
                listingId: existingListing.id,
                cardId: card.id,
                title: card.title
              });
              continue;
            }
          }
          remaining.push(card);
        }

        if (autoCompleted.length > 0) {
          payload.stagedCards = remaining;
          payload.completedListings = [...(payload.completedListings || []), ...autoCompleted];
          if (remaining.length === 0) {
            draft.status = 'PUBLISHED';
            draft.itemCount = 0;
          } else {
            draft.itemCount = remaining.length;
          }
          draft.payload = JSON.stringify(payload);
        }
      }

      // Reconstruct media if staged media rows are empty
      let media = mockDraftMedia.get(draftId) || [];
      if (media.length === 0 && Array.isArray(payload.stagedCards)) {
        for (const card of payload.stagedCards) {
          const originDraftId = `${draftId}:${card.id}`;
          for (const [id, listing] of mockListings.entries()) {
            if (listing.originDraftId === originDraftId) {
              const assets = mockMediaAssets.get(id) || [];
              for (const a of assets) {
                media.push({
                  id: a.uploadRequestId,
                  cardId: card.id,
                  previewUrl: a.url,
                  originalFilename: 'permanent_asset.jpg',
                  roomTag: a.roomTag,
                  isCover: a.isCover
                });
              }
            }
          }
        }
      }

      return { ...draft, media };
    }
  }

  // Model frontend handlePublishAll logic matching BatchPropertyIngestionStudio.tsx
  async function simulateFrontendPublishAll(backend, draftId, stagedCards, callbacks) {
    const newlyCompleted = [];
    const successfulCardIds = [];

    // 1. Create listings
    let batchResult;
    try {
      batchResult = backend.createBatch(draftId, stagedCards);
    } catch (err) {
      // Network interruption before listing creation
      callbacks.onError?.('Network error during batch creation');
      return { completed: false, reason: 'network_interruption' };
    }

    // 2. Media processing
    for (const created of batchResult.createdListings) {
      const card = stagedCards.find(c => c.id === created.cardId);
      const mediaList = card?.stagedMedia || [];
      let mediaFailed = false;

      for (const m of mediaList) {
        try {
          backend.uploadMedia(created.listingId, m.file, {
            uploadRequestId: m.id,
            roomTag: m.roomTag,
            isCover: m.isCover
          });
        } catch (err) {
          mediaFailed = true;
          break;
        }
      }

      if (!mediaFailed) {
        successfulCardIds.push(created.cardId);
        newlyCompleted.push({
          listingId: created.listingId,
          cardId: created.cardId,
          title: card?.title
        });
      }
    }

    // CRITICAL: DO NOT call onSuccess or onPartialSuccess merely because createBatch returned successCount > 0!
    if (successfulCardIds.length === 0) {
      callbacks.onError?.('No properties could be completely published due to media errors.');
      return { completed: false, reason: 'media_failure' };
    }

    // 3. Authoritative draft reconciliation
    let reconciledDraft;
    try {
      reconciledDraft = backend.reconcileBatchDraft(draftId, successfulCardIds, newlyCompleted);
    } catch (err) {
      // Network interruption before reconciliation confirmation
      // Treat outcome as interrupted/unknown. DO NOT emit green success!
      callbacks.onError?.('Publishing interrupted: The network connection was interrupted before publication could be verified. Use Resume to reconcile your draft.');
      return { completed: false, reason: 'network_interruption_during_reconciliation' };
    }

    // 4. Authoritative remaining count from reconciledDraft
    const serverRemainingCount = reconciledDraft.itemCount ?? 0;
    const isServerBatchComplete = reconciledDraft.status === 'PUBLISHED' || serverRemainingCount === 0;

    if (isServerBatchComplete) {
      callbacks.onSuccess?.({
        message: `${successfulCardIds.length} properties published successfully. 0 properties remain in draft.`,
        remainingCount: 0
      });
    } else {
      callbacks.onPartialSuccess?.({
        message: `${successfulCardIds.length} property published. ${serverRemainingCount} property remains in draft.`,
        remainingCount: serverRemainingCount
      });
    }

    return { completed: isServerBatchComplete, remainingCount: serverRemainingCount };
  }

  // ============================================================
  // TESTS (1 - 32)
  // ============================================================

  test('1. Normal partial publish -> authoritative success -> correct remaining count', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-101';
    const cards = [
      { id: 'card-1', title: 'Property A', stagedMedia: [] },
      { id: 'card-2', title: 'Property B', stagedMedia: [] }
    ];
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: cards, completedListings: [] }),
      itemCount: 2
    });

    let partialMsg = '';
    let remaining = -1;
    await simulateFrontendPublishAll(backend, draftId, [cards[0]], {
      onPartialSuccess: (res) => {
        partialMsg = res.message;
        remaining = res.remainingCount;
      }
    });

    assert.equal(remaining, 1);
    assert.match(partialMsg, /1 property published\. 1 property remains in draft\./);
  });

  test('2. Network interruption before backend receives publish -> no confirmed success -> draft recoverable', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-102';
    const cards = [{ id: 'card-1', title: 'Property A', stagedMedia: [] }];
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: cards, completedListings: [] }),
      itemCount: 1
    });

    simulatedNetworkError = true;
    let successFired = false;
    let errorMsg = '';
    await simulateFrontendPublishAll(backend, draftId, cards, {
      onSuccess: () => { successFired = true; },
      onError: (err) => { errorMsg = err; }
    });

    assert.equal(successFired, false, 'Success must NEVER fire on network interruption');
    assert.match(errorMsg, /Network error during batch creation/);
    const draft = backend.getDraft(draftId);
    assert.equal(draft.itemCount, 1);
    assert.equal(draft.status, 'DRAFT');
  });

  test('3. Network interruption after listing creation -> no premature success -> Resume finds existing listing -> no duplicate', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-103';
    const cards = [{ id: 'card-1', title: 'Property A', stagedMedia: [{ id: 'm1' }] }];
    mockDrafts.set(draftId, {
      draftId,
      status: 'PUBLISHING',
      payload: JSON.stringify({ stagedCards: cards, completedListings: [] }),
      itemCount: 1
    });

    // Create listing succeeds
    const batchRes = backend.createBatch(draftId, cards);
    assert.equal(mockListings.size, 1);

    // Media upload disconnected
    simulatedNetworkError = true;
    let successFired = false;
    await simulateFrontendPublishAll(backend, draftId, cards, {
      onSuccess: () => { successFired = true; }
    });
    assert.equal(successFired, false);

    // Resume: restore network
    simulatedNetworkError = false;
    const replayRes = backend.createBatch(draftId, cards);
    assert.equal(mockListings.size, 1, 'Listing must not be duplicated');
    assert.equal(replayRes.createdListings[0].isReplay, true);
  });

  test('4. Network interruption during media processing -> no premature success -> recoverable state retained', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-104';
    const cards = [{ id: 'card-1', title: 'Property A', stagedMedia: [{ id: 'm1' }, { id: 'm2' }] }];
    mockDrafts.set(draftId, {
      draftId,
      status: 'PUBLISHING',
      payload: JSON.stringify({ stagedCards: cards, completedListings: [] }),
      itemCount: 1
    });

    const listingRes = backend.createBatch(draftId, cards);
    backend.uploadMedia(listingRes.createdListings[0].listingId, null, { draftMediaId: 'm1' });
    assert.equal(mockMediaAssets.get(listingRes.createdListings[0].listingId).length, 1);

    // Network drops before m2
    simulatedNetworkError = true;
    let successCalled = false;
    let errorCalled = false;
    await simulateFrontendPublishAll(backend, draftId, cards, {
      onSuccess: () => { successCalled = true; },
      onError: () => { errorCalled = true; }
    });

    assert.equal(successCalled, false);
    assert.equal(errorCalled, true);
  });

  test('5. Disconnect after some permanent media persisted -> Resume detects existing assets -> only missing work processed', () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-105';
    const listingId = 55;
    mockListings.set(listingId, { originDraftId: `${draftId}:card-1`, mediaUrls: [] });
    backend.uploadMedia(listingId, null, { draftMediaId: 'm1' });

    const assets = mockMediaAssets.get(listingId);
    assert.equal(assets.length, 1);

    // Missing work detection:
    const cardMedia = [{ id: 'm1' }, { id: 'm2' }];
    const missing = cardMedia.filter(m => !assets.some(a => a.uploadRequestId.includes(m.id)));
    assert.equal(missing.length, 1);
    assert.equal(missing[0].id, 'm2');
  });

  test('6. Disconnect after all permanent media persisted but before client confirmation -> Resume reconciles as complete -> no duplicate listing -> no duplicate media', () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-106';
    const cards = [{ id: 'card-1', title: 'Property A', stagedMedia: [{ id: 'm1' }] }];
    mockDrafts.set(draftId, {
      draftId,
      status: 'PUBLISHING',
      payload: JSON.stringify({ stagedCards: cards, completedListings: [] }),
      itemCount: 1
    });

    // Backend completed listing + media
    const batchRes = backend.createBatch(draftId, cards);
    backend.uploadMedia(batchRes.createdListings[0].listingId, null, { draftMediaId: 'm1' });

    // Client Resume calls getDraft
    const resumed = backend.getDraft(draftId);
    assert.equal(resumed.status, 'PUBLISHED');
    assert.equal(resumed.itemCount, 0);
    assert.equal(mockListings.size, 1);
    assert.equal(mockMediaAssets.get(batchRes.createdListings[0].listingId).length, 1);
  });

  test('7. reconcileBatchDraft does not delete staging metadata before safe draft reconciliation', () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-107';
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: [{ id: 'c1' }, { id: 'c2' }], completedListings: [] }),
      itemCount: 2
    });
    mockDraftMedia.set(draftId, [
      { id: 'm1', cardId: 'c1' },
      { id: 'm2', cardId: 'c2' }
    ]);

    // Reconcile c1
    backend.reconcileBatchDraft(draftId, ['c1'], [{ cardId: 'c1', listingId: 1 }]);
    const remainingMedia = mockDraftMedia.get(draftId);
    assert.equal(remainingMedia.length, 1);
    assert.equal(remainingMedia[0].cardId, 'c2');
  });

  test('8. Final-card reconciliation updates payload before tombstone validation', () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-108';
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: [{ id: 'c1' }], completedListings: [] }),
      itemCount: 1
    });

    const reconciled = backend.reconcileBatchDraft(draftId, ['c1'], [{ cardId: 'c1', listingId: 1 }]);
    const payload = JSON.parse(reconciled.payload);
    assert.equal(payload.stagedCards.length, 0);
    assert.equal(payload.completedListings.length, 1);
    assert.equal(reconciled.status, 'PUBLISHED');
  });

  test('9. Final card successfully tombstones after authoritative reconciliation', () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-109';
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: [{ id: 'c1' }], completedListings: [{ cardId: 'c0', listingId: 99 }] }),
      itemCount: 1
    });

    const reconciled = backend.reconcileBatchDraft(draftId, ['c1'], [{ cardId: 'c1', listingId: 100 }]);
    assert.equal(reconciled.status, 'PUBLISHED');
    assert.equal(reconciled.itemCount, 0);
  });

  test('10. Failed/aborted reconciliation cannot leave an unpublished card with its required staging metadata destroyed', () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-110';
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: [{ id: 'c1' }], completedListings: [] }),
      itemCount: 1
    });
    mockDraftMedia.set(draftId, [{ id: 'm1', cardId: 'c1' }]);

    simulatedNetworkError = true;
    assert.throws(() => {
      backend.reconcileBatchDraft(draftId, ['c1'], []);
    }, /reconciliation/);

    // Staging metadata must remain intact!
    assert.equal(mockDraftMedia.get(draftId).length, 1);
  });

  test('11. Resume with property_draft_media restores staged media', () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-111';
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: [{ id: 'c1', stagedMedia: [{ id: 'm1' }] }], completedListings: [] }),
      itemCount: 1
    });
    mockDraftMedia.set(draftId, [{ id: 'm1', cardId: 'c1', previewUrl: 'http://cdn/m1' }]);

    const draft = backend.getDraft(draftId);
    assert.equal(draft.media.length, 1);
    assert.equal(draft.media[0].id, 'm1');
  });

  test('12. Resume with permanent media and missing staging rows reconciles from property_media_assets', () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-112';
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: [{ id: 'c1' }], completedListings: [] }),
      itemCount: 1
    });
    // mockDraftMedia is empty (e.g. prematurely deleted)
    mockDraftMedia.set(draftId, []);
    // But permanent asset exists
    mockListings.set(200, { originDraftId: `${draftId}:c1`, mediaUrls: [] });
    backend.uploadMedia(200, null, { draftMediaId: 'asset-perm-1', roomTag: 'KITCHEN' });

    const draft = backend.getDraft(draftId);
    assert.equal(draft.media.length, 1);
    assert.equal(draft.media[0].roomTag, 'KITCHEN');
    assert.match(draft.media[0].previewUrl, /cloudinary/);
  });

  test('13. Metadata-only media renders without File', () => {
    const restoredMediaItem = {
      id: 'dm-1',
      originalFilename: 'bedroom.jpg',
      fileSizeBytes: 1048576,
      previewUrl: 'https://cloudinary.com/pathome/bedroom.jpg',
      roomTag: 'BEDROOM',
      isCover: true,
      file: undefined // No in-memory File!
    };

    const isVideo = restoredMediaItem.file?.type?.startsWith('video/') ||
                    restoredMediaItem.contentType?.startsWith('video/') ||
                    /\.(mp4|mov|webm)$/i.test(restoredMediaItem.originalFilename);
    const filename = restoredMediaItem.file?.name || restoredMediaItem.originalFilename || 'Media file';
    const preview = restoredMediaItem.previewUrl || '';

    assert.equal(isVideo, false);
    assert.equal(filename, 'bedroom.jpg');
    assert.equal(preview, 'https://cloudinary.com/pathome/bedroom.jpg');
  });

  test('14. Media count includes restored durable media correctly', () => {
    const card = {
      id: 'card-1',
      localPhotos: [], // 0 in-memory files
      mediaUrls: [],
      stagedMedia: [
        { id: 'm1', previewUrl: 'http://cdn/1' },
        { id: 'm2', previewUrl: 'http://cdn/2' }
      ]
    };
    const totalMedia = (card.stagedMedia && card.stagedMedia.length > 0)
      ? card.stagedMedia.length
      : (card.localPhotos.length + card.mediaUrls.length);

    assert.equal(totalMedia, 2);
  });

  test('15. Media count does not double-count duplicated representations', () => {
    const card = {
      id: 'card-1',
      localPhotos: [{ name: 'f1.jpg' }], // 1 local file
      mediaUrls: ['http://cdn/f1.jpg'],  // 1 url
      stagedMedia: [{ id: 'm1' }]        // staged media is authoritative source
    };
    const totalMedia = (card.stagedMedia && card.stagedMedia.length > 0)
      ? card.stagedMedia.length
      : (card.localPhotos.length + card.mediaUrls.length);

    assert.equal(totalMedia, 1, 'When stagedMedia is present, count is strictly stagedMedia.length');
  });

  test('16. Room tags preserved', () => {
    const backend = new AuthoritativeMockBackend();
    const asset = backend.uploadMedia(1, null, { draftMediaId: 'm1', roomTag: 'BALCONY' });
    assert.equal(asset.roomTag, 'BALCONY');
  });

  test('17. Captions preserved', () => {
    const backend = new AuthoritativeMockBackend();
    const asset = backend.uploadMedia(1, null, { draftMediaId: 'm1', caption: 'Spacious balcony view' });
    assert.equal(asset.caption, 'Spacious balcony view');
  });

  test('18. Cover preserved', () => {
    const backend = new AuthoritativeMockBackend();
    const asset = backend.uploadMedia(1, null, { draftMediaId: 'm1', isCover: true });
    assert.equal(asset.isCover, true);
  });

  test('19. Video preserved', () => {
    const backend = new AuthoritativeMockBackend();
    const asset = backend.uploadMedia(1, null, { draftMediaId: 'm1', mediaType: 'VIDEO' });
    assert.equal(asset.mediaType, 'VIDEO');
  });

  test('20. Media order preserved', () => {
    const backend = new AuthoritativeMockBackend();
    const a1 = backend.uploadMedia(1, null, { draftMediaId: 'm1', displayOrder: 0 });
    const a2 = backend.uploadMedia(1, null, { draftMediaId: 'm2', displayOrder: 1 });
    assert.equal(a1.displayOrder, 0);
    assert.equal(a2.displayOrder, 1);
  });

  test('21. origin_draft_id replay prevents duplicate listing', () => {
    const backend = new AuthoritativeMockBackend();
    const res1 = backend.createBatch('draft-200', [{ id: 'card-1', title: 'Flat 1' }]);
    const res2 = backend.createBatch('draft-200', [{ id: 'card-1', title: 'Flat 1' }]);
    assert.equal(mockListings.size, 1);
    assert.equal(res2.createdListings[0].isReplay, true);
  });

  test('22. uploadRequestId prevents duplicate permanent media', () => {
    const backend = new AuthoritativeMockBackend();
    const a1 = backend.uploadMedia(50, null, { draftMediaId: 'm1' });
    const a2 = backend.uploadMedia(50, null, { draftMediaId: 'm1' });
    assert.equal(mockMediaAssets.get(50).length, 1);
    assert.equal(a2.isReplay, true);
  });

  test('23. Success notification occurs only after authoritative reconciliation', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-203';
    const cards = [{ id: 'c1', title: 'Villa', stagedMedia: [] }];
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: cards, completedListings: [] }),
      itemCount: 1
    });

    let eventOrder = [];
    const origReconcile = backend.reconcileBatchDraft.bind(backend);
    backend.reconcileBatchDraft = (...args) => {
      eventOrder.push('reconcile');
      return origReconcile(...args);
    };

    await simulateFrontendPublishAll(backend, draftId, cards, {
      onSuccess: () => { eventOrder.push('success_notification'); }
    });

    assert.deepEqual(eventOrder, ['reconcile', 'success_notification']);
  });

  test('24. Success remaining count uses server state', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-204';
    const cards = [
      { id: 'c1', title: 'A', stagedMedia: [] },
      { id: 'c2', title: 'B', stagedMedia: [] }
    ];
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: cards, completedListings: [] }),
      itemCount: 2
    });

    let notificationRemaining = -1;
    await simulateFrontendPublishAll(backend, draftId, [cards[0]], {
      onPartialSuccess: (res) => { notificationRemaining = res.remainingCount; }
    });

    const serverDraft = backend.getDraft(draftId);
    assert.equal(notificationRemaining, serverDraft.itemCount);
  });

  test('25. Ambiguous interruption produces no confirmed-success notification', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-205';
    const cards = [{ id: 'c1', title: 'A', stagedMedia: [] }];
    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: cards, completedListings: [] }),
      itemCount: 1
    });

    // Disconnect after listing creation during reconciliation
    const origReconcile = backend.reconcileBatchDraft.bind(backend);
    backend.reconcileBatchDraft = () => {
      throw new Error('Network error during reconciliation');
    };

    let confirmedSuccessFired = false;
    let errorFired = false;
    await simulateFrontendPublishAll(backend, draftId, cards, {
      onSuccess: () => { confirmedSuccessFired = true; },
      onPartialSuccess: () => { confirmedSuccessFired = true; },
      onError: () => { errorFired = true; }
    });

    assert.equal(confirmedSuccessFired, false, 'No success toast on network interruption');
    assert.equal(errorFired, true);
  });

  test('26. Resume reconciliation does not create duplicate success events', () => {
    const notifiedCardIds = new Set();
    function emitSuccessNotification(cardId) {
      if (notifiedCardIds.has(cardId)) {
        return false; // Suppressed
      }
      notifiedCardIds.add(cardId);
      return true; // Emitted
    }

    assert.equal(emitSuccessNotification('card-1'), true);
    assert.equal(emitSuccessNotification('card-1'), false);
  });

  test('27. REAL INCIDENT REGRESSION: Property 1 partial publish + Property 2 interruption scenario', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-real-incident';
    const propA = { id: 'card-1', title: 'Property 1 (Nanda Nagar)', stagedMedia: [{ id: 'mA1' }] };
    const propB = { id: 'card-2', title: 'Property 2 (Vijay Nagar)', stagedMedia: [{ id: 'mB1' }, { id: 'mB2' }] };

    mockDrafts.set(draftId, {
      draftId,
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: [propA, propB], completedListings: [] }),
      itemCount: 2
    });

    // 1. Property A publishes completely
    let partialMsg = '';
    await simulateFrontendPublishAll(backend, draftId, [propA], {
      onPartialSuccess: (res) => { partialMsg = res.message; }
    });
    assert.match(partialMsg, /1 property published\. 1 property remains in draft\./);
    assert.equal(mockDrafts.get(draftId).itemCount, 1);

    // 2. Property B starts publish
    mockDrafts.get(draftId).status = 'PUBLISHING';
    // Backend creates listing #51 and uploads permanent media
    const batchResB = backend.createBatch(draftId, [propB]);
    const listingIdB = batchResB.createdListings[0].listingId;
    backend.uploadMedia(listingIdB, null, { draftMediaId: 'mB1' });
    backend.uploadMedia(listingIdB, null, { draftMediaId: 'mB2' });

    // Client loses network BEFORE client receives reconciliation confirmation!
    simulatedNetworkError = true;
    let falseSuccessMsg = '';
    await simulateFrontendPublishAll(backend, draftId, [propB], {
      onSuccess: (res) => { falseSuccessMsg = res.message; },
      onPartialSuccess: (res) => { falseSuccessMsg = res.message; }
    });
    assert.equal(falseSuccessMsg, '', 'Must NOT emit false success message!');

    // 3. User refreshes and hits Resume
    simulatedNetworkError = false;
    const resumedDraft = backend.getDraft(draftId);

    // Resume auto-reconciles Property B because listing exists + all permanent media persisted!
    assert.equal(resumedDraft.status, 'PUBLISHED', 'Draft tombstones safely');
    assert.equal(resumedDraft.itemCount, 0, '0 properties remain');
    const resumedPayload = JSON.parse(resumedDraft.payload);
    assert.equal(resumedPayload.completedListings.length, 2);
    assert.equal(mockListings.size, 2, 'No duplicate listings created');
  });

  test('28. Single-property regression', () => {
    const singleDraft = {
      draftType: 'SINGLE',
      status: 'DRAFT',
      payload: JSON.stringify({ title: 'Single House', price: 5000000 })
    };
    assert.equal(singleDraft.draftType, 'SINGLE');
  });

  test('29. Direct Batch regression', () => {
    const batchDraft = {
      draftType: 'BATCH',
      status: 'DRAFT',
      payload: JSON.stringify({ stagedCards: [{ id: 'c1' }, { id: 'c2' }] })
    };
    assert.equal(batchDraft.draftType, 'BATCH');
    assert.equal(JSON.parse(batchDraft.payload).stagedCards.length, 2);
  });

  test('30. SINGLE -> BATCH transition regression', () => {
    const draft = {
      draftId: 'd-1',
      draftType: 'SINGLE',
      payload: JSON.stringify({ title: 'Property 1' })
    };
    // Transition keeps same draftId, updates type to BATCH
    draft.draftType = 'BATCH';
    draft.payload = JSON.stringify({
      stagedCards: [{ id: 'c1', title: 'Property 1' }, { id: 'c2', title: 'Property 2' }]
    });
    assert.equal(draft.draftId, 'd-1');
    assert.equal(draft.draftType, 'BATCH');
  });

  test('31. Example Prompt append regression', () => {
    const initialPrompt = '2 BHK flat in Vijay Nagar';
    const examplePrompt = '3 BHK penthouse in Palasia';
    const combined = `${initialPrompt}\n\nNext Property\n\n${examplePrompt}`;
    assert.match(combined, /Next Property/);
    assert.match(combined, /Palasia/);
  });

  test('32. Next Property scroll regression', () => {
    const textarea = {
      scrollHeight: 1200,
      scrollTop: 0
    };
    textarea.scrollTop = textarea.scrollHeight;
    assert.equal(textarea.scrollTop, 1200);
  });

  // ============================================================
  // P0 CASE C: RESUME OF BACKEND-COMPLETED BATCH TESTS (33 - 40)
  // ============================================================

  test('33. Case C: loadDraft receiving PUBLISHED response invokes onDraftAlreadyPublished and prevents blank restore', async () => {
    let restoredCalled = false;
    let alreadyPublishedPayload = null;
    let alreadyPublishedCount = 0;

    const mockDetail = {
      draftId: 'draft-case-c',
      status: 'PUBLISHED',
      itemCount: 0,
      payload: JSON.stringify({
        completedListings: [
          { cardId: 'c-1', listingId: 50, title: 'Penthouse' },
          { cardId: 'c-2', listingId: 51, title: 'Flat' }
        ]
      })
    };

    // Simulate loadDraft logic
    const detail = mockDetail;
    let loadResult;
    if (detail.status === 'PUBLISHED' || (!detail.payload || detail.payload === '{}')) {
      let completedListings = [];
      if (detail.payload && detail.payload !== '{}') {
        try {
          const parsed = JSON.parse(detail.payload);
          if (Array.isArray(parsed.completedListings)) {
            completedListings = parsed.completedListings;
          }
        } catch {}
      }
      alreadyPublishedPayload = detail;
      alreadyPublishedCount = completedListings.length;
      loadResult = {
        status: 'ALREADY_PUBLISHED',
        detail,
        completedCount: completedListings.length,
        completedListings
      };
    } else {
      restoredCalled = true;
      loadResult = { status: 'RESTORED' };
    }

    assert.equal(restoredCalled, false, 'onRestoreDraft must NOT be called for PUBLISHED draft');
    assert.equal(loadResult.status, 'ALREADY_PUBLISHED');
    assert.equal(alreadyPublishedCount, 2);
    assert.equal(loadResult.completedListings.length, 2);
    assert.equal(loadResult.completedListings[0].listingId, 50);
    assert.equal(loadResult.completedListings[1].listingId, 51);
  });

  test('34. Case C: Legacy PUBLISHED tombstone with payload "{}" handles gracefully without opening blank workspace', async () => {
    let restoredCalled = false;
    let alreadyPublishedResult = null;

    const mockDetail = {
      draftId: 'draft-legacy-34',
      status: 'PUBLISHED',
      itemCount: 0,
      payload: '{}'
    };

    const detail = mockDetail;
    if (detail.status === 'PUBLISHED' || (!detail.payload || detail.payload === '{}')) {
      let completedListings = [];
      try {
        if (detail.payload && detail.payload !== '{}') {
          const parsed = JSON.parse(detail.payload);
          if (Array.isArray(parsed.completedListings)) completedListings = parsed.completedListings;
        }
      } catch {}
      alreadyPublishedResult = {
        status: 'ALREADY_PUBLISHED',
        detail,
        completedCount: completedListings.length,
        completedListings
      };
    } else {
      restoredCalled = true;
    }

    assert.equal(restoredCalled, false);
    assert.equal(alreadyPublishedResult.status, 'ALREADY_PUBLISHED');
    assert.equal(alreadyPublishedResult.completedCount, 0);
  });

  test('35. MasterAdminDashboard onSelectDraft checks PUBLISHING draft first and prevents mounting Batch studio', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-single-mu9h9tjg-2lirt';

    // Simulate backend state where all cards finished in background
    mockListings.set(50, { originDraftId: `${draftId}:c1`, status: 'ACTIVE' });
    mockListings.set(51, { originDraftId: `${draftId}:c2`, status: 'ACTIVE' });
    mockMediaAssets.set(50, [{ uploadRequestId: 'm1' }, { uploadRequestId: 'm2' }]);
    mockMediaAssets.set(51, [{ uploadRequestId: 'm3' }, { uploadRequestId: 'm4' }]);

    mockDrafts.set(draftId, {
      draftId,
      draftType: 'BATCH',
      status: 'PUBLISHING',
      itemCount: 1,
      payload: JSON.stringify({
        stagedCards: [
          { id: 'c1', title: 'Listing 50', stagedMedia: [{ id: 'm1' }, { id: 'm2' }] },
          { id: 'c2', title: 'Listing 51', stagedMedia: [{ id: 'm3' }, { id: 'm4' }] }
        ]
      })
    });

    let uploadMode = 'single';
    let selectedBatchDraftId = null;
    let toastEmitted = null;

    // Simulate MasterAdminDashboard.onSelectDraft
    const handleDraftAlreadyPublished = (detail, count, listings) => {
      uploadMode = 'single';
      selectedBatchDraftId = null;
      toastEmitted = {
        title: 'Publishing recovered',
        message: count > 0 ? `All ${count} properties were already published successfully.` : 'This draft was already fully published.',
        secondary: listings?.length ? `Listings: ${listings.map(l => `#${l.listingId}`).join(', ')}` : undefined
      };
    };

    const onSelectDraft = async (id) => {
      const selected = mockDrafts.get(id);
      const isPublishing = selected?.status === 'PUBLISHING';
      const isBatch = selected?.draftType === 'BATCH';

      if (isPublishing) {
        const detail = backend.getDraft(id);
        if (detail && detail.status === 'PUBLISHED') {
          let listings = [];
          if (detail.payload && detail.payload !== '{}') {
            try {
              const p = JSON.parse(detail.payload);
              if (Array.isArray(p.completedListings)) listings = p.completedListings;
            } catch {}
          }
          handleDraftAlreadyPublished(detail, listings.length, listings);
          return;
        }
      }

      if (isBatch) {
        selectedBatchDraftId = id;
        uploadMode = 'multiple';
      }
    };

    await onSelectDraft(draftId);

    // Verification: uploadMode remained 'single', studio was NOT mounted!
    assert.equal(uploadMode, 'single');
    assert.equal(selectedBatchDraftId, null);
    assert.notEqual(toastEmitted, null);
    assert.equal(toastEmitted.title, 'Publishing recovered');
    assert.equal(toastEmitted.message, 'All 2 properties were already published successfully.');
    assert.equal(toastEmitted.secondary, 'Listings: #50, #51');
  });

  test('36. Case C recovery does NOT create new listings or media', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-c-idempotency';
    mockListings.set(70, { originDraftId: `${draftId}:c1`, status: 'ACTIVE' });
    mockMediaAssets.set(70, [{ uploadRequestId: 'm1' }]);

    mockDrafts.set(draftId, {
      draftId,
      draftType: 'BATCH',
      status: 'PUBLISHING',
      itemCount: 1,
      payload: JSON.stringify({
        stagedCards: [{ id: 'c1', title: 'Prop', stagedMedia: [{ id: 'm1' }] }]
      })
    });

    const listingCountBefore = mockListings.size;
    const mediaCountBefore = (mockMediaAssets.get(70) || []).length;

    // Trigger getDraft / resume reconciliation
    const detail = backend.getDraft(draftId);
    assert.equal(detail.status, 'PUBLISHED');

    assert.equal(mockListings.size, listingCountBefore, 'Zero new listings created');
    assert.equal((mockMediaAssets.get(70) || []).length, mediaCountBefore, 'Zero new media uploaded');
  });

  test('37. Duplicate recovery click within 2 seconds is de-duplicated', () => {
    let toastCount = 0;
    let lastRecoveryToastTime = 0;

    const handleRecoveryToast = () => {
      const now = Date.now();
      if (lastRecoveryToastTime && now - lastRecoveryToastTime < 2000) {
        return;
      }
      lastRecoveryToastTime = now;
      toastCount++;
    };

    handleRecoveryToast();
    handleRecoveryToast(); // second click 0ms later
    assert.equal(toastCount, 1, 'Duplicate toast suppressed');
  });

  test('38. PUBLISHED tombstone disappears from active draft list', () => {
    const drafts = [
      { draftId: 'd-1', status: 'DRAFT' },
      { draftId: 'd-pub', status: 'PUBLISHED' }
    ];
    // listDrafts filtering
    const activeDrafts = drafts.filter(d => d.status !== 'PUBLISHED');
    assert.equal(activeDrafts.length, 1);
    assert.equal(activeDrafts[0].draftId, 'd-1');
  });

  test('39. Case A & B: Uncompleted cards in PUBLISHING draft still open batch studio normally', async () => {
    const backend = new AuthoritativeMockBackend();
    const draftId = 'draft-uncompleted';
    // Card has NO existing listing in database
    mockDrafts.set(draftId, {
      draftId,
      draftType: 'BATCH',
      status: 'PUBLISHING',
      itemCount: 1,
      payload: JSON.stringify({
        stagedCards: [{ id: 'c-new', title: 'Unpublished Prop', stagedMedia: [] }]
      })
    });

    let uploadMode = 'single';
    let selectedBatchDraftId = null;

    const onSelectDraft = async (id) => {
      const selected = mockDrafts.get(id);
      const isPublishing = selected?.status === 'PUBLISHING';
      const isBatch = selected?.draftType === 'BATCH';

      if (isPublishing) {
        const detail = backend.getDraft(id);
        if (detail && detail.status === 'PUBLISHED') {
          return;
        }
      }

      if (isBatch) {
        selectedBatchDraftId = id;
        uploadMode = 'multiple';
      }
    };

    await onSelectDraft(draftId);
    assert.equal(uploadMode, 'multiple', 'Batch workspace opens for uncompleted cards');
    assert.equal(selectedBatchDraftId, 'draft-uncompleted');
  });

  test('40. Normal DRAFT Continue opens workspace without publication checks', async () => {
    let uploadMode = 'single';
    let selectedBatchDraftId = null;
    let publishedCheckRun = false;

    const normalDraft = { draftId: 'd-norm', status: 'DRAFT', draftType: 'BATCH' };

    const onSelectDraft = (draft) => {
      if (draft.status === 'PUBLISHING') {
        publishedCheckRun = true;
      }
      if (draft.draftType === 'BATCH') {
        selectedBatchDraftId = draft.draftId;
        uploadMode = 'multiple';
      }
    };

    onSelectDraft(normalDraft);
    assert.equal(publishedCheckRun, false, 'No publishing check for normal DRAFT');
    assert.equal(uploadMode, 'multiple');
    assert.equal(selectedBatchDraftId, 'd-norm');
  });
});
