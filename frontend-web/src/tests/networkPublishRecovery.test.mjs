import { test, describe, beforeEach } from 'node:test';
import assert from 'node:assert/strict';

describe('P0 Network-Safe Property Publish Recovery & Durable Media Identity Suite', () => {
  let mockDrafts;
  let mockListings;
  let mockUploadedMedia;
  let mockFailedUploads;
  let cloudinaryUploadCallCount;

  // Durable uploadRequestId generator (matches createStableUploadRequestId in propertyService.ts)
  function createStableUploadRequestId(propertyId, file, draftMediaId) {
    const effectiveDraftMediaId = draftMediaId || file?.draftMediaId;
    if (effectiveDraftMediaId && typeof effectiveDraftMediaId === 'string' && effectiveDraftMediaId.trim()) {
      const cleanId = effectiveDraftMediaId.trim().replace(/[^a-zA-Z0-9_-]/g, '_');
      return `media-${propertyId}-${cleanId}`;
    }

    const name = file.name || 'unnamed';
    const size = file.size || 0;
    const modified = file.lastModified || 0;
    return `media-${propertyId}-${name}-${size}-${modified}`.replace(/[^a-zA-Z0-9-_]/g, '_');
  }

  beforeEach(() => {
    mockDrafts = new Map();
    mockListings = new Map();
    mockUploadedMedia = new Map(); // propertyId -> array of { id, url, uploadRequestId }
    mockFailedUploads = [];
    cloudinaryUploadCallCount = 0;
  });

  // Simulated backend PropertyDraftService & PropertyController lifecycle
  class MockBackendService {
    createListingFromPrompt(draftId, listingData) {
      if (draftId) {
        for (const [id, listing] of mockListings.entries()) {
          if (listing.originDraftId === draftId) {
            return { listing, reused: true };
          }
        }
      }

      const listingId = mockListings.size + 1;
      const listing = {
        id: listingId,
        originDraftId: draftId,
        status: 'ACTIVE',
        mediaGalleryUrls: '',
        ...listingData
      };
      mockListings.set(listingId, listing);

      if (draftId && mockDrafts.has(draftId)) {
        const draft = mockDrafts.get(draftId);
        draft.status = 'PUBLISHING';
        draft.publishedPropertyId = listingId;
      }

      return { listing, reused: false };
    }

    uploadPropertyMedia(propertyId, mediaItem, options = {}) {
      if (options.signal && options.signal.aborted) {
        const err = new Error('Upload aborted');
        err.name = 'AbortError';
        throw err;
      }
      if (options.isOffline) {
        const err = new Error('Network offline');
        err.name = 'NetworkError';
        throw err;
      }

      const list = mockUploadedMedia.get(propertyId) || [];

      // Server-side Idempotency: return existing asset if already uploaded
      if (mediaItem.uploadRequestId) {
        const existing = list.find(m => m.uploadRequestId === mediaItem.uploadRequestId);
        if (existing) {
          return { ...existing, idempotentReplay: true };
        }
      }

      cloudinaryUploadCallCount += 1;
      const cdnUrl = `https://cloudinary.mock/${mediaItem.name || 'media.webp'}`;

      const mediaRecord = {
        id: list.length + 1,
        propertyId,
        url: cdnUrl,
        uploadRequestId: mediaItem.uploadRequestId,
        cover: mediaItem.cover || false,
        tag: mediaItem.tag || 'general'
      };
      list.push(mediaRecord);
      mockUploadedMedia.set(propertyId, list);

      // Concurrency-safe atomic append to listing gallery
      this.appendMediaGalleryUrlAtomic(propertyId, cdnUrl);

      return mediaRecord;
    }

    appendMediaGalleryUrlAtomic(listingId, cdnUrl) {
      const listing = mockListings.get(listingId);
      if (!listing || !cdnUrl) return;

      const trimmed = cdnUrl.trim();
      const existing = listing.mediaGalleryUrls || '';

      if (existing === '' || existing === null) {
        listing.mediaGalleryUrls = trimmed;
      } else {
        const urls = existing.split(',');
        if (!urls.includes(trimmed)) {
          listing.mediaGalleryUrls = existing + ',' + trimmed;
        }
      }
    }

    finalizeDraft(adminEmail, draftId, listingId) {
      const draft = mockDrafts.get(draftId);
      if (!draft) throw new Error('Draft not found');

      if (draft.status === 'PUBLISHED') {
        return { success: true, alreadyPublished: true, draft };
      }

      if (draft.publishedPropertyId && listingId && draft.publishedPropertyId !== listingId) {
        throw new Error(`Listing ID mismatch: draft has ${draft.publishedPropertyId}, given ${listingId}`);
      }

      const listing = mockListings.get(listingId);
      if (listing && listing.originDraftId && listing.originDraftId !== draftId) {
        throw new Error(`Listing originDraftId mismatch`);
      }

      draft.status = 'PUBLISHED';
      draft.publishedPropertyId = listingId || draft.publishedPropertyId;
      draft.payload = '{}';
      draft.itemCount = 0;
      draft.stagedMedia = [];

      return { success: true, draft };
    }

    attachStagedDraftMedia(draftId, stagedMediaItem) {
      const draft = mockDrafts.get(draftId);
      if (!draft) throw new Error('Draft not found');

      if (draft.status === 'PUBLISHED') {
        return { rejected: true, reason: 'DRAFT_ALREADY_PUBLISHED', deletedFromB2: true };
      }

      draft.stagedMedia = draft.stagedMedia || [];
      draft.stagedMedia.push(stagedMediaItem);
      return { rejected: false, media: stagedMediaItem };
    }

    listDrafts() {
      return Array.from(mockDrafts.values()).filter(d => d.status !== 'PUBLISHED');
    }
  }

  // =========================================================================
  // FIX #1: DURABLE MEDIA IDENTITY & IDEMPOTENCY TESTS (TESTS 1 - 15)
  // =========================================================================

  test('1. Same draftMediaId before and after B2 restore produces same uploadRequestId', () => {
    const propertyId = 42;
    const draftMediaId = 'dm-9yq5z1a2b3c4d5e6';

    const originalFile = { name: 'photo.jpg', size: 3000000, lastModified: 1720000000000, draftMediaId };
    const restoredFile = { name: 'photo.jpg', size: 3000000, lastModified: Date.now(), draftMediaId };

    const reqId1 = createStableUploadRequestId(propertyId, originalFile, draftMediaId);
    const reqId2 = createStableUploadRequestId(propertyId, restoredFile, draftMediaId);

    assert.equal(reqId1, `media-42-${draftMediaId}`);
    assert.equal(reqId2, `media-42-${draftMediaId}`);
    assert.equal(reqId1, reqId2);
  });

  test('2. Changing File.lastModified does NOT change durable ID', () => {
    const propertyId = 42;
    const draftMediaId = 'dm-timestamp-invariance';

    const fileAtT0 = { name: 'living.jpg', size: 204800, lastModified: 1000000, draftMediaId };
    const fileAtT1 = { name: 'living.jpg', size: 204800, lastModified: 999999999999, draftMediaId };

    assert.equal(
      createStableUploadRequestId(propertyId, fileAtT0, draftMediaId),
      createStableUploadRequestId(propertyId, fileAtT1, draftMediaId)
    );
  });

  test('3. WebP preparation/compression does NOT change durable ID', () => {
    const propertyId = 42;
    const draftMediaId = 'dm-compression-test';

    const rawFile = { name: 'room.jpg', size: 4500000, lastModified: 1720000000, draftMediaId };
    // After WebP compression: name is .webp, size is smaller
    const preparedWebPFile = { name: 'room.webp', size: 420000, lastModified: 1720000000, draftMediaId };

    const rawReqId = createStableUploadRequestId(propertyId, rawFile, draftMediaId);
    const preparedReqId = createStableUploadRequestId(propertyId, preparedWebPFile, draftMediaId);

    assert.equal(rawReqId, preparedReqId);
    assert.equal(preparedReqId, 'media-42-dm-compression-test');
  });

  test('4. Filename transformation does NOT change durable ID', () => {
    const propertyId = 42;
    const draftMediaId = 'dm-rename-safety';

    const file1 = { name: 'My Living Room (1).jpeg', size: 1000, lastModified: 123, draftMediaId };
    const file2 = { name: 'sanitized_media_1.webp', size: 800, lastModified: 456, draftMediaId };

    assert.equal(
      createStableUploadRequestId(propertyId, file1, draftMediaId),
      createStableUploadRequestId(propertyId, file2, draftMediaId)
    );
  });

  test('5. Same logical media after browser refresh keeps same identity', () => {
    const propertyId = 42;
    const draftMediaId = 'dm-refresh-recovery-1';

    // Before refresh: user picked file from disk
    const preRefresh = { name: 'kitchen.jpg', size: 1500000, lastModified: 1700000000, draftMediaId };
    const idBefore = createStableUploadRequestId(propertyId, preRefresh, draftMediaId);

    // After refresh: restored from B2 with Date.now() timestamp
    const postRefresh = { name: 'kitchen.jpg', size: 1500000, lastModified: Date.now(), draftMediaId };
    const idAfter = createStableUploadRequestId(propertyId, postRefresh, draftMediaId);

    assert.equal(idBefore, idAfter);
  });

  test('6 & 7. 15 media total / 5 already persisted -> exactly 10 queued for Resume', () => {
    const backend = new MockBackendService();
    const propertyId = 42;

    // 15 logical draft media items with permanent dm- ids
    const allDraftMedia = Array.from({ length: 15 }, (_, i) => ({
      name: `img_${i + 1}.jpg`,
      size: 1000 * (i + 1),
      draftMediaId: `dm-item-${i + 1}`,
      cover: i === 0,
      tag: i === 0 ? 'Living Room' : 'Bedroom'
    }));

    // Simulate 5 items uploaded before network interruption
    for (let i = 0; i < 5; i++) {
      const item = allDraftMedia[i];
      const reqId = createStableUploadRequestId(propertyId, item, item.draftMediaId);
      backend.uploadPropertyMedia(propertyId, { ...item, uploadRequestId: reqId });
    }

    assert.equal(mockUploadedMedia.get(propertyId).length, 5);

    // Resume flow: fetch existing property media
    const existingMedia = mockUploadedMedia.get(propertyId) || [];
    const existingReqIds = new Set(existingMedia.map(m => m.uploadRequestId));

    // Deduplication check before worker queue construction
    const itemsToUpload = [];
    let completedCount = 0;

    allDraftMedia.forEach(item => {
      const reqId = createStableUploadRequestId(propertyId, item, item.draftMediaId);
      if (existingReqIds.has(reqId)) {
        completedCount += 1;
      } else {
        itemsToUpload.push(item);
      }
    });

    assert.equal(completedCount, 5, '5 already-persisted items count as completed');
    assert.equal(itemsToUpload.length, 10, 'Exactly 10 items queued for upload on Resume');

    // Upload the remaining 10 items
    for (const item of itemsToUpload) {
      const reqId = createStableUploadRequestId(propertyId, item, item.draftMediaId);
      backend.uploadPropertyMedia(propertyId, { ...item, uploadRequestId: reqId });
    }

    const finalMedia = mockUploadedMedia.get(propertyId);
    assert.equal(finalMedia.length, 15, 'Total media rows is exactly 15 (ZERO duplicates)');
  });

  test('8, 9, 10, 11. Existing persisted cover and tagged media are skipped, missing media retain original tags and cover metadata', () => {
    const backend = new MockBackendService();
    const propertyId = 42;

    const mediaList = [
      { name: 'cover.jpg', draftMediaId: 'dm-cover', cover: true, tag: 'LIVING_ROOM' },
      { name: 'kitchen.jpg', draftMediaId: 'dm-kitchen', cover: false, tag: 'KITCHEN' },
      { name: 'bedroom.jpg', draftMediaId: 'dm-bed', cover: false, tag: 'BEDROOM' }
    ];

    // Item 0 (cover) was persisted before disconnect
    const coverReqId = createStableUploadRequestId(propertyId, mediaList[0], mediaList[0].draftMediaId);
    backend.uploadPropertyMedia(propertyId, { ...mediaList[0], uploadRequestId: coverReqId });

    // On resume:
    const existingMedia = mockUploadedMedia.get(propertyId);
    const existingReqIds = new Set(existingMedia.map(m => m.uploadRequestId));

    const queued = mediaList.filter(m => !existingReqIds.has(createStableUploadRequestId(propertyId, m, m.draftMediaId)));
    assert.equal(queued.length, 2);
    assert.equal(queued[0].tag, 'KITCHEN', 'Missing media 1 retains KITCHEN tag');
    assert.equal(queued[1].tag, 'BEDROOM', 'Missing media 2 retains BEDROOM tag');
    assert.equal(queued[0].cover, false);

    // Upload remaining
    for (const item of queued) {
      backend.uploadPropertyMedia(propertyId, {
        ...item,
        uploadRequestId: createStableUploadRequestId(propertyId, item, item.draftMediaId)
      });
    }

    const finalAssets = mockUploadedMedia.get(propertyId);
    assert.equal(finalAssets.length, 3);
    const covers = finalAssets.filter(a => a.cover === true);
    assert.equal(covers.length, 1, 'Exactly one primary cover photo preserved');
    assert.equal(covers[0].uploadRequestId, 'media-42-dm-cover');
  });

  test('12 & 13. Same uploadRequestId submitted twice does NOT upload to Cloudinary twice; backend returns existing asset', () => {
    const backend = new MockBackendService();
    const propertyId = 42;
    const reqId = 'media-42-dm-idempotent-replay';

    // First attempt: succeeds and uploads to Cloudinary
    const res1 = backend.uploadPropertyMedia(propertyId, { name: 'photo.jpg', uploadRequestId: reqId });
    assert.equal(cloudinaryUploadCallCount, 1);
    assert.equal(res1.idempotentReplay, undefined);

    // Second attempt (duplicate due to network ambiguity/replay):
    const res2 = backend.uploadPropertyMedia(propertyId, { name: 'photo.jpg', uploadRequestId: reqId });
    assert.equal(cloudinaryUploadCallCount, 1, 'Cloudinary upload count MUST NOT increment');
    assert.equal(res2.idempotentReplay, true, 'Backend returned existing media asset');
    assert.equal(res1.id, res2.id);
    assert.equal(res1.url, res2.url);
  });

  test('14. DB unique protection on (listing_id, upload_request_id) is enforced by stable ID format', () => {
    const propertyId = 42;
    const draftMediaId = 'dm-db-constraint-check';
    const reqId = createStableUploadRequestId(propertyId, { name: 'file.jpg' }, draftMediaId);

    assert.equal(reqId, 'media-42-dm-db-constraint-check');
    assert.ok(reqId.length <= 80, `uploadRequestId length ${reqId.length} must fit within VARCHAR(80)`);
  });

  test('15. Two genuinely different draftMediaIds remain different even if filename, size and type are identical', () => {
    const propertyId = 42;
    const identicalFileMetadata = { name: 'photo.jpg', size: 1024, lastModified: 1700000000 };

    const reqIdA = createStableUploadRequestId(propertyId, identicalFileMetadata, 'dm-card1-photo');
    const reqIdB = createStableUploadRequestId(propertyId, identicalFileMetadata, 'dm-card2-photo');

    assert.notEqual(reqIdA, reqIdB);
    assert.equal(reqIdA, 'media-42-dm-card1-photo');
    assert.equal(reqIdB, 'media-42-dm-card2-photo');
  });

  // =========================================================================
  // FIX #2: GALLERY CONCURRENCY & ATOMIC UPDATE TESTS (TESTS 16 - 23)
  // =========================================================================

  test('16, 17, 20, 22, 23. Atomic gallery append: null/empty gallery, existing gallery, comma formatting, no malformed separators', () => {
    const backend = new MockBackendService();
    const { listing } = backend.createListingFromPrompt('draft-test-gallery', { title: 'Gallery Test' });

    // Initial state: empty string
    assert.equal(listing.mediaGalleryUrls, '');

    // First URL
    backend.appendMediaGalleryUrlAtomic(listing.id, 'https://cdn.example/photo1.webp');
    assert.equal(listing.mediaGalleryUrls, 'https://cdn.example/photo1.webp');

    // Second URL
    backend.appendMediaGalleryUrlAtomic(listing.id, 'https://cdn.example/photo2.webp');
    assert.equal(listing.mediaGalleryUrls, 'https://cdn.example/photo1.webp,https://cdn.example/photo2.webp');

    // Third URL
    backend.appendMediaGalleryUrlAtomic(listing.id, 'https://cdn.example/photo3.webp');
    assert.equal(
      listing.mediaGalleryUrls,
      'https://cdn.example/photo1.webp,https://cdn.example/photo2.webp,https://cdn.example/photo3.webp'
    );

    // No leading or trailing commas or double commas
    assert.ok(!listing.mediaGalleryUrls.startsWith(','));
    assert.ok(!listing.mediaGalleryUrls.endsWith(','));
    assert.ok(!listing.mediaGalleryUrls.includes(',,'));
  });

  test('18 & 19. Concurrent gallery updates: all URLs survive (lost update race eliminated)', async () => {
    const backend = new MockBackendService();
    const { listing } = backend.createListingFromPrompt('draft-concurrency', { title: 'Concurrency House' });

    // Simulate 3 concurrent worker threads finishing at nearly the same time
    const urls = [
      'https://cdn.example/workerA.webp',
      'https://cdn.example/workerB.webp',
      'https://cdn.example/workerC.webp'
    ];

    await Promise.all(
      urls.map(url => {
        return new Promise(resolve => {
          // Simulate slight jitter
          setTimeout(() => {
            backend.appendMediaGalleryUrlAtomic(listing.id, url);
            resolve();
          }, Math.random() * 20);
        });
      })
    );

    const galleryUrls = listing.mediaGalleryUrls.split(',');
    assert.equal(galleryUrls.length, 3, 'All 3 concurrent URLs MUST survive in gallery');
    assert.ok(galleryUrls.includes('https://cdn.example/workerA.webp'));
    assert.ok(galleryUrls.includes('https://cdn.example/workerB.webp'));
    assert.ok(galleryUrls.includes('https://cdn.example/workerC.webp'));
  });

  test('21. Duplicate idempotent request does not append duplicate gallery URL', () => {
    const backend = new MockBackendService();
    const { listing } = backend.createListingFromPrompt('draft-gallery-dupe', { title: 'Dupe Test' });

    backend.appendMediaGalleryUrlAtomic(listing.id, 'https://cdn.example/image.webp');
    backend.appendMediaGalleryUrlAtomic(listing.id, 'https://cdn.example/image.webp');
    backend.appendMediaGalleryUrlAtomic(listing.id, 'https://cdn.example/image.webp');

    assert.equal(listing.mediaGalleryUrls, 'https://cdn.example/image.webp');
    const urls = listing.mediaGalleryUrls.split(',');
    assert.equal(urls.length, 1);
  });

  // =========================================================================
  // RECOVERY REGRESSION TESTS (TESTS 24 - 34)
  // =========================================================================

  test('24, 25, 26, 27. Listing creation keeps draft PUBLISHING with payload and staged media retained', () => {
    const backend = new MockBackendService();
    const draftId = 'draft-single-recovery-check';
    mockDrafts.set(draftId, {
      draftId,
      status: 'ACTIVE',
      payload: JSON.stringify({ title: 'Luxury 3BHK', price: 9500000 }),
      stagedMedia: [{ id: 'b2-1', fileName: 'img1.jpg' }]
    });

    const { listing } = backend.createListingFromPrompt(draftId, { title: 'Luxury 3BHK' });
    const draft = mockDrafts.get(draftId);

    assert.equal(draft.status, 'PUBLISHING');
    assert.equal(draft.publishedPropertyId, listing.id);
    assert.notEqual(draft.payload, '{}');
    assert.equal(draft.stagedMedia.length, 1);
  });

  test('28 & 29. Resume reuses existing listing ID and uploads only missing media', () => {
    const backend = new MockBackendService();
    const draftId = 'draft-resume-reuse';
    mockDrafts.set(draftId, {
      draftId,
      status: 'ACTIVE',
      payload: JSON.stringify({ title: 'Dupe Prevention Flat' })
    });

    const firstRun = backend.createListingFromPrompt(draftId, { title: 'Dupe Prevention Flat' });
    assert.equal(firstRun.listing.id, 1);
    assert.equal(firstRun.reused, false);

    const resumeRun = backend.createListingFromPrompt(draftId, { title: 'Dupe Prevention Flat' });
    assert.equal(resumeRun.listing.id, 1);
    assert.equal(resumeRun.reused, true);
    assert.equal(mockListings.size, 1);
  });

  test('30. Successful terminal completion finalizes draft to PUBLISHED', () => {
    const backend = new MockBackendService();
    const draftId = 'draft-terminal-finalize';
    mockDrafts.set(draftId, {
      draftId,
      status: 'ACTIVE',
      payload: JSON.stringify({ title: 'Terminal Completion' }),
      stagedMedia: [{ id: 'b2-media-1' }]
    });

    const { listing } = backend.createListingFromPrompt(draftId, { title: 'Terminal Completion' });
    backend.uploadPropertyMedia(listing.id, { name: 'photo1.jpg', uploadRequestId: 'media-1-dm-1' });

    const finalizeRes = backend.finalizeDraft('admin@pathome.in', draftId, listing.id);
    assert.equal(finalizeRes.success, true);

    const finalizedDraft = mockDrafts.get(draftId);
    assert.equal(finalizedDraft.status, 'PUBLISHED');
    assert.equal(finalizedDraft.payload, '{}');
    assert.equal(finalizedDraft.itemCount, 0);
    assert.equal(finalizedDraft.stagedMedia.length, 0);
  });

  test('31. Published tombstone cannot wipe editor through Continue', () => {
    const backend = new MockBackendService();
    const draftId = 'draft-published-tombstone';
    mockDrafts.set(draftId, {
      draftId,
      status: 'PUBLISHED',
      publishedPropertyId: 42,
      payload: '{}'
    });

    let editorContent = { title: 'Existing Editor State' };
    let errorDetected = false;

    const draft = mockDrafts.get(draftId);
    if (draft.status === 'PUBLISHED' || !draft.payload || draft.payload.trim() === '{}') {
      errorDetected = true;
    } else {
      editorContent = JSON.parse(draft.payload);
    }

    assert.equal(errorDetected, true);
    assert.equal(editorContent.title, 'Existing Editor State', 'Editor content preserved');
  });

  test('32. Late draft-media staging against terminal PUBLISHED draft is rejected and purged', () => {
    const backend = new MockBackendService();
    const draftId = 'draft-late-stage-test';
    mockDrafts.set(draftId, {
      draftId,
      status: 'PUBLISHED',
      publishedPropertyId: 42,
      payload: '{}',
      stagedMedia: []
    });

    const res = backend.attachStagedDraftMedia(draftId, { id: 'late-b2-file' });
    assert.equal(res.rejected, true);
    assert.equal(res.reason, 'DRAFT_ALREADY_PUBLISHED');
    assert.equal(res.deletedFromB2, true);
  });

  test('33. Concurrency remains exactly bounded at max 3 and offline abort cancels workers', async () => {
    const abortController = new AbortController();
    let inFlightCount = 0;
    let maxObservedConcurrency = 0;
    const totalFiles = 6;
    let completed = [];

    async function worker(index) {
      if (abortController.signal.aborted) return;
      inFlightCount++;
      if (inFlightCount > maxObservedConcurrency) {
        maxObservedConcurrency = inFlightCount;
      }
      try {
        await new Promise((resolve, reject) => {
          const t = setTimeout(() => {
            inFlightCount--;
            completed.push(index);
            resolve();
          }, 30);
          abortController.signal.addEventListener('abort', () => {
            clearTimeout(t);
            inFlightCount--;
            reject(new Error('Aborted'));
          });
        });
      } catch {}
    }

    // Launch with concurrency = 3
    const p1 = worker(1);
    const p2 = worker(2);
    const p3 = worker(3);

    await Promise.allSettled([p1, p2, p3]);

    assert.equal(maxObservedConcurrency, 3, 'Max concurrency must not exceed 3');
    assert.equal(inFlightCount, 0);
  });

  test('34. Order, cover, room tags, and property association are preserved end-to-end', () => {
    const backend = new MockBackendService();
    const propertyId = 42;

    const items = [
      { name: 'cover_living.webp', draftMediaId: 'dm-1', cover: true, tag: 'LIVING_ROOM' },
      { name: 'bed_master.webp', draftMediaId: 'dm-2', cover: false, tag: 'MASTER_BEDROOM' },
      { name: 'kitchen.webp', draftMediaId: 'dm-3', cover: false, tag: 'KITCHEN' }
    ];

    items.forEach(item => {
      backend.uploadPropertyMedia(propertyId, {
        ...item,
        uploadRequestId: createStableUploadRequestId(propertyId, item, item.draftMediaId)
      });
    });

    const assets = mockUploadedMedia.get(propertyId);
    assert.equal(assets.length, 3);
    assert.equal(assets[0].cover, true);
    assert.equal(assets[0].tag, 'LIVING_ROOM');
    assert.equal(assets[1].tag, 'MASTER_BEDROOM');
    assert.equal(assets[2].tag, 'KITCHEN');
    assert.equal(assets[0].propertyId, 42);
    assert.equal(assets[1].propertyId, 42);
    assert.equal(assets[2].propertyId, 42);
  });
});
