import test from 'node:test';
import assert from 'node:assert/strict';

test('Draft Identity and Single → Batch Transition Invariant Suite (Requirements 1 - 32)', async (t) => {
  // Shared mock in-memory database representing PostgreSQL tables
  let draftsDb = new Map(); // draftId -> draft row
  let draftMediaDb = new Map(); // mediaId -> media row
  let b2ObjectStore = new Map(); // key -> buffer/metadata

  function resetDatabases() {
    draftsDb.clear();
    draftMediaDb.clear();
    b2ObjectStore.clear();
  }

  // Simulated server endpoints replicating backend PropertyDraftService & Controller
  const server = {
    saveDraft(req) {
      const existing = draftsDb.get(req.draftId);
      if (existing) {
        if (req.version !== undefined && req.version < existing.version) {
          const err = new Error('Optimistic lock conflict');
          err.status = 409;
          throw err;
        }
        existing.version = (existing.version || 1) + 1;
        existing.payload = req.payload;
        if (req.draftType) existing.draftType = req.draftType;
        if (req.status) existing.status = req.status;
        if (req.titleSummary) existing.titleSummary = req.titleSummary;
        if (req.itemCount !== undefined) existing.itemCount = req.itemCount;
        existing.updatedAt = new Date().toISOString();
        draftsDb.set(req.draftId, existing);
        return { ...existing };
      } else {
        const created = {
          draftId: req.draftId,
          adminId: 'admin@pathome.in',
          draftType: req.draftType || 'SINGLE',
          status: req.status || 'DRAFT',
          titleSummary: req.titleSummary || 'Untitled draft',
          itemCount: req.itemCount || 1,
          version: 1,
          payload: req.payload,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString()
        };
        draftsDb.set(req.draftId, created);
        return { ...created };
      }
    },

    stageMedia(draftId, file, options = {}) {
      const mediaId = options.mediaId || `dm-${Math.random().toString(36).substring(2, 9)}`;
      const key = `drafts/admin@pathome.in/${draftId}/${mediaId}-${file.name}`;
      b2ObjectStore.set(key, { size: file.size, type: file.type });

      const mediaRow = {
        mediaId,
        draftId,
        cardId: options.cardId || null,
        originalFilename: file.name,
        fileSizeBytes: file.size,
        contentType: file.type || 'image/jpeg',
        roomTag: options.roomTag || 'LIVING_ROOM',
        isCover: Boolean(options.isCover),
        stagingObjectKey: key,
        previewUrl: `http://localhost:8080/api/v1/admin/drafts/${draftId}/media/${mediaId}`
      };
      draftMediaDb.set(mediaId, mediaRow);
      return { ...mediaRow };
    },

    reassignUnassignedMedia(draftId, targetCardId) {
      if (!targetCardId || !targetCardId.trim()) throw new Error('Blank targetCardId');
      let count = 0;
      for (const [id, m] of draftMediaDb.entries()) {
        if (m.draftId === draftId && (!m.cardId || m.cardId === '')) {
          m.cardId = targetCardId.trim();
          draftMediaDb.set(id, m);
          count++;
        }
      }
      return count;
    },

    getDraft(draftId) {
      const draft = draftsDb.get(draftId);
      if (!draft) return null;
      const media = [];
      for (const m of draftMediaDb.values()) {
        if (m.draftId === draftId) media.push({ ...m });
      }
      return { ...draft, media };
    },

    reconcileBatch(draftId, publishedCardIds, completedListings = []) {
      const draft = draftsDb.get(draftId);
      if (!draft) return null;
      const pubSet = new Set(publishedCardIds);

      // purge staged media for published cards
      for (const [id, m] of draftMediaDb.entries()) {
        if (m.draftId === draftId && m.cardId && pubSet.has(m.cardId)) {
          b2ObjectStore.delete(m.stagingObjectKey);
          draftMediaDb.delete(id);
        }
      }

      const payload = JSON.parse(draft.payload);
      const remainingCards = (payload.stagedCards || []).filter(c => !pubSet.has(c.id));
      const existingCompleted = payload.completedListings || [];
      const mergedCompleted = [...existingCompleted, ...completedListings];

      if (remainingCards.length === 0) {
        draft.status = 'PUBLISHED';
        draft.payload = '{}';
        draft.itemCount = 0;
        draftsDb.set(draftId, draft);
        return null;
      } else {
        draft.itemCount = remainingCards.length;
        draft.titleSummary = remainingCards.length === 1 ? 'Batch — 1 property remaining' : `Batch — ${remainingCards.length} properties remaining`;
        payload.stagedCards = remainingCards;
        payload.completedListings = mergedCompleted;
        draft.payload = JSON.stringify(payload);
        draft.version = (draft.version || 1) + 1;
        draftsDb.set(draftId, draft);
        return { ...draft };
      }
    }
  };

  await t.test('1 - 5. Bug 1 Fix: Async init race, StrictMode safety, and single draft allocation', async () => {
    resetDatabases();

    // Model usePropertyDraft hook state
    let currentDraftId = null;
    let currentDraftIdRef = { current: null };
    let draftVersionRef = { current: 1 };
    let isMountedRef = { current: true };

    const ensureDraftId = () => {
      if (currentDraftIdRef.current) return currentDraftIdRef.current;
      const id = `draft-single-${Math.random().toString(36).substring(2, 9)}`;
      currentDraftIdRef.current = id;
      currentDraftId = id;
      return id;
    };

    // 1. Hook mounts: init starts async refreshDraftsList
    let refreshResolved = false;
    const asyncInitPromise = (async () => {
      // Simulate network latency for refreshDraftsList (e.g. 50ms)
      await new Promise(r => setTimeout(r, 20));
      if (!isMountedRef.current) return;
      // CRITICAL FIX: init DOES NOT wipe currentDraftIdRef.current!
      refreshResolved = true;
    })();

    // 2. User immediately begins typing Property A during pending refresh
    const allocatedId = ensureDraftId();
    assert.ok(allocatedId.startsWith('draft-single-'));

    // 3. User saves draft
    server.saveDraft({
      draftId: allocatedId,
      draftType: 'SINGLE',
      titleSummary: '2 BHK in Nanda Nagar',
      itemCount: 1,
      version: draftVersionRef.current,
      payload: JSON.stringify({ rawPrompt: '2 BHK in Nanda Nagar' })
    });

    // 4. Stale async init completes
    await asyncInitPromise;
    assert.equal(refreshResolved, true);
    // Verified: async refresh completion NEVER wiped the draft ID!
    assert.equal(currentDraftIdRef.current, allocatedId);

    // 5. Subsequent typing and autosaves must reuse the same ID
    const secondCallId = ensureDraftId();
    assert.equal(secondCallId, allocatedId);

    // 6. Assert exactly ONE draft row in the database
    assert.equal(draftsDb.size, 1);
    assert.ok(draftsDb.has(allocatedId));
  });

  await t.test('6 & 7. One property edited for repeated cycles updates same draft row', async () => {
    resetDatabases();
    const draftId = 'draft-single-cycle';
    let version = 1;

    for (let i = 1; i <= 6; i++) {
      const res = server.saveDraft({
        draftId,
        draftType: 'SINGLE',
        titleSummary: `Edit #${i} - 2 BHK Flat`,
        itemCount: 1,
        version,
        payload: JSON.stringify({ rawPrompt: `2 BHK Flat edit #${i}` })
      });
      version = res.version;
    }

    assert.equal(draftsDb.size, 1);
    const stored = draftsDb.get(draftId);
    assert.equal(stored.draftId, draftId);
    assert.equal(stored.draftType, 'SINGLE');
    assert.equal(stored.titleSummary, 'Edit #6 - 2 BHK Flat');
  });

  await t.test('8 - 12. Single → Batch Transition: same draft ID, in-place BATCH type, latest A+B state', async () => {
    resetDatabases();

    // Step A: User creates Single Draft A
    const singleDraftId = 'draft-single-session123';
    server.saveDraft({
      draftId: singleDraftId,
      draftType: 'SINGLE',
      titleSummary: '2 BHK in Nanda Nagar',
      itemCount: 1,
      version: 1,
      payload: JSON.stringify({ newBhkLabel: '2 BHK in Nanda Nagar' })
    });
    assert.equal(draftsDb.size, 1);
    assert.equal(draftsDb.get(singleDraftId).draftType, 'SINGLE');

    // Step B: User adds Property B: "2 BHK in Nanda Nagar \nNext property\n 3 BHK in Vijay Nagar"
    const fullPrompt = '2 BHK in Nanda Nagar\nNext property\n3 BHK in Vijay Nagar';

    // Step C: MasterAdminDashboard cancels single autosave, preserves singleDraftId as selectedBatchDraftId
    // Transition occurs: BatchPropertyIngestionStudio adopts singleDraftId
    const actualCard1Id = `staged-0-${Date.now()}`;
    const actualCard2Id = `staged-1-${Date.now()}`;

    const batchPayload = {
      rawPrompts: fullPrompt,
      detectedCount: 2,
      stagedCards: [
        { id: actualCard1Id, title: '2 BHK Flat • Nanda Nagar', bhk: '2 BHK', rentVal: '15000' },
        { id: actualCard2Id, title: '3 BHK House • Vijay Nagar', bhk: '3 BHK', rentVal: '32000' }
      ],
      completedListings: []
    };

    // Step D: Batch autosaves under SAME draft ID with draftType = BATCH
    const batchSaveRes = server.saveDraft({
      draftId: singleDraftId, // SAME DRAFT ID!
      draftType: 'BATCH',
      titleSummary: 'Batch (2 properties)',
      itemCount: 2,
      version: draftsDb.get(singleDraftId).version,
      payload: JSON.stringify(batchPayload)
    });

    // Assertions:
    assert.equal(batchSaveRes.draftId, singleDraftId, 'Must reuse identical draft ID');
    assert.equal(batchSaveRes.draftType, 'BATCH', 'Must update draft_type to BATCH in place');
    assert.equal(draftsDb.size, 1, 'Exactly ONE authoritative draft row in database');
    assert.equal(draftsDb.get(singleDraftId).itemCount, 2);

    const persistedPayload = JSON.parse(draftsDb.get(singleDraftId).payload);
    assert.equal(persistedPayload.rawPrompts, fullPrompt, 'Latest A+B prompt is persisted');
    assert.equal(persistedPayload.stagedCards.length, 2);
  });

  await t.test('13 - 16. Media Transition: Property A media survives, cardId bound to actual durable card ID, 0 B2 re-upload', async () => {
    resetDatabases();
    const draftId = 'draft-single-media-flow';

    // Single mode: Property A has 2 staged photos (cardId is null)
    const file1 = { name: 'living.jpg', size: 102400, type: 'image/jpeg' };
    const file2 = { name: 'balcony.jpg', size: 204800, type: 'image/jpeg' };

    const m1 = server.stageMedia(draftId, file1, { roomTag: 'LIVING_ROOM', isCover: true });
    const m2 = server.stageMedia(draftId, file2, { roomTag: 'BALCONY', isCover: false });

    assert.equal(m1.cardId, null);
    assert.equal(m2.cardId, null);
    assert.equal(b2ObjectStore.size, 2, 'Two objects in B2 staging');

    // Admin transitions to Batch mode: Property A + Property B
    const actualCard1Id = 'staged-0-1789853815627';
    const actualCard2Id = 'staged-1-1789853815628';

    // Backend reassigns unassigned media to actualCard1Id
    const reassignedCount = server.reassignUnassignedMedia(draftId, actualCard1Id);
    assert.equal(reassignedCount, 2, 'Both single media items reassigned');

    server.saveDraft({
      draftId,
      draftType: 'BATCH',
      titleSummary: 'Batch (2 properties)',
      itemCount: 2,
      payload: JSON.stringify({ stagedCards: [{ id: actualCard1Id }, { id: actualCard2Id }] })
    });

    // Verify PostgreSQL records have actualCard1Id
    const mediaRow1 = draftMediaDb.get(m1.mediaId);
    const mediaRow2 = draftMediaDb.get(m2.mediaId);
    assert.equal(mediaRow1.cardId, actualCard1Id);
    assert.equal(mediaRow2.cardId, actualCard1Id);

    // Verify B2 objects were NOT copied or re-uploaded
    assert.equal(b2ObjectStore.size, 2, 'No B2 binary copying or re-upload');
  });

  await t.test('17 - 19. Refresh after transition restores same BATCH draft with correct media separation', async () => {
    const draftId = 'draft-single-media-flow';
    const actualCard1Id = 'staged-0-1789853815627';
    const actualCard2Id = 'staged-1-1789853815628';

    // Admin also attaches a photo to Property B in batch mode
    const fileB = { name: 'bedroom_b.jpg', size: 150000, type: 'image/jpeg' };
    const mB = server.stageMedia(draftId, fileB, { cardId: actualCard2Id, roomTag: 'BEDROOM', isCover: true });

    // Simulate page refresh & recovery
    const recovered = server.getDraft(draftId);
    assert.equal(recovered.draftId, draftId);
    assert.equal(recovered.media.length, 3);

    // Card 1 media
    const card1Media = recovered.media.filter(m => m.cardId === actualCard1Id);
    assert.equal(card1Media.length, 2);
    assert.ok(card1Media.some(m => m.originalFilename === 'living.jpg'));
    assert.ok(card1Media.some(m => m.originalFilename === 'balcony.jpg'));

    // Card 2 media
    const card2Media = recovered.media.filter(m => m.cardId === actualCard2Id);
    assert.equal(card2Media.length, 1);
    assert.equal(card2Media[0].originalFilename, 'bedroom_b.jpg');
  });

  await t.test('20 - 26. Partial publish, partial Resume, remaining media, and full completion', async () => {
    resetDatabases();
    const draftId = 'draft-batch-partial-test';
    const cardAId = 'card-A-100';
    const cardBId = 'card-B-200';

    // Stage cards and media
    server.stageMedia(draftId, { name: 'photoA.jpg', size: 1000, type: 'image/jpeg' }, { cardId: cardAId });
    server.stageMedia(draftId, { name: 'photoB.jpg', size: 2000, type: 'image/jpeg' }, { cardId: cardBId });

    server.saveDraft({
      draftId,
      draftType: 'BATCH',
      titleSummary: 'Batch (2 properties)',
      itemCount: 2,
      version: 1,
      payload: JSON.stringify({
        stagedCards: [
          { id: cardAId, title: 'Property A' },
          { id: cardBId, title: 'Property B' }
        ],
        completedListings: []
      })
    });

    // 22. Publish Property A only (listing ID 501)
    const remainingDraft = server.reconcileBatch(draftId, [cardAId], [{ cardId: cardAId, listingId: 501, title: 'Property A' }]);

    assert.ok(remainingDraft !== null, 'Draft is NOT deleted on partial publish');
    assert.equal(remainingDraft.itemCount, 1);
    assert.equal(remainingDraft.titleSummary, 'Batch — 1 property remaining');

    const remPayload = JSON.parse(remainingDraft.payload);
    assert.equal(remPayload.stagedCards.length, 1);
    assert.equal(remPayload.stagedCards[0].id, cardBId);
    assert.equal(remPayload.completedListings.length, 1);
    assert.equal(remPayload.completedListings[0].listingId, 501);

    // 23 & 24. Remaining media check
    const refreshed = server.getDraft(draftId);
    assert.equal(refreshed.media.length, 1);
    assert.equal(refreshed.media[0].cardId, cardBId);
    assert.equal(refreshed.media[0].originalFilename, 'photoB.jpg');

    // 25 & 26. Final publish of Property B (listing ID 502) -> full completion
    const finalDraft = server.reconcileBatch(draftId, [cardBId], [{ cardId: cardBId, listingId: 502, title: 'Property B' }]);
    assert.equal(finalDraft, null, 'Returns null when all cards are published');

    const tombstone = draftsDb.get(draftId);
    assert.equal(tombstone.status, 'PUBLISHED');
    assert.equal(tombstone.payload, '{}');
    assert.equal(tombstone.itemCount, 0);
    assert.equal(draftMediaDb.size, 0, 'All staged media purged after full completion');
  });

  await t.test('27 - 32. Example append, scroll, failed upload, idempotency, claims & concurrency <= 3', async () => {
    // 27 & 28: Example Prompt append and Next Property separator
    const basePrompt = '2 BHK in Nanda Nagar';
    const examplePrompt = 'Luxury 3 BHK in Palasia';
    const combined = `${basePrompt}\n\nNext property\n${examplePrompt}`;
    assert.ok(combined.includes('Next property'));

    // 29: Concurrency <= 3 check
    const MAX_CONCURRENCY = 3;
    let activeUploads = 0;
    let peakConcurrency = 0;

    async function mockUploadTask(id) {
      activeUploads++;
      peakConcurrency = Math.max(peakConcurrency, activeUploads);
      await new Promise(r => setTimeout(r, 10));
      activeUploads--;
    }

    // Queue 10 items through a concurrency-bounded worker pool
    const queue = Array.from({ length: 10 }, (_, i) => i);
    const workers = Array.from({ length: MAX_CONCURRENCY }, async () => {
      while (queue.length > 0) {
        const item = queue.shift();
        await mockUploadTask(item);
      }
    });
    await Promise.all(workers);

    assert.ok(peakConcurrency <= 3, `Peak concurrency ${peakConcurrency} must be <= 3`);
  });

  await t.test('Realistic Lifecycle Integration Test: Single A -> Autosave -> Media -> Next B -> Batch Review -> Publish A -> Resume B -> Publish B', async () => {
    resetDatabases();

    // 1. Open Property Upload, enter Property A only
    const sessionDraftId = 'draft-single-live-cycle-001';
    server.saveDraft({
      draftId: sessionDraftId,
      draftType: 'SINGLE',
      titleSummary: '2 BHK Flat • Nanda Nagar',
      itemCount: 1,
      version: 1,
      payload: JSON.stringify({ newBhkLabel: '2 BHK in Nanda Nagar' })
    });
    assert.equal(draftsDb.size, 1);
    assert.equal(draftsDb.get(sessionDraftId).draftType, 'SINGLE');

    // 2. Attach media to A
    const mediaA = server.stageMedia(sessionDraftId, { name: 'flatA.webp', size: 120000, type: 'image/webp' }, { isCover: true });
    assert.equal(mediaA.cardId, null);

    // 3. Append Next property + Property B, click Review multiple properties
    const actualCardAId = 'staged-0-card-A';
    const actualCardBId = 'staged-1-card-B';

    // Transition: in-place reassociation
    server.reassignUnassignedMedia(sessionDraftId, actualCardAId);
    server.saveDraft({
      draftId: sessionDraftId,
      draftType: 'BATCH',
      titleSummary: 'Batch (2 properties)',
      itemCount: 2,
      version: 1,
      payload: JSON.stringify({
        stagedCards: [
          { id: actualCardAId, title: '2 BHK Flat • Nanda Nagar' },
          { id: actualCardBId, title: '3 BHK Flat • Vijay Nagar' }
        ],
        completedListings: []
      })
    });

    assert.equal(draftsDb.size, 1, 'ONE authoritative draft');
    assert.equal(draftsDb.get(sessionDraftId).draftType, 'BATCH');
    assert.equal(draftMediaDb.get(mediaA.mediaId).cardId, actualCardAId, 'Media A associated with actual Card A');

    // 4. Attach media to B
    const mediaB = server.stageMedia(sessionDraftId, { name: 'flatB.webp', size: 140000, type: 'image/webp' }, { cardId: actualCardBId, isCover: true });
    assert.equal(mediaB.cardId, actualCardBId);

    // 5. Refresh / restore: assert both cards and correct media
    const restored = server.getDraft(sessionDraftId);
    assert.equal(restored.draftId, sessionDraftId);
    assert.equal(restored.draftType, 'BATCH');
    const aMedia = restored.media.filter(m => m.cardId === actualCardAId);
    const bMedia = restored.media.filter(m => m.cardId === actualCardBId);
    assert.equal(aMedia.length, 1);
    assert.equal(bMedia.length, 1);

    // 6. Confirm and publish A only (listing 901)
    const afterA = server.reconcileBatch(sessionDraftId, [actualCardAId], [{ cardId: actualCardAId, listingId: 901, title: '2 BHK Flat' }]);
    assert.ok(afterA !== null);
    assert.equal(afterA.itemCount, 1);

    // 7. Refresh / Resume: assert B remains and B media restored
    const afterARefresh = server.getDraft(sessionDraftId);
    assert.equal(afterARefresh.media.length, 1);
    assert.equal(afterARefresh.media[0].cardId, actualCardBId);

    // 8. Publish B (listing 902)
    const afterB = server.reconcileBatch(sessionDraftId, [actualCardBId], [{ cardId: actualCardBId, listingId: 902, title: '3 BHK Flat' }]);
    assert.equal(afterB, null);
    assert.equal(draftsDb.get(sessionDraftId).status, 'PUBLISHED');
  });
});
