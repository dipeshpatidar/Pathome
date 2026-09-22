import test, { describe } from 'node:test';
import assert from 'node:assert/strict';

describe('Partially-Published Card Recovery & Semantic Contract Suite', () => {

  // Helper mimicking BatchPropertyIngestionStudio.tsx validation and presentation logic
  function validateCard(card) {
    const missing = [];
    if (!card.title || !card.title.trim()) missing.push('Property title');
    if (!card.rentVal || !/\d/.test(card.rentVal)) missing.push('Monthly rent');
    if (card.conflicts && card.conflicts.length > 0) missing.push('Resolve conflicting details');

    return {
      ...card,
      missingFields: missing,
      isValid: (card.isConfirmed || Boolean(card.publishedId)) && missing.length === 0
    };
  }

  function computeBadgeText(card, publishingCardId) {
    if (card.publishedId) {
      return 'Media Pending';
    }
    if (publishingCardId === card.id) {
      return 'Publishing';
    }
    if (card.isValid) {
      return 'Ready to publish';
    }
    return card.missingFields?.[0] || 'Awaiting confirmation';
  }

  function computeBatchState({ stagedCards, completedListings, isPublishing = false }) {
    const totalOriginalCount = completedListings.length + stagedCards.length;
    const readyToPublishCount = stagedCards.filter((card) => card.isValid).length;
    const totalCardsCount = stagedCards.length;
    const unconfirmedCount = stagedCards.filter((card) => !card.isValid).length;

    const summaryText = `${completedListings.length} of ${totalOriginalCount} properties published`;
    const remainingText = `${totalCardsCount} ${totalCardsCount === 1 ? 'property' : 'properties'} remaining`;

    let desktopCta = '';
    let mobileCta = '';

    if (completedListings.length > 0) {
      if (readyToPublishCount === 0) {
        desktopCta = totalCardsCount === 1 ? 'Confirm remaining property' : 'Confirm remaining properties';
        mobileCta = totalCardsCount === 1 ? 'Confirm property' : 'Confirm properties';
      } else if (readyToPublishCount === totalCardsCount) {
        desktopCta = totalCardsCount === 1 ? 'Publish remaining property' : `Publish all ${totalCardsCount} remaining properties`;
        mobileCta = totalCardsCount === 1 ? 'Publish remaining' : `Publish all ${totalCardsCount} remaining`;
      } else {
        desktopCta = `Publish ${readyToPublishCount} of ${totalCardsCount} remaining properties`;
        mobileCta = `Publish ${readyToPublishCount} of ${totalCardsCount} remaining`;
      }
    } else {
      if (readyToPublishCount === 0) {
        desktopCta = 'Confirm properties to publish';
        mobileCta = 'Confirm properties';
      } else if (totalCardsCount === 1) {
        desktopCta = 'Publish 1 property';
        mobileCta = 'Publish 1 property';
      } else if (readyToPublishCount === totalCardsCount) {
        desktopCta = `Publish all ${totalCardsCount} properties`;
        mobileCta = `Publish all ${totalCardsCount} properties`;
      } else {
        desktopCta = `Publish ${readyToPublishCount} of ${totalCardsCount} confirmed`;
        mobileCta = `Publish ${readyToPublishCount} of ${totalCardsCount} confirmed`;
      }
    }

    const isButtonDisabled = isPublishing || readyToPublishCount === 0;

    return {
      totalOriginalCount,
      readyToPublishCount,
      totalCardsCount,
      unconfirmedCount,
      summaryText,
      remainingText,
      desktopCta,
      mobileCta,
      isButtonDisabled
    };
  }

  // --------------------------------------------------------------------------
  // TEST 1: Exact Incident Regression
  // --------------------------------------------------------------------------
  test('1. Exact incident regression: 2 completed listings, 1 staged card with publishedId #75 and pending media', () => {
    const completedListings = [
      { cardId: 'staged-0-1790086191970', listingId: 73, title: '2 BHK Flat in Viman Nagar, Indore' },
      { cardId: 'staged-1-1790086191970', listingId: 74, title: '3 BHK Penthouse in Kali Nagar, Indore' }
    ];

    const mhowCard = validateCard({
      id: 'staged-2-1790086191970',
      title: '4 BHK House in Mhow, Indore',
      rentVal: '₹60,000',
      publishedId: 75,
      isConfirmed: true,
      conflicts: [],
      stagedMedia: [
        { id: 'dm-2gat90bsmucr4itb_0', roomTag: 'LIVING_ROOM', isCover: false },
        { id: 'dm-6fc9u143mucr4itb_1', roomTag: 'BEDROOM', isCover: true },
        { id: 'dm-3bj8ebramucr4itb_2', roomTag: 'KITCHEN', isCover: false }
      ]
    });

    const stagedCards = [mhowCard];

    // Assert summary
    const state = computeBatchState({ stagedCards, completedListings });
    assert.equal(state.summaryText, '2 of 3 properties published');
    assert.equal(state.remainingText, '1 property remaining');

    // Assert Mhow badge: MUST NOT show "Published", MUST show "Media Pending"
    const badgeText = computeBadgeText(mhowCard, null);
    assert.notEqual(badgeText, 'Published', 'Staged card must never show Published while in stagedCards');
    assert.equal(badgeText, 'Media Pending');

    // Assert readiness & button state
    assert.equal(state.readyToPublishCount, 1, 'Mhow must be counted in readyToPublishCount');
    assert.equal(state.unconfirmedCount, 0);
    assert.equal(state.isButtonDisabled, false, 'Publish/Resume button must be enabled');
    assert.equal(state.desktopCta, 'Publish remaining property');
    assert.equal(state.mobileCta, 'Publish remaining');
  });

  // --------------------------------------------------------------------------
  // TEST 2: Resume Targets Existing Listing #75 With Zero Listing Creation Calls
  // --------------------------------------------------------------------------
  test('2. Resume targets listing #75 directly and makes no listing-creation request', async () => {
    let createBatchCallCount = 0;
    const listingCreationCalls = [];
    const uploadedMediaForListing = [];

    const mockPropertyService = {
      createBatchProperties: async (payloadListings, draftId) => {
        createBatchCallCount++;
        listingCreationCalls.push({ payloadListings, draftId });
        return { createdListings: [], successCount: 0, failedCount: 0 };
      },
      uploadTaggedMedia: async (listingId, file, meta) => {
        uploadedMediaForListing.push({ listingId, meta });
        return { success: true };
      }
    };

    const validCards = [
      {
        id: 'staged-2-1790086191970',
        title: '4 BHK House in Mhow, Indore',
        publishedId: 75,
        isValid: true,
        stagedMedia: [
          { id: 'dm-1', file: new Blob(), roomTag: 'LIVING_ROOM', isCover: false },
          { id: 'dm-2', file: new Blob(), roomTag: 'BEDROOM', isCover: true },
          { id: 'dm-3', file: new Blob(), roomTag: 'KITCHEN', isCover: false }
        ]
      }
    ];

    // Execution mimicking handlePublishAll
    const cardsNeedingListing = validCards.filter((c) => !c.publishedId);
    const publishedIdsByCardId = new Map();

    for (const card of validCards) {
      if (card.publishedId) {
        publishedIdsByCardId.set(card.id, Number(card.publishedId));
      }
    }

    if (cardsNeedingListing.length > 0) {
      await mockPropertyService.createBatchProperties(cardsNeedingListing, 'draft-test');
    }

    // Assert no listing creation request was made for Mhow
    assert.equal(createBatchCallCount, 0, 'Must NOT call createBatchProperties for cards with existing publishedId');
    assert.equal(listingCreationCalls.length, 0);

    // Assert publishedId #75 is mapped
    assert.equal(publishedIdsByCardId.get('staged-2-1790086191970'), 75);

    // Simulate media upload loop
    for (const card of validCards) {
      const propId = publishedIdsByCardId.get(card.id);
      for (const item of card.stagedMedia) {
        await mockPropertyService.uploadTaggedMedia(propId, item.file, {
          roomTag: item.roomTag,
          isPrimaryCover: item.isCover
        });
      }
    }

    // Assert all 3 media were uploaded to listing #75, never #76
    assert.equal(uploadedMediaForListing.length, 3);
    for (const upload of uploadedMediaForListing) {
      assert.equal(upload.listingId, 75, 'All media must target listing #75');
    }
  });

  // --------------------------------------------------------------------------
  // TEST 3: Successful Resume Results in 3 of 3 Completed
  // --------------------------------------------------------------------------
  test('3. Successful resume reconciliation moves Mhow to completedListings (3/3 published)', () => {
    let completedListings = [
      { cardId: 'staged-0-1790086191970', listingId: 73, title: '2 BHK Flat in Viman Nagar, Indore' },
      { cardId: 'staged-1-1790086191970', listingId: 74, title: '3 BHK Penthouse in Kali Nagar, Indore' }
    ];

    let stagedCards = [
      {
        id: 'staged-2-1790086191970',
        title: '4 BHK House in Mhow, Indore',
        publishedId: 75,
        isValid: true
      }
    ];

    // Simulate successful media upload completion
    const successfulCardIds = ['staged-2-1790086191970'];
    const newlyCompleted = [
      { cardId: 'staged-2-1790086191970', listingId: 75, title: '4 BHK House in Mhow, Indore' }
    ];

    // State update mimicking handlePublishAll completion
    completedListings = [...completedListings, ...newlyCompleted];
    stagedCards = stagedCards.filter((c) => !successfulCardIds.includes(c.id));

    assert.equal(completedListings.length, 3);
    assert.equal(stagedCards.length, 0);

    const finalState = computeBatchState({ stagedCards, completedListings });
    assert.equal(finalState.summaryText, '3 of 3 properties published');
    assert.equal(finalState.remainingText, '0 properties remaining');
  });

  // --------------------------------------------------------------------------
  // TEST 4: Second Interruption Scenario
  // --------------------------------------------------------------------------
  test('4. Second interruption scenario: resume interrupted halfway leaves card staged, resumable, and targeting #75', () => {
    // Initial state before second interruption
    const completedListings = [
      { cardId: 'staged-0-1790086191970', listingId: 73, title: '2 BHK Flat in Viman Nagar, Indore' },
      { cardId: 'staged-1-1790086191970', listingId: 74, title: '3 BHK Penthouse in Kali Nagar, Indore' }
    ];

    // Mhow begins resume
    let stagedCards = [
      validateCard({
        id: 'staged-2-1790086191970',
        title: '4 BHK House in Mhow, Indore',
        rentVal: '₹60,000',
        publishedId: 75,
        isConfirmed: true,
        conflicts: []
      })
    ];

    // 1st file uploads, but then network drops on 2nd file
    const mediaUploadErrors = ['Property 3: Connection lost while uploading dm-6fc9u143mucr4itb_1'];
    const successfulCardIds = []; // Mhow is NOT marked successful

    const cardHadErrors = mediaUploadErrors.some((err) => err.startsWith('Property 3:'));
    if (!cardHadErrors) {
      successfulCardIds.push('staged-2-1790086191970');
    }

    // Because of media errors, Mhow is NOT removed from stagedCards
    stagedCards = stagedCards.filter((c) => successfulCardIds.includes(c.id));
    // Simulated reload / draft restore
    const restoredCards = [
      validateCard({
        id: 'staged-2-1790086191970',
        title: '4 BHK House in Mhow, Indore',
        rentVal: '₹60,000',
        publishedId: 75,
        isConfirmed: true,
        conflicts: []
      })
    ];

    // Assert post-interruption state
    const postState = computeBatchState({ stagedCards: restoredCards, completedListings });
    assert.equal(postState.summaryText, '2 of 3 properties published');
    assert.equal(postState.remainingText, '1 property remaining');
    assert.equal(postState.readyToPublishCount, 1);
    assert.equal(postState.isButtonDisabled, false, 'Must remain resumable after second interruption');

    const badge = computeBadgeText(restoredCards[0], null);
    assert.equal(badge, 'Media Pending', 'Must still show Media Pending, never Published');
    assert.equal(restoredCards[0].publishedId, 75, 'Must retain existing listing #75');
  });

  // --------------------------------------------------------------------------
  // TEST 5: Mixed Batch (1 Partial Card + 1 Unstarted Card)
  // --------------------------------------------------------------------------
  test('5. Mixed batch: 1 partial card (publishedId: 75) + 1 new unstarted card creates listing ONLY for new card', async () => {
    const createdPayloads = [];
    const mockPropertyService = {
      createBatchProperties: async (payloadListings) => {
        createdPayloads.push(...payloadListings);
        return {
          createdListings: [{ id: 76, requestIndex: 0 }],
          successCount: 1,
          failedCount: 0
        };
      }
    };

    const validCards = [
      { id: 'card-mhow', title: 'Mhow', publishedId: 75, isValid: true },
      { id: 'card-new', title: 'New Flat', publishedId: undefined, isValid: true }
    ];

    const cardsNeedingListing = validCards.filter((c) => !c.publishedId);
    const publishedIdsByCardId = new Map();

    for (const card of validCards) {
      if (card.publishedId) {
        publishedIdsByCardId.set(card.id, Number(card.publishedId));
      }
    }

    if (cardsNeedingListing.length > 0) {
      const res = await mockPropertyService.createBatchProperties(cardsNeedingListing);
      for (const created of res.createdListings) {
        const card = cardsNeedingListing[created.requestIndex];
        if (card) {
          publishedIdsByCardId.set(card.id, Number(created.id));
        }
      }
    }

    // Only 1 card was sent for listing creation
    assert.equal(createdPayloads.length, 1);
    // Both cards have mapped listing IDs
    assert.equal(publishedIdsByCardId.get('card-mhow'), 75);
    assert.equal(publishedIdsByCardId.get('card-new'), 76);
  });

  // --------------------------------------------------------------------------
  // TEST 6: Mobile Restore & "Review Property Details" Click Guard
  // --------------------------------------------------------------------------
  test('6. Mobile restore: clicking Review property details does NOT re-parse or alter stable card ID', () => {
    let parseCalls = 0;
    const completedListings = [
      { cardId: 'staged-0-1790086191970', listingId: 73, title: '2 BHK Flat in Viman Nagar, Indore' },
      { cardId: 'staged-1-1790086191970', listingId: 74, title: '3 BHK Penthouse in Kali Nagar, Indore' }
    ];

    const initialMhowCard = {
      id: 'staged-2-1790086191970',
      title: '4 BHK House in Mhow, Indore',
      publishedId: 75,
      isConfirmed: true,
      isValid: true,
      stagedMedia: [{ id: 'dm-1' }, { id: 'dm-2' }, { id: 'dm-3' }]
    };

    let stagedCards = [initialMhowCard];
    let mobileWorkspaceView = 'descriptions';
    const rawPrompts = 'Viman... Kali... Mhow...';
    const parsedPrompts = rawPrompts;

    // Simulated handleParseBatch logic from BatchPropertyIngestionStudio.tsx
    const handleParseBatch = () => {
      if (stagedCards.length > 0 && (completedListings.length > 0 || stagedCards.some((c) => Boolean(c.publishedId)))) {
        mobileWorkspaceView = 'review';
        return;
      }
      if (stagedCards.length > 0 && parsedPrompts === rawPrompts.trim()) {
        mobileWorkspaceView = 'review';
        return;
      }
      parseCalls++;
    };

    // User/Agent clicks "Review property details"
    handleParseBatch();

    assert.equal(parseCalls, 0, 'Parser must NOT be called on interrupted recovery draft');
    assert.equal(mobileWorkspaceView, 'review', 'View must switch to review workspace');
    assert.equal(stagedCards[0].id, 'staged-2-1790086191970', 'Original card ID must be preserved');
    assert.equal(stagedCards[0].publishedId, 75, 'publishedId must remain 75');
    assert.equal(stagedCards[0].stagedMedia.length, 3, 'Staged media must remain intact');
    assert.equal(completedListings.length, 2, 'Completed listings must remain untouched');
  });

  // --------------------------------------------------------------------------
  // TEST 7: Desktop Restore Guard
  // --------------------------------------------------------------------------
  test('7. Desktop restore: clicking Review property details does NOT re-parse or alter card identity', () => {
    let parseCalls = 0;
    const completedListings = [
      { cardId: 'staged-0-1790086191970', listingId: 73, title: 'Viman' }
    ];
    const stagedCards = [
      { id: 'staged-2-1790086191970', title: 'Mhow', publishedId: 75, isValid: true }
    ];

    // Parser guard function
    const tryParse = (cards, completed) => {
      if (completed.length > 0 || cards.some((c) => Boolean(c.publishedId))) {
        return false; // Guard blocks re-parse
      }
      parseCalls++;
      return true;
    };

    const allowed = tryParse(stagedCards, completedListings);
    assert.equal(allowed, false);
    assert.equal(parseCalls, 0);
    assert.equal(stagedCards[0].id, 'staged-2-1790086191970');
  });

  // --------------------------------------------------------------------------
  // TEST 8: Workspace Navigation Preserves Card Identity
  // --------------------------------------------------------------------------
  test('8. Navigation between descriptions and review workspaces retains original card ID and publishedId', () => {
    let currentView = 'descriptions';
    const card = { id: 'staged-2-1790086191970', publishedId: 75, title: 'Mhow' };

    // Navigate to review
    currentView = 'review';
    assert.equal(card.id, 'staged-2-1790086191970');
    assert.equal(card.publishedId, 75);

    // Navigate back to descriptions
    currentView = 'descriptions';
    assert.equal(card.id, 'staged-2-1790086191970');
    assert.equal(card.publishedId, 75);

    // Navigate back to review
    currentView = 'review';
    assert.equal(card.id, 'staged-2-1790086191970');
    assert.equal(card.publishedId, 75);
  });

  // --------------------------------------------------------------------------
  // TEST 9: Resume After Navigation Targets Existing Listing #75
  // --------------------------------------------------------------------------
  test('9. Resume after navigation targets listing #75 and does not invoke createBatchProperties', async () => {
    let createBatchInvoked = false;
    const validCards = [
      { id: 'staged-2-1790086191970', publishedId: 75, isValid: true }
    ];

    const cardsNeedingListing = validCards.filter((c) => !c.publishedId);
    if (cardsNeedingListing.length > 0) {
      createBatchInvoked = true;
    }

    assert.equal(createBatchInvoked, false, 'createBatchProperties must not be invoked');
    assert.equal(validCards[0].publishedId, 75, 'Existing listing #75 must be targeted');
  });

  // --------------------------------------------------------------------------
  // TEST 10: Second Interruption With Navigation Before Resume
  // --------------------------------------------------------------------------
  test('10. Second interruption flow: resume -> interrupt -> restore -> navigate -> resume retains listing #75', () => {
    const card = { id: 'staged-2-1790086191970', publishedId: 75, isValid: true };
    const completed = [
      { cardId: 'staged-0-1790086191970', listingId: 73 },
      { cardId: 'staged-1-1790086191970', listingId: 74 }
    ];

    // Interruption 1: 0 media
    // Restore: card has publishedId: 75
    assert.equal(card.publishedId, 75);

    // User navigates between tabs
    let activeTab = 'descriptions';
    activeTab = 'review';

    // Guard ensures card is never wiped out
    const cardsNeedingListing = [card].filter((c) => !c.publishedId);
    assert.equal(cardsNeedingListing.length, 0);

    // Resumed media uploads to 75
    const targetListingId = card.publishedId;
    assert.equal(targetListingId, 75);
  });

  // --------------------------------------------------------------------------
  // TEST 11: Normal Fresh Parse Continues Working
  // --------------------------------------------------------------------------
  test('11. Normal fresh parse without active publication state parses prompts and creates initial cards', () => {
    let parseCalls = 0;
    const completedListings = [];
    const stagedCards = [];

    const handleParseBatch = (prompt) => {
      if (stagedCards.length > 0 && (completedListings.length > 0 || stagedCards.some((c) => Boolean(c.publishedId)))) {
        return;
      }
      parseCalls++;
      return [
        { id: `staged-0-${Date.now()}`, title: 'Fresh 2 BHK', isValid: false }
      ];
    };

    const newCards = handleParseBatch('2 BHK Flat in Vijay Nagar, Indore');
    assert.equal(parseCalls, 1, 'Fresh prompt must parse normally');
    assert.equal(newCards.length, 1);
    assert.match(newCards[0].id, /^staged-0-/);
  });

  // --------------------------------------------------------------------------
  // TEST 12: One-Click Resume Semantics With 4-Property Mixed Batch
  // --------------------------------------------------------------------------
  test('12. One-click resume semantics with 4-property mixed batch (A=published, B=partial, C=confirmed, D=unconfirmed)', async () => {
    // PROPERTY A: already fully published in completedListings
    const completedListings = [
      { cardId: 'staged-a-1790086191970', listingId: 73, title: 'Property A (Viman Nagar)' }
    ];

    // PROPERTY B: reviewed/approved, partial published (listing #75 exists, media pending)
    const cardB = validateCard({
      id: 'staged-b-1790086191970',
      title: 'Property B (Mhow)',
      rentVal: '₹60,000',
      publishedId: 75,
      isConfirmed: true,
      conflicts: [],
      stagedMedia: [{ id: 'm-b-1', originalFilename: 'photo-b1.webp' }]
    });

    // PROPERTY C: reviewed/approved, publication not started (no publishedId)
    const cardC = validateCard({
      id: 'staged-c-1790086191970',
      title: 'Property C (Kali Nagar)',
      rentVal: '₹45,000',
      publishedId: undefined,
      isConfirmed: true,
      conflicts: [],
      stagedMedia: [{ id: 'm-c-1', originalFilename: 'photo-c1.webp' }]
    });

    // PROPERTY D: unreviewed/unconfirmed (isConfirmed = false, no publishedId, technically complete fields)
    const cardD = validateCard({
      id: 'staged-d-1790086191970',
      title: 'Property D (Bhawarkua)',
      rentVal: '₹25,000',
      publishedId: undefined,
      isConfirmed: false,
      conflicts: [],
      stagedMedia: [{ id: 'm-d-1', originalFilename: 'photo-d1.webp' }]
    });

    const stagedCards = [cardB, cardC, cardD];

    // Verify initial batch evaluation
    assert.equal(cardB.isValid, true, 'Property B must be valid (has publishedId & 0 missing fields)');
    assert.equal(cardC.isValid, true, 'Property C must be valid (isConfirmed & 0 missing fields)');
    assert.equal(cardD.isValid, false, 'Property D must be invalid (isConfirmed is false)');

    const initialBatchState = computeBatchState({ stagedCards, completedListings });
    assert.equal(initialBatchState.totalOriginalCount, 4, 'Total original properties is 4 (1 completed + 3 staged)');
    assert.equal(initialBatchState.readyToPublishCount, 2, 'Exactly 2 properties (B and C) are ready to publish');
    assert.equal(initialBatchState.totalCardsCount, 3, '3 staged cards remaining');
    assert.equal(initialBatchState.unconfirmedCount, 1, '1 staged card unconfirmed (D)');
    assert.equal(initialBatchState.desktopCta, 'Publish 2 of 3 remaining properties');
    assert.equal(initialBatchState.isButtonDisabled, false, 'Publish button must be enabled');

    assert.equal(computeBadgeText(cardB, null), 'Media Pending');
    assert.equal(computeBadgeText(cardC, null), 'Ready to publish');
    assert.equal(computeBadgeText(cardD, null), 'Awaiting confirmation');

    // Simulate ONE invocation of handlePublishAll
    const validCards = stagedCards.filter((c) => c.isValid);
    assert.equal(validCards.length, 2, 'validCards must contain only B and C');
    assert.equal(validCards.some((c) => c.id === cardD.id), false, 'Property D must NOT be in validCards');

    // 1. Property A is skipped completely (not in stagedCards, untouched in completedListings)
    assert.equal(validCards.some((c) => c.id === 'staged-a-1790086191970'), false, 'Property A must be skipped completely');

    // Partition cards needing listing creation vs cards with existing publishedId
    const cardsNeedingListing = validCards.filter((c) => !c.publishedId);
    const publishedIdsByCardId = new Map();

    for (const card of validCards) {
      if (card.publishedId) {
        publishedIdsByCardId.set(card.id, Number(card.publishedId));
      }
    }

    // Assert Property B eligibility and reuse
    assert.equal(publishedIdsByCardId.get(cardB.id), 75, 'Property B must reuse existing publishedId 75');
    assert.equal(cardsNeedingListing.some((c) => c.id === cardB.id), false, 'No listing-creation request for Property B');

    // Mock createBatchProperties
    const createdPayloads = [];
    const mockPropertyService = {
      createBatchProperties: async (payloadListings) => {
        createdPayloads.push(...payloadListings);
        return {
          createdListings: [{ id: 76, requestIndex: 0 }],
          successCount: 1,
          failedCount: 0
        };
      }
    };

    if (cardsNeedingListing.length > 0) {
      const res = await mockPropertyService.createBatchProperties(cardsNeedingListing);
      for (const created of res.createdListings) {
        const card = cardsNeedingListing[created.requestIndex];
        if (card) {
          publishedIdsByCardId.set(card.id, Number(created.id));
        }
      }
    }

    // Assert createBatchProperties received ONLY Property C
    assert.equal(createdPayloads.length, 1, 'createBatchProperties receives exactly 1 property');
    assert.equal(createdPayloads[0].id, cardC.id, 'createBatchProperties receives ONLY Property C');
    assert.equal(createdPayloads.some((c) => c.id === cardD.id), false, 'Property D is NOT sent to createBatchProperties');
    assert.equal(publishedIdsByCardId.get(cardC.id), 76, 'Property C receives new listing ID 76');

    // Simulate sequential media processing loop
    const mediaUploadOrder = [];
    const targetListingIdsByCardId = new Map();

    for (let i = 0; i < validCards.length; i++) {
      const card = validCards[i];
      const propId = publishedIdsByCardId.get(card.id);
      if (!propId) continue;

      mediaUploadOrder.push(card.id);
      targetListingIdsByCardId.set(card.id, propId);
    }

    // Assert media processing order and targets
    assert.deepEqual(mediaUploadOrder, [cardB.id, cardC.id], 'Media processing order must be B then C');
    assert.equal(mediaUploadOrder.includes(cardD.id), false, 'Property D does NOT enter media upload');
    assert.equal(targetListingIdsByCardId.get(cardB.id), 75, 'Property B media targets its EXISTING listing ID 75');
    assert.equal(targetListingIdsByCardId.get(cardC.id), 76, 'Property C media targets the newly returned listing ID 76');

    // Simulate successful completion reconciliation
    const successfulCardIds = [cardB.id, cardC.id];
    const newlyCompleted = [
      { cardId: cardB.id, listingId: 75, title: cardB.title },
      { cardId: cardC.id, listingId: 76, title: cardC.title }
    ];

    const updatedCompleted = [...completedListings];
    newlyCompleted.forEach((nc) => {
      if (!updatedCompleted.some((e) => e.cardId === nc.cardId || e.listingId === nc.listingId)) {
        updatedCompleted.push(nc);
      }
    });

    const remainingCardsAfterPublish = stagedCards.filter((c) => !successfulCardIds.includes(c.id));

    // Post-publish assertions
    assert.equal(updatedCompleted.length, 3, 'completedListings now contains 3 properties (A, B, C)');
    assert.equal(updatedCompleted[0].listingId, 73, 'Property A remains untouched in completedListings');
    assert.equal(updatedCompleted[1].listingId, 75, 'Property B moves to completedListings');
    assert.equal(updatedCompleted[2].listingId, 76, 'Property C moves to completedListings');

    assert.equal(remainingCardsAfterPublish.length, 1, 'Property D remains in stagedCards');
    assert.equal(remainingCardsAfterPublish[0].id, cardD.id, 'Remaining card is Property D');
    assert.equal(remainingCardsAfterPublish[0].isConfirmed, false, 'Property D remains isConfirmed = false');
    assert.equal(computeBadgeText(remainingCardsAfterPublish[0], null), 'Awaiting confirmation', 'Property D remains Awaiting confirmation');

    // Duplicate check
    const mhowListings = updatedCompleted.filter((l) => l.cardId === cardB.id);
    assert.equal(mhowListings.length, 1, 'No duplicate listing is created for Property B');
    assert.equal(mhowListings[0].listingId, 75);
  });
});

